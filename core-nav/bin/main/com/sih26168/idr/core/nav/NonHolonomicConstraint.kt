package com.sih26168.idr.core.nav

interface NonHolonomicConstraint {

    fun apply(lateralMps: Float, verticalMps: Float): Pair<Float, Float>
}

class NoOpNonHolonomicConstraint : NonHolonomicConstraint {
    override fun apply(lateralMps: Float, verticalMps: Float): Pair<Float, Float> =
        lateralMps to verticalMps
}
