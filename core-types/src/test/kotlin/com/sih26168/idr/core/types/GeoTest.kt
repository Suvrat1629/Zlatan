package com.sih26168.idr.core.types

import kotlin.test.Test
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun stepForwardNorthIncreasesLatitude() {
        val start = LatLon(12.9716, 77.5946)
        val moved = Geo.stepForward(start, headingDeg = 0.0, forwardM = 100.0)
        assertTrue(moved.lat > start.lat, "moving north should increase latitude")
        assertTrue(kotlin.math.abs(moved.lon - start.lon) < 1e-9, "heading 0 should not change longitude")
    }

    @Test
    fun distanceRoundTripsWithStepForward() {
        val start = LatLon(12.9716, 77.5946)
        val moved = Geo.stepForward(start, headingDeg = 90.0, forwardM = 250.0)
        val d = Geo.distanceM(start, moved)
        assertTrue(kotlin.math.abs(d - 250.0) < 1.0, "distance should round-trip to ~250 m, got $d")
    }
}
