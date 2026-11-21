package com.luisell.bestnotes2

import android.app.Activity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

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
 * Mover a carpeta: primero "➕ Agregar carpeta" (la crea y mete la nota ahí de una vez),
 * después tus carpetas, y al final "Sacar de la carpeta" (solo si ya está en una).
 */
fun Activity.pickFolder(inFolder: Boolean, onPick: (String?) -> Unit) {
    val names = ArrayList<String>()
    names.add("➕  " + L.t("addFolder"))
    Store.folders.forEach { names.add("📁  " + it.name) }
    if (inFolder) names.add("↩  " + L.t("outOfFolder"))
    dialog().setTitle(L.t("moveToFolder")).setItems(names.toTypedArray()) { _, i ->
        when {
            i == 0 -> folderEditDialog(null) { ff -> onPick(ff.id); toast("📁 " + ff.name) }
            i <= Store.folders.size -> onPick(Store.folders[i - 1].id)
            else -> onPick(null)
        }
    }.show()
}
