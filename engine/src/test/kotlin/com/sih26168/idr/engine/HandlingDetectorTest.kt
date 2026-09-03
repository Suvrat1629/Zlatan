package com.sih26168.idr.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * The property that makes the handling gate safe is that it reads the gyro component ORTHOGONAL to
 * gravity, while vehicle yaw lives entirely in the parallel component. These tests assert that
 * separation directly, at arbitrary mounting angles, because it is the whole argument for why a
 * detector that can zero the speed output cannot fire on a sharp turn.
 */
class HandlingDetectorTest {

    private val g = 9.81f

    /** Gravity as seen by a device tilted [tiltDeg] about its x axis and rolled [rollDeg]. */
    private fun gravityAt(tiltDeg: Double, rollDeg: Double): Triple<Float, Float, Float> {
        val t = Math.toRadians(tiltDeg); val r = Math.toRadians(rollDeg)
        return Triple(
            (g * sin(r) * cos(t)).toFloat(),
            (g * sin(t)).toFloat(),
            (g * cos(r) * cos(t)).toFloat(),
        )
    }

    @Test
    fun `pure vehicle yaw produces zero tilt rate at every mounting angle`() {
        // A vehicle turn is rotation about the local vertical. Whatever the phone's orientation,
        // that rotation must appear entirely in the yaw projection and not at all here -- otherwise
        // the gate could suppress a genuine turn, which is the one thing it must never do.
        val yawRateRadS = 1.2f // ~69 deg/s: a hard turn, well past anything a car sustains
        for (tilt in listOf(0.0, 15.0, 30.0, 55.0, 70.0, 89.0)) {
            for (roll in listOf(0.0, 40.0, 120.0, 250.0)) {
                val (grx, gry, grz) = gravityAt(tilt, roll)
                val mag = kotlin.math.sqrt(grx * grx + gry * gry + grz * grz)
                // Rotation about the vertical, expressed in device axes.
                val gx = yawRateRadS * grx / mag
                val gy = yawRateRadS * gry / mag
                val gz = yawRateRadS * grz / mag
                val tiltRate = HandlingDetector.tiltRate(gx, gy, gz, grx, gry, grz)
                assertTrue(
                    tiltRate < 1e-4f,
                    "tilt=$tilt roll=$roll: pure yaw leaked $tiltRate rad/s into the tilt channel",
                )
                assertFalse(HandlingDetector.isHandling(tiltRate, 0.44f))
            }
        }
    }

    @Test
    fun `rotation about a horizontal axis is reported in full`() {
        // The complementary half: horizontal-axis rotation must be measured at full magnitude,
        // again independent of how the phone is mounted.
        val rate = 2.0f
        for (tilt in listOf(0.0, 30.0, 70.0)) {
            for (roll in listOf(0.0, 90.0, 200.0)) {
                val (grx, gry, grz) = gravityAt(tilt, roll)
                val mag = kotlin.math.sqrt(grx * grx + gry * gry + grz * grz)
                val ux = grx / mag; val uy = gry / mag; val uz = grz / mag
                // Any vector orthogonal to gravity: cross(gravity, an arbitrary non-parallel axis).
                var px = uy * 0f - uz * 1f
                var py = uz * 0f - ux * 0f
                var pz = ux * 1f - uy * 0f
                val pm = kotlin.math.sqrt(px * px + py * py + pz * pz)
                px /= pm; py /= pm; pz /= pm
                val tiltRate = HandlingDetector.tiltRate(rate * px, rate * py, rate * pz, grx, gry, grz)
                assertEquals(rate, tiltRate, 1e-3f, "tilt=$tilt roll=$roll")
                assertTrue(HandlingDetector.isHandling(tiltRate, 0.44f))
            }
        }
    }

    @Test
    fun `a hard turn combined with road vibration stays under the threshold`() {
        // The realistic false-positive risk: a genuine sharp turn plus the small horizontal-axis
        // rotation a car body actually produces. This must not read as handling.
        val (grx, gry, grz) = gravityAt(30.0, 20.0)
        val mag = kotlin.math.sqrt(grx * grx + gry * gry + grz * grz)
        val yaw = 1.0f // 57 deg/s
        val bodyRoll = 0.10f // ~6 deg/s of pitch/roll, generous for a car
        val gx = yaw * grx / mag + bodyRoll
        val gy = yaw * gry / mag
        val gz = yaw * grz / mag
        val tiltRate = HandlingDetector.tiltRate(gx, gy, gz, grx, gry, grz)
        assertTrue(tiltRate < 0.44f, "hard turn + body roll read as handling: $tiltRate rad/s")
    }

    @Test
    fun `unusable gravity falls back to full gyro magnitude rather than claiming yaw`() {
        // Without a reference axis we cannot tell yaw from tilt. The conservative answer is to
        // report the rotation, not to assume it is vehicle yaw and discard it.
        val r = HandlingDetector.tiltRate(0.3f, 0.4f, 0f, 0f, 0f, 0f)
        assertEquals(0.5f, r, 1e-5f)
    }

    @Test
    fun `shaking sweeps well past the threshold across the rotation sweep`() {
        // A shake is large rotation with no consistent axis. Sampled around a full circle in the
        // horizontal plane so the result cannot depend on which way the phone happened to be held.
        val (grx, gry, grz) = gravityAt(45.0, 10.0)
        val mag = kotlin.math.sqrt(grx * grx + gry * gry + grz * grz)
        val ux = grx / mag; val uy = gry / mag; val uz = grz / mag
        var minSeen = Float.MAX_VALUE
        for (i in 0 until 36) {
            val a = 2 * PI * i / 36
            // An axis orthogonal to gravity, swept through the horizontal plane.
            var ax = (cos(a) * uy - sin(a) * uz).toFloat()
            var ay = (sin(a) * uz - cos(a) * ux).toFloat()
            var az = (cos(a) * ux - sin(a) * uy).toFloat()
            val am = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
            if (am < 1e-6f) continue
            ax /= am; ay /= am; az /= am
            val rate = 3.0f // ~172 deg/s, the order the field session recorded while walking
            val tiltRate = HandlingDetector.tiltRate(rate * ax, rate * ay, rate * az, grx, gry, grz)
            minSeen = minOf(minSeen, tiltRate)
        }
        assertTrue(minSeen > 0.44f, "worst-case shake orientation read only $minSeen rad/s")
    }
}

/**
 * The coast bound is per-EPISODE, not per unbroken run of ticks.
 *
 * This models the state machine in `RealEngine.tickOnce` directly, because the bug it guards
 * against was written and caught in review rather than in the field: clearing `handling` on expiry
 * made the next tick look like a fresh episode, which restarted the timer, which re-entered
 * coasting — an oscillation that would have held a stale speed indefinitely, in limit-length
 * bursts, while reporting that it had stopped.
 */
class HandlingCoastBoundTest {

    private class Gate(private val maxCoastSeconds: Double) {
        private var sinceNanos = 0L
        private var expired = false
        var handling = false; private set

        fun tick(tNanos: Long, handlingNow: Boolean) {
            if (!handlingNow) { sinceNanos = 0L; expired = false }
            else if (sinceNanos == 0L) sinceNanos = tNanos
            val coastSeconds = if (sinceNanos == 0L) 0.0 else (tNanos - sinceNanos) / 1e9
            if (handlingNow && coastSeconds > maxCoastSeconds) expired = true
            handling = handlingNow && !expired
        }
    }

    @Test
    fun `coasting stops once and stays stopped while handling persists`() {
        val gate = Gate(maxCoastSeconds = 10.0)
        var t = 0L
        val step = 100_000_000L // 10 Hz
        var coastingTicks = 0
        // Thirty seconds of continuous handling — three times the bound.
        repeat(300) {
            gate.tick(t, handlingNow = true)
            if (gate.handling) coastingTicks++
            t += step
        }
        // Ten seconds at 10 Hz, give or take the tick the bound is crossed on.
        kotlin.test.assertTrue(
            coastingTicks in 99..102,
            "expected coasting to stop after the bound and stay stopped, got $coastingTicks ticks",
        )
    }

    @Test
    fun `a new episode gets a full coast allowance`() {
        val gate = Gate(maxCoastSeconds = 10.0)
        var t = 0L
        val step = 100_000_000L
        repeat(300) { gate.tick(t, handlingNow = true); t += step }
        kotlin.test.assertFalse(gate.handling, "first episode should have expired")
        // Device set down: one quiet tick ends the episode.
        gate.tick(t, handlingNow = false); t += step
        // Picked up again — this is a new episode and must coast again.
        gate.tick(t, handlingNow = true)
        kotlin.test.assertTrue(gate.handling, "a fresh episode should coast, not inherit the expiry")
    }
}
