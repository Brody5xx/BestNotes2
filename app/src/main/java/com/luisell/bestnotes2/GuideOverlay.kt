package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * La cruz roja al mover una foto (como en Best Notes): una línea de arriba a abajo
 * y otra de lado a lado por el centro de la foto, y una etiqueta con cuánto se movió.
 * Cuando la foto "pega" en la orilla o el centro, la línea se pone más gruesa.
 */
@SuppressLint("ViewConstructor")
class GuideOverlay(ctx: Context) : View(ctx) {
    private var x = -1f; private var y = -1f
    private var snap = ""
    private var label: String? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61C1C1F.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6B60.toInt(); textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val r = RectF()

    init { isClickable = false }

    fun set(nx: Float, ny: Float, s: String, l: String?) {
        x = nx; y = ny; snap = s; label = l
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        if (x >= 0 && y >= 0) {
            line.strokeWidth = (if (snap.isNotEmpty()) 2.5f else 1.2f) * d
            c.drawLine(x, 0f, x, height.toFloat(), line)
            line.strokeWidth = 1.2f * d
            c.drawLine(0f, y, width.toFloat(), y, line)
            if (snap.isNotEmpty()) {
                // orilla de la hoja (izquierda / derecha) marcada también
                line.strokeWidth = 3f * d
                when (snap) { "l" -> c.drawLine(1.5f * d, 0f, 1.5f * d, height.toFloat(), line); "r" -> c.drawLine(width - 1.5f * d, 0f, width - 1.5f * d, height.toFloat(), line) }
            }
        }
        val l = label ?: return
        txt.textSize = 13 * d
        val w = txt.measureText(l) + 24 * d
        val cx = if (x >= 0) x.coerceIn(w / 2 + 4 * d, width - w / 2 - 4 * d) else width / 2f
        val cy = if (y >= 0) (y - 28 * d).coerceAtLeast(20 * d) else 40 * d
        r.set(cx - w / 2, cy - 15 * d, cx + w / 2, cy + 15 * d)
        c.drawRoundRect(r, 15 * d, 15 * d, pill)
        c.drawText(l, cx, cy + 4.5f * d, txt)
    }
}
