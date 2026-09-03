package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon

/**
 * A map-matcher's output for one tick: the snapped [position] plus an honest [uncertaintyM]
 * (metres, 1-std proxy) and whether [rawPosition] actually had a nearby road to snap to at
 * all -- covariance/confidence instead of a bare `LatLon` (plan2.md §3 step 4).
 *
 * [roadBearingDeg] is the geographic bearing (0 = north, clockwise) of the matched road at
 * [position], or null when there is no segment (off-road / no map). A fusion filter uses it to
 * apply the match *anisotropically* -- tight across the road, effectively free along it --
 * because map matching constrains cross-track error well and along-track barely at all
 * (architecture doc §4). Direction along the segment is arbitrary; only the axis matters.
 *
 * Whether `RealEngine` feeds this back into the fusion filter is gated by
 * `EngineConfig.useMapMatchFusion` (plan2.md §3 step 6); default off, display-only, unchanged
 * from before -- see the comment at `RealEngine.tickOnce()` for why the earlier
 * reset-the-reckoner-to-the-match approach was reverted.
 */
data class MapMatchResult(
    val position: LatLon,
    val uncertaintyM: Float,
    val onRoad: Boolean,
    val roadBearingDeg: Double? = null,
)

interface MapMatcher {

    fun snap(rawPosition: LatLon): MapMatchResult

    /**
     * VESTIGIAL — no implementation overrides either of these, so both return null for every
     * matcher that exists. Any gate written against them is dead code that looks live.
     *
     * [MapMatchResult.roadBearingDeg] is the bearing that is actually populated (HmmMapMatcher
     * sets it from the matched candidate), and [MapMatchResult.uncertaintyM] is the confidence
     * figure to gate on. Kept only so an out-of-tree matcher can still supply them; delete once
     * that is ruled out.
     */
    fun matchedBearingDeg(): Double? = null

    /** VESTIGIAL — see [matchedBearingDeg]. Perpendicular distance (m) of the last snap. */
    fun matchedDistanceM(): Double? = null
    /**
     * Whether this matcher's [MapMatchResult.uncertaintyM] is a real positional covariance
     * safe to fold into the fusion filter as a measurement (plan2.md §3 step 6). Only the
     * multi-hypothesis HMM qualifies: a greedy nearest-segment snapper has no protection
     * against locking onto a parallel road, and feeding that in as a tight cross-track
     * measurement is strictly worse than leaving it display-only. `RealEngine` refuses
     * `useMapMatchFusion` unless this is true.
     */
    val emitsFusableCovariance: Boolean get() = false
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
