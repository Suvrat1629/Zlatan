package com.sih26168.idr.map

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Point
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import com.sih26168.idr.R
import com.sih26168.idr.core.types.Mode
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import com.sih26168.idr.core.types.LatLon
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

class OsmdroidMapRenderer(context: Context) : MapRenderer {

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().osmdroidBasePath = context.getExternalFilesDir(null) ?: context.filesDir
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val mapView = MapView(context).apply {
        setMultiTouchControls(true)
        controller.setZoom(17.0)
    }

    init {
        configureTileSource(context)
    }

    /**
     * Aneesh/TODO.md A1: TileSourceFactory.MAPNIK fetches every tile over HTTP from the
     * public OSM demo servers — stalls when panning into new territory, breaks outright
     * with no signal, and bulk-fetching from those servers is against their usage policy
     * (see docs/architecture-android.md §9). Fix: look for a pre-supplied offline tile
     * archive first; only fall back to the live server if none is found, so this never
     * breaks a dev setup that hasn't been given an archive yet.
     *
     * Drop a `.mbtiles` or osmdroid `.sqlite` archive for the demo region at
     * `<external-files-dir>/offline-tiles/` on the device — e.g.
     *   adb push demo-region.mbtiles /sdcard/Android/data/com.sih26168.idr/files/offline-tiles/
     * This does NOT generate that archive — that's a separate, deliberate data-prep step
     * (Geofabrik extract → tile render, see docs/architecture-system.md §10), not
     * something to do live from the app.
     */
    private fun configureTileSource(context: Context) {
        val archiveDir = File(context.getExternalFilesDir(null) ?: context.filesDir, OFFLINE_TILE_DIR)
        // isFileExtensionRegistered wants the bare extension (e.g. "mbtiles"), not the
        // full filename with the dot — passing file.name here always returns false.
        val archiveFiles = archiveDir.listFiles { file ->
            ArchiveFileFactory.isFileExtensionRegistered(file.extension.lowercase())
        }
            ?.toList()
            .orEmpty()

        if (archiveFiles.isEmpty()) {
            System.err.println(
                "[OsmdroidMapRenderer] no offline tile archive at ${archiveDir.absolutePath} — " +
                    "falling back to the live OSM Mapnik server (needs network, and is not for " +
                    "production use — Aneesh/TODO.md A1). Drop a .mbtiles/.sqlite archive there " +
                    "to go fully offline."
            )
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            return
        }

        try {
            val offlineProvider = OfflineTileProvider(SimpleRegisterReceiver(context), archiveFiles.toTypedArray())
            mapView.setTileProvider(offlineProvider)
            mapView.setUseDataConnection(false)

            // MBTiles archives don't store a tile-source name at all, so this can come
            // back empty — the archive still serves tiles by z/x/y regardless of which
            // source is nominally configured, and the network path is already off above.
            val sourceName = offlineProvider.archives
                .asSequence()
                .flatMap { it.tileSources.asSequence() }
                .firstOrNull()
            mapView.setTileSource(
                if (sourceName != null) FileBasedTileSource.getSource(sourceName) else TileSourceFactory.MAPNIK
            )

            System.err.println(
                "[OsmdroidMapRenderer] loaded ${archiveFiles.size} offline tile archive(s) from " +
                    "${archiveDir.absolutePath} — network tile fetching disabled."
            )
        } catch (e: Exception) {
            System.err.println(
                "[OsmdroidMapRenderer] failed to load offline archive(s) at ${archiveDir.absolutePath} " +
                    "(${e.message}) — falling back to the live OSM Mapnik server."
            )
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }
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

    // A5: running total of points retained across all trailSegments, so appendTrailPoint
    // doesn't have to sum actualPoints.size over every segment on each call.
    private var trailPointCount = 0

    // A2: cache the uncertainty-circle geometry so pointsAsCircle() (a fresh vertex-list
    // allocation) only runs when the centre/radius/visibility actually changed materially.
    private var lastCircleCenter: GeoPoint? = null
    private var lastCircleRadiusM = -1.0
    private var lastCircleShown: Boolean? = null
    // Only re-assign the fill/stroke Paint colours when the degraded-ness flips (lastMode
    // is mutated in appendTrailPoint, not here, so start null to force the first write).
    private var lastAppliedDegraded: Boolean? = null

    // A4: reused across frames so toPixels() doesn't allocate two Points per update.
    private val centerPixelA = Point()
    private val centerPixelB = Point()

    private val routeLine = Polyline(mapView).apply {
        outlinePaint.strokeWidth = 14f
        outlinePaint.color = 0x883D5AFE.toInt()      // translucent indigo, under the trails
    }

    private var longPressListener: ((Double, Double) -> Unit)? = null
    private val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
        override fun longPressHelper(p: GeoPoint?): Boolean {
            p ?: return false
            longPressListener?.invoke(p.latitude, p.longitude)
            return true
        }
    })

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
        mapView.overlays.add(eventsOverlay)
        mapView.overlays.add(routeLine)
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

        // A2: this runs ~60×/sec. Rebuilding the circle's vertex list every frame is a
        // per-frame allocation for geometry that barely moved. Only recompute when the
        // centre drifted > ~0.5 m, the radius changed > ~1 m, or it toggled shown/hidden.
        val shown = uncertaintyM > 0f
        val cachedCenter = lastCircleCenter
        val needsCircleRebuild = when {
            lastCircleShown != shown -> true
            !shown -> false // already hidden — nothing to recompute
            cachedCenter == null -> true
            cachedCenter.distanceToAsDouble(point) > CIRCLE_MOVE_EPSILON_M -> true
            abs(uncertaintyM.toDouble() - lastCircleRadiusM) > CIRCLE_RADIUS_EPSILON_M -> true
            else -> false
        }
        if (needsCircleRebuild) {
            uncertaintyCircle.points =
                if (shown) Polygon.pointsAsCircle(point, uncertaintyM.toDouble()) else emptyList()
            lastCircleCenter = point
            lastCircleRadiusM = uncertaintyM.toDouble()
            lastCircleShown = shown
        }

        // Circle colour reinforces the mode: blue with a fix, amber when
        // degraded / dead-reckoning (paired with the dashed trail, not hue alone).
        // Cheap, but only touch the Paint when the degraded state actually flipped.
        val degraded = lastMode == Mode.DEGRADED || lastMode == Mode.DEAD_RECKONING
        if (lastAppliedDegraded != degraded) {
            uncertaintyCircle.fillPaint.color =
                if (degraded) UNCERTAINTY_FILL_DR else UNCERTAINTY_FILL_GNSS
            uncertaintyCircle.outlinePaint.color =
                if (degraded) UNCERTAINTY_STROKE_DR else UNCERTAINTY_STROKE_GNSS
            lastAppliedDegraded = degraded
        }

        if (followEnabled) {
            if (!hasCentered) {
                // The very first fix: jump straight there. MapView defaults to
                // (0,0) ("Null Island") when no center is ever set, and animating
                // from there at a tight zoom means flying tiles across half the
                // globe before settling — this is what "the map takes forever to
                // load" actually was.
                mapView.controller.setCenter(point)
                hasCentered = true
            } else {
                // A4: animateTo() queued a fresh camera animation on every frame,
                // stacked on top of the motion PositionInterpolator has already
                // smoothed — visible rubber-banding plus wasted CPU. setCenter() is
                // an instant jump; the interpolator upstream is what keeps it smooth.
                // Only move the camera when the target drifted a few dp from centre.
                val projection = mapView.projection
                if (projection == null) {
                    mapView.controller.setCenter(point)
                } else {
                    val target = projection.toPixels(point, centerPixelA)
                    val current = projection.toPixels(mapView.mapCenter, centerPixelB)
                    val movedPx = hypot(
                        (target.x - current.x).toDouble(),
                        (target.y - current.y).toDouble()
                    )
                    if (movedPx > dp(CAMERA_MOVE_THRESHOLD_DP)) {
                        mapView.controller.setCenter(point)
                    }
                }
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
        // A5: trailSegments (and its backing vertex lists) otherwise grow for the whole
        // trip and are all redrawn on every invalidate(). Cap retained points at the most
        // recent ~MAX_TRAIL_POINTS, dropping from the oldest segment first.
        pruneTrailToCapacity()
        trailSegments.last().addPoint(GeoPoint(lat, lon))
        trailPointCount++
    }

    /**
     * Drop leading points from the oldest trail segment(s) until adding one more point
     * keeps us within MAX_TRAIL_POINTS. A fully-drained segment is removed from both
     * trailSegments and the overlay list — but never the last remaining segment (it may
     * be emptied, then immediately gets the new point), so the `trailSegments.last()`
     * append below always has a target.
     */
    private fun pruneTrailToCapacity() {
        var overflow = trailPointCount + 1 - MAX_TRAIL_POINTS
        while (overflow > 0 && trailSegments.isNotEmpty()) {
            val oldest = trailSegments.first()
            val pts = oldest.actualPoints
            if (pts.size <= overflow && trailSegments.size > 1) {
                trailSegments.removeAt(0)
                mapView.overlays.remove(oldest)
                trailPointCount -= pts.size
                overflow -= pts.size
            } else {
                // Trim leading points from the oldest (cold) segment — a full rebuild,
                // but rare and never on the segment being appended to unless it's the
                // only one left.
                val drop = minOf(overflow, pts.size)
                oldest.setPoints(ArrayList(pts.subList(drop, pts.size)))
                trailPointCount -= drop
                overflow -= drop
                if (trailSegments.size == 1) break // can't shrink further
            }
        }
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
        val point = lastPoint ?: return
        if (!hasCentered) {
            // Same reasoning as updatePosition()'s first-fix case: if the user pans away
            // before any fix has landed and then hits recenter, don't animate from
            // Null Island either.
            mapView.controller.setCenter(point)
            hasCentered = true
        } else {
            mapView.controller.animateTo(point)
        }
    }

    override fun showRoute(points: List<LatLon>) {
        routeLine.setPoints(points.map { GeoPoint(it.lat, it.lon) })
        mapView.invalidate()
    }

    override fun setOnMapLongPress(listener: ((Double, Double) -> Unit)?) {
        longPressListener = listener
    }

    companion object {
        private const val OFFLINE_TILE_DIR = "offline-tiles"

        // A5: roughly how many trail points to retain across all trailSegments.
        private const val MAX_TRAIL_POINTS = 400
        // A2: don't rebuild the circle for sub-metre jitter / sub-metre radius wobble.
        private const val CIRCLE_MOVE_EPSILON_M = 0.5
        private const val CIRCLE_RADIUS_EPSILON_M = 1.0
        // A4: only re-centre the camera once the target is this far (dp) off centre.
        private const val CAMERA_MOVE_THRESHOLD_DP = 2.5f

        private val GNSS_TRAIL_COLOR = 0xFF1565C0.toInt()
        private val DEAD_RECKONING_TRAIL_COLOR = 0xFFFF8F00.toInt()

        private val UNCERTAINTY_FILL_GNSS = 0x331565C0
        private val UNCERTAINTY_STROKE_GNSS = 0x661565C0
        private val UNCERTAINTY_FILL_DR = 0x33FF8F00
        private val UNCERTAINTY_STROKE_DR = 0x66FF8F00
    }
}
