package com.sih26168.idr.map

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import com.sih26168.idr.R
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
        setIcon(context.getDrawable(R.drawable.ic_position_dot))
        title = "You"
    }

    private val trailSegments = mutableListOf<Polyline>()
    private var currentSegmentIsGnss: Boolean? = null

    // Following the dot every update is what forces the map back under you the instant
    // you try to pan/zoom away — a real usability bug, not the intended behaviour. Track
    // whether the user has taken manual control instead, and only auto-follow when they
    // haven't. Detecting an actual touch (not our own animateTo() calls) is the reliable
    // signal for "the user grabbed the map."
    private var followEnabled = true
    private var lastPoint: GeoPoint? = null

    override fun attach(container: ViewGroup) {
        container.removeAllViews()
        container.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mapView.overlays.add(vehicleMarker)
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) followEnabled = false
            false // don't consume — let the map's own pan/zoom handling still run
        }
    }

    override fun detach() {
        mapView.overlays.clear()
        (mapView.parent as? ViewGroup)?.removeView(mapView)
    }

    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()

    override fun updatePosition(lat: Double, lon: Double, headingDeg: Float) {
        val point = GeoPoint(lat, lon)
        lastPoint = point
        vehicleMarker.position = point
        vehicleMarker.setRotation(headingDeg)
        if (followEnabled) mapView.controller.animateTo(point)
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

    override fun recenter() {
        followEnabled = true
        lastPoint?.let { mapView.controller.animateTo(it) }
    }

    companion object {
        private const val GNSS_TRAIL_COLOR = 0xFF1565C0.toInt()
        private const val DEAD_RECKONING_TRAIL_COLOR = 0xFFFF8F00.toInt()
    }
}
