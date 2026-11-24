package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/* ============================================================
   Ask.kt — 🤖 PREGÚNTALE A TUS NOTAS. Escribes (o dices) una
   pregunta, la app busca las notas que más tienen que ver y la
   IA contesta usando SOLO lo que está en tus notas, diciendo de
   qué nota sacó cada cosa (tócala para abrirla).
   Las notas bloqueadas nunca se mandan a la IA.
   ============================================================ */
object AskNotes {
    private val STOP = setOf("que", "qué", "cual", "cuál", "como", "cómo", "donde", "dónde", "cuando", "cuándo", "para", "por", "con", "los", "las", "del", "una", "uno",
        "the", "what", "which", "where", "when", "how", "for", "with", "and", "tengo", "tiene", "hay", "esta", "este", "eso", "esa", "mis", "my", "is", "are", "was", "de", "la", "el", "en", "y", "a")

    /** Las notas que más tienen que ver con la pregunta (las mejores primero). */
    fun find(q: String, max: Int = 10): List<NoteMeta> {
        val words = fold(q).split(Regex("[^\\p{L}\\p{N}.]+")).map { it.trim('.') }.filter { it.length >= 2 && it !in STOP }
        val notes = Store.notes.filter { !it.deleted && !it.locked }
        val scored = notes.map { n ->
            val t = fold(n.title); val body = n.folded ?: fold(n.title + " " + n.plain)
            var s = 0.0
            for (w in words) {
                if (t.contains(w)) s += 4
                var i = body.indexOf(w); var c = 0
                while (i >= 0 && c < 6) { c++; i = body.indexOf(w, i + w.length) }
                s += c
            }
            n to s
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return (scored.ifEmpty { notes.sortedByDescending { it.updated } }).take(max)
    }

    private fun context(l: List<NoteMeta>): String = buildString {
        l.forEachIndexed { i, n ->
            append("[${i + 1}] ").append(n.title.ifBlank { L.t("untitled") }).append(" (")
            append(java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date(n.updated))).append(")\n")
            append((n.plain + "\n" + Ocr.textOf(null, n.id)).trim().take(1600)).append("\n\n")
        }
    }

    private const val SYS = "You answer questions about the user's own notes. Use ONLY the information in the notes provided. " +
        "Answer in the SAME language as the question, short and clear (1 to 5 sentences, or a short list with \"- \"). " +
        "After each fact, cite the note it came from with its number in square brackets, like [2]. " +
        "If the answer is not in the notes, say clearly that you could not find it in the notes and suggest what to search. No markdown symbols like ** or #."

    /** La hoja para preguntar. [open] abre una nota. */
    fun show(act: Activity, open: (NoteMeta) -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(14), 0, act.dp(14), act.dp(6)) }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Glass.make(act, 22f); setPadding(act.dp(14), act.dp(4), act.dp(4), act.dp(4)) }
        val q = EditText(act).apply {
            hint = L.t("askHint"); setTextColor(T.text); setHintTextColor(T.faint); textSize = 16f; background = null; maxLines = 4
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        row.addView(q, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mic = act.iconBtn(MoreIcons.MIC, 9.5f, T.text) {}.apply { layoutParams = LinearLayout.LayoutParams(act.dp(42), act.dp(42)) }
        val send = act.iconBtn(Icons.SEND, 9f, Color.WHITE) {}.apply { layoutParams = LinearLayout.LayoutParams(act.dp(42), act.dp(42)); background = roundBg(T.accent, act.dp(21).toFloat()) }
        row.addView(mic); row.addView(send)
        box.addView(row)
        // ideas de preguntas
        val ideas = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; setPadding(0, act.dp(10), 0, act.dp(4)); addView(ideas) }
        box.addView(hs)
        // la respuesta
        val card = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; background = Glass.make(act, 20f); setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(14)); visibility = View.GONE }
        val ans = act.tv("", 15.5f, T.text).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }
        card.addView(ans)
        val srcs = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, act.dp(8), 0, 0) }
        card.addView(srcs)
        box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(10) })

        var sheet: Sheet? = null
        var dots: ValueAnimator? = null
        var busy = false
        fun ask() {
            val text = q.text.toString().trim()
            if (text.length < 3 || busy) return
            busy = true
            (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(q.windowToken, 0)
            card.visibility = View.VISIBLE; srcs.removeAllViews()
            if (card.alpha < 1f || card.translationY != 0f) { card.alpha = 0f; card.translationY = act.dp(16).toFloat(); card.animate().alpha(1f).translationY(0f).setDuration(320).start() }
            // 💭 los puntitos de "pensando"
            dots = ValueAnimator.ofInt(0, 3).apply { duration = 900; repeatCount = ValueAnimator.INFINITE; addUpdateListener { ans.text = "💭 " + L.t("askThinking") + ".".repeat((it.animatedValue as Int) + 1) }; start() }
            val notes = find(text)
            Thread {
                val r = if (notes.isEmpty()) null else Ai.ask(SYS, "NOTES:\n" + context(notes) + "\nQUESTION: " + text)?.let { Ai.clean(it) }
                act.runOnUiThread {
                    dots?.cancel(); busy = false
                    if (notes.isEmpty()) { ans.text = L.t("askNoNotes"); return@runOnUiThread }
                    if (r.isNullOrBlank()) { ans.text = "⚠️ " + L.t("aiFail"); return@runOnUiThread }
                    // ✍️ la respuesta se escribe solita
                    val clean = r.replace(Regex("\\s*\\[(\\d+(?:\\s*,\\s*\\d+)*)\\]")) { m -> " [" + m.groupValues[1].replace(" ", "") + "]" }
                    ValueAnimator.ofInt(0, clean.length).apply { duration = minOf(1800L, 12L * clean.length); addUpdateListener { ans.text = clean.substring(0, it.animatedValue as Int) }; start() }
                    // 📝 las notas de donde salió
                    val used = Regex("\\[(\\d+(?:,\\d+)*)\\]").findAll(clean).flatMap { it.groupValues[1].split(",").asSequence() }.mapNotNull { it.toIntOrNull() }.distinct().filter { it in 1..notes.size }.toList()
                    if (used.isNotEmpty()) srcs.addView(act.tv(L.t("askFrom"), 12f, T.muted, true).apply { setPadding(0, act.dp(4), 0, act.dp(4)) })
                    used.forEachIndexed { k, i ->
                        val n = notes[i - 1]
                        val chip = act.tv("[$i]  📝 " + n.title.ifBlank { n.plain.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: L.t("untitled") } + "  ›", 14f, T.text, true).apply {
                            setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9)); background = Glass.make(act, 14f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                            setOnClickListener { v -> Anim.bump(v, 1.04f); sheet?.dismiss(); open(n) }
                        }
                        srcs.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(6) })
                        Anim.enter(chip, 300L + k * 80, 16f)
                    }
                }
            }.start()
        }
        send.setOnClickListener { v -> Anim.bump(v); ask() }
        q.setOnEditorActionListener { _, _, _ -> ask(); true }
        for (s in listOf(L.t("askIdea1"), L.t("askIdea2"), L.t("askIdea3"), L.t("askIdea4"))) {
            ideas.addView(act.tv(s, 13.5f, T.text).apply {
                setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8)); background = Glass.make(act, 16f)
                setOnClickListener { v -> Anim.bump(v); q.setText(s); q.setSelection(s.length); ask() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = act.dp(8) })
        }
        // 🎙️ preguntar hablando
        var dict: Dictation? = null
        mic.setOnClickListener { v ->
            Anim.bump(v)
            if (dict?.running == true) { dict?.stop(); return@setOnClickListener }
            if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 47); act.toast(L.t("askMicAgain")); return@setOnClickListener
            }
            val before = q.text.toString().trim()
            val d = Dictation(act, if (L.lang == "en") "en" else "es",
                onPartial = { t -> if (t.isNotEmpty()) q.setText((before + " " + t).trim()) },
                onFinal = { t -> q.setText((before + " " + t).trim()); q.setSelection(q.length()); dict?.stop(); ask() },
                onState = { on -> (mic as? IconView)?.color = if (on) 0xFFFF453A.toInt() else T.text; if (on) Anim.bump(mic, 1.25f) })
            if (!d.available()) { act.toast(L.t("noDictation")); return@setOnClickListener }
            dict = d; d.start()
        }
        sheet = Sheet(act, "🤖 " + L.t("askTitle"), L.t("askSub")).custom(box).show()
        q.postDelayed({ q.requestFocus(); (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(q, 0) }, 350)
    }
}
