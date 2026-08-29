package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.LatLon

interface FusionFilter {

    fun predict(deadReckoned: LatLon, speedMps: Float, headingDeg: Double, dtSeconds: Double)

    fun updateWithGnss(fix: LatLon, speedMps: Float, bearingDeg: Float, horizAccM: Float)

    fun estimate(): LatLon

    fun uncertaintyM(): Float
}

class PassthroughFusionFilter(initial: LatLon) : FusionFilter {
    private var current = initial

    override fun predict(deadReckoned: LatLon, speedMps: Float, headingDeg: Double, dtSeconds: Double) {
        current = deadReckoned
    }

    override fun updateWithGnss(fix: LatLon, speedMps: Float, bearingDeg: Float, horizAccM: Float) {
        current = fix
    }

    override fun estimate(): LatLon = current

    override fun uncertaintyM(): Float = 0f
}
