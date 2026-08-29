package com.sih26168.idr.engine

import com.sih26168.idr.core.types.ImuSampleRecord
import com.sih26168.idr.engine.testutil.Npz
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Parity gates (Aneesh `SIH-IDR-android.md` §11) — the checks that prove the Kotlin
 * pipeline produces the same numbers as the model team's Python.
 *
 * Answer key: `engine/src/test/resources/parity/testset.npz` (from `sih-26168-model`,
 * model version `tcn_v1_2026-08-29_419ee90`), 97 windows biased to hard cases.
 * Normalisation constants: `parity/norm.json` (same commit); mirrored inline below.
 *
 * These run on the plain JVM — no phone, no TFLite interpreter:
 *   G2   FeatureExtractor(raw_inputs)   == model_inputs        (feature math)
 *   G2a  Decimator box-average          == decimate.py         (decimation)
 *   G3a  Normalizer(model_inputs)       == model_inputs_norm   (normalisation)
 *
 * G0 (manifest self-test), G3 (normalise + model) and G4 (full pipeline on device) need
 * the real interpreter — see `android-model/src/androidTest`.
 */
class ParityGatesTest {

    // parity/norm.json -> input_norm  (model tcn_v1_2026-08-29_419ee90 train split)
    private val normMean = floatArrayOf(1.603948f, 0.040492f, 1.742827f, -0.000353f, -0.003135f, 0.000113f, 0.193925f)
    private val normStd = floatArrayOf(1.552867f, 0.724677f, 1.575946f, 0.114793f, 0.231024f, 0.137221f, 0.219624f)

    private val testset by lazy { Npz.loadResource("parity/testset.npz") }

    @Test
    fun g2_featureExtractor_matches_python_preprocess() {
        val raw = testset.getValue("raw_inputs")        // [97, 50, 9]
        val expected = testset.getValue("model_inputs") // [97, 50, 7]
        var maxDiff = 0f
        for (k in 0 until raw.rows) {
            val got = FeatureExtractor.featureWindow(raw.window(k))
            val exp = expected.window(k)
            for (t in got.indices) for (c in got[t].indices) {
                maxDiff = maxOf(maxDiff, abs(got[t][c] - exp[t][c]))
            }
        }
        assertTrue(maxDiff <= 1e-4f, "G2 feature parity: max abs diff $maxDiff over ${raw.rows} windows")
    }

    @Test
    fun g3a_normalizer_matches_python() {
        val normalizer = Normalizer(normMean, normStd)
        val input = testset.getValue("model_inputs")        // [97, 50, 7]
        val expected = testset.getValue("model_inputs_norm") // [97, 50, 7]
        var maxDiff = 0f
        for (k in 0 until input.rows) {
            val got = normalizer.apply(input.window(k))
            val exp = expected.window(k)
            for (t in got.indices) for (c in got[t].indices) {
                maxDiff = maxOf(maxDiff, abs(got[t][c] - exp[t][c]))
            }
        }
        assertTrue(maxDiff <= 1e-4f, "G3a normalisation parity: max abs diff $maxDiff")
    }

    @Test
    fun g2a_decimator_boxAverage_matches_decimate_py_definition() {
        // testset's raw_high_rate == raw_inputs (IO-VNBD is natively 10 Hz), so the answer
        // key can't exercise real downsampling. Instead: synthesise a 100 Hz stream, run
        // it through Decimator, and check every output row equals the mean of the native
        // samples in its bin — the exact definition decimate.py implements.
        val dec = Decimator(modelRateHz = 10.0, windowSamples = 50)
        val periodNs = 100_000_000L
        val native = (0 until 700).map { i ->
            val t = i * 10_000_000L
            val v = kotlin.math.sin(i * 0.3).toFloat() + 0.4f * kotlin.math.sin(i * 2.1).toFloat()
            ImuSampleRecord(t, v, 2f * v, 9.81f + v, 0.1f * v, 0f, 9.81f, v, -v, 0.5f * v)
        }
        val tEnd = native.last().tNanos
        val out = dec.decimate(native, tEnd)!!
        var maxDiff = 0f
        for (k in out.indices) {
            val centre = tEnd - (out.size - 1 - k) * periodNs
            val inBin = native.filter { it.tNanos >= centre - periodNs / 2 && it.tNanos < centre + periodNs / 2 }
            assertTrue(inBin.isNotEmpty(), "bin $k unexpectedly empty in this synthetic stream")
            val expected = FloatArray(9)
            for (s in inBin) {
                val ch = s.toRawChannels()
                for (d in 0 until 9) expected[d] += ch[d]
            }
            for (d in 0 until 9) expected[d] /= inBin.size
            for (d in 0 until 9) maxDiff = maxOf(maxDiff, abs(out[k][d] - expected[d]))
        }
        assertTrue(maxDiff <= 1e-5f, "G2a box-average parity: max abs diff $maxDiff")
    }

    @Test
    fun g2a_note_realHighRateFixtureStillNeeded() {
        // Documents the gap: a Python-generated high-rate -> 10 Hz fixture from decimate.py
        // would let G2a compare against the actual reference, not just the definition.
        val raw = testset.getValue("raw_inputs")
        val hi = testset.getValue("raw_high_rate")
        assertTrue(
            raw.shape.contentEquals(hi.shape),
            "raw_high_rate should mirror raw_inputs until a real high-rate capture exists",
        )
    }
}
