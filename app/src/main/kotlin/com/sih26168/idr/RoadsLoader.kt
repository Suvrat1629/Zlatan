package com.sih26168.idr

import android.content.Context
import com.sih26168.idr.core.map.HmmMapMatcher
import com.sih26168.idr.core.map.MapMatcher
import com.sih26168.idr.core.map.NoOpMapMatcher
import com.sih26168.idr.core.map.RoadGraph
import com.sih26168.idr.core.map.RoadMatcher
import com.sih26168.idr.core.map.RoutePlanner
import com.sih26168.idr.core.types.EngineConfig
import org.json.JSONObject

/**
 * Loads assets/roads.json (compact OSM road polylines built by the desktop Overpass
 * pipeline), builds the shared RoadGraph, and constructs whichever MapMatcher config selects.
 * Missing/corrupt asset -> NoOpMapMatcher, so the engine runs exactly as before outside the
 * covered region.
 */
object RoadsLoader {
    private const val ASSET = "roads.json"

    @Volatile private var cachedGraph: RoadGraph? = null
    @Volatile private var cachedPlanner: RoutePlanner? = null

    private fun graph(context: Context): RoadGraph? {
        cachedGraph?.let { return it }
        return try {
            val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val o = JSONObject(json)
            val waysJson = o.getJSONArray("ways")
            val ways = ArrayList<List<DoubleArray>>(waysJson.length())
            for (i in 0 until waysJson.length()) {
                val w = waysJson.getJSONArray(i)
                val pts = ArrayList<DoubleArray>(w.length())
                for (j in 0 until w.length()) {
                    val p = w.getJSONArray(j)
                    pts.add(doubleArrayOf(p.getDouble(0), p.getDouble(1)))
                }
                ways.add(pts)
            }
            val g = RoadGraph.fromWays(ways)
            cachedGraph = g
            g
        } catch (e: Exception) {
            System.err.println("[RoadsLoader] no usable $ASSET (${e.message})")
            null
        }
    }

    fun load(context: Context, config: EngineConfig): MapMatcher {
        val g = graph(context) ?: return NoOpMapMatcher()
        return if (config.useHmmMapMatcher) {
            System.out.println("[RoadsLoader] road network loaded: ${g.edges.size} segments — HMM map matching active")
            HmmMapMatcher(
                graph = g,
                maxSnapM = config.hmmMaxSnapM.toDouble(),
                candidateCount = config.hmmCandidateCount,
                emissionSigmaM = config.hmmEmissionSigmaM.toDouble(),
                transitionBetaM = config.hmmTransitionBetaM.toDouble(),
                maxTransitionSearchM = config.hmmMaxTransitionSearchM.toDouble(),
                minAdvanceDisplacementM = config.hmmMinAdvanceDisplacementM.toDouble(),
            )
        } else {
            System.out.println("[RoadsLoader] road network loaded: ${g.edges.size} segments — greedy map matching active")
            RoadMatcher(g)
        }
    }

    /** Lazily-built offline route planner over the same road network (null if no road data). */
    fun planner(context: Context): RoutePlanner? {
        cachedPlanner?.let { return it }
        val g = graph(context) ?: return null
        val p = RoutePlanner(g)
        cachedPlanner = p
        System.out.println("[RoadsLoader] route planner ready")
        return p
    }
}
