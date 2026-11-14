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
fun Context.pickColor(title: String, current: Int, presets: IntArray, onPick: (Int) -> Unit) {
    val ctx = this
    var hsv = FloatArray(3).also { Color.colorToHSV(if (current != 0) current or 0xFF000000.toInt() else T.textColors[4], it) }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(4)) }
    lateinit var d: AlertDialog

    // Muestra del color + código
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val swatch = View(this)
    val hex = tv("", 15f, T.muted).apply { setPadding(dp(12), 0, 0, 0) }
    head.addView(swatch, lp(dp(44), dp(44)))
    head.addView(hex, lp(0, WRAP, 1f))
    col.addView(head, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })

    val sv = SVView(this); val hue = HueView(this)
    fun now() = Color.HSVToColor(hsv)
    fun paint() {
        swatch.background = roundBg(now(), dp(22).toFloat(), T.border, dp(1))
        hex.text = String.format("#%06X", now() and 0xFFFFFF)
        sv.hue = hsv[0]; sv.s = hsv[1]; sv.v = hsv[2]; sv.invalidate()
        hue.hue = hsv[0]; hue.invalidate()
    }
    sv.onChange = { s, v -> hsv[1] = s; hsv[2] = v; paint() }
    hue.onChange = { h -> hsv[0] = h; paint() }
    col.addView(sv, lp(MATCH, dp(170)))
    col.addView(hue, lp(MATCH, dp(34)).apply { topMargin = dp(10) })

    fun section(label: String, colors: List<Int>, removable: Boolean = false): View? {
        if (colors.isEmpty()) return null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(tv(label, 13f, T.muted, true).apply { setPadding(0, dp(12), 0, dp(2)) })
        box.addView(colorRow(colors.toIntArray(), false, -1) { c -> Color.colorToHSV(c or 0xFF000000.toInt(), hsv); paint() }.also { v ->
            if (removable) v.setOnLongClickListener { true }
        })
        return box
    }
    section(L.t("colorsBasic"), presets.toList())?.let { col.addView(it) }
    section(L.t("colorsRecent"), prefsList(this, "colorRecent"))?.let { col.addView(it) }
    val favBox = FrameLayout(this)
    fun drawFavs() {
        favBox.removeAllViews()
        val favs = prefsList(ctx, "colorFav")
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val h = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        h.addView(tv(L.t("colorsFav"), 13f, T.muted, true).apply { setPadding(0, dp(12), 0, dp(2)) }, lp(0, WRAP, 1f))
        h.addView(tv("＋ " + L.t("colorsFavAdd"), 13f, T.accent, true).apply {
            setPadding(dp(8), dp(12), 0, dp(2))
            setOnClickListener { val l = prefsList(ctx, "colorFav"); val c = now(); l.remove(c); l.add(0, c); savePrefsList(ctx, "colorFav", l.take(20)); drawFavs() }
        })
        box.addView(h)
        if (favs.isNotEmpty()) {
            box.addView(colorRow(favs.toIntArray(), false, -1) { c -> Color.colorToHSV(c or 0xFF000000.toInt(), hsv); paint() })
            box.addView(tv(L.t("colorsFavHint"), 11.5f, T.faint))
            // mantener presionado un favorito lo quita
            ((box.getChildAt(1) as android.widget.HorizontalScrollView).getChildAt(0) as LinearLayout).let { row ->
                for (i in 0 until row.childCount) {
                    val c = favs[i]
                    row.getChildAt(i).setOnLongClickListener { savePrefsList(ctx, "colorFav", prefsList(ctx, "colorFav").filter { it != c }); drawFavs(); true }
                }
            }
        }
        favBox.addView(box)
    }
    drawFavs()
    col.addView(favBox)
    paint()
    d = dialog().setTitle(title).setView(ScrollView(this).apply { addView(col) })
        .setPositiveButton(L.t("apply")) { _, _ -> addRecentColor(ctx, now()); onPick(now()) }
        .setNeutralButton(L.t("noColor")) { _, _ -> onPick(0) }
        .setNegativeButton(L.t("cancel"), null).show()
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
        val rad = dp(12).toFloat()
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
        setPadding(dp(14), dp(8), dp(14), dp(8)); background = roundBg(T.card, dp(16).toFloat(), T.border, dp(1))
        setOnClickListener { size = z; paint() }
    }, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
    chips.addView(cr); col.addView(chips)
    paint()
    dialog().setTitle(L.t("size")).setView(col)
        .setPositiveButton(L.t("apply")) { _, _ -> onPick(if (size == T.baseSize) 0 else size) }
        .setNeutralButton(L.t("sizeNormal")) { _, _ -> onPick(0) }
        .setNegativeButton(L.t("cancel"), null).show()
}
