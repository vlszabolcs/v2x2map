package org.opentrafficmap.receiver

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.opentrafficmap.receiver.databinding.ActivityMapLayersBinding

/**
 * Manage base-map layers: add your own XYZ tile URL, edit or delete it, hide
 * layers you don't use, and change the order shown in the layer picker.
 */
class MapLayersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapLayersBinding
    private lateinit var layers: MutableList<MapLayers.Layer>
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapLayersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_add_layer -> { editDialog(null); true }
                R.id.action_reset_layers -> { confirmReset(); true }
                else -> false
            }
        }
        binding.btnAddLayer.setOnClickListener { editDialog(null) }
        layers = MapLayers.all(this)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    private fun persist() {
        // Never hide every layer: the map needs at least one
        if (layers.none { it.visible }) layers.first().visible = true
        MapLayers.save(this, layers)
        // If the active layer was hidden or deleted, fall back to the first visible one
        val active = Prefs.mapLayer(this)
        if (layers.none { it.key == active && it.visible })
            Prefs.setMapLayer(this, layers.first { it.visible }.key)
        adapter.notifyDataSetChanged()
    }

    private fun move(pos: Int, delta: Int) {
        val to = pos + delta
        if (to !in layers.indices) return
        val l = layers.removeAt(pos)
        layers.add(to, l)
        persist()
    }

    private fun editDialog(existing: MapLayers.Layer?) {
        val view = layoutInflater.inflate(R.layout.dialog_map_layer, null)
        val name = view.findViewById<TextInputEditText>(R.id.layerName)
        val urlBox = view.findViewById<TextInputLayout>(R.id.layerUrlBox)
        val url = view.findViewById<TextInputEditText>(R.id.layerUrl)
        val zoom = view.findViewById<TextInputEditText>(R.id.layerMaxZoom)
        zoom.inputType = InputType.TYPE_CLASS_NUMBER
        existing?.let {
            name.setText(it.name); url.setText(it.url); zoom.setText(it.maxZoom.toString())
        } ?: zoom.setText("19")

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.layers_add else R.string.layers_edit)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Validate before closing
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val u = url.text?.toString()?.trim().orEmpty()
            val err = MapLayers.validateUrl(u)
            if (err != null) { urlBox.error = getString(err); return@setOnClickListener }
            val z = zoom.text?.toString()?.toIntOrNull() ?: 19
            val fresh = MapLayers.newCustom(name.text?.toString()?.trim().orEmpty(), u, z)
            if (existing == null) {
                layers.add(0, fresh)
            } else {
                val i = layers.indexOfFirst { it.key == existing.key }
                if (i >= 0) layers[i] = fresh.copy(key = existing.key, visible = existing.visible)
            }
            persist()
            dlg.dismiss()
        }
    }

    private fun confirmDelete(l: MapLayers.Layer) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.layers_delete_title, l.name))
            .setPositiveButton(R.string.layers_delete) { _, _ ->
                layers.removeAll { it.key == l.key }
                persist()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.layers_reset)
            .setMessage(R.string.layers_reset_body)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MapLayers.reset(this)
                layers = MapLayers.all(this)
                persist()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.layerTitle)
            val sub: TextView = v.findViewById(R.id.layerSub)
            val visible: MaterialSwitch = v.findViewById(R.id.layerVisible)
            val up: ImageButton = v.findViewById(R.id.layerUp)
            val down: ImageButton = v.findViewById(R.id.layerDown)
            val delete: ImageButton = v.findViewById(R.id.layerDelete)
        }

        override fun getItemCount() = layers.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_map_layer, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val l = layers[pos]
            h.name.text = l.name
            h.sub.text = if (l.custom) l.url else getString(R.string.layers_builtin)
            h.visible.setOnCheckedChangeListener(null)
            h.visible.isChecked = l.visible
            h.visible.setOnCheckedChangeListener { _, on -> l.visible = on; persist() }
            h.up.isEnabled = pos > 0
            h.down.isEnabled = pos < layers.size - 1
            h.up.alpha = if (h.up.isEnabled) 1f else 0.3f
            h.down.alpha = if (h.down.isEnabled) 1f else 0.3f
            h.up.setOnClickListener { move(h.bindingAdapterPosition, -1) }
            h.down.setOnClickListener { move(h.bindingAdapterPosition, +1) }
            h.delete.visibility = if (l.custom) View.VISIBLE else View.GONE
            h.delete.setOnClickListener { confirmDelete(l) }
            h.itemView.setOnClickListener { if (l.custom) editDialog(l) }
        }
    }
}
