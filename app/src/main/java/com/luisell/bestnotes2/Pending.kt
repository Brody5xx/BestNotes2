package com.luisell.bestnotes2

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/* ============================================================
   Pending.kt — ✅ Pendientes: todas las casillas SIN marcar de
   todas tus notas, en un solo lugar (agrupadas por nota).
   Tocar la casilla la marca como hecha en su nota.
   Las notas bloqueadas o en la papelera no salen.
   ============================================================ */
object Pending {
    class Row(val note: NoteMeta, val index: Int, val text: String)

    /** Lee los pendientes (en segundo plano) y los entrega en la pantalla. */
    fun load(done: (List<Row>) -> Unit) {
        val notes = Store.notes.filter { !it.deleted && !it.locked && it.checkTotal > it.checkDone }.sortedByDescending { it.updated }
        Store.io.execute {
            val out = ArrayList<Row>()
            for (n in notes) {
                val blocks = blocksFromJson(Store.readBody(n.id).first)
                var k = 0
                for (b in blocks) if (b is Block.ListB) {
                    for (it in b.items) {
                        if (b.kind == "check" && !it.done && runsPlain(it.runs).isNotBlank()) out.add(Row(n, k, runsPlain(it.runs)))
                        k++
                    }
                }
            }
            Store.main.post { done(out) }
        }
    }

    /** Marca como hecho el renglón número [index] (contando todos los renglones de listas de la nota). */
    /** 🎉 Confeti si con este se terminaron los pendientes de la nota (o todos los de la app). */
    fun celebrate(act: android.app.Activity, row: Row) {
        val n = row.note
        val left = Store.notes.filter { !it.deleted && !it.locked }.sumOf { maxOf(0, it.checkTotal - it.checkDone) }
        when {
            left == 0 -> { Confetti.burst(act); act.toast("🎉 " + L.t("allPendDone")) }
            n.checkTotal >= 2 && n.checkDone >= n.checkTotal -> { Confetti.burst(act); act.toast("🎉 " + L.t("notePendDone", "t" to n.title.ifEmpty { L.t("untitled") })) }
        }
    }

    fun markDone(row: Row, after: () -> Unit) {
        Store.io.execute {
            val (body, extra) = Store.readBody(row.note.id)
            val blocks = blocksFromJson(body)
            var k = 0
            loop@ for (b in blocks) if (b is Block.ListB) {
                for (it in b.items.toList()) {
                    if (k == row.index) {
                        if (runsPlain(it.runs) == row.text && !it.done) {
                            it.pos = b.items.filter { x -> !x.done }.indexOf(it)
                            it.done = true; b.items.remove(it); b.items.add(it)
                        }
                        break@loop
                    }
                    k++
                }
            }
            Store.main.post {
                row.note.updated = System.currentTimeMillis()
                Store.fillMeta(row.note, blocks)
                Store.saveNote(row.note, blocksJson(blocks), extra)
                Store.changed(); after()
            }
        }
    }
}

class PendingActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var count: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(12), dp(6)) }
        top.addView(iconBtn(Icons.BACK) { finish() })
        top.addView(tv(L.t("pending"), 20f, T.text, true), lp(0, WRAP, 1f))
        count = tv("", 14f, T.muted); top.addView(count)
        root.addView(top)
        val sv = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(40)) }
        sv.addView(list)
        root.addView(sv, lp(MATCH, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); if (Store.loaded) refresh() else Store.load { refresh() } }

    private fun refresh() {
        Pending.load { rows -> draw(rows) }
    }

    private fun draw(rows: List<Pending.Row>) {
        list.removeAllViews()
        count.text = if (rows.isEmpty()) "" else "${rows.size}"
        if (rows.isEmpty()) { list.addView(tv(L.t("pendingEmpty"), 16f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(80), dp(20), 0) }); return }
        for ((note, items) in rows.groupBy { it.note }) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(8)); background = roundBg(if (note.color != 0) (note.color and 0xFFFFFF) or 0x2E000000 else T.card, dp(18).toFloat(), T.border, dp(1)) }
            card.addView(tv(note.title.ifEmpty { L.t("untitled") }, 16f, if (note.titleColor != 0) note.titleColor else if (note.color != 0) note.color or 0xFF000000.toInt() else T.text, true).apply {
                setPadding(0, dp(2), 0, dp(6)); setOnClickListener { startActivity(Intent(this@PendingActivity, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, note.id)) }
            })
            for (r in items) card.addView(itemRow(r))
            list.addView(card, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    private fun itemRow(r: Pending.Row): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val box = ListaView.Mark(this, "check", false, 0)
        box.setOnClickListener {
            dialog().setMessage(L.t("ckDoneQ") + "\n\n" + r.text).setPositiveButton(L.t("ckDoneYes")) { _, _ -> Pending.markDone(r) { refresh(); Pending.celebrate(this, r) } }
                .setNegativeButton(L.t("cancel"), null).show()
        }
        row.addView(box, lp(dp(34), dp(34)))
        row.addView(tv(r.text, 15.5f, T.text).apply { setPadding(0, dp(6), dp(4), dp(6)) }, lp(0, WRAP, 1f))
        row.addView(iconBtn(Icons.CLOCK, 8f, T.muted) { remind(this, r.note.id, r.text) }.apply { layoutParams = lp(dp(38), dp(38)) })
        return row
    }
}

/** Ventana para poner un recordatorio (se usa en la nota y en Pendientes). */
fun remind(a: Activity, noteId: String, text: String, done: () -> Unit = {}) {
    if (android.os.Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
    val q = Reminders.quick()
    val existing = Reminders.forNote(a, noteId)
    val labels = ArrayList<String>()
    q.forEach { labels.add(it.first + "  ·  " + L.when_(it.second)) }
    labels.add(L.t("pickDateTime"))
    existing.forEach { labels.add("✕ " + L.t("removeReminder") + ": " + L.when_(it.at)) }
    a.dialog().setTitle(L.t("reminder")).setItems(labels.toTypedArray()) { _, i ->
        when {
            i < q.size -> { Reminders.add(a, noteId, text, q[i].second); a.toast(L.t("reminderSet", "w" to L.when_(q[i].second))); done() }
            i == q.size -> pickDateTime(a) { at -> Reminders.add(a, noteId, text, at); a.toast(L.t("reminderSet", "w" to L.when_(at))); done() }
            else -> { Reminders.remove(a, existing[i - q.size - 1].id); done() }
        }
    }.show()
}

fun pickDateTime(a: Activity, onPick: (Long) -> Unit) {
    val c = java.util.Calendar.getInstance()
    android.app.DatePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, { _, y, m, d ->
        android.app.TimePickerDialog(a, android.R.style.Theme_Material_Dialog_Alert, { _, h, min ->
            val t = java.util.Calendar.getInstance().apply { set(y, m, d, h, min, 0) }
            onPick(t.timeInMillis)
        }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), L.is24).show()
    }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
}
