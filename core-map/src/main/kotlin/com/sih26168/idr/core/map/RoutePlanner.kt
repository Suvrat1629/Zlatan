package com.sih26168.idr.core.map

import com.sih26168.idr.core.types.LatLon

/**
 * Offline A*-equivalent (Dijkstra over a small graph is close enough at city scale) route
 * planner over the same [RoadGraph] the map matcher uses.
 *
 * Scope honesty (architecture doc §3.5): routing is demo garnish, not a deliverable -- this
 * exists because the road graph was already on the device for map matching. Known limit: the
 * current extract carries no one-way tags, so routes may ignore one-way restrictions.
 *
 * Pure Kotlin, no platform imports.
 */
class RoutePlanner(private val graph: RoadGraph) {

    /** Shortest road path between the road-graph nodes nearest to [from] and [to].
     *  Returns the polyline including both snapped endpoints, or null if unreachable. */
    fun route(from: LatLon, to: LatLon): List<LatLon>? {
        val start = nearestNode(from) ?: return null
        val goal = nearestNode(to) ?: return null
        if (start == goal) return listOf(from, to)

        val path = graph.shortestPath(start, goal) ?: return null
        return path.map { graph.nodeLatLon(it) }
    }

    /** Nearest graph node to [p], found via the grid index -- a spatial-query replacement for
     *  what used to be a linear scan over every node in the graph. */
    private fun nearestNode(p: LatLon): Int? {
        // don't route from/to points far off the network
        val c = graph.candidatesNear(p, maxDistanceM = 250.0, k = 1).firstOrNull() ?: return null
        return graph.nearerEndpoint(c)
    }
}
