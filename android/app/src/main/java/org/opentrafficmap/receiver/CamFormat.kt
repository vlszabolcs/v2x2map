package org.opentrafficmap.receiver

import android.content.Context
import java.util.Locale
import kotlin.math.abs

/**
 * Human-readable CAM vehicle state: a compact one-line summary for the
 * station list and labelled rows for the frame detail sheet.
 *
 * Lights and vehicle role arrive only in the low-frequency container
 * (every ≥ 500 ms), so the last known values per station are kept here.
 */
object CamFormat {

    class LowFreq(val lights: Int, val role: Int)

    private val lastLow = HashMap<Long, LowFreq>()

    /** Remember the low-frequency part of [c]; call for every CAM shown. */
    @Synchronized
    fun remember(c: CamParser.Cam) {
        val l = c.lights ?: return
        lastLow[c.stationId] = LowFreq(l, c.role ?: 0)
        if (lastLow.size > 2000) lastLow.clear()
    }

    @Synchronized
    fun lowFor(c: CamParser.Cam): LowFreq? =
        c.lights?.let { LowFreq(it, c.role ?: 0) } ?: lastLow[c.stationId]

    @Synchronized
    fun clear() = lastLow.clear()

    /** Compact state for the station list, e.g. "◀ Index · Brake · Low beam". Empty if nothing notable. */
    fun summary(ctx: Context, c: CamParser.Cam): String {
        val parts = mutableListOf<String>()
        val low = lowFor(c)
        low?.let { l ->
            val left = l.lights and CamParser.L_LEFT != 0
            val right = l.lights and CamParser.L_RIGHT != 0
            when {
                left && right -> parts += "⚠ " + ctx.getString(R.string.cam_hazard_lights)
                left -> parts += "◀ " + ctx.getString(R.string.cam_indicator)
                right -> parts += ctx.getString(R.string.cam_indicator) + " ▶"
            }
        }
        if (c.ac(CamParser.AC_EMERGENCY) == true) parts += ctx.getString(R.string.cam_emergency_brake)
        else if (c.ac(CamParser.AC_BRAKE) == true) parts += ctx.getString(R.string.cam_brake)
        if (c.ac(CamParser.AC_GAS) == true) parts += ctx.getString(R.string.cam_gas)
        if (c.driveDirection == 1) parts += ctx.getString(R.string.cam_reversing)
        low?.let { l ->
            when {
                l.lights and CamParser.L_HIGH_BEAM != 0 -> parts += ctx.getString(R.string.cam_high_beam)
                l.lights and CamParser.L_LOW_BEAM != 0 -> parts += ctx.getString(R.string.cam_low_beam)
            }
            if (l.lights and CamParser.L_FOG != 0) parts += ctx.getString(R.string.cam_fog)
        }
        when {
            c.ac(CamParser.AC_ACC) == true -> parts += "ACC"
            c.ac(CamParser.AC_CRUISE) == true -> parts += ctx.getString(R.string.cam_cruise)
        }
        return parts.joinToString(" · ")
    }

    /** Labelled rows for the detail sheet. */
    fun rows(ctx: Context, c: CamParser.Cam): List<Pair<String, String>> {
        val r = mutableListOf<Pair<String, String>>()
        fun s(id: Int) = ctx.getString(id)
        fun yes(b: Boolean?) = when (b) { true -> s(R.string.cam_on); false -> s(R.string.cam_off); null -> "—" }

        r += s(R.string.cam_label_its_id) to "${c.stationId}"
        if (c.rsu) { r += s(R.string.cam_label_kind) to s(R.string.cam_rsu); return r }

        r += s(R.string.cam_label_speed) to (c.speedMps?.let { "%.1f km/h".format(Locale.ROOT, it * 3.6) } ?: "—")
        r += s(R.string.cam_label_direction) to s(when (c.driveDirection) {
            0 -> R.string.cam_forward; 1 -> R.string.cam_backward; else -> R.string.cam_unavailable })
        r += s(R.string.cam_label_accel) to (c.longAccel?.let { "%+.1f m/s²".format(Locale.ROOT, it) } ?: "—")

        // Pedals and driver assistance
        if (c.accelControl != null) {
            r += s(R.string.cam_label_brake) to yes(c.ac(CamParser.AC_BRAKE))
            r += s(R.string.cam_label_gas) to yes(c.ac(CamParser.AC_GAS))
            r += s(R.string.cam_label_emergency) to yes(c.ac(CamParser.AC_EMERGENCY))
            r += s(R.string.cam_label_collision) to yes(c.ac(CamParser.AC_COLLISION))
            r += "ACC" to yes(c.ac(CamParser.AC_ACC))
            r += s(R.string.cam_cruise) to yes(c.ac(CamParser.AC_CRUISE))
            r += s(R.string.cam_label_limiter) to yes(c.ac(CamParser.AC_LIMITER))
        } else {
            r += s(R.string.cam_label_pedals) to s(R.string.cam_not_sent)
        }

        // Lights (last known if this CAM has no low-frequency container)
        val low = lowFor(c)
        if (low != null) {
            val l = low.lights
            fun on(bit: Int) = yes(l and bit != 0)
            val ind = when {
                l and CamParser.L_LEFT != 0 && l and CamParser.L_RIGHT != 0 -> s(R.string.cam_hazard_lights)
                l and CamParser.L_LEFT != 0 -> "◀ " + s(R.string.cam_left)
                l and CamParser.L_RIGHT != 0 -> s(R.string.cam_right) + " ▶"
                else -> s(R.string.cam_off)
            }
            r += s(R.string.cam_indicator) to ind
            r += s(R.string.cam_low_beam) to on(CamParser.L_LOW_BEAM)
            r += s(R.string.cam_high_beam) to on(CamParser.L_HIGH_BEAM)
            r += s(R.string.cam_drl) to on(CamParser.L_DRL)
            r += s(R.string.cam_fog) to on(CamParser.L_FOG)
            r += s(R.string.cam_reverse_light) to on(CamParser.L_REVERSE)
            r += s(R.string.cam_parking) to on(CamParser.L_PARKING)
            if (c.lights == null) r += "" to s(R.string.cam_lights_last_known)
            val roles = ctx.resources.getStringArray(R.array.cam_vehicle_roles)
            r += s(R.string.cam_label_role) to (roles.getOrNull(low.role) ?: "${low.role}")
        } else {
            r += s(R.string.cam_label_lights) to s(R.string.cam_not_yet)
        }

        // Dynamics and size
        c.yawRate?.let { r += s(R.string.cam_label_yaw) to "%+.2f °/s".format(Locale.ROOT, it) }
        c.curvature?.let { v ->
            r += s(R.string.cam_label_curve) to if (v == 0) s(R.string.cam_straight)
                else "%s, r ≈ %.0f m".format(Locale.ROOT, s(if (v > 0) R.string.cam_left else R.string.cam_right), 10000.0 / abs(v))
        }
        c.steeringDeg?.let { r += s(R.string.cam_label_steering) to "%+.1f°".format(Locale.ROOT, it) }
        c.latAccel?.let { r += s(R.string.cam_label_lat_accel) to "%+.1f m/s²".format(Locale.ROOT, it) }
        c.lanePosition?.let { lane ->
            r += s(R.string.cam_label_lane) to when (lane) {
                -1 -> s(R.string.cam_off_road)
                0 -> s(R.string.cam_hard_shoulder)
                14 -> s(R.string.cam_hard_shoulder)
                else -> ctx.getString(R.string.cam_lane_n, lane)
            }
        }
        if (c.lengthM != null || c.widthM != null)
            r += s(R.string.cam_label_size) to "%s × %s m".format(Locale.ROOT,
                c.lengthM?.let { "%.1f".format(Locale.ROOT, it) } ?: "?",
                c.widthM?.let { "%.1f".format(Locale.ROOT, it) } ?: "?")
        c.altitudeM?.let { r += s(R.string.cam_label_altitude) to "%.1f m".format(Locale.ROOT, it) }
        c.pathPoints?.let { r += s(R.string.cam_label_path) to "$it" }
        return r
    }
}
