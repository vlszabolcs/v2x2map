package org.opentrafficmap.receiver

import android.content.Context
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
import org.opentrafficmap.receiver.databinding.ActivityRecordingsBinding
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every recording stored on the phone: the phone's own recordings and the
 * logs downloaded from receivers. Works without a receiver connected.
 * Replay on the map, export (PCAP / GPX / KML / CSV), share, delete.
 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private val main = Handler(Looper.getMainLooper())
    private var all: List<Recordings.Entry> = emptyList()
    private var shown: List<Recordings.Entry> = emptyList()
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_device_logs -> { startActivity(Intent(this, DeviceLogsActivity::class.java)); true }
                R.id.action_refresh -> { reload(); true }
                else -> false
            }
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.filterGroup.setOnCheckedStateChangeListener { _, _ -> applyFilter() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        all = Recordings.all(this)
        applyFilter()
    }

    private fun applyFilter() {
        shown = when (binding.filterGroup.checkedChipId) {
            R.id.filterPhone -> all.filter { it.source != Recordings.Source.RECEIVER }
            R.id.filterReceiver -> all.filter { it.source == Recordings.Source.RECEIVER }
            else -> all
        }
        binding.status.text = if (shown.isEmpty()) getString(R.string.recs_empty)
            else getString(R.string.recs_summary, shown.size, human(shown.sumOf { it.file.length() }))
        adapter.notifyDataSetChanged()
    }

    private fun isActive(e: Recordings.Entry): Boolean {
        val rec = SettingsBus.recorder() ?: return false
        return rec.isRecording && rec.file?.absolutePath == e.file.absolutePath
    }

    // ── actions ───────────────────────────────────────────────────────────

    private fun onRowClick(e: Recordings.Entry) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.logs_action_replay) to { replay(e) }
        if (e.source == Recordings.Source.LEGACY_PCAP) {
            actions += getString(R.string.recs_action_share_pcap) to { share(this, e.file, "application/vnd.tcpdump.pcap") }
        } else {
            actions += getString(R.string.logs_action_pcap) to { export(e, "pcap", "application/vnd.tcpdump.pcap") { s, d -> LogTools.toPcap(s, d) } }
            actions += getString(R.string.logs_action_gpx) to { export(e, "gpx", "application/gpx+xml") { s, d -> LogTools.toGpx(s, d) } }
            actions += getString(R.string.logs_action_kml) to { export(e, "kml", "application/vnd.google-earth.kml+xml") { s, d -> LogTools.toKml(s, d) } }
        }
        actions += getString(R.string.logs_action_csv) to { export(e, "csv", "text/csv") { s, d -> LogTools.toCsv(s, d) } }
        if (e.source != Recordings.Source.LEGACY_PCAP)
            actions += getString(R.string.logs_action_share) to { share(this, e.file, "application/octet-stream") }
        if (!isActive(e))
            actions += getString(R.string.logs_action_delete_local) to { confirmDelete(e) }
        MaterialAlertDialogBuilder(this)
            .setTitle(label(e))
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun replay(e: Recordings.Entry) {
        LogReplay.request(e.file)
        toast(getString(R.string.logs_replay_started))
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun confirmDelete(e: Recordings.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recs_delete_title)
            .setMessage(getString(R.string.recs_delete_body, label(e)))
            .setPositiveButton(R.string.recs_delete_confirm) { _, _ ->
                e.file.delete()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun export(e: Recordings.Entry, ext: String, mime: String, write: (File, File) -> Int) {
        val dst = File(Recordings.exportDir(this), exportName(e, ext))
        toast(getString(R.string.recs_exporting))
        Thread {
            val n = try { write(e.file, dst) } catch (_: Exception) { -1 }
            main.post {
                if (n < 0) toast(getString(R.string.logs_export_failed))
                else { toast(getString(R.string.logs_export_done, dst.name)); share(this, dst, mime) }
            }
        }.start()
    }

    private fun exportName(e: Recordings.Entry, ext: String): String =
        if (e.source == Recordings.Source.RECEIVER) "${e.node}-${e.file.nameWithoutExtension}.$ext"
        else "${e.file.nameWithoutExtension}.$ext"

    private fun label(e: Recordings.Entry): String = when (e.source) {
        Recordings.Source.PHONE -> getString(R.string.recs_phone_title, startTime(e))
        Recordings.Source.RECEIVER -> getString(R.string.recs_receiver_title, e.node ?: "?",
            getString(R.string.logs_file_title, e.logId ?: 0L))
        Recordings.Source.LEGACY_PCAP -> getString(R.string.recs_pcap_title, startTime(e))
    }

    /** Start time from the file name (phone-YYYYMMDD-HHMMSS / itsg5-YYYYMMDD-HHMMSS). */
    private fun startTime(e: Recordings.Entry): String {
        val m = Regex("(\\d{8}-\\d{6})").find(e.file.name)
        val d = m?.let { try { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(it.value) } catch (_: Exception) { null } }
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(d ?: Date(e.time))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ── list ──────────────────────────────────────────────────────────────

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.logTitle)
            val sub: TextView = v.findViewById(R.id.logSub)
            val state: TextView = v.findViewById(R.id.logState)
            val bar: LinearProgressIndicator = v.findViewById(R.id.logProgress)
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device_log, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = shown[pos]
            h.title.text = label(e)
            val modified = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.time))
            h.sub.text = buildString {
                append(getString(when (e.source) {
                    Recordings.Source.RECEIVER -> R.string.recs_src_receiver
                    else -> R.string.recs_src_phone
                }))
                append(" · ").append(human(e.file.length()))
                append(" · ").append(modified)
            }
            h.state.text = if (isActive(e)) getString(R.string.recs_active) else ""
            h.bar.visibility = View.GONE
            h.itemView.setOnClickListener { onRowClick(e) }
        }
    }

    companion object {
        fun human(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", b / 1024.0)
            else -> String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
        }

        fun share(ctx: Context, f: File, mime: String) {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, f.name))
        }
    }
}
