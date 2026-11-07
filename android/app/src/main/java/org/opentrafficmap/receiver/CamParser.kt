package org.opentrafficmap.receiver

/**
 * UPER decoder for the vehicle part of a CAM (ETSI EN 302 637-2 v1.4.1,
 * CDD TS 102 894-2 v1.3.1, ITS PDU protocolVersion 1 or 2):
 * basic container, the vehicle high-frequency container (incl. the optional
 * acceleration control = brake / gas pedal, cruise control …) and the vehicle
 * low-frequency container (vehicle role, exterior lights, path history).
 *
 * The low-frequency container is only sent every ≥ 500 ms, so [lights] and
 * [role] are null in most CAMs; callers keep the last known values.
 * Values that are "unavailable" in the standard come back as null.
 */
object CamParser {

    class Cam(
        val stationId: Long,
        val generationDeltaTime: Int,
        val stationType: Int,
        val altitudeM: Double?,
        // high frequency (vehicle)
        val headingDeg: Double?,
        val speedMps: Double?,
        val driveDirection: Int,          // 0 forward, 1 backward, 2 unavailable
        val lengthM: Double?,
        val widthM: Double?,
        val longAccel: Double?,           // m/s²
        val curvature: Int?,              // raw, 1/10000 m⁻¹ (radius = 10000 / |value| m)
        val yawRate: Double?,             // °/s
        val accelControl: Int?,           // 7 bits, see [AC_BRAKE] …
        val lanePosition: Int?,           // -1 off road, 0 inner hard shoulder, 1 innermost lane …
        val steeringDeg: Double?,
        val latAccel: Double?,
        val vertAccel: Double?,
        // low frequency (vehicle)
        val role: Int?,                   // VehicleRole
        val lights: Int?,                 // 8 bits, see [L_LOW_BEAM] …
        val pathPoints: Int?,
        val rsu: Boolean,
    ) {
        fun ac(bit: Int) = accelControl?.let { it and bit != 0 }
        fun light(bit: Int) = lights?.let { it and bit != 0 }
    }

    // AccelerationControl BIT STRING (SIZE(7)), bit 0 first on the wire
    const val AC_BRAKE      = 1 shl 0
    const val AC_GAS        = 1 shl 1
    const val AC_EMERGENCY  = 1 shl 2
    const val AC_COLLISION  = 1 shl 3
    const val AC_ACC        = 1 shl 4
    const val AC_CRUISE     = 1 shl 5
    const val AC_LIMITER    = 1 shl 6

    // ExteriorLights BIT STRING (SIZE(8))
    const val L_LOW_BEAM  = 1 shl 0
    const val L_HIGH_BEAM = 1 shl 1
    const val L_LEFT      = 1 shl 2
    const val L_RIGHT     = 1 shl 3
    const val L_DRL       = 1 shl 4
    const val L_REVERSE   = 1 shl 5
    const val L_FOG       = 1 shl 6
    const val L_PARKING   = 1 shl 7

    /**
     * @param p      full 802.11 frame payload
     * @param btpOff byte offset of the 4-byte BTP-B header within [p]
     */
    fun parse(p: ByteArray, btpOff: Int): Cam? {
        val its = btpOff + 4
        if (its + 8 > p.size) return null
        val ver = p[its].toInt() and 0xFF
        if (ver != 1 && ver != 2) return null
        if ((p[its + 1].toInt() and 0xFF) != 2) return null   // messageID = CAM
        val sid = ((p[its + 2].toLong() and 0xFF) shl 24) or ((p[its + 3].toLong() and 0xFF) shl 16) or
                  ((p[its + 4].toLong() and 0xFF) shl 8) or (p[its + 5].toLong() and 0xFF)
        return try { parseBody(Bits(p, its + 6), sid) } catch (_: Exception) { null }
    }

    private fun parseBody(b: Bits, sid: Long): Cam? {
        val gdt = b.bits(16)
        // CamParameters: ext bit + lowFrequency / specialVehicle present
        b.bit()
        val lfPresent = b.bit() == 1
        b.bit()

        // BasicContainer: ext bit, stationType, referencePosition
        b.bit()
        val stationType = b.bits(8)
        b.bits(31); b.bits(32)            // latitude, longitude (taken from GN)
        b.bits(36)                        // PosConfidenceEllipse
        val alt = b.bits(20) - 100000     // AltitudeValue, 0.01 m
        b.bits(4)                         // AltitudeConfidence

        // HighFrequencyContainer CHOICE: ext bit + index (0 vehicle, 1 RSU)
        if (b.bit() == 1) return null
        if (b.bit() == 1) {
            return Cam(sid, gdt, stationType, altOrNull(alt), null, null, 2, null, null, null,
                null, null, null, null, null, null, null, null, null, null, rsu = true)
        }

        // BasicVehicleContainerHighFrequency: 7 OPTIONAL flags
        val accPres  = b.bit() == 1
        val lanePres = b.bit() == 1
        val steerPres = b.bit() == 1
        val latPres  = b.bit() == 1
        val vertPres = b.bit() == 1
        val perfPres = b.bit() == 1
        val tollPres = b.bit() == 1

        val heading = b.bits(12); b.bits(7)
        val speed = b.bits(14); b.bits(7)
        val drive = b.bits(2)
        val length = b.bits(10) + 1; b.bits(3)
        val width = b.bits(6) + 1
        val lonAcc = b.bits(9) - 160; b.bits(7)
        val curv = b.bits(11) - 1023; b.bits(3)
        b.bit(); b.bits(2)                // CurvatureCalculationMode (extensible enum)
        val yaw = b.bits(16) - 32766
        b.bit(); b.bits(4)                // YawRateConfidence (extensible enum)
        val accCtl = if (accPres) rev(b.bits(7), 7) else null
        val lane = if (lanePres) b.bits(4) - 1 else null
        val steer = if (steerPres) (b.bits(10) - 511).also { b.bits(7) } else null
        val latA = if (latPres) (b.bits(9) - 160).also { b.bits(7) } else null
        val vertA = if (vertPres) (b.bits(9) - 160).also { b.bits(7) } else null
        if (perfPres) b.bits(3)
        if (tollPres) {
            b.bit()                       // ext bit
            val idPres = b.bit() == 1
            b.bits(31); b.bits(32)
            if (idPres) b.bits(27)
        }

        var role: Int? = null
        var lights: Int? = null
        var path: Int? = null
        if (lfPresent) {
            // LowFrequencyContainer CHOICE: ext bit, single root alternative (no index bits)
            if (b.bit() == 0) {
                role = b.bits(4)
                lights = rev(b.bits(8), 8)
                path = b.bits(6)          // pathHistory SIZE(0..40): length only
            }
        }

        return Cam(
            stationId = sid,
            generationDeltaTime = gdt,
            stationType = stationType,
            altitudeM = altOrNull(alt),
            headingDeg = heading.takeIf { it <= 3600 }?.let { it / 10.0 },
            speedMps = speed.takeIf { it < 16383 }?.let { it / 100.0 },
            driveDirection = drive,
            lengthM = length.takeIf { it < 1022 }?.let { it / 10.0 },
            widthM = width.takeIf { it < 61 }?.let { it / 10.0 },
            longAccel = lonAcc.takeIf { it != 161 }?.let { it / 10.0 },
            curvature = curv.takeIf { it != 1023 },
            yawRate = yaw.takeIf { it != 32767 }?.let { it / 100.0 },
            accelControl = accCtl,
            lanePosition = lane,
            steeringDeg = steer?.takeIf { it != 512 }?.let { it * 1.5 },
            latAccel = latA?.takeIf { it != 161 }?.let { it / 10.0 },
            vertAccel = vertA?.takeIf { it != 161 }?.let { it / 10.0 },
            role = role,
            lights = lights,
            pathPoints = path,
            rsu = false,
        )
    }

    /** BIT STRING: the first bit on the wire is named bit 0 → make it the LSB. */
    private fun rev(v: Int, n: Int): Int {
        var r = 0
        for (i in 0 until n) if (v and (1 shl (n - 1 - i)) != 0) r = r or (1 shl i)
        return r
    }

    private fun altOrNull(alt: Int): Double? = if (alt == 800001) null else alt / 100.0

    /** UPER bit reader, MSB first. */
    private class Bits(val b: ByteArray, startByte: Int) {
        var pos = startByte * 8

        fun bit(): Int {
            if (pos / 8 >= b.size) throw IndexOutOfBoundsException()
            return ((b[pos / 8].toInt() ushr (7 - pos % 8)) and 1).also { pos++ }
        }

        fun bits(n: Int): Int = (0 until n).fold(0) { acc, _ -> (acc shl 1) or bit() }
    }
}
