package com.sih26168.idr.engine

/**
 * Zero-velocity update (ZUPT): decide whether the device is stationary from the feature
 * window, so the engine can clamp speed to 0.
 *
 * Why this exists: the speed model is a regression trained on driving data and never emits
 * exactly 0 — a dead-still phone still produces ~1-5 m/s. Indoors (no GNSS fix) the engine is
 * permanently dead-reckoning, so that phantom speed integrates into a dot that slides forever.
 * Real inertial systems always gate on a stationarity test; this is that gate.
 *
 * Stationary iff BOTH the mean linear-acceleration magnitude and the mean gyro magnitude over
 * the window are below their thresholds. Requiring both avoids false-triggering during a
 * constant-velocity cruise, where road/engine vibration keeps these elevated.
 *
 * Feature channel order (FeatureExtractor.CHANNEL_ORDER):
 *   0 a_horiz  1 a_vert  2 a_lin_mag  3 gyr_y  4 gyr_p  5 gyr_r  6 gyro_mag
 */
object ZeroVelocityDetector {
    private const val A_LIN_MAG = 2
    private const val GYRO_MAG = 6

    fun isStationary(
        featureWindow: Array<FloatArray>,
        accelThresholdMps2: Float,
        gyroThresholdRps: Float,
    ): Boolean {
        if (featureWindow.isEmpty()) return false
        var accelSum = 0f
        var gyroSum = 0f
        for (row in featureWindow) {
            accelSum += row[A_LIN_MAG]
            gyroSum += row[GYRO_MAG]
        }
        val n = featureWindow.size
        return (accelSum / n) < accelThresholdMps2 && (gyroSum / n) < gyroThresholdRps
    }
}
