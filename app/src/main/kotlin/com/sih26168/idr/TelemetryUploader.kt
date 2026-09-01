package com.sih26168.idr

import android.os.Build
import com.sih26168.idr.core.types.TelemetryTick
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ships telemetry to the team's Supabase table so every device's drives land in one
 * dashboard-queryable place instead of per-phone CSVs.
 *
 * Design choices, deliberate:
 *  - Uploads a 0.5 Hz sample (every [SAMPLE_EVERY]th tick), not the full 10 Hz stream — the
 *    cloud copy is for cross-device analysis and the live dashboard; the lossless record
 *    stays in the on-device CSV (never decimate at capture, F-block rule).
 *  - Batched POSTs on a single background thread, queue capped so a dead network can never
 *    grow memory without bound; drops oldest first and says so once per session.
 *  - Plain HttpURLConnection: no new dependencies.
 *  - The anon key is the public client credential (RLS insert/select only) — acceptable for
 *    hackathon telemetry, not an auth model.
 */
class TelemetryUploader : Closeable {

    private val queue = ConcurrentLinkedQueue<JSONObject>()
    @Volatile private var queued = 0
    private var tickCounter = 0
    private var warnedDrop = false
    @Volatile private var legacyColumnsOnly = false

    private val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}".replace(' ', '_')

    /** Set by [EngineService] from [TelemetrySession.id] so uploaded rows carry the same id as the
     *  local CSV. They used to be generated independently -- the CSV from TelemetrySession, these
     *  rows from this class's own clock -- so a local file and its cloud copy could not be matched
     *  up. The default only exists so the uploader is usable standalone; the app overwrites it
     *  before the first tick. */
    @Volatile private var sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun setSessionId(id: String) { sessionId = id }

    private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "telemetry-upload").apply { isDaemon = true }
    }

    init {
        executor.scheduleWithFixedDelay({ flush() }, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS)
    }

    /** Called from the engine tick thread — must stay cheap. */
    fun onTick(t: TelemetryTick) {
        if (tickCounter++ % SAMPLE_EVERY != 0) return
        if (queued >= MAX_QUEUE) {
            queue.poll()
            if (!warnedDrop) {
                warnedDrop = true
                System.err.println("[TelemetryUploader] queue full — dropping oldest rows (network down?)")
            }
        } else {
            queued++
        }
        val row = JSONObject().apply {
            // Stamp at capture, not at insert: DB-default ts gave every row in a batch the
            // same time, and rows are only individually placeable via boot-relative t_nanos.
            put("ts", ISO_UTC.format(Date()))
            put("device_id", deviceId)
            put("session_id", sessionId)
            put("t_nanos", t.tNanos)
            putFinite("v_model", t.vModelMps)
            putFinite("dv", t.dvMps2)
            putFinite("v_out", t.vOutMps)
            putFinite("v_gnss", t.vGnssMps)
            putFinite("lambda", t.blendLambda)
            putFinite("yaw_rate", t.yawRateRadS)
            putFinite("heading", t.headingDeg)
            putFinite("gnss_bearing", t.gnssBearingDeg)
            putFinite("a_horiz", t.aHorizMps2)
            put("stationary", t.stationary)
            put("mode", t.mode.name)
            put("sats", t.satsInFix)
            put("irnss", t.irnssSatsInFix)
            put("lat", t.lat)
            put("lon", t.lon)
            putFinite("gnss_lat", t.gnssLat)
            putFinite("gnss_lon", t.gnssLon)
            putFinite("uncertainty_m", t.uncertaintyM)
            // Diagnostics added after the uploader was written, and silently absent from every row
            // collected before 2026-08-31 (TODO.md H2). These are not optional extras: the NIS gate,
            // the map-match fusion flag and the gyro bias state are all deliberately left disabled
            // until a real drive supplies their distributions, and these columns ARE those
            // distributions. Without them the one drive that is supposed to settle those questions
            // uploads no evidence for any of them.
            putFinite("gyro_bias_dps", t.gyroBiasDps)
            // The delta model's learned device offset, same reasoning: it converges or it does not,
            // and a stdout line cannot say which after the fact.
            putFinite("dv_bias", t.dvBiasMps2)
            putFinite("heading_unc_deg", t.headingUncertaintyDeg)
            putFinite("gnss_nis", t.gnssNis)
            put("yaw_clamp_count", t.yawClampCount)
            put("map_on_road", t.mapMatchOnRoad)
            putFinite("map_unc_m", t.mapMatchUncertaintyM)
            // Handling gate (TODO.md G1/H4): the threshold is argued from vehicle physics and is
            // measurably too high for the pedestrian phantom. Uploading its raw input is what lets
            // a drive replace the argument with a distribution.
            putFinite("tick_interval_ms", t.tickIntervalMs)
            putFinite("tilt_rate_rps", t.tiltRateRadS)
            put("handling", t.handling)
            put("vehicle_mode", t.vehicleMode.name)
            put("gnss_muted", t.gnssMuted)
            putFinite("mag_heading_deg", t.magHeadingDeg)
            put("mag_accuracy", t.magAccuracy)
            putFinite("inference_ms", t.inferenceMs)
            putFinite("tick_ms", t.tickMs)
        }
        queue.add(if (legacyColumnsOnly) stripToLegacy(row) else row)
    }

    private fun JSONObject.putFinite(key: String, v: Float) {
        if (v.isFinite()) put(key, v) else put(key, JSONObject.NULL)
    }

    private fun JSONObject.putFinite(key: String, v: Double) {
        if (v.isFinite()) put(key, v) else put(key, JSONObject.NULL)
    }

    /**
     * Drop the post-schema-2 diagnostic columns from a queued row, for the fallback above. Listed
     * explicitly rather than by a whitelist of legacy names so that adding a new column to
     * [onTick] and forgetting it here fails visibly at the server rather than being quietly
     * stripped on every upload.
     */
    private fun stripToLegacy(row: JSONObject): JSONObject {
        for (k in EXTENDED_COLUMNS) row.remove(k)
        return row
    }

    private fun flush() {
        if (queue.isEmpty()) return
        val batch = JSONArray()
        while (batch.length() < MAX_BATCH) {
            val row = queue.poll() ?: break
            queued--
            batch.put(row)
        }
        if (batch.length() == 0) return
        try {
            val conn = URL("$SUPABASE_URL/rest/v1/telemetry").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("apikey", ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $ANON_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.outputStream.use { it.write(batch.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                conn.disconnect()
                // A schema mismatch is the one failure worth surviving rather than reporting.
                // PostgREST answers 400/404/422 with PGRST204 when a posted column does not exist
                // on the table, and without this the app would silently upload NOTHING for the
                // whole session -- on exactly the drive whose telemetry we most need. Degrade to
                // the columns the table is known to have, loudly, and keep the drive's data.
                if (code in listOf(400, 404, 422) && !legacyColumnsOnly) {
                    legacyColumnsOnly = true
                    System.err.println(
                        "[TelemetryUploader] server rejected the full row (HTTP $code: ${body.take(200)}). " +
                            "The telemetry table is missing the diagnostic columns — run the migration in " +
                            "TODO.md H2. Falling back to the legacy column set so this session still uploads; " +
                            "gyro bias, NIS, map-match and handling data will NOT be recorded."
                    )
                    for (i in 0 until batch.length()) {
                        queue.add(stripToLegacy(batch.getJSONObject(i)))
                        queued++
                    }
                    return
                }
                System.err.println("[TelemetryUploader] upload failed HTTP $code — rows dropped")
                return
            }
            conn.disconnect()
        } catch (e: Exception) {
            // network down: put the batch back (bounded by MAX_QUEUE on the next onTick)
            for (i in 0 until batch.length()) {
                queue.add(batch.getJSONObject(i))
                queued++
            }
        }
    }

    override fun close() {
        flush()
        executor.shutdown()
    }

    companion object {
        private const val SUPABASE_URL = "https://brkfxezmwdhufpblnowp.supabase.co"
        private const val ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJya2Z4ZXptd2RodWZwYmxub3dwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgxMDExNzYsImV4cCI6MjEwMzY3NzE3Nn0.FmlckIWMuHBmve5_gl0TXMlbJ2WdR3KAqBXV8IgPZx8"
        private const val SAMPLE_EVERY = 20      // 10 Hz ticks -> one uploaded row per 2 s
        private const val FLUSH_SECONDS = 10L
        private const val MAX_BATCH = 60
        private const val MAX_QUEUE = 600        // ~20 min of offline buffering

        /** Columns added after the cloud table was created (TODO.md H2). Dropped by the fallback
         *  when the server says the table does not have them yet. */
        private val EXTENDED_COLUMNS = listOf(
            "gyro_bias_dps", "heading_unc_deg", "gnss_nis", "yaw_clamp_count",
            "map_on_road", "map_unc_m", "tilt_rate_rps", "handling", "vehicle_mode", "gnss_muted", "tick_interval_ms",
            "dv_bias", "mag_heading_deg", "mag_accuracy",
        )
    }
}
