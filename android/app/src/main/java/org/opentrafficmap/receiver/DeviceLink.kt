package org.opentrafficmap.receiver

import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Command channel to the receiver, over whichever link is up (USB preferred:
 * faster and does not steal radio time from the sniffer). MainActivity
 * registers the senders; any screen can send commands and listen for the
 * replies (ITSJ JSON records and ITSL log chunks), delivered on the main thread.
 */
object DeviceLink {

    @Volatile var usbSender: ((String) -> Boolean)? = null
    @Volatile var bleSender: ((String) -> Boolean)? = null

    private val main = Handler(Looper.getMainLooper())
    private val jsonListeners  = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val chunkListeners = CopyOnWriteArrayList<(FrameReader.LogChunk) -> Unit>()

    val connected: Boolean get() = usbSender != null || bleSender != null
    val viaUsb: Boolean get() = usbSender != null

    /** Largest LOG_GET chunk the current link carries in one reply. */
    val chunkSize: Int get() = if (viaUsb) 4096 else 1024

    fun send(cmd: String): Boolean =
        usbSender?.invoke(cmd) ?: bleSender?.invoke(cmd) ?: false

    /** Send on every connected link (time / position keep both in sync). */
    fun sendAll(cmd: String) {
        usbSender?.invoke(cmd)
        bleSender?.invoke(cmd)
    }

    fun addJsonListener(l: (JSONObject) -> Unit) { jsonListeners += l }
    fun removeJsonListener(l: (JSONObject) -> Unit) { jsonListeners -= l }
    fun addChunkListener(l: (FrameReader.LogChunk) -> Unit) { chunkListeners += l }
    fun removeChunkListener(l: (FrameReader.LogChunk) -> Unit) { chunkListeners -= l }

    /** Called from the reader thread. */
    fun dispatchJson(raw: String) {
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        main.post { jsonListeners.forEach { it(o) } }
    }

    fun dispatchChunk(c: FrameReader.LogChunk) {
        main.post { chunkListeners.forEach { it(c) } }
    }

    /** POS:<unix_ms>,<lat>,<lon>,<alt>,<spd>,<hdg>,<acc> */
    fun positionCommand(loc: Location): String = String.format(
        Locale.ROOT, "POS:%d,%.7f,%.7f,%.1f,%.2f,%.1f,%.1f",
        System.currentTimeMillis(), loc.latitude, loc.longitude,
        if (loc.hasAltitude()) loc.altitude else 0.0,
        if (loc.hasSpeed()) loc.speed else 0f,
        if (loc.hasBearing()) loc.bearing else -1f,
        if (loc.hasAccuracy()) loc.accuracy else 0f,
    )

    fun timeCommand(): String = "TIME:${System.currentTimeMillis()}"
}
