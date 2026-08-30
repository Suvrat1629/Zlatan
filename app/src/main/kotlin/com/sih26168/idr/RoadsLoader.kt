package com.sih26168.idr

import android.content.Context
import com.sih26168.idr.core.map.MapMatcher
import com.sih26168.idr.core.map.NoOpMapMatcher
import com.sih26168.idr.core.map.RoadMatcher
import org.json.JSONObject

/**
 * Loads assets/roads.json (compact OSM road polylines built by the desktop Overpass
 * pipeline) and constructs the RoadMatcher. Missing/corrupt asset -> NoOpMapMatcher, so
 * the engine runs exactly as before outside the covered region.
 */
object RoadsLoader {
    private const val ASSET = "roads.json"

    fun load(context: Context): MapMatcher = try {
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
        System.out.println("[RoadsLoader] road network loaded: ${ways.size} ways — map matching active")
        RoadMatcher(ways)
    } catch (e: Exception) {
        System.err.println("[RoadsLoader] no usable $ASSET (${e.message}) — map matching disabled")
        NoOpMapMatcher()
    }
}
