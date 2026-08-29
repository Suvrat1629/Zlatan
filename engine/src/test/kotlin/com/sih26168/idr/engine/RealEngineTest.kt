package com.sih26168.idr.engine

import com.sih26168.idr.core.model.ConstantSpeedEstimator
import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.ImuSampleRecord
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealEngineTest {
    private val identityNormalizer = Normalizer(FloatArray(7), FloatArray(7) { 1f })

    private fun imu(tNanos: Long) = ImuSampleRecord(tNanos, 0f, 0f, 9.81f, 0f, 0f, 9.81f, 0f, 0f, 0f)

    private fun newEngine(startAt: LatLon = LatLon(0.0, 0.0)) = RealEngine(
        config = EngineConfig(outputRateHz = 10.0),
        speedEstimator = ConstantSpeedEstimator(5f),
        normalizer = identityNormalizer,
        startAt = startAt,
    )

    @Test
    fun coldStartPublishesInitModeBeforeFirstFullWindow() {
        val engine = newEngine()
        repeat(5) { engine.onImuSample3(imu(it * 10_000_000L)) }
        engine.tickOnce()
        assertEquals(Mode.INIT, engine.state.value.mode)
    }

    @Test
    fun reachesNavicModeThenFallsBackToDeadReckoningAfterGnssLost() {
        val engine = newEngine()
        var t = 0L
        val stepNs = 10_000_000L

        repeat(600) { i ->
            engine.onImuSample3(imu(t))
            if (i % 100 == 0) {
                engine.onGnssFix(
                    tNanos = t, lat = 0.0, lon = 0.0, speedMps = 5f, bearingDeg = 90f,
                    horizAccM = 5f, satsInFix = 6, irnssSatsInFix = 2,
                )
            }
            t += stepNs
        }
        engine.tickOnce()
        assertEquals(Mode.NAVIC, engine.state.value.mode)
        assertTrue(engine.state.value.speedMps in 0f..60f)

        engine.onGnssLost(t)
        repeat(50) {
            engine.onImuSample3(imu(t))
            t += stepNs
        }
        engine.tickOnce()

        val finalState = engine.state.value
        assertEquals(Mode.DEAD_RECKONING, finalState.mode, "should fall back once GNSS is lost")
        assertTrue(finalState.speedMps in 0f..60f, "speed should stay within the clamp range")
    }

    private fun RealEngine.onImuSample3(s: ImuSampleRecord) =
        onImuSample(s.tNanos, s.ax, s.ay, s.az, s.grx, s.gry, s.grz, s.gx, s.gy, s.gz)
}
