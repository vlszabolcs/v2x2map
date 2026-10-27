package org.opentrafficmap.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.opentrafficmap.receiver.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.LinkedList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FrameLogAdapter
    private val reader = FrameReader()
    private var usb: UsbSerialController? = null
    private var bt: BluetoothController? = null
    private val mqttBridges = mutableListOf<MqttBridge>()
    private lateinit var recorder: FrameRecorder
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var markers: MarkerLayer
    private lateinit var geiger: GeigerCounter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rateWindow = LinkedList<Long>()
    private var totalFrames = 0L
    @Volatile private var lastCycle: BluetoothController.CycleConfig? = null
    @Volatile private var lastSpeedMps: Float = 0f
    @Volatile private var followEnabled: Boolean = false
    @Volatile private var spatLightEnabled: Boolean = true
    private lateinit var sheet: BottomSheetBehavior<View>

    // Connection state shown in the top pill
    private var usbState = UsbSerialController.State.IDLE
    private var usbInfo: String? = null
    private var btState = BluetoothController.State.IDLE
    private var btInfo: String? = null
    private var recStartMs = 0L
    private var lastPosSentMs = 0L
    private var pillCompact = false
    private lateinit var replay: ReplayController

    // Own GPS track
    private val ownTrackPoints = mutableListOf<GeoPoint>()
    private var ownTrackLine: Polyline? = null
    @Volatile private var ownTrackEnabled: Boolean = false

    // Compass / bearing-up mode
    @Volatile private var compassMode: Boolean = false

    // Frame log: buffer fills on the main thread, drains every LOG_REFRESH_MS
    private val pendingLogFrames = mutableListOf<Frame>()

    private data class SpatRsu(
        val lat: Double, val lon: Double,
        val phase: SpatTemParser.Phase, val lastSeenMs: Long
    )
    private val spatRsus = mutableMapOf<Long, SpatRsu>()
    private var lastLocation: Location? = null

    // ---------------------------------------------------------------- launchers

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r -> if (r.values.all { it }) startBt()
             else Toast.makeText(this, "BT: permission denied", Toast.LENGTH_SHORT).show() }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startReceiverService() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { centreOnMyLocation(); startLocationUpdates() }
        else toast(getString(R.string.loc_perm_denied))
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastSpeedMps = if (loc.hasSpeed()) loc.speed else 0f
            lastLocation = loc
            // The phone is the receiver's position source while connected
            val now = System.currentTimeMillis()
            if (DeviceLink.connected && now - lastPosSentMs >= 1000) {
                lastPosSentMs = now
                DeviceLink.sendAll(DeviceLink.positionCommand(loc))
            }
            if (followEnabled) followLocation(loc)
            if (ownTrackEnabled && ::binding.isInitialized)
                runOnUiThread { addOwnTrackPoint(GeoPoint(loc.latitude, loc.longitude)) }
            if (::binding.isInitialized) {
                adapter.userLocation = loc          // for distance column
                runOnUiThread { updateSpeedOverlay(loc) }
            }
            if (compassMode && loc.hasBearing() && ::binding.isInitialized)
                runOnUiThread { binding.map.mapOrientation = -loc.bearing }
            updateSpatLight(loc)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.legalAccepted(this)) { showLegalDialog(); return }
        spatLightEnabled = Prefs.spatLightEnabled(this)
        setupUi()
    }

    private fun showLegalDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.legal_title)
            .setMessage(R.string.legal_body)
            .setCancelable(false)
            .setPositiveButton(R.string.legal_accept) { _, _ -> Prefs.setLegalAccepted(this, true); setupUi() }
            .setNegativeButton(R.string.legal_quit) { _, _ -> finish() }
            .show()
    }

    private fun setupUi() {
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().tileFileSystemCacheMaxBytes  = 600L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 500L * 1024 * 1024

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        recorder = FrameRecorder(this)
        fused    = LocationServices.getFusedLocationProviderClient(this)
        followEnabled   = Prefs.followEnabled(this)
        ownTrackEnabled = Prefs.ownTrackEnabled(this)
        compassMode     = Prefs.compassMode(this)
        markers = MarkerLayer(binding.map, this)
        geiger  = GeigerCounter(this)
        if (Prefs.audioFeedback(this)) geiger.start()

        sheet = BottomSheetBehavior.from(binding.sheet)
        sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        binding.sheetPeek.setOnClickListener {
            sheet.state = if (sheet.state == BottomSheetBehavior.STATE_COLLAPSED)
                BottomSheetBehavior.STATE_HALF_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sheet.state != BottomSheetBehavior.STATE_COLLAPSED) {
                    sheet.state = BottomSheetBehavior.STATE_COLLAPSED
                } else if (replay.active) {
                    replay.stop()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.map.setTileSource(tileSourceForKey(Prefs.mapLayer(this)))
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(6.0)
        binding.map.controller.setCenter(GeoPoint(51.0, 10.0))
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.map)
        binding.map.overlays.add(locationOverlay)

        reader.onJson = DeviceLink::dispatchJson
        reader.onLogChunk = DeviceLink::dispatchChunk
        LogReplay.listener = { f -> runOnUiThread { replayLog(f) } }

        adapter = FrameLogAdapter(this) { f -> FrameDetailSheet.show(this, f) }
        binding.log.layoutManager = LinearLayoutManager(this)
        binding.log.adapter = adapter
        replay = ReplayController(this, binding, markers, adapter,
            clear = ::clearMapFrames,
            feed = { frames -> handleFrames(frames, live = false) })

        binding.connPill.setOnClickListener { showConnectionMenu() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.fabLocate.setOnClickListener { onLocateClick() }
        binding.fabLayers.setOnClickListener { showLayerPicker() }
        binding.fabCompass.setOnClickListener { toggleCompassMode() }
        setupFilterChips()
        renderConnPill()

        // Reflect saved compass mode on FAB immediately
        applyCompassFabTint()

        applyKeepScreenOn()
        wireSettingsBus()
        if (Prefs.mqttEnabled(this) && Prefs.mqttBrokerList(this).isNotEmpty()) startMqtt()
        if (followEnabled || ownTrackEnabled || compassMode) ensureLocation()
        mainHandler.post(rateRefresh)
        mainHandler.post(logRefresh)
    }

    // ------------------------------------------ Filter chips

    private class FilterRow(
        val row: View,
        val box: com.google.android.material.checkbox.MaterialCheckBox,
        val count: android.widget.TextView,
        val type: ItsG5Decoder.MsgType,
    )

    private val filterRows by lazy {
        listOf(
            FilterRow(binding.filterCamRow,  binding.filterCam,  binding.countCam,  ItsG5Decoder.MsgType.CAM),
            FilterRow(binding.filterDenmRow, binding.filterDenm, binding.countDenm, ItsG5Decoder.MsgType.DENM),
            FilterRow(binding.filterSpatRow, binding.filterSpat, binding.countSpat, ItsG5Decoder.MsgType.SPATEM),
            FilterRow(binding.filterMapRow,  binding.filterMap,  binding.countMap,  ItsG5Decoder.MsgType.MAPEM),
        )
    }

    private fun setupFilterChips() {
        binding.filterButton.setOnClickListener { setFilterMenuOpen(binding.filterMenu.visibility != View.VISIBLE) }
        for (r in filterRows) {
            r.row.setOnClickListener { r.box.toggle() }
            r.box.setOnCheckedChangeListener { _, on ->
                markers.setTypeVisible(r.type, on)
                updateFilterLabel()
            }
        }
        // One-finger zoom (double tap + drag up/down); any touch on the map
        // also closes the filter menu
        binding.map.setOnTouchListener(MapZoomGesture(binding.map) {
            if (binding.filterMenu.visibility == View.VISIBLE) setFilterMenuOpen(false)
        })
        updateFilterLabel()
    }

    private fun setFilterMenuOpen(open: Boolean) {
        android.transition.TransitionManager.beginDelayedTransition(binding.topBar)
        binding.filterMenu.visibility = if (open) View.VISIBLE else View.GONE
        binding.filterButton.isSelected = open
    }

    private fun updateFilterLabel() {
        val on = filterRows.count { it.box.isChecked }
        binding.filterLabel.text = getString(R.string.filter_label, on, filterRows.size)
        binding.filterButton.contentDescription =
            getString(R.string.filter_desc, on, filterRows.size)
    }

    private fun updateChipCounts(counts: Map<ItsG5Decoder.MsgType, Int>) {
        for (r in filterRows) r.count.text = (counts[r.type] ?: 0).toString()
    }

    // ------------------------------------------ Connection pill

    private fun showConnectionMenu() {
        val usbOn = usb != null
        val btOn  = bt != null
        val labels = arrayOf(
            getString(if (usbOn) R.string.conn_action_usb_disconnect else R.string.conn_action_usb_connect),
            getString(if (btOn)  R.string.conn_action_bt_disconnect  else R.string.conn_action_bt_connect),
            getString(R.string.logs_title),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_choose_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> toggleUsb()
                    1 -> toggleBt()
                    else -> startActivity(Intent(this, DeviceLogsActivity::class.java))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Ordered by how "alive" a link is: the livelier link is shown in the pill. */
    private enum class Health { IDLE, ERROR, BUSY, OK }

    private fun renderConnPill(rate: Int = rateWindow.size) {
        if (!::binding.isInitialized) return
        val usbHealth = when (usbState) {
            UsbSerialController.State.IDLE       -> Health.IDLE
            UsbSerialController.State.REQUESTING -> Health.BUSY
            UsbSerialController.State.CONNECTED  -> Health.OK
            UsbSerialController.State.ERROR      -> Health.ERROR
        }
        val btHealth = when (btState) {
            BluetoothController.State.IDLE       -> Health.IDLE
            BluetoothController.State.SCANNING,
            BluetoothController.State.CONNECTING -> Health.BUSY
            BluetoothController.State.CONNECTED  -> Health.OK
            BluetoothController.State.ERROR      -> Health.ERROR
        }
        // The more "alive" link wins the pill
        val useUsb = usbHealth != Health.IDLE && usbHealth.ordinal >= btHealth.ordinal
        val health = if (useUsb) usbHealth else btHealth
        val via    = getString(if (useUsb) R.string.conn_usb else R.string.conn_bt)
        val info   = (if (useUsb) usbInfo else btInfo)?.trim()?.takeIf { it.isNotEmpty() }

        val (title, sub) = when (health) {
            Health.IDLE  -> getString(R.string.conn_none) to getString(R.string.conn_none_sub)
            Health.BUSY  -> getString(R.string.conn_connecting, via) to (info ?: "")
            Health.OK    -> getString(R.string.conn_title_fmt, info ?: getString(R.string.conn_receiver), via) to
                            getString(R.string.conn_rate, rate, totalFrames.toInt())
            Health.ERROR -> getString(R.string.conn_error, via) to (info ?: "")
        }
        // Once connected the pill shrinks to a circle: green dot + link type
        val compact = health == Health.OK
        if (compact != pillCompact) {
            pillCompact = compact
            android.transition.TransitionManager.beginDelayedTransition(binding.topBar)
            val lp = binding.connPill.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.width  = if (compact) (52 * resources.displayMetrics.density).toInt() else 0
            lp.weight = if (compact) 0f else 1f
            binding.connPill.layoutParams = lp
            binding.connFull.visibility    = if (compact) View.GONE else View.VISIBLE
            binding.connCompact.visibility = if (compact) View.VISIBLE else View.GONE
            binding.connSpacer.visibility  = if (compact) View.VISIBLE else View.GONE
        }
        val shortVia = getString(if (useUsb) R.string.conn_usb else R.string.conn_ble_short)
        binding.connCompactLabel.text = shortVia
        binding.connCompactDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_ok))
        binding.connPill.contentDescription =
            if (compact) getString(R.string.conn_compact_desc, shortVia, rate) else getString(R.string.conn_pill_desc)

        binding.connTitle.text = title
        binding.connSub.text   = sub
        binding.connSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        binding.connDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, when (health) {
            Health.IDLE  -> R.color.status_idle
            Health.BUSY  -> R.color.status_busy
            Health.OK    -> R.color.status_ok
            Health.ERROR -> R.color.status_error
        }))

        if (::recorder.isInitialized && recorder.isRecording) {
            if (recStartMs == 0L) recStartMs = System.currentTimeMillis()
            val sec = (System.currentTimeMillis() - recStartMs) / 1000
            binding.recChip.text = getString(R.string.rec_chip, "%02d:%02d".format(sec / 60, sec % 60))
            binding.recChip.visibility = View.VISIBLE
            // In the circle there is no room for the timer: a red ring instead
            binding.connPill.strokeColor = if (pillCompact)
                ContextCompat.getColor(this, R.color.status_error)
            else MaterialColors.getColor(binding.connPill, com.google.android.material.R.attr.colorOutlineVariant)
        } else {
            recStartMs = 0L
            binding.recChip.visibility = View.GONE
            binding.connPill.strokeColor = if (pillCompact)
                (ContextCompat.getColor(this, R.color.status_ok) and 0x00FFFFFF) or 0x8C000000.toInt()
            else MaterialColors.getColor(binding.connPill, com.google.android.material.R.attr.colorOutlineVariant)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        binding.map.onResume()
        applyMapLayer()
        applyKeepScreenOn()
        LogReplay.pending?.let { LogReplay.pending = null; replayLog(it) }
        if (followEnabled || ownTrackEnabled || compassMode) ensureLocation()
    }

    override fun onPause() {
        super.onPause()
        if (!::binding.isInitialized) return
        binding.map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ReceiverForegroundService::class.java))
        usb?.stop(); bt?.stop(); stopMqtt()
        if (::recorder.isInitialized) recorder.stop()
        if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation()
        if (::fused.isInitialized) stopLocationUpdates()
        if (::geiger.isInitialized) geiger.stop()
        SettingsBus.liveRecorder = null
        SettingsBus.liveBtController = null
        LogReplay.listener = null
        if (::replay.isInitialized) replay.stop()
        DeviceLink.usbSender = null
        DeviceLink.bleSender = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ----------------------------------------------------------- Settings bus

    private fun wireSettingsBus() {
        SettingsBus.liveRecorder = recorder
        SettingsBus.onFollowChanged = SettingsBus.OnFollowChanged { on ->
            followEnabled = on
            invalidateOptionsMenu()
            if (on || ownTrackEnabled || compassMode) ensureLocation() else stopLocationUpdates()
        }
        SettingsBus.onMqttToggle = SettingsBus.OnMqttToggle { on ->
            stopMqtt(); if (on) startMqtt()
        }
        SettingsBus.onRecordingToggle = SettingsBus.OnRecordingToggle { toggleRecording() }
        SettingsBus.onMapDownload = SettingsBus.OnMapDownload { downloadVisibleMap() }
        SettingsBus.onAudioChanged = SettingsBus.OnAudioChanged { on ->
            if (on) geiger.start() else geiger.stop()
        }
        SettingsBus.onSpatLightChanged = SettingsBus.OnSpatLightChanged { on ->
            spatLightEnabled = on
            if (!on) binding.spatLight.visibility = View.GONE
            else lastLocation?.let { updateSpatLight(it) }
        }
        SettingsBus.onCycleApply = SettingsBus.OnCycleApply { c ->
            lastCycle = c; SettingsBus.liveCycleConfig = c
            val ok = bt?.writeConfig(c) ?: false
            toast(getString(if (ok) R.string.cycle_saved else R.string.cycle_not_connected))
        }
        SettingsBus.onKeepScreenOnChanged = SettingsBus.OnKeepScreenOnChanged { applyKeepScreenOn() }
        SettingsBus.onDarkModeChanged = SettingsBus.OnDarkModeChanged { on ->
            AppCompatDelegate.setDefaultNightMode(
                if (on) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
        SettingsBus.onOwnTrackChanged = SettingsBus.OnOwnTrackChanged { on ->
            ownTrackEnabled = on
            if (!on) {
                ownTrackPoints.clear()
                ownTrackLine?.let { binding.map.overlays.remove(it) }
                ownTrackLine = null
                binding.map.invalidate()
            }
            if (on) ensureLocation()
        }
        SettingsBus.onResetAll = SettingsBus.OnResetAll { resetAll() }
    }

    // ------------------------------------------ Keep screen on

    private fun applyKeepScreenOn() {
        if (!::binding.isInitialized) return
        if (Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------ Reset

    private fun resetAll() {
        if (::replay.isInitialized && replay.active) replay.stop()
        clearMapFrames()
        rateWindow.clear()
        spatRsus.clear()
        ownTrackPoints.clear()
        ownTrackLine?.let { binding.map.overlays.remove(it) }
        ownTrackLine = null
        binding.logStats.text    = getString(R.string.log_stats, 0, 0)
        binding.statFrames.text  = "0"
        binding.statStations.text = "0"
        binding.statRate.text    = "0"
        binding.lastFrame.text   = getString(R.string.empty_log)
        binding.activityBars.clear()
        updateChipCounts(emptyMap())
        binding.emptyLog.visibility  = View.VISIBLE
        binding.spatLight.visibility = View.GONE
        binding.map.invalidate()
    }

    /** Markers, station list and frame counters (log replay rebuilds these on every seek). */
    private fun clearMapFrames() {
        pendingLogFrames.clear()
        if (::markers.isInitialized) markers.clear()
        adapter.clear()
        totalFrames = 0
        binding.statFrames.text = "0"
        binding.statStations.text = "0"
    }

    // ------------------------------------------ Map layers

    private fun showLayerPicker() {
        val layers = MapLayers.visible(this)
        val current = layers.indexOfFirst { it.key == Prefs.mapLayer(this) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_layer_title)
            .setSingleChoiceItems(layers.map { it.name }.toTypedArray(), current) { dlg, which ->
                val key = layers[which].key
                Prefs.setMapLayer(this, key)
                applyMapLayer()
                dlg.dismiss()
            }
            .setNeutralButton(R.string.layers_manage) { _, _ ->
                startActivity(Intent(this, MapLayersActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** (Re)apply the selected base layer; also picks up edits made in the
     *  layer manager. Falls back to the first visible layer. */
    private fun applyMapLayer() {
        val visible = MapLayers.visible(this)
        var key = Prefs.mapLayer(this)
        if (visible.none { it.key == key }) {
            key = visible.firstOrNull()?.key ?: "MAPNIK"
            Prefs.setMapLayer(this, key)
        }
        val src = MapLayers.tileSource(this, key)
        if (binding.map.tileProvider.tileSource.name() != src.name()) binding.map.setTileSource(src)
    }

    private fun tileSourceForKey(key: String): ITileSource = MapLayers.tileSource(this, key)

    // ------------------------------------------ Compass / bearing-up

    private fun toggleCompassMode() {
        compassMode = !compassMode
        Prefs.setCompassMode(this, compassMode)
        applyCompassFabTint()
        if (!compassMode) {
            binding.map.mapOrientation = 0f  // reset to North-up
        } else {
            ensureLocation()
        }
        toast(getString(if (compassMode) R.string.compass_bearing_up else R.string.compass_north_up))
    }

    private fun applyCompassFabTint() {
        if (!::binding.isInitialized) return
        val attr = if (compassMode) androidx.appcompat.R.attr.colorPrimary
                   else com.google.android.material.R.attr.colorOnSurface
        binding.fabCompass.imageTintList =
            ColorStateList.valueOf(MaterialColors.getColor(binding.fabCompass, attr))
        binding.fabCompass.contentDescription =
            getString(if (compassMode) R.string.compass_bearing_up else R.string.compass_north_up)
    }

    // ------------------------------------------ Speed overlay

    private fun updateSpeedOverlay(loc: Location) {
        val speedKph = loc.speed * 3.6f
        val bearingText = if (loc.hasBearing()) formatBearing(loc.bearing) else "—"
        binding.speedValue.text = "%.0f".format(speedKph)
        binding.speedMeta.text  = getString(R.string.speed_meta, bearingText)
        binding.speedOverlay.visibility = View.VISIBLE
    }

    private fun formatBearing(deg: Float): String {
        val card = resources.getStringArray(R.array.compass_points)
        val idx = ((deg / 45f + 0.5f).toInt() % 8).coerceIn(0, 7)
        return "%s %.0f°".format(card[idx], deg)
    }

    // ------------------------------------------ Own track

    private fun addOwnTrackPoint(pt: GeoPoint) {
        ownTrackPoints.add(pt)
        if (ownTrackPoints.size > OWN_TRACK_MAX) ownTrackPoints.removeAt(0)
        val line = ownTrackLine ?: Polyline().also { poly ->
            poly.outlinePaint.color       = 0xFF2196F3.toInt()
            poly.outlinePaint.strokeWidth = 5f
            poly.outlinePaint.alpha       = 200
            binding.map.overlays.add(0, poly)
            ownTrackLine = poly
        }
        line.setPoints(ownTrackPoints)
        binding.map.invalidate()
    }

    // ------------------------------------------------------- Foreground service

    private fun ensureServiceRunning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReceiverService()
        }
    }

    private fun startReceiverService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    // -------------------------------------------------------------- USB

    private fun toggleUsb() {
        if (usb == null) {
            ensureServiceRunning()
            usb = UsbSerialController(this, ::onSerialBytes, ::onUsbState).also { it.start() }
        } else {
            usb?.stop(); usb = null
            onUsbState(UsbSerialController.State.IDLE, null)
        }
    }

    private fun onUsbState(state: UsbSerialController.State, info: String?) = runOnUiThread {
        val wasConnected = usbState == UsbSerialController.State.CONNECTED
        usbState = state
        usbInfo  = info
        if (state == UsbSerialController.State.CONNECTED) {
            DeviceLink.usbSender = { cmd -> usb?.writeLine(cmd) ?: false }
            if (!wasConnected) {
                DeviceLink.usbSender?.invoke(DeviceLink.timeCommand())
                ensureLocation()
            }
        } else {
            DeviceLink.usbSender = null
        }
        renderConnPill()
    }

    // --------------------------------------------------------------- BT

    private fun toggleBt() {
        if (bt == null) {
            val missing = BluetoothController.runtimePermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) { btPermissionLauncher.launch(missing.toTypedArray()); return }
            startBt()
        } else {
            bt?.stop(); bt = null
            SettingsBus.liveBtController = null
            onBtState(BluetoothController.State.IDLE, null)
        }
    }

    private fun startBt() {
        ensureServiceRunning()
        bt = BluetoothController(
            context  = this,
            onBytes  = ::onSerialBytes,
            onState  = ::onBtState,
            onConfig = { lastCycle = it; SettingsBus.liveCycleConfig = it },
        ).also { it.start(); SettingsBus.liveBtController = it }
    }

    private fun onBtState(state: BluetoothController.State, info: String?) = runOnUiThread {
        val wasConnected = btState == BluetoothController.State.CONNECTED
        btState = state
        btInfo  = info
        if (state == BluetoothController.State.CONNECTED) {
            DeviceLink.bleSender = { cmd -> bt?.sendCommand(cmd) ?: false }
            if (!wasConnected) ensureLocation()
        } else {
            DeviceLink.bleSender = null
        }
        renderConnPill()
    }

    // ---------------------------------------------------------- Location

    private fun onLocateClick() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        centreOnMyLocation()
    }

    private fun ensureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        startLocationUpdates()
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L).build()
        try { fused.requestLocationUpdates(req, locationCallback, mainLooper) }
        catch (_: SecurityException) {}
    }

    private fun stopLocationUpdates() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    private fun centreOnMyLocation() {
        if (!locationOverlay.isMyLocationEnabled) locationOverlay.enableMyLocation()
        val now = locationOverlay.myLocation
        if (now != null) {
            binding.map.controller.animateTo(now)
            binding.map.controller.setZoom(15.0)
        } else {
            locationOverlay.runOnFirstFix {
                runOnUiThread {
                    binding.map.controller.animateTo(locationOverlay.myLocation)
                    binding.map.controller.setZoom(15.0)
                }
            }
            toast(getString(R.string.loc_unknown))
        }
    }

    /** Pan only — zoom is never changed here. */
    private fun followLocation(loc: Location) {
        binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
    }

    // -------------------------------------------------------- Recording

    private fun toggleRecording(): String = if (recorder.isRecording) {
        recStartMs = 0L
        val stopped = recorder.stop()
        getString(R.string.rec_stopped, recorder.frameCount, stopped?.absolutePath ?: "?")
    } else {
        val f = recorder.start()
        recStartMs = System.currentTimeMillis()
        if (f != null) getString(R.string.rec_started, f.absolutePath) else "recording start failed"
    }

    // ------------------------------------------------------- Map cache

    private fun downloadVisibleMap() {
        val src = binding.map.tileProvider.tileSource
            as? org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
        if (src == null || !src.tileSourcePolicy.acceptsBulkDownload()) {
            toast(getString(R.string.map_dl_unsupported)); return
        }
        val mgr     = CacheManager(binding.map)
        val box     = binding.map.boundingBox
        val zoomMin = binding.map.zoomLevelDouble.toInt()
        val zoomMax = (zoomMin + 2).coerceAtMost(src.maximumZoomLevel)
        val total   = mgr.possibleTilesInArea(box, zoomMin, zoomMax)
        toast(getString(R.string.map_dl_started))
        try {
            mgr.downloadAreaAsync(this, box, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete()          = runOnUiThread { toast(getString(R.string.map_dl_done)) }
                override fun onTaskFailed(e: Int)      = runOnUiThread { toast("Map dl error: $e") }
                override fun updateProgress(p: Int, cz: Int, zn: Int, zx: Int) {
                    if (p % 50 == 0) runOnUiThread { toast(getString(R.string.map_dl_progress, p, total)) }
                }
                override fun downloadStarted() {}
                override fun setPossibleTilesInArea(t: Int) {}
            })
        } catch (e: Exception) {
            toast("Map dl: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ MQTT

    private fun startMqtt() {
        stopMqtt()
        val nodeId = Prefs.nodeId(this).trim().ifEmpty { getString(R.string.default_node_id) }
        Prefs.mqttBrokerList(this).filter { it.isNotBlank() }.forEach { url ->
            MqttBridge(nodeId, normaliseBroker(url.trim())).also {
                it.start()
                mqttBridges.add(it)
            }
        }
    }

    private fun stopMqtt() {
        mqttBridges.forEach { it.stop() }
        mqttBridges.clear()
    }

    private fun normaliseBroker(s: String): String = when {
        s.startsWith("mqtts://") -> "ssl://" + s.removePrefix("mqtts://")
        s.startsWith("mqtt://")  -> "tcp://" + s.removePrefix("mqtt://")
        s.startsWith("ssl://")   -> s
        s.startsWith("tcp://")   -> s
        else                      -> "ssl://$s"
    }

    // ---------------------------------------------------------- Frames

    private fun onSerialBytes(chunk: ByteArray) {
        val frames = synchronized(reader) { reader.feed(chunk) }
        if (frames.isEmpty()) return
        runOnUiThread { handleFrames(frames) }
    }

    private fun handleFrames(frames: List<Frame>, live: Boolean = true) {
        // While a log is replayed the map shows the log; live frames are
        // still recorded and forwarded
        val show = !(live && ::replay.isInitialized && replay.active)
        if (!show) {
            val mqttFilter = Prefs.mqttFilterTypes(this)
            for (f in frames) {
                geiger.click(f.msgType)
                if (f.msgType.name in mqttFilter) mqttBridges.forEach { it.publish(f.payload) }
                if (recorder.isRecording) recorder.append(f)
                rateWindow.add(System.currentTimeMillis())
                binding.activityBars.hit()
            }
            return
        }
        frames.lastOrNull()?.let { f ->
            val dist = adapter.formatDistance(f.latLon, lastLocation)
            val what = adapter.titleFor(f.msgType, f)
            binding.lastFrame.text = getString(R.string.last_frame_fmt, f.msgType.short,
                if (dist == "—") what else "$what · $dist")
        }
        val mqttFilter = Prefs.mqttFilterTypes(this)
        for (f in frames) {
            totalFrames++
            pendingLogFrames.add(f)          // buffered; drains in logRefresh
            markers.add(f)
            if (live) {
                geiger.click(f.msgType)
                if (f.msgType.name in mqttFilter) mqttBridges.forEach { it.publish(f.payload) }
                if (recorder.isRecording) recorder.append(f)
                rateWindow.add(System.currentTimeMillis())
                binding.activityBars.hit()
            }

            if (live && f.msgType == ItsG5Decoder.MsgType.SPATEM && f.latLon != null) {
                val (lat, lon) = f.latLon
                spatRsus[f.stationId ?: 0L] = SpatRsu(lat, lon,
                    f.spatPhase ?: SpatTemParser.Phase.UNKNOWN, System.currentTimeMillis())
                lastLocation?.let { updateSpatLight(it) }
            }
        }
    }

    private fun updateSpatLight(loc: Location) {
        if (!spatLightEnabled) return
        val now = System.currentTimeMillis()
        spatRsus.entries.removeAll { now - it.value.lastSeenMs > 30_000 }
        if (spatRsus.isEmpty()) { binding.spatLight.visibility = View.GONE; return }

        val results = FloatArray(2)
        val nearest = spatRsus.values.filter { rsu ->
            Location.distanceBetween(loc.latitude, loc.longitude, rsu.lat, rsu.lon, results)
            val dist = results[0]
            if (dist > 400f) return@filter false
            if (loc.hasBearing()) {
                val diff = Math.abs(((loc.bearing - results[1] + 180f + 360f) % 360f) - 180f)
                diff < 70f
            } else dist < 250f
        }.minByOrNull { rsu ->
            Location.distanceBetween(loc.latitude, loc.longitude, rsu.lat, rsu.lon, results)
            results[0]
        }

        if (nearest == null) { binding.spatLight.visibility = View.GONE; return }
        binding.spatLight.visibility = View.VISIBLE
        Location.distanceBetween(loc.latitude, loc.longitude, nearest.lat, nearest.lon, results)
        binding.spatDist.text  = getString(R.string.spat_signal_dist, "%.0f m".format(results[0]))
        binding.spatPhase.text = adapter.phaseLabel(nearest.phase)
        binding.spatPhase.setTextColor(adapter.phaseColor(nearest.phase))
        applyPhaseColors(nearest.phase)
    }

    private fun applyPhaseColors(phase: SpatTemParser.Phase) {
        fun tint(active: Boolean, rgb: Int): ColorStateList =
            ColorStateList.valueOf((if (active) 0xFF000000.toInt() else 0x38000000) or (rgb and 0xFFFFFF))
        binding.lightRed.backgroundTintList    = tint(phase == SpatTemParser.Phase.RED,    0xFF5C5C)
        binding.lightYellow.backgroundTintList = tint(phase == SpatTemParser.Phase.YELLOW, 0xFFC93D)
        binding.lightGreen.backgroundTintList  = tint(phase == SpatTemParser.Phase.GREEN,  0x3DDC97)
    }

    /** Drains frame buffer into the grouped adapter every 300 ms. */
    private val logRefresh = object : Runnable {
        override fun run() {
            if (pendingLogFrames.isNotEmpty()) {
                val batch = pendingLogFrames.toList()
                pendingLogFrames.clear()
                adapter.addFrames(batch)
                if (binding.emptyLog.visibility != View.GONE)
                    binding.emptyLog.visibility = View.GONE
            }
            mainHandler.postDelayed(this, LOG_REFRESH_MS)
        }
    }

    private val rateRefresh = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - 60_000
            while (rateWindow.isNotEmpty() && rateWindow.first() < cutoff) rateWindow.removeFirst()
            val rate = rateWindow.size
            binding.logStats.text = getString(R.string.log_stats, totalFrames.toInt(), rate)
            val counts = adapter.activeCounts()
            binding.statFrames.text   = String.format(java.util.Locale.ROOT, "%,d", totalFrames).replace(',', '\u202F')
            binding.statStations.text = counts.values.sum().toString()
            binding.statRate.text     = rate.toString()
            updateChipCounts(counts)
            binding.activityBars.tick()
            adapter.refreshAges()
            renderConnPill(rate)
            if (::markers.isInitialized) markers.prune()
            ReceiverForegroundService.instance?.updateStats(totalFrames.toInt(), rate)
            mainHandler.postDelayed(this, 1_000)
        }
    }

    // ------------------------------------------------------------ Log replay

    /** Open a log downloaded from the receiver's SD card in the replay bar. */
    private fun replayLog(f: java.io.File) {
        if (!::binding.isInitialized) { LogReplay.pending = f; return }
        Thread {
            val data = try {
                val p = LogTools.parse(f)
                ReplayController.Data(f, p, LogTools.coverage(p), LogTools.transmitters(p))
            } catch (e: Exception) { null }
            runOnUiThread {
                if (data == null) { toast(getString(R.string.logs_replay_failed)); return@runOnUiThread }
                replay.start(data)
                toast(getString(R.string.logs_replay_done, data.parsed.frames.size, f.name))
            }
        }.start()
    }

    fun toastLong(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val OWN_TRACK_MAX  = 2000
        private const val LOG_REFRESH_MS = 300L  // log drain interval
    }
}
