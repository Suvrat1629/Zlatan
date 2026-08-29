package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon

interface MapMatcher {

    fun snap(rawPosition: LatLon): LatLon
}

class NoOpMapMatcher : MapMatcher {
    override fun snap(rawPosition: LatLon): LatLon = rawPosition
}
