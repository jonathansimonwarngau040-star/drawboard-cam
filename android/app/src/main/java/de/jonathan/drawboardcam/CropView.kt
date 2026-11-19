package de.jonathan.drawboardcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Magnifier
import kotlin.math.hypot
import kotlin.math.min

/** Zeigt das Foto mit dem erkannten Viereck; die vier Ecken lassen sich mit dem Finger verschieben. */
class CropView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** Wird beim ersten Antippen aufgerufen (stoppt z. B. den Countdown). */
    var onUserTouch: (() -> Unit)? = null

    /** Ecken in Bildpixeln: oben-links, oben-rechts, unten-rechts, unten-links. */
    val corners = FloatArray(8)

    private var bitmap: Bitmap? = null
    private val dp = resources.displayMetrics.density
    private val dst = RectF()
    private var scale = 1f
    private val pad = 24 * dp

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF42A5F5.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * dp
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * dp
    }
    private val path = Path()

    private var active = -1
    private var grabDx = 0f
    private var grabDy = 0f
    private val magnifier: Magnifier? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Magnifier(this) else null

    fun setImage(bmp: Bitmap, quad: FloatArray) {
        bitmap = bmp
        quad.copyInto(corners)
        layoutImage()
        invalidate()
    }

    fun clear() {
        bitmap = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutImage()

    private fun layoutImage() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun vx(i: Int) = dst.left + corners[2 * i] * scale
    private fun vy(i: Int) = dst.top + corners[2 * i + 1] * scale

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, null, dst, bmpPaint)

        // Außerhalb des Vierecks abdunkeln
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(dst, Path.Direction.CW)
        path.moveTo(vx(0), vy(0))
        for (i in 1..3) path.lineTo(vx(i), vy(i))
        path.close()
        canvas.drawPath(path, dim)

        path.reset()
        path.moveTo(vx(0), vy(0))
        for (i in 1..3) path.lineTo(vx(i), vy(i))
        path.close()
        canvas.drawPath(path, line)

        val r = 11 * dp
        for (i in 0 until 4) {
            canvas.drawCircle(vx(i), vy(i), r, handleFill)
            canvas.drawCircle(vx(i), vy(i), r, handleRing)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onUserTouch?.invoke()
                var best = -1
                var bestD = 48 * dp
                for (i in 0 until 4) {
                    val d = hypot(e.x - vx(i), e.y - vy(i))
                    if (d < bestD) { bestD = d; best = i }
                }
                active = best
                if (active >= 0) {
                    grabDx = vx(active) - e.x
                    grabDy = vy(active) - e.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return true
                val x = ((e.x + grabDx - dst.left) / scale).coerceIn(0f, b.width - 1f)
                val y = ((e.y + grabDy - dst.top) / scale).coerceIn(0f, b.height - 1f)
                corners[2 * active] = x
                corners[2 * active + 1] = y
                magnifier?.show(vx(active), vy(active))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                magnifier?.dismiss()
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
