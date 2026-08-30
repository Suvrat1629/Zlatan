package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorStateEkfTest {
    private val start = LatLon(12.9716, 77.5946)

    @Test
    fun passthroughHasNoHeadingButEkfDoes() {
        assertNull(PassthroughFusionFilter(start).headingDeg())

        val ekf = ErrorStateEkf(start)
        ekf.predict(start, speedMps = 10f, headingDeg = 45.0, dtSeconds = 0.1)
        assertTrue(kotlin.math.abs(ekf.headingDeg()!! - 45.0) < 1e-6)
    }

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
    fun validBearingCorrectsHeadingFasterThanPositionAlone() {
        val withBearing = ErrorStateEkf(start)
        val positionOnly = ErrorStateEkf(start)

        // Both drift identically with a heading bias, no GNSS yet.
        repeat(300) {
            withBearing.predict(start, speedMps = 15f, headingDeg = 10.0, dtSeconds = 0.1)
            positionOnly.predict(start, speedMps = 15f, headingDeg = 10.0, dtSeconds = 0.1)
        }

        val truePos = Geo.stepForward(start, headingDeg = 0.0, forwardM = 15.0 * 30.0)
        withBearing.updateWithGnss(truePos, speedMps = 15f, bearingDeg = 0f, horizAccM = 3f, bearingValid = true)
        positionOnly.updateWithGnss(truePos, speedMps = 15f, bearingDeg = 0f, horizAccM = 3f, bearingValid = false)

        assertTrue(
            withBearing.headingUncertaintyDeg() < positionOnly.headingUncertaintyDeg(),
            "a valid GNSS bearing should shrink heading uncertainty beyond what position " +
                "coupling alone gives (withBearing=${withBearing.headingUncertaintyDeg()} deg, " +
                "positionOnly=${positionOnly.headingUncertaintyDeg()} deg)",
        )
    }

    @Test
    fun bearingIgnoredWhenInvalidOrBelowSpeedThreshold() {
        val truePos = Geo.stepForward(start, headingDeg = 0.0, forwardM = 100.0)

        val invalidBearing = ErrorStateEkf(start)
        val tooSlow = ErrorStateEkf(start)
        repeat(50) {
            invalidBearing.predict(start, speedMps = 10f, headingDeg = 10.0, dtSeconds = 1.0)
            tooSlow.predict(start, speedMps = 10f, headingDeg = 10.0, dtSeconds = 1.0)
        }
        invalidBearing.updateWithGnss(truePos, speedMps = 10f, bearingDeg = 0f, horizAccM = 3f, bearingValid = false)
        tooSlow.updateWithGnss(truePos, speedMps = 1f, bearingDeg = 0f, horizAccM = 3f, bearingValid = true)

        // Neither gate should let the bearing measurement fire, so both should behave
        // exactly like the position-only update (same heading uncertainty).
        val reference = ErrorStateEkf(start)
        repeat(50) { reference.predict(start, speedMps = 10f, headingDeg = 10.0, dtSeconds = 1.0) }
        reference.updateWithGnss(truePos, speedMps = 10f, bearingDeg = 0f, horizAccM = 3f, bearingValid = false)

        assertTrue(
            kotlin.math.abs(invalidBearing.headingUncertaintyDeg() - reference.headingUncertaintyDeg()) < 1e-9,
        )
        assertTrue(
            kotlin.math.abs(tooSlow.headingUncertaintyDeg() - reference.headingUncertaintyDeg()) < 1e-9,
        )
    }

    @Test
    fun mapMatchCorrectsCrossTrackButNotAlongTrack() {
        // Drive due north along the road (heading 0) so covariance grows cleanly: heading ARW
        // feeds the EAST (cross-track) variance, speed noise feeds NORTH (along-track), and the
        // N/E cross-covariance stays ~0. Then a map match reports the road is 30 m east of
        // where the filter believes -- a pure cross-track correction.
        val ekf = ErrorStateEkf(start)
        repeat(400) { ekf.predict(start, speedMps = 15f, headingDeg = 0.0, dtSeconds = 0.1, speedSigmaMps = 1.0f) }

        val estBefore = ekf.estimate()
        val covBefore = ekf.positionCovarianceNE() // [varN (along), varE (cross), covNE]

        val matched = Geo.stepForward(estBefore, headingDeg = 90.0, forwardM = 30.0) // 30 m east
        ekf.updateWithMapMatch(matched, alongTrackSigmaM = 10_000f, crossTrackSigmaM = 3f, roadBearingDeg = 0.0)

        val estAfter = ekf.estimate()
        val covAfter = ekf.positionCovarianceNE()
        val eastMove = Geo.distanceM(LatLon(estBefore.lat, estAfter.lon), estBefore)
        val northMove = Geo.distanceM(LatLon(estAfter.lat, estBefore.lon), estBefore)

        assertTrue(eastMove > 20.0, "cross-track: should pull most of the 30 m toward the road, moved $eastMove m")
        assertTrue(northMove < eastMove * 0.1, "along-track estimate barely moves ($northMove m vs $eastMove m east)")
        assertTrue(
            covAfter[1] < covBefore[1] * 0.5,
            "cross-track (east) variance should collapse (before=${covBefore[1]}, after=${covAfter[1]})",
        )
        assertTrue(
            covAfter[0] > covBefore[0] * 0.85,
            "along-track (north) variance must be left essentially untouched " +
                "(before=${covBefore[0]}, after=${covAfter[0]})",
        )
    }

    @Test
    fun mapMatchOnAnEastWestRoadCorrectsTheOrthogonalAxis() {
        // Same mechanism, road running due east (bearing 90) -- guards the axis rotation in
        // updateWithMapMatch, not just the north-aligned special case. Drive east; the match
        // says the road is 30 m north of the filter's belief.
        val ekf = ErrorStateEkf(start)
        repeat(400) { ekf.predict(start, speedMps = 15f, headingDeg = 90.0, dtSeconds = 0.1, speedSigmaMps = 1.0f) }
        val before = ekf.estimate()
        val covBefore = ekf.positionCovarianceNE()

        val matched = Geo.stepForward(before, headingDeg = 0.0, forwardM = 30.0) // 30 m north
        ekf.updateWithMapMatch(matched, alongTrackSigmaM = 10_000f, crossTrackSigmaM = 3f, roadBearingDeg = 90.0)

        val after = ekf.estimate()
        val covAfter = ekf.positionCovarianceNE()
        val northMove = Geo.distanceM(LatLon(after.lat, before.lon), before)
        val eastMove = Geo.distanceM(LatLon(before.lat, after.lon), before)
        assertTrue(northMove > 20.0, "cross-track (north) should be pulled toward the road, moved $northMove m")
        assertTrue(eastMove < northMove * 0.1, "along-track (east) estimate barely moves")
        assertTrue(covAfter[0] < covBefore[0] * 0.5, "cross-track (north) variance should collapse")
        assertTrue(covAfter[1] > covBefore[1] * 0.85, "along-track (east) variance left untouched")
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
