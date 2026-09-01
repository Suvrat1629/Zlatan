package com.sih26168.idr.core.nav

interface HeadingEstimator {

    fun predict(gyroZRadPerSec: Float, dtSeconds: Double)

    fun seedFromGnssCourse(bearingDeg: Float)

    /** Pull the heading a fraction [alpha] of the way toward [bearingDeg] on the circle.
     *  Used by the road-heading correction; default no-op for estimators without it. */
    fun nudgeToward(bearingDeg: Double, alpha: Double) {}

    fun headingDeg(): Double
}

class GyroIntegrationHeadingEstimator(initialHeadingDeg: Double = 0.0) : HeadingEstimator {
    private var headingDeg: Double = initialHeadingDeg

    override fun predict(gyroZRadPerSec: Float, dtSeconds: Double) {
        // Sign convention: Android's gyro z follows the right-hand rule (screen-up phone,
        // a LEFT turn is a POSITIVE z rate), while compass bearing is clockwise-positive
        // (a left turn DECREASES heading). Integrating with '+' therefore mirrors every
        // turn — observed in the field as left showing as right in dead reckoning.
        // Mount-agnostic since block F1: RealEngine feeds this the rate about the LOCAL VERTICAL
        // from YawRate.aboutVertical, not the raw device z axis, so any mounting orientation gives
        // the same rotation. This comment previously said the opposite and a 2026-09-01 audit of
        // the deliverables read it and concluded alignment had not been started -- a stale comment
        // is worse than no comment. See YawRateTest and MountInvarianceTest for the sweep.
        headingDeg = (headingDeg - Math.toDegrees(gyroZRadPerSec.toDouble()) * dtSeconds).mod(360.0)
    }

    override fun seedFromGnssCourse(bearingDeg: Float) {
        headingDeg = bearingDeg.toDouble().mod(360.0)
    }

    override fun nudgeToward(bearingDeg: Double, alpha: Double) {
        // shortest signed angular difference, then move a fraction of it
        val diff = ((bearingDeg - headingDeg + 540.0) % 360.0) - 180.0
        headingDeg = (headingDeg + alpha * diff).mod(360.0)
    }

    override fun headingDeg(): Double = headingDeg
}
