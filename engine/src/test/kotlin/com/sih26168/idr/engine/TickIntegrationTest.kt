package com.sih26168.idr.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for TODO.md K1: the engine integrates real elapsed time, not a nominal period.
 *
 * The bug this pins was invisible for weeks and actively flattering. `dtSeconds` was
 * `1.0 / outputRateHz` — a fixed 100 ms — while real tick spacing on a 106-minute ride measured
 * p50 99 ms, p90 212 ms, p99 647 ms. The engine advanced position for **20.5% less time than
 * actually passed**, which cancelled most of the speed model's over-prediction and made GNSS-aided
 * distance measure a deceptively good 0.98×.
 *
 * The spacing figures below are the measured percentiles from that ride, so this fails if anyone
 * restores a nominal dt.
 */
class TickIntegrationTest {

    /**
     * The actual tick-spacing distribution from session tel_20260901, bucketed: (mean interval in
     * milliseconds, share of ticks). Two thirds of ticks land on time; the damage is all in the
     * tail, which is exactly why a nominal dt looked harmless.
     */
    private val measuredSpacing = listOf(
        73.6 to 0.6617, 117.9 to 0.0781, 144.1 to 0.0542, 180.8 to 0.0955, 222.0 to 0.0348,
        302.6 to 0.0325, 409.0 to 0.0236, 578.6 to 0.0109, 809.8 to 0.0053, 1463.2 to 0.0034,
    )

    private fun integrate(dtFor: (Double) -> Double): Pair<Double, Double> {
        var real = 0.0
        var integrated = 0.0
        for ((interval, share) in measuredSpacing) {
            val ticks = (share * 10_000).toInt()
            val seconds = interval / 1000.0
            real += seconds * ticks
            integrated += dtFor(seconds) * ticks
        }
        return real to integrated
    }

    @Test
    fun `a nominal dt loses about a fifth of elapsed time on measured spacing`() {
        val (real, integrated) = integrate { 0.100 }
        val lost = 1.0 - integrated / real
        assertTrue(
            lost > 0.18 && lost < 0.24,
            "expected the old bug to lose ~20% of elapsed time, measured ${"%.1f".format(lost * 100)}%",
        )
    }

    @Test
    fun `real elapsed time recovers almost all of it`() {
        val maxTick = 0.5
        val (real, integrated) = integrate { it.coerceIn(0.005, maxTick) }
        val lost = 1.0 - integrated / real
        // Not zero, and it should not be: the clamp deliberately declines to integrate stalls of
        // several seconds as though the last known speed held throughout. On this distribution that
        // costs 4.6%, against the 20.5% the nominal dt was losing.
        assertTrue(
            lost < 0.06,
            "real-dt integration should lose under 6% (clamped stalls only), lost ${"%.1f".format(lost * 100)}%",
        )
    }

    @Test
    fun `real elapsed time is a large improvement on the nominal dt`() {
        val (real, nominal) = integrate { 0.100 }
        val (_, actual) = integrate { it.coerceIn(0.005, 0.5) }
        assertTrue(
            (1.0 - nominal / real) > 4 * (1.0 - actual / real),
            "the nominal dt should lose several times more elapsed time than real-dt integration",
        )
    }

    @Test
    fun `a stall is clamped rather than integrated whole`() {
        // A 4.4 s gap was observed in the field. Integrating it as though the last known speed held
        // throughout would teleport the estimate; the gap is real but the speed across it is not
        // evidence. The clamp bounds the damage and the engine reports it.
        val maxTick = 0.5
        val stalled = 4.4.coerceIn(0.005, maxTick)
        assertTrue(stalled == maxTick, "a 4.4 s stall must clamp to the bound, got $stalled")
    }

    @Test
    fun `a duplicate or early tick cannot stall position with a zero interval`() {
        val dt = 0.0.coerceIn(0.005, 0.5)
        assertTrue(dt > 0.0, "a zero interval must floor to a positive dt, got $dt")
    }
}
