package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.Mode

class ModeArbiter(
    private val noFixTimeoutMs: Long,
) {
    private var lastFixAtNanos: Long? = null
    private var lastSatsInFix = 0
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

    fun markWindowReady() {
        coldStart = false
    }

    fun currentMode(nowNanos: Long): Mode {
        if (coldStart) return Mode.INIT
        val fixAt = lastFixAtNanos
        val staleMs = if (fixAt == null) Long.MAX_VALUE else (nowNanos - fixAt) / 1_000_000
        return when {
            lastSatsInFix == 0 || staleMs > noFixTimeoutMs -> Mode.DEAD_RECKONING
            lastIrnssInFix > 0 -> Mode.NAVIC
            else -> Mode.GNSS

        }
    }
}
