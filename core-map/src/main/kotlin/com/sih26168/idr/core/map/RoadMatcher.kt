package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Forward-only road snapper: projects each position onto the nearest road segment so the
 * vehicle icon travels along the road network instead of cutting through buildings.
 *
 * Design (per SIH-IDR-architecture §9): a spatial grid index over OSM road segments,
 * nearest-perpendicular-projection candidate search, and hysteresis that prefers staying on
 * the current way (a mini Newson–Krumm) so the dot does not flicker between parallel roads.
 * If no road lies within [maxSnapM] (parking lots, off-map areas) the position is returned
 * unchanged — the same graceful degradation path as having no map at all.
 *
 * Pure Kotlin, no platform imports: geometry works in a local equirectangular metre frame
 * anchored at the road data's bbox centre, which is accurate to well under a metre at city
 * scale — far below GPS noise.
 *
 * @param ways road polylines as [lat, lon] point lists (from assets/roads.json)
 */
class RoadMatcher(
    ways: List<List<DoubleArray>>,
    private val maxSnapM: Double = 35.0,
    private val stickyBonusM: Double = 12.0,
    cellSizeM: Double = 120.0,
) : MapMatcher {

    private class Segment(
        val wayId: Int,
        val ax: Double, val ay: Double,
        val bx: Double, val by: Double,
    )

    private val lat0: Double
    private val cosLat0: Double
    private val cell: Double = cellSizeM
    private val grid = HashMap<Long, MutableList<Segment>>()
    private var lastWayId: Int = -1

    init {
        var latSum = 0.0
        var n = 0
        for (w in ways) for (p in w) { latSum += p[0]; n++ }
        lat0 = if (n > 0) latSum / n else 0.0
        cosLat0 = cos(Math.toRadians(lat0))

        for ((wayId, w) in ways.withIndex()) {
            for (i in 0 until w.size - 1) {
                val (ax, ay) = toXY(w[i][0], w[i][1])
                val (bx, by) = toXY(w[i + 1][0], w[i + 1][1])
                val seg = Segment(wayId, ax, ay, bx, by)
                // register the segment in every grid cell its bounding box touches
                val minCx = floor(minOf(ax, bx) / cell).toLong()
                val maxCx = floor(maxOf(ax, bx) / cell).toLong()
                val minCy = floor(minOf(ay, by) / cell).toLong()
                val maxCy = floor(maxOf(ay, by) / cell).toLong()
                for (cx in minCx..maxCx) for (cy in minCy..maxCy) {
                    grid.getOrPut(key(cx, cy)) { mutableListOf() }.add(seg)
                }
            }
        }
    }

    override fun snap(rawPosition: LatLon): LatLon {
        val (px, py) = toXY(rawPosition.lat, rawPosition.lon)
        val cx = floor(px / cell).toLong()
        val cy = floor(py / cell).toLong()

        var bestScore = maxSnapM
        var bestX = 0.0
        var bestY = 0.0
        var bestWay = -1
        for (dx in -1..1) for (dy in -1..1) {
            val segs = grid[key(cx + dx, cy + dy)] ?: continue
            for (s in segs) {
                val (qx, qy) = project(px, py, s)
                val d = hypot(px - qx, py - qy)
                // hysteresis: staying on the current way is worth stickyBonusM of distance,
                // which stops flicker between parallel roads / service lanes
                val score = if (s.wayId == lastWayId) d - stickyBonusM else d
                if (score < bestScore) {
                    bestScore = score
                    bestX = qx; bestY = qy
                    bestWay = s.wayId
                }
            }
        }
        if (bestWay == -1) {
            lastWayId = -1                     // off the network: free-run, honest
            return rawPosition
        }
        lastWayId = bestWay
        return toLatLon(bestX, bestY)
    }

    /** Closest point on segment to (px, py). */
    private fun project(px: Double, py: Double, s: Segment): Pair<Double, Double> {
        val vx = s.bx - s.ax
        val vy = s.by - s.ay
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-9) return s.ax to s.ay
        var t = ((px - s.ax) * vx + (py - s.ay) * vy) / len2
        t = t.coerceIn(0.0, 1.0)
        return (s.ax + t * vx) to (s.ay + t * vy)
    }

    private fun toXY(lat: Double, lon: Double): Pair<Double, Double> =
        (lon * 111_320.0 * cosLat0) to (lat * 111_320.0)

    private fun toLatLon(x: Double, y: Double): LatLon =
        LatLon(lat = y / 111_320.0, lon = x / (111_320.0 * cosLat0))

    private fun key(cx: Long, cy: Long): Long = (cx shl 32) xor (cy and 0xffffffffL)
}
