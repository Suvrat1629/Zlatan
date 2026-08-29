package com.sih26168.idr.engine

import kotlin.test.Test
import kotlin.test.assertTrue

class FeatureExtractorTest {
    private val g = 9.80665f

    @Test
    fun stationaryFlatPhoneProducesZeroFeatures() {

        val raw = floatArrayOf(0f, 0f, g, 0f, 0f, g, 0f, 0f, 0f)
        val f = FeatureExtractor.features(raw)
        assertClose(0f, f[0])
        assertClose(0f, f[1])
        assertClose(0f, f[2])
        assertClose(0f, f[6])
    }

    @Test
    fun pureVerticalAccelerationShowsUpAsAVertOnly() {

        val raw = floatArrayOf(0f, 0f, g + 2f, 0f, 0f, g, 0f, 0f, 0f)
        val f = FeatureExtractor.features(raw)
        assertClose(2f, f[1], 1e-3f)
        assertClose(0f, f[0], 1e-3f)
        assertClose(2f, f[2], 1e-3f)
    }

    @Test
    fun pureHorizontalAccelerationShowsUpAsAHorizOnly() {

        val raw = floatArrayOf(3f, 0f, g, 0f, 0f, g, 0f, 0f, 0f)
        val f = FeatureExtractor.features(raw)
        assertClose(3f, f[0], 1e-3f)
        assertClose(0f, f[1], 1e-3f)
    }

    @Test
    fun gyroPassesThroughRawOnChannels3to5() {
        val raw = floatArrayOf(0f, 0f, g, 0f, 0f, g, 1f, 2f, 3f)
        val f = FeatureExtractor.features(raw)
        assertClose(1f, f[3])
        assertClose(2f, f[4])
        assertClose(3f, f[5])
        assertClose(kotlin.math.sqrt(14f), f[6])
    }

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 1e-4f) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected, got $actual (tolerance $tolerance)"
        )
    }
}
