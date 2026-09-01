package com.sih26168.idr.core.nav

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The blend must never hand the estimate entirely to the speed model (TODO.md K12).
 *
 * `lam = t/(t+tau)` reaches 1 given a long enough outage, at which point the model fully replaces
 * the constant-velocity prior. That is only safe if the model beats constant velocity. Measured on
 * 238 s of motorbike riding with GNSS live throughout — 1,704 labelled windows — it does not:
 *
 *   model correlation with true speed   r-squared = 0.037
 *   model MAE                           2.84 m/s
 *   MAE of predicting the median        1.46 m/s
 *
 * The model is worse than a constant on this vehicle, so a cap is not conservatism, it is the
 * arithmetic. The same weights score R-squared 0.66 on IO-VNBD, which is cars: this is a domain
 * failure, and the cap is the honest interim position until a retrain fixes it.
 */
class BlendLambdaCapTest {

    private fun lambda(tSeconds: Double, tau: Double, cap: Float): Float =
        (tSeconds / (tSeconds + tau)).toFloat().coerceAtMost(cap)

    private val tau = 240.0
    private val cap = 0.5f

    @Test
    fun `a fresh anchor still rides the GNSS fix, not the model`() {
        assertTrue(lambda(0.0, tau, cap) < 0.01f)
        assertTrue(lambda(5.0, tau, cap) < 0.05f, "short outages must barely touch the model")
    }

    @Test
    fun `the model never fully owns the estimate however long the outage`() {
        for (t in listOf(60.0, 300.0, 1_800.0, 86_400.0)) {
            assertTrue(
                lambda(t, tau, cap) <= cap,
                "at ${t}s lambda reached ${lambda(t, tau, cap)} — a model with r-squared 0.037 " +
                    "must never outweigh the constant-velocity prior",
            )
        }
    }

    @Test
    fun `without the cap a long outage hands everything to the model`() {
        // What the code did before, and why the cap exists: after an hour the constant-velocity
        // prior contributes under 7%.
        val uncapped = (3600.0 / (3600.0 + tau)).toFloat()
        assertTrue(uncapped > 0.93f, "uncapped lambda after an hour was $uncapped")
    }

    @Test
    fun `the cap is a statement about model quality, so it is configurable`() {
        // When a retrain beats a constant on the target vehicle, this number should move with it.
        assertTrue(lambda(10_000.0, tau, 0.9f) == 0.9f)
        assertTrue(lambda(10_000.0, tau, 0.2f) == 0.2f)
    }
}
