package org.opentrafficmap.receiver

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helpers for log files downloaded from the receiver's SD card. A log file is
 * the same record stream as the live link (see [FrameReader]): a "log" header
 * record, ITS5 frames, and "time" / "pos" JSON records.
 */
object LogTools {

    class Parsed(
        val frames: List<Frame>,
        /** Receiver positions (GNSS or the phone that was connected), in order. */
        val track: List<Pair<Double, Double>>,
        /** unix_us − uptime_us from the first time-sync record, or null. */
        val clockOffsetUs: Long?,
        val header: JSONObject?,
    )

    fun parse(file: File): Parsed {
        val frames = mutableListOf<Frame>()
        val track = mutableListOf<Pair<Double, Double>>()
        var offset: Long? = null
        var header: JSONObject? = null
        val reader = FrameReader()
        reader.onJson = { raw ->
            try {
                val o = JSONObject(raw)
                val t = o.optString("t")
                if (t == "log" && header == null) {
                    header = o
                } else if (t == "time" && offset == null) {
                    offset = o.getLong("unix_ms") * 1000L - o.getLong("up_us")
                } else if (t == "pos") {
                    track.add(o.getDouble("lat") to o.getDouble("lon"))
                }
            } catch (_: Exception) {
            }
            Unit
        }
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                frames += reader.feed(buf, n)
            }
        }
        return Parsed(frames, track, offset, header)
    }

    /** Frame time in µs since the epoch; relative stamps are fixed up with
     *  the log's time-sync record when there is one. */
    fun unixMicros(f: Frame, clockOffsetUs: Long?): Long {
        val t = f.sec * 1_000_000L + f.usec
        return if (f.sec >= 1_000_000_000L || clockOffsetUs == null) t else t + clockOffsetUs
    }

    /** Write a libpcap file (link type 105 = IEEE 802.11) for Wireshark. */
    fun toPcap(src: File, dst: File): Int {
        val p = parse(src)
        BufferedOutputStream(FileOutputStream(dst)).use { out ->
            val gh = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            gh.putInt(0xa1b2c3d4.toInt()); gh.putShort(2); gh.putShort(4)
            gh.putInt(0); gh.putInt(0); gh.putInt(65535); gh.putInt(105)
            out.write(gh.array())
            for (f in p.frames) {
                val us = unixMicros(f, p.clockOffsetUs)
                val rh = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                rh.putInt((us / 1_000_000L).toInt()); rh.putInt((us % 1_000_000L).toInt())
                rh.putInt(f.payload.size); rh.putInt(f.payload.size)
                out.write(rh.array())
                out.write(f.payload)
            }
        }
        return p.frames.size
    }
}
