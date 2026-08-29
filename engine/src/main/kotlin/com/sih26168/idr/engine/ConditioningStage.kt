package com.sih26168.idr.engine

import com.sih26168.idr.core.types.ImuSampleRecord

class ConditioningStage(

    private val clippingThresholdMps2: Float = 19.0f,

    private val gapWarnThresholdMs: Long = 200,
) {
    data class Result(
        val samples: List<ImuSampleRecord>,
        val warnings: List<String>,
    )

    fun process(rawInOrder: List<ImuSampleRecord>): Result {
        if (rawInOrder.isEmpty()) return Result(emptyList(), emptyList())

        val warnings = mutableListOf<String>()

        val sorted = rawInOrder.sortedBy { it.tNanos }
        val deduped = ArrayList<ImuSampleRecord>(sorted.size)
        var lastT = Long.MIN_VALUE
        for (s in sorted) {
            if (s.tNanos == lastT) continue
            deduped.add(s)
            lastT = s.tNanos
        }
        if (deduped.size != sorted.size) {
            warnings += "dropped ${sorted.size - deduped.size} duplicate-timestamp sample(s)"
        }

        for (i in 1 until deduped.size) {
            val gapMs = (deduped[i].tNanos - deduped[i - 1].tNanos) / 1_000_000
            if (gapMs > gapWarnThresholdMs) {
                warnings += "gap of ${gapMs}ms between samples at index ${i - 1} and $i"
            }
        }

        for ((i, s) in deduped.withIndex()) {
            if (isClipped(s)) warnings += "possible accelerometer clipping at index $i (t=${s.tNanos})"
        }

        return Result(deduped, warnings)
    }

    private fun isClipped(s: ImuSampleRecord): Boolean {
        val mag = kotlin.math.sqrt((s.ax * s.ax + s.ay * s.ay + s.az * s.az).toDouble())
        return mag >= clippingThresholdMps2
    }
}
