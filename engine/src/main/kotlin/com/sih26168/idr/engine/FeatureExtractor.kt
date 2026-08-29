package com.sih26168.idr.engine

import com.sih26168.idr.core.types.PositioningEngine

object FeatureExtractor {

    val CHANNEL_ORDER = listOf("a_horiz", "a_vert", "a_lin_mag", "gyr_y", "gyr_p", "gyr_r", "gyro_mag")

    fun features(raw: FloatArray): FloatArray {
        require(raw.size == PositioningEngine.RAW_CHANNELS) {
            "expected ${PositioningEngine.RAW_CHANNELS} raw channels, got ${raw.size}"
        }
        val ax = raw[0]; val ay = raw[1]; val az = raw[2]
        val grx = raw[3]; val gry = raw[4]; val grz = raw[5]
        val gx = raw[6]; val gy = raw[7]; val gz = raw[8]

        val lx = ax - grx; val ly = ay - gry; val lz = az - grz

        val gMag = kotlin.math.sqrt(grx * grx + gry * gry + grz * grz) + 1e-6f
        val ghx = grx / gMag; val ghy = gry / gMag; val ghz = grz / gMag

        val aVert = lx * ghx + ly * ghy + lz * ghz
        val hx = lx - aVert * ghx; val hy = ly - aVert * ghy; val hz = lz - aVert * ghz
        val aHoriz = kotlin.math.sqrt(hx * hx + hy * hy + hz * hz)
        val aLinMag = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
        val gyroMag = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)

        return floatArrayOf(aHoriz, aVert, aLinMag, gx, gy, gz, gyroMag)
    }

    fun featureWindow(rawWindow: Array<FloatArray>): Array<FloatArray> =
        Array(rawWindow.size) { i -> features(rawWindow[i]) }
}
