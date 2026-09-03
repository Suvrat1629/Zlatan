package com.sih26168.idr.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The loop that matters: a delta model reading high by a fixed amount should have that amount
 * recovered from GNSS alone, and a model reading true should be left alone.
 */
class DvBiasEstimatorTest {

    private fun newEstimator(initialBiasMps2: Float = 0f) = DvBiasEstimator(
        alpha = 0.05f, minFixDtSeconds = 0.2f, maxFixDtSeconds = 5.0f,
        initialBiasMps2 = initialBiasMps2,
    )

    /**
     * Drives a constant-acceleration profile through the estimator: the vehicle really
     * accelerates at [trueDvMps2], the model reports that plus [modelOffsetMps2], and a fix
     * arrives every second carrying the true speed.
     */
    private fun run(
        estimator: DvBiasEstimator,
        trueDvMps2: Float,
        modelOffsetMps2: Float,
        fixes: Int,
        ticksPerFix: Int = 10,
    ) {
        var speedMps = 0f
        var tNanos = 0L
        repeat(fixes) {
            repeat(ticksPerFix) { estimator.observePrediction(trueDvMps2 + modelOffsetMps2) }
            speedMps += trueDvMps2
            tNanos += 1_000_000_000L
            estimator.onTrustedFix(speedMps, tNanos)
        }
    }

    @Test
    fun recoversAConstantModelOffsetFromGnss() {
        val estimator = newEstimator()
        run(estimator, trueDvMps2 = 0.5f, modelOffsetMps2 = 1.2f, fixes = 200)
        assertTrue(
            abs(estimator.biasMps2 - 1.2f) < 0.05f,
            "expected the 1.2 m/s^2 model offset back, got ${estimator.biasMps2}",
        )
    }

    @Test
    fun leavesAnUnbiasedModelAlone() {
        val estimator = newEstimator()
        run(estimator, trueDvMps2 = 0.5f, modelOffsetMps2 = 0f, fixes = 200)
        assertTrue(
            abs(estimator.biasMps2) < 0.05f,
            "a model with no offset should not grow one, got ${estimator.biasMps2}",
        )
    }

    /** A seeded estimate is the point of persisting it: right from the first fix, not after ~20. */
    @Test
    fun aSeededEstimateStartsCorrectedAndStaysPut() {
        val estimator = newEstimator(initialBiasMps2 = 1.2f)
        assertTrue(estimator.biasMps2 == 1.2f, "seed must apply before any observation")
        run(estimator, trueDvMps2 = 0.5f, modelOffsetMps2 = 1.2f, fixes = 20)
        assertTrue(
            abs(estimator.biasMps2 - 1.2f) < 0.05f,
            "a correct seed should survive its own data, got ${estimator.biasMps2}",
        )
    }

    /**
     * Intervals outside the dt window are the ones where GNSS speed noise or a stale previous fix
     * dominates. They must not move the estimate.
     */
    @Test
    fun ignoresFixIntervalsOutsideTheConfiguredWindow() {
        val estimator = newEstimator()
        var tNanos = 0L
        // First fix only establishes the previous-fix anchor.
        estimator.onTrustedFix(speedMps = 0f, tNanos = tNanos)
        repeat(10) { estimator.observePrediction(2f) }
        tNanos += 30_000_000_000L // 30 s: far past maxFixDtSeconds
        estimator.onTrustedFix(speedMps = 0f, tNanos = tNanos)
        assertTrue(estimator.biasMps2 == 0f, "out-of-window interval moved the estimate")
    }
}
