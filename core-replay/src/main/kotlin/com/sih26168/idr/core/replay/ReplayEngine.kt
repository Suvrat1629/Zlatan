package com.sih26168.idr.core.replay

import com.sih26168.idr.core.types.PositioningEngine
import java.io.File

class ReplayEngine(private val engine: PositioningEngine) {

    fun replay(events: List<TraceEvent>) {
        engine.start()
        for (event in events) {
            when (event) {
                is TraceEvent.Imu -> with(event.record) {
                    engine.onImuSample(tNanos, ax, ay, az, grx, gry, grz, gx, gy, gz)
                }
                is TraceEvent.Gnss -> with(event.record) {
                    engine.onGnssFix(tNanos, lat, lon, speedMps, bearingDeg, horizAccM, satsInFix, irnssSatsInFix)
                }
                is TraceEvent.Lost -> engine.onGnssLost(event.tNanos)
            }
        }
        engine.stop()
    }

    fun replayFile(file: File) = replay(TraceReader.read(file))
}
