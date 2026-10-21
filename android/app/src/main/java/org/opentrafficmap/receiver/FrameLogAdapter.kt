package org.opentrafficmap.receiver

import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grouped log adapter. One row per station (stationId + msgType). Tapping a row
 * expands it to show the last [FRAME_HISTORY] individual frames.
 *
 * Card: type circle | name + "TYPE · id · count" | key value + distance · age | lock
 *
 * At high packet rates this stays readable because 20 active vehicles → 20 rows,
 * not 300 rows/min. Batch updates via [addFrames] use notifyDataSetChanged() for
 * stability; individual insertions are never issued.
 */
class FrameLogAdapter(
    private val context: android.content.Context,
    private val onFrameClick: (Frame) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Updated by MainActivity on every location fix for distance column. */
    @Volatile var userLocation: Location? = null

    // ── internal data model ──────────────────────────────────────────────────

    data class StationEntry(
        val key: String,
        val msgType: ItsG5Decoder.MsgType,
        val stationId: Long?,
        val recentFrames: ArrayDeque<Frame> = ArrayDeque(),
        var frameCount: Int = 0,
        var lastSeen: Long = 0L,
        var expanded: Boolean = false,
        var lastSecured: Boolean? = null,
        var lastLatLon: Pair<Double, Double>? = null,
    )

    /** Flat display list — either StationEntry (header row) or Frame (expanded row). */
    private val stations    = HashMap<String, StationEntry>()
    private val displayList = mutableListOf<Any>()
    private val timeFmt     = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Insert a batch of frames. Groups them by station + message type.
     * Must be called on the main thread.
     */
    fun addFrames(frames: List<Frame>) {
        if (frames.isEmpty()) return
        for (frame in frames) {
            val key   = stationKey(frame)
            val entry = stations.getOrPut(key) { StationEntry(key, frame.msgType, frame.stationId) }
            entry.frameCount++
            entry.lastSeen   = System.currentTimeMillis()
            entry.lastSecured = frame.secured
            entry.lastLatLon  = frame.latLon ?: entry.lastLatLon
            entry.recentFrames.addFirst(frame)
            if (entry.recentFrames.size > FRAME_HISTORY) entry.recentFrames.removeLast()
        }
        // Evict least-recently-seen stations beyond cap
        while (stations.size > MAX_STATIONS) {
            stations.values.minByOrNull { it.lastSeen }?.key?.let { stations.remove(it) }
        }
        rebuildAndNotify()
    }

    fun clear() {
        stations.clear()
        displayList.clear()
        notifyDataSetChanged()
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    private fun stationKey(f: Frame): String {
        val sid = f.stationId?.toString() ?: "anon"
        return "${f.msgType.name}|$sid"
    }

    private fun rebuildAndNotify() {
        displayList.clear()
        stations.values.sortedWith(
            compareByDescending<StationEntry> { it.msgType == ItsG5Decoder.MsgType.DENM }
                .thenByDescending { it.lastSeen }
        ).forEach { entry ->
            displayList.add(entry)
            if (entry.expanded) entry.recentFrames.forEach { displayList.add(it) }
        }
        notifyDataSetChanged()
    }

    // ── RecyclerView plumbing ────────────────────────────────────────────────

    override fun getItemCount() = displayList.size
    override fun getItemViewType(pos: Int) = if (displayList[pos] is StationEntry) TYPE_STATION else TYPE_FRAME

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_STATION)
            StationVH(inf.inflate(R.layout.item_station, parent, false))
        else
            FrameVH(inf.inflate(R.layout.item_frame, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayList[position]) {
            is StationEntry -> bindStation(holder as StationVH, item)
            is Frame        -> bindFrame(holder as FrameVH, item)
        }
    }

    // ── station row ──────────────────────────────────────────────────────────

    private fun bindStation(vh: StationVH, entry: StationEntry) {
        val ctx   = vh.itemView.context
        val color = entry.msgType.color
        val now   = System.currentTimeMillis()

        // Type icon on a tinted circle
        vh.iconBg.backgroundTintList = ColorStateList.valueOf((color and 0x00FFFFFF) or 0x2E000000)
        vh.icon.setImageDrawable(
            ContextCompat.getDrawable(ctx, iconFor(entry.msgType))!!.mutate().also {
                androidx.core.graphics.drawable.DrawableCompat.setTint(it, color)
            }
        )

        val f = entry.recentFrames.firstOrNull()
        vh.title.text   = titleFor(entry.msgType, f)
        val sid = entry.stationId?.let { "#%08x".format(it.toInt()) } ?: "—"
        vh.summary.text = "${entry.msgType.short} · $sid · ${entry.frameCount}×"

        // Right column: the one number that matters for this type, then distance · age
        vh.main.text = mainValueFor(entry.msgType, f)
        vh.main.setTextColor(
            if (entry.msgType == ItsG5Decoder.MsgType.SPATEM) phaseColor(f?.spatPhase)
            else vh.title.currentTextColor
        )
        val dist = formatDistance(entry.lastLatLon, userLocation)
        val age  = formatAge(now - entry.lastSeen)
        vh.meta.text = if (dist == "—") age else "$dist · $age"

        // Lock icon
        val sec = entry.lastSecured
        if (sec == null) {
            vh.lock.visibility = View.GONE
        } else {
            vh.lock.visibility = View.VISIBLE
            vh.lock.setImageResource(if (sec) R.drawable.ic_lock_closed else R.drawable.ic_lock_open)
        }

        // Hazards stand out; stale stations fade
        val isHazard = entry.msgType == ItsG5Decoder.MsgType.DENM
        vh.card.strokeColor = if (isHazard) (color and 0x00FFFFFF) or 0x8C000000.toInt() else Color.TRANSPARENT
        vh.itemView.alpha = if (now - entry.lastSeen > STALE_MS) 0.55f else 1f

        vh.itemView.setOnClickListener {
            entry.expanded = !entry.expanded
            rebuildAndNotify()
        }
    }

    private fun iconFor(t: ItsG5Decoder.MsgType) = when (t) {
        ItsG5Decoder.MsgType.CAM    -> R.drawable.ic_marker_car
        ItsG5Decoder.MsgType.SPATEM -> R.drawable.ic_marker_trafficlight
        ItsG5Decoder.MsgType.DENM   -> R.drawable.ic_marker_hazard
        ItsG5Decoder.MsgType.MAPEM  -> R.drawable.ic_marker_intersection
        else                        -> R.drawable.ic_marker_dot
    }

    /** Human-readable name: vehicle class for CAM, cause for DENM, … */
    fun titleFor(t: ItsG5Decoder.MsgType, f: Frame?): String = when (t) {
        ItsG5Decoder.MsgType.CAM -> {
            val names = context.resources.getStringArray(R.array.station_types)
            f?.stationType?.takeIf { it in names.indices && it != 0 }?.let { names[it] }
                ?: context.getString(R.string.label_vehicle)
        }
        ItsG5Decoder.MsgType.SPATEM -> context.getString(R.string.label_traffic_light)
        ItsG5Decoder.MsgType.MAPEM  -> context.getString(R.string.log_intersection)
        ItsG5Decoder.MsgType.DENM   -> f?.denmCause?.let { c ->
            c.label() + c.sublabel().let { if (it.isNotEmpty()) " – $it" else "" }
        } ?: context.getString(R.string.log_hazard)
        else -> t.short
    }

    private fun mainValueFor(t: ItsG5Decoder.MsgType, f: Frame?): String = when (t) {
        ItsG5Decoder.MsgType.CAM -> f?.speedMps?.let { "%.0f km/h".format(it * 3.6) } ?: "—"
        ItsG5Decoder.MsgType.SPATEM -> phaseLabel(f?.spatPhase)
        ItsG5Decoder.MsgType.DENM -> formatDistance(f?.latLon, userLocation)
        else -> ""
    }

    fun phaseLabel(p: SpatTemParser.Phase?): String = when (p) {
        SpatTemParser.Phase.RED    -> context.getString(R.string.log_phase_red)
        SpatTemParser.Phase.YELLOW -> context.getString(R.string.log_phase_yellow)
        SpatTemParser.Phase.GREEN  -> context.getString(R.string.log_phase_green)
        else                       -> context.getString(R.string.log_phase_unknown)
    }

    fun phaseColor(p: SpatTemParser.Phase?): Int = when (p) {
        SpatTemParser.Phase.RED    -> 0xFFFF5C5C.toInt()
        SpatTemParser.Phase.YELLOW -> 0xFFFFC93D.toInt()
        SpatTemParser.Phase.GREEN  -> 0xFF3DDC97.toInt()
        else                       -> 0xFF8A97A5.toInt()
    }

    private fun formatAge(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 120) context.getString(R.string.age_s, s.toInt())
               else context.getString(R.string.age_m, (s / 60).toInt())
    }

    fun formatDistance(latLon: Pair<Double, Double>?, userLoc: Location?): String {
        if (latLon == null || userLoc == null) return "—"
        val result = FloatArray(1)
        Location.distanceBetween(userLoc.latitude, userLoc.longitude,
                                 latLon.first, latLon.second, result)
        return when {
            result[0] < 1000  -> "%.0f m".format(result[0])
            result[0] < 10000 -> "%.1f km".format(result[0] / 1000)
            else               -> "%.0f km".format(result[0] / 1000)
        }
    }

    // ── live stats for the sheet header and filter chips ─────────────────────

    /** Stations heard within [windowMs], per message type. */
    fun activeCounts(windowMs: Long = ACTIVE_MS): Map<ItsG5Decoder.MsgType, Int> {
        val cutoff = System.currentTimeMillis() - windowMs
        return stations.values.filter { it.lastSeen >= cutoff }.groupingBy { it.msgType }.eachCount()
    }

    /** Re-bind rows so ages stay current without new frames. */
    fun refreshAges() {
        if (displayList.isNotEmpty()) rebuildAndNotify()
    }

    // ── expanded frame row ───────────────────────────────────────────────────

    private fun bindFrame(vh: FrameVH, frame: Frame) {
        vh.itemView.setBackgroundColor(0x0A000000)   // faint tint to visually indent
        vh.itemView.setOnClickListener { onFrameClick(frame) }
        vh.time.text = timeFmt.format(Date(frame.sec * 1000L + frame.usec / 1000L))
        vh.len.text  = frame.len.toString()
        vh.hex.text  = frame.hexPreview()
        when (frame.etherType) {
            null   -> { vh.et.text = "?";      vh.et.setTextColor(Color.parseColor("#F0883E")) }
            0x8947 -> { vh.et.text = "ITS-G5"; vh.et.setTextColor(Color.parseColor("#7EE787")) }
            else   -> { vh.et.text = "0x%04x".format(frame.etherType)
                        vh.et.setTextColor(Color.parseColor("#8B949E")) }
        }
    }

    // ── ViewHolder classes ───────────────────────────────────────────────────

    class StationVH(v: View) : RecyclerView.ViewHolder(v) {
        val card:    com.google.android.material.card.MaterialCardView = v.findViewById(R.id.stationCard)
        val iconBg:  View      = v.findViewById(R.id.stationIconBg)
        val icon:    ImageView = v.findViewById(R.id.stationIcon)
        val title:   TextView  = v.findViewById(R.id.stationTitle)
        val summary: TextView  = v.findViewById(R.id.stationSummary)
        val main:    TextView  = v.findViewById(R.id.stationTime)
        val meta:    TextView  = v.findViewById(R.id.stationDist)
        val lock:    ImageView = v.findViewById(R.id.stationLock)
    }

    class FrameVH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.fTime)
        val et:   TextView = v.findViewById(R.id.fEt)
        val len:  TextView = v.findViewById(R.id.fLen)
        val hex:  TextView = v.findViewById(R.id.fHex)
    }

    companion object {
        private const val TYPE_STATION   = 0
        private const val TYPE_FRAME     = 1
        private const val MAX_STATIONS   = 150  // max unique stations kept
        private const val FRAME_HISTORY  = 20   // frames kept per station for expand view
        private const val STALE_MS       = 30_000L
        const val ACTIVE_MS              = 60_000L
    }
}
