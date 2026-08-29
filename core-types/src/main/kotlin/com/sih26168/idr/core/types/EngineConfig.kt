package com.sih26168.idr.core.types

data class EngineConfig(

    val modelRateHz: Double = 10.0,

    val windowSeconds: Double = 5.0,

    val antiAliasCutoffHz: Double = 4.0,

    val ringBufferSeconds: Double = 8.0,

    val coldStartSeconds: Double = 5.0,

    val speedMinMps: Float = 0f,
    val speedMaxMps: Float = 60f,

    // Zero-velocity (ZUPT) gate: below BOTH thresholds over a window, speed is clamped to 0.
    val zuptAccelThresholdMps2: Float = 0.5f,
    val zuptGyroThresholdRps: Float = 0.05f,

    // Exponential smoothing on model speed (0..1); lower = smoother, tames output spikes.
    val speedSmoothingAlpha: Float = 0.35f,

    val gnssLostNoFixTimeoutMs: Long = 3_000,
    val handoverSlewSeconds: Double = 1.5,

    val outputRateHz: Double = 10.0,

    val engineTickP95BudgetMs: Float = 50f,
) {
    val windowSamples: Int get() = (modelRateHz * windowSeconds).toInt()

    companion object {

        val DEFAULT = EngineConfig()
    }
}
