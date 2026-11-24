package com.luisell.bestnotes2

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/* ============================================================
   Trio.kt — 🌐 LA NOTA EN 3 IDIOMAS. Pestañas Español · English ·
   中文: tocas una (o deslizas el dedo) y ves la nota en ese idioma.
   Cada traducción se hace una sola vez con la IA y se guarda
   mientras la nota no cambie.
   ============================================================ */
object Trio {
    private val LANGS = listOf("es" to "🇪🇸 Español", "en" to "🇺🇸 English", "zh" to "🇨🇳 中文")
    private val cache = HashMap<String, String>()        // "idioma|texto" → traducción

    fun show(act: Activity, text: String, onNewNote: (lang: String, text: String) -> Unit) {
        if (text.isBlank()) { act.toast(L.t("ttsEmpty")); return }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(14), 0, act.dp(14), act.dp(6)) }
        // las 3 pestañas con la pastilla que se desliza
        val tabsBox = FrameLayout(act).apply { background = Glass.make(act, 22f); setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(4)) }
        val slider = View(act).apply { background = roundBg(T.accent, act.dp(18).toFloat()) }
        tabsBox.addView(slider, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        tabsBox.addView(tabs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(tabsBox)
        val body = act.tv("", 16f, T.text).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true); setPadding(act.dp(6), act.dp(14), act.dp(6), act.dp(14)) }
        val sc = ScrollView(act).apply { addView(body) }
        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(340)))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        box.addView(bar)

        var cur = -1
        val views = ArrayList<TextView>()
        fun load(i: Int) {
            val lg = LANGS[i].first
            val key = "$lg|$text"
            cache[key]?.let { t -> body.text = t; return }
            body.text = "🌐 " + L.t("translating")
            body.alpha = 0.6f
            Thread {
                val r = Ai.ask(Ai.system("translate", lg), text)?.let { Ai.clean(it) }
                act.runOnUiThread {
                    if (r != null) cache[key] = r
                    if (cur == i) { body.text = r ?: ("⚠️ " + L.t("aiFail")); body.alpha = 0f; body.animate().alpha(1f).setDuration(250).start() }
                }
            }.start()
        }
        fun select(i: Int) {
            if (i == cur || i !in LANGS.indices) return
            val dir = if (i > cur) 1 else -1
            cur = i
            for ((k, v) in views.withIndex()) v.setTextColor(if (k == i) Color.WHITE else T.text)
            // la pastilla se desliza a la pestaña escogida
            val w = tabs.width / LANGS.size
            if (w > 0) { slider.layoutParams = (slider.layoutParams as FrameLayout.LayoutParams).apply { width = w }; slider.animate().translationX((w * i).toFloat()).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start() }
            body.animate().alpha(0f).translationX(-dir * act.dp(30).toFloat()).setDuration(130).withEndAction {
                body.translationX = dir * act.dp(30).toFloat(); load(i)
                body.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }.start()
        }
        for ((i, l) in LANGS.withIndex()) {
            val t = act.tv(l.second, 14.5f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(10), 0, act.dp(10)); setOnClickListener { select(i) } }
            views.add(t); tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // 👉 deslizar el dedo para cambiar de idioma
        var x0 = 0f
        sc.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x0 = e.x
                MotionEvent.ACTION_UP -> { val dx = e.x - x0; if (Math.abs(dx) > act.dp(70)) select(cur + if (dx < 0) 1 else -1) }
            }
            false
        }
        fun pill(label: String, color: Int, go: () -> Unit) = bar.addView(act.tv(label, 13.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(act.dp(8), act.dp(11), act.dp(8), act.dp(11)); background = roundBg(color, act.dp(20).toFloat())
            setOnClickListener { v -> Anim.bump(v); go() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(3), act.dp(8), act.dp(3), 0) })
        fun now() = cache[LANGS[cur].first + "|" + text]
        pill(L.t("copy"), 0xFF3A7BFF.toInt()) { now()?.let { (act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("nota", it)); act.toast(L.t("copied")) } }
        pill(L.t("share"), 0xFF7A5CFF.toInt()) { now()?.let { act.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, it), null)) } }
        pill(L.t("trioNew"), 0xFF2FA866.toInt()) { now()?.let { onNewNote(LANGS[cur].first, it) } }
        Sheet(act, "🌐 " + L.t("trioTitle"), L.t("trioSub")).custom(box).show()
        // empieza en el idioma de la app
        tabs.post { select(LANGS.indexOfFirst { it.first == L.lang }.coerceAtLeast(0)) }
    }
}
