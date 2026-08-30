package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.LocalEnu
import com.sih26168.idr.core.types.LocalFrame
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Shared road topology, attributes and spatial index (plan2.md §3 step 2). Replaces the two
 * independent, lossy node/edge reconstructions that [RoadMatcher] and [RoutePlanner] used to
 * build from the same raw polylines -- both now consume this instead.
 *
 * Attribute fields are nullable and unpopulated today: the current `roads.json` comes from a
 * geometry-only Overpass query (plan2.md §3 step 1, not done). This graph is shaped to carry
 * `highway=`/`oneway=`/`tunnel=`/`layer=` the day that query is extended, not to require it --
 * every edge just has null attributes meanwhile, and adjacency defaults to bidirectional
 * (matching the current no-oneway-data behaviour `RoutePlanner` already had).
 *
 * Pure Kotlin, no platform imports. Geometry works in a local tangent-plane metre frame
 * ([LocalFrame], anchored at the mean of all way points -- the same convention `RoadMatcher`
 * and `RoutePlanner` used independently before this), accurate to well under a metre at city
 * scale.
 */
class RoadGraph(
    ways: List<WayInput>,
    private val cellSizeM: Double = 120.0,
) {
    data class Attributes(
        val highway: String? = null,
        val oneway: Boolean? = null,
        val tunnel: Boolean? = null,
        val layer: Int? = null,
    )

    /** One way's geometry plus whatever attributes the loader has for it. */
    data class WayInput(val points: List<DoubleArray>, val attributes: Attributes = Attributes())

    class Edge internal constructor(
        val id: Int,
        val wayId: Int,
        val fromNode: Int,
        val toNode: Int,
        val ax: Double, val ay: Double,
        val bx: Double, val by: Double,
        val lengthM: Double,
        val attributes: Attributes,
    )

    /** A point projected onto [edge] at parameter [t] (0 = fromNode, 1 = toNode). */
    data class Candidate(val edge: Edge, val t: Double, val x: Double, val y: Double, val distanceM: Double)

    private class Node(val x: Double, val y: Double)
    private class AdjEntry(val to: Int, val costM: Double)

    val anchor: LatLon
    private val frame: LocalFrame
    private val nodes = ArrayList<Node>()
    private val nodeKey = HashMap<Long, Int>()
    private val adjacency = ArrayList<ArrayList<AdjEntry>>()
    val edges = ArrayList<Edge>()
    private val cell = cellSizeM
    private val grid = HashMap<Long, MutableList<Int>>() // cell key -> edge ids

    init {
        var latSum = 0.0; var lonSum = 0.0; var n = 0
        for (w in ways) for (p in w.points) { latSum += p[0]; lonSum += p[1]; n++ }
        anchor = if (n > 0) LatLon(latSum / n, lonSum / n) else LatLon(0.0, 0.0)
        frame = LocalFrame(anchor)

        for ((wayId, w) in ways.withIndex()) {
            val pts = w.points
            for (i in 0 until pts.size - 1) {
                val a = nodeFor(pts[i][0], pts[i][1])
                val b = nodeFor(pts[i + 1][0], pts[i + 1][1])
                if (a == b) continue
                addEdge(wayId, a, b, w.attributes)
            }
        }
    }

    private fun nodeFor(lat: Double, lon: Double): Int {
        // ~1 m rounding: shared intersection vertices collapse into one graph node, same
        // convention RoutePlanner used standalone.
        val key = ((lat * 1e5).roundToLong() shl 32) xor ((lon * 1e5).roundToLong() and 0xffffffffL)
        return nodeKey.getOrPut(key) {
            val local = frame.toLocal(LatLon(lat, lon))
            nodes.add(Node(local.east, local.north))
            adjacency.add(ArrayList())
            nodes.size - 1
        }
    }

    private fun addEdge(wayId: Int, a: Int, b: Int, attributes: Attributes) {
        val na = nodes[a]; val nb = nodes[b]
        val lengthM = hypot(nb.x - na.x, nb.y - na.y)
        val edge = Edge(edges.size, wayId, a, b, na.x, na.y, nb.x, nb.y, lengthM, attributes)
        edges.add(edge)
        registerInGrid(edge)

        adjacency[a].add(AdjEntry(b, lengthM))
        // Bidirectional unless the way is explicitly tagged one-way -- there is no one-way
        // data in the current extract, so this is always both directions today.
        if (attributes.oneway != true) adjacency[b].add(AdjEntry(a, lengthM))
    }

    private fun registerInGrid(e: Edge) {
        val minCx = floor(minOf(e.ax, e.bx) / cell).toLong()
        val maxCx = floor(maxOf(e.ax, e.bx) / cell).toLong()
        val minCy = floor(minOf(e.ay, e.by) / cell).toLong()
        val maxCy = floor(maxOf(e.ay, e.by) / cell).toLong()
        for (cx in minCx..maxCx) for (cy in minCy..maxCy) {
            grid.getOrPut(key(cx, cy)) { mutableListOf() }.add(e.id)
        }
    }

    /**
     * Candidate edges within [maxDistanceM] of [rawPosition], nearest first. [k] caps the
     * count (default unbounded -- every segment in range) for callers like the HMM that only
     * want a handful of hypotheses per tick.
     */
    fun candidatesNear(rawPosition: LatLon, maxDistanceM: Double, k: Int = Int.MAX_VALUE): List<Candidate> {
        val local = frame.toLocal(rawPosition)
        val px = local.east; val py = local.north
        val cx = floor(px / cell).toLong()
        val cy = floor(py / cell).toLong()
        val ring = ceil(maxDistanceM / cell).toLong() + 1

        val seen = HashSet<Int>()
        val out = ArrayList<Candidate>()
        for (dx in -ring..ring) for (dy in -ring..ring) {
            val ids = grid[key(cx + dx, cy + dy)] ?: continue
            for (eid in ids) {
                if (!seen.add(eid)) continue
                val e = edges[eid]
                val (qx, qy, t) = project(px, py, e)
                val d = hypot(px - qx, py - qy)
                if (d <= maxDistanceM) out.add(Candidate(e, t, qx, qy, d))
            }
        }
        out.sortBy { it.distanceM }
        return if (out.size > k) out.subList(0, k) else out
    }

    /** Route distance between two projected points, considering both directions each edge
     *  can be entered/left from. Null if unreachable within [cutoffM] (bounded search --
     *  see the HMM's per-tick call volume note in plan2.md §3). */
    fun routeDistanceM(a: Candidate, b: Candidate, cutoffM: Double): Double? {
        if (a.edge.id == b.edge.id) return kotlin.math.abs(a.t - b.t) * a.edge.lengthM

        val aExits = listOf(a.edge.toNode to (1 - a.t) * a.edge.lengthM, a.edge.fromNode to a.t * a.edge.lengthM)
        val bEntries = listOf(b.edge.fromNode to b.t * b.edge.lengthM, b.edge.toNode to (1 - b.t) * b.edge.lengthM)

        var best: Double? = null
        for ((nodeA, partialA) in aExits) {
            for ((nodeB, partialB) in bEntries) {
                val core = shortestPathDistanceM(nodeA, nodeB, cutoffM) ?: continue
                val total = partialA + core + partialB
                if (best == null || total < best) best = total
            }
        }
        return best
    }

    /** Bounded Dijkstra, distance only -- cheap enough to call many times per tick. */
    fun shortestPathDistanceM(fromNode: Int, toNode: Int, cutoffM: Double): Double? {
        if (fromNode == toNode) return 0.0
        val best = HashMap<Int, Double>()
        val open = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
        best[fromNode] = 0.0
        open.add(fromNode to 0.0)
        while (open.isNotEmpty()) {
            val (u, du) = open.poll()
            if (du > (best[u] ?: Double.MAX_VALUE)) continue
            if (u == toNode) return du
            if (du > cutoffM) continue
            for (a in adjacency[u]) {
                val nd = du + a.costM
                if (nd <= cutoffM && nd < (best[a.to] ?: Double.MAX_VALUE)) {
                    best[a.to] = nd
                    open.add(a.to to nd)
                }
            }
        }
        return null
    }

    /** Full, unbounded Dijkstra with path reconstruction, for one-off route requests
     *  (RoutePlanner) rather than the HMM's many-per-tick distance-only calls. */
    fun shortestPath(fromNode: Int, toNode: Int): List<Int>? {
        if (fromNode == toNode) return listOf(fromNode)
        val dist = HashMap<Int, Double>()
        val cameFrom = HashMap<Int, Int>()
        val open = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
        dist[fromNode] = 0.0
        open.add(fromNode to 0.0)
        while (open.isNotEmpty()) {
            val (u, du) = open.poll()
            if (du > (dist[u] ?: Double.MAX_VALUE)) continue
            if (u == toNode) break
            for (a in adjacency[u]) {
                val nd = du + a.costM
                if (nd < (dist[a.to] ?: Double.MAX_VALUE)) {
                    dist[a.to] = nd
                    cameFrom[a.to] = u
                    open.add(a.to to nd)
                }
            }
        }
        if (toNode !in dist) return null
        val path = ArrayList<Int>()
        var cur = toNode
        while (true) {
            path.add(cur)
            cur = cameFrom[cur] ?: break
        }
        path.reverse()
        return path
    }

    fun nodeLatLon(nodeId: Int): LatLon {
        val nd = nodes[nodeId]
        return frame.toLatLon(LocalEnu(north = nd.y, east = nd.x))
    }

    fun positionOf(c: Candidate): LatLon = frame.toLatLon(LocalEnu(north = c.y, east = c.x))

    /** Whichever endpoint of the candidate's edge is geometrically nearer its projected point
     *  -- used by RoutePlanner as a cheap grid-indexed replacement for a linear nearest-node
     *  scan over every node in the graph. */
    fun nearerEndpoint(c: Candidate): Int {
        val dFrom = hypot(c.x - c.edge.ax, c.y - c.edge.ay)
        val dTo = hypot(c.x - c.edge.bx, c.y - c.edge.by)
        return if (dFrom <= dTo) c.edge.fromNode else c.edge.toNode
    }

    /** Closest point on segment [e] to (px, py), with the projection parameter t. */
    private fun project(px: Double, py: Double, e: Edge): Triple<Double, Double, Double> {
        val vx = e.bx - e.ax
        val vy = e.by - e.ay
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-9) return Triple(e.ax, e.ay, 0.0)
        var t = ((px - e.ax) * vx + (py - e.ay) * vy) / len2
        t = t.coerceIn(0.0, 1.0)
        return Triple(e.ax + t * vx, e.ay + t * vy, t)
    }

    private fun key(cx: Long, cy: Long): Long = (cx shl 32) xor (cy and 0xffffffffL)

    companion object {
        /** Convenience for callers with no attributes yet (today's geometry-only roads.json). */
        fun fromWays(rawWays: List<List<DoubleArray>>, cellSizeM: Double = 120.0): RoadGraph =
            RoadGraph(rawWays.map { WayInput(it) }, cellSizeM)
    }
}
