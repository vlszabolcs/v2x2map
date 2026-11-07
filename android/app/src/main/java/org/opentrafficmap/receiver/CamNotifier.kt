package org.opentrafficmap.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Nearby vehicles (CAM) as notifications, relative to the phone's position
 * and direction of travel:
 *
 *  - a quiet summary with the closest vehicle ahead and behind
 *    (distance, same direction / oncoming, indicator, accelerating / braking),
 *    updated at most once a second, removed when nobody is near;
 *  - a heads-up alert when a vehicle ahead brakes hard or makes an emergency
 *    brake, or a vehicle close ahead / behind starts indicating.
 */
class CamNotifier(private val ctx: Context) {

    enum class Where { AHEAD, BEHIND, LEFT, RIGHT, UNKNOWN }

    private class Vehicle(
        var cam: CamParser.Cam,
        var lat: Double,
        var lon: Double,
        var seenMs: Long,
        var indicator: Int = 0,          // 0 none, 1 left, 2 right, 3 hazard
        val alertedMs: HashMap<String, Long> = HashMap(),
    )

    private val vehicles = HashMap<Long, Vehicle>()
    private var own: Location? = null
    private var ownHeading: Float? = null
    private var lastSummaryMs = 0L
    private var summaryShown = false
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannels()
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(CH_SUMMARY,
            ctx.getString(R.string.near_channel_summary), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT,
            ctx.getString(R.string.near_channel_alert), NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true) })
    }

    /** Own position; the heading is kept from the last fix taken while moving. */
    fun updateOwn(loc: Location) {
        own = loc
        if (loc.hasBearing() && (!loc.hasSpeed() || loc.speed > 1.5f)) ownHeading = loc.bearing
    }

    /** Every live CAM frame (with a position). */
    fun onFrame(f: Frame) {
        if (!Prefs.nearbyNotify(ctx)) return
        val cam = f.cam ?: return
        if (cam.rsu) return
        val (lat, lon) = f.latLon ?: return
        val now = System.currentTimeMillis()
        val v = vehicles.getOrPut(cam.stationId) { Vehicle(cam, lat, lon, now) }
        v.cam = cam; v.lat = lat; v.lon = lon; v.seenMs = now

        val low = CamFormat.lowFor(cam, now)
        val newInd = low?.let { indicatorOf(it.lights) } ?: v.indicator
        val prevInd = v.indicator
        v.indicator = newInd
        val me = own
        if (me != null) checkAlerts(v, me, prevInd, now)

        if (now - lastSummaryMs >= 1000L) { lastSummaryMs = now; renderSummary(now) }
    }

    /** Call about once a second: drops vehicles that went quiet. */
    fun tick() {
        val now = System.currentTimeMillis()
        vehicles.values.removeAll { now - it.seenMs > GONE_MS }
        if (summaryShown && now - lastSummaryMs >= 1000L) { lastSummaryMs = now; renderSummary(now) }
    }

    fun stop() {
        nm.cancel(ID_SUMMARY)
        summaryShown = false
        vehicles.clear()
    }

    // ── geometry ──────────────────────────────────────────────────────────

    private class Rel(val dist: Float, val where: Where, val sameDir: Boolean?)

    private fun relation(v: Vehicle, me: Location): Rel {
        val out = FloatArray(2)
        Location.distanceBetween(me.latitude, me.longitude, v.lat, v.lon, out)
        val dist = out[0]
        val myHdg = ownHeading
        val where = if (myHdg == null) Where.UNKNOWN else {
            val a = norm(out[1] - myHdg)
            when {
                abs(a) <= 45f -> Where.AHEAD
                abs(a) >= 135f -> Where.BEHIND
                a > 0 -> Where.RIGHT
                else -> Where.LEFT
            }
        }
        val sameDir = if (myHdg == null) null else v.cam.headingDeg?.let { abs(norm((it - myHdg).toFloat())) <= 60f }
        return Rel(dist, where, sameDir)
    }

    private fun norm(a: Float): Float { var x = a % 360f; if (x > 180f) x -= 360f; if (x < -180f) x += 360f; return x }

    private fun indicatorOf(lights: Int): Int {
        val l = lights and CamParser.L_LEFT != 0
        val r = lights and CamParser.L_RIGHT != 0
        return when { l && r -> 3; l -> 1; r -> 2; else -> 0 }
    }

    // ── texts ─────────────────────────────────────────────────────────────

    private fun whereText(r: Rel): String = ctx.getString(when (r.where) {
        Where.AHEAD -> R.string.near_ahead
        Where.BEHIND -> R.string.near_behind
        Where.LEFT -> R.string.near_left
        Where.RIGHT -> R.string.near_right
        Where.UNKNOWN -> R.string.near_nearby
    })

    private fun motionText(c: CamParser.Cam): String? = when {
        c.ac(CamParser.AC_EMERGENCY) == true -> ctx.getString(R.string.cam_emergency_brake)
        c.ac(CamParser.AC_BRAKE) == true || (c.longAccel ?: 0.0) <= -1.0 -> ctx.getString(R.string.near_braking)
        (c.longAccel ?: 0.0) >= 0.7 -> ctx.getString(R.string.near_accelerating)
        else -> null
    }

    private fun indicatorText(ind: Int): String? = when (ind) {
        1 -> "◀ " + ctx.getString(R.string.cam_indicator)
        2 -> ctx.getString(R.string.cam_indicator) + " ▶"
        3 -> "⚠ " + ctx.getString(R.string.cam_hazard_lights)
        else -> null
    }

    private fun describe(v: Vehicle, r: Rel): String {
        val parts = mutableListOf("${whereText(r)} ${r.dist.roundToInt()} m")
        when (r.sameDir) {
            true -> parts += ctx.getString(R.string.near_same_dir)
            false -> parts += ctx.getString(R.string.near_oncoming)
            null -> {}
        }
        v.cam.speedMps?.let { parts += "%.0f km/h".format(Locale.ROOT, it * 3.6) }
        indicatorText(v.indicator)?.let { parts += it }
        motionText(v.cam)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    // ── summary notification ──────────────────────────────────────────────

    private fun renderSummary(now: Long) {
        val me = own
        val near = if (me == null) emptyList() else vehicles.values
            .filter { now - it.seenMs <= GONE_MS }
            .map { it to relation(it, me) }
            .filter { it.second.dist in MIN_DIST_M..RANGE_M }
            .sortedBy { it.second.dist }
        if (near.isEmpty()) {
            if (summaryShown) { nm.cancel(ID_SUMMARY); summaryShown = false }
            return
        }
        val ahead = near.firstOrNull { it.second.where == Where.AHEAD }
        val behind = near.firstOrNull { it.second.where == Where.BEHIND }
        val lines = listOfNotNull(ahead, behind).ifEmpty { listOf(near.first()) }
            .map { (v, r) -> describe(v, r) }
        val title = ctx.getString(R.string.near_title, near.size)
        val n = NotificationCompat.Builder(ctx, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(tapIntent())
            .build()
        if (notify(ID_SUMMARY, n)) summaryShown = true
    }

    // ── heads-up alerts ───────────────────────────────────────────────────

    private fun checkAlerts(v: Vehicle, me: Location, prevInd: Int, now: Long) {
        val newInd = v.indicator
        val r = relation(v, me)
        if (r.dist < MIN_DIST_M || r.dist > ALERT_RANGE_M) return
        val c = v.cam
        val ahead = r.where == Where.AHEAD && r.sameDir != false
        when {
            ahead && c.ac(CamParser.AC_EMERGENCY) == true ->
                alert(v, "ebrake", now, ctx.getString(R.string.near_alert_ebrake, r.dist.roundToInt()), describe(v, r))
            ahead && ((c.longAccel ?: 0.0) <= HARD_BRAKE || c.ac(CamParser.AC_BRAKE) == true && r.dist < 60f) ->
                alert(v, "brake", now, ctx.getString(R.string.near_alert_brake, r.dist.roundToInt()), describe(v, r))
        }
        if (newInd != 0 && newInd != prevInd && r.where != Where.UNKNOWN && r.dist <= INDICATOR_RANGE_M &&
            r.sameDir != false) {
            val what = indicatorText(newInd) ?: return
            alert(v, "ind$newInd", now, ctx.getString(R.string.near_alert_indicator, whereText(r), r.dist.roundToInt(), what), describe(v, r))
        }
    }

    private fun alert(v: Vehicle, key: String, now: Long, title: String, text: String) {
        val last = v.alertedMs[key] ?: 0L
        if (now - last < ALERT_REPEAT_MS) return
        v.alertedMs[key] = now
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setTimeoutAfter(8_000L)
            .setAutoCancel(true)
            .setContentIntent(tapIntent())
            .build()
        notify(ID_ALERT, n)
    }

    private fun notify(id: Int, n: android.app.Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try { nm.notify(id, n); true } catch (_: SecurityException) { false }
    }

    private fun tapIntent(): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val CH_SUMMARY = "v2x2map_nearby"
        private const val CH_ALERT = "v2x2map_nearby_alert"
        private const val ID_SUMMARY = 20
        private const val ID_ALERT = 21
        private const val GONE_MS = 5_000L
        /** Closer than this is most likely the phone's own car. */
        private const val MIN_DIST_M = 4f
        private const val RANGE_M = 300f
        private const val ALERT_RANGE_M = 150f
        private const val INDICATOR_RANGE_M = 80f
        private const val HARD_BRAKE = -3.0          // m/s²
        private const val ALERT_REPEAT_MS = 15_000L
    }
}
