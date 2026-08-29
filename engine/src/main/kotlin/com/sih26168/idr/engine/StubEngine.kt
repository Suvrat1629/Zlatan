package com.sih26168.idr.engine

import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.PositionState
import com.sih26168.idr.core.types.PositioningEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class StubEngine(
    outputRateHz: Double = 10.0,
    startAt: LatLon = LatLon(12.9716, 77.5946),
) : PositioningEngine {

    private val _state = MutableStateFlow(PositionState(lat = startAt.lat, lon = startAt.lon))
    override val state: StateFlow<PositionState> = _state.asStateFlow()

    private var lat = startAt.lat
    private var lon = startAt.lon
    private var headingDeg = 0.0
    private var lastGyroZ = 0f
    private var gnssSpeedMps = 0f
    private var gnssGood = false
    private var satsInFix = 0
    private var irnssSatsInFix = 0
    private var uncertaintyM = MIN_UNCERTAINTY_M
    private var hasReceivedFix = false
    private var running = false

    private val periodMs = (1000.0 / outputRateHz).toLong()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stub-engine").apply { isDaemon = true }
    }

    override fun start() {
        if (running) return
        running = true
        scheduler.scheduleAtFixedRate({ tick() }, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        running = false
        scheduler.shutdown()
    }

    override fun onImuSample(
        tNanos: Long,
        ax: Float, ay: Float, az: Float,
        grx: Float, gry: Float, grz: Float,
        gx: Float, gy: Float, gz: Float,
    ) {
        lastGyroZ = gz
    }

    override fun onGnssFix(
        tNanos: Long,
        lat: Double, lon: Double,
        speedMps: Float, bearingDeg: Float, horizAccM: Float,
        satsInFix: Int, irnssSatsInFix: Int,
    ) {
        if (!hasReceivedFix) {
            this.lat = lat
            this.lon = lon
            hasReceivedFix = true
        } else {
            this.lat += POSITION_SMOOTHING_ALPHA * (lat - this.lat)
            this.lon += POSITION_SMOOTHING_ALPHA * (lon - this.lon)
        }
        this.headingDeg = bearingDeg.toDouble()
        this.gnssSpeedMps = speedMps
        this.gnssGood = true
        this.satsInFix = satsInFix
        this.irnssSatsInFix = irnssSatsInFix
    }

    override fun onGnssLost(tNanos: Long) {
        gnssGood = false
        satsInFix = 0
        irnssSatsInFix = 0
    }

    private fun tick() {
        if (!running) return
        val t0 = System.nanoTime()
        val dtSeconds = periodMs / 1000.0

        if (!gnssGood) {
            headingDeg = (headingDeg + Math.toDegrees(lastGyroZ * dtSeconds)).mod(360.0)
            val forwardM = gnssSpeedMps * dtSeconds
            val headingRad = Math.toRadians(headingDeg)
            val earthRadiusM = 6_378_137.0
            lat += (forwardM * cos(headingRad)) / earthRadiusM * (180.0 / Math.PI)
            lon += (forwardM * sin(headingRad)) / (earthRadiusM * cos(Math.toRadians(lat))) * (180.0 / Math.PI)
        }

        uncertaintyM = if (gnssGood) {
            MIN_UNCERTAINTY_M
        } else {
            (uncertaintyM + UNCERTAINTY_GROWTH_M_PER_S.toFloat() * dtSeconds.toFloat()).coerceAtMost(MAX_UNCERTAINTY_M)
        }

        _state.value = PositionState(
            lat = lat, lon = lon,
            speedMps = gnssSpeedMps,
            headingDeg = headingDeg.toFloat(),
            mode = if (gnssGood) (if (irnssSatsInFix > 0) Mode.NAVIC else Mode.GNSS) else Mode.DEAD_RECKONING,
            satsInFix = satsInFix,
            irnssSatsInFix = irnssSatsInFix,
            uncertaintyM = uncertaintyM,
            engineTickMs = (System.nanoTime() - t0) / 1_000_000f,
        )
    }

    companion object {
        private const val MIN_UNCERTAINTY_M = 5f
        private const val MAX_UNCERTAINTY_M = 150f
        private const val UNCERTAINTY_GROWTH_M_PER_S = 1.5
        private const val POSITION_SMOOTHING_ALPHA = 0.35
    }
}
