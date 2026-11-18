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
import android.widget.FrameLayout
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
        const val REQ_SCAN = 66
        const val REQ_GALLERY = 52
        const val REQ_CROP = 53
        const val REQ_MIC = 54
        const val REQ_VIDEO_CAM = 55
        const val REQ_VIDEO_PICK = 56
        const val REQ_AUDIO_PICK = 57
        const val REQ_PHOTO_DRAW = 58
        const val REQ_DICT = 59
        const val REQ_CALC = 60
        const val REQ_WALL = 61
    }

    private lateinit var meta: NoteMeta
    private var extra: String? = null
    private var isNew = false
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var title: TitleEdit
    private lateinit var editor: EditorView
    private lateinit var bar: LinearLayout
    private lateinit var sizeTv: TextView
    private lateinit var colorDot: View
    private lateinit var star: IconView
    private lateinit var histBtn: IconView
    private lateinit var spellBtn: IconView

    /** Rebotito al tocar un botón. */
    private fun pop(v: View) {
        v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }.start()
    }
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
        if (!Store.loaded) { setContentView(View(this).apply { background = Glass.background(this@NoteActivity) }); Store.load { init() }; return }
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
        // estilo del título (contorno, resaltado, tachado)
        title.setText(Rich.build(titleRuns()))
        muting = true
        editor.readOnly = meta.deleted
        editor.setBlocks(blocks)
        muting = false
        loaded = true
        snapshot()
        showBanners()
        // ✨ el contenido de la nota llega en ola, renglón tras renglón
        if (!isNew) editor.post {
            for (i in 0 until minOf(editor.childCount, 10)) {
                val v = editor.getChildAt(i)
                v.alpha = 0f; v.translationY = dp(18).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(90L + 45L * i).setDuration(360).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
            }
        }
        // lo que había al abrir (para guardar la versión de antes si cambias algo)
        openTitle = meta.title; openBody = blocksJson(editor.blocks); openInk = extraObj.opt("ink"); openAt = meta.updated; wasNew = isNew
        sessStart = System.currentTimeMillis(); sessPre = Timeline.snap(meta.title, extraObj.optJSONArray("tr"), openBody, openInk)
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
        edgeToEdge(root, windowBg = false)
        tint()

        // Arriba, como en Best Notes: ← · [deshacer · historial · rehacer · ortografía] · fondo · calculadora · ⋮
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        fun small(v: View) = v.apply { layoutParams = lp(dp(42), dp(42)).apply { marginStart = dp(2); marginEnd = dp(2) } }
        top.addView(small(glassBtn(Icons.BACK) { finish() }))
        top.addView(View(this), lp(0, 1, 1f))
        val pill = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Glass.make(this@NoteActivity, 22f); setPadding(dp(2), 0, dp(2), 0) }
        fun pillBtn(icon: Int, size: Float, a: () -> Unit) = iconBtn(icon, size) { v -> pop(v); a() }.apply { layoutParams = lp(dp(38), dp(42)) }
        undoBtn = pillBtn(Icons.UNDO, 10.5f) { undo() }
        histBtn = pillBtn(MoreIcons.HISTORY, 10f) { openHistory() }
        redoBtn = pillBtn(MoreIcons.REDO, 10.5f) { redo() }
        spellBtn = pillBtn(Icons.SPELL, 10.5f) { if (spell != null) closeSpell() else startSpell() }
        pill.addView(undoBtn); pill.addView(histBtn); pill.addView(redoBtn); pill.addView(spellBtn)
        top.addView(pill, lp(WRAP, dp(42)).apply { marginEnd = dp(2) })
        top.addView(small(glassBtn(MoreIcons.WALLPAPER) { wallMenu() }))
        top.addView(small(glassBtn(Icons.CALC) { openCalc() }))
        top.addView(small(glassBtn(MoreIcons.DOTS_V) { v -> v.animate().rotationBy(90f).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start(); options() }))
        star = IconView(this, MoreIcons.STAR5)
        root.addView(top, lp(MATCH, WRAP))
        paintStar(); paintUndo()

        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TitleEdit(this).apply {
            background = null
            setText(meta.title)
            hint = L.t("titlePh")
            setHintTextColor(T.faint)
            setTextColor(T.text)
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
            setOnFocusChangeListener { _, has -> if (has) onFocusEdit(this) }
            onCursor = { onFocusEdit(it) }
            onEdit = { onDirty() }                    // pintar letras del título también se guarda
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
        this.page = page
        page.addView(col, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        ink = InkView(this)
        ink.onPan = { dy -> scroll.scrollBy(0, dy.toInt()) }
        ink.onChange = { inkChanged() }
        page.addView(ink, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        scroll.addView(page, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        val holder = android.widget.FrameLayout(this)
        noteHolder = holder
        wallImg = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        holder.addView(wallImg, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        holder.addView(scroll, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        guideView = GuideOverlay(this)
        holder.addView(guideView, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        hintTv = tv("", 13.5f, T.text, true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8)); background = roundBg(0xF0303036.toInt(), dp(16).toFloat(), T.border, dp(1)); alpha = 0f
        }
        holder.addView(hintTv, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        root.addView(holder, lp(MATCH, 0, 1f))

        // Barra de herramientas: dos filas, igual que en Best Notes
        //   ✏️ 🖼️ | B  I  U  🖍️
        //   •  1. ☑ | Aa 17 ▾   ⚪ ▾
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        bar = grid
        fun rowL() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun sep(r: LinearLayout) = r.addView(View(this).apply { setBackgroundColor(tw(0x33)) }, lp(dp(1), dp(30)).apply { marginStart = dp(2); marginEnd = dp(2) })
        fun tool(r: LinearLayout, key: String, icon: Int, size: Float = 10.5f, act: () -> Unit) {
            val b = iconBtn(icon, size) { v -> pop(v); act() }
            toolBtns[key] = b; r.addView(b, lp(0, dp(48), 1f))
        }
        val r1 = rowL(); val r2 = rowL()
        tool(r1, "draw", MoreIcons.PEN) { setDrawMode(true) }
        tool(r1, "photo", Icons.IMAGE) { insertMenu() }
        sep(r1)
        tool(r1, "b", MoreIcons.BOLD) { style { s -> s.copy(b = !cur().b) } }
        tool(r1, "i", MoreIcons.ITALIC) { style { s -> s.copy(i = !cur().i) } }
        tool(r1, "u", MoreIcons.UNDERLINE) { style { s -> s.copy(u = !cur().u) } }
        tool(r1, "hi", MoreIcons.HILITE) { pickColor("hi") }
        tool(r2, "ul", MoreIcons.BULLETS) { editor.toggleList("ul") }
        tool(r2, "ol", MoreIcons.NUMBERS) { editor.toggleList("ol") }
        tool(r2, "check", MoreIcons.CHECKBOX) { editor.toggleList("check") }
        sep(r2)
        // Aa 17 ▾
        val sizeCell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tw(0x33)), null, roundBg(Color.WHITE, dp(22).toFloat()))
            setOnClickListener { pop(it); pickSize() }
        }
        sizeCell.addView(tv("Aa", 17f, T.muted))
        sizeTv = tv("${T.baseSize}", 21f, T.text, true).apply { setPadding(dp(8), 0, dp(6), 0) }
        sizeCell.addView(sizeTv)
        sizeCell.addView(tv("▾", 13f, T.muted))
        toolBtns["size"] = sizeCell
        r2.addView(sizeCell, lp(0, dp(48), 2.2f))
        // ⚪ ▾ (el color de la letra)
        val colorCell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tw(0x33)), null, roundBg(Color.WHITE, dp(22).toFloat()))
            setOnClickListener { pop(colorDot); colorMenu() }
        }
        colorDot = View(this).apply { background = roundBg(T.text, dp(14).toFloat(), 0x55000000, dp(1)) }
        colorCell.addView(colorDot, lp(dp(28), dp(28)))
        colorCell.addView(tv("▾", 13f, T.muted).apply { setPadding(dp(8), 0, 0, 0) })
        toolBtns["color"] = colorCell
        r2.addView(colorCell, lp(0, dp(48), 1.6f))
        grid.addView(r1, lp(MATCH, WRAP)); grid.addView(r2, lp(MATCH, WRAP))
        val hs = grid
        formatBar = hs
        // Abajo: la barra de vidrio flotante (como en Best Notes)
        bottom = android.widget.FrameLayout(this).apply { background = Glass.make(this@NoteActivity, 26f, strong = true); setPadding(dp(4), dp(2), dp(4), dp(2)) }
        bottom.addView(hs, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(bottom, lp(MATCH, WRAP).apply { setMargins(dp(8), dp(4), dp(8), dp(8)) })
        // ⌨️ con el teclado abierto la barra se esconde (para ver más de la nota); el botón "Aa" la saca un ratito
        kbBtn = glassBtn(MoreIcons.TEXT_T) { pop(it); kbPeek = !kbPeek; updateKbBar() }.apply { visibility = View.GONE }
        holder.addView(kbBtn, android.widget.FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(10)) })
        root.viewTreeObserver.addOnGlobalLayoutListener { updateKbBar() }
        setContentView(root)
        root.alpha = 0f
        reveal(true)
    }

    /* ---------- 🔵 abrir y cerrar como un círculo que crece desde donde tocaste ---------- */
    private var closing = false

    private fun reveal(open: Boolean, after: () -> Unit = {}) {
        val v = root
        v.post {
            val w = v.width.toFloat(); val h = v.height.toFloat()
            if (w <= 0 || !v.isAttachedToWindow) { v.alpha = 1f; after(); return@post }
            v.alpha = 1f
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val cx = (intent.getFloatExtra("x", w / 2) - loc[0]).coerceIn(0f, w)
            val cy = (intent.getFloatExtra("y", h / 3) - loc[1]).coerceIn(0f, h)
            val rMax = Math.hypot(maxOf(cx, w - cx).toDouble(), maxOf(cy, h - cy).toDouble()).toFloat() + dp(12)
            val anim = android.view.ViewAnimationUtils.createCircularReveal(v, cx.toInt(), cy.toInt(), if (open) 0f else rMax, if (open) rMax else 0f)
            anim.duration = if (open) 600 else 460
            anim.interpolator = android.view.animation.PathInterpolator(0.65f, 0f, 0.35f, 1f)
            anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (!open) v.visibility = View.INVISIBLE; after() }
            })
            anim.start()
            // ✨ estrellitas alrededor del círculo
            val deco = window.decorView as android.view.ViewGroup
            val sp = SparkleView(this, Fx.ring(this, cx + loc[0], cy + loc[1], rMax * (if (open) 0.9f else 0.5f)))
            deco.addView(sp, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            sp.play(if (open) 760 else 560)
        }
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
        // el fondo de vidrio de Best Notes, con el color de la nota encima (suavecito)
        val layers = arrayListOf<android.graphics.drawable.Drawable>(Glass.background(this))
        if (Wall.current(this) == "phone") layers.add(0, android.graphics.drawable.ColorDrawable(0x1A000000))
        if (c != 0) layers.add(android.graphics.drawable.ColorDrawable((c and 0xFFFFFF) or 0x24000000))
        root.background = android.graphics.drawable.LayerDrawable(layers.toTypedArray())
        bgColor = bg
    }
    private var bgColor = T.bg

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(), (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(), (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun paintStar() { star.kind = if (meta.fav) MoreIcons.STAR5_ON else MoreIcons.STAR5; star.color = if (meta.fav) 0xFFFFD60A.toInt() else T.text; star.invalidate() }
    private fun paintUndo() {
        undoBtn.alpha = if (hIdx > 0) 1f else 0.35f
        redoBtn.alpha = if (hIdx < history.size - 1) 1f else 0.35f
    }

    /* ---------- estilos ---------- */
    /** Los pedacitos del título con su estilo (o el título de antes, todo de un color). */
    private fun titleRuns(): List<Run> {
        extraObj.optJSONArray("tr")?.let { val r = runsFromJson(it); if (runsPlain(r).trim() == meta.title.trim()) return r }
        if (meta.title.isEmpty()) return emptyList()
        val st = St.PLAIN.copy(color = meta.titleColor, o = extraObj.optInt("tOl", 0), bg = extraObj.optInt("tBg", 0), s = extraObj.optBoolean("tS"))
        return listOf(Run(meta.title, st))
    }

    /** Dónde se aplica el estilo: el título (si estás ahí) o el texto. */
    private fun target(): RichEdit? {
        if (multi != null) mPieces().firstOrNull()?.first?.let { if (it is RichEdit) return it }
        return if (::title.isInitialized && title.hasFocus()) title else editor.focused
    }
    private fun cur(): St = target()?.currentStyle() ?: St.PLAIN
    private fun style(fn: (St) -> St) {
        if (multi != null) { multiStyle(fn); return }      // ↕ con la selección de la app: a todo lo seleccionado
        val e = target() ?: return
        if (!e.hasFocus()) return
        // en el título sin seleccionar nada: se pinta TODO el título
        if (e === title && e.selectionStart == e.selectionEnd && e.text.isNotEmpty()) {
            val pos = e.selectionStart
            e.setSelection(0, e.text.length); style(fn); e.setSelection(pos.coerceAtMost(e.text.length)); return
        }
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
        // cambiar el estilo SIEMPRE cuenta como cambio (para que se guarde al salir)
        onDirty(); dirty = true
    }

    private fun pickSize() {
        val e = target() ?: return
        val a0 = minOf(e.selectionStart, e.selectionEnd); val b0 = maxOf(e.selectionStart, e.selectionEnd)
        pickSize(e.currentStyle().size) { z ->
            if (!e.hasFocus()) e.requestFocus()
            if (a0 in 0 until b0 && b0 <= e.text.length) e.setSelection(a0, b0)
            style { s -> s.copy(size = z) }
        }
    }

    /** Color de letra (o del título si estás en el título), resaltar o contorno. */
    private fun pickColor(kind: String) {
        val e = target() ?: return
        val c = e.currentStyle()
        val a0 = minOf(e.selectionStart, e.selectionEnd); val b0 = maxOf(e.selectionStart, e.selectionEnd)
        // en vivo: se pinta lo seleccionado mientras mueves la rueda
        fun sel() {
            if (!e.hasFocus()) e.requestFocus()
            if (a0 in 0 until b0 && (e.selectionStart != a0 || e.selectionEnd != b0) && b0 <= e.text.length) e.setSelection(a0, b0)
        }
        fun liveOn(f: (St, Int) -> St): (Int) -> Unit = { col -> sel(); style { s -> f(s, col) } }
        when (kind) {
            "hi" -> pickColor(L.t("hilite"), if (c.bg != 0) c.bg or 0xFF000000.toInt() else 0, T.hiliteColors.map { it or 0xFF000000.toInt() }.toIntArray(),
                liveOn { s, col -> s.copy(bg = if (col == 0) 0 else (col and 0xFFFFFF) or 0x73000000) }) { col ->
                sel(); style { s -> s.copy(bg = if (col == 0) 0 else (col and 0xFFFFFF) or 0x73000000) }; Store.main.post { fillStyle() }
            }
            "ol" -> pickColor(L.t("outline"), c.o, T.textColors, liveOn { s, col -> s.copy(o = col) }) { col -> sel(); style { s -> s.copy(o = col) }; Store.main.post { fillStyle() } }
            else -> pickColor(L.t("textColor"), c.color, T.textColors, liveOn { s, col -> s.copy(color = col) }) { col -> sel(); style { s -> s.copy(color = col) }; Store.main.post { fillStyle() } }
        }
    }

    /** Mientras mueves el cursor esto se llama MUCHAS veces: se junta en una sola (así la lupa y el cursor van suaves). */
    private var focusPending: RichEdit? = null
    private var focusPosted = false
    private val toolOn = HashMap<String, Boolean>()
    override fun onFocusEdit(e: RichEdit?) {
        focusPending = e
        if (focusPosted) return
        focusPosted = true
        Store.main.postDelayed({ focusPosted = false; paintTools(focusPending) }, 70)
    }

    private fun paintTools(e: RichEdit?) {
        val st = e?.currentStyle() ?: St.PLAIN
        fun on(k: String, v: Boolean) {
            if (toolOn[k] == v) return
            toolOn[k] = v
            toolBtns[k]?.background = if (v) roundBg(tw(0x33), dp(22).toFloat()) else android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tw(0x33)), null, roundBg(Color.WHITE, dp(22).toFloat()))
        }
        on("b", st.b); on("i", st.i); on("u", st.u)
        on("hi", st.bg != 0)
        val kind = e?.let { ed -> editor.views.firstOrNull { it.edits().contains(ed) }?.block as? Block.ListB }?.kind
        on("check", kind == "check"); on("ul", kind == "ul"); on("ol", kind == "ol")
        val sz = "${if (st.size > 0) st.size else T.baseSize}"
        if (sizeTv.text.toString() != sz) sizeTv.text = sz
        if (stylePanel != null) Store.main.post { fillStyle() }
        val dotC = if (st.color != 0) st.color else T.text
        if (colorDot.tag != dotC) { colorDot.tag = dotC; colorDot.background = roundBg(dotC, dp(14).toFloat(), 0x55000000, dp(1)) }
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
        val tRuns = Rich.runs(title.text)
        extraObj.remove("tOl"); extraObj.remove("tBg"); extraObj.remove("tS")
        if (tRuns.any { !it.st.isPlain() }) extraObj.put("tr", runsJson(tRuns)) else extraObj.remove("tr")
        meta.titleColor = tRuns.firstOrNull { it.text.isNotBlank() }?.st?.color ?: 0
        Store.fillMeta(meta, editor.blocks, tRuns, extraObj.optJSONArray("ink"))
        extra = if (extraObj.length() > 0) extraObj.toString() else null
        val bodyNow = blocksJson(editor.blocks)
        Store.saveNote(meta, bodyNow, extra, keepExtra = false)
        isNew = false
        // 🕘 la línea de tiempo: este rato de trabajo (un solo bloque que se va actualizando)
        sessPre?.let { pre ->
            val post = Timeline.snap(meta.title, extraObj.optJSONArray("tr"), bodyNow, extraObj.opt("ink"))
            val st = sessStart; val nid = meta.id; val c = applicationContext
            Store.io.execute { try { Timeline.record(c, nid, st, pre, post) } catch (_: Exception) {} }
        }
        // 📖 el registro se actualiza solo (en segundo plano)
        val toks = NoteLog.tokens(meta.title, editor.blocks); val id = meta.id; val app = applicationContext
        Store.io.execute { try { NoteLog.update(app, id, toks, logMine) } catch (_: Exception) {} }
        Store.changed()
    }

    override fun onResume() {
        super.onResume()
        // ↕ "Seleccionar todo" y "Seleccionar varias líneas" toman TODA la nota
        RichEdit.onSelectAll = { multiStart(null, true) }
        RichEdit.onSelectMore = { e -> multiStart(e, false, lines = true) }
        RichEdit.onUserSelect = { e, a, b -> startFromNative(e, a, b) }
    }

    override fun onPause() {
        super.onPause()
        RichEdit.onSelectAll = null; RichEdit.onSelectMore = null; RichEdit.onUserSelect = null
        if (dict != null) stopDictation()
        AudioPlayer.stop()
        save()
    }

    /* ---------- deshacer / rehacer ---------- */
    private fun state(): String {
        editor.flushAll()
        return JSONObject().put("t", title.text.toString()).put("tr", runsJson(Rich.runs(title.text))).put("b", blocksJson(editor.blocks)).toString()
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
        title.setText(o.optJSONArray("tr")?.let { Rich.build(runsFromJson(it)) } ?: o.optString("t"))
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

    /* ---------- ✨ IA (la gratis de Best Notes: Pollinations) ---------- */
    private fun noteText(): String { editor.flushAll(); return Share.text(title.text.toString(), editor.blocks) }

    private fun aiMenu() {
        val e = editor.focused?.takeIf { it.hasFocus() }
        val selA = e?.let { minOf(it.selectionStart, it.selectionEnd) } ?: -1
        val selB = e?.let { maxOf(it.selectionStart, it.selectionEnd) } ?: -1
        val sel = if (e != null && selA in 0 until selB) e.text.substring(selA, selB) else ""
        Sheet(this, "✨ " + L.t("ai"), L.t("aiFree"))
            .item(Icons.BRIEFCASE, L.t("aiAssistant"), Sheet.VIOLET, L.t("aiAssistantSub")) { aiAssistant() }
            .item(Icons.FORMAL, L.t("aiRewrite"), Sheet.BLUE, if (sel.isNotEmpty()) "«" + sel.take(40) + "»" else L.t("aiRewriteSub")) { aiRewrite(e, selA, selB) }
            .item(Icons.BOOK, L.t("aiWord"), Sheet.TEAL, L.t("aiWordSub")) { aiWord(e, selA, selB, "syn") }
            .item(Icons.BUBBLE, L.t("aiPhrase"), Sheet.GREEN, L.t("aiPhraseSub")) { aiWord(e, selA, selB, "phrase") }
            .item(Icons.SPELL, L.t("aiSpell"), Sheet.PINK, L.t("aiSpellSub")) { aiSpell() }
            .show()
    }

    /** 👔 Asistente de trabajo: trabaja con TODA la nota. */
    private fun aiAssistant() {
        val txt = noteText()
        if (txt.isBlank()) { toast(L.t("aiEmpty")); return }
        val opts = listOf("report" to L.t("aiReport"), "pronote" to L.t("aiProNote"), "email" to L.t("aiEmail"), "chat" to L.t("aiChat"),
            "english" to L.t("aiEnglish"), "todo" to L.t("aiTodo"), "summary" to L.t("aiSummary"), "steps" to L.t("aiSteps"))
        dialog().setTitle("✨ " + L.t("aiAssistant")).setItems(opts.map { it.second }.toTypedArray()) { _, i ->
            val (style, label) = opts[i]
            val langs = if (style == "english") listOf("en") else listOf("es", "en")
            val jobs = langs.map { lg -> AiJob(if (lg == "es") "Español" else "English") { Ai.ask(Ai.system(style, lg), txt) } }
            val actions = ArrayList<AiAction>()
            if (style == "todo") actions.add(AiAction(L.t("aiAddChecklist"), true) { out -> appendBlocks(aiToBlocks(out, checklist = true)) })
            else {
                actions.add(AiAction(L.t("aiNewNote"), true) { out -> openNew(label + " · " + title.text.toString().ifBlank { L.t("untitled") }, aiToBlocks(out)) })
                actions.add(AiAction(L.t("aiAppend")) { out -> appendBlocks(aiToBlocks(out)) })
                if (style == "pronote") actions.add(AiAction(L.t("aiReplaceNote")) { out -> replaceAll(aiToBlocks(out)) })
            }
            actions.add(AiAction(L.t("share")) { out -> Share.sendText(this, title.text.toString(), out) })
            aiDialog(label, jobs, actions)
        }.show()
    }

    /** ✍️ Reescribir lo seleccionado (o el renglón donde está el cursor). Sale en español y en inglés. */
    private fun aiRewrite(e0: RichEdit?, a0: Int, b0: Int) {
        val e = e0 ?: run { toast(L.t("aiSelect")); return }
        var a = a0; var b = b0
        if (a < 0 || a >= b) {                       // sin selección: el renglón del cursor
            val t = e.text.toString(); val c = maxOf(0, e.selectionStart)
            a = t.lastIndexOf('\n', c - 1) + 1; b = t.indexOf('\n', c).let { if (it < 0) t.length else it }
        }
        val src = e.text.substring(a, b)
        if (src.isBlank()) { toast(L.t("aiSelect")); return }
        val styles = listOf("pro" to L.t("rwPro"), "formal" to L.t("rwFormal"), "biz" to L.t("rwBiz"), "fix" to L.t("rwFix"), "clear" to L.t("rwClear"),
            "short" to L.t("rwShort"), "long" to L.t("rwLong"), "friendly" to L.t("rwFriendly"), "persuasive" to L.t("rwPersuasive"),
            "funny" to L.t("rwFunny"), "emoji" to L.t("rwEmoji"), "translate" to L.t("rwTranslate"))
        dialog().setTitle("✨ " + L.t("aiRewrite")).setItems(styles.map { it.second }.toTypedArray()) { _, i ->
            val (style, label) = styles[i]
            val jobs = if (style == "translate") listOf(AiJob("") { Ai.ask(Ai.system("translate", ""), src) })
                else listOf("es", "en").map { lg -> AiJob(if (lg == "es") "Español" else "English") { Ai.ask(Ai.system(style, lg), src) } }
            aiDialog(label, jobs, listOf(
                AiAction(L.t("aiUse"), true) { out -> replaceRange(e, a, b, out) },
                AiAction(L.t("aiBelow")) { out -> replaceRange(e, b, b, "\n" + out) },
            ))
        }.show()
    }

    /** 📖 Palabra o frase más profesional: escoges una y la cambia. */
    private fun aiWord(e: RichEdit?, a: Int, b: Int, style: String) {
        if (e == null || a < 0 || a >= b) { toast(L.t("aiSelectWord")); return }
        val src = e.text.substring(a, b)
        aiDialog(L.t(if (style == "syn") "aiWord" else "aiPhrase"), listOf(AiJob("«" + src.take(50) + "»") { Ai.ask(Ai.system(style, ""), src) }), emptyList(), lines = true) { pick ->
            replaceRange(e, a, b, pick)
        }
    }

    /** ✅ Revisar ortografía de toda la nota (renglón por renglón, sin perder el orden). */
    private fun aiSpell() {
        editor.flushAll()
        val pieces = ArrayList<String>()
        for (bl in editor.blocks) when (bl) {
            is Block.Text -> runsPlain(bl.runs).split('\n').forEach { pieces.add(it) }
            is Block.ListB -> bl.items.forEach { pieces.add(runsPlain(it.runs)) }
            is Block.Img -> runsPlain(bl.beside).split('\n').forEach { pieces.add(it) }
            else -> {}
        }
        val idx = pieces.indices.filter { pieces[it].isNotBlank() }
        if (idx.isEmpty()) { toast(L.t("aiEmpty")); return }
        val input = idx.joinToString("\n") { "[${it + 1}] " + pieces[it] }
        val sys = Ai.system("fix", "") + " IMPORTANT: the text is a note split into numbered pieces like \"[1] ...\". Return EVERY piece, each on its own line starting with the same number (\"[n] new text\"), in the same order. Never merge, split, add or drop pieces."
        aiDialog(L.t("aiSpell"), listOf(AiJob("") { Ai.ask(sys, input) }), listOf(AiAction(L.t("aiApplyFix"), true) { out ->
            val fixed = HashMap<Int, String>()
            Regex("^\\[(\\d+)]\\s?(.*)$", RegexOption.MULTILINE).findAll(out).forEach { m -> fixed[m.groupValues[1].toInt() - 1] = m.groupValues[2] }
            applyPieces(fixed)
        }))
    }

    /** Cambia los renglones arreglados (cada renglón conserva el estilo de su principio). */
    private fun applyPieces(fixed: Map<Int, String>) {
        if (fixed.isEmpty()) return
        editor.change { bl ->
            var k = 0
            fun fix(runs: MutableList<Run>): MutableList<Run> {
                val lines = runsPlain(runs).split('\n'); val out = ArrayList<Run>(); var pos = 0
                lines.forEachIndexed { li, line ->
                    if (li > 0) out.add(Run("\n"))
                    val f = fixed[k]
                    if (f != null && f != line && line.isNotBlank()) out.add(Run(f, sliceRuns(runs, pos, pos + line.length).firstOrNull()?.st ?: St.PLAIN))
                    else out.addAll(sliceRuns(runs, pos, pos + line.length))
                    pos += line.length + 1; k++
                }
                return mergeRuns(out)
            }
            for (b in bl) when (b) {
                is Block.Text -> b.runs = fix(b.runs)
                is Block.ListB -> b.items.forEach { it.runs = fix(it.runs) }
                is Block.Img -> b.beside = fix(b.beside)
                else -> {}
            }
            null
        }
        toast(L.t("aiFixed"))
    }

    private fun replaceRange(e: RichEdit, a: Int, b: Int, text: String) {
        val t = e.text
        if (a > t.length || b > t.length) return
        t.replace(a, b, text)
        e.requestFocus(); e.setSelection(minOf(t.length, a + text.length))
    }

    private fun appendBlocks(add: List<Block>) {
        editor.change { bl -> bl.addAll(add); null }
        editor.post { scroll.smoothScrollTo(0, editor.bottom) }
    }

    private fun replaceAll(newBlocks: MutableList<Block>) { editor.change { bl -> bl.clear(); bl.addAll(newBlocks); null } }

    private fun openNew(t: String, blocks: MutableList<Block>) {
        val m = Store.createNote(t, blocks, meta.folder)
        startActivity(Intent(this, NoteActivity::class.java).putExtra(EXTRA_ID, m.id))
    }

    /* ---------- 📤 compartir ---------- */
    private lateinit var page: android.widget.FrameLayout

    private fun shareMenu() {
        Sheet(this, L.t("share"), L.t("shareHint"))
            .item(MoreIcons.VIDEO, L.t("sharePage"), Sheet.ORANGE, L.t("sharePageSub")) { save(); Store.io.execute { Store.main.post { shareNotes(listOf(meta.id)) } } }
            .item(Icons.IMAGE, L.t("shareImg"), Sheet.PINK, "WhatsApp, Gmail…") { shareAs("img") }
            .item(Icons.BOOK, L.t("sharePdf"), Sheet.RED, "Gmail, Outlook…") { shareAs("pdf") }
            .item(MoreIcons.TEXT_T, L.t("shareText"), Sheet.BLUE) { editor.flushAll(); Share.sendText(this, title.text.toString(), Share.text(title.text.toString(), editor.blocks)) }
            .item(Icons.BUBBLE, L.t("shareSms"), Sheet.GREEN) { editor.flushAll(); Share.sms(this, Share.text(title.text.toString(), editor.blocks)) }
            .show()
    }

    /** Foto o PDF de la nota tal como se ve (sin cursor ni iluminado de búsqueda). */
    private fun shareAs(kind: String) {
        save()
        if (ink.drawing) setDrawMode(false)
        editor.selectImage(null)
        currentFocus?.let { v -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); v.clearFocus() }
        editor.highlight(null); Rich.highlight(title.text, null)
        toast(L.t("preparing"))
        page.postDelayed({
            val bottomPx = maxOf(editor.top + editor.height - dp(100), (Stroke.bottom(ink.strokes, page.width.toFloat()) + dp(16)).toInt())
            val h = Share.contentHeight(page, bottomPx)
            try {
                val f = if (kind == "pdf") Share.pdf(this, page, h, bgColor, title.text.toString()) else Share.image(this, page, h, bgColor, title.text.toString())
                Share.send(this, f, if (kind == "pdf") "application/pdf" else "image/jpeg", title.text.toString().ifBlank { L.t("appName") })
            } catch (e: Throwable) { toast(L.t("shareFail")) }
        }, 250)
    }

    /* ---------- 🗣️ dictar ---------- */
    private var dict: Dictation? = null
    private var dictBar: View? = null

    private fun startDictation() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_DICT); return
        }
        if (dict != null) return
        if (ink.drawing) setDrawMode(false)
        if (editor.focused == null || editor.focused?.hasFocus() != true) editor.focusLastText()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8)) }
        val dot = View(this).apply { background = roundBg(0xFF30D158.toInt(), dp(6).toFloat()) }
        bar.addView(dot, lp(dp(12), dp(12)))
        val live = tv(L.t("listening"), 15f, T.text).apply { setPadding(dp(10), 0, dp(8), 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.START }
        bar.addView(live, lp(0, WRAP, 1f))
        var lang = if (L.lang == "en") "en" else "es"
        val langBtn = tv(lang.uppercase(), 14f, T.text, true).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); pressBg(T.cardStrong, 16f, T.border) }
        bar.addView(langBtn, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        bar.addView(tv("■ " + L.t("stop"), 14f, 0xFF16161A.toInt(), true).apply { setPadding(dp(14), dp(8), dp(14), dp(8)); pressBg(T.text, 16f); setOnClickListener { stopDictation() } })
        val d = Dictation(this, lang,
            onPartial = { t -> live.text = if (t.isEmpty()) L.t("listening") else t },
            onFinal = { t -> insertDictated(t) },
            onState = { on -> dot.alpha = if (on) 1f else 0.3f })
        if (!d.available()) { toast(L.t("noDictation")); return }
        langBtn.setOnClickListener { lang = if (lang == "es") "en" else "es"; langBtn.text = lang.uppercase(); d.lang = lang; d.restart() }
        dict = d; dictBar = bar
        formatBar.visibility = View.GONE
        bottom.addView(bar, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        d.start()
    }

    /** Lo dictado se escribe donde está el cursor (con mayúscula al empezar una frase). */
    private fun insertDictated(t0: String) {
        val e = editor.focused ?: run { editor.focusLastText(); editor.focused } ?: return
        val txt = e.text
        val at = maxOf(0, e.selectionEnd)
        val before = txt.subSequence(0, at).toString()
        val startSentence = before.isBlank() || before.trimEnd().lastOrNull()?.let { it in ".!?\n" } == true || before.endsWith("\n")
        var t = t0.trim()
        if (startSentence) t = t.replaceFirstChar { it.uppercase() }
        val space = if (before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")) " " else ""
        txt.insert(at, space + t)
        e.setSelection(minOf(txt.length, at + space.length + t.length))
    }

    private fun stopDictation() {
        dict?.stop(); dict = null
        dictBar?.let { bottom.removeView(it) }; dictBar = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!ink.drawing && rec == null && editor.selected == null) formatBar.visibility = View.VISIBLE
    }

    /* ---------- 📷 panel de la foto seleccionada (como en Best Notes) ---------- */
    private lateinit var guideView: GuideOverlay
    private lateinit var hintTv: TextView
    private var imgPanel: View? = null
    private val hideHint = Runnable { hintTv.animate().alpha(0f).setDuration(250).start() }

    override fun showGuides(x: Float, y: Float, snap: String, label: String?) {
        val loc = IntArray(2); guideView.getLocationInWindow(loc)
        guideView.set(if (x < 0) -1f else x - loc[0], if (y < 0) -1f else y - loc[1], snap, label)
    }

    override fun hint(msg: String) {
        hintTv.text = msg
        // ✨ el aviso cae desde arriba con un rebotito
        if (hintTv.alpha < 0.5f) { hintTv.translationY = -dp(24).toFloat(); hintTv.scaleX = 0.85f; hintTv.scaleY = 0.85f }
        hintTv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        Store.main.removeCallbacks(hideHint); Store.main.postDelayed(hideHint, 2600)
    }

    override fun onImageSelected(f: FotoView?) {
        if (f != null && stylePanel != null) { bottom.removeView(stylePanel); stylePanel = null }
        imgPanel?.let { bottom.removeView(it) }; imgPanel = null
        if (f == null) { if (!ink.drawing && rec == null) formatBar.visibility = View.VISIBLE; return }
        formatBar.visibility = View.GONE
        val p = buildImagePanel(f)
        imgPanel = p
        bottom.addView(p, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        p.translationY = dp(60).toFloat(); p.alpha = 0f
        p.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        (p as? LinearLayout)?.let { c ->
            for (i in 0 until c.childCount) { val ch = c.getChildAt(i); ch.alpha = 0f; ch.translationY = dp(14).toFloat()
                ch.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 70).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator()).start() }
        }
    }

    override fun onImageChanged(f: FotoView) {
        if (editor.selected !== f) return
        imgPanel?.let { bottom.removeView(it) }
        val p = buildImagePanel(f); imgPanel = p
        bottom.addView(p, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
    }

    /** El panel de la foto, igual que en Best Notes: ✂ · Editar · Sello · ⟲ · 🗑 · ✕ / ángulos / tamaño. */
    private fun buildImagePanel(f: FotoView): View {
        val b = f.block
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(8), dp(6), dp(6)) }
        fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // 1) acciones (repartidas a lo ancho)
        val r1 = row()
        fun act(icon: Int, label: String, color: Int = T.text, a: (View) -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8))
                pressBg(0, 18f); setOnClickListener { pop(it); a(it) }
            }
            v.addView(IconView(this, icon, 9f).apply { this.color = color; isClickable = false }, lp(dp(26), dp(26)))
            if (label.isNotEmpty()) v.addView(tv(label, 14f, color, true).apply { setPadding(dp(5), 0, 0, 0); maxLines = 1 })
            r1.addView(v, lp(0, WRAP, if (label.isEmpty()) 0.7f else 1.6f))
        }
        act(MoreIcons.SCISSORS, "") { cropImage(f) }
        act(MoreIcons.PEN, L.t("editShort")) { drawOnImage(f) }
        act(Icons.CLOCK, L.t("stamp"), if (b.stamp.isNotEmpty()) T.accent else T.text) { f.toggleStamp() }
        act(MoreIcons.ROTATE, "") { v -> v.getChildAt0()?.animate()?.rotationBy(-90f)?.setDuration(300)?.start(); rotateImage(f) }
        act(Icons.TRASH, "", T.danger) { editor.removeImage(f) }
        act(Icons.CLOSE, "") { editor.selectImage(null) }
        col.addView(r1)
        // 2) ángulos
        val r2 = row().apply { setPadding(dp(2), dp(6), dp(2), dp(4)) }
        val angles = ArrayList<TextView>()
        fun paintAngles() { for (t in angles) { val on = Math.round(b.rot) == t.tag as Int; t.setTextColor(if (on) 0xFFFFFFFF.toInt() else T.text); t.pressBg(if (on) 0xFFFF3B30.toInt() else 0, 20f, if (on) 0 else tw(0x40)) } }
        for (deg in intArrayOf(0, 15, 30, 45, 60, 75, 90, -15, -30, -45, -60, -75, -90)) {
            val t = tv("$deg°", 15f, T.text, true).apply {
                tag = deg; gravity = Gravity.CENTER; setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener { pop(it); f.setRot(deg.toFloat()); paintAngles() }
            }
            angles.add(t)
            r2.addView(t, lp(dp(66), WRAP).apply { marginEnd = dp(8) })
        }
        paintAngles()
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r2) })
        // 3) tamaño
        val r3 = row().apply { setPadding(dp(8), dp(2), dp(8), 0) }
        r3.addView(tv("↻ ${Math.round(b.rot)}°", 14f, T.muted, true).apply { minWidth = dp(50) })
        fun round(t: String, a: () -> Unit) = tv(t, 22f, T.text).apply {
            gravity = Gravity.CENTER; background = roundBg(0, dp(22).toFloat(), tw(0x40), dp(1)); setOnClickListener { pop(it); a() }
        }
        r3.addView(round("−") { f.setPct(b.pct - 5) }, lp(dp(44), dp(44)))
        val pctTv = tv("${b.pct.toInt()}%", 15f, T.text, true).apply { minWidth = dp(52); gravity = Gravity.END }
        val sb = android.widget.SeekBar(this).apply {
            max = 90; progress = (b.pct - 10).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFE0E0E0.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFF3B30.toInt())
        }
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { b.pct = (p + 10f).coerceAtMost(if (b.side.isEmpty()) 100f else 75f); b.x = b.x.coerceIn(0f, 100f - b.pct); f.relayout(); pctTv.text = "${b.pct.toInt()}%" } }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) { onDirty() }
        })
        r3.addView(sb, lp(0, WRAP, 1f))
        r3.addView(round("+") { f.setPct(b.pct + 5) }, lp(dp(44), dp(44)))
        r3.addView(pctTv)
        col.addView(r3)
        return col
    }
    private fun View.getChildAt0(): View? = (this as? android.view.ViewGroup)?.getChildAt(0)

    /* ---------- ✏️ dibujar ---------- */
    private lateinit var ink: InkView
    private lateinit var col: LinearLayout
    private lateinit var wallImg: android.widget.ImageView
    private lateinit var bottom: android.widget.FrameLayout
    private lateinit var kbBtn: View
    private var kbPeek = false
    private var barHiddenByKb = false

    /** ¿Está abierto el teclado? */
    private fun kbOpen(): Boolean {
        val r = root.rootView
        if (android.os.Build.VERSION.SDK_INT >= 30) return r.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
        val vis = android.graphics.Rect(); r.getWindowVisibleDisplayFrame(vis)
        return r.height - vis.bottom > r.height * 0.18f
    }

    /**
     * ⌨️ Teclado abierto → la barra de abajo se va (si no hay otro panel abierto, como el de la foto).
     * Teclado cerrado → la barra regresa. Con el botón "Aa" la puedes sacar mientras escribes.
     */
    private fun updateKbBar() {
        if (!::bottom.isInitialized) return
        val kb = kbOpen()
        if (!kb) kbPeek = false
        val onlyBar = (0 until bottom.childCount).all { val c = bottom.getChildAt(it); c === formatBar || c.visibility == View.GONE }
        val hide = kb && onlyBar && formatBar.visibility == View.VISIBLE && !kbPeek
        val showBtn = kb && onlyBar && formatBar.visibility == View.VISIBLE
        if (showBtn != (kbBtn.visibility == View.VISIBLE)) {
            kbBtn.visibility = if (showBtn) View.VISIBLE else View.GONE
            if (showBtn) { kbBtn.scaleX = 0.5f; kbBtn.scaleY = 0.5f; kbBtn.animate().scaleX(1f).scaleY(1f).setDuration(200).start() }
        }
        (kbBtn as? IconView)?.color = if (kbPeek) 0xFF4FC3F7.toInt() else T.text
        if (hide == barHiddenByKb) return
        barHiddenByKb = hide
        bottom.animate().cancel()
        if (hide) {
            bottom.visibility = View.GONE
        } else {
            bottom.visibility = View.VISIBLE
            bottom.alpha = 0f; bottom.translationY = dp(24).toFloat()
            bottom.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
    }
    private lateinit var formatBar: View
    private var drawBar: LinearLayout? = null
    private var extraObj = JSONObject()

    /** Fondo propio de la nota (los que vienen de Best Notes). */
    private fun showWall() {
        val w = extraObj.optString("wall", "")
        if (w.isEmpty()) { wallImg.visibility = View.GONE; return }
        wallImg.visibility = View.VISIBLE
        if (w.startsWith("g:")) {
            wallImg.clearColorFilter()
            wallImg.setImageDrawable(Wall.grad(w.substring(2)))
            return
        }
        wallImg.setColorFilter(0x59000000, android.graphics.PorterDuff.Mode.SRC_ATOP)
        Thumbs.load(w, resources.displayMetrics.widthPixels) { bm -> if (bm != null) wallImg.setImageBitmap(bm) else wallImg.visibility = View.GONE }
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
        for (k in listOf("pen", "hl", "eraser")) drawBtns[k]?.background = if (ink.tool == k) roundBg(tw(0x33), dp(22).toFloat()) else null
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
        Sheet(this, "🎙️ " + L.t("audio"))
            .quick(MoreIcons.MIC, L.t("recordShort"), Sheet.RED) { startRecording() }
            .quick(MoreIcons.TEXT_T, L.t("dictShort"), Sheet.GREEN) { startDictation() }
            .quick(MoreIcons.ATTACH, L.t("fileShort"), Sheet.BLUE) { pickMedia(false) }
            .item(MoreIcons.TEXT_T, L.t("dictate"), Sheet.GREEN, L.t("dictateSub")) { startDictation() }
            .item(MoreIcons.MIC, L.t("recordNow"), Sheet.RED) { startRecording() }
            .item(MoreIcons.ATTACH, L.t("audioFile"), Sheet.BLUE, L.t("audioFileSub")) { pickMedia(false) }
            .show()
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
        if (requestCode == REQ_DICT) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startDictation() else toast(L.t("micPerm"))
        }
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
            else toast(L.t("micPerm"))
        }
    }

    /* ---------- 🎬 video ---------- */
    private var vidFile: File? = null

    private fun videoMenu() {
        Sheet(this, "🎬 " + L.t("video"))
            .quick(MoreIcons.VIDEO, L.t("recordShort"), Sheet.RED) { recordVideo() }
            .quick(Icons.IMAGE, L.t("gallery"), Sheet.BLUE) { pickMedia(true) }
            .show()
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
        Sheet(this, "📷 " + L.t("addPhoto"))
            .quick(Icons.SCAN, L.t("camera"), Sheet.PINK) { openCamera() }
            .quick(Icons.REPORT, L.t("scanDocQuick"), Sheet.TEAL) { openCamera(scan = true) }
            .quick(Icons.IMAGE, L.t("gallery"), Sheet.BLUE) { openGallery() }
            .show()
    }

    private var scanMode = false

    private fun openCamera(scan: Boolean = false) {
        val f = File(cacheDir, "camara_" + System.currentTimeMillis() + ".jpg")
        camFile = f; scanMode = scan
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
    /* ---------- 🧮 calculadora: el resultado se pone donde estaba el cursor ---------- */
    private var calcEdit: RichEdit? = null
    private var calcPos = -1
    private fun openCalc() {
        calcEdit = editor.focused; calcPos = calcEdit?.selectionEnd ?: -1
        startActivityForResult(Intent(this, CalcActivity::class.java).putExtra(CalcActivity.EXTRA_CAN_INSERT, true), REQ_CALC)
    }
    private fun putCalc(text: String) {
        val e = calcEdit?.takeIf { it.isAttachedToWindow }
        if (e == null) {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("calc", text))
            toast(L.t("calcCopied")); return
        }
        val at = calcPos.coerceIn(0, e.text.length)
        val before = if (at > 0 && e.text[at - 1] != '\n') "\n" else ""
        val after = if (at < e.text.length && e.text[at] != '\n') "\n" else ""
        e.text.insert(at, before + text + after)
        e.requestFocus(); e.setSelection((at + before.length + text.length).coerceAtMost(e.text.length))
        toast(L.t("calcInserted"))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WALL) { if (resultCode == RESULT_OK) data?.data?.let { setWallPhoto(it) }; return }
        if (requestCode == REQ_CALC) { if (resultCode == RESULT_OK) data?.getStringExtra(CalcActivity.RESULT_TEXT)?.let { putCalc(it) }; return }
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
            REQ_CAMERA -> camFile?.let { f ->
                if (f.length() == 0L) return@let
                if (scanMode) {
                    // 📄 escanear: primero se endereza y aclara
                    Loader.pool.execute {
                        val r = try { Photos.import(this, android.net.Uri.fromFile(f)) } catch (_: Throwable) { null }
                        f.delete()
                        runOnUiThread { if (r != null) startActivityForResult(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.FILE, r.file), REQ_SCAN) else toast(L.t("photoFail")) }
                    }
                } else addUris(listOf(android.net.Uri.fromFile(f)), fromCamera = true) { f.delete() }
            }
            REQ_SCAN -> {
                val nf = data?.getStringExtra(ScanActivity.OUT) ?: return
                editor.insertImages(listOf(Block.Img(nf, 90f, "", 5f, 0f, data.getIntExtra(ScanActivity.OUT_W, 0), data.getIntExtra(ScanActivity.OUT_H, 0), "", 0f, System.currentTimeMillis())))
            }
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

    /* ---------- ⋮ opciones (el panel bonito) ---------- */
    private fun options() {
        save()
        val sub = L.t("modified") + " · " + L.when_(meta.updated.takeIf { it > 0 } ?: System.currentTimeMillis())
        val sh = Sheet(this, meta.title.ifEmpty { title.text.toString().ifEmpty { L.t("noteOptions") } }, sub)
        if (meta.deleted) {
            sh.item(MoreIcons.RESTORE, L.t("recover"), Sheet.GREEN) { recover() }
            sh.item(Icons.TRASH, L.t("delForever"), danger = true) {
                confirm(L.t("delForever"), L.t("delForeverMsg"), L.t("del"), true) { Versions.deleteAll(this, meta.id); Store.deleteForever(listOf(meta.id)); Store.changed(); loaded = false; finish() }
            }
            sh.show(); return
        }
        sh.quick(if (meta.fav) MoreIcons.STAR5_ON else MoreIcons.STAR5, L.t("favShort"), Sheet.AMBER, meta.fav) {
            meta.fav = !meta.fav; paintStar(); Store.saveMeta(meta); Store.changed(); hint(L.t(if (meta.fav) "favAdded" else "favRemoved"))
        }
        sh.quick(Icons.PIN, L.t("pinShort"), Sheet.SKY, meta.pinned) {
            meta.pinned = !meta.pinned; Store.saveMeta(meta); Store.changed(); hint(L.t(if (meta.pinned) "pinnedOk" else "unpinnedOk"))
        }
        sh.quick(MoreIcons.PALETTE, L.t("colorShort"), Sheet.PINK, meta.color != 0) { noteColor() }
        sh.quick(MoreIcons.IMPORT, L.t("share"), Sheet.BLUE) { shareMenu() }
        sh.item(Icons.STAR, L.t("aiAssistant"), Sheet.VIOLET, L.t("aiAssistantSub")) { aiAssistant() }
        sh.item(Icons.CLOCK, L.t("reminder"), Sheet.AMBER, Reminders.forNote(this, meta.id).firstOrNull()?.let { L.when_(it.at) }) {
            save(); remind(this, meta.id, (meta.title.ifEmpty { blocksPlain(editor.blocks).lineSequence().firstOrNull { it.isNotBlank() } ?: L.t("untitled") }).take(120)) { Store.changed() }
        }
        sh.item(MoreIcons.MIC, L.t("dictate"), Sheet.GREEN, L.t("dictateSub")) { startDictation() }
        sh.item(MoreIcons.HISTORY, L.t("history"), Sheet.TEAL, L.t("historySub")) { openHistory() }
        sh.item(Icons.SPELL, L.t("spellCheck"), Sheet.SKY, L.t("spellCheckSub")) { startSpell() }
        sh.item(Icons.REPORT, L.t("templates"), Sheet.BLUE, L.t("templatesSub")) { openTemplates() }
        sh.divider()
        sh.item(MoreIcons.PALETTE, L.t("titleColor"), Sheet.PINK) { title.requestFocus(); pickColor("color") }
        sh.item(MoreIcons.WALLPAPER, L.t("noteWall"), Sheet.ORANGE, L.t("noteWallSub")) { wallMenu() }
        sh.item(MoreIcons.FOLDER, L.t("moveToFolder"), Sheet.ORANGE, Store.folders.firstOrNull { it.id == meta.folder }?.name) { moveFolder() }
        sh.item(Icons.LOCK, L.t(if (meta.locked) "unlock" else "lockPin"), Sheet.GRAY) { toggleLock() }
        sh.item(MoreIcons.INFO, L.t("info"), Sheet.GRAY) { info() }
        sh.divider()
        sh.item(Icons.DELETE, L.t("clearAll"), danger = true) {
            confirm(L.t("clearAll"), L.t("clearAllMsg"), L.t("del"), true) {
                if (snapPending) snapshot()
                muting = true
                editor.setBlocks(mutableListOf(Block.Text()))
                ink.set(emptyList()); extraObj.remove("ink"); fitInk()
                muting = false
                dirty = true; onDirty(); snapshot(); hint("🧹 " + L.t("cleared"))
            }
        }
        sh.item(Icons.TRASH, L.t("moveTrash"), danger = true) {
            if (isNew && isEmptyNote()) { finish(); return@item }
            meta.deleted = true; meta.deletedAt = System.currentTimeMillis(); Store.saveMeta(meta); Store.changed()
            MainActivity.pendingTrash = listOf(meta.id); finish()
        }
        sh.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            Sheet.closeTop(this) -> {}
            multi != null -> multiEnd()
            spell != null -> closeSpell()
            stylePanel != null -> closeStyle()
            editor.selected != null -> editor.selectImage(null)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun recover() {
        meta.deleted = false; Store.saveMeta(meta); Store.changed(); toast(L.t("recovered"))
        editor.readOnly = false; editor.flushAll(); editor.rebuild(null); showBanners()
    }

    private fun noteColor() {
        lateinit var d: GlassDialog
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

    /* ---------- 📋 plantillas ---------- */
    private fun openTemplates() {
        templateSheet({ t -> useTemplate(t) }) {
            askText(L.t("tplSave"), title.text.toString(), L.t("tplName")) { name ->
                if (name.isBlank()) return@askText
                editor.flushAll(); Templates.saveMine(this, name.trim(), title.text.toString(), editor.blocks); toast(L.t("tplSaved"))
            }
        }
    }

    private fun useTemplate(t: Templates.Tpl) {
        if (snapPending) snapshot()
        editor.flushAll()
        val add = t.blocks()
        muting = true
        if (isEmptyNote()) {
            if (title.text.isBlank()) title.setText(t.title)
            editor.setBlocks(normalize(add))
        } else editor.setBlocks(normalize((editor.blocks + add).toMutableList()))
        muting = false
        dirty = true; onDirty(); snapshot()
        root.alpha = 0.4f; root.animate().alpha(1f).setDuration(400).start()
        hint("📋 " + t.name)
    }

    /* ---------- 🖼️ insertar (foto, video, audio, IA) ---------- */
    private fun insertMenu() {
        Sheet(this, L.t("insert"))
            .quick(Icons.SCAN, L.t("camera"), Sheet.PINK) { openCamera() }
            .quick(Icons.REPORT, L.t("scanDocQuick"), Sheet.TEAL) { openCamera(scan = true) }
            .quick(Icons.IMAGE, L.t("gallery"), Sheet.BLUE) { openGallery() }
            .quick(MoreIcons.VIDEO, L.t("video"), Sheet.ORANGE) { videoMenu() }
            .quick(MoreIcons.MIC, L.t("audio"), Sheet.RED) { audioMenu() }
            .item(Icons.STAR, "✨ " + L.t("ai"), Sheet.VIOLET, L.t("aiFree")) { aiMenu() }
            .item(MoreIcons.TEXT_T, L.t("dictate"), Sheet.GREEN, L.t("dictateSub")) { startDictation() }
            .item(MoreIcons.PEN, L.t("newDraw"), Sheet.TEAL) { setDrawMode(true) }
            .item(Icons.REPORT, L.t("templates"), Sheet.BLUE, L.t("templatesSub")) { openTemplates() }
            .show()
    }

    /* ---------- 🎨 estilo de la letra: un panel ABAJO (no tapa lo que seleccionaste) ---------- */
    private var stylePanel: LinearLayout? = null

    private fun colorMenu() {
        if (stylePanel != null) { closeStyle(); return }
        if (spell != null) closeSpell()
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(8), dp(10)) }
        stylePanel = p
        formatBar.visibility = View.GONE
        bottom.addView(p, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        fillStyle()
        p.translationY = dp(50).toFloat(); p.alpha = 0f
        p.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        for (i in 0 until p.childCount) { val ch = p.getChildAt(i); ch.alpha = 0f; ch.translationX = dp(24).toFloat()
            ch.animate().alpha(1f).translationX(0f).setStartDelay(80L + i * 70).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator()).start() }
    }

    private fun fillStyle() {
        val p = stylePanel ?: return
        p.removeAllViews()
        val inTitle = title.hasFocus()
        val st = target()?.currentStyle() ?: St.PLAIN
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, 0, 0) }
        head.addView(tv((if (inTitle) L.t("titleStyle") else L.t("textStyle")).uppercase(L.locale), 12f, T.muted, true).apply { letterSpacing = 0.1f }, lp(0, WRAP, 1f))
        head.addView(iconBtn(Icons.CLOSE, 8.5f) { closeStyle() }.apply { layoutParams = lp(dp(36), dp(36)) })
        p.addView(head)
        // 1) los colores de letra: un toque y listo
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(8)) }
        fun dot(c: Int, on: Boolean, a: (View) -> Unit) {
            val v = FrameLayout(this)
            val inner = View(this).apply { background = if (c == 0) roundBg(0, dp(16).toFloat(), 0x99FFFFFF.toInt(), dp(2)) else roundBg(c, dp(16).toFloat(), 0x40000000, dp(1)) }
            v.addView(inner, android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            if (c == 0) v.addView(tv("∅", 15f, T.text, true).apply { gravity = Gravity.CENTER }, android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            if (on) v.background = roundBg(0, dp(21).toFloat(), T.text, dp(2))
            v.setOnClickListener { pop(inner); a(it) }
            row.addView(v, lp(dp(42), dp(42)).apply { marginEnd = dp(4) })
        }
        dot(0, st.color == 0) { styleNow { s -> s.copy(color = 0) } }
        for (c in T.textColors) dot(c, st.color == c) { styleNow { s -> s.copy(color = c) } }
        // ＋ = todos los colores (rueda)
        val more = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                gradientType = android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
                colors = intArrayOf(0xFFFF453A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF64D2FF.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF453A.toInt())
            }
            setOnClickListener { pop(it); pickColor("color") }
        }
        row.addView(more, lp(dp(32), dp(32)).apply { marginStart = dp(5); marginEnd = dp(8) })
        p.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        // 2) resaltar · contorno · tachado
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun pill(icon: Int, label: String, on: Boolean, color: Int, a: () -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(9), dp(8), dp(9))
                background = if (on) roundBg((color and 0xFFFFFF) or 0x40000000, dp(20).toFloat(), color, dp(1)) else roundBg(tw(0x14), dp(20).toFloat(), tw(0x26), dp(1))
                setOnClickListener { pop(it); a() }
            }
            v.addView(IconView(this, icon, 7.5f).apply { this.color = if (on) color else T.text; isClickable = false }, lp(dp(22), dp(22)))
            v.addView(tv(label, 13.5f, if (on) color else T.text, true).apply { setPadding(dp(5), 0, 0, 0); maxLines = 1 })
            r2.addView(v, lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
        }
        pill(MoreIcons.HILITE, L.t("hilite"), st.bg != 0, 0xFFFFD54F.toInt()) { pickColor("hi") }
        pill(MoreIcons.OUTLINE, L.t("outlineShort"), st.o != 0, 0xFF4FC3F7.toInt()) { pickColor("ol") }
        pill(MoreIcons.STRIKE, L.t("strike"), st.s, 0xFFF48FB1.toInt()) { styleNow { s -> s.copy(s = !cur().s) } }
        p.addView(r2)
    }

    /** Aplica el estilo sin quitar lo que seleccionaste, y repinta el panel. */
    private fun styleNow(f: (St) -> St) {
        target()?.let { if (!it.hasFocus()) it.requestFocus() }
        style(f)
        Store.main.post { fillStyle() }
    }

    private fun closeStyle() {
        val p = stylePanel ?: return
        stylePanel = null
        p.animate().translationY(dp(40).toFloat()).alpha(0f).setDuration(200).withEndAction {
            bottom.removeView(p); if (imgPanel == null && spell == null && !ink.drawing && rec == null) formatBar.visibility = View.VISIBLE
        }.start()
    }

    /* ---------- 🖼️ fondo de la nota ---------- */
    private fun wallMenu() {
        val cur = extraObj.optString("wall", "")
        val sh = Sheet(this, "🖼️ " + L.t("noteWall"), L.t("noteWallSub"))
        sh.quick(Icons.IMAGE, L.t("wallPhoto"), Sheet.BLUE, cur.isNotEmpty() && !cur.startsWith("g:")) {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_WALL) } catch (_: Exception) { toast(L.t("cantOpen")) }
        }
        if (cur.isNotEmpty()) sh.quick(Icons.CLOSE, L.t("wallNone"), Sheet.RED) { extraObj.remove("wall"); showWall(); dirty = true; save() }
        // degradados para tocar
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(8)) }
        for (g in Wall.GRADS) {
            val id = "g:" + g[0]
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val th = View(this).apply {
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, g[3] as IntArray).apply {
                    cornerRadius = dp(14).toFloat(); if (id == cur) setStroke(dp(3), 0xFF4FC3F7.toInt()) else setStroke(dp(1), tw(0x33))
                }
            }
            cell.addView(th, lp(dp(62), dp(90)))
            cell.addView(tv((if (L.lang == "es") g[1] else g[2]) as String, 11.5f, if (id == cur) 0xFF4FC3F7.toInt() else T.muted).apply { setPadding(0, dp(4), 0, 0) })
            cell.setOnClickListener { pop(th); extraObj.put("wall", id); showWall(); dirty = true; save(); Sheet.closeTop(this) }
            row.addView(cell, lp(WRAP, WRAP).apply { marginEnd = dp(10) })
        }
        sh.custom(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        sh.show()
    }

    private fun setWallPhoto(uri: android.net.Uri) {
        Thread {
            val r = try { Photos.import(this, uri) } catch (_: Exception) { null }
            runOnUiThread {
                if (r == null) { toast(L.t("cantOpen")); return@runOnUiThread }
                extraObj.put("wall", r.file); showWall(); dirty = true; save()
                wallImg.alpha = 0f; wallImg.animate().alpha(1f).setDuration(600).start()
            }
        }.start()
    }

    /* ---------- 🕘 historial y versiones ---------- */
    private var openTitle = ""
    private var openBody = ""
    private var openInk: Any? = null
    private var openAt = 0L
    /** 🕘 este rato de trabajo (para la línea de tiempo). */
    private var sessStart = 0L
    private var sessPre: JSONObject? = null
    private var wasNew = false

    private val logMine = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun histLocked() = extraObj.optBoolean("histLocked")

    private fun openHistory() {
        save()
        if (histLocked()) Pin.check(this) { showHistory() } else showHistory()
    }

    private fun showHistory() {
        editor.flushAll(); save()
        val list = Versions.load(this, meta.id)
        val logN = NoteLog.load(this, meta.id).size
        val sh = Sheet(this, "🕘 " + L.t("history"))
        timelineInto(sh)
        sh.custom(tv(L.t("tlMore").uppercase(L.locale), 13f, T.muted, true).apply { letterSpacing = 0.1f; setPadding(dp(14), dp(18), dp(14), dp(8)) })
        // 📖 el registro (una sola lista que se actualiza sola)
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = Glass.make(this@NoteActivity, 20f)
        }
        val ic = FrameLayout(this).apply { background = roundBg(T.pill, dp(24).toFloat()) }
        ic.addView(IconView(this, Icons.BOOK, 9.5f).apply { color = T.onPill; isClickable = false }, FrameLayout.LayoutParams(dp(48), dp(48)))
        logCard.addView(ic, lp(dp(48), dp(48)))
        val lt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
        lt.addView(tv(L.t("record"), 17f, T.text, true))
        lt.addView(tv(L.t("recLines", "n" to logN), 13f, T.muted))
        logCard.addView(lt, lp(0, WRAP, 1f))
        logCard.addView(IconView(this, Icons.CHEVRON, 6.5f).apply { color = T.muted; isClickable = false }, lp(dp(20), dp(20)))
        logCard.setOnClickListener { pop(it); Sheet.closeTop(this); Store.main.postDelayed({ viewLog() }, 300) }
        sh.custom(FrameLayout(this).apply { setPadding(dp(6), 0, dp(6), dp(10)); addView(logCard) })
        // guardar ahora
        val saveBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(14), dp(14), dp(14), dp(14))
            pressBg(T.pill, 24f)
        }
        saveBtn.addView(IconView(this, Icons.CHECK, 8.5f).apply { color = T.onPill; isClickable = false }, lp(dp(22), dp(22)))
        saveBtn.addView(tv(L.t("saveVersionNow"), 16f, T.onPill, true).apply { setPadding(dp(8), 0, 0, 0) })
        saveBtn.setOnClickListener {
            pop(it)
            val v = Versions.make(title.text.toString(), blocksJson(editor.blocks), extraObj.opt("ink"), auto = false)
            if (Versions.add(this, meta.id, v)) { toast(L.t("versionSaved")); Sheet.closeTop(this); Store.main.postDelayed({ showHistory() }, 320) }
            else toast(L.t("versionExists"))
        }
        sh.custom(LinearLayout(this).apply { setPadding(dp(6), 0, dp(6), dp(6)); addView(saveBtn, lp(MATCH, WRAP)) })
        sh.custom(tv(L.t("savedVersions").uppercase(L.locale), 13f, T.muted, true).apply { letterSpacing = 0.1f; setPadding(dp(14), dp(12), dp(14), dp(8)) })
        if (list.isEmpty()) sh.custom(tv(L.t("noVersions"), 14.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(6), dp(20), dp(14)) })
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, L.locale)
        for ((i, v) in list.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(10), dp(11))
                background = Glass.make(this@NoteActivity, 18f)
            }
            card.addView(View(this).apply { background = roundBg(0xFFFFD54F.toInt(), dp(5).toFloat()) }, lp(dp(10), dp(10)))
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
            txt.addView(tv(df.format(Date(v.optLong("at"))) + (if (i == 0) "  ·  " + L.t("latest") else ""), 14f, T.text, true))
            txt.addView(tv(Versions.preview(v).ifEmpty { L.t("emptyNote") }, 12.5f, T.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            card.addView(txt, lp(0, WRAP, 1f))
            card.addView(IconView(this, Icons.CHEVRON, 6f).apply { color = tw(0x55); isClickable = false }, lp(dp(18), dp(18)))
            card.setOnClickListener { pop(it); Sheet.closeTop(this); Store.main.postDelayed({ viewVersion(v) }, 300) }
            sh.custom(FrameLayout(this).apply { setPadding(dp(6), 0, dp(6), dp(8)); addView(card) })
        }
        // proteger con PIN
        val sw = android.widget.Switch(this).apply {
            text = "🔒  " + L.t("protectHist"); setTextColor(T.text); textSize = 15.5f; isChecked = histLocked(); setPadding(dp(14), dp(12), dp(14), dp(8))
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt()))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF4FC3F7.toInt(), tw(0x55)))
            setOnCheckedChangeListener { b, on ->
                if (on) {
                    val go = { extraObj.put("histLocked", true); dirty = true; save(); hint(L.t("histWithPin")) }
                    if (Pin.saved(this@NoteActivity) == null) Pin.create(this@NoteActivity) { go() } else go()
                } else Pin.check(this@NoteActivity) { extraObj.remove("histLocked"); dirty = true; save(); hint(L.t("histNoPin")) }
                b.postDelayed({ b.isChecked = histLocked() }, 400)
            }
        }
        sh.custom(sw)
        sh.show()
    }

    /** 📖 Ver el registro: todo lo que escribiste (y fotos), en orden. */
    private fun viewLog() {
        val log = NoteLog.load(this, meta.id)
        val sh = Sheet(this, "📖 " + L.t("record"), L.t("recIntro"))
        sh.quick(MoreIcons.RESTORE, L.t("putAllInNote"), Sheet.GREEN) {
            if (log.isEmpty()) return@quick
            confirm(L.t("putAllInNote"), L.t("putAllMsg"), L.t("ok")) { logToNote(log) }
        }
        sh.quick(Icons.TRASH, L.t("recClear"), Sheet.RED) {
            confirm(L.t("recClear"), L.t("recClearMsg"), L.t("del"), true) { NoteLog.clear(this, meta.id); logMine.clear(); toast(L.t("recCleared")) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = Glass.make(this@NoteActivity, 18f) }
        if (log.isEmpty()) box.addView(tv(L.t("recEmpty"), 15f, T.muted).apply { gravity = Gravity.CENTER })
        for (e in log) {
            if (e.startsWith(NoteLog.IMG)) {
                val iv = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundBg(tw(0x1A), dp(12).toFloat()) }
                Thumbs.load(e.substring(1), dp(300)) { bm -> if (bm != null) iv.setImageBitmap(bm) else iv.visibility = View.GONE }
                box.addView(iv, lp(MATCH, dp(130)).apply { topMargin = dp(4); bottomMargin = dp(6) })
            } else box.addView(tv(e, 15.5f, T.text).apply { setPadding(0, dp(3), 0, dp(3)) })
        }
        sh.custom(FrameLayout(this).apply { setPadding(dp(6), dp(4), dp(6), dp(4)); addView(box) })
        sh.show()
    }

    private fun logToNote(log: List<String>) {
        if (snapPending) snapshot()
        val blocks = ArrayList<Block>()
        val sb = StringBuilder()
        fun flushText() { if (sb.isNotEmpty()) { blocks.add(Block.Text(mutableListOf(Run(sb.toString().trimEnd('\n'))))); sb.clear() } }
        for (e in log) if (e.startsWith(NoteLog.IMG)) { flushText(); blocks.add(Block.Img(e.substring(1))) } else sb.append(e).append('\n')
        flushText()
        muting = true
        editor.setBlocks(normalize(blocks))
        muting = false
        dirty = true; save(); snapshot()
        root.alpha = 0.3f; root.animate().alpha(1f).setDuration(450).start()
        toast(L.t("putAllDone"))
    }

    /* ---------- 🕘 la línea de tiempo ---------- */
    private var tlFilter = "all"

    private fun dayLabel(t: Long): String {
        val c = java.util.Calendar.getInstance(); val d = java.util.Calendar.getInstance().apply { timeInMillis = t }
        fun same() = c.get(java.util.Calendar.YEAR) == d.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.DAY_OF_YEAR) == d.get(java.util.Calendar.DAY_OF_YEAR)
        if (same()) return L.t("today")
        c.add(java.util.Calendar.DAY_OF_YEAR, -1); if (same()) return L.t("yesterday")
        if (System.currentTimeMillis() - t < 7 * 86_400_000L) return L.t("tlThisWeek")
        return java.text.SimpleDateFormat(if (L.lang == "es") "d 'de' MMMM yyyy" else "MMMM d, yyyy", L.locale).format(Date(t))
    }

    private fun tlRange(o: JSONObject): String {
        val a = L.time(o.optLong("s")); val b = L.time(o.optLong("e"))
        return if (a == b) a else "$a – $b"
    }

    private fun tlStyle(kind: String): Pair<Int, Int> = when (kind) {
        "img" -> Icons.IMAGE to Sheet.PINK
        "media" -> MoreIcons.MIC to Sheet.ORANGE
        "list" -> MoreIcons.CHECKBOX to Sheet.GREEN
        "style" -> MoreIcons.PALETTE to Sheet.VIOLET
        else -> MoreIcons.PEN to Sheet.SKY
    }

    /** Pone los filtros y la línea de tiempo dentro del panel de historial. */
    private fun timelineInto(sh: Sheet) {
        val all = Timeline.load(this, meta.id).map { it to Timeline.diff(it.getJSONObject("pre"), it.getJSONObject("post")) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(2), dp(6), dp(12)) }
        val filters = listOf("all" to L.t("tlAll"), "text" to "✏️ " + L.t("tlText"), "img" to "📷 " + L.t("tlPhotos"), "list" to "☑ " + L.t("tlLists"), "media" to "🎤 " + L.t("tlMedia"), "style" to "🎨 " + L.t("tlStyle"))
        fun render() {
            for (k in 0 until chips.childCount) (chips.getChildAt(k) as TextView).let { c ->
                val on = c.tag == tlFilter
                c.setTextColor(if (on) T.onPill else T.text)
                c.pressBg(if (on) T.pill else 0, 18f, if (on) 0 else tw(0x33))
            }
            box.removeAllViews()
            val list = all.filter { tlFilter == "all" || tlFilter in it.second.kinds }
            if (list.isEmpty()) {
                box.addView(tv(if (all.isEmpty()) L.t("tlEmpty") else L.t("tlNoneFilter"), 14.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(14)) })
                return
            }
            var lastDay = ""
            for ((idx, p) in list.withIndex()) {
                val (o, d) = p
                val day = dayLabel(o.optLong("s"))
                if (day != lastDay) {
                    lastDay = day
                    box.addView(tv(day, 13.5f, T.muted, true).apply { setPadding(dp(8), dp(if (idx == 0) 0 else 12), dp(8), dp(6)) })
                }
                val (icon, col) = tlStyle(d.main)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, dp(4), 0) }
                // la rayita y la bolita con el ícono
                val rail = FrameLayout(this)
                val last = idx == list.size - 1 || dayLabel(list[idx + 1].first.optLong("s")) != day
                rail.addView(View(this).apply { setBackgroundColor(tw(0x26)) }, FrameLayout.LayoutParams(dp(2), if (last) dp(40) else MATCH, Gravity.CENTER_HORIZONTAL))
                val dot = FrameLayout(this).apply { background = roundBg(col and 0x33FFFFFF or 0x33000000, dp(18).toFloat(), col, dp(2)) }
                dot.addView(IconView(this, icon, 7.5f).apply { color = col; isClickable = false }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                rail.addView(dot, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
                row.addView(rail, lp(dp(44), MATCH))
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(11), dp(12), dp(11))
                    background = Glass.make(this@NoteActivity, 18f)
                }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(tv(tlRange(o), 15f, T.text, true), lp(0, WRAP, 1f))
                top.addView(IconView(this, Icons.CHEVRON, 6f).apply { color = tw(0x55); isClickable = false }, lp(dp(16), dp(16)))
                card.addView(top)
                card.addView(tv(Timeline.summary(d), 13f, col).apply { setPadding(0, dp(3), 0, 0) })
                (d.changes.firstOrNull { it.op == '+' && it.tok.k != Timeline.K.IMG && it.tok.k != Timeline.K.AUDIO && it.tok.k != Timeline.K.VIDEO }
                    ?: d.changes.firstOrNull { it.tok.k == Timeline.K.TEXT || it.tok.k == Timeline.K.LIST })?.let { c ->
                    card.addView(tv((if (c.op == '+') "" else "− ") + c.tok.show, 13f, T.muted).apply {
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
                        if (c.op == '-') paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    })
                }
                card.setOnClickListener { pop(it); Sheet.closeTop(this); Store.main.postDelayed({ viewSession(o, d) }, 300) }
                row.addView(card, lp(0, WRAP, 1f).apply { bottomMargin = dp(8) })
                box.addView(row, lp(MATCH, WRAP))
                // entra con una animacioncita
                row.alpha = 0f; row.translationX = dp(24).toFloat()
                row.animate().alpha(1f).translationX(0f).setStartDelay(40L * minOf(idx, 10)).setDuration(260).start()
            }
        }
        for ((k, label) in filters) chips.addView(tv(label, 13.5f, T.text, true).apply {
            tag = k; setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { pop(it); tlFilter = k; render() }
        }, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        sh.custom(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        render()
        sh.custom(box)
    }

    /** Lo que cambió en un rato de trabajo: verde = nuevo, rojo = borrado. */
    private fun viewSession(o: JSONObject, d: Timeline.Diff) {
        val pre = o.getJSONObject("pre"); val post = o.getJSONObject("post")
        val sh = Sheet(this, dayLabel(o.optLong("s")) + ", " + tlRange(o), Timeline.summary(d))
        sh.quick(MoreIcons.RESTORE, L.t("tlBackTo"), Sheet.GREEN) {
            confirm(L.t("tlBackTo"), L.t("tlBackToMsg"), L.t("restore")) { restoreVersion(post) }
        }
        sh.quick(MoreIcons.HISTORY, L.t("tlBefore"), Sheet.BLUE) {
            confirm(L.t("tlBefore"), L.t("tlBeforeMsg"), L.t("restore")) { restoreVersion(pre) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); background = Glass.make(this@NoteActivity, 18f) }
        val green = 0xFF30D158.toInt(); val red = 0xFFFF453A.toInt()
        if (d.changes.isEmpty()) box.addView(tv(L.t("tlOnlyStyle"), 14.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)) })
        for (c in d.changes.take(250)) {
            val add = c.op == '+'
            val col = if (add) green else red
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(7), dp(8), dp(7))
                background = roundBg(col and 0x00FFFFFF or 0x24000000, dp(12).toFloat())
            }
            row.addView(tv(if (add) "+" else "−", 17f, col, true).apply { gravity = Gravity.CENTER }, lp(dp(20), WRAP))
            when (c.tok.k) {
                Timeline.K.IMG -> {
                    val iv = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundBg(tw(0x1A), dp(10).toFloat(), col, dp(2)); setPadding(dp(2), dp(2), dp(2), dp(2)) }
                    Thumbs.load(c.tok.show, dp(240)) { bm -> if (bm != null) iv.setImageBitmap(bm) }
                    row.addView(iv, lp(0, dp(110), 1f).apply { marginStart = dp(4); marginEnd = dp(8) })
                }
                Timeline.K.AUDIO, Timeline.K.VIDEO -> {
                    val m = c.tok.block as? Block.Media
                    val name = (if (c.tok.k == Timeline.K.AUDIO) "🎤 " + L.t("tlVoice") else "🎬 " + L.t("tlVideo")) + (m?.let { if (it.dur > 0) "  " + MediaFiles.timeText(it.dur) else "" } ?: "")
                    row.addView(tv(name, 15f, T.text, true).apply { setPadding(dp(4), 0, dp(6), 0) }, lp(0, WRAP, 1f))
                }
                else -> row.addView(tv((if (c.tok.k == Timeline.K.TITLE) "🏷️ " else if (c.tok.k == Timeline.K.LIST) "• " else "") + c.tok.show, 15f, if (add) T.text else T.muted).apply {
                    setPadding(dp(4), 0, dp(6), 0)
                    if (!add) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                }, lp(0, WRAP, 1f))
            }
            if (!add && c.tok.k != Timeline.K.TITLE) {
                row.addView(tv("↩ " + L.t("tlRecover"), 13f, T.onPill, true).apply {
                    setPadding(dp(12), dp(7), dp(12), dp(7)); pressBg(T.pill, 16f)
                    setOnClickListener { b -> pop(b); recover(c.tok); (b as TextView).text = "✓"; b.isEnabled = false }
                })
            }
            box.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(6) })
        }
        if (d.changes.size > 250) box.addView(tv("… +" + (d.changes.size - 250), 14f, T.muted).apply { gravity = Gravity.CENTER })
        sh.custom(FrameLayout(this).apply { setPadding(dp(6), dp(4), dp(6), dp(4)); addView(box) })
        sh.show()
    }

    /** Vuelve a poner (al final de la nota) algo que habías borrado. */
    private fun recover(t: Timeline.Tok) {
        if (snapPending) snapshot()
        editor.flushAll()
        val b: Block = when (t.k) {
            Timeline.K.TEXT, Timeline.K.TITLE -> (t.block as? Block.Text)?.let { tb ->
                // si el renglón tenía estilo, se recupera con su estilo
                val plain = runsPlain(tb.runs); val i = plain.indexOf(t.show)
                if (i >= 0) Block.Text(sliceRuns(tb.runs, i, i + t.show.length)) else null
            } ?: Block.Text(mutableListOf(Run(t.show)))
            else -> t.block ?: Block.Text(mutableListOf(Run(t.show)))
        }
        val nb = editor.blocks.toMutableList(); nb.add(b)
        muting = true
        editor.setBlocks(normalize(nb))
        muting = false
        dirty = true; save(); snapshot()
        toast(L.t("tlRecovered"))
    }

    private fun viewVersion(v: JSONObject) {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, L.locale)
        val blocks = blocksFromJson(v.optString("b"))
        val photos = blocks.count { it is Block.Img }
        val sh = Sheet(this, df.format(Date(v.optLong("at"))), L.t(if (v.optBoolean("auto")) "versionAuto" else "versionMine"))
        sh.quick(MoreIcons.RESTORE, L.t("restore"), Sheet.GREEN) {
            confirm(L.t("versionRestoreTitle"), L.t("versionRestoreMsg"), L.t("restore")) { restoreVersion(v) }
        }
        sh.quick(Icons.TRASH, L.t("del"), Sheet.RED) {
            confirm(L.t("versionDelTitle"), null, L.t("del"), true) { Versions.remove(this, meta.id, v.optLong("at")); toast(L.t("versionDeleted")) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(12)); background = Glass.make(this@NoteActivity, 18f) }
        if (v.optString("t").isNotEmpty()) box.addView(tv(v.optString("t"), 19f, T.text, true).apply { setPadding(0, 0, 0, dp(6)) })
        box.addView(tv(blocksPlain(blocks).trim().ifEmpty { L.t("emptyNote") }, 15.5f, T.text))
        if (photos > 0) box.addView(tv("📷 × $photos", 14f, T.muted).apply { setPadding(0, dp(8), 0, 0) })
        sh.custom(FrameLayout(this).apply { setPadding(dp(6), dp(4), dp(6), dp(4)); addView(box) })
        sh.show()
    }

    private fun restoreVersion(v: JSONObject) {
        if (snapPending) snapshot()
        muting = true
        val tr = v.optJSONArray("tr")?.let { runsFromJson(it) }?.takeIf { runsPlain(it).trim() == v.optString("t").trim() }
        if (tr != null) { extraObj.put("tr", runsJson(tr)); title.setText(Rich.build(tr)) } else { extraObj.remove("tr"); title.setText(v.optString("t")) }
        editor.setBlocks(blocksFromJson(v.optString("b")))
        val inkJ = v.opt("ink")
        if (inkJ is org.json.JSONArray) { extraObj.put("ink", inkJ); ink.set(Stroke.listFrom(inkJ)) } else { extraObj.remove("ink"); ink.set(emptyList()) }
        fitInk()
        muting = false
        dirty = true; save(); snapshot()
        root.alpha = 0.3f; root.animate().alpha(1f).setDuration(450).start()
        toast(L.t("versionRestored"))
    }

    /* ---------- 🔤 revisar ortografía (como en Best Notes, con LanguageTool gratis) ---------- */
    private class SpErr(val edit: EditText, var start: Int, val len: Int, val word: String, val msg: String, val sugs: List<String>)
    class SpellS : android.text.style.BackgroundColorSpan(0x4DFF5252)
    private var spell: LinearLayout? = null
    private val spErrs = ArrayList<SpErr>()
    private var spI = 0

    /* =========================================================
       ↕ SELECCIONAR TEXTO DE VERDAD (aunque esté en renglones distintos)
       - Dejas presionada una palabra: salen DOS GOTITAS de la app.
         Las arrastras y se selecciona todo lo que quieras: el título,
         el texto y todos los renglones de las listas, de corrido.
       - "Líneas sueltas": tocas los renglones que quieras (1 sí, 2 no, 3 sí…)
         y se copian juntos.
       - Copiar, cortar, borrar y TODA la barra de abajo (negrita, colores,
         tamaño, contorno…) funcionan con lo seleccionado.
       ========================================================= */
    private class SelS(c: Int) : android.text.style.BackgroundColorSpan(c)
    private var multi: LinearLayout? = null
    private var mEdits: List<EditText> = emptyList()
    private var mA = 0 to 0
    private var mB = 0 to 0
    private var mInfo: TextView? = null
    private var mLinesMode = false
    private val mLines = java.util.TreeSet<Pair<Int, Int>>(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
    private var handles: HandlesView? = null
    private var mLinesBtn: TextView? = null
    private lateinit var noteHolder: android.widget.FrameLayout

    private fun mRange(): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val a = mA; val b = mB
        return if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a to b else b to a
    }

    /** Dónde empieza y termina el renglón [line] (separado por Enter) de una cajita. */
    private fun lineRange(e: EditText, line: Int): Pair<Int, Int>? {
        val t = e.text.toString(); var start = 0
        for (k in 0 until line) { val n = t.indexOf('\n', start); if (n < 0) return null; start = n + 1 }
        val end = t.indexOf('\n', start).let { if (it < 0) t.length else it }
        return start to end
    }
    private fun lineOf(e: EditText, off: Int): Int = e.text.toString().substring(0, off.coerceIn(0, e.text.length)).count { it == '\n' }

    /** Los pedazos seleccionados, en orden: (cajita, desde, hasta). */
    private fun mPieces(): List<Triple<EditText, Int, Int>> {
        val out = ArrayList<Triple<EditText, Int, Int>>()
        if (mLinesMode) {
            for ((i, l) in mLines) { val e = mEdits.getOrNull(i) ?: continue; val r = lineRange(e, l) ?: continue; if (r.first < r.second) out.add(Triple(e, r.first, r.second)) }
            return out
        }
        val (s, e) = mRange()
        for (i in s.first..e.first) {
            val ed = mEdits.getOrNull(i) ?: continue
            val len = ed.text.length
            val a = if (i == s.first) s.second.coerceIn(0, len) else 0
            val b = if (i == e.first) e.second.coerceIn(0, len) else len
            if (a < b) out.add(Triple(ed, a, b))
        }
        return out
    }

    private fun multiPaint(wave: Boolean) {
        val col = if (T.light) 0x664FC3F7 else 0x668B7CFF.toInt()
        for (e in mEdits) e.text.getSpans(0, e.text.length, SelS::class.java).forEach { e.text.removeSpan(it) }
        for ((k, p) in mPieces().withIndex()) {
            val (e, a, b) = p
            val put = { if (multi != null && b <= e.text.length) e.text.setSpan(SelS(col), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (wave) { e.postDelayed(put, 30L * minOf(k, 20)); e.postDelayed({ Anim.bump(e, 1.012f) }, 30L * minOf(k, 20)) } else put()
        }
        mLinesBtn?.let { b -> b.setTextColor(if (mLinesMode) 0xFF4FC3F7.toInt() else T.text) }
        handles?.visibility = if (mLinesMode) View.GONE else View.VISIBLE
        handles?.invalidate()
    }

    /** El sitio (cajita, letra) que está debajo de un punto de la nota (en coordenadas de [noteHolder]). */
    private fun spotAt(x: Float, y: Float): Pair<Int, Int>? {
        val hl = IntArray(2); noteHolder.getLocationInWindow(hl)
        var best = -1; var bestD = Float.MAX_VALUE
        val loc = IntArray(2)
        for ((i, e) in mEdits.withIndex()) {
            if (!e.isShown) continue
            e.getLocationInWindow(loc)
            val top = (loc[1] - hl[1]).toFloat(); val bot = top + e.height
            val d = if (y < top) top - y else if (y > bot) y - bot else 0f
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return null
        val e = mEdits[best]; e.getLocationInWindow(loc)
        val lx = x - (loc[0] - hl[0]); val ly = (y - (loc[1] - hl[1])).coerceIn(0f, e.height - 1f)
        return best to e.getOffsetForPosition(lx, ly).coerceIn(0, e.text.length)
    }

    /** Dónde se dibuja la gotita de un sitio (x, arriba del renglón, abajo del renglón). */
    private fun spotXY(p: Pair<Int, Int>): FloatArray? {
        val e = mEdits.getOrNull(p.first) ?: return null
        val lay = e.layout ?: return null
        if (!e.isShown) return null
        val hl = IntArray(2); noteHolder.getLocationInWindow(hl)
        val loc = IntArray(2); e.getLocationInWindow(loc)
        val off = p.second.coerceIn(0, e.text.length)
        val line = lay.getLineForOffset(off)
        val x = lay.getPrimaryHorizontal(off) + e.totalPaddingLeft + (loc[0] - hl[0])
        val ty = lay.getLineTop(line) + e.totalPaddingTop + (loc[1] - hl[1]).toFloat()
        val by = lay.getLineBottom(line) + e.totalPaddingTop + (loc[1] - hl[1]).toFloat()
        return floatArrayOf(x, ty, by)
    }

    /**
     * ✍️ Las dos agarraderas para seleccionar: SE VEN IGUAL que las del teléfono
     * (las mismas gotitas, del color del cursor) pero cruzan renglones, el título y las listas.
     * Al moverlas sale la lupa, que va siguiendo tu dedo.
     */
    @android.annotation.SuppressLint("ViewConstructor")
    inner class HandlesView : View(this@NoteActivity) {
        private var drag = 0          // 0 nada, 1 = la de A, 2 = la de B
        private var lastX = 0f; private var lastY = 0f
        private var grabDx = 0f; private var grabDy = 0f
        private val tint = if (T.light) 0xFF2979FF.toInt() else 0xFFB3E5FC.toInt()
        private fun handle(left: Boolean): android.graphics.drawable.Drawable? = try {
            val a = obtainStyledAttributes(intArrayOf(if (left) android.R.attr.textSelectHandleLeft else android.R.attr.textSelectHandleRight))
            val d = a.getDrawable(0)?.mutate(); a.recycle(); d?.setTint(tint); d
        } catch (_: Throwable) { null }
        private val hl = handle(true); private val hr = handle(false)
        private val mag: android.widget.Magnifier? = if (android.os.Build.VERSION.SDK_INT >= 28) try { android.widget.Magnifier(noteHolder) } catch (_: Throwable) { null } else null
        private val autoScroll = object : Runnable {
            override fun run() {
                if (drag == 0) return
                val edge = dp(60); var dy = 0
                if (lastY < edge) dy = -dp(12) else if (lastY > height - edge) dy = dp(12)
                if (dy != 0) { scroll.scrollBy(0, dy); move() }
                postOnAnimation(this)
            }
        }
        init { isClickable = false }

        /** La agarradera de la izquierda va donde EMPIEZA lo seleccionado; la de la derecha donde TERMINA. */
        private fun pts(): List<Pair<Int, Pair<Int, Int>>> {
            val (s, e) = mRange()
            val first = if (s == mA) 1 else 2
            return listOf(first to s, (3 - first) to e)
        }
        private fun rect(k: Int, pt: Pair<Int, Int>, isStart: Boolean): android.graphics.Rect? {
            val xy = spotXY(pt) ?: return null
            val d = (if (isStart) hl else hr) ?: return null
            val w = d.intrinsicWidth; val h = d.intrinsicHeight
            val x = xy[0].toInt(); val y = xy[2].toInt()
            // la puntita de la gotita toca la letra (como en Android)
            return if (isStart) android.graphics.Rect(x - w * 3 / 4, y, x + w / 4, y + h) else android.graphics.Rect(x - w / 4, y, x + w * 3 / 4, y + h)
        }

        override fun onDraw(c: android.graphics.Canvas) {
            if (multi == null || mLinesMode) return
            for ((idx, p) in pts().withIndex()) {
                val (k, pt) = p
                val r = rect(k, pt, idx == 0) ?: continue
                val d = (if (idx == 0) hl else hr) ?: continue
                d.bounds = r
                d.alpha = if (drag == k) 255 else 235
                d.draw(c)
            }
        }

        private fun hit(x: Float, y: Float): Int {
            for ((idx, p) in pts().withIndex()) {
                val (k, pt) = p
                val r = rect(k, pt, idx == 0) ?: continue
                val big = android.graphics.Rect(r); big.inset(-dp(14), -dp(10))
                if (big.contains(x.toInt(), y.toInt())) {
                    val xy = spotXY(pt)!!
                    grabDx = xy[0] - x; grabDy = (xy[1] + xy[2]) / 2 - y      // dónde agarraste la gotita
                    return k
                }
            }
            return 0
        }

        private fun move() {
            val tx = lastX + grabDx; val ty = lastY + grabDy
            val s = spotAt(tx, ty) ?: return
            if (drag == 1) mA = s else mB = s
            multiPaint(false)
            // 🔍 la lupa sigue al dedo
            spotXY(s)?.let { xy -> try { mag?.show(xy[0], (xy[1] + xy[2]) / 2) } catch (_: Throwable) {} }
            selBar?.let { it.alpha = 0f }
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (multi == null || mLinesMode) return false
                    drag = hit(e.x, e.y); if (drag == 0) return false
                    parent.requestDisallowInterceptTouchEvent(true)
                    lastX = e.x; lastY = e.y
                    performHapticFeedback(android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                    postOnAnimation(autoScroll)
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> if (drag != 0) { lastX = e.x; lastY = e.y; move() }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (drag != 0) {
                    drag = 0; try { mag?.dismiss() } catch (_: Throwable) {}
                    invalidate(); placeSelBar()
                }
            }
            return drag != 0
        }
    }

    /** Dejaste presionada una palabra (o la seleccionaste): empieza la selección que cruza renglones. */
    private fun startFromNative(e: RichEdit, a: Int, b: Int) {
        if (multi != null || ink.drawing) return
        if (allEdits().indexOf(e) < 0) return
        multiStart(e, false, minOf(a, b), maxOf(a, b))
    }

    /** La barrita flotante (como la de Cortar/Copiar del teléfono), arriba de lo seleccionado. */
    private var selBar: LinearLayout? = null

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun multiStart(from: RichEdit?, all: Boolean, a: Int = -1, b: Int = -1, lines: Boolean = false) {
        if (multi != null) {
            if (all) { mLinesMode = false; mA = 0 to 0; mB = (mEdits.size - 1) to mEdits.last().text.length; multiPaint(true); placeSelBar() }
            return
        }
        editor.selectImage(null); editor.flushAll()
        if (spell != null) closeSpell()
        stylePanel?.let { closeStyle() }
        mEdits = allEdits()
        if (mEdits.isEmpty()) return
        mLinesMode = lines; mLines.clear()
        val i = from?.let { mEdits.indexOf(it).coerceAtLeast(0) } ?: 0
        when {
            all || from == null -> { mA = 0 to 0; mB = (mEdits.size - 1) to mEdits.last().text.length }
            a >= 0 && b >= 0 -> { mA = i to a; mB = i to b }
            else -> { mA = i to minOf(from.selectionStart, from.selectionEnd).coerceAtLeast(0); mB = i to maxOf(from.selectionStart, from.selectionEnd) }
        }
        if (lines && from != null) mLines.add(i to lineOf(from, mA.second))
        multi = LinearLayout(this)            // (marca de que la selección está activa)
        // primero se pinta lo seleccionado; después se quita la del teléfono (así no parpadea)
        multiPaint(false)
        RichEdit.toolbar?.finish()
        from?.let { f -> val pos = maxOf(a, b).coerceIn(0, f.text.length); f.setSelection(pos) }
        currentFocus?.let { f -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0) }
        for ((k, e) in mEdits.withIndex()) {
            e.showSoftInputOnFocus = false
            e.isCursorVisible = false
            // ⚠️ el dedo que dejó presionada la palabra TODAVÍA está en la pantalla:
            // ese toque NO quita la selección; si lo mueves, la selección crece (como en el teléfono)
            var mine = false
            e.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { mine = true; return@setOnTouchListener true }
                    android.view.MotionEvent.ACTION_CANCEL -> { mine = false; return@setOnTouchListener true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!mine && !mLinesMode) {
                            val hs = IntArray(2); noteHolder.getLocationOnScreen(hs)
                            spotAt(ev.rawX - hs[0], ev.rawY - hs[1])?.let { sp -> if (sp != mB) { mB = sp; multiPaint(false); handles?.invalidate() } }
                        }
                        return@setOnTouchListener true
                    }
                    android.view.MotionEvent.ACTION_UP -> if (!mine) { placeSelBar(); return@setOnTouchListener true }
                }
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    mine = false
                    val off = (v as EditText).getOffsetForPosition(ev.x, ev.y).coerceIn(0, v.text.length)
                    if (mLinesMode) {
                        val key = k to lineOf(v, off)
                        if (!mLines.remove(key)) mLines.add(key)
                        multiPaint(false); placeSelBar()
                    } else {
                        // tocar el texto = se acaba la selección y escribes ahí (como siempre)
                        multiEnd()
                        v.requestFocus(); v.setSelection(off); showKeyboard(v)
                    }
                }
                true
            }
        }
        val hv = HandlesView(); handles = hv
        noteHolder.addView(hv, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        scroll.viewTreeObserver.addOnScrollChangedListener(scrollInv)
        multiPaint(false)
        hv.post { placeSelBar() }
    }

    /** Pone (o mueve) la barrita flotante arriba de lo seleccionado. */
    private fun placeSelBar() {
        if (multi == null) return
        val bar = selBar ?: buildSelBar().also { selBar = it }
        bar.post {
            if (bar.width == 0) bar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val bw = if (bar.width > 0) bar.width else bar.measuredWidth
            val bh = if (bar.height > 0) bar.height else bar.measuredHeight
            val (s, _) = mRange()
            val first = if (mLinesMode) mLines.firstOrNull()?.let { (i, l) -> mEdits.getOrNull(i)?.let { e -> lineRange(e, l)?.let { i to it.first } } } else s
            val xy = first?.let { spotXY(it) }
            val top = (xy?.get(1) ?: dp(80).toFloat()) - bh - dp(10)
            // si no cabe arriba, va debajo de donde TERMINA lo seleccionado (abajo de la gotita)
            val endXY = if (mLinesMode) null else spotXY(mRange().second)
            val y = if (top < dp(8)) ((endXY ?: xy)?.get(2) ?: 0f) + dp(40) else top
            val x = ((xy?.get(0) ?: 0f) - bw / 2f).coerceIn(dp(8).toFloat(), (noteHolder.width - bw - dp(8)).toFloat().coerceAtLeast(dp(8).toFloat()))
            val first0 = bar.alpha == 0f && bar.translationY == 0f && bar.translationX == 0f
            bar.translationX = x
            if (first0) { bar.translationY = y + dp(10); bar.scaleX = 0.85f; bar.scaleY = 0.85f }
            bar.animate().translationY(y).alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        }
    }

    private fun buildSelBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4))
            background = android.graphics.drawable.LayerDrawable(arrayOf(roundBg(if (T.light) 0xF5FFFFFF.toInt() else 0xF2262629.toInt(), dp(22).toFloat()), Glass.make(this@NoteActivity, 22f, strong = true)))
            elevation = dp(10).toFloat(); alpha = 0f; isClickable = true
        }
        fun item(label: String, act: () -> Unit) = tv(label, 14f, T.text, true).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9)); pressBg(0, 16f)
            setOnClickListener { pop(it); act() }
        }
        fun sep() = View(this).apply { setBackgroundColor(tw(0x22)) }
        val items = listOf(
            item(L.t("cut")) { multiCopy(); multiDelete() },
            item(L.t("copy")) { multiCopy(); selBar?.let { Anim.sparkle(it, 40f, 500) }; multiEnd() },
            item(L.t("selAll")) { mLinesMode = false; mA = 0 to 0; mB = (mEdits.size - 1) to mEdits.last().text.length; multiPaint(true); placeSelBar() },
            item("☰ " + L.t("selLoose")) {
                mLinesMode = !mLinesMode
                if (mLinesMode && mLines.isEmpty()) mPiecesFromRangeToLines()
                multiPaint(true); placeSelBar()
            }.also { mLinesBtn = it },
        )
        for ((k, v) in items.withIndex()) { if (k > 0) bar.addView(sep(), lp(dp(1), dp(22))); bar.addView(v) }
        noteHolder.addView(bar, android.widget.FrameLayout.LayoutParams(WRAP, WRAP))
        return bar
    }

    private val scrollInv = android.view.ViewTreeObserver.OnScrollChangedListener { handles?.invalidate(); placeSelBar() }

    /** Al pasar a "líneas sueltas": los renglones que ya estaban seleccionados quedan marcados. */
    private fun mPiecesFromRangeToLines() {
        val (s, e) = mRange()
        for (i in s.first..e.first) {
            val ed = mEdits.getOrNull(i) ?: continue
            val a = if (i == s.first) lineOf(ed, s.second) else 0
            val b = if (i == e.first) lineOf(ed, e.second) else lineOf(ed, ed.text.length)
            for (l in a..b) mLines.add(i to l)
        }
    }

    private fun multiCopy() {
        val txt = mPieces().joinToString("\n") { (e, a, b) -> e.text.substring(a, b) }
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("nota", txt))
        hint(L.t("selCopied"))
    }

    /** Un estilo a TODO lo seleccionado (solo cambia lo que cambió: no pisa otros estilos). */
    private fun multiStyle(fn: (St) -> St) {
        if (snapPending) snapshot()
        val pieces = mPieces()
        val c = pieces.firstOrNull()?.let { (e, a, _) -> Rich.styleAt(e.text, minOf(a + 1, e.text.length)) } ?: St.PLAIN
        val next = fn(c)
        for ((e, a, b) in pieces) {
            val r = e as? RichEdit ?: continue
            r.setSelection(a, b)
            r.applyStyle { s ->
                var x = s
                if (next.b != c.b) x = x.copy(b = next.b)
                if (next.i != c.i) x = x.copy(i = next.i)
                if (next.u != c.u) x = x.copy(u = next.u)
                if (next.s != c.s) x = x.copy(s = next.s)
                if (next.size != c.size) x = x.copy(size = next.size)
                if (next.color != c.color) x = x.copy(color = next.color)
                if (next.bg != c.bg) x = x.copy(bg = next.bg)
                if (next.o != c.o) x = x.copy(o = next.o)
                x
            }
            r.setSelection(b)
        }
        dirty = true; onDirty()
        multiPaint(false)
        snapshot()
    }

    private fun multiDelete() {
        if (snapPending) snapshot()
        val pieces = mPieces()
        if (pieces.isEmpty()) return
        pieces.forEach { (e, _, _) -> e.animate().alpha(0.35f).setDuration(160).start() }
        root.postDelayed({
            // de atrás para adelante (así no se mueven las posiciones)
            for ((e, a, b) in pieces.sortedWith(compareBy({ -mEdits.indexOf(it.first) }, { -it.second }))) if (b <= e.text.length) e.text.delete(a, b)
            pieces.forEach { (e, _, _) -> e.alpha = 1f }
            multiEnd()
            editor.change { bl -> bl.filterIsInstance<Block.ListB>().forEach { l -> l.items.removeAll { runsPlain(it.runs).isEmpty() } }; null }
            dirty = true; onDirty(); snapshot()
            hint(L.t("selDeleted"))
        }, 170)
    }

    private fun multiEnd() {
        if (multi == null) return
        multi = null
        for (e in mEdits) {
            e.text.getSpans(0, e.text.length, SelS::class.java).forEach { e.text.removeSpan(it) }
            e.setOnTouchListener(null); e.showSoftInputOnFocus = true; e.isCursorVisible = true
        }
        scroll.viewTreeObserver.removeOnScrollChangedListener(scrollInv)
        handles?.let { h -> noteHolder.removeView(h) }
        handles = null; mLinesBtn = null; mLinesMode = false
        selBar?.let { b -> b.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(140).withEndAction { noteHolder.removeView(b) }.start() }
        selBar = null
    }

    private fun allEdits(): List<EditText> {
        val out = arrayListOf<EditText>(title)
        fun walk(v: View) { if (v is EditText) { if (v.visibility == View.VISIBLE) out.add(v); return }; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(editor)
        return out
    }

    private fun startSpell() {
        if (spell != null) return
        editor.selectImage(null)
        editor.flushAll()
        spellBtn.color = T.onPill; spellBtn.background = roundBg(T.pill, dp(20).toFloat())
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(12), dp(12)) }
        spell = p
        formatBar.visibility = View.GONE
        bottom.addView(p, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        p.addView(tv("🔤 " + L.t("spellChecking"), 15f, T.muted))
        p.translationY = dp(50).toFloat(); p.alpha = 0f
        p.animate().translationY(0f).alpha(1f).setDuration(360).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        val edits = allEdits()
        val starts = ArrayList<Int>(); val sb = StringBuilder()
        for (e in edits) { starts.add(sb.length); sb.append(e.text.toString()).append("\n\n") }
        val text = sb.toString()
        val lang = when (L.lang) { "es" -> "es"; "en" -> "en-US"; "pt" -> "pt-BR"; "zh-Hans", "zh-Hant" -> "zh-CN"; else -> L.lang }
        Thread {
            val res = try {
                val c = java.net.URL("https://api.languagetool.org/v2/check").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("Accept", "application/json")
                val body = "language=" + java.net.URLEncoder.encode(lang, "UTF-8") + "&text=" + java.net.URLEncoder.encode(text.take(19000), "UTF-8")
                c.outputStream.use { it.write(body.toByteArray()) }
                JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("matches")
            } catch (_: Exception) { null }
            runOnUiThread {
                if (spell !== p) return@runOnUiThread
                if (res == null) { spellMsg(L.t("spellFail")); return@runOnUiThread }
                spErrs.clear()
                for (k in 0 until res.length()) {
                    val m = res.getJSONObject(k)
                    val off = m.optInt("offset"); val len = m.optInt("length")
                    val ei = starts.indexOfLast { it <= off }; if (ei < 0) continue
                    val e = edits[ei]; val st = off - starts[ei]
                    if (st + len > e.text.length || len <= 0) continue
                    val rep = m.optJSONArray("replacements")
                    val sugs = (0 until minOf(5, rep?.length() ?: 0)).map { rep!!.getJSONObject(it).optString("value") }.filter { it.isNotEmpty() }
                    spErrs.add(SpErr(e, st, len, e.text.substring(st, st + len), m.optString("shortMessage").ifEmpty { m.optString("message") }, sugs))
                }
                for (er in spErrs) er.edit.text.setSpan(SpellS(), er.start, er.start + er.len, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spI = 0
                if (spErrs.isEmpty()) spellMsg("✅ " + L.t("spellNone")) else showSpErr()
            }
        }.start()
    }

    private fun spellMsg(msg: String) {
        val p = spell ?: return
        p.removeAllViews()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(tv(msg, 16f, T.text, true), lp(0, WRAP, 1f))
        r.addView(iconBtn(Icons.CLOSE, 9f) { closeSpell() })
        p.addView(r)
    }

    private fun showSpErr() {
        val p = spell ?: return
        if (spErrs.isEmpty()) { spellMsg("✅ " + L.t("spellDone")); return }
        spI = spI.coerceIn(0, spErrs.size - 1)
        val er = spErrs[spI]
        p.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(L.t("spellTitle") + "  ${spI + 1}/${spErrs.size}", 12f, T.muted, true).apply { letterSpacing = 0.06f }, lp(0, WRAP, 1f))
        head.addView(iconBtn(Icons.CLOSE, 8.5f) { closeSpell() }.apply { layoutParams = lp(dp(36), dp(36)) })
        p.addView(head)
        p.addView(tv(er.word, 20f, 0xFFFF6B6B.toInt(), true).apply { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
        if (er.msg.isNotEmpty()) p.addView(tv(er.msg, 13.5f, T.muted).apply { maxLines = 2; setPadding(0, dp(2), 0, 0) })
        val sugRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        if (er.sugs.isEmpty()) sugRow.addView(tv(L.t("spellNoSug"), 14f, T.muted))
        for ((i, sug) in er.sugs.withIndex()) {
            val chip = tv(sug, 16f, T.onPill, true).apply {
                setPadding(dp(16), dp(9), dp(16), dp(9)); pressBg(T.pill, 20f)
                setOnClickListener { pop(it); fixSpErr(er, sug) }
                scaleX = 0.6f; scaleY = 0.6f; alpha = 0f
                animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(i * 50L).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
            }
            sugRow.addView(chip, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        p.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sugRow) })
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        nav.addView(tv(L.t("spellIgnore"), 14f, T.text).apply { setPadding(dp(14), dp(8), dp(14), dp(8)); pressBg(0, 18f, T.border); setOnClickListener { pop(it); dropSpErr(er) } })
        nav.addView(View(this), lp(0, 1, 1f))
        nav.addView(tv("‹", 22f, T.text, true).apply { gravity = Gravity.CENTER; pressBg(0, 18f, T.border); setOnClickListener { spI = (spI - 1 + spErrs.size) % spErrs.size; showSpErr() } }, lp(dp(44), dp(40)))
        nav.addView(tv("›", 22f, T.text, true).apply { gravity = Gravity.CENTER; pressBg(0, 18f, T.border); setOnClickListener { spI = (spI + 1) % spErrs.size; showSpErr() } }, lp(dp(44), dp(40)).apply { marginStart = dp(8) })
        p.addView(nav)
        // se ve dónde está la palabra
        val e = er.edit
        if (er.start + er.len <= e.text.length) {
            e.requestFocus(); e.setSelection(er.start, er.start + er.len)
            e.post {
                val lay = e.layout ?: return@post
                val y = lay.getLineTop(lay.getLineForOffset(er.start))
                val loc = IntArray(2); e.getLocationInWindow(loc); val sl = IntArray(2); scroll.getLocationInWindow(sl)
                scroll.smoothScrollTo(0, maxOf(0, scroll.scrollY + loc[1] - sl[1] + y - dp(120)))
            }
        }
    }

    private fun fixSpErr(er: SpErr, sug: String) {
        val e = er.edit
        if (er.start + er.len <= e.text.length && e.text.substring(er.start, er.start + er.len) == er.word) {
            e.text.getSpans(er.start, er.start + er.len, SpellS::class.java).forEach { e.text.removeSpan(it) }
            e.text.replace(er.start, er.start + er.len, sug)
            val delta = sug.length - er.len
            for (o in spErrs) if (o !== er && o.edit === e && o.start > er.start) o.start += delta
            onDirty()
        }
        dropSpErr(er)
    }

    private fun dropSpErr(er: SpErr) {
        val e = er.edit
        e.text.getSpans(er.start, minOf(e.text.length, er.start + er.len), SpellS::class.java).forEach { e.text.removeSpan(it) }
        val i = spErrs.indexOf(er); spErrs.remove(er)
        if (i >= 0 && i < spI) spI--
        showSpErr()
    }

    private fun closeSpell() {
        val p = spell ?: return
        spell = null
        for (e in allEdits()) e.text.getSpans(0, e.text.length, SpellS::class.java).forEach { e.text.removeSpan(it) }
        spErrs.clear()
        spellBtn.color = T.text; spellBtn.background = null
        p.animate().translationY(dp(40).toFloat()).alpha(0f).setDuration(200).withEndAction {
            bottom.removeView(p); if (imgPanel == null && !ink.drawing && rec == null) formatBar.visibility = View.VISIBLE
        }.start()
    }

    override fun finish() {
        if (closing) return
        if (rec != null) stopRecording(true)
        save()
        if (!::root.isInitialized || !root.isAttachedToWindow) { super.finish(); return }
        closing = true
        currentFocus?.let { v -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }
        reveal(false) { super.finish(); overridePendingTransition(0, 0) }
    }
}
