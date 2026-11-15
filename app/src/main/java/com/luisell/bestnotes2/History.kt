package com.luisell.bestnotes2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* ============================================================
   History.kt — 🕘 versiones de cada nota.
   - "Guardar versión ahora" (las tuyas no se borran nunca).
   - Al salir de una nota que cambiaste, se guarda sola la versión
     de ANTES (por si te equivocaste). De esas se guardan las 40 últimas.
   - Cada versión: { at, t (título), b (bloques), ink, auto }
   ============================================================ */
object Versions {
    private const val MAX_AUTO = 40

    private fun dir(ctx: Context) = File(ctx.filesDir, "historial").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), id.replace(Regex("[^\\w.\\-]"), "_") + ".json")

    /** Solo las versiones que guardaste tú (las automáticas de antes ya no se usan: ahora está el 📖 registro). */
    fun load(ctx: Context, id: String): MutableList<JSONObject> = try {
        val a = JSONArray(file(ctx, id).takeIf { it.exists() }?.readText() ?: "[]")
        MutableList(a.length()) { a.getJSONObject(it) }.filter { !it.optBoolean("auto") }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun save(ctx: Context, id: String, list: List<JSONObject>) {
        val f = file(ctx, id); val t = File(f.path + ".tmp")
        t.writeText(JSONArray(list).toString()); t.renameTo(f)
    }

    fun make(title: String, body: String, ink: Any?, auto: Boolean, at: Long = System.currentTimeMillis()): JSONObject =
        JSONObject().put("at", at).put("t", title).put("b", body).put("auto", auto).apply { if (ink != null) put("ink", ink) }

    private fun same(a: JSONObject?, b: JSONObject) = a != null && a.optString("t") == b.optString("t") && a.optString("b") == b.optString("b") &&
        (a.opt("ink")?.toString() ?: "") == (b.opt("ink")?.toString() ?: "")

    /** Agrega una versión arriba. Devuelve false si ya estaba igual. */
    fun add(ctx: Context, id: String, v: JSONObject): Boolean {
        val list = load(ctx, id)
        if (list.any { same(it, v) }) return false
        list.add(0, v)
        // de las automáticas solo se guardan las últimas
        var autos = 0
        val keep = list.filter { if (it.optBoolean("auto")) ++autos <= MAX_AUTO else true }
        save(ctx, id, keep)
        return true
    }

    fun remove(ctx: Context, id: String, at: Long) = save(ctx, id, load(ctx, id).filter { it.optLong("at") != at })

    fun deleteAll(ctx: Context, id: String) { file(ctx, id).delete() }

    /** Para no borrar fotos que todavía usa alguna versión vieja. */
    fun allText(ctx: Context): Sequence<String> = (dir(ctx).listFiles() ?: emptyArray()).asSequence().filter { it.name.endsWith(".json") }.map { it.readText() }

    /** Texto corto para mostrar en la lista. */
    fun preview(v: JSONObject): String {
        val txt = blocksPlain(blocksFromJson(v.optString("b"))).replace(Regex("\\s+"), " ").trim()
        val t = v.optString("t")
        return (if (t.isNotEmpty()) "$t — " else "") + txt
    }
}

/**
 * 📖 El REGISTRO de cada nota: UNA sola lista que se va actualizando sola
 * con todo lo que escribes (renglones y fotos), aunque después lo borres.
 * - Si sigues escribiendo el mismo renglón, se actualiza (no se repite).
 * - Lo que borras se queda guardado aquí.
 * Fotos: se guardan como "\u0001archivo".
 */
object NoteLog {
    const val IMG = "\u0001"
    private fun dir(ctx: Context) = File(ctx.filesDir, "registro").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), id.replace(Regex("[^\\w.\\-]"), "_") + ".json")

    fun load(ctx: Context, id: String): MutableList<String> = try {
        val a = JSONArray(file(ctx, id).takeIf { it.exists() }?.readText() ?: "[]")
        MutableList(a.length()) { a.getString(it) }
    } catch (_: Exception) { mutableListOf() }

    fun save(ctx: Context, id: String, list: List<String>) {
        val f = file(ctx, id); val t = File(f.path + ".tmp")
        t.writeText(JSONArray(list).toString()); t.renameTo(f)
    }

    fun clear(ctx: Context, id: String) { file(ctx, id).delete() }

    /** Los renglones y fotos que tiene la nota ahora. */
    fun tokens(title: String, blocks: List<Block>): List<String> {
        val out = ArrayList<String>()
        fun add(t: String) { t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) } }
        add(title)
        for (b in blocks) when (b) {
            is Block.Text -> add(runsPlain(b.runs))
            is Block.ListB -> b.items.forEach { add(runsPlain(it.runs)) }
            is Block.Img -> { out.add(IMG + b.file); add(runsPlain(b.beside)) }
            else -> {}
        }
        return out
    }

    /**
     * Actualiza el registro. [mine] = renglones que se escribieron en esta sesión
     * (esos se pueden ir "creciendo"; lo de antes nunca se toca).
     */
    fun update(ctx: Context, id: String, now: List<String>, mine: MutableSet<String>): Int {
        val list = load(ctx, id)
        var changed = false
        for (t in now) {
            if (t in list) continue
            // ¿es el mismo renglón que estabas escribiendo? (empieza igual) → se actualiza
            val i = if (t.startsWith(IMG)) -1 else list.indexOfLast { it in mine && !it.startsWith(IMG) && (t.startsWith(it) || it.startsWith(t)) && it !in now }
            if (i >= 0 && t.length > list[i].length) { mine.remove(list[i]); list[i] = t }
            else if (i >= 0) continue                         // borraste letras: lo largo se queda
            else list.add(t)
            mine.add(t); changed = true
        }
        if (changed) save(ctx, id, list)
        return list.size
    }

    /** Para no borrar fotos que están en un registro. */
    fun allText(ctx: Context): Sequence<String> = (dir(ctx).listFiles() ?: emptyArray()).asSequence().filter { it.name.endsWith(".json") }.map { it.readText() }
}
