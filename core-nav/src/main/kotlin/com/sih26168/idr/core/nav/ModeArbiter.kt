package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.Mode

/**
 * Decides which mode the engine is operating in, from GNSS liveness alone.
 *
 * On the satellite count (TODO.md G5): the count arrives on the `GnssStatus` callback, which is
 * timed independently of the location callback and reads zero before its first delivery. So zero
 * has two possible meanings -- "no satellites in the fix" and "the count has not arrived yet" --
 * and this class previously conflated them, reporting DEAD_RECKONING while the engine was fusing
 * live fixes normally. [SATS_UNKNOWN] makes the second meaning explicit: an unknown count is not
 * evidence of GNSS loss, and only a fix timeout is.
 */
class ModeArbiter(
    private val noFixTimeoutMs: Long,
) {
    private var lastFixAtNanos: Long? = null
    private var lastSatsInFix = SATS_UNKNOWN
    private var lastIrnssInFix = 0
    private var coldStart = true

    fun onGnssFix(tNanos: Long, satsInFix: Int, irnssSatsInFix: Int) {
        lastFixAtNanos = tNanos
        lastSatsInFix = satsInFix
        lastIrnssInFix = irnssSatsInFix
    }

    fun satsInFix(): Int = lastSatsInFix
    fun irnssSatsInFix(): Int = lastIrnssInFix

    fun onGnssLost() {
        lastSatsInFix = 0
        lastIrnssInFix = 0
    }

    /** True when no satellite count has been reported yet, as opposed to a reported count of 0. */
    fun satsUnknown(): Boolean = lastSatsInFix == SATS_UNKNOWN

    fun markWindowReady() {
        coldStart = false
    }

    fun currentMode(nowNanos: Long): Mode {
        if (coldStart) return Mode.INIT
        val fixAt = lastFixAtNanos
        val staleMs = if (fixAt == null) Long.MAX_VALUE else (nowNanos - fixAt) / 1_000_000
        return when {
            // An UNKNOWN count is not evidence of GNSS loss -- a fresh fix is evidence to the
            // contrary, and the count callback simply has not fired yet. Only a reported zero, or
            // a stale fix, means dead reckoning.
            lastSatsInFix == 0 || staleMs > noFixTimeoutMs -> Mode.DEAD_RECKONING
            lastIrnssInFix > 0 -> Mode.NAVIC
            else -> Mode.GNSS

        }
    }

    companion object {
        /** Sentinel for "the GnssStatus callback has not reported a count yet". Distinct from 0,
         *  which means "a fix was reported with no satellites used". */
        const val SATS_UNKNOWN = -1
    }
}
