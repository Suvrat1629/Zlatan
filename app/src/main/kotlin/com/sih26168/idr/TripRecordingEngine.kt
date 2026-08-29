package com.sih26168.idr

import com.sih26168.idr.core.replay.TraceEvent
import com.sih26168.idr.core.replay.TraceWriter
import com.sih26168.idr.core.types.GnssFixRecord
import com.sih26168.idr.core.types.ImuSampleRecord
import com.sih26168.idr.core.types.PositioningEngine
import java.io.File

class TripRecordingEngine(
    private val delegate: PositioningEngine,
    private val flushEveryNEvents: Int = 200,
) : PositioningEngine by delegate {

    @Volatile private var writer: TraceWriter? = null
    private var eventsSinceFlush = 0

    val isRecording: Boolean get() = writer != null

    fun startRecording(traceFile: File) {
        writer?.close()
        writer = TraceWriter(traceFile)
        eventsSinceFlush = 0
    }

    fun stopRecording() {
        writer?.close()
        writer = null
    }

    override fun onImuSample(
        tNanos: Long,
        ax: Float, ay: Float, az: Float,
        grx: Float, gry: Float, grz: Float,
        gx: Float, gy: Float, gz: Float,
    ) {
        logEvent(TraceEvent.Imu(ImuSampleRecord(tNanos, ax, ay, az, grx, gry, grz, gx, gy, gz)))
        delegate.onImuSample(tNanos, ax, ay, az, grx, gry, grz, gx, gy, gz)
    }

    override fun onGnssFix(
        tNanos: Long,
        lat: Double, lon: Double,
        speedMps: Float, bearingDeg: Float, horizAccM: Float,
        satsInFix: Int, irnssSatsInFix: Int,
    ) {
        logEvent(TraceEvent.Gnss(GnssFixRecord(tNanos, lat, lon, speedMps, bearingDeg, horizAccM, satsInFix, irnssSatsInFix)))
        delegate.onGnssFix(tNanos, lat, lon, speedMps, bearingDeg, horizAccM, satsInFix, irnssSatsInFix)
    }

    override fun onGnssLost(tNanos: Long) {
        logEvent(TraceEvent.Lost(tNanos))
        delegate.onGnssLost(tNanos)
    }

    private fun logEvent(event: TraceEvent) {
        val w = writer ?: return
        w.write(event)
        eventsSinceFlush++
        if (eventsSinceFlush >= flushEveryNEvents) {
            w.flush()
            eventsSinceFlush = 0
        }
    }
}
