package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/* ============================================================
   OcrPick.kt — 🔎 Escoger las palabras de una foto (como Google
   Lens): la foto se ve en grande con cada palabra marcada.
   Tocas una palabra para escogerla, o deslizas el dedo para
   escoger varias seguidas. Luego: Copiar, Traducir o ponerla
   en la nota. Con dos dedos haces zoom.
   ============================================================ */
object OcrPick {
    fun show(act: Activity, f: File, onPut: ((String) -> Unit)?, onClose: (() -> Unit)? = null) {
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        val wv = WordsView(act)
        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val close = act.glassBtn(Icons.CLOSE, 9f, Color.WHITE) { d.dismiss() }
        root.addView(close, FrameLayout.LayoutParams(act.dp(46), act.dp(46), Gravity.TOP or Gravity.END).apply { setMargins(0, act.dp(36), act.dp(14), 0) })
        val hint = act.tv("🔎 " + L.t("ocrReading"), 14f, Color.WHITE).apply {
            background = Glass.make(act, 20f, strong = true); setPadding(act.dp(16), act.dp(9), act.dp(16), act.dp(9))
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(act.dp(70), act.dp(42), act.dp(70), 0) })

        // ⬇ el panel de abajo: lo escogido + los botones
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = Glass.make(act, 26f, strong = true)
            setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(12)); visibility = View.INVISIBLE
        }
        val preview = act.tv("", 15.5f, Color.WHITE).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
        val count = act.tv("", 12f, 0xB3FFFFFF.toInt()).apply { setPadding(0, act.dp(4), 0, act.dp(10)) }
        panel.addView(preview); panel.addView(count)
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun pill(label: String, color: Int, go: (TextView) -> Unit): TextView = act.tv(label, 13.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; maxLines = 1; setPadding(act.dp(6), act.dp(11), act.dp(6), act.dp(11))
            background = roundBg(color, act.dp(20).toFloat())
            setOnClickListener { v -> Anim.bump(v); go(this) }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(3), 0, act.dp(3), 0) }) }
        panel.addView(row)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(act.dp(12), 0, act.dp(12), act.dp(30)) })

        fun textNow() = wv.selectedText().ifEmpty { wv.allText() }
        val allBtn = pill(L.t("ocrAll"), 0x33FFFFFF) { b -> wv.selectAll(!wv.allSelected()); b.text = L.t(if (wv.allSelected()) "ocrNoneSel" else "ocrAll") }
        pill(L.t("copy"), 0xFF3A7BFF.toInt()) {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("foto", textNow()))
            act.toast(L.t("copied"))
        }
        pill("🌐 " + L.t("ocrTranslate"), 0xFF7A5CFF.toInt()) { translate(act, textNow(), onPut, d) }
        if (onPut != null) pill(L.t("ocrPutShort"), 0xFF2FA866.toInt()) { onPut(textNow()); d.dismiss() }

        fun update() {
            val t = wv.selectedText()
            preview.text = t.ifEmpty { "👆 " + L.t("ocrPickHint") }
            preview.alpha = if (t.isEmpty()) 0.75f else 1f
            val n = wv.selectedCount()
            count.text = if (n == 0) L.t("ocrNoSel") else L.t("ocrNWords", "n" to n)
            allBtn.text = L.t(if (wv.allSelected()) "ocrNoneSel" else "ocrAll")
        }
        wv.onChange = { update() }

        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.setOnDismissListener { wv.stop(); onClose?.invoke() }
        d.show()
        Ocr.words(act, f, onImage = { bm -> wv.setBitmap(bm) }) { bm, words ->
            if (!d.isShowing) return@words
            if (bm == null) { act.toast(L.t("cantOpen")); d.dismiss(); return@words }
            wv.setBitmap(bm)
            if (words.isEmpty()) { wv.setWords(words); hint.text = L.t("ocrNone"); return@words }
            wv.setWords(words)
            hint.animate().alpha(0f).setDuration(250).withEndAction { hint.visibility = View.GONE }.start()
            update()
            // ✨ el panel sube rebotando
            panel.visibility = View.VISIBLE; panel.alpha = 0f; panel.translationY = act.dp(120).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        }
    }

    /** 🌐 Traducir lo escogido (con la IA gratis). Chino → español, español → inglés. */
    private fun translate(act: Activity, text: String, onPut: ((String) -> Unit)?, pick: Dialog) {
        val wait = act.dialog().setMessage("🌐 " + L.t("translating")).setCancelable(false).show()
        Thread {
            val r = Ai.ask(Ai.system("translate", ""), text)?.let { Ai.clean(it) }
            act.runOnUiThread {
                wait.dismiss()
                if (r.isNullOrBlank()) { act.toast(L.t("aiFail")); return@runOnUiThread }
                val e = android.widget.EditText(act).apply { setText(r); setTextColor(T.text); textSize = 15f; background = null; setPadding(act.dp(18), act.dp(6), act.dp(18), act.dp(6)) }
                val b = act.dialog().setTitle("🌐 " + L.t("ocrTranslate")).setView(e)
                    .setNeutralButton(L.t("copy")) { _, _ ->
                        (act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("foto", e.text.toString())); act.toast(L.t("copied"))
                    }.setNegativeButton(L.t("cancel"), null)
                if (onPut != null) b.setPositiveButton(L.t("ocrPutShort")) { _, _ -> onPut(e.text.toString()); pick.dismiss() }
                b.show()
            }
        }.start()
    }
}

/** La foto con las palabras marcadas (tocar / deslizar para escoger, dos dedos para zoom). */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class WordsView(ctx: Context) : View(ctx) {
    private var bm: Bitmap? = null
    private var words: List<Ocr.Word> = emptyList()
    private var sel = BooleanArray(0)
    private var cur = FloatArray(0)               // la animación de cada palabra (0 = sin escoger, 1 = escogida)
    private val m = Matrix(); private val inv = Matrix()
    private var base = 1f
    private var scanning = true
    private var appear = 0f
    private var anim: ValueAnimator? = null
    var onChange: (() -> Unit)? = null
    private val dpF = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dim = Paint()
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scanP = Paint()
    private val r = RectF()

    fun setBitmap(b: Bitmap) { if (bm === b) return; bm = b; fit(); invalidate() }
    fun setWords(w: List<Ocr.Word>) {
        words = w; sel = BooleanArray(w.size); cur = FloatArray(w.size); scanning = false
        anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 900; addUpdateListener { appear = it.animatedValue as Float; invalidate() }; start() }
    }
    fun stop() { anim?.cancel(); scanning = false }

    private fun fit() {
        val b = bm ?: return
        if (width == 0) return
        val top = 90 * dpF; val bottom = 190 * dpF
        val h = height - top - bottom
        base = minOf(width.toFloat() / b.width, h / b.height)
        m.reset(); m.postScale(base, base); m.postTranslate((width - b.width * base) / 2, top + (h - b.height * base) / 2)
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    override fun onDraw(c: Canvas) {
        val b = bm ?: return
        c.drawBitmap(b, m, bmpP)
        r.set(0f, 0f, b.width.toFloat(), b.height.toFloat()); m.mapRect(r)
        if (scanning) {
            // 🔎 una luz que baja leyendo la foto
            val t = (android.os.SystemClock.uptimeMillis() % 1500) / 1500f
            val y = r.top + r.height() * t
            scanP.shader = LinearGradient(0f, y - 90 * dpF, 0f, y, 0x00000000, (0x88 shl 24) or (T.accent and 0xFFFFFF), Shader.TileMode.CLAMP)
            c.drawRect(r.left, maxOf(r.top, y - 90 * dpF), r.right, y, scanP)
            line.color = T.accent; line.strokeWidth = 2.5f * dpF; c.drawLine(r.left, y, r.right, y, line)
            postInvalidateOnAnimation(); return
        }
        if (words.isEmpty()) return
        dim.color = Color.argb((95 * appear).toInt(), 0, 0, 0); c.drawRect(r, dim)
        var moving = false
        val n = words.size
        val rad = 5 * dpF
        for (i in 0 until n) {
            val k = ((appear * 1.5f) - (i.toFloat() / n) * 0.5f).coerceIn(0f, 1f)
            if (k <= 0f) continue
            val goal = if (sel[i]) 1f else 0f
            if (Math.abs(cur[i] - goal) > 0.01f) { cur[i] += (goal - cur[i]) * 0.35f; moving = true } else cur[i] = goal
            val s = cur[i]
            r.set(words[i].box); m.mapRect(r); r.inset(-3 * dpF, -2 * dpF)
            // al escogerla da un saltito
            val pop = 1f + 0.18f * s * (1f - s) * 4f * 0.5f
            val cx = r.centerX(); val cy = r.centerY()
            r.set(cx - r.width() / 2 * pop, cy - r.height() / 2 * pop, cx + r.width() / 2 * pop, cy + r.height() / 2 * pop)
            fill.color = blend(Color.argb((60 * k).toInt(), 255, 255, 255), (0xAA shl 24) or (T.accent and 0xFFFFFF), s)
            c.drawRoundRect(r, rad, rad, fill)
            line.strokeWidth = 1.2f * dpF; line.color = blend(Color.argb((150 * k).toInt(), 255, 255, 255), T.accent, s)
            c.drawRoundRect(r, rad, rad, line)
        }
        if (moving || appear < 1f) postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /* ---------- escoger ---------- */
    fun selectedCount() = sel.count { it }
    fun allSelected() = sel.isNotEmpty() && sel.all { it }
    fun selectAll(on: Boolean) { sel.fill(on); invalidate(); onChange?.invoke() }
    fun selectedText() = join { sel[it] }
    fun allText() = join { true }
    private fun join(take: (Int) -> Boolean): String {
        val sb = StringBuilder(); var last = -1
        for (i in words.indices) {
            if (!take(i)) continue
            if (last >= 0) sb.append(if (words[i].line != words[last].line) "\n" else if (words[i].cjk && words[last].cjk) "" else " ")
            sb.append(words[i].text); last = i
        }
        return sb.toString()
    }

    /** Qué palabra hay debajo del dedo ([far] = acepta la más cercana aunque no esté justo encima). */
    private fun hit(x: Float, y: Float, far: Boolean): Int {
        var best = -1; var bd = Float.MAX_VALUE
        val lim = (if (far) 34f else 12f) * dpF
        for (i in words.indices) {
            r.set(words[i].box); m.mapRect(r)
            val dx = maxOf(r.left - x, 0f, x - r.right); val dy = maxOf(r.top - y, 0f, y - r.bottom)
            val dd = dx * dx + dy * dy * 2.5f
            if (dd < bd) { bd = dd; best = i }
        }
        return if (bd <= lim * lim) best else -1
    }

    private var anchor = -1; private var adding = true; private var before = BooleanArray(0); private var lastJ = -1
    private var lastX = 0f; private var lastY = 0f; private var multi = false
    private val sg = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val now = scale(); val k = (now * d.scaleFactor).coerceIn(base, base * 6) / now
            m.postScale(k, k, d.focusX, d.focusY); clamp(); invalidate(); return true
        }
    })
    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }
    private fun clamp() {
        val b = bm ?: return
        r.set(0f, 0f, b.width.toFloat(), b.height.toFloat()); m.mapRect(r)
        val dx = if (r.width() <= width) width / 2f - r.centerX() else if (r.left > 0) -r.left else if (r.right < width) width - r.right else 0f
        val dy = if (r.height() <= height) height / 2f - r.centerY() else if (r.top > 0) -r.top else if (r.bottom < height) height - r.bottom else 0f
        if (scale() <= base * 1.01f) { fit(); return }
        m.postTranslate(dx, dy)
    }

    private fun apply(j: Int) {
        if (j == lastJ) return
        lastJ = j
        sel = before.copyOf()
        for (k in minOf(anchor, j)..maxOf(anchor, j)) sel[k] = adding
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate(); onChange?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (words.isEmpty() && bm == null) return false
        sg.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false; lastX = e.x; lastY = e.y; lastJ = -1
                val i = hit(e.x, e.y, false)
                anchor = i
                if (i >= 0) { adding = !sel[i]; before = sel.copyOf(); apply(i) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                if (anchor >= 0) { sel = before; anchor = -1; invalidate(); onChange?.invoke() }   // dos dedos = zoom, no escoger
                lastX = (e.getX(0) + e.getX(1)) / 2; lastY = (e.getY(0) + e.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi && e.pointerCount >= 2) {
                    val x = (e.getX(0) + e.getX(1)) / 2; val y = (e.getY(0) + e.getY(1)) / 2
                    m.postTranslate(x - lastX, y - lastY); clamp(); lastX = x; lastY = y; invalidate()
                } else if (anchor >= 0) {
                    val j = hit(e.x, e.y, true); if (j >= 0) apply(j)
                } else if (!multi && scale() > base * 1.01f) {
                    m.postTranslate(e.x - lastX, e.y - lastY); clamp(); lastX = e.x; lastY = e.y; invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { anchor = -1; multi = false }
        }
        return true
    }
}
