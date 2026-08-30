package com.sih26168.idr

import android.content.Context

/** Records when the user last confirmed the compass was in a good (high-accuracy) state.
 *  Nothing to persist beyond that — the calibration itself is the vendor's own continuous
 *  hard-iron correction (see MagnetometerCalibrationActivity's doc), not a correction we
 *  compute or own, so there's no offset/scale of ours to save. */
class MagnetometerCalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordConfirmed() {
        prefs.edit().putLong(KEY_CONFIRMED_AT_MS, System.currentTimeMillis()).apply()
    }

    fun lastConfirmedAtEpochMs(): Long? {
        val value = prefs.getLong(KEY_CONFIRMED_AT_MS, -1L)
        return if (value < 0) null else value
    }

    companion object {
        private const val PREFS_NAME = "magnetometer_calibration"
        private const val KEY_CONFIRMED_AT_MS = "confirmed_at_ms"
    }
}
