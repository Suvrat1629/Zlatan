package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon

/**
 * A map-matcher's output for one tick: the snapped [position] plus an honest [uncertaintyM]
 * (metres, 1-std proxy) and whether [rawPosition] actually had a nearby road to snap to at
 * all -- covariance/confidence instead of a bare `LatLon` (plan2.md §3 step 4).
 *
 * Still display-only (plan2.md §3 step 6, not done): `RealEngine` publishes [position] to the
 * UI but does not feed it back into the fusion filter or reset the dead reckoner to it -- see
 * the comment at `RealEngine.tickOnce()` for why that reset was tried and reverted.
 */
data class MapMatchResult(
    val position: LatLon,
    val uncertaintyM: Float,
    val onRoad: Boolean,
)

interface MapMatcher {

    fun snap(rawPosition: LatLon): MapMatchResult

    /** Bearing (deg, clockwise from north) of the road segment the last snap matched,
     *  or null when the last position was off the network. Direction is the way's
     *  drawing order — callers must resolve the 180-degree ambiguity themselves. */
    fun matchedBearingDeg(): Double? = null

    /** Perpendicular distance (m) of the last snap, or null when off-network. */
    fun matchedDistanceM(): Double? = null
}

class NoOpMapMatcher : MapMatcher {
    override fun snap(rawPosition: LatLon): MapMatchResult =
        MapMatchResult(rawPosition, NO_MAP_UNCERTAINTY_M, onRoad = false)

    companion object {
        /** No map data at all (or off the covered network): honestly worse than any real
         *  snap's uncertainty, never mistaken for a confident match. */
        const val NO_MAP_UNCERTAINTY_M = 1_000f
    }
}
