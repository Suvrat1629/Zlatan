package com.sih26168.idr.map

import android.view.ViewGroup
import com.sih26168.idr.core.types.Mode

interface MapRenderer {

    fun attach(container: ViewGroup)

    fun detach()
    fun onResume()
    fun onPause()

    fun updatePosition(lat: Double, lon: Double, headingDeg: Float, uncertaintyM: Float)

    fun appendTrailPoint(lat: Double, lon: Double, mode: Mode)

    fun appendPlainGpsPoint(lat: Double, lon: Double)

    /** Draw (or replace) the planned route polyline. Empty list clears it. */
    fun showRoute(points: List<com.sih26168.idr.core.types.LatLon>)

    /** Long-press callback for destination picking (lat, lon). */
    fun setOnMapLongPress(listener: ((Double, Double) -> Unit)?)

    fun setCompareMode(enabled: Boolean)

    fun recenter()

    fun isGnssFamily(mode: Mode): Boolean = mode != Mode.DEAD_RECKONING && mode != Mode.INIT
}
