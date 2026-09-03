package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Turn geometry (heading work plan F2 / TODO E2d).
 *
 * A vehicle holding constant speed and turn rate traces a circular arc. Advancing along a single
 * heading per tick and rotating afterwards turns late, so the path swings wide of that circle —
 * observed in the field as overshooting into the next street. These tests pin the geometry.
 */
class TurnArcTest {

    private val start = LatLon(12.9716, 77.5946)

    /** Drive a quarter circle of known radius and report how far the end point misses it. */
    private fun quarterCircleErrorM(useArc: Boolean, speedMps: Float, turnRateDegPerSec: Double): Double {
        val dt = 0.1
        val steps = (90.0 / (turnRateDegPerSec * dt)).toInt()
        val dr = DeadReckoner(start)
        var heading = 0.0
        repeat(steps) {
            val next = heading + turnRateDegPerSec * dt
            if (useArc) dr.stepArc(speedMps, heading, next, dt) else dr.step(speedMps, heading, dt)
            heading = next
        }
        // A quarter circle from a northward start ends one radius north and one radius east of
        // the centre; equivalently, the chord from start to end subtends 90 degrees.
        val radius = speedMps / Math.toRadians(turnRateDegPerSec)
        val expectedChord = radius * Math.sqrt(2.0)
        return abs(Geo.distanceM(start, dr.position) - expectedChord)
    }

    @Test
    fun arcIsAccurateOnASharpTurn() {
        // 15 m/s through a 45 deg/s turn -- a brisk junction turn, radius about 19 m.
        val err = quarterCircleErrorM(useArc = true, speedMps = 15f, turnRateDegPerSec = 45.0)
        assertTrue(err < 0.5, "arc integration should land within 0.5 m of the true chord, was ${"%.2f".format(err)} m")
    }

    @Test
    fun straightSegmentOvershootsAndTheGapGrowsWithTurnRate() {
        val gentle = quarterCircleErrorM(useArc = false, speedMps = 15f, turnRateDegPerSec = 10.0)
        val sharp = quarterCircleErrorM(useArc = false, speedMps = 15f, turnRateDegPerSec = 45.0)
        assertTrue(sharp > gentle, "straight-segment error should grow with turn rate: gentle=$gentle sharp=$sharp")
        val arcSharp = quarterCircleErrorM(useArc = true, speedMps = 15f, turnRateDegPerSec = 45.0)
        assertTrue(sharp > arcSharp, "arc must beat the straight segment on a sharp turn: $sharp vs $arcSharp")
    }

    @Test
    fun ekfPropagatesOnAnArcToo() {
        // Same quarter circle through the filter's own predict(): the EKF ignores the passed
        // dead-reckoned point and propagates its own state, so this exercises the arc there.
        val ekf = ErrorStateEkf(start, EngineConfig.DEFAULT)
        val dt = 0.1
        val turnRate = 45.0
        var heading = 0.0
        repeat((90.0 / (turnRate * dt)).toInt()) {
            heading += turnRate * dt
            ekf.predict(start, 15f, heading, dt, null)
        }
        val radius = 15.0 / Math.toRadians(turnRate)
        val expectedChord = radius * Math.sqrt(2.0)
        val err = abs(Geo.distanceM(start, ekf.estimate()) - expectedChord)
        assertTrue(err < 1.0, "EKF arc propagation should land within 1 m of the true chord, was ${"%.2f".format(err)} m")
    }
}
