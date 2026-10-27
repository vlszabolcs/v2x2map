package org.opentrafficmap.receiver

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Helpers for log files downloaded from the receiver's SD card. A log file is
 * the same record stream as the live link (see [FrameReader]): a "log" header
 * record, ITSR + ITS5 frames, and "time" / "pos" JSON records.
 */
object LogTools {

    /** Receiver position; [timeUs] is µs since the epoch once the log's clock
     *  is known, otherwise µs since the receiver booted. */
    class TrackPoint(val timeUs: Long, val lat: Double, val lon: Double, val src: String)

    class Parsed(
        val frames: List<Frame>,
        /** Receiver positions (GNSS or the phone that was connected), in time order. */
        val track: List<TrackPoint>,
        /** unix_us − uptime_us from the first time-sync record, or null. */
        val clockOffsetUs: Long?,
        val header: JSONObject?,
    ) {
        /** Frame times on the same scale as [track], in file order. */
        val frameTimesUs: LongArray = LongArray(frames.size) { unixMicros(frames[it], clockOffsetUs) }

        /** Where the receiver was when it heard frame [i]: the nearest track
         *  point within [maxGapUs], or null. */
        fun receiverPos(i: Int, maxGapUs: Long = 10_000_000L): TrackPoint? {
            if (track.isEmpty()) return null
            val t = frameTimesUs[i]
            var lo = 0
            var hi = track.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (track[mid].timeUs < t) lo = mid + 1 else hi = mid
            }
            val cand = listOfNotNull(track.getOrNull(lo), track.getOrNull(lo - 1))
            val best = cand.minByOrNull { kotlin.math.abs(it.timeUs - t) } ?: return null
            return if (kotlin.math.abs(best.timeUs - t) <= maxGapUs) best else null
        }
    }

    fun parse(file: File): Parsed {
        val frames = mutableListOf<Frame>()
        class RawPos(val upUs: Long, val unixMs: Long, val lat: Double, val lon: Double, val src: String)
        val raw = mutableListOf<RawPos>()
        var offset: Long? = null
        var header: JSONObject? = null
        val reader = FrameReader()
        reader.onJson = { js ->
            try {
                val o = JSONObject(js)
                when (o.optString("t")) {
                    "log" -> if (header == null) header = o
                    "time" -> if (offset == null) offset = o.getLong("unix_ms") * 1000L - o.getLong("up_us")
                    "pos" -> raw += RawPos(o.optLong("up_us"), o.optLong("unix_ms"),
                        o.getDouble("lat"), o.getDouble("lon"), o.optString("src"))
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
        val off = offset
        val track = raw.map { p ->
            val t = if (p.unixMs > 0) p.unixMs * 1000L else p.upUs + (off ?: 0L)
            TrackPoint(t, p.lat, p.lon, p.src)
        }.sortedBy { it.timeUs }
        return Parsed(frames, track, off, header)
    }

    /** Frame time in µs since the epoch; relative stamps are fixed up with
     *  the log's time-sync record when there is one. */
    fun unixMicros(f: Frame, clockOffsetUs: Long?): Long {
        val t = f.sec * 1_000_000L + f.usec
        return if (f.sec >= 1_000_000_000L || clockOffsetUs == null) t else t + clockOffsetUs
    }

    // ── PCAP ──────────────────────────────────────────────────────────────

    /** libpcap with radiotap headers (link type 127): Wireshark shows the
     *  802.11 frame and the RSSI the receiver measured. */
    object Pcap {
        fun writeGlobalHeader(out: OutputStream) {
            val gh = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            gh.putInt(0xa1b2c3d4.toInt()); gh.putShort(2); gh.putShort(4)
            gh.putInt(0); gh.putInt(0); gh.putInt(65535); gh.putInt(127)
            out.write(gh.array())
        }

        fun writePacket(out: OutputStream, timeUs: Long, payload: ByteArray, rssi: Int?) {
            // Radiotap: version, pad, length, present flags (bit 5 = dBm antenna signal)
            val rt = ByteBuffer.allocate(if (rssi != null) 9 else 8).order(ByteOrder.LITTLE_ENDIAN)
            rt.put(0); rt.put(0); rt.putShort(rt.capacity().toShort())
            rt.putInt(if (rssi != null) 0x20 else 0)
            if (rssi != null) rt.put(rssi.toByte())
            val len = rt.capacity() + payload.size
            val rh = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            rh.putInt((timeUs / 1_000_000L).toInt()); rh.putInt((timeUs % 1_000_000L).toInt())
            rh.putInt(len); rh.putInt(len)
            out.write(rh.array())
            out.write(rt.array())
            out.write(payload)
        }
    }

    fun toPcap(src: File, dst: File): Int {
        val p = parse(src)
        BufferedOutputStream(FileOutputStream(dst)).use { out ->
            Pcap.writeGlobalHeader(out)
            p.frames.forEachIndexed { i, f -> Pcap.writePacket(out, p.frameTimesUs[i], f.payload, f.rssi) }
        }
        return p.frames.size
    }

    // ── coverage ──────────────────────────────────────────────────────────

    /** One grid cell (~[CELL_M] m) of the receiver's route: how much it heard there. */
    class CoverageCell(
        val lat: Double, val lon: Double,
        val frames: Int,
        val stations: Int,
        /** Strongest RSSI heard in the cell, or null without RSSI records. */
        val bestRssi: Int?,
        val meanRssi: Double?,
    )

    const val CELL_M = 25.0

    fun coverage(p: Parsed): List<CoverageCell> {
        class Acc {
            var lat = 0.0; var lon = 0.0; var n = 0
            var rssiSum = 0.0; var rssiN = 0; var best: Int? = null
            val stations = HashSet<String>()
        }
        val cells = HashMap<Long, Acc>()
        for (i in p.frames.indices) {
            val pos = p.receiverPos(i) ?: continue
            val f = p.frames[i]
            val latStep = CELL_M / 111_320.0
            val lonStep = CELL_M / (111_320.0 * cos(Math.toRadians(pos.lat)).coerceAtLeast(0.01))
            val key = (Math.floor(pos.lat / latStep).toLong() shl 32) xor
                      (Math.floor(pos.lon / lonStep).toLong() and 0xFFFFFFFFL)
            val a = cells.getOrPut(key) { Acc() }
            a.lat += pos.lat; a.lon += pos.lon; a.n++
            f.rssi?.let { r ->
                a.rssiSum += r; a.rssiN++
                a.best = maxOf(a.best ?: r, r)
            }
            a.stations += transmitterKey(f)
        }
        return cells.values.map { a ->
            CoverageCell(a.lat / a.n, a.lon / a.n, a.n, a.stations.size, a.best,
                if (a.rssiN > 0) a.rssiSum / a.rssiN else null)
        }
    }

    // ── transmitter (RSU) position estimate ───────────────────────────────

    /** Station ID when the frame carries one, otherwise the 802.11 source address. */
    fun transmitterKey(f: Frame): String =
        f.stationId?.let { "sid:%08x".format(it.toInt()) } ?: "mac:${f.srcMac ?: "?"}"

    class Transmitter(
        val key: String,
        val msgTypes: Set<ItsG5Decoder.MsgType>,
        val frames: Int,
        /** Position the frames themselves report (last one), if any. */
        val reported: Pair<Double, Double>?,
        /** RSSI-weighted centroid of where the receiver heard it, if enough samples. */
        val estimated: Pair<Double, Double>?,
        /** Rough uncertainty of [estimated] in metres. */
        val radiusM: Double?,
        val bestRssi: Int?,
        val firstUs: Long,
        val lastUs: Long,
    ) {
        val isRsu: Boolean get() = msgTypes.any {
            it == ItsG5Decoder.MsgType.SPATEM || it == ItsG5Decoder.MsgType.MAPEM ||
            it == ItsG5Decoder.MsgType.IVIM || it == ItsG5Decoder.MsgType.SSEM ||
            it == ItsG5Decoder.MsgType.RTCMEM
        }
    }

    /**
     * Group frames by transmitter and estimate where each one is from the
     * receiver's own positions: a centroid weighted by received power
     * (10^(rssi/10)), so the places where it was heard loudest count most.
     * Needs [minSamples] receiver positions spread over at least [minSpreadM].
     * Without RSSI every sample counts the same.
     */
    fun transmitters(p: Parsed, minSamples: Int = 5, minSpreadM: Double = 30.0): List<Transmitter> {
        class Acc {
            val types = HashSet<ItsG5Decoder.MsgType>()
            var n = 0
            var reported: Pair<Double, Double>? = null
            var best: Int? = null
            var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
            val samples = ArrayList<Triple<Double, Double, Double>>()   // lat, lon, weight
        }
        val acc = LinkedHashMap<String, Acc>()
        for (i in p.frames.indices) {
            val f = p.frames[i]
            val a = acc.getOrPut(transmitterKey(f)) { Acc() }
            a.types += f.msgType
            a.n++
            f.latLon?.let { a.reported = it }
            f.rssi?.let { a.best = maxOf(a.best ?: it, it) }
            val t = p.frameTimesUs[i]
            if (t < a.first) a.first = t
            if (t > a.last) a.last = t
            val pos = p.receiverPos(i) ?: continue
            val w = f.rssi?.let { 10.0.pow(it / 10.0) } ?: 1.0
            a.samples += Triple(pos.lat, pos.lon, w)
        }
        return acc.map { (key, a) ->
            var est: Pair<Double, Double>? = null
            var radius: Double? = null
            if (a.samples.size >= minSamples && spreadM(a.samples) >= minSpreadM) {
                val wSum = a.samples.sumOf { it.third }
                val lat = a.samples.sumOf { it.first * it.third } / wSum
                val lon = a.samples.sumOf { it.second * it.third } / wSum
                est = lat to lon
                radius = sqrt(a.samples.sumOf { distM(lat, lon, it.first, it.second).pow(2) * it.third } / wSum)
            }
            Transmitter(key, a.types, a.n, a.reported, est, radius, a.best, a.first, a.last)
        }
    }

    private fun spreadM(s: List<Triple<Double, Double, Double>>): Double {
        val minLat = s.minOf { it.first }; val maxLat = s.maxOf { it.first }
        val minLon = s.minOf { it.second }; val maxLon = s.maxOf { it.second }
        return distM(minLat, minLon, maxLat, maxLon)
    }

    /** Equirectangular distance, fine for the few hundred metres involved. */
    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * 6_371_000.0
    }

    // ── GPX / KML export ──────────────────────────────────────────────────

    private fun isoTime(us: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(us / 1000L))

    /** Unix-time stamps only; relative ones (clock never synced) are left out. */
    private fun hasUnixTime(us: Long) = us >= 1_000_000_000_000_000L

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** GPX: the receiver's route as a track, transmitters as waypoints. Returns the point count. */
    fun toGpx(src: File, dst: File): Int {
        val p = parse(src)
        val tx = transmitters(p)
        dst.bufferedWriter().use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
            w.write("""<gpx version="1.1" creator="V2X2MAP" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
            for (t in tx) {
                val (lat, lon) = t.reported ?: t.estimated ?: continue
                w.write("""  <wpt lat="%.7f" lon="%.7f"><name>%s</name><desc>%s</desc></wpt>""".format(
                    Locale.ROOT, lat, lon, esc(txName(t)), esc(txDesc(t))) + "\n")
            }
            w.write("  <trk><name>${esc(src.nameWithoutExtension)}</name><trkseg>\n")
            for (pt in p.track) {
                w.write("""    <trkpt lat="%.7f" lon="%.7f">""".format(Locale.ROOT, pt.lat, pt.lon))
                if (hasUnixTime(pt.timeUs)) w.write("<time>${isoTime(pt.timeUs)}</time>")
                w.write("</trkpt>\n")
            }
            w.write("  </trkseg></trk>\n</gpx>\n")
        }
        return p.track.size
    }

    /** KML: route, coverage cells coloured by signal, transmitters (reported and estimated). */
    fun toKml(src: File, dst: File): Int {
        val p = parse(src)
        val cells = coverage(p)
        val tx = transmitters(p)
        dst.bufferedWriter().use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
            w.write("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""" + "\n")
            w.write("<name>${esc(src.nameWithoutExtension)}</name>\n")
            w.write("""<Style id="route"><LineStyle><color>ffff9621</color><width>3</width></LineStyle></Style>""" + "\n")
            w.write("""<Style id="rsu"><IconStyle><color>ff2196f3</color></IconStyle></Style>""" + "\n")
            w.write("""<Style id="est"><IconStyle><color>ff00c8ff</color></IconStyle></Style>""" + "\n")
            if (p.track.size >= 2) {
                w.write("<Placemark><name>Route</name><styleUrl>#route</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n")
                for (pt in p.track) w.write("%.7f,%.7f,0\n".format(Locale.ROOT, pt.lon, pt.lat))
                w.write("</coordinates></LineString></Placemark>\n")
            }
            w.write("<Folder><name>Coverage</name>\n")
            for (c in cells) {
                val color = kmlColor(Coverage.colorFor(c))
                w.write("<Placemark><name>${c.bestRssi?.let { "$it dBm" } ?: "${c.frames}"}</name>")
                w.write("<description>${esc(cellDesc(c))}</description>")
                w.write("<Style><IconStyle><color>$color</color><scale>0.5</scale>" +
                        "<Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon></IconStyle></Style>")
                w.write("<Point><coordinates>%.7f,%.7f,0</coordinates></Point></Placemark>\n".format(Locale.ROOT, c.lon, c.lat))
            }
            w.write("</Folder>\n<Folder><name>Transmitters</name>\n")
            for (t in tx) {
                t.reported?.let { (lat, lon) ->
                    w.write("<Placemark><name>${esc(txName(t))}</name><description>${esc(txDesc(t))}</description>")
                    w.write("<styleUrl>#rsu</styleUrl><Point><coordinates>%.7f,%.7f,0</coordinates></Point></Placemark>\n"
                        .format(Locale.ROOT, lon, lat))
                }
                t.estimated?.let { (lat, lon) ->
                    w.write("<Placemark><name>${esc(txName(t))} (est.)</name><description>${esc(txDesc(t))}</description>")
                    w.write("<styleUrl>#est</styleUrl><Point><coordinates>%.7f,%.7f,0</coordinates></Point></Placemark>\n"
                        .format(Locale.ROOT, lon, lat))
                }
            }
            w.write("</Folder>\n</Document></kml>\n")
        }
        return cells.size
    }

    /** #AARRGGBB → KML aabbggrr */
    private fun kmlColor(argb: Int): String = "%02x%02x%02x%02x".format(
        (argb ushr 24) and 0xFF, argb and 0xFF, (argb ushr 8) and 0xFF, (argb ushr 16) and 0xFF)

    fun txName(t: Transmitter): String =
        t.msgTypes.filter { it != ItsG5Decoder.MsgType.UNKNOWN }.joinToString("/") { it.short }
            .ifEmpty { "?" } + " " + t.key.substringAfter(':')

    fun txDesc(t: Transmitter): String = buildString {
        append("${t.frames} frames")
        t.bestRssi?.let { append(", best $it dBm") }
        t.radiusM?.let { append(", estimate ±%.0f m".format(Locale.ROOT, it)) }
        if (hasUnixTime(t.firstUs)) append(", ${isoTime(t.firstUs)} – ${isoTime(t.lastUs)}")
    }

    fun cellDesc(c: CoverageCell): String = buildString {
        append("${c.frames} frames, ${c.stations} transmitters")
        c.bestRssi?.let { append(", best $it dBm") }
        c.meanRssi?.let { append(", mean %.0f dBm".format(Locale.ROOT, it)) }
    }
}
