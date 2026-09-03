package com.sih26168.idr.core.model

import com.sih26168.idr.core.assets.AssetManifest

/**
 * Runs the manifest's recorded input/output pair through the loaded model and refuses the model if
 * the answer differs. This is the only check that catches a model whose weights, normalization or
 * quantisation are wrong in a way that still produces a plausible-looking number.
 */
object ModelSelfTest {
    class SelfTestFailedException(message: String) : IllegalStateException(message)
    class SelfTestMissingException(message: String) : IllegalStateException(message)

    fun run(estimator: SpeedEstimator, manifest: AssetManifest) {
        // A missing self-test is a failure, not a pass.
        //
        // This used to return silently when the manifest declared no expected output, which turned
        // the guard into a no-op precisely when it was needed most. It fired for real on
        // 2026-08-31: an exporter schema change moved the vector from `self_test` to
        // `self_test_vector`, the parser read null, this function returned, and a model swap went
        // through unverified. The check was being called the whole time and quietly doing nothing.
        //
        // Every navigation model we ship carries a self-test by construction, so absence means the
        // manifest and the parser disagree about where it lives -- exactly the condition worth
        // failing on. See sih-26168-notes/Aneesh/TODO.md G2.
        val expected = manifest.selfTestExpectedOutput
            ?: throw SelfTestMissingException(
                "model '${manifest.assetId}' v${manifest.version} declares no self-test expected output. " +
                    "Either the manifest is incomplete or its schema no longer matches the parser " +
                    "(AssetManifestJson reads 'self_test'). Refusing to load an unverified model."
            )
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
