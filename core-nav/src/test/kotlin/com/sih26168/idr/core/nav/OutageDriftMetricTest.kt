package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.OutageRecord
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the drift metric, using the numbers from the 2026-08-31 bike session.
 *
 * The metric used to divide the error by the engine's own dead-reckoned distance. That denominator
 * is itself a product of the error being measured, so the further the engine over-travelled the
 * smaller the reported drift became: **the headline number improved as the system got worse.**
 * On the bike session it reported a median 42% where the true figure against GNSS distance was
 * 154% — wrong by the over-travel factor, in the flattering direction.
 */
class OutageDriftMetricTest {

    /** Outage #1 from session 20260831_044634: 38 m truly travelled, engine believed 262 m. */
    private fun bikeOutage1(withTruth: Boolean) = OutageRecord(
        startNanos = 0, endNanos = 23_200_000_000,
        durationSeconds = 23.2,
        deadReckonedDistanceM = 262.0,
        errorM = 138.0,
        trueDistanceM = if (withTruth) 38.0 else Double.NaN,
    )

    @Test
    fun `drift is measured against ground truth when it is available`() {
        val o = bikeOutage1(withTruth = true)
        assertTrue(abs(o.driftPercent - 363.0) < 2.0, "expected ~363%, got ${o.driftPercent}")
        assertTrue(o.driftIsAgainstTruth)
    }

    @Test
    fun `the old denominator understated this outage by the over-travel factor`() {
        val truth = bikeOutage1(withTruth = true)
        val noTruth = bikeOutage1(withTruth = false)
        // What the old formula reported, still the fallback when truth is missing.
        assertTrue(abs(noTruth.driftPercent - 52.7) < 2.0, "got ${noTruth.driftPercent}")
        // The gap is exactly the over-travel ratio, which is the point: the error inflates its own
        // denominator, so the metric cannot see the failure it is supposed to measure.
        val ratio = truth.driftPercent / noTruth.driftPercent
        assertTrue(
            abs(ratio - truth.overTravelRatio) < 0.05,
            "the understatement should equal the over-travel ratio: $ratio vs ${truth.overTravelRatio}",
        )
        assertTrue(truth.overTravelRatio > 6.0, "over-travel was ${truth.overTravelRatio}")
    }

    @Test
    fun `a fallback to the engine denominator is flagged, never silently optimistic`() {
        assertFalse(bikeOutage1(withTruth = false).driftIsAgainstTruth)
    }

    @Test
    fun `a perfect run reports zero drift and unit over-travel`() {
        val o = OutageRecord(0, 1, 10.0, deadReckonedDistanceM = 100.0, errorM = 0.0, trueDistanceM = 100.0)
        assertTrue(o.driftPercent == 0.0)
        assertTrue(abs(o.overTravelRatio - 1.0) < 1e-9)
    }
}
