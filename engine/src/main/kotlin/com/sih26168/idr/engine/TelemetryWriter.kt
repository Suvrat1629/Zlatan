package com.sih26168.idr.engine

import com.sih26168.idr.core.types.SessionSummary
import com.sih26168.idr.core.types.TelemetryTick
import java.io.OutputStream
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File

/**
 * Tier 1 telemetry sink: one CSV row per engine tick, alongside the raw trace written by
 * TraceWriter. Buffered, flushed periodically, never per row — a logger that stutters corrupts the
 * data it is collecting.
 *
 * Schema version is in the header so a reader can tell what it is looking at when the columns
 * change, which they will.
 *
 * Version 3 is the union of two independent "schema 2"s that shipped on different branches -- the
 * GNSS reference position, and the filter/heading diagnostics. Bumped rather than reusing 2, since
 * two different column sets under one version number is worse than no version number at all.
 *
 *
 * Version 3 is the union of two independent "schema 2"s that shipped on different branches -- the
 * GNSS reference position, and the filter/heading diagnostics. Bumped rather than reusing 2, since
 * two different column sets under one version number is worse than no version number at all.
 *
 * Version 4 adds the handling detector's raw input and verdict (`tilt_rate_rps`, `handling`). The
 * threshold that gate uses is argued from vehicle physics rather than measured, so the column is
 * what lets the first real drive replace the argument with a distribution.
 *
 * Version 5 adds `vehicle_mode`. WALK damps the published speed, so without this column a reader
 * cannot tell whether damping was applied — which is how session 20260831-035044's mis-set selector
 * had to be inferred from the speed cap rather than simply read.
 *
 * Version 6 adds `gnss_muted`, so a deliberate blackout probe is distinguishable from real signal
 * loss — they are otherwise identical in the data, and they are not equivalent tests.
 */
class TelemetryWriter(
    private val writer: BufferedWriter,
    private val flushEveryNRows: Int = 100,
) : Closeable {
    constructor(file: File, flushEveryNRows: Int = 100) :
        this(file.bufferedWriter(), flushEveryNRows)

    constructor(stream: OutputStream, flushEveryNRows: Int = 100) :
        this(stream.bufferedWriter(), flushEveryNRows)
    private var rows = 0

    init {
        writer.write("#schema=6")
        writer.newLine()
        writer.write(HEADER)
        writer.newLine()
    }

    @Synchronized
    fun write(t: TelemetryTick) {
        writer.write(
            listOf(
                t.tNanos, t.vModelMps, t.dvMps2, t.vOutMps, t.vGnssMps, t.blendLambda,
                t.yawRateRadS, t.headingDeg, t.gnssBearingDeg, t.aHorizMps2,
                if (t.stationary) 1 else 0, t.tiltRateRadS, if (t.handling) 1 else 0,
                t.vehicleMode, if (t.gnssMuted) 1 else 0, t.mode, t.satsInFix, t.irnssSatsInFix,
                t.lat, t.lon, t.gnssLat, t.gnssLon, t.uncertaintyM,
                t.gyroBiasDps, t.headingUncertaintyDeg, t.gnssNis, t.yawClampCount,
                if (t.mapMatchOnRoad) 1 else 0, t.mapMatchUncertaintyM,
                t.inferenceMs, t.tickMs,
            ).joinToString(",")
        )
        writer.newLine()
        if (++rows >= flushEveryNRows) {
            writer.flush()
            rows = 0
        }
    }

    @Synchronized
    override fun close() {
        writer.flush()
        writer.close()
    }

    private companion object {
        const val HEADER =
            "t_nanos,v_model_mps,dv_mps2,v_out_mps,v_gnss_mps,blend_lambda," +
                "yaw_rate_rad_s,heading_deg,gnss_bearing_deg,a_horiz_mps2," +
                "stationary,tilt_rate_rps,handling,vehicle_mode,gnss_muted,mode,sats,irnss_sats,lat,lon,gnss_lat,gnss_lon,uncertainty_m," +
                "gyro_bias_dps,heading_unc_deg,gnss_nis,yaw_clamp_count," +
                "map_on_road,map_unc_m,inference_ms,tick_ms"
    }
}

/**
 * Renders a [SessionSummary] as the short, pasteable report. This is what a tester copies out of
 * the app to say "here is what we measured" without shipping a multi-megabyte trace.
 */
object SummaryReport {

    fun render(s: SessionSummary): String = buildString {
        appendLine("=== IDR session ${s.sessionId} ===")
        appendLine("device=${s.deviceModel}  app=${s.appVersion}  model=${s.modelVersion}")
        appendLine("vehicle=${s.vehicle}  mount=${s.mount}")
        appendLine("duration=${f(s.durationSeconds)}s  imu=${s.imuSamples}  gnss=${s.gnssFixes}")
        appendLine()
        appendLine("-- sensors --")
        appendLine("imu rate      ${f(s.imuRateHz)} Hz   jitter ${f(s.imuJitterMs)} ms")
        appendLine("inference     p50 ${f(s.inferenceMsP50)} ms   p95 ${f(s.inferenceMsP95)} ms")
        appendLine("engine tick   p95 ${f(s.tickMsP95)} ms")
        appendLine()
        appendLine("-- speed model vs GNSS (n=${s.speedPairs}) --")
        appendLine("ratio median  ${f(s.speedRatioMedian)}   IQR ${f(s.speedRatioIqr)}")
        appendLine("signed bias   ${f(s.speedSignedBiasMps)} m/s     MAE ${f(s.speedMaeMps)} m/s")
        appendLine("  (ratio near 1 with a wide IQR and near-zero bias = random error, not scale)")
        appendLine()
        appendLine("-- heading --")
        appendLine("error median  ${f(s.headingErrorMedianDeg)} deg   p90 ${f(s.headingErrorP90Deg)} deg")
        appendLine("yaw consistency median ${f(s.yawConsistencyMedian)}  (a_horiz / v*omega, expect >= 1)")
        appendLine("suspected shake events ${s.suspectedShakeEvents}")
        appendLine("yaw clamps at the physical bound ${s.yawClampCount}")
        appendLine()
        appendLine("-- filter --")
        appendLine("gyro bias     ${f(s.gyroBiasFinalDps)} deg/s   stability ${f(s.gyroBiasStabilityDps)} deg/s")
        appendLine("  (a converged bias is stable; one that keeps moving is absorbing noise)")
        appendLine("gnss NIS      median ${f(s.gnssNisMedian)}   p90 ${f(s.gnssNisP90)}")
        appendLine("  (chi-square 2 DOF: below ~6 is healthy. Decides if the NIS gate can reject)")
        appendLine("map matcher   on-road ${f(s.mapMatchOnRoadPercent)}% of ticks")
        appendLine()
        appendLine("-- outages (${s.outages.size}) --")
        if (s.outages.isEmpty()) appendLine("none")
        s.outages.forEachIndexed { i, o ->
            appendLine(
                "#${i + 1}  ${f(o.durationSeconds)}s  true=${f(o.trueDistanceM)}m  " +
                    "dr=${f(o.deadReckonedDistanceM)}m  over=${f(o.overTravelRatio)}x  " +
                    "err=${f(o.errorM)}m  drift=${f(o.driftPercent)}%" +
                    if (o.driftIsAgainstTruth) "" else "  (vs dr — no truth)"
            )
        }
        if (s.markers.isNotEmpty()) {
            appendLine()
            appendLine("-- markers --")
            s.markers.forEach { appendLine("${it.tNanos}  ${it.label}  ${it.note}") }
        }
        if (s.warnings.isNotEmpty()) {
            appendLine()
            appendLine("-- warnings --")
            s.warnings.forEach { appendLine("! $it") }
        }
    }

    private fun f(x: Double): String = if (x.isFinite()) "%.2f".format(x) else "--"
}
