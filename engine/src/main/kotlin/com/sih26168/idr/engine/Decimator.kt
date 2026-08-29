package com.sih26168.idr.engine

import com.sih26168.idr.core.types.ImuSampleRecord

/**
 * Native-rate IMU -> the model's fixed-rate grid, by per-bin box averaging.
 *
 * Kotlin twin of the model team's `sih-26168-model/decimate.py` (parity gate **G2a**).
 * Each output row is the mean of the native samples whose timestamp falls in that
 * `1 / modelRateHz`-wide bin, centred on the grid point. An empty bin falls back to the
 * first sample at or after the centre (clamped), exactly as `decimate.py` does.
 *
 * The box average is a simple **causal** anti-alias matched to the output rate: no filter
 * state, trivial to reproduce, and it matches training — IO-VNBD's smartphone data is
 * natively 10 Hz, so the model never saw a low-pass-then-resample pipeline. Picking the
 * nearest sample instead would alias vibration energy into the band the speed model uses.
 */
class Decimator(
    private val modelRateHz: Double,
    private val windowSamples: Int,
) {
    private val periodNs: Long = (1_000_000_000.0 / modelRateHz).toLong()
    private val halfNs: Long = periodNs / 2

    /**
     * The last [windowSamples] rows at [modelRateHz], the final bin centred on [tEndNanos].
     * Returns null until the ring buffer covers the whole window.
     */
    fun decimate(nativeSamples: List<ImuSampleRecord>, tEndNanos: Long): Array<FloatArray>? {
        if (nativeSamples.size < 2) return null
        val firstCentre = tEndNanos - (windowSamples - 1) * periodNs
        if (nativeSamples.first().tNanos > firstCentre - halfNs) return null
        if (nativeSamples.last().tNanos < tEndNanos - halfNs) return null
        val centres = LongArray(windowSamples) { k -> tEndNanos - (windowSamples - 1 - k) * periodNs }
        return boxAverage(nativeSamples, centres)
    }

    /**
     * Faithful port of `decimate.py`: a uniform grid from the first sample's timestamp to
     * the last, `floor((t1 - t0) / period)` points, centred bins. Used by the G2a parity
     * test to check this class against the Python reference on the same input.
     */
    fun decimateFullGrid(nativeSamples: List<ImuSampleRecord>): Array<FloatArray> {
        require(nativeSamples.size >= 2) { "need >= 2 samples" }
        val t0 = nativeSamples.first().tNanos
        val t1 = nativeSamples.last().tNanos
        val n = ((t1 - t0) / periodNs).toInt().coerceAtLeast(1)   // == len(np.arange(t0, t1, period))
        val centres = LongArray(n) { m -> t0 + m * periodNs }
        return boxAverage(nativeSamples, centres)
    }

    private fun boxAverage(samples: List<ImuSampleRecord>, centres: LongArray): Array<FloatArray> {
        val ts = LongArray(samples.size) { samples[it].tNanos }
        val ch = Array(samples.size) { samples[it].toRawChannels() }
        val width = ch[0].size
        return Array(centres.size) { m ->
            val c = centres[m]
            val lo = lowerBound(ts, c - halfNs)   // first idx with t >= c - half  (np.searchsorted, side='left')
            val hi = lowerBound(ts, c + halfNs)   // first idx with t >= c + half
            if (hi <= lo) {
                // empty bin -> decimate.py: min(searchsorted(t, centre), len - 1)
                ch[minOf(lowerBound(ts, c), samples.size - 1)].copyOf()
            } else {
                val acc = DoubleArray(width)
                for (i in lo until hi) {
                    val row = ch[i]
                    for (d in 0 until width) acc[d] += row[d]
                }
                val cnt = (hi - lo).toDouble()
                FloatArray(width) { d -> (acc[d] / cnt).toFloat() }
            }
        }
    }

    private fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
