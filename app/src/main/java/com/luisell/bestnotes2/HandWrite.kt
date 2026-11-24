package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink as MlInk

/* ============================================================
   HandWrite.kt — ✍️ ESCRIBIR A MANO → LETRAS. Escribes con el
   dedo y la app lo convierte en texto normal (español, inglés o
   chino). La primera vez que usas un idioma se baja su "modelo"
   (una sola vez, con internet); después funciona sin internet.
   ============================================================ */
object HandWrite {
    private val LANGS = listOf(Triple("es", "es", "🇪🇸 ES"), Triple("en", "en-US", "🇺🇸 EN"), Triple("zh", "zh-Hani-CN", "🇨🇳 中文"))

    fun show(act: Activity, onPut: (String) -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(14), 0, act.dp(14), act.dp(4)) }
        // idiomas
        val langRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        box.addView(langRow)
        // lo que ya escribiste (y lo que se está leyendo ahora, en morado)
        val out = act.tv("", 20f, T.text, true).apply { setPadding(act.dp(10), act.dp(12), act.dp(10), act.dp(6)); minHeight = act.dp(58); setLineSpacing(0f, 1.15f) }
        box.addView(out)
        // otras opciones de lo que escribiste
        val alts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(alts); setPadding(0, 0, 0, act.dp(8)) })
        val pad = Pad(act)
        box.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(230)))
        val status = act.tv("", 12.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(6), 0, 0) }
        box.addView(status)
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, act.dp(8), 0, 0) }
        box.addView(bar)

        val done = StringBuilder()
        var now = ""
        var lang = LANGS.indexOfFirst { it.first == L.lang }.coerceAtLeast(0)
        var rec: DigitalInkRecognizer? = null
        val langViews = ArrayList<TextView>()
        fun paint() {
            val sp = android.text.SpannableStringBuilder(done)
            if (now.isNotEmpty()) { val a = sp.length; sp.append(now); sp.setSpan(android.text.style.ForegroundColorSpan(T.accent), a, sp.length, 0) }
            out.text = if (sp.isEmpty()) L.t("hwEmpty") else sp
            out.alpha = if (sp.isEmpty()) 0.5f else 1f
        }
        fun sep() = if (LANGS[lang].first == "zh") "" else " "
        fun commit() { if (now.isNotEmpty()) { if (done.isNotEmpty() && !done.endsWith(" ") && !done.endsWith("\n")) done.append(sep()); done.append(now); now = "" }; pad.clear(); alts.removeAllViews(); paint() }

        fun recognize() {
            val r = rec ?: return
            val ink = pad.ink() ?: return
            r.recognize(ink).addOnSuccessListener { res ->
                val c = res.candidates
                now = c.firstOrNull()?.text ?: ""
                paint(); Anim.bump(out, 1.03f)
                alts.removeAllViews()
                for (x in c.drop(1).take(4)) alts.addView(act.tv(x.text, 15f, T.text).apply {
                    setPadding(act.dp(12), act.dp(7), act.dp(12), act.dp(7)); background = Glass.make(act, 14f)
                    setOnClickListener { v -> Anim.bump(v); now = x.text; paint() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = act.dp(6) })
            }
        }
        pad.onPause = { recognize() }

        fun setLang(i: Int) {
            lang = i
            for ((k, v) in langViews.withIndex()) v.background = if (k == i) roundBg(T.accent, act.dp(18).toFloat()) else Glass.make(act, 18f)
            for ((k, v) in langViews.withIndex()) v.setTextColor(if (k == i) Color.WHITE else T.text)
            commit()
            try { rec?.close() } catch (_: Exception) {}
            rec = null
            val id = try { DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGS[i].second) } catch (_: Exception) { null }
            if (id == null) { status.text = "⚠️ " + L.t("vFail"); return }
            val model = DigitalInkRecognitionModel.builder(id).build()
            val mgr = RemoteModelManager.getInstance()
            status.text = "⏳ " + L.t("hwLoading")
            pad.on = false
            mgr.isModelDownloaded(model).addOnSuccessListener { ok ->
                fun ready() {
                    if (lang != i) return
                    rec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                    pad.on = true; status.text = "✍️ " + L.t("hwReady")
                }
                if (ok) ready()
                else {
                    status.text = "⬇️ " + L.t("hwDownload")
                    mgr.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { ready(); Anim.sparkle(pad, 50f, 600) }
                        .addOnFailureListener { if (lang == i) status.text = "⚠️ " + L.t("hwNoNet") }
                }
            }.addOnFailureListener { status.text = "⚠️ " + L.t("hwNoNet") }
        }
        for ((i, l) in LANGS.withIndex()) {
            val v = act.tv(l.third, 14f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8)); setOnClickListener { x -> Anim.bump(x); if (lang != i) setLang(i) } }
            langViews.add(v); langRow.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(act.dp(4), 0, act.dp(4), act.dp(4)) })
        }
        fun btn(label: String, color: Int, go: () -> Unit) = bar.addView(act.tv(label, 13.5f, if (color == 0) T.text else Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(act.dp(6), act.dp(11), act.dp(6), act.dp(11))
            background = if (color == 0) Glass.make(act, 20f) else roundBg(color, act.dp(20).toFloat())
            setOnClickListener { v -> Anim.bump(v); go() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(3), 0, act.dp(3), 0) })
        var sheet: Sheet? = null
        btn("↺ " + L.t("hwErase"), 0) {
            if (pad.isEmpty()) { // borra la última palabra
                val s = done.toString().trimEnd(); val k = maxOf(s.lastIndexOf(' '), s.lastIndexOf('\n'))
                done.setLength(0); done.append(if (k >= 0) s.substring(0, k + 1) else if (LANGS[lang].first == "zh" && s.isNotEmpty()) s.dropLast(1) else "")
            }
            now = ""; pad.clear(); alts.removeAllViews(); paint()
        }
        btn("␣ " + L.t("hwNext"), 0) { commit() }
        btn("↵", 0) { commit(); done.append('\n'); paint() }
        btn(L.t("ocrPutShort"), 0xFF2FA866.toInt()) {
            commit()
            val t = done.toString().trim()
            if (t.isEmpty()) { act.toast(L.t("hwEmpty")); return@btn }
            sheet?.dismiss(); onPut(t)
        }
        paint()
        sheet = Sheet(act, "✍️ " + L.t("hwTitle"), L.t("hwSub")).custom(box).show()
        setLang(lang)
    }

    /** La hoja donde escribes con el dedo (la tinta se ve suavecita). */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class Pad(ctx: Context) : View(ctx) {
        var on = false
            set(v) { field = v; alpha = if (v) 1f else 0.55f }
        private val t0 = System.currentTimeMillis()
        var onPause: (() -> Unit)? = null
        private val strokes = ArrayList<ArrayList<FloatArray>>()       // x, y, tiempo
        private var cur: ArrayList<FloatArray>? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
        private val path = Path()
        private val d = resources.displayMetrics.density
        private val pause = Runnable { onPause?.invoke() }
        init { background = Glass.make(ctx, 20f) }

        fun isEmpty() = strokes.isEmpty()
        fun clear() { strokes.clear(); cur = null; invalidate() }
        fun ink(): MlInk? {
            if (strokes.isEmpty()) return null
            val b = MlInk.builder()
            for (s in strokes) { val sb = MlInk.Stroke.builder(); for (q in s) sb.addPoint(MlInk.Point.create(q[0], q[1], q[2].toLong())); b.addStroke(sb.build()) }
            return b.build()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!on) return true
            parent.requestDisallowInterceptTouchEvent(true)
            val t = (System.currentTimeMillis() - t0).toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { removeCallbacks(pause); cur = arrayListOf(floatArrayOf(e.x, e.y, t)).also { strokes.add(it) } }
                MotionEvent.ACTION_MOVE -> { val c = cur ?: return true; for (h in 0 until e.historySize) c.add(floatArrayOf(e.getHistoricalX(h), e.getHistoricalY(h), t)); c.add(floatArrayOf(e.x, e.y, t)) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { cur = null; postDelayed(pause, 550) }
            }
            invalidate(); return true
        }

        override fun onDraw(c: Canvas) {
            // la rayita para escribir derechito
            guide.color = tw(0x22); val y = height * 0.72f
            c.drawLine(18 * d, y, width - 18 * d, y, guide)
            p.color = T.text; p.strokeWidth = 4.2f * d
            for (s in strokes) {
                path.reset(); path.moveTo(s[0][0], s[0][1])
                if (s.size == 1) path.lineTo(s[0][0] + 0.1f, s[0][1])
                for (i in 1 until s.size - 1) path.quadTo(s[i][0], s[i][1], (s[i][0] + s[i + 1][0]) / 2, (s[i][1] + s[i + 1][1]) / 2)
                if (s.size > 1) path.lineTo(s.last()[0], s.last()[1])
                c.drawPath(path, p)
            }
        }
    }
}
