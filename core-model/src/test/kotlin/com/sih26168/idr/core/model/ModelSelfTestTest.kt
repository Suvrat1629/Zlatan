package com.sih26168.idr.core.model

import com.sih26168.idr.core.assets.AssetManifest
import com.sih26168.idr.core.assets.AssetType
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression test for TODO.md G2: a manifest that declares no self-test must be rejected, not
 * treated as a pass.
 *
 * The failure this guards against is subtle and already happened. `ModelSelfTest.run` was being
 * called on every model load, and it was correct — but it returned silently when the manifest
 * declared no expected output. An exporter schema change moved the self-test vector from
 * `self_test` to `self_test_vector`, the parser read null, this function returned, and a model
 * carrying different normalization constants was loaded unverified. The guard was live and doing
 * nothing, which is worse than not existing, because it looked like coverage.
 */
class ModelSelfTestTest {

    private fun manifest(expected: Float?, input: List<List<Float>> = listOf(listOf(1f, 2f))) =
        AssetManifest(
            assetId = "speed_model",
            version = "test",
            sha256 = "",
            assetType = AssetType.SPEED_MODEL,
            selfTestInput = input,
            selfTestExpectedOutput = expected,
            selfTestToleranceAbs = 1e-3f,
        )

    private class FixedEstimator(private val value: Float) : SpeedEstimator {
        override fun estimate(normalizedWindow: Array<FloatArray>): Float = value
    }

    @Test
    fun `a manifest with no expected output is rejected rather than skipped`() {
        assertFailsWith<ModelSelfTest.SelfTestMissingException> {
            ModelSelfTest.run(FixedEstimator(3f), manifest(expected = null))
        }
    }

    @Test
    fun `a model that returns the wrong number is rejected`() {
        assertFailsWith<ModelSelfTest.SelfTestFailedException> {
            ModelSelfTest.run(FixedEstimator(3f), manifest(expected = 11.107f))
        }
    }

    @Test
    fun `a matching model passes`() {
        ModelSelfTest.run(FixedEstimator(11.107f), manifest(expected = 11.107f))
    }

    @Test
    fun `an expected output with no input vector is a malformed manifest`() {
        assertFailsWith<IllegalArgumentException> {
            ModelSelfTest.run(FixedEstimator(1f), manifest(expected = 1f, input = emptyList()))
        }
    }
}
