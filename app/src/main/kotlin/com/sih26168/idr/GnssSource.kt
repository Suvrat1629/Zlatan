package com.sih26168.idr

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.sih26168.idr.core.types.PositioningEngine
import java.util.concurrent.atomic.AtomicBoolean

class GnssSource(
    context: Context,
    private val engine: PositioningEngine,
) {
    @Volatile var gnssMuted: Boolean = false

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val statusThread = HandlerThread("idr-gnss-status").apply { start() }
    private val statusHandler = Handler(statusThread.looper)
    private val started = AtomicBoolean(false)

    @Volatile private var satsInFix = 0
    @Volatile private var irnssSatsInFix = 0
    @Volatile private var lastFixElapsedRealtimeNanos = 0L

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var total = 0
            var irnss = 0
            for (i in 0 until status.satelliteCount) {
                if (!status.usedInFix(i)) continue
                total++

                if (status.getConstellationType(i) == GnssStatus.CONSTELLATION_IRNSS) irnss++
            }
            satsInFix = total
            irnssSatsInFix = irnss
        }
    }

    private val locationListener = LocationListener { location: Location ->
        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        lastFixElapsedRealtimeNanos = nowElapsedRealtimeNanos
        if (gnssMuted) return@LocationListener

        engine.onGnssFix(
            tNanos = nowElapsedRealtimeNanos,
            lat = location.latitude, lon = location.longitude,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            bearingDeg = if (location.hasBearing()) location.bearing else 0f,
            horizAccM = if (location.hasAccuracy()) location.accuracy else 999f,
            satsInFix = satsInFix, irnssSatsInFix = irnssSatsInFix,
        )
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            System.err.println("[GnssSource] GPS_PROVIDER is disabled — ask the user to enable location.")
        }
        locationManager.registerGnssStatusCallback(gnssStatusCallback, statusHandler)

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,  1000L,  0f,
            locationListener, Looper.getMainLooper(),
        )
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        locationManager.removeUpdates(locationListener)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        statusThread.quitSafely()
    }

    fun setMuted(muted: Boolean, tNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        gnssMuted = muted
        if (muted) engine.onGnssLost(tNanos)
    }
}
