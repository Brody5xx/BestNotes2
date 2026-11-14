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
        const val REQ_CAMERA = 51
        const val REQ_GALLERY = 52
        const val REQ_CROP = 53
        const val REQ_MIC = 54
        const val REQ_VIDEO_CAM = 55
        const val REQ_VIDEO_PICK = 56
        const val REQ_AUDIO_PICK = 57
        const val REQ_PHOTO_DRAW = 58
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
            if (kind == "draw") meta.kind = "draw"
            start(b)
            when (kind) {
                "check" -> editor.post { editor.views.firstOrNull()?.focusAt(0, 0) }
                "draw" -> setDrawMode(true)
                else -> { title.requestFocus(); showKeyboard(title) }
            }
        } else {
            Store.io.execute {
                val (body, ex) = Store.readBody(meta.id)
                Store.main.post { extra = ex; start(blocksFromJson(body)) }
            }
        }
    }

    private fun start(blocks: MutableList<Block>) {
        extraObj = try { JSONObject(extra ?: "{}") } catch (_: Exception) { JSONObject() }
        ink.set(Stroke.listFrom(extraObj.optJSONArray("ink")))
        fitInk()
        showWall()
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
        star = iconBtn(MoreIcons.STAR5) { meta.fav = !meta.fav; paintStar(); Store.saveMeta(meta); Store.changed() }
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
        this.col = col
        // La hoja de dibujo va encima de toda la nota (título incluido), como en Best Notes
        val page = android.widget.FrameLayout(this)
        page.addView(col, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        ink = InkView(this)
        ink.onPan = { dy -> scroll.scrollBy(0, dy.toInt()) }
        ink.onChange = { inkChanged() }
        page.addView(ink, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        scroll.addView(page, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        val holder = android.widget.FrameLayout(this)
        wallImg = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        holder.addView(wallImg, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        holder.addView(scroll, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(holder, lp(MATCH, 0, 1f))

        // Barra de herramientas
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(0xF01C1C1F.toInt()) }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        fun tool(key: String, icon: Int, size: Float = 10.5f, act: () -> Unit) {
            val b = iconBtn(icon, size) { act() }
            toolBtns[key] = b; bar.addView(b)
        }
        tool("photo", Icons.IMAGE) { addPhoto() }
        tool("mic", MoreIcons.MIC) { audioMenu() }
        tool("video", MoreIcons.VIDEO) { videoMenu() }
        tool("draw", MoreIcons.PEN) { setDrawMode(true) }
        tool("check", MoreIcons.CHECKBOX) { editor.toggleList("check") }
        tool("ul", MoreIcons.BULLETS) { editor.toggleList("ul") }
        tool("ol", MoreIcons.NUMBERS) { editor.toggleList("ol") }
        bar.addView(View(this).apply { setBackgroundColor(T.border) }, lp(dp(1), dp(24)).apply { marginStart = dp(4); marginEnd = dp(4) })
        tool("b", MoreIcons.BOLD) { style { s -> s.copy(b = !cur().b) } }
        tool("i", MoreIcons.ITALIC) { style { s -> s.copy(i = !cur().i) } }
        tool("u", MoreIcons.UNDERLINE) { style { s -> s.copy(u = !cur().u) } }
        tool("s", MoreIcons.STRIKE) { style { s -> s.copy(s = !cur().s) } }
        tool("size", MoreIcons.SIZE, 11f) { pickSize() }
        tool("color", MoreIcons.PALETTE) { pickColor("color") }
        tool("hi", MoreIcons.HILITE) { pickColor("hi") }
        tool("out", MoreIcons.OUTLINE) { pickColor("ol") }
        hs.addView(bar)
        formatBar = hs
        root.addView(View(this).apply { setBackgroundColor(T.border) }, lp(MATCH, dp(1)))
        bottom = android.widget.FrameLayout(this).apply { setBackgroundColor(0xF01C1C1F.toInt()) }
        bottom.addView(hs, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(bottom, lp(MATCH, WRAP))
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
    }

    private fun tint() {
        val c = meta.color
        val bg = if (c == 0) T.bg else blend(T.bg, c, 0.14f)
        root.setBackgroundColor(bg); window.statusBarColor = bg
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(), (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(), (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun paintStar() { star.kind = if (meta.fav) MoreIcons.STAR5_ON else MoreIcons.STAR5; star.color = if (meta.fav) 0xFFFFD60A.toInt() else T.text; star.invalidate() }
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
            if (next.o != c.o) r = r.copy(o = next.o)
            r
        }
    }

    private fun pickSize() {
        val e = editor.focused ?: return
        pickSize(e.currentStyle().size) { z -> e.requestFocus(); style { s -> s.copy(size = z) } }
    }

    /** Color de letra (o del título si estás en el título), resaltar o contorno. */
    private fun pickColor(kind: String) {
        if (kind == "color" && title.hasFocus()) {
            pickColor(L.t("titleColor"), meta.titleColor, T.textColors) { c ->
                meta.titleColor = c; title.setTextColor(if (c != 0) c else T.text); onDirty()
            }
            return
        }
        val e = editor.focused ?: return
        val c = e.currentStyle()
        when (kind) {
            "hi" -> pickColor(L.t("hilite"), if (c.bg != 0) c.bg or 0xFF000000.toInt() else 0, T.hiliteColors.map { it or 0xFF000000.toInt() }.toIntArray()) { col ->
                e.requestFocus(); style { s -> s.copy(bg = if (col == 0) 0 else (col and 0xFFFFFF) or 0x73000000) }
            }
            "ol" -> pickColor(L.t("outline"), c.o, T.textColors) { col -> e.requestFocus(); style { s -> s.copy(o = col) } }
            else -> pickColor(L.t("textColor"), c.color, T.textColors) { col -> e.requestFocus(); style { s -> s.copy(color = col) } }
        }
    }

    override fun onFocusEdit(e: RichEdit?) {
        val st = e?.currentStyle() ?: St.PLAIN
        fun on(k: String, v: Boolean) { toolBtns[k]?.background = if (v) roundBg(0x33FFFFFF, dp(22).toFloat()) else android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, roundBg(Color.WHITE, dp(22).toFloat())) }
        on("b", st.b); on("i", st.i); on("u", st.u); on("s", st.s)
        on("size", st.size > 0); on("color", st.color != 0); on("hi", st.bg != 0); on("out", st.o != 0)
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

    private fun isEmptyNote(): Boolean = title.text.isBlank() && ink.strokes.isEmpty() && editor.blocks.all {
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
        extra = if (extraObj.length() > 0) extraObj.toString() else null
        Store.saveNote(meta, blocksJson(editor.blocks), extra, keepExtra = false)
        isNew = false
        Store.changed()
    }

    override fun onPause() {
        super.onPause()
        AudioPlayer.stop()
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
        if (ink.drawing) { ink.undo(); paintDrawBar(); return }
        if (snapPending) snapshot()
        if (hIdx <= 0) return
        hIdx--; restore(history[hIdx])
    }

    private fun redo() {
        if (ink.drawing) { ink.redoOne(); paintDrawBar(); return }
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

    /* ---------- ✏️ dibujar ---------- */
    private lateinit var ink: InkView
    private lateinit var col: LinearLayout
    private lateinit var wallImg: android.widget.ImageView
    private lateinit var bottom: android.widget.FrameLayout
    private lateinit var formatBar: View
    private var drawBar: LinearLayout? = null
    private var extraObj = JSONObject()

    /** Fondo propio de la nota (los que vienen de Best Notes). */
    private fun showWall() {
        val w = extraObj.optString("wall", "")
        if (w.isEmpty()) { wallImg.visibility = View.GONE; return }
        wallImg.visibility = View.VISIBLE
        wallImg.setColorFilter(0x59000000, android.graphics.PorterDuff.Mode.SRC_ATOP)
        Thumbs.load(w, resources.displayMetrics.widthPixels) { bm -> if (bm != null) wallImg.setImageBitmap(bm) else wallImg.visibility = View.GONE }
        root.setBackgroundColor(T.bg)
    }

    /** La hoja crece para que quepa todo el dibujo (y en modo dibujo, un poco más para seguir). */
    private fun fitInk() {
        ink.post {
            val need = (Stroke.bottom(ink.strokes, ink.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()) +
                dp(if (ink.drawing) 500 else 40)).toInt()
            val min = maxOf(resources.displayMetrics.heightPixels, need)
            if (col.minimumHeight != min) { col.minimumHeight = min; col.requestLayout() }
        }
    }

    private fun inkChanged() {
        if (ink.strokes.isEmpty()) extraObj.remove("ink") else extraObj.put("ink", Stroke.listJson(ink.strokes))
        fitInk(); paintDrawBar(); onDirty()
    }

    fun setDrawMode(on: Boolean) {
        ink.drawing = on
        if (on) {
            editor.selectImage(null)
            currentFocus?.let { v -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); v.clearFocus() }
            if (drawBar == null) drawBar = buildDrawBar()
            formatBar.visibility = View.GONE
            if (drawBar!!.parent == null) bottom.addView(drawBar, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
            drawBar!!.visibility = View.VISIBLE
            paintDrawBar()
            toast(L.t("drawHint"))
        } else {
            drawBar?.visibility = View.GONE
            formatBar.visibility = View.VISIBLE
            paintUndo()
        }
        fitInk()
    }

    private val drawBtns = HashMap<String, View>()
    private fun buildDrawBar(): LinearLayout {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        row.addView(tv("✓ " + L.t("done"), 15f, 0xFF16161A.toInt(), true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8)); pressBg(T.text, 18f); setOnClickListener { setDrawMode(false) }
        }, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        fun b(k: String, icon: Int, act: () -> Unit) { val v = iconBtn(icon, 10.5f) { act() }; drawBtns[k] = v; row.addView(v) }
        b("pen", MoreIcons.PEN) { ink.tool = "pen"; paintDrawBar() }
        b("hl", MoreIcons.HILITE) { ink.tool = "hl"; paintDrawBar() }
        b("eraser", MoreIcons.ERASER) { ink.tool = "eraser"; paintDrawBar() }
        val dot = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) { p.color = ink.color; c.drawCircle(width / 2f, height / 2f, dp(11).toFloat(), p); p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = T.text; c.drawCircle(width / 2f, height / 2f, dp(12).toFloat(), p); p.style = android.graphics.Paint.Style.FILL }
        }
        dot.setOnClickListener { pickColor(L.t("penColor"), ink.color, T.textColors) { c -> if (c != 0) { ink.color = c; if (ink.tool == "eraser") ink.tool = "pen"; paintDrawBar() } } }
        drawBtns["color"] = dot; row.addView(dot, lp(dp(44), dp(44)))
        val sz = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = T.text }
            override fun onDraw(c: android.graphics.Canvas) { c.drawCircle(width / 2f, height / 2f, dp(floatArrayOf(2.5f, 4.5f, 7f)[ink.size]).toFloat(), p) }
        }
        sz.setOnClickListener { ink.size = (ink.size + 1) % 3; sz.invalidate() }
        drawBtns["size"] = sz; row.addView(sz, lp(dp(44), dp(44)))
        b("undo", Icons.UNDO) { ink.undo(); paintDrawBar() }
        b("redo", MoreIcons.REDO) { ink.redoOne(); paintDrawBar() }
        b("clear", Icons.TRASH) { confirm(L.t("clearDrawing"), L.t("clearDrawingMsg"), L.t("del"), true) { ink.clearAll(); paintDrawBar() } }
        hs.addView(row)
        return LinearLayout(this).apply { addView(hs, lp(MATCH, WRAP)) }
    }

    private fun paintDrawBar() {
        if (drawBar == null) return
        for (k in listOf("pen", "hl", "eraser")) drawBtns[k]?.background = if (ink.tool == k) roundBg(0x33FFFFFF, dp(22).toFloat()) else null
        drawBtns["color"]?.invalidate()
        drawBtns["undo"]?.alpha = if (ink.canUndo()) 1f else 0.35f
        drawBtns["redo"]?.alpha = if (ink.canRedo()) 1f else 0.35f
        undoBtn.alpha = if (ink.canUndo()) 1f else 0.35f
        redoBtn.alpha = if (ink.canRedo()) 1f else 0.35f
    }

    /* ---------- 🎙️ audio ---------- */
    private var rec: Recorder? = null
    private var recBar: View? = null
    private var recTime: TextView? = null
    private val recTick = object : Runnable {
        override fun run() { val r = rec ?: return; recTime?.text = MediaFiles.timeText(r.elapsed() / 1000.0); Store.main.postDelayed(this, 500) }
    }

    private fun audioMenu() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(10), dp(6), dp(10)) }
        val d = dialog().setTitle(L.t("audio")).setView(list).create()
        list.addView(menuRow(this, MoreIcons.MIC, L.t("recordNow")) { d.dismiss(); startRecording() })
        list.addView(menuRow(this, MoreIcons.ATTACH, L.t("audioFile"), L.t("audioFileSub")) { d.dismiss(); pickMedia(false) })
        d.show()
    }

    private fun startRecording() {
        if (rec != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        AudioPlayer.stop()
        val r = Recorder(this)
        if (!r.start()) { toast(L.t("recFail")); return }
        rec = r
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(8), dp(8)) }
        bar.addView(View(this).apply { background = roundBg(0xFFFF3B30.toInt(), dp(6).toFloat()) }, lp(dp(12), dp(12)))
        val t = tv("0:00", 18f, T.text, true).apply { setPadding(dp(10), 0, 0, 0) }
        recTime = t
        bar.addView(t, lp(0, WRAP, 1f))
        val pause = tv(L.t("pause"), 14f, T.text, true).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); pressBg(T.cardStrong, 16f, T.border) }
        pause.setOnClickListener { rec?.let { it.togglePause(); pause.text = L.t(if (it.paused) "resume" else "pause") } }
        bar.addView(pause, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        bar.addView(tv(L.t("cancel"), 14f, T.danger, true).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { stopRecording(false) } }, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        bar.addView(tv("✓ " + L.t("done"), 14f, 0xFF16161A.toInt(), true).apply { setPadding(dp(14), dp(8), dp(14), dp(8)); pressBg(T.text, 16f); setOnClickListener { stopRecording(true) } })
        recBar = bar
        formatBar.visibility = View.GONE
        bottom.addView(bar, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        Store.main.post(recTick)
    }

    private fun stopRecording(keep: Boolean) {
        val r = rec ?: return
        rec = null
        Store.main.removeCallbacks(recTick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recBar?.let { bottom.removeView(it) }; recBar = null
        if (!ink.drawing) formatBar.visibility = View.VISIBLE
        if (!keep) { r.cancel(); return }
        val f = r.stop() ?: run { toast(L.t("recFail")); return }
        val i = MediaFiles.info(f)
        editor.insertImages(listOf(Block.Media("audio", f, i.dur, i.size)))
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
            else toast(L.t("micPerm"))
        }
    }

    /* ---------- 🎬 video ---------- */
    private var vidFile: File? = null

    private fun videoMenu() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(10), dp(6), dp(10)) }
        val d = dialog().setTitle(L.t("video")).setView(list).create()
        list.addView(menuRow(this, MoreIcons.VIDEO, L.t("recordVideo")) { d.dismiss(); recordVideo() })
        list.addView(menuRow(this, Icons.IMAGE, L.t("gallery")) { d.dismiss(); pickMedia(true) })
        d.show()
    }

    private fun recordVideo() {
        val f = File(cacheDir, "video_" + System.currentTimeMillis() + ".mp4")
        vidFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.archivos", f)
        val i = Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_VIDEO_CAM) } catch (_: Exception) { toast(L.t("cantOpen")) }
    }

    private fun pickMedia(video: Boolean) {
        val i = if (video && android.os.Build.VERSION.SDK_INT >= 33)
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("video/*")
        else Intent(Intent.ACTION_GET_CONTENT).setType(if (video) "video/*" else "audio/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, if (video) REQ_VIDEO_PICK else REQ_AUDIO_PICK) } catch (_: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType(if (video) "video/*" else "audio/*"), if (video) REQ_VIDEO_PICK else REQ_AUDIO_PICK) } catch (_: Exception) { toast(L.t("cantOpen")) }
        }
    }

    /** Copia el audio o video a la nota (en segundo plano) y lo pone donde está el cursor. */
    private fun addMedia(uri: android.net.Uri, video: Boolean, after: () -> Unit = {}) {
        toast(L.t(if (video) "addingVideo" else "addingAudio"))
        Loader.pool.execute {
            val i = try { MediaFiles.copyIn(this, uri, video) } catch (_: Throwable) { null }
            if (i != null && video) MediaFiles.makeThumb(i.file)
            runOnUiThread {
                after()
                if (i == null) toast(L.t("videoFail"))
                else editor.insertImages(listOf(Block.Media(if (video) "video" else "audio", i.file, i.dur, i.size)))
            }
        }
    }

    override fun drawOnImage(f: FotoView) {
        cropTarget = f.block
        startActivityForResult(Intent(this, PhotoDrawActivity::class.java).putExtra(PhotoDrawActivity.FILE, f.block.file), REQ_PHOTO_DRAW)
    }

    /* ---------- fotos ---------- */
    override fun noteTitle(): String = title.text.toString()

    private var camFile: File? = null
    private var cropTarget: Block.Img? = null

    private fun addPhoto() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(10), dp(6), dp(10)) }
        val d = dialog().setTitle(L.t("addPhoto")).setView(list).create()
        list.addView(menuRow(this, Icons.SCAN, L.t("camera")) { d.dismiss(); openCamera() })
        list.addView(menuRow(this, Icons.IMAGE, L.t("gallery")) { d.dismiss(); openGallery() })
        d.show()
    }

    private fun openCamera() {
        val f = File(cacheDir, "camara_" + System.currentTimeMillis() + ".jpg")
        camFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.archivos", f)
        val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_CAMERA) } catch (_: Exception) { toast(L.t("cantOpen")) }
    }

    private fun openGallery() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33)
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 20)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_GALLERY) } catch (_: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_GALLERY) } catch (_: Exception) { toast(L.t("cantOpen")) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { if (requestCode == REQ_CAMERA) camFile?.delete(); if (requestCode == REQ_VIDEO_CAM) vidFile?.delete(); return }
        when (requestCode) {
            REQ_VIDEO_CAM -> {
                val f = vidFile
                if (f != null && f.length() > 0) addMedia(android.net.Uri.fromFile(f), true) { f.delete() }
                else data?.data?.let { addMedia(it, true) }        // algunas cámaras no usan el archivo que se les da
            }
            REQ_VIDEO_PICK -> data?.data?.let { addMedia(it, true) }
            REQ_AUDIO_PICK -> data?.data?.let { addMedia(it, false) }
            REQ_PHOTO_DRAW -> {
                val t = cropTarget ?: return
                val nf = data?.getStringExtra(CropActivity.OUT) ?: return
                editor.flushAll()
                t.file = nf; t.w = data.getIntExtra(CropActivity.OUT_W, 0); t.h = data.getIntExtra(CropActivity.OUT_H, 0)
                editor.rebuild(null, t); onDirty()
            }
            REQ_CAMERA -> camFile?.let { f -> if (f.length() > 0) addUris(listOf(android.net.Uri.fromFile(f)), fromCamera = true) { f.delete() } }
            REQ_GALLERY -> {
                val uris = ArrayList<android.net.Uri>()
                data?.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
                if (uris.isEmpty()) data?.data?.let { uris.add(it) }
                addUris(uris, fromCamera = false) {}
            }
            REQ_CROP -> {
                val t = cropTarget ?: return
                val nf = data?.getStringExtra(CropActivity.OUT) ?: return
                editor.flushAll()
                t.file = nf; t.w = data.getIntExtra(CropActivity.OUT_W, 0); t.h = data.getIntExtra(CropActivity.OUT_H, 0)
                editor.rebuild(null, t); onDirty()
            }
        }
    }

    /** Prepara las fotos en segundo plano y las pone donde está el cursor. */
    private fun addUris(uris: List<android.net.Uri>, fromCamera: Boolean, after: () -> Unit) {
        if (uris.isEmpty()) return
        toast(L.t("addingPhotos"))
        val stamp = Photos.stampOn(this)
        val site = noteTitle()
        Loader.pool.execute {
            val out = ArrayList<Block.Img>()
            for (u in uris) {
                val r = try { Photos.import(this, u) } catch (_: Throwable) { null } ?: continue
                val taken = if (fromCamera) System.currentTimeMillis() else r.taken
                val pct = if (uris.size > 1) 48f else 60f
                out.add(Block.Img(r.file, pct, "", (100f - pct) / 2, 0f, r.w, r.h, if (stamp) Photos.stampText(taken, site) else "", 0f, taken))
            }
            runOnUiThread {
                after()
                if (out.isEmpty()) toast(L.t("photoFail")) else editor.insertImages(out)
            }
        }
    }

    override fun cropImage(f: FotoView) {
        cropTarget = f.block
        startActivityForResult(Intent(this, CropActivity::class.java).putExtra(CropActivity.FILE, f.block.file), REQ_CROP)
    }

    override fun rotateImage(f: FotoView) {
        val b = f.block
        Loader.pool.execute {
            val r = Photos.rotate90(b.file)
            runOnUiThread {
                if (r == null) return@runOnUiThread
                b.file = r.file; b.w = r.w; b.h = r.h
                f.reload(); onDirty()
            }
        }
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
            add(MoreIcons.PALETTE, L.t("titleColor")) { title.requestFocus(); pickColor("color") }
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
        if (rec != null) stopRecording(true)
        save()
        super.finish()
    }
}
