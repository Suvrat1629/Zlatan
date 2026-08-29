package com.sih26168.idr.core.nav

interface HeadingEstimator {

    fun predict(gyroZRadPerSec: Float, dtSeconds: Double)

    fun seedFromGnssCourse(bearingDeg: Float)

    fun headingDeg(): Double
}

class GyroIntegrationHeadingEstimator(initialHeadingDeg: Double = 0.0) : HeadingEstimator {
    private var headingDeg: Double = initialHeadingDeg

    override fun predict(gyroZRadPerSec: Float, dtSeconds: Double) {
        headingDeg = (headingDeg + Math.toDegrees(gyroZRadPerSec.toDouble()) * dtSeconds).mod(360.0)
    }

    override fun seedFromGnssCourse(bearingDeg: Float) {
        headingDeg = bearingDeg.toDouble().mod(360.0)
    }

    override fun headingDeg(): Double = headingDeg
}
