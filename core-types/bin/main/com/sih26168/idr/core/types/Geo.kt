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

/** A point in a local tangent-plane frame: metres north and east of some anchor. */
data class LocalEnu(val north: Double, val east: Double)

/**
 * Local tangent-plane (north, east) frame anchored at a fixed lat/lon, using an
 * equirectangular approximation — accurate to well under a metre at city scale.
 */
class LocalFrame(private val anchor: LatLon) {
    private val metresPerDegLat = Geo.EARTH_RADIUS_M * PI / 180.0
    private val cosAnchorLat = cos(Math.toRadians(anchor.lat))

    fun toLocal(p: LatLon): LocalEnu = LocalEnu(
        north = (p.lat - anchor.lat) * metresPerDegLat,
        east = (p.lon - anchor.lon) * metresPerDegLat * cosAnchorLat,
    )

    fun toLatLon(local: LocalEnu): LatLon = LatLon(
        lat = anchor.lat + local.north / metresPerDegLat,
        lon = anchor.lon + local.east / (metresPerDegLat * cosAnchorLat),
    )
}
