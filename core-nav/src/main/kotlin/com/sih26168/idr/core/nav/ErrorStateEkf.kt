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

    override fun predict(
        deadReckoned: LatLon,
        speedMps: Float,
        headingDeg: Double,
        dtSeconds: Double,
        speedSigmaMps: Float?,
    ) {
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
        val sigmaV = (speedSigmaMps ?: config.ekfSpeedNoiseMps).toDouble()
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

    override fun updateWithGnss(fix: LatLon, speedMps: Float, bearingDeg: Float, horizAccM: Float, bearingValid: Boolean) {
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

        // GNSS course-over-ground as a direct heading measurement -- corrects theta faster
        // than waiting for it to emerge from the n/e-theta covariance alone. Gated on
        // bearingValid (Android reports 0f, not "unknown", when no bearing is available) and
        // a minimum speed (bearing is unreliable near-stationary).
        if (bearingValid && speedMps > config.ekfMinBearingTrustSpeedMps) {
            val bearingRad = Math.toRadians(bearingDeg.toDouble())
            val yTheta = ((bearingRad - theta + Math.PI).mod(2 * Math.PI)) - Math.PI
            val rTheta = Math.toRadians(config.ekfGnssBearingNoiseDeg.toDouble()).let { it * it }
            updateScalar(2, yTheta, rTheta)
        }
    }

    override fun updateWithMapMatch(
        position: LatLon,
        alongTrackSigmaM: Float,
        crossTrackSigmaM: Float,
        roadBearingDeg: Double,
    ) {
        val z = frame.toLocal(position)
        val yN = z.north - n
        val yE = z.east - e

        // Road axis unit vectors in (north, east): along the road, and across it.
        val b = Math.toRadians(roadBearingDeg)
        val alongN = cos(b); val alongE = sin(b)
        val crossN = -sin(b); val crossE = cos(b)

        // Two scalar updates in the rotated basis. The along-track one is a near-no-op when
        // alongTrackSigmaM is large: the matcher genuinely doesn't know where along a straight
        // road you are, so it must not shrink the along-track covariance.
        val alongSigma = alongTrackSigmaM.toDouble()
        val crossSigma = crossTrackSigmaM.toDouble()
        updateRow(alongN, alongE, 0.0, alongN * yN + alongE * yE, alongSigma * alongSigma)
        updateRow(crossN, crossE, 0.0, crossN * yN + crossE * yE, crossSigma * crossSigma)
    }

    /** Scalar Kalman update on state[idx] given innovation y and measurement noise r. */
    private fun updateScalar(idx: Int, y: Double, r: Double) {
        val s = p[idx][idx] + r
        if (s < 1e-12) return
        val k = DoubleArray(3) { i -> p[i][idx] / s }
        n += k[0] * y; e += k[1] * y; theta += k[2] * y
        val newP = Array(3) { i -> DoubleArray(3) { j -> p[i][j] - k[i] * p[idx][j] } }
        p = newP
        symmetrize()
    }

    /** Scalar Kalman update with an arbitrary row measurement matrix H = [hN, hE, hTheta],
     *  pre-computed innovation [y] and measurement noise [r]. Used for projected (rotated-axis)
     *  position measurements where H is not a single state selector. */
    private fun updateRow(hN: Double, hE: Double, hT: Double, y: Double, r: Double) {
        val ph0 = p[0][0] * hN + p[0][1] * hE + p[0][2] * hT
        val ph1 = p[1][0] * hN + p[1][1] * hE + p[1][2] * hT
        val ph2 = p[2][0] * hN + p[2][1] * hE + p[2][2] * hT
        val s = hN * ph0 + hE * ph1 + hT * ph2 + r
        if (s < 1e-12) return
        val k0 = ph0 / s; val k1 = ph1 / s; val k2 = ph2 / s
        n += k0 * y; e += k1 * y; theta += k2 * y
        val imKH = arrayOf(
            doubleArrayOf(1 - k0 * hN, -k0 * hE, -k0 * hT),
            doubleArrayOf(-k1 * hN, 1 - k1 * hE, -k1 * hT),
            doubleArrayOf(-k2 * hN, -k2 * hE, 1 - k2 * hT),
        )
        p = matMul(imKH, p)
        symmetrize()
    }

    override fun estimate(): LatLon = frame.toLatLon(LocalEnu(n, e))

    /**
     * 1-std along the covariance ellipse's MAJOR axis -- the largest eigenvalue of the 2x2
     * position block, not the RMS of the two variances. After an outage the covariance is
     * strongly anisotropic (large along-track, small cross-track once map-matched), and
     * averaging the variances understates the real uncertainty exactly then -- which is when
     * the UI ellipse and the drift readout are read.
     */
    override fun uncertaintyM(): Float {
        val a = p[0][0]; val b = p[1][1]; val c = p[0][1]
        val mean = (a + b) / 2.0
        val halfDiff = (a - b) / 2.0
        val lambdaMax = mean + sqrt(halfDiff * halfDiff + c * c)
        return sqrt(lambdaMax.coerceAtLeast(0.0)).toFloat()
    }

    override fun headingDeg(): Double = Math.toDegrees(theta).mod(360.0)

    override fun headingUncertaintyDeg(): Double = Math.toDegrees(sqrt(p[2][2]))

    /** Position covariance as [varNorth, varEast, covNorthEast] (m^2). The full 2x2 the
     *  uncertainty ellipse needs -- and what lets a caller check that a map-match update only
     *  shrank the cross-track axis. */
    fun positionCovarianceNE(): DoubleArray = doubleArrayOf(p[0][0], p[1][1], p[0][1])

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
