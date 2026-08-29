package com.sih26168.idr.core.types

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class LatLon(val lat: Double, val lon: Double)

object Geo {

    const val EARTH_RADIUS_M = 6_378_137.0

    fun stepForward(from: LatLon, headingDeg: Double, forwardM: Double): LatLon {
        val headingRad = Math.toRadians(headingDeg)
        val dLat = (forwardM * cos(headingRad)) / EARTH_RADIUS_M * (180.0 / PI)
        val dLon = (forwardM * sin(headingRad)) /
            (EARTH_RADIUS_M * cos(Math.toRadians(from.lat))) * (180.0 / PI)
        return LatLon(from.lat + dLat, from.lon + dLon)
    }

    fun distanceM(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
        return EARTH_RADIUS_M * c
    }
}
