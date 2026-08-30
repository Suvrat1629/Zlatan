package com.sih26168.idr

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sih26168.idr.core.nav.MagnetometerCalibrator
import kotlin.math.sqrt

/**
 * User-facing magnetometer hard/soft-iron calibration (figure-8 motion). Deliberately
 * self-contained: its own SensorEventListener, not routed through SensorSource/
 * PositioningEngine — the magnetometer isn't a model input (docs/model-app-integration-
 * answers.md), and this shouldn't add any risk to the tuned DR/GNSS pipeline. The persisted
 * correction is what a future heading-fusion step (once FusionFilter is a real EKF, not
 * PassthroughFusionFilter) will consume — this screen doesn't wire it in yet.
 */
class MagnetometerCalibrationActivity : AppCompatActivity() {

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var gravitySensor: Sensor? = null

    private val calibrator = MagnetometerCalibrator()
    private lateinit var store: MagnetometerCalibrationStore

    private val gravityValues = FloatArray(3)
    private var hasGravity = false
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    private var lastUiUpdateRealtimeMs = 0L

    private lateinit var headingText: TextView
    private lateinit var headingCardinalText: TextView
    private lateinit var coverageProgress: LinearProgressIndicator
    private lateinit var coverageCheck: ImageView
    private lateinit var consistencyProgress: LinearProgressIndicator
    private lateinit var consistencyCheck: ImageView
    private lateinit var fieldValueText: TextView
    private lateinit var fieldCheck: ImageView
    private lateinit var statusText: TextView
    private lateinit var finishButton: MaterialButton
    private lateinit var lastCalibratedText: TextView
    private lateinit var unsupportedText: TextView
    private lateinit var figureEightView: View

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> {
                    System.arraycopy(event.values, 0, gravityValues, 0, 3)
                    hasGravity = true
                }
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD -> {
                    // Uncalibrated carries [raw_x,y,z, bias_x,y,z] — only the first 3 are the
                    // actual field reading; we deliberately never touch the vendor's own bias
                    // estimate (see class doc / MagnetometerCalibrator doc).
                    // Every sample still feeds the fit at full sensor rate — only the (much
                    // more expensive, and only human-visible) UI refresh below is throttled.
                    calibrator.addSample(event.values[0], event.values[1], event.values[2])

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdateRealtimeMs >= UI_REFRESH_PERIOD_MS) {
                        lastUiUpdateRealtimeMs = now
                        updateHeading(event.values[0], event.values[1], event.values[2])
                        updateQualityUi()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_magnetometer_calibration)
        store = MagnetometerCalibrationStore(this)

        findViewById<View>(R.id.close_button).setOnClickListener { finish() }
        headingText = findViewById(R.id.heading_text)
        headingCardinalText = findViewById(R.id.heading_cardinal_text)
        coverageProgress = findViewById(R.id.coverage_progress)
        coverageCheck = findViewById(R.id.coverage_check)
        consistencyProgress = findViewById(R.id.consistency_progress)
        consistencyCheck = findViewById(R.id.consistency_check)
        fieldValueText = findViewById(R.id.field_value_text)
        fieldCheck = findViewById(R.id.field_check)
        statusText = findViewById(R.id.status_text)
        finishButton = findViewById(R.id.finish_button)
        lastCalibratedText = findViewById(R.id.last_calibrated_text)
        unsupportedText = findViewById(R.id.unsupported_text)
        figureEightView = findViewById(R.id.figure_eight_view)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        if (magnetometer == null) {
            // Some devices only expose the calibrated variant. Its vendor-side hard-iron
            // estimate drifts under us (see MagnetometerCalibrator's doc), so this is a worse
            // fallback, but still better than refusing the feature outright.
            System.err.println(
                "[MagnetometerCalibrationActivity] no TYPE_MAGNETIC_FIELD_UNCALIBRATED on this " +
                    "device — falling back to TYPE_MAGNETIC_FIELD, which fights the vendor's own " +
                    "drifting bias estimate."
            )
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }

        if (magnetometer == null || gravitySensor == null) {
            showUnsupported()
            return
        }

        showLastCalibrated()
        finishButton.setOnClickListener { saveAndFinish() }
    }

    private fun showUnsupported() {
        unsupportedText.visibility = View.VISIBLE
        findViewById<View>(R.id.instruction_text).visibility = View.GONE
        figureEightView.visibility = View.GONE
        headingText.visibility = View.GONE
        headingCardinalText.visibility = View.GONE
        finishButton.visibility = View.GONE
        statusText.visibility = View.GONE
    }

    private fun showLastCalibrated() {
        val existing = store.load()
        lastCalibratedText.text = if (existing != null) {
            getString(
                R.string.calibration_last_calibrated_format,
                DateUtils.getRelativeTimeSpanString(
                    existing.calibratedAtEpochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                ),
            )
        } else {
            getString(R.string.calibration_never)
        }
    }

    override fun onResume() {
        super.onResume()
        val mag = magnetometer ?: return
        val gravity = gravitySensor ?: return
        // maxReportLatencyUs=0 explicitly: the 3-arg overload leaves batching up to the
        // vendor's sensor hub, and on this Vivo hardware that meant samples queued in a FIFO
        // and arrived in one bursty flush instead of streaming — see SensorSource.kt, which
        // hit the same thing and already disables it this way for the model's IMU feed.
        val samplingPeriodUs = SensorManager.SENSOR_DELAY_GAME
        sensorManager.registerListener(sensorListener, mag, samplingPeriodUs, 0)
        sensorManager.registerListener(sensorListener, gravity, samplingPeriodUs, 0)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(sensorListener)
    }

    private fun updateHeading(rawX: Float, rawY: Float, rawZ: Float) {
        if (!hasGravity) return
        // calibrate() normalizes each axis to their mean RMS (magnitude ~ field/√3, since a
        // sphere's variance splits three ways across axes — see MagnetometerCalibrator's doc
        // on fieldMagnitudeUt), not the actual field strength. getRotationMatrix wants a real
        // geomagnetic-vector-shaped input, so scale back up before handing it over.
        val calibrated = calibrator.calibrate(rawX, rawY, rawZ)
        val physicalScale = sqrt(3.0).toFloat()
        val geomagnetic = floatArrayOf(
            calibrated[0] * physicalScale, calibrated[1] * physicalScale, calibrated[2] * physicalScale,
        )
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, geomagnetic)
        if (!success) {
            // Happens when gravity and the magnetic vector are near-parallel — the phone
            // passes through that orientation constantly during a figure-8. Dim instead of
            // silently freezing on the last good value, so it's clear this reading is stale.
            headingText.alpha = STALE_HEADING_ALPHA
            headingCardinalText.alpha = STALE_HEADING_ALPHA
            return
        }
        SensorManager.getOrientation(rotationMatrix, orientationValues)
        val headingDeg = (Math.toDegrees(orientationValues[0].toDouble()) + 360.0).mod(360.0).toFloat()
        headingText.alpha = 1f
        headingCardinalText.alpha = 1f
        headingText.text = getString(R.string.calibration_heading_format, headingDeg)
        headingCardinalText.text = cardinalLabel(headingDeg)
    }

    private fun cardinalLabel(headingDeg: Float): String {
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((headingDeg / 45f) + 0.5f).toInt() % 8
        return labels[index]
    }

    private fun updateQualityUi() {
        coverageProgress.setProgressCompat((calibrator.coverageFraction * 100).toInt(), true)
        coverageCheck.visibility = if (calibrator.isCoverageGoodEnough) View.VISIBLE else View.INVISIBLE

        consistencyProgress.setProgressCompat((calibrator.consistencyProgressFraction * 100).toInt(), true)
        consistencyCheck.visibility = if (calibrator.isConsistencyGoodEnough) View.VISIBLE else View.INVISIBLE

        val field = calibrator.fieldMagnitudeUt
        fieldValueText.text = if (field.isNaN()) "—" else getString(R.string.calibration_field_value_format, field)
        fieldCheck.visibility = if (calibrator.isFieldStrengthPlausible) View.VISIBLE else View.INVISIBLE

        val ready = calibrator.isGoodEnough
        finishButton.isEnabled = ready
        statusText.text = getString(
            when {
                ready -> R.string.calibration_status_ready
                !calibrator.isCoverageGoodEnough -> R.string.calibration_status_low_coverage
                !calibrator.isFieldStrengthPlausible -> R.string.calibration_status_weak_field
                else -> R.string.calibration_status_noisy
            }
        )
    }

    private fun saveAndFinish() {
        store.save(calibrator.hardIronOffset, calibrator.softIronScale, calibrator.fieldMagnitudeUt)
        android.widget.Toast.makeText(this, R.string.calibration_saved_toast, android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val UI_REFRESH_PERIOD_MS = 50L
        private const val STALE_HEADING_ALPHA = 0.35f
    }
}
