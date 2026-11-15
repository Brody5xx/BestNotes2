package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.MotionEvent
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/* ============================================================
   Editor.kt — el editor de la nota, 100% nativo.
   La nota se dibuja como bloques uno debajo del otro:
     TextoView  → una caja de texto con estilos
     ListaView  → checklist / viñetas / números (un renglón = una cajita)
     FotoView   → la foto (en la parte 2 se podrá mover, girar, recortar…)
     MediaView  → audio o video (en la parte 3 se reproduce aquí mismo)
   ============================================================ */

/** Fotos ya cargadas (para no leerlas otra vez del disco). */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    /** Carga la foto [file] a un ancho de unos [w] px, en segundo plano. */
    fun load(file: String, w: Int, into: (Bitmap?) -> Unit) {
        val key = "$file@$w"
        cache.get(key)?.let { into(it); return }
        Loader.pool.execute {
            val bm = decode(File(Store.mediaDir, file), w)
            if (bm != null) cache.put(key, bm)
            Store.main.post { into(bm) }
        }
    }

    fun decode(f: File, w: Int): Bitmap? {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= w) s *= 2
        return try { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) } catch (_: OutOfMemoryError) { null }
    }
}

object Loader { val pool = java.util.concurrent.Executors.newFixedThreadPool(2) }

interface BlockView {
    val block: Block
    val view: View
    /** Pasa lo que hay en pantalla al bloque. */
    fun flush() {}
    fun focusAt(item: Int, offset: Int) {}
    fun edits(): List<RichEdit> = emptyList()
    fun highlight(q: String?): Int = -1
}

@SuppressLint("ViewConstructor")
class EditorView(ctx: Context, val host: Host) : LinearLayout(ctx) {
    interface Host {
        fun onDirty()
        fun onFocusEdit(e: RichEdit?)
        fun askDone(onYes: () -> Unit)
        fun openMedia(file: String, mime: String)
        fun noteTitle(): String
        fun cropImage(f: FotoView)
        fun rotateImage(f: FotoView)
        fun drawOnImage(f: FotoView)
        /** La cruz roja al mover una foto (x, y en la ventana; -1 = esconder). */
        fun showGuides(x: Float, y: Float, snap: String, label: String?)
        fun hint(msg: String)
        /** Se seleccionó (o se soltó) una foto: muestra el panel de la foto. */
        fun onImageSelected(f: FotoView?)
        fun onImageChanged(f: FotoView)
    }

    var blocks: MutableList<Block> = ArrayList()
        private set
    val views = ArrayList<BlockView>()
    var focused: RichEdit? = null
        private set
    var searchQ: String? = null
    var readOnly = false

    init {
        orientation = VERTICAL
        setPadding(dp(18), 0, dp(18), dp(120))
    }

    fun setBlocks(b: MutableList<Block>) {
        blocks = normalize(b)
        rebuild(null)
    }

    fun flushAll() { views.forEach { it.flush() } }

    /** Dónde poner el cursor después de cambiar los bloques. */
    class Spot(val block: Int, val item: Int, val offset: Int, val itemRef: Item? = null)

    fun rebuild(spot: Spot?, select: Block? = null) {
        removeAllViews(); views.clear()
        if (selected != null) { selected = null; host.onImageSelected(null) }
        for (b in blocks) {
            val v: BlockView = when (b) {
                is Block.Text -> TextoView(this, b)
                is Block.ListB -> ListaView(this, b)
                is Block.Img -> FotoView(this, b)
                is Block.Media -> if (b.kind == "video") VideoCard(this, b) else MediaView(this, b)
            }
            views.add(v); addView(v.view)
        }
        if (searchQ != null) views.forEach { it.highlight(searchQ) }
        if (select != null) (views.firstOrNull { it.block === select } as? FotoView)?.let { selectImage(it) }
        if (spot != null) post {
            val ref = spot.itemRef
            val lv = if (ref != null) views.firstOrNull { it.block is Block.ListB && (it.block as Block.ListB).items.any { x -> x === ref } } else null
            if (lv != null) lv.focusAt((lv.block as Block.ListB).items.indexOfFirst { x -> x === ref }, spot.offset)
            else views.getOrNull(spot.block)?.focusAt(spot.item, spot.offset)
        }
    }

    fun indexOf(v: BlockView) = views.indexOf(v)

    internal fun wire(e: RichEdit) {
        e.isEnabled = !readOnly
        e.onEdit = { host.onDirty() }
        e.setOnFocusChangeListener { _, has -> if (has) { focused = e; selectImage(null); host.onFocusEdit(e) } }
        e.onCursor = { if (it.hasFocus()) host.onFocusEdit(it) }
    }

    /** Cambia los bloques (después de pasar lo de la pantalla) y vuelve a dibujar. */
    fun change(spot: (MutableList<Block>) -> Spot?) {
        flushAll()
        val s = spot(blocks)
        blocks = normalize(blocks)
        rebuild(s?.let { Spot(minOf(it.block, blocks.size - 1), it.item, it.offset, it.itemRef) })
        host.onDirty()
    }

    fun focusLastText() {
        val v = views.lastOrNull { it is TextoView } ?: return
        v.focusAt(-1, -1)
    }

    /* ---------- convertir renglones en lista y al revés ---------- */

    /** Botón checklist / viñetas / números. */
    fun toggleList(kind: String) {
        val e = focused ?: run { focusLastText(); focused } ?: return
        val owner = views.firstOrNull { it.edits().contains(e) } ?: return
        val bi = views.indexOf(owner)
        if (owner is TextoView) {
            owner.flush()
            val txt = e.text.toString()
            val a = minOf(e.selectionStart, e.selectionEnd).coerceAtLeast(0); val b = maxOf(e.selectionStart, e.selectionEnd).coerceAtLeast(0)
            val ls = txt.lastIndexOf('\n', a - 1) + 1
            val le = txt.indexOf('\n', b).let { if (it < 0) txt.length else it }
            val runs = owner.block.runs
            val before = sliceRuns(runs, 0, maxOf(0, ls - 1))
            val mid = sliceRuns(runs, ls, le)
            val after = sliceRuns(runs, minOf(txt.length, le + 1), txt.length)
            val items = ArrayList<Item>()
            var cur = ArrayList<Run>()
            for (r in mid) {
                val parts = r.text.split('\n')
                parts.forEachIndexed { k, p -> if (k > 0) { items.add(Item(mergeRuns(cur))); cur = ArrayList() }; if (p.isNotEmpty()) cur.add(Run(p, r.st)) }
            }
            items.add(Item(mergeRuns(cur)))
            val firstIdx = if (ls > 0) bi + 1 else bi
            val off = a - ls
            change { bl ->
                bl.removeAt(bi)
                val add = ArrayList<Block>()
                if (ls > 0) add.add(Block.Text(mergeRuns(before)))
                add.add(Block.ListB(kind, items))
                if (le < txt.length) add.add(Block.Text(mergeRuns(after)))
                bl.addAll(bi, add)
                Spot(firstIdx, 0, off, items[0])
            }
            // si quedó pegada a otra lista igual, normalize las juntó: el cursor va al renglón correcto
        } else if (owner is ListaView) {
            val l = owner.block
            if (l.kind != kind) {
                owner.flush()
                val (ii, off) = owner.itemOf(e)
                change { l.kind = kind; Spot(bi, ii, off, l.items.getOrNull(ii)) }
            } else {
                val (ii, off) = owner.itemOf(e)
                itemToText(owner, ii, off)
            }
        }
    }

    /** Un renglón de la lista pasa a ser texto normal (la lista se parte en dos). */
    fun itemToText(owner: ListaView, ii: Int, off: Int) {
        val bi = views.indexOf(owner)
        change { bl ->
            val l = owner.block
            val it = l.items.removeAt(ii)
            val first = Block.ListB(l.kind, ArrayList(l.items.subList(0, ii)))
            val rest = Block.ListB(l.kind, ArrayList(l.items.subList(ii, l.items.size)))
            bl.removeAt(bi)
            val add = ArrayList<Block>()
            if (first.items.isNotEmpty()) add.add(first)
            add.add(Block.Text(it.runs))
            if (rest.items.isNotEmpty()) add.add(rest)
            bl.addAll(bi, add)
            Spot(bi + if (first.items.isNotEmpty()) 1 else 0, -1, off)
        }
    }

    /** Borrar al principio de un texto: se junta con lo de arriba. */
    fun backAtStartOfText(owner: TextoView): Boolean {
        val bi = views.indexOf(owner)
        if (bi <= 0) return false
        val prev = blocks[bi - 1]
        owner.flush()
        val txt = runsPlain(owner.block.runs)
        val firstLineEnd = txt.indexOf('\n').let { if (it < 0) txt.length else it }
        when (prev) {
            is Block.Text -> change { bl ->
                val p = prev; val off = runsPlain(p.runs).length
                p.runs = mergeRuns(p.runs + Run("\n") + owner.block.runs); bl.removeAt(bi); Spot(bi - 1, -1, off)
            }
            is Block.ListB -> change { bl ->
                // el primer renglón del texto se pega al último renglón de la lista
                val lastIdx = prev.items.indexOfLast { !it.done }.let { if (it < 0) prev.items.size - 1 else it }
                val last = prev.items[lastIdx]
                val off = runsPlain(last.runs).length
                last.runs = mergeRuns(last.runs + sliceRuns(owner.block.runs, 0, firstLineEnd))
                val rest = sliceRuns(owner.block.runs, minOf(txt.length, firstLineEnd + 1), txt.length)
                if (firstLineEnd >= txt.length) bl.removeAt(bi) else owner.block.runs = mergeRuns(rest)
                Spot(bi - 1, lastIdx, off, last)
            }
            else -> return false
        }
        return true
    }

    /* ---------- fotos ---------- */
    var selected: FotoView? = null
        private set

    fun selectImage(f: FotoView?) {
        if (selected === f) return
        selected?.setSelected(false)
        selected = f
        host.onImageSelected(f)
        if (f != null) {
            f.setSelected(true)
            (context as? android.app.Activity)?.currentFocus?.let { v ->
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            }
        }
    }

    /** Sube (-1) o baja (+1) un bloque (la foto) un lugar. */
    fun moveBlock(v: BlockView, dir: Int) {
        val i = views.indexOf(v); val j = i + dir
        if (i < 0 || j < 0 || j >= blocks.size) return
        flushAll()
        val b = blocks[i]
        blocks.removeAt(i); blocks.add(j, b)
        blocks = normalize(blocks)
        rebuild(null, b)
        host.onDirty()
    }

    fun insertAfter(v: BlockView, b: Block) {
        val i = views.indexOf(v); if (i < 0) return
        flushAll()
        blocks.add(i + 1, b)
        blocks = normalize(blocks)
        rebuild(null); host.onDirty()
    }

    fun removeBlock(v: BlockView) {
        val i = views.indexOf(v); if (i < 0) return
        flushAll()
        blocks.removeAt(i)
        blocks = normalize(blocks)
        rebuild(null); host.onDirty()
    }

    fun removeImage(f: FotoView) {
        val i = views.indexOf(f); if (i < 0) return
        flushAll()
        val b = f.block
        blocks.removeAt(i)
        if (b.beside.isNotEmpty()) blocks.add(i, Block.Text(b.beside))
        blocks = normalize(blocks)
        rebuild(null); host.onDirty()
    }

    /** Pone [runs] como texto justo debajo de la foto (al dejar de estar "a un lado"). */
    fun afterImage(f: FotoView, runs: List<Run>) {
        val i = views.indexOf(f); if (i < 0) return
        flushAll()
        blocks.add(i + 1, Block.Text(runs.toMutableList()))
        blocks = normalize(blocks)
        rebuild(null, f.block); host.onDirty()
    }

    /** Pone fotos donde está el cursor (parte el texto o la lista en dos). */
    fun insertImages(imgs: List<Block>) {
        if (imgs.isEmpty()) return
        flushAll()
        val e = focused
        val owner = e?.let { ed -> views.firstOrNull { it.edits().contains(ed) } }
        val bi = owner?.let { views.indexOf(it) } ?: -1
        when {
            owner is TextoView -> {
                val runs = owner.block.runs; val txt = runsPlain(runs)
                val c = maxOf(0, e!!.selectionStart).coerceAtMost(txt.length)
                var a = c; var bb = c
                if (a > 0 && txt[a - 1] == '\n') a--                 // sin renglones vacíos de más
                if (bb < txt.length && txt[bb] == '\n') bb++
                val before = Block.Text(mergeRuns(sliceRuns(runs, 0, a)))
                val after = Block.Text(mergeRuns(sliceRuns(runs, bb, txt.length)))
                blocks.removeAt(bi)
                val add = ArrayList<Block>()
                if (a > 0) add.add(before)
                add.addAll(imgs); add.add(after)
                blocks.addAll(bi, add)
                blocks = normalize(blocks)
                rebuild(null)
                post { views.firstOrNull { it.block === after }?.focusAt(-1, 0) }
            }
            owner is ListaView -> {
                val (ii, _) = owner.itemOf(e!!)
                val l = owner.block
                val first = Block.ListB(l.kind, ArrayList(l.items.subList(0, ii + 1)))
                val rest = Block.ListB(l.kind, ArrayList(l.items.subList(ii + 1, l.items.size)))
                blocks.removeAt(bi)
                val add = ArrayList<Block>(); add.add(first); add.addAll(imgs); if (rest.items.isNotEmpty()) add.add(rest)
                blocks.addAll(bi, add)
                blocks = normalize(blocks); rebuild(null)
            }
            owner != null -> { blocks.addAll(bi + 1, imgs); blocks = normalize(blocks); rebuild(null) }
            else -> {
                // sin cursor: al final
                val last = blocks.lastOrNull()
                val at = if (last is Block.Text && runsPlain(last.runs).isEmpty()) blocks.size - 1 else blocks.size
                blocks.addAll(at, imgs); blocks = normalize(blocks); rebuild(null)
            }
        }
        host.onDirty()
    }

    fun highlight(q: String?): Int {
        searchQ = q
        var first = -1
        views.forEachIndexed { i, v -> if (v.highlight(q) >= 0 && first < 0) first = i }
        return first
    }
}

/* ---------- Texto ---------- */
@SuppressLint("ViewConstructor")
class TextoView(val ed: EditorView, override val block: Block.Text) : BlockView {
    val edit = RichEdit(ed.context)
    override val view: View get() = edit

    init {
        edit.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        edit.setRuns(block.runs)
        if (ed.blocks.size == 1) edit.hint = L.t("bodyPh")
        edit.minHeight = edit.dp(36)
        ed.wire(edit)
        edit.onBackAtStart = { ed.backAtStartOfText(this) }
    }

    override fun flush() { block.runs = edit.runs() }
    override fun edits() = listOf(edit)
    override fun focusAt(item: Int, offset: Int) {
        edit.requestFocus()
        val o = if (offset < 0) edit.length() else offset.coerceIn(0, edit.length())
        edit.setSelection(o)
        showKeyboard(edit)
    }
    override fun highlight(q: String?) = Rich.highlight(edit.text, q)
}

fun showKeyboard(v: View) {
    v.post {
        val im = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        im.showSoftInput(v, 0)
    }
}

/* ---------- Listas (checklist, viñetas, números) ---------- */
@SuppressLint("ViewConstructor")
class ListaView(val ed: EditorView, override val block: Block.ListB) : BlockView {
    private val box = LinearLayout(ed.context).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = box
    /** Renglón en pantalla → su Item. */
    private val rows = ArrayList<Pair<Item, RichEdit>>()
    private var doneOpen = true
    private fun dp(v: Int) = ed.dp(v)

    init { draw(null) }

    fun itemOf(e: RichEdit): Pair<Int, Int> {
        val r = rows.firstOrNull { it.second === e } ?: return 0 to 0
        return block.items.indexOf(r.first) to maxOf(0, e.selectionStart)
    }

    override fun flush() { rows.forEach { (it, e) -> it.runs = e.runs() } }
    override fun edits() = rows.map { it.second }

    override fun focusAt(item: Int, offset: Int) {
        val it = block.items.getOrNull(if (item < 0) block.items.size - 1 else item) ?: return
        val e = rows.firstOrNull { r -> r.first === it }?.second ?: return
        e.requestFocus()
        e.setSelection(if (offset < 0) e.length() else offset.coerceIn(0, e.length()))
        showKeyboard(e)
    }

    override fun highlight(q: String?): Int {
        var f = -1
        rows.forEach { (_, e) -> val p = Rich.highlight(e.text, q); if (p >= 0 && f < 0) f = p }
        return f
    }

    /** Dibuja los renglones: primero lo que falta y abajo "Completados". */
    fun draw(focus: Item?, offset: Int = 0) {
        box.removeAllViews(); rows.clear()
        val todo = block.items.filter { !it.done }
        val done = block.items.filter { it.done }
        var n = 0
        for (it in todo) { n++; box.addView(row(it, n)) }
        if (block.kind == "check" && done.isNotEmpty()) {
            val head = LinearLayout(ed.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
                setOnClickListener { flush(); doneOpen = !doneOpen; draw(null) }
            }
            head.addView(View(ed.context).apply { setBackgroundColor(T.border) }, lp(0, dp(1), 1f))
            head.addView(ed.context.tv((if (doneOpen) "▾ " else "▸ ") + L.t("completed", "n" to done.size), 13f, T.muted).apply { setPadding(dp(10), 0, dp(10), 0) })
            head.addView(View(ed.context).apply { setBackgroundColor(T.border) }, lp(0, dp(1), 1f))
            box.addView(head)
            if (doneOpen) for (it in done) box.addView(row(it, 0))
        }
        if (focus != null) box.post { focusAt(block.items.indexOf(focus), offset) }
    }

    private fun row(it: Item, num: Int): View {
        val ctx = ed.context
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val mark = Mark(ctx, block.kind, it.done, num)
        r.addView(mark, lp(dp(34), dp(34)))
        val e = RichEdit(ctx)
        e.setRuns(it.runs)
        if (it.done) { e.setTextColor(T.muted); e.paintFlags = e.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
        e.setPadding(0, dp(5), 0, dp(5))
        r.addView(e, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ed.wire(e)
        rows.add(it to e)
        if (block.kind == "check") mark.setOnClickListener { _ -> toggle(it) }
        e.onEnter = { ed2, at -> enter(it, ed2, at); true }
        val item = it
        e.onBackAtStart = { back(item, e) }
        return r
    }

    private fun toggle(it: Item) {
        if (ed.readOnly) return
        flush()
        if (!it.done) {
            ed.host.askDone {
                val undone = block.items.filter { x -> !x.done }
                it.pos = undone.indexOf(it)
                it.done = true
                block.items.remove(it); block.items.add(it)
                // al tocar la casilla se esconde el teclado
                (ed.context as? android.app.Activity)?.currentFocus?.let { f ->
                    (ed.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0)
                    f.clearFocus()
                }
                draw(null); ed.host.onDirty()
            }
        } else {
            it.done = false
            block.items.remove(it)
            val undone = block.items.count { x -> !x.done }
            block.items.add(minOf(maxOf(0, it.pos), undone), it)
            it.pos = -1
            draw(null); ed.host.onDirty()
        }
    }

    /** Enter: lo que está después del cursor pasa a un renglón nuevo. En un renglón vacío: sale de la lista. */
    private fun enter(it: Item, e: RichEdit, at: Int) {
        val all = e.runs()
        val txt = runsPlain(all)
        val before = sliceRuns(all, 0, at)
        val after = sliceRuns(all, at + 1, txt.length)
        if (runsPlain(before).isBlank() && runsPlain(after).isBlank()) {
            // renglón vacío + Enter = termina la lista
            it.runs = ArrayList()
            e.setRuns(it.runs)
            flush()
            ed.itemToText(this, block.items.indexOf(it), 0)
            return
        }
        flush()
        it.runs = mergeRuns(before)
        val n = Item(mergeRuns(after), it.done)
        block.items.add(block.items.indexOf(it) + 1, n)
        val rowView = e.parent as? View
        if (block.kind == "ol" || it.done || rowView == null || rowView.parent !== box) { draw(n, 0); ed.host.onDirty(); return }
        // Renglón nuevo sin volver a dibujar toda la lista (así el teclado no parpadea)
        e.setRuns(it.runs)
        val nr = row(n, 0)
        box.addView(nr, box.indexOfChild(rowView) + 1)
        rows.firstOrNull { r -> r.first === n }?.second?.let { ne -> ne.requestFocus(); ne.setSelection(0) }
        ed.host.onDirty()
    }

    /** Borrar al principio: renglón vacío se quita; con texto, se vuelve texto normal. */
    private fun back(it: Item, e: RichEdit): Boolean {
        flush()
        val idx = block.items.indexOf(it)
        if (runsPlain(it.runs).isEmpty() && block.items.size > 1) {
            // se borra y el cursor va al final del renglón de arriba
            val prev = block.items.getOrNull(idx - 1)
            block.items.removeAt(idx)
            val prevEdit = prev?.let { p -> rows.firstOrNull { r -> r.first === p }?.second }
            val rowView = e.parent as? View
            if (prevEdit != null && block.kind != "ol" && !it.done && rowView != null && rowView.parent === box) {
                // se pasa el cursor arriba ANTES de quitar el renglón (así el teclado no se cierra)
                prevEdit.requestFocus(); prevEdit.setSelection(prevEdit.length())
                box.removeView(rowView); rows.removeAll { r -> r.first === it }
                ed.host.onDirty(); return true
            }
            if (prev != null) { draw(prev, -1); ed.host.onDirty(); return true }
            draw(null); ed.host.onDirty()
            return true
        }
        ed.itemToText(this, idx, 0)
        return true
    }

    /** La casilla, la viñeta o el número. */
    @SuppressLint("ViewConstructor")
    class Mark(ctx: Context, val kind: String, val done: Boolean, val num: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        init { if (kind == "check") isClickable = true }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = dp(17).toFloat()
            when (kind) {
                "check" -> {
                    val s = dp(9).toFloat()
                    r.set(cx - s, cy - s, cx + s, cy + s)
                    if (done) {
                        p.style = Paint.Style.FILL; p.color = T.accent
                        c.drawRoundRect(r, dp(5).toFloat(), dp(5).toFloat(), p)
                        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2.2f).toFloat(); p.color = 0xFFFFFFFF.toInt(); p.strokeCap = Paint.Cap.ROUND
                        c.drawLine(cx - s * 0.45f, cy, cx - s * 0.1f, cy + s * 0.4f, p)
                        c.drawLine(cx - s * 0.1f, cy + s * 0.4f, cx + s * 0.5f, cy - s * 0.35f, p)
                    } else {
                        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = T.muted
                        c.drawRoundRect(r, dp(5).toFloat(), dp(5).toFloat(), p)
                    }
                }
                "ul" -> { p.style = Paint.Style.FILL; p.color = T.text; c.drawCircle(cx, cy, dp(3).toFloat(), p) }
                else -> {
                    p.style = Paint.Style.FILL; p.color = T.muted; p.textSize = dp(16).toFloat(); p.textAlign = Paint.Align.CENTER
                    c.drawText("$num.", cx, cy + dp(6), p)
                }
            }
        }
    }
}

/* ---------- Foto ---------- */
/**
 * Una foto en la nota (como en Best Notes). Tócala para seleccionarla:
 *  - brilla con un borde azul y abajo sale el panel de la foto
 *  - un dedo: la mueves a donde quieras y el texto se acomoda EN VIVO
 *    (cruz roja + cuántos px se movió); pegada a una orilla = texto al lado
 *  - dos dedos: más grande / más chica (en vivo) y girarla
 *  - "Anclar": ya no se mueve sin querer
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FotoView(val ed: EditorView, override val block: Block.Img) : BlockView {
    private val ctx = ed.context
    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    override val view: View get() = root
    private val box = FrameLayout(ctx)                  // la foto (con su sello)
    private val img = ImageView(ctx)
    private val stampTv = TextView(ctx)
    private val edit = RichEdit(ctx)                    // el texto de al lado
    private var anchorPill: TextView? = null
    var selected = false
        private set
    private var loadedW = 0
    private var glow: GlowBorder? = null
    /** El brillo sigue a la foto (también mientras la mueves). */
    private fun placeGlow() {
        val g = glow ?: return
        val pad = dp(10)
        g.setBounds(box.left - pad, box.top - pad + box.translationY.toInt(), box.right + pad, box.bottom + pad + box.translationY.toInt())
    }

    // mientras arrastras: dónde va (en vivo)
    private var dragLeft = -1f
    private var liveSide: String? = null

    private fun dp(v: Int) = ed.dp(v)
    private fun ratio(): Float = if (block.w > 0 && block.h > 0) block.h.toFloat() / block.w
        else img.drawable?.let { d -> if (d.intrinsicWidth > 0) d.intrinsicHeight.toFloat() / d.intrinsicWidth else 0.75f } ?: 0.75f
    private fun side() = liveSide ?: block.side

    /** El tamaño que ocupa la foto ya girada (ancho, alto). */
    private fun rotBox(pw: Int, ph: Int): Pair<Int, Int> {
        val a = Math.toRadians(block.rot.toDouble()); val c = Math.abs(Math.cos(a)); val s = Math.abs(Math.sin(a))
        return (pw * c + ph * s).toInt() to (pw * s + ph * c).toInt()
    }

    /** Mientras giras con dos dedos, el panel de abajo se actualiza (sin hacerlo mil veces). */
    private var panelPending = false
    private fun panelLater() {
        if (panelPending) return
        panelPending = true
        flow.postDelayed({ panelPending = false; if (selected) ed.host.onImageChanged(this) }, 140)
    }

    /** La fila: acomoda la foto y el texto (al lado o debajo) cada vez que algo cambia. */
    private val flow = object : ViewGroup(ctx) {
        // el brillo se pinta ENCIMA de la foto (y un poquito por fuera)
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val g = glow ?: return
            // el brillo gira junto con la foto
            canvas.save()
            canvas.rotate(box.rotation, (box.left + box.right) / 2f, (box.top + box.bottom) / 2f + box.translationY)
            g.draw(canvas)
            canvas.restore()
        }
        override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === glow || super.verifyDrawable(who)
        override fun onMeasure(ws: Int, hs: Int) {
            val W = MeasureSpec.getSize(ws)
            var pw = (W * block.pct / 100f).toInt().coerceIn(dp(40), W)
            var ph = (pw * ratio()).toInt()
            // girada ocupa más lugar: si no cabe a lo ancho, se ve un poquito más chica
            val (bw0, _) = rotBox(pw, ph)
            if (bw0 > W) { val k = W.toFloat() / bw0; pw = (pw * k).toInt().coerceAtLeast(dp(30)); ph = (pw * ratio()).toInt() }
            box.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY))
            val (bw, bh) = rotBox(pw, ph)
            val top = dp(block.y.toInt())
            var h = top + bh
            val gap = dp(12)
            if (edit.visibility != View.GONE) {
                if (side().isNotEmpty()) {
                    edit.measure(MeasureSpec.makeMeasureSpec(maxOf(dp(60), W - bw - gap), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    h = maxOf(h, edit.measuredHeight)
                } else {
                    edit.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    h += gap / 2 + edit.measuredHeight
                }
            }
            setMeasuredDimension(W, h)
            if (pw > loadedW * 1.3f || loadedW == 0) { loadedW = pw; post { loadImg(pw) } }
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val W = r - l; val pw = box.measuredWidth; val ph = box.measuredHeight
            val (bw, bh) = rotBox(pw, ph)
            val ox = (bw - pw) / 2; val oy = (bh - ph) / 2       // lo que sobresale al estar girada
            val top = dp(block.y.toInt())
            val left = when {
                dragLeft >= 0 -> dragLeft.toInt()
                side() == "l" -> ox
                side() == "r" -> W - pw - ox
                else -> (W * block.x / 100f).toInt()
            }.coerceIn(minOf(ox, maxOf(0, W - pw - ox)), maxOf(ox, W - pw - ox))
            box.layout(left, top + oy, left + pw, top + oy + ph)
            placeGlow()
            if (edit.visibility != View.GONE) {
                val gap = dp(12)
                when (side()) {
                    "l" -> { val x = left + pw + ox + gap; edit.layout(x, 0, x + edit.measuredWidth, edit.measuredHeight) }
                    "r" -> { val x = maxOf(0, left - ox - gap - edit.measuredWidth); edit.layout(x, 0, x + edit.measuredWidth, edit.measuredHeight) }
                    else -> { val y = top + bh + gap / 2; edit.layout(0, y, W, y + edit.measuredHeight) }
                }
            }
        }
    }

    init {
        root.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) }
        flow.clipChildren = false
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        box.background = roundBg(tw(0x10), dp(12).toFloat())
        box.clipToOutline = true
        box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Sello: franja oscura abajo con la fecha, la hora y el sitio
        stampTv.setTextColor(0xFFFFFFFF.toInt()); stampTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
        stampTv.typeface = Typeface.DEFAULT_BOLD; stampTv.gravity = Gravity.END
        stampTv.maxLines = 2
        stampTv.setShadowLayer(3f, 0f, 1f, 0xFF000000.toInt())
        stampTv.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x9E000000.toInt()))
        stampTv.setPadding(dp(8), dp(14), dp(8), dp(5))
        box.addView(stampTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        edit.setRuns(block.beside); edit.hint = L.t("besidePh"); edit.minHeight = dp(70)
        ed.wire(edit)
        flow.addView(box); flow.addView(edit)
        root.addView(flow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paint()
        box.setOnTouchListener(Touch())
    }

    private fun loadImg(pw: Int) {
        Thumbs.load(block.file, pw) { bm -> if (bm != null) img.setImageBitmap(bm) else img.setImageDrawable(null) }
    }

    /** Muestra el sello, el giro y si hay texto al lado. */
    private fun paint() {
        stampTv.text = block.stamp
        stampTv.visibility = if (block.stamp.isEmpty()) View.GONE else View.VISIBLE
        box.rotation = block.rot
        val hasText = edit.length() > 0
        edit.visibility = if (side().isNotEmpty() || hasText) View.VISIBLE else View.GONE
    }

    /** Cambio con animación suave (tamaño, lado, giro…). */
    private fun animateChange() {
        android.transition.TransitionManager.beginDelayedTransition(ed, android.transition.ChangeBounds().setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()))
    }

    fun relayout() { paint(); flow.requestLayout() }

    override fun flush() { block.beside = edit.runs() }
    override fun edits() = if (edit.visibility == View.VISIBLE) listOf(edit) else emptyList()
    override fun focusAt(item: Int, offset: Int) {
        edit.visibility = View.VISIBLE
        edit.requestFocus(); edit.setSelection(if (offset < 0) edit.length() else offset.coerceIn(0, edit.length())); showKeyboard(edit)
    }
    override fun highlight(q: String?): Int = Rich.highlight(edit.text, q)

    /* ---------- seleccionar (con brillo azul, como en Best Notes) ---------- */
    fun setSelected(on: Boolean) {
        if (selected == on) return
        selected = on
        if (on) {
            glow = GlowBorder(ctx.resources.displayMetrics.density, 12f).also { g -> g.callback = flow; g.start() }
            flow.clipChildren = false; (root.parent as? ViewGroup)?.clipChildren = false
            flow.invalidate()
            placeGlow()
            box.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { box.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start() }.start()
            showAnchor()
        } else {
            glow?.let { it.stop(); it.callback = null }; glow = null; flow.invalidate()
            anchorPill?.let { p -> p.animate().alpha(0f).setDuration(120).withEndAction { root.removeView(p) }.start() }; anchorPill = null
        }
    }

    /** El botón "Anclar" debajo de la foto. */
    private fun showAnchor() {
        anchorPill?.let { root.removeView(it) }
        val t = ctx.tv((if (block.pin) "⚓ " + L.t("anchored") else "⚓ " + L.t("imgPin")), 14f, if (block.pin) T.onPill else T.text, true)
        t.setPadding(dp(16), dp(7), dp(16), dp(7))
        if (block.pin) t.pressBg(T.text, 18f) else t.background = Glass.make(ctx, 18f)
        t.setOnClickListener { setPin(!block.pin) }
        anchorPill = t
        root.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        t.alpha = 0f; t.translationY = -dp(6).toFloat(); t.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    fun setPin(on: Boolean) { block.pin = on; ed.host.onDirty(); showAnchor(); ed.host.onImageChanged(this) }

    fun setPct(p: Float) {
        animateChange()
        block.pct = p.coerceIn(10f, if (block.side.isEmpty()) 100f else 75f)
        block.x = block.x.coerceIn(0f, 100f - block.pct)
        relayout(); ed.host.onDirty(); ed.host.onImageChanged(this)
    }

    fun setRot(r: Float) {
        block.rot = r
        flow.requestLayout()
        box.animate().rotation(r).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator())
            .setUpdateListener { flow.invalidate() }.start()
        ed.host.onDirty(); ed.host.onImageChanged(this)
    }

    fun setSide(side: String) {
        if (block.side == side) return
        flush()
        animateChange()
        if (side.isNotEmpty() && block.pct > 60f) block.pct = 45f
        block.side = side
        block.x = if (side == "r") 100f - block.pct else if (side == "l") 0f else (100f - block.pct) / 2
        relayout(); ed.host.onDirty(); ed.host.onImageChanged(this)
    }

    fun toggleStamp() {
        if (block.stamp.isEmpty()) {
            block.stamp = Photos.stampText(if (block.taken > 0) block.taken else System.currentTimeMillis(), ed.host.noteTitle())
            Photos.setStampOn(ctx, true)
        } else { block.stamp = ""; Photos.setStampOn(ctx, false) }
        stampTv.alpha = 0f; paint(); stampTv.animate().alpha(1f).setDuration(200).start()
        ed.host.onDirty(); ed.host.onImageChanged(this)
    }

    /** La foto cambió de archivo (girar o recortar). */
    fun reload() { loadedW = 0; img.setImageDrawable(null); flow.requestLayout() }

    /* ---------- tocar, mover, agrandar y girar ---------- */
    private inner class Touch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var moved = false; private var multi = false
        private var startDist = 0f; private var startAng = 0f; private var startPct = 0f; private var startRot = 0f
        private var baseLeft = 0f; private var baseY = 0f
        private var snap = ""
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

        private fun dist(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
        private fun ang(e: MotionEvent) = Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

        private fun guides(label: String?) {
            val loc = IntArray(2); box.getLocationInWindow(loc)
            ed.host.showGuides(loc[0] + box.width / 2f, loc[1] + box.height / 2f, snap, label)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; moved = false; multi = false; snap = ""
                    baseLeft = box.left.toFloat(); baseY = block.y
                    if (selected) v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (selected && !block.pin && e.pointerCount == 2) {
                    multi = true; startDist = dist(e); startAng = ang(e); startPct = block.pct; startRot = block.rot
                    dragLeft = -1f
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!selected) return true
                    if (block.pin) {
                        if (!moved && (Math.abs(e.rawX - downX) > slop * 2 || Math.abs(e.rawY - downY) > slop * 2)) {
                            moved = true; ed.host.hint(L.t("anchoredHint"))
                            anchorPill?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(120)?.withEndAction { anchorPill?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start() }?.start()
                        }
                        return true
                    }
                    if (multi && e.pointerCount >= 2) {
                        // dos dedos: tamaño EN VIVO (el texto se acomoda) y giro
                        val k = (dist(e) / maxOf(1f, startDist)).coerceIn(0.25f, 4f)
                        block.pct = (startPct * k).coerceIn(10f, if (side().isEmpty()) 100f else 75f)
                        var r = startRot + (ang(e) - startAng)
                        while (r > 180) r -= 360; while (r < -180) r += 360
                        if (Math.abs(r) < 4) r = 0f                 // se endereza sola cerca de 0°
                        block.rot = r; box.rotation = r
                        moved = true
                        flow.requestLayout(); flow.invalidate(); panelLater()
                        ed.host.showGuides(-1f, -1f, "", "${block.pct.toInt()}%  ↻ ${r.toInt()}°")
                        return true
                    }
                    if (multi) return true
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && Math.abs(dx) < slop && Math.abs(dy) < slop) return true
                    moved = true
                    val W = flow.width.toFloat(); val pw = box.width.toFloat()
                    val ox = (rotBox(box.width, box.height).first - box.width) / 2f    // girada sobresale un poco
                    // a los lados, "pegando" en la orilla izquierda, el centro y la orilla derecha
                    var left = (baseLeft + dx).coerceIn(minOf(ox, W - pw - ox).coerceAtLeast(0f), maxOf(ox, W - pw - ox))
                    snap = ""
                    val near = dp(14).toFloat()
                    when {
                        left < ox + near -> { left = ox; snap = "l" }
                        Math.abs(left - (W - pw) / 2) < near -> { left = (W - pw) / 2; snap = "c" }
                        left > W - pw - ox - near -> { left = W - pw - ox; snap = "r" }
                    }
                    // el texto se acomoda al lado EN VIVO cuando la foto toca una orilla
                    val want = when { snap == "l" && block.pct <= 75 -> "l"; snap == "r" && block.pct <= 75 -> "r"; else -> "" }
                    if (want != side()) { liveSide = want; paint() }
                    dragLeft = left
                    val d = ctx.resources.displayMetrics.density
                    block.y = (baseY + dy / d).coerceAtLeast(0f)
                    // hacia arriba más allá de su lugar: se ve que se va a subir
                    box.translationY = if (baseY + dy / d < 0) dy + baseY * d else 0f
                    flow.requestLayout(); placeGlow()
                    val sideTxt = when (want) { "l" -> "  ·  " + L.t("textRight"); "r" -> "  ·  " + L.t("textLeft"); else -> "" }
                    guides("${if (left - baseLeft >= 0) "+" else ""}${((left - baseLeft) / d).toInt()} px  ↕ ${(dy / d).toInt()} px$sideTxt")
                }
                MotionEvent.ACTION_UP -> {
                    ed.host.showGuides(-1f, -1f, "", null)
                    if (!moved) { ed.selectImage(if (selected) null else this@FotoView); return true }
                    if (multi) { ed.host.onDirty(); ed.host.onImageChanged(this@FotoView); return true }
                    finishDrag()
                }
                MotionEvent.ACTION_CANCEL -> {
                    ed.host.showGuides(-1f, -1f, "", null)
                    dragLeft = -1f; liveSide = null; block.y = baseY; box.translationY = 0f
                    animateChange(); relayout()
                }
            }
            return true
        }

        /** Al soltar: se guarda dónde quedó. */
        private fun finishDrag() {
            val W = flow.width.toFloat()
            val up = box.translationY < -dp(40)
            box.animate().translationY(0f).setDuration(150).setUpdateListener { placeGlow() }.start()
            val want = liveSide ?: block.side
            val left = dragLeft
            dragLeft = -1f; liveSide = null
            if (up) { block.y = 0f; ed.moveBlock(this@FotoView, -1); ed.host.hint(L.t("photoMoved")); return }
            if (block.y > 220f) { block.y = 0f; ed.moveBlock(this@FotoView, 1); ed.host.hint(L.t("photoMoved")); return }
            block.side = want
            if (want.isEmpty() && W > 0 && left >= 0) block.x = (left / W * 100f).coerceIn(0f, 100f - block.pct)
            else if (want == "l") block.x = 0f else if (want == "r") block.x = 100f - block.pct
            relayout(); ed.host.onDirty(); ed.host.onImageChanged(this@FotoView)
            ed.host.hint(if (want.isNotEmpty()) L.t("writeBeside") else L.t("photoMoved"))
        }
    }
}

/* ---------- Audio / video ---------- */
/** Botones de abajo de un audio o video: subir, bajar, abrir con…, quitar (y en video: sin sonido, achicar). */
internal fun mediaOptions(ed: EditorView, v: BlockView, block: Block.Media, extra: List<Pair<String, () -> Unit>>) {
    val items = ArrayList<Pair<String, () -> Unit>>()
    items.addAll(extra)
    items.add(L.t("moveUp") to { ed.moveBlock(v, -1) })
    items.add(L.t("moveDown") to { ed.moveBlock(v, 1) })
    items.add(L.t("openWith") to {
        val ext = block.file.substringAfterLast('.')
        val isV = block.kind == "video"
        ed.host.openMedia(block.file, when (ext) { "mp4", "mov" -> "video/mp4"; "webm" -> "video/webm"; "weba" -> "audio/webm"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; "3gp" -> if (isV) "video/3gpp" else "audio/3gpp"; else -> if (isV) "video/*" else "audio/mp4" })
    })
    items.add(L.t("remove") to { ed.context.confirm(L.t("remove"), null, L.t("remove"), true) { ed.removeBlock(v) } })
    ed.context.dialog().setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
}

/** 🎙️ Nota de voz: ▶ escuchar aquí mismo, barra para adelantar, velocidad 1x / 1.5x / 2x. */
@SuppressLint("ViewConstructor")
class MediaView(val ed: EditorView, override val block: Block.Media) : BlockView {
    private val ctx = ed.context
    private val row = LinearLayout(ctx)
    override val view: View get() = row
    private val play = IconView(ctx, MoreIcons.PLAY, 8.5f)
    private val seek = android.widget.SeekBar(ctx)
    private val time = ctx.tv("", 12.5f, T.muted)
    private val speedTv = ctx.tv("", 12.5f, T.text, true)
    private val tick = object : Runnable { override fun run() { paint(); if (AudioPlayer.isPlaying(block.file)) row.postDelayed(this, 250) } }
    private val listener: () -> Unit = { paint(); if (AudioPlayer.isPlaying(block.file)) { row.removeCallbacks(tick); row.post(tick) } }

    init {
        row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(6), ctx.dp(6))
        row.background = roundBg(T.card, ctx.dp(18).toFloat(), T.border, ctx.dp(1))
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6); bottomMargin = ctx.dp(6) }
        play.color = T.onPill; play.pressBg(T.text, 20f)
        play.setOnClickListener { AudioPlayer.toggle(block.file) }
        row.addView(play, lp(ctx.dp(40), ctx.dp(40)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && block.dur > 0) { if (AudioPlayer.file != block.file) AudioPlayer.toggle(block.file); AudioPlayer.seek(block.file, (p / 1000.0 * block.dur * 1000).toInt()); paint() }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        col.addView(seek, lp(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(30)))
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(14), 0, ctx.dp(8), 0) }
        info.addView(IconView(ctx, MoreIcons.MIC, 6f).apply { color = T.muted; isClickable = false }, lp(ctx.dp(16), ctx.dp(16)))
        info.addView(time.apply { setPadding(ctx.dp(4), 0, 0, 0) }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(info)
        row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speedTv.setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
        speedTv.setOnClickListener { AudioPlayer.setSpeedNow(when (AudioPlayer.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }) }
        row.addView(speedTv)
        row.addView(ctx.iconBtn(MoreIcons.DOTS_V, 8f, T.muted) { mediaOptions(ed, this, block, listOf(L.t("transcribe") to { transcribe() })) }.apply { layoutParams = lp(ctx.dp(36), ctx.dp(40)) })
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { AudioPlayer.addListener(listener); paint() }
            override fun onViewDetachedFromWindow(v: View) { AudioPlayer.removeListener(listener); row.removeCallbacks(tick) }
        })
        if (block.dur <= 0) Loader.pool.execute { val i = MediaFiles.info(block.file); Store.main.post { if (i.dur > 0) { block.dur = i.dur; block.size = i.size; paint() } } }
        paint()
    }

    /** 🎙️→📝 Pasar el audio a texto (con la IA gratis) y ponerlo debajo. */
    private fun transcribe() {
        val a = ctx as? android.app.Activity ?: return
        ctx.dialog().setTitle(L.t("transcribe")).setMessage(L.t("transcribeNote")).setPositiveButton("Español") { _, _ -> go(a, "es") }
            .setNeutralButton("English") { _, _ -> go(a, "en") }.setNegativeButton(L.t("cancel"), null).show()
    }

    private fun go(a: android.app.Activity, lang: String) {
        if (!Transcriber.available(ctx)) { ctx.dialog().setMessage(L.t("transcribeNo")).setPositiveButton(L.t("ok"), null).show(); return }
        val wait = ctx.dialog().setMessage(L.t("transcribing")).setCancelable(false).show()
        Transcriber.run(ctx, File(Store.mediaDir, block.file), lang) { out ->
            wait.dismiss()
            if (out == null) ctx.toast(L.t("transcribeFail"))
            else ed.insertAfter(this, Block.Text(mutableListOf(Run(out))))
        }
    }

    private fun paint() {
        val playing = AudioPlayer.isPlaying(block.file)
        play.kind = if (playing) MoreIcons.PAUSE else MoreIcons.PLAY; play.invalidate()
        val pos = AudioPlayer.position(block.file) / 1000.0
        if (block.dur > 0) seek.progress = (pos / block.dur * 1000).toInt().coerceIn(0, 1000)
        time.text = (if (pos > 0 || playing) MediaFiles.timeText(pos) + " / " else "") + MediaFiles.timeText(block.dur) +
            if (!File(Store.mediaDir, block.file).exists()) "  ·  " + L.t("fileMissing") else ""
        speedTv.text = (if (AudioPlayer.speed == 1f) "1" else AudioPlayer.speed.toString()) + "x"
    }
}

/** 🎬 Video dentro de la nota: portada, ▶ aquí mismo, pantalla completa, 🔇 sin sonido y Achicar. */
@SuppressLint("ViewConstructor")
class VideoCard(val ed: EditorView, override val block: Block.Media) : BlockView {
    private val ctx = ed.context
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = col
    private val frame = object : FrameLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val r = if (vw > 0 && vh > 0) vh.toFloat() / vw else 9f / 16f
            val hh = minOf((width * r).toInt(), (resources.displayMetrics.heightPixels * 0.7f).toInt())
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY))
        }
    }
    private val thumb = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val playBig = IconView(ctx, MoreIcons.PLAY, 12f)
    private var vv: android.widget.VideoView? = null
    private val label = ctx.tv("", 12.5f, T.muted)
    private var vw = 0; private var vh = 0
    private var busy = false

    init {
        col.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6); bottomMargin = ctx.dp(6) }
        frame.background = roundBg(0xFF000000.toInt(), ctx.dp(16).toFloat(), T.border, ctx.dp(1))
        frame.clipToOutline = true
        frame.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playBig.color = 0xFF16161A.toInt(); playBig.background = roundBg(0xE6FFFFFF.toInt(), ctx.dp(30).toFloat()); playBig.isClickable = false
        frame.addView(playBig, FrameLayout.LayoutParams(ctx.dp(60), ctx.dp(60), Gravity.CENTER))
        frame.setOnClickListener { toggle() }
        col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(4), ctx.dp(2), 0, 0) }
        bar.addView(IconView(ctx, MoreIcons.VIDEO, 6.5f).apply { color = T.muted; isClickable = false }, lp(ctx.dp(18), ctx.dp(18)))
        bar.addView(label.apply { setPadding(ctx.dp(6), 0, 0, 0) }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ctx.iconBtn(MoreIcons.FULL, 7.5f, T.muted) { full() }.apply { layoutParams = lp(ctx.dp(38), ctx.dp(38)) })
        bar.addView(ctx.iconBtn(MoreIcons.DOTS_V, 8f, T.muted) { options() }.apply { layoutParams = lp(ctx.dp(36), ctx.dp(38)) })
        col.addView(bar)
        load()
    }

    private fun load() {
        Loader.pool.execute {
            val i = MediaFiles.info(block.file)
            val ok = MediaFiles.makeThumb(block.file)
            Store.main.post {
                vw = i.w; vh = i.h
                if (i.dur > 0) block.dur = i.dur
                block.size = i.size
                paintLabel(); frame.requestLayout()
                if (ok) Thumbs.load(MediaFiles.thumbName(block.file), ctx.dp(360)) { bm -> thumb.setImageBitmap(bm) }
            }
        }
    }

    private fun paintLabel(extra: String? = null) {
        val parts = ArrayList<String>()
        if (block.dur > 0) parts.add(MediaFiles.timeText(block.dur))
        if (block.size > 0) parts.add(MediaFiles.sizeText(block.size))
        if (block.muted) parts.add(L.t("muted"))
        if (!File(Store.mediaDir, block.file).exists()) parts.add(L.t("fileMissing"))
        if (extra != null) parts.add(extra)
        label.text = parts.joinToString("  ·  ")
    }

    private fun toggle() {
        val v = vv
        if (v == null) {
            AudioPlayer.stop()
            val n = android.widget.VideoView(ctx)
            vv = n
            frame.addView(n, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            n.setVideoPath(File(Store.mediaDir, block.file).path)
            n.setOnPreparedListener { mp -> thumb.visibility = View.GONE; playBig.visibility = View.GONE; mp.start() }
            n.setOnCompletionListener { playBig.visibility = View.VISIBLE; playBig.kind = MoreIcons.PLAY; playBig.invalidate() }
            n.setOnErrorListener { _, _, _ -> stopVideo(); ctx.toast(L.t("cantOpen")); true }
            n.setOnClickListener { toggle() }
            return
        }
        if (v.isPlaying) { v.pause(); playBig.kind = MoreIcons.PLAY; playBig.visibility = View.VISIBLE }
        else { v.start(); playBig.visibility = View.GONE }
        playBig.invalidate()
    }

    private fun stopVideo() {
        vv?.let { it.stopPlayback(); frame.removeView(it) }
        vv = null; thumb.visibility = View.VISIBLE; playBig.visibility = View.VISIBLE; playBig.kind = MoreIcons.PLAY; playBig.invalidate()
    }

    private fun full() {
        stopVideo()
        ed.host.openMedia(block.file, if (block.file.endsWith(".webm")) "video/webm" else "video/mp4")
    }

    private fun options() {
        if (busy) return
        val extra = ArrayList<Pair<String, () -> Unit>>()
        if (!block.muted) extra.add(L.t("mute") to { mute() })
        extra.add(L.t("shrink") to { shrink() })
        mediaOptions(ed, this, block, extra)
    }

    private fun replaceFile(name: String) {
        block.file = name
        val i = MediaFiles.info(name); block.size = i.size; if (i.dur > 0) block.dur = i.dur
        stopVideo(); load(); ed.host.onDirty()
    }

    private fun mute() {
        busy = true; paintLabel(L.t("working"))
        Loader.pool.execute {
            val n = MediaFiles.removeAudio(block.file)
            Store.main.post {
                busy = false
                if (n == null) { ctx.toast(L.t("videoFail")); paintLabel() } else { block.muted = true; replaceFile(n); ctx.toast(L.t("mutedOk")) }
            }
        }
    }

    private fun shrink() {
        busy = true; paintLabel(L.t("shrinking", "n" to 0))
        val before = block.size
        Shrink.run(ctx, block.file, vw, vh, { p -> paintLabel(L.t("shrinking", "n" to p)) }) { n ->
            busy = false
            if (n == null) { ctx.toast(L.t("videoFail")); paintLabel(); return@run }
            val after = File(Store.mediaDir, n).length()
            if (after <= 0 || (before > 0 && after >= before)) { File(Store.mediaDir, n).delete(); ctx.toast(L.t("shrinkNoGain")); paintLabel(); return@run }
            replaceFile(n)
            ctx.toast(L.t("shrinkOk", "a" to MediaFiles.sizeText(before), "b" to MediaFiles.sizeText(after)))
        }
    }
}
