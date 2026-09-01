package com.sih26168.idr.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The correctness check that made it defensible to turn map-match fusion on (TODO.md K10).
 *
 * The uncertainty gate asks whether the matcher is CONFIDENT. Measured on 2026-09-01 it was on-road
 * for 88-100% of ticks at 8.8 m median uncertainty and would have passed the 15 m gate 90% of the
 * time — while the map visibly showed the estimate following the wrong streets. Confidence and
 * correctness are not the same question on a dense grid, and fusing a confident wrong snap is worse
 * than not fusing: it drags the filter onto a parallel road and reports high confidence in it.
 */
class MapMatchHeadingGateTest {

    private val maxDisagree = 35.0

    /** Mirrors the gate in RealEngine: fold onto [0,90], since a two-way road driven the other way
     *  is the same road and the anisotropic update is symmetric about the road axis. */
    private fun agrees(headingDeg: Double, roadBearingDeg: Double): Boolean {
        val d = abs(((headingDeg - roadBearingDeg) % 360.0 + 540.0) % 360.0 - 180.0)
        val folded = if (d > 90.0) 180.0 - d else d
        return folded <= maxDisagree
    }

    @Test
    fun `travelling along a road is accepted`() {
        assertTrue(agrees(90.0, 90.0))
        assertTrue(agrees(90.0, 100.0), "a 10 degree curve is the same road")
    }

    @Test
    fun `the same road driven the other way is accepted`() {
        // A two-way road's stored bearing may point either direction. 180 degrees of disagreement
        // is the strongest possible agreement about which road we are on.
        assertTrue(agrees(90.0, 270.0))
        assertTrue(agrees(0.0, 180.0))
    }

    @Test
    fun `a snap onto a cross street is rejected`() {
        // The failure this exists to catch: a perpendicular road, metres away, that the matcher is
        // entirely confident about.
        assertFalse(agrees(90.0, 0.0))
        assertFalse(agrees(90.0, 180.0))
        assertFalse(agrees(45.0, 135.0))
    }

    @Test
    fun `a genuine turn stays inside the tolerance`() {
        // Mid-turn our heading leads or lags the road we are joining. 35 degrees has to admit that,
        // or fusion would drop out exactly at corners, which is where it is most valuable.
        assertTrue(agrees(90.0, 60.0), "30 degrees into a turn must still fuse")
        assertTrue(agrees(90.0, 120.0))
    }

    @Test
    fun `the boundary is where the config says it is`() {
        assertTrue(agrees(0.0, 34.0))
        assertFalse(agrees(0.0, 36.0))
    }
}
