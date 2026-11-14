package com.luisell.bestnotes2

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/* ============================================================
   Report.kt — 📊 Reporte del día: junta las notas de un día y la
   IA escribe un reporte profesional para tu jefe (como en Best Notes):
   Resumen · Trabajo realizado (por sitio) · Pendientes y Materiales
   (como checklist) · Notas para el supervisor.
   Nunca inventa nada. Si la IA falla, pone tus notas tal cual.
   ============================================================ */
object ReportAi {
    fun system(lang: String) =
        "You write daily work reports for a field technician (low-voltage / IT / security work: cameras, access control, " +
            "network cabling, switches, fiber, door hardware), using ONLY the notes of the day that the user gives you. " +
            "Write a clear, professional report that a supervisor can understand quickly. " +
            "Use EXACTLY these 5 sections, in this order, each title on its own line starting with \"## \": " +
            "1) Summary  2) Work completed  3) Pending / follow-up  4) Materials or purchases needed  5) Notes for the supervisor. " +
            "Write the section titles and ALL the text in ${Ai.langName(lang)}. " +
            "Summary: 2-3 short sentences. In the other sections use one \"- \" bullet per item. " +
            "In \"Work completed\", group items by site / building / floor when the notes mention them (start the bullet with the place). " +
            "Keep names, numbers, floors, rooms, port numbers, cable IDs, equipment models and technical terms exactly as written. " +
            "NEVER invent work, places, quantities or dates that are not in the notes. If a section has nothing, write a single bullet \"- \" followed by the word for \"None\". " +
            "Reply with ONLY the report: no introduction, no closing, no markdown other than \"## \" titles and \"- \" bullets."

    /** Texto de la IA → bloques. Las secciones 3 y 4 (pendientes y materiales) salen como checklist. */
    fun blocks(text: String): MutableList<Block> {
        val out = ArrayList<Block>()
        var sec = 0
        for (raw in text.replace("\r", "").lines()) {
            val l = raw.trim().replace("**", "")
            if (l.isEmpty()) continue
            val head = Regex("^#{1,4}\\s*(.+)$").find(l)
            if (head != null) {
                sec++
                val t = head.groupValues[1].replace(Regex("^\\d+[.)]\\s*"), "")
                if (out.isNotEmpty()) out.add(Block.Text(mutableListOf(Run(""))))
                out.add(Block.Text(mutableListOf(Run(t, St(b = true, size = 20)))))
                continue
            }
            val bul = Regex("^(?:[-•*–]|\\d+[.)])\\s+(.*)$").find(l)
            if (bul != null) {
                val kind = if (sec == 3 || sec == 4) "check" else "ul"
                val last = out.lastOrNull()
                val it = Item(mutableListOf(Run(bul.groupValues[1])))
                if (last is Block.ListB && last.kind == kind) last.items.add(it) else out.add(Block.ListB(kind, mutableListOf(it)))
            } else out.add(Block.Text(mutableListOf(Run(l))))
        }
        return normalize(out)
    }
}

class ReportActivity : Activity() {
    private var day = Calendar.getInstance()
    private var lang = "es"
    private var photos = true
    private val chosen = HashSet<String>()
    private lateinit var dayTv: android.widget.TextView
    private lateinit var list: LinearLayout
    private lateinit var langRow: LinearLayout
    private var notes: List<NoteMeta> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        lang = if (L.lang == "en") "en" else "es"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(12), dp(6)) }
        top.addView(glassBtn(Icons.BACK) { finish() })
        top.addView(tv("📊 " + L.t("report"), 20f, T.text, true).apply { setPadding(dp(8), 0, 0, 0) }, lp(0, WRAP, 1f))
        root.addView(top)
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(40)) }
        sv.addView(col)
        col.addView(tv(L.t("reportHint"), 14f, T.muted).apply { setPadding(dp(2), 0, 0, dp(10)) })
        // ‹ día ›
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Glass.make(this@ReportActivity, 22f); setPadding(dp(4), dp(2), dp(4), dp(2)) }
        dayRow.addView(iconBtn(Icons.BACK, 9f) { day.add(Calendar.DAY_OF_YEAR, -1); load() })
        dayTv = tv("", 17f, T.text, true).apply { gravity = Gravity.CENTER }
        dayRow.addView(dayTv, lp(0, WRAP, 1f))
        dayRow.addView(iconBtn(Icons.BACK, 9f) { day.add(Calendar.DAY_OF_YEAR, 1); load() }.apply { rotation = 180f })
        col.addView(dayRow, lp(MATCH, WRAP))
        col.addView(tv(L.t("reportNotes"), 13f, T.muted, true).apply { setPadding(dp(4), dp(16), 0, dp(4)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        col.addView(tv(L.t("reportLang"), 13f, T.muted, true).apply { setPadding(dp(4), dp(16), 0, dp(6)) })
        langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(langRow)
        paintLang()
        val ph = CheckBox(this).apply { text = L.t("reportInclude"); isChecked = true; setTextColor(T.text); setOnCheckedChangeListener { _, c -> photos = c } }
        col.addView(ph, lp(WRAP, WRAP).apply { topMargin = dp(10) })
        col.addView(tv("✨ " + L.t("reportMake"), 17f, 0xFF16161A.toInt(), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14)); pressBg(T.text, 26f); setOnClickListener { make() }
        }, lp(MATCH, WRAP).apply { topMargin = dp(18) })
        root.addView(sv, lp(MATCH, 0, 1f))
        setContentView(root)
        if (Store.loaded) load() else Store.load { load() }
    }

    private fun paintLang() {
        langRow.removeAllViews()
        for ((k, label) in listOf("es" to "Español", "en" to "English", "both" to L.t("both"))) {
            val on = lang == k
            langRow.addView(tv(label, 14.5f, if (on) 0xFF16161A.toInt() else T.text, true).apply {
                setPadding(dp(16), dp(9), dp(16), dp(9)); if (on) pressBg(T.text, 18f) else background = Glass.make(this@ReportActivity, 18f)
                setOnClickListener { lang = k; paintLang() }
            }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
    }

    private fun load() {
        val a = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val b = a + 86_400_000
        dayTv.text = SimpleDateFormat(if (L.lang == "es") "EEEE d 'de' MMMM" else "EEEE, MMMM d", L.locale).format(Date(a)).replaceFirstChar { it.uppercase() }
        notes = Store.notes.filter { !it.deleted && !it.locked && (it.updated in a until b || it.created in a until b) && !it.title.startsWith(L.t("report")) }.sortedBy { it.created }
        chosen.clear(); notes.forEach { chosen.add(it.id) }
        list.removeAllViews()
        if (notes.isEmpty()) list.addView(tv(L.t("reportNone"), 15f, T.muted).apply { setPadding(dp(6), dp(10), 0, dp(10)) })
        for (n in notes) list.addView(CheckBox(this).apply {
            text = n.title.ifEmpty { n.plain.lineSequence().firstOrNull { it.isNotBlank() } ?: L.t("untitled") }.take(60)
            isChecked = true; setTextColor(T.text); textSize = 15.5f
            setOnCheckedChangeListener { _, c -> if (c) chosen.add(n.id) else chosen.remove(n.id) }
        })
    }

    private fun make() {
        val sel = notes.filter { it.id in chosen }
        if (sel.isEmpty()) { toast(L.t("reportNone")); return }
        val dateTxt = dayTv.text.toString()
        val wait = dialog().setMessage("✨ " + L.t("aiWorking")).setCancelable(false).show()
        Thread {
            val bodies = sel.map { n -> n to blocksFromJson(Store.readBody(n.id).first) }
            val input = bodies.mapIndexed { k, (n, b) -> "[${k + 1}] " + Share.text(n.title, b) }.joinToString("\n\n")
            val langs = if (lang == "both") listOf("es", "en") else listOf(lang)
            val out = ArrayList<Block>()
            var failed = false
            for ((k, lg) in langs.withIndex()) {
                val r = Ai.ask(ReportAi.system(lg), input, 90000)
                if (r == null) { failed = true; break }
                if (k > 0) { out.add(Block.Text(mutableListOf(Run("")))); out.add(Block.Text(mutableListOf(Run("———————")))) }
                out.addAll(ReportAi.blocks(r))
            }
            if (failed) {
                out.clear()
                // Nunca sale vacío: tus notas tal cual
                for ((n, b) in bodies) {
                    out.add(Block.Text(mutableListOf(Run(n.title.ifEmpty { L.t("untitled") }, St(b = true, size = 20)))))
                    out.addAll(b.filter { it !is Block.Img && it !is Block.Media })
                }
            }
            if (photos) {
                val imgs = bodies.flatMap { (_, b) -> b.filterIsInstance<Block.Img>() }
                if (imgs.isNotEmpty()) {
                    out.add(Block.Text(mutableListOf(Run(if (L.lang == "es") "Fotos" else "Photos", St(b = true, size = 20)))))
                    imgs.forEach { out.add(Block.Img(it.file, 48f, "", 26f, 0f, it.w, it.h, it.stamp, 0f, it.taken)) }
                }
            }
            runOnUiThread {
                wait.dismiss()
                if (failed) toast(L.t("reportFallback"))
                val m = Store.createNote(L.t("reportTitle", "d" to dateTxt), out)
                startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id))
                finish()
            }
        }.start()
    }
}
