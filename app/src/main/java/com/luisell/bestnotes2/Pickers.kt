package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/* ============================================================
   Pickers.kt — escoger color (rueda de colores, recientes y
   favoritos, como en Best Notes) y tamaño de letra (5 a 100).
   ============================================================ */

private fun prefsList(ctx: Context, key: String): MutableList<Int> =
    (ctx.getSharedPreferences("ajustes", 0).getString(key, "") ?: "").split(',').mapNotNull { it.toLongOrNull()?.toInt() }.toMutableList()

private fun savePrefsList(ctx: Context, key: String, l: List<Int>) =
    ctx.getSharedPreferences("ajustes", 0).edit().putString(key, l.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()

/** Guarda el color en "Recientes" (los últimos 12). */
fun addRecentColor(ctx: Context, c: Int) {
    if (c == 0) return
    val l = prefsList(ctx, "colorRecent"); l.remove(c); l.add(0, c)
    savePrefsList(ctx, "colorRecent", l.take(12))
}

/**
 * Ventana para escoger un color. [current] = el color de ahora (0 = ninguno).
 * [onPick] recibe el color escogido, o 0 si tocaste "Sin color".
 */
fun Context.pickColor(title: String, current: Int, presets: IntArray, onLive: ((Int) -> Unit)? = null, onPick: (Int) -> Unit) {
    val act = this as? android.app.Activity ?: return
    val ctx = this
    val hsv = FloatArray(3).also { Color.colorToHSV(if (current != 0) current or 0xFF000000.toInt() else T.textColors[4], it) }
    lateinit var sheet: Sheet
    var decided = false
    var touched = false
    fun now() = Color.HSVToColor(hsv)
    /** Se ve en la letra mientras escoges (en vivo). */
    fun live() { touched = true; onLive?.invoke(now()) }

    // muestra grande: una bolita que brilla con el color + "Aa" pintado + el código
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(2), dp(14), dp(12)) }
    val glow = View(this)
    val sample = tv("Aa", 30f, T.text, true).apply { gravity = Gravity.CENTER }
    val swatchBox = FrameLayout(this)
    swatchBox.addView(glow, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
    swatchBox.addView(sample, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
    head.addView(swatchBox, lp(dp(70), dp(70)))
    val hexCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
    val hex = tv("", 20f, T.text, true)
    hexCol.addView(hex)
    hexCol.addView(tv(L.t("colorHint"), 12.5f, T.muted))
    head.addView(hexCol, lp(0, WRAP, 1f))

    val sv = SVView(this); val hue = HueView(this)
    fun paint() {
        val c = now()
        glow.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(c, c, (c and 0xFFFFFF) or 0x55000000, (c and 0xFFFFFF)); gradientRadius = dp(32).toFloat()
        }
        val lum = (Color.red(c) * 0.3 + Color.green(c) * 0.59 + Color.blue(c) * 0.11)
        sample.setTextColor(if (lum > 150) 0xFF161618.toInt() else Color.WHITE)
        hex.text = String.format("#%06X", c and 0xFFFFFF); hex.setTextColor(if (lum < 60) T.text else c)
        sv.hue = hsv[0]; sv.s = hsv[1]; sv.v = hsv[2]; sv.invalidate()
        hue.hue = hsv[0]; hue.invalidate()
    }
    fun pickDot(c: Int, v: View) {
        v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start() }.start()
        Color.colorToHSV(c or 0xFF000000.toInt(), hsv); paint(); live()
        swatchBox.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110).withEndAction { swatchBox.animate().scaleX(1f).scaleY(1f).setDuration(220).start() }.start()
    }
    sv.onChange = { s, v -> hsv[1] = s; hsv[2] = v; paint(); live() }
    hue.onChange = { h -> hsv[0] = h; paint(); live() }
    val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
    pad.addView(sv, lp(MATCH, dp(140)))
    pad.addView(hue, lp(MATCH, dp(38)).apply { topMargin = dp(10) })

    fun dots(colors: List<Int>, onLong: ((Int) -> Unit)? = null): View {
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        for (c in colors) {
            val v = View(this).apply {
                background = roundBg(c or 0xFF000000.toInt(), dp(19).toFloat(), tw(0x40), dp(1))
                setOnClickListener { pickDot(c, it) }
                if (onLong != null) setOnLongClickListener { onLong(c); true }
            }
            row.addView(v, lp(dp(38), dp(38)).apply { marginEnd = dp(10) })
        }
        hs.addView(row); return hs
    }
    fun label(t: String) = tv(t.uppercase(L.locale), 12f, T.muted, true).apply { letterSpacing = 0.1f; setPadding(dp(16), dp(14), dp(16), 0) }

    val favBox = FrameLayout(this)
    fun drawFavs() {
        favBox.removeAllViews()
        val favs = prefsList(ctx, "colorFav")
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val h = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        h.addView(label(L.t("colorsFav")), lp(0, WRAP, 1f))
        h.addView(tv("＋ " + L.t("colorsFavAdd"), 13f, 0xFFB388FF.toInt(), true).apply {
            setPadding(dp(12), dp(10), dp(16), 0)
            setOnClickListener { val l = prefsList(ctx, "colorFav"); val c = now(); l.remove(c); l.add(0, c); savePrefsList(ctx, "colorFav", l.take(20)); drawFavs() }
        })
        box.addView(h)
        if (favs.isNotEmpty()) {
            box.addView(dots(favs) { c -> savePrefsList(ctx, "colorFav", prefsList(ctx, "colorFav").filter { it != c }); drawFavs() })
            box.addView(tv(L.t("colorsFavHint"), 11.5f, T.faint).apply { setPadding(dp(16), 0, dp(16), 0) })
        } else box.addView(tv(L.t("colorsFavEmpty"), 12.5f, T.faint).apply { setPadding(dp(16), dp(4), dp(16), dp(4)) })
        favBox.addView(box)
    }
    drawFavs()

    // botones de abajo: Sin color · Aplicar
    // botones fijos abajo (siempre se ven): Sin color · ✓ Listo
    val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(12), dp(10), dp(2)) }
    btns.addView(tv(L.t("noColor"), 15.5f, T.text, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(10), dp(13), dp(10), dp(13)); pressBg(0, 24f, tw(0x55))
        setOnClickListener { decided = true; sheet.dismiss { onPick(0) } }
    }, lp(0, WRAP, 1f).apply { marginEnd = dp(10) })
    btns.addView(tv("✓  " + L.t("done"), 16f, T.onPill, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(10), dp(13), dp(10), dp(13)); pressBg(T.pill, 24f)
        setOnClickListener { decided = true; val c = now(); addRecentColor(ctx, c); sheet.dismiss { onPick(c) } }
    }, lp(0, WRAP, 1.4f))

    sheet = Sheet(act, title).custom(head).custom(pad)
        .custom(label(L.t("colorsBasic"))).custom(dots(presets.toList()))
    val rec = prefsList(this, "colorRecent")
    if (rec.isNotEmpty()) sheet.custom(label(L.t("colorsRecent"))).custom(dots(rec))
    sheet.custom(favBox).footer(btns)
    // si cierras sin tocar "Listo", la letra vuelve como estaba
    sheet.onClose = { if (!decided && touched) onLive?.invoke(current) }
    paint()
    sheet.show()
}

/** Cuadro de saturación (izq→der) y brillo (arriba→abajo). */
@SuppressLint("ViewConstructor")
class SVView(ctx: Context) : View(ctx) {
    var hue = 0f; var s = 1f; var v = 1f
    var onChange: ((Float, Float) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        val rad = dp(18).toFloat()
        p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        val x = s * width; val y = (1 - v) * height
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.color = Color.WHITE
        c.drawCircle(x, y, dp(10).toFloat(), p)
        p.strokeWidth = dp(1).toFloat(); p.color = 0x66000000
        c.drawCircle(x, y, dp(12).toFloat(), p)
        p.style = Paint.Style.FILL
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        s = (e.x / width).coerceIn(0f, 1f); v = 1 - (e.y / height).coerceIn(0f, 1f)
        onChange?.invoke(s, v); return true
    }
}

/** Barra con todos los colores (el tono). */
@SuppressLint("ViewConstructor")
class HueView(ctx: Context) : View(ctx) {
    var hue = 0f
    var onChange: ((Float) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val cols = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, cols, null, Shader.TileMode.CLAMP)
        r.set(0f, dp(6).toFloat(), width.toFloat(), height - dp(6).toFloat())
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.shader = null
        val x = hue / 360f * width
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.color = Color.WHITE
        c.drawCircle(x.coerceIn(dp(12).toFloat(), width - dp(12).toFloat()), height / 2f, dp(12).toFloat(), p)
        p.style = Paint.Style.FILL
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        hue = (e.x / width).coerceIn(0f, 0.999f) * 360f
        onChange?.invoke(hue); return true
    }
}

/** Ventana para escoger el tamaño de letra (5 a 100). [onPick] recibe 0 para "Normal". */
fun Context.pickSize(current: Int, onPick: (Int) -> Unit) {
    var size = if (current > 0) current else T.baseSize
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
    val prev = TextView(this).apply {
        text = "Aa"; setTextColor(T.text); gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val num = tv("", 15f, T.muted).apply { gravity = Gravity.CENTER }
    val bar = SeekBar(this).apply { max = 95 }
    fun paint() {
        prev.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size.toFloat())
        num.text = if (size == T.baseSize) "$size · " + L.t("sizeNormal") else "$size"
        if (bar.progress != size - 5) bar.progress = size - 5
    }
    bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { size = p + 5; paint() } }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    })
    col.addView(FrameLayout(this).apply { addView(prev, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)) }, lp(MATCH, dp(130)))
    col.addView(num)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    row.addView(tv("−", 26f, T.text).apply { setPadding(dp(10), 0, dp(10), 0); setOnClickListener { size = (size - 1).coerceAtLeast(5); paint() } })
    row.addView(bar, lp(0, WRAP, 1f))
    row.addView(tv("+", 26f, T.text).apply { setPadding(dp(10), 0, dp(10), 0); setOnClickListener { size = (size + 1).coerceAtMost(100); paint() } })
    col.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(6) })
    val chips = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4)) }
    for (z in T.sizes) cr.addView(tv("$z", 14f, T.text).apply {
        setPadding(dp(16), dp(9), dp(16), dp(9)); pressBg(tw(0x1A), 18f, tw(0x33))
        setOnClickListener { v -> size = z; paint(); v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start() }.start() }
    }, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
    chips.addView(cr); col.addView(chips)
    paint()
    val act = this as? android.app.Activity ?: return
    lateinit var sheet: Sheet
    val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, dp(4)) }
    btns.addView(tv(L.t("sizeNormal"), 15.5f, T.text, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(10), dp(13), dp(10), dp(13)); pressBg(0, 24f, tw(0x55))
        setOnClickListener { sheet.dismiss { onPick(0) } }
    }, lp(0, WRAP, 1f).apply { marginEnd = dp(10) })
    btns.addView(tv("✓  " + L.t("done"), 16f, T.onPill, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(10), dp(13), dp(10), dp(13)); pressBg(T.pill, 24f)
        setOnClickListener { sheet.dismiss { onPick(if (size == T.baseSize) 0 else size) } }
    }, lp(0, WRAP, 1.4f))
    btns.setPadding(dp(14), dp(12), dp(14), dp(2))
    sheet = Sheet(act, "Aa  " + L.t("size")).custom(col).footer(btns)
    sheet.show()
}
