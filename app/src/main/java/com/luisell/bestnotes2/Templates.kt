package com.luisell.bestnotes2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/* ============================================================
   Templates.kt — 📋 PLANTILLAS: notas listas para llenar.
   - Las de la app (reporte, checklist, materiales, reunión…).
   - Las tuyas: "Guardar esta nota como plantilla".
   Todos los ejemplos son inventados (nada de nombres reales).
   ============================================================ */
object Templates {
    class Tpl(val emoji: String, val name: String, val title: String, val blocks: () -> MutableList<Block>, val mine: Int = -1, val icon: Int = MoreIcons.TEXT_T)

    private fun today(): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL, L.locale)
        .format(java.util.Date()).replaceFirstChar { it.uppercase() }

    private fun b(t: String) = Run(t, St.PLAIN.copy(b = true))
    private fun r(t: String) = Run(t)
    private fun text(vararg runs: Run) = Block.Text(runs.toMutableList())
    private fun list(kind: String, vararg items: String) = Block.ListB(kind, items.map { Item(mutableListOf(Run(it))) }.toMutableList())

    /** Los textos en inglés de las plantillas (para traducirlos con la IA). */
    val EN_STRINGS = LinkedHashMap<String, String>()

    fun builtIn(): List<Tpl> {
        fun t(a: String, b: String): String {
            val k = "tpl." + b.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
            EN_STRINGS[k] = b
            return when (L.lang) { "es" -> a; "en" -> b; else -> L.tx(k) ?: b }
        }
        return listOf(
            Tpl("📋", t("Reporte de trabajo", "Work report"), t("Reporte", "Report") + " — " + today(), {
                mutableListOf(
                    text(b(t("📅 Fecha: ", "📅 Date: ")), r(today() + "\n"), b(t("📍 Lugar: ", "📍 Place: ")), r("\n"), b(t("🕒 Hora: ", "🕒 Time: ")), r("\n\n"), b(t("👷 Trabajo hecho:", "👷 Work done:"))),
                    list("ul", ""),
                    text(b(t("🧰 Materiales usados:", "🧰 Materials used:"))),
                    list("ul", ""),
                    text(b(t("⏳ Pendientes:", "⏳ To do:"))),
                    list("check", ""),
                    text(b(t("📝 Notas:", "📝 Notes:")), r("\n")),
                )
            }, icon = Icons.REPORT),
            Tpl("✅", t("Checklist de instalación", "Installation checklist"), t("Instalación", "Installation") + " — " + today(), {
                mutableListOf(
                    text(b(t("📍 Lugar: ", "📍 Place: ")), r("\n")),
                    list("check", t("Revisar el plano y el área", "Check the plan and the area"), t("Tener los materiales listos", "Have the materials ready"),
                        t("Pasar y organizar el cable", "Run and organize the cable"), t("Conectar y probar", "Connect and test"),
                        t("Etiquetar todo", "Label everything"), t("Tomar fotos de cómo quedó", "Take photos of the result"), t("Limpiar el área", "Clean the area")),
                    text(b(t("📝 Notas:", "📝 Notes:")), r("\n")),
                )
            }, icon = MoreIcons.CHECKBOX),
            Tpl("🧰", t("Lista de materiales", "Materials list"), t("Materiales", "Materials") + " — " + today(), {
                mutableListOf(
                    list("check", t("Cable", "Cable"), t("Conectores", "Connectors"), t("Tornillos y tacos", "Screws and anchors"),
                        t("Cinta y amarres", "Tape and zip ties"), t("Canaleta", "Raceway"), t("Herramientas", "Tools")),
                    text(b(t("🛒 Falta comprar:", "🛒 Still to buy:"))),
                    list("check", ""),
                )
            }, icon = Icons.BOX),
            Tpl("🤝", t("Reunión", "Meeting"), t("Reunión", "Meeting") + " — " + today(), {
                mutableListOf(
                    text(b(t("👥 Personas: ", "👥 People: ")), r("\n\n"), b(t("💬 Temas:", "💬 Topics:"))),
                    list("ul", ""),
                    text(b(t("🤝 Acuerdos:", "🤝 Agreements:"))),
                    list("ol", ""),
                    text(b(t("⏳ Pendientes:", "⏳ To do:"))),
                    list("check", ""),
                )
            }, icon = Icons.PEOPLE),
            Tpl("🛒", t("Lista de compras", "Shopping list"), t("Compras", "Shopping"), { mutableListOf(list("check", "", "", "")) }, icon = Icons.TODO),
            Tpl("📔", t("Diario", "Journal"), today(), {
                mutableListOf(
                    text(b(t("🌤️ Hoy…", "🌤️ Today…")), r("\n\n"), b(t("⭐ Lo mejor del día:", "⭐ Best part of the day:")), r("\n\n"), b(t("🎯 Mañana quiero:", "🎯 Tomorrow I want to:"))),
                    list("check", ""),
                )
            }, icon = Icons.BOOK),
            Tpl("💡", t("Idea o proyecto", "Idea or project"), t("Idea", "Idea"), {
                mutableListOf(
                    text(b(t("💡 La idea:", "💡 The idea:")), r("\n\n"), b(t("❓ ¿Para qué sirve?", "❓ What is it for?")), r("\n\n"), b(t("🪜 Pasos:", "🪜 Steps:"))),
                    list("ol", ""),
                )
            }, icon = Icons.IDEA),
        )
    }

    /* ---------- las tuyas ---------- */
    private fun load(ctx: Context): JSONArray = try { JSONArray(ctx.getSharedPreferences("ajustes", 0).getString("tpl", "[]")) } catch (_: Exception) { JSONArray() }
    private fun store(ctx: Context, a: JSONArray) = ctx.getSharedPreferences("ajustes", 0).edit().putString("tpl", a.toString()).apply()

    fun mine(ctx: Context): List<Tpl> {
        val a = load(ctx)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Tpl("⭐", o.optString("name"), o.optString("title"), { blocksFromJson(o.optString("body")) }, i)
        }
    }

    fun saveMine(ctx: Context, name: String, title: String, blocks: List<Block>) {
        val a = load(ctx)
        a.put(JSONObject().put("name", name).put("title", title).put("body", blocksJson(blocks)))
        store(ctx, a)
    }

    fun deleteMine(ctx: Context, i: Int) {
        val a = load(ctx); val out = JSONArray()
        for (k in 0 until a.length()) if (k != i) out.put(a.get(k))
        store(ctx, out)
    }
}

/** El panel para escoger una plantilla. [onSaveCurrent] = "Guardar esta nota como plantilla" (solo dentro de una nota). */
fun android.app.Activity.templateSheet(onPick: (Templates.Tpl) -> Unit, onSaveCurrent: (() -> Unit)? = null) {
    val sh = Sheet(this, "📋 " + L.t("templates"), L.t("templatesSub"))
    val colors = intArrayOf(Sheet.BLUE, Sheet.GREEN, Sheet.ORANGE, Sheet.VIOLET, Sheet.PINK, Sheet.AMBER, Sheet.TEAL)
    for ((i, t) in Templates.builtIn().withIndex()) sh.item(t.icon, t.name, colors[i % colors.size], t.emoji + "  " + t.title) { onPick(t) }
    val mine = Templates.mine(this)
    if (mine.isNotEmpty()) {
        sh.divider()
        for (t in mine) sh.item(MoreIcons.STAR5_ON, t.name, Sheet.AMBER, L.t("tplMine")) { onPick(t) }
        sh.item(Icons.TRASH, L.t("tplManage"), danger = true) {
            val s2 = Sheet(this, L.t("tplManage"), L.t("tplManageSub"))
            for (t in mine) s2.item(Icons.TRASH, t.name, danger = true) { confirm(L.t("tplDelete"), t.name, L.t("del"), true) { Templates.deleteMine(this, t.mine); toast(L.t("tplDeleted")) } }
            s2.show()
        }
    }
    if (onSaveCurrent != null) { sh.divider(); sh.item(MoreIcons.PLUS, L.t("tplSave"), Sheet.SKY, L.t("tplSaveSub")) { onSaveCurrent() } }
    sh.show()
}
