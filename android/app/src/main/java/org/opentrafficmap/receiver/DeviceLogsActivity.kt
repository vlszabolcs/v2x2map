package org.opentrafficmap.receiver

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import org.opentrafficmap.receiver.databinding.ActivityDeviceLogsBinding
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Logs recorded on the receiver's SD card: list, download (resumable, CRC
 * checked), replay on the map, export as PCAP, delete on the receiver.
 * Works over whichever link is up; USB is ~4× faster than BLE.
 */
class DeviceLogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceLogsBinding
    private val main = Handler(Looper.getMainLooper())

    data class Row(
        val id: Long,
        var deviceSize: Long = -1,     // -1 = not on the receiver (anymore)
        var current: Boolean = false,
        var localSize: Long = 0,
        var progress: Int = -1,        // 0..100 while downloading
    )

    private val rows = mutableListOf<Row>()
    private val adapter = LogAdapter()
    private var node = "receiver"
    private var download: Download? = null
    private val queue = ArrayDeque<Long>()

    // ── lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> { refresh(); true }
                R.id.action_download_all -> { downloadAll(); true }
                R.id.action_forget -> { confirmForget(); true }
                else -> false
            }
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        DeviceLink.addJsonListener(onJson)
        DeviceLink.addChunkListener(onChunk)
        loadLocal()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        DeviceLink.removeJsonListener(onJson)
        DeviceLink.removeChunkListener(onChunk)
        download?.cancel()
    }

    // ── device communication ──────────────────────────────────────────────

    private fun refresh() {
        if (!DeviceLink.connected) {
            binding.status.text = getString(R.string.logs_not_connected)
            return
        }
        binding.status.text = getString(R.string.logs_loading)
        DeviceLink.send("STATUS")
        DeviceLink.send("LOG_LIST")
    }

    private val onJson: (JSONObject) -> Unit = { o ->
        when (o.optString("t")) {
            "status" -> {
                node = o.optString("node", node).ifEmpty { node }
                loadLocal()
            }
            "loglist" -> applyList(o)
            "logrotate" -> DeviceLink.send("LOG_LIST")
            "logdel" -> DeviceLink.send("LOG_LIST")
            "err" -> download?.onError(o.optString("msg"))
        }
    }

    private val onChunk: (FrameReader.LogChunk) -> Unit = { c -> download?.onChunk(c) }

    private fun applyList(o: JSONObject) {
        if (!o.optBoolean("sd", false)) {
            binding.status.text = getString(R.string.logs_no_sd)
        }
        val files = o.optJSONArray("files")
        val seen = HashSet<Long>()
        for (i in 0 until (files?.length() ?: 0)) {
            val f = files!!.getJSONObject(i)
            val id = f.getLong("id")
            seen += id
            val r = rows.firstOrNull { it.id == id } ?: Row(id).also { rows += it }
            r.deviceSize = f.getLong("size")
            r.current = f.optBoolean("cur", false)
        }
        rows.filter { it.id !in seen }.forEach { it.deviceSize = -1 }
        rows.sortByDescending { it.id }
        if (o.optBoolean("sd", false)) {
            binding.status.text = getString(R.string.logs_summary,
                seen.size, o.optLong("free_mb"), if (DeviceLink.viaUsb) "USB" else "BLE")
        }
        adapter.notifyDataSetChanged()
        // Continue a queued "download all"
        if (download == null) queue.removeFirstOrNull()?.let { startDownload(it) }
    }

    // ── local files ───────────────────────────────────────────────────────

    private fun dir(): File = File(getExternalFilesDir("logs") ?: filesDir, node).apply { mkdirs() }
    private fun localFile(id: Long) = File(dir(), "%08d.its".format(Locale.ROOT, id))

    private fun loadLocal() {
        dir().listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".its").toLongOrNull() ?: return@forEach
            val r = rows.firstOrNull { it.id == id } ?: Row(id).also { rows += it }
            r.localSize = f.length()
        }
        rows.forEach { it.localSize = localFile(it.id).takeIf { f -> f.exists() }?.length() ?: 0 }
        rows.sortByDescending { it.id }
        adapter.notifyDataSetChanged()
    }

    // ── actions ───────────────────────────────────────────────────────────

    private fun onRowClick(r: Row) {
        val complete = r.localSize > 0 && (r.deviceSize < 0 || r.localSize >= r.deviceSize)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (r.deviceSize >= 0 && !complete)
            actions += getString(R.string.logs_action_download) to { startDownload(r.id) }
        if (r.localSize > 0) {
            actions += getString(R.string.logs_action_replay) to { replay(r) }
            actions += getString(R.string.logs_action_pcap) to { exportPcap(r) }
            actions += getString(R.string.logs_action_share) to { share(localFile(r.id), "application/octet-stream") }
        }
        if (r.deviceSize >= 0)
            actions += getString(R.string.logs_action_delete_device) to { DeviceLink.send("LOG_DEL:${r.id}"); Unit }
        if (r.localSize > 0)
            actions += getString(R.string.logs_action_delete_local) to { localFile(r.id).delete(); loadLocal() }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logs_file_title, r.id))
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadAll() {
        queue.clear()
        rows.filter { it.deviceSize > 0 && it.localSize < it.deviceSize }.forEach { queue += it.id }
        if (download == null) queue.removeFirstOrNull()?.let { startDownload(it) }
    }

    private fun startDownload(id: Long) {
        val r = rows.firstOrNull { it.id == id } ?: return
        if (download != null) { toast(getString(R.string.logs_busy)); return }
        if (r.current) {
            // Close the file being written so it has a final size
            DeviceLink.send("LOG_ROTATE")
            queue.addFirst(id)
            return
        }
        download = Download(r).also { it.start() }
    }

    private fun replay(r: Row) {
        val f = localFile(r.id)
        LogReplay.request(f)
        toast(getString(R.string.logs_replay_started))
        finish()
    }

    private fun exportPcap(r: Row) {
        val src = localFile(r.id)
        val dst = File(dir(), "%08d.pcap".format(Locale.ROOT, r.id))
        Thread {
            val n = try { LogTools.toPcap(src, dst) } catch (e: Exception) { -1 }
            main.post {
                if (n < 0) toast(getString(R.string.logs_pcap_failed))
                else { toast(getString(R.string.logs_pcap_done, n)); share(dst, "application/vnd.tcpdump.pcap") }
            }
        }.start()
    }

    private fun share(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, f.name))
    }

    private fun confirmForget() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_forget_title)
            .setMessage(R.string.logs_forget_body)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DeviceLink.send("BLE_FORGET")
                Prefs.resetClientId(this)
                toast(getString(R.string.logs_forget_done))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ── resumable, CRC-checked download ───────────────────────────────────

    private inner class Download(val row: Row) {
        private val file = localFile(row.id)
        private var offset = if (file.exists()) file.length() else 0L
        private var retries = 0
        private var cancelled = false
        private val timeout = Runnable { retry("timeout") }

        fun start() { request() }

        fun cancel() { cancelled = true; main.removeCallbacks(timeout) }

        private fun request() {
            if (cancelled) return
            if (row.deviceSize in 0..offset) { finish(true); return }
            if (!DeviceLink.send("LOG_GET:${row.id}:$offset:${DeviceLink.chunkSize}")) {
                finish(false); return
            }
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, if (DeviceLink.viaUsb) 3000 else 8000)
        }

        fun onChunk(c: FrameReader.LogChunk) {
            if (cancelled || c.fileId != row.id || c.offset != offset) return
            main.removeCallbacks(timeout)
            if (!c.crcOk) { retry("crc"); return }
            if (c.data.isEmpty()) { finish(true); return }
            RandomAccessFile(file, "rw").use { raf -> raf.seek(offset); raf.write(c.data) }
            offset += c.data.size
            retries = 0
            row.localSize = offset
            row.progress = if (row.deviceSize > 0) (offset * 100 / row.deviceSize).toInt() else 0
            adapter.notifyItemChanged(rows.indexOf(row))
            request()
        }

        fun onError(msg: String) {
            main.removeCallbacks(timeout)
            retry(msg)
        }

        private fun retry(why: String) {
            if (cancelled) return
            if (++retries > 5) { finish(false); return }
            android.util.Log.w("DeviceLogs", "chunk retry ($why) at $offset")
            request()
        }

        private fun finish(ok: Boolean) {
            main.removeCallbacks(timeout)
            row.progress = -1
            row.localSize = if (file.exists()) file.length() else 0
            adapter.notifyItemChanged(rows.indexOf(row))
            download = null
            if (!ok) toast(getString(R.string.logs_download_failed, row.id))
            else if (queue.isEmpty()) toast(getString(R.string.logs_download_done, row.id))
            queue.removeFirstOrNull()?.let { startDownload(it) }
        }
    }

    // ── list ──────────────────────────────────────────────────────────────

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.logTitle)
            val sub: TextView = v.findViewById(R.id.logSub)
            val state: TextView = v.findViewById(R.id.logState)
            val bar: LinearProgressIndicator = v.findViewById(R.id.logProgress)
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device_log, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            h.title.text = getString(R.string.logs_file_title, r.id)
            val size = if (r.deviceSize >= 0) r.deviceSize else r.localSize
            h.sub.text = buildString {
                append(human(size))
                if (r.current) append(" · ").append(getString(R.string.logs_recording))
                if (r.deviceSize < 0) append(" · ").append(getString(R.string.logs_only_local))
            }
            h.state.text = when {
                r.progress >= 0 -> "${r.progress}%"
                r.localSize > 0 && (r.deviceSize < 0 || r.localSize >= r.deviceSize) -> "✓"
                r.localSize > 0 -> getString(R.string.logs_partial)
                else -> ""
            }
            h.bar.visibility = if (r.progress >= 0) View.VISIBLE else View.GONE
            if (r.progress >= 0) h.bar.progress = r.progress
            h.itemView.setOnClickListener { onRowClick(r) }
        }
    }

    private fun human(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", b / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
    }
}

/** Hand-off from the logs screen to the map (MainActivity picks it up). */
object LogReplay {
    @Volatile var pending: File? = null
    @Volatile var listener: ((File) -> Unit)? = null

    fun request(f: File) {
        val l = listener
        if (l != null) l(f) else pending = f
    }
}
