package com.luisell.bestnotes2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

/* ============================================================
   Materials.kt — 📦 Lista de materiales (como en Best Notes):
   la IA lee tus notas y saca todo lo que hay que pedir o comprar.
   Cada material: el TOTAL y, debajo, para dónde va cada parte.
   Estados: ○ Por pedir → 🛒 Pedido → ✅ Llegó.
   Lo que ya marcaste como Pedido o Llegó no se borra al volver a buscar.
   ============================================================ */
class Mat(val id: String, var item: String, var qty: Double?, var unit: String, var site: String, var status: Int, var note: String)

object Materials {
    private const val KEY = "materiales"

    fun load(ctx: Context): MutableList<Mat> {
        val out = ArrayList<Mat>()
        try {
            val a = JSONArray(ctx.getSharedPreferences("ajustes", 0).getString(KEY, "[]"))
            for (k in 0 until a.length()) a.optJSONObject(k)?.let { o ->
                out.add(Mat(o.optString("id"), o.optString("item"), if (o.isNull("qty") || !o.has("qty")) null else o.optDouble("qty"), o.optString("unit"), o.optString("site"), o.optInt("st"), o.optString("n")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, l: List<Mat>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("item", it.item).put("qty", it.qty ?: JSONObject.NULL).put("unit", it.unit).put("site", it.site).put("st", it.status).put("n", it.note)) }
        ctx.getSharedPreferences("ajustes", 0).edit().putString(KEY, a.toString()).apply()
    }

    fun system(lang: String): String {
        val L2 = if (lang == "en") "English" else "Spanish"
        return "You extract a MATERIALS / PURCHASE list from a field technician's notes (low-voltage, IT, security: cameras, access control, " +
            "switches, cable, fiber, mag locks, door hardware, power supplies, connectors, tools). " +
            "Include ONLY materials, equipment, parts or supplies that must be ordered, bought, picked up, replaced or are missing / needed. " +
            "Do NOT include work that was already done or installed, and do NOT include tasks that are not materials. " +
            "Return ONLY a JSON array, nothing else. Each element: {\"item\": short canonical material name in $L2 (keep models, sizes, port counts, " +
            "e.g. \"Switch PoE 16 puertos\"; use EXACTLY the same name for the same material across notes), \"qty\": number or null if not stated, " +
            "\"unit\": short unit or \"\", \"site\": the place / building / floor / room it is for (from the note text or its title), \"note\": the note number}. " +
            "Never invent materials or quantities. If there is nothing, return []."
    }

    fun parse(r: String?): JSONArray? {
        val s = r ?: return null
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        if (a < 0 || b <= a) return null
        return try { JSONArray(s.substring(a, b + 1)) } catch (_: Exception) { null }
    }

    fun qtyText(q: Double?) = when { q == null -> ""; q == Math.floor(q) -> q.toLong().toString(); else -> q.toString() }

    /** Mensaje profesional para el jefe (sin IA: sale al instante). */
    fun bossText(groups: Map<String, List<Mat>>, lang: String): String {
        val es = lang == "es"
        val date = SimpleDateFormat(if (es) "d 'de' MMMM 'de' yyyy" else "MMMM d, yyyy", if (es) java.util.Locale("es") else java.util.Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append(if (es) "Buenos días,\n\nLe comparto la lista de materiales que se necesitan pedir ($date):\n\n" else "Good morning,\n\nHere is the list of materials that need to be ordered ($date):\n\n")
        for ((item, l) in groups) {
            val open = l.filter { it.status == 0 }
            if (open.isEmpty()) continue
            val total = open.mapNotNull { it.qty }.sum()
            sb.append("• ").append(if (total > 0) qtyText(total) + " × " else "").append(item).append(open.firstOrNull()?.unit?.let { if (it.isNotEmpty()) " ($it)" else "" } ?: "").append('\n')
            open.filter { it.site.isNotBlank() }.forEach { sb.append("   – ").append(if (it.qty != null) qtyText(it.qty) + " " + (if (es) "para" else "for") + " " else "").append(it.site).append('\n') }
        }
        sb.append(if (es) "\nQuedo atento a la confirmación del pedido.\nGracias." else "\nPlease let me know once the order is confirmed.\nThank you.")
        return sb.toString()
    }
}

class MaterialsActivity : Activity() {
    private var list = ArrayList<Mat>()
    private var scope = "week"
    private lateinit var box: LinearLayout
    private lateinit var scopeRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        list = ArrayList(Materials.load(this))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        top.addView(glassBtn(Icons.BACK) { finish() })
        top.addView(tv("📦 " + L.t("materials"), 20f, T.text, true).apply { setPadding(dp(8), 0, 0, 0) }, lp(0, WRAP, 1f))
        top.addView(glassBtn(MoreIcons.PLUS) { edit(null) })
        top.addView(glassBtn(MoreIcons.DOTS_V) { more() })
        root.addView(top)
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(40)) }
        sv.addView(col)
        scopeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(scopeRow); paintScope()
        col.addView(tv("✨ " + L.t("matSearch"), 16f, 0xFF16161A.toInt(), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(12)); pressBg(T.text, 24f); setOnClickListener { search() }
        }, lp(MATCH, WRAP).apply { topMargin = dp(10); bottomMargin = dp(10) })
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box)
        root.addView(sv, lp(MATCH, 0, 1f))
        setContentView(root)
        draw()
    }

    private fun paintScope() {
        scopeRow.removeAllViews()
        for ((k, label) in listOf("today" to L.t("matToday"), "week" to L.t("matWeek"), "all" to L.t("matAll"))) {
            val on = scope == k
            scopeRow.addView(tv(label, 14f, if (on) 0xFF16161A.toInt() else T.text, true).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8)); if (on) pressBg(T.text, 18f) else background = Glass.make(this@MaterialsActivity, 18f)
                setOnClickListener { scope = k; paintScope() }
            }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
    }

    private fun groups(): Map<String, List<Mat>> = list.groupBy { it.item.trim().lowercase() }.mapKeys { (_, v) -> v.first().item.trim() }

    private fun draw() {
        box.removeAllViews()
        if (list.isEmpty()) { box.addView(tv(L.t("matEmpty"), 15f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(40), dp(16), 0) }); return }
        for ((item, l) in groups()) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10)); background = Glass.make(this@MaterialsActivity, 18f) }
            val total = l.mapNotNull { it.qty }.sum()
            card.addView(tv((if (total > 0) Materials.qtyText(total) + " × " else "") + item + (l.first().unit.let { if (it.isNotEmpty()) " ($it)" else "" }), 16.5f, T.text, true).apply { setPadding(dp(4), 0, 0, dp(4)) })
            for (m in l) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setOnClickListener { edit(m) } }
                val st = tv(when (m.status) { 1 -> "🛒"; 2 -> "✅"; else -> "○" }, 18f, T.text).apply {
                    gravity = Gravity.CENTER; setOnClickListener { m.status = (m.status + 1) % 3; save(); draw() }
                }
                row.addView(st, lp(dp(40), dp(40)))
                val c2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                c2.addView(tv((if (m.qty != null) Materials.qtyText(m.qty) + " · " else "") + m.site.ifBlank { "—" }, 15f, if (m.status == 2) T.muted else T.text))
                c2.addView(tv(when (m.status) { 1 -> L.t("matOrdered"); 2 -> L.t("matArrived"); else -> L.t("matToOrder") }, 12f, T.faint))
                row.addView(c2, lp(0, WRAP, 1f))
                card.addView(row)
            }
            box.addView(card, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }
    }

    private fun save() = Materials.save(this, list)

    private fun search() {
        val now = System.currentTimeMillis()
        val since = when (scope) { "today" -> java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis; "week" -> now - 7 * 86_400_000L; else -> 0L }
        val notes = Store.notes.filter { !it.deleted && !it.locked && it.updated >= since }.sortedByDescending { it.updated }.take(60)
        if (notes.isEmpty()) { toast(L.t("matNone")); return }
        val wait = dialog().setMessage("✨ " + L.t("aiWorking")).setCancelable(false).show()
        val lang = if (L.lang == "en") "en" else "es"
        Thread {
            val input = notes.mapIndexed { k, n -> "[${k + 1}] " + Share.text(n.title, blocksFromJson(Store.readBody(n.id).first)).take(3000) }.joinToString("\n\n").take(14000)
            var arr: JSONArray? = null
            for (k in 0 until 3) { arr = Materials.parse(Ai.ask(Materials.system(lang), input, 90000)); if (arr != null) break }
            runOnUiThread {
                wait.dismiss()
                val a = arr ?: run { toast(L.t("aiFail")); return@runOnUiThread }
                var n = 0
                for (k in 0 until a.length()) {
                    val o = a.optJSONObject(k) ?: continue
                    val item = o.optString("item").trim(); if (item.isEmpty()) continue
                    val site = o.optString("site").trim()
                    val qty = if (o.isNull("qty")) null else o.optDouble("qty").takeIf { !it.isNaN() }
                    val noteIdx = o.optInt("note", 0) - 1
                    val old = list.firstOrNull { it.item.equals(item, true) && it.site.equals(site, true) }
                    if (old != null) { if (old.status == 0) old.qty = qty ?: old.qty }
                    else { list.add(Mat(newId(), item, qty, o.optString("unit"), site, 0, notes.getOrNull(noteIdx)?.id ?: "")); n++ }
                }
                save(); draw()
                toast(if (a.length() == 0) L.t("matNone") else L.t("matFound", "n" to a.length()))
            }
        }.start()
    }

    /** Agregar o cambiar un material a mano. */
    private fun edit(m: Mat?) {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        fun field(h: String, v: String, num: Boolean = false) = EditText(this).apply {
            hint = h; setText(v); setTextColor(T.text); setHintTextColor(T.faint)
            inputType = if (num) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }.also { c.addView(it) }
        val fi = field(L.t("matItem"), m?.item ?: "")
        val fq = field(L.t("matQty"), Materials.qtyText(m?.qty), true)
        val fs = field(L.t("matSite"), m?.site ?: "")
        val d = dialog().setTitle(if (m == null) L.t("matAdd") else m.item).setView(c)
            .setPositiveButton(L.t("save")) { _, _ ->
                val name = fi.text.toString().trim(); if (name.isEmpty()) return@setPositiveButton
                val q = fq.text.toString().replace(',', '.').toDoubleOrNull()
                if (m == null) list.add(Mat(newId(), name, q, "", fs.text.toString().trim(), 0, ""))
                else { m.item = name; m.qty = q; m.site = fs.text.toString().trim() }
                save(); draw()
            }.setNegativeButton(L.t("cancel"), null)
        if (m != null) d.setNeutralButton(L.t("del")) { _, _ -> list.remove(m); save(); draw() }
        d.show()
    }

    private fun more() {
        val items = arrayOf(L.t("matBoss"), L.t("matSaveNote"), L.t("matClear"))
        dialog().setItems(items) { _, i ->
            when (i) {
                0 -> dialog().setTitle(L.t("matBoss")).setItems(arrayOf("Español", "English", L.t("both"))) { _, k ->
                    val g = groups()
                    val t = when (k) { 0 -> Materials.bossText(g, "es"); 1 -> Materials.bossText(g, "en"); else -> Materials.bossText(g, "es") + "\n\n———\n\n" + Materials.bossText(g, "en") }
                    Share.sendText(this, L.t("materials"), t)
                }.show()
                1 -> {
                    val blocks = ArrayList<Block>()
                    for ((item, l) in groups()) {
                        val total = l.mapNotNull { it.qty }.sum()
                        blocks.add(Block.ListB("check", mutableListOf(Item(mutableListOf(Run((if (total > 0) Materials.qtyText(total) + " × " else "") + item, St(b = true))), l.all { it.status == 2 }))))
                        val sites = l.filter { it.site.isNotBlank() }.joinToString("\n") { "   " + (if (it.qty != null) Materials.qtyText(it.qty) + " · " else "") + it.site }
                        if (sites.isNotEmpty()) blocks.add(Block.Text(mutableListOf(Run(sites, St(color = 0xFF8E8E93.toInt())))))
                    }
                    val m = Store.createNote(L.t("materials"), blocks)
                    startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id))
                }
                2 -> confirm(L.t("matClear"), null, L.t("del"), true) { list.clear(); save(); draw() }
            }
        }.show()
    }
}
