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
    /** Yaw-rate samples clamped to the physical vehicle bound so far this session. */
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
     *  first usable reading, and on a phone with no magnetometer. Recorded, never fused: this
     *  is the phone's azimuth, not the vehicle's heading, and the offset between them depends on
     *  the mount. Read it against [headingDeg] — a residual that holds steady per mount at HIGH
     *  accuracy is what would justify building the mount-offset calibrator that fusing it needs. */
    val magHeadingDeg: Float,
    /** The vendor's own confidence in [magHeadingDeg]: SensorManager.SENSOR_STATUS_* (0 unreliable
     *  to 3 high), or -1 before any reading. Only HIGH readings mean anything for the residual. */
    val magAccuracy: Int,
    /** The filter's estimate of the phone-to-vehicle mount offset, degrees; NaN for filters that
     *  do not solve one. Only meaningful once the compass has actually been fed — until then it is
     *  still the (deliberately enormous) prior, so read it beside [magAccuracy]. A converged offset
     *  that then walks is the phone moving in its cradle. */
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
) {
    val driftPercent: Double
        get() = if (deadReckonedDistanceM > 1.0) errorM / deadReckonedDistanceM * 100.0 else Double.NaN
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
