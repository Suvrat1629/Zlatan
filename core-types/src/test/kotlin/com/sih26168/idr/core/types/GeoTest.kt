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

    @Test
    fun localFrameRoundTripsThroughLocalAndBack() {
        val anchor = LatLon(12.9716, 77.5946)
        val frame = LocalFrame(anchor)
        val point = Geo.stepForward(anchor, headingDeg = 35.0, forwardM = 1200.0)

        val local = frame.toLocal(point)
        val backAgain = frame.toLatLon(local)

        val d = Geo.distanceM(point, backAgain)
        assertTrue(d < 0.5, "local frame round trip should be sub-metre accurate, got $d m")
    }

    @Test
    fun localFrameNorthAndEastMatchCompassConvention() {
        val anchor = LatLon(12.9716, 77.5946)
        val frame = LocalFrame(anchor)

        val north = Geo.stepForward(anchor, headingDeg = 0.0, forwardM = 100.0)
        val east = Geo.stepForward(anchor, headingDeg = 90.0, forwardM = 100.0)

        val localNorth = frame.toLocal(north)
        val localEast = frame.toLocal(east)

        assertTrue(localNorth.north > 90.0 && kotlin.math.abs(localNorth.east) < 1.0)
        assertTrue(localEast.east > 90.0 && kotlin.math.abs(localEast.north) < 1.0)
    }
}
