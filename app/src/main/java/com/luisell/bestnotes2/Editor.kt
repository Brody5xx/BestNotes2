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
        removeAllViews(); views.clear(); selected = null
        for (b in blocks) {
            val v: BlockView = when (b) {
                is Block.Text -> TextoView(this, b)
                is Block.ListB -> ListaView(this, b)
                is Block.Img -> FotoView(this, b)
                is Block.Media -> MediaView(this, b)
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
    fun insertImages(imgs: List<Block.Img>) {
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
 * Una foto en la nota. Tócala para seleccionarla:
 *  - un dedo: moverla a los lados (salen guías rojas) o arriba/abajo
 *  - dos dedos: hacerla más grande o más chica, y girarla
 *  - la barrita de abajo: tamaño, a un lado con texto, girar, recortar, sello, anclar…
 * Con "a un lado" (izquierda/derecha) el texto se escribe al lado de la foto.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FotoView(val ed: EditorView, override val block: Block.Img) : BlockView {
    private val ctx = ed.context
    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = root
    private val lane = FrameLayout(ctx)                 // franja de todo el ancho
    private val box = FrameLayout(ctx)                  // la foto (con su sello)
    private val img = object : ImageView(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, (width * ratio()).toInt())
        }
    }
    private val stampTv = TextView(ctx)
    private var besideEdit: RichEdit? = null
    private val guides = Guides(ctx)
    private var bar: View? = null
    var selected = false
        private set
    // (van antes de init: build() los usa)
    private var lastW = -1
    private val sizer = View.OnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> place(r - l) }

    private fun dp(v: Int) = ed.dp(v)
    private fun ratio(): Float = if (block.w > 0 && block.h > 0) block.h.toFloat() / block.w
        else img.drawable?.let { d -> if (d.intrinsicWidth > 0) d.intrinsicHeight.toFloat() / d.intrinsicWidth else 0.75f } ?: 0.75f

    init {
        root.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) }
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        box.background = roundBg(0x10FFFFFF, dp(12).toFloat())
        box.clipToOutline = true
        box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Sello: franja oscura abajo con la fecha, la hora y el sitio
        stampTv.setTextColor(0xFFFFFFFF.toInt()); stampTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
        stampTv.typeface = Typeface.DEFAULT_BOLD; stampTv.gravity = Gravity.END
        stampTv.maxLines = 2
        stampTv.setShadowLayer(3f, 0f, 1f, 0xFF000000.toInt())
        stampTv.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x9E000000.toInt()))
        stampTv.setPadding(dp(8), dp(14), dp(8), dp(5))
        box.addView(stampTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        build()
        box.setOnTouchListener(Touch())
    }

    /** Arma la fila: foto sola, o foto + texto al lado. */
    private fun build() {
        root.removeAllViews(); lane.removeAllViews()
        (box.parent as? ViewGroup)?.removeView(box)
        stampTv.text = block.stamp
        stampTv.visibility = if (block.stamp.isEmpty()) View.GONE else View.VISIBLE
        box.rotation = block.rot
        if (block.side.isEmpty()) {
            besideEdit = null
            lane.addView(box, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(block.y.toInt()) })
            lane.addView(guides, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(lane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val e = besideEdit ?: RichEdit(ctx).also { r ->
                r.setRuns(block.beside); r.hint = L.t("besidePh"); ed.wire(r)
            }
            besideEdit = e
            (e.parent as? ViewGroup)?.removeView(e)
            val boxLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(block.y.toInt()) }
            val eLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (block.side == "l") { row.addView(box, boxLp); eLp.marginStart = dp(12); row.addView(e, eLp) }
            else { eLp.marginEnd = dp(12); row.addView(e, eLp); row.addView(box, boxLp) }
            lane.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            lane.addView(guides, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(lane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (selected) showBar()
        lane.removeOnLayoutChangeListener(sizer); lane.addOnLayoutChangeListener(sizer)
        lane.requestLayout()
        lastW = -1
    }


    /** Pone la foto de su tamaño y en su lugar según el ancho de la nota. */
    private fun place(W: Int) {
        if (W <= 0) return
        val pw = (W * block.pct / 100f).toInt().coerceAtLeast(dp(40))
        val lpB = box.layoutParams ?: return
        var changed = lpB.width != pw
        lpB.width = pw
        if (lpB is FrameLayout.LayoutParams) {
            val left = (W * block.x / 100f).toInt().coerceIn(0, maxOf(0, W - pw))
            if (lpB.leftMargin != left) { lpB.leftMargin = left; changed = true }
        }
        if (changed) box.post { box.layoutParams = lpB }
        if (W != lastW || changed) {
            lastW = W
            Thumbs.load(block.file, pw) { bm -> if (bm != null) { img.setImageBitmap(bm); img.requestLayout() } else img.setImageDrawable(null) }
        }
    }

    private fun relayout() { place(lane.width) }

    override fun flush() { besideEdit?.let { block.beside = it.runs() } }
    override fun edits() = listOfNotNull(besideEdit)
    override fun focusAt(item: Int, offset: Int) {
        val e = besideEdit ?: return
        e.requestFocus(); e.setSelection(if (offset < 0) e.length() else offset.coerceIn(0, e.length())); showKeyboard(e)
    }
    override fun highlight(q: String?): Int = besideEdit?.let { Rich.highlight(it.text, q) } ?: -1

    /* ---------- seleccionar ---------- */
    fun setSelected(on: Boolean) {
        if (selected == on) return
        selected = on
        box.foreground = if (on) roundBg(0, dp(12).toFloat(), T.accent, dp(3)) else null
        if (on) showBar() else { bar?.let { root.removeView(it) }; bar = null }
    }

    private fun showBar() {
        bar?.let { root.removeView(it) }
        val hs = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(2)) }
        fun b(label: String, on: Boolean = false, act: () -> Unit) {
            row.addView(ctx.tv(label, 13.5f, if (on) 0xFF16161A.toInt() else T.text, true).apply {
                setPadding(dp(13), dp(8), dp(13), dp(8))
                pressBg(if (on) T.text else T.cardStrong, 16f, T.border)
                setOnClickListener { act() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        b("−") { resize(-10f) }
        b("+") { resize(10f) }
        b(L.t("imgLeft"), block.side == "l") { setSide("l") }
        b(L.t("imgAlone"), block.side.isEmpty()) { setSide("") }
        b(L.t("imgRight"), block.side == "r") { setSide("r") }
        b(L.t("imgRotate")) { ed.host.rotateImage(this) }
        b(L.t("crop")) { ed.host.cropImage(this) }
        b(L.t("stamp"), block.stamp.isNotEmpty()) { toggleStamp() }
        b(L.t(if (block.pin) "imgUnpin" else "imgPin"), block.pin) { block.pin = !block.pin; ed.host.onDirty(); showBar() }
        b(L.t("moveUp")) { ed.moveBlock(this, -1) }
        b(L.t("moveDown")) { ed.moveBlock(this, 1) }
        b(L.t("view")) { ed.host.openMedia(block.file, "image/jpeg") }
        b(L.t("remove")) { ed.removeImage(this) }
        hs.addView(row)
        bar = hs
        root.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun resize(d: Float) {
        block.pct = (block.pct + d).coerceIn(15f, 100f)
        if (block.side.isNotEmpty() && block.pct > 75f) block.pct = 75f
        block.x = block.x.coerceIn(0f, 100f - block.pct)
        relayout(); ed.host.onDirty()
    }

    private fun setSide(side: String) {
        if (block.side == side) return
        flush()
        if (side.isEmpty() && block.beside.isNotEmpty()) {
            // el texto que estaba al lado pasa abajo de la foto
            ed.afterImage(this, block.beside); block.beside = ArrayList(); block.side = ""; return
        }
        if (side.isNotEmpty() && block.pct > 60f) block.pct = 45f
        block.side = side
        block.x = if (side == "r") 100f - block.pct else if (side == "l") 0f else (100f - block.pct) / 2
        build(); ed.host.onDirty()
    }

    private fun toggleStamp() {
        if (block.stamp.isEmpty()) {
            block.stamp = Photos.stampText(if (block.taken > 0) block.taken else System.currentTimeMillis(), ed.host.noteTitle())
            Photos.setStampOn(ctx, true)
        } else { block.stamp = ""; Photos.setStampOn(ctx, false) }
        stampTv.text = block.stamp
        stampTv.visibility = if (block.stamp.isEmpty()) View.GONE else View.VISIBLE
        ed.host.onDirty(); showBar()
    }

    /** La foto cambió de archivo (girar o recortar). */
    fun reload() { lastW = -1; img.setImageDrawable(null); img.requestLayout(); relayout() }

    /* ---------- tocar, mover, agrandar y girar ---------- */
    private inner class Touch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0f
        private var moved = false; private var multi = false
        private var startDist = 0f; private var startPct = 0f
        private var startAng = 0f; private var startRot = 0f
        private var accY = 0f
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

        private fun dist(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
        private fun ang(e: MotionEvent) = Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = block.x; moved = false; multi = false; accY = 0f
                    if (selected) v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (selected && !block.pin && e.pointerCount == 2) {
                    multi = true; startDist = dist(e); startPct = block.pct; startAng = ang(e); startRot = block.rot
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!selected || block.pin) return true
                    if (multi && e.pointerCount >= 2) {
                        // dos dedos: tamaño y giro
                        val k = dist(e) / maxOf(1f, startDist)
                        block.pct = (startPct * k).coerceIn(15f, if (block.side.isEmpty()) 100f else 75f)
                        var r = startRot + (ang(e) - startAng)
                        while (r > 180) r -= 360; while (r < -180) r += 360
                        if (Math.abs(r) < 4) r = 0f                 // se endereza sola cerca de 0°
                        block.rot = r; box.rotation = r
                        block.x = block.x.coerceIn(0f, 100f - block.pct)
                        relayout(); moved = true
                        return true
                    }
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && Math.abs(dx) < slop && Math.abs(dy) < slop) return true
                    moved = true
                    val W = lane.width.toFloat()
                    if (block.side.isEmpty() && W > 0) {
                        // un dedo: a los lados, con guías rojas que "pegan" a la orilla y al centro
                        var x = (startX + dx / W * 100f).coerceIn(0f, 100f - block.pct)
                        val center = (100f - block.pct) / 2
                        val g = ArrayList<Float>()
                        if (Math.abs(x) < 2.5f) { x = 0f; g.add(0f) }
                        if (Math.abs(x - center) < 2.5f) { x = center; g.add(50f) }
                        if (Math.abs(x - (100f - block.pct)) < 2.5f) { x = 100f - block.pct; g.add(100f) }
                        block.x = x; guides.show(g); relayout()
                    } else if (block.side.isNotEmpty() && W > 0 && Math.abs(dx) > W * 0.35f) {
                        // arrastrar al otro lado cambia de lado
                        val want = if (dx > 0) "r" else "l"
                        if (want != block.side) { block.side = want; downX = e.rawX; build(); ed.host.onDirty() }
                    }
                    // arriba / abajo: pasa por encima del bloque de al lado
                    val step = dp(70).toFloat()
                    if (dy - accY > step) { accY += step; ed.moveBlock(this@FotoView, 1) }
                    else if (dy - accY < -step) { accY -= step; ed.moveBlock(this@FotoView, -1) }
                }
                MotionEvent.ACTION_UP -> {
                    guides.show(emptyList())
                    if (!moved) ed.selectImage(if (selected) null else this@FotoView)
                    else ed.host.onDirty()
                }
                MotionEvent.ACTION_CANCEL -> guides.show(emptyList())
            }
            return true
        }
    }

    /** Las guías rojas (orilla izquierda, centro y orilla derecha). */
    private class Guides(c: Context) : View(c) {
        private var at: List<Float> = emptyList()
        private val p = Paint().apply { color = 0xFFFF3B30.toInt(); strokeWidth = c.dp(1.5f).toFloat() }
        fun show(l: List<Float>) { if (l != at) { at = l; invalidate() } }
        override fun onDraw(c: Canvas) { for (x in at) { val px = (x / 100f * width).coerceIn(1f, width - 1f); c.drawLine(px, 0f, px, height.toFloat(), p) } }
    }
}

/* ---------- Audio / video ---------- */
@SuppressLint("ViewConstructor")
class MediaView(val ed: EditorView, override val block: Block.Media) : BlockView {
    private val row = LinearLayout(ed.context)
    override val view: View get() = row

    init {
        val ctx = ed.context
        row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        row.pressBg(T.card, 16f, T.border)
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(8) }
        val isV = block.kind == "video"
        row.addView(IconView(ctx, if (isV) Icons.IMAGE else Icons.MIC, 10f).apply { color = T.text; isClickable = false }, lp(ctx.dp(36), ctx.dp(36)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), 0, 0, 0) }
        val d = block.dur.toInt()
        col.addView(ctx.tv(L.t(if (isV) "video" else "audio") + if (d > 0) "  ·  %d:%02d".format(d / 60, d % 60) else "", 15f, T.text, true))
        col.addView(ctx.tv(L.t("mediaSoon"), 12.5f, T.muted))
        row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            val ext = block.file.substringAfterLast('.')
            val mime = when (ext) { "mp4" -> "video/mp4"; "webm" -> if (isV) "video/webm" else "audio/webm"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; else -> if (isV) "video/*" else "audio/*" }
            ed.host.openMedia(block.file, mime)
        }
    }
}
