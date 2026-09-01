package com.sih26168.idr

import android.content.Context

/**
 * Carries the delta model's learned offset across process restarts.
 *
 * The estimate is a slow average over roughly 20 trusted fixes (see DvBiasEstimator). Starting it
 * at zero every launch means every cold start re-pays the full device offset — up to 1.8 m/s^2 on
 * an S24 — as inflated speed while it relearns. The offset is a property of the phone and the
 * model, not of the trip, so the previous session's answer is a far better starting guess than
 * zero.
 *
 * Stored per model version: a new delta model has its own offset, and silently seeding it with the
 * old one would start the estimator further from the truth than zero does.
 */
class DvBiasStore(context: Context, private val modelVersion: String) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Last saved estimate, or 0 when there is none for this model version. Clamped: the prefs
     *  file survives backup/restore and is writable on a rooted device, and an absurd value here
     *  would be subtracted from every prediction for the ~20 fixes it takes to wash out. */
    fun load(): Float =
        prefs.getFloat(key(), 0f).coerceIn(-MAX_ABS_BIAS_MPS2, MAX_ABS_BIAS_MPS2)

    fun save(biasMps2: Float) {
        if (!biasMps2.isFinite()) return
        prefs.edit().putFloat(key(), biasMps2.coerceIn(-MAX_ABS_BIAS_MPS2, MAX_ABS_BIAS_MPS2)).apply()
    }

    private fun key() = "$KEY_PREFIX$modelVersion"

    companion object {
        private const val PREFS_NAME = "dv_bias"
        private const val KEY_PREFIX = "bias_mps2_"

        /** Same physical bound RealEngine clamps a raw delta prediction to: beyond 0.4 g is not a
         *  car, so a bias correction larger than that is not a bias. */
        private const val MAX_ABS_BIAS_MPS2 = 4f
    }
}
