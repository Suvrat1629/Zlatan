package com.sih26168.idr.core.nav

interface HeadingEstimator {

    fun predict(gyroZRadPerSec: Float, dtSeconds: Double)

    fun seedFromGnssCourse(bearingDeg: Float)

    fun headingDeg(): Double
}

class GyroIntegrationHeadingEstimator(initialHeadingDeg: Double = 0.0) : HeadingEstimator {
    private var headingDeg: Double = initialHeadingDeg

    override fun predict(gyroZRadPerSec: Float, dtSeconds: Double) {
        // Sign convention: Android's gyro z follows the right-hand rule (screen-up phone,
        // a LEFT turn is a POSITIVE z rate), while compass bearing is clockwise-positive
        // (a left turn DECREASES heading). Integrating with '+' therefore mirrors every
        // turn — observed in the field as left showing as right in dead reckoning.
        // NOTE: assumes the usual screen-up mount; a screen-down phone flips the sign
        // again. Proper mount-agnostic handling = project gyro onto the gravity axis
        // (alignment engine, W6) — this fixes the standard orientation until then.
        headingDeg = (headingDeg - Math.toDegrees(gyroZRadPerSec.toDouble()) * dtSeconds).mod(360.0)
    }

    override fun seedFromGnssCourse(bearingDeg: Float) {
        headingDeg = bearingDeg.toDouble().mod(360.0)
    }

    override fun headingDeg(): Double = headingDeg
}
