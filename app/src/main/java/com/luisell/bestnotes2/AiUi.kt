package com.luisell.bestnotes2

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/* ============================================================
   AiUi.kt — la ventana de la IA: "La IA está escribiendo…" y
   después el resultado (en español y en inglés si lo pediste),
   con botones para usarlo, copiarlo o compartirlo.
   ============================================================ */
class AiJob(val label: String, val run: () -> String?)
class AiAction(val label: String, val primary: Boolean = false, val act: (String) -> Unit)

/**
 * Corre [jobs] uno por uno (en segundo plano) y muestra cada resultado con sus [actions].
 * Si [lines] es true, cada renglón del resultado es un botón (para escoger una palabra o frase).
 */
fun Activity.aiDialog(title: String, jobs: List<AiJob>, actions: List<AiAction>, lines: Boolean = false, onLine: ((String) -> Unit)? = null) {
    val a = this
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
    val wait = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
    wait.addView(ProgressBar(this), lp(dp(30), dp(30)))
    val waitTv = tv(L.t("aiWorking"), 15f, T.muted).apply { setPadding(dp(12), 0, 0, 0) }
    wait.addView(waitTv)
    col.addView(wait)
    lateinit var d: GlassDialog
    d = dialog().setTitle("✨ $title").setView(ScrollView(this).apply { addView(col) }).setNegativeButton(L.t("close"), null).show()
    var alive = true
    d.setOnDismissListener { alive = false }
    Thread {
        for ((k, j) in jobs.withIndex()) {
            val out = try { j.run()?.let { Ai.clean(it) } } catch (_: Throwable) { null }
            runOnUiThread {
                if (!alive) return@runOnUiThread
                if (k == jobs.size - 1) col.removeView(wait) else waitTv.text = L.t("aiWorking") + " (${k + 2}/${jobs.size})"
                val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(10)); background = Glass.make(a, 18f) }
                if (jobs.size > 1 || j.label.isNotEmpty()) card.addView(tv(j.label, 12.5f, T.accent, true).apply { setPadding(0, 0, 0, dp(6)) })
                if (out == null) {
                    card.addView(tv(L.t("aiFail"), 14.5f, T.danger))
                } else if (lines) {
                    out.lines().map { it.trim().trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.isNotBlank() }.forEach { line ->
                        card.addView(tv(line, 16f, T.text).apply {
                            setPadding(dp(10), dp(10), dp(10), dp(10)); pressBg(0, 12f)
                            setOnClickListener { d.dismiss(); onLine?.invoke(line) }
                        })
                    }
                } else {
                    card.addView(TextView(a).apply { text = out; setTextColor(T.text); textSize = 15.5f; setTextIsSelectable(true); setLineSpacing(0f, 1.15f) })
                    val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
                    val hs = android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }
                    for (ac in actions) row.addView(tv(ac.label, 13.5f, if (ac.primary) T.onPill else T.text, true).apply {
                        setPadding(dp(14), dp(8), dp(14), dp(8)); pressBg(if (ac.primary) T.text else T.cardStrong, 16f, T.border)
                        setOnClickListener { d.dismiss(); ac.act(out) }
                    }, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
                    row.addView(tv(L.t("copy"), 13.5f, T.text, true).apply {
                        setPadding(dp(14), dp(8), dp(14), dp(8)); pressBg(T.cardStrong, 16f, T.border)
                        setOnClickListener { (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Best Notes", out)); toast(L.t("copied")) }
                    })
                    card.addView(hs)
                }
                col.addView(card, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
            }
        }
    }.start()
}

/** Convierte el texto de la IA en bloques: "Título:" y "## Título" en negrita, "- algo" como viñeta, "1. algo" como número. */
fun aiToBlocks(text: String, checklist: Boolean = false): MutableList<Block> {
    val out = ArrayList<Block>()
    var tb: MutableList<Run>? = null
    fun line(r: List<Run>) { val t = tb; if (t == null) { tb = r.toMutableList(); out.add(Block.Text(tb!!)) } else { t.add(Run("\n")); t.addAll(r) } }
    fun endText() { (out.lastOrNull() as? Block.Text)?.let { it.runs = mergeRuns(it.runs) }; tb = null }
    fun item(kind: String, s: String) {
        endText()
        val last = out.lastOrNull()
        if (last is Block.ListB && last.kind == kind) last.items.add(Item(mutableListOf(Run(s)))) else out.add(Block.ListB(kind, mutableListOf(Item(mutableListOf(Run(s))))))
    }
    for (raw in text.replace("\r", "").lines()) {
        val l = raw.trim().replace("**", "")
        when {
            l.isEmpty() -> { if (tb != null) line(emptyList()) }
            l.startsWith("#") -> { endText(); line(listOf(Run(l.trimStart('#').trim(), St(b = true, size = 20)))) }
            checklist -> item("check", l.trimStart('-', '•', '*', ' '))
            Regex("^[-•*–]\\s+.*").matches(l) -> item("ul", l.substring(1).trim())
            Regex("^\\d+[.)]\\s+.*").matches(l) -> item("ol", l.replace(Regex("^\\d+[.)]\\s+"), ""))
            l.endsWith(":") && l.length < 60 -> { endText(); line(listOf(Run(l, St(b = true)))) }
            else -> { if (out.lastOrNull() !is Block.Text) endText(); line(listOf(Run(l))) }
        }
    }
    endText()
    return normalize(out)
}
