package com.sih26168.idr.core.model

import com.sih26168.idr.core.assets.AssetManifest

object ModelSelfTest {
    class SelfTestFailedException(message: String) : IllegalStateException(message)

    fun run(estimator: SpeedEstimator, manifest: AssetManifest) {
        val expected = manifest.selfTestExpectedOutput ?: return
        val input = manifest.selfTestInput
        require(input.isNotEmpty()) { "manifest '${manifest.assetId}' has selfTestExpectedOutput but no selfTestInput" }
        val window = Array(input.size) { i -> input[i].toFloatArray() }
        val actual = estimator.estimate(window)
        val diff = kotlin.math.abs(actual - expected)
        if (diff > manifest.selfTestToleranceAbs) {
            throw SelfTestFailedException(
                "model '${manifest.assetId}' v${manifest.version} self-test failed: " +
                    "expected $expected, got $actual (tolerance ${manifest.selfTestToleranceAbs})"
            )
        }
    }
}
