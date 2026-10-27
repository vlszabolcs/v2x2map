package org.opentrafficmap.receiver

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

/** Colour scale shared by the map overlay and the KML export. */
object Coverage {
    private const val WEAK = -95.0
    private const val STRONG = -55.0

    /** Red (weak) → yellow → green (strong) by best RSSI; without RSSI, blue
     *  getting darker with the number of frames heard in the cell. */
    fun colorFor(c: LogTools.CoverageCell): Int {
        val r = c.bestRssi
        if (r == null) {
            val k = (Math.log10(c.frames.toDouble().coerceAtLeast(1.0)) / 3.0).coerceIn(0.0, 1.0)
            val a = (0x80 + 0x60 * k).toInt()
            return (a shl 24) or 0x2196F3
        }
        val t = ((r - WEAK) / (STRONG - WEAK)).coerceIn(0.0, 1.0)
        val red: Int
        val green: Int
        if (t < 0.5) { red = 0xE5; green = (0x39 + (0xC8 - 0x39) * t * 2).toInt() }
        else { red = (0xE5 - (0xE5 - 0x2E) * (t - 0.5) * 2).toInt(); green = 0xC8 }
        return (0xD0 shl 24) or (red shl 16) or (green shl 8) or 0x3C
    }
}

/** Coverage cells of a replayed log drawn as dots on the receiver's route,
 *  one per [LogTools.CELL_M] m cell. Tapping a dot shows its numbers. */
class CoverageOverlay(
    private val cells: List<LogTools.CoverageCell>,
    private val onTap: (LogTools.CoverageCell) -> Unit,
) : Overlay() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66000000
        strokeWidth = 1f
    }
    private val pt = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val r = radiusPx(map, proj)
        for (cell in cells) {
            proj.toPixels(GeoPoint(cell.lat, cell.lon), pt)
            if (pt.x < -r || pt.y < -r || pt.x > c.width + r || pt.y > c.height + r) continue
            fill.color = Coverage.colorFor(cell)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fill)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, ring)
        }
    }

    /** Half a cell on the ground, but never smaller than 4 dp. */
    private fun radiusPx(map: MapView, proj: Projection): Float {
        val minPx = 4f * map.resources.displayMetrics.density
        return maxOf(minPx, proj.metersToPixels((LogTools.CELL_M / 2).toFloat()))
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        val r = radiusPx(map, proj) * 1.5f
        val hit = cells.minByOrNull { cell ->
            proj.toPixels(GeoPoint(cell.lat, cell.lon), pt)
            val dx = pt.x - e.x; val dy = pt.y - e.y
            dx * dx + dy * dy
        } ?: return false
        proj.toPixels(GeoPoint(hit.lat, hit.lon), pt)
        val dx = pt.x - e.x; val dy = pt.y - e.y
        if (dx * dx + dy * dy > r * r) return false
        onTap(hit)
        return true
    }
}
