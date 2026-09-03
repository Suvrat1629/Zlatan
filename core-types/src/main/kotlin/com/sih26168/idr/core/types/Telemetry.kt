package com.sih26168.idr.core.types

/**
 * One row per engine tick — the Tier 1 pipeline intermediates from the telemetry design.
 *
 * Everything here is derivable from the raw trace in principle, but recording it directly turns
 * "the phone disagrees with the laptop" into a bisection rather than a mystery: it runs the parity
 * check continuously on live data instead of once against a fixture.
 */
data class TelemetryTick(
    val tNanos: Long,
    /** Absolute-speed model output, before blending, m/s. */
    val vModelMps: Float,
    /** Delta-model acceleration output after bias correction, m/s^2. NaN when no delta model. */
    val dvMps2: Float,
    /** Speed actually published after blend, ZUPT and vehicle-mode damping, m/s. */
    val vOutMps: Float,
    /** Most recent trusted GNSS speed, m/s. NaN before the first fix. */
    val vGnssMps: Float,
    /** Blend weight on the absolute model: 0 = pure anchor+delta, 1 = pure absolute. */
    val blendLambda: Float,
    /** Yaw rate used for heading this tick, rad/s, sign in the heading convention. */
    val yawRateRadS: Float,
    val headingDeg: Float,
    /** GNSS course at the last trusted fix, deg. NaN when unavailable. */
    val gnssBearingDeg: Float,
    /** Horizontal linear-acceleration magnitude from the feature window, m/s^2. */
    val aHorizMps2: Float,
    val stationary: Boolean,
    /** Window-mean gyro rate perpendicular to gravity, rad/s — the handling detector's raw input.
     *  Logged unconditionally so the physics-argued threshold can be replaced by a measured
     *  distribution after the first real drive, before it is trusted any further. */
    /** Real elapsed time this tick covered, milliseconds. Nominal is 1000/outputRateHz; anything
     *  much above it means the scheduler was starved, which used to be invisible because the engine
     *  integrated the nominal value regardless (TODO.md K1). */
    val tickIntervalMs: Float,
    val tiltRateRadS: Float,
    /** Whether the handling detector fired this tick. This is the VERDICT, independent of whether
     *  `use_handling_gate` let it act — with the gate in measure-only mode this column is the
     *  calibration data. */
    val handling: Boolean,
    /** The vehicle mode selected by the operator. Recorded because it changes the published speed
     *  (WALK damps by `walkingSpeedScale`) and was previously unrecoverable from the data: session
     *  20260831-035044 had to be diagnosed as a mis-set selector by inference from the speed cap
     *  (TODO.md H8). Nobody analysing a session should have to guess whether damping was applied. */
    val vehicleMode: VehicleMode,
    /** True when GNSS was deliberately muted for a blackout test rather than genuinely unavailable.
     *  Without this, a controlled probe and a real signal loss are identical in the data — both show
     *  DEAD_RECKONING and no fixes — yet they are not equivalent tests: a mute cuts cleanly from a
     *  good fix, real denial arrives after degraded multipath (TODO.md H10). */
    val gnssMuted: Boolean,
    val mode: Mode,
    val satsInFix: Int,
    val irnssSatsInFix: Int,
    val lat: Double,
    val lon: Double,
    /** Last trusted GNSS fix position — diverges from lat/lon during blackout, and that
     *  divergence is the model-failure signal this log exists to expose. NaN before first fix. */
    val gnssLat: Double,
    val gnssLon: Double,
    val uncertaintyM: Float,
    /** Estimated gyro yaw-rate bias, deg/s. NaN when the filter tracks no bias state.
     *  Whether this converges to a stable value per device is the check that the bias state is
     *  modelling bias rather than absorbing angle random walk. */
    val gyroBiasDps: Float,
    /** The filter's own heading 1-std, degrees. NaN when the filter tracks no heading. */
    val headingUncertaintyDeg: Float,
    /** Normalised innovation squared of the last GNSS position update. Chi-square, 2 DOF: a healthy
     *  filter sits mostly below ~6. This is measured before it is ever enforced. */
    val gnssNis: Float,
    /** Yaw-rate samples REJECTED for exceeding the physical vehicle bound so far this session
     *  (they contribute zero rotation rather than the clamped magnitude — see TODO.md G4).
     *  A rising count during ordinary driving means the bound is set wrong. */
    val yawClampCount: Long,
    /** Whether the map matcher found a road for this tick. */
    val mapMatchOnRoad: Boolean,
    /** The matcher's reported positional 1-std, metres. NaN when off-road. */
    val mapMatchUncertaintyM: Float,
    /** Wall time spent inside the model call, milliseconds. */
    val inferenceMs: Float,
    /** Wall time for the whole engine tick, milliseconds. */
    val tickMs: Float,
    /** The delta model's learned offset, m/s^2 — the amount already subtracted to produce
     *  [dvMps2]. NaN when no delta model is loaded. Reads as a convergence check, the same way
     *  [gyroBiasDps] does: it starts at 0 each session and should climb to a stable
     *  device-specific value within roughly 20 fixes. One that keeps wandering is tracking
     *  something other than a fixed model offset. */
    val dvBiasMps2: Float,
    /** Tilt-compensated compass azimuth, degrees clockwise from MAGNETIC north. NaN until the
     *  first usable reading, and on a phone with no magnetometer. Always recorded; fused only
     *  when `use_mag_heading` is on, which it is not by default. This is the phone's azimuth, not
     *  the vehicle's heading, and the offset between them depends on the mount. Read it against
     *  [headingDeg] — a residual that holds steady per mount at HIGH accuracy is what justifies
     *  turning that flag on. */
    val magHeadingDeg: Float,
    /** The vendor's own confidence in [magHeadingDeg]: SensorManager.SENSOR_STATUS_* (0 unreliable
     *  to 3 high), or -1 before any reading. Only HIGH readings mean anything for the residual. */
    val magAccuracy: Int,
    /** The filter's estimate of the phone-to-vehicle mount offset, degrees. NaN for filters that
     *  do not solve one, and NaN whenever `use_mag_heading` is off — an unfed mount state reads a
     *  perfectly plausible 0.0, and that must not be mistaken for a converged answer. A value that
     *  converges and then walks is the phone moving in its cradle. */
    val mountOffsetDeg: Float,
)

/** A moment the tester flagged, or an automatic event worth finding again in the log. */
data class TelemetryMarker(
    val tNanos: Long,
    val label: String,
    val note: String = "",
)

/** One GNSS-denied stretch, measured against truth at re-acquisition. */
data class OutageRecord(
    val startNanos: Long,
    val endNanos: Long,
    val durationSeconds: Double,
    /** Distance the engine believes it travelled while denied, metres. */
    val deadReckonedDistanceM: Double,
    /** Straight-line error between the engine's position and the first trusted fix, metres. */
    val errorM: Double,
    /** Ground-truth distance travelled during the outage, metres, from GNSS. NaN when unknown.
     *  This is the benchmark's denominator and the only honest one — see [driftPercent]. */
    val trueDistanceM: Double = Double.NaN,
) {
    /**
     * Drift as a percentage of distance travelled — the benchmark metric.
     *
     * **Divided by ground truth, not by the engine's own belief.** It used to divide by
     * [deadReckonedDistanceM], which is wrong in the worst possible way: the denominator is itself
     * a product of the error being measured, so the further the engine over-travels the smaller the
     * reported drift becomes. The metric improved as the system got worse.
     *
     * Measured on the 2026-08-31 bike session: the engine over-travelled 3.26x, and the old formula
     * reported a median 42% where the true figure against GNSS distance was 154%. Off by the
     * over-travel factor, in the flattering direction, on the headline number.
     *
     * Falls back to the old denominator only when truth is unavailable, and [driftIsAgainstTruth]
     * says which was used so a reader is never silently handed the optimistic one.
     */
    val driftPercent: Double
        get() = when {
            trueDistanceM > 1.0 -> errorM / trueDistanceM * 100.0
            deadReckonedDistanceM > 1.0 -> errorM / deadReckonedDistanceM * 100.0
            else -> Double.NaN
        }

    val driftIsAgainstTruth: Boolean get() = trueDistanceM > 1.0

    /** How much further the engine believed it travelled than it did. 1.0 is perfect; the bike
     *  session measured 3.26. This is the speed error made visible, and it is what corrupted the
     *  old drift metric. */
    val overTravelRatio: Double
        get() = if (trueDistanceM > 1.0) deadReckonedDistanceM / trueDistanceM else Double.NaN
}

/**
 * The small, pasteable end-of-session report. Deliberately compact: this is what a tester sends
 * to someone else to say "here is what we measured", without shipping a 40 MB trace.
 */
data class SessionSummary(
    val sessionId: String,
    val deviceModel: String,
    val appVersion: String,
    val modelVersion: String,
    val vehicle: String,
    val mount: String,
    val durationSeconds: Double,
    val imuSamples: Long,
    val gnssFixes: Long,
    val imuRateHz: Double,
    val imuJitterMs: Double,
    val inferenceMsP50: Double,
    val inferenceMsP95: Double,
    val tickMsP95: Double,
    /** v_model / v_gnss over samples with GNSS speed above the analysis floor. */
    val speedRatioMedian: Double,
    val speedRatioIqr: Double,
    /** Mean signed (v_model - v_gnss); near zero means random error, not a scale factor. */
    val speedSignedBiasMps: Double,
    val speedMaeMps: Double,
    val speedPairs: Long,
    /** Heading held by the engine minus GNSS course, degrees, while moving. */
    val headingErrorMedianDeg: Double,
    val headingErrorP90Deg: Double,
    /** a_horiz / (v * |omega|) during turns. Well below 1 suggests phone rotation, not vehicle. */
    val yawConsistencyMedian: Double,
    val suspectedShakeEvents: Int,
    /** Final gyro bias estimate, deg/s, and its spread over the second half of the session. A
     *  converged bias is stable; one that keeps moving is absorbing noise, not modelling bias. */
    val gyroBiasFinalDps: Double,
    val gyroBiasStabilityDps: Double,
    /** Distribution of the GNSS innovation, which is what decides whether the NIS gate can safely
     *  be switched from measuring to rejecting. */
    val gnssNisMedian: Double,
    val gnssNisP90: Double,
    val yawClampCount: Long,
    /** Share of ticks where the matcher found a road. */
    val mapMatchOnRoadPercent: Double,
    val outages: List<OutageRecord>,
    val markers: List<TelemetryMarker>,
    val warnings: List<String>,
)
