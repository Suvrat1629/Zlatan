package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.math.exp

/**
 * Forward-only HMM map matcher (plan2.md §3 step 3; architecture doc §9/§16 decision 4).
 *
 * Keeps up to [candidateCount] live hypotheses -- candidate (road segment, projected point)
 * pairs -- and scores each tick's new candidates against every surviving hypothesis with a
 * Newson-Krumm-style transition probability, not just against raw distance. That is what lets
 * it stay correctly matched to a road even when a *closer* parallel road momentarily looks
 * like a better pointwise fit: a greedy nearest-segment pick (see [RoadMatcher]) has no way to
 * tell "closer, but the route to get there doesn't match how far I actually travelled" from
 * "closer, and genuinely on the new road."
 *
 * This is an online forward decode (max-product per tick, no backtrace) rather than batch
 * Viterbi with a traceback: nothing downstream replays or smooths history, so there is nothing
 * to correct retroactively -- consistent with the rest of the engine being forward-only and
 * display-only for this matcher (plan2.md §3 step 6 not done yet).
 *
 * Rate note (plan2.md): `snap()` is called every engine tick (10 Hz). At typical driving
 * speed that is roughly a metre or two of travel per call -- too little for the
 * route-distance/great-circle-distance transition term to discriminate anything, which would
 * silently degenerate this into emission-only (i.e. greedy) scoring. [minAdvanceDisplacementM]
 * gates the hypothesis chain to only advance once genuine displacement has accumulated;
 * ticks in between republish the last result unchanged.
 */
class HmmMapMatcher(
    private val graph: RoadGraph,
    private val maxSnapM: Double = 35.0,
    private val candidateCount: Int = 5,
    private val emissionSigmaM: Double = 10.0,
    private val transitionBetaM: Double = 5.0,
    private val maxTransitionSearchM: Double = 400.0,
    private val minAdvanceDisplacementM: Double = 8.0,
) : MapMatcher {

    override val emitsFusableCovariance: Boolean get() = true

    private class Hypothesis(val candidate: RoadGraph.Candidate, val logProb: Double)

    private var hypotheses: List<Hypothesis> = emptyList()
    private var lastAdvancePosition: LatLon? = null
    private var lastResult: MapMatchResult? = null

    override fun snap(rawPosition: LatLon): MapMatchResult {
        val prev = lastAdvancePosition
        if (prev != null && Geo.distanceM(prev, rawPosition) < minAdvanceDisplacementM) {
            // Below the displacement gate: nothing new to score, republish the last result.
            // lastResult is guaranteed non-null here since prev != null only after the first
            // real advance below has already set it.
            return lastResult!!
        }

        val candidates = graph.candidatesNear(rawPosition, maxSnapM, candidateCount)
        if (candidates.isEmpty()) {
            hypotheses = emptyList()
            lastAdvancePosition = rawPosition
            val result = MapMatchResult(rawPosition, NoOpMapMatcher.NO_MAP_UNCERTAINTY_M, onRoad = false)
            lastResult = result
            return result
        }

        val greatCircleM = prev?.let { Geo.distanceM(it, rawPosition) } ?: 0.0
        var scored = scoreAgainstPrevious(candidates, greatCircleM)
        // If every transition from the surviving hypotheses is unreachable within
        // maxTransitionSearchM (a genuine jump, e.g. after a long off-network gap), don't let
        // that collapse to an arbitrary pick -- restart from emission-only scores instead.
        if (scored.all { it.logProb.isInfinite() }) {
            scored = candidates.map { Hypothesis(it, emissionLogProb(it)) }
        }
        hypotheses = scored
        lastAdvancePosition = rawPosition

        val top = scored.maxByOrNull { it.logProb }!!
        val maxLog = top.logProb
        val weights = scored.map { exp(it.logProb - maxLog) }
        val sumW = weights.sum()

        // Confidence has two independent parts, added in quadrature so uncertaintyM is a real
        // positional 1-std that means the same thing as RoadMatcher's (perpendicular snap
        // distance) -- one `mapMatchMaxFuseUncertaintyM` threshold has to gate both:
        //  - transition/ambiguity: softmax-weighted spread of the hypothesis positions around
        //    the top pick (several live contenders widen it; one dominant hypothesis -> ~0);
        //  - emission: how far the top pick actually is from the road. Without this a match
        //    that is confident but 20 m off any road would report ~4 m and get fused.
        var varAcc = 0.0
        for (i in scored.indices) {
            val w = weights[i] / sumW
            val dx = scored[i].candidate.x - top.candidate.x
            val dy = scored[i].candidate.y - top.candidate.y
            varAcc += w * (dx * dx + dy * dy)
        }
        val uncertaintyM = kotlin.math.sqrt(varAcc + top.candidate.distanceM * top.candidate.distanceM)
            .coerceAtLeast(MIN_UNCERTAINTY_M)

        val result = MapMatchResult(
            graph.positionOf(top.candidate), uncertaintyM.toFloat(), onRoad = true,
            roadBearingDeg = graph.bearingDegOf(top.candidate),
        )
        lastResult = result
        return result
    }

    private fun scoreAgainstPrevious(candidates: List<RoadGraph.Candidate>, greatCircleM: Double): List<Hypothesis> {
        if (hypotheses.isEmpty()) {
            return candidates.map { Hypothesis(it, emissionLogProb(it)) }
        }
        // A turn taken over one advance step makes the on-road route longer than the
        // straight-line (great-circle) distance between the two observations -- pure geometry,
        // nothing to do with the map being wrong (a right angle needs the route to be ~0.41x
        // longer than the gc distance). Penalising that as a detour is what made an earlier
        // version refuse to follow a turn onto a cross street. So tolerate a route excess of up
        // to CORNER_TOLERANCE_FRAC * gcDistance before the transition penalty starts; at 0.6
        // that covers turns past a right angle. A genuinely wrong match (snapped to a road that
        // needs a real detour to reach) still blows straight past it.
        val slackM = CORNER_TOLERANCE_FRAC * greatCircleM
        return candidates.map { c ->
            var best = Double.NEGATIVE_INFINITY
            for (h in hypotheses) {
                val routeDistM = graph.routeDistanceM(h.candidate, c, maxTransitionSearchM) ?: continue
                val excessM = maxOf(0.0, routeDistM - greatCircleM - slackM)
                val transitionLogProb = -excessM / transitionBetaM
                val score = h.logProb + transitionLogProb
                if (score > best) best = score
            }
            Hypothesis(c, best + emissionLogProb(c))
        }
    }

    private fun emissionLogProb(c: RoadGraph.Candidate): Double =
        -(c.distanceM * c.distanceM) / (2 * emissionSigmaM * emissionSigmaM)

    companion object {
        private const val MIN_UNCERTAINTY_M = 2.0

        /** Route-vs-straight-line excess tolerated before the transition penalty engages, as a
         *  fraction of the step's great-circle distance. A right-angle turn over one step needs
         *  ~0.41; 0.6 leaves headroom past 90 degrees without admitting real detours. */
        private const val CORNER_TOLERANCE_FRAC = 0.6
    }
}
