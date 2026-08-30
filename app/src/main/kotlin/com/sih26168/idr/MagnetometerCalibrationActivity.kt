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
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Compass "calibration" screen — really a confirmation screen for the vendor's own continuous
 * hard-iron calibration, not a correction we compute ourselves.
 *
 * Every Android device keeps calibrating its magnetometer in the background whenever the
 * sensor is active; `TYPE_MAGNETIC_FIELD` is that already-corrected reading, and
 * `onAccuracyChanged` reports the vendor's own confidence in it
 * (UNRELIABLE/LOW/MEDIUM/HIGH) — the same signal every nav app already relies on for the
 * "move your phone in a figure-8" prompt. This screen just surfaces that signal with a live
 * heading, instead of reinventing hard/soft-iron math and asking the user to clear
 * self-invented thresholds by hand (an earlier version of this screen did exactly that; it
 * was correct but impractical to actually finish — see git history).
 *
 * Deliberately self-contained: its own SensorEventListener, not routed through SensorSource/
 * PositioningEngine — the magnetometer isn't a model input, and this shouldn't add any risk
 * to the tuned DR/GNSS pipeline. Nothing here is persisted for a future fusion step to
 * consume, since there's no correction of ours to persist — a future heading-fusion step
 * would read TYPE_MAGNETIC_FIELD + its accuracy directly, the same way this screen does.
 */
class MagnetometerCalibrationActivity : AppCompatActivity() {

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private lateinit var store: MagnetometerCalibrationStore

    private val gravityValues = FloatArray(3)
    private var hasGravity = false
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    private var lastUiUpdateRealtimeMs = 0L
    private var currentAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    private lateinit var headingText: TextView
    private lateinit var headingCardinalText: TextView
    private lateinit var accuracyValueText: TextView
    private lateinit var accuracyCheck: ImageView
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
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    // Read accuracy off the event itself, not just onAccuracyChanged below —
                    // that callback only fires on a *change*, and on a HAL that never calls it
                    // (some vendors don't), currentAccuracy would stay stuck at its initial
                    // UNRELIABLE forever with no way to tell that apart from a genuinely bad
                    // reading. event.accuracy is populated on every single event regardless.
                    currentAccuracy = event.accuracy
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdateRealtimeMs >= UI_REFRESH_PERIOD_MS) {
                        lastUiUpdateRealtimeMs = now
                        updateHeading(event.values[0], event.values[1], event.values[2])
                        updateAccuracyUi()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                currentAccuracy = accuracy
                updateAccuracyUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_magnetometer_calibration)
        store = MagnetometerCalibrationStore(this)

        findViewById<View>(R.id.close_button).setOnClickListener { finish() }
        headingText = findViewById(R.id.heading_text)
        headingCardinalText = findViewById(R.id.heading_cardinal_text)
        accuracyValueText = findViewById(R.id.accuracy_value_text)
        accuracyCheck = findViewById(R.id.accuracy_check)
        statusText = findViewById(R.id.status_text)
        finishButton = findViewById(R.id.finish_button)
        lastCalibratedText = findViewById(R.id.last_calibrated_text)
        unsupportedText = findViewById(R.id.unsupported_text)
        figureEightView = findViewById(R.id.figure_eight_view)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (magnetometer == null || gravitySensor == null) {
            showUnsupported()
            return
        }

        showLastCalibrated()
        updateAccuracyUi()
        finishButton.setOnClickListener { confirmAndFinish() }
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
        val confirmedAt = store.lastConfirmedAtEpochMs()
        lastCalibratedText.text = if (confirmedAt != null) {
            getString(
                R.string.calibration_last_calibrated_format,
                DateUtils.getRelativeTimeSpanString(confirmedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        } else {
            getString(R.string.calibration_never)
        }
    }

    override fun onResume() {
        super.onResume()
        val mag = magnetometer ?: return
        val gravity = gravitySensor ?: return
        // maxReportLatencyUs=0: disable batching so readings stream rather than arrive in
        // rare bursts — see SensorSource.kt, which hit and fixed the same thing.
        val samplingPeriodUs = SensorManager.SENSOR_DELAY_GAME
        sensorManager.registerListener(sensorListener, mag, samplingPeriodUs, 0)
        sensorManager.registerListener(sensorListener, gravity, samplingPeriodUs, 0)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(sensorListener)
    }

    private fun updateHeading(magX: Float, magY: Float, magZ: Float) {
        if (!hasGravity) return
        val geomagnetic = floatArrayOf(magX, magY, magZ)
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

    private fun updateAccuracyUi() {
        val ready = currentAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        accuracyValueText.text = getString(
            when (currentAccuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> R.string.calibration_accuracy_high
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> R.string.calibration_accuracy_medium
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> R.string.calibration_accuracy_low
                else -> R.string.calibration_accuracy_unreliable
            }
        )
        accuracyValueText.setTextColor(
            ContextCompat.getColor(this, if (ready) R.color.idr_navic else R.color.idr_dead_reckoning)
        )
        accuracyCheck.visibility = if (ready) View.VISIBLE else View.INVISIBLE
        finishButton.isEnabled = ready
        statusText.text = getString(
            if (ready) R.string.calibration_status_ready else R.string.calibration_status_needs_motion
        )
        // Otherwise it keeps telling the user to rotate even after they're done.
        figureEightView.visibility = if (ready) View.INVISIBLE else View.VISIBLE
    }

    private fun confirmAndFinish() {
        store.recordConfirmed()
        android.widget.Toast.makeText(this, R.string.calibration_saved_toast, android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val UI_REFRESH_PERIOD_MS = 50L
        private const val STALE_HEADING_ALPHA = 0.35f
    }
}
