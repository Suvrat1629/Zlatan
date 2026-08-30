package com.sih26168.idr.engine

import com.sih26168.idr.core.map.MapMatchResult
import com.sih26168.idr.core.map.MapMatcher
import com.sih26168.idr.core.model.ConstantSpeedEstimator
import com.sih26168.idr.core.nav.ErrorStateEkf
import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
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

    /** Always reports the same snapped point, on-road, with a fixed bearing. */
    private class FixedMatcher(
        private val snapTo: LatLon,
        private val bearingDeg: Double,
        override val emitsFusableCovariance: Boolean = true,
    ) : MapMatcher {
        override fun snap(rawPosition: LatLon) =
            MapMatchResult(snapTo, uncertaintyM = 3f, onRoad = true, roadBearingDeg = bearingDeg)
    }

    private fun engineWith(matcher: MapMatcher, fuse: Boolean, startAt: LatLon) = RealEngine(
        config = EngineConfig(outputRateHz = 10.0, useErrorStateEkf = true, useMapMatchFusion = fuse),
        speedEstimator = ConstantSpeedEstimator(5f),
        normalizer = identityNormalizer,
        startAt = startAt,
        fusionFilter = ErrorStateEkf(startAt),
        mapMatcher = matcher,
    )

    private fun RealEngine.drive(seconds: Int) {
        var t = 0L
        repeat(seconds * 100) { onImuSample3(imu(t)); t += 10_000_000L }
        tickOnce()
    }

    @Test
    fun mapMatchFusionOffLeavesTheDisplayOnlyPathUnchanged() {
        val start = LatLon(12.0, 77.0)
        val snapTo = Geo.stepForward(start, headingDeg = 90.0, forwardM = 25.0)
        val displayOnly = engineWith(FixedMatcher(snapTo, bearingDeg = 0.0), fuse = false, startAt = start)
            .also { it.drive(6) }
        // Twin engine with no matcher at all: its filter state is the "never fused" reference.
        val noMatcher = RealEngine(
            config = EngineConfig(outputRateHz = 10.0, useErrorStateEkf = true, useMapMatchFusion = false),
            speedEstimator = ConstantSpeedEstimator(5f),
            normalizer = identityNormalizer,
            startAt = start,
            fusionFilter = ErrorStateEkf(start),
        ).also { it.drive(6) }

        val s = displayOnly.state.value
        // 1. The published dot is exactly the matcher's snapped point.
        assertEquals(snapTo.lat, s.lat, 1e-9, "flag off must publish the raw snapped point")
        assertEquals(snapTo.lon, s.lon, 1e-9)
        // 2. The filter itself was never touched -- identical uncertainty to the no-matcher twin.
        assertEquals(
            noMatcher.state.value.uncertaintyM, s.uncertaintyM, 1e-4f,
            "flag off must not let the map match into the filter",
        )
    }

    @Test
    fun mapMatchFusionOnPullsTheEstimateNotJustTheDisplay() {
        val start = LatLon(12.0, 77.0)
        val snapTo = Geo.stepForward(start, headingDeg = 90.0, forwardM = 25.0)
        val off = engineWith(FixedMatcher(snapTo, 0.0), fuse = false, startAt = start).also { it.drive(6) }
        val on = engineWith(FixedMatcher(snapTo, 0.0), fuse = true, startAt = start).also { it.drive(6) }

        val offState = off.state.value
        val onState = on.state.value
        // Fusion on: the published point is the road-corrected filter estimate, not the raw
        // snap, so it differs from the flag-off published point.
        val delta = Geo.distanceM(LatLon(offState.lat, offState.lon), LatLon(onState.lat, onState.lon))
        assertTrue(delta > 0.5, "fusion should change the published position (delta=$delta m)")
        assertTrue(onState.uncertaintyM > 0f, "EKF should still report a real uncertainty")
    }

    @Test
    fun mapMatchFusionIsRefusedForAMatcherThatDoesNotEmitFusableCovariance() {
        val start = LatLon(12.0, 77.0)
        val snapTo = Geo.stepForward(start, headingDeg = 90.0, forwardM = 25.0)
        // Flag on, but the matcher is not fusable (a greedy snapper stand-in).
        val nonFusable = engineWith(
            FixedMatcher(snapTo, 0.0, emitsFusableCovariance = false), fuse = true, startAt = start,
        ).also { it.drive(6) }
        val displayOnly = engineWith(
            FixedMatcher(snapTo, 0.0, emitsFusableCovariance = true), fuse = false, startAt = start,
        ).also { it.drive(6) }

        // Should fall back to display-only: same published point, same filter uncertainty.
        assertEquals(displayOnly.state.value.lat, nonFusable.state.value.lat, 1e-9)
        assertEquals(displayOnly.state.value.uncertaintyM, nonFusable.state.value.uncertaintyM, 1e-4f)
    }

    private fun RealEngine.onImuSample3(s: ImuSampleRecord) =
        onImuSample(s.tNanos, s.ax, s.ay, s.az, s.grx, s.gry, s.grz, s.gx, s.gy, s.gz)
}
