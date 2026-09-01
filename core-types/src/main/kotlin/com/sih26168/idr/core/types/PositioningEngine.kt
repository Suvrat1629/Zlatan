package com.sih26168.idr.core.types

import kotlinx.coroutines.flow.StateFlow

interface PositioningEngine {

    fun start()

    fun stop()

    fun onImuSample(
        tNanos: Long,
        ax: Float, ay: Float, az: Float,
        grx: Float, gry: Float, grz: Float,
        gx: Float, gy: Float, gz: Float,
    )

    fun onGnssFix(
        tNanos: Long,
        lat: Double, lon: Double,
        speedMps: Float, bearingDeg: Float, horizAccM: Float,
        satsInFix: Int, irnssSatsInFix: Int,
        bearingValid: Boolean = false,
    )

    fun onGnssLost(tNanos: Long)

    /**
     * Tilt-compensated compass azimuth, degrees clockwise from MAGNETIC north, plus the vendor's
     * own accuracy rating for it (SensorManager.SENSOR_STATUS_*).
     *
     * The compass reads the phone's azimuth while the filter tracks the vehicle's heading; the two
     * differ by however the phone was seated in its cradle. [declinationDeg] converts magnetic to
     * true north — the frame the filter's heading and GNSS bearing already use. It is passed in
     * rather than assumed because it depends on where on Earth the vehicle is, and folding it into
     * the mount offset would leave that offset wrong as soon as the vehicle travelled.
     *
     * Always recorded; whether it is also fused is the filter's decision, gated on
     * EngineConfig.useMagHeading and on the accuracy rating.
     *
     * Default no-op: engines that do not record telemetry have nothing to do with it.
     */
    fun onMagneticHeading(
        tNanos: Long,
        magneticHeadingDeg: Float,
        accuracy: Int,
        declinationDeg: Float = 0f,
    ) {}

    val state: StateFlow<PositionState>

    companion object {

        const val WINDOW_SAMPLES = 50

        const val FEATURES = 7

        const val RAW_CHANNELS = 9

    }
}
