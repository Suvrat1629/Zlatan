package com.sih26168.idr.core.assets

data class AssetManifest(
    val assetId: String,
    val version: String,
    val sha256: String,
    val assetType: AssetType,

    val tensorShapes: Map<String, List<Int>> = emptyMap(),
    val tensorDtypes: Map<String, String> = emptyMap(),
    val channelOrder: List<String> = emptyList(),
    val units: Map<String, String> = emptyMap(),
    val gravityHandling: String = "",
    val windowSamples: Int = 0,
    val strideSamples: Int = 1,
    val expectedRateHz: Double = 0.0,
    val inputMean: List<Float> = emptyList(),
    val inputStd: List<Float> = emptyList(),
    val outputMean: Float = 0f,
    val outputStd: Float = 1f,
    val outputNormalization: String = "none",

    val selfTestInput: List<List<Float>> = emptyList(),
    val selfTestExpectedOutput: Float? = null,
    val selfTestToleranceAbs: Float = 1e-3f,
    val minEngineVersion: String = "0.0.0",

    val mapRegion: String? = null,
    val mapValidFrom: String? = null,
    val mapValidUntil: String? = null,
)

enum class AssetType { SPEED_MODEL, CONTEXT_MODEL, MAP, CONFIG }

class AssetManifestMismatchException(message: String) : IllegalStateException(message)

object ManifestValidation {

    fun validateShape(
        manifest: AssetManifest,
        expectedWindowSamples: Int,
        expectedFeatures: Int,
        expectedChannelOrder: List<String>,
        inputTensorName: String = "imu_window",
    ) {
        val shape = manifest.tensorShapes[inputTensorName]
            ?: throw AssetManifestMismatchException(
                "manifest for '${manifest.assetId}' has no shape for tensor '$inputTensorName'"
            )
        val expected = listOf(1, expectedWindowSamples, expectedFeatures)
        if (shape != expected) {
            throw AssetManifestMismatchException(
                "manifest for '${manifest.assetId}' declares input shape $shape, engine expects $expected"
            )
        }
        if (manifest.channelOrder.isNotEmpty() && manifest.channelOrder != expectedChannelOrder) {
            throw AssetManifestMismatchException(
                "manifest for '${manifest.assetId}' channel order ${manifest.channelOrder} != " +
                    "engine's expected $expectedChannelOrder"
            )
        }
        if (manifest.windowSamples != 0 && manifest.windowSamples != expectedWindowSamples) {
            throw AssetManifestMismatchException(
                "manifest for '${manifest.assetId}' window=${manifest.windowSamples}, " +
                    "engine expects $expectedWindowSamples"
            )
        }
    }
}
