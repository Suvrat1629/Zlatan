package com.sih26168.idr.engine

import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.OutageRecord
import com.sih26168.idr.core.types.SessionSummary
import com.sih26168.idr.core.types.TelemetryMarker
import com.sih26168.idr.core.types.TelemetryTick
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Accumulates the residuals that answer the open questions in the telemetry design, and renders
 * them as a compact session summary.
 *
 * Deliberately platform-free so it runs unchanged in the replay harness on the JVM: the same
 * numbers can be produced from a recorded trace on a laptop as from a live drive on a phone, which
 * is what makes them comparable.
 *
 * Thread-safety: every mutating method is synchronized on this instance. The engine thread calls
 * [onTick]; the UI thread may call [snapshot] or [summary] at any time.
 */
class Diagnostics(
    private val sessionId: String,
    private val deviceModel: String = "unknown",
    private val appVersion: String = "unknown",
    private val modelVersion: String = "unknown",
    private var vehicle: String = "unspecified",
    private var mount: String = "unspecified",
    /** GNSS speed below which speed and heading comparisons are meaningless. */
    private val analysisFloorMps: Float = 3f,
) {
    private val speedRatios = ArrayList<Double>()
    private val speedResiduals = ArrayList<Double>()
    private val headingErrors = ArrayList<Double>()
    private val yawConsistency = ArrayList<Double>()
    private val inferenceMs = ArrayList<Double>()
    private val tickMs = ArrayList<Double>()
    private val imuGapsMs = ArrayList<Double>()
    private val markers = ArrayList<TelemetryMarker>()
    private val outages = ArrayList<OutageRecord>()
    private val warnings = LinkedHashSet<String>()

    private var imuSamples = 0L
    private var gnssFixes = 0L
    private var firstNanos = 0L
    private var lastNanos = 0L
    private var prevImuNanos = 0L
    private var shakeEvents = 0

    // Outage tracking
    private var outageStartNanos = 0L
    private var outageDistanceM = 0.0
    private var prevTickPos: LatLon? = null
    private var inOutage = false
    // The re-acquisition anchor arrives before the tick that closes the outage record, so it is
    // stashed here and consumed in onTick().
    private var pendingTruth: LatLon? = null
    private var pendingBelief: LatLon? = null

    private var latest: TelemetryTick? = null

    @Synchronized
    fun setContext(vehicle: String, mount: String) {
        this.vehicle = vehicle
        this.mount = mount
    }

    @Synchronized
    fun onImuSample(tNanos: Long) {
        imuSamples++
        if (firstNanos == 0L) firstNanos = tNanos
        lastNanos = tNanos
        if (prevImuNanos != 0L) {
            val gapMs = (tNanos - prevImuNanos) / 1e6
            // Ignore absurd gaps (app resumed, clock jump) so they do not swamp the jitter figure.
            if (gapMs in 0.0..1000.0) imuGapsMs.add(gapMs)
            if (gapMs > 500.0) warnings.add("IMU gap of ${"%.0f".format(gapMs)} ms — sensor delivery stalled")
            if (gapMs <= 0.0) warnings.add("Non-monotonic or duplicate IMU timestamp")
        }
        prevImuNanos = tNanos
    }

    @Synchronized
    fun onGnssFix(tNanos: Long) {
        gnssFixes++
    }

    @Synchronized
    fun addMarker(marker: TelemetryMarker) {
        markers.add(marker)
    }

    @Synchronized
    fun onTick(t: TelemetryTick) {
        latest = t
        if (t.tickMs.isFinite()) tickMs.add(t.tickMs.toDouble())
        if (t.inferenceMs.isFinite() && t.inferenceMs > 0f) inferenceMs.add(t.inferenceMs.toDouble())

        // --- speed residual, only where GNSS speed is meaningful ---
        val vg = t.vGnssMps
        if (vg.isFinite() && vg >= analysisFloorMps && t.vModelMps.isFinite()) {
            speedRatios.add((t.vModelMps / vg).toDouble())
            speedResiduals.add((t.vModelMps - vg).toDouble())
        }

        // --- heading residual against GNSS course while moving ---
        if (vg.isFinite() && vg >= analysisFloorMps && t.gnssBearingDeg.isFinite()) {
            headingErrors.add(abs(wrapDeg(t.headingDeg - t.gnssBearingDeg)))
        }

        // --- yaw-rate consistency: a real turn produces lateral force, a shake does not ---
        // Magnitude-based approximation. a_horiz mixes forward and lateral acceleration, so the
        // expected value is an upper bound: a_horiz should be at least |v * omega| during a turn.
        // Exact separation needs the vehicle-frame alignment that does not exist yet.
        val omega = abs(t.yawRateRadS)
        val v = if (t.vOutMps.isFinite()) t.vOutMps else 0f
        val expected = v * omega
        if (omega > 0.15f && v > analysisFloorMps && expected > 0.2f) {
            val ratio = (t.aHorizMps2 / expected).toDouble()
            yawConsistency.add(ratio)
            // Substantial rotation with no matching lateral force: the phone turned, not the car.
            if (ratio < 0.25) shakeEvents++
        }

        // --- outage accounting ---
        val pos = LatLon(t.lat, t.lon)
        val denied = t.mode == Mode.DEAD_RECKONING
        if (denied && !inOutage) {
            inOutage = true
            outageStartNanos = t.tNanos
            outageDistanceM = 0.0
            pendingTruth = null
            pendingBelief = null
        } else if (denied) {
            prevTickPos?.let { outageDistanceM += Geo.distanceM(it, pos) }
        } else if (inOutage) {
            inOutage = false
            // Error is measured against the first trusted fix after the outage, stashed by
            // onReacquisition(). Measuring against the engine's own corrected estimate would be
            // scoring the engine against itself.
            val errM = pendingTruth?.let { truth ->
                pendingBelief?.let { belief -> Geo.distanceM(truth, belief) }
            } ?: Double.NaN
            pendingTruth = null
            pendingBelief = null
            outages.add(
                OutageRecord(
                    startNanos = outageStartNanos,
                    endNanos = t.tNanos,
                    durationSeconds = (t.tNanos - outageStartNanos) / 1e9,
                    deadReckonedDistanceM = outageDistanceM,
                    errorM = errM,
                )
            )
        }
        prevTickPos = pos
    }

    /**
     * Records the true position at the moment GNSS is re-acquired. Called on every fix, but only
     * the one arriving during an outage is the anchor — and the outage record does not exist yet
     * at that point, so the pair is stashed and [onTick] consumes it when it closes the record.
     */
    @Synchronized
    fun onReacquisition(truth: LatLon, engineBelief: LatLon) {
        if (!inOutage) return
        pendingTruth = truth
        pendingBelief = engineBelief
    }

    /** One short line for the debug overlay and for logcat. */
    @Synchronized
    fun snapshot(): String {
        val t = latest ?: return "no ticks yet"
        val ratio = median(speedRatios)
        val head = median(headingErrors)
        return buildString {
            append("mode=${t.mode} ")
            append("v=${fmt(t.vOutMps * 3.6f)}km/h ")
            append("gnss=${if (t.vGnssMps.isFinite()) fmt(t.vGnssMps * 3.6f) else "--"}km/h ")
            append("ratio=${fmt(ratio)} ")
            append("hdgErr=${fmt(head)}deg ")
            append("sats=${t.satsInFix}/${t.irnssSatsInFix} ")
            append("rate=${fmt(imuRateHz())}Hz ")
            append("inf=${fmt(percentile(inferenceMs, 0.95))}ms ")
            append("shake=$shakeEvents")
        }
    }

    @Synchronized
    fun summary(): SessionSummary = SessionSummary(
        sessionId = sessionId,
        deviceModel = deviceModel,
        appVersion = appVersion,
        modelVersion = modelVersion,
        vehicle = vehicle,
        mount = mount,
        durationSeconds = if (lastNanos > firstNanos) (lastNanos - firstNanos) / 1e9 else 0.0,
        imuSamples = imuSamples,
        gnssFixes = gnssFixes,
        imuRateHz = imuRateHz(),
        imuJitterMs = stdDev(imuGapsMs),
        inferenceMsP50 = percentile(inferenceMs, 0.50),
        inferenceMsP95 = percentile(inferenceMs, 0.95),
        tickMsP95 = percentile(tickMs, 0.95),
        speedRatioMedian = median(speedRatios),
        speedRatioIqr = percentile(speedRatios, 0.75) - percentile(speedRatios, 0.25),
        speedSignedBiasMps = mean(speedResiduals),
        speedMaeMps = mean(speedResiduals.map { abs(it) }),
        speedPairs = speedRatios.size.toLong(),
        headingErrorMedianDeg = median(headingErrors),
        headingErrorP90Deg = percentile(headingErrors, 0.90),
        yawConsistencyMedian = median(yawConsistency),
        suspectedShakeEvents = shakeEvents,
        outages = outages.toList(),
        markers = markers.toList(),
        warnings = warnings.toList(),
    )

    /** True when anything was actually measured — used to skip writing an empty summary file. */
    @Synchronized
    fun hasData(): Boolean = latest != null

    private fun imuRateHz(): Double {
        val span = (lastNanos - firstNanos) / 1e9
        return if (span > 0.5) imuSamples / span else 0.0
    }

    private companion object {
        fun wrapDeg(d: Float): Double {
            var x = d.toDouble() % 360.0
            if (x > 180.0) x -= 360.0
            if (x < -180.0) x += 360.0
            return x
        }

        fun median(xs: List<Double>): Double = percentile(xs, 0.5)

        fun percentile(xs: List<Double>, p: Double): Double {
            if (xs.isEmpty()) return Double.NaN
            val sorted = xs.sorted()
            val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }

        fun mean(xs: List<Double>): Double = if (xs.isEmpty()) Double.NaN else xs.sum() / xs.size

        fun stdDev(xs: List<Double>): Double {
            if (xs.size < 2) return Double.NaN
            val m = xs.sum() / xs.size
            return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        }

        fun fmt(x: Double): String = if (x.isFinite()) "%.2f".format(x) else "--"
        fun fmt(x: Float): String = fmt(x.toDouble())
    }
}
