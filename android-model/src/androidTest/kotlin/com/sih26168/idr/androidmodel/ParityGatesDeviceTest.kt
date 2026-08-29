package com.sih26168.idr.androidmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sih26168.idr.engine.FeatureExtractor
import com.sih26168.idr.engine.Normalizer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Parity gates G3 / G4 — the on-device half (Aneesh `SIH-IDR-android.md` §11).
 *
 *   G3  model_inputs  -> normalise -> interpreter   == keras_outputs   (± 1e-3)
 *   G4  raw_inputs     -> full Kotlin pipeline       == keras_outputs   (± 1e-3)  <- de-risking milestone
 *
 * Assets are gitignored — see this module's `src/androidTest/assets/README.md`.
 * G0 (the manifest self-test vector) runs automatically in `TfliteSpeedEstimator.init`.
 */
@RunWith(AndroidJUnit4::class)
class ParityGatesDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().context

    // parity/norm.json -> input_norm (model tcn_v1_2026-08-29_419ee90)
    private val mean = floatArrayOf(1.603948f, 0.040492f, 1.742827f, -0.000353f, -0.003135f, 0.000113f, 0.193925f)
    private val std = floatArrayOf(1.552867f, 0.724677f, 1.575946f, 0.114793f, 0.231024f, 0.137221f, 0.219624f)

    private fun assetOrSkip(name: String): ByteArray? = try {
        ctx.assets.open(name).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    private fun interpreter(model: ByteArray): Interpreter {
        val buf = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
        buf.put(model); buf.rewind()
        return Interpreter(buf, Interpreter.Options().apply { setNumThreads(2) })
    }

    private fun run(interp: Interpreter, window: Array<FloatArray>): Float {
        val inBuf = ByteBuffer.allocateDirect(4 * 50 * 7).order(ByteOrder.nativeOrder())
        for (row in window) for (v in row) inBuf.putFloat(v)
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        interp.run(inBuf, outBuf)
        outBuf.rewind()
        return outBuf.float
    }

    @Test
    fun g3_normalize_plus_model_matches_python() {
        val model = assetOrSkip("engine.tflite")
        val npz = assetOrSkip("testset.npz")
        assumeTrue("engine.tflite + testset.npz not in androidTest assets (see README)", model != null && npz != null)

        val t = Npy.load(npz!!)
        val modelInputs = t.getValue("model_inputs")   // [97,50,7]
        val kerasOut = t.getValue("keras_outputs")      // [97,1]
        val normalizer = Normalizer(mean, std)
        interpreter(model!!).use { interp ->
            var maxDiff = 0f
            for (k in 0 until modelInputs.rows) {
                val got = run(interp, normalizer.apply(modelInputs.window(k)))
                maxDiff = maxOf(maxDiff, abs(got - kerasOut.scalar(k)))
            }
            assertTrue("G3 parity: max abs diff $maxDiff m/s", maxDiff <= 1e-3f)
        }
    }

    @Test
    fun g4_full_pipeline_on_device_matches_python() {
        val model = assetOrSkip("engine.tflite")
        val npz = assetOrSkip("testset.npz")
        assumeTrue("assets missing (see README)", model != null && npz != null)

        val t = Npy.load(npz!!)
        val rawInputs = t.getValue("raw_inputs")    // [97,50,9] — already at 10 Hz
        val kerasOut = t.getValue("keras_outputs")
        val normalizer = Normalizer(mean, std)
        interpreter(model!!).use { interp ->
            var maxDiff = 0f
            for (k in 0 until rawInputs.rows) {
                val features = FeatureExtractor.featureWindow(rawInputs.window(k))
                val got = run(interp, normalizer.apply(features))
                maxDiff = maxOf(maxDiff, abs(got - kerasOut.scalar(k)))
            }
            assertTrue("G4 parity: max abs diff $maxDiff m/s — de-risking milestone", maxDiff <= 1e-3f)
        }
    }
}

/** Tiny C-order little-endian `<f4` .npz reader, self-contained for the androidTest. */
private class NpyArr(val shape: IntArray, val data: FloatArray) {
    val rows get() = shape[0]
    fun window(i: Int): Array<FloatArray> {
        val (t, c) = shape[1] to shape[2]
        val base = i * t * c
        return Array(t) { r -> FloatArray(c) { col -> data[base + r * c + col] } }
    }
    fun scalar(i: Int) = data[i]
}

private object Npy {
    fun load(bytes: ByteArray): Map<String, NpyArr> {
        val out = LinkedHashMap<String, NpyArr>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val raw = zip.readBytes()
                parse(raw)?.let { out[e.name.removeSuffix(".npy")] = it }
            }
        }
        return out
    }

    private fun parse(b: ByteArray): NpyArr? {
        if (b.size < 12 || b[0].toInt() != 0x93) return null
        val v1 = b[6].toInt() == 1
        val hlen = if (v1) (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)
        else (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8) or
            ((b[10].toInt() and 0xFF) shl 16) or ((b[11].toInt() and 0xFF) shl 24)
        val hStart = if (v1) 10 else 12
        val header = String(b, hStart, hlen, Charsets.US_ASCII)
        if (!header.contains("'<f4'")) return null
        val shape = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)!!.groupValues[1]
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
        val count = shape.fold(1) { a, x -> a * x }
        val buf = ByteBuffer.wrap(b, hStart + hlen, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        return NpyArr(shape, FloatArray(count) { buf.float })
    }

    private fun ZipInputStream.readBytes(): ByteArray {
        val o = java.io.ByteArrayOutputStream(); val t = ByteArray(8192)
        while (true) { val n = read(t); if (n < 0) break; o.write(t, 0, n) }
        return o.toByteArray()
    }
}
