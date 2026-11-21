package com.luisell.bestnotes2

import android.app.Activity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.Gravity
import android.view.View

/* ============================================================
   Folders.kt — 📁 crear carpetas y escoger a cuál va la nota.
   ============================================================ */

/** Crear (f = null) o cambiar una carpeta: nombre y color. [onSaved] recibe la carpeta guardada. */
fun Activity.folderEditDialog(f: Folder?, onSaved: (Folder) -> Unit) {
    var color = f?.color ?: T.noteColors[5]
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
    val e = EditText(this).apply {
        setText(f?.name ?: ""); hint = L.t("folderName"); setTextColor(T.text); setHintTextColor(T.faint)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    box.addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(14), 0, dp(14), dp(6)) })
    val holder = FrameLayout(this)
    fun row() { holder.removeAllViews(); holder.addView(colorRow(T.noteColors, false, color) { c -> color = c; row() }) }
    row()
    box.addView(holder)
    val d = dialog().setTitle(L.t(if (f == null) "newFolder" else "editFolder")).setView(box)
        .setPositiveButton(L.t("save")) { _, _ ->
            val name = e.text.toString().trim()
            if (name.isEmpty()) return@setPositiveButton
            val ff = f ?: Folder(newId(), name, color, Store.folders.size)
            ff.name = name; ff.color = color
            Store.saveFolder(ff); Store.changed()
            onSaved(ff)
        }.setNegativeButton(L.t("cancel"), null).create()
    d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    d.show(); e.requestFocus()
}

/**
 * 📁 Mover a carpeta (como Best Notes viejo): arriba ← · MOVER A CARPETA · Listo;
 * "Sin carpeta" con su bandeja, tus carpetas con su ícono de SU color, ✓ en la que está,
 * y abajo "＋ Nueva carpeta". [current] = la carpeta de ahora ("?" = varias distintas).
 */
fun Activity.pickFolder(current: String?, onPick: (String?) -> Unit) {
    val act = this
    var chosen: String? = current
    lateinit var sheet: Sheet
    val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    val checks = HashMap<String, View>()          // id ("" = sin carpeta) → la ✓
    fun key(id: String?) = id ?: ""
    fun paintChecks(anim: Boolean) {
        for ((k, v) in checks) {
            val on = k == key(chosen) && chosen != "?"
            if (on && v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; if (anim) { v.scaleX = 0f; v.scaleY = 0f; v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start() } }
            else if (!on) v.visibility = View.INVISIBLE
        }
    }
    fun row(id: String?, icon: Int, color: Int, name: String): View {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)); pressBg(0, 16f) }
        r.addView(IconView(act, icon, 11f).apply { this.color = color; isClickable = false }, LinearLayout.LayoutParams(dp(32), dp(32)))
        r.addView(act.tv(name, 17f, T.text).apply { setPadding(dp(16), 0, dp(8), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val ck = IconView(act, Icons.CHECK, 10f).apply { this.color = T.text; isClickable = false; visibility = View.INVISIBLE }
        r.addView(ck, LinearLayout.LayoutParams(dp(26), dp(26)))
        checks[key(id)] = ck
        r.setOnClickListener { v -> Anim.press(v); chosen = id; paintChecks(true); v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        return r
    }
    fun fill() {
        list.removeAllViews(); checks.clear()
        list.addView(row(null, MoreIcons.INBOX, T.text, L.t("noFolder")))
        for (f in Store.folders) list.addView(row(f.id, MoreIcons.FOLDER, f.color, f.name))
        paintChecks(false)
    }
    fill()
    // arriba: ←   MOVER A CARPETA   (Listo)
    val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(4), dp(8)) }
    head.addView(FrameLayout(act).apply {
        background = Glass.make(act, 24f)
        addView(IconView(act, Icons.BACK, 8.5f).apply { color = T.text; isClickable = false }, FrameLayout.LayoutParams(dp(48), dp(48)))
        setOnClickListener { sheet.dismiss() }
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
    head.addView(act.tv(L.t("moveToFolder").uppercase(L.locale), 15f, T.muted, false).apply { letterSpacing = 0.14f; setPadding(dp(14), 0, dp(8), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(act.tv(L.t("done"), 17f, T.onPill, true).apply {
        setPadding(dp(20), dp(10), dp(20), dp(10)); pressBg(T.pill, 24f)
        setOnClickListener { v -> Anim.press(v); val c = chosen; sheet.dismiss { if (c != "?" && c != current) onPick(c) } }
    })
    // abajo: ＋ Nueva carpeta (la crea y la deja escogida)
    val foot = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    foot.addView(View(act).apply { setBackgroundColor(tw(0x14)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(14), dp(4), dp(14), 0) })
    val add = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(10)); pressBg(0, 16f) }
    add.addView(IconView(act, MoreIcons.PLUS, 10f).apply { color = T.text; isClickable = false }, LinearLayout.LayoutParams(dp(30), dp(30)))
    add.addView(act.tv(L.t("newFolder"), 17f, T.text).apply { setPadding(dp(16), 0, 0, 0) })
    add.setOnClickListener { v -> Anim.press(v); folderEditDialog(null) { ff -> chosen = ff.id; fill(); checks[ff.id]?.let { Anim.popIn(it) } } }
    foot.addView(add)
    sheet = Sheet(act).custom(head).custom(list).footer(foot)
    sheet.show()
}
