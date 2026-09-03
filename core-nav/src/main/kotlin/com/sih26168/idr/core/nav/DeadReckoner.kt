package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon

class DeadReckoner(initial: LatLon) {
    var position: LatLon = initial
        private set

    /**
     * Straight-segment step, for callers with no turn-rate information. Prefer the arc overload
     * where the heading at both ends of the interval is known: advancing along a single heading
     * turns late and swings the path wide, and the error scales with turn rate (heading work plan
     * F2).
     */
    fun step(speedMps: Float, headingDeg: Double, dtSeconds: Double): LatLon {
        val forwardM = speedMps * dtSeconds
        position = Geo.stepForward(position, headingDeg, forwardM)
        return position
    }

    /**
     * Exact coordinated-turn step. For constant speed and turn rate across the interval the true
     * path is a circular arc; this is its closed form, exact at any turn rate and degenerating to
     * the straight segment as the heading change approaches zero.
     */
    fun stepArc(
        speedMps: Float,
        headingStartDeg: Double,
        headingEndDeg: Double,
        dtSeconds: Double,
    ): LatLon {
        val dThetaDeg = ((headingEndDeg - headingStartDeg + 540.0).mod(360.0)) - 180.0
        val dTheta = Math.toRadians(dThetaDeg)
        val v = speedMps.toDouble()
        if (kotlin.math.abs(dTheta) <= ARC_MIN_DTHETA) return step(speedMps, headingStartDeg, dtSeconds)

        val t0 = Math.toRadians(headingStartDeg)
        val t1 = t0 + dTheta
        val radius = v * dtSeconds / dTheta
        val north = radius * (kotlin.math.sin(t1) - kotlin.math.sin(t0))
        val east = radius * (kotlin.math.cos(t0) - kotlin.math.cos(t1))
        // Re-expressed as a bearing and a distance so the existing geodetic step stays the single
        // place that converts local metres back to lat/lon.
        val distanceM = kotlin.math.sqrt(north * north + east * east)
        if (distanceM < 1e-9) return position
        val bearingDeg = Math.toDegrees(kotlin.math.atan2(east, north)).mod(360.0)
        position = Geo.stepForward(position, bearingDeg, distanceM)
        return position
    }

    private companion object {
        const val ARC_MIN_DTHETA = 1e-6
    }

    fun reset(to: LatLon) {
        position = to
    }
}

fun drStep(from: LatLon, speedMps: Float, headingDeg: Double, dtSeconds: Double): LatLon =
    Geo.stepForward(from, headingDeg, speedMps * dtSeconds)
