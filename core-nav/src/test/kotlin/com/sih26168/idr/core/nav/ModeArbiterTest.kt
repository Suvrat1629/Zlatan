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

    /**
     * Regression test for TODO.md G5: an unknown satellite count is not evidence of GNSS loss.
     *
     * The `GnssStatus` callback is timed independently of the location callback and has not fired
     * when the first fix arrives. Treating its initial 0 as "no satellites" made the app display
     * DEAD_RECKONING while it was fusing live fixes normally, which is most of what the field
     * report "it isn't using live GPS" turned out to mean.
     */
    @Test
    fun unknownSatelliteCountWithAFreshFixIsNotDeadReckoning() {
        val arbiter = ModeArbiter(noFixTimeoutMs = 3000)
        arbiter.markWindowReady()
        arbiter.onGnssFix(tNanos = 0, satsInFix = ModeArbiter.SATS_UNKNOWN, irnssSatsInFix = 0)
        assertEquals(Mode.GNSS, arbiter.currentMode(0))
    }

    @Test
    fun aReportedZeroSatellitesStillMeansDeadReckoning() {
        // The other half of the distinction: 0 reported by a callback that HAS fired is real
        // evidence, and must keep its old meaning.
        val arbiter = ModeArbiter(noFixTimeoutMs = 3000)
        arbiter.markWindowReady()
        arbiter.onGnssFix(tNanos = 0, satsInFix = 0, irnssSatsInFix = 0)
        assertEquals(Mode.DEAD_RECKONING, arbiter.currentMode(0))
    }

    @Test
    fun anUnknownCountStillTimesOutLikeAnyOtherStaleFix() {
        // Unknown means "no information about geometry", not "trust me indefinitely". Liveness is
        // still enforced by the fix timeout.
        val arbiter = ModeArbiter(noFixTimeoutMs = 3000)
        arbiter.markWindowReady()
        arbiter.onGnssFix(tNanos = 0, satsInFix = ModeArbiter.SATS_UNKNOWN, irnssSatsInFix = 0)
        assertEquals(Mode.DEAD_RECKONING, arbiter.currentMode(4_000_000_000L))
    }
}
