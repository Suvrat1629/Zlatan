package com.sih26168.idr.engine

import com.sih26168.idr.core.model.SpeedEstimator
import com.sih26168.idr.core.nav.DeadReckoner
import com.sih26168.idr.core.nav.FusionFilter
import com.sih26168.idr.core.nav.GyroIntegrationHeadingEstimator
import com.sih26168.idr.core.nav.HeadingEstimator
import com.sih26168.idr.core.nav.ModeArbiter
import com.sih26168.idr.core.nav.PassthroughFusionFilter
import com.sih26168.idr.core.map.MapMatcher
import com.sih26168.idr.core.map.NoOpMapMatcher
import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.GnssFixRecord
import com.sih26168.idr.core.types.ImuSampleRecord
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.PositionState
import com.sih26168.idr.core.types.PositioningEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RealEngine(
    private val config: EngineConfig,
    private val speedEstimator: SpeedEstimator,
    private val normalizer: Normalizer,
    startAt: LatLon,
    private val headingEstimator: HeadingEstimator = GyroIntegrationHeadingEstimator(),
    private val fusionFilter: FusionFilter = PassthroughFusionFilter(startAt),
    private val mapMatcher: MapMatcher = NoOpMapMatcher(),
    // Optional delta-speed model enabling the time-varying blend (see tickOnce). When null,
    // the engine publishes the absolute model's speed exactly as before.
    private val deltaEstimator: SpeedEstimator? = null,
    private val deltaNormalizer: Normalizer? = null,

    ringBufferCapacitySamples: Int = 4000,
) : PositioningEngine {

    private val ringBuffer = RingBuffer(ringBufferCapacitySamples)
    private val conditioning = ConditioningStage()
    private val decimator = Decimator(
        cutoffHz = config.antiAliasCutoffHz,
        modelRateHz = config.modelRateHz,
        windowSamples = PositioningEngine.WINDOW_SAMPLES,
    )
    private val deadReckoner = DeadReckoner(startAt)
    private val modeArbiter = ModeArbiter(config.gnssLostNoFixTimeoutMs)

    private val _state = MutableStateFlow(PositionState(lat = startAt.lat, lon = startAt.lon))
    override val state: StateFlow<PositionState> = _state.asStateFlow()

    @Volatile private var lastGyroZ = 0f
    private val pendingGnssFix = AtomicReference<GnssFixRecord?>(null)
    private val lastKnownGnssSpeedMps = AtomicReference(0f)

    // Time-varying blend state: the propagated speed estimate and when it was last anchored
    // to a trusted GNSS speed. 0L = never anchored (e.g. indoors since launch).
    @Volatile private var blendSpeedMps = 0f
    @Volatile private var lastAnchorNanos = 0L

    @Volatile private var running = false
    private val periodMs = (1000.0 / config.outputRateHz).toLong()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "real-engine").apply {
            isDaemon = true

            priority = Thread.MAX_PRIORITY - 1
        }
    }

    override fun start() {
        if (running) return
        running = true
        scheduler.scheduleAtFixedRate({ safeTick() }, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        running = false
        scheduler.shutdown()
        speedEstimator.close()
    }

    private fun safeTick() {
        if (!running) return
        try {
            tickOnce()
        } catch (t: Throwable) {

            System.err.println("[RealEngine] tick failed: ${t.message}")
        }
    }

    override fun onImuSample(
        tNanos: Long,
        ax: Float, ay: Float, az: Float,
        grx: Float, gry: Float, grz: Float,
        gx: Float, gy: Float, gz: Float,
    ) {
        lastGyroZ = gz
        ringBuffer.push(ImuSampleRecord(tNanos, ax, ay, az, grx, gry, grz, gx, gy, gz))
    }

    override fun onGnssFix(
        tNanos: Long,
        lat: Double, lon: Double,
        speedMps: Float, bearingDeg: Float, horizAccM: Float,
        satsInFix: Int, irnssSatsInFix: Int,
    ) {
        pendingGnssFix.set(GnssFixRecord(tNanos, lat, lon, speedMps, bearingDeg, horizAccM, satsInFix, irnssSatsInFix))
        lastKnownGnssSpeedMps.set(speedMps)
        modeArbiter.onGnssFix(tNanos, satsInFix, irnssSatsInFix)
    }

    override fun onGnssLost(tNanos: Long) {
        modeArbiter.onGnssLost()
    }

    fun tickOnce() {
        val t0 = System.nanoTime()
        val samples = ringBuffer.snapshot()
        if (samples.isEmpty()) return

        val conditioned = conditioning.process(samples)

        conditioned.warnings.forEach { System.err.println("[RealEngine] conditioning: $it") }

        val tEndNanos = conditioned.samples.last().tNanos
        val rawWindow = decimator.decimate(conditioned.samples, tEndNanos)

        val fix = pendingGnssFix.getAndSet(null)
        if (fix != null) {
            headingEstimator.seedFromGnssCourse(fix.bearingDeg)
            fusionFilter.updateWithGnss(LatLon(fix.lat, fix.lon), fix.speedMps, fix.bearingDeg, fix.horizAccM)
            // CRITICAL: also snap the dead-reckoner to the fix. Without this, the next
            // deadReckoner.step() continues from its own stale position and
            // fusionFilter.predict() overwrites the GNSS update in this same tick — so the
            // published dot was pure dead-reckoning forever after INIT, silently ignoring
            // every GNSS fix (map position visibly desynced from real GPS).
            deadReckoner.reset(LatLon(fix.lat, fix.lon))
            // Blend anchor: a trusted fix re-anchors the propagated speed estimate.
            blendSpeedMps = fix.speedMps.coerceIn(config.speedMinMps, config.speedMaxMps)
            lastAnchorNanos = fix.tNanos
        }

        if (rawWindow == null) {

            publish(
                lat = fusionFilter.estimate().lat, lon = fusionFilter.estimate().lon,
                speedMps = lastKnownGnssSpeedMps.get(), headingDeg = headingEstimator.headingDeg().toFloat(),
                mode = Mode.INIT, tEndNanos = tEndNanos, tickStartNanos = t0,
            )
            return
        }
        modeArbiter.markWindowReady()

        val features = FeatureExtractor.featureWindow(rawWindow)
        val normalized = normalizer.apply(features)
        val dtSeconds = 1.0 / config.outputRateHz
        val vAbs = speedEstimator.estimate(normalized).coerceIn(config.speedMinMps, config.speedMaxMps)

        // Time-varying blend (validated offline: results/blend_tv_eval.json):
        //   lam = t/(t+tau), t = seconds since last trusted GNSS anchor
        //   v   = (1-lam) * (v + dv*dt)  +  lam * vAbs
        // Fresh anchor -> ride the anchor + delta model (CV prior's strong zone).
        // Old/no anchor -> the duration-stable absolute model takes over (lam -> 1),
        // which also keeps indoor/hand-held behaviour pinned near zero (v2 negatives).
        val speedMps = if (deltaEstimator != null && deltaNormalizer != null && lastAnchorNanos != 0L) {
            val dvPerSecond = deltaEstimator.estimate(deltaNormalizer.apply(features))
            val tSec = ((tEndNanos - lastAnchorNanos) / 1e9).coerceAtLeast(0.0)
            val lam = (tSec / (tSec + config.blendTauSeconds)).toFloat()
            blendSpeedMps = ((1f - lam) * (blendSpeedMps + dvPerSecond * dtSeconds.toFloat()) + lam * vAbs)
                .coerceIn(config.speedMinMps, config.speedMaxMps)
            blendSpeedMps
        } else {
            vAbs
        }
        headingEstimator.predict(lastGyroZ, dtSeconds)
        val headingDeg = headingEstimator.headingDeg()

        val deadReckoned = deadReckoner.step(speedMps, headingDeg, dtSeconds)
        fusionFilter.predict(deadReckoned, speedMps, headingDeg, dtSeconds)
        val fused = fusionFilter.estimate()
        val matched = mapMatcher.snap(fused)
        deadReckoner.reset(matched)

        publish(
            lat = matched.lat, lon = matched.lon, speedMps = speedMps, headingDeg = headingDeg.toFloat(),
            mode = modeArbiter.currentMode(tEndNanos), tEndNanos = tEndNanos, tickStartNanos = t0,
        )
    }

    private fun publish(
        lat: Double, lon: Double, speedMps: Float, headingDeg: Float,
        mode: Mode, tEndNanos: Long, tickStartNanos: Long,
    ) {
        _state.value = PositionState(
            lat = lat, lon = lon, speedMps = speedMps, headingDeg = headingDeg, mode = mode,
            satsInFix = modeArbiter.satsInFix(),
            irnssSatsInFix = modeArbiter.irnssSatsInFix(),
            uncertaintyM = fusionFilter.uncertaintyM(),
            engineTickMs = (System.nanoTime() - tickStartNanos) / 1_000_000f,
        )
    }
}
