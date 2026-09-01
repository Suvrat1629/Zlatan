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
            lastIrnssInFix >= MIN_IRNSS_FOR_NAVIC -> Mode.NAVIC
            else -> Mode.GNSS

        }
    }

    companion object {
        /** Sentinel for "the GnssStatus callback has not reported a count yet". Distinct from 0,
         *  which means "a fix was reported with no satellites used". */
        const val SATS_UNKNOWN = -1

        /**
         * IRNSS satellites that must be in the fix before the mode is reported as NAVIC.
         *
         * Was 1, which overstated the claim (TODO.md K9). Measured on 2026-09-01: NavIC contributed
         * to 6% of fixes, and where it contributed it supplied 2-3 satellites out of 25-30. A single
         * satellite contributes almost nothing to a position solution, so calling that fix "NavIC"
         * would not survive a question from anyone who knows how a fix is computed — and NavIC
         * support is a named problem-statement requirement, so it is exactly the claim that will be
         * examined.
         *
         * Two is the smallest count that represents a real contribution. The mode still means
         * "NavIC-aided" rather than "solved from NavIC alone", and the summary reports the actual
         * contribution share so the distinction is available rather than implied.
         */
        const val MIN_IRNSS_FOR_NAVIC = 2
    }
}
