package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.LocalEnu
import com.sih26168.idr.core.types.LocalFrame
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * plan2.md §3 step 5 (offline validation) done the same way the EKF's was: a synthetic case,
 * since no recorded device traces exist in this repo/environment. Reproduces architecture doc
 * §14 W4's own done-criterion in miniature -- "runs offline on a recorded track without
 * snapping to parallel service roads" -- by constructing two parallel roads close enough that
 * a single noisy fix looks closer to the wrong one, and checking the HMM's route-distance
 * consistency check keeps it on the right one while the greedy RoadMatcher gets pulled off.
 */
class HmmMapMatcherTest {

    private val origin = LocalFrame(LatLon(0.0, 0.0))
    private fun at(northM: Double, eastM: Double): LatLon = origin.toLatLon(LocalEnu(northM, eastM))
    private fun pt(northM: Double, eastM: Double): DoubleArray = at(northM, eastM).let { doubleArrayOf(it.lat, it.lon) }
    private fun way(vararg points: Pair<Double, Double>): List<DoubleArray> =
        points.map { (n, e) -> pt(n, e) }

    /** Two parallel N-S roads 8 m apart, joined by cross streets at both ends -- a realistic
     *  minimal topology (a service road running alongside a main road, connected at junctions). */
    private fun parallelRoadsGraph(): RoadGraph {
        val roadA = way(0.0 to 0.0, 200.0 to 0.0)
        val roadB = way(0.0 to 8.0, 200.0 to 8.0)
        val crossNear = way(0.0 to 0.0, 0.0 to 8.0)
        val crossFar = way(200.0 to 0.0, 200.0 to 8.0)
        return RoadGraph.fromWays(listOf(roadA, roadB, crossNear, crossFar))
    }

    @Test
    fun greedyMatcherFlickersOntoTheWrongParallelRoad() {
        val graph = parallelRoadsGraph()
        val greedy = RoadMatcher(graph, maxSnapM = 35.0, stickyBonusM = 6.0)

        greedy.snap(at(20.0, 0.0))                          // clearly on A
        val duringNoise = greedy.snap(at(40.0, 7.9))         // 0.1 m from B, 7.9 m from A
        assertTrue(duringNoise.onRoad)
        val distToB = Geo.distanceM(duringNoise.position, at(40.0, 8.0))
        val distToA = Geo.distanceM(duringNoise.position, at(40.0, 0.0))
        assertTrue(distToB < distToA, "greedy should get pulled onto the closer wrong road B here")
    }

    @Test
    fun hmmStaysOnTheCorrectRoadDespiteACloserWrongCandidate() {
        val graph = parallelRoadsGraph()
        val hmm = HmmMapMatcher(
            graph = graph,
            maxSnapM = 35.0,
            candidateCount = 5,
            emissionSigmaM = 10.0,
            transitionBetaM = 5.0,
            maxTransitionSearchM = 400.0,
            minAdvanceDisplacementM = 1.0, // small so every synthetic step in this test advances
        )

        hmm.snap(at(20.0, 0.0))                              // establishes the hypothesis on A
        val duringNoise = hmm.snap(at(40.0, 7.9))             // 0.1 m from B, 7.9 m from A
        assertTrue(duringNoise.onRoad)
        val distToA = Geo.distanceM(duringNoise.position, at(40.0, 0.0))
        val distToB = Geo.distanceM(duringNoise.position, at(40.0, 8.0))
        assertTrue(
            distToA < distToB,
            "HMM should stay on road A: the route from the A hypothesis to the B candidate is " +
                "~68 m (via the near junction), wildly inconsistent with the ~21.5 m actually " +
                "travelled, while staying on A (~20 m along the same edge) is fully consistent",
        )

        val after = hmm.snap(at(60.0, 0.0))                   // back to unambiguously on A
        assertTrue(after.onRoad)
        val afterDistToA = Geo.distanceM(after.position, at(60.0, 0.0))
        assertTrue(afterDistToA < 3.0, "should still be tracking road A cleanly after the noisy tick")
    }

    @Test
    fun offRoadPositionReturnsHonestUncertainty() {
        val graph = parallelRoadsGraph()
        val hmm = HmmMapMatcher(graph, maxSnapM = 35.0)
        val farAway = at(20.0, 500.0)
        val result = hmm.snap(farAway)
        assertTrue(!result.onRoad)
        assertTrue(result.uncertaintyM >= NoOpMapMatcher.NO_MAP_UNCERTAINTY_M)
    }

    @Test
    fun subThresholdMovementRepublishesTheSameResultInsteadOfRescoring() {
        val graph = parallelRoadsGraph()
        val hmm = HmmMapMatcher(graph, maxSnapM = 35.0, minAdvanceDisplacementM = 8.0)
        val first = hmm.snap(at(20.0, 0.0))
        val second = hmm.snap(at(20.5, 0.0)) // 0.5 m of movement, below the 8 m gate
        assertTrue(first === second || first == second)
    }
}
