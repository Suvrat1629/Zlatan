package com.sih26168.idr.map

import android.content.Context
import android.graphics.DashPathEffect
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

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val mapView = MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(17.0)
    }

    // Non-deprecated osmdroid API: fillPaint / outlinePaint instead of the
    // fillColor / strokeColor / strokeWidth setters.
    private val uncertaintyCircle = Polygon(mapView).apply {
        fillPaint.color = UNCERTAINTY_FILL_GNSS
        outlinePaint.color = UNCERTAINTY_STROKE_GNSS
        outlinePaint.strokeWidth = dp(1.5f)
    }

    private val vehicleMarker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setIcon(context.getDrawable(R.drawable.ic_position_dot))
        title = "You"
    }

    private val trailSegments = mutableListOf<Polyline>()
    private var currentSegmentIsGnss: Boolean? = null
    private var lastMode: Mode = Mode.INIT

    private val plainGpsTrail = Polyline(mapView).apply {
        outlinePaint.strokeWidth = dp(2.5f)
        outlinePaint.color = 0xFF9E9E9E.toInt()
        isEnabled = false
    }

    private var followEnabled = true
    private var lastPoint: GeoPoint? = null
    private var hasCentered = false

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
        // Circle colour reinforces the mode: blue with a fix, amber when
        // degraded / dead-reckoning (paired with the dashed trail, not hue alone).
        val degraded = lastMode == Mode.DEGRADED || lastMode == Mode.DEAD_RECKONING
        uncertaintyCircle.fillPaint.color =
            if (degraded) UNCERTAINTY_FILL_DR else UNCERTAINTY_FILL_GNSS
        uncertaintyCircle.outlinePaint.color =
            if (degraded) UNCERTAINTY_STROKE_DR else UNCERTAINTY_STROKE_GNSS
        if (followEnabled) {
            if (!hasCentered) {
                // The very first fix: jump straight there. MapView defaults to
                // (0,0) ("Null Island") when no center is ever set, and animating
                // from there at a tight zoom means flying tiles across half the
                // globe before settling — this is what "the map takes forever to
                // load" actually was. Every update after this one still animates.
                mapView.controller.setCenter(point)
                hasCentered = true
            } else {
                mapView.controller.animateTo(point)
            }
        }
        mapView.invalidate()
    }

    override fun appendTrailPoint(lat: Double, lon: Double, mode: Mode) {
        lastMode = mode
        val isGnss = isGnssFamily(mode)
        if (currentSegmentIsGnss != isGnss) {
            val segment = Polyline(mapView).apply {
                outlinePaint.strokeWidth = dp(4f)
                outlinePaint.color = if (isGnss) GNSS_TRAIL_COLOR else DEAD_RECKONING_TRAIL_COLOR
                // Dead-reckoning segments are dashed as well as amber, so the
                // "no fix here" stretch reads without relying on colour.
                if (!isGnss) {
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)
                }
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
        private val GNSS_TRAIL_COLOR = 0xFF1565C0.toInt()
        private val DEAD_RECKONING_TRAIL_COLOR = 0xFFFF8F00.toInt()

        private val UNCERTAINTY_FILL_GNSS = 0x331565C0
        private val UNCERTAINTY_STROKE_GNSS = 0x661565C0
        private val UNCERTAINTY_FILL_DR = 0x33FF8F00
        private val UNCERTAINTY_STROKE_DR = 0x66FF8F00
    }
}
