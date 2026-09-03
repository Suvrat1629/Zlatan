package com.sih26168.idr.engine

import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.TelemetryTick
import com.sih26168.idr.core.types.VehicleMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GNSS-derived references must only score the estimate while GNSS is actually being received
 * (TODO.md L5).
 *
 * `vGnssMps` and `gnssBearingDeg` hold the LAST trusted values, so during a blackout they are frozen
 * at whatever they were when the fix was lost. Scoring against them measures how far the vehicle has
 * moved since — not how wrong the estimate is — and it does so in the direction that makes a long
 * outage look like an estimator failure.
 *
 * Measured on session 20260902_123708 (77% muted): the summary reported heading error of 18.05 deg
 * median where the true GNSS-aided figure was 2.8 deg. This is the third metric in this project
 * found to be contaminated by the failure it exists to measure, after the drift denominator and the
 * over-travel ratio.
 */
class DiagnosticsStaleReferenceTest {

    private fun tick(mode: Mode, headingDeg: Float, bearingDeg: Float) = TelemetryTick(
        tNanos = 0, vModelMps = 8f, dvMps2 = Float.NaN, vOutMps = 8f, vGnssMps = 8f,
        blendLambda = 0f, yawRateRadS = 0f, headingDeg = headingDeg, gnssBearingDeg = bearingDeg,
        aHorizMps2 = 0f, stationary = false, tickIntervalMs = 100f, tiltRateRadS = 0f,
        handling = false, vehicleMode = VehicleMode.BIKE, gnssMuted = mode == Mode.DEAD_RECKONING,
        mode = mode, satsInFix = 8, irnssSatsInFix = 0, lat = 0.0, lon = 0.0,
        gnssLat = 0.0, gnssLon = 0.0, uncertaintyM = 1f, gyroBiasDps = Float.NaN,
        headingUncertaintyDeg = Float.NaN, gnssNis = Float.NaN, yawClampCount = 0,
        mapMatchOnRoad = false, mapMatchUncertaintyM = Float.NaN,
        inferenceMs = 1f, tickMs = 5f,
    )

    private fun diag() = Diagnostics(
        sessionId = "t", deviceModel = "t", appVersion = "t", modelVersion = "t",
    )

    @Test
    fun `heading is scored while GNSS is live`() {
        val d = diag()
        repeat(20) { d.onTick(tick(Mode.GNSS, headingDeg = 93f, bearingDeg = 90f)) }
        assertTrue(
            d.summary().headingErrorMedianDeg in 2.0..4.0,
            "expected ~3 deg, got ${d.summary().headingErrorMedianDeg}",
        )
    }

    @Test
    fun `a stale bearing during a blackout is not scored as heading error`() {
        val d = diag()
        repeat(20) { d.onTick(tick(Mode.GNSS, headingDeg = 93f, bearingDeg = 90f)) }
        // Now the vehicle turns through 90 degrees with GNSS gone. The bearing is frozen, so the
        // gap grows to 90 degrees — none of which is estimator error.
        repeat(200) { d.onTick(tick(Mode.DEAD_RECKONING, headingDeg = 180f, bearingDeg = 90f)) }
        assertTrue(
            d.summary().headingErrorMedianDeg in 2.0..4.0,
            "blackout ticks contaminated the metric: ${d.summary().headingErrorMedianDeg} deg " +
                "(this is the 18.05-vs-2.8 bug)",
        )
    }

    @Test
    fun `NAVIC counts as live, since a NavIC-aided fix is still a fix`() {
        val d = diag()
        repeat(20) { d.onTick(tick(Mode.NAVIC, headingDeg = 95f, bearingDeg = 90f)) }
        assertTrue(d.summary().headingErrorMedianDeg in 4.0..6.0)
    }
}
