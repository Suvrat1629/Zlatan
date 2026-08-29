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

    private val thread = HandlerThread("idr-sensors").apply { start() }
    private val handler = Handler(thread.looper)

    private val lastGravity = FloatArray(3).also { it[2] = SensorManager.GRAVITY_EARTH }
    private val lastGyro = FloatArray(3)

    private var timestampBaseValidated = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {

            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> System.arraycopy(event.values, 0, lastGravity, 0, 3)
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

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        thread.quitSafely()
    }
}
