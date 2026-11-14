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

    fun load(ctx: Context, id: String): MutableList<JSONObject> = try {
        val a = JSONArray(file(ctx, id).takeIf { it.exists() }?.readText() ?: "[]")
        MutableList(a.length()) { a.getJSONObject(it) }
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
