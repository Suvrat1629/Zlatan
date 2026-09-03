package com.sih26168.idr.engine

import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.TelemetryTick
import com.sih26168.idr.core.types.VehicleMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards against the measurement biases found across this project (TODO.md L7).
 *
 * Five metrics were found reporting numbers kinder than reality, all by the same mechanism: the
 * reference degraded in the same conditions as the estimate, or the sample excluded the failures.
 * These tests pin the three that live in Diagnostics.
 */
class MeasurementBiasTest {

    private var clock = 0L

    private fun tick(
        mode: Mode = Mode.GNSS, vGnss: Float = 8f, vModel: Float = 8f, clamps: Long = 0,
    ): TelemetryTick {
        clock += 100_000_000
        return TelemetryTick(
            tNanos = clock, vModelMps = vModel, dvMps2 = Float.NaN, vOutMps = vGnss,
            vGnssMps = vGnss, blendLambda = 0f, yawRateRadS = 0f, headingDeg = 90f,
            gnssBearingDeg = 90f, aHorizMps2 = 0f, stationary = false, tickIntervalMs = 100f,
            tiltRateRadS = 0f, handling = false, vehicleMode = VehicleMode.BIKE,
            gnssMuted = mode == Mode.DEAD_RECKONING, mode = mode, satsInFix = 8, irnssSatsInFix = 0,
            lat = 12.9716, lon = 77.5946, gnssLat = 12.9716, gnssLon = 77.5946,
            uncertaintyM = 1f, gyroBiasDps = Float.NaN, headingUncertaintyDeg = Float.NaN,
            gnssNis = Float.NaN, yawClampCount = clamps, mapMatchOnRoad = false,
            mapMatchUncertaintyM = Float.NaN, inferenceMs = 1f, tickMs = 5f,
        )
    }

    private fun diag() = Diagnostics("t", "t", "t", "t")

    @Test
    fun `yaw clamps are counted per session, not since the app started`() {
        // The engine's counter is monotonic from construction; a Diagnostics covers one recording.
        // Session 20260902_123708 reported 18,670 where 31 occurred during the recording.
        val d = diag()
        repeat(10) { d.onTick(tick(clamps = 18_639)) }
        repeat(10) { d.onTick(tick(clamps = 18_670)) }
        assertEquals(31L, d.summary().yawClampCount, "must report the session delta, not the total")
    }

    @Test
    fun `an outage that never reacquires is still recorded`() {
        // Unclosed outages are by definition the longest, so dropping them removed the worst tail
        // of the drift distribution. 22 blackouts, 21 reported.
        val d = diag()
        d.onTick(tick(mode = Mode.GNSS))
        repeat(50) { d.onTick(tick(mode = Mode.DEAD_RECKONING)) }
        assertTrue(d.summary().outages.isEmpty(), "still open, nothing to report yet")
        d.closeOpenOutage(clock)
        assertEquals(1, d.summary().outages.size, "a session ending mid-blackout must report it")
    }

    @Test
    fun `an outage with no reacquisition reports no drift rather than a fabricated one`() {
        val d = diag()
        d.onTick(tick(mode = Mode.GNSS))
        repeat(50) { d.onTick(tick(mode = Mode.DEAD_RECKONING)) }
        d.closeOpenOutage(clock)
        val o = d.summary().outages.single()
        assertTrue(o.errorM.isNaN(), "no reacquisition fix means no error is measurable")
        assertTrue(o.durationSeconds > 0.0, "but the outage itself is real and must be reported")
    }

    @Test
    fun `low-speed error is reported even though the headline ratio excludes it`() {
        // The 3 m/s floor is correct for a ratio and wrong as a summary: it hid 26% of one ride,
        // and precisely the band where phantom speed lives.
        val d = diag()
        repeat(20) { d.onTick(tick(vGnss = 8f, vModel = 9f)) }
        repeat(20) { d.onTick(tick(vGnss = 0.2f, vModel = 4f)) }
        val s = d.summary()
        assertEquals(20L, s.lowSpeedPairs)
        assertTrue(
            s.lowSpeedResidualMedianMps > 3.0,
            "a 3.8 m/s phantom must surface, got ${s.lowSpeedResidualMedianMps}",
        )
    }
}
