package com.luisell.bestnotes2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* ============================================================
   Timeline.kt — 🕘 la LÍNEA DE TIEMPO de cada nota.
   Cada vez que abres una nota y cambias algo se guarda UN bloque
   (un "rato de trabajo") con cómo estaba antes y cómo quedó:
     { s: inicio, e: fin, pre: {t,tr,b,ink}, post: {t,tr,b,ink} }
   Con eso se puede:
     - ver qué cambió (verde = lo nuevo, rojo = lo que borraste),
     - volver a como estaba en ese momento,
     - recuperar una foto, audio o renglón que borraste.
   Se guardan los últimos 40 ratos de cada nota.
   ============================================================ */
object Timeline {
    const val MAX = 40

    private fun dir(ctx: Context) = File(ctx.filesDir, "linea").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), id.replace(Regex("[^\\w.\\-]"), "_") + ".json")

    fun load(ctx: Context, id: String): MutableList<JSONObject> = try {
        val a = JSONArray(file(ctx, id).takeIf { it.exists() }?.readText() ?: "[]")
        MutableList(a.length()) { a.getJSONObject(it) }
    } catch (_: Exception) { mutableListOf() }

    private fun save(ctx: Context, id: String, list: List<JSONObject>) {
        val f = file(ctx, id); val t = File(f.path + ".tmp")
        t.writeText(JSONArray(list).toString()); t.renameTo(f)
    }

    fun clear(ctx: Context, id: String) { file(ctx, id).delete() }

    /** Para no borrar fotos/audios que todavía están en la línea de tiempo. */
    fun allText(ctx: Context): Sequence<String> = (dir(ctx).listFiles() ?: emptyArray()).asSequence().filter { it.name.endsWith(".json") }.map { it.readText() }

    fun snap(title: String, titleRuns: JSONArray?, body: String, ink: Any?): JSONObject =
        JSONObject().put("t", title).put("b", body).apply { if (titleRuns != null) put("tr", titleRuns); if (ink != null) put("ink", ink) }

    private fun same(a: JSONObject, b: JSONObject) =
        a.optString("t") == b.optString("t") && a.optString("b") == b.optString("b") &&
            (a.optJSONArray("tr")?.toString() ?: "") == (b.optJSONArray("tr")?.toString() ?: "") &&
            (a.opt("ink")?.toString() ?: "") == (b.opt("ink")?.toString() ?: "")

    /** Guarda (o actualiza) el rato de trabajo que empezó en [start]. */
    @Synchronized
    fun record(ctx: Context, id: String, start: Long, pre: JSONObject, post: JSONObject) {
        val list = load(ctx, id)
        val i = list.indexOfFirst { it.optLong("s") == start }
        if (same(pre, post)) {           // lo dejaste igual que al abrir: ese rato no cuenta
            if (i >= 0) { list.removeAt(i); save(ctx, id, list) }
            return
        }
        val o = JSONObject().put("s", start).put("e", System.currentTimeMillis()).put("pre", pre).put("post", post)
        if (i >= 0) list[i] = o else list.add(0, o)
        save(ctx, id, list.take(MAX))
    }

    /* ---------------- ¿qué cambió? ---------------- */
    enum class K { TITLE, TEXT, LIST, IMG, AUDIO, VIDEO }
    class Tok(val k: K, val key: String, val show: String, val block: Block?, val done: Boolean = false)
    class Ch(val op: Char, val tok: Tok)   // '+' nuevo, '-' borrado

    class Diff(val changes: List<Ch>) {
        var wordsAdd = 0; var wordsDel = 0
        var checked = 0; var unchecked = 0
        var style = false; var titleChanged = false
        fun count(k: K, op: Char) = changes.count { it.tok.k == k && it.op == op }
        val kinds: Set<String> get() = buildSet {
            if (wordsAdd + wordsDel > 0 || titleChanged || changes.any { it.tok.k == K.TEXT || it.tok.k == K.TITLE }) add("text")
            if (changes.any { it.tok.k == K.IMG }) add("img")
            if (changes.any { it.tok.k == K.LIST } || checked + unchecked > 0) add("list")
            if (changes.any { it.tok.k == K.AUDIO || it.tok.k == K.VIDEO }) add("media")
            if (style) add("style")
        }
        /** El tipo principal (para el ícono). */
        val main: String get() = kinds.let { k -> listOf("img", "media", "list", "text", "style").firstOrNull { it in k } ?: "text" }
    }

    fun tokens(s: JSONObject): List<Tok> {
        val out = ArrayList<Tok>()
        fun lines(k: K, txt: String, b: Block?) = txt.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(Tok(k, k.name + "\u0000" + it, it, b)) }
        lines(K.TITLE, s.optString("t"), null)
        for (b in blocksFromJson(s.optString("b"))) when (b) {
            is Block.Text -> lines(K.TEXT, runsPlain(b.runs), b)
            is Block.ListB -> b.items.forEach { it2 ->
                val t = runsPlain(it2.runs).trim()
                if (t.isNotEmpty()) out.add(Tok(K.LIST, "L\u0000" + t, t, Block.ListB(b.kind, mutableListOf(Item(it2.runs.toMutableList(), false))), it2.done))
            }
            is Block.Img -> { out.add(Tok(K.IMG, "I\u0000" + b.file, b.file, b)); lines(K.TEXT, runsPlain(b.beside), null) }
            is Block.Media -> { val k = if (b.kind == "video") K.VIDEO else K.AUDIO; out.add(Tok(k, k.name + "\u0000" + b.file, b.file, b)) }
        }
        return out
    }

    private fun words(t: List<Tok>): HashMap<String, Int> {
        val m = HashMap<String, Int>()
        t.filter { it.k == K.TEXT || it.k == K.LIST || it.k == K.TITLE }.forEach { x -> x.show.lowercase().split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }.forEach { w -> m[w] = (m[w] ?: 0) + 1 } }
        return m
    }

    /** Los pedazos con estilo (negrita, colores…), para saber si cambió el estilo. */
    private fun styleSig(s: JSONObject): String = buildString {
        s.optJSONArray("tr")?.let { append(it.toString()) }
        for (b in blocksFromJson(s.optString("b"))) {
            val runs = when (b) { is Block.Text -> b.runs; is Block.ListB -> b.items.flatMap { it.runs }; is Block.Img -> b.beside; else -> emptyList() }
            runs.filter { !it.st.isPlain() }.forEach { append(it.text.trim()).append('|').append(it.st.toJson()).append(';') }
        }
    }

    fun diff(pre: JSONObject, post: JSONObject): Diff {
        val a = tokens(pre); val b = tokens(post)
        val ch = ArrayList<Ch>()
        val matched = ArrayList<Pair<Tok, Tok>>()
        if (a.size * b.size <= 2_500_000) {
            // LCS: lo que quedó igual, lo nuevo y lo borrado (en orden)
            val n = a.size; val m = b.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
                dp[i][j] = if (a[i].key == b[j].key) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            var i = 0; var j = 0
            while (i < n && j < m) {
                when {
                    a[i].key == b[j].key -> { matched.add(a[i] to b[j]); i++; j++ }
                    dp[i + 1][j] >= dp[i][j + 1] -> { ch.add(Ch('-', a[i])); i++ }
                    else -> { ch.add(Ch('+', b[j])); j++ }
                }
            }
            while (i < n) ch.add(Ch('-', a[i++]))
            while (j < m) ch.add(Ch('+', b[j++]))
        } else {
            val ka = a.map { it.key }.toHashSet(); val kb = b.map { it.key }.toHashSet()
            a.filter { it.key !in kb }.forEach { ch.add(Ch('-', it)) }
            b.filter { it.key !in ka }.forEach { ch.add(Ch('+', it)) }
        }
        val d = Diff(ch)
        val wa = words(a); val wb = words(b)
        wb.forEach { (w, c) -> d.wordsAdd += maxOf(0, c - (wa[w] ?: 0)) }
        wa.forEach { (w, c) -> d.wordsDel += maxOf(0, c - (wb[w] ?: 0)) }
        matched.forEach { (x, y) -> if (x.k == K.LIST && x.done != y.done) { if (y.done) d.checked++ else d.unchecked++ } }
        d.style = styleSig(pre) != styleSig(post)
        d.titleChanged = pre.optString("t").trim() != post.optString("t").trim()
        return d
    }

    /** "+45 palabras · −3 · 📷 +2 · ☑ 1 · 🎨" */
    fun summary(d: Diff): String {
        val p = ArrayList<String>()
        if (d.wordsAdd > 0) p.add("+" + L.t("tlWords", "n" to d.wordsAdd))
        if (d.wordsDel > 0) p.add("−" + L.t("tlWords", "n" to d.wordsDel))
        fun pm(icon: String, k: K) { val x = d.count(k, '+'); val y = d.count(k, '-'); if (x + y > 0) p.add(icon + " " + listOfNotNull(if (x > 0) "+$x" else null, if (y > 0) "−$y" else null).joinToString(" ")) }
        pm("📷", K.IMG); pm("🎤", K.AUDIO); pm("🎬", K.VIDEO)
        val li = d.count(K.LIST, '+'); val ld = d.count(K.LIST, '-')
        if (li + ld > 0) p.add("☰ " + listOfNotNull(if (li > 0) "+$li" else null, if (ld > 0) "−$ld" else null).joinToString(" "))
        if (d.checked > 0) p.add("☑ " + L.t("tlChecked", "n" to d.checked))
        if (d.unchecked > 0) p.add("☐ " + L.t("tlUnchecked", "n" to d.unchecked))
        if (d.titleChanged) p.add("🏷️ " + L.t("tlTitle"))
        if (d.style) p.add("🎨 " + L.t("tlStyle"))
        if (p.isEmpty()) p.add("✏️ " + L.t("tlSmall"))
        return p.joinToString("  ·  ")
    }
}
