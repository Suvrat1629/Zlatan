package com.sih26168.idr.core.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forward axis is the missing half of alignment (TODO.md K6). [YawRate.aboutVertical] made
 * rotation mount-agnostic; this makes longitudinal acceleration mount-agnostic AND signed, which is
 * what the delta model needs and never had.
 *
 * The property that has to hold is the same one the yaw projection has: it must work at any mounting
 * orientation, because the phone's frame is not the vehicle's and we never get to choose it.
 */
class ForwardAxisTest {

    private val g = 9.81f

    /** Gravity for a device tilted [tiltDeg] and rolled [rollDeg]. */
    private fun gravityAt(tiltDeg: Double, rollDeg: Double): FloatArray {
        val t = Math.toRadians(tiltDeg); val r = Math.toRadians(rollDeg)
        return floatArrayOf(
            (g * sin(r) * cos(t)).toFloat(), (g * sin(t)).toFloat(), (g * cos(r) * cos(t)).toFloat(),
        )
    }

    /** Two orthonormal horizontal axes for a given gravity vector. */
    private fun horizontalBasis(grav: FloatArray): Pair<FloatArray, FloatArray> {
        val m = sqrt(grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2])
        val u = floatArrayOf(grav[0] / m, grav[1] / m, grav[2] / m)
        val seed = when {
            abs(u[0]) < abs(u[1]) && abs(u[0]) < abs(u[2]) -> floatArrayOf(1f, 0f, 0f)
            abs(u[1]) < abs(u[2]) -> floatArrayOf(0f, 1f, 0f)
            else -> floatArrayOf(0f, 0f, 1f)
        }
        var e1 = floatArrayOf(
            u[1] * seed[2] - u[2] * seed[1], u[2] * seed[0] - u[0] * seed[2], u[0] * seed[1] - u[1] * seed[0],
        )
        val e1m = sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2])
        e1 = floatArrayOf(e1[0] / e1m, e1[1] / e1m, e1[2] / e1m)
        val e2 = floatArrayOf(
            u[1] * e1[2] - u[2] * e1[1], u[2] * e1[0] - u[0] * e1[2], u[0] * e1[1] - u[1] * e1[0],
        )
        return e1 to e2
    }

    /**
     * A synthetic drive: longitudinal acceleration along `fwd` with realistic asymmetry — gentle
     * under power, hard under braking — plus a little lateral noise.
     */
    private fun drive(fwd: FloatArray, lat: FloatArray, n: Int = 200, seed: Int = 7): FloatArray {
        val rnd = java.util.Random(seed.toLong())
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            // 80% mild acceleration, 20% hard braking: the negative skew the sign test relies on.
            val a = if (rnd.nextDouble() < 0.8) rnd.nextDouble() * 1.5 else -(3.0 + rnd.nextDouble() * 4.0)
            val s = rnd.nextGaussian() * 0.25
            out[3 * i] = (a * fwd[0] + s * lat[0]).toFloat()
            out[3 * i + 1] = (a * fwd[1] + s * lat[1]).toFloat()
            out[3 * i + 2] = (a * fwd[2] + s * lat[2]).toFloat()
        }
        return out
    }

    @Test
    fun `recovers the forward axis at every mounting orientation`() {
        for (tilt in listOf(0.0, 20.0, 45.0, 70.0)) {
            for (roll in listOf(0.0, 60.0, 150.0, 250.0)) {
                val grav = gravityAt(tilt, roll)
                val (e1, e2) = horizontalBasis(grav)
                // True forward: an arbitrary horizontal direction, 35 degrees off the basis.
                val c = cos(Math.toRadians(35.0)).toFloat(); val s = sin(Math.toRadians(35.0)).toFloat()
                val fwd = floatArrayOf(c * e1[0] + s * e2[0], c * e1[1] + s * e2[1], c * e1[2] + s * e2[2])
                val lat = floatArrayOf(-s * e1[0] + c * e2[0], -s * e1[1] + c * e2[1], -s * e1[2] + c * e2[2])

                val axis = ForwardAxis.estimate(drive(fwd, lat), grav)
                assertNotNull(axis, "tilt=$tilt roll=$roll: no axis recovered")
                val dot = axis.x * fwd[0] + axis.y * fwd[1] + axis.z * fwd[2]
                assertTrue(
                    dot > 0.95f,
                    "tilt=$tilt roll=$roll: recovered axis off by ${Math.toDegrees(Math.acos(dot.toDouble()))} deg",
                )
            }
        }
    }

    @Test
    fun `braking reads negative and acceleration positive`() {
        // The whole point of the exercise: a_horiz as a magnitude cannot express this, which is why
        // the delta model returned +0.30 m/s2 for a hard-braking window.
        val grav = gravityAt(30.0, 40.0)
        val (e1, e2) = horizontalBasis(grav)
        val fwd = e1
        val axis = ForwardAxis.estimate(drive(fwd, e2), grav)
        assertNotNull(axis)
        val accel = ForwardAxis.longitudinal(2f * fwd[0], 2f * fwd[1], 2f * fwd[2], axis)
        val brake = ForwardAxis.longitudinal(-3f * fwd[0], -3f * fwd[1], -3f * fwd[2], axis)
        assertTrue(accel > 1.5f, "acceleration should read positive, got $accel")
        assertTrue(brake < -2.0f, "braking should read negative, got $brake")
    }

    @Test
    fun `the axis is orthogonal to gravity, so it cannot absorb vertical motion`() {
        val grav = gravityAt(55.0, 110.0)
        val (e1, e2) = horizontalBasis(grav)
        val axis = ForwardAxis.estimate(drive(e1, e2), grav)
        assertNotNull(axis)
        val m = sqrt(grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2])
        val dot = (axis.x * grav[0] + axis.y * grav[1] + axis.z * grav[2]) / m
        assertTrue(abs(dot) < 1e-3f, "axis leaked into the vertical: $dot")
    }

    @Test
    fun `refuses an isotropic window instead of guessing`() {
        // Cruising at constant speed on a straight road: no longitudinal signal, so the eigenvector
        // is noise. A held stale axis beats a freshly-spinning one.
        val grav = gravityAt(15.0, 0.0)
        val rnd = java.util.Random(3)
        val round = FloatArray(300 * 3) { (rnd.nextGaussian() * 0.2).toFloat() }
        assertNull(ForwardAxis.estimate(round, grav), "an isotropic cloud must not yield an axis")
    }

    @Test
    fun `refuses degenerate input rather than returning something unusable`() {
        val grav = gravityAt(0.0, 0.0)
        assertNull(ForwardAxis.estimate(FloatArray(9), floatArrayOf(0f, 0f, 0f)), "no gravity")
        assertNull(ForwardAxis.estimate(FloatArray(6), grav), "too few samples")
    }
}
