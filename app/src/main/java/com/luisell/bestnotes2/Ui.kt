package com.luisell.bestnotes2

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/* ============================================================
   Ui.kt — colores, medidas y piezas de pantalla que se repiten.
   Diseño oscuro tipo "vidrio", como Best Notes.
   ============================================================ */

object T {
    /** ☀️ Modo claro (se escoge en Apariencia). */
    @JvmStatic var light = false
    val bg get() = if (light) 0xFFF2F2F7.toInt() else 0xFF161618.toInt()
    val card get() = if (light) 0x0F000000 else 0x14FFFFFF
    val cardStrong get() = if (light) 0xFFFFFFFF.toInt() else 0xFF26262A.toInt()
    val border get() = if (light) 0x1F000000 else 0x24FFFFFF
    val text get() = if (light) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    val muted get() = if (light) 0x99000000.toInt() else 0xADFFFFFF.toInt()
    val faint get() = if (light) 0x59000000 else 0x66FFFFFF
    const val accent = 0xFF8B7CFF.toInt()
    /** Violeta de la búsqueda iluminada (como en Best Notes). */
    val search get() = if (light) 0x668B5CF6 else 0x998B5CF6.toInt()
    const val danger = 0xFFFF6B6B.toInt()
    val sheet get() = if (light) 0xFFF7F7FA.toInt() else 0xFF202024.toInt()
    /** Botón "fuerte" (blanco en oscuro, negro en claro) y su letra. */
    val pill get() = if (light) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    val onPill get() = if (light) 0xFFFFFFFF.toInt() else 0xFF161618.toInt()

    /** Colores para notas y carpetas (los mismos de Best Notes). */
    val noteColors = intArrayOf(
        0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF00ACC1.toInt(),
        0xFF1E88E5.toInt(), 0xFF3949AB.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFF757575.toInt(),
    )

    /** Colores de letra del editor (como el editor rápido de Best Notes). */
    val textColors = intArrayOf(
        0xFFFF453A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt(),
        0xFFBF5AF2.toInt(), 0xFF8E8E93.toInt(), 0xFFFFFFFF.toInt(), 0xFF64D2FF.toInt(), 0xFFFF375F.toInt(),
    )
    val hiliteColors = intArrayOf(
        0x73FFD60A, 0x6630D158, 0x660A84FF, 0x66FF453A, 0x66BF5AF2, 0x66FF9F0A,
    )
    val sizes = intArrayOf(12, 14, 17, 20, 24, 30, 38, 48, 64, 80, 100)
    const val baseSize = 17
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun View.dp(v: Int): Int = context.dp(v)
fun View.dp(v: Float): Int = context.dp(v)

fun roundBg(color: Int, radiusPx: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

/** Fondo redondo que se ilumina al tocar. */
fun View.pressBg(color: Int, radiusDp: Float, stroke: Int = 0) {
    val r = context.dp(radiusDp).toFloat()
    val content = roundBg(color, r, stroke, if (stroke != 0) context.dp(1) else 0)
    val mask = roundBg(Color.WHITE, r)
    background = RippleDrawable(ColorStateList.valueOf(tw(0x33)), content, mask)
}

fun Context.tv(text: CharSequence = "", sizeSp: Float = 16f, color: Int = T.text, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.iconBtn(kind: Int, sizeDp: Float = 11f, color: Int = T.text, onClick: (View) -> Unit): IconView =
    IconView(this, kind, sizeDp).apply {
        this.color = color
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        val r = dp(22).toFloat()
        background = RippleDrawable(ColorStateList.valueOf(tw(0x33)), null, roundBg(Color.WHITE, r))
        setOnClickListener(onClick)
    }

/** Un blanco transparente (o negro transparente en modo claro), para fondos y rayitas. */
fun tw(a: Int): Int = if (T.light) ((a * 0.75f).toInt() shl 24) else (a shl 24) or 0xFFFFFF

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** Las ventanitas de la app, con el vidrio bonito (ver GlassDialog.kt). */
fun Context.dialog(): GlassBuilder = GlassBuilder(this)

fun Context.confirm(title: String, msg: String?, okText: String, danger: Boolean = false, onOk: () -> Unit) {
    dialog().apply { this.danger = danger }.setTitle(title).setMessage(msg).setPositiveButton(okText) { _, _ -> onOk() }
        .setNegativeButton(L.t("cancel"), null).show()
}

/** Pide un texto (nombre de carpeta, contraseña…). */
fun Context.askText(title: String, initial: String = "", hint: String = "", password: Boolean = false, onOk: (String) -> Unit) {
    val e = EditText(this).apply {
        setText(initial); this.hint = hint; setSelection(text.length)
        setTextColor(T.text); setHintTextColor(T.faint)
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    val box = FrameLayout(this).apply { setPadding(0, dp(4), 0, dp(4)); addView(e) }
    val d = dialog().setTitle(title).setView(box)
        .setPositiveButton(L.t("ok")) { _, _ -> onOk(e.text.toString()) }
        .setNegativeButton(L.t("cancel"), null).create()
    d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    d.show()
    e.requestFocus()
}

/** Fila de bolitas de colores para escoger. [withNone] agrega "sin color" al principio. */
fun Context.colorRow(colors: IntArray, withNone: Boolean, selected: Int, onPick: (Int) -> Unit): View {
    val sv = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(6), dp(14), dp(6)) }
    val all = if (withNone) intArrayOf(0) + colors else colors
    for (c in all) {
        val v = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(cv: android.graphics.Canvas) {
                val r = width / 2f - dp(3)
                if (c == 0) {
                    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = T.muted
                    cv.drawCircle(width / 2f, height / 2f, r, p)
                    cv.drawLine(width / 2f - r * 0.7f, height / 2f + r * 0.7f, width / 2f + r * 0.7f, height / 2f - r * 0.7f, p)
                } else {
                    p.style = android.graphics.Paint.Style.FILL; p.color = c
                    cv.drawCircle(width / 2f, height / 2f, r, p)
                }
                if (c == selected) {
                    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2.5f).toFloat(); p.color = T.text
                    cv.drawCircle(width / 2f, height / 2f, width / 2f - dp(1), p)
                }
            }
        }
        v.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) }
        v.setOnClickListener { onPick(c) }
        row.addView(v)
    }
    sv.addView(row)
    return sv
}

/** Barra de arriba con la altura de la barra de estado ya sumada. */
fun Activity.edgeToEdge(root: View, windowBg: Boolean = true) {
    // El fondo de vidrio de Best Notes se ve también detrás de la barra de arriba
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = if (T.light) 0xFFF2F2F7.toInt() else 0xFF1F1F1F.toInt()
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        (if (T.light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0) else 0)
    root.background = null
    root.fitsSystemWindows = true
    // 🌍 árabe, hebreo, persa y urdu: la pantalla se acomoda de derecha a izquierda
    window.decorView.layoutDirection = if (L.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    // 📱 modo translúcido: se ve el fondo de pantalla del teléfono detrás de la app
    if (Wall.current(this) == "phone") {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
    } else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    if (windowBg) window.setBackgroundDrawable(Glass.background(this))       // el fondo escogido en ☀️ Apariencia
}

/** Botón redondo de vidrio (como los de arriba en Best Notes). */
fun Context.glassBtn(kind: Int, sizeDp: Float = 10f, color: Int = T.text, onClick: (View) -> Unit): IconView =
    IconView(this, kind, sizeDp).apply {
        this.color = color
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(3); marginEnd = dp(3) }
        background = RippleDrawable(ColorStateList.valueOf(tw(0x33)), Glass.make(context, 22f), roundBg(Color.WHITE, dp(22).toFloat()))
        setOnClickListener { it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start(); onClick(it) }
    }

fun menuRow(ctx: Context, icon: Int, label: String, sub: String? = null, color: Int = T.text, iconColor: Int = 0, onClick: () -> Unit): View {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        pressBg(0, 14f)
        setOnClickListener { onClick() }
    }
    row.addView(IconView(ctx, icon, 9.5f).apply { this.color = if (iconColor != 0) iconColor else if (color == T.text) T.muted else color; isClickable = false }, lp(ctx.dp(26), ctx.dp(26)))
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(14), 0, 0, 0) }
    col.addView(ctx.tv(label, 16f, color))
    if (sub != null) col.addView(ctx.tv(sub, 12.5f, T.muted))
    row.addView(col, lp(0, WRAP, 1f))
    return row
}

/* ---------- 👁 el ojito para ver la contraseña mientras la escribes ---------- */
private fun EditText.isPass(): Boolean {
    val v = inputType and android.text.InputType.TYPE_MASK_VARIATION
    return (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT &&
        (v == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD || v == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
}

/** Le pone el ojito 👁 a las cajitas de contraseña que haya dentro de [root] (una sola vez). */
fun addEyes(root: View) {
    val found = ArrayList<EditText>()
    fun walk(v: View) { if (v is EditText) { if (v.isPass() && v.tag != "eye") found.add(v) } else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
    walk(root)
    for (e in found) {
        val parent = e.parent as? ViewGroup ?: continue
        val idx = parent.indexOfChild(e); val lpE = e.layoutParams
        parent.removeViewAt(idx)
        val ctx = e.context
        val box = FrameLayout(ctx)
        e.tag = "eye"
        e.typeface = android.graphics.Typeface.DEFAULT
        e.setPaddingRelative(e.paddingStart, e.paddingTop, e.paddingEnd + ctx.dp(40), e.paddingBottom)
        box.addView(e, FrameLayout.LayoutParams(MATCH, WRAP))
        var shown = false
        val eye = IconView(ctx, Icons.EYE, 8.5f).apply { color = T.muted; contentDescription = L.t("showPass") }
        eye.setOnClickListener {
            shown = !shown
            val sel = e.selectionEnd
            e.inputType = android.text.InputType.TYPE_CLASS_TEXT or if (shown) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            e.typeface = android.graphics.Typeface.DEFAULT
            e.setSelection(sel.coerceIn(0, e.text.length))
            eye.kind = if (shown) Icons.EYE_OFF else Icons.EYE
            eye.color = if (shown) 0xFF4FC3F7.toInt() else T.muted
            eye.animate().scaleX(0.75f).scaleY(0.75f).setDuration(70).withEndAction { eye.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
        }
        box.addView(eye, FrameLayout.LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ctx.dp(4) })
        parent.addView(box, idx, lpE ?: ViewGroup.LayoutParams(MATCH, WRAP))
    }
}
