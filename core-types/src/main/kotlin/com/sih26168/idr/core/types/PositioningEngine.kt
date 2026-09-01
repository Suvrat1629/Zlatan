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
     * Recorded, never fused. The compass reads the phone's azimuth while the filter tracks the
     * vehicle's heading, and the constant yaw offset between the two depends on how the phone is
     * mounted — so this cannot enter the filter until that offset is shown to be stable, which is
     * what logging it is for. Declination is deliberately not applied: it is a slowly varying
     * constant that would be absorbed into the same mount offset, and leaving it out keeps this
     * free of any dependency on position.
     *
     * Default no-op: engines that do not record telemetry have nothing to do with it.
     */
    fun onMagneticHeading(tNanos: Long, magneticHeadingDeg: Float, accuracy: Int) {}

    val state: StateFlow<PositionState>

    companion object {

        const val WINDOW_SAMPLES = 50

        const val FEATURES = 7

        const val RAW_CHANNELS = 9

    }
}
