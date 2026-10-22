package org.opentrafficmap.receiver

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Resync parser for the ESP-side wire format (same framing on USB, BLE and in
 * the SD-card log files):
 *
 *     "ITS5"  sec u32 | usec u32 | len u16 | payload      captured 802.11 frame
 *     "ITSJ"  len u16 | json                              reply / meta record
 *     "ITSL"  file u32 | offset u32 | len u16 | data | crc32 u32   log chunk
 *
 * Integers little-endian. Everything else (bootloader text, debug lines,
 * CFG_OK replies) is skipped. A frame's sec >= 1e9 means Unix time; smaller
 * values are seconds since the receiver booted (clock not yet synced).
 */
class FrameReader {
    private val buf = ArrayDeque<Byte>()
    private var seq = 0L

    /** JSON meta records (command replies, time/position records in logs). */
    var onJson: ((String) -> Unit)? = null
    /** Log file chunks (replies to LOG_GET). */
    var onLogChunk: ((LogChunk) -> Unit)? = null

    class LogChunk(val fileId: Long, val offset: Long, val data: ByteArray, val crcOk: Boolean)

    /** Feed raw bytes from the serial port. Returns any complete frames found. */
    fun feed(chunk: ByteArray, len: Int = chunk.size): List<Frame> {
        for (i in 0 until len) buf.addLast(chunk[i])
        val out = mutableListOf<Frame>()
        while (true) {
            val (mIdx, kind) = findMagic()
            if (mIdx < 0) {
                // Keep up to 3 trailing bytes (magic could span the next read)
                while (buf.size > 3) buf.removeFirst()
                return out
            }
            repeat(mIdx) { buf.removeFirst() }   // drop pre-magic noise

            when (kind) {
                '5' -> {
                    if (buf.size < HEADER_LEN) return out
                    val hdr = peek(HEADER_LEN)
                    val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    bb.position(4)
                    val sec = bb.int.toLong() and 0xFFFFFFFFL
                    val usec = bb.int.toLong() and 0xFFFFFFFFL
                    val plen = bb.short.toInt() and 0xFFFF
                    if (plen > MAX_PAYLOAD || usec >= 1_000_000L) { repeat(4) { buf.removeFirst() }; continue }
                    if (buf.size < HEADER_LEN + plen) return out
                    repeat(HEADER_LEN) { buf.removeFirst() }
                    val payload = take(plen)
                    out += decode(sec, usec, payload)
                }
                'J' -> {
                    if (buf.size < 6) return out
                    val hdr = peek(6)
                    val jlen = (hdr[4].toInt() and 0xFF) or ((hdr[5].toInt() and 0xFF) shl 8)
                    if (jlen == 0 || jlen > MAX_JSON) { repeat(4) { buf.removeFirst() }; continue }
                    if (buf.size < 6 + jlen) return out
                    repeat(6) { buf.removeFirst() }
                    val js = String(take(jlen), Charsets.UTF_8)
                    if (js.startsWith("{")) onJson?.invoke(js)
                }
                'L' -> {
                    if (buf.size < LOG_HDR) return out
                    val hdr = peek(LOG_HDR)
                    val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                    bb.position(4)
                    val file = bb.int.toLong() and 0xFFFFFFFFL
                    val off = bb.int.toLong() and 0xFFFFFFFFL
                    val dlen = bb.short.toInt() and 0xFFFF
                    if (dlen > MAX_PAYLOAD) { repeat(4) { buf.removeFirst() }; continue }
                    if (buf.size < LOG_HDR + dlen + 4) return out
                    repeat(LOG_HDR) { buf.removeFirst() }
                    val data = take(dlen)
                    val crcB = take(4)
                    val crcRx = ByteBuffer.wrap(crcB).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    val crc = CRC32().apply { update(data) }.value
                    onLogChunk?.invoke(LogChunk(file, off, data, crc == crcRx))
                }
            }
        }
    }

    private fun decode(sec: Long, usec: Long, payload: ByteArray): Frame {
        val d = ItsG5Decoder.decodeFull(payload)
        return Frame(
            seq         = ++seq,
            sec         = sec,
            usec        = usec,
            payload     = payload,
            etherType   = d.etherType,
            msgType     = d.msgType,
            stationId   = d.stationId,
            stationType = d.stationType,
            latLon      = d.latLon,
            headingDeg  = d.headingDeg,
            speedMps    = d.speedMps,
            spatPhase   = d.spatPhase,
            denmCause   = d.denmCause,
            secured     = d.secured,
        )
    }

    private fun peek(n: Int): ByteArray {
        val out = ByteArray(n)
        val it = buf.iterator()
        for (j in 0 until n) out[j] = it.next()
        return out
    }

    private fun take(n: Int): ByteArray {
        val out = ByteArray(n)
        for (j in 0 until n) out[j] = buf.removeFirst()
        return out
    }

    /** Index of the first "ITS5" / "ITSJ" / "ITSL" magic and its kind. */
    private fun findMagic(): Pair<Int, Char> {
        val n = buf.size
        if (n < 4) return -1 to ' '
        val arr = buf.iterator()
        var b0 = 0; var b1 = 0; var b2 = 0
        var i = 0
        while (arr.hasNext()) {
            val b3 = arr.next().toInt() and 0xFF
            if (i >= 3 && b0 == 0x49 && b1 == 0x54 && b2 == 0x53) {
                when (b3) {
                    0x35 -> return (i - 3) to '5'
                    0x4A -> return (i - 3) to 'J'
                    0x4C -> return (i - 3) to 'L'
                }
            }
            b0 = b1; b1 = b2; b2 = b3
            i++
        }
        return -1 to ' '
    }

    companion object {
        const val HEADER_LEN = 14
        const val LOG_HDR = 14
        const val MAX_PAYLOAD = 4096
        const val MAX_JSON = 4096
    }
}
