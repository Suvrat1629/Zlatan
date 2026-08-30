package com.sih26168.idr.core.types

data class EngineConfig(

    val modelRateHz: Double = 10.0,

    val windowSeconds: Double = 5.0,

    val antiAliasCutoffHz: Double = 4.0,

    val ringBufferSeconds: Double = 8.0,

    val coldStartSeconds: Double = 5.0,

    val speedMinMps: Float = 0f,
    val speedMaxMps: Float = 60f,

    // Time-varying blend gain timescale: lam = t/(t+tau), t = seconds since GNSS anchor.
    val blendTauSeconds: Double = 240.0,

    // ZUPT: below BOTH thresholds over a window, speed is clamped to 0 instantly.
    val zuptAccelThresholdMps2: Float = 0.8f,
    val zuptGyroThresholdRps: Float = 0.15f,

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
