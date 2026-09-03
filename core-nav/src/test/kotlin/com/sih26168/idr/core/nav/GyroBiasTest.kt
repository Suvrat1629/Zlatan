package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Gyro bias state (heading work plan F3).
 *
 * The existing outage tests inject a zero-mean random walk with no bias in it, so they measure
 * the *cost* of carrying the state — correctly, since estimating something that is not there can
 * only add variance. These tests measure the *benefit*: a synthetic constant yaw-rate bias, which
 * is the failure mode the state exists for and the one a real MEMS gyro actually has.
 */
class GyroBiasTest {

    private val start = LatLon(12.9716, 77.5946)
    private val speedMps = 15f
    private val dt = 0.1

    /**
     * Drive straight with a constant yaw-rate bias corrupting the heading input, feeding periodic
     * zero-velocity intervals, and report the filter's bias estimate and final position error.
     */
    private fun runWithBias(biasDps: Double, ticks: Int, zuptEveryTicks: Int): Pair<Double, Double> {
        val ekf = ErrorStateEkf(start, EngineConfig.DEFAULT)
        var truePos = start
        val trueHeadingDeg = 0.0
        var measuredHeadingDeg = 0.0

        for (i in 0 until ticks) {
            val stationary = zuptEveryTicks > 0 && (i % zuptEveryTicks) < 20
            val v = if (stationary) 0f else speedMps
            if (!stationary) truePos = Geo.stepForward(truePos, trueHeadingDeg, v * dt)

            // The gyroscope reports the true rate plus its bias; heading is that, integrated.
            measuredHeadingDeg += biasDps * dt
            ekf.predict(truePos, v, measuredHeadingDeg, dt)
            // A stationary vehicle cannot rotate, so the measured rate IS the bias.
            if (stationary) ekf.updateStationaryGyro(Math.toRadians(biasDps).toFloat())
        }
        return ekf.gyroBiasDps() to Geo.distanceM(truePos, ekf.estimate())
    }

    @Test
    fun zuptObservesTheBiasDirectly() {
        val trueBiasDps = 0.2
        val (estimated, _) = runWithBias(trueBiasDps, ticks = 1200, zuptEveryTicks = 200)
        assertTrue(
            abs(estimated - trueBiasDps) < 0.05,
            "ZUPT should recover the bias: estimated ${"%.4f".format(estimated)} deg/s, true $trueBiasDps",
        )
    }

    @Test
    fun estimatingTheBiasReducesDriftVersusIgnoringIt() {
        val trueBiasDps = 0.2
        val (_, withZupt) = runWithBias(trueBiasDps, ticks = 1200, zuptEveryTicks = 200)
        val (_, withoutZupt) = runWithBias(trueBiasDps, ticks = 1200, zuptEveryTicks = 0)
        assertTrue(
            withZupt < withoutZupt,
            "observing the bias should beat not observing it: $withZupt m vs $withoutZupt m",
        )
    }

    @Test
    fun biasStateStaysPutWhenThereIsNoBias() {
        // The failure mode the tightened priors exist to prevent: with no real bias present the
        // state must not wander off and start subtracting rotation that was never there.
        val (estimated, _) = runWithBias(0.0, ticks = 1200, zuptEveryTicks = 200)
        assertTrue(
            abs(estimated) < 0.05,
            "bias state should stay near zero with no bias present, was ${"%.4f".format(estimated)} deg/s",
        )
    }
}
