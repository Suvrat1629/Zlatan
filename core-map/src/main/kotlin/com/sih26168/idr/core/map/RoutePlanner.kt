package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Offline A* route planner over the same OSM road polylines the RoadMatcher uses.
 *
 * Graph: way vertices become nodes keyed by their coordinates rounded to ~1 m, so ways that
 * share an intersection point connect automatically. Edges are consecutive vertex pairs with
 * geodesic-metre weights (local equirectangular frame — sub-metre accurate at city scale).
 *
 * Scope honesty (architecture doc §3.5): routing is demo garnish, not a deliverable — this
 * exists because the road graph was already on the device for map matching. Known limit:
 * the current extract carries no one-way tags, so routes may ignore one-way restrictions.
 *
 * Pure Kotlin, no platform imports.
 */
class RoutePlanner(ways: List<List<DoubleArray>>) {

    private class Node(val x: Double, val y: Double, val lat: Double, val lon: Double) {
        val edges = ArrayList<Edge>(4)
    }

    private class Edge(val to: Int, val costM: Double)

    private val nodes = ArrayList<Node>()
    private val byKey = HashMap<Long, Int>()
    private val lat0: Double
    private val cosLat0: Double

    init {
        var latSum = 0.0
        var n = 0
        for (w in ways) for (p in w) { latSum += p[0]; n++ }
        lat0 = if (n > 0) latSum / n else 0.0
        cosLat0 = cos(Math.toRadians(lat0))

        for (w in ways) {
            var prev = -1
            for (p in w) {
                val id = nodeFor(p[0], p[1])
                if (prev != -1 && prev != id) {
                    val a = nodes[prev]; val b = nodes[id]
                    val c = hypot(a.x - b.x, a.y - b.y)
                    a.edges.add(Edge(id, c))
                    b.edges.add(Edge(prev, c))   // bidirectional (no one-way data yet)
                }
                prev = id
            }
        }
    }

    /** Shortest road path between the road-graph nodes nearest to [from] and [to].
     *  Returns the polyline including both snapped endpoints, or null if unreachable. */
    fun route(from: LatLon, to: LatLon): List<LatLon>? {
        val start = nearestNode(from) ?: return null
        val goal = nearestNode(to) ?: return null
        if (start == goal) return listOf(from, to)

        val gx = nodes[goal].x
        val gy = nodes[goal].y
        val dist = HashMap<Int, Double>()
        val cameFrom = HashMap<Int, Int>()
        val open = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
        dist[start] = 0.0
        open.add(start to hypot(nodes[start].x - gx, nodes[start].y - gy))

        while (open.isNotEmpty()) {
            val (u, _) = open.poll()
            if (u == goal) break
            val du = dist[u] ?: continue
            for (e in nodes[u].edges) {
                val nd = du + e.costM
                if (nd < (dist[e.to] ?: Double.MAX_VALUE)) {
                    dist[e.to] = nd
                    cameFrom[e.to] = u
                    open.add(e.to to nd + hypot(nodes[e.to].x - gx, nodes[e.to].y - gy))
                }
            }
        }
        if (goal !in dist) return null

        val path = ArrayList<LatLon>()
        var cur = goal
        while (true) {
            path.add(LatLon(nodes[cur].lat, nodes[cur].lon))
            cur = cameFrom[cur] ?: break
        }
        path.reverse()
        return path
    }

    private fun nearestNode(p: LatLon): Int? {
        val px = p.lon * 111_320.0 * cosLat0
        val py = p.lat * 111_320.0
        var best = -1
        var bestD = 250.0                      // don't route from/to points far off the network
        for (i in nodes.indices) {
            val d = hypot(nodes[i].x - px, nodes[i].y - py)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0) best else null
    }

    private fun nodeFor(lat: Double, lon: Double): Int {
        // ~1 m rounding: shared intersection vertices collapse into one graph node
        val key = ((lat * 1e5).roundToLong() shl 32) xor ((lon * 1e5).roundToLong() and 0xffffffffL)
        return byKey.getOrPut(key) {
            nodes.add(Node(lon * 111_320.0 * cosLat0, lat * 111_320.0, lat, lon))
            nodes.size - 1
        }
    }
}
