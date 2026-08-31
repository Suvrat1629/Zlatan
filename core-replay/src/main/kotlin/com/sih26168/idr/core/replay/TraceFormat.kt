package com.sih26168.idr.core.replay

import com.sih26168.idr.core.types.GnssFixRecord
import com.sih26168.idr.core.types.ImuSampleRecord
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File

sealed class TraceEvent {
    abstract val tNanos: Long

    data class Imu(val record: ImuSampleRecord) : TraceEvent() {
        override val tNanos get() = record.tNanos
    }

    data class Gnss(val record: GnssFixRecord) : TraceEvent() {
        override val tNanos get() = record.tNanos
    }

    data class Lost(override val tNanos: Long) : TraceEvent()
}

class TraceWriter(private val writer: BufferedWriter) : Closeable {
    /** Convenience for tests and replay tooling, which work with real files. */
    constructor(file: File) : this(file.bufferedWriter())

    /** The app writes to the phone's shared Documents/IDR folder, which MediaStore hands out as a
     *  stream rather than a File — so a tester can retrieve the raw trace from the Files app
     *  instead of needing adb and app-private storage. */
    constructor(stream: java.io.OutputStream) : this(stream.bufferedWriter())

    fun write(event: TraceEvent) {
        val line = when (event) {
            is TraceEvent.Imu -> with(event.record) {
                "IMU,$tNanos,$ax,$ay,$az,$grx,$gry,$grz,$gx,$gy,$gz"
            }
            is TraceEvent.Gnss -> with(event.record) {
                "GNSS,$tNanos,$lat,$lon,$speedMps,$bearingDeg,$horizAccM,$satsInFix,$irnssSatsInFix,$bearingValid"
            }
            is TraceEvent.Lost -> "LOST,${event.tNanos}"
        }
        writer.write(line)
        writer.newLine()
    }

    fun flush() = writer.flush()

    override fun close() {
        writer.flush()
        writer.close()
    }
}

object TraceReader {
    fun read(file: File): List<TraceEvent> =
        file.useLines { lines -> lines.filter { it.isNotBlank() }.map(::parseLine).toList() }

    private fun parseLine(line: String): TraceEvent {
        val parts = line.split(",")
        return when (parts[0]) {
            "IMU" -> TraceEvent.Imu(
                ImuSampleRecord(
                    tNanos = parts[1].toLong(),
                    ax = parts[2].toFloat(), ay = parts[3].toFloat(), az = parts[4].toFloat(),
                    grx = parts[5].toFloat(), gry = parts[6].toFloat(), grz = parts[7].toFloat(),
                    gx = parts[8].toFloat(), gy = parts[9].toFloat(), gz = parts[10].toFloat(),
                )
            )
            "GNSS" -> TraceEvent.Gnss(
                GnssFixRecord(
                    tNanos = parts[1].toLong(),
                    lat = parts[2].toDouble(), lon = parts[3].toDouble(),
                    speedMps = parts[4].toFloat(), bearingDeg = parts[5].toFloat(),
                    horizAccM = parts[6].toFloat(),
                    satsInFix = parts[7].toInt(), irnssSatsInFix = parts[8].toInt(),
                    // Older trace files (9 fields) predate bearing validity tracking --
                    // default false (untrusted), consistent with GnssFixRecord's own default.
                    bearingValid = if (parts.size > 9) parts[9].toBoolean() else false,
                )
            )
            "LOST" -> TraceEvent.Lost(parts[1].toLong())
            else -> error("unrecognized trace line: $line")
        }
    }
}
