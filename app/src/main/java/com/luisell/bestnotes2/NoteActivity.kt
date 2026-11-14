package com.luisell.bestnotes2

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/* ============================================================
   NoteActivity.kt — la pantalla de una nota.
   Arriba: volver, deshacer, rehacer, favorito y ⋮.
   En medio: el título y el editor.
   Abajo (encima del teclado): checklist, viñetas, números,
   B, I, U, S, tamaño, color y resaltar.
   Se guarda sola mientras escribes.
   ============================================================ */
class NoteActivity : Activity(), EditorView.Host {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NEW = "nuevo"          // "text" o "check"
        const val EXTRA_Q = "q"                // lo que se buscaba (para iluminarlo)
        const val EXTRA_FOLDER = "carpeta"
    }

    private lateinit var meta: NoteMeta
    private var extra: String? = null
    private var isNew = false
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var title: EditText
    private lateinit var editor: EditorView
    private lateinit var bar: LinearLayout
    private lateinit var star: IconView
    private lateinit var undoBtn: IconView
    private lateinit var redoBtn: IconView
    private val toolBtns = HashMap<String, View>()
    private var loaded = false
    private var dirty = false
    private var muting = false

    private val saveRun = Runnable { save() }
    private val snapRun = Runnable { snapshot() }
    private val history = ArrayList<String>()
    private var hIdx = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Si la app se acaba de abrir (Android la cerró por memoria), primero se carga la lista
        if (!Store.loaded) { setContentView(View(this).apply { setBackgroundColor(T.bg) }); Store.load { init() }; return }
        init()
    }

    private fun init() {
        val id = intent.getStringExtra(EXTRA_ID)
        val m = id?.let { Store.meta(it) }
        if (id != null && m == null) { finish(); return }
        if (m == null) {
            isNew = true
            meta = NoteMeta(newId())
            meta.folder = intent.getStringExtra(EXTRA_FOLDER)
            meta.kind = "text"
        } else meta = m
        buildUi()
        if (isNew) {
            val kind = intent.getStringExtra(EXTRA_NEW) ?: "text"
            val b: MutableList<Block> = if (kind == "check") mutableListOf(Block.ListB("check", mutableListOf(Item(ArrayList())))) else mutableListOf(Block.Text())
            start(b)
            if (kind == "check") editor.post { editor.views.firstOrNull()?.focusAt(0, 0) } else { title.requestFocus(); showKeyboard(title) }
        } else {
            Store.io.execute {
                val (body, ex) = Store.readBody(meta.id)
                Store.main.post { extra = ex; start(blocksFromJson(body)) }
            }
        }
    }

    private fun start(blocks: MutableList<Block>) {
        muting = true
        editor.readOnly = meta.deleted
        editor.setBlocks(blocks)
        muting = false
        loaded = true
        snapshot()
        showBanners()
        val q = intent.getStringExtra(EXTRA_Q)
        if (!q.isNullOrBlank()) {
            Rich.highlight(title.text, q)
            val bi = editor.highlight(q)
            if (bi >= 0) editor.post { scroll.smoothScrollTo(0, maxOf(0, editor.top + editor.views[bi].view.top - dp(80))) }
        }
    }

    /* ---------- pantalla ---------- */
    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        tint()

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        top.addView(iconBtn(Icons.BACK) { finish() })
        top.addView(View(this), lp(0, 1, 1f))
        undoBtn = iconBtn(Icons.UNDO) { undo() }
        redoBtn = iconBtn(MoreIcons.REDO) { redo() }
        top.addView(undoBtn); top.addView(redoBtn)
        star = iconBtn(Icons.STAR) { meta.fav = !meta.fav; paintStar(); Store.saveMeta(meta); Store.changed() }
        top.addView(star)
        top.addView(iconBtn(MoreIcons.DOTS_V) { options() })
        root.addView(top, lp(MATCH, WRAP))
        paintStar(); paintUndo()

        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = EditText(this).apply {
            background = null
            setText(meta.title)
            hint = L.t("titlePh")
            setHintTextColor(T.faint)
            setTextColor(if (meta.titleColor != 0) meta.titleColor else T.text)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            setPadding(dp(18), dp(6), dp(18), dp(6))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable) {
                    // Enter en el título: baja al texto
                    val nl = s.indexOf('\n')
                    if (nl >= 0) { s.delete(nl, nl + 1); editor.views.firstOrNull()?.focusAt(0, 0); return }
                    onDirty()
                }
            })
            setOnFocusChangeListener { _, has -> if (has) onFocusEdit(null) }
        }
        col.addView(title, lp(MATCH, WRAP))
        bannerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        col.addView(bannerBox, lp(MATCH, WRAP))
        editor = EditorView(this, this)
        col.addView(editor, lp(MATCH, WRAP))
        // Tocar el espacio vacío de abajo = seguir escribiendo al final
        val filler = View(this).apply { setOnClickListener { editor.focusLastText() } }
        col.addView(filler, lp(MATCH, 0, 1f).apply { height = 0 })
        col.minimumHeight = resources.displayMetrics.heightPixels
        scroll.addView(col, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))

        // Barra de herramientas
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(0xF01C1C1F.toInt()) }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        fun tool(key: String, icon: Int, size: Float = 10.5f, act: () -> Unit) {
            val b = iconBtn(icon, size) { act() }
            toolBtns[key] = b; bar.addView(b)
        }
        tool("check", MoreIcons.CHECKBOX) { editor.toggleList("check") }
        tool("ul", MoreIcons.BULLETS) { editor.toggleList("ul") }
        tool("ol", MoreIcons.NUMBERS) { editor.toggleList("ol") }
        bar.addView(View(this).apply { setBackgroundColor(T.border) }, lp(dp(1), dp(24)).apply { marginStart = dp(4); marginEnd = dp(4) })
        tool("b", MoreIcons.BOLD) { style { s -> s.copy(b = !cur().b) } }
        tool("i", MoreIcons.ITALIC) { style { s -> s.copy(i = !cur().i) } }
        tool("u", MoreIcons.UNDERLINE) { style { s -> s.copy(u = !cur().u) } }
        tool("s", MoreIcons.STRIKE) { style { s -> s.copy(s = !cur().s) } }
        tool("size", MoreIcons.SIZE, 11f) { pickSize() }
        tool("color", MoreIcons.PALETTE) { pickColor(false) }
        tool("hi", MoreIcons.HILITE) { pickColor(true) }
        hs.addView(bar)
        root.addView(View(this).apply { setBackgroundColor(T.border) }, lp(MATCH, dp(1)))
        root.addView(hs, lp(MATCH, WRAP))
        setContentView(root)
    }

    private lateinit var bannerBox: LinearLayout

    private fun showBanners() {
        bannerBox.removeAllViews()
        if (meta.deleted) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(8), dp(8)); background = roundBg(T.card, dp(14).toFloat(), T.border, dp(1)) }
            r.addView(tv(L.t("inTrash"), 14f, T.muted), lp(0, WRAP, 1f))
            r.addView(tv(L.t("recover"), 14f, T.accent, true).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { recover() } })
            bannerBox.addView(r, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        val hasInk = try { (JSONObject(extra ?: "{}").optJSONArray("ink")?.length() ?: 0) > 0 } catch (_: Exception) { false }
        if (hasInk || meta.kind == "draw") {
            bannerBox.addView(tv(L.t("drawSoon"), 13.5f, T.muted).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10)); background = roundBg(T.card, dp(14).toFloat(), T.border, dp(1))
            }, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
    }

    private fun tint() {
        val c = meta.color
        val bg = if (c == 0) T.bg else blend(T.bg, c, 0.14f)
        root.setBackgroundColor(bg); window.statusBarColor = bg
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(), (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(), (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun paintStar() { star.color = if (meta.fav) 0xFFFFD60A.toInt() else T.text }
    private fun paintUndo() {
        undoBtn.alpha = if (hIdx > 0) 1f else 0.35f
        redoBtn.alpha = if (hIdx < history.size - 1) 1f else 0.35f
    }

    /* ---------- estilos ---------- */
    private fun cur(): St = editor.focused?.currentStyle() ?: St.PLAIN
    private fun style(fn: (St) -> St) {
        val e = editor.focused ?: return
        if (!e.hasFocus()) return
        val c = e.currentStyle()
        val next = fn(c)
        // se guarda solo lo que cambió (así no se pisan otros estilos al aplicarlo a un pedazo)
        e.applyStyle { s ->
            var r = s
            if (next.b != c.b) r = r.copy(b = next.b)
            if (next.i != c.i) r = r.copy(i = next.i)
            if (next.u != c.u) r = r.copy(u = next.u)
            if (next.s != c.s) r = r.copy(s = next.s)
            if (next.size != c.size) r = r.copy(size = next.size)
            if (next.color != c.color) r = r.copy(color = next.color)
            if (next.bg != c.bg) r = r.copy(bg = next.bg)
            r
        }
    }

    private fun pickSize() {
        val e = editor.focused ?: return
        val curSize = e.currentStyle().size.let { if (it == 0) T.baseSize else it }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val d = dialog().setTitle(L.t("size")).setView(ScrollView(this).apply { addView(list) }).setNegativeButton(L.t("cancel"), null).create()
        for (z in T.sizes) {
            val t = tv(if (z == T.baseSize) "$z  ·  Normal" else "$z", minOf(z, 40).toFloat(), if (z == curSize) T.accent else T.text).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, minOf(z, 40).toFloat())
                setPadding(dp(24), dp(8), dp(24), dp(8))
                setOnClickListener { d.dismiss(); e.requestFocus(); style { s -> s.copy(size = if (z == T.baseSize) 0 else z) } }
            }
            list.addView(t)
        }
        d.show()
    }

    private fun pickColor(hi: Boolean) {
        val e = editor.focused ?: return
        val c = e.currentStyle()
        lateinit var d: android.app.AlertDialog
        val v = colorRow(if (hi) T.hiliteColors else T.textColors, true, if (hi) c.bg else c.color) { col ->
            d.dismiss(); e.requestFocus()
            style { s -> if (hi) s.copy(bg = col) else s.copy(color = col) }
        }
        d = dialog().setTitle(L.t(if (hi) "hilite" else "textColor")).setView(v).setNegativeButton(L.t("cancel"), null).show()
    }

    override fun onFocusEdit(e: RichEdit?) {
        val st = e?.currentStyle() ?: St.PLAIN
        fun on(k: String, v: Boolean) { toolBtns[k]?.background = if (v) roundBg(0x33FFFFFF, dp(22).toFloat()) else android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, roundBg(Color.WHITE, dp(22).toFloat())) }
        on("b", st.b); on("i", st.i); on("u", st.u); on("s", st.s)
        on("size", st.size > 0); on("color", st.color != 0); on("hi", st.bg != 0)
        val kind = e?.let { ed -> editor.views.firstOrNull { it.edits().contains(ed) }?.block as? Block.ListB }?.kind
        on("check", kind == "check"); on("ul", kind == "ul"); on("ol", kind == "ol")
        (toolBtns["color"] as? IconView)?.color = if (st.color != 0) st.color else T.text
    }

    /* ---------- guardar ---------- */
    override fun onDirty() {
        if (muting || !loaded) return
        dirty = true
        Store.main.removeCallbacks(saveRun); Store.main.postDelayed(saveRun, 1200)
        Store.main.removeCallbacks(snapRun); Store.main.postDelayed(snapRun, 700); snapPending = true
    }

    private fun isEmptyNote(): Boolean = title.text.isBlank() && editor.blocks.all {
        when (it) { is Block.Text -> runsPlain(it.runs).isBlank(); is Block.ListB -> it.items.all { x -> runsPlain(x.runs).isBlank() }; else -> false }
    }

    private fun save() {
        Store.main.removeCallbacks(saveRun)
        if (!loaded || !dirty) return
        dirty = false
        editor.flushAll()
        if (isNew && isEmptyNote()) return
        meta.title = title.text.toString().trim()
        meta.updated = System.currentTimeMillis()
        Store.fillMeta(meta, editor.blocks)
        Store.saveNote(meta, blocksJson(editor.blocks), extra)
        isNew = false
        Store.changed()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    /* ---------- deshacer / rehacer ---------- */
    private fun state(): String {
        editor.flushAll()
        return JSONObject().put("t", title.text.toString()).put("b", blocksJson(editor.blocks)).toString()
    }

    private var snapPending = false
    private fun snapshot() {
        Store.main.removeCallbacks(snapRun); snapPending = false
        if (!loaded) return
        val s = state()
        if (hIdx >= 0 && history[hIdx] == s) return
        while (history.size > hIdx + 1) history.removeAt(history.size - 1)
        history.add(s); hIdx = history.size - 1
        if (history.size > 200) { history.removeAt(0); hIdx-- }
        paintUndo()
    }

    private fun restore(s: String) {
        val o = JSONObject(s)
        val f = editor.focused
        val bi = editor.views.indexOfFirst { v -> f != null && v.edits().contains(f) }
        muting = true
        title.setText(o.optString("t"))
        editor.setBlocks(blocksFromJson(o.optString("b")))
        muting = false
        dirty = true; Store.main.removeCallbacks(saveRun); Store.main.postDelayed(saveRun, 600)
        if (bi >= 0) editor.post { editor.views.getOrNull(minOf(bi, editor.views.size - 1))?.focusAt(-1, -1) }
        paintUndo()
    }

    private fun undo() {
        if (snapPending) snapshot()
        if (hIdx <= 0) return
        hIdx--; restore(history[hIdx])
    }

    private fun redo() {
        if (hIdx >= history.size - 1) return
        hIdx++; restore(history[hIdx])
    }

    /* ---------- checklist: "¿Ya terminaste esto?" ---------- */
    override fun askDone(onYes: () -> Unit) {
        dialog().setMessage(L.t("ckDoneQ")).setPositiveButton(L.t("ckDoneYes")) { _, _ -> onYes() }.setNegativeButton(L.t("cancel"), null).show()
    }

    override fun openMedia(file: String, mime: String) {
        val f = File(Store.mediaDir, file)
        if (!f.exists()) { toast(L.t("cantOpen")); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.archivos", f)
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.t("openWith")))
        } catch (_: Exception) { toast(L.t("cantOpen")) }
    }

    /* ---------- ⋮ opciones ---------- */
    private fun options() {
        save()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(10), dp(6), dp(10)) }
        val d = dialog().setView(ScrollView(this).apply { addView(list) }).create()
        fun add(icon: Int, label: String, color: Int = T.text, act: () -> Unit) { list.addView(menuRow(this, icon, label, null, color) { d.dismiss(); act() }) }
        if (meta.deleted) {
            add(MoreIcons.RESTORE, L.t("recover")) { recover() }
            add(Icons.TRASH, L.t("delForever"), T.danger) {
                confirm(L.t("delForever"), L.t("delForeverMsg"), L.t("del"), true) { Store.deleteForever(listOf(meta.id)); Store.changed(); loaded = false; finish() }
            }
        } else {
            add(MoreIcons.PALETTE, L.t("noteColor")) { noteColor() }
            add(MoreIcons.FOLDER, L.t("moveToFolder")) { moveFolder() }
            add(Icons.PIN, L.t(if (meta.pinned) "unpin" else "pinTop")) { meta.pinned = !meta.pinned; Store.saveMeta(meta); Store.changed() }
            add(Icons.LOCK, L.t(if (meta.locked) "unlock" else "lockPin")) { toggleLock() }
            add(MoreIcons.INFO, L.t("info")) { info() }
            add(Icons.TRASH, L.t("moveTrash"), T.danger) {
                if (isNew && isEmptyNote()) { finish(); return@add }
                meta.deleted = true; meta.deletedAt = System.currentTimeMillis(); Store.saveMeta(meta); Store.changed()
                toast(L.t("movedTrash")); finish()
            }
        }
        d.show()
    }

    private fun recover() {
        meta.deleted = false; Store.saveMeta(meta); Store.changed(); toast(L.t("recovered"))
        editor.readOnly = false; editor.flushAll(); editor.rebuild(null); showBanners()
    }

    private fun noteColor() {
        lateinit var d: android.app.AlertDialog
        val v = colorRow(T.noteColors, true, meta.color) { c -> d.dismiss(); meta.color = c; tint(); Store.saveMeta(meta); Store.changed() }
        d = dialog().setTitle(L.t("noteColor")).setView(v).setNegativeButton(L.t("cancel"), null).show()
    }

    private fun moveFolder() {
        val names = listOf(L.t("noFolder")) + Store.folders.map { it.name }
        dialog().setTitle(L.t("moveToFolder")).setItems(names.toTypedArray()) { _, i ->
            meta.folder = if (i == 0) null else Store.folders[i - 1].id
            if (!isNew) { Store.saveMeta(meta); Store.changed() } else onDirty()
        }.show()
    }

    private fun toggleLock() {
        if (meta.locked) Pin.check(this) { meta.locked = false; Store.saveMeta(meta); Store.changed(); toast(L.t("lockRemoved")) }
        else {
            val go = { meta.locked = true; dirty = true; save(); Store.saveMeta(meta); Store.changed(); toast(L.t("lockedOk")) }
            if (Pin.saved(this) == null) Pin.create(this) { go() } else go()
        }
    }

    private fun info() {
        editor.flushAll()
        val txt = title.text.toString() + "\n" + blocksPlain(editor.blocks)
        val words = Regex("\\S+").findAll(txt).count()
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, L.locale)
        dialog().setTitle(L.t("info")).setMessage(
            L.t("wordCount", "w" to words, "c" to txt.replace(Regex("\\s"), "").length) + "\n\n" +
                L.t("created") + ": " + df.format(Date(meta.created)) + "\n" + L.t("modified") + ": " + df.format(Date(meta.updated)),
        ).setPositiveButton(L.t("ok"), null).show()
    }

    override fun finish() {
        save()
        super.finish()
    }
}
