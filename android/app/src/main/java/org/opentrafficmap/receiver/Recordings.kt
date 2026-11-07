package org.opentrafficmap.receiver

import android.content.Context
import java.io.File

/**
 * Where recordings live on the phone, all under the app's external files dir
 * (no storage permission needed, shareable through the FileProvider):
 *
 *     logs/_phone/phone-YYYYMMDD-HHMMSS.its   recorded by the phone
 *     logs/<node>/NNNNNNNN.its                 downloaded from receiver <node>
 *     logs/_export/…                           PCAP / GPX / KML / CSV exports
 *     itsg5-*.pcap (files root)                phone recordings of older app versions
 */
object Recordings {

    enum class Source { PHONE, RECEIVER, LEGACY_PCAP }

    class Entry(
        val file: File,
        val source: Source,
        /** Receiver node id for downloaded logs. */
        val node: String?,
        /** Log number on the receiver for downloaded logs. */
        val logId: Long?,
    ) {
        val time: Long get() = file.lastModified()
    }

    private const val PHONE = "_phone"
    private const val EXPORT = "_export"

    fun logsRoot(ctx: Context): File = (ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")).apply { mkdirs() }
    fun phoneDir(ctx: Context): File = File(logsRoot(ctx), PHONE).apply { mkdirs() }
    fun exportDir(ctx: Context): File = File(logsRoot(ctx), EXPORT).apply { mkdirs() }
    fun nodeDir(ctx: Context, node: String): File = File(logsRoot(ctx), node).apply { mkdirs() }

    /** Every recording on the phone, newest first. */
    fun all(ctx: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        logsRoot(ctx).listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name == EXPORT) return@forEach
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".its") || f.length() == 0L) return@forEach
                out += if (dir.name == PHONE) Entry(f, Source.PHONE, null, null)
                       else Entry(f, Source.RECEIVER, dir.name, f.nameWithoutExtension.toLongOrNull())
            }
        }
        (ctx.getExternalFilesDir(null) ?: ctx.filesDir).listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".pcap") && f.length() > 24)
                out += Entry(f, Source.LEGACY_PCAP, null, null)
        }
        return out.sortedByDescending { it.time }
    }
}
