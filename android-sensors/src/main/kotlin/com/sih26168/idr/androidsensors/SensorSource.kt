package com.sih26168.idr.androidsensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.sih26168.idr.core.types.PositioningEngine

class SensorSource(
    context: Context,
    private val engine: PositioningEngine,
) {
    /**
     * Magnetic declination at the vehicle's location, degrees east of true north. Set by whoever
     * knows where the vehicle is; 0 until then, which costs about a degree in India.
     *
     * Set once at service start from the last known location. Declination changes by roughly
     * 0.01 deg/km, so a whole drive moves it well inside the compass's own 10 deg noise floor --
     * refresh per fix only if a session ever crosses hundreds of kilometres.
     */
    @Volatile var declinationDeg: Float = 0f
    class NoGyroscopeException : IllegalStateException(
        "This device has no gyroscope. IDR cannot function without one " +
            "(docs/architecture-android.md §12 caveat 1) — refuse clearly, don't silently degrade."
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor = requireNotNull(
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    ) { "This device has no accelerometer." }
    private val gravity: Sensor = requireNotNull(
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    ) { "This device has no TYPE_GRAVITY sensor (needed for the feature math, see docs/model-app-integration-answers.md A4/A5)." }
    private val gyroscope: Sensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: throw NoGyroscopeException()
    // Optional: a phone without one still navigates, this is diagnostic only.
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val thread = HandlerThread("idr-sensors").apply { start() }
    private val handler = Handler(thread.looper)

    private val lastGravity = FloatArray(3).also { it[2] = SensorManager.GRAVITY_EARTH }
    private val lastGyro = FloatArray(3)

    // Compass scratch. Reused per event rather than allocated: this runs on the sensor thread at
    // the same rate as the IMU, and the engine tick budget has no room for per-sample garbage.
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastMagneticEmitNanos = 0L

    private var timestampBaseValidated = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {

            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> System.arraycopy(event.values, 0, lastGravity, 0, 3)
                Sensor.TYPE_MAGNETIC_FIELD -> emitMagneticHeading(event)
                Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, lastGyro, 0, 3)
                Sensor.TYPE_ACCELEROMETER -> {
                    if (!timestampBaseValidated) validateTimestampBase(event.timestamp)
                    engine.onImuSample(
                        tNanos = event.timestamp,
                        ax = event.values[0], ay = event.values[1], az = event.values[2],
                        grx = lastGravity[0], gry = lastGravity[1], grz = lastGravity[2],
                        gx = lastGyro[0], gy = lastGyro[1], gz = lastGyro[2],
                    )
                }
            }
        }

        // Accuracy is read off each event instead: onAccuracyChanged only fires on a change, and
        // some vendors' HALs never call it at all, which is indistinguishable from a genuinely
        // unreliable compass. event.accuracy is populated on every event regardless.
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Tilt-compensated azimuth from the magnetic field and gravity, thinned to [MAGNETIC_EMIT_HZ].
     * The full sensor rate carries nothing extra here — the question this answers is whether the
     * compass tracks the filter's heading over minutes, not milliseconds.
     */
    private fun emitMagneticHeading(event: SensorEvent) {
        if (event.timestamp - lastMagneticEmitNanos < MAGNETIC_EMIT_PERIOD_NANOS) return
        // getRotationMatrix fails when gravity and the magnetic vector are near-parallel, which a
        // phone passes through in normal handling. Skip the sample rather than publish a stale one.
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, lastGravity, event.values)) return
        lastMagneticEmitNanos = event.timestamp
        SensorManager.getOrientation(rotationMatrix, orientation)
        val headingDeg = (Math.toDegrees(orientation[0].toDouble()) + 360.0).mod(360.0)
        engine.onMagneticHeading(event.timestamp, headingDeg.toFloat(), event.accuracy, declinationDeg)
    }

    private fun validateTimestampBase(firstEventTimestampNanos: Long) {

        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val offsetMs = (nowElapsedRealtimeNanos - firstEventTimestampNanos) / 1_000_000
        if (kotlin.math.abs(offsetMs) > 5_000) {
            System.err.println(
                "[SensorSource] WARNING: sensor timestamp base looks off by ${offsetMs}ms " +
                    "vs elapsedRealtimeNanos() — this device's SensorEvent.timestamp may not " +
                    "be boot-relative. Do not ship without investigating on this specific device."
            )
        }
        timestampBaseValidated = true
    }

    fun start() {

        val samplingPeriodUs = SensorManager.SENSOR_DELAY_FASTEST
        val maxReportLatencyUs = 0
        sensorManager.registerListener(listener, accelerometer, samplingPeriodUs, maxReportLatencyUs, handler)
        sensorManager.registerListener(listener, gravity, samplingPeriodUs, maxReportLatencyUs, handler)
        sensorManager.registerListener(listener, gyroscope, samplingPeriodUs, maxReportLatencyUs, handler)
        magnetometer?.let {
            // SENSOR_DELAY_UI, not FASTEST: emitMagneticHeading thins to 2 Hz anyway, and the
            // magnetometer is the one sensor here that is not on the navigation path.
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, maxReportLatencyUs, handler)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        thread.quitSafely()
    }

    private companion object {
        const val MAGNETIC_EMIT_HZ = 2
        const val MAGNETIC_EMIT_PERIOD_NANOS = 1_000_000_000L / MAGNETIC_EMIT_HZ
    }
}
