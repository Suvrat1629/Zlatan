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

    /**
     * Ceiling on the blend weight given to the absolute speed model, 0..1.
     *
     * The blend fades from the GNSS anchor toward the model as `lam = t/(t+tau)`, and with no cap
     * `lam` reaches 1: given a long enough outage the model replaces the constant-velocity prior
     * entirely. That is only safe if the model beats constant velocity, and on a two-wheeler it
     * emphatically does not.
     *
     * Measured 2026-09-02 over 238 s of continuous motorbike riding with GNSS live throughout
     * (1,704 labelled windows, `rides/ride_20260902_015526.npz`):
     *
     *   correlation with true speed   r = 0.19, r-squared = 0.037
     *   model MAE                     2.84 m/s
     *   MAE of simply predicting the median speed   1.46 m/s
     *
     * **The model is worse than a constant.** Checked at every lag from -3 s to +3 s and after 3 s
     * smoothing; it never rises. The same weights report R-squared 0.66 on the IO-VNBD test set,
     * which is cars — this is a domain failure on two-wheelers, not a tuning problem.
     *
     * So the model may inform the estimate but must not own it. 0.5 keeps the constant-velocity
     * prior worth at least as much as the model at any outage length. Raise it once a retrain on
     * target-vehicle data shows the model beating a constant on that vehicle — the number is a
     * statement about model quality, so it moves when the model does.
     */
    val blendMaxLambda: Float = 0.5f,

    /**
     * Ceiling on the blend weight given to the speed model when the vehicle mode is BIKE.
     *
     * The model is trained on IO-VNBD, which is cars, and validated on cars at R-squared 0.66. On a
     * two-wheeler it scores **R-squared 0.037 with an MAE of 2.84 m/s, against 1.46 m/s for simply
     * predicting the median** — worse than ignoring the sensors
     * (`wiki/notes/idr-model-domain-failure-2026-09-02.md`).
     *
     * Trust it where it was validated and distrust it where it was measured to fail. That is not
     * caution, it is the evidence: a predictor that loses to a constant should not be given weight
     * against the constant-velocity prior, and the vehicle selector already tells us which case we
     * are in.
     *
     * 0.05 rather than 0 so the value stays a weight rather than a special case, and so telemetry
     * still shows the model's contribution being made and discarded. Raise it the moment a retrain
     * beats a constant on a two-wheeler.
     */
    val blendMaxLambdaBike: Float = 0.05f,

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
     * Whether the delta-speed model participates in the blend.
     *
     * Off by default. The delta model is **architecturally incapable** of the job the blend gives
     * it, and no retraining fixes that: `FeatureExtractor` emits `a_horiz` as a MAGNITUDE, so the
     * sign of longitudinal acceleration is destroyed before the model ever sees it. Accelerating
     * and braking are the same input. Verified directly against the shipped `delta_v1` weights —
     * a hard-braking window returns **+0.30 m/s²** where the truth is about −2.5.
     *
     * Confirmed in the field: across 18,000 GNSS-aided ticks its output was +0.01 m/s² whether the
     * vehicle was braking hard or accelerating hard. It contributes nothing but a false dependency,
     * and the blend silently relies on it.
     *
     * Re-enabling this requires a signed longitudinal-acceleration channel — projecting linear
     * acceleration onto an estimated forward axis — which changes the feature vector and forces a
     * retrain of both models. That is a real design task, not a config change.
     */
    val useDeltaModel: Boolean = false,

    /**
     * Whether the speed model's own per-window uncertainty drives the filter's process noise.
     *
     * This is the project's answer to the problem statement's call for AI-based fusion.
     * [ErrorStateEkf] is hand-tuned throughout, and a full learned filter is not warranted at this
     * stage — but its speed process noise is exactly where a heteroscedastic variance head belongs.
     * A model that knows it is guessing should widen the filter for that tick rather than being
     * trusted at a fixed constant, which is genuine learned noise adaptation in the fusion step
     * rather than a classical filter with a neural network bolted upstream.
     *
     * ON by default and harmless before the head exists: models without one report no uncertainty
     * and the filter falls back to `ekfSpeedNoiseMps` exactly as before. It becomes live the moment
     * a two-output model is dropped into assets.
     */
    val useLearnedSpeedVariance: Boolean = true,
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
    // Online delta-model bias calibration (DvBiasEstimator). The delta model carries a
    // device-specific offset (+0.5..1.8 m/s^2 measured on an S24 against ~0 on the training
    // set), tracked against trusted GNSS and subtracted. Alpha is low because the offset is a
    // slow device property and one inter-fix interval is a noisy look at it; at 0.05 the
    // estimate is most of the way there after roughly 20 fixes. The dt bounds discard intervals
    // too short to carry signal over GNSS speed noise, and ones long enough that the average
    // prediction spans a different driving regime.
    val dvBiasEmaAlpha: Float = 0.05f,
    val dvBiasFixDtMinSeconds: Float = 0.2f,
    val dvBiasFixDtMaxSeconds: Float = 5.0f,

    // WALK mode damping: published speed = min(speed * scale, cap). Car-trained models
    // misread gait as vehicle speed; this keeps walking display in a sane band.
    val walkingSpeedScale: Float = 0.3f,
    val walkingSpeedMaxMps: Float = 2.5f,

    // Road-heading correction: when confidently on a road, pull DR heading toward the
    // road's bearing. Kills the gyro random-walk on straights (the dominant cross-track
    // error, Part C). Gated off while turning so it never fights a real turn.
    val roadHeadingGain: Double = 0.08,
    /** Confidence gate on the road-heading correction, metres. Compared against
     *  MapMatchResult.uncertaintyM -- the matcher's positional uncertainty, NOT a perpendicular
     *  distance, which is what the old road_heading_max_dist_m name implied. The pre-EKF gate did
     *  read a perpendicular distance, via MapMatcher.matchedDistanceM(), but no matcher has ever
     *  overridden that method: it returns the interface default of null, so the gate it guarded
     *  could never pass. Renamed rather than reverted, because reverting restores a dead gate. */
    val roadHeadingMaxUncertaintyM: Double = 15.0,
    val roadHeadingMaxTurnRps: Double = 0.15,

    // Physical slew limits on the published speed: no ground vehicle gains more than
    // ~4 m/s^2 or brakes harder than ~12 m/s^2. Kills sensor-shake speed spikes.
    val maxSpeedRiseMps2: Float = 4.0f,
    val maxSpeedDropMps2: Float = 12.0f,

    /**
     * Upper bound on the elapsed time a single engine tick may integrate, seconds.
     *
     * The engine used to integrate a FIXED nominal dt of 1/outputRateHz. Measured on a 106-minute
     * ride, real tick spacing was p50 99 ms but p90 212 ms and p99 647 ms — `scheduleAtFixedRate`
     * gets starved on a loaded phone. The engine therefore advanced position for **20.5% less time
     * than actually passed**, and 25% less during outages specifically.
     *
     * That error was silently cancelling most of the speed model's over-prediction, which is why
     * GNSS-aided distance measured a flattering 0.98x. Using real elapsed time is correct, and it
     * will make the speed model's error visible rather than creating it.
     *
     * The clamp exists because a genuinely huge gap — app suspended, doze, a 4.4 s stall was
     * observed — must not be integrated as if the last known speed held throughout. Beyond this
     * bound the tick integrates the bound and says so; the gap is real but the speed estimate
     * across it is not evidence.
     */
    val maxTickIntegrationSeconds: Double = 0.5,

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

    // Road bearing as a heading measurement for the EKF. The pre-EKF engine already pulled the
    // gyro heading toward the matched road's bearing (see roadHeadingGain below); that correction
    // went dark when the EKF took ownership of heading, because nudging the gyro estimator
    // underneath the filter would inject the whole correction as unweighted rotation. This is the
    // same correction re-entered the right way: as a weighted measurement on theta, under the
    // same gates.
    //
    // OFF by default, on the same measure-first discipline as useGnssNisGate and useHandlingGate.
    // The update fires every tick with the same segment bearing, so the filter counts one
    // geometric fact as outputRateHz independent observations; balanced against the heading ARW
    // the effective sigma settles near 3 deg, tighter than the 5 deg GNSS bearing it would then
    // outvote. "Restores shipped behaviour" is not quite a defence either: the pre-EKF nudge
    // applied a 0.08 gain, not a full-weight measurement.
    //
    // Enable for a drive, confirm heading is not dragged toward segment bearings through curves,
    // then either keep it or rate-limit the update to segment changes.
    val useRoadBearingHeading: Boolean = false,
    // Wider than the GNSS bearing noise on purpose: a road's bearing is its polyline's bearing, so
    // a gentle curve or a wide junction sits 10-15 degrees off the vehicle's true heading. The
    // road is the fallback reference, not the better one.
    // NOTE: the update is applied every tick with the same segment bearing, so the filter counts
    // one geometric fact as outputRateHz independent observations and the effective weight is far
    // tighter than this sigma reads. That over-weighting is why useRoadBearingHeading ships off.
    // Harmless on a straight; on a gentle curve inside the turn gate it lags heading toward the
    // segment. Rate-limiting to segment changes is the fix, and is deliberately not in this PR.
    val ekfRoadBearingNoiseDeg: Float = 10f,

    // --- compass as a heading measurement, with the mount offset as a state ---
    // The compass reads the PHONE's azimuth; the filter tracks the VEHICLE's heading. They differ
    // by however the driver seated the phone, so the compass is unusable until that offset is
    // known -- and it cannot be known in advance. Carried as a filter state instead: with a wide
    // prior on the offset and a tight one on heading, an early compass reading corrects the offset;
    // once the offset has converged and GNSS drops, the same reading corrects heading. The
    // covariance decides which, so there is no mode to switch and get wrong.
    //
    // OFF by default. Unlike useRoadBearingHeading this is a genuinely new fusion input, not a
    // restored one, and the magnetometer is the sensor the integration contract excluded for
    // vehicle distortion. Turn it on after a drive where telemetry's (mag_heading_deg - heading)
    // residual looks steady per mount at mag_accuracy = 3, which is what the compass columns were
    // added to answer.
    val useMagHeading: Boolean = false,
    /** Prior 1-std on the mount offset, degrees. Wide on purpose: the phone's orientation in the
     *  cradle is genuinely unknown, and a prior far wider than the heading's is what routes the
     *  first compass innovation into the offset rather than into heading.
     *
     *  90, not 180. The filter linearises, and on a circular quantity a 180-degree 1-std is
     *  effectively uniform -- precisely where a linearised update is least trustworthy. 90 still
     *  dwarfs the 30-degree heading prior by the factor that makes the routing work, and it keeps
     *  the innovation inside the range where the small-angle behaviour of the update holds. */
    val ekfInitialMountOffsetDeg: Float = 90f,
    /** Mount-offset random walk, deg per sqrt(second). Small but never zero: a converged offset
     *  with no process noise could never recover from the phone being knocked or re-seated
     *  mid-drive, which is the only thing that actually changes it. Sized by measurement, not
     *  taste -- MagneticHeadingTest.recoversAfterThePhoneIsReSeated drives a worst-case 180-degree
     *  re-seat, and the recovery times are 0.05 -> ~5 min, 0.2 -> ~60 s, 0.5 -> ~30 s. 0.2 is the
     *  slowest value that recovers inside a minute, and it stays a factor of 20 below the heading
     *  ARW, so during a blackout the compass innovation still lands on heading rather than being
     *  absorbed by an offset that has gone soft. */
    val ekfMountOffsetRandomWalkDegPerSqrtSec: Float = 0.2f,
    /** Compass measurement noise at the vendor's HIGH accuracy rating. Wider than GNSS bearing:
     *  even a well-calibrated compass sits inside a steel box with a running motor in it. */
    val ekfMagHeadingNoiseHighDeg: Float = 10f,
    /** At MEDIUM. Wide enough that a mediocre reading nudges rather than steers; LOW and
     *  UNRELIABLE are not used at all. */
    val ekfMagHeadingNoiseMediumDeg: Float = 25f,

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
    /**
     * Yaw-rate bound for a two-wheeler, deg/s. Overrides [maxYawRateDps] when the vehicle mode is
     * BIKE.
     *
     * A car body cannot rotate quickly; a leaning two-wheeler can. Measured on a motorbike ride
     * 2026-09-01: projected yaw reached **190 deg/s** with p99.9 at 102, and the samples above the
     * 90 deg/s bound were NOT isolated spikes — mean yaw in the 300 ms around each one was
     * **41 deg/s**, the signature of a genuine sustained turn. The bound was cutting into real turn
     * dynamics, costing about 5 degrees of true rotation across a 4.4 minute ride.
     *
     * This also corrects the reasoning in TODO.md G4, which argued that "a genuine vehicle turn
     * never approaches 90 deg/s". True for a car, false for a bike, and the bound was applied to
     * both.
     *
     * 200 sits above the measured maximum for a real turn and below the 271 deg/s recorded while
     * walking, which is the motion the bound exists to reject. The per-mode split is what makes both
     * numbers honest: one bound cannot serve a car body and a leaning bike.
     */
    val maxYawRateBikeDps: Float = 200f,

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
    /**
     * Maximum disagreement between our heading and the matched road's bearing before the match is
     * rejected as a wrong snap, degrees. Folded onto [0,90], so driving a two-way road in either
     * direction agrees.
     *
     * The uncertainty gate asks whether the matcher is CONFIDENT; this asks whether it is plausibly
     * CORRECT. On a dense grid those differ sharply — measured 2026-09-01 the matcher was on-road on
     * 88-100% of ticks with 8.8 m median uncertainty while visibly following the wrong streets.
     * Fusing a confident wrong snap is worse than not fusing: it drags the filter onto a parallel
     * road and reports high confidence in the result.
     *
     * 35 degrees admits genuine turns and curved roads while rejecting a snap onto a cross-street,
     * which is the failure this exists to catch.
     */
    val mapMatchMaxHeadingDisagreeDeg: Double = 35.0,

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
