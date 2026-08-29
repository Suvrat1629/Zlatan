package com.sih26168.idr.map

import android.view.ViewGroup
import com.sih26168.idr.core.types.Mode

interface MapRenderer {

    fun attach(container: ViewGroup)

    fun detach()
    fun onResume()
    fun onPause()

    fun updatePosition(lat: Double, lon: Double, headingDeg: Float)

    fun appendTrailPoint(lat: Double, lon: Double, mode: Mode)

    fun isGnssFamily(mode: Mode): Boolean = mode != Mode.DEAD_RECKONING && mode != Mode.INIT
}
