package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compass fusion with the phone-to-vehicle mount offset carried as a filter state.
 *
 * The whole design rests on one claim: the same measurement corrects the offset while GNSS is
 * healthy and corrects heading once it is not, with the covariance deciding which and no mode
 * switch anywhere. These tests are that claim, in order — learn the offset, then spend it.
 */
class MagneticHeadingTest {

    private val start = LatLon(12.9716, 77.5946)
    private val speedMps = 15f
    private val dt = 0.1
    private val trueHeadingDeg = 0.0          // driving due north
    // Non-zero on purpose: this is the sign convention most likely to be wrong. GeomagneticField
    // reports declination positive east, so true = magnetic + declination, and Bengaluru is about
    // -1. A flipped sign here would show up as the offset landing two degrees out.
    private val declinationDeg = -1.0
    private val config = EngineConfig.DEFAULT
    private val sigmaHigh = config.ekfMagHeadingNoiseHighDeg

    /** What a compass reads with the phone rotated [mountDeg] from the vehicle's nose. */
    private fun magReading(vehicleHeadingDeg: Double, mountDeg: Double) =
        (vehicleHeadingDeg - mountDeg - declinationDeg + 360.0).mod(360.0)

    private fun wrapDeg(deg: Double) = ((deg + 540.0).mod(360.0)) - 180.0

    /**
     * Phase A: drive straight with healthy GNSS (position and bearing), feeding the compass. The
     * offset is the only badly-known state, so this is where it should converge.
     */
    private fun learnOffset(
        ekf: ErrorStateEkf,
        mountDeg: Double,
        ticks: Int = 300,
        gnssEveryTicks: Int = 10,
    ): LatLon {
        var truePos = start
        var measuredHeadingDeg = 0.0
        for (i in 0 until ticks) {
            truePos = Geo.stepForward(truePos, trueHeadingDeg, speedMps * dt)
            ekf.predict(truePos, speedMps, measuredHeadingDeg, dt)
            if (i % gnssEveryTicks == 0) {
                ekf.updateWithGnss(
                    truePos, speedMps, trueHeadingDeg.toFloat(), 5f, bearingValid = true,
                )
            }
            ekf.updateWithMagneticHeading(magReading(trueHeadingDeg, mountDeg), declinationDeg, sigmaHigh)
        }
        return truePos
    }

    @Test
    fun learnsTheMountOffsetWhileGnssIsHealthy() {
        val ekf = ErrorStateEkf(start, config)
        learnOffset(ekf, mountDeg = 90.0)

        // offset = vehicle heading - compass reading. Phone rotated 90 degrees from the nose, so
        // the compass reads 90 degrees less than the vehicle heads, and the offset is +90.
        // Tight bound, and that is the point: a declination sign error would land this near +88.
        val offsetError = wrapDeg(ekf.mountOffsetDeg() - 90.0)
        assertTrue(abs(offsetError) < 1.0, "mount offset should converge to +90; got ${ekf.mountOffsetDeg()}")

        // And it must have converged by moving the offset, not by dragging heading off true.
        val headingError = wrapDeg(ekf.headingDeg() - trueHeadingDeg)
        assertTrue(abs(headingError) < 3.0, "heading was pulled off true while learning: $headingError deg")
    }

    /**
     * Phase B, the payoff: GNSS gone, gyro drifting, compass the only absolute reference left. This
     * is the case road bearing cannot cover — off the graph, or before the matcher re-locks.
     */
    private fun headingErrorAfterBlackout(useCompass: Boolean): Double {
        val ekf = ErrorStateEkf(start, config)
        val mountDeg = 90.0
        var truePos = learnOffset(ekf, mountDeg)

        var measuredHeadingDeg = 0.0
        val driftDps = 0.5
        for (i in 0 until 600) {
            truePos = Geo.stepForward(truePos, trueHeadingDeg, speedMps * dt)
            measuredHeadingDeg += driftDps * dt
            ekf.predict(truePos, speedMps, measuredHeadingDeg, dt)
            if (useCompass) {
                ekf.updateWithMagneticHeading(magReading(trueHeadingDeg, mountDeg), declinationDeg, sigmaHigh)
            }
        }
        return abs(wrapDeg(ekf.headingDeg() - trueHeadingDeg))
    }

    @Test
    fun theLearnedOffsetHoldsHeadingThroughABlackout() {
        val withCompass = headingErrorAfterBlackout(useCompass = true)
        val withoutCompass = headingErrorAfterBlackout(useCompass = false)
        assertTrue(withoutCompass > 20.0, "60 s of 0.5 deg/s drift should show; got $withoutCompass deg")
        assertTrue(withCompass < 5.0, "the compass should hold heading; got $withCompass deg")
    }

    /**
     * The knocked phone. A converged offset with no process noise could never recover from the
     * cradle being bumped, which is the one thing that genuinely changes it. This is what
     * ekfMountOffsetRandomWalkDegPerSqrtSec is sized for — a failure here is a config finding.
     */
    @Test
    fun recoversAfterThePhoneIsReSeated() {
        val ekf = ErrorStateEkf(start, config)
        var truePos = learnOffset(ekf, mountDeg = 90.0)

        var measuredHeadingDeg = 0.0
        val newMountDeg = -90.0
        for (i in 0 until 900) {                       // 90 s
            truePos = Geo.stepForward(truePos, trueHeadingDeg, speedMps * dt)
            ekf.predict(truePos, speedMps, measuredHeadingDeg, dt)
            if (i % 10 == 0) {
                ekf.updateWithGnss(truePos, speedMps, trueHeadingDeg.toFloat(), 5f, bearingValid = true)
            }
            ekf.updateWithMagneticHeading(magReading(trueHeadingDeg, newMountDeg), declinationDeg, sigmaHigh)
        }
        val offsetError = wrapDeg(ekf.mountOffsetDeg() - newMountDeg)
        assertTrue(
            abs(offsetError) < 10.0,
            "offset should chase the new mount within 90 s; got ${ekf.mountOffsetDeg()}, wanted $newMountDeg",
        )
    }

    /**
     * At a stop the compass and the zero-velocity gyro observation both fire, and the compass has
     * coupled itself to the other states by then. The gyro bias must still come only from the gyro:
     * a compass reading is not evidence about a rate sensor's offset.
     */
    @Test
    fun aCompassReadingAtAStopDoesNotDisturbTheGyroBias() {
        val ekf = ErrorStateEkf(start, config)
        learnOffset(ekf, mountDeg = 90.0)

        var measuredHeadingDeg = 0.0
        repeat(200) {
            ekf.predict(start, 0f, measuredHeadingDeg, dt)
            ekf.updateStationaryGyro(0f)
        }
        val biasBefore = ekf.gyroBiasDps()
        repeat(200) {
            ekf.predict(start, 0f, measuredHeadingDeg, dt)
            ekf.updateStationaryGyro(0f)
            ekf.updateWithMagneticHeading(magReading(trueHeadingDeg, 90.0), declinationDeg, sigmaHigh)
        }
        assertTrue(
            abs(ekf.gyroBiasDps() - biasBefore) < 0.05,
            "compass moved the gyro bias: $biasBefore -> ${ekf.gyroBiasDps()} deg/s",
        )
    }
}
