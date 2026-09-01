package com.sih26168.idr.core.nav

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estimates the vehicle's forward direction in device coordinates, so longitudinal acceleration can
 * be recovered with its SIGN.
 *
 * ## Why this is needed
 *
 * [YawRate.aboutVertical] made heading mount-agnostic by projecting the gyro onto the gravity axis.
 * That solves rotation about the vertical, which is what heading needs — and it is only half of the
 * alignment problem. It says nothing about which way the vehicle points in the horizontal plane.
 *
 * The cost of that omission is concrete. `FeatureExtractor` emits horizontal acceleration as an
 * unsigned MAGNITUDE, so braking and accelerating produce identical inputs, and no model trained on
 * those features can ever predict signed acceleration. Verified against the shipped delta model: a
 * hard-braking window returns +0.30 m/s² where the truth is about −2.5, and in the field its output
 * was +0.01 m/s² whether the vehicle braked hard or accelerated hard. It was disabled for exactly
 * this reason (TODO.md K2).
 *
 * ## How the axis is found
 *
 * A vehicle's horizontal acceleration is not isotropic. It accelerates and brakes along one axis far
 * more than it slides sideways, so over a window the horizontal acceleration samples form an
 * elongated cloud whose long axis IS the forward axis. Recovering it is a 2x2 eigenproblem on the
 * horizontal covariance, which has a closed form — no iteration, no library.
 *
 * Two properties make this usable rather than merely elegant:
 *
 *  - It needs no GNSS, so it keeps working through the blackout where it matters most.
 *  - It is computed in the horizontal plane defined by gravity, so it inherits the mount-invariance
 *    the gravity projection already established.
 *
 * ## The sign ambiguity, and why it needs an external cue
 *
 * An eigenvector gives an axis, not a direction: forward and backward are equally good solutions.
 * Nothing in the covariance can break that tie, and picking arbitrarily would put a sign flip in the
 * middle of the feature the model depends on — worse than the unsigned magnitude it replaces.
 *
 * The tie is broken by a fact about vehicles rather than about geometry: **they accelerate more
 * gently than they brake.** A car or a two-wheeler is limited to roughly 0.3 g under power and can
 * exceed 0.8 g under braking, so the distribution of longitudinal acceleration is negatively skewed
 * along the forward axis. The sign is chosen to make the skew negative. This is measurable, holds
 * for any road vehicle, and needs no fix.
 *
 * Below [MIN_ANISOTROPY] the cloud is too round to carry a direction — cruising at constant speed on
 * a straight road produces almost no longitudinal signal — and the estimate is refused rather than
 * guessed. Callers hold the last confident axis.
 */
object ForwardAxis {

    /**
     * Ratio of major to minor eigenvalue below which the horizontal acceleration cloud is treated as
     * having no usable direction.
     *
     * 2.0 means the long axis must carry twice the variance of the short one. Argued rather than
     * fitted: at ratios near 1 the eigenvector is dominated by noise and rotates freely, so any
     * value it reports is arbitrary. Refusing is correct — a held stale axis is far better than a
     * spinning fresh one.
     */
    const val MIN_ANISOTROPY = 2.0f

    /** A forward axis in device coordinates, unit length and orthogonal to gravity. */
    data class Axis(val x: Float, val y: Float, val z: Float, val anisotropy: Float)

    /**
     * @param linAccel horizontal-plane linear acceleration samples, already gravity-removed, as
     *   flat triples [x0,y0,z0, x1,y1,z1, ...]
     * @param gravity gravity vector for the same window, any magnitude
     * @return the forward axis, or null when the window carries no usable direction
     */
    fun estimate(linAccel: FloatArray, gravity: FloatArray): Axis? {
        require(linAccel.size % 3 == 0) { "linAccel must be triples, got ${linAccel.size}" }
        val n = linAccel.size / 3
        if (n < 8) return null

        val gm = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (gm < 1e-3f || gm.isNaN()) return null
        val ux = gravity[0] / gm; val uy = gravity[1] / gm; val uz = gravity[2] / gm

        // Two orthonormal axes spanning the horizontal plane. The seed is chosen to be the axis
        // least aligned with gravity, so the cross product never degenerates.
        val seedX: Float; val seedY: Float; val seedZ: Float
        if (abs(ux) < abs(uy) && abs(ux) < abs(uz)) { seedX = 1f; seedY = 0f; seedZ = 0f }
        else if (abs(uy) < abs(uz)) { seedX = 0f; seedY = 1f; seedZ = 0f }
        else { seedX = 0f; seedY = 0f; seedZ = 1f }
        var e1x = uy * seedZ - uz * seedY
        var e1y = uz * seedX - ux * seedZ
        var e1z = ux * seedY - uy * seedX
        val e1m = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
        if (e1m < 1e-6f) return null
        e1x /= e1m; e1y /= e1m; e1z /= e1m
        val e2x = uy * e1z - uz * e1y
        val e2y = uz * e1x - ux * e1z
        val e2z = ux * e1y - uy * e1x

        // Project each sample into that plane and accumulate the 2x2 covariance.
        val a = FloatArray(n); val b = FloatArray(n)
        var ma = 0.0; var mb = 0.0
        for (i in 0 until n) {
            val x = linAccel[3 * i]; val y = linAccel[3 * i + 1]; val z = linAccel[3 * i + 2]
            val pa = x * e1x + y * e1y + z * e1z
            val pb = x * e2x + y * e2y + z * e2z
            a[i] = pa; b[i] = pb; ma += pa; mb += pb
        }
        ma /= n; mb /= n
        var saa = 0.0; var sbb = 0.0; var sab = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma; val db = b[i] - mb
            saa += da * da; sbb += db * db; sab += da * db
        }
        saa /= n; sbb /= n; sab /= n

        // Closed-form eigen-decomposition of [[saa,sab],[sab,sbb]].
        val tr = saa + sbb
        val det = saa * sbb - sab * sab
        val disc = tr * tr / 4.0 - det
        if (disc < 0.0) return null
        val root = sqrt(disc)
        val l1 = tr / 2.0 + root
        val l2 = tr / 2.0 - root
        if (l1 <= 1e-9) return null
        val anisotropy = (l1 / kotlin.math.max(l2, 1e-9)).toFloat()
        if (anisotropy < MIN_ANISOTROPY) return null

        // Eigenvector for the major eigenvalue, in plane coordinates.
        var va: Double; var vb: Double
        if (abs(sab) > 1e-12) { va = l1 - sbb; vb = sab }
        else if (saa >= sbb) { va = 1.0; vb = 0.0 }
        else { va = 0.0; vb = 1.0 }
        val vm = sqrt(va * va + vb * vb)
        if (vm < 1e-12) return null
        va /= vm; vb /= vm

        // Break the sign ambiguity: vehicles brake harder than they accelerate, so the projection
        // onto the true forward axis is negatively skewed. Flip until it is.
        var skew = 0.0
        for (i in 0 until n) {
            val p = (a[i] - ma) * va + (b[i] - mb) * vb
            skew += p * p * p
        }
        val s = if (skew > 0.0) -1.0 else 1.0
        va *= s; vb *= s

        return Axis(
            x = (va * e1x + vb * e2x).toFloat(),
            y = (va * e1y + vb * e2y).toFloat(),
            z = (va * e1z + vb * e2z).toFloat(),
            anisotropy = anisotropy,
        )
    }

    /** Signed longitudinal acceleration: positive under power, negative under braking. */
    fun longitudinal(lx: Float, ly: Float, lz: Float, axis: Axis): Float =
        lx * axis.x + ly * axis.y + lz * axis.z
}
