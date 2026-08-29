package com.sih26168.idr.core.types

data class PositionState(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val speedMps: Float = 0f,
    val headingDeg: Float = 0f,
    val mode: Mode = Mode.INIT,
    val satsInFix: Int = 0,
    val irnssSatsInFix: Int = 0,
    val uncertaintyM: Float = 0f,
    val engineTickMs: Float = 0f,
)
