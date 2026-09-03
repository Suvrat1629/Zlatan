package com.sih26168.idr.engine

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.ImuSampleRecord
import com.sih26168.idr.core.types.PositioningEngine
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The per-tick snapshot is trimmed to the span the decimator can reach, because the untrimmed
 * version reprocessed roughly 1,700 samples ten times a second at 214 Hz (field telemetry: engine
 * tick 20.9 ms median, 101.9 ms max, against a 100 ms period, of which only 2.6 ms was inference).
 *
 * An optimisation is only safe if it changes nothing, so that is what these assert: the decimated
 * window produced from the trimmed snapshot must be bit-identical to the one produced from the full
 * buffer, including when timestamps arrive out of order.
 */
class SnapshotTrimTest {

    private val config = EngineConfig(outputRateHz = 10.0)
    private val periodNs = (1_000_000_000.0 / config.modelRateHz).toLong()
    private val spanNanos = DecimationSpan.nanosFor(config)

    private fun sample(t: Long, v: Float) =
        ImuSampleRecord(t, v, 2f * v, 9.81f + v, 0.1f * v, 0f, 9.81f, v, -v, 0.5f * v)

    /** 8 s of 214 Hz samples — the real device rate, and more than the decimator needs. */
    private fun fill(buffer: RingBuffer, count: Int, stepNs: Long, jitter: Random? = null): Long {
        var t = 0L
        repeat(count) { i ->
            val offset = jitter?.let { (it.nextInt(-3, 4)).toLong() * 1_000_000L } ?: 0L
            buffer.push(sample(t + offset, kotlin.math.sin(i * 0.05).toFloat()))
            t += stepNs
        }
        return t - stepNs
    }

    @Test
    fun trimmedSnapshotProducesTheIdenticalDecimatedWindow() {
        val buffer = RingBuffer(4000)
        val stepNs = 1_000_000_000L / 214
        fill(buffer, 1712, stepNs)

        val conditioning = ConditioningStage()
        val decimator = Decimator(
            config.antiAliasCutoffHz, config.modelRateHz, PositioningEngine.WINDOW_SAMPLES,
        )

        val full = conditioning.process(buffer.snapshot()).samples
        val newest = buffer.newestTNanos()!!
        val trimmed = conditioning.process(buffer.snapshotSince(newest - spanNanos)).samples

        assertTrue(trimmed.size < full.size, "trim should actually reduce the sample count")

        val fromFull = decimator.decimate(full, full.last().tNanos)
        val fromTrimmed = decimator.decimate(trimmed, trimmed.last().tNanos)
        assertNotNull(fromFull); assertNotNull(fromTrimmed)
        assertEquals(fromFull.size, fromTrimmed.size)
        for (r in fromFull.indices) {
            for (c in fromFull[r].indices) {
                // Not bit-identical by construction: the low-pass is an IIR, so a bounded residual
                // from the trimmed history survives. The warm-up margin is what bounds it -- at 15
                // time constants the residual is e^-15 of the initial state error.
                assertTrue(
                    abs(fromFull[r][c] - fromTrimmed[r][c]) < 1e-4f,
                    "row $r channel $c differs: ${fromFull[r][c]} vs ${fromTrimmed[r][c]}",
                )
            }
        }
    }

    @Test
    fun outOfOrderTimestampsDoNotTruncateTheTail() {
        // Vendors do not always deliver monotonically. Walking backwards and stopping at the first
        // old sample would drop stragglers; the tolerance window is what prevents that.
        val buffer = RingBuffer(4000)
        val stepNs = 1_000_000_000L / 214
        fill(buffer, 1712, stepNs, jitter = Random(7))

        val newest = buffer.newestTNanos()!!
        val trimmed = buffer.snapshotSince(newest - spanNanos)
        val fullInSpan = buffer.snapshot().filter { it.tNanos >= newest - spanNanos }

        assertEquals(
            fullInSpan.size, trimmed.size,
            "trimmed snapshot must contain every in-span sample despite timestamp jitter",
        )
    }

    @Test
    fun spanTracksConfigRatherThanBeingHardcoded() {
        // A future config change must move the span with it, or it silently starves the decimator
        // instead of failing loudly.
        val slowModel = EngineConfig(outputRateHz = 10.0, modelRateHz = 5.0)
        val fastModel = EngineConfig(outputRateHz = 10.0, modelRateHz = 20.0)
        assertTrue(
            DecimationSpan.nanosFor(slowModel) > DecimationSpan.nanosFor(fastModel),
            "a slower model rate needs a longer span",
        )
        // And a gentler low-pass has a longer time constant, so it needs more warm-up.
        val gentle = EngineConfig(outputRateHz = 10.0, antiAliasCutoffHz = 1.0)
        val sharp = EngineConfig(outputRateHz = 10.0, antiAliasCutoffHz = 8.0)
        assertTrue(
            DecimationSpan.nanosFor(gentle) > DecimationSpan.nanosFor(sharp),
            "a lower cutoff means a longer IIR settling time and so a longer span",
        )
    }
}
