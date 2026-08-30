package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.LatLon
import kotlin.test.Test
import kotlin.test.assertTrue

class DeadReckonerTest {
    @Test
    fun stepMovesForwardAlongHeading() {
        val start = LatLon(12.9716, 77.5946)
        val dr = DeadReckoner(start)
        val moved = dr.step(speedMps = 10f, headingDeg = 0.0, dtSeconds = 1.0)
        assertTrue(moved.lat > start.lat, "10 m/s north for 1 s should move north")
        assertTrue(dr.position == moved)
    }

    @Test
    fun resetOverridesPosition() {
        val dr = DeadReckoner(LatLon(0.0, 0.0))
        dr.step(10f, 0.0, 1.0)
        val fix = LatLon(5.0, 5.0)
        dr.reset(fix)
        assertTrue(dr.position == fix)
    }
}
