package com.sih26168.idr.engine

/**
 * Zero-velocity update (ZUPT): declare the device stationary when both the mean linear
 * acceleration magnitude and mean gyro magnitude over the window sit below thresholds.
 *
 * Field motivation (twice over): (1) a halt otherwise registers slowly — the blend decays
 * toward zero instead of snapping; (2) every stop is a free error reset for the speed
 * random walk, the doc's "cheapest large win" (stop-and-go traffic especially).
 * Requiring BOTH channels quiet avoids false triggers during constant-speed cruising,
 * where road/engine vibration keeps them elevated (measured: driving lin-accel p10 ~0.9,
 * stationary p90 well below the 0.8/0.15 defaults in config.json).
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
