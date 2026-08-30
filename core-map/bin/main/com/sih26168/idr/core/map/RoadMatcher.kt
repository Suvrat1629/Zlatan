package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon

/**
 * Forward-only greedy road snapper: projects each position onto the nearest road segment so
 * the vehicle icon travels along the road network instead of cutting through buildings.
 *
 * Kept alongside [HmmMapMatcher] as the second implementation the architecture doc's rule 1
 * requires (every boundary needs at least two, normally real + a simpler fallback) and as the
 * A/B baseline the HMM is judged against, the same pattern `useErrorStateEkf` used for the
 * fusion filter. Selected via `EngineConfig.useHmmMapMatcher = false`.
 *
 * Single nearest-candidate pick each tick plus hysteresis that prefers staying on the current
 * way (a mini Newson-Krumm) so the dot does not flicker between parallel roads -- but with no
 * memory of the route travelled, that hysteresis is the only thing standing between it and a
 * wrong pick when a closer wrong road is momentarily nearer than the correct one; see
 * `HmmMapMatcherTest` for a case where this genuinely picks wrong and the HMM does not.
 * [uncertaintyM] is a proxy (the perpendicular snap distance, floored), not a real emission
 * probability -- there is no hypothesis set here to derive one from.
 *
 * If no road lies within [maxSnapM] (parking lots, off-map areas) the position is returned
 * unchanged -- the same graceful degradation path as having no map at all.
 */
class RoadMatcher(
    private val graph: RoadGraph,
    private val maxSnapM: Double = 35.0,
    private val stickyBonusM: Double = 6.0,
) : MapMatcher {

    private var lastWayId: Int = -1

    override fun snap(rawPosition: LatLon): MapMatchResult {
        val candidates = graph.candidatesNear(rawPosition, maxSnapM)
        if (candidates.isEmpty()) {
            lastWayId = -1                     // off the network: free-run, honest
            return MapMatchResult(rawPosition, NoOpMapMatcher.NO_MAP_UNCERTAINTY_M, onRoad = false)
        }

        var best: RoadGraph.Candidate? = null
        var bestScore = Double.MAX_VALUE
        for (c in candidates) {
            // hysteresis: staying on the current way is worth stickyBonusM of distance, which
            // stops flicker between parallel roads / service lanes
            val score = if (c.edge.wayId == lastWayId) c.distanceM - stickyBonusM else c.distanceM
            if (score < bestScore) { bestScore = score; best = c }
        }
        val chosen = best!!
        lastWayId = chosen.edge.wayId
        val uncertaintyM = chosen.distanceM.coerceAtLeast(MIN_UNCERTAINTY_M).toFloat()
        return MapMatchResult(
            graph.positionOf(chosen), uncertaintyM, onRoad = true,
            roadBearingDeg = graph.bearingDegOf(chosen),
        )
    }

    companion object {
        private const val MIN_UNCERTAINTY_M = 3.0
    }
}
