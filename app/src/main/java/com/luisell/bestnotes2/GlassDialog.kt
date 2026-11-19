package com.luisell.bestnotes2

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/* ============================================================
   GlassDialog.kt — ✨ las ventanitas de la app (preguntar, avisar,
   escoger) con el mismo vidrio bonito de los paneles:
   - entran con un rebotito y se van suave,
   - botones grandes redondeados (el principal resaltado),
   - se usan igual que las de Android (dialog().setTitle()…).
   ============================================================ */
class GlassDialog(ctx: Context) : Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar) {
    internal val buttons = HashMap<Int, TextView>()
    internal lateinit var card: View
    private var leaving = false

    /** Como en AlertDialog: el botón POSITIVE / NEGATIVE / NEUTRAL. */
    fun getButton(which: Int): TextView? = buttons[which]

    override fun show() {
        super.show()
        card.scaleX = 0.86f; card.scaleY = 0.86f; card.alpha = 0f; card.translationY = card.context.dp(18).toFloat()
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator(1.3f)).start()
    }

    override fun dismiss() {
        if (leaving || !isShowing) { if (!isShowing) super.dismiss(); return }
        leaving = true
        card.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).setDuration(160).setInterpolator(DecelerateInterpolator())
            .withEndAction { try { super.dismiss() } catch (_: Exception) {} }.start()
    }
}

/** Arma una [GlassDialog] igual que AlertDialog.Builder. */
class GlassBuilder(private val ctx: Context) {
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var view: View? = null
    private var pos: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var neg: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var neu: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var items: Array<out CharSequence>? = null
    private var itemsCb: ((DialogInterface, Int) -> Unit)? = null
    private var checked = -1
    private var single = false
    private var cancelable = true
    private var onDismiss: (() -> Unit)? = null
    var danger = false

    fun setTitle(t: CharSequence?) = apply { title = t }
    fun setTitle(res: Int) = apply { title = ctx.getString(res) }
    fun setMessage(m: CharSequence?) = apply { message = m }
    fun setView(v: View?) = apply { view = v }
    fun setPositiveButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { pos = t to l }
    fun setNegativeButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { neg = t to l }
    fun setNeutralButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { neu = t to l }
    fun setItems(a: Array<out CharSequence>, l: (DialogInterface, Int) -> Unit) = apply { items = a; itemsCb = l }
    fun setSingleChoiceItems(a: Array<out CharSequence>, sel: Int, l: (DialogInterface, Int) -> Unit) = apply { items = a; itemsCb = l; checked = sel; single = true }
    fun setCancelable(c: Boolean) = apply { cancelable = c }
    fun setOnDismissListener(l: () -> Unit) = apply { onDismiss = l }

    fun show(): GlassDialog = create().also { it.show() }

    fun create(): GlassDialog {
        val d = GlassDialog(ctx)
        val dp = { v: Int -> ctx.dp(v) }
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(if (T.light) 0x59000000 else 0x8C000000.toInt())
            setOnClickListener { if (cancelable) d.dismiss() }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = android.graphics.drawable.LayerDrawable(arrayOf(roundBg(if (T.light) 0xF7FAFAFC.toInt() else 0xF2202024.toInt(), dp(28).toFloat()), Glass.make(ctx, 28f, strong = true)))
            setPadding(dp(22), if (title == null && message == null) dp(10) else dp(22), dp(22), dp(16)); elevation = dp(18).toFloat()
        }
        d.card = card
        title?.let { card.addView(ctx.tv(it, 20f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), dp(8)) }) }
        message?.let { card.addView(ctx.tv(it, 15.5f, T.muted).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f); setPadding(dp(4), 0, dp(4), dp(10)) }) }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        view?.let { v -> (v.parent as? ViewGroup)?.removeView(v); body.addView(v, LinearLayout.LayoutParams(MATCH, WRAP)) }
        // las cajitas de texto también de vidrio (redondas, sin la rayita de Android)
        fun glassFields(v: View) {
            if (v is android.widget.EditText && v !is RichEdit) {
                v.background = roundBg(tw(0x12), dp(16).toFloat(), tw(0x38), dp(1))
                v.setPadding(dp(16), dp(13), dp(16), dp(13)); v.textSize = 16.5f
                v.setTextColor(T.text); v.setHintTextColor(T.faint); v.niceCursor()
            } else if (v is ViewGroup) for (i in 0 until v.childCount) glassFields(v.getChildAt(i))
        }
        view?.let { glassFields(it); addEyes(it) }
        items?.let { arr ->
            for ((i, t) in arr.withIndex()) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13))
                    background = if (single && i == checked) roundBg(tw(0x1F), dp(16).toFloat(), 0xFF4FC3F7.toInt(), dp(1)) else null
                    pressBg(if (single && i == checked) tw(0x1F) else 0, 16f, if (single && i == checked) 0xFF4FC3F7.toInt() else 0)
                    setOnClickListener { itemsCb?.invoke(d, i); if (!single || pos == null) d.dismiss() }
                }
                if (single) {
                    val on = i == checked
                    row.addView(View(ctx).apply { background = if (on) roundBg(0xFF4FC3F7.toInt(), dp(11).toFloat(), T.text, dp(3)) else roundBg(0, dp(11).toFloat(), T.faint, dp(2)) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
                }
                row.addView(ctx.tv(t, 16.5f, T.text, single && i == checked), LinearLayout.LayoutParams(0, WRAP, 1f))
                body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) })
            }
        }
        if (body.childCount > 0) {
            val sv = object : ScrollView(ctx) {
                override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec((ctx.resources.displayMetrics.heightPixels * 0.6f).toInt(), MeasureSpec.AT_MOST)) }
            }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
            card.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // botones
        val btns = listOfNotNull(neu?.let { Triple(DialogInterface.BUTTON_NEUTRAL, it, 0) }, neg?.let { Triple(DialogInterface.BUTTON_NEGATIVE, it, 1) }, pos?.let { Triple(DialogInterface.BUTTON_POSITIVE, it, 2) })
        if (btns.isNotEmpty()) {
            val long = btns.size == 3 || btns.any { it.second.first.length > 14 }
            val row = LinearLayout(ctx).apply { orientation = if (long) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            val order = if (long) btns.sortedByDescending { it.third } else btns
            for ((which, b, kind) in order) {
                val (label, cb) = b
                val primary = kind == 2
                val tvb = ctx.tv(label, 16f, if (primary) (if (danger) Color.WHITE else T.onPill) else T.text, true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(12), dp(13), dp(12), dp(13)); maxLines = 2
                    when {
                        primary -> pressBg(if (danger) 0xFFE53935.toInt() else T.pill, 24f)
                        kind == 1 -> pressBg(0, 24f, tw(0x40))
                        else -> pressBg(0, 24f)
                    }
                    setOnClickListener { v ->
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
                        cb?.invoke(d, which); d.dismiss()
                    }
                }
                d.buttons[which] = tvb
                if (long) row.addView(tvb, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
                else row.addView(tvb, LinearLayout.LayoutParams(0, WRAP, if (primary) 1.3f else 1f).apply { marginStart = dp(5); marginEnd = dp(5) })
            }
            card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(22), dp(40), dp(22), dp(40)) })
        d.setContentView(root)
        d.setCancelable(cancelable)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(MATCH, MATCH)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            w.statusBarColor = Color.TRANSPARENT
        }
        onDismiss?.let { l -> d.setOnDismissListener { l() } }
        return d
    }
}

/* =========================================================
   🔒 El teclado del PIN (crear, cambiar o escribir el PIN):
   un candado, 4 bolitas que se llenan y teclas redondas de vidrio.
   ========================================================= */
object PinPad {
    /**
     * [title] arriba. [onDone] recibe los 4 números; si devuelve false (PIN equivocado),
     * las bolitas tiemblan y se borran para volver a intentar.
     */
    fun show(ctx: Context, title: String, sub: String? = null, onBio: (() -> Unit)? = null, onCancel: (() -> Unit)? = null, onDone: (String) -> Boolean) {
        val d = GlassDialog(ctx)
        val dp = { v: Int -> ctx.dp(v) }
        val root = FrameLayout(ctx).apply { setBackgroundColor(if (T.light) 0x66000000 else 0xA6000000.toInt()) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true
            background = android.graphics.drawable.LayerDrawable(arrayOf(roundBg(if (T.light) 0xF7FAFAFC.toInt() else 0xF21C1C20.toInt(), dp(34).toFloat()), Glass.make(ctx, 34f, strong = true)))
            setPadding(dp(18), dp(18), dp(18), dp(14)); elevation = dp(20).toFloat()
        }
        d.card = card
        var entered = ""
        // arriba: ← · candado
        val head = FrameLayout(ctx)
        head.addView(ctx.glassBtn(Icons.BACK) { d.dismiss(); onCancel?.invoke() }.apply { layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL) })
        val lock = IconView(ctx, Icons.LOCK, 16f).apply { color = T.text; isClickable = false }
        head.addView(lock, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(ctx.tv(title, 21f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(8), dp(6), if (sub != null) dp(2) else dp(14)) })
        val subTv = ctx.tv(sub ?: "", 13.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), dp(14)); visibility = if (sub != null) View.VISIBLE else View.GONE }
        card.addView(subTv)
        // las 4 bolitas
        val dotsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(18)) }
        val dots = (0 until 4).map { View(ctx).also { v -> dotsRow.addView(v, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10); marginEnd = dp(10) }) } }
        fun paintDots() {
            dots.forEachIndexed { i, v -> v.background = if (i < entered.length) roundBg(0xFF4FC3F7.toInt(), dp(9).toFloat()) else roundBg(0, dp(9).toFloat(), T.text, dp(2)) }
        }
        paintDots()
        card.addView(dotsRow)
        fun shake() {
            dotsRow.animate().translationX(dp(14).toFloat()).setDuration(50).withEndAction {
                dotsRow.animate().translationX(-dp(12).toFloat()).setDuration(60).withEndAction {
                    dotsRow.animate().translationX(dp(8).toFloat()).setDuration(60).withEndAction {
                        dotsRow.animate().translationX(0f).setDuration(60).start()
                    }.start()
                }.start()
            }.start()
            try { (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(android.os.VibrationEffect.createOneShot(80, 120)) } catch (_: Throwable) {}
            dots.forEach { it.background = roundBg(0xFFFF453A.toInt(), dp(9).toFloat()) }
            card.postDelayed({ entered = ""; paintDots() }, 420)
        }
        fun press(k: String) {
            if (k == "<") { if (entered.isNotEmpty()) { entered = entered.dropLast(1); paintDots() }; return }
            if (entered.length >= 4) return
            entered += k; paintDots()
            val dv = dots[entered.length - 1]
            dv.scaleX = 0.4f; dv.scaleY = 0.4f; dv.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
            if (entered.length == 4) {
                val v = entered
                card.postDelayed({
                    if (onDone(v)) {
                        Anim.sparkle(lock, 70f, 700)
                        dots.forEach { Anim.bump(it, 1.4f) }
                        // ✓ se abre el candado y se va
                        lock.animate().rotation(-12f).scaleX(1.15f).scaleY(1.15f).setDuration(160).withEndAction { d.dismiss() }.start()
                    } else shake()
                }, 140)
            }
        }
        // el teclado: 1-9, ·, 0, ⌫
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        for (r in 0 until 4) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (c in 0 until 3) {
                val k = keys[r * 3 + c]
                val v: View = when {
                    k.isEmpty() && onBio != null -> FingerView(ctx).apply { color = 0xFF4FC3F7.toInt() }
                    k.isEmpty() -> View(ctx)
                    k == "<" -> IconView(ctx, Icons.DELETE, 11f).apply { color = T.text }
                    else -> ctx.tv(k, 30f, T.text, true).apply { gravity = Gravity.CENTER }
                }
                if (k.isEmpty() && onBio != null) {
                    v.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tw(0x40)), Glass.make(ctx, 40f), roundBg(Color.WHITE, dp(40).toFloat()))
                    v.setOnClickListener { Bio.ask(ctx, title) { lock.animate().rotation(-12f).scaleX(1.15f).scaleY(1.15f).setDuration(160).withEndAction { d.dismiss() }.start(); onBio() } }
                }
                if (k.isNotEmpty()) {
                    v.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tw(0x40)), Glass.make(ctx, 40f), roundBg(Color.WHITE, dp(40).toFloat()))
                    v.setOnClickListener { x ->
                        x.animate().scaleX(0.88f).scaleY(0.88f).setDuration(60).withEndAction { x.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(2.5f)).start() }.start()
                        press(k)
                    }
                    if (k == "<") v.setOnLongClickListener { entered = ""; paintDots(); true }
                }
                row.addView(v, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(9), dp(7), dp(9), dp(7)) })
            }
            grid.addView(row)
        }
        card.addView(grid)
        card.addView(ctx.tv(L.t("cancel"), 16f, T.muted, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(14), dp(24), dp(8))
            setOnClickListener { d.dismiss(); onCancel?.invoke() }
        })
        root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(16), dp(24), dp(16), dp(24)) })
        d.setContentView(root)
        d.setOnCancelListener { onCancel?.invoke() }
        d.window?.let { w -> w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); w.setLayout(MATCH, MATCH); w.statusBarColor = Color.TRANSPARENT }
        d.show()
        // el candado se mueve un poquito al entrar
        lock.rotation = -20f; lock.animate().rotation(0f).setDuration(600).setInterpolator(OvershootInterpolator(3f)).start()
        // 👆 si se puede, la huella se pide sola
        if (onBio != null) card.postDelayed({ if (d.isShowing) Bio.ask(ctx, title) { lock.animate().rotation(-12f).scaleX(1.15f).scaleY(1.15f).setDuration(160).withEndAction { d.dismiss() }.start(); onBio() } }, 450)
    }
}
