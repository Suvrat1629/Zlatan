package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.LatLon

interface FusionFilter {

    fun predict(deadReckoned: LatLon, speedMps: Float, headingDeg: Double, dtSeconds: Double)

    fun updateWithGnss(fix: LatLon, speedMps: Float, bearingDeg: Float, horizAccM: Float, bearingValid: Boolean = false)

    fun estimate(): LatLon

    fun uncertaintyM(): Float

    /** The filter's own corrected heading (degrees), if it tracks one. Null means "doesn't
     *  track heading" -- callers should fall back to their own heading estimator. */
    fun headingDeg(): Double? = null
}

class PassthroughFusionFilter(initial: LatLon) : FusionFilter {
    private var current = initial

    override fun predict(deadReckoned: LatLon, speedMps: Float, headingDeg: Double, dtSeconds: Double) {
        current = deadReckoned
    }

    override fun updateWithGnss(fix: LatLon, speedMps: Float, bearingDeg: Float, horizAccM: Float, bearingValid: Boolean) {
        current = fix
    }

    override fun estimate(): LatLon = current

    override fun uncertaintyM(): Float = 0f
}
