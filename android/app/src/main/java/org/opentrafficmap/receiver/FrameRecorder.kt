package org.opentrafficmap.receiver

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes received frames as a standard PCAP file (libpcap format, link type 127 =
 * radiotap + 802.11, so the RSSI is kept). Files can be opened directly in Wireshark.
 *
 * Output path: Android/data/org.opentrafficmap.receiver/files/  (no extra permission)
 */
class FrameRecorder(private val context: Context) {

    private val tag = "FrameRecorder"
    private var stream: BufferedOutputStream? = null
    var file: File? = null
        private set
    var frameCount: Int = 0
        private set

    val isRecording: Boolean get() = stream != null

    fun start(): File? {
        if (stream != null) return file
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "itsg5-$ts.pcap")
        return try {
            val out = BufferedOutputStream(FileOutputStream(f))
            writePcapGlobalHeader(out)
            out.flush()
            stream = out
            file = f
            frameCount = 0
            Log.i(tag, "recording to ${f.absolutePath}")
            f
        } catch (e: Exception) {
            Log.w(tag, "open failed", e)
            null
        }
    }

    fun stop(): File? {
        val s = stream ?: return null
        return try {
            s.flush(); s.close()
            stream = null
            Log.i(tag, "stopped after $frameCount frames → ${file?.absolutePath}")
            file
        } catch (e: Exception) {
            Log.w(tag, "close failed", e); null
        }
    }

    @Synchronized
    fun append(frame: Frame) {
        val s = stream ?: return
        try {
            writePcapPacket(s, frame)
            frameCount++
        } catch (e: Exception) {
            Log.w(tag, "write failed", e)
            stop()
        }
    }

    // ── PCAP format ────────────────────────────────────────────────────────

    private fun writePcapGlobalHeader(out: BufferedOutputStream) = LogTools.Pcap.writeGlobalHeader(out)

    private fun writePcapPacket(out: BufferedOutputStream, frame: Frame) =
        LogTools.Pcap.writePacket(out, frame.sec * 1_000_000L + frame.usec, frame.payload, frame.rssi)
}
