package com.sih26168.idr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.GeomagneticField
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.sih26168.idr.androidsensors.SensorSource
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.PositioningEngine
import com.sih26168.idr.core.replay.TraceWriter
import com.sih26168.idr.core.types.TelemetryTick
import com.sih26168.idr.core.types.VehicleMode
import com.sih26168.idr.engine.RealEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EngineService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): EngineService = this@EngineService
    }

    private val binder = LocalBinder()

    private lateinit var rawEngine: PositioningEngine
    /** Null when the model failed to load and EngineFactory returned a stub. */
    private var realEngine: RealEngine? = null
    private lateinit var recordingEngine: TripRecordingEngine
    private var sensorSource: SensorSource? = null
    private var gnssSource: GnssSource? = null

    lateinit var telemetry: TelemetrySession
        private set

    private val uploader = TelemetryUploader()

    private lateinit var dvBiasStore: DvBiasStore
    @Volatile private var lastDvBiasMps2 = Float.NaN
    private var dvBiasTickCounter = 0

    /** Drives the once-a-second telemetry logcat line; cancelled in onDestroy. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Session context for the telemetry summary header. Set from the UI before recording. */
    var currentVehicle: String = "unspecified"
    var currentMount: String = "unspecified"

    var startupFailed: Boolean = false
        private set

    val engine: PositioningEngine get() = recordingEngine
    val isRecording: Boolean get() = recordingEngine.isRecording

    override fun onCreate() {
        super.onCreate()
        val lastKnown = lastKnownLocation()
        // Telemetry is created before the engine so the engine can be handed its diagnostics
        // sink immediately.
        telemetry = TelemetrySession(this, EngineFactory.modelVersion(this))
        // Keep the uploaded rows and the local CSV on the same session id. They used to be
        // generated independently — the CSV from TelemetrySession, the cloud rows from the
        // uploader's own clock — so a local file and its cloud copy could not be matched up.
        telemetry.onSessionIdChanged = uploader::setSessionId
        uploader.setSessionId(telemetry.id)
        dvBiasStore = DvBiasStore(this, EngineFactory.deltaModelVersion(this))
        rawEngine = EngineFactory.create(
            context = this,
            startAt = lastKnown ?: LatLon(12.9716, 77.5946),
            initialDvBiasMps2 = dvBiasStore.load(),
        )
        recordingEngine = TripRecordingEngine(rawEngine)

        realEngine = rawEngine as? RealEngine
        if (realEngine == null) {
            // Otherwise this fails silently: every setTelemetry call is on a nullable receiver,
            // so a stub engine would disable telemetry with no error and no data written.
            Log.e(TAG_TEL, "engine is not RealEngine — model failed to load, telemetry disabled")
        }
        // Both sinks run from service start: the full-rate CSV on the phone AND the 0.5 Hz HTTP
        // upload. The CSV used to wait for the Record button, which meant the two records covered
        // different spans of the same drive and the local one was simply missing whenever nobody
        // pressed Record. The upload is a sampled convenience copy for the dashboard; the local
        // file is the lossless record, and it is the one that must never be optional.
        telemetry.startFileCapture(currentVehicle, currentMount)
        realEngine?.setTelemetry(telemetry.diagnostics, telemetry.telemetryWriter, ::onEngineTick)

        createNotificationChannel()
        try {
            sensorSource = SensorSource(this, recordingEngine).apply {
                // Magnetic declination turns the compass's magnetic azimuth into the true-north
                // frame the filter and GNSS bearing already use. Roughly -1 degree in Bengaluru,
                // and it varies by about 0.01 deg/km, so one reading at start covers a drive.
                lastKnown?.let {
                    declinationDeg = GeomagneticField(
                        it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis(),
                    ).declination
                }
            }
            val config = EngineFactory.loadConfig(this)
            gnssSource = GnssSource(
                this, recordingEngine,
                accuracyGateM = config.gnssAccuracyGateM,
                starvedAfterSeconds = config.gnssStarvedAfterSeconds,
                starvedAccuracyCeilingM = config.gnssStarvedAccuracyCeilingM,
            ).apply {
                truthSink = { lat, lon -> realEngine?.onGnssTruthOnly(lat, lon) }
            }
        } catch (e: SensorSource.NoGyroscopeException) {
            startupFailed = true
            Toast.makeText(this, getString(R.string.no_gyroscope_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun lastKnownLocation(): LatLon? {
        val locationManager = getSystemService(LocationManager::class.java) ?: return null
        return try {
            val fromGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val fromNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = listOfNotNull(fromGps, fromNetwork).maxByOrNull { it.time }
            best?.let { LatLon(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(recordingEngine.state.value.mode))
        if (startupFailed) {
            stopSelf()
            return START_NOT_STICKY
        }
        recordingEngine.start()
        sensorSource?.start()
        gnssSource?.start()
        // One published position per engine tick; TelemetrySession throttles to one log line
        // per second, so the engine itself stays platform-free.
        serviceScope.launch {
            recordingEngine.state.collect { telemetry.onPublished() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        sensorSource?.stop()
        gnssSource?.stop()
        recordingEngine.stopRecording()
        // Detach the engine's sinks BEFORE closing the writer, or a tick in flight could write to
        // a closed stream. Then write the summary even if Record was never stopped, so a killed
        // session still leaves behind the numbers it measured.
        realEngine?.setTelemetry(null, null, null)
        telemetry.stopFileCapture()
        saveDvBias()
        uploader.close()
        recordingEngine.stop()
        super.onDestroy()
    }

    /**
     * Tick sink for everything that is not the CSV. Also checkpoints the learned dv-bias: saving
     * only in onDestroy would lose it whenever the system kills the service outright, which is
     * the common way a long drive ends.
     */
    private fun onEngineTick(t: TelemetryTick) {
        uploader.onTick(t)
        if (++dvBiasTickCounter % DV_BIAS_SAVE_EVERY_TICKS == 0) saveDvBias(t.dvBiasMps2)
        lastDvBiasMps2 = t.dvBiasMps2
    }

    private fun saveDvBias(value: Float = lastDvBiasMps2) {
        if (value.isFinite()) dvBiasStore.save(value)
    }

    fun setVehicleMode(mode: VehicleMode) {
        realEngine?.setVehicleMode(mode)
    }

    fun setGnssMuted(muted: Boolean) {
        gnssSource?.setMuted(muted)
        realEngine?.setGnssMuted(muted)
        // The blackout toggle is the most important instant in a controlled test and was previously
        // recorded nowhere. A marker puts it in the session summary; the per-row `gnss_muted` column
        // makes it recoverable from the CSV without cross-referencing timestamps.
        telemetry.mark(if (muted) "blackout-on" else "blackout-off")
    }

    /**
     * Record controls the raw IMU trace (`trip_*.csv`) and closes off a telemetry session.
     *
     * It no longer gates the telemetry CSV itself — that now runs from service start alongside the
     * upload, so a drive is never lost to nobody having pressed the button. Stopping still rolls
     * the session: it writes the summary, starts a fresh id, and reopens the CSV under it, which
     * keeps one session per file rather than one file per app launch.
     */
    fun toggleRecording(): Boolean {
        if (recordingEngine.isRecording) {
            recordingEngine.stopRecording()
            telemetry.stopFileCapture()
            telemetry.startFileCapture(currentVehicle, currentMount)
            realEngine?.setTelemetry(telemetry.diagnostics, telemetry.telemetryWriter)
        } else {
            // Shared storage, and named with the SAME session id as the telemetry CSV.
            //
            // The raw trace used to land in app-private storage under a timestamp of its own. On
            // Android 11+ that folder is not browsable, so retrieving a trace needed adb — and the
            // independent timestamp meant matching a trace to its telemetry was a manual job of
            // comparing clocks. Both problems bit at once when the v3 negatives needed exactly one
            // trace and nobody could tell which file it was, or reach it. See TODO.md K5.
            val name = "trip_${telemetry.id}.csv"
            val stream = SharedStorage.openForWrite(this, name, "text/csv")
            if (stream != null) {
                recordingEngine.startRecording(TraceWriter(stream))
                Log.i(TAG_TEL, "raw trace -> Documents/IDR/$name")
            } else {
                val dir = File(getExternalFilesDir(null), "traces").apply { mkdirs() }
                Log.e(TAG_TEL, "shared storage unavailable — raw trace falling back to app-private ${dir.path}")
                recordingEngine.startRecording(File(dir, name))
            }
            telemetry.setContext(currentVehicle, currentMount)
            realEngine?.setTelemetry(telemetry.diagnostics, telemetry.telemetryWriter)
        }
        return recordingEngine.isRecording
    }

    /** One tap from the UI when something looks wrong, so the moment is findable in the log. */
    fun mark(label: String, note: String = "") = telemetry.mark(label, note)

    /** The one-line live readout for the debug overlay. */
    fun telemetryLine(): String = telemetry.liveLine()

    /** The pasteable session report. */
    fun telemetrySummary(): String? = telemetry.summaryText()

    fun updateNotification(mode: Mode) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(mode))
    }

    private fun buildNotification(mode: Mode): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val modeText = when (mode) {
            Mode.INIT -> getString(R.string.mode_init)
            Mode.NAVIC -> getString(R.string.mode_navic)
            Mode.GNSS -> getString(R.string.mode_gnss)
            Mode.DEGRADED -> getString(R.string.mode_degraded)
            Mode.DEAD_RECKONING -> getString(R.string.mode_dead_reckoning)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(modeText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows the current positioning mode while navigating." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "idr_navigation"
        private const val TAG_TEL = "IDR-TEL"
        /** Checkpoint the learned dv-bias every 30 s at the 10 Hz tick rate. Often enough that a
         *  killed service loses almost nothing, rare enough to stay off the tick thread's back. */
        private const val DV_BIAS_SAVE_EVERY_TICKS = 300
    }
}
