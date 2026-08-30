package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.Mode
import kotlin.test.Test
import kotlin.test.assertEquals

class ModeArbiterTest {
    @Test
    fun startsInInitUntilWindowReady() {
        val arbiter = ModeArbiter(noFixTimeoutMs = 3000)
        assertEquals(Mode.INIT, arbiter.currentMode(0))
    }

    @Test
    fun navicWhenIrnssContributing() {
        val arbiter = ModeArbiter(noFixTimeoutMs = 3000)
        arbiter.markWindowReady()
        arbiter.onGnssFix(tNanos = 0, satsInFix = 6, irnssSatsInFix = 2)
        assertEquals(Mode.NAVIC, arbiter.currentMode(0))
    }

    @Test
    fun gnssWhenNoIrnss() {
        val arbiter = ModeArbiter(noFixTimeoutMs = 3000)
        arbiter.markWindowReady()
        arbiter.onGnssFix(tNanos = 0, satsInFix = 6, irnssSatsInFix = 0)
        assertEquals(Mode.GNSS, arbiter.currentMode(0))
    }

    @Test
    fun deadReckoningWhenFixIsStale() {
        val arbiter = ModeArbiter(noFixTimeoutMs = 3000)
        arbiter.markWindowReady()
        arbiter.onGnssFix(tNanos = 0, satsInFix = 6, irnssSatsInFix = 2)
        val fourSecondsLaterNs = 4_000_000_000L
        assertEquals(Mode.DEAD_RECKONING, arbiter.currentMode(fourSecondsLaterNs))
    }

    @Test
    fun deadReckoningAfterExplicitLoss() {
        val arbiter = ModeArbiter(noFixTimeoutMs = 3000)
        arbiter.markWindowReady()
        arbiter.onGnssFix(tNanos = 0, satsInFix = 6, irnssSatsInFix = 2)
        arbiter.onGnssLost()
        assertEquals(Mode.DEAD_RECKONING, arbiter.currentMode(0))
    }
}
