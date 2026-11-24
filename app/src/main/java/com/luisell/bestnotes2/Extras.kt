package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/* ============================================================
   Extras.kt — 🔊 leer en voz alta,
   ✍️ firma del cliente, 📊 resumen y 🎉 confeti.
   ============================================================ */

/* ---------------------------- 🔊 Leer en voz alta ---------------------------- */
/**
 * Lee la nota frase por frase. Cada frase en su idioma: si tiene letras chinas, en chino;
 * si parece inglés, en inglés; si no, en español. Una pastilla abajo con ⏸ / velocidad / ✕.
 */
class Speaker(private val act: Activity, private val parent: ViewGroup) {
    private var tts: TextToSpeech? = null
    private var parts: List<String> = emptyList()
    private var idx = 0
    private var playing = false
    private var rate = 1f
    private var pill: LinearLayout? = null
    private var label: TextView? = null
    private var playBtn: TextView? = null
    private var wave: Wave? = null
    var onClose: (() -> Unit)? = null

    val active get() = pill != null

    fun start(text: String) {
        parts = split(text)
        if (parts.isEmpty()) { act.toast(L.t("ttsEmpty")); return }
        idx = 0
        showPill()
        label?.text = L.t("ttsLoading")
        tts = TextToSpeech(act) { ok ->
            if (ok != TextToSpeech.SUCCESS) { act.runOnUiThread { act.toast(L.t("ttsFail")); stop() }; return@TextToSpeech }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) { act.runOnUiThread { idx = id.toIntOrNull() ?: idx; paint() } }
                override fun onDone(id: String) {
                    act.runOnUiThread { if ((id.toIntOrNull() ?: -1) >= parts.size - 1 && playing) { playing = false; idx = 0; paint(); label?.text = "✓ " + L.t("ttsDone") } }
                }
                @Deprecated("Deprecated in Java") override fun onError(id: String) {}
            })
            act.runOnUiThread { play() }
        }
    }

    private fun play() {
        val t = tts ?: return
        t.stop(); t.setSpeechRate(rate)
        playing = true
        for (i in idx until parts.size) {
            t.language = langOf(parts[i])
            t.speak(parts[i], TextToSpeech.QUEUE_ADD, null, i.toString())
        }
        paint()
    }
    private fun pause() { playing = false; tts?.stop(); paint() }

    fun stop() {
        playing = false
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        pill?.let { p -> p.animate().alpha(0f).translationY(p.height.toFloat()).setDuration(220).withEndAction { parent.removeView(p) }.start() }
        pill = null; wave = null
        onClose?.invoke()
    }

    private fun paint() {
        playBtn?.text = if (playing) "⏸" else "▶"
        wave?.on = playing
        if (parts.isNotEmpty()) label?.text = "${idx + 1}/${parts.size} · " + parts[idx.coerceIn(0, parts.size - 1)]
    }

    private fun showPill() {
        if (pill != null) return
        val p = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.make(act, 28f, strong = true); setPadding(act.dp(10), act.dp(6), act.dp(6), act.dp(6)); elevation = act.dp(12).toFloat()
        }
        val w = Wave(act).also { wave = it }
        p.addView(w, LinearLayout.LayoutParams(act.dp(30), act.dp(30)))
        label = act.tv("", 13.5f, T.text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(act.dp(8), 0, act.dp(4), 0) }
        p.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun btn(s: String, go: (TextView) -> Unit) = act.tv(s, 16f, T.text, true).apply {
            gravity = Gravity.CENTER; setOnClickListener { v -> Anim.bump(v); go(this) }
        }.also { p.addView(it, LinearLayout.LayoutParams(act.dp(42), act.dp(42))) }
        btn("1×") { b ->
            rate = when (rate) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 0.8f; else -> 1f }
            b.text = (if (rate % 1f == 0f) rate.toInt().toString() else rate.toString()) + "×"
            if (playing) play()
        }.textSize = 13f
        playBtn = btn("⏸") { if (playing) pause() else play() }
        btn("✕") { stop() }
        pill = p
        parent.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(act.dp(14), 0, act.dp(14), act.dp(96)) })
        p.alpha = 0f; p.translationY = act.dp(60).toFloat()
        p.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
    }

    companion object {
        private val EN = setOf("the", "and", "is", "are", "to", "of", "for", "with", "this", "that", "on", "in", "it", "you", "we", "be", "at", "from", "have", "will")
        private val ES = setOf("el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "por", "para", "con", "es", "se", "del", "al", "lo", "no", "hay")
        fun langOf(s: String): Locale {
            if (s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return Locale.SIMPLIFIED_CHINESE
            val w = s.lowercase().split(Regex("[^\\p{L}]+"))
            val en = w.count { it in EN }; val es = w.count { it in ES } + s.count { it in "ñáéíóú¿¡" }
            return if (en > es) Locale.US else Locale("es", "US")
        }
        /** Corta el texto en frases (por punto, salto de línea, etc.). */
        fun split(t: String): List<String> = t.split(Regex("(?<=[.!?。！？])\\s+|\\n+")).map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }
    }

    /** 〰️ Las barritas que bailan mientras lee. */
    private class Wave(ctx: Context) : View(ctx) {
        var on = false
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.accent; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) {
            val t = android.os.SystemClock.uptimeMillis() / 1000.0
            p.strokeWidth = width / 9f
            for (i in 0 until 4) {
                val k = if (on) (0.35 + 0.65 * Math.abs(Math.sin(t * 5 + i * 1.3))).toFloat() else 0.3f
                val x = width * (0.2f + i * 0.2f); val h = height * 0.7f * k
                c.drawLine(x, height / 2f - h / 2, x, height / 2f + h / 2, p)
            }
            if (on) postInvalidateOnAnimation()
        }
    }
}

/* ---------------------------- ✍️ Firma ---------------------------- */
object Signature {
    /** Abre la hoja blanca para firmar. Al tocar Listo te da el archivo de la foto de la firma y el texto del sello. */
    fun ask(act: Activity, onDone: (file: String, w: Int, h: Int, stamp: String) -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(16), act.dp(4), act.dp(16), 0) }
        val pad = FrameLayout(act).apply { background = roundBg(Color.WHITE, act.dp(16).toFloat()); clipToOutline = true }
        val ink = InkView(act).apply { drawing = true; color = 0xFF10204A.toInt(); size = 1 }
        pad.addView(ink, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // la rayita y la "X" de dónde se firma
        val line = object : View(act) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8BCC8.toInt(); strokeWidth = act.dp(1.5f).toFloat(); textSize = act.dp(18).toFloat() }
            override fun onDraw(c: Canvas) { val y = height * 0.74f; c.drawLine(width * 0.08f, y, width * 0.92f, y, p); c.drawText("✕", width * 0.08f, y - act.dp(8), p) }
        }
        pad.addView(line, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(210)))
        val name = android.widget.EditText(act).apply {
            hint = L.t("sigName"); setTextColor(T.text); setHintTextColor(T.faint); textSize = 15f; isSingleLine = true
            background = Glass.make(act, 14f); setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
        }
        box.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(12) })
        val clear = act.tv("↺ " + L.t("sigClear"), 14f, T.accent, true).apply { setPadding(act.dp(4), act.dp(12), act.dp(4), act.dp(4)); setOnClickListener { v -> Anim.bump(v); ink.clearAll() } }
        box.addView(clear)
        act.dialog().setTitle("✍️ " + L.t("sigTitle")).setView(box)
            .setPositiveButton(L.t("done")) { _, _ ->
                if (ink.strokes.isEmpty()) { act.toast(L.t("sigEmpty")); return@setPositiveButton }
                val strokes = ArrayList(ink.strokes); val W = ink.width.toFloat(); val H = ink.height.toFloat()
                val who = name.text.toString().trim()
                Loader.pool.execute {
                    val r = render(strokes, W, H)
                    act.runOnUiThread {
                        if (r == null) { act.toast(L.t("vFail")); return@runOnUiThread }
                        val date = java.text.SimpleDateFormat("d MMM yyyy, h:mm a", L.locale).format(java.util.Date())
                        onDone(r.first, r.second, r.third, "✍️ " + (if (who.isNotEmpty()) "$who · " else "") + date)
                    }
                }
            }.setNegativeButton(L.t("cancel"), null).show()
    }

    /** Dibuja la firma en una foto blanca, cortando lo que sobra alrededor. */
    private fun render(strokes: List<Stroke>, W: Float, H: Float): Triple<String, Int, Int>? = try {
        val k = 2f
        val full = Bitmap.createBitmap((W * k).toInt(), (H * k).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(full); c.drawColor(Color.WHITE)
        for (s in strokes) Stroke.draw(c, s, W * k)
        // los bordes de la firma
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = 0f; var b = 0f
        for (s in strokes) for (q in s.p) { l = minOf(l, q[0] * W * k); r = maxOf(r, q[0] * W * k); t = minOf(t, q[1] * W * k); b = maxOf(b, q[1] * W * k) }
        val m = 40f
        val box = RectF(maxOf(0f, l - m), maxOf(0f, t - m), minOf(full.width.toFloat(), r + m), minOf(full.height.toFloat(), b + m))
        val out = Bitmap.createBitmap(full, box.left.toInt(), box.top.toInt(), maxOf(1, box.width().toInt()), maxOf(1, box.height().toInt()))
        val name = "f" + newId() + ".jpg"
        FileOutputStream(File(Store.mediaDir, name)).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        Triple(name, out.width, out.height)
    } catch (_: Throwable) { null }
}

/* ---------------------------- 🎉 Confeti ---------------------------- */
object Confetti {
    private val COLORS = intArrayOf(0xFFFF453A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF64D2FF.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF6FB5.toInt())

    /** Lluvia de papelitos de colores por toda la pantalla (1.8 segundos). */
    @SuppressLint("ViewConstructor")
    fun burst(act: Activity) {
        val decor = act.window.decorView as ViewGroup
        val v = object : View(act) {
            val n = 120
            val rnd = java.util.Random()
            val x = FloatArray(n); val y = FloatArray(n); val vx = FloatArray(n); val vy = FloatArray(n)
            val rot = FloatArray(n); val vr = FloatArray(n); val col = IntArray(n); val sz = FloatArray(n); val kind = IntArray(n)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            var t0 = 0L; var last = 0L
            val d = resources.displayMetrics.density
            fun init() {
                for (i in 0 until n) {
                    // salen disparados desde las dos esquinas de abajo y desde arriba
                    val from = i % 3
                    x[i] = when (from) { 0 -> 0f; 1 -> width.toFloat(); else -> rnd.nextFloat() * width }
                    y[i] = if (from == 2) -20 * d else height * 0.85f
                    val sp = (9 + rnd.nextFloat() * 9) * d * 60
                    val ang = when (from) { 0 -> Math.toRadians(-60.0 - rnd.nextFloat() * 25); 1 -> Math.toRadians(-120.0 + rnd.nextFloat() * 25); else -> Math.toRadians(90.0) }
                    vx[i] = (Math.cos(ang) * sp).toFloat() * (if (from == 2) 0.1f else 1f); vy[i] = if (from == 2) rnd.nextFloat() * 200 * d else (Math.sin(ang) * sp).toFloat()
                    rot[i] = rnd.nextFloat() * 360; vr[i] = (rnd.nextFloat() - 0.5f) * 720
                    col[i] = COLORS[rnd.nextInt(COLORS.size)]; sz[i] = (5 + rnd.nextFloat() * 5) * d; kind[i] = rnd.nextInt(3)
                }
            }
            override fun onDraw(c: Canvas) {
                val now = android.os.SystemClock.uptimeMillis()
                if (t0 == 0L) { t0 = now; last = now; init() }
                val dt = ((now - last) / 1000f).coerceAtMost(0.05f); last = now
                val life = (now - t0) / 1800f
                p.alpha = (255 * (1 - ((life - 0.7f) / 0.3f).coerceIn(0f, 1f))).toInt()
                for (i in 0 until n) {
                    vy[i] += 900 * d * dt; vx[i] *= 0.985f; vy[i] = vy[i].coerceAtMost(420 * d)
                    x[i] += vx[i] * dt; y[i] += vy[i] * dt; rot[i] += vr[i] * dt
                    val a = p.alpha; p.color = col[i]; p.alpha = a
                    c.save(); c.translate(x[i], y[i]); c.rotate(rot[i])
                    // papelito que gira (se ve más angosto cuando está de lado)
                    val w = sz[i] * Math.abs(Math.cos(Math.toRadians(rot[i] * 1.7))).toFloat().coerceAtLeast(0.2f)
                    when (kind[i]) { 0 -> c.drawRect(-w, -sz[i] / 2, w, sz[i] / 2, p); 1 -> c.drawCircle(0f, 0f, sz[i] / 2, p); else -> c.drawRoundRect(-w, -sz[i] * 0.3f, w, sz[i] * 0.3f, 3 * d, 3 * d, p) }
                    c.restore()
                }
                if (life < 1f) postInvalidateOnAnimation() else post { decor.removeView(this) }
            }
        }.apply { isClickable = false; elevation = 100f }
        decor.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    }
}

/* ---------------------------- 📊 Resumen ---------------------------- */
object Stats {
    class Quick(val week: Int, val pct: Int, val total: Int, val done: Int, val streak: Int, val notes: Int, val photos: Int, val perDay: IntArray, val names: List<String>)
    /** Los números de la pantalla principal (el medidor en vivo). */
    fun quick(): Quick {
        val notes = Store.notes.filter { !it.deleted }
        val day = 86_400_000L
        val today0 = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val t = notes.sumOf { it.checkTotal }; val d = notes.sumOf { it.checkDone }
        val perDay = IntArray(7)
        for (n in notes) for (x in setOf(n.created, n.updated)) { val k = Math.floorDiv(x - today0, day).toInt() + 6; if (k in 0..6) perDay[k]++ }
        val names = (0..6).map { k -> java.text.SimpleDateFormat("EEE", L.locale).format(java.util.Date(today0 - (6 - k) * day)).take(2).replaceFirstChar { it.uppercase(L.locale) } }
        val photos = Store.mediaDir.list()?.count { it.endsWith(".jpg") || it.endsWith(".png") } ?: 0
        return Quick(notes.count { it.created >= today0 - 6 * day }, if (t == 0) 0 else d * 100 / t, t, d, streak(notes, today0), notes.size, photos, perDay, names)
    }
    private fun streak(notes: List<NoteMeta>, today0: Long): Int {
        val day = 86_400_000L
        val days = HashSet<Long>()
        for (n in notes) for (x in longArrayOf(n.created, n.updated)) days.add(Math.floorDiv(x - today0, day))
        var s = 0
        // si hoy todavía no escribiste, la racha cuenta desde ayer
        var k = if (0L in days) 0L else -1L
        while (k in days && s < 3650) { s++; k-- }
        return s
    }

    fun show(act: Activity) {
        val notes = Store.notes.filter { !it.deleted }
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val today0 = cal.timeInMillis
        // notas escritas o cambiadas cada día (últimos 7 días)
        val perDay = IntArray(7)
        for (n in notes) {
            for (t in setOf(n.created, n.updated)) { val d = ((today0 + day - 1 - t) / day).toInt(); if (t >= today0 - 6 * day && d in 0..6) perDay[6 - d]++ }
        }
        val dayNames = (0..6).map { k -> java.text.SimpleDateFormat("EEE", L.locale).format(java.util.Date(today0 - (6 - k) * day)).take(3) }
        val week = notes.count { it.created >= today0 - 6 * day }
        val ckT = notes.sumOf { it.checkTotal }; val ckD = notes.sumOf { it.checkDone }
        val words = notes.sumOf { n -> n.plain.split(Regex("\\s+")).count { it.isNotBlank() } }
        val media = Store.mediaDir.listFiles()?.toList() ?: emptyList()
        val photos = media.count { it.name.endsWith(".jpg") || it.name.endsWith(".png") }
        val videos = media.count { it.name.endsWith(".mp4") }
        val audios = media.count { it.name.endsWith(".m4a") || it.name.endsWith(".3gp") || it.name.endsWith(".aac") }
        // racha: días seguidos (hasta hoy) en que escribiste algo
        val streak = streak(notes, today0)

        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(14), act.dp(4), act.dp(14), act.dp(12)) }
        // 1) las tres tarjetas con números que suben
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun card(emoji: String, value: Int, label: String, delay: Long) {
            val c = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = Glass.make(act, 18f); setPadding(act.dp(6), act.dp(12), act.dp(6), act.dp(12)) }
            c.addView(act.tv(emoji, 20f).apply { gravity = Gravity.CENTER })
            val num = act.tv("0", 24f, T.text, true).apply { gravity = Gravity.CENTER }
            c.addView(num); c.addView(act.tv(label, 11.5f, T.muted).apply { gravity = Gravity.CENTER; maxLines = 2 })
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(4), 0, act.dp(4), 0) })
            Anim.enter(c, delay)
            ValueAnimator.ofInt(0, value).apply { startDelay = delay + 150; duration = 900; interpolator = android.view.animation.DecelerateInterpolator(2f); addUpdateListener { num.text = it.animatedValue.toString() }; start() }
        }
        card("📝", notes.size, L.t("stNotes"), 0)
        card("☑️", ckD, L.t("stDone"), 80)
        card("📷", photos, L.t("stPhotos"), 160)
        box.addView(row)

        // 2) las barras de la semana
        box.addView(act.tv(L.t("stWeek", "n" to week), 13f, T.muted, true).apply { setPadding(act.dp(6), act.dp(18), 0, act.dp(6)) })
        val bars = Bars(act, perDay, dayNames)
        box.addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(150)))

        // 3) los pendientes con su anillo
        val ringRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Glass.make(act, 18f); setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12)) }
        val ring = Ring(act, if (ckT == 0) 0f else ckD.toFloat() / ckT)
        ringRow.addView(ring, LinearLayout.LayoutParams(act.dp(66), act.dp(66)))
        ringRow.addView(act.tv(L.t("stTodo", "d" to ckD, "t" to ckT) + "\n" + (if (ckT > ckD) L.t("stLeft", "n" to (ckT - ckD)) else "🎉 " + L.t("stAllDone")), 14.5f, T.text).apply { setLineSpacing(0f, 1.2f); setPadding(act.dp(14), 0, 0, 0) })
        box.addView(ringRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(14) })
        Anim.enter(ringRow, 240)

        // 4) más datos
        val more = act.tv(
            "🔥 " + L.t("stStreak", "n" to streak) + "\n" +
            "✍️ " + L.t("stWords", "n" to String.format(L.locale, "%,d", words)) + "\n" +
            "🎬 " + L.t("stMedia", "v" to videos, "a" to audios) + "\n" +
            "⭐ " + L.t("stFav", "n" to notes.count { it.fav }) + "   🔒 " + L.t("stLocked", "n" to notes.count { it.locked }),
            14f, T.text).apply { setLineSpacing(0f, 1.45f); setPadding(act.dp(10), act.dp(14), act.dp(10), 0) }
        box.addView(more); Anim.enter(more, 320)
        // ⚙️ mostrar (o no) el medidor en la pantalla principal
        val pr = act.getSharedPreferences("ajustes", 0)
        val tog = act.tv("", 14f, T.accent, true).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(16), 0, act.dp(4)) }
        fun paintTog() { tog.text = if (pr.getBoolean("liveStats", true)) "👁  " + L.t("stHideLive") else "📊  " + L.t("stShowLive") }
        paintTog()
        tog.setOnClickListener { v -> Anim.bump(v); pr.edit().putBoolean("liveStats", !pr.getBoolean("liveStats", true)).apply(); paintTog(); Store.changed() }
        box.addView(tog)
        // 🎉 todos los pendientes hechos: confeti cuando el anillo se llena
        if (ckT > 0 && ckD >= ckT) ringRow.postDelayed({ if (ringRow.isAttachedToWindow) Confetti.burst(act) }, 1500)
        Sheet(act, "📊 " + L.t("stTitle"), java.text.SimpleDateFormat("EEEE d MMMM", L.locale).format(java.util.Date(now))).custom(box).show()
    }

    /** Barras que crecen rebotando, una por día. */
    private class Bars(ctx: Context, val v: IntArray, val names: List<String>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var k = 0f
        init { ValueAnimator.ofFloat(0f, 1f).apply { startDelay = 200; duration = 1100; interpolator = android.view.animation.OvershootInterpolator(1.4f); addUpdateListener { k = it.animatedValue as Float; invalidate() }; start() } }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val max = maxOf(1, v.maxOrNull() ?: 1)
            val n = v.size; val slot = width / n.toFloat(); val bw = slot * 0.52f
            val base = height - 22 * d; val top = 26 * d
            for (i in 0 until n) {
                val stagger = ((k * 1.4f) - i * 0.06f).coerceIn(0f, 1.15f)
                val h = (base - top) * v[i] / max * minOf(stagger, 1.04f)
                val cx = slot * i + slot / 2
                val today = i == n - 1
                p.shader = android.graphics.LinearGradient(0f, base - h, 0f, base, if (today) T.accent else (0xCC shl 24) or (T.accent and 0xFFFFFF), (0x55 shl 24) or (T.accent and 0xFFFFFF), android.graphics.Shader.TileMode.CLAMP)
                c.drawRoundRect(cx - bw / 2, base - maxOf(h, 4 * d), cx + bw / 2, base, 8 * d, 8 * d, p)
                tp.color = if (today) T.text else T.muted; tp.textSize = 11.5f * d; tp.isFakeBoldText = today
                c.drawText(names[i], cx, height - 4 * d, tp)
                if (v[i] > 0 && stagger > 0.9f) { tp.color = T.text; tp.textSize = 12f * d; c.drawText(v[i].toString(), cx, base - h - 5 * d, tp) }
            }
        }
    }

    /** El anillo de pendientes terminados (se llena dando la vuelta). */
    private class Ring(ctx: Context, val frac: Float) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private var k = 0f
        init { ValueAnimator.ofFloat(0f, 1f).apply { startDelay = 350; duration = 1200; interpolator = android.view.animation.DecelerateInterpolator(2f); addUpdateListener { k = it.animatedValue as Float; invalidate() }; start() } }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val sw = 7 * d; val r = RectF(sw, sw, width - sw, height - sw)
            p.strokeWidth = sw; p.color = tw(0x22); c.drawArc(r, 0f, 360f, false, p)
            p.color = 0xFF30D158.toInt(); c.drawArc(r, -90f, 360f * frac * k, false, p)
            tp.color = T.text; tp.textSize = 15 * d
            c.drawText("${(frac * 100 * k).toInt()}%", width / 2f, height / 2f + 5 * d, tp)
        }
    }
}

/* ---------------------------- 🔗 Notas conectadas ---------------------------- */
object NoteLinks {
    const val PREFIX = "nota:"

    /** Escoger una nota (con buscador). */
    fun pick(act: Activity, exclude: String?, onPick: (NoteMeta) -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(14), 0, act.dp(14), act.dp(8)) }
        val q = android.widget.EditText(act).apply {
            hint = "🔍 " + L.t("search"); setTextColor(T.text); setHintTextColor(T.faint); textSize = 15f; isSingleLine = true
            background = Glass.make(act, 16f); setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
        }
        box.addView(q)
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, act.dp(6), 0, 0) }
        val sc = android.widget.ScrollView(act).apply { addView(list) }
        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(330)))
        var sheet: Sheet? = null
        fun fill() {
            list.removeAllViews()
            val f = fold(q.text.toString().trim())
            val l = Store.notes.filter { !it.deleted && it.id != exclude && (f.isEmpty() || fold(it.title + " " + it.plain).contains(f)) }.sortedByDescending { it.updated }.take(60)
            if (l.isEmpty()) list.addView(act.tv(L.t("noMatch", "q" to q.text.toString()), 14f, T.muted).apply { setPadding(act.dp(8), act.dp(16), act.dp(8), act.dp(16)) })
            l.forEachIndexed { i, n ->
                val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10)); background = Glass.make(act, 14f) }
                row.addView(act.tv((if (n.locked) "🔒 " else "📝 ") + titleOf(n), 15f, T.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (!n.locked && n.plain.isNotBlank()) row.addView(act.tv(n.plain.replace('\n', ' ').take(90), 12.5f, T.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                row.setOnClickListener { v -> Anim.bump(v, 1.04f); sheet?.dismiss(); onPick(n) }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(6) })
                if (i < 12) Anim.enter(row, i * 30L, 14f)
            }
        }
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { fill() }
        })
        fill()
        sheet = Sheet(act, "🔗 " + L.t("linkTitle"), L.t("linkSub")).custom(box).show()
    }

    fun titleOf(n: NoteMeta) = n.title.ifBlank { n.plain.lineSequence().firstOrNull { it.isNotBlank() }?.take(50) ?: L.t("untitled") }

    /** Las notas que tienen un enlace a [id] (se buscan en segundo plano). */
    fun backlinks(id: String, onDone: (List<NoteMeta>) -> Unit) {
        Store.io.execute {
            val out = Store.notes.filter { n -> !n.deleted && n.id != id && try { (Store.readBody(n.id).first ?: "").contains("\"" + PREFIX + id + "\"") } catch (_: Exception) { false } }
            Store.main.post { onDone(out) }
        }
    }
}


/* ---------------------------- 📊 El medidor en vivo (pantalla principal) ---------------------------- */
/**
 * Debajo de "Todas las notas": el anillo de pendientes, las barritas de la semana y los números.
 * Cada vez que cambia algo (marcas un pendiente, escribes una nota…) se mueve solo, con animación.
 */
@SuppressLint("ViewConstructor")
class LiveStats(private val act: Activity) : LinearLayout(act) {
    private val ring = LiveRing(act)
    private val bars = LiveBars(act)
    private val head = act.tv("", 12.5f, T.muted, true).apply { letterSpacing = 0.06f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val nNotes = act.tv("0", 15f, T.text, true)
    private val nPhotos = act.tv("0", 15f, T.text, true)
    private val nStreak = act.tv("0", 15f, T.text, true)
    private var last: Stats.Quick? = null

    init {
        orientation = VERTICAL
        background = Glass.make(act, 22f); setPadding(act.dp(14), act.dp(10), act.dp(12), act.dp(10))
        val top = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(head, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(act.tv("›", 18f, T.muted, true))
        addView(top)
        val row = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(6), 0, 0) }
        row.addView(ring, LayoutParams(act.dp(66), act.dp(66)))
        row.addView(bars, LayoutParams(0, act.dp(70), 1f).apply { setMargins(act.dp(12), 0, act.dp(10), 0) })
        val col = LinearLayout(act).apply { orientation = VERTICAL }
        fun line(e: String, v: TextView) = col.addView(LinearLayout(act).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(act.tv(e, 13f).apply { setPadding(0, 0, act.dp(5), 0) }); addView(v)
        })
        line("📝", nNotes); line("📷", nPhotos); line("🔥", nStreak)
        row.addView(col)
        addView(row)
        setOnClickListener { Anim.press(it); Stats.show(act) }
    }

    /** Pone los números nuevos (con animación solo en lo que cambió). */
    fun update(q: Stats.Quick) {
        val old = last; last = q
        head.text = "📊 " + L.t("stLiveHead", "n" to q.week).uppercase(L.locale)
        ring.set(if (q.total == 0) 0f else q.done.toFloat() / q.total, q.total > 0 && q.done >= q.total)
        bars.set(q.perDay, q.names)
        count(nNotes, old?.notes ?: 0, q.notes); count(nPhotos, old?.photos ?: 0, q.photos); count(nStreak, old?.streak ?: 0, q.streak)
        // 🎉 justo ahora se terminaron todos los pendientes
        if (old != null && old.total > 0 && old.done < old.total && q.total > 0 && q.done >= q.total) Anim.sparkle(ring, 60f, 900)
    }

    private fun count(v: TextView, a: Int, b: Int) {
        if (a == b && v.text.toString() == b.toString()) return
        ValueAnimator.ofInt(a, b).apply { duration = 700; interpolator = android.view.animation.DecelerateInterpolator(2f); addUpdateListener { v.text = it.animatedValue.toString() }; start() }
        if (a != b) Anim.bump(v, 1.25f)
    }

    /** ◯ El anillo de pendientes que se llena o se vacía suavecito. */
    private class LiveRing(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var cur = 0f; private var full = false
        private var anim: ValueAnimator? = null
        fun set(f: Float, all: Boolean) {
            full = all
            if (Math.abs(f - cur) < 0.001f) { invalidate(); return }
            anim?.cancel()
            anim = ValueAnimator.ofFloat(cur, f).apply { duration = 900; interpolator = android.view.animation.OvershootInterpolator(1.2f); addUpdateListener { cur = (it.animatedValue as Float).coerceIn(0f, 1f); invalidate() }; start() }
        }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val sw = 6.5f * d; val r = RectF(sw, sw, width - sw, height - sw)
            p.strokeWidth = sw; p.shader = null; p.color = tw(0x22); c.drawArc(r, 0f, 360f, false, p)
            p.shader = android.graphics.SweepGradient(width / 2f, height / 2f, intArrayOf(0xFF30D158.toInt(), 0xFF64D2FF.toInt(), 0xFF30D158.toInt()), null)
            c.save(); c.rotate(-90f, width / 2f, height / 2f); c.drawArc(r, 0f, 360f * cur, false, p); c.restore()
            tp.color = T.text; tp.textSize = 14.5f * d
            c.drawText("${(cur * 100).toInt()}%", width / 2f, height / 2f + 2 * d, tp)
            sp.color = T.muted; sp.textSize = 8.5f * d
            c.drawText(if (full) "🎉" else "☑", width / 2f, height / 2f + 13 * d, sp)
        }
    }

    /** ▮▮▮ Las barritas de los 7 días: cuando cambia un número, la barra crece rebotando. */
    private class LiveBars(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var from = FloatArray(7); private var to = FloatArray(7); private var k = 1f
        private var names: List<String> = List(7) { "" }
        private var vals = IntArray(7)
        fun set(v: IntArray, n: List<String>) {
            names = n
            if (v.contentEquals(vals) && k >= 1f) { invalidate(); return }
            val max = maxOf(1, v.maxOrNull() ?: 1).toFloat()
            from = FloatArray(7) { i -> cur(i) }
            to = FloatArray(7) { i -> v[i] / max }
            vals = v.copyOf()
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 800; interpolator = android.view.animation.OvershootInterpolator(1.6f); addUpdateListener { k = it.animatedValue as Float; invalidate() }; start() }
            k = 0f
        }
        private fun cur(i: Int) = from[i] + (to[i] - from[i]) * k
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val n = 7; val slot = width / n.toFloat(); val bw = slot * 0.5f
            val base = height - 14 * d; val top = 4 * d
            for (i in 0 until n) {
                val h = maxOf(3 * d, (base - top) * cur(i).coerceIn(0f, 1.08f))
                val cx = slot * i + slot / 2
                val today = i == n - 1
                p.shader = android.graphics.LinearGradient(0f, base - h, 0f, base, if (today) T.accent else (0xCC shl 24) or (T.accent and 0xFFFFFF), (0x44 shl 24) or (T.accent and 0xFFFFFF), android.graphics.Shader.TileMode.CLAMP)
                c.drawRoundRect(cx - bw / 2, base - h, cx + bw / 2, base, 5 * d, 5 * d, p)
                tp.color = if (today) T.text else T.muted; tp.textSize = 10 * d; tp.isFakeBoldText = today
                c.drawText(names.getOrElse(i) { "" }, cx, height - 2 * d, tp)
            }
        }
    }
}
