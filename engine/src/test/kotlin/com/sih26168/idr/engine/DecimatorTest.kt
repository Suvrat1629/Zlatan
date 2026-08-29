package com.sih26168.idr.engine

import com.sih26168.idr.core.types.ImuSampleRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecimatorTest {
    private fun sample(tNanos: Long, ax: Float): ImuSampleRecord =
        ImuSampleRecord(tNanos, ax, 0f, 9.81f, 0f, 0f, 9.81f, 0f, 0f, 0f)

    @Test
    fun returnsNullWithoutEnoughHistory() {
        val decimator = Decimator(modelRateHz = 10.0, windowSamples = 50)
        val samples = (0 until 5).map { sample(it * 20_000_000L, 1f) }
        val result = decimator.decimate(samples, tEndNanos = samples.last().tNanos)
        assertNull(result, "5 s of window can't come from 100 ms of history")
    }

    @Test
    fun producesExactWindowShapeOnceEnoughHistoryExists() {
        val decimator = Decimator(modelRateHz = 10.0, windowSamples = 50)

        val samples = (0 until 600).map { sample(it * 10_000_000L, 1f) }
        val tEnd = samples.last().tNanos
        val window = decimator.decimate(samples, tEnd)
        assertNotNull(window)
        assertEquals(50, window.size)
        assertEquals(9, window[0].size)

        // constant input -> box average is the same constant
        assertTrue(kotlin.math.abs(window.last()[0] - 1f) < 1e-4f, "expected ax == 1, got ${window.last()[0]}")
    }

    @Test
    fun boxAverageAttenuatesHighFrequencyNoise() {
        val decimator = Decimator(modelRateHz = 10.0, windowSamples = 50)

        // 40 Hz tone sampled at 100 Hz -> exactly 4 cycles per 100 ms bin -> mean ~= 0
        val samples = (0 until 600).map { i ->
            val t = i * 10_000_000L
            val phase = 2.0 * Math.PI * 40.0 * (i / 100.0)
            sample(t, kotlin.math.sin(phase).toFloat() * 10f)
        }
        val window = decimator.decimate(samples, samples.last().tNanos)
        assertNotNull(window)
        val maxAbsOutput = window.maxOf { kotlin.math.abs(it[0]) }
        assertTrue(maxAbsOutput < 1f, "expected a 40 Hz tone to average out over a 100 ms bin, got $maxAbsOutput")
    }

    @Test
    fun boxAverageMatchesDecimatePyDefinitionOnAHandBin() {
        // Bin centred on t = 100 ms spans [50 ms, 150 ms). Samples at 60/100/140 ms carry
        // ax = 1/2/3 -> mean = 2. (0 ms and 200 ms samples exist only to satisfy the
        // window-history guard; they fall outside the bin.)
        val decimator = Decimator(modelRateHz = 10.0, windowSamples = 1)
        val samples = listOf(
            sample(0L, 0f),
            sample(60_000_000L, 1f),
            sample(100_000_000L, 2f),
            sample(140_000_000L, 3f),
            sample(200_000_000L, 0f),
        )
        val window = decimator.decimate(samples, tEndNanos = 100_000_000L)
        assertNotNull(window)
        assertEquals(1, window.size)
        assertTrue(kotlin.math.abs(window[0][0] - 2f) < 1e-4f, "box average should be 2, got ${window[0][0]}")
    }

    @Test
    fun emptyBinFallsBackToFirstSampleAtOrAfterCentre() {
        // Bin centred on 100 ms ([50 ms, 150 ms)) is empty; decimate.py falls back to the
        // first sample at/after the centre -> ax = 7.
        val decimator = Decimator(modelRateHz = 10.0, windowSamples = 1)
        val samples = listOf(
            sample(0L, 5f),
            sample(160_000_000L, 7f),
        )
        val window = decimator.decimate(samples, tEndNanos = 100_000_000L)
        assertNotNull(window)
        assertTrue(kotlin.math.abs(window[0][0] - 7f) < 1e-4f, "empty-bin fallback should be 7, got ${window[0][0]}")
    }
}
