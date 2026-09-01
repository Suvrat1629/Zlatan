package com.sih26168.idr.engine

import com.sih26168.idr.core.model.SpeedEstimator
import com.sih26168.idr.core.nav.DeadReckoner
import com.sih26168.idr.core.nav.FusionFilter
import com.sih26168.idr.core.nav.GyroIntegrationHeadingEstimator
import com.sih26168.idr.core.nav.HeadingEstimator
import com.sih26168.idr.core.nav.ModeArbiter
import com.sih26168.idr.core.nav.PassthroughFusionFilter
import com.sih26168.idr.core.nav.YawRate
import com.sih26168.idr.core.map.MapMatcher
import com.sih26168.idr.core.map.NoOpMapMatcher
import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.GnssFixRecord
import com.sih26168.idr.core.types.ImuSampleRecord
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.PositionState
import com.sih26168.idr.core.types.PositioningEngine
import com.sih26168.idr.core.types.TelemetryTick
import com.sih26168.idr.core.types.VehicleMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exponential settling margin for the decimator's IIR low-pass; leaves an e^-15 residual. */
private const val WARMUP_TIME_CONSTANTS = 15.0

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
    // Previous session's learned delta-model offset, or 0 for a first run. See DvBiasEstimator:
    // the estimate takes ~20 trusted fixes to converge, and it is a property of the device and
    // the model rather than of the trip, so last session's answer beats starting from zero.
    initialDvBiasMps2: Float = 0f,

    ringBufferCapacitySamples: Int = 4000,
) : PositioningEngine {

    private val ringBuffer = RingBuffer(ringBufferCapacitySamples)
    private val conditioning = ConditioningStage()
    private val decimator = Decimator(
        cutoffHz = config.antiAliasCutoffHz,
        modelRateHz = config.modelRateHz,
        windowSamples = PositioningEngine.WINDOW_SAMPLES,
    )
    /** See [DecimationSpan] — one place, so the engine and its tests cannot drift apart. */
    private val decimationSpanNanos: Long = DecimationSpan.nanosFor(config)

    private val deadReckoner = DeadReckoner(startAt)
    private val modeArbiter = ModeArbiter(config.gnssLostNoFixTimeoutMs)

    private val _state = MutableStateFlow(PositionState(lat = startAt.lat, lon = startAt.lon))
    override val state: StateFlow<PositionState> = _state.asStateFlow()

    /** Physical yaw-rate bound, from config so it is tunable per vehicle class rather than baked in. */
    private val maxYawRateRadS: Float = Math.toRadians(config.maxYawRateDps.toDouble()).toFloat()
    private val yawClampCount = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var lastYawRateRadS = 0f
    @Volatile private var lastGyroZ = 0f
    @Volatile private var lastGnssBearingDeg = Float.NaN
    @Volatile private var lastGnssLat = Double.NaN
    @Volatile private var lastGnssLon = Double.NaN
    @Volatile private var lastInferenceMs = Float.NaN
    @Volatile private var lastDvMps2 = Float.NaN
    @Volatile private var lastLambda = Float.NaN
    // Swapped in and out as recording starts and stops: the engine outlives any one session.
    @Volatile private var diagnostics: Diagnostics? = null
    @Volatile private var telemetryWriter: TelemetryWriter? = null

    @Volatile private var tickListener: ((TelemetryTick) -> Unit)? = null

    fun setTelemetry(
        diagnostics: Diagnostics?,
        writer: TelemetryWriter?,
        tickListener: ((TelemetryTick) -> Unit)? = this.tickListener,
    ) {
        this.diagnostics = diagnostics
        this.telemetryWriter = writer
        this.tickListener = tickListener
    }
    // Heading must integrate EVERY gyro sample: point-sampling one z-rate per 100 ms tick
    // aliases fast turns (a 1-2 s 90-degree turn gets undercounted while a slow U-turn
    // survives — exactly the field symptom). Accumulated on the sensor thread, drained
    // once per tick.
    private val headingAccumLock = Any()
    private var headingAccumRad = 0.0
    private var prevImuNanos = 0L
    private val pendingGnssFix = AtomicReference<GnssFixRecord?>(null)
    private val lastKnownGnssSpeedMps = AtomicReference(0f)

    // Time-varying blend state: the propagated speed estimate and when it was last anchored
    // to a trusted GNSS speed. 0L = never anchored (e.g. indoors since launch).
    @Volatile private var blendSpeedMps = 0f
    private var blendLogCounter = 0
    @Volatile private var lastAnchorNanos = 0L
    @Volatile private var lastPublishedSpeedMps = 0f

    // User-selected vehicle context (GUI). WALK damps published speed; CAR/BIKE unchanged.
    @Volatile private var vehicleMode: VehicleMode = VehicleMode.CAR

    fun setVehicleMode(mode: VehicleMode) { vehicleMode = mode }

    // Online delta-bias calibration (doc §14: "online recalibration against GNSS").
    // See DvBiasEstimator for what it corrects and why the state lives outside this class.
    private val dvBias = DvBiasEstimator(
        alpha = config.dvBiasEmaAlpha,
        minFixDtSeconds = config.dvBiasFixDtMinSeconds,
        maxFixDtSeconds = config.dvBiasFixDtMaxSeconds,
        initialBiasMps2 = initialDvBiasMps2,
    )

    // Map-match fusion only runs when the matcher actually emits a fusable covariance (the
    // HMM). Feeding a greedy snapper's nearest-segment pick into the EKF as a tight
    // measurement is worse than display-only -- see MapMatcher.emitsFusableCovariance.
    private val mapMatchFusionEnabled: Boolean = config.useMapMatchFusion && mapMatcher.emitsFusableCovariance
    private var mapMatchGateOkCount = 0L
    private var mapMatchGateFailCount = 0L
    private var mapMatchGateLogCounter = 0

    init {
        if (config.useMapMatchFusion && !mapMatcher.emitsFusableCovariance) {
            System.err.println(
                "[RealEngine] use_map_match_fusion IGNORED: needs the HMM matcher " +
                    "(set use_hmm_map_matcher=true); got ${mapMatcher::class.simpleName}. Running display-only."
            )
        }
    }

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
        diagnostics?.onImuSample(tNanos)
        // Vehicle yaw is rotation about the local VERTICAL, not about the phone's z axis. Raw gz
        // reads cos(tilt) of the true rate -- 87% at 30 degrees, 34% at 70 -- and with a fixed
        // mount that is a systematic scale error on every turn, which the filter cannot correct
        // because consistent under-rotation looks like real rotation. See YawRate and the heading
        // work plan (wiki/notes/idr-heading-fix-plan.md, F1).
        val rawYawRate = YawRate.aboutVertical(gx, gy, gz, grx, gry, grz)
        // Physical plausibility bound (heading work plan F4a), NOT a filter. No road vehicle
        // sustains this rate; field telemetry recorded 271 deg/s while walking, which is hand
        // motion being integrated as vehicle rotation. A magnitude clamp cannot distort a genuine
        // turn -- unlike the low-pass that was considered and rejected, which would smear a real
        // turn's onset by a few hundred milliseconds and misplace the corner by metres.
        // Clamped rather than dropped: dropping a sample leaves a hole in the integral.
        val yawRate = rawYawRate.coerceIn(-maxYawRateRadS, maxYawRateRadS)
        if (yawRate != rawYawRate) yawClampCount.incrementAndGet()
        lastYawRateRadS = yawRate
        synchronized(headingAccumLock) {
            if (prevImuNanos != 0L) {
                val dt = ((tNanos - prevImuNanos) / 1e9).coerceIn(0.0, 0.05)
                headingAccumRad += yawRate * dt
            }
            prevImuNanos = tNanos
        }
        ringBuffer.push(ImuSampleRecord(tNanos, ax, ay, az, grx, gry, grz, gx, gy, gz))
    }

    override fun onGnssFix(
        tNanos: Long,
        lat: Double, lon: Double,
        speedMps: Float, bearingDeg: Float, horizAccM: Float,
        satsInFix: Int, irnssSatsInFix: Int,
        bearingValid: Boolean,
    ) {
        diagnostics?.onGnssFix(tNanos)
        lastGnssBearingDeg = bearingDeg
        lastGnssLat = lat
        lastGnssLon = lon
        // The first trusted fix after a blackout is the only truth we get. Hand it to
        // diagnostics with what the engine believed at that instant, before the fix is applied
        // and the belief is overwritten.
        diagnostics?.onReacquisition(
            truth = LatLon(lat, lon),
            engineBelief = LatLon(_state.value.lat, _state.value.lon),
        )
        pendingGnssFix.set(GnssFixRecord(tNanos, lat, lon, speedMps, bearingDeg, horizAccM, satsInFix, irnssSatsInFix, bearingValid))
        lastKnownGnssSpeedMps.set(speedMps)
        modeArbiter.onGnssFix(tNanos, satsInFix, irnssSatsInFix)
    }

    override fun onGnssLost(tNanos: Long) {
        modeArbiter.onGnssLost()
    }

    fun tickOnce() {
        val t0 = System.nanoTime()
        // Only the span the decimator will bin, not the whole ring buffer. Everything downstream
        // (sort, dedupe, gap scan, clipping scan, decimation) is linear or worse in the sample
        // count, and at 214 Hz the untrimmed buffer was roughly 1,700 samples reprocessed ten times
        // a second. See RingBuffer.snapshotSince.
        val newestTNanos = ringBuffer.newestTNanos() ?: return
        val samples = ringBuffer.snapshotSince(newestTNanos - decimationSpanNanos)
        if (samples.isEmpty()) return

        val conditioned = conditioning.process(samples)

        conditioned.warnings.forEach { System.err.println("[RealEngine] conditioning: $it") }

        val tEndNanos = conditioned.samples.last().tNanos
        val rawWindow = decimator.decimate(conditioned.samples, tEndNanos)

        val fix = pendingGnssFix.getAndSet(null)
        if (fix != null) {
            // Reseed the gyro heading estimator from GNSS course ONLY when the fusion filter
            // does not track its own heading (PassthroughFusionFilter). When the EKF owns
            // heading it corrects theta through its weighted bearing + position measurements in
            // updateWithGnss below. Reseeding here would jump the headingDeg value the EKF's
            // predict() reads as a tick-to-tick DELTA, so the whole (course - gyroHeading) gap
            // gets applied to theta as if it were real rotation -- unweighted, double-counting
            // the correction, and at low speed injecting the course noise that the EKF's own
            // ekfMinBearingTrustSpeedMps gate exists to reject. The gyro estimator free-runs as
            // a pure delta source; its absolute drift is irrelevant since only the delta is used.
            if (fusionFilter.headingDeg() == null) {
                headingEstimator.seedFromGnssCourse(fix.bearingDeg)
            }
            fusionFilter.updateWithGnss(LatLon(fix.lat, fix.lon), fix.speedMps, fix.bearingDeg, fix.horizAccM, fix.bearingValid)
            // CRITICAL: also snap the dead-reckoner to the fix. Without this, the next
            // deadReckoner.step() continues from its own stale position and
            // fusionFilter.predict() overwrites the GNSS update in this same tick — so the
            // published dot was pure dead-reckoning forever after INIT, silently ignoring
            // every GNSS fix (map position visibly desynced from real GPS).
            deadReckoner.reset(LatLon(fix.lat, fix.lon))
            // Blend anchor: a trusted fix re-anchors the propagated speed estimate.
            blendSpeedMps = fix.speedMps.coerceIn(config.speedMinMps, config.speedMaxMps)
            lastAnchorNanos = fix.tNanos

            // Online dv-bias update: compare the delta model's average prediction over the
            // inter-fix interval with the GNSS-observed speed change per second.
            dvBias.onTrustedFix(fix.speedMps, fix.tNanos)
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
        val inferStart = System.nanoTime()
        val vAbs = speedEstimator.estimate(normalized).coerceIn(config.speedMinMps, config.speedMaxMps)
        lastInferenceMs = (System.nanoTime() - inferStart) / 1_000_000f

        // Drain the fully-integrated turn angle accumulated since the last tick and feed it
        // to the heading estimator as an equivalent mean rate (interface unchanged).
        val turnRad = synchronized(headingAccumLock) {
            val a = headingAccumRad; headingAccumRad = 0.0; a
        }

        // Time-varying blend (validated offline: results/blend_tv_eval.json):
        //   lam = t/(t+tau), t = seconds since last trusted GNSS anchor
        //   v   = (1-lam) * (v + dv*dt)  +  lam * vAbs
        // Fresh anchor -> ride the anchor + delta model (CV prior's strong zone).
        // Old/no anchor -> the duration-stable absolute model takes over (lam -> 1),
        // which also keeps indoor/hand-held behaviour pinned near zero (v2 negatives).
        // ZUPT: sensors quiet -> we KNOW v = 0. Registers halts instantly (the blend alone
        // only decays toward zero) and wipes the speed random-walk's accumulated error at
        // every stop — the cheapest large drift win in stop-and-go traffic.
        val stationary = ZeroVelocityDetector.isStationary(
            features, config.zuptAccelThresholdMps2, config.zuptGyroThresholdRps,
        )

        // A stationary vehicle cannot be rotating, so the measured yaw rate is bias by definition
        // -- the only direct observation of it available during a blackout (heading work plan F3).
        if (stationary) fusionFilter.updateStationaryGyro(lastYawRateRadS)

        val speedMps = if (deltaEstimator != null && deltaNormalizer != null && lastAnchorNanos != 0L) {
            val dvRaw = deltaEstimator.estimate(deltaNormalizer.apply(features))
                .coerceIn(-4f, 4f)                    // physical sanity: > 0.4 g is not a car
            dvBias.observePrediction(dvRaw)
            val dv = dvRaw - dvBias.biasMps2          // online bias correction (learned vs GNSS)
            lastDvMps2 = dv
            val tSec = ((tEndNanos - lastAnchorNanos) / 1e9).coerceAtLeast(0.0)
            val lam = (tSec / (tSec + config.blendTauSeconds)).toFloat()
            lastLambda = lam
            blendSpeedMps = if (stationary) 0f else
                ((1f - lam) * (blendSpeedMps + dv * dtSeconds.toFloat()) + lam * vAbs)
                    .coerceIn(config.speedMinMps, config.speedMaxMps)
            if (blendLogCounter++ % 20 == 0) {
                System.out.println(
                    "IDR-BLEND vAbs=${"%.1f".format(vAbs * 3.6f)}km/h dvRaw=${"%.2f".format(dvRaw)} " +
                        "bias=${"%.2f".format(dvBias.biasMps2)} lam=${"%.3f".format(lam)} " +
                        "t=${"%.0f".format(tSec)}s zupt=$stationary blend=${"%.1f".format(blendSpeedMps * 3.6f)}km/h"
                )
            }
            blendSpeedMps
        } else {
            if (stationary) 0f else vAbs
        }

        // WALK-mode damping: car-trained models fabricate vehicle speeds from gait motion
        // (field: 6 km/h walking read as 20-35). CAR/BIKE pass through unchanged.
        val speedOut = if (vehicleMode == VehicleMode.WALK)
            (speedMps * config.walkingSpeedScale).coerceAtMost(config.walkingSpeedMaxMps)
        else speedMps

        // Physical slew limit: a ground vehicle cannot gain more than ~4 m/s^2 or shed more
        // than ~12 m/s^2. Without this, shaking the phone spikes the model to absurd speeds
        // (field: 160 km/h while standing still). ZUPT's instant zero survives because a
        // drop is allowed 12 m/s^2, reaching 0 from city speeds within a couple of ticks.
        val dtF = dtSeconds.toFloat()
        val slewed = speedOut.coerceIn(
            lastPublishedSpeedMps - config.maxSpeedDropMps2 * dtF,
            lastPublishedSpeedMps + config.maxSpeedRiseMps2 * dtF,
        ).coerceAtLeast(0f)
        lastPublishedSpeedMps = slewed
        headingEstimator.predict((turnRad / dtSeconds).toFloat(), dtSeconds)
        val headingDeg = headingEstimator.headingDeg()

        val deadReckoned = deadReckoner.step(slewed, headingDeg, dtSeconds)
        // headingDeg here is the raw gyro-integrated control input -- it must stay raw, not
        // the filter's own corrected heading, or the correction would feed back into itself
        // instead of being driven by fresh gyro data.
        fusionFilter.predict(deadReckoned, slewed, headingDeg, dtSeconds)
        val preMatchPos = fusionFilter.estimate()
        // ONE snap call per tick -- the HMM advances its hypothesis chain on each call past the
        // displacement gate, so calling it twice would double-step it.
        val matchResult = mapMatcher.snap(preMatchPos)
        val gatePasses = matchResult.onRoad &&
            matchResult.roadBearingDeg != null &&
            matchResult.uncertaintyM <= config.mapMatchMaxFuseUncertaintyM
        val fuseMapMatch = mapMatchFusionEnabled && gatePasses
        // Gate statistics run whenever the matcher COULD be fused (i.e. the HMM), even with
        // use_map_match_fusion off -- a display-only drive on the real 25k-way graph then
        // still reveals whether the toy-fixture-calibrated 15 m gate fires often enough,
        // turning one drive into the data that decides the fusion flag. Rate-limited to the
        // IDR-BLEND cadence.
        if (mapMatcher.emitsFusableCovariance) {
            if (gatePasses) mapMatchGateOkCount++ else mapMatchGateFailCount++
            if (mapMatchGateLogCounter++ % 20 == 0) {
                val reason = when {
                    !matchResult.onRoad -> "off-road"
                    matchResult.roadBearingDeg == null -> "no-bearing"
                    matchResult.uncertaintyM > config.mapMatchMaxFuseUncertaintyM ->
                        "unc=${"%.1f".format(matchResult.uncertaintyM)}>${config.mapMatchMaxFuseUncertaintyM}"
                    else -> "gate-ok"
                }
                val total = mapMatchGateOkCount + mapMatchGateFailCount
                val applied = if (mapMatchFusionEnabled) "applied" else "display-only"
                System.out.println(
                    "IDR-MAPFUSE $reason ($applied) gate-ok=$mapMatchGateOkCount/$total " +
                        "(${"%.0f".format(100.0 * mapMatchGateOkCount / total)}%)"
                )
            }
        }
        if (fuseMapMatch) {
            // Anisotropic: tight across the road, ~free along it. Unlike the reverted
            // "reset the reckoner to the matched point", this is a covariance-weighted partial
            // pull that only touches cross-track -- it can't erase a genuine turn onto a cross
            // street (the HMM switches hypotheses as the sideways motion accumulates).
            fusionFilter.updateWithMapMatch(
                matchResult.position,
                alongTrackSigmaM = config.mapMatchAlongTrackSigmaM,
                crossTrackSigmaM = matchResult.uncertaintyM.coerceAtLeast(config.mapMatchMinCrossTrackSigmaM),
                roadBearingDeg = matchResult.roadBearingDeg!!,
            )
        }
        val fused = fusionFilter.estimate()
        // Default (display-only) path: publish the snapped point but keep the reckoner on the
        // true, unmatched trajectory -- resetting it to the match erased cross-track motion
        // every tick (a turn's first sideways metres got projected back onto the current way
        // before the dot could ever reach the cross street). Fusion path: the filter itself is
        // road-corrected now, so its own estimate is what we publish and continue from.
        val displayPos = if (fuseMapMatch) fused else matchResult.position
        deadReckoner.reset(fused)

        // Road-heading correction: on a confident match, pull heading toward the road's bearing.
        // The gyro's heading random-walk is the dominant cross-track error on long straights
        // (Part C: 4% -> 34% with outage duration); the road geometry is the absolute reference
        // the magnetometer failed to be. Gates: tight match only, moving (bearing is meaningless
        // when parked), and NOT turning (never fight a real turn).
        //
        // Two routes, because the correction has to enter wherever heading actually lives. When
        // the filter owns heading it must arrive as a weighted measurement on the filter's own
        // theta: nudging the gyro estimator underneath the EKF would inject the whole correction
        // as if it were real rotation -- unweighted, bypassing the covariance, the same trap
        // seedFromGnssCourse is gated against above. (updateWithMapMatch also takes a road
        // bearing, but only to rotate the position measurement into road axes; its H row has
        // hTheta = 0, so it corrects heading not at all. It is also off by default.)
        //
        // Same gates either way: confident match, moving, and not mid-turn -- never fight a
        // real turn with the road's average bearing.
        val roadBearing = matchResult.roadBearingDeg
        if (roadBearing != null &&
            matchResult.onRoad &&
            matchResult.uncertaintyM <= config.roadHeadingMaxDistM &&
            slewed > 3f &&
            kotlin.math.abs(turnRad / dtSeconds) < config.roadHeadingMaxTurnRps
        ) {
            if (fusionFilter.headingDeg() == null) {
                // Way direction is arbitrary: resolve the 180-degree ambiguity toward whichever
                // end is closer to the current heading. The filter path does the same thing by
                // wrapping its innovation to a quarter turn.
                val h = headingEstimator.headingDeg()
                val d1 = kotlin.math.abs(((roadBearing - h + 540.0) % 360.0) - 180.0)
                val target = if (d1 <= 90.0) roadBearing else (roadBearing + 180.0).mod(360.0)
                headingEstimator.nudgeToward(target, config.roadHeadingGain)
            } else if (config.useRoadBearingHeading) {
                fusionFilter.updateWithRoadBearing(roadBearing, config.ekfRoadBearingNoiseDeg)
            }
        }

        // The filter may track its own, better-corrected heading (e.g. ErrorStateEkf, via GNSS
        // bearing and position-residual coupling) -- publish that when available instead of the
        // raw gyro heading, which never benefits from those corrections on its own.
        val publishedHeadingDeg = fusionFilter.headingDeg() ?: headingEstimator.headingDeg()

        val mode = modeArbiter.currentMode(tEndNanos)
        publish(
            lat = displayPos.lat, lon = displayPos.lon, speedMps = slewed,
            headingDeg = publishedHeadingDeg.toFloat(),
            mode = mode, tEndNanos = tEndNanos, tickStartNanos = t0,
        )

        if (diagnostics != null || telemetryWriter != null || tickListener != null) {
            val tick = TelemetryTick(
                tNanos = tEndNanos,
                vModelMps = vAbs,
                dvMps2 = lastDvMps2,
                vOutMps = slewed,
                vGnssMps = lastKnownGnssSpeedMps.get(),
                blendLambda = lastLambda,
                yawRateRadS = (turnRad / dtSeconds).toFloat(),
                headingDeg = publishedHeadingDeg.toFloat(),
                gnssBearingDeg = lastGnssBearingDeg,
                aHorizMps2 = features.last()[0],          // channel 0 is a_horiz
                stationary = stationary,
                mode = mode,
                satsInFix = modeArbiter.satsInFix(),
                irnssSatsInFix = modeArbiter.irnssSatsInFix(),
                lat = displayPos.lat,
                lon = displayPos.lon,
                gnssLat = lastGnssLat,
                gnssLon = lastGnssLon,
                uncertaintyM = fusionFilter.uncertaintyM(),
                gyroBiasDps = fusionFilter.gyroBiasDps().toFloat(),
                headingUncertaintyDeg = (fusionFilter.headingUncertaintyDeg() ?: Double.NaN).toFloat(),
                gnssNis = fusionFilter.lastGnssNis().toFloat(),
                yawClampCount = yawClampCount.get(),
                mapMatchOnRoad = matchResult.onRoad,
                mapMatchUncertaintyM = if (matchResult.onRoad) matchResult.uncertaintyM else Float.NaN,
                inferenceMs = lastInferenceMs,
                tickMs = (System.nanoTime() - t0) / 1_000_000f,
                // NaN, not 0, when there is no delta model: 0 is a legitimate converged estimate
                // and must not be confused with "this loop never ran".
                dvBiasMps2 = if (deltaEstimator != null) dvBias.biasMps2 else Float.NaN,
            )
            diagnostics?.onTick(tick)
            telemetryWriter?.write(tick)
            tickListener?.invoke(tick)
        }
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
