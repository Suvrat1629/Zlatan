package com.sih26168.idr.engine

import com.sih26168.idr.core.assets.AssetManifest

class Normalizer(private val mean: FloatArray, private val std: FloatArray) {
    init {
        require(mean.size == std.size) { "mean/std length mismatch: ${mean.size} vs ${std.size}" }
    }

    fun apply(window: Array<FloatArray>): Array<FloatArray> = Array(window.size) { r ->
        val row = window[r]
        require(row.size == mean.size) { "row has ${row.size} channels, normalizer expects ${mean.size}" }
        FloatArray(row.size) { c -> (row[c] - mean[c]) / std[c] }
    }

    companion object {
        fun fromManifest(manifest: AssetManifest): Normalizer {
            require(manifest.inputMean.isNotEmpty() && manifest.inputStd.isNotEmpty()) {
                "manifest '${manifest.assetId}' has no input normalization constants"
            }
            return Normalizer(manifest.inputMean.toFloatArray(), manifest.inputStd.toFloatArray())
        }
    }
}
