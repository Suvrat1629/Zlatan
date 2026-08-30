package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.LocalEnu
import com.sih26168.idr.core.types.LocalFrame
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Error-state EKF over local-frame position and heading: state = [n, e, theta].
 *
 * Heading is a real state, not just an exogenous input: it's nudged by the tick-to-tick
 * CHANGE in the incoming headingDeg (wrap-aware), carries its own ARW-driven variance, and
 * gets corrected by GNSS position fixes through the n/e-theta covariance built up in
 * predict(). A position-only version of this filter badly underestimated uncertainty after
 * a long outage and was slower than a hard snap to recover on reacquisition — see plan2.md.
 */
class ErrorStateEkf(
    initial: LatLon,
    private val config: EngineConfig = EngineConfig.DEFAULT,
) : FusionFilter {

    private val frame = LocalFrame(initial)

    // State [n, e, theta]: metres, metres, radians.
    private var n = 0.0
    private var e = 0.0
    private var theta = 0.0
    private var thetaInitialised = false
    private var lastInputHeadingDeg = 0.0

    // Symmetric 3x3 covariance, row-major.
    private var p = Array(3) { i -> DoubleArray(3) { j -> if (i == j) initialVariance(i) else 0.0 } }

    private fun initialVariance(i: Int): Double {
        val posVar = config.ekfInitialUncertaintyM.toDouble().let { it * it }
        val thetaVar = Math.toRadians(30.0).let { it * it }
        return if (i < 2) posVar else thetaVar
    }

    override fun predict(deadReckoned: LatLon, speedMps: Float, headingDeg: Double, dtSeconds: Double) {
        if (!thetaInitialised) {
            theta = Math.toRadians(headingDeg)
            lastInputHeadingDeg = headingDeg
            thetaInitialised = true
        }
        val rawDeltaDeg = ((headingDeg - lastInputHeadingDeg + 540.0).mod(360.0)) - 180.0
        lastInputHeadingDeg = headingDeg
        val dTheta = Math.toRadians(rawDeltaDeg)

        val c = cos(theta)
        val s = sin(theta)
        val v = speedMps.toDouble()
        val dt = dtSeconds

        n += v * c * dt
        e += v * s * dt
        theta += dTheta

        val fNT = -v * s * dt
        val fET = v * c * dt
        val f = arrayOf(
            doubleArrayOf(1.0, 0.0, fNT),
            doubleArrayOf(0.0, 1.0, fET),
            doubleArrayOf(0.0, 0.0, 1.0),
        )

        // sigmaTheta scales with sqrt(dt), not dt: heading error is an Angle Random Walk,
        // so its variance grows linearly with time.
        val sigmaV = config.ekfSpeedNoiseMps.toDouble()
        val sigmaTheta = Math.toRadians(config.ekfHeadingArwDegPerSqrtSec.toDouble()) * sqrt(dt)
        val qNNSpeed = (c * dt) * (c * dt) * sigmaV * sigmaV
        val qEESpeed = (s * dt) * (s * dt) * sigmaV * sigmaV
        val qNESpeed = (c * dt) * (s * dt) * sigmaV * sigmaV
        val q = arrayOf(
            doubleArrayOf(qNNSpeed, qNESpeed, 0.0),
            doubleArrayOf(qNESpeed, qEESpeed, 0.0),
            doubleArrayOf(0.0, 0.0, sigmaTheta * sigmaTheta),
        )

        p = add(matMul(matMul(f, p), transpose(f)), q)
        symmetrize()
    }

    override fun updateWithGnss(fix: LatLon, speedMps: Float, bearingDeg: Float, horizAccM: Float) {
        val z = frame.toLocal(fix)
        val r = maxOf(horizAccM, config.ekfMinGnssAccuracyM).toDouble()
        val rVar = r * r

        val yN = z.north - n
        val yE = z.east - e

        val pNN = p[0][0]; val pNE = p[0][1]; val pNT = p[0][2]
        val pEE = p[1][1]; val pET = p[1][2]

        val sNN = pNN + rVar
        val sEE = pEE + rVar
        val sNE = pNE
        val det = sNN * sEE - sNE * sNE
        if (det < 1e-9) return

        val invSNN = sEE / det
        val invSEE = sNN / det
        val invSNE = -sNE / det

        // K = P * H^T * S^-1, H selecting the n/e columns of P (GNSS observes position, not
        // heading directly — heading still gets corrected via the n/e-theta covariance).
        val kN0 = pNN * invSNN + pNE * invSNE; val kN1 = pNN * invSNE + pNE * invSEE
        val kE0 = pNE * invSNN + pEE * invSNE; val kE1 = pNE * invSNE + pEE * invSEE
        val kT0 = pNT * invSNN + pET * invSNE; val kT1 = pNT * invSNE + pET * invSEE

        n += kN0 * yN + kN1 * yE
        e += kE0 * yN + kE1 * yE
        theta += kT0 * yN + kT1 * yE

        val imKH = arrayOf(
            doubleArrayOf(1 - kN0, -kN1, 0.0),
            doubleArrayOf(-kE0, 1 - kE1, 0.0),
            doubleArrayOf(-kT0, -kT1, 1.0),
        )
        p = matMul(imKH, p)
        symmetrize()
    }

    override fun estimate(): LatLon = frame.toLatLon(LocalEnu(n, e))

    override fun uncertaintyM(): Float = sqrt((p[0][0] + p[1][1]) / 2.0).toFloat()

    /** Heading uncertainty (1 std, degrees). Not on FusionFilter yet. */
    fun headingUncertaintyDeg(): Double = Math.toDegrees(sqrt(p[2][2]))

    private fun symmetrize() {
        for (i in 0..2) for (j in i + 1..2) {
            val avg = (p[i][j] + p[j][i]) / 2.0
            p[i][j] = avg; p[j][i] = avg
        }
    }

    companion object {
        private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
            val r = Array(3) { DoubleArray(3) }
            for (i in 0..2) for (j in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += a[i][k] * b[k][j]
                r[i][j] = sum
            }
            return r
        }

        private fun transpose(a: Array<DoubleArray>): Array<DoubleArray> =
            Array(3) { i -> DoubleArray(3) { j -> a[j][i] } }

        private fun add(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
            Array(3) { i -> DoubleArray(3) { j -> a[i][j] + b[i][j] } }
    }
}
