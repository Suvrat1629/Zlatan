package com.sih26168.idr.engine

/**
 * Detects the device being *handled* — picked up, shaken, reoriented — as distinct from the
 * vehicle it is riding in being in motion.
 *
 * ## Why this exists
 *
 * [ZeroVelocityDetector] is a floor test: it declares zero velocity when both accelerometer and
 * gyro window means fall *below* thresholds. Requiring both is correct, and stops road vibration
 * during constant-speed cruising from registering a false halt. But it leaves the engine with no
 * way to express "definitely not travelling" — only "definitely not moving at all". Shaking the
 * phone exceeds both thresholds, so ZUPT switches off, and the one mechanism that would have
 * pinned speed to zero is disabled by exactly the motion that most needs pinning. Field symptom:
 * a stationary phone's map dot walks away when the phone is shaken.
 *
 * ## Why the tilt rate, and not something cheaper
 *
 * The discriminator has to be something a vehicle physically cannot do. That rules out speed,
 * acceleration magnitude and yaw rate — a car produces all three in quantity. It also rules out
 * acceleration incoherence (vector mean much smaller than mean magnitude), which sounds
 * discriminative but fires during constant-speed cruising, where mean linear acceleration is also
 * near zero.
 *
 * What a vehicle body cannot do is rapidly and continuously reorient about its **horizontal** axes.
 * Pitch and roll rates in a car are small, and a speed bump produces a brief transient, not a
 * sustained rate. A phone in a hand does nothing else. So the signal is the gyro component
 * perpendicular to gravity:
 *
 *     tiltRate = | gyro - (gyro . g_hat) g_hat |
 *
 * This is the exact orthogonal complement of [com.sih26168.idr.core.nav.YawRate.aboutVertical],
 * which projects *onto* gravity. That orthogonality is the property that makes this safe: a genuine
 * vehicle turn — however sharp — lives entirely in the component this measurement discards, so the
 * detector is mathematically incapable of suppressing one.
 *
 * ## Calibration
 *
 * The threshold is argued from vehicle physics rather than fitted to a dataset, and it is applied
 * to the **window mean** so that a single pothole cannot trip it. Telemetry records the raw tilt
 * rate on every tick, so the assumption can be checked against the first real drive — the same
 * collect-the-statistics-before-acting pattern the map-match and GNSS NIS gates already follow.
 *
 * See `sih-26168-notes/Aneesh/TODO.md` block G1 and
 * `wiki/notes/idr-stationary-shake-and-gnss.md` for the full reasoning and the rejected
 * alternatives.
 */
object HandlingDetector {

    private const val MIN_GRAVITY_MAG = 1e-3f

    /**
     * Magnitude of the gyro component perpendicular to gravity, rad/s — the rate at which the
     * device is being tilted, with rotation about the vertical (vehicle yaw) removed.
     *
     * Falls back to the full gyro magnitude when gravity is unusable, which is the conservative
     * direction: without a reference axis we cannot tell yaw from tilt, so we do not claim the
     * rotation is yaw.
     */
    fun tiltRate(gx: Float, gy: Float, gz: Float, grx: Float, gry: Float, grz: Float): Float {
        val mag = kotlin.math.sqrt(grx * grx + gry * gry + grz * grz)
        if (mag < MIN_GRAVITY_MAG || mag.isNaN()) {
            return kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
        }
        val ux = grx / mag; val uy = gry / mag; val uz = grz / mag
        val alongVertical = gx * ux + gy * uy + gz * uz
        val px = gx - alongVertical * ux
        val py = gy - alongVertical * uy
        val pz = gz - alongVertical * uz
        return kotlin.math.sqrt(px * px + py * py + pz * pz)
    }

    /**
     * True when the mean tilt rate over the window exceeds the bound a vehicle body can sustain.
     *
     * @param meanTiltRateRadS window mean of [tiltRate]
     */
    fun isHandling(meanTiltRateRadS: Float, thresholdRadS: Float): Boolean =
        meanTiltRateRadS > thresholdRadS
}
