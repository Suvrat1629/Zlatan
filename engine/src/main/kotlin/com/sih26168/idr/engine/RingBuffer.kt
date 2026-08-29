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
