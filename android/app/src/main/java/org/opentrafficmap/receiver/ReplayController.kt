package org.opentrafficmap.receiver

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.opentrafficmap.receiver.databinding.ActivityMainBinding
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replay of a log downloaded from the receiver: a time slider with play/pause
 * and speed, the receiver's route and position at the slider time, and two
 * optional layers computed from the whole log: coverage (where frames were
 * heard, coloured by RSSI) and estimated positions of transmitters whose
 * frames carry none.
 *
 * The map shows the state "as of" the slider time: frames from the marker
 * lifetime (settings) before it. Marker and station-list ages follow the
 * replay clock while a replay is open.
 */
class ReplayController(
    private val act: MainActivity,
    private val b: ActivityMainBinding,
    private val markers: MarkerLayer,
    private val adapter: FrameLogAdapter,
    /** Clear markers, station list and counters. */
    private val clear: () -> Unit,
    /** Show frames on the map and in the list (not live: no MQTT, no recording). */
    private val feed: (List<Frame>) -> Unit,
) {
    class Data(
        val file: File,
        val parsed: LogTools.Parsed,
        val coverage: List<LogTools.CoverageCell>,
        val transmitters: List<LogTools.Transmitter>,
    )

    private val main = Handler(Looper.getMainLooper())
    private var data: Data? = null
    private var order = IntArray(0)        // frame indices sorted by time
    private var times = LongArray(0)       // their times (µs), ascending
    private var cursor = 0                 // next index into order to feed
    private var curUs = 0L
    private var playing = false
    private var speedIdx = 1
    private val speeds = intArrayOf(1, 10, 60, 300)

    private var routeLine: Polyline? = null
    private var receiverMarker: Marker? = null
    private var coverageOverlay: Overlay? = null
    private val txOverlays = mutableListOf<Overlay>()

    val active: Boolean get() = data != null

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    init {
        b.replayPlay.setOnClickListener { if (playing) pause() else play() }
        b.replaySpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            renderSpeed()
        }
        b.replayClose.setOnClickListener { stop() }
        b.replaySlider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            main.removeCallbacks(seekRunnable)
            pendingSeekUs = sliderToUs(v)
            main.postDelayed(seekRunnable, 120)
        }
        b.replaySlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { pause() }
            override fun onStopTrackingTouch(slider: Slider) {
                main.removeCallbacks(seekRunnable)
                seek(sliderToUs(slider.value))
            }
        })
        b.replayCoverage.setOnCheckedChangeListener { _, on -> showCoverage(on) }
        b.replayTransmitters.setOnCheckedChangeListener { _, on -> showTransmitters(on) }
        renderSpeed()
    }

    private var pendingSeekUs = 0L
    private val seekRunnable = Runnable { seek(pendingSeekUs) }

    // ── open / close ──────────────────────────────────────────────────────

    fun start(d: Data) {
        stop()
        data = d
        val p = d.parsed
        order = p.frames.indices.sortedBy { p.frameTimesUs[it] }.toIntArray()
        times = LongArray(order.size) { p.frameTimesUs[order[it]] }
        markers.clock = { curUs / 1000L }
        adapter.clock = { curUs / 1000L }

        b.replayTitle.text = d.parsed.header?.optLong("file")?.takeIf { it > 0 }
            ?.let { act.getString(R.string.logs_file_title, it) } ?: d.file.nameWithoutExtension
        b.replayCoverage.isChecked = false
        b.replayTransmitters.isChecked = false
        b.replayCoverage.isEnabled = d.coverage.isNotEmpty()
        b.replayTransmitters.isEnabled = d.transmitters.any { it.reported == null && it.estimated != null }
        b.replayBar.visibility = View.VISIBLE

        drawRoute(p.track)
        zoomToLog()
        seek(if (times.isEmpty()) 0L else times.last())
        if (p.track.isEmpty()) act.toastLong(act.getString(R.string.replay_no_track))
    }

    fun stop() {
        if (data == null) return
        pause()
        main.removeCallbacks(seekRunnable)
        showCoverage(false)
        showTransmitters(false)
        routeLine?.let { b.map.overlays.remove(it) }; routeLine = null
        receiverMarker?.let { b.map.overlays.remove(it) }; receiverMarker = null
        data = null
        markers.clock = { System.currentTimeMillis() }
        adapter.clock = { System.currentTimeMillis() }
        b.replayBar.visibility = View.GONE
        clear()
        b.map.invalidate()
    }

    // ── time navigation ───────────────────────────────────────────────────

    private fun startUs() = if (times.isEmpty()) 0L else times.first()
    private fun endUs() = if (times.isEmpty()) 0L else times.last()

    private fun sliderToUs(v: Float): Long =
        startUs() + ((endUs() - startUs()) * (v / 1000.0)).toLong()

    private fun usToSlider(us: Long): Float {
        val span = endUs() - startUs()
        return if (span <= 0) 1000f else ((us - startUs()) * 1000.0 / span).toFloat().coerceIn(0f, 1000f)
    }

    /** Rebuild the map as of [us]: frames from the marker lifetime before it. */
    private fun seek(us: Long) {
        if (data == null) return
        curUs = us
        clear()
        val ttlUs = Prefs.markerTtlMinutes(act).toLong() * 60_000_000L
        cursor = lowerBound(us - ttlUs)
        advanceTo(us)
    }

    /** Feed every frame up to [us] that has not been shown yet. */
    private fun advanceTo(us: Long) {
        val d = data ?: return
        curUs = us
        val batch = ArrayList<Frame>()
        while (cursor < order.size && times[cursor] <= us) {
            batch += d.parsed.frames[order[cursor]]
            cursor++
            if (batch.size >= 500) { feed(batch.toList()); batch.clear() }
        }
        if (batch.isNotEmpty()) feed(batch)
        markers.prune()
        render()
    }

    private fun lowerBound(us: Long): Int {
        var lo = 0; var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < us) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun play() {
        if (data == null || times.isEmpty()) return
        if (curUs >= endUs()) seek(startUs())
        playing = true
        b.replayPlay.setImageResource(R.drawable.ic_pause)
        b.replayPlay.contentDescription = act.getString(R.string.replay_pause)
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    private fun pause() {
        playing = false
        main.removeCallbacks(tick)
        b.replayPlay.setImageResource(R.drawable.ic_play)
        b.replayPlay.contentDescription = act.getString(R.string.replay_play)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val next = curUs + TICK_MS * 1000L * speeds[speedIdx]
            if (next >= endUs()) { advanceTo(endUs()); pause(); return }
            advanceTo(next)
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun renderSpeed() {
        b.replaySpeed.text = "${speeds[speedIdx]}×"
    }

    private fun render() {
        b.replaySlider.value = usToSlider(curUs)
        val label = if (curUs >= 1_000_000_000_000_000L) timeFmt.format(Date(curUs / 1000L))
                    else "+%d:%02d".format(Locale.ROOT, (curUs - startUs()) / 60_000_000L,
                                           (curUs - startUs()) / 1_000_000L % 60)
        b.replayTime.text = act.getString(R.string.replay_time_fmt, label, cursor, order.size)
        moveReceiver()
        b.map.invalidate()
    }

    // ── map layers ────────────────────────────────────────────────────────

    private fun drawRoute(track: List<LogTools.TrackPoint>) {
        if (track.size < 2) return
        routeLine = Polyline().also { poly ->
            poly.outlinePaint.color = ContextCompat.getColor(act, R.color.type_denm)
            poly.outlinePaint.strokeWidth = 6f
            poly.setPoints(track.map { GeoPoint(it.lat, it.lon) })
            b.map.overlays.add(0, poly)
        }
    }

    private fun zoomToLog() {
        val d = data ?: return
        val pts = d.parsed.track.map { GeoPoint(it.lat, it.lon) }.ifEmpty {
            d.parsed.frames.mapNotNull { f -> f.latLon?.let { GeoPoint(it.first, it.second) } }
        }
        if (pts.isNotEmpty())
            b.map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(pts).increaseByScale(1.2f), true)
    }

    /** Receiver position at the replay time (last track point before it). */
    private fun moveReceiver() {
        val track = data?.parsed?.track ?: return
        if (track.isEmpty()) return
        var lo = 0; var hi = track.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (track[mid].timeUs <= curUs) lo = mid + 1 else hi = mid
        }
        val pt = track[(lo - 1).coerceAtLeast(0)]
        val m = receiverMarker ?: Marker(b.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(act, R.drawable.ic_my_location)!!.mutate().also {
                DrawableCompat.setTint(it, ContextCompat.getColor(act, R.color.type_denm))
            }
            title = act.getString(R.string.replay_receiver)
            b.map.overlays.add(this)
            receiverMarker = this
        }
        m.position = GeoPoint(pt.lat, pt.lon)
    }

    private fun showCoverage(on: Boolean) {
        coverageOverlay?.let { b.map.overlays.remove(it) }
        coverageOverlay = null
        val d = data
        if (on && d != null) {
            coverageOverlay = CoverageOverlay(d.coverage) { c ->
                MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.replay_coverage)
                    .setMessage(LogTools.cellDesc(c))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.also { b.map.overlays.add(0, it) }
        }
        b.map.invalidate()
    }

    /** Estimated positions (with an uncertainty circle) of transmitters whose
     *  frames carry no position of their own. */
    private fun showTransmitters(on: Boolean) {
        txOverlays.forEach { b.map.overlays.remove(it) }
        txOverlays.clear()
        val d = data
        if (on && d != null) {
            val color = 0xFFFF9800.toInt()
            for (t in d.transmitters) {
                if (t.reported != null) continue
                val (lat, lon) = t.estimated ?: continue
                val center = GeoPoint(lat, lon)
                t.radiusM?.let { r ->
                    txOverlays += Polygon(b.map).apply {
                        points = Polygon.pointsAsCircle(center, r.coerceAtLeast(10.0))
                        fillPaint.color = (color and 0x00FFFFFF) or 0x30000000
                        outlinePaint.color = color
                        outlinePaint.strokeWidth = 2f
                    }
                }
                txOverlays += Marker(b.map).apply {
                    position = center
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ContextCompat.getDrawable(act,
                        if (t.isRsu) R.drawable.ic_marker_trafficlight else R.drawable.ic_marker_dot
                    )!!.mutate().also { DrawableCompat.setTint(it, color) }
                    title = act.getString(R.string.replay_tx_title, LogTools.txName(t))
                    snippet = LogTools.txDesc(t)
                }
            }
            if (txOverlays.isEmpty()) act.toastLong(act.getString(R.string.replay_tx_none))
            txOverlays.forEach { b.map.overlays.add(it) }
        }
        b.map.invalidate()
    }

    companion object {
        private const val TICK_MS = 200L
    }
}
