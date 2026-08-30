package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon

interface MapMatcher {

    fun snap(rawPosition: LatLon): LatLon

    /** Bearing (deg, clockwise from north) of the road segment the last snap matched,
     *  or null when the last position was off the network. Direction is the way's
     *  drawing order — callers must resolve the 180-degree ambiguity themselves. */
    fun matchedBearingDeg(): Double? = null

    /** Perpendicular distance (m) of the last snap, or null when off-network. */
    fun matchedDistanceM(): Double? = null
}

class NoOpMapMatcher : MapMatcher {
    override fun snap(rawPosition: LatLon): LatLon = rawPosition
}
