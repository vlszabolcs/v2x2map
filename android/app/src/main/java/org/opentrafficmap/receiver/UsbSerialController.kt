package org.opentrafficmap.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * Wraps `usb-serial-for-android`. Auto-reconnects with exponential backoff
 * (1 s → 2 s → 4 s → 8 s → stays at 8 s) whenever the cable is pulled or a
 * read error occurs. Call stop() to cancel reconnects and release all resources.
 */
class UsbSerialController(
    private val context: Context,
    private val onBytes: (ByteArray) -> Unit,
    private val onState: (State, String?) -> Unit,
) {
    enum class State { IDLE, REQUESTING, CONNECTED, ERROR }

    private val tag = "UsbSerialController"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var stopped         = false
    @Volatile private var receiverReg     = false
    @Volatile private var deviceLabel: String? = null
    private var retryCount = 0

    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val pendingFlags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private val reconnectRunnable = Runnable { if (!stopped) tryConnect() }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device  = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (granted && device != null) openDevice(device)
            else onState(State.ERROR, context.getString(R.string.usb_perm_denied))
        }
    }

    fun start() {
        stopped    = false
        retryCount = 0
        ensureReceiverRegistered()
        tryConnect()
    }

    private fun tryConnect() {
        val devices = findDrivers()
        if (devices.isEmpty()) {
            val delaySec = backoffSec(retryCount)
            onState(State.ERROR, context.getString(R.string.usb_reconnecting, retryCount + 1, delaySec))
            scheduleReconnect()
            return
        }
        val driver = devices.first()
        val device = driver.device
        if (usbManager.hasPermission(device)) {
            openDevice(device, driver)
        } else {
            onState(State.REQUESTING, context.getString(R.string.usb_perm_request))
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(permissionAction).setPackage(context.packageName), pendingFlags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun scheduleReconnect() {
        val delayMs = backoffSec(retryCount) * 1_000L
        retryCount++
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun backoffSec(attempt: Int) = minOf(1 shl attempt.coerceAtMost(3), 8)

    private fun ensureReceiverRegistered() {
        if (receiverReg) return
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            context.registerReceiver(permissionReceiver, filter)
        receiverReg = true
    }

    /**
     * Returns all detected serial drivers: default prober PLUS Espressif USB-Serial-JTAG.
     */
    private fun findDrivers(): List<UsbSerialDriver> {
        val defaultHits = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (defaultHits.isNotEmpty()) return defaultHits
        val espressifTable = ProbeTable().apply {
            addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
            addProduct(0x303A, 0x0002, CdcAcmSerialDriver::class.java)
            addProduct(0x303A, 0x8001, CdcAcmSerialDriver::class.java)
        }
        return UsbSerialProber(espressifTable).findAllDrivers(usbManager)
    }

    private fun openDevice(device: UsbDevice, drv: UsbSerialDriver? = null) {
        try {
            val driver = drv
                ?: UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: run { onState(State.ERROR, context.getString(R.string.usb_no_driver)); scheduleReconnect(); return }
            val connection = usbManager.openDevice(driver.device)
                ?: run { onState(State.ERROR, context.getString(R.string.usb_open_failed)); scheduleReconnect(); return }
            val p = driver.ports.first()
            p.open(connection)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.dtr = false; p.rts = false } catch (e: Exception) {
                Log.w(tag, "DTR/RTS nicht unterstützt", e)
            }

            val mgr = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) { onBytes(data) }
                override fun onRunError(e: Exception) {
                    closeQuietly()
                    onState(State.ERROR, context.getString(R.string.usb_conn_lost))
                    scheduleReconnect()
                }
            })
            executor.submit(mgr)
            port = p
            ioManager = mgr
            retryCount = 0  // reset backoff on successful connect
            deviceLabel = "${driver.javaClass.simpleName} VID=%04X PID=%04X"
                .format(device.vendorId, device.productId)
            onState(State.CONNECTED, deviceLabel)
        } catch (e: Exception) {
            onState(State.ERROR, e.message ?: e.javaClass.simpleName)
            scheduleReconnect()
        }
    }

    /** Send one command line to the receiver (appends the newline). */
    fun writeLine(line: String): Boolean {
        val p = port ?: return false
        return try { p.write((line + "\n").toByteArray(Charsets.US_ASCII), 500); true }
        catch (e: Exception) { Log.w(tag, "write failed", e); false }
    }

    fun resetDevice() {
        try { port?.let { it.dtr = false; it.rts = true; Thread.sleep(100); it.rts = false } }
        catch (e: Exception) { Log.w(tag, "reset failed", e) }
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacks(reconnectRunnable)
        closeQuietly()
        if (receiverReg) {
            try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
            receiverReg = false
        }
        onState(State.IDLE, null)
    }

    private fun closeQuietly() {
        try { ioManager?.stop() } catch (_: Exception) {}
        try { port?.close() }     catch (_: Exception) {}
        ioManager = null
        port = null
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, clazz)
    else getParcelableExtra(name)
