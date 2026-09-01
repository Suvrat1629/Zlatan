package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Road bearing as a heading measurement.
 *
 * The case this exists for is a GNSS blackout on a long straight: heading random walk is the
 * dominant cross-track error there (Part C: 4% -> 34% with outage duration), and the road under
 * the vehicle is the only absolute heading reference still available. No GNSS is fed in at all
 * here, so anything the filter recovers came from the road alone.
 */
class RoadBearingHeadingTest {

    private val start = LatLon(12.9716, 77.5946)
    private val speedMps = 15f
    private val dt = 0.1
    private val roadBearingDeg = 0.0        // due north, straight
    private val sigmaDeg = EngineConfig.DEFAULT.ekfRoadBearingNoiseDeg

    /**
     * Drive north with a constant yaw-rate error corrupting the heading input — a drifting gyro,
     * no zero-velocity intervals to observe the bias from. Returns the filter's heading error in
     * degrees at the end.
     */
    private fun headingErrorAfterDrift(
        driftDps: Double,
        ticks: Int,
        applyRoadBearing: Boolean,
        measuredRoadBearingDeg: Double = roadBearingDeg,
    ): Double {
        val ekf = ErrorStateEkf(start, EngineConfig.DEFAULT)
        var truePos = start
        var measuredHeadingDeg = 0.0

        for (i in 0 until ticks) {
            truePos = Geo.stepForward(truePos, roadBearingDeg, speedMps * dt)
            measuredHeadingDeg += driftDps * dt
            ekf.predict(truePos, speedMps, measuredHeadingDeg, dt)
            if (applyRoadBearing) ekf.updateWithRoadBearing(measuredRoadBearingDeg, sigmaDeg)
        }
        val error = ((ekf.headingDeg() - roadBearingDeg + 540.0).mod(360.0)) - 180.0
        return abs(error)
    }

    @Test
    fun gyroDriftWalksTheHeadingOffWithoutTheRoad() {
        val error = headingErrorAfterDrift(driftDps = 0.5, ticks = 600, applyRoadBearing = false)
        assertTrue(error > 20.0, "expected 60 s of 0.5 deg/s drift to show; got $error deg")
    }

    @Test
    fun theRoadHoldsTheHeadingThroughTheSameDrift() {
        val error = headingErrorAfterDrift(driftDps = 0.5, ticks = 600, applyRoadBearing = true)
        assertTrue(error < 2.0, "road bearing should pin heading to the road; got $error deg")
    }

    /**
     * A way's direction of travel is arbitrary — the same road drawn the other way round reports a
     * bearing 180 degrees off. The innovation wraps to a quarter turn precisely so that costs
     * nothing, and this is the case the pre-EKF nudge resolved with an explicit branch.
     */
    @Test
    fun aReversedWayCorrectsIdenticallyToAnAlignedOne() {
        val aligned = headingErrorAfterDrift(
            driftDps = 0.5, ticks = 600, applyRoadBearing = true,
            measuredRoadBearingDeg = roadBearingDeg,
        )
        val reversed = headingErrorAfterDrift(
            driftDps = 0.5, ticks = 600, applyRoadBearing = true,
            measuredRoadBearingDeg = roadBearingDeg + 180.0,
        )
        assertTrue(
            abs(aligned - reversed) < 1e-9,
            "way direction must not matter: aligned $aligned deg vs reversed $reversed deg",
        )
    }

    /**
     * theta is accumulated across predict() and every update; nothing wrapped it before. The
     * published heading was always right because headingDeg() wraps on read, but the stored angle
     * grew by 2*pi per lap, and every innovation is a difference against it.
     */
    @Test
    fun headingStaysBoundedThroughManyFullRotations() {
        val ekf = ErrorStateEkf(start, EngineConfig.DEFAULT)
        var headingDeg = 0.0
        // 50 full turns at 36 deg/s.
        repeat(5_000) {
            headingDeg += 3.6
            ekf.predict(start, speedMps, headingDeg, dt)
        }
        val published = ekf.headingDeg()
        assertTrue(published in 0.0..360.0, "published heading left its range: $published")

        // The real check: an innovation against the stored angle must still be exact. A road
        // bearing equal to the current heading has to produce no correction at all.
        val before = ekf.headingDeg()
        ekf.updateWithRoadBearing(before, sigmaDeg)
        assertTrue(
            abs(ekf.headingDeg() - before) < 1e-9,
            "a zero innovation moved the heading: $before -> ${ekf.headingDeg()}",
        )
    }
}
