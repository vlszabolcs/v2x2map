package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Tiny bar chart of received frames over the last [BUCKETS] × [BUCKET_MS]
 * (two minutes by default). Older bars fade out so "now" reads at the right.
 */
class ActivityBarsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val counts = IntArray(BUCKETS)
    private var headBucket = System.currentTimeMillis() / BUCKET_MS

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.type_cam)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.status_idle)
        alpha = 60
    }
    private val rect = RectF()
    private val radius = 2f * resources.displayMetrics.density
    private val gap    = 3f * resources.displayMetrics.density
    private val minH   = 3f * resources.displayMetrics.density

    /** Count one frame at the current time. Main thread only. */
    fun hit(n: Int = 1) {
        roll()
        counts[(headBucket % BUCKETS).toInt()] += n
    }

    fun clear() {
        counts.fill(0)
        invalidate()
    }

    /** Advance the window to "now"; call periodically so idle time shows. */
    fun tick() {
        roll()
        invalidate()
    }

    private fun roll() {
        val now = System.currentTimeMillis() / BUCKET_MS
        if (now - headBucket >= BUCKETS) {
            counts.fill(0)
        } else {
            var b = headBucket + 1
            while (b <= now) { counts[(b % BUCKETS).toInt()] = 0; b++ }
        }
        headBucket = now
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barW = (w - gap * (BUCKETS - 1)) / BUCKETS
        val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        for (i in 0 until BUCKETS) {
            // i = 0 is the oldest bucket, BUCKETS-1 the current one
            val bucket = headBucket - (BUCKETS - 1 - i)
            val c = counts[(((bucket % BUCKETS) + BUCKETS) % BUCKETS).toInt()]
            val left = i * (barW + gap)
            if (c == 0) {
                rect.set(left, h - minH, left + barW, h)
                canvas.drawRoundRect(rect, radius, radius, emptyPaint)
            } else {
                val bh = (minH + (h - minH) * c / max).coerceAtMost(h)
                rect.set(left, h - bh, left + barW, h)
                barPaint.alpha = (90 + 165 * i / (BUCKETS - 1))
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
        }
    }

    companion object {
        private const val BUCKETS = 40
        private const val BUCKET_MS = 3_000L
    }
}
