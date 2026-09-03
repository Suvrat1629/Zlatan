package com.sih26168.idr.core.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mount-invariance sweep for the yaw-rate projection (heading plan F1 / TODO E2c).
 *
 * The property under test: a vehicle turning at a known rate must produce the same measured yaw
 * rate regardless of how the phone is mounted. Raw `gz` fails this by `cos(tilt)`; the projection
 * onto gravity does not.
 */
class YawRateTest {

    private val g = 9.81f

    /** Rotate a device-frame vector by [tiltRad] about the device x axis (phone pitched forward). */
    private fun tiltAboutX(v: Triple<Float, Float, Float>, tiltRad: Double): Triple<Float, Float, Float> {
        val c = cos(tiltRad).toFloat()
        val s = sin(tiltRad).toFloat()
        val (x, y, z) = v
        return Triple(x, c * y - s * z, s * y + c * z)
    }

    @Test
    fun projectionIsInvariantAcrossMountingAngles() {
        val trueYawRate = 0.8f   // rad/s, a brisk but ordinary turn
        // World-frame: gravity along +z (phone flat, screen up), rotation about the same axis.
        val gyroFlat = Triple(0f, 0f, trueYawRate)
        val gravFlat = Triple(0f, 0f, g)

        for (tiltDeg in listOf(0.0, 30.0, 45.0, 70.0, 90.0, 180.0)) {
            val rad = Math.toRadians(tiltDeg)
            val (gx, gy, gz) = tiltAboutX(gyroFlat, rad)
            val (rx, ry, rz) = tiltAboutX(gravFlat, rad)
            val projected = YawRate.aboutVertical(gx, gy, gz, rx, ry, rz)
            assertTrue(
                abs(projected - trueYawRate) < 1e-4f,
                "tilt ${tiltDeg}deg: projected $projected, expected $trueYawRate",
            )
        }
    }

    @Test
    fun rawZAxisIsWhatWeAreReplacing() {
        // Documents the bug being fixed: at 70 degrees of tilt the raw z axis sees about a third
        // of the true turn rate, so a 90 degree corner integrates to roughly 31 degrees.
        val trueYawRate = 0.8f
        val rad = Math.toRadians(70.0)
        val (_, _, gz) = tiltAboutX(Triple(0f, 0f, trueYawRate), rad)
        assertTrue(abs(gz / trueYawRate - cos(rad).toFloat()) < 1e-4f)
        assertTrue(gz < 0.4f * trueYawRate, "raw gz should be badly attenuated at 70deg, was $gz")
    }

    @Test
    fun faceDownFlipsSignAutomatically() {
        // Screen-down: gravity points the other way, so the projection flips with it and the
        // heading convention survives without a special case.
        val trueYawRate = 0.8f
        val projected = YawRate.aboutVertical(0f, 0f, -trueYawRate, 0f, 0f, -g)
        assertEquals(trueYawRate, projected, 1e-4f)
    }

    @Test
    fun fallsBackToZAxisWhenGravityIsUnusable() {
        val projected = YawRate.aboutVertical(0f, 0f, 0.5f, 0f, 0f, 0f)
        assertEquals(0.5f, projected, 1e-6f)
    }
}
