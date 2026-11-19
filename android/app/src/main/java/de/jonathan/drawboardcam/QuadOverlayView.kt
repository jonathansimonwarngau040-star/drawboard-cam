package de.jonathan.drawboardcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

/** Zeigt im Sucher live den erkannten Rahmen (Tafel/Folie). Nimmt keine Touches an. */
class QuadOverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3342A5F5; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF42A5F5.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * dp; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var quad: FloatArray? = null

    /** Ecken in View-Koordinaten (8 Werte) oder null zum Ausblenden. Leicht geglättet gegen Zittern. */
    fun setQuad(q: FloatArray?) {
        val old = quad
        quad = when {
            q == null -> null
            old == null -> q.copyOf()
            else -> {
                var d = 0f
                for (i in 0 until 4) d += hypot(q[2 * i] - old[2 * i], q[2 * i + 1] - old[2 * i + 1])
                d /= 4
                val jump = 0.08f * hypot(width.toFloat(), height.toFloat())
                if (d > jump) q.copyOf() else FloatArray(8) { old[it] * 0.5f + q[it] * 0.5f }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val q = quad ?: return
        path.reset()
        path.moveTo(q[0], q[1])
        path.lineTo(q[2], q[3])
        path.lineTo(q[4], q[5])
        path.lineTo(q[6], q[7])
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }
}
