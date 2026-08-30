package com.sih26168.idr.core.types

data class ImuSampleRecord(
    val tNanos: Long,
    val ax: Float, val ay: Float, val az: Float,
    val grx: Float, val gry: Float, val grz: Float,
    val gx: Float, val gy: Float, val gz: Float,
) {

    fun toRawChannels(): FloatArray = floatArrayOf(ax, ay, az, grx, gry, grz, gx, gy, gz)
}

data class GnssFixRecord(
    val tNanos: Long,
    val lat: Double, val lon: Double,
    val speedMps: Float, val bearingDeg: Float, val horizAccM: Float,
    val satsInFix: Int, val irnssSatsInFix: Int,
    // Android's Location.hasBearing() -- bearingDeg defaults to 0f when this is false, so
    // consumers must not treat bearingDeg as a real heading measurement without checking.
    val bearingValid: Boolean = false,
)
