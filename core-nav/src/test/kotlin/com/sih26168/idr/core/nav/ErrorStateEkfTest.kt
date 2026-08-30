package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.test.Test
import kotlin.test.assertTrue

class ErrorStateEkfTest {
    private val start = LatLon(12.9716, 77.5946)

    @Test
    fun predictWithoutGnssMovesForwardAlongHeading() {
        val ekf = ErrorStateEkf(start)
        ekf.predict(deadReckoned = start, speedMps = 10f, headingDeg = 0.0, dtSeconds = 1.0)
        val moved = ekf.estimate()
        assertTrue(moved.lat > start.lat, "10 m/s north for 1 s should move north")
        assertTrue(kotlin.math.abs(moved.lon - start.lon) < 1e-9, "heading 0 should not change longitude")
    }

    @Test
    fun uncertaintyGrowsWithoutGnssAndShrinksAfterAFix() {
        val ekf = ErrorStateEkf(start)
        val u0 = ekf.uncertaintyM()

        repeat(50) { ekf.predict(start, speedMps = 10f, headingDeg = 0.0, dtSeconds = 1.0) }
        val uAfterDeadReckoning = ekf.uncertaintyM()
        assertTrue(uAfterDeadReckoning > u0, "uncertainty should grow with no GNSS updates")

        val trueFix = Geo.stepForward(start, headingDeg = 0.0, forwardM = 500.0)
        ekf.updateWithGnss(trueFix, speedMps = 10f, bearingDeg = 0f, horizAccM = 3f)
        val uAfterFix = ekf.uncertaintyM()
        assertTrue(uAfterFix < uAfterDeadReckoning, "a good GNSS fix should shrink uncertainty")
    }

    @Test
    fun accurateFixPullsStrongerThanInaccurateFix() {
        val accurate = ErrorStateEkf(start)
        val inaccurate = ErrorStateEkf(start)
        // Same dead-reckoning drift for both, so any difference comes from the fix itself.
        repeat(30) {
            accurate.predict(start, speedMps = 10f, headingDeg = 0.0, dtSeconds = 1.0)
            inaccurate.predict(start, speedMps = 10f, headingDeg = 0.0, dtSeconds = 1.0)
        }
        val estimateBefore = accurate.estimate()

        // Fix reports a point 100 m further along than dead reckoning currently believes.
        val fix = Geo.stepForward(estimateBefore, headingDeg = 0.0, forwardM = 100.0)
        accurate.updateWithGnss(fix, speedMps = 10f, bearingDeg = 0f, horizAccM = 3f)
        inaccurate.updateWithGnss(fix, speedMps = 10f, bearingDeg = 0f, horizAccM = 50f)

        val movedAccurate = Geo.distanceM(estimateBefore, accurate.estimate())
        val movedInaccurate = Geo.distanceM(estimateBefore, inaccurate.estimate())
        assertTrue(
            movedAccurate > movedInaccurate,
            "a 3 m-accuracy fix should pull the estimate more than a 50 m-accuracy fix " +
                "(moved $movedAccurate m vs $movedInaccurate m)",
        )
    }

    @Test
    fun reacquisitionAfterSustainedHeadingBiasCorrectsQuickly() {
        // Regression test: a position-only EKF's covariance underestimated true uncertainty
        // after a long outage, so a single fresh fix couldn't correct it quickly. See plan2.md.
        val ekf = ErrorStateEkf(start)

        val biasedHeadingDeg = 8.0
        repeat(600) { ekf.predict(start, speedMps = 15f, headingDeg = biasedHeadingDeg, dtSeconds = 0.1) }

        val truePositionAfterOutage = Geo.stepForward(start, headingDeg = 0.0, forwardM = 15.0 * 60.0)
        val errorBeforeFix = Geo.distanceM(truePositionAfterOutage, ekf.estimate())
        assertTrue(errorBeforeFix > 50.0, "sanity: 8 degrees over 900 m should drift well over 50 m, got $errorBeforeFix m")

        ekf.updateWithGnss(truePositionAfterOutage, speedMps = 15f, bearingDeg = 0f, horizAccM = 3f)
        val errorAfterOneFix = Geo.distanceM(truePositionAfterOutage, ekf.estimate())

        assertTrue(
            errorAfterOneFix < errorBeforeFix * 0.5,
            "a single accurate fix after a long outage should correct MOST of the error in " +
                "one update, not need many fixes to claw back (before=$errorBeforeFix m, " +
                "after=$errorAfterOneFix m)",
        )
    }

    @Test
    fun gnssUpdateNeverThrowsOnDegenerateInitialCovariance() {
        // Guard against the det < 1e-9 branch being reachable in a way that silently no-ops
        // every update — construct with a config with different-scale noise sources and
        // hammer both predict/update in sequence.
        val ekf = ErrorStateEkf(start, EngineConfig(ekfInitialUncertaintyM = 1f))
        repeat(20) { i ->
            ekf.predict(start, speedMps = 15f, headingDeg = (i * 7).toDouble(), dtSeconds = 0.1)
            if (i % 5 == 0) {
                ekf.updateWithGnss(start, speedMps = 15f, bearingDeg = 0f, horizAccM = 8f)
            }
        }
        assertTrue(ekf.uncertaintyM().isFinite() && ekf.uncertaintyM() >= 0f)
    }
}
