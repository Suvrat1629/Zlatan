package com.sih26168.idr.androidmodel

import com.sih26168.idr.core.assets.AssetHandle
import com.sih26168.idr.core.model.ModelSelfTest
import com.sih26168.idr.core.model.SpeedEstimate
import com.sih26168.idr.core.model.SpeedEstimator
import com.sih26168.idr.core.types.PositioningEngine
import org.tensorflow.lite.Interpreter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class TfliteSpeedEstimator(handle: AssetHandle) : SpeedEstimator {

    private val manifest = handle.manifest
    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private var hasVarianceHead = false

    init {
        val model = loadMappedFile(handle.file)
        interpreter = Interpreter(
            model,
            Interpreter.Options().apply {
                setNumThreads(2)

            },
        )

        val inShape = interpreter.getInputTensor(0).shape()
        require(inShape.size == 3 && inShape[0] == 1) {
            "unexpected input tensor rank/batch: ${inShape.toList()}"
        }
        require(inShape[1] == PositioningEngine.WINDOW_SAMPLES && inShape[2] == PositioningEngine.FEATURES) {
            "model '${manifest.assetId}' v${manifest.version} declares input shape ${inShape.toList()}, " +
                "engine expects [1, ${PositioningEngine.WINDOW_SAMPLES}, ${PositioningEngine.FEATURES}] — " +
                "refusing to load (Aneesh §7: fail loudly on a mismatch, don't produce silently wrong positions)"
        }
        val outShape = interpreter.getOutputTensor(0).shape()
        // A second output element is the variance head: [speed, log_variance]. Detected rather than
        // configured, so a model with one can be dropped in without an app change — and a model
        // without one keeps working. See SpeedEstimator.estimateWithVariance.
        hasVarianceHead = outShape.size >= 2 && outShape[1] >= 2
        if (hasVarianceHead) {
            System.out.println(
                "[TfliteSpeedEstimator] model '${manifest.version}' has a variance head — " +
                    "per-window uncertainty will drive the filter's process noise"
            )
        }

        inputBuffer = ByteBuffer.allocateDirect(4 * inShape[1] * inShape[2]).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(4 * outShape[1]).order(ByteOrder.nativeOrder())

        warmUp()
        ModelSelfTest.run(this, manifest)
    }

    override fun estimateWithVariance(normalizedWindow: Array<FloatArray>): SpeedEstimate {
        val speed = estimate(normalizedWindow)
        if (!hasVarianceHead) return SpeedEstimate(speed, null)
        // The head emits log-variance, which keeps the network's output unconstrained while the
        // variance itself stays positive. Bounded on read: a model that has gone out of
        // distribution can emit an extreme value, and an unbounded sigma would either freeze the
        // filter or make it ignore its own propagation entirely.
        val logVar = outputBuffer.getFloat(4)
        val sigma = kotlin.math.sqrt(kotlin.math.exp(logVar.coerceIn(MIN_LOG_VAR, MAX_LOG_VAR)))
        return SpeedEstimate(speed, sigma)
    }

    override fun estimate(normalizedWindow: Array<FloatArray>): Float {
        require(normalizedWindow.size == PositioningEngine.WINDOW_SAMPLES) {
            "expected ${PositioningEngine.WINDOW_SAMPLES} rows, got ${normalizedWindow.size}"
        }
        inputBuffer.rewind()
        for (row in normalizedWindow) {
            require(row.size == PositioningEngine.FEATURES) { "expected ${PositioningEngine.FEATURES} features, got ${row.size}" }
            for (v in row) inputBuffer.putFloat(v)
        }
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        val speed = outputBuffer.float
        require(speed.isFinite()) {
            "model '${manifest.assetId}' produced a non-finite output (${speed}) — rejecting rather than " +
                "letting it poison downstream filter state (Aneesh §13.9)"
        }
        return speed
    }

    override fun close() {
        interpreter.close()
    }

    private fun warmUp() {

        val dummy = Array(PositioningEngine.WINDOW_SAMPLES) { FloatArray(PositioningEngine.FEATURES) }
        estimate(dummy)
    }

    private fun loadMappedFile(file: java.io.File): ByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    private companion object {
        /** exp(-6) -> sigma 0.05 m/s. Below this the model claims more certainty than the sensors
         *  can support, and the filter would stop trusting its own propagation. */
        const val MIN_LOG_VAR = -6f
        /** exp(6) -> sigma 20 m/s. Above this the measurement is worthless anyway, and an
         *  unbounded value from an out-of-distribution window would freeze the filter. */
        const val MAX_LOG_VAR = 6f
    }
}
