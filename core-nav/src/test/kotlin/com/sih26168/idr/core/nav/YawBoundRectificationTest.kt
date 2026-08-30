package com.sih26168.idr.core.nav

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the physical yaw bound can and cannot do about shake, TODO.md G4.
 *
 * **This test falsified the reasoning that motivated it, and the record is kept deliberately.**
 * The original claim was that the bound rectified a shake because it CLAMPED, and that rejecting
 * out-of-bound samples instead would restore the cancellation. The first version of
 * `rejectingOutOfBoundSamples...` failed: on an asymmetric shake, rejection produced 300 degrees of
 * false rotation against clamping's 255. Discarding the fast out-swing entirely leaves the slow
 * return swing standing on its own, which is worse than keeping a truncated piece of it.
 *
 * The correct statement is stronger and less convenient: **no per-sample transform of the yaw rate
 * can fix a sustained shake.** The out-swing and the return swing land in different engine ticks,
 * so any nonlinearity applied sample-by-sample breaks their cancellation, whichever way it leans.
 * The only thing that works is discarding the whole episode — which is what the handling detector
 * does, and is why G1 is the fix here and this bound is not.
 *
 * The bound is still worth having, and rejection is still the right strategy for it, but for a
 * different job: an ISOLATED out-of-bound spike, with no compensating swing to preserve. There,
 * clamping injects false rotation and rejection injects none. That is what these tests now assert.
 */
class YawBoundRectificationTest {

    private val boundRadS = Math.toRadians(90.0).toFloat()

    private fun clampStrategy(rate: Float) = rate.coerceIn(-boundRadS, boundRadS)
    private fun rejectStrategy(rate: Float) = if (abs(rate) > boundRadS) 0f else rate

    private fun integrate(samples: List<Pair<Float, Double>>, strategy: (Float) -> Float): Double =
        samples.sumOf { (rate, dt) -> strategy(rate) * dt }

    /**
     * One shake cycle: a fast out-swing above the bound, then a slow return through the SAME total
     * angle below it. Net rotation is zero by construction.
     */
    private fun shakeCycle(): List<Pair<Float, Double>> {
        val fastRate = Math.toRadians(600.0).toFloat()
        val fastDt = 0.05
        val angle = fastRate * fastDt
        val slowDt = 0.45
        val slowRate = (-angle / slowDt).toFloat()
        return buildList {
            repeat(5) { add(fastRate to (fastDt / 5)) }
            repeat(45) { add(slowRate to (slowDt / 45)) }
        }
    }

    @Test
    fun `the raw shake integrates to zero`() {
        assertTrue(abs(integrate(shakeCycle()) { it }) < 1e-4)
    }

    @Test
    fun `neither clamping nor rejecting saves a sustained asymmetric shake`() {
        // The finding this test exists to record. Both strategies produce hundreds of degrees of
        // false rotation from a shake that is zero-mean in angle, because the compensating swing
        // is spread across later samples that the transform treats differently. Anyone tempted to
        // solve shake by adjusting this bound should read the numbers here first.
        val samples = (1..10).flatMap { shakeCycle() }
        val clamped = abs(Math.toDegrees(integrate(samples, ::clampStrategy)))
        val rejected = abs(Math.toDegrees(integrate(samples, ::rejectStrategy)))
        assertTrue(clamped > 100.0, "clamping produced only $clamped deg — fixture has drifted")
        assertTrue(rejected > 100.0, "rejection produced only $rejected deg — fixture has drifted")
    }

    @Test
    fun `on an isolated spike rejection injects no false rotation and clamping does`() {
        // The job the bound actually has, once the handling detector owns sustained shake: a single
        // out-of-bound sample — a sensor glitch, a knock — with nothing to compensate it. Here the
        // two strategies genuinely differ, and rejection is unambiguously right.
        val spike = Math.toRadians(600.0).toFloat()
        val samples = listOf(spike to 0.02)
        val clamped = Math.toDegrees(integrate(samples, ::clampStrategy))
        val rejected = Math.toDegrees(integrate(samples, ::rejectStrategy))
        assertTrue(abs(rejected) < 1e-9, "rejection injected $rejected deg from an isolated spike")
        assertTrue(abs(clamped) > 1.0, "clamping should inject the bound's worth: $clamped deg")
    }

    @Test
    fun `freezing the whole episode is what actually cancels a shake`() {
        // The handling detector's contribution, simulated at the engine's tick granularity: the
        // bound rejects the out-of-bound samples in the first tick, and the gate freezes heading
        // integration for every tick after that, so the return swing never lands either.
        val samples = (1..10).flatMap { shakeCycle() }
        val tickSeconds = 0.1
        var elapsed = 0.0
        var net = 0.0
        for ((rate, dt) in samples) {
            val bounded = rejectStrategy(rate)
            // Handling is detected from the first tick boundary onward: a shake this violent is far
            // above the tilt threshold, so only the opening tick integrates at all.
            val frozen = elapsed >= tickSeconds
            if (!frozen) net += bounded * dt
            elapsed += dt
        }
        val netDeg = abs(Math.toDegrees(net))
        assertTrue(
            netDeg < 10.0,
            "bound plus handling freeze should leave under 10 deg of residual, got $netDeg — " +
                "compare the hundreds of degrees either bound strategy leaves on its own",
        )
    }

    @Test
    fun `a genuine vehicle turn is untouched by either strategy`() {
        val rate = Math.toRadians(45.0).toFloat()
        val samples = (1..20).map { rate to 0.1 }
        assertTrue(abs(Math.toDegrees(integrate(samples, ::rejectStrategy)) - 90.0) < 0.5)
        assertTrue(abs(Math.toDegrees(integrate(samples, ::clampStrategy)) - 90.0) < 0.5)
    }
}
