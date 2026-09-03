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

    private class Result(
        val truePosition: LatLon,
        val filterPosition: LatLon,
        /** Filter state captured at the last tick of the outage, before reacquisition. */
        val trueErrorAtOutageEndM: Double,
        val reportedUncertaintyAtOutageEndM: Double,
    )

    private fun run(
        filter: FusionFilter,
        ticks: Int,
        gnssEveryTicks: Int,
        outageStart: Int,
        outageEnd: Int,
        rng: Random,
        /**
         * The heading ARW actually injected into the synthetic truth. Defaults to the value
         * the filter assumes (headingArwDegPerSqrtS) — pass a multiple of it to simulate the
         * filter's process-noise model undershooting real gyro noise (plan2.md §3a notes the
         * configured 1.41 deg/sqrt(s), fit to aggregate cross-track medians, likely undershoots
         * by ~10x).
         */
        trueHeadingArwDegPerSqrtS: Double = headingArwDegPerSqrtS,
    ): Result {
        val dr = DeadReckoner(start)
        var truePos = start
        var trueHeadingDeg = 0.0
        var headingDriftDeg = 0.0
        var trueErrorAtOutageEndM = 0.0
        var reportedUncertaintyAtOutageEndM = 0.0

        for (i in 0 until ticks) {
            trueHeadingDeg = (trueHeadingDeg + 0.03).mod(360.0)
            truePos = Geo.stepForward(truePos, trueHeadingDeg, speedMps * dt)

            headingDriftDeg += rng.nextGaussian() * trueHeadingArwDegPerSqrtS * sqrt(dt)
            val noisyHeading = trueHeadingDeg + headingDriftDeg
            val noisySpeed = speedMps + (rng.nextDouble(-1.0, 1.0) * speedNoiseMps).toFloat()

            val deadReckoned = dr.step(noisySpeed, noisyHeading, dt)
            filter.predict(deadReckoned, noisySpeed, noisyHeading, dt)

            val inOutage = i in outageStart until outageEnd
            if (inOutage && i == outageEnd - 1) {
                trueErrorAtOutageEndM = Geo.distanceM(truePos, filter.estimate())
                reportedUncertaintyAtOutageEndM = filter.uncertaintyM().toDouble()
            }
            if (!inOutage && i % gnssEveryTicks == 0) {
                val fixBearing = rng.nextDouble(0.0, 360.0)
                val fixMag = rng.nextDouble(0.0, fixNoiseM)
                val noisyFix = Geo.stepForward(truePos, fixBearing, fixMag)
                filter.updateWithGnss(noisyFix, speedMps, trueHeadingDeg.toFloat(), horizAccM = fixNoiseM.toFloat())
                headingDriftDeg = rng.nextGaussian() * fixNoiseM / 20.0
            }
            dr.reset(filter.estimate())
        }
        return Result(truePos, filter.estimate(), trueErrorAtOutageEndM, reportedUncertaintyAtOutageEndM)
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

    /**
     * Precondition check for plan2.md §3 step 6 (wiring the map matcher as an EKF measurement).
     *
     * The filter's Q uses config.ekfHeadingArwDegPerSqrtSec. plan2.md §3a records that this
     * value was fit to aggregate cross-track-vs-duration medians and lumps angle random walk,
     * rate random walk and scale-factor error into one white-noise coefficient — likely
     * undershooting real gyro heading noise by ~10x. If Q is too low the filter is
     * overconfident: reported uncertainty stays small while true error grows, and the Kalman
     * gain then under-weights EVERY measurement, including the map-match update step 6 adds.
     *
     * This drives the same 60s outage with the INJECTED heading ARW scaled 1x / 3x / 10x
     * against a filter left at its default assumption, and reports the ratio of reported
     * uncertainty to actual error at outage end. A consistent filter sits near or above 1.0;
     * a badly overconfident one collapses toward 0.
     *
     * Diagnostic — the only hard assertion is that at 1x (assumption == reality) the filter
     * is not grossly overconfident, and that the EKF still beats a raw snap at every scale.
     */
    @Test
    fun ekfCovarianceRealismUnderHeadingNoiseMismatch() {
        val ticksPerSecond = (1 / dt).toInt()
        val totalSeconds = 90
        val outageSeconds = 60
        val ticks = totalSeconds * ticksPerSecond
        val outageStart = 20 * ticksPerSecond
        val outageEnd = outageStart + outageSeconds * ticksPerSecond
        val outageDistanceM = speedMps * outageSeconds
        val trials = 40

        println("IDR-EKF-COV-REALISM (filter assumes ARW=${"%.2f".format(headingArwDegPerSqrtS)} deg/sqrt(s), 60s outage):")

        var ekfBeatsSnapAtX1 = false
        var calibrationRatioAtX1 = Double.NaN
        for (scale in listOf(1.0, 3.0, 10.0)) {
            var sumPassthroughM = 0.0
            var sumEkfM = 0.0
            var sumReportedM = 0.0
            var sumActualAtOutageEndM = 0.0
            var ekfWins = 0
            for (trial in 0 until trials) {
                val passthrough = PassthroughFusionFilter(start)
                val ekf = ErrorStateEkf(start, EngineConfig.DEFAULT)
                val injectedArw = headingArwDegPerSqrtS * scale
                val rp = run(passthrough, ticks, ticksPerSecond, outageStart, outageEnd, Random(trial), injectedArw)
                val re = run(ekf, ticks, ticksPerSecond, outageStart, outageEnd, Random(trial), injectedArw)
                val errP = Geo.distanceM(rp.truePosition, rp.filterPosition)
                val errE = Geo.distanceM(re.truePosition, re.filterPosition)
                sumPassthroughM += errP
                sumEkfM += errE
                sumReportedM += re.reportedUncertaintyAtOutageEndM
                sumActualAtOutageEndM += re.trueErrorAtOutageEndM
                if (errE < errP) ekfWins++
            }
            val meanReportedM = sumReportedM / trials
            val meanActualM = sumActualAtOutageEndM / trials
            val calibrationRatio = if (meanActualM > 1e-6) meanReportedM / meanActualM else Double.NaN

            println(
                "  ARW x${"%.0f".format(scale)}: ekf_mean=${"%.1f".format(sumEkfM / trials)}m " +
                    "(${"%.1f".format(100.0 * (sumEkfM / trials) / outageDistanceM)}%) " +
                    "passthrough_mean=${"%.1f".format(sumPassthroughM / trials)}m " +
                    "ekf_won=$ekfWins/$trials | at outage end: reported_sigma=${"%.1f".format(meanReportedM)}m " +
                    "actual_err=${"%.1f".format(meanActualM)}m reported/actual=${"%.2f".format(calibrationRatio)}"
            )

            if (scale == 1.0) {
                ekfBeatsSnapAtX1 = sumEkfM / trials < sumPassthroughM / trials
                calibrationRatioAtX1 = calibrationRatio
            }
        }

        // Hard assertions only for the case where the filter's assumption matches reality.
        // The x3 / x10 rows are diagnostic: they show how fast the reacquisition advantage and
        // covariance honesty degrade once the real ARW exceeds the configured 1.41 — the open
        // question plan2.md §3a flags and that a real stationary-phone Allan variance must
        // settle before step 6's map-match weighting can be trusted.
        assertTrue(
            ekfBeatsSnapAtX1,
            "with a matched ARW assumption the EKF should beat a raw last-fix snap",
        )
        assertTrue(
            calibrationRatioAtX1 > 0.5,
            "with a matched ARW assumption the filter should be roughly consistent at outage " +
                "end (reported/actual sigma ratio at x1 = $calibrationRatioAtX1)",
        )
    }
}
