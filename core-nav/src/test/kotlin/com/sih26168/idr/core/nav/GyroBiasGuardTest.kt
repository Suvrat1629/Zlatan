package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gyro-bias state was not tracking a bias — it was absorbing heading error and re-injecting it
 * during the next blackout, with a sign that changed per session (TODO.md L11):
 *
 *   ride A: +0.38 deg/s -> heading drifted LEFT
 *   ride B: -0.43 deg/s -> heading drifted RIGHT, +1.55 deg/s of excess rotation
 *   ride C: excursion to -3.13 deg/s
 *
 * `dTheta = raw - biasZ * dt`, so a NEGATIVE estimate adds clockwise rotation. That single sign is
 * why the same defect was reported as a left bias one week and a right bias the next.
 *
 * These test the two guards directly rather than trying to reproduce the field absorption
 * synthetically. A constant course error lands on theta, not on the bias — the real mechanism
 * needed long blackouts and map-match pull to build up, and a test that faked it would be testing
 * the fake.
 */
class GyroBiasGuardTest {

    private val start = LatLon(12.9716, 77.5946)
    private val dt = 0.1
    private val config = EngineConfig.DEFAULT

    /** Feed [observedDps] as the rate seen while stationary — a direct bias observation. */
    private fun observeAtStop(ekf: ErrorStateEkf, observedDps: Double, ticks: Int) {
        repeat(ticks) {
            ekf.predict(start, 0f, 0.0, dt)
            ekf.updateStationaryGyro(Math.toRadians(observedDps).toFloat())
        }
    }

    @Test
    fun `the bias can never reach a value a gyroscope cannot have`() {
        // A consumer MEMS residual offset is a few tenths of a deg/s; the field saw -3.13. A stop
        // reporting 3 deg/s of rotation is far more likely a mis-detected stop than a real offset,
        // so clamping rather than believing it is the correct response.
        val ekf = ErrorStateEkf(start, config)
        observeAtStop(ekf, observedDps = -3.5, ticks = 3_000)
        assertTrue(
            abs(ekf.gyroBiasDps()) <= config.ekfMaxGyroBiasDps + 1e-6,
            "bias escaped its physical bound: ${ekf.gyroBiasDps()} deg/s",
        )
    }

    @Test
    fun `a bias confirmed at stops is learned and held`() {
        // The decay must not eat a real bias: a vehicle that keeps stopping keeps observing it.
        val ekf = ErrorStateEkf(start, config)
        observeAtStop(ekf, observedDps = 0.4, ticks = 4_000)
        assertTrue(
            abs(ekf.gyroBiasDps() - 0.4) < 0.15,
            "a directly observed bias must be learned and kept: got ${ekf.gyroBiasDps()}",
        )
    }

    @Test
    fun `an unobserved bias fades back toward the prior`() {
        val ekf = ErrorStateEkf(start, config)
        observeAtStop(ekf, observedDps = 0.4, ticks = 4_000)
        val learned = abs(ekf.gyroBiasDps())

        // Now drive without ever stopping. Nothing observes the bias directly, so the prior is the
        // better estimate and the state should return to it rather than persist into a blackout.
        var pos = start
        repeat(6_000) {                                  // 10 min, several half-lives
            pos = Geo.stepForward(pos, 0.0, 15f * dt)
            ekf.predict(pos, 15f, 0.0, dt)
        }
        assertTrue(
            abs(ekf.gyroBiasDps()) < learned * 0.5,
            "an unobserved bias should fade: $learned -> ${ekf.gyroBiasDps()} deg/s",
        )
    }

    @Test
    fun `the decay can be switched off, leaving only the bound`() {
        val noDecay = config.copy(ekfGyroBiasDecayHalfLifeSeconds = 0.0)
        val ekf = ErrorStateEkf(start, noDecay)
        observeAtStop(ekf, observedDps = 0.4, ticks = 4_000)
        val learned = abs(ekf.gyroBiasDps())
        var pos = start
        repeat(6_000) {
            pos = Geo.stepForward(pos, 0.0, 15f * dt)
            ekf.predict(pos, 15f, 0.0, dt)
        }
        assertTrue(abs(ekf.gyroBiasDps()) > learned * 0.9, "decay disabled should hold the value")
        assertTrue(abs(ekf.gyroBiasDps()) <= noDecay.ekfMaxGyroBiasDps + 1e-6)
    }
}
