package com.sih26168.idr.core.nav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Hard-iron + soft-iron magnetometer calibration, driven by a user figure-8 motion. Feed it
 * raw (uncalibrated) magnetometer samples as the phone rotates; it fits a sphere to them
 * incrementally (streaming — no sample storage) and reports both the correction and whether
 * it's good enough to finish on.
 *
 * Model: at one location, Earth's field has constant magnitude and direction — only the
 * phone's orientation changes as it rotates, so raw readings should trace a sphere centered
 * on the origin. Hard iron (nearby ferrous material, magnets) offsets that center; soft iron
 * (the phone's own case/speakers) stretches it unevenly per axis. This fits the center via
 * incremental least squares (x²+y²+z² = 2ax+2by+2cz+d, solved from streaming moment sums —
 * every sample contributes, one bad sample can't ruin it the way min/max tracking would) and
 * corrects per-axis soft iron via RMS deviation from that center. It does not correct
 * cross-axis (off-diagonal) soft iron, which needs a full ellipsoid fit — not worth the extra
 * complexity here.
 *
 * Read `TYPE_MAGNETIC_FIELD_UNCALIBRATED`, not `TYPE_MAGNETIC_FIELD` — the latter already has
 * the vendor's own hard-iron estimate baked in and drifting, so fitting against it means
 * fitting a moving target.
 */
class MagnetometerCalibrator {

    // Streaming moment sums for the incremental sphere fit. No raw samples are kept.
    private var n = 0L
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0
    private var sxx = 0.0
    private var syy = 0.0
    private var szz = 0.0
    private var sxy = 0.0
    private var sxz = 0.0
    private var syz = 0.0
    private var sxl = 0.0
    private var syl = 0.0
    private var szl = 0.0
    private var sl = 0.0

    // Bounded recent-sample buffer, used only for directional coverage. Binning against a
    // causal running mean is order-sensitive — it systematically under-counts coverage for a
    // path that sweeps smoothly through orientation space (exactly what a real rotation looks
    // like), because the mean lags behind wherever the sweep currently is. Recomputing octants
    // against the current best-fit center on every query avoids that; this is the one place
    // that keeps recent raw samples, not the whole history.
    private val recentX = FloatArray(COVERAGE_BUFFER_CAPACITY)
    private val recentY = FloatArray(COVERAGE_BUFFER_CAPACITY)
    private val recentZ = FloatArray(COVERAGE_BUFFER_CAPACITY)
    private var recentWriteIndex = 0
    private var recentFilled = 0

    // Out-of-sample residual: score each new sample against the fit *before* folding it in,
    // so this reflects how well the calibration predicts fresh data, not just how well it
    // fits what it already saw (which only ever improves and would lie about convergence).
    private var residualSum = 0.0
    private var residualSumSq = 0.0
    private var residualCount = 0L

    // solve() is called from several getters that all get queried together on every UI
    // refresh (coverage, consistency, field strength, isGoodEnough) — memoize it instead of
    // re-running the 4x4 solve on each one. Cache is read (via calibrate()) before it's
    // invalidated below, so residual scoring still uses the *previous* fit, as intended.
    private var cachedFit: Fit? = null
    private var fitIsCurrent = false

    fun addSample(mx: Float, my: Float, mz: Float) {
        if (n >= MIN_SAMPLES_FOR_FIT) {
            val calibrated = calibrate(mx, my, mz)
            val mag = sqrt(
                calibrated[0].toDouble() * calibrated[0] +
                    calibrated[1] * calibrated[1] +
                    calibrated[2] * calibrated[2]
            )
            residualSum += mag
            residualSumSq += mag * mag
            residualCount++
        }

        recentX[recentWriteIndex] = mx
        recentY[recentWriteIndex] = my
        recentZ[recentWriteIndex] = mz
        recentWriteIndex = (recentWriteIndex + 1) % COVERAGE_BUFFER_CAPACITY
        if (recentFilled < COVERAGE_BUFFER_CAPACITY) recentFilled++

        val x = mx.toDouble()
        val y = my.toDouble()
        val z = mz.toDouble()
        val l = x * x + y * y + z * z

        sx += x; sy += y; sz += z
        sxx += x * x; syy += y * y; szz += z * z
        sxy += x * y; sxz += x * z; syz += y * z
        sxl += x * l; syl += y * l; szl += z * l; sl += l
        n++
        fitIsCurrent = false
    }

    fun reset() {
        n = 0
        sx = 0.0; sy = 0.0; sz = 0.0
        sxx = 0.0; syy = 0.0; szz = 0.0
        sxy = 0.0; sxz = 0.0; syz = 0.0
        sxl = 0.0; syl = 0.0; szl = 0.0; sl = 0.0
        recentWriteIndex = 0; recentFilled = 0
        residualSum = 0.0; residualSumSq = 0.0; residualCount = 0
        cachedFit = null; fitIsCurrent = false
    }

    val samplesCollected: Int get() = n.toInt()

    private data class Fit(
        val centerX: Double, val centerY: Double, val centerZ: Double,
        val scaleX: Double, val scaleY: Double, val scaleZ: Double,
        val avgRmsUt: Double,
    )

    private fun solve(): Fit? {
        if (n < MIN_SAMPLES_FOR_FIT) return null
        if (fitIsCurrent) return cachedFit
        // Normal equations for x²+y²+z² = 2a·x + 2b·y + 2c·z + d, i.e. A^T A [2a,2b,2c,d] = A^T L.
        val augmented = arrayOf(
            doubleArrayOf(sxx, sxy, sxz, sx, sxl),
            doubleArrayOf(sxy, syy, syz, sy, syl),
            doubleArrayOf(sxz, syz, szz, sz, szl),
            doubleArrayOf(sx, sy, sz, n.toDouble(), sl),
        )
        val beta = solve4x4(augmented) ?: run {
            fitIsCurrent = true
            cachedFit = null
            return null
        }
        val a = beta[0] / 2.0
        val b = beta[1] / 2.0
        val c = beta[2] / 2.0

        val meanX = sx / n
        val meanY = sy / n
        val meanZ = sz / n
        val varX = (sxx / n - 2 * a * meanX + a * a).coerceAtLeast(0.0)
        val varY = (syy / n - 2 * b * meanY + b * b).coerceAtLeast(0.0)
        val varZ = (szz / n - 2 * c * meanZ + c * c).coerceAtLeast(0.0)
        val rmsX = sqrt(varX)
        val rmsY = sqrt(varY)
        val rmsZ = sqrt(varZ)
        val avgRms = (rmsX + rmsY + rmsZ) / 3.0
        val scaleX = if (rmsX > MIN_AXIS_RMS_UT) avgRms / rmsX else 1.0
        val scaleY = if (rmsY > MIN_AXIS_RMS_UT) avgRms / rmsY else 1.0
        val scaleZ = if (rmsZ > MIN_AXIS_RMS_UT) avgRms / rmsZ else 1.0
        val fit = Fit(a, b, c, scaleX, scaleY, scaleZ, avgRms)
        cachedFit = fit
        fitIsCurrent = true
        return fit
    }

    /** Gaussian elimination with partial pivoting on a 4x4 system, `m[i]` = row i of `[A|b]`.
     *  Mutates `m` in place — safe here since every caller builds a fresh array, but don't
     *  pass in anything you still need afterward. */
    private fun solve4x4(m: Array<DoubleArray>): DoubleArray? {
        for (col in 0 until 4) {
            var pivot = col
            for (row in col + 1 until 4) {
                if (kotlin.math.abs(m[row][col]) > kotlin.math.abs(m[pivot][col])) pivot = row
            }
            if (kotlin.math.abs(m[pivot][col]) < 1e-9) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp

            for (row in 0 until 4) {
                if (row == col) continue
                val factor = m[row][col] / m[col][col]
                for (c in col..4) m[row][c] -= factor * m[col][c]
            }
        }
        return DoubleArray(4) { m[it][4] / m[it][it] }
    }

    /** Hard-iron offset, µT — (0,0,0) until [MIN_SAMPLES_FOR_FIT] samples exist. */
    val hardIronOffset: FloatArray
        get() = solve()?.let { floatArrayOf(it.centerX.toFloat(), it.centerY.toFloat(), it.centerZ.toFloat()) }
            ?: floatArrayOf(0f, 0f, 0f)

    /** Soft-iron per-axis scale — (1,1,1) until [MIN_SAMPLES_FOR_FIT] samples exist. */
    val softIronScale: FloatArray
        get() = solve()?.let { floatArrayOf(it.scaleX.toFloat(), it.scaleY.toFloat(), it.scaleZ.toFloat()) }
            ?: floatArrayOf(1f, 1f, 1f)

    /** Applies the current best-fit correction to a raw reading. Identity until enough
     *  samples exist to fit anything. */
    fun calibrate(mx: Float, my: Float, mz: Float): FloatArray {
        val fit = solve() ?: return floatArrayOf(mx, my, mz)
        return floatArrayOf(
            ((mx - fit.centerX) * fit.scaleX).toFloat(),
            ((my - fit.centerY) * fit.scaleY).toFloat(),
            ((mz - fit.centerZ) * fit.scaleZ).toFloat(),
        )
    }

    /** The current best-fit center, or (while there isn't one yet) the buffered samples' own
     *  mean — shared by [coverageFraction] and [coverageGrid], which both need *some* center
     *  to bin directions around even before a real sphere fit exists. */
    private fun currentCenterOrBufferMean(): Triple<Double, Double, Double>? {
        if (recentFilled == 0) return null
        val fit = solve()
        if (fit != null) return Triple(fit.centerX, fit.centerY, fit.centerZ)
        var sumX = 0.0; var sumY = 0.0; var sumZ = 0.0
        for (i in 0 until recentFilled) { sumX += recentX[i]; sumY += recentY[i]; sumZ += recentZ[i] }
        return Triple(sumX / recentFilled, sumY / recentFilled, sumZ / recentFilled)
    }

    /** Fraction of the 8 octants around the current center that have real coverage
     *  (>= [MIN_SAMPLES_PER_OCTANT] samples) — a figure-8 needs varied orientation, not a
     *  cluster of samples near one bin boundary. This is the pass/fail gate; [coverageGrid]
     *  is a finer breakdown of the same idea, for showing *where* gaps are. */
    val coverageFraction: Float
        get() {
            val (centerX, centerY, centerZ) = currentCenterOrBufferMean() ?: return 0f
            val octantCounts = IntArray(8)
            var sumSqDist = 0.0
            for (i in 0 until recentFilled) {
                val dx = recentX[i] - centerX
                val dy = recentY[i] - centerY
                val dz = recentZ[i] - centerZ
                val octant = (if (dx >= 0) 1 else 0) or (if (dy >= 0) 2 else 0) or (if (dz >= 0) 4 else 0)
                octantCounts[octant]++
                sumSqDist += dx * dx + dy * dy + dz * dz
            }
            // A phone sitting still has readings clustered in a tiny noise cloud around the
            // mean — pure noise flips the *sign* of the deviation unpredictably, so it can
            // spuriously light up all 8 octants without the phone ever having moved. Require
            // real spread, not just directional sign diversity, before counting it.
            val spreadRmsUt = sqrt(sumSqDist / recentFilled)
            if (spreadRmsUt < MIN_COVERAGE_SPREAD_UT) return 0f
            return octantCounts.count { it >= MIN_SAMPLES_PER_OCTANT } / 8f
        }

    /** Finer azimuth x elevation coverage grid, purely so the UI can show the user *which*
     *  directions are still missing instead of one abstract percentage — real calibration
     *  tools (e.g. MotionCal) show a live point cloud for exactly this reason; a single
     *  number gives no sense of where to rotate next. Does not affect [isGoodEnough] — the
     *  8-octant [coverageFraction] is still the actual gate. Flat array, row-major
     *  (elevation bin * [GRID_AZIMUTH_BINS] + azimuth bin), true where that direction has
     *  enough samples. */
    val coverageGrid: BooleanArray
        get() {
            val grid = BooleanArray(GRID_AZIMUTH_BINS * GRID_ELEVATION_BINS)
            val (centerX, centerY, centerZ) = currentCenterOrBufferMean() ?: return grid
            val counts = IntArray(grid.size)
            for (i in 0 until recentFilled) {
                val dx = recentX[i] - centerX
                val dy = recentY[i] - centerY
                val dz = recentZ[i] - centerZ
                val mag = sqrt(dx * dx + dy * dy + dz * dz)
                // A different job than MIN_COVERAGE_SPREAD_UT below: that one gates the
                // *aggregate* buffer spread so a motionless phone can't fake coverage. This is
                // a per-sample "is this direction even meaningful" floor, and needs to be much
                // smaller — MIN_COVERAGE_SPREAD_UT here would drop legitimate early samples
                // whenever the not-yet-converged center sits inside the sample cloud, leaving
                // the grid falsely blank (exactly the "nothing is happening" problem this
                // exists to fix). MIN_SAMPLES_PER_GRID_CELL below does the real noise filtering.
                if (mag < MIN_AXIS_RMS_UT) continue
                val azimuth = (atan2(dy, dx) + 2 * PI) % (2 * PI) // 0..2pi
                val elevation = asin((dz / mag).coerceIn(-1.0, 1.0)) // -pi/2..pi/2
                val azBin = ((azimuth / (2 * PI)) * GRID_AZIMUTH_BINS).toInt().coerceIn(0, GRID_AZIMUTH_BINS - 1)
                val elBin = (((elevation + PI / 2) / PI) * GRID_ELEVATION_BINS).toInt().coerceIn(0, GRID_ELEVATION_BINS - 1)
                counts[elBin * GRID_AZIMUTH_BINS + azBin]++
            }
            for (i in counts.indices) grid[i] = counts[i] >= MIN_SAMPLES_PER_GRID_CELL
            return grid
        }

    /** Coefficient of variation of the out-of-sample residual magnitude — the honest
     *  "is this converged" signal, since in-sample fit error only ever shrinks. NaN until
     *  there's enough post-fit data to say anything. Lower is better. */
    val residualCoefficientOfVariation: Float
        get() {
            if (residualCount < MIN_RESIDUAL_SAMPLES) return Float.NaN
            val mean = residualSum / residualCount
            if (mean <= 0.0) return Float.NaN
            val variance = (residualSumSq / residualCount - mean * mean).coerceAtLeast(0.0)
            return (sqrt(variance) / mean).toFloat()
        }

    /** Estimated local field strength, µT (the fitted sphere's radius) — NaN until fit
     *  exists. Real fields run ~25-65 µT; far outside that means "move away from metal and
     *  redo this", not a usable calibration.
     *
     *  [Fit.avgRmsUt] is the mean per-axis RMS deviation, not the radius directly: for a
     *  point uniformly spread over a sphere of radius r, each axis only carries 1/3 of the
     *  variance (dx²+dy²+dz²=1 always), so per-axis RMS is r/√3 — scale back up by √3. */
    val fieldMagnitudeUt: Float
        get() = solve()?.let { (it.avgRmsUt * sqrt(3.0)).toFloat() } ?: Float.NaN

    val isCoverageGoodEnough: Boolean get() = coverageFraction >= MIN_COVERAGE_FRACTION

    val isConsistencyGoodEnough: Boolean
        get() {
            val cov = residualCoefficientOfVariation
            return !cov.isNaN() && cov <= MAX_RESIDUAL_COV
        }

    /** 0..1 "how close to a good consistency reading" — for progress-bar display, since the
     *  raw coefficient of variation isn't itself bounded to a nice display range. */
    val consistencyProgressFraction: Float
        get() {
            val cov = residualCoefficientOfVariation
            if (cov.isNaN()) return 0f
            return (1f - cov / MAX_RESIDUAL_COV).coerceIn(0f, 1f)
        }

    val isFieldStrengthPlausible: Boolean
        get() {
            val field = fieldMagnitudeUt
            return !field.isNaN() && field in PLAUSIBLE_FIELD_RANGE_UT
        }

    /** True once coverage, consistency, and field strength are all within the thresholds
     *  that make this correction trustworthy to persist and hand to the user. */
    val isGoodEnough: Boolean
        get() = n >= MIN_SAMPLES_FOR_FIT &&
            isCoverageGoodEnough && isConsistencyGoodEnough && isFieldStrengthPlausible

    companion object {
        // At ~100Hz raw sensor rate, 600 was only ~6 seconds of history — too short for a
        // real figure-8, which can easily take 15-30s and doesn't sweep every orientation in
        // any single 6s slice. 3000 (~30s) is still trivial memory (36KB) and lets coverage
        // reflect the whole session instead of whatever the last few seconds happened to be.
        private const val COVERAGE_BUFFER_CAPACITY = 3000
        private const val MIN_SAMPLES_FOR_FIT = 40L
        private const val MIN_SAMPLES_PER_OCTANT = 15L
        private const val MIN_COVERAGE_SPREAD_UT = 3.0
        // Purely for the gap-visualization grid — smaller cells than the 8 octants above, so
        // a lower per-cell sample requirement (finer granularity means each cell naturally
        // gets fewer of the total samples).
        const val GRID_AZIMUTH_BINS = 12
        const val GRID_ELEVATION_BINS = 6
        private const val MIN_SAMPLES_PER_GRID_CELL = 5
        private const val MIN_RESIDUAL_SAMPLES = 60L
        private const val MIN_AXIS_RMS_UT = 0.5
        private const val MIN_COVERAGE_FRACTION = 0.75f
        private const val MAX_RESIDUAL_COV = 0.08f
        private val PLAUSIBLE_FIELD_RANGE_UT = 25f..65f
    }
}
