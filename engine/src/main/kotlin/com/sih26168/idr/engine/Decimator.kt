package com.sih26168.idr.engine

import com.sih26168.idr.core.types.ImuSampleRecord
import kotlin.math.PI
import kotlin.math.exp

class Decimator(
    private val cutoffHz: Double,
    private val modelRateHz: Double,
    private val windowSamples: Int,
) {

    fun decimate(nativeSamples: List<ImuSampleRecord>, tEndNanos: Long): Array<FloatArray>? {
        if (nativeSamples.size < 2) return null
        val filtered = lowPass(nativeSamples)
        val periodNs = (1_000_000_000.0 / modelRateHz).toLong()
        val earliestNeededNs = tEndNanos - (windowSamples - 1) * periodNs
        if (filtered.first().tNanos > earliestNeededNs) return null
        if (filtered.last().tNanos < tEndNanos) return null

        return Array(windowSamples) { k ->
            val t = tEndNanos - (windowSamples - 1 - k) * periodNs
            interpolate(filtered, t)
        }
    }

    private class Filtered(val tNanos: Long, val channels: FloatArray)

    private fun lowPass(samples: List<ImuSampleRecord>): List<Filtered> {
        val out = ArrayList<Filtered>(samples.size)
        var state = samples.first().toRawChannels()
        out.add(Filtered(samples.first().tNanos, state.copyOf()))
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val cur = samples[i]
            val dtSeconds = (cur.tNanos - prev.tNanos) / 1e9

            val alpha = if (dtSeconds <= 0) 1f else (1.0 - exp(-2.0 * PI * cutoffHz * dtSeconds)).toFloat()
            val raw = cur.toRawChannels()
            val next = FloatArray(raw.size) { c -> state[c] + alpha * (raw[c] - state[c]) }
            state = next
            out.add(Filtered(cur.tNanos, next.copyOf()))
        }
        return out
    }

    private fun interpolate(filtered: List<Filtered>, tNanos: Long): FloatArray {
        var lo = 0
        var hi = filtered.size - 1
        if (tNanos <= filtered[lo].tNanos) return filtered[lo].channels.copyOf()
        if (tNanos >= filtered[hi].tNanos) return filtered[hi].channels.copyOf()
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (filtered[mid].tNanos <= tNanos) lo = mid else hi = mid
        }
        val a = filtered[lo]
        val b = filtered[hi]
        val span = (b.tNanos - a.tNanos).toDouble()
        val frac = if (span <= 0) 0.0 else (tNanos - a.tNanos) / span
        return FloatArray(a.channels.size) { c -> (a.channels[c] + frac * (b.channels[c] - a.channels[c])).toFloat() }
    }
}
