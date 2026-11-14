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
    const val bg = 0xFF161618.toInt()
    const val card = 0x14FFFFFF
    const val cardStrong = 0xFF26262A.toInt()
    const val border = 0x24FFFFFF
    const val text = 0xFFFFFFFF.toInt()
    const val muted = 0xADFFFFFF.toInt()
    const val faint = 0x66FFFFFF
    const val accent = 0xFF8B7CFF.toInt()
    /** Violeta de la búsqueda iluminada (como en Best Notes). */
    const val search = 0x998B5CF6.toInt()
    const val danger = 0xFFFF6B6B.toInt()
    const val sheet = 0xFF202024.toInt()

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
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
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
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, roundBg(Color.WHITE, r))
        setOnClickListener(onClick)
    }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

fun Context.confirm(title: String, msg: String?, okText: String, danger: Boolean = false, onOk: () -> Unit) {
    val d = dialog().setTitle(title).setMessage(msg).setPositiveButton(okText) { _, _ -> onOk() }
        .setNegativeButton(L.t("cancel"), null).show()
    if (danger) d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(T.danger)
}

/** Pide un texto (nombre de carpeta, contraseña…). */
fun Context.askText(title: String, initial: String = "", hint: String = "", password: Boolean = false, onOk: (String) -> Unit) {
    val e = EditText(this).apply {
        setText(initial); this.hint = hint; setSelection(text.length)
        setTextColor(T.text); setHintTextColor(T.faint)
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(e) }
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
fun Activity.edgeToEdge(root: View) {
    window.statusBarColor = T.bg
    window.navigationBarColor = T.bg
    root.setBackgroundColor(T.bg)
    root.fitsSystemWindows = true
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
