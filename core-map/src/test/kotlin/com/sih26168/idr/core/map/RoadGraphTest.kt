package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.LocalEnu
import com.sih26168.idr.core.types.LocalFrame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadGraphTest {

    // A fixed local-frame origin used only to build test fixtures in metres, independent of
    // whatever internal anchor RoadGraph itself picks.
    private val origin = LocalFrame(LatLon(0.0, 0.0))
    private fun pt(northM: Double, eastM: Double): DoubleArray {
        val ll = origin.toLatLon(LocalEnu(northM, eastM))
        return doubleArrayOf(ll.lat, ll.lon)
    }
    private fun way(vararg points: Pair<Double, Double>): List<DoubleArray> =
        points.map { (n, e) -> pt(n, e) }

    @Test
    fun snapsOntoNearestSegment() {
        val roadA = way(0.0 to 0.0, 200.0 to 0.0)
        val graph = RoadGraph.fromWays(listOf(roadA))

        val raw = origin.toLatLon(LocalEnu(50.0, 3.0)) // 3 m east of the road at north=50
        val candidates = graph.candidatesNear(raw, maxDistanceM = 35.0)
        assertEquals(1, candidates.size)
        assertTrue(abs(candidates.first().distanceM - 3.0) < 0.5)

        val snapped = graph.positionOf(candidates.first())
        val expected = origin.toLatLon(LocalEnu(50.0, 0.0)) // the point on the road, not raw
        val residualM = com.sih26168.idr.core.types.Geo.distanceM(snapped, expected)
        assertTrue(residualM < 0.5, "expected the snap to land on the road, residual was $residualM m")
    }

    @Test
    fun noCandidateBeyondMaxDistance() {
        val roadA = way(0.0 to 0.0, 200.0 to 0.0)
        val graph = RoadGraph.fromWays(listOf(roadA))
        val raw = origin.toLatLon(LocalEnu(50.0, 100.0)) // 100 m away
        assertTrue(graph.candidatesNear(raw, maxDistanceM = 35.0).isEmpty())
    }

    @Test
    fun routeDistanceAlongASingleEdgeIsTheAlongTrackDifference() {
        val roadA = way(0.0 to 0.0, 200.0 to 0.0)
        val graph = RoadGraph.fromWays(listOf(roadA))
        val c1 = graph.candidatesNear(origin.toLatLon(LocalEnu(20.0, 0.0)), 5.0).first()
        val c2 = graph.candidatesNear(origin.toLatLon(LocalEnu(60.0, 0.0)), 5.0).first()
        val d = graph.routeDistanceM(c1, c2, cutoffM = 100.0)
        assertNotNull(d)
        assertTrue(abs(d!! - 40.0) < 1.0)
    }

    @Test
    fun routeDistanceThroughAJunctionSumsThePath() {
        // Two parallel N-S roads 8 m apart, connected by cross streets at north=0 and north=200
        // -- a small rectangle, like a real pair of streets joined by cross streets.
        val roadA = way(0.0 to 0.0, 200.0 to 0.0)
        val roadB = way(0.0 to 8.0, 200.0 to 8.0)
        val crossNear = way(0.0 to 0.0, 0.0 to 8.0)
        val crossFar = way(200.0 to 0.0, 200.0 to 8.0)
        val graph = RoadGraph.fromWays(listOf(roadA, roadB, crossNear, crossFar))

        // From (10, 0) on road A to (20, 8) on road B, both near the north=0 cross street:
        // 10 (down A to the junction) + 8 (across) + 20 (up B) = 38 m via the near junction,
        // versus 190 + 8 + 180 = 378 m the long way round -- routeDistanceM must pick the min.
        val a = graph.candidatesNear(origin.toLatLon(LocalEnu(10.0, 0.0)), 5.0).first()
        val b = graph.candidatesNear(origin.toLatLon(LocalEnu(20.0, 8.0)), 5.0).first()
        val d = graph.routeDistanceM(a, b, cutoffM = 100.0)
        assertNotNull(d)
        assertTrue(abs(d!! - 38.0) < 2.0, "expected ~38 m via the near junction, got $d")
    }

    @Test
    fun unreachableWithinCutoffReturnsNull() {
        val roadA = way(0.0 to 0.0, 200.0 to 0.0)
        val roadB = way(1000.0 to 500.0, 1200.0 to 500.0) // disconnected, far away
        val graph = RoadGraph.fromWays(listOf(roadA, roadB))
        val a = graph.candidatesNear(origin.toLatLon(LocalEnu(20.0, 0.0)), 5.0).first()
        val b = graph.candidatesNear(origin.toLatLon(LocalEnu(1020.0, 500.0)), 5.0).first()
        assertNull(graph.routeDistanceM(a, b, cutoffM = 100.0))
    }
}
