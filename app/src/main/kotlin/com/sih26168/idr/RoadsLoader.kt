package com.sih26168.idr

import android.content.Context
import com.sih26168.idr.core.map.MapMatcher
import com.sih26168.idr.core.map.NoOpMapMatcher
import com.sih26168.idr.core.map.RoadMatcher
import com.sih26168.idr.core.map.RoutePlanner
import org.json.JSONObject

/**
 * Loads assets/roads.json (compact OSM road polylines built by the desktop Overpass
 * pipeline) and constructs the RoadMatcher. Missing/corrupt asset -> NoOpMapMatcher, so
 * the engine runs exactly as before outside the covered region.
 */
object RoadsLoader {
    private const val ASSET = "roads.json"

    @Volatile private var cachedWays: List<List<DoubleArray>>? = null
    @Volatile private var cachedPlanner: RoutePlanner? = null

    private fun ways(context: Context): List<List<DoubleArray>>? {
        cachedWays?.let { return it }
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
            cachedWays = ways
            ways
        } catch (e: Exception) {
            System.err.println("[RoadsLoader] no usable $ASSET (${e.message})")
            null
        }
    }

    fun load(context: Context): MapMatcher {
        val ways = ways(context) ?: return NoOpMapMatcher()
        System.out.println("[RoadsLoader] road network loaded: ${ways.size} ways — map matching active")
        return RoadMatcher(ways)
    }

    /** Lazily-built offline A* planner over the same road network (null if no road data). */
    fun planner(context: Context): RoutePlanner? {
        cachedPlanner?.let { return it }
        val ways = ways(context) ?: return null
        val p = RoutePlanner(ways)
        cachedPlanner = p
        System.out.println("[RoadsLoader] route planner ready")
        return p
    }
}
