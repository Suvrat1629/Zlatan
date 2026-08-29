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
import org.osmdroid.views.overlay.Polygon
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

    private val uncertaintyCircle = Polygon(mapView).apply {
        fillColor = 0x334285F4
        strokeColor = 0x664285F4
        strokeWidth = 2f
    }

    private val vehicleMarker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setIcon(context.getDrawable(R.drawable.ic_position_dot))
        title = "You"
    }

    private val trailSegments = mutableListOf<Polyline>()
    private var currentSegmentIsGnss: Boolean? = null

    private val plainGpsTrail = Polyline(mapView).apply {
        outlinePaint.strokeWidth = 8f
        outlinePaint.color = 0xFF9E9E9E.toInt()
        isEnabled = false
    }

    private var followEnabled = true
    private var lastPoint: GeoPoint? = null

    override fun attach(container: ViewGroup) {
        container.removeAllViews()
        container.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mapView.overlays.add(plainGpsTrail)
        mapView.overlays.add(uncertaintyCircle)
        mapView.overlays.add(vehicleMarker)
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) followEnabled = false
            false
        }
    }

    override fun detach() {
        mapView.overlays.clear()
        (mapView.parent as? ViewGroup)?.removeView(mapView)
    }

    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()

    override fun updatePosition(lat: Double, lon: Double, headingDeg: Float, uncertaintyM: Float) {
        val point = GeoPoint(lat, lon)
        lastPoint = point
        vehicleMarker.position = point
        vehicleMarker.setRotation(headingDeg)
        uncertaintyCircle.points = if (uncertaintyM > 0f) {
            Polygon.pointsAsCircle(point, uncertaintyM.toDouble())
        } else {
            emptyList()
        }
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

    override fun appendPlainGpsPoint(lat: Double, lon: Double) {
        plainGpsTrail.addPoint(GeoPoint(lat, lon))
    }

    override fun setCompareMode(enabled: Boolean) {
        plainGpsTrail.isEnabled = enabled
        mapView.invalidate()
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
