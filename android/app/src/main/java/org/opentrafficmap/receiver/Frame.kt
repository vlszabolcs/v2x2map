package org.opentrafficmap.receiver

/**
 * One ITS-G5 sniffer frame as it arrived from the ESP32-C5 over USB or BLE,
 * with whatever the lightweight [ItsG5Decoder] could pull out of the payload.
 */
data class Frame(
    val seq: Long,
    val sec: Long,
    val usec: Long,
    val payload: ByteArray,
    val etherType: Int?,
    val msgType: ItsG5Decoder.MsgType,
    val stationId: Long?,
    val stationType: Int? = null,   // ETSI StationType (5-bit: 0=unknown 5=passengerCar 15=RSU)
    val latLon: Pair<Double, Double>?,
    val headingDeg: Double?,
    val speedMps: Double?,
    val spatPhase: SpatTemParser.Phase? = null,
    val secured: Boolean? = null,   // GN Basic Header NH==2 → IEEE 1609.2 signed packet
    val denmCause: DenmParser.Cause? = null,
    val rssi: Int? = null,          // dBm, from the receiver's ITSR record (null on old firmware)
) {
    val len: Int get() = payload.size

    /** Transmitter address (802.11 addr2) as aa:bb:cc:dd:ee:ff, if present. */
    val srcMac: String? get() =
        if (payload.size < 16) null
        else (10 until 16).joinToString(":") { "%02x".format(payload[it].toInt() and 0xFF) }

    fun hexPreview(maxBytes: Int = 32): String {
        val n = minOf(payload.size, maxBytes)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            val v = payload[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = seq.hashCode()
}
