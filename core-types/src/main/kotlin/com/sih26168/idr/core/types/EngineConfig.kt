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
    val ekfInitialUncertaintyM: Float = 10f,
    val ekfSpeedNoiseMps: Float = 1.0f,
    val ekfHeadingArwDegPerSqrtSec: Float = 1.41f,
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
    // Still display-only either way -- see MapMatchResult's doc comment.
    val useHmmMapMatcher: Boolean = false,
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

    val outputRateHz: Double = 10.0,

    val engineTickP95BudgetMs: Float = 50f,
) {
    val windowSamples: Int get() = (modelRateHz * windowSeconds).toInt()

    companion object {

        val DEFAULT = EngineConfig()
    }
}
