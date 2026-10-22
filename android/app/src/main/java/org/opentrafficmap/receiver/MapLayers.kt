package org.opentrafficmap.receiver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/**
 * Base-map layers: the built-in ones plus user-added XYZ tile URLs.
 * The user can hide layers, reorder them and delete custom ones; the list
 * (order, visibility, custom entries) is stored as JSON in the app prefs.
 *
 * Custom URL templates support {z} {x} {y}, {-y} (TMS) and {s} (subdomain,
 * rotated over the "subdomains" letters, default "abc").
 */
object MapLayers {

    data class Layer(
        val key: String,               // built-in key or "custom:<id>"
        val name: String,
        val url: String? = null,       // null for built-ins
        val maxZoom: Int = 19,
        val subdomains: String = "abc",
        var visible: Boolean = true,
    ) {
        val custom: Boolean get() = url != null
    }

    private const val PREFS = "map_layers"
    private const val KEY = "layers_v1"

    private fun builtIns(ctx: Context) = listOf(
        Layer("MAPNIK",    ctx.getString(R.string.map_layer_standard)),
        Layer("DARK",      ctx.getString(R.string.map_layer_dark)),
        Layer("SATELLITE", ctx.getString(R.string.map_layer_satellite)),
        Layer("TRANSPORT", ctx.getString(R.string.map_layer_transport)),
        Layer("HOT",       ctx.getString(R.string.map_layer_humanitarian)),
    )

    /** All layers in the user's order (hidden ones included). */
    fun all(ctx: Context): MutableList<Layer> {
        val defaults = builtIns(ctx)
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return defaults.toMutableList()
        val out = mutableListOf<Layer>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.getString("key")
                val url = o.optString("url").ifEmpty { null }
                if (url == null) {
                    // built-in: name follows the current language
                    defaults.firstOrNull { it.key == key }?.let {
                        out += it.copy(visible = o.optBoolean("visible", true))
                    }
                } else {
                    out += Layer(key, o.optString("name", key), url,
                        o.optInt("maxZoom", 19), o.optString("sub", "abc"),
                        o.optBoolean("visible", true))
                }
            }
        } catch (_: Exception) {
            return defaults.toMutableList()
        }
        // Built-ins added in a later app version
        defaults.filter { d -> out.none { it.key == d.key } }.forEach { out += it }
        return out
    }

    fun save(ctx: Context, layers: List<Layer>) {
        val arr = JSONArray()
        layers.forEach { l ->
            arr.put(JSONObject().apply {
                put("key", l.key)
                put("visible", l.visible)
                if (l.custom) {
                    put("name", l.name); put("url", l.url)
                    put("maxZoom", l.maxZoom); put("sub", l.subdomains)
                }
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    fun visible(ctx: Context): List<Layer> = all(ctx).filter { it.visible }

    fun reset(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()

    /** Null when the template is usable, otherwise a string resource id. */
    fun validateUrl(url: String): Int? = when {
        !url.startsWith("https://") && !url.startsWith("http://") -> R.string.layers_err_scheme
        !url.contains("{z}") || !url.contains("{x}") ||
            !(url.contains("{y}") || url.contains("{-y}")) -> R.string.layers_err_placeholders
        else -> null
    }

    fun newCustom(name: String, url: String, maxZoom: Int) = Layer(
        key = "custom:" + java.util.UUID.randomUUID().toString().take(8),
        name = name.ifBlank { url.substringAfter("://").substringBefore('/') },
        url = url.trim(),
        maxZoom = maxZoom.coerceIn(1, 22),
    )

    fun tileSource(ctx: Context, key: String): ITileSource {
        all(ctx).firstOrNull { it.key == key && it.custom }?.let { return customSource(it) }
        return when (key) {
            "DARK" -> XYTileSource(
                "CartoDB_DarkMatter", 0, 19, 256, ".png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/dark_all/",
                    "https://b.basemaps.cartocdn.com/dark_all/",
                    "https://c.basemaps.cartocdn.com/dark_all/",
                    "https://d.basemaps.cartocdn.com/dark_all/"
                )
            )
            "SATELLITE" -> object : OnlineTileSourceBase("Esri_WorldImagery", 0, 19, 256, "",
                arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String =
                    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
                    "${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
            }
            "TRANSPORT" -> XYTileSource(
                "OPNV_Karte", 0, 18, 256, ".png",
                arrayOf("https://tileserver.memomaps.de/tilegen/")
            )
            "HOT" -> XYTileSource(
                "HOT", 0, 19, 256, ".png",
                arrayOf(
                    "https://a.tile.openstreetmap.fr/hot/",
                    "https://b.tile.openstreetmap.fr/hot/",
                    "https://c.tile.openstreetmap.fr/hot/"
                )
            )
            else -> TileSourceFactory.MAPNIK
        }
    }

    private fun customSource(l: Layer): ITileSource {
        val tpl = l.url ?: ""
        // The cache name must be unique per layer so tiles don't mix
        return object : OnlineTileSourceBase(l.key.replace(':', '_'), 0, l.maxZoom, 256, "",
            arrayOf(tpl)) {
            override fun getTileURLString(index: Long): String {
                val z = MapTileIndex.getZoom(index)
                val x = MapTileIndex.getX(index)
                val y = MapTileIndex.getY(index)
                val subs = l.subdomains.ifEmpty { "a" }
                val s = subs[((x + y) % subs.length + subs.length) % subs.length].toString()
                return tpl
                    .replace("{z}", z.toString())
                    .replace("{x}", x.toString())
                    .replace("{-y}", ((1 shl z) - 1 - y).toString())
                    .replace("{y}", y.toString())
                    .replace("{s}", s)
            }
        }
    }
}
