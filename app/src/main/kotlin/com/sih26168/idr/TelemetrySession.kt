package com.sih26168.idr

import android.content.Context
import android.os.Build
import android.util.Log
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.SessionSummary
import com.sih26168.idr.core.types.TelemetryMarker
import com.sih26168.idr.engine.Diagnostics
import com.sih26168.idr.engine.SummaryReport
import com.sih26168.idr.engine.TelemetryWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns one recording session's telemetry: the per-tick CSV, the running diagnostics, the periodic
 * logcat line, and the short summary a tester can copy out.
 *
 * Three ways to see what the engine is doing, in increasing weight:
 *
 *  1. `adb logcat -s IDR-TEL` — one line a second, live, no files involved.
 *  2. `summary_*.txt` — a few hundred bytes, pasteable into a message.
 *  3. `trip_*.csv` + `tel_*.csv` — the full record, for offline replay and model training.
 *
 * Two sinks run in parallel from service start: this full-rate local CSV, and the 0.5 Hz HTTP
 * upload in [TelemetryUploader]. They are not alternatives. The upload is a sampled convenience
 * copy that makes a drive visible on the dashboard within seconds; the local file is the lossless
 * record and the only one suitable for replay or training. The local CSV used to wait for the
 * Record button while the upload ran regardless, so the two covered different spans of the same
 * drive and the local — more valuable — copy was simply missing whenever nobody pressed Record.
 *
 * Both carry the same [id], so a local file and its cloud rows can be joined.
 */
class TelemetrySession(
    private val context: Context,
    private val modelVersion: String,
) {
    private var writer: TelemetryWriter? = null
    private var diag: Diagnostics = newDiagnostics()
    private var sessionId: String = ""
    private var logCounter = 0

    val diagnostics: Diagnostics get() = diag
    val telemetryWriter: TelemetryWriter? get() = writer

    /** The id shared by the local CSV filenames and the uploaded rows, so a session recorded in
     *  both places can be joined. They used to be generated independently — the CSV from this
     *  class, the cloud rows from [TelemetryUploader]'s own clock — which made a local file and its
     *  cloud copy impossible to match up. */
    val id: String get() = sessionId

    /** Notified whenever a new session starts, so the uploader can follow the same id. */
    var onSessionIdChanged: ((String) -> Unit)? = null

    private fun newDiagnostics(): Diagnostics {
        sessionId = STAMP.format(Date())
        onSessionIdChanged?.invoke(sessionId)
        return Diagnostics(
            sessionId = sessionId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})",
            appVersion = BuildConfigCompat.versionName(context),
            modelVersion = modelVersion,
        )
    }
    /** Opens the per-tick CSV in shared storage. Diagnostics were already running. */
    fun startFileCapture(vehicle: String, mount: String) {
        diag.setContext(vehicle, mount)
        val stream = SharedStorage.openForWrite(context, "tel_$sessionId.csv", "text/csv")
        writer = stream?.let { TelemetryWriter(it) }
        Log.i(TAG, if (writer != null) "capture $sessionId -> Documents/IDR/tel_$sessionId.csv"
                   else "capture $sessionId: shared storage unavailable, diagnostics only")
    }

    /**
     * Closes the CSV, writes the summary, starts a fresh session.
     *
     * A no-op when no capture is open. Both `onDestroy` and the Record button can reach this, and
     * without the guard the second call would write a summary for a session that recorded nothing.
     */
    fun stopFileCapture() {
        if (writer == null) return
        // Close any blackout still in progress BEFORE rendering the summary, or a session that
        // ends mid-outage silently drops it — and those are the longest outages there are.
        diag.closeOpenOutage(System.nanoTime())
        writer?.close(); writer = null
        val text = SummaryReport.render(diag.summary())
        SharedStorage.openForWrite(context, "summary_$sessionId.txt", "text/plain")
            ?.use { it.write(text.toByteArray()) }
        text.lineSequence().forEach { Log.i(TAG, it) }
        diag = newDiagnostics()
    }

    /** Vehicle/mount for the summary header, settable without reopening the CSV. */
    fun setContext(vehicle: String, mount: String) = diag.setContext(vehicle, mount)

    fun mark(label: String, note: String = "") {
        diag?.addMarker(TelemetryMarker(System.nanoTime(), label, note))
        Log.i(TAG, "marker: $label $note")
    }

    fun onReacquisition(truth: LatLon, belief: LatLon) = diag?.onReacquisition(truth, belief)

    /** Call once per published position. Emits one logcat line a second, not one per tick. */
    fun onPublished() {
        val d = diag ?: return
        if (logCounter++ % LOG_EVERY_N_TICKS != 0) return
        Log.i(TAG, d.snapshot())
    }

    fun liveLine(): String = diag?.snapshot() ?: "telemetry off"

    fun summaryText(): String? = diag?.let { SummaryReport.render(it.summary()) }

    private companion object {
        const val TAG = "IDR-TEL"
        /** Engine publishes at 10 Hz, so every tenth tick is one line per second. */
        const val LOG_EVERY_N_TICKS = 10
        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

/** Kept separate so the telemetry code does not depend on the generated BuildConfig class. */
private object BuildConfigCompat {
    fun versionName(context: Context): String = try {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        pkg.versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
