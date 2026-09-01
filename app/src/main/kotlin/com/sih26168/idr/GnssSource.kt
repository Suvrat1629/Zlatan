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
import com.sih26168.idr.core.nav.ModeArbiter
import com.sih26168.idr.core.types.PositioningEngine
import java.util.concurrent.atomic.AtomicBoolean

class GnssSource(
    context: Context,
    private val engine: PositioningEngine,
    private val accuracyGateM: Float = 30f,
    private val starvedAfterSeconds: Float = 10f,
    private val starvedAccuracyCeilingM: Float = 150f,
) {
    @Volatile var gnssMuted: Boolean = false

    /** Where withheld fixes go for scoring. Set by [EngineService]; never wired to the filter. */
    @Volatile var truthSink: ((Double, Double) -> Unit)? = null

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val statusThread = HandlerThread("idr-gnss-status").apply { start() }
    private val statusHandler = Handler(statusThread.looper)
    private val started = AtomicBoolean(false)

    // ModeArbiter.SATS_UNKNOWN, not 0: the GnssStatus callback is timed independently of the
    // location callback and has not fired yet at this point. Reporting 0 here made the mode
    // arbiter announce DEAD_RECKONING while live fixes were being fused normally (TODO.md G5).
    @Volatile private var satsInFix = ModeArbiter.SATS_UNKNOWN
    @Volatile private var irnssSatsInFix = 0
    @Volatile private var lastFixElapsedRealtimeNanos = 0L
    @Volatile private var lastAcceptedElapsedNanos = 0L

    private val accepted = java.util.concurrent.atomic.AtomicLong(0)
    private val rejectedForAccuracy = java.util.concurrent.atomic.AtomicLong(0)
    private val rejectedForSatellites = java.util.concurrent.atomic.AtomicLong(0)
    private val mutedDrops = java.util.concurrent.atomic.AtomicLong(0)
    private val implausibleCounts = java.util.concurrent.atomic.AtomicLong(0)
    private val fixesWithIrnss = java.util.concurrent.atomic.AtomicLong(0)
    private val totalStatusUpdates = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var warnedImplausible = false
    @Volatile private var satsVisible = 0

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var total = 0
            var irnss = 0
            var visible = 0
            for (i in 0 until status.satelliteCount) {
                visible++
                if (!status.usedInFix(i)) continue
                total++
                if (status.getConstellationType(i) == GnssStatus.CONSTELLATION_IRNSS) irnss++
            }
            // Sanity bound on the used-in-fix count (TODO.md K9). Measured on a real ride the count
            // sat between 16 and 37, which is normal for a modern multi-constellation receiver, but
            // three fixes out of 83 reported 58-62 and an earlier session reported 78. No receiver
            // solves a position from that many satellites; the vendor's usedInFix flag is
            // over-reporting, most likely while constellations are being switched in and out.
            //
            // The count is not navigation-critical -- the mode arbiter only asks whether it is zero
            // -- but it IS the number that evidences the NavIC requirement in a demo, so a
            // physically impossible value must be visible rather than silently published.
            if (total > MAX_PLAUSIBLE_SATS_IN_FIX) {
                implausibleCounts.incrementAndGet()
                if (!warnedImplausible) {
                    warnedImplausible = true
                    System.err.println(
                        "[GnssSource] receiver reported $total satellites used in fix out of $visible " +
                            "visible — implausible, capping at $MAX_PLAUSIBLE_SATS_IN_FIX. The count is " +
                            "reported, not trusted; NavIC evidence should cite the IRNSS figure."
                    )
                }
                total = MAX_PLAUSIBLE_SATS_IN_FIX
            }
            satsInFix = total
            irnssSatsInFix = irnss
            satsVisible = visible
            if (irnss > 0) fixesWithIrnss.incrementAndGet()
            totalStatusUpdates.incrementAndGet()
        }
    }

    private val locationListener = LocationListener { location: Location ->
        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        lastFixElapsedRealtimeNanos = nowElapsedRealtimeNanos
        if (gnssMuted) {
            mutedDrops.incrementAndGet()
            // The fix is withheld from the ENGINE, not from the record. During a blackout probe the
            // phone still has a good fix and the app is merely choosing not to navigate by it —
            // throwing it away too meant the outage could only be scored endpoint-to-endpoint,
            // which understates a path with turns. This reaches diagnostics only.
            truthSink?.invoke(location.latitude, location.longitude)
            return@LocationListener
        }

        // Escalating accuracy gate (EngineConfig.gnssAccuracyGateM and friends): strict while
        // fixes are flowing, but once the engine has been starved past starvedAfterSeconds the
        // ceiling takes over and the fix goes through with its REAL horizontal accuracy, so
        // downstream weighting can discount it instead of the pre-filter pretending it doesn't
        // exist. A binary 30 m gate with no recovery path once locked GNSS out for 2h11m of a
        // stationary indoor session while the engine integrated 1.4 km of drift.
        val starvedNanos = lastAcceptedElapsedNanos.let {
            if (it == 0L) Long.MAX_VALUE else nowElapsedRealtimeNanos - it
        }
        val starved = starvedNanos > (starvedAfterSeconds * 1e9).toLong()
        val gateM = if (starved) starvedAccuracyCeilingM else accuracyGateM
        val accuracyM = if (location.hasAccuracy()) location.accuracy else null
        if (accuracyM != null && accuracyM > gateM) {
            rejectedForAccuracy.incrementAndGet()
            System.err.println("[GnssSource] rejected fix with accuracy ${accuracyM}m (multipath/poor geometry) — over ${gateM}m threshold")
            return@LocationListener
        }
        // A KNOWN count below the trust floor is weak geometry. An UNKNOWN count is not evidence
        // of anything and must not be treated as a rejection reason.
        if (satsInFix != ModeArbiter.SATS_UNKNOWN && satsInFix < MIN_SATS_FOR_TRUST) {
            rejectedForSatellites.incrementAndGet()
            System.err.println("[GnssSource] rejected fix with only $satsInFix satellites — under $MIN_SATS_FOR_TRUST sats, geometry too weak to trust (this is exactly what causes standing-still jitter)")
            return@LocationListener
        }
        if (starved && accuracyM != null && accuracyM > accuracyGateM) {
            System.err.println("[GnssSource] accepting degraded fix (accuracy ${accuracyM}m) after ${starvedNanos / 1_000_000_000}s without an accepted fix")
        }
        lastAcceptedElapsedNanos = nowElapsedRealtimeNanos
        accepted.incrementAndGet()

        engine.onGnssFix(
            tNanos = nowElapsedRealtimeNanos,
            lat = location.latitude, lon = location.longitude,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            bearingDeg = if (location.hasBearing()) location.bearing else 0f,
            horizAccM = accuracyM ?: 999f,
            satsInFix = satsInFix, irnssSatsInFix = irnssSatsInFix,
            bearingValid = location.hasBearing(),
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

    /**
     * Why fixes did not reach the engine, for the session summary. Without this, a rejected fix and
     * no fix at all are indistinguishable from outside the app — which is most of what "it isn't
     * using live GPS" turns out to mean (TODO.md G5).
     */
    data class FixCounts(
        val accepted: Long, val rejectedAccuracy: Long, val rejectedSatellites: Long, val mutedDrops: Long,
        /** Status updates whose used-in-fix count exceeded what a receiver can physically solve. */
        val implausibleSatCounts: Long,
        /** Share of status updates where NavIC actually contributed a satellite to the fix.
         *  Measured 6% on 2026-09-01 — NavIC is a contributor, not the source of most fixes. */
        val irnssContributionPercent: Double,
    )

    fun fixCounts(): FixCounts {
        val updates = totalStatusUpdates.get()
        return FixCounts(
            accepted.get(), rejectedForAccuracy.get(), rejectedForSatellites.get(), mutedDrops.get(),
            implausibleCounts.get(),
            if (updates > 0) 100.0 * fixesWithIrnss.get() / updates else 0.0,
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
        private const val MIN_SATS_FOR_TRUST = 4

        /**
         * Ceiling on satellites reported as used in a single fix.
         *
         * A multi-constellation receiver (GPS, GLONASS, Galileo, BeiDou, QZSS, NavIC) can genuinely
         * use 30-40. It cannot use 78. 40 sits above every plausible real value measured and below
         * every implausible one, so it separates the two without discarding good data.
         */
        private const val MAX_PLAUSIBLE_SATS_IN_FIX = 40
    }
}
