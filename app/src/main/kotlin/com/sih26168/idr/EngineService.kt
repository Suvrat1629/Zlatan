package com.sih26168.idr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.PositioningEngine
import com.sih26168.idr.androidsensors.SensorSource
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
    private lateinit var recordingEngine: TripRecordingEngine
    private lateinit var sensorSource: SensorSource
    private lateinit var gnssSource: GnssSource

    val engine: PositioningEngine get() = recordingEngine
    val isRecording: Boolean get() = recordingEngine.isRecording

    override fun onCreate() {
        super.onCreate()
        val lastKnown = lastKnownLocation()
        rawEngine = if (lastKnown != null) EngineFactory.create(this, lastKnown) else EngineFactory.create(this)
        recordingEngine = TripRecordingEngine(rawEngine)
        sensorSource = SensorSource(this, recordingEngine)
        gnssSource = GnssSource(this, recordingEngine)
        createNotificationChannel()
    }

    private fun lastKnownLocation(): LatLon? {
        val locationManager = getSystemService(LocationManager::class.java) ?: return null
        return try {
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            location?.let { LatLon(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(recordingEngine.state.value.mode))
        recordingEngine.start()
        sensorSource.start()
        gnssSource.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sensorSource.stop()
        gnssSource.stop()
        recordingEngine.stopRecording()
        recordingEngine.stop()
        super.onDestroy()
    }

    fun setGnssMuted(muted: Boolean) = gnssSource.setMuted(muted)

    fun toggleRecording(): Boolean {
        if (recordingEngine.isRecording) {
            recordingEngine.stopRecording()
        } else {
            val dir = File(getExternalFilesDir(null), "traces").apply { mkdirs() }
            val name = "trip_${TRACE_TIMESTAMP_FORMAT.format(Date())}.csv"
            recordingEngine.startRecording(File(dir, name))
        }
        return recordingEngine.isRecording
    }

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
        private val TRACE_TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
