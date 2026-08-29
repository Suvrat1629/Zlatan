package com.sih26168.idr.core.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MagnetometerCalibratorTest {

    /** Evenly spread unit directions (Fibonacci sphere) — stands in for "the user actually
     *  rotated the phone through many orientations", not just wobbled near one. */
    private fun sphereDirections(count: Int): List<Triple<Double, Double, Double>> {
        val goldenAngle = PI * (3.0 - sqrt(5.0))
        return (0 until count).map { i ->
            val y = 1.0 - (i / (count - 1).toDouble()) * 2.0
            val radiusAtY = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = goldenAngle * i
            Triple(cos(theta) * radiusAtY, y, sin(theta) * radiusAtY)
        }
    }

    @Test
    fun startsUncalibratedWithNoSamples() {
        val calibrator = MagnetometerCalibrator()
        assertEquals(0, calibrator.samplesCollected)
        assertEquals(0f, calibrator.hardIronOffset[0])
        assertEquals(1f, calibrator.softIronScale[0])
        assertTrue(calibrator.coverageFraction == 0f)
        assertTrue(calibrator.residualCoefficientOfVariation.isNaN())
        assertTrue(calibrator.fieldMagnitudeUt.isNaN())
        assertFalse(calibrator.isGoodEnough)
        val raw = calibrator.calibrate(12f, -3f, 40f)
        assertEquals(12f, raw[0]); assertEquals(-3f, raw[1]); assertEquals(40f, raw[2])
    }

    @Test
    fun recoversHardIronOffsetFromWellDistributedSamples() {
        val calibrator = MagnetometerCalibrator()
        val trueCenter = Triple(12.0, -6.0, 9.0)
        val trueRadius = 45.0

        sphereDirections(400).forEach { (dx, dy, dz) ->
            calibrator.addSample(
                (trueCenter.first + trueRadius * dx).toFloat(),
                (trueCenter.second + trueRadius * dy).toFloat(),
                (trueCenter.third + trueRadius * dz).toFloat(),
            )
        }

        val offset = calibrator.hardIronOffset
        assertTrue(abs(offset[0] - trueCenter.first) < 1.0, "x offset off: ${offset[0]}")
        assertTrue(abs(offset[1] - trueCenter.second) < 1.0, "y offset off: ${offset[1]}")
        assertTrue(abs(offset[2] - trueCenter.third) < 1.0, "z offset off: ${offset[2]}")
        assertTrue(
            abs(calibrator.fieldMagnitudeUt - trueRadius) < 1.0,
            "expected field magnitude close to $trueRadius, got ${calibrator.fieldMagnitudeUt}",
        )
    }

    @Test
    fun goodCoverageAndConsistencyReportsGoodEnough() {
        val calibrator = MagnetometerCalibrator()
        sphereDirections(500).forEach { (dx, dy, dz) ->
            calibrator.addSample((5.0 + 40.0 * dx).toFloat(), (-2.0 + 40.0 * dy).toFloat(), (3.0 + 40.0 * dz).toFloat())
        }
        assertEquals(1f, calibrator.coverageFraction)
        assertTrue(calibrator.isGoodEnough)
    }

    @Test
    fun clusteredSamplesNeverReportGoodEnoughRegardlessOfCount() {
        val calibrator = MagnetometerCalibrator()
        // 2000 samples, but all near the same orientation (tight jitter) — a phone left
        // sitting on a desk while just vibrating, not genuinely rotated.
        repeat(2000) { i ->
            val jitter = (i % 5) * 0.01f
            calibrator.addSample(40f + jitter, 5f + jitter, 3f + jitter)
        }
        assertTrue(calibrator.coverageFraction < MIN_COVERAGE_FOR_TEST)
        assertFalse(calibrator.isGoodEnough)
    }

    @Test
    fun implausibleFieldStrengthIsNotGoodEnough() {
        val calibrator = MagnetometerCalibrator()
        // Radius ~500 µT — an order of magnitude above Earth's field, e.g. held right next
        // to a real magnet. Well-covered and self-consistent, but not a usable calibration.
        sphereDirections(400).forEach { (dx, dy, dz) ->
            calibrator.addSample((500.0 * dx).toFloat(), (500.0 * dy).toFloat(), (500.0 * dz).toFloat())
        }
        assertEquals(1f, calibrator.coverageFraction)
        assertFalse(calibrator.isGoodEnough)
    }

    @Test
    fun softIronScaleCompensatesTheStretchedAxis() {
        val calibrator = MagnetometerCalibrator()
        // Y axis reads 1.4x hot relative to X and Z — simulated soft iron anisotropy.
        sphereDirections(400).forEach { (dx, dy, dz) ->
            calibrator.addSample((40.0 * dx).toFloat(), (40.0 * 1.4 * dy).toFloat(), (40.0 * dz).toFloat())
        }
        val scale = calibrator.softIronScale
        // The stretched axis must get pulled down relative to the other two.
        assertTrue(scale[1] < scale[0], "expected Y scale (${scale[1]}) < X scale (${scale[0]})")
        assertTrue(scale[1] < scale[2], "expected Y scale (${scale[1]}) < Z scale (${scale[2]})")

        val calibrated = calibrator.calibrate(0f, (40f * 1.4f), 0f)
        val calibratedOnAxisX = calibrator.calibrate(40f, 0f, 0f)
        // After correction, equal raw excursions on X vs the (originally stretched) Y axis
        // should land close to the same corrected magnitude.
        assertTrue(abs(abs(calibrated[1]) - abs(calibratedOnAxisX[0])) < 3f)
    }

    @Test
    fun residualConsistencyImprovesAsMoreSamplesArrive() {
        val calibrator = MagnetometerCalibrator()
        // Needs to clear both MIN_SAMPLES_FOR_FIT (40, before residual scoring starts) and
        // MIN_RESIDUAL_SAMPLES (60, before the CoV stops reporting NaN) — 150 clears both
        // with margin so covEarly is a real number, not a NaN that trivially fails below.
        sphereDirections(150).forEach { (dx, dy, dz) ->
            calibrator.addSample((7.0 + 40.0 * dx).toFloat(), (7.0 + 40.0 * dy).toFloat(), (7.0 + 40.0 * dz).toFloat())
        }
        val covEarly = calibrator.residualCoefficientOfVariation
        assertFalse(covEarly.isNaN(), "expected covEarly to already be a real number at 150 samples")

        sphereDirections(500).forEach { (dx, dy, dz) ->
            calibrator.addSample((7.0 + 40.0 * dx).toFloat(), (7.0 + 40.0 * dy).toFloat(), (7.0 + 40.0 * dz).toFloat())
        }
        val covLater = calibrator.residualCoefficientOfVariation

        assertFalse(covLater.isNaN())
        assertTrue(covLater <= covEarly + 1e-6f, "expected consistency to not get worse: $covEarly -> $covLater")
    }

    @Test
    fun resetClearsEverything() {
        val calibrator = MagnetometerCalibrator()
        sphereDirections(200).forEach { (dx, dy, dz) -> calibrator.addSample((40 * dx).toFloat(), (40 * dy).toFloat(), (40 * dz).toFloat()) }
        assertTrue(calibrator.samplesCollected > 0)
        calibrator.reset()
        assertEquals(0, calibrator.samplesCollected)
        assertEquals(0f, calibrator.coverageFraction)
        assertFalse(calibrator.isGoodEnough)
    }

    companion object {
        private const val MIN_COVERAGE_FOR_TEST = 0.75f
    }
}
