package com.sih26168.idr.map

data class PositionSample(val lat: Double, val lon: Double, val headingDeg: Float, val atNanos: Long)

class PositionInterpolator {
    private var previous: PositionSample? = null
    private var latest: PositionSample? = null

    fun push(lat: Double, lon: Double, headingDeg: Float, nowNanos: Long = System.nanoTime()) {
        previous = latest
        latest = PositionSample(lat, lon, headingDeg, nowNanos)
    }

    fun interpolate(nowNanos: Long = System.nanoTime()): PositionSample? {
        val to = latest ?: return null
        val from = previous ?: return to
        val intervalNanos = to.atNanos - from.atNanos
        if (intervalNanos <= 0) return to
        val elapsedNanos = nowNanos - to.atNanos
        val t = (elapsedNanos.toDouble() / intervalNanos).coerceIn(0.0, 1.0)
        val lat = from.lat + (to.lat - from.lat) * t
        val lon = from.lon + (to.lon - from.lon) * t
        val heading = lerpAngleDeg(from.headingDeg, to.headingDeg, t.toFloat())
        return PositionSample(lat, lon, heading, nowNanos)
    }

    private fun lerpAngleDeg(a: Float, b: Float, t: Float): Float {
        var diff = (b - a) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return (a + diff * t + 360f) % 360f
    }
}
