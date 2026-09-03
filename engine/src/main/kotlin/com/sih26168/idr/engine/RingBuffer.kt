package com.sih26168.idr.engine

import com.sih26168.idr.core.types.ImuSampleRecord

class RingBuffer(private val capacity: Int) {
    init {
        require(capacity > 1) { "ring buffer capacity must be > 1, got $capacity" }
    }

    private val buffer = arrayOfNulls<ImuSampleRecord>(capacity)
    private var writeIndex = 0
    private var size = 0
    private var droppedBeforeRead = 0L
    private val lock = Any()

    fun push(sample: ImuSampleRecord) {
        synchronized(lock) {
            if (size == capacity) droppedBeforeRead++
            buffer[writeIndex] = sample
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++
        }
    }

    /** Timestamp of the newest sample, or null when empty. */
    fun newestTNanos(): Long? = synchronized(lock) {
        if (size == 0) null else buffer[(writeIndex - 1 + capacity) % capacity]!!.tNanos
    }

    /**
     * The tail of the buffer back to [minTNanos], newest-last.
     *
     * The engine only ever needs the span the decimator will bin — about 5 s — but the buffer holds
     * 8 s, and at 214 Hz that is roughly 1,700 samples the old full [snapshot] copied, sorted and
     * scanned ten times a second for nothing. Field telemetry measured the engine tick at 20.9 ms
     * median and 101.9 ms maximum against a 100 ms period, with only 2.6 ms of it inference; this
     * is where the rest went.
     *
     * [outOfOrderTolerance] keeps the walk-backwards safe against non-monotonic vendor timestamps:
     * rather than stopping at the first sample older than the cutoff, it keeps going for a few more
     * before giving up, so a straggler that arrived late is not silently dropped. Conditioning
     * sorts afterwards anyway, so including a little extra is free and excluding too much is not.
     */
    fun snapshotSince(minTNanos: Long, outOfOrderTolerance: Int = 16): List<ImuSampleRecord> =
        synchronized(lock) {
            if (size == 0) return@synchronized emptyList()
            val start = if (size < capacity) 0 else writeIndex
            val out = ArrayList<ImuSampleRecord>(minOf(size, 1024))
            var strikes = 0
            for (i in size - 1 downTo 0) {
                val sample = buffer[(start + i) % capacity]!!
                if (sample.tNanos < minTNanos) {
                    if (++strikes > outOfOrderTolerance) break
                    continue
                }
                strikes = 0
                out.add(sample)
            }
            out.reverse()
            out
        }

    fun snapshot(): List<ImuSampleRecord> = synchronized(lock) {
        val out = ArrayList<ImuSampleRecord>(size)
        val start = if (size < capacity) 0 else writeIndex
        for (i in 0 until size) {
            out.add(buffer[(start + i) % capacity]!!)
        }
        out
    }

    fun droppedBeforeRead(): Long = synchronized(lock) { droppedBeforeRead }

    fun isEmpty(): Boolean = synchronized(lock) { size == 0 }
}
