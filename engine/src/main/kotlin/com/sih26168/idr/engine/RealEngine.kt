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
    private val maxYawRateCarRadS: Float = Math.toRadians(config.maxYawRateDps.toDouble()).toFloat()
    private val maxYawRateBikeRadS: Float = Math.toRadians(config.maxYawRateBikeDps.toDouble()).toFloat()

    /** A leaning two-wheeler genuinely out-rotates a car body — measured 190 deg/s inside a real
     *  turn against a 90 deg/s bound that was cutting into it (TODO.md K11). */
    /** The speed model is validated on cars and measured to lose to a constant on two-wheelers,
     *  so its weight is capped per vehicle mode rather than globally (TODO.md L4). */
    private val activeBlendMaxLambda: Float
        get() = if (vehicleMode == VehicleMode.BIKE) config.blendMaxLambdaBike else config.blendMaxLambda

    private val maxYawRateRadS: Float
        get() = if (vehicleMode == VehicleMode.BIKE) maxYawRateBikeRadS else maxYawRateCarRadS
    private val yawClampCount = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var lastYawRateRadS = 0f
    @Volatile private var lastGyroZ = 0f
    @Volatile private var lastGnssBearingDeg = Float.NaN
    @Volatile private var lastGnssLat = Double.NaN
    @Volatile private var lastGnssLon = Double.NaN
    @Volatile private var lastInferenceMs = Float.NaN
    @Volatile private var lastDvMps2 = Float.NaN

    // Compass, always recorded; fused only when config.useMagHeading is on (off by default) --
    // see onMagneticHeading. The pair the dashboard is for is (magHeading - publishedHeading): if
    // that residual is a stable constant per mount at HIGH accuracy the compass is worth fusing;
    // if it wanders, the vehicle-distortion call in the integration contract stands and it is not.
    @Volatile private var lastMagHeadingDeg = Float.NaN
    @Volatile private var lastMagAccuracy = -1
    @Volatile private var lastDeclinationDeg = 0f
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
    private var tiltRateAccumRadS = 0.0
    private var tiltRateAccumSeconds = 0.0
    private var prevImuNanos = 0L
    @Volatile private var lastTiltRateRadS = 0f
    @Volatile private var handling = false
    @Volatile private var handlingDetected = false
    private var handlingSinceNanos = 0L
    private var handlingEpisodeExpired = false
    private val handlingTickCount = java.util.concurrent.atomic.AtomicLong(0)
    private val pendingGnssFix = AtomicReference<GnssFixRecord?>(null)
    private val lastKnownGnssSpeedMps = AtomicReference(0f)

    /** A compass reading waiting for the tick thread. See [onMagneticHeading]. */
    private data class MagHeadingRecord(
        val headingDeg: Double,
        val declinationDeg: Double,
        val sigmaDeg: Float,
    )
    private val pendingMagHeading = AtomicReference<MagHeadingRecord?>(null)

    // Time-varying blend state: the propagated speed estimate and when it was last anchored
    // to a trusted GNSS speed. 0L = never anchored (e.g. indoors since launch).
    @Volatile private var blendSpeedMps = 0f
    private var blendLogCounter = 0
    private var handlingLogCounter = 0
    private var longTickLogCounter = 0
    private val longTickCount = java.util.concurrent.atomic.AtomicLong(0)
    private var lastTickEndNanos = 0L
    @Volatile private var dtSecondsForTelemetry = 0.0
    @Volatile private var lastSpeedSigmaMps = Float.NaN

    /** Floor on integrated tick time. A tick that fires early or twice on the same sample must not
     *  integrate zero (which would stall position) or a negative interval. */
    private val MIN_TICK_SECONDS = 0.005
    @Volatile private var lastAnchorNanos = 0L
    @Volatile private var lastPublishedSpeedMps = 0f

    // User-selected vehicle context (GUI). WALK damps published speed; CAR/BIKE unchanged.
    @Volatile private var vehicleMode: VehicleMode = VehicleMode.CAR

    fun setVehicleMode(mode: VehicleMode) { vehicleMode = mode }

    /**
     * Whether GNSS is being deliberately muted for a blackout test, as opposed to genuinely lost.
     *
     * Recorded on every row because the two are otherwise indistinguishable in the data: both put
     * the arbiter in DEAD_RECKONING and both stop the fixes. Analysing session 20260831-035044 it
     * was impossible to tell whether its seven outages were deliberate probes or real signal loss,
     * which changes what the numbers mean -- a deliberate mute cuts cleanly from a good fix, while
     * real denial is preceded by degraded multipath. See TODO.md H10.
     */
    @Volatile private var gnssMuted = false
    fun setGnssMuted(muted: Boolean) { gnssMuted = muted }

    override fun onMagneticHeading(
        tNanos: Long,
        magneticHeadingDeg: Float,
        accuracy: Int,
        declinationDeg: Float,
    ) {
        lastMagHeadingDeg = magneticHeadingDeg
        lastMagAccuracy = accuracy
        lastDeclinationDeg = declinationDeg

        // Queued, not fused here. This runs on the sensor thread while tickOnce() drives
        // predict/update on the same filter, and the EKF's state and its covariance array are
        // plain fields -- applying a measurement inline would race them. onGnssFix already solves
        // exactly this with pendingGnssFix; this is the same handover, drained in tickOnce().
        //
        // Gates that depend only on the reading are applied here, so a reading that could never be
        // used is never queued. LOW and UNRELIABLE are skipped outright -- an uncalibrated compass
        // is not a weak measurement, it is a wrong one.
        //
        // WALK is excluded because the whole model assumes the phone is fixed relative to the
        // vehicle. A phone in a walking hand has no mount offset to solve, and letting the filter
        // chase one would corrupt heading with arm swing.
        if (!config.useMagHeading || vehicleMode == VehicleMode.WALK) return
        val sigmaDeg = when (accuracy) {
            MAG_ACCURACY_HIGH -> config.ekfMagHeadingNoiseHighDeg
            MAG_ACCURACY_MEDIUM -> config.ekfMagHeadingNoiseMediumDeg
            else -> return
        }
        // Unconditional set: a newer reading supersedes an undrained one rather than queueing
        // behind it, so what the tick applies is never more than one emit period stale.
        pendingMagHeading.set(
            MagHeadingRecord(magneticHeadingDeg.toDouble(), declinationDeg.toDouble(), sigmaDeg)
        )
    }

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
    private var mapMatchHeadingRejects = 0L

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
        // scheduleWithFixedDelay, not scheduleAtFixedRate (TODO.md K4).
        //
        // scheduleAtFixedRate targets absolute deadlines, so when the phone starves this thread it
        // banks the missed runs and then fires them back to back with no spacing. Measured spacing
        // on a real ride was p50 99 ms but p90 212 ms and p99 647 ms, while the tick's own compute
        // cost was only 29.5 ms p95 -- the loop was not slow, it was starved and then bursting.
        //
        // Bursts are worse than lateness here: several ticks land on nearly the same IMU window,
        // each integrating a real dt that has already been consumed by the one before. Fixed delay
        // spaces every run from the END of the previous one, which cannot burst. Combined with real
        // elapsed-time integration (K1), a late tick now integrates the time it actually covers
        // instead of the engine silently losing it.
        //
        // The residual turn error is a pipeline lag of roughly 0.2 s -- about one tick period -- so
        // steadier spacing shortens it directly.
        scheduler.scheduleWithFixedDelay({ safeTick() }, 0, periodMs, TimeUnit.MILLISECONDS)
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
        // motion being integrated as vehicle rotation. Unlike the low-pass that was considered and
        // rejected, a magnitude bound cannot smear a genuine turn's onset.
        //
        // REJECTED, not clamped, and the difference matters (TODO.md G4). This originally clamped,
        // reasoning that "dropping a sample leaves a hole in the integral". That is wrong for this
        // bound, and the error is in the sign rather than the magnitude: a hand shake is near
        // zero-mean in ANGLE but strongly asymmetric in RATE -- a fast flick out and a slow drift
        // back. Clamping truncates the fast half and passes the slow half through whole, so the
        // integral stops cancelling and a symmetric-in-angle shake is RECTIFIED into a net
        // rotation. The clamp did not merely fail to help; it manufactured the heading error.
        //
        // Contributing zero is symmetric by construction, so no rate profile can rectify. It is
        // also what the bound's own premise implies: if a sample above the bound is not vehicle
        // rotation, integrating the bound's worth of it anyway is incoherent. A genuine vehicle
        // turn never approaches 90 deg/s, so nothing real is lost -- and a rising reject count is
        // the signal that the bound itself is set wrong.
        val outOfBound = kotlin.math.abs(rawYawRate) > maxYawRateRadS
        val yawRate = if (outOfBound) 0f else rawYawRate
        if (outOfBound) yawClampCount.incrementAndGet()

        // Tilt rate: the gyro component PERPENDICULAR to gravity, i.e. everything the yaw
        // projection above discards. Accumulated as a time-weighted mean over the tick so the
        // handling gate reads a window statistic and a single pothole cannot trip it.
        val tiltRate = HandlingDetector.tiltRate(gx, gy, gz, grx, gry, grz)
        lastYawRateRadS = yawRate
        synchronized(headingAccumLock) {
            if (prevImuNanos != 0L) {
                val dt = ((tNanos - prevImuNanos) / 1e9).coerceIn(0.0, 0.05)
                headingAccumRad += yawRate * dt
                tiltRateAccumRadS += tiltRate * dt
                tiltRateAccumSeconds += dt
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

    /**
     * A fix that is deliberately being withheld from the filter (GNSS muted for a blackout probe),
     * handed to diagnostics as ground truth only.
     *
     * Deliberately NOT routed through [onGnssFix]: nothing here may touch the filter, the mode
     * arbiter, the blend anchor or any published value, or the probe would be measuring a system
     * that was quietly still being corrected.
     */
    fun onGnssTruthOnly(lat: Double, lon: Double) {
        diagnostics?.onGnssTruthOnly(LatLon(lat, lon))
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
        // REAL elapsed time, not the nominal tick period (TODO.md K1).
        //
        // This used to be `1.0 / config.outputRateHz`, a fixed 100 ms. Measured over a 106-minute
        // ride, actual spacing was p50 99 ms but p90 212 ms and p99 647 ms: `scheduleAtFixedRate`
        // is starved whenever the phone is busy, and the work itself is not the problem (tick p95
        // compute is 29.5 ms). Integrating a nominal dt meant the engine advanced position for
        // 20.5% LESS time than actually passed, and 25% less during outages.
        //
        // That was not a small error hiding in the noise -- it was cancelling most of the speed
        // model's over-prediction, which is why GNSS-aided distance measured a flattering 0.98x.
        // Expect distance to read LONGER after this change, not shorter: the compensation is gone
        // and the model's real bias is now visible. That is the point.
        //
        // Clamped because a genuine stall (4.4 s was observed) must not be integrated as though the
        // last known speed held across it. The gap is real; the speed estimate spanning it is not.
        val elapsedSeconds = if (lastTickEndNanos == 0L) 1.0 / config.outputRateHz
                             else (tEndNanos - lastTickEndNanos) / 1e9
        val dtSeconds = elapsedSeconds.coerceIn(MIN_TICK_SECONDS, config.maxTickIntegrationSeconds)
        if (elapsedSeconds > config.maxTickIntegrationSeconds) {
            longTickCount.incrementAndGet()
            if (longTickLogCounter++ % 10 == 0) {
                System.err.println(
                    "[RealEngine] tick spanned ${"%.1f".format(elapsedSeconds)}s, integrating only " +
                        "${config.maxTickIntegrationSeconds}s — position will lag reality across this gap"
                )
            }
        }
        lastTickEndNanos = tEndNanos
        dtSecondsForTelemetry = elapsedSeconds
        val inferStart = System.nanoTime()
        // Ask for the model's own uncertainty as well as its answer. When the loaded model has a
        // variance head this is what puts something learned into the fusion step -- the filter's
        // process noise stops being a hand-tuned constant and becomes the model's per-window
        // confidence. Null when the model has no head, and the filter falls back to the constant.
        val estimate = speedEstimator.estimateWithVariance(normalized)
        lastSpeedSigmaMps = estimate.sigmaMps ?: Float.NaN
        val vAbs = estimate.speedMps.coerceIn(config.speedMinMps, config.speedMaxMps)
        lastInferenceMs = (System.nanoTime() - inferStart) / 1_000_000f

        // Drain the fully-integrated turn angle accumulated since the last tick and feed it
        // to the heading estimator as an equivalent mean rate (interface unchanged).
        val turnRad = synchronized(headingAccumLock) {
            val a = headingAccumRad; headingAccumRad = 0.0; a
        }
        // Time-weighted mean tilt rate over the tick, drained under the same lock discipline.
        val meanTiltRateRadS = synchronized(headingAccumLock) {
            val sum = tiltRateAccumRadS; val secs = tiltRateAccumSeconds
            tiltRateAccumRadS = 0.0; tiltRateAccumSeconds = 0.0
            if (secs > 0.0) (sum / secs).toFloat() else 0f
        }
        lastTiltRateRadS = meanTiltRateRadS

        // Handling gate (TODO.md G1): ZUPT's missing ceiling. A vehicle body cannot sustain rapid
        // rotation about its horizontal axes; a hand does nothing else. Because this reads the
        // component orthogonal to the yaw projection, a genuine turn -- however sharp -- cannot
        // trip it. On detection the engine COASTS: it holds the last speed and freezes heading
        // integration rather than asserting a value it has no evidence for. Forcing zero would be
        // wrong for a passenger picking up their phone at 60 km/h.
        val handlingNow = HandlingDetector.isHandling(meanTiltRateRadS, config.handlingTiltRateThresholdRps)
        if (!handlingNow) {
            // Episode over: clear both the timer and the expiry latch so the next episode gets a
            // full coast allowance of its own.
            handlingSinceNanos = 0L
            handlingEpisodeExpired = false
        } else if (handlingSinceNanos == 0L) {
            handlingSinceNanos = tEndNanos
        }
        // Coasting is open-loop and unobservable, so it is bounded. Past the limit the device is
        // more likely being carried than shaken, and a stale held speed is worse than a noisy one.
        //
        // The latch matters: without it, expiry clears `handling`, the next tick sees handling
        // start afresh, the timer restarts, and the engine re-enters coasting every limit period
        // forever. The bound has to apply to the EPISODE, not to each unbroken run of ticks.
        val coastSeconds = if (handlingSinceNanos == 0L) 0.0 else (tEndNanos - handlingSinceNanos) / 1e9
        if (handlingNow && coastSeconds > config.handlingMaxCoastSeconds) handlingEpisodeExpired = true
        val coastExpired = handlingNow && handlingEpisodeExpired
        // `handlingDetected` is the verdict and is always measured; `handling` is whether the
        // engine ACTS on it. Splitting them is what lets the first vehicle drive calibrate the
        // threshold without the gate being able to corrupt that same drive (TODO.md H9).
        handlingDetected = handlingNow && !coastExpired
        handling = config.useHandlingGate && handlingDetected
        if (handling) handlingTickCount.incrementAndGet()
        if (handlingDetected && !config.useHandlingGate && handlingLogCounter % 20 == 0) {
            System.out.println(
                "IDR-HANDLING detected (tilt ${"%.0f".format(Math.toDegrees(meanTiltRateRadS.toDouble()))} deg/s) " +
                    "— measure-only, not acting (config.use_handling_gate=false)"
            )
        }
        if (coastExpired && handlingLogCounter++ % 20 == 0) {
            System.err.println(
                "[RealEngine] handling persisted past ${config.handlingMaxCoastSeconds}s " +
                    "(tilt ${"%.0f".format(Math.toDegrees(meanTiltRateRadS.toDouble()))} deg/s) — " +
                    "resuming normal processing; the device is probably being carried, not shaken"
            )
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

        // The delta model is gated OFF by default (TODO.md K2). It cannot do its job with the
        // current feature set: `a_horiz` is a magnitude, so accelerating and braking are the same
        // input, and the shipped weights return +0.30 m/s^2 for a hard-braking window. With it
        // disabled the blend reduces to the GNSS anchor plus the absolute model, which is what it
        // was actually doing anyway -- the delta term measured +0.01 m/s^2 in the field regardless
        // of what the vehicle did.
        val speedMps = if (config.useDeltaModel && deltaEstimator != null && deltaNormalizer != null && lastAnchorNanos != 0L) {
            val dvRaw = deltaEstimator.estimate(deltaNormalizer.apply(features))
                .coerceIn(-4f, 4f)                    // physical sanity: > 0.4 g is not a car
            // Handling corrupts the delta model's input as thoroughly as the absolute model's, so
            // its output must not reach the GNSS-referenced bias estimator either -- that average
            // is compared against a real observed speed change, and feeding hand motion into it
            // would poison a correction that persists long after the shake ends.
            if (!handling) dvBias.observePrediction(dvRaw)
            val dv = dvRaw - dvBias.biasMps2          // online bias correction (learned vs GNSS)
            lastDvMps2 = dv
            val tSec = ((tEndNanos - lastAnchorNanos) / 1e9).coerceAtLeast(0.0)
            val lam = (tSec / (tSec + config.blendTauSeconds)).toFloat().coerceAtMost(activeBlendMaxLambda)
            lastLambda = lam
            // Hold the blend's INTERNAL state too, not just the published output. Letting it keep
            // integrating while the published speed is held would hide the garbage rather than
            // reject it: the accumulated error would spring back into view the moment handling
            // ended. The hold has to reach the state, or it is only a display filter.
            blendSpeedMps = when {
                handling -> blendSpeedMps
                stationary -> 0f
                else -> ((1f - lam) * (blendSpeedMps + dv * dtSeconds.toFloat()) + lam * vAbs)
                    .coerceIn(config.speedMinMps, config.speedMaxMps)
            }
            if (blendLogCounter++ % 20 == 0) {
                System.out.println(
                    "IDR-BLEND vAbs=${"%.1f".format(vAbs * 3.6f)}km/h dvRaw=${"%.2f".format(dvRaw)} " +
                        "bias=${"%.2f".format(dvBias.biasMps2)} lam=${"%.3f".format(lam)} " +
                        "t=${"%.0f".format(tSec)}s zupt=$stationary blend=${"%.1f".format(blendSpeedMps * 3.6f)}km/h"
                )
            }
            blendSpeedMps
        } else {
            // No delta term: fade from the GNSS anchor to the absolute model on the same timescale
            // the blend has always used, rather than snapping to the absolute model the instant a
            // fix is lost. lambda is still reported, so telemetry keeps its meaning.
            val tSec = if (lastAnchorNanos == 0L) Double.MAX_VALUE
                       else ((tEndNanos - lastAnchorNanos) / 1e9).coerceAtLeast(0.0)
            val lam = if (tSec == Double.MAX_VALUE) 1f
                      else (tSec / (tSec + config.blendTauSeconds)).toFloat().coerceAtMost(activeBlendMaxLambda)
            lastLambda = lam
            lastDvMps2 = Float.NaN
            blendSpeedMps = when {
                handling -> blendSpeedMps
                stationary -> 0f
                else -> ((1f - lam) * blendSpeedMps + lam * vAbs)
                    .coerceIn(config.speedMinMps, config.speedMaxMps)
            }
            blendSpeedMps
        }

        // WALK-mode damping: car-trained models fabricate vehicle speeds from gait motion
        // (field: 6 km/h walking read as 20-35). CAR/BIKE pass through unchanged.
        val speedOut = if (vehicleMode == VehicleMode.WALK)
            (speedMps * config.walkingSpeedScale).coerceAtMost(config.walkingSpeedMaxMps)
        else speedMps

        // Speed hold while handling (TODO.md G1). The model's input window is dominated by hand
        // motion, so its output carries no information about vehicle speed -- and measurably so:
        // fed a synthetic shaken-stationary window, the shipped model returns a confident
        // three-figure km/h. Holding the last published value is the standard INS response to a
        // corrupted measurement. If the vehicle was genuinely stationary, ZUPT had already driven
        // this to 0 before the shake began and holding keeps it there, which is the reported
        // symptom resolved. If it was moving, coasting is right and zeroing would be a fabrication.
        val held = if (handling) lastPublishedSpeedMps else speedOut

        // Physical slew limit: a ground vehicle cannot gain more than ~4 m/s^2 or shed more
        // than ~12 m/s^2. Without this, shaking the phone spikes the model to absurd speeds
        // (field: 160 km/h while standing still). ZUPT's instant zero survives because a
        // drop is allowed 12 m/s^2, reaching 0 from city speeds within a couple of ticks.
        val dtF = dtSeconds.toFloat()
        val slewed = held.coerceIn(
            lastPublishedSpeedMps - config.maxSpeedDropMps2 * dtF,
            lastPublishedSpeedMps + config.maxSpeedRiseMps2 * dtF,
        ).coerceAtLeast(0f)
        lastPublishedSpeedMps = slewed
        // While handling, the gyro is measuring a hand rather than a vehicle, so the accumulated
        // rotation is not vehicle heading change. Nothing is a better estimate of vehicle heading
        // right now than the heading from just before the handling began -- so freeze, do not
        // integrate. The accumulator was already drained above, so this discards it rather than
        // deferring it to the next tick.
        val turnRadUsed = if (handling) 0.0 else turnRad
        headingEstimator.predict((turnRadUsed / dtSeconds).toFloat(), dtSeconds)
        val headingDeg = headingEstimator.headingDeg()

        val deadReckoned = deadReckoner.step(slewed, headingDeg, dtSeconds)
        // headingDeg here is the raw gyro-integrated control input -- it must stay raw, not
        // the filter's own corrected heading, or the correction would feed back into itself
        // instead of being driven by fresh gyro data.
        fusionFilter.predict(
            deadReckoned, slewed, headingDeg, dtSeconds,
            speedSigmaMps = estimate.sigmaMps?.takeIf { config.useLearnedSpeedVariance },
        )
        // Compass, drained here rather than applied on the sensor thread that produced it: the
        // filter is single-threaded by construction (see onMagneticHeading). After predict() for
        // two reasons. theta is initialised lazily by the first predict(), which assigns it
        // outright -- a compass update landing before that would converge the mount offset against
        // a theta that is then overwritten, leaving a wrong offset with a collapsed variance and
        // no way back for a minute. And `handling` is this tick's verdict by this point.
        //
        // Dropped while handling, on the same argument that gates the dv-bias estimator: a phone
        // being waved reads a mount offset that is not the mount's, and unlike one bad speed
        // sample that error persists in a filter state long after the shake ends.
        pendingMagHeading.getAndSet(null)?.let {
            if (!handling) {
                fusionFilter.updateWithMagneticHeading(it.headingDeg, it.declinationDeg, it.sigmaDeg)
            }
        }

        val preMatchPos = fusionFilter.estimate()
        // ONE snap call per tick -- the HMM advances its hypothesis chain on each call past the
        // displacement gate, so calling it twice would double-step it.
        val matchResult = mapMatcher.snap(preMatchPos)
        // Heading agreement between the matched road and where we believe we are pointing.
        //
        // The gate below asks whether the matcher is CONFIDENT. It cannot ask whether the matcher is
        // CORRECT, and on a dense street grid those are very different questions: measured
        // 2026-09-01 the matcher reported on-road on 88-100% of ticks with a median uncertainty of
        // 8.8 m, while the map visibly showed the estimate following the wrong roads. A confident
        // wrong snap is worse than no snap, because fusing it drags the filter onto a parallel
        // street and the covariance says to trust it.
        //
        // A road we are genuinely travelling along should have a bearing close to our heading, or
        // close to its reverse for a two-way road we are driving the other way down. A snap onto a
        // cross-street fails both. This is the cheapest available check that tests correctness
        // rather than confidence, and it is what makes turning fusion on defensible (TODO.md K10).
        val matchedBearing = matchResult.roadBearingDeg
        val headingAgrees = matchedBearing == null || run {
            val d = kotlin.math.abs(((headingDeg - matchedBearing) % 360.0 + 540.0) % 360.0 - 180.0)
            // Fold onto [0,90]: a one-way mismatch of 180 degrees is the same road, driven the
            // other way, and the anisotropic update is symmetric about the road axis anyway.
            val folded = if (d > 90.0) 180.0 - d else d
            folded <= config.mapMatchMaxHeadingDisagreeDeg
        }
        if (!headingAgrees) mapMatchHeadingRejects++

        val gatePasses = matchResult.onRoad &&
            matchResult.roadBearingDeg != null &&
            matchResult.uncertaintyM <= config.mapMatchMaxFuseUncertaintyM &&
            headingAgrees
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
                    !headingAgrees -> "wrong-road (heading disagrees)"
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

        // The filter may track its own, better-corrected heading (e.g. ErrorStateEkf, via
        // GNSS bearing and position-residual coupling) -- publish that when available instead
        // of the raw gyro heading, which never benefits from those corrections on its own.
        val publishedHeadingDeg = fusionFilter.headingDeg() ?: headingDeg

        val mode = modeArbiter.currentMode(tEndNanos)
        publish(
            lat = displayPos.lat, lon = displayPos.lon, speedMps = slewed, headingDeg = publishedHeadingDeg.toFloat(),
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
                tickIntervalMs = (dtSecondsForTelemetry * 1000.0).toFloat(),
                tiltRateRadS = lastTiltRateRadS,
                // The verdict, not whether it was acted on -- with the gate in measure-only mode
                // this column is the whole point, and reporting `handling` would log all false.
                handling = handlingDetected,
                vehicleMode = vehicleMode,
                gnssMuted = gnssMuted,
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
                magHeadingDeg = lastMagHeadingDeg,
                magAccuracy = lastMagAccuracy,
                // NaN unless the compass is actually being fused, for the same reason dvBias is
                // NaN without a delta model: an untouched mount state reads 0.0, which is a
                // legitimate converged value for a phone facing straight ahead and must not be
                // confused with "this never ran".
                mountOffsetDeg = if (config.useMagHeading) fusionFilter.mountOffsetDeg().toFloat() else Float.NaN,
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

    private companion object {
        // android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM / _HIGH. Duplicated as
        // plain Ints because :engine is pure Kotlin and must not depend on the Android SDK.
        const val MAG_ACCURACY_MEDIUM = 2
        const val MAG_ACCURACY_HIGH = 3
    }
}
