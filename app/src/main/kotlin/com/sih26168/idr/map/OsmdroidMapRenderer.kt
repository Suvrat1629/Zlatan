package com.sih26168.idr.map

import android.content.Context
import android.graphics.DashPathEffect
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

class OsmdroidMapRenderer(context: Context) : MapRenderer {

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        Configuration.getInstance().osmdroidBasePath = context.getExternalFilesDir(null) ?: context.filesDir
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    /**
     * [ROAD_WIDTH_M] expressed in screen pixels at the current zoom and latitude.
     *
     * osmdroid strokes are in pixels, so a fixed width would mean a corridor that covers a lane at
     * one zoom and a whole district at another. Deriving it from ground distance keeps the band
     * meaning the same thing however far the user has zoomed out, and the clamp stops it becoming
     * either invisible or a smear when they go to the extremes.
     */
    private fun roadWidthPx(): Float {
        val lat = lastPoint?.latitude ?: mapView.mapCenter.latitude
        val metresPerPixel = org.osmdroid.util.TileSystem.GroundResolution(lat, mapView.zoomLevelDouble)
        if (metresPerPixel <= 0.0) return dp(MIN_CORRIDOR_DP)
        return (ROAD_WIDTH_M / metresPerPixel).toFloat()
            .coerceIn(dp(MIN_CORRIDOR_DP), dp(MAX_CORRIDOR_DP))
    }

    /** Zoom changes the metres-per-pixel scale, so every corridor is restroked when it moves. */
    private fun refreshCorridorWidth() {
        val w = roadWidthPx()
        if (kotlin.math.abs(w - lastCorridorPx) < 0.5f) return
        lastCorridorPx = w
        corridorSegments.forEach { it.outlinePaint.strokeWidth = w }
    }

    private var lastCorridorPx = 0f
    private var lastTrailPoint: GeoPoint? = null

    private fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val latRad = Math.toRadians(a.latitude)
        val dLat = (b.latitude - a.latitude) * 111_320.0
        val dLon = (b.longitude - a.longitude) * 111_320.0 * kotlin.math.cos(latRad)
        return kotlin.math.hypot(dLat, dLon)
    }

    /**
     * Drop the oldest segment once the trail exceeds [MAX_TRAIL_POINTS].
     *
     * Whole segments rather than individual points, so a dropped stretch never leaves a
     * dead-reckoning span rendered as if it had been GNSS-tracked. At the spacing above this is
     * several kilometres of history, which is more than any demo shows and far less than a session
     * used to accumulate.
     */
    private fun trimTrail() {
        var total = trailSegments.sumOf { it.actualPoints.size }
        while (total > MAX_TRAIL_POINTS && trailSegments.size > 1) {
            val oldTrail = trailSegments.removeAt(0)
            val oldCorridor = corridorSegments.removeAt(0)
            total -= oldTrail.actualPoints.size
            mapView.overlays.remove(oldTrail)
            mapView.overlays.remove(oldCorridor)
        }
    }

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

    /**
     * Wide translucent band drawn beneath each trail segment, representing the WIDTH OF THE ROAD
     * rather than a decorative thickness.
     *
     * The thin centre line is one point per 100 ms tick, so estimator wobble of a metre or two
     * reads as a visibly ragged path even when the position is well inside the correct lane. That
     * misrepresents the result in the opposite direction to the usual: it looks worse than it is.
     *
     * The band's width is [ROAD_WIDTH_M] converted to pixels at the current zoom and latitude, so
     * it always covers a real road's worth of ground. That keeps the visual claim honest — map
     * matching asserts "the vehicle is on this road", not "the vehicle is at this exact point", and
     * a corridor is the shape of that claim. The uncertainty circle continues to carry the
     * magnitude, so nothing about the error is hidden by this.
     */
    private val corridorSegments = mutableListOf<Polyline>()

    private val trailSegments = mutableListOf<Polyline>()
    private var currentSegmentIsGnss: Boolean? = null
    private var lastMode: Mode = Mode.INIT

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
            val corridor = Polyline(mapView).apply {
                outlinePaint.strokeWidth = roadWidthPx()
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                outlinePaint.color = if (isGnss) GNSS_CORRIDOR_COLOR else DEAD_RECKONING_CORRIDOR_COLOR
            }
            corridorSegments += corridor

            val segment = Polyline(mapView).apply {
                outlinePaint.strokeWidth = dp(4f)
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                outlinePaint.color = if (isGnss) GNSS_TRAIL_COLOR else DEAD_RECKONING_TRAIL_COLOR
                // Dead-reckoning segments are dashed as well as amber, so the
                // "no fix here" stretch reads without relying on colour.
                if (!isGnss) {
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)
                }
            }
            trailSegments += segment
            // Order matters: osmdroid draws overlays in list order, so index 0 paints FIRST and
            // ends up at the BOTTOM. Insert the centre line, then the corridor beneath it —
            // inserting the corridor first would leave it painting over the line it exists to
            // sit behind.
            mapView.overlays.add(0, segment)
            mapView.overlays.add(0, corridor)
            currentSegmentIsGnss = isGnss
        }
        // Only add a vertex once the vehicle has actually moved.
        //
        // At 10 Hz and city speed a point lands every half metre, so the drawn path is dominated by
        // estimator jitter rather than by where the vehicle went — which is what made the corridor
        // read as lumpy blocks rather than a road. Spacing vertices by MIN_TRAIL_SPACING_M smooths
        // it, and does so by dropping points that carry no information rather than by filtering
        // ones that do.
        //
        // It also bounds the trail, which was growing without limit for a whole session (TODO A5)
        // and had just been doubled by the corridor.
        val point = GeoPoint(lat, lon)
        val previous = lastTrailPoint
        if (previous != null && distanceM(previous, point) < MIN_TRAIL_SPACING_M) return
        lastTrailPoint = point

        corridorSegments.last().addPoint(point)
        trailSegments.last().addPoint(point)
        trimTrail()
        refreshCorridorWidth()
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

        private val GNSS_TRAIL_COLOR = 0xFF1565C0.toInt()
        private val DEAD_RECKONING_TRAIL_COLOR = 0xFFFF8F00.toInt()

        // Corridor bands: same hues, heavily translucent so the road and its label stay readable
        // underneath. The centre line remains the precise claim; the band is the road-width one.
        private val GNSS_CORRIDOR_COLOR = 0x381565C0
        private val DEAD_RECKONING_CORRIDOR_COLOR = 0x38FF8F00

        /**
         * Width of the drawn corridor in metres of ground.
         *
         * 7 m is a two-lane urban carriageway, which is what map matching actually asserts the
         * vehicle is within. Chosen as a real quantity rather than a pleasing thickness: if the
         * band is ever widened to make results look tidier, that stops being true and the picture
         * starts overstating the result.
         */
        private const val ROAD_WIDTH_M = 7.0

        /** Bounds so the band stays legible when zoomed far out or far in. */
        /**
         * Minimum ground distance between drawn vertices, metres.
         *
         * Below roughly this the difference between consecutive points is estimator noise, not
         * travel. 3 m is under a car length, so no real manoeuvre is lost.
         */
        private const val MIN_TRAIL_SPACING_M = 3.0

        /** Trail length cap. At 3 m spacing this is about 15 km of history. */
        private const val MAX_TRAIL_POINTS = 5_000

        private const val MIN_CORRIDOR_DP = 6f
        private const val MAX_CORRIDOR_DP = 44f

        private val UNCERTAINTY_FILL_GNSS = 0x331565C0
        private val UNCERTAINTY_STROKE_GNSS = 0x661565C0
        private val UNCERTAINTY_FILL_DR = 0x33FF8F00
        private val UNCERTAINTY_STROKE_DR = 0x66FF8F00
    }
}
