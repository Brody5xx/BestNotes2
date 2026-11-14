package com.luisell.bestnotes2

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/* ============================================================
   Glass.kt — el diseño de "vidrio" (Liquid Glass) de Best Notes:
   borde con brillo, reflejo en diagonal, luz arriba y sombra abajo,
   y el fondo oscuro desenfocado de siempre.
   ============================================================ */
object Glass {
    /** Vidrio casi transparente (tarjetas, botones). [strong] = más sólido (menú, paneles). [tint] = color de la nota. */
    fun make(ctx: Context, radiusDp: Float, strong: Boolean = false, tint: Int = 0, bar: Boolean = false, selected: Boolean = false): Drawable =
        GlassDrawable(ctx.resources.displayMetrics.density, radiusDp, strong, tint, bar, selected)

    /** El fondo desenfocado de Best Notes (se estira suave a toda la pantalla). */
    fun background(ctx: Context): Drawable =
        BitmapDrawable(ctx.resources, android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.bg_dark)).apply { isFilterBitmap = true; setDither(true) }
}

class GlassDrawable(
    private val d: Float, private val radiusDp: Float, private val strong: Boolean,
    private val tint: Int, private val bar: Boolean, private val selected: Boolean,
) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val rad = minOf(radiusDp * d, b.height() / 2f, b.width() / 2f)
        r.set(b.left + 0.5f * d, b.top + 0.5f * d, b.right - 0.5f * d, b.bottom - 0.5f * d)
        clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        // relleno
        p.shader = null; p.style = Paint.Style.FILL
        p.color = if (strong) 0xD9262628.toInt() else 0x0DFFFFFF
        c.drawRect(r, p)
        if (tint != 0) {
            p.color = (tint and 0xFFFFFF) or 0x29000000          // el color de la nota, suavecito (16%)
            c.drawRect(r, p)
        }
        // reflejo en diagonal
        p.shader = LinearGradient(r.left, r.top, r.left + r.width() * 0.75f, r.top + r.height() * 0.75f,
            intArrayOf(0x1AFFFFFF, 0x0AFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.35f, 0.7f), Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        // luz arriba y sombra abajo
        p.shader = LinearGradient(0f, r.top, 0f, r.top + 6 * d, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.top, r.right, r.top + 6 * d, p)
        p.shader = LinearGradient(0f, r.bottom - 6 * d, 0f, r.bottom, 0x00000000, 0x1F000000, Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.bottom - 6 * d, r.right, r.bottom, p)
        p.shader = null
        // brillo interno pegado al borde (efecto lente)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * d; p.color = 0x24FFFFFF
        c.drawRoundRect(r, rad, rad, p)
        if (bar && tint != 0) {
            p.style = Paint.Style.FILL; p.color = tint or 0xFF000000.toInt()
            c.drawRoundRect(r.left + 0f, r.top + 14 * d, r.left + 4 * d, r.bottom - 14 * d, 2 * d, 2 * d, p)
        }
        c.restore()
        // borde con brillo (más claro arriba a la izquierda y abajo a la derecha)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (selected) 2.5f * d else 1f * d
        p.shader = if (selected) null else LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(0x8CFFFFFF.toInt(), 0x1FFFFFFF, 0x14FFFFFF, 0x66FFFFFF), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        p.color = if (selected) T.accent else 0xFFFFFFFF.toInt()
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
