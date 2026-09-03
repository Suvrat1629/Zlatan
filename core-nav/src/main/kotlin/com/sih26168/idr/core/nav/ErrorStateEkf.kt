package com.sih26168.idr.core.nav

import com.sih26168.idr.core.types.EngineConfig
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.LocalEnu
import com.sih26168.idr.core.types.LocalFrame
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Error-state EKF over local-frame position, heading and gyro bias: state = [n, e, theta, biasZ].
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

    // State [n, e, theta, biasZ, mountOffset]: metres, metres, radians, radians/second, radians.
    //
    // biasZ is the yaw-rate bias (heading work plan F3). The incoming heading delta already
    // contains it, so the true rotation is (dTheta - biasZ * dt). It is a state rather than a
    // calibration constant because removing a fitted constant bought nothing offline -- the bias
    // moves within a drive, largely with temperature, and a dashboard phone gets hot. It is
    // observable two ways: through the theta-bias covariance whenever a trusted GNSS bearing
    // arrives, and directly from a zero-velocity interval, where a stationary vehicle means any
    // measured yaw rate IS bias.
    private var n = 0.0
    private var e = 0.0
    private var theta = 0.0
    private var biasZ = 0.0
    // Mount offset: vehicle heading minus the phone's true-north azimuth, radians. The compass
    // reads the phone, the filter tracks the vehicle, and a phone is mounted however the driver
    // felt like -- so the two differ by a constant that nobody can know in advance. Carrying it as
    // a state rather than calibrating it separately is what makes the compass usable at all: with
    // a wide prior on the offset and a tight one on theta, the first compass reading corrects the
    // offset and leaves heading alone; once the offset has converged and GNSS drops, theta is the
    // uncertain one and the same measurement corrects heading instead. The filter's covariance
    // decides which, with no mode switch to get wrong.
    private var mountOffset = 0.0
    private var thetaInitialised = false
    private var lastInputHeadingDeg = 0.0

    // Innovation gate bookkeeping.
    private var lastNis = Double.NaN
    private var rejectedFixes = 0L
    private var consecutiveRejects = 0

    // Symmetric NxN covariance, row-major.
    private var p = Array(N) { i -> DoubleArray(N) { j -> if (i == j) initialVariance(i) else 0.0 } }

    private fun initialVariance(i: Int): Double = when (i) {
        0, 1 -> config.ekfInitialUncertaintyM.toDouble().let { it * it }
        2 -> Math.toRadians(30.0).let { it * it }
        3 -> Math.toRadians(config.ekfInitialGyroBiasDps.toDouble()).let { it * it }
        // Deliberately enormous: the phone's orientation in the cradle is genuinely unknown, and
        // a wide prior here is what routes the first compass innovation into the offset instead
        // of into heading.
        else -> Math.toRadians(config.ekfInitialMountOffsetDeg.toDouble()).let { it * it }
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
        // The measured rotation carries the bias; the true rotation is what is left after
        // removing the bias accumulated over this interval.
        val dTheta = Math.toRadians(rawDeltaDeg) - biasZ * dtSeconds

        val c = cos(theta)
        val s = sin(theta)
        val v = speedMps.toDouble()
        val dt = dtSeconds

        // Exact coordinated-turn arc, not a straight segment along one heading (heading work plan
        // F2). Advancing along the start-of-interval heading and rotating afterwards turns LATE, so
        // the path swings wide -- the observed overshoot into the next street. The error scales
        // with turn rate, which is why it only shows on sharp turns. For constant v and omega the
        // true path is a circular arc with a closed form; below ARC_MIN_DTHETA it degenerates to
        // the straight-line form, which is also what avoids dividing by ~0.
        val thetaEnd = theta + dTheta
        if (kotlin.math.abs(dTheta) > ARC_MIN_DTHETA) {
            val radius = v * dt / dTheta          // v / omega
            n += radius * (sin(thetaEnd) - s)
            e += radius * (c - cos(thetaEnd))
        } else {
            n += v * c * dt
            e += v * s * dt
        }
        theta = thetaEnd

        val fNT = -v * s * dt
        val fET = v * c * dt
        // d(theta)/d(bias) = -dt: an over-estimated bias subtracts too much rotation, which is
        // exactly the coupling that lets a GNSS bearing correction flow back into the bias.
        // The mount offset does not propagate: it is a property of the cradle, not of motion, so
        // its row is the identity and it couples to nothing here. It moves only through a
        // measurement, or through its own random walk in Q below.
        val f = arrayOf(
            doubleArrayOf(1.0, 0.0, fNT, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, fET, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0, -dt, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0),
        )

        // Jacobian kept in its straight-segment form: over one 100 ms tick the arc correction to
        // F is second order in dTheta and does not measurably change the covariance, while the
        // state update above genuinely does move the position.
        // sigmaTheta scales with sqrt(dt), not dt: heading error is an Angle Random Walk,
        // so its variance grows linearly with time.
        val sigmaV = (speedSigmaMps ?: config.ekfSpeedNoiseMps).toDouble()
        val sigmaTheta = Math.toRadians(config.ekfHeadingArwDegPerSqrtSec.toDouble()) * sqrt(dt)
        val qNNSpeed = (c * dt) * (c * dt) * sigmaV * sigmaV
        val qEESpeed = (s * dt) * (s * dt) * sigmaV * sigmaV
        val qNESpeed = (c * dt) * (s * dt) * sigmaV * sigmaV
        // Bias random walk: variance grows linearly with time, so sigma scales with sqrt(dt).
        val sigmaBias = Math.toRadians(config.ekfGyroBiasRandomWalkDpsPerSqrtSec.toDouble()) * sqrt(dt)
        // Mount random walk: small, but not zero. A converged offset with zero process noise can
        // never recover from the phone being knocked or re-seated mid-drive, which is the one
        // thing that actually changes it.
        val sigmaMount = Math.toRadians(config.ekfMountOffsetRandomWalkDegPerSqrtSec.toDouble()) * sqrt(dt)
        val q = arrayOf(
            doubleArrayOf(qNNSpeed, qNESpeed, 0.0, 0.0, 0.0),
            doubleArrayOf(qNESpeed, qEESpeed, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, sigmaTheta * sigmaTheta, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, sigmaBias * sigmaBias, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, sigmaMount * sigmaMount),
        )

        p = add(matMul(matMul(f, p), transpose(f)), q)
        symmetrize()
        // Keep theta in (-pi, pi]. headingDeg() wraps on read, so the published value was always
        // right, but the stored angle itself grew without bound -- every lap of a roundabout added
        // 2*pi. Innovations are differences against theta, and a double loses absolute precision as
        // its magnitude grows, so an unwrapped angle quietly degrades every heading measurement.
        theta = wrapToPi(theta)
        // Same for the offset: every path that moves theta now moves this too, including the GNSS
        // position update once the compass has coupled them.
        mountOffset = wrapToPi(mountOffset)

        // Independent of the wrapping above: that bounds the two angle states, this bounds and
        // fades biasZ. Order does not matter -- constrainGyroBias touches only the scalar bias.
        elapsedNanos += (dt * 1e9).toLong()
        constrainGyroBias(dt)
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
        if (det < 1e-9) {
            // A degenerate innovation covariance is a filter problem, not a fix problem. Returning
            // silently here hid it; count it so it shows up in telemetry instead.
            rejectedFixes++
            return
        }

        val invSNN = sEE / det
        val invSEE = sNN / det
        val invSNE = -sNE / det

        // Innovation gate. NIS = y' S^-1 y, chi-square with 2 degrees of freedom for a 2D position
        // measurement. A multipath fix arrives with a confident accuracy figure, so horizAccM alone
        // cannot reject it -- but it disagrees with the filter's own prediction, and that is what
        // this measures. The escape hatch matters as much as the gate: a diverged filter makes every
        // honest fix look like an outlier, so after a run of rejections we accept and widen instead
        // of locking GNSS out forever.
        lastNis = yN * (invSNN * yN + invSNE * yE) + yE * (invSNE * yN + invSEE * yE)
        if (config.useGnssNisGate &&
            lastNis > config.ekfGnssNisGate &&
            consecutiveRejects < config.ekfMaxConsecutiveGnssRejects
        ) {
            rejectedFixes++
            consecutiveRejects++
            return
        }
        if (config.useGnssNisGate && consecutiveRejects >= config.ekfMaxConsecutiveGnssRejects) {
            // Trust the fix and admit the state is stale: inflate position variance so the update
            // below can actually move it.
            val inflate = config.ekfInitialUncertaintyM.toDouble().let { it * it }
            p[0][0] += inflate; p[1][1] += inflate
        }
        consecutiveRejects = 0

        // K = P * H^T * S^-1, H selecting the n/e columns of P (GNSS observes position, not
        // heading directly — heading still gets corrected via the n/e-theta covariance).
        val pBN = p[3][0]; val pBE = p[3][1]

        val kN0 = pNN * invSNN + pNE * invSNE; val kN1 = pNN * invSNE + pNE * invSEE
        val kE0 = pNE * invSNN + pEE * invSNE; val kE1 = pNE * invSNE + pEE * invSEE
        val kT0 = pNT * invSNN + pET * invSNE; val kT1 = pNT * invSNE + pET * invSEE
        val kB0 = pBN * invSNN + pBE * invSNE; val kB1 = pBN * invSNE + pBE * invSEE

        n += kN0 * yN + kN1 * yE
        e += kE0 * yN + kE1 * yE
        theta += kT0 * yN + kT1 * yE
        biasZ += kB0 * yN + kB1 * yE

        // GNSS position does not observe the mount offset directly -- nothing about a fix says
        // which way the phone faces in its cradle -- so pMN/pME are exactly zero until the first
        // compass update couples p[4][2], which predict() then leaks into p[4][0] and p[4][1]
        // through fNT/fET. After that this gain is small but real. Not dead code.
        val pMN = p[4][0]; val pME = p[4][1]
        val kM0 = pMN * invSNN + pME * invSNE; val kM1 = pMN * invSNE + pME * invSEE
        mountOffset += kM0 * yN + kM1 * yE
        val imKH = arrayOf(
            doubleArrayOf(1 - kN0, -kN1, 0.0, 0.0, 0.0),
            doubleArrayOf(-kE0, 1 - kE1, 0.0, 0.0, 0.0),
            doubleArrayOf(-kT0, -kT1, 1.0, 0.0, 0.0),
            doubleArrayOf(-kB0, -kB1, 0.0, 1.0, 0.0),
            doubleArrayOf(-kM0, -kM1, 0.0, 0.0, 1.0),
        )
        p = matMul(imKH, p)
        symmetrize()

        // GNSS course-over-ground as a direct heading measurement -- corrects theta faster
        // than waiting for it to emerge from the n/e-theta covariance alone. Gated on
        // bearingValid (Android reports 0f, not "unknown", when no bearing is available) and
        // a minimum speed (bearing is unreliable near-stationary).
        if (bearingValid && speedMps > config.ekfMinBearingTrustSpeedMps) {
            val bearingRad = Math.toRadians(bearingDeg.toDouble())
            val yTheta = wrapToPi(bearingRad - theta)
            val rTheta = Math.toRadians(config.ekfGnssBearingNoiseDeg.toDouble()).let { it * it }
            updateScalar(2, yTheta, rTheta)
        }
    }

    /**
     * Road bearing as a heading measurement. The road under the vehicle is an absolute heading
     * reference the gyro does not have, and unlike GNSS course it survives a blackout -- on a long
     * straight, where heading random walk is the dominant cross-track error, it is the only
     * absolute reference available.
     *
     * The innovation wraps to +/-pi/2, not +/-pi, because a way's direction of travel is arbitrary:
     * a road drawn south-to-north and one drawn north-to-south describe the same road. Wrapping to
     * a quarter turn resolves that 180-degree ambiguity toward whichever end the filter already
     * believes in, which is also why a bearing exactly reversed from the true heading produces the
     * same correction as an aligned one.
     *
     * LIMITATION, and it follows directly from that: a genuine 180-degree heading error cannot be
     * corrected here. The road reports the reverse of what the filter believes, the wrap folds
     * that to a zero innovation, and the filter is confirmed in the wrong direction rather than
     * turned around. Recovering a reversed heading is GNSS course's job (updateWithGnss above),
     * or the compass's. The road can hold a heading; it cannot find one.
     */
    override fun updateWithRoadBearing(roadBearingDeg: Double, sigmaDeg: Float) {
        val bearingRad = Math.toRadians(roadBearingDeg)
        val y = ((bearingRad - theta + Math.PI / 2).mod(Math.PI)) - Math.PI / 2
        val r = Math.toRadians(sigmaDeg.toDouble()).let { it * it }
        updateScalar(2, y, r)
    }

    /**
     * Compass azimuth as a heading measurement, with the mount offset solved alongside it.
     *
     * The measurement model is: vehicle heading = phone azimuth + declination + mount offset. So
     * the innovation is that predicted heading minus the filter's own, and H picks out
     * (-1) on theta and (+1) on the offset -- one measurement, two states, and the covariance
     * apportions it. Early on, offset variance dwarfs heading variance and the correction lands
     * almost entirely on the offset; after a blackout has widened heading, it lands on heading.
     *
     * [declinationDeg] converts magnetic north to true north, which is the frame the filter's
     * heading and GNSS bearing are already in. It depends on where on Earth the vehicle is, so it
     * is passed in rather than assumed -- folding it into the mount offset would make the offset
     * wrong as soon as the vehicle travelled any distance.
     */
    override fun updateWithMagneticHeading(magHeadingDeg: Double, declinationDeg: Double, sigmaDeg: Float) {
        val predictedTheta = Math.toRadians(magHeadingDeg + declinationDeg) + mountOffset
        val y = wrapToPi(predictedTheta - theta)
        val r = Math.toRadians(sigmaDeg.toDouble()).let { it * it }
        updateHeadingRow(y, r)
    }

    /**
     * Scalar update for the compass measurement. H is the Jacobian of what the compass SHOULD
     * read given the state -- h(x) = theta - mountOffset - declination -- so it is
     * [0, 0, +1, 0, -1], the negation of the innovation's own derivative. Getting that backwards
     * drives both states the wrong way while still producing a plausible-looking S, so: a compass
     * reading ahead of the filter's heading must raise theta and lower the offset.
     *
     * Kept separate from [updateRow], which assumes a position-shaped H with no bias or mount
     * column.
     */
    private fun updateHeadingRow(y: Double, r: Double) {
        val h = doubleArrayOf(0.0, 0.0, 1.0, 0.0, -1.0)
        val ph = DoubleArray(N) { i -> (0 until N).sumOf { j -> p[i][j] * h[j] } }
        val s = (0 until N).sumOf { i -> h[i] * ph[i] } + r
        if (s < 1e-12) return
        val k = DoubleArray(N) { i -> ph[i] / s }
        n += k[0] * y; e += k[1] * y; theta += k[2] * y; biasZ += k[3] * y
        mountOffset += k[4] * y
        val imKH = Array(N) { i -> DoubleArray(N) { j -> (if (i == j) 1.0 else 0.0) - k[i] * h[j] } }
        p = matMul(imKH, p)
        symmetrize()
    }

    /** Estimated mount offset, degrees, wrapped to (-180, 180]. NaN-free from the first tick: it
     *  starts at 0 with an enormous variance, so read it together with the compass columns rather
     *  than on its own -- an offset that has never seen a HIGH-accuracy reading is still the prior. */
    override fun mountOffsetDeg(): Double = Math.toDegrees(wrapToPi(mountOffset))

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
        val k = DoubleArray(N) { i -> p[i][idx] / s }
        n += k[0] * y; e += k[1] * y; theta += k[2] * y; biasZ += k[3] * y
        mountOffset += k[4] * y
        val newP = Array(N) { i -> DoubleArray(N) { j -> p[i][j] - k[i] * p[idx][j] } }
        p = newP
        symmetrize()
    }

    /** Scalar Kalman update with an arbitrary row measurement matrix H = [hN, hE, hTheta],
     *  pre-computed innovation [y] and measurement noise [r]. Used for projected (rotated-axis)
     *  position measurements where H is not a single state selector. */
    private fun updateRow(hN: Double, hE: Double, hT: Double, y: Double, r: Double) {
        val h = doubleArrayOf(hN, hE, hT, 0.0, 0.0)
        val ph = DoubleArray(N) { i -> (0 until N).sumOf { j -> p[i][j] * h[j] } }
        val s = (0 until N).sumOf { i -> h[i] * ph[i] } + r
        if (s < 1e-12) return
        val k = DoubleArray(N) { i -> ph[i] / s }
        n += k[0] * y; e += k[1] * y; theta += k[2] * y; biasZ += k[3] * y
        mountOffset += k[4] * y
        val imKH = Array(N) { i -> DoubleArray(N) { j -> (if (i == j) 1.0 else 0.0) - k[i] * h[j] } }
        p = matMul(imKH, p)
        symmetrize()
    }

    /**
     * Zero-velocity gyro observation (heading work plan F3). A stationary vehicle cannot be
     * rotating, so whatever yaw rate the gyroscope reports IS the bias -- a direct measurement of
     * the state, and the only one available during a GNSS blackout in stop-and-go traffic. This is
     * why ZUPT is worth more than the speed reset it is usually built for.
     */
    override fun updateStationaryGyro(measuredYawRateRadS: Float) {
        val r = Math.toRadians(config.ekfZuptGyroNoiseDps.toDouble()).let { it * it }
        updateScalar(3, measuredYawRateRadS.toDouble() - biasZ, r)
        // A stationary interval is the ONLY measurement where "all observed rotation is bias" is
        // true by construction, so it is the only one that resets this clock. Everything else the
        // bias couples to -- GNSS position residuals, GNSS course -- also carries heading error and
        // map-match pull, and cannot tell those apart from a rate-sensor offset.
        lastDirectBiasObservationNanos = elapsedNanos
        // Bound here as well as in predict(). A stop reporting several deg/s of rotation is far
        // more likely a mis-detected stop -- the vehicle was actually turning -- than a real
        // rate-sensor offset, and this is the one measurement that can drive the state hard in a
        // single update. Clamping only on the next propagation would leave the impossible value
        // visible to telemetry in between.
        clampGyroBias()
    }

    /** Estimated yaw-rate bias, deg/s. Exposed for telemetry: whether it converges to a stable
     *  value per device is the check that this state is doing its job rather than absorbing noise. */
    private var elapsedNanos = 0L
    private var lastDirectBiasObservationNanos = -1L

    /**
     * Keep the yaw-rate bias inside what a gyroscope can actually have, and shrink it toward the
     * prior when nothing has observed it directly.
     *
     * Measured across three rides, this state does not track a bias — it absorbs heading error and
     * re-injects it during the next blackout, with a sign that changes per session:
     *
     *   ride A: settled at +0.38 deg/s -> heading drifted LEFT
     *   ride B: settled at -0.43 deg/s -> heading drifted RIGHT, +1.55 deg/s excess
     *   ride C: excursion to -3.13 deg/s
     *
     * `dTheta = raw - biasZ * dt`, so a negative estimate ADDS rotation — that is the mechanism,
     * and it is why the same defect reads as a left bias one day and a right bias the next.
     * -3.13 deg/s is an order of magnitude past any consumer MEMS residual offset: the state is
     * being used as a dumping ground, exactly as [EngineConfig.ekfInitialGyroBiasDps] warned.
     *
     * Two constraints, both physical rather than fitted:
     *
     *  - a hard bound, because a factory-calibrated gyro's residual offset is a few tenths of a
     *    deg/s and nothing the filter can observe makes 3 deg/s true;
     *  - shrinkage toward zero when [updateStationaryGyro] has not fired recently. Without a direct
     *    observation the prior is a better estimate than one inferred from residuals that conflate
     *    bias with heading error, so it decays back rather than persisting into the next blackout.
     *
     * The half-life is long relative to how often stops occur — ZUPT fired on 17.7% of ticks on the
     * measured ride — so a genuine bias seen at stops is held. Only an unobserved one fades.
     */
    private fun clampGyroBias() {
        val maxRad = Math.toRadians(config.ekfMaxGyroBiasDps.toDouble())
        biasZ = biasZ.coerceIn(-maxRad, maxRad)
    }

    private fun constrainGyroBias(dt: Double) {
        clampGyroBias()

        val halfLife = config.ekfGyroBiasDecayHalfLifeSeconds
        if (halfLife <= 0.0) return
        val sinceObservation = if (lastDirectBiasObservationNanos < 0) Double.MAX_VALUE
                               else (elapsedNanos - lastDirectBiasObservationNanos) / 1e9
        if (sinceObservation < halfLife) return
        biasZ *= Math.pow(0.5, dt / halfLife)
    }

    override fun gyroBiasDps(): Double = Math.toDegrees(biasZ)

    override fun lastGnssNis(): Double = lastNis

    override fun gnssRejectedCount(): Long = rejectedFixes

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
        for (i in 0 until N) for (j in i + 1 until N) {
            val avg = (p[i][j] + p[j][i]) / 2.0
            p[i][j] = avg; p[j][i] = avg
        }
    }

    companion object {
        /** Below this per-tick rotation the arc and the straight segment agree to well under a
         *  millimetre at road speed, and the v/omega division would be numerically pointless. */
        private const val ARC_MIN_DTHETA = 1e-6

        /** Shortest signed angle in (-pi, pi]. */
        private fun wrapToPi(radians: Double): Double =
            ((radians + Math.PI).mod(2 * Math.PI)) - Math.PI

        /** State dimension: [n, e, theta, biasZ, mountOffset]. */
        const val N = 5

        private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
            val r = Array(N) { DoubleArray(N) }
            for (i in 0 until N) for (j in 0 until N) {
                var sum = 0.0
                for (k in 0 until N) sum += a[i][k] * b[k][j]
                r[i][j] = sum
            }
            return r
        }

        private fun transpose(a: Array<DoubleArray>): Array<DoubleArray> =
            Array(N) { i -> DoubleArray(N) { j -> a[j][i] } }

        private fun add(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
            Array(N) { i -> DoubleArray(N) { j -> a[i][j] + b[i][j] } }
    }
}
