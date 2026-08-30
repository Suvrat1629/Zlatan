package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Synthetic outage-drift check: drives a known ground-truth path with a random-walk heading
 * error (ARW model, matching ErrorStateEkf's own process noise) through both filters,
 * withholds GNSS for a window, and compares drift. Not a KPI number — see plan2.md.
 */
class ErrorStateEkfOutageTest {

    private val start = LatLon(12.9716, 77.5946)
    private val speedMps = 15f
    private val dt = 0.1
    private val speedNoiseMps = 1.0f
    private val headingArwDegPerSqrtS = EngineConfig.DEFAULT.ekfHeadingArwDegPerSqrtSec.toDouble()
    private val fixNoiseM = 5.0

    private class Result(val truePosition: LatLon, val filterPosition: LatLon)

    private fun run(
        filter: FusionFilter,
        ticks: Int,
        gnssEveryTicks: Int,
        outageStart: Int,
        outageEnd: Int,
        rng: Random,
    ): Result {
        val dr = DeadReckoner(start)
        var truePos = start
        var trueHeadingDeg = 0.0
        var headingDriftDeg = 0.0

        for (i in 0 until ticks) {
            trueHeadingDeg = (trueHeadingDeg + 0.03).mod(360.0)
            truePos = Geo.stepForward(truePos, trueHeadingDeg, speedMps * dt)

            headingDriftDeg += rng.nextGaussian() * headingArwDegPerSqrtS * sqrt(dt)
            val noisyHeading = trueHeadingDeg + headingDriftDeg
            val noisySpeed = speedMps + (rng.nextDouble(-1.0, 1.0) * speedNoiseMps).toFloat()

            val deadReckoned = dr.step(noisySpeed, noisyHeading, dt)
            filter.predict(deadReckoned, noisySpeed, noisyHeading, dt)

            val inOutage = i in outageStart until outageEnd
            if (!inOutage && i % gnssEveryTicks == 0) {
                val fixBearing = rng.nextDouble(0.0, 360.0)
                val fixMag = rng.nextDouble(0.0, fixNoiseM)
                val noisyFix = Geo.stepForward(truePos, fixBearing, fixMag)
                filter.updateWithGnss(noisyFix, speedMps, trueHeadingDeg.toFloat(), horizAccM = fixNoiseM.toFloat())
                headingDriftDeg = rng.nextGaussian() * fixNoiseM / 20.0
            }
            dr.reset(filter.estimate())
        }
        return Result(truePos, filter.estimate())
    }

    private fun Random.nextGaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    @Test
    fun ekfBeatsARawFixSnapOnAverageAcrossManyOutages() {
        val ticksPerSecond = (1 / dt).toInt()
        val totalSeconds = 90
        val outageSeconds = 60
        val ticks = totalSeconds * ticksPerSecond
        val outageStart = 20 * ticksPerSecond
        val outageEnd = outageStart + outageSeconds * ticksPerSecond
        val outageDistanceM = speedMps * outageSeconds
        val trials = 40

        var sumPassthroughM = 0.0
        var sumEkfM = 0.0
        var ekfWins = 0
        for (trial in 0 until trials) {
            val passthrough = PassthroughFusionFilter(start)
            val ekf = ErrorStateEkf(start, EngineConfig.DEFAULT)
            val rp = run(passthrough, ticks, ticksPerSecond, outageStart, outageEnd, Random(trial))
            val re = run(ekf, ticks, ticksPerSecond, outageStart, outageEnd, Random(trial))
            val errP = Geo.distanceM(rp.truePosition, rp.filterPosition)
            val errE = Geo.distanceM(re.truePosition, re.filterPosition)
            sumPassthroughM += errP
            sumEkfM += errE
            if (errE < errP) ekfWins++
        }
        val meanPassthroughM = sumPassthroughM / trials
        val meanEkfM = sumEkfM / trials

        println(
            "IDR-EKF-SYNTH ($trials trials, 60s outage): passthrough_mean=${"%.1f".format(meanPassthroughM)}m " +
                "(${"%.1f".format(100.0 * meanPassthroughM / outageDistanceM)}%) " +
                "ekf_mean=${"%.1f".format(meanEkfM)}m " +
                "(${"%.1f".format(100.0 * meanEkfM / outageDistanceM)}%) ekf_won=$ekfWins/$trials"
        )

        assertTrue(
            meanEkfM < meanPassthroughM,
            "expected the EKF to beat a raw last-fix snap on average across $trials trials " +
                "(ekf_mean=${meanEkfM}m, passthrough_mean=${meanPassthroughM}m, won $ekfWins/$trials)",
        )
    }
}
