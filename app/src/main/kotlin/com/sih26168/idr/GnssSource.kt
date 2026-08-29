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

        val accuracyM = if (location.hasAccuracy()) location.accuracy else null

        // Tiered acceptance:
        //  - <= MAX_ACCEPTABLE_ACCURACY_M and enough sats: full-trust fix (position+speed+bearing).
        //  - <= COARSE_ACCURACY_M: coarse position-only snap. Without this, a user indoors sits
        //    at a stale position ("wrong galaxy") until a clean fix arrives; a 30-150 m indoor
        //    fix is still vastly better than kilometres-stale. Speed/bearing are zeroed so the
        //    weak fix can't steer heading or velocity, and the reported horizAcc lets the
        //    fusion side de-weight it.
        //  - worse than COARSE_ACCURACY_M: rejected.
        val fullTrust = (accuracyM == null || accuracyM <= MAX_ACCEPTABLE_ACCURACY_M) &&
            (satsInFix == 0 || satsInFix >= MIN_SATS_FOR_TRUST)
        val coarse = !fullTrust && accuracyM != null && accuracyM <= COARSE_ACCURACY_M
        if (!fullTrust && !coarse) {
            System.err.println("[GnssSource] rejected fix (accuracy=${accuracyM}m sats=$satsInFix) — worse than coarse threshold ${COARSE_ACCURACY_M}m")
            return@LocationListener
        }
        if (coarse) {
            System.err.println("[GnssSource] coarse fix accepted for position-only snap (accuracy=${accuracyM}m sats=$satsInFix)")
        }

        engine.onGnssFix(
            tNanos = nowElapsedRealtimeNanos,
            lat = location.latitude, lon = location.longitude,
            speedMps = if (fullTrust && location.hasSpeed()) location.speed else 0f,
            bearingDeg = if (fullTrust && location.hasBearing()) location.bearing else 0f,
            horizAccM = accuracyM ?: 999f,
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

    companion object {
        private const val MAX_ACCEPTABLE_ACCURACY_M = 30f
        private const val COARSE_ACCURACY_M = 150f
        private const val MIN_SATS_FOR_TRUST = 4
    }
}
