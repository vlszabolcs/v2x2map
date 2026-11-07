package org.opentrafficmap.receiver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.textfield.TextInputEditText
import org.opentrafficmap.receiver.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // --- Keep screen on -------------------------------------------------
        binding.swKeepScreenOn.isChecked = Prefs.keepScreenOn(this)
        binding.swKeepScreenOn.setOnCheckedChangeListener { _, c ->
            Prefs.setKeepScreenOn(this, c)
            SettingsBus.keepScreenOnChanged()
        }

        // --- Dark mode ------------------------------------------------------
        binding.swDarkMode.isChecked = Prefs.forceDarkMode(this)
        binding.swDarkMode.setOnCheckedChangeListener { _, c ->
            Prefs.setForceDarkMode(this, c)
            SettingsBus.darkModeChanged(c)
            AppCompatDelegate.setDefaultNightMode(
                if (c) AppCompatDelegate.MODE_NIGHT_YES
                else   AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
            recreate()
        }

        // --- Reset ----------------------------------------------------------
        binding.btnReset.setOnClickListener {
            SettingsBus.resetAll()
            Toast.makeText(this, getString(R.string.reset), Toast.LENGTH_SHORT).show()
        }

        // --- Auto-follow ----------------------------------------------------
        binding.swFollow.isChecked = Prefs.followEnabled(this)
        binding.swFollow.setOnCheckedChangeListener { _, c ->
            Prefs.setFollowEnabled(this, c)
            SettingsBus.followChanged(c)
        }

        // --- Geiger-counter audio -------------------------------------------
        binding.swAudio.isChecked = Prefs.audioFeedback(this)
        binding.swAudio.setOnCheckedChangeListener { _, c ->
            Prefs.setAudioFeedback(this, c)
            SettingsBus.audioChanged(c)
        }

        // --- SPATEM traffic light -------------------------------------------
        binding.swSpatLight.isChecked = Prefs.spatLightEnabled(this)
        binding.swSpatLight.setOnCheckedChangeListener { _, c ->
            Prefs.setSpatLightEnabled(this, c)
            SettingsBus.spatLightChanged(c)
        }

        // --- MQTT -----------------------------------------------------------
        loadBrokerRows()
        binding.btnAddBroker.setOnClickListener { addBrokerRow("") }

        binding.edNodeId.setText(Prefs.nodeId(this))
        binding.swMqtt.isChecked = Prefs.mqttEnabled(this)
        binding.swMqtt.setOnCheckedChangeListener { _, c ->
            saveBrokerList()
            Prefs.setNodeId(this, binding.edNodeId.text.toString().trim())
            Prefs.setMqttEnabled(this, c)
            SettingsBus.mqttToggle(c)
        }

        // --- MQTT type filter -----------------------------------------------
        binding.btnMqttFilter.setOnClickListener { showMqttFilterDialog() }

        // --- Recording ------------------------------------------------------
        renderRecordingState()
        binding.btnRecord.setOnClickListener {
            val msg = SettingsBus.recordingToggle()
            Toast.makeText(this, msg ?: getString(R.string.rec_status_idle), Toast.LENGTH_LONG).show()
            renderRecordingState()
        }

        binding.btnRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        binding.swAutoRecord.isChecked = Prefs.autoRecord(this)
        binding.swAutoRecord.setOnCheckedChangeListener { _, c -> Prefs.setAutoRecord(this, c) }

        binding.swNearby.isChecked = Prefs.nearbyNotify(this)
        binding.swNearby.setOnCheckedChangeListener { _, c ->
            Prefs.setNearbyNotify(this, c)
            if (!c) getSystemService(android.app.NotificationManager::class.java).cancel(20)
        }

        // --- Receiver GNSS / background -------------------------------------
        binding.swReceiverGnss.isChecked = Prefs.receiverGnss(this)
        binding.swReceiverGnss.setOnCheckedChangeListener { _, c -> Prefs.setReceiverGnss(this, c) }
        binding.btnBattery.setOnClickListener { BatteryHelper.request(this) }

        // --- Marker / path TTL ----------------------------------------------
        binding.edMarkerTtl.setText(Prefs.markerTtlMinutes(this).toString())

        // --- Map prefetch ---------------------------------------------------
        binding.btnDownloadMap.setOnClickListener {
            SettingsBus.downloadVisibleMap()
            finish()
        }

        // --- Own track ------------------------------------------------------
        binding.swOwnTrack.isChecked = Prefs.ownTrackEnabled(this)
        binding.swOwnTrack.setOnCheckedChangeListener { _, c ->
            Prefs.setOwnTrackEnabled(this, c)
            SettingsBus.ownTrackChanged(c)
        }

        // --- BLE coex -------------------------------------------------------
        binding.btnCycle.setOnClickListener {
            val controller = SettingsBus.btController()
            if (controller == null) {
                Toast.makeText(this, getString(R.string.cycle_not_connected), Toast.LENGTH_SHORT).show()
            } else {
                CycleSettingsDialog.show(this, SettingsBus.lastCycleConfig()) { SettingsBus.applyCycle(it) }
            }
        }

        // --- About / GitHub -------------------------------------------------
        binding.btnAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        binding.btnGithub.setOnClickListener { openUrl(getString(R.string.github_url)) }
    }

    override fun onPause() {
        super.onPause()
        saveBrokerList()
        Prefs.setNodeId(this, binding.edNodeId.text.toString().trim())
        binding.edMarkerTtl.text.toString().trim().toIntOrNull()
            ?.let { Prefs.setMarkerTtlMinutes(this, it) }
    }

    // ── Broker row management ────────────────────────────────────────────────

    private fun loadBrokerRows() {
        binding.brokerContainer.removeAllViews()
        val brokers = Prefs.mqttBrokerList(this)
        if (brokers.isEmpty()) {
            addBrokerRow("")
        } else {
            brokers.forEach { addBrokerRow(it) }
        }
    }

    private fun addBrokerRow(url: String) {
        val row = layoutInflater.inflate(R.layout.item_broker_entry, binding.brokerContainer, false)
        row.findViewById<TextInputEditText>(R.id.edBrokerEntry).setText(url)
        row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteEntry)
            .setOnClickListener {
                binding.brokerContainer.removeView(row)
                // Always keep at least one empty row so the field is never fully gone
                if (binding.brokerContainer.childCount == 0) addBrokerRow("")
            }
        binding.brokerContainer.addView(row)
        // Focus the new (empty) field
        if (url.isEmpty()) {
            row.findViewById<TextInputEditText>(R.id.edBrokerEntry).requestFocus()
        }
    }

    private fun saveBrokerList() {
        val urls = mutableListOf<String>()
        for (i in 0 until binding.brokerContainer.childCount) {
            val url = binding.brokerContainer.getChildAt(i)
                .findViewById<TextInputEditText>(R.id.edBrokerEntry)
                ?.text?.toString()?.trim()
            if (!url.isNullOrBlank()) urls.add(url)
        }
        Prefs.setMqttBrokerList(this, urls)
    }

    // ── MQTT filter dialog ───────────────────────────────────────────────────

    private fun showMqttFilterDialog() {
        val allTypes = ItsG5Decoder.MsgType.values()
        val allowed  = Prefs.mqttFilterTypes(this)
        val checked  = allTypes.map { it.name in allowed }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.mqtt_filter_btn)
            .setMultiChoiceItems(allTypes.map { mqttTypeLabel(it) }.toTypedArray(), checked) { _, i, on ->
                checked[i] = on
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newFilter = allTypes.filterIndexed { i, _ -> checked[i] }.map { it.name }.toSet()
                Prefs.setMqttFilterTypes(this, newFilter)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        renderBatteryState()
    }

    private fun renderBatteryState() {
        val exempt = BatteryHelper.isExempt(this)
        binding.batteryStatus.text = getString(if (exempt) R.string.battery_status_ok else R.string.battery_status_restricted)
        binding.btnBattery.visibility = if (exempt) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun renderRecordingState() {
        val rec = SettingsBus.recorder()
        binding.btnRecord.text = getString(if (rec?.isRecording == true) R.string.rec_on else R.string.rec_off)
        binding.recStatus.text = if (rec?.isRecording == true)
            getString(R.string.rec_status_active, rec.file?.absolutePath ?: "?", rec.frameCount)
        else getString(R.string.rec_status_idle)
    }

    private fun mqttTypeLabel(type: ItsG5Decoder.MsgType): String = getString(when (type) {
        ItsG5Decoder.MsgType.UNKNOWN -> R.string.msgtype_unknown
        ItsG5Decoder.MsgType.CAM     -> R.string.msgtype_cam
        ItsG5Decoder.MsgType.DENM    -> R.string.msgtype_denm
        ItsG5Decoder.MsgType.MAPEM   -> R.string.msgtype_mapem
        ItsG5Decoder.MsgType.SPATEM  -> R.string.msgtype_spatem
        ItsG5Decoder.MsgType.IVIM    -> R.string.msgtype_ivim
        ItsG5Decoder.MsgType.SREM    -> R.string.msgtype_srem
        ItsG5Decoder.MsgType.SSEM    -> R.string.msgtype_ssem
        ItsG5Decoder.MsgType.TLM     -> R.string.msgtype_tlm
        ItsG5Decoder.MsgType.RTCMEM  -> R.string.msgtype_rtcmem
    })

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }
}
