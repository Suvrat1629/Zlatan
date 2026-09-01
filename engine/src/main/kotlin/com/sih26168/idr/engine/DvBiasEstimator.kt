package com.sih26168.idr.engine

/**
 * Online bias calibration for the delta (speed-change) model — doc §14, "online recalibration
 * against GNSS".
 *
 * Field logs showed the delta model carries a device- and domain-specific offset: +0.5..1.8
 * m/s^2 on an S24 against roughly zero on the training set. Left alone that offset is integrated
 * between fixes and inflates the published speed. While GNSS is trusted the true speed change per
 * second is known, so the average prediction over an inter-fix interval can be compared against it
 * and the difference tracked with an EMA, then subtracted from every prediction.
 *
 * Held apart from RealEngine.tickOnce because that method needs TFLite models to run at all, so
 * the loop was untestable in place. The state is deliberately owned here rather than recomputed:
 * the estimate is a running average across fixes, not a per-tick quantity.
 *
 * Not thread-safe; the engine drives it from a single tick thread.
 */
class DvBiasEstimator(
    /** EMA weight on each new observation. Low: the offset is a slow device property, and a single
     *  inter-fix interval is a noisy look at it. At 0.05 the estimate is most of the way there
     *  after roughly 20 fixes. */
    private val alpha: Float,
    /** Inter-fix intervals outside this range are discarded rather than trusted. Too short and the
     *  GNSS speed difference is mostly fix noise divided by a small number; too long and the fix
     *  before it is stale enough that the average prediction covers a different driving regime. */
    private val minFixDtSeconds: Float,
    private val maxFixDtSeconds: Float,
    initialBiasMps2: Float = 0f,
) {
    /** Current estimate of the model's offset, m/s^2. Subtract it from a raw prediction. */
    var biasMps2: Float = initialBiasMps2
        private set

    private var sumSinceFix = 0f
    private var countSinceFix = 0
    private var prevFixSpeedMps = Float.NaN
    private var prevFixNanos = 0L

    /** Record one raw delta-model prediction, before correction. */
    fun observePrediction(dvRawMps2: Float) {
        sumSinceFix += dvRawMps2
        countSinceFix++
    }

    /**
     * Fold in a trusted GNSS fix. Compares the mean prediction since the previous fix against the
     * speed change the fix actually reports, and moves the estimate toward the difference.
     * Resets the accumulator either way, so a discarded interval costs the samples in it rather
     * than contaminating the next one.
     */
    fun onTrustedFix(speedMps: Float, tNanos: Long) {
        if (!prevFixSpeedMps.isNaN() && prevFixNanos != 0L && countSinceFix > 0) {
            val dtFix = (tNanos - prevFixNanos) / 1e9f
            if (dtFix in minFixDtSeconds..maxFixDtSeconds) {
                val trueDv = (speedMps - prevFixSpeedMps) / dtFix
                val predDv = sumSinceFix / countSinceFix
                biasMps2 = (1f - alpha) * biasMps2 + alpha * (predDv - trueDv)
            }
        }
        prevFixSpeedMps = speedMps
        prevFixNanos = tNanos
        sumSinceFix = 0f
        countSinceFix = 0
    }
}
