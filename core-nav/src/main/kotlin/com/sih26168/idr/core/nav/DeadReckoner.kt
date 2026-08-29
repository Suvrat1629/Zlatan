package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon

class DeadReckoner(initial: LatLon) {
    var position: LatLon = initial
        private set

    fun step(speedMps: Float, headingDeg: Double, dtSeconds: Double): LatLon {
        val forwardM = speedMps * dtSeconds
        position = Geo.stepForward(position, headingDeg, forwardM)
        return position
    }

    fun reset(to: LatLon) {
        position = to
    }
}

fun drStep(from: LatLon, speedMps: Float, headingDeg: Double, dtSeconds: Double): LatLon =
    Geo.stepForward(from, headingDeg, speedMps * dtSeconds)
