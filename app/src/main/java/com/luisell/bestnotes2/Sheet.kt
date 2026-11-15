package com.luisell.bestnotes2

import android.app.Activity
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/* ============================================================
   Sheet.kt — ✨ el panel bonito que sube desde abajo (vidrio).
   - Sube con un rebotito, el fondo se oscurece suave.
   - Las opciones entran una por una (cascada) y pasa un brillo.
   - Botones redondos arriba para lo más usado.
   - Se cierra tocando afuera, con "atrás" o deslizando hacia abajo.
   ============================================================ */
class Sheet(private val act: Activity, private val title: String? = null, private val sub: String? = null) {
    companion object {
        private val open = HashMap<Activity, Sheet>()
        /** Para el botón "atrás": cierra el panel abierto (si hay). */
        fun closeTop(a: Activity): Boolean { val s = open[a] ?: return false; s.dismiss(); return true }

        // colores bonitos para los íconos
        const val VIOLET = 0xFFB388FF.toInt()
        const val BLUE = 0xFF64B5F6.toInt()
        const val AMBER = 0xFFFFD54F.toInt()
        const val GREEN = 0xFF81C784.toInt()
        const val TEAL = 0xFF4DD0E1.toInt()
        const val PINK = 0xFFF48FB1.toInt()
        const val ORANGE = 0xFFFFB74D.toInt()
        const val GRAY = 0xFFB0BEC5.toInt()
        const val RED = 0xFFFF6B6B.toInt()
        const val SKY = 0xFF4FC3F7.toInt()
    }

    private val d = act.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d + 0.5f).toInt()
    private val quick = ArrayList<View>()
    private val rows = ArrayList<View>()
    private lateinit var overlay: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var scrim: View
    private var closing = false
    var onClose: (() -> Unit)? = null

    /** Botón redondo grande arriba (lo más usado). [on] = encendido. */
    fun quick(icon: Int, label: String, color: Int, on: Boolean = false, keepOpen: Boolean = false, action: (TextView) -> Unit): Sheet {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val circle = FrameLayout(act).apply {
            background = if (on) roundBg((color and 0xFFFFFF) or 0x55000000, dp(26f).toFloat(), color, dp(1.5f))
            else Glass.make(act, 26f)
        }
        circle.addView(IconView(act, icon, 10.5f).apply { this.color = if (on) 0xFFFFFFFF.toInt() else color; isClickable = false },
            FrameLayout.LayoutParams(dp(52f), dp(52f)))
        box.addView(circle, LinearLayout.LayoutParams(dp(52f), dp(52f)))
        val lbl = act.tv(label, 12f, if (on) color else T.muted, on).apply { gravity = Gravity.CENTER; setPadding(0, dp(5f), 0, 0); maxLines = 1 }
        box.addView(lbl)
        box.setOnClickListener {
            circle.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
                circle.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
            if (keepOpen) action(lbl) else dismiss { action(lbl) }
        }
        quick.add(box)
        return this
    }

    /** Una opción de la lista: ícono de color en una bolita, texto y (si quieres) una línea abajo. */
    fun item(icon: Int, label: String, color: Int = SKY, sub: String? = null, danger: Boolean = false, action: () -> Unit): Sheet {
        val c = if (danger) RED else color
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(9f), dp(10f), dp(9f)); pressBg(0, 16f)
        }
        val badge = FrameLayout(act).apply { background = roundBg((c and 0xFFFFFF) or 0x2E000000, dp(13f).toFloat()) }
        badge.addView(IconView(act, icon, 8.5f).apply { this.color = c; isClickable = false }, FrameLayout.LayoutParams(dp(38f), dp(38f)))
        row.addView(badge, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, dp(6f), 0) }
        txt.addView(act.tv(label, 16f, if (danger) RED else T.text, false))
        if (!sub.isNullOrEmpty()) txt.addView(act.tv(sub, 12.5f, T.muted).apply { maxLines = 2 })
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!danger) row.addView(IconView(act, Icons.CHEVRON, 6f).apply { this.color = 0x55FFFFFF; isClickable = false }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
        row.setOnClickListener { v ->
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
            dismiss(action)
        }
        rows.add(row)
        return this
    }

    /** Una línea suave para separar grupos. */
    fun divider(): Sheet { rows.add(View(act).apply { setBackgroundColor(0x14FFFFFF); tag = "div" }); return this }

    /** Cualquier otra cosa (por ejemplo la lista del historial). */
    fun custom(v: View): Sheet { rows.add(v); return this }

    fun show(): Sheet {
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        overlay = FrameLayout(act).apply { isClickable = true; isFocusable = true }
        scrim = View(act).apply { setBackgroundColor(0x8C000000.toInt()); alpha = 0f; setOnClickListener { dismiss() } }
        overlay.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            // vidrio sobre un fondo oscuro (para que no se mezcle con lo de atrás)
            background = android.graphics.drawable.LayerDrawable(arrayOf(roundBg(0xE61C1C20.toInt(), dp(30f).toFloat()), Glass.make(act, 30f, strong = true)))
            setPadding(dp(8f), dp(8f), dp(8f), dp(14f))
            isClickable = true; elevation = dp(16f).toFloat()
        }
        // manija para deslizar
        val head = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(2f), 0, dp(6f)) }
        head.addView(View(act).apply { background = roundBg(0x59FFFFFF, dp(3f).toFloat()) }, LinearLayout.LayoutParams(dp(40f), dp(5f)))
        if (title != null) {
            head.addView(act.tv(title, 19f, T.text, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(16f), dp(10f), dp(16f), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (sub != null) head.addView(act.tv(sub, 12.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(16f), dp(2f), dp(16f), 0) })
        }
        panel.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        dragToClose(head)

        if (quick.isNotEmpty()) {
            val qr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4f), dp(8f), dp(4f), dp(10f)) }
            for (q in quick) qr.addView(q, LinearLayout.LayoutParams(0, WRAP, 1f))
            panel.addView(qr)
            panel.addView(View(act).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(MATCH, dp(1f)).apply { setMargins(dp(14f), 0, dp(14f), dp(4f)) })
        }
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        for (r in rows) {
            if (r.tag == "div") list.addView(r, LinearLayout.LayoutParams(MATCH, dp(1f)).apply { setMargins(dp(60f), dp(4f), dp(14f), dp(4f)) })
            else list.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val sv = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) {
                val max = (act.resources.displayMetrics.heightPixels * 0.62f).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
        panel.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))

        val pf = FrameLayout(act)                       // el panel + el brillo que pasa encima
        pf.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP))
        val shine = Shine(act); pf.addView(shine, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay.addView(pf, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(8f), 0, dp(8f), dp(10f) + navInset()) })
        content.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        open[act]?.dismissNow(); open[act] = this

        // ✨ entrada
        scrim.animate().alpha(1f).setDuration(260).start()
        pf.alpha = 0f
        pf.post {
            pf.translationY = pf.height.toFloat() * 0.6f + dp(40f)
            pf.scaleX = 0.94f; pf.scaleY = 0.94f; pf.alpha = 1f
            pf.pivotY = pf.height.toFloat()
            pf.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(OvershootInterpolator(0.9f)).start()
            var i = 0
            for (q in quick) {
                q.scaleX = 0.4f; q.scaleY = 0.4f; q.alpha = 0f
                q.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120L + i++ * 45).setDuration(380).setInterpolator(OvershootInterpolator(2.4f)).start()
            }
            var k = 0
            for (r in rows) {
                r.alpha = 0f; r.translationY = dp(18f).toFloat(); r.translationX = dp(10f).toFloat()
                r.animate().alpha(1f).translationY(0f).translationX(0f).setStartDelay(150L + i * 30 + k++ * 32).setDuration(340)
                    .setInterpolator(DecelerateInterpolator(1.8f)).start()
            }
            shine.play(260)
        }
        return this
    }

    private fun navInset(): Int {
        val id = act.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return 0.coerceAtLeast(if (id > 0) act.resources.getDimensionPixelSize(id) / 3 else 0)
    }

    /** Deslizar hacia abajo para cerrar. */
    private fun dragToClose(handle: View) {
        var y0 = 0f; var moving = false
        handle.setOnTouchListener { _, e ->
            val pf = panel.parent as View
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; moving = true; true }
                MotionEvent.ACTION_MOVE -> {
                    if (moving) { val dy = (e.rawY - y0).coerceAtLeast(0f); pf.translationY = dy; scrim.alpha = 1f - (dy / pf.height).coerceIn(0f, 1f) * 0.8f }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    moving = false
                    if (pf.translationY > pf.height * 0.22f) dismiss()
                    else { pf.animate().translationY(0f).setDuration(260).setInterpolator(OvershootInterpolator(1.2f)).start(); scrim.animate().alpha(1f).setDuration(200).start() }
                    true
                }
                else -> false
            }
        }
    }

    fun dismiss(after: () -> Unit = {}) {
        if (closing || !::overlay.isInitialized) { after(); return }
        closing = true
        if (open[act] === this) open.remove(act)
        val pf = panel.parent as View
        scrim.animate().alpha(0f).setDuration(240).start()
        pf.animate().translationY(pf.height.toFloat() + dp(30f)).alpha(0.4f).setDuration(260)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay); onClose?.invoke() }.start()
        // la acción va un poquito después, para que se vea suave
        overlay.postDelayed({ after() }, 140)
    }

    private fun dismissNow() { if (open[act] === this) open.remove(act); (overlay.parent as? ViewGroup)?.removeView(overlay) }

    /** Un brillo diagonal que cruza el panel al abrirse. */
    private class Shine(ctx: android.content.Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = -1f
        init { isClickable = false }
        fun play(delay: Long) {
            val a = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(900)
            a.startDelay = delay; a.interpolator = PathInterpolator(0.3f, 0f, 0.3f, 1f)
            a.addUpdateListener { t = it.animatedValue as Float; invalidate() }
            a.addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(x: android.animation.Animator) { t = -1f; invalidate() } })
            a.start()
        }
        override fun onDraw(c: Canvas) {
            if (t < 0 || width == 0) return
            val w = width.toFloat(); val band = w * 0.35f
            val x = -band + (w + band * 2) * t
            p.shader = LinearGradient(x - band, 0f, x + band, height * 0.5f, intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            val r = 30f * resources.displayMetrics.density
            c.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, p)
        }
    }
}
