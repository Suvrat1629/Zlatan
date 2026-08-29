package com.sih26168.idr.map

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.sih26168.idr.core.types.Mode
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class OsmdroidMapRenderer(context: Context) : MapRenderer {

    init {

        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().osmdroidBasePath = context.getExternalFilesDir(null) ?: context.filesDir
    }

    private val mapView = MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(17.0)
    }

    private val vehicleMarker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = "You"
    }

    private val trailSegments = mutableListOf<Polyline>()
    private var currentSegmentIsGnss: Boolean? = null

    override fun attach(container: ViewGroup) {
        container.removeAllViews()
        container.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mapView.overlays.add(vehicleMarker)
    }

    override fun detach() {
        mapView.overlays.clear()
        (mapView.parent as? ViewGroup)?.removeView(mapView)
    }

    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()

    override fun updatePosition(lat: Double, lon: Double, headingDeg: Float) {
        val point = GeoPoint(lat, lon)
        vehicleMarker.position = point
        vehicleMarker.setRotation(headingDeg)
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    override fun appendTrailPoint(lat: Double, lon: Double, mode: Mode) {
        val isGnss = isGnssFamily(mode)
        if (currentSegmentIsGnss != isGnss) {
            val segment = Polyline(mapView).apply {
                outlinePaint.strokeWidth = 10f
                outlinePaint.color = if (isGnss) GNSS_TRAIL_COLOR else DEAD_RECKONING_TRAIL_COLOR
            }
            trailSegments += segment
            mapView.overlays.add(0, segment)
            currentSegmentIsGnss = isGnss
        }
        trailSegments.last().addPoint(GeoPoint(lat, lon))
    }

    companion object {

        private const val GNSS_TRAIL_COLOR = 0xFF1565C0.toInt()
        private const val DEAD_RECKONING_TRAIL_COLOR = 0xFFFF8F00.toInt()
    }
}
