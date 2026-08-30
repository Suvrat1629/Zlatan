package com.sih26168.idr.core.types

/**
 * User-selected mode of travel. The speed model (tcn_v2) is vehicle-trained, so
 * today this only sets a sane absolute speed ceiling per profile — it caps the
 * estimator's output (e.g. keeps a hand-carried phone in WALK from reading a
 * cyclist's speed). Deeper differentiation — a pedestrian motion model, ZUPT
 * thresholds, the non-holonomic constraint — is model/engine-team work; this
 * enum is the seam it plugs into.
 */
enum class MotionProfile(val speedMaxMps: Float) {
    WALK(3.0f),
    BIKE(14.0f),
    CAR(60.0f),
    ;

    companion object {
        val DEFAULT = CAR
    }
}
