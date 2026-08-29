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
        val decimator = Decimator(cutoffHz = 4.0, modelRateHz = 10.0, windowSamples = 50)
        val samples = (0 until 5).map { sample(it * 20_000_000L, 1f) }
        val result = decimator.decimate(samples, tEndNanos = samples.last().tNanos)
        assertNull(result, "5 s of window can't come from 100 ms of history")
    }

    @Test
    fun producesExactWindowShapeOnceEnoughHistoryExists() {
        val decimator = Decimator(cutoffHz = 4.0, modelRateHz = 10.0, windowSamples = 50)

        val samples = (0 until 600).map { sample(it * 10_000_000L, 1f) }
        val tEnd = samples.last().tNanos
        val window = decimator.decimate(samples, tEnd)
        assertNotNull(window)
        assertEquals(50, window.size)
        assertEquals(9, window[0].size)

        assertTrue(kotlin.math.abs(window.last()[0] - 1f) < 0.05f, "expected ax ~= 1, got ${window.last()[0]}")
    }

    @Test
    fun lowPassAttenuatesHighFrequencyNoise() {
        val decimator = Decimator(cutoffHz = 2.0, modelRateHz = 10.0, windowSamples = 50)

        val samples = (0 until 600).map { i ->
            val t = i * 10_000_000L
            val phase = 2.0 * Math.PI * 40.0 * (i / 100.0)
            sample(t, kotlin.math.sin(phase).toFloat() * 10f)
        }
        val window = decimator.decimate(samples, samples.last().tNanos)
        assertNotNull(window)
        val maxAbsOutput = window.maxOf { kotlin.math.abs(it[0]) }
        assertTrue(maxAbsOutput < 3f, "expected heavy attenuation of a 40 Hz tone at 2 Hz cutoff, got amplitude $maxAbsOutput (input amplitude was 10)")
    }
}
