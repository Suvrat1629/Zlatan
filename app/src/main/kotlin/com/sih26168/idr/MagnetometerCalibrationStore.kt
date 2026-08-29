package com.sih26168.idr

import android.content.Context

/** Persists the magnetometer hard/soft-iron correction across app restarts, so the user only
 *  calibrates once (until they choose to redo it). */
class MagnetometerCalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Calibration(
        val offset: FloatArray,
        val scale: FloatArray,
        val fieldMagnitudeUt: Float,
        val calibratedAtEpochMs: Long,
    )

    fun save(offset: FloatArray, scale: FloatArray, fieldMagnitudeUt: Float) {
        prefs.edit()
            .putFloat(KEY_OFFSET_X, offset[0]).putFloat(KEY_OFFSET_Y, offset[1]).putFloat(KEY_OFFSET_Z, offset[2])
            .putFloat(KEY_SCALE_X, scale[0]).putFloat(KEY_SCALE_Y, scale[1]).putFloat(KEY_SCALE_Z, scale[2])
            .putFloat(KEY_FIELD_UT, fieldMagnitudeUt)
            .putLong(KEY_CALIBRATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun load(): Calibration? {
        if (!prefs.contains(KEY_CALIBRATED_AT_MS)) return null
        return Calibration(
            offset = floatArrayOf(
                prefs.getFloat(KEY_OFFSET_X, 0f), prefs.getFloat(KEY_OFFSET_Y, 0f), prefs.getFloat(KEY_OFFSET_Z, 0f),
            ),
            scale = floatArrayOf(
                prefs.getFloat(KEY_SCALE_X, 1f), prefs.getFloat(KEY_SCALE_Y, 1f), prefs.getFloat(KEY_SCALE_Z, 1f),
            ),
            fieldMagnitudeUt = prefs.getFloat(KEY_FIELD_UT, Float.NaN),
            calibratedAtEpochMs = prefs.getLong(KEY_CALIBRATED_AT_MS, 0L),
        )
    }

    companion object {
        private const val PREFS_NAME = "magnetometer_calibration"
        private const val KEY_OFFSET_X = "offset_x"
        private const val KEY_OFFSET_Y = "offset_y"
        private const val KEY_OFFSET_Z = "offset_z"
        private const val KEY_SCALE_X = "scale_x"
        private const val KEY_SCALE_Y = "scale_y"
        private const val KEY_SCALE_Z = "scale_z"
        private const val KEY_FIELD_UT = "field_ut"
        private const val KEY_CALIBRATED_AT_MS = "calibrated_at_ms"
    }
}
