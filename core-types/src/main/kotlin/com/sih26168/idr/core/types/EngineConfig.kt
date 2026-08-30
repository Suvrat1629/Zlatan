package com.sih26168.idr.core.types

data class EngineConfig(

    val modelRateHz: Double = 10.0,

    val windowSeconds: Double = 5.0,

    val antiAliasCutoffHz: Double = 4.0,

    val ringBufferSeconds: Double = 8.0,

    val coldStartSeconds: Double = 5.0,

    val speedMinMps: Float = 0f,
    val speedMaxMps: Float = 60f,

    /**
     * Time-varying blend gain timescale: lam = t/(t+tau), t = seconds since GNSS anchor.
     *
     * 240 is the value `sih-26168-model/results/blend_tv_eval.json` was evaluated at, and it is the
     * only value backed by evidence. It was raised to 1200 in the shipped config on 2026-08-30 to
     * suppress an out-of-domain misfire from speed model v1 ("30-35 km/h at true 5"), and reverted
     * on 2026-08-31 once v2 -- fine-tuned with not-driving negatives -- replaced it.
     *
     * Why the raise was harmful, so it is not repeated: at tau = 1200, sixty seconds into a
     * blackout the absolute model carries under 5% of the answer and the rest is an unbounded open
     * integral of the delta model with nothing to anchor it. That is what let a shaken phone
     * accumulate speed without bound. If the absolute model misbehaves again, fix the model or gate
     * its input -- do not turn it down. See TODO.md G3.
     */
    val blendTauSeconds: Double = 240.0,

    // ZUPT: below BOTH thresholds over a window, speed is clamped to 0 instantly.
    val zuptAccelThresholdMps2: Float = 0.8f,
    val zuptGyroThresholdRps: Float = 0.15f,

    // --- handling detection (TODO.md G1) ---
    // ZUPT above is a floor test: it can say "definitely not moving", never "definitely not
    // travelling". Shaking the phone exceeds both its thresholds, disabling the very mechanism
    // that would pin speed to zero. HandlingDetector supplies the missing ceiling, using the gyro
    // component PERPENDICULAR to gravity -- the orthogonal complement of the yaw projection, so it
    // cannot suppress a genuine turn however sharp.
    /**
     * Mean tilt rate above which the device is treated as being handled rather than transported,
     * rad/s. 0.44 rad/s is 25 deg/s.
     *
     * Argued from vehicle physics, not fitted: a car body's sustained pitch and roll rates are a
     * few deg/s, and a speed bump or pothole is a brief transient that a window mean absorbs. Hand
     * motion sits an order of magnitude above this -- field telemetry recorded 271 deg/s of raw
     * rotation while walking. 25 deg/s sits in the empty band between the two, deliberately closer
     * to the vehicle side so that a false positive needs genuinely violent motion.
     *
     * Telemetry logs the raw tilt rate every tick so this can be replaced with a measured
     * distribution after the first real drive, exactly as the map-match and NIS gates are being
     * calibrated. Until then, treat it as a bound rather than a tuned value.
     *
     * MEASURED LIMITATION, 2026-08-31 (TODO.md H4). Against ~14,000 rows of uploaded fleet
     * telemetry, a gate at this value would catch only about 18% of the rows where the engine
     * published speed while GNSS reported standstill. Those rows have a median rotation of 5 deg/s
     * -- they are a phone being carried or set down, not shaken, and they sit in the band between
     * ZUPT's floor and anything a rotation bound can see. Horizontal acceleration separates them
     * far more cleanly than rotation does.
     *
     * The value is NOT being lowered to chase that, for two reasons. The measurement uses yaw rate,
     * the only rotation channel the cloud schema carries, which is a proxy for the tilt rate this
     * gate actually reads. And there is still no vehicle data to check false positives against --
     * a false positive while driving holds speed and freezes heading, which is far more damaging
     * than the phantom it would prevent. Tightening a safety gate against pedestrian data, for a
     * system meant to run in vehicles, is how a bound stops being a bound.
     *
     * So: this gate is a partial fix, knowingly. The rest of that band is a transport-mode problem
     * (TODO.md E5), not a rotation-threshold problem.
     */
    val handlingTiltRateThresholdRps: Float = 0.44f,
    /**
     * Whether the handling gate ACTS, as opposed to only being measured.
     *
     * Off by default, deliberately, and for the same reason as [useGnssNisGate] and
     * [useMapMatchFusion]: the gate has never seen a vehicle. Its threshold is argued from physics,
     * and the only data available (pedestrian, TODO.md H4) says it is set too high for that domain
     * -- but says nothing at all about the false-positive side, which is the side that matters. A
     * false positive while driving holds speed and freezes heading, so a gate that misfires on the
     * first measurement drive would corrupt the very data the drive exists to produce, and would do
     * it in a way that reads as a model failure rather than a gate failure.
     *
     * So: compute the tilt rate and the verdict from day one, ship both in telemetry, and switch
     * this on once a real drive -- hard turns, potholes, a passenger picking the phone up -- shows
     * what the distribution actually looks like. Same collect-statistics-before-acting pattern the
     * NIS and map-match gates already follow.
     */
    val useHandlingGate: Boolean = false,
    /**
     * How long the engine may coast on a held speed and frozen heading before it resumes normal
     * processing regardless, seconds.
     *
     * Coasting is the right response to a corrupted measurement -- it asserts nothing -- but it is
     * open-loop and unobservable, so it cannot be allowed to run indefinitely. If handling persists
     * past this, the device is probably being carried rather than shaken, and stale coasting is
     * worse than a noisy estimate. The engine says so in telemetry when this fires.
     */
    val handlingMaxCoastSeconds: Double = 10.0,

    // WALK mode damping: published speed = min(speed * scale, cap). Car-trained models
    // misread gait as vehicle speed; this keeps walking display in a sane band.
    val walkingSpeedScale: Float = 0.3f,
    val walkingSpeedMaxMps: Float = 2.5f,

    // Road-heading correction: when confidently on a road, pull DR heading toward the
    // road's bearing. Kills the gyro random-walk on straights (the dominant cross-track
    // error, Part C). Gated off while turning so it never fights a real turn.
    val roadHeadingGain: Double = 0.08,
    val roadHeadingMaxDistM: Double = 15.0,
    val roadHeadingMaxTurnRps: Double = 0.15,

    // Physical slew limits on the published speed: no ground vehicle gains more than
    // ~4 m/s^2 or brakes harder than ~12 m/s^2. Kills sensor-shake speed spikes.
    val maxSpeedRiseMps2: Float = 4.0f,
    val maxSpeedDropMps2: Float = 12.0f,

    val gnssLostNoFixTimeoutMs: Long = 3_000,
    val handoverSlewSeconds: Double = 1.5,

    // GNSS pre-filter acceptance (GnssSource). A binary hardcoded 30 m accuracy gate locked
    // GNSS out for 2h11m of a stationary indoor session (9 satellites in view, every fix
    // reporting accuracy >30 m) while the engine dead-reckoned 1.4 km of drift. The gate now
    // escalates: strict [gnssAccuracyGateM] while fixes flow; after [gnssStarvedAfterSeconds]
    // with nothing accepted, fixes up to [gnssStarvedAccuracyCeilingM] pass through with their
    // real horizontal accuracy so downstream weighting can discount them instead of the
    // pre-filter pretending they don't exist.
    val gnssAccuracyGateM: Float = 30f,
    val gnssStarvedAfterSeconds: Float = 10f,
    val gnssStarvedAccuracyCeilingM: Float = 150f,

    // ErrorStateEkf process/measurement noise (plan2.md §2). ekfHeadingArwDegPerSqrtSec is
    // an Angle Random Walk coefficient fit from real IO-VNBD cross-track-vs-duration data.
    // ErrorStateEkf process/measurement noise (plan2.md §2).
    //
    // ekfHeadingArwDegPerSqrtSec: the heading process-noise coefficient. The original 1.41
    // was fit to sih-26168-model's aggregate cross-track-vs-duration medians as if it were a
    // pure Angle Random Walk; that fit lumps ARW, rate random walk and gyro scale-factor error
    // into one white-noise term and, per plan2.md §3a, undershoots the real spread badly.
    // ErrorStateEkfOutageTest.ekfCovarianceRealismUnderHeadingNoiseMismatch shows the concrete
    // cost: at 3x the fitted value the filter is ~2.5x overconfident at outage end and loses
    // to a raw last-fix snap on reacquisition. 4.2 (= 3x) makes the synthetic 60 s outage
    // drift (~22%) line up with Part C's measured ~25% median 2D system drift
    // (sih-26168-notes/15-Part-C-Fusion-Drift.md) and keeps the filter on the underconfident
    // side. PENDING: a real stationary-phone Allan variance (architecture doc §11) to replace
    // this with a measured ARW + bias-instability split before relying on the uncertainty
    // ellipse or on step 6's map-match weighting.
    val ekfInitialUncertaintyM: Float = 10f,
    val ekfSpeedNoiseMps: Float = 1.0f,
    val ekfHeadingArwDegPerSqrtSec: Float = 4.2f,
    val ekfMinGnssAccuracyM: Float = 5f,
    // GNSS course-over-ground as a direct heading measurement: only trusted above this
    // speed (Android's own bearing gets noisy/unreliable near-stationary).
    val ekfMinBearingTrustSpeedMps: Float = 3f,
    val ekfGnssBearingNoiseDeg: Float = 5f,

    // --- gyro bias state (heading work plan F3) ---
    // The error budget needs the yaw-rate bias held near 0.01 deg/s to keep cross-track inside
    // budget over a 60 s outage; consumer MEMS in-run bias instability sits well above that, and
    // removing a fitted CONSTANT bought nothing offline -- the bias moves within a drive, with
    // temperature. So it is a state with a slow random walk, not a calibration constant.
    /**
     * 1-std of the initial bias estimate, deg/s. Deliberately tight, and the reason matters: with
     * a mis-specified heading process noise (the configured ARW is fitted to aggregate cross-track
     * medians and probably undershoots real gyro noise), an EKF attributes residuals to whichever
     * state has the loosest prior. Give the bias a wide prior and it becomes a sink for angle
     * random walk, which it then subtracts from every later rotation -- measurably worse than
     * having no bias state at all. A factory-calibrated consumer MEMS gyro's residual bias is
     * within a few tenths of a deg/s, so this is also the physical value.
     */
    val ekfInitialGyroBiasDps: Float = 0.3f,
    /**
     * Bias random-walk driving noise, deg/s per sqrt(second). Bias instability drifts over minutes
     * -- with temperature, mostly -- not over seconds. Set it fast enough to chase gyro noise and
     * the state stops modelling bias and starts modelling noise.
     */
    val ekfGyroBiasRandomWalkDpsPerSqrtSec: Float = 0.002f,
    /** Measurement noise on the zero-velocity bias observation, deg/s. A stationary vehicle means
     *  any measured yaw rate IS bias, so this is close to the sensor's own noise floor. */
    val ekfZuptGyroNoiseDps: Float = 0.5f,

    // --- GNSS innovation gating (architecture doc: divergence guard) ---
    // A multipath fix in an urban canyon arrives with a confident-looking accuracy figure, so
    // horizAccM alone cannot reject it. The filter's own innovation can: NIS = y' S^-1 y is
    // chi-square with 2 degrees of freedom for a 2D position measurement, so 9.21 is the 99th
    // percentile. Above that, either the fix is wrong or the filter is -- see the consecutive
    // reject limit below, which decides between those two.
    val ekfGnssNisGate: Float = 9.21f,
    /**
     * Whether the NIS gate actually REJECTS, as opposed to only being measured.
     *
     * Off by default, deliberately. A statistical gate is only as trustworthy as the process noise
     * it is measured against, and this filter's heading ARW is fitted to aggregate cross-track
     * medians and is documented as probably undershooting real gyro noise. An overconfident filter
     * makes honest fixes look like outliers, so switching rejection on now discards good GNSS --
     * observed directly: a 3 m-accuracy fix was rejected outright while a 50 m one was accepted.
     *
     * So: measure NIS from day one, ship the distribution in telemetry, and turn rejection on once a
     * real drive shows what the distribution actually looks like. Same pattern the map-match gate
     * already uses -- collect the statistics with the action disabled.
     */
    val useGnssNisGate: Boolean = false,
    /**
     * Kinematic plausibility bound on a GNSS fix, m/s. Unlike NIS this does not depend on the
     * filter believing itself: a fix implying the vehicle moved faster than this since the previous
     * one is impossible regardless of how confident anything is. Safe to enforce immediately.
     */
    val gnssMaxImpliedSpeedMps: Float = 60f,
    /**
     * After this many consecutive rejections the filter, not the fix, is assumed wrong: a diverged
     * state makes every honest fix look like an outlier, and a gate with no escape hatch would lock
     * GNSS out permanently. On tripping, the next fix is accepted and the covariance inflated.
     */
    val ekfMaxConsecutiveGnssRejects: Int = 5,

    // --- yaw plausibility (heading work plan F4a) ---
    // A physical bound, not a filter: no road vehicle sustains this. Field telemetry recorded
    // 271 deg/s while walking, which is hand motion being integrated as vehicle rotation. A
    // magnitude clamp cannot harm a genuine turn -- unlike the low-pass that was considered and
    // rejected, which would smear a real turn's onset by a few hundred milliseconds.
    val maxYawRateDps: Float = 90f,
    // Switches RealEngine's fusion filter from PassthroughFusionFilter (hard-snap to each
    // GNSS fix) to ErrorStateEkf. See plan2.md for what's actually been validated.
    val useErrorStateEkf: Boolean = true,

    // HmmMapMatcher (plan2.md §3 steps 2-4): forward HMM over RoadGraph, top-k hypotheses,
    // Newson-Krumm-style transition scoring. false = RoadMatcher (greedy nearest-segment).
    // DEFAULT true: still display-only (see useMapMatchFusion, which stays off), so the worst
    // case is the dot sitting on a slightly different road than the greedy snapper picked --
    // and the HMM is tested against the parallel-road case the greedy matcher demonstrably
    // fails and the turn-following case an earlier HMM version failed. A display-only drive
    // with this on also produces the IDR-MAPFUSE gate statistics that inform the fusion flag.
    val useHmmMapMatcher: Boolean = true,
    val hmmMaxSnapM: Float = 35f,
    val hmmCandidateCount: Int = 5,
    // Emission noise: how far off-road a fix can be before it stops looking like that road.
    val hmmEmissionSigmaM: Float = 10f,
    // Transition scoring: penalty per metre of mismatch between route distance and
    // great-circle distance between consecutive observations.
    val hmmTransitionBetaM: Float = 5f,
    // Bounded-Dijkstra cutoff for route-distance search between candidate pairs.
    val hmmMaxTransitionSearchM: Float = 400f,
    // Displacement gate: the hypothesis chain only advances after this much movement, so the
    // transition term isn't asked to discriminate sub-metre steps between 10 Hz ticks.
    val hmmMinAdvanceDisplacementM: Float = 8f,

    // Map-match fusion (plan2.md §3 step 6): feed the matched position back into the fusion
    // filter as an anisotropic measurement instead of only drawing it on the map. OFF by
    // default -- the earlier "snap the reckoner to the match" attempt erased cross-track
    // motion and was reverted (see RealEngine.tickOnce), so this stays gated until validated
    // on a real drive, the same discipline useErrorStateEkf/useHmmMapMatcher started with.
    val useMapMatchFusion: Boolean = false,
    // Only fuse a match at least this confident: uncertaintyM below this. Both matchers emit
    // uncertaintyM as a positional 1-std on the same scale -- RoadMatcher = perpendicular snap
    // distance, HmmMapMatcher = hypothesis spread and snap distance in quadrature -- so one
    // threshold gates both. A wide hypothesis spread or an off-road result exceeds it.
    val mapMatchMaxFuseUncertaintyM: Float = 15f,
    // Cross-track measurement-noise floor: never trust a match tighter than this.
    val mapMatchMinCrossTrackSigmaM: Float = 2f,
    // Along-track measurement noise: deliberately huge. A straight road carries no
    // information about how far along it you are (architecture doc §4), so the along-track
    // update must be a near-no-op and must not shrink the along-track covariance.
    val mapMatchAlongTrackSigmaM: Float = 10_000f,

    val outputRateHz: Double = 10.0,

    val engineTickP95BudgetMs: Float = 50f,
) {
    val windowSamples: Int get() = (modelRateHz * windowSeconds).toInt()

    companion object {

        val DEFAULT = EngineConfig()
    }
}
