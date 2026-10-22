package org.opentrafficmap.receiver

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.osmdroid.views.MapView
import kotlin.math.abs

/**
 * One-finger zoom: double tap, keep the finger down on the second tap and
 * drag — up zooms in, down zooms out (continuous). A plain double tap still
 * zooms in by one level. The point under the second tap stays fixed.
 *
 * Installed as the MapView's OnTouchListener; while the gesture is active it
 * consumes the events so the map does not pan.
 */
class MapZoomGesture(
    private val map: MapView,
    private val onTouchDown: () -> Unit = {},
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(map.context).scaledTouchSlop
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlop = ViewConfiguration.get(map.context).scaledDoubleTapSlop

    /** Finger travel (px) for one zoom level. */
    private val pxPerLevel = 160f * map.resources.displayMetrics.density

    private var lastUpTime = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f
    private var firstDownTime = 0L
    private var multiTouchSeen = false

    private var active = false
    private var dragging = false
    private var startY = 0f
    private var startZoom = 0.0
    private var anchorX = 0
    private var anchorY = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchDown()
                multiTouchSeen = false
                val isSecondTap = ev.eventTime - lastUpTime <= doubleTapTimeout &&
                    abs(ev.x - lastUpX) < doubleTapSlop && abs(ev.y - lastUpY) < doubleTapSlop
                if (isSecondTap) {
                    active = true
                    dragging = false
                    startY = ev.y
                    startZoom = map.zoomLevelDouble
                    anchorX = ev.x.toInt()
                    anchorY = ev.y.toInt()
                    return true
                }
                firstDownTime = ev.eventTime
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouchSeen = true
                if (active) { active = false; return false }
            }
            MotionEvent.ACTION_MOVE -> if (active) {
                val dy = startY - ev.y               // up = positive = zoom in
                if (!dragging && abs(dy) > slop) dragging = true
                if (dragging) zoomTo(startZoom + dy / pxPerLevel)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (active) {
                    active = false
                    if (!dragging) zoomTo(map.zoomLevelDouble + 1.0)   // plain double tap
                    lastUpTime = 0L                                   // a third tap starts fresh
                    return true
                }
                // Remember a quick single tap as a potential first tap
                if (!multiTouchSeen && ev.eventTime - firstDownTime < doubleTapTimeout) {
                    lastUpTime = ev.eventTime
                    lastUpX = ev.x
                    lastUpY = ev.y
                } else {
                    lastUpTime = 0L
                }
            }
            MotionEvent.ACTION_CANCEL -> active = false
        }
        return false
    }

    /** Zoom while keeping the geo point under the anchor pixel in place. */
    private fun zoomTo(level: Double) {
        val z = level.coerceIn(map.minZoomLevel, map.maxZoomLevel)
        val geo = map.projection.fromPixels(anchorX, anchorY)
        map.controller.setZoom(z)
        val p = map.projection.toPixels(geo, null)
        map.scrollBy(p.x - anchorX, p.y - anchorY)
        map.invalidate()
    }
}
