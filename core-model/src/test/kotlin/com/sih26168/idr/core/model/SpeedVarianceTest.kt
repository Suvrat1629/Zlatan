package com.sih26168.idr.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The variance path is the project's answer to the problem statement's "AI-based fusion" — the
 * filter's process noise comes from the model rather than a hand-tuned constant (TODO.md K7).
 *
 * The property that must hold is that it degrades silently: a model with no variance head has to
 * keep working exactly as before, because that is what ships today and what will ship if the v3
 * retrain does not get a head in time.
 */
class SpeedVarianceTest {

    private class PlainEstimator : SpeedEstimator {
        override fun estimate(normalizedWindow: Array<FloatArray>) = 12.5f
    }

    private class VarianceEstimator(private val sigma: Float) : SpeedEstimator {
        override fun estimate(normalizedWindow: Array<FloatArray>) = 12.5f
        override fun estimateWithVariance(normalizedWindow: Array<FloatArray>) =
            SpeedEstimate(estimate(normalizedWindow), sigma)
    }

    private val window = Array(50) { FloatArray(7) }

    @Test
    fun `a model without a variance head reports no uncertainty`() {
        val e = PlainEstimator().estimateWithVariance(window)
        assertEquals(12.5f, e.speedMps)
        assertNull(e.sigmaMps, "no head must mean null, not a fabricated default")
    }

    @Test
    fun `a model with a head reports both`() {
        val e = VarianceEstimator(0.8f).estimateWithVariance(window)
        assertEquals(12.5f, e.speedMps)
        assertEquals(0.8f, e.sigmaMps)
    }

    @Test
    fun `speed is identical on both paths`() {
        // The variance path must not perturb the number the filter navigates on. If these ever
        // diverge, the head has been wired into the speed output by mistake.
        val v = VarianceEstimator(2f)
        assertEquals(v.estimate(window), v.estimateWithVariance(window).speedMps)
    }

    @Test
    fun `log-variance decoding covers the range a head can emit`() {
        // Mirrors TfliteSpeedEstimator's bounds. exp(-6) -> 0.05 m/s, exp(6) -> ~20 m/s: below the
        // floor the model claims more certainty than the sensors support and the filter would stop
        // trusting its own propagation; above the ceiling an out-of-distribution window could
        // freeze it outright.
        fun decode(logVar: Float): Float =
            kotlin.math.sqrt(kotlin.math.exp(logVar.coerceIn(-6f, 6f)))
        assertTrue(decode(-20f) in 0.04f..0.06f, "floor: ${decode(-20f)}")
        assertTrue(decode(20f) in 19f..21f, "ceiling: ${decode(20f)}")
        assertTrue(decode(0f) in 0.99f..1.01f, "unit variance should give sigma 1")
    }
}
