package com.sih26168.idr.engine.testutil

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Minimal reader for the model team's `testset.npz` — enough to run the parity gates on
 * the JVM without a numpy dependency.
 *
 * `.npz` is a ZIP of `.npy` entries. We only need C-order little-endian `<f4` (float32)
 * arrays, which is all `testset.npz` contains apart from a `<U..` string scalar we skip.
 */
class NpyArray(val shape: IntArray, val data: FloatArray) {
    val rows: Int get() = shape[0]

    /** A `[shape[1]][shape[2]]` slice for window index [i] (arrays here are `[K, T, C]`). */
    fun window(i: Int): Array<FloatArray> {
        require(shape.size == 3) { "window() needs a 3-D array, got ${shape.toList()}" }
        val t = shape[1]
        val c = shape[2]
        val base = i * t * c
        return Array(t) { r -> FloatArray(c) { col -> data[base + r * c + col] } }
    }

    /** Scalar per window for `[K, 1]` arrays. */
    fun scalar(i: Int): Float {
        require(shape.size == 2 && shape[1] == 1) { "scalar() needs a [K, 1] array, got ${shape.toList()}" }
        return data[i]
    }
}

object Npz {
    fun load(stream: InputStream): Map<String, NpyArray> {
        val out = LinkedHashMap<String, NpyArray>()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removeSuffix(".npy")
                val bytes = zip.readBytes()
                parseNpy(bytes)?.let { out[name] = it }
            }
        }
        return out
    }

    fun loadResource(path: String): Map<String, NpyArray> {
        val s = Npz::class.java.classLoader.getResourceAsStream(path)
            ?: error("test resource not found: $path")
        return s.use { load(it) }
    }

    private fun parseNpy(bytes: ByteArray): NpyArray? {
        require(bytes.size > 10 && bytes[0].toInt() == 0x93) { "not an .npy stream" }
        val major = bytes[6].toInt()
        val headerLen: Int
        val headerStart: Int
        if (major == 1) {
            headerLen = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            headerStart = 10
        } else {
            headerLen = (bytes[8].toInt() and 0xFF) or
                ((bytes[9].toInt() and 0xFF) shl 8) or
                ((bytes[10].toInt() and 0xFF) shl 16) or
                ((bytes[11].toInt() and 0xFF) shl 24)
            headerStart = 12
        }
        val header = String(bytes, headerStart, headerLen, Charsets.US_ASCII)
        if (!header.contains("'<f4'")) return null   // skip the meta string scalar etc.
        require(header.contains("'fortran_order': False")) { "only C-order arrays supported" }

        val shape = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)!!.groupValues[1]
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }
            .toIntArray()

        val dataStart = headerStart + headerLen
        val count = if (shape.isEmpty()) 1 else shape.fold(1) { a, b -> a * b }
        val buf = ByteBuffer.wrap(bytes, dataStart, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        val data = FloatArray(count) { buf.float }
        return NpyArray(shape, data)
    }

    private fun ZipInputStream.readBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (true) {
            val n = read(tmp)
            if (n < 0) break
            out.write(tmp, 0, n)
        }
        return out.toByteArray()
    }
}
