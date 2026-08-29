package com.sih26168.idr.androidassets

import com.sih26168.idr.core.assets.AssetManifest
import com.sih26168.idr.core.assets.AssetType
import org.json.JSONObject

object AssetManifestJson {
    fun parse(json: String): AssetManifest {
        val o = JSONObject(json)
        val inputNorm = o.optJSONObject("input_norm")
        val outputNorm = o.optJSONObject("output_norm")
        val selfTest = o.optJSONObject("self_test")

        return AssetManifest(
            assetId = o.getString("asset_id"),
            version = o.getString("version"),
            sha256 = o.optString("sha256", ""),
            assetType = AssetType.valueOf(o.optString("asset_type", "SPEED_MODEL")),
            tensorShapes = o.optJSONObject("tensor_shapes")?.let { shapes ->
                shapes.keys().asSequence().associateWith { key ->
                    val arr = shapes.getJSONArray(key)
                    (0 until arr.length()).map { arr.getInt(it) }
                }
            } ?: emptyMap(),
            tensorDtypes = o.optJSONObject("tensor_dtypes")?.let { dtypes ->
                dtypes.keys().asSequence().associateWith { dtypes.getString(it) }
            } ?: emptyMap(),
            channelOrder = o.optJSONArray("channel_order")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            gravityHandling = o.optString("gravity_handling", ""),
            windowSamples = o.optInt("window_samples", 0),
            strideSamples = o.optInt("stride_samples", 1),
            expectedRateHz = o.optDouble("expected_rate_hz", 0.0),
            inputMean = inputNorm?.optJSONArray("mean")?.let { arr -> (0 until arr.length()).map { arr.getDouble(it).toFloat() } } ?: emptyList(),
            inputStd = inputNorm?.optJSONArray("std")?.let { arr -> (0 until arr.length()).map { arr.getDouble(it).toFloat() } } ?: emptyList(),
            outputMean = outputNorm?.optDouble("mean", 0.0)?.toFloat() ?: 0f,
            outputStd = outputNorm?.optDouble("std", 1.0)?.toFloat() ?: 1f,
            outputNormalization = outputNorm?.optString("method", "none") ?: "none",
            selfTestInput = selfTest?.optJSONArray("input")?.let { rows ->
                (0 until rows.length()).map { r ->
                    val row = rows.getJSONArray(r)
                    (0 until row.length()).map { row.getDouble(it).toFloat() }
                }
            } ?: emptyList(),
            selfTestExpectedOutput = selfTest?.let { if (it.has("expected_output")) it.getDouble("expected_output").toFloat() else null },
            selfTestToleranceAbs = selfTest?.optDouble("tolerance_abs", 1e-3)?.toFloat() ?: 1e-3f,
            minEngineVersion = o.optString("min_engine_version", "0.0.0"),

            mapRegion = o.optString("map_region", "").ifEmpty { null },
            mapValidFrom = o.optString("map_valid_from", "").ifEmpty { null },
            mapValidUntil = o.optString("map_valid_until", "").ifEmpty { null },
        )
    }
}
