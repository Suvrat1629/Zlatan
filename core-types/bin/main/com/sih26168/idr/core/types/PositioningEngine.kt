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

    val state: StateFlow<PositionState>

    companion object {

        const val WINDOW_SAMPLES = 50

        const val FEATURES = 7

        const val RAW_CHANNELS = 9

    }
}
