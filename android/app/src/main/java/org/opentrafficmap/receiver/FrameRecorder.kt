package org.opentrafficmap.receiver

import android.content.Context
import android.location.Location
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the live frames on the phone in the receiver's own log format (the
 * record stream of [FrameReader]: a "log" header, ITSR + ITS5 per frame and
 * "time" / "pos" JSON records). So a phone recording carries the route too,
 * replays on the map exactly like a log downloaded from the receiver, and
 * exports to PCAP / GPX / KML / CSV with the same tools.
 *
 * Output: Android/data/org.opentrafficmap.receiver/files/logs/_phone/
 */
class FrameRecorder(private val context: Context) {

    private val tag = "FrameRecorder"
    private var stream: BufferedOutputStream? = null
    var file: File? = null
        private set
    var frameCount: Int = 0
        private set
    var startedMs: Long = 0L
        private set
    /** Started by the auto-record setting (stopped again when the receiver goes away). */
    var autoStarted: Boolean = false
    private var timeSynced = false
    private var lastPosMs = 0L
    private var unflushed = 0

    val isRecording: Boolean get() = stream != null

    @Synchronized
    fun start(auto: Boolean = false): File? {
        if (stream != null) return file
        val dir = Recordings.phoneDir(context)
        val now = System.currentTimeMillis()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val f = File(dir, "phone-$ts.its")
        return try {
            val out = BufferedOutputStream(FileOutputStream(f))
            writeJson(out, JSONObject()
                .put("t", "log").put("src", "phone").put("start_ms", now)
                .put("model", android.os.Build.MODEL))
            out.flush()
            stream = out
            file = f
            frameCount = 0
            startedMs = now
            autoStarted = auto
            timeSynced = false
            lastPosMs = 0L
            Log.i(tag, "recording to ${f.absolutePath}")
            f
        } catch (e: Exception) {
            Log.w(tag, "open failed", e)
            null
        }
    }

    @Synchronized
    fun stop(): File? {
        val s = stream ?: return null
        return try {
            s.flush(); s.close()
            stream = null
            autoStarted = false
            Log.i(tag, "stopped after $frameCount frames → ${file?.absolutePath}")
            file
        } catch (e: Exception) {
            stream = null
            Log.w(tag, "close failed", e); null
        }
    }

    @Synchronized
    fun append(frame: Frame) {
        val s = stream ?: return
        try {
            // Receiver clock not synced yet: one time record maps its uptime to Unix time
            if (!timeSynced && frame.sec < 1_000_000_000L) {
                writeJson(s, JSONObject().put("t", "time")
                    .put("unix_ms", System.currentTimeMillis())
                    .put("up_us", frame.sec * 1_000_000L + frame.usec))
                timeSynced = true
            }
            frame.rssi?.let { rssi ->
                val r = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
                r.put(MAGIC_R); r.put(rssi.toByte()); r.putShort((frame.usec and 0xFFFF).toShort())
                s.write(r.array())
            }
            val h = ByteBuffer.allocate(FrameReader.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
            h.put(MAGIC_5); h.putInt(frame.sec.toInt()); h.putInt(frame.usec.toInt())
            h.putShort(frame.payload.size.toShort())
            s.write(h.array())
            s.write(frame.payload)
            frameCount++
            if (++unflushed >= 50) { s.flush(); unflushed = 0 }
        } catch (e: Exception) {
            Log.w(tag, "write failed", e)
            stop()
        }
    }

    /** The receiver's position (phone GPS or the receiver's GNSS), at most once a second. */
    @Synchronized
    fun appendPosition(lat: Double, lon: Double, src: String, unixMs: Long = System.currentTimeMillis(),
                       alt: Double? = null, speedMps: Float? = null, bearing: Float? = null, acc: Float? = null) {
        val s = stream ?: return
        if (unixMs - lastPosMs < 1000L) return
        lastPosMs = unixMs
        try {
            val o = JSONObject().put("t", "pos").put("src", src).put("unix_ms", unixMs)
                .put("lat", lat).put("lon", lon)
            alt?.let { o.put("alt", it) }
            speedMps?.let { o.put("spd", it.toDouble()) }
            bearing?.let { o.put("hdg", it.toDouble()) }
            acc?.let { o.put("acc", it.toDouble()) }
            writeJson(s, o)
            s.flush(); unflushed = 0
        } catch (e: Exception) {
            Log.w(tag, "write failed", e)
            stop()
        }
    }

    fun appendLocation(loc: Location) = appendPosition(
        loc.latitude, loc.longitude, "phone", System.currentTimeMillis(),
        if (loc.hasAltitude()) loc.altitude else null,
        if (loc.hasSpeed()) loc.speed else null,
        if (loc.hasBearing()) loc.bearing else null,
        if (loc.hasAccuracy()) loc.accuracy else null,
    )

    private fun writeJson(out: BufferedOutputStream, o: JSONObject) {
        val js = o.toString().toByteArray(Charsets.UTF_8)
        val h = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        h.put(MAGIC_J); h.putShort(js.size.toShort())
        out.write(h.array())
        out.write(js)
    }

    companion object {
        private val MAGIC_5 = "ITS5".toByteArray(Charsets.US_ASCII)
        private val MAGIC_J = "ITSJ".toByteArray(Charsets.US_ASCII)
        private val MAGIC_R = "ITSR".toByteArray(Charsets.US_ASCII)
    }
}
