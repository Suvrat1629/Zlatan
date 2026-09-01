package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.LatLon

interface FusionFilter {

    /**
     * Propagate one tick. [speedSigmaMps] is the 1-std uncertainty of [speedMps] for this
     * tick, when the speed source can supply one (Decision 3's variance head, plan2.md §2
     * step 5). Null means "no per-sample estimate" -- the filter falls back to its configured
     * process-noise constant. Passed as null everywhere today; wired through so the variance
     * head is a value change, not an interface change, when it lands.
     */
    fun predict(
        deadReckoned: LatLon,
        speedMps: Float,
        headingDeg: Double,
        dtSeconds: Double,
        speedSigmaMps: Float? = null,
    )

    fun updateWithGnss(fix: LatLon, speedMps: Float, bearingDeg: Float, horizAccM: Float, bearingValid: Boolean = false)

    /**
     * Fold in a map-matched position as a measurement (plan2.md §3 step 6). The match is
     * applied anisotropically about [roadBearingDeg]: [crossTrackSigmaM] across the road
     * (small -- this is the real information) and [alongTrackSigmaM] along it (large -- a
     * straight road says nothing about how far along it you are; architecture doc §4).
     *
     * Default no-op so `PassthroughFusionFilter` and any caller that never map-matches are
     * unaffected.
     */
    fun updateWithMapMatch(
        position: LatLon,
        alongTrackSigmaM: Float,
        crossTrackSigmaM: Float,
        roadBearingDeg: Double,
    ) {}

    /**
     * Zero-velocity gyro observation: the vehicle is known to be stationary, so the measured yaw
     * rate is the sensor's bias. Filters that track a bias state use it as a direct measurement;
     * default no-op for those that do not.
     */
    fun updateStationaryGyro(measuredYawRateRadS: Float) {}

    /**
     * The matched road's bearing as a heading measurement, with a 1-std in degrees. Only meaningful
     * for filters that track heading; default no-op for those that do not. The caller owns the
     * gating (confident match, moving, not mid-turn) — the filter owns the weighting.
     */
    fun updateWithRoadBearing(roadBearingDeg: Double, sigmaDeg: Float) {}

    /** Normalised innovation squared of the most recent GNSS position update, or NaN if none.
     *  Chi-square with 2 degrees of freedom: a healthy filter sits mostly below ~6. */
    fun lastGnssNis(): Double = Double.NaN

    /** GNSS fixes rejected by the innovation gate so far this session. */
    fun gnssRejectedCount(): Long = 0L

    /** Estimated yaw-rate bias in deg/s, or NaN for filters that do not track one. */
    fun gyroBiasDps(): Double = Double.NaN

    fun estimate(): LatLon

    fun uncertaintyM(): Float

    /** The filter's own corrected heading (degrees), if it tracks one. Null means "doesn't
     *  track heading" -- callers should fall back to their own heading estimator. */
    fun headingDeg(): Double? = null

    /** The filter's heading uncertainty (1 std, degrees), if it tracks one. Null otherwise. */
    fun headingUncertaintyDeg(): Double? = null
}

class PassthroughFusionFilter(initial: LatLon) : FusionFilter {
    private var current = initial

    override fun predict(
        deadReckoned: LatLon,
        speedMps: Float,
        headingDeg: Double,
        dtSeconds: Double,
        speedSigmaMps: Float?,
    ) {
        current = deadReckoned
    }

    override fun updateWithGnss(fix: LatLon, speedMps: Float, bearingDeg: Float, horizAccM: Float, bearingValid: Boolean) {
        current = fix
    }

    override fun estimate(): LatLon = current

    override fun uncertaintyM(): Float = 0f
}
