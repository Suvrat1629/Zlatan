package com.sih26168.idr.core.model

interface SpeedEstimator {

    fun estimate(normalizedWindow: Array<FloatArray>): Float

    fun close() {}
}

class ConstantSpeedEstimator(private val constantMps: Float = 8.33f) : SpeedEstimator {
    override fun estimate(normalizedWindow: Array<FloatArray>): Float = constantMps
}
