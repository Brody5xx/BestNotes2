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

    /** El fondo de la app: el de Best Notes, un degradado o tu foto (☀️ Apariencia). */
    fun background(ctx: Context): Drawable = Wall.drawable(ctx)
}

/** 🖼️ Los fondos de pantalla de la app. */
object Wall {
    /** [id, nombre ES, nombre EN, colores] (los degradados son inventados, oscuros para que se lea bien). */
    val GRADS: List<List<Any>> = listOf(
        listOf("aurora", "Aurora", "Aurora", intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt(), 0xFF2C5364.toInt())),
        listOf("violeta", "Violeta", "Violet", intArrayOf(0xFF140C2B.toInt(), 0xFF3A1C71.toInt(), 0xFF5B2A86.toInt())),
        listOf("oceano", "Océano", "Ocean", intArrayOf(0xFF000428.toInt(), 0xFF003366.toInt(), 0xFF004E92.toInt())),
        listOf("bosque", "Bosque", "Forest", intArrayOf(0xFF07211A.toInt(), 0xFF0B3D2E.toInt(), 0xFF134E4A.toInt())),
        listOf("atardecer", "Atardecer", "Sunset", intArrayOf(0xFF2B1055.toInt(), 0xFF7A2E3B.toInt(), 0xFF3A1C1C.toInt())),
        listOf("noche", "Noche", "Night", intArrayOf(0xFF000000.toInt(), 0xFF141416.toInt(), 0xFF1C1C1E.toInt())),
    )
    private var cacheKey = ""
    private var cacheBmp: android.graphics.Bitmap? = null

    fun current(ctx: Context): String = ctx.getSharedPreferences("ajustes", 0).getString("wall", "best") ?: "best"
    fun blur(ctx: Context): Boolean = ctx.getSharedPreferences("ajustes", 0).getBoolean("wallBlur", true)
    fun photoFile(ctx: Context) = java.io.File(ctx.filesDir, "fondo_app.jpg")

    fun set(ctx: Context, id: String) { ctx.getSharedPreferences("ajustes", 0).edit().putString("wall", id).apply(); cacheKey = "" }
    fun setBlur(ctx: Context, on: Boolean) { ctx.getSharedPreferences("ajustes", 0).edit().putBoolean("wallBlur", on).apply(); cacheKey = "" }

    fun drawable(ctx: Context, id: String = current(ctx)): Drawable {
        GRADS.firstOrNull { (it[0] as String) == id }?.let {
            return android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, it[3] as IntArray)
        }
        val key = id + blur(ctx) + (if (id == "photo") photoFile(ctx).lastModified() else 0L)
        if (key != cacheKey || cacheBmp == null) {
            cacheBmp = if (id == "photo" && photoFile(ctx).exists()) loadPhoto(ctx) else null
            if (cacheBmp == null) cacheBmp = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.bg_dark)
            cacheKey = key
        }
        val bd = BitmapDrawable(ctx.resources, cacheBmp).apply { isFilterBitmap = true; setDither(true) }
        if (id != "photo") return bd
        // tu foto se oscurece un poco para que las letras blancas se lean
        return android.graphics.drawable.LayerDrawable(arrayOf(CoverDrawable(cacheBmp!!), android.graphics.drawable.ColorDrawable(0x73000000)))
    }

    /** Tu foto: más chiquita (rápida) y, si quieres, difuminada. */
    private fun loadPhoto(ctx: Context): android.graphics.Bitmap? {
        val f = photoFile(ctx)
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var smp = 1; while (o.outWidth / smp > 1400 || o.outHeight / smp > 1400) smp *= 2
        val b = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = smp }) ?: return null
        if (!blur(ctx)) return b
        // difuminado barato: se achica mucho y se vuelve a agrandar suave (dos veces)
        var x = b
        repeat(2) {
            val small = android.graphics.Bitmap.createScaledBitmap(x, maxOf(1, x.width / 10), maxOf(1, x.height / 10), true)
            x = android.graphics.Bitmap.createScaledBitmap(small, b.width / 2, b.height / 2, true)
        }
        return x
    }

    /** Muestra la foto llenando la pantalla sin estirarla (como "cover"). */
    class CoverDrawable(private val bmp: android.graphics.Bitmap) : Drawable() {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val src = android.graphics.Rect()
        override fun draw(c: Canvas) {
            val b = bounds; if (b.isEmpty) return
            val sc = maxOf(b.width() / bmp.width.toFloat(), b.height() / bmp.height.toFloat())
            val w = (b.width() / sc).toInt(); val h = (b.height() / sc).toInt()
            val l = (bmp.width - w) / 2; val t = (bmp.height - h) / 2
            src.set(l, t, l + w, t + h)
            c.drawBitmap(bmp, src, b, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
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

/**
 * ✨ El borde azul que BRILLA alrededor de la foto seleccionada (como en Best Notes):
 * un halo azul que respira y una luz que da vueltas por la orilla.
 */
class GlowBorder(private val d: Float, private val radiusDp: Float, private val padDp: Float = 10f) : Drawable(), android.graphics.drawable.Animatable {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private val edge = Path()
    private val seg = Path()
    private val pm = android.graphics.PathMeasure()
    private var len = 0f
    private var lastW = -1; private var lastH = -1
    private var t = 0f
    private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3200; repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidateSelf() }
    }

    /** Halo fino: pocas capas delgadas y suaves. */
    private val HALO = (0 until 6).map { i -> val k = i / 5f; (7f - 5.5f * k) to (0.04f + 0.10f * k * k) }

    /** [bounds] = la foto + [padDp] de cada lado (el brillo va por FUERA de la foto). */
    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0) return
        val pad = padDp * d
        val rad = radiusDp * d
        val breathe = 0.5f + 0.5f * Math.sin(t * Math.PI * 4).toFloat()     // respira 2 veces por vuelta
        // halo azul fino por fuera
        for ((w, a) in HALO) {
            val sw = w * d
            p.strokeWidth = sw
            p.color = 0x4FC3F7 or ((255 * a * (0.6f + 0.4f * breathe)).toInt() shl 24)
            val o = pad - sw / 2
            r.set(b.left + o, b.top + o, b.right - o, b.bottom - o)
            c.drawRoundRect(r, rad + sw / 2, rad + sw / 2, p)
        }
        // la línea azul fina pegada a la foto
        p.strokeWidth = 1.4f * d; p.color = 0xFF4FC3F7.toInt()
        r.set(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
        c.drawRoundRect(r, rad, rad, p)
        // el camino de la orilla (se mide una sola vez por tamaño)
        if (b.width() != lastW || b.height() != lastH) {
            edge.reset(); edge.addRoundRect(r, rad, rad, Path.Direction.CW)
            pm.setPath(edge, true); len = pm.length; lastW = b.width(); lastH = b.height()
        }
        // ✨ dos líneas blancas que van trazando la orilla, una frente a la otra, con cola que se apaga
        val tail = len * 0.16f
        val steps = 24
        for (k in 0..1) {
            val head = ((t + k * 0.5f) % 1f) * len
            p.strokeCap = Paint.Cap.BUTT
            for (i in 0 until steps) {
                val f0 = i / steps.toFloat(); val f1 = (i + 1) / steps.toFloat()
                val a0 = head - tail * (1 - f0); val a1 = head - tail * (1 - f1)
                seg.reset(); addPiece(a0, a1)
                p.strokeWidth = (0.8f + 1.4f * f1) * d
                p.color = 0xFFFFFF or ((255 * f1 * f1).toInt() shl 24)
                c.drawPath(seg, p)
            }
            p.strokeCap = Paint.Cap.ROUND
            // puntita brillante con un poquito de luz
            seg.reset(); addPiece(head - len * 0.012f, head)
            p.strokeWidth = 5f * d; p.color = 0x55E1F5FE
            c.drawPath(seg, p)
        }
    }

    /** Agrega a [seg] el pedazo de la orilla entre [a] y [b] (da la vuelta si hace falta). */
    private fun addPiece(a: Float, b: Float) {
        var s = a; var e = b
        while (s < 0) { s += len; e += len }
        if (e <= len) pm.getSegment(s, e, seg, true)
        else { pm.getSegment(s, len, seg, true); pm.getSegment(0f, e - len, seg, true) }
    }

    override fun start() { if (!anim.isRunning) anim.start() }
    override fun stop() { anim.cancel() }
    override fun isRunning() = anim.isRunning
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
