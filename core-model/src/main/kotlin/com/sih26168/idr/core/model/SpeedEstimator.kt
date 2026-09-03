package com.sih26168.idr.core.model

/**
 * A speed prediction, optionally with the model's own estimate of how much to trust it.
 *
 * [sigmaMps] is the 1-std uncertainty from a heteroscedastic variance head — the model saying how
 * confident it is about THIS window, not an average over the test set. Null when the loaded model
 * has no variance head, in which case the filter falls back to its configured constant.
 */
data class SpeedEstimate(val speedMps: Float, val sigmaMps: Float?)

interface SpeedEstimator {

    fun estimate(normalizedWindow: Array<FloatArray>): Float

    /**
     * Speed together with its per-window uncertainty, when the model provides one.
     *
     * This is the path that puts something learned into the fusion step. The problem statement asks
     * for AI-based fusion, and `ErrorStateEkf` is hand-tuned throughout — but its process noise is
     * exactly the term a variance head belongs in. A model that knows it is guessing (out of
     * distribution, unusual vibration, a window spanning a pothole) should widen the filter's
     * uncertainty for that tick rather than being trusted at a fixed constant.
     *
     * Default implementation reports no uncertainty, so an estimator without a variance head keeps
     * working unchanged.
     */
    fun estimateWithVariance(normalizedWindow: Array<FloatArray>): SpeedEstimate =
        SpeedEstimate(estimate(normalizedWindow), null)

    fun close() {}
}

class ConstantSpeedEstimator(private val constantMps: Float = 8.33f) : SpeedEstimator {
    override fun estimate(normalizedWindow: Array<FloatArray>): Float = constantMps
}
