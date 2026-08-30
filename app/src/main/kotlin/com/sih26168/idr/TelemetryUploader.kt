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

    private val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}".replace(' ', '_')
    private val sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

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
        queue.add(JSONObject().apply {
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
            putFinite("inference_ms", t.inferenceMs)
            putFinite("tick_ms", t.tickMs)
        })
    }

    private fun JSONObject.putFinite(key: String, v: Float) {
        if (v.isFinite()) put(key, v) else put(key, JSONObject.NULL)
    }

    private fun JSONObject.putFinite(key: String, v: Double) {
        if (v.isFinite()) put(key, v) else put(key, JSONObject.NULL)
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
                System.err.println("[TelemetryUploader] upload failed HTTP $code — rows dropped")
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
    }
}
