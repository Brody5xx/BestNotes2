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
    /** La perilla de girar flota afuera de la foto (encima del texto). Android solo le da los toques a una vista
     *  si caen dentro de su "cajita", así que aquí los atrapamos primero y se los pasamos a la perilla. */
    var knobView: View? = null
    var knobTouch: View.OnTouchListener? = null
    /** Lo mismo para las bolitas de estirar la foto (quedan en la orilla, medio afuera). */
    val floaters = ArrayList<Pair<View, View.OnTouchListener>>()
    private var routeV: View? = null; private var routeT: View.OnTouchListener? = null
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            routeV = null; routeT = null
            val all = ArrayList<Pair<View, View.OnTouchListener>>()
            val k = knobView; val t = knobTouch
            if (k != null && t != null) all.add(k to t)
            all.addAll(floaters)
            // 🎯 la bolita MÁS CERCANA al dedo (con un área más grande para atinarle fácil)
            val slop = 14 * resources.displayMetrics.density
            val near = all.mapNotNull { (v, l) ->
                if (!v.isShown || v.width == 0 || v.alpha < 0.5f) return@mapNotNull null
                val p = IntArray(2); v.getLocationOnScreen(p)
                if (e.rawX < p[0] - slop || e.rawX > p[0] + v.width + slop || e.rawY < p[1] - slop || e.rawY > p[1] + v.height + slop) return@mapNotNull null
                val dx = e.rawX - (p[0] + v.width / 2f); val dy = e.rawY - (p[1] + v.height / 2f)
                Triple(v, l, dx * dx + dy * dy)
            }.sortedBy { it.third }
            for ((v, l, _) in near) if (l.onTouch(v, e)) { routeV = v; routeT = l; return true }
        } else {
            val v = routeV; val l = routeT
            if (v != null && l != null) {
                l.onTouch(v, e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { routeV = null; routeT = null }
                return true
            }
        }
        return super.dispatchTouchEvent(e)
    }
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
        /** La línea roja larga cuando la orilla de una foto queda pareja con la de otra (x en la ventana; -1 = esconder). */
        fun showEdge(x: Float, top: Float, bottom: Float) {}
        fun hint(msg: String)
        /** Se seleccionó (o se soltó) una foto: muestra el panel de la foto. */
        fun onImageSelected(f: FotoView?)
        fun onImageChanged(f: FotoView)
        /** ☑️ Se marcó un pendiente como hecho. */
        fun onChecked() {}
        /** 🗺️ Abrir el plano con pines de esta foto. */
        fun openPlan(f: FotoView) {}
        /** ↔️ Antes y después con esta foto. */
        fun beforeAfter(f: FotoView) {}
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
            is Block.Img -> {
                if (views[bi - 1] !is FotoView) return false
                if (prev.side.isNotEmpty() || prev.beside.isNotEmpty()) {
                    // ⌫ sigue borrando en el texto de AL LADO de la foto
                    change { bl ->
                        val off = runsPlain(prev.beside).length
                        prev.beside = mergeRuns(prev.beside + sliceRuns(owner.block.runs, 0, firstLineEnd))
                        val rest = sliceRuns(owner.block.runs, minOf(txt.length, firstLineEnd + 1), txt.length)
                        if (firstLineEnd >= txt.length) bl.removeAt(bi) else owner.block.runs = mergeRuns(rest)
                        Spot(bi - 1, 0, off)
                    }
                } else {
                    // sin texto al lado: el renglón vacío se va y el cursor sube al texto de ARRIBA de la foto
                    val target = (bi - 2 downTo 0).firstOrNull { views[it].edits().isNotEmpty() } ?: return true
                    if (txt.isEmpty() && blocks.size > bi + 1) change { bl -> bl.removeAt(bi); null }
                    post { views.getOrNull(target)?.focusAt(-1, -1) }
                }
            }
            else -> return false
        }
        return true
    }

    /** El cursor al final de lo que está ARRIBA de una foto (saltándose la foto). */
    fun focusAbove(f: FotoView) {
        val i = views.indexOf(f)
        for (k in i - 1 downTo 0) { val v = views[k]; if (v.edits().isNotEmpty()) { post { v.focusAt(-1, -1) }; return } }
    }

    /** ⌫ al principio del texto de al lado: sube al texto de arriba de la foto. */
    fun backFromSide(f: FotoView): Boolean { focusAbove(f); return true }

    /* ---------- fotos ---------- */
    var selected: FotoView? = null
        private set

    /** ✍️ Escribir ARRIBA de un bloque (si no hay texto arriba, se crea un renglón). */
    fun writeAbove(v: BlockView) {
        selectImage(null)
        val i = views.indexOf(v); if (i < 0) return
        val prev = views.getOrNull(i - 1)
        if (prev != null && prev.block is Block.Text) { prev.focusAt(0, -1); return }
        flushAll()
        val nb = blocks.toMutableList(); nb.add(i, Block.Text())
        setBlocks(nb); host.onDirty()
        post { views.getOrNull(i)?.focusAt(0, 0) }
    }

    /**
     * ✍️ Tocaste el espacio vacío que quedó ARRIBA de una foto que bajaste:
     * ese espacio se vuelve renglones de verdad y el cursor queda donde tocaste
     * (como en Best Notes vieja: escribes justo arriba de la foto).
     */
    fun writeInGap(f: FotoView, tapY: Float) {
        val i = views.indexOf(f); if (i < 0) return
        val img = f.block as Block.Img
        val d = resources.displayMetrics.density
        val gapPx = img.y * d
        if (gapPx < 4) { writeAbove(f); return }
        selectImage(null); flushAll()
        val prevView = views.getOrNull(i - 1)
        val lineH = ((prevView?.edits()?.firstOrNull()?.lineHeight) ?: (24 * d).toInt()).toFloat().coerceAtLeast(8f)
        val n = maxOf(1, Math.round(gapPx / lineH))
        val k = (Math.floor((tapY / lineH).toDouble()).toInt() + 1).coerceIn(1, n)
        img.y = 0f
        val prev = blocks.getOrNull(i - 1) as? Block.Text
        val at: Int; val offset: Int
        if (prev != null) {
            val plain = runsPlain(prev.runs)
            if (plain.isEmpty()) { prev.runs = mutableListOf(Run("\n".repeat(n - 1))); offset = k - 1 }
            else { prev.runs = mergeRuns(prev.runs + Run("\n".repeat(n))); offset = plain.length + k }
            at = i - 1
        } else {
            blocks.add(i, Block.Text(mutableListOf(Run("\n".repeat(n - 1))))); at = i; offset = k - 1
        }
        setBlocks(blocks); host.onDirty()
        post { views.getOrNull(at)?.focusAt(0, offset) }
    }

    /** ✍️ Escribir DEBAJO de un bloque. */
    fun writeBelow(v: BlockView) {
        selectImage(null)
        val i = views.indexOf(v); if (i < 0) return
        val next = views.getOrNull(i + 1)
        if (next != null && next.block is Block.Text) { next.focusAt(0, 0); return }
        flushAll()
        val nb = blocks.toMutableList(); nb.add(i + 1, Block.Text())
        setBlocks(nb); host.onDirty()
        val fi = blocks.indexOf(v.block)
        post { views.getOrNull(fi + 1)?.focusAt(0, 0) }
    }

    /** 🎬 El video seleccionado (con su brillo y sus bolitas), como las fotos. */
    var selVideo: VideoCard? = null
        private set
    fun selectVideo(v: VideoCard?) {
        if (selVideo === v) return
        if (v != null) selectImage(null)
        selVideo?.setSelected(false)
        selVideo = v
        if (v != null) {
            v.setSelected(true)
            (context as? android.app.Activity)?.currentFocus?.let { cf ->
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(cf.windowToken, 0)
                cf.clearFocus()
            }
        }
    }

    fun selectImage(f: FotoView?) {
        if (selVideo != null) { selVideo?.setSelected(false); selVideo = null }
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

    /** 🎬 Achica TODOS los videos de la nota, uno detrás del otro. */
    fun shrinkAllVideos() {
        val vids = views.filterIsInstance<VideoCard>()
        if (vids.isEmpty()) return
        Shrink.askLevel(context, vids.map { it.block.file }) { level ->
            var a = 0L; var b = 0L; var ok = 0
            fun next(i: Int) {
                if (i >= vids.size) {
                    if (ok > 0) context.toast(L.t("shrinkAllOk", "n" to ok, "a" to MediaFiles.sizeText(a), "b" to MediaFiles.sizeText(b)))
                    else context.toast(L.t("shrinkNoGain"))
                    return
                }
                val v = vids[i]
                v.view.parent?.requestChildFocus(v.view, v.view)
                v.shrinkWith(level) { x, y -> if (y > 0) { ok++; a += x; b += y }; post { next(i + 1) } }
            }
            next(0)
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

    /** ⏎ El texto que ya no cabe al lado de la foto sigue debajo (todo el ancho) y el cursor se va ahí. */
    fun textBelow(f: FotoView, runs: List<Run>) {
        val i = views.indexOf(f); if (i < 0) return
        flushAll()
        val nb = Block.Text(runs.toMutableList())
        blocks.add(i + 1, nb)
        blocks = normalize(blocks)
        rebuild(null); host.onDirty()
        // se busca la foto otra vez (al acomodar, puede que arriba se haya agregado un renglón)
        val fi = blocks.indexOf(f.block)
        post { views.getOrNull(fi + 1)?.focusAt(0, 0) }
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
        // ✨ las fotos nuevas llegan inflándose con estrellitas
        post { postDelayed({ views.filterIsInstance<FotoView>().filter { f -> imgs.any { it === f.block } }.forEachIndexed { i, f -> f.appear(i * 90L) } }, 60) }
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
                // ✨ estrellitas en la casilla y el renglón se va deslizando antes de bajar a "Terminados"
                val row = rows.firstOrNull { r -> r.first === it }?.second?.parent as? View
                row?.let { r ->
                    (r as? ViewGroup)?.getChildAt(0)?.let { m -> Anim.bump(m, 1.35f); Anim.sparkle(m, 34f, 560) }
                }
                val undone = block.items.filter { x -> !x.done }
                it.pos = undone.indexOf(it)
                it.done = true
                block.items.remove(it); block.items.add(it)
                // al tocar la casilla se esconde el teclado
                (ed.context as? android.app.Activity)?.currentFocus?.let { f ->
                    (ed.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0)
                    f.clearFocus()
                }
                if (row != null) row.animate().alpha(0.25f).translationX(row.width * 0.18f).setStartDelay(160).setDuration(220).withEndAction { draw(null); ed.host.onDirty(); ed.host.onChecked() }.start()
                else { draw(null); ed.host.onDirty(); ed.host.onChecked() }
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
    private val pinsView = PinsOverlay(ctx)
    /** 🗺️ Volvió del plano con los pines nuevos. */
    fun setPins(json: String) { block.pins = json; pinsView.pins = Pins.parse(json); Anim.sparkle(box, 50f, 600); ed.host.onDirty() }
    private val edit = RichEdit(ctx)                    // el texto de al lado
    private var anchorPill: View? = null
    var selected = false
        private set
    private var loadedW = 0
    private var glow: GlowBorder? = null
    private val infoBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ed.dp(4), ed.dp(2), 0, 0) }
    private val infoLabel = ctx.tv("", 11.5f, T.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    /** ↻ La perilla para GIRAR la foto con un solo dedo (en la esquina de arriba a la derecha). */
    private val knob = RotKnob(ctx).apply { visibility = View.GONE }
    private var knobCorner = -1
    private var knobHeld = false
    private val knobTouchL by lazy { KnobTouch() }
    private val knobScroll = android.view.ViewTreeObserver.OnScrollChangedListener { placeKnob() }
    /** ⇔ Las bolitas para ESTIRAR la foto: a los lados (ancho), abajo (alto) y en la esquina (las dos cosas, sin deformar). */
    val dots = listOf(StretchDot(ctx, 0), StretchDot(ctx, 0), StretchDot(ctx, 1), StretchDot(ctx, 2))   // izq, der, abajo, esquina
    private val dotTouch = dots.indices.map { StretchTouch(it) }
    /** El brillo sigue a la foto (también mientras la mueves). */
    private fun placeGlow() {
        val g = glow ?: return
        val pad = dp(10)
        g.setBounds(box.left - pad, box.top - pad + box.translationY.toInt(), box.right + pad, box.bottom + pad + box.translationY.toInt())
        infoBar.translationY = box.translationY; placeKnob(); placeDots()
    }

    // mientras arrastras: dónde va (en vivo)
    private var dragLeft = -1f
    private var liveSide: String? = null

    private fun dp(v: Int) = ed.dp(v)
    private fun ratio(): Float = if (block.ar > 0f) block.ar else origRatio()
    private fun origRatio(): Float = if (block.w > 0 && block.h > 0) block.h.toFloat() / block.w
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
        // tocar el espacio vacío ARRIBA de la foto: escribes arriba de la foto
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (e.y < box.top) ed.writeInGap(this@FotoView, e.y)
                else if (e.y > box.bottom && (edit.visibility != View.VISIBLE || e.y > edit.bottom)) ed.writeBelow(this@FotoView)
                else if (side().isNotEmpty()) focusAt(0, -1)
                else ed.writeBelow(this@FotoView)
            }
            return true
        }
        override fun onMeasure(ws: Int, hs: Int) {
            val W = MeasureSpec.getSize(ws)
            var pw = (W * block.pct / 100f).toInt().coerceIn(dp(40), W)
            var ph = (pw * ratio()).toInt()
            // girada ocupa más lugar: si no cabe a lo ancho, se ve un poquito más chica
            val (bw0, _) = rotBox(pw, ph)
            if (bw0 > W) { val k = W.toFloat() / bw0; pw = (pw * k).toInt().coerceAtLeast(dp(30)); ph = (pw * ratio()).toInt() }
            box.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY))
            val (bw, bh0) = rotBox(pw, ph)
            // 🖼 la barrita va pegada debajo de la foto y es del MISMO ancho (cambia de tamaño con ella)
            infoBar.measure(MeasureSpec.makeMeasureSpec(maxOf(pw, dp(96)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            knob.measure(MeasureSpec.makeMeasureSpec(dp(28), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(28), MeasureSpec.EXACTLY))
            for (d in dots) d.measure(MeasureSpec.makeMeasureSpec(dp(26), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(26), MeasureSpec.EXACTLY))
            val bh = bh0 + infoH()
            val top = dp(block.y.toInt())
            var h = top + bh
            val gap = dp(12)
            if (edit.visibility != View.GONE) {
                if (side().isNotEmpty()) {
                    edit.measure(MeasureSpec.makeMeasureSpec(maxOf(dp(60), W - bw - gap), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    h = maxOf(h, top + edit.measuredHeight)          // el texto de al lado empieza donde empieza la foto
                } else {
                    edit.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    h += gap / 2 + edit.measuredHeight
                }
            }
            setMeasuredDimension(W, h)
            if (!stretching && (pw > loadedW * 1.3f || loadedW == 0)) { loadedW = pw; post { loadImg(pw) } }
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val W = r - l; val pw = box.measuredWidth; val ph = box.measuredHeight
            val (bw, bh0) = rotBox(pw, ph)
            val bh = bh0 + infoH()
            val ox = (bw - pw) / 2; val oy = (bh0 - ph) / 2      // lo que sobresale al estar girada
            val top = dp(block.y.toInt())
            val left = when {
                dragLeft >= 0 -> dragLeft.toInt()
                side() == "l" -> ox
                side() == "r" -> W - pw - ox
                else -> (W * block.x / 100f).toInt()
            }.coerceIn(minOf(ox, maxOf(0, W - pw - ox)), maxOf(ox, W - pw - ox))
            box.layout(left, top + oy, left + pw, top + oy + ph)
            // la barrita debajo de la foto (centrada con ella)
            val iw = infoBar.measuredWidth; val ix = (left + pw / 2 - iw / 2).coerceIn(0, maxOf(0, W - iw))
            infoBar.layout(ix, top + bh0, ix + iw, top + bh0 + infoBar.measuredHeight)
            infoBar.visibility = if (infoShown) View.VISIBLE else View.INVISIBLE
            if (changed || infoLabel.text.isEmpty()) post { fitInfo() }
            placeKnob(); if (knob.visibility == View.VISIBLE) post { placeKnob() }     // otra vez, ya con la pantalla acomodada
            placeGlow()
            if (edit.visibility != View.GONE) {
                val gap = dp(12)
                when (side()) {
                    // el texto de al lado viaja con la foto (empieza a su altura) y ocupa todo el lado: tocas ahí y escribes
                    "l" -> { val x = left + pw + ox + gap; edit.layout(x, top, x + edit.measuredWidth, top + maxOf(edit.measuredHeight, bh)) }
                    "r" -> { val x = maxOf(0, left - ox - gap - edit.measuredWidth); edit.layout(x, top, x + edit.measuredWidth, top + maxOf(edit.measuredHeight, bh)) }
                    else -> { val y = top + bh + gap / 2; edit.layout(0, y, W, y + edit.measuredHeight) }
                }
            }
        }
    }

    init {
        root.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) }
        flow.clipChildren = false
        img.scaleType = if (block.ar > 0f) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.CENTER_CROP
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
        // 🗺️ los pines del plano encima de la foto
        box.addView(pinsView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pinsView.pins = Pins.parse(block.pins)
        edit.setRuns(block.beside); edit.hint = null; edit.minHeight = dp(40)    // sin letrero: así las capturas se ven limpias
        ed.wire(edit)
        flow.addView(box); flow.addView(edit)
        root.addView(flow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 🖼 la barrita de abajo, igual que la de los videos: tamaño · ⛶ ver en grande · ⋮ opciones
        infoBar.addView(IconView(ctx, Icons.IMAGE, 6.5f).apply { color = T.muted; isClickable = false }, LinearLayout.LayoutParams(dp(18), dp(18)))
        infoBar.addView(infoLabel.apply { setPadding(dp(6), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 🔎 leer las letras de la foto (escoges las palabras que quieras)
        infoBar.addView(ctx.iconBtn(MoreIcons.TEXT_SCAN, 7.5f, T.accent) { v -> Anim.bump(v); readText() }.apply { layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)) })
        // 🗺️ plano con pines
        infoBar.addView(ctx.iconBtn(Icons.MAP_PIN, 7.5f, 0xFFFF6FB5.toInt()) { v -> Anim.bump(v); ed.host.openPlan(this) }.apply { layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)) })
        infoBar.addView(ctx.iconBtn(MoreIcons.FULL, 7.5f, T.muted) { v -> Anim.bump(v); viewBig() }.apply { layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)) })
        infoBar.addView(ctx.iconBtn(MoreIcons.DOTS_V, 8f, T.muted) { options() }.apply { layoutParams = LinearLayout.LayoutParams(dp(36), dp(38)) })
        flow.addView(infoBar)
        flow.addView(knob)
        knob.setOnTouchListener(knobTouchL)
        for (d in dots) { d.visibility = View.GONE; flow.addView(d) }
        paintInfo()
        // ⏎ escribiendo al lado: cuando el texto ya pasó el final de la foto, sigue ABAJO de la foto (todo el ancho)
        edit.onBackAtStart = { ed.backFromSide(this) }
        edit.onEnter = { e, at ->
            val lay = e.layout
            val pastPhoto = side().isNotEmpty() && lay != null && e.top + lay.getLineTop(lay.getLineForOffset(minOf(at + 1, e.text.length))) >= infoBar.top + infoH() - dp(4)
            if (pastPhoto) {
                val runs = e.runs(); val len = runsPlain(runs).length
                val after = sliceRuns(runs, minOf(at + 1, len), len)
                val before = sliceRuns(runs, 0, at)
                e.setRuns(before); block.beside = before
                ed.textBelow(this, after)
            }
            pastPhoto
        }
        paint()
        box.setOnTouchListener(Touch())
        if (block.pin) box.post { paintPin(false) }
    }


    /** "1080×2340 · 345 KB" */
    private fun paintInfo() {
        Loader.pool.execute {
            val f = File(Store.mediaDir, block.file)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            val t = (if (o.outWidth > 0) "${o.outWidth}×${o.outHeight} · " else "") + MediaFiles.sizeText(f.length())
            infoLong = t; infoShort = MediaFiles.sizeText(f.length())
            Store.main.post { fitInfo() }
        }
    }

    private fun showKnob(on: Boolean) {
        if (on == (knob.visibility == View.VISIBLE)) return
        if (on) {
            knob.visibility = View.VISIBLE; knob.angle = block.rot; knobCorner = -1; placeKnob()
            ed.knobView = knob; ed.knobTouch = knobTouchL
            flow.viewTreeObserver.addOnScrollChangedListener(knobScroll)
            knob.scaleX = 0f; knob.scaleY = 0f; knob.rotation = -120f
            knob.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        } else {
            flow.viewTreeObserver.removeOnScrollChangedListener(knobScroll)
            if (ed.knobView === knob) { ed.knobView = null; ed.knobTouch = null }
            knob.animate().scaleX(0f).scaleY(0f).setDuration(150).withEndAction { knob.visibility = View.GONE }.start()
        }
    }

    private var dotsOn = false
    private fun showDots(on: Boolean) {
        if (on == dotsOn) return
        dotsOn = on
        dots.forEachIndexed { i, d ->
            val pair = ed.floaters.firstOrNull { it.first === d }
            if (on) { if (pair == null) ed.floaters.add(d to dotTouch[i]) } else if (pair != null) ed.floaters.remove(pair)
        }
        placeDots()
        if (on) dots.forEachIndexed { i, d -> if (d.visibility == View.VISIBLE) { d.scaleX = 0f; d.scaleY = 0f
            d.animate().scaleX(1f).scaleY(1f).setStartDelay(60L + i * 45).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start() } }
    }

    /** ¿Está derechita? (las bolitas solo salen con la foto sin girar). */
    private fun straight() = Math.abs(((block.rot % 360f) + 360f) % 360f).let { it < 0.5f || it > 359.5f }

    /** Pone cada bolita en su orilla (y esconde la que no sirve según el lado del texto). */
    private fun placeDots() {
        if (box.width == 0) return
        val show = dotsOn && straight() && !block.pin
        val ty = box.translationY.toInt()
        val l = box.left; val r = box.right; val t = box.top + ty; val b = box.bottom + ty; val my = (t + b) / 2; val mx = (l + r) / 2
        val sd = side()
        val pos = listOf(l to my, r to my, mx to b, (if (sd == "r") l else r) to b)
        val want = listOf(sd != "l", sd != "r", true, true)
        dots.forEachIndexed { i, d ->
            val vis = show && want[i]
            if (vis && d.visibility != View.VISIBLE) { d.visibility = View.VISIBLE; d.alpha = 1f }
            if (!vis && d.visibility == View.VISIBLE) d.visibility = View.GONE
            val h = (d.measuredWidth.takeIf { it > 0 } ?: dp(26)) / 2
            val (x, y) = pos[i]
            d.layout(x - h, y - h, x + h, y + h)
        }
    }

    /** La orilla de esta foto en la pantalla (para que otra foto se empareje con ella). null = girada. */
    fun edgesOnScreen(): android.graphics.RectF? {
        if (!straight() || box.width == 0 || !box.isShown) return null
        val p = IntArray(2); box.getLocationOnScreen(p)
        return android.graphics.RectF(p[0].toFloat(), p[1].toFloat(), (p[0] + box.width).toFloat(), (p[1] + box.height).toFloat())
    }

    /** Mientras estiras con una bolita (la foto no se vuelve a cargar a medio camino). */
    private var stretching = false

    /** Estirar con las bolitas. which: 0 izq, 1 der, 2 abajo, 3 esquina. */
    private inner class StretchTouch(val which: Int) : View.OnTouchListener {
        private var x0 = 0f; private var y0 = 0f; private var pw0 = 0; private var ph0 = 0; private var l0 = 0; private var r0 = 0
        private var snapped: Float = Float.NaN; private var origSnap = false
        /** El ancho al que está pegada (o -1). Se pega cerquita y se suelta solo si te alejas más (no tiembla). */
        private var lockW = -1
        private var screenL = 0
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val W = flow.width.takeIf { it > 0 } ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (block.pin || !straight()) return false
                    x0 = e.rawX; y0 = e.rawY; pw0 = box.width; ph0 = box.height; l0 = box.left; r0 = box.right
                    val fl = IntArray(2); flow.getLocationOnScreen(fl); screenL = fl[0]
                    snapped = Float.NaN; origSnap = false; lockW = -1
                    stretching = true
                    knob.animate().alpha(0f).setDuration(120).start()          // la perilla se esconde: así no salta de esquina en esquina
                    (v as StretchDot).grab(true)
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    if (block.ar <= 0f) block.ar = ph0.toFloat() / pw0      // desde ahora tiene su propia forma
                    img.scaleType = ImageView.ScaleType.FIT_XY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    val minS = dp(40)
                    var nl = l0; var nr = r0; var nh = ph0
                    when (which) {
                        0 -> nl = (l0 + dx).toInt().coerceIn(0, r0 - minS)
                        1 -> nr = (r0 + dx).toInt().coerceIn(l0 + minS, W)
                        2 -> nh = (ph0 + dy).toInt().coerceIn(minS, dp(1400))
                        3 -> if (side() == "r") nl = (l0 + dx).toInt().coerceIn(maxOf(0, r0 - W), r0 - minS) else nr = (r0 + dx).toInt().coerceIn(l0 + minS, W)
                    }
                    // 📏 ¿quedó del MISMO ANCHO que otra foto? → se pega y sale la línea roja
                    val movingLeft = which == 0 || (which == 3 && side() == "r")
                    var edgeX = Float.NaN; var span: android.graphics.RectF? = null
                    if (which != 2) {
                        val cw = (nr - nl).toFloat()
                        var best = (if (lockW > 0) dp(16) else dp(7)).toFloat(); var bestW = -1
                        for (o in ed.views) {
                            if (o === this@FotoView || o !is FotoView) continue
                            val rr = o.edgesOnScreen() ?: continue
                            val dd = Math.abs(rr.width() - cw)
                            if (dd < best) { best = dd; bestW = Math.round(rr.width()); span = rr }
                        }
                        lockW = bestW
                        if (bestW > 0) {
                            if (movingLeft) nl = (nr - bestW).coerceAtLeast(0) else nr = (nl + bestW).coerceAtMost(W)
                            if (nr - nl == bestW) edgeX = screenL + (if (movingLeft) nl else nr).toFloat() else span = null
                        }
                    }
                    val nw = nr - nl
                    if (which == 3) nh = (nw * ph0.toFloat() / pw0).toInt()        // la esquina: crece sin deformar
                    // ↕ abajo: se pega a la forma original de la foto
                    var orig = false
                    if (which == 2) { val oh = (nw * origRatio()).toInt(); if (Math.abs(nh - oh) < (if (origSnap) dp(14) else dp(7))) { nh = oh; orig = true } }
                    if (orig != origSnap) { origSnap = orig; if (orig) { v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); (v as StretchDot).flash() } }
                    if (edgeX.isNaN() != snapped.isNaN() || (!edgeX.isNaN() && edgeX != snapped)) {
                        if (!edgeX.isNaN()) { v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); (v as StretchDot).flash() }
                        snapped = edgeX
                    }
                    // aplicar
                    block.ar = if (orig) 0f else nh.toFloat() / nw
                    block.pct = (nw * 100f / W).coerceIn(5f, 100f)
                    if (block.pct > 75f && block.side.isNotEmpty()) { block.side = ""; liveSide = null }
                    if (block.side.isEmpty()) block.x = (nl * 100f / W).coerceIn(0f, 100f - block.pct)
                    flow.requestLayout(); panelLater()
                    // la línea roja: larga, de una foto a la otra
                    if (!edgeX.isNaN() && span != null) {
                        val me = IntArray(2); box.getLocationOnScreen(me)
                        val top = minOf(span.top, me[1].toFloat()); val bot = maxOf(span.bottom, (me[1] + nh).toFloat())
                        val win = IntArray(2); flow.getLocationInWindow(win); val off = win[1] - flow.let { val q = IntArray(2); it.getLocationOnScreen(q); q[1] }
                        ed.host.showEdge(edgeX - (screenL - win[0]), top + off, bot + off)
                    } else ed.host.showEdge(-1f, 0f, 0f)
                    val label = when { orig -> "✓ Forma original"; which == 2 -> "↕ ${nh * 100 / maxOf(1, nw)}%"; !edgeX.isNaN() -> "📏 Mismo ancho que la otra foto"; else -> "↔ ${block.pct.toInt()}%" }
                    ed.host.showGuides(-1f, -1f, "", label)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (v as StretchDot).grab(false)
                    stretching = false
                    placeKnob(); knob.animate().alpha(1f).setDuration(220).start()
                    if (block.ar <= 0f) img.scaleType = ImageView.ScaleType.CENTER_CROP
                    ed.host.showEdge(-1f, 0f, 0f); ed.host.showGuides(-1f, -1f, "", null)
                    if (box.width > loadedW) { loadedW = box.width; loadImg(box.width) }
                    ed.host.onDirty(); ed.host.onImageChanged(this@FotoView)
                }
            }
            return true
        }
    }

    /**
     * La perilla va en una esquina de la foto (y gira con ella). Primero arriba a la derecha;
     * si ahí no se ve (se sale de la pantalla o queda tapada arriba), se pasa a otra esquina que SÍ se vea.
     */
    private fun placeKnob() {
        if (knob.visibility != View.VISIBLE || box.width == 0) return
        val cx = (box.left + box.right) / 2f; val cy = (box.top + box.bottom) / 2f + box.translationY
        val a = Math.toRadians(box.rotation.toDouble())
        val s2 = (knob.measuredWidth.takeIf { it > 0 } ?: dp(28)) / 2f
        // esquinas: arriba-der, arriba-izq, abajo-der, abajo-izq (m>0 = por fuera de la foto, m<0 = metida adentro)
        fun cornersAt(m: Float) = listOf(1f to -1f, -1f to -1f, 1f to 1f, -1f to 1f).map { (sx, sy) ->
            val dx = sx * (box.width / 2f + m); val dy = sy * (box.height / 2f + m)
            (cx + (dx * Math.cos(a) - dy * Math.sin(a)).toFloat()) to (cy + (dx * Math.sin(a) + dy * Math.cos(a)).toFloat())
        }
        val corners = cornersAt(s2 + dp(5)) + cornersAt(-s2 - dp(6))     // flotando afuera, sin tocar la foto
        // ¿dónde se ve? (dentro del pedazo de la nota que está en la pantalla)
        val vis = android.graphics.Rect()
        var sv: View? = flow; while (sv != null && sv !is android.widget.ScrollView) sv = sv.parent as? View
        (sv ?: flow.rootView).getGlobalVisibleRect(vis)
        val fl = IntArray(2); flow.getLocationOnScreen(fl)
        val screenW = flow.rootView.width.takeIf { it > 0 } ?: flow.resources.displayMetrics.widthPixels
        // tiene que caber entero: dentro de la nota (si no, se corta) y dentro de la pantalla
        fun ok(p: Pair<Float, Float>) = p.first - s2 >= 0 && p.first + s2 <= flow.width &&
            fl[0] + p.first - s2 >= dp(4) && fl[0] + p.first + s2 <= screenW - dp(4) &&
            (vis.isEmpty || (fl[1] + p.second - s2 >= vis.top + dp(2) && fl[1] + p.second + s2 <= vis.bottom - dp(2)))
        // mientras la agarras no se cambia de esquina (si no, se te escaparía del dedo)
        val pick = if (knobHeld && knobCorner >= 0) knobCorner else (corners.indices.firstOrNull { ok(corners[it]) } ?: 0)
        val (x, y) = corners[pick]
        val oldL = knob.left; val oldT = knob.top
        knob.layout((x - s2).toInt(), (y - s2).toInt(), (x + s2).toInt(), (y + s2).toInt())
        // al cambiar de esquina se desliza suavecito hasta la nueva
        if (knobCorner >= 0 && pick != knobCorner && oldL != 0) {
            knob.translationX = (oldL - knob.left).toFloat(); knob.translationY = (oldT - knob.top).toFloat()
            knob.animate().translationX(0f).translationY(0f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }
        knobCorner = pick
    }

    /** Girar con un dedo: arrastras la perilla alrededor de la foto. Se "pega" en 0°, 45°, 90°… */
    private inner class KnobTouch : View.OnTouchListener {
        private var cx = 0f; private var cy = 0f; private var a0 = 0f; private var r0 = 0f; private var lastSnap = Float.NaN
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (block.pin) return false
                    val loc = IntArray(2); box.getLocationOnScreen(loc)
                    // el centro de la foto (no cambia al girar)
                    val p = floatArrayOf(box.width / 2f, box.height / 2f); box.matrix.mapPoints(p)
                    val bl = IntArray(2); (box.parent as View).getLocationOnScreen(bl)
                    cx = bl[0] + box.left + p[0]; cy = bl[1] + box.top + p[1]
                    a0 = Math.toDegrees(Math.atan2((e.rawY - cy).toDouble(), (e.rawX - cx).toDouble())).toFloat(); r0 = block.rot
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    knob.grab(true); knobHeld = true
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                MotionEvent.ACTION_MOVE -> {
                    val a = Math.toDegrees(Math.atan2((e.rawY - cy).toDouble(), (e.rawX - cx).toDouble())).toFloat()
                    var r = r0 + (a - a0)
                    while (r > 180) r -= 360; while (r < -180) r += 360
                    // se pega en los ángulos "redondos" (con un toquecito en el teléfono)
                    val near = Math.round(r / 45f) * 45f
                    if (Math.abs(r - near) < 4f) {
                        if (near != lastSnap) { lastSnap = near; v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); knob.flash() }
                        r = near
                    } else lastSnap = Float.NaN
                    block.rot = r; box.rotation = r
                    flow.requestLayout(); flow.invalidate(); panelLater()
                    knob.angle = r
                    placeDots()
                    val loc = IntArray(2); box.getLocationInWindow(loc)
                    ed.host.showGuides(loc[0] + box.width / 2f, loc[1] + box.height / 2f, "", "↻ ${r.toInt()}°")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    knob.grab(false); knobHeld = false; placeKnob()
                    ed.host.showGuides(-1f, -1f, "", null)
                    ed.host.onDirty(); ed.host.onImageChanged(this@FotoView)
                }
            }
            return true
        }
    }

    /** 🖼 La barrita de la foto (tamaño · ⛶ · ⋮) solo sale cuando tocas la foto: así las capturas se ven limpias. */
    private var infoShown = false
    private fun infoH() = if (infoShown) infoBar.measuredHeight else 0
    private fun showInfo(on: Boolean) {
        if (on == infoShown) return
        infoShown = on
        if (on) { infoBar.alpha = 0f; infoBar.translationY = -dp(8).toFloat() + box.translationY
            flow.requestLayout(); infoBar.post { infoBar.animate().alpha(1f).translationY(box.translationY).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator()).start() } }
        else flow.requestLayout()
    }

    private var infoLong = ""; private var infoShort = ""
    /** Si la foto está chiquita, la barrita dice solo cuánto pesa (así no se corta). */
    private fun fitInfo() {
        val t = if (infoBar.width in 1 until dp(210)) infoShort else infoLong
        if (infoLabel.text.toString() != t) infoLabel.text = t
    }

    /** ⛶ La foto en grande. */
    fun viewBig() { (ctx as? android.app.Activity)?.let { PhotoViewer.show(it, block.file, block.pins) { ed.host.openPlan(this) } } }

    /** ⋮ Igual que los videos: ver en grande, achicar, subir, bajar, abrir con…, quitar. */
    /** CENTER_CROP con su forma original; FIT_XY si la estiraste (como en Samsung Notes). */
    private fun applyShape() { img.scaleType = if (block.ar > 0f) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.CENTER_CROP }

    private fun options() {
        val items = listOfNotNull<Pair<String, () -> Unit>>(
            ("⛶  " + L.t("viewBig")) to { viewBig() },
            ("🔎  " + L.t("ocrRead")) to { readText() },
            ("🗺️  " + L.t("planOpt")) to { ed.host.openPlan(this) },
            ("↔️  " + L.t("baOpt")) to { ed.host.beforeAfter(this) },
            if (block.ar > 0f) ("↺  " + L.t("origShape")) to { animateChange(); block.ar = 0f; applyShape(); relayout(); ed.host.onDirty(); ed.host.onImageChanged(this) } else null,
            L.t("shrink") to { shrinkPhoto() },
            L.t("moveUp") to { ed.moveBlock(this, -1) },
            L.t("moveDown") to { ed.moveBlock(this, 1) },
            L.t("openWith") to { ed.host.openMedia(block.file, "image/jpeg") },
            L.t("remove") to { ctx.confirm(L.t("remove"), null, L.t("remove"), true) { ed.removeImage(this) } },
        )
        ctx.dialog().setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    /** 🔎 Lee lo escrito en la foto (etiqueta, placa, serial…) y lo puedes poner en la nota o copiar. */
    fun readText() {
        val act = ctx as? android.app.Activity ?: return
        OcrPick.show(act, File(Store.mediaDir, block.file), onPut = { t -> ed.textBelow(this, listOf(Run(t))); ed.host.onDirty(); ctx.toast(L.t("ocrPutOk")) })
    }

    /** Achicar la foto (pesa menos): máximo 1600 px y un poquito más comprimida. */
    private fun shrinkPhoto() {
        infoLabel.text = L.t("working")
        Loader.pool.execute {
            val src = File(Store.mediaDir, block.file)
            val before = src.length()
            val bm = Thumbs.decode(src, 1600)
            var res: Photos.Result? = null
            if (bm != null) {
                val name = "f" + newId() + ".jpg"
                val out = File(Store.mediaDir, name)
                java.io.FileOutputStream(out).use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, it) }
                if (out.length() in 1 until before) res = Photos.Result(name, bm.width, bm.height, 0L) else out.delete()
            }
            Store.main.post {
                val r = res
                if (r == null) { ctx.toast(L.t("photoNoGain")); paintInfo(); return@post }
                val after = File(Store.mediaDir, r.file).length()
                block.file = r.file; block.w = r.w; block.h = r.h
                reload(); paintInfo(); ed.host.onDirty()
                ctx.toast(L.t("shrinkOk", "a" to MediaFiles.sizeText(before), "b" to MediaFiles.sizeText(after)))
                Anim.sparkle(box, 60f, 600)
            }
        }
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
            showKnob(!block.pin); showDots(!block.pin)
            showInfo(true)
            box.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { box.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start() }.start()
            showAnchor()
        } else {
            glow?.let { it.stop(); it.callback = null }; glow = null; flow.invalidate()
            showKnob(false); showDots(false)
            showInfo(false)
            anchorPill?.let { p -> p.animate().alpha(0f).setDuration(120).withEndAction { root.removeView(p) }.start() }; anchorPill = null
        }
    }

    /** La foto anclada más cercana de ARRIBA (para "Anclar igual"). */
    private fun prevAnchored(): FotoView? {
        val i = ed.views.indexOf(this)
        for (k in i - 1 downTo 0) { val v = ed.views[k]; if (v is FotoView && v.block.pin) return v }
        return null
    }

    /** Los botones debajo de la foto: "Anclar" y, si arriba hay una foto anclada, "Anclar igual". */
    private fun showAnchor() {
        anchorPill?.let { root.removeView(it) }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun pill(text: String, on: Boolean, act: () -> Unit) = ctx.tv(text, 14f, if (on) T.onPill else T.text, true).apply {
            setPadding(dp(16), dp(7), dp(16), dp(7))
            if (on) pressBg(T.text, 18f) else background = Glass.make(ctx, 18f)
            setOnClickListener { v -> Anim.bump(v, 1.1f); act() }
        }
        row.addView(pill("⚓ " + L.t(if (block.pin) "anchored" else "imgPin"), block.pin) { setPin(!block.pin) })
        val prev = if (block.pin) null else prevAnchored()
        if (prev != null) row.addView(pill("⚓ " + L.t("anchorSame"), false) { anchorLike(prev) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        anchorPill = row
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        for (k in 0 until row.childCount) Anim.popIn(row.getChildAt(k), 40L * k)
    }

    /** ⚓ "Anclar igual": la foto toma el mismo tamaño, lado y lugar que la foto anclada de arriba, y se ancla. */
    fun anchorLike(prev: FotoView) {
        flush()
        animateChange()
        val p = prev.block
        block.pct = p.pct; block.side = p.side; block.x = p.x; block.y = p.y; block.rot = p.rot; block.ar = p.ar; applyShape()
        liveSide = null; dragLeft = -1f
        block.pin = true
        relayout(); paintPin(true)
        ed.host.onDirty(); showAnchor(); ed.host.onImageChanged(this)
        ed.host.hint("📌 " + L.t("anchoredSame"))
        box.postDelayed({ Anim.sparkle(box, 70f, 650) }, 240)
    }

    /** 📌 La tachuela en la esquina de las fotos ancladas. */
    private val pinTv = TextView(ctx).apply {
        text = "📌"; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f); rotation = -20f
        setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()); visibility = View.GONE
    }
    private fun paintPin(animate: Boolean) {
        if (pinTv.parent == null) box.addView(pinTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(4), dp(2), 0, 0) })
        val show = block.pin
        if (show && pinTv.visibility != View.VISIBLE) {
            pinTv.visibility = View.VISIBLE
            if (animate) { pinTv.translationY = -dp(30).toFloat(); pinTv.alpha = 0f; pinTv.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(android.view.animation.BounceInterpolator()).start() }
        } else if (!show) pinTv.visibility = View.GONE
    }

    fun setPin(on: Boolean) {
        showKnob(selected && !on)
        block.pin = on; ed.host.onDirty(); showAnchor(); paintPin(true); ed.host.onImageChanged(this)
        ed.host.hint(if (on) "📌 " + L.t("photoAnchored") else L.t("photoUnanchored"))
    }

    fun setPct(p: Float) {
        animateChange()
        block.pct = p.coerceIn(10f, 100f)
        if (block.pct > 75f && block.side.isNotEmpty()) { block.side = ""; liveSide = null }   // muy grande: el texto de al lado pasa abajo
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

    /** ✨ Llega inflándose (cuando la acabas de poner). */
    fun appear(delay: Long) {
        box.scaleX = 0.3f; box.scaleY = 0.3f; box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(delay).setDuration(480)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).withEndAction { Anim.sparkle(box, 80f, 650) }.start()
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

        /** Los dos dedos en coordenadas de la PANTALLA (no de la foto girada: si no, al girar la foto
         *  el ángulo de los dedos "cambia" solo y la foto brinca de un lado a otro). */
        private fun fingers(e: MotionEvent): FloatArray {
            val p = floatArrayOf(e.getX(0), e.getY(0), e.getX(1), e.getY(1))
            box.matrix.mapPoints(p)
            return p
        }
        private fun dist(e: MotionEvent): Float { val p = fingers(e); return Math.hypot((p[0] - p[2]).toDouble(), (p[1] - p[3]).toDouble()).toFloat() }
        private fun ang(e: MotionEvent): Float { val p = fingers(e); return Math.toDegrees(Math.atan2((p[3] - p[1]).toDouble(), (p[2] - p[0]).toDouble())).toFloat() }

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
                        block.pct = (startPct * k).coerceIn(10f, 100f)
                        if (block.pct > 75f && side().isNotEmpty()) { block.side = ""; liveSide = null; paint() }
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
    /** 🎬 La fila del video: el video (con su barrita) y, si quieres, texto al lado. Se mueve y cambia de tamaño como las fotos. */
    private val flow = object : ViewGroup(ctx) {
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            glow?.let { g -> val pad = ctx.dp(10); g.setBounds(card.left + frame.left - pad + card.translationX.toInt(), card.top + frame.top - pad, card.left + frame.right + pad + card.translationX.toInt(), card.top + frame.bottom + pad); g.draw(canvas) }
        }
        override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === glow || super.verifyDrawable(who)
        override fun onMeasure(ws: Int, hs: Int) {
            val W = MeasureSpec.getSize(ws)
            val pw = (W * block.pct / 100f).toInt().coerceIn(ctx.dp(120), W)
            card.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            for (d in dots) d.measure(MeasureSpec.makeMeasureSpec(ctx.dp(26), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ctx.dp(26), MeasureSpec.EXACTLY))
            var h = card.measuredHeight
            if (side().isNotEmpty()) {
                edit.visibility = View.VISIBLE
                edit.measure(MeasureSpec.makeMeasureSpec(maxOf(ctx.dp(60), W - pw - ctx.dp(12)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                h = maxOf(h, edit.measuredHeight)
            } else edit.visibility = View.GONE
            setMeasuredDimension(W, h)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val W = r - l; val pw = card.measuredWidth
            val left = when {
                dragLeft >= 0 -> dragLeft.toInt()
                side() == "l" -> 0
                side() == "r" -> W - pw
                else -> (W * block.x / 100f).toInt()
            }.coerceIn(0, maxOf(0, W - pw))
            card.layout(left, 0, left + pw, card.measuredHeight)
            placeDots()
            if (edit.visibility == View.VISIBLE) {
                val gap = ctx.dp(12)
                val x = if (side() == "l") left + pw + gap else maxOf(0, left - gap - edit.measuredWidth)
                edit.layout(x, 0, x + edit.measuredWidth, maxOf(edit.measuredHeight, card.measuredHeight))
            }
        }
    }
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = col
    private val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val edit = RichEdit(ctx)
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
    private val pinTv = ctx.tv("📌", 15f).apply { visibility = View.GONE }
    private var vv: android.widget.VideoView? = null
    private val label = ctx.tv("", 12.5f, T.muted)
    private var vw = 0; private var vh = 0
    private var busy = false
    // mientras lo mueves: dónde va (en vivo)
    private var dragLeft = -1f
    private var liveSide: String? = null
    private fun side() = liveSide ?: block.side
    // ✨ seleccionado: brillo alrededor + bolitas para cambiar el tamaño (como las fotos)
    var selected = false
        private set
    private var glow: GlowBorder? = null
    val dots = listOf(StretchDot(ctx, 0), StretchDot(ctx, 0), StretchDot(ctx, 2))      // izq, der, esquina
    private val dotTouch = dots.indices.map { SizeTouch(it) }

    fun setSelected(on: Boolean) {
        if (selected == on) return
        selected = on
        if (on) {
            glow = GlowBorder(ctx.resources.displayMetrics.density, 16f).also { g -> g.callback = flow; g.start() }
            flow.clipChildren = false; col.clipChildren = false; (col.parent as? ViewGroup)?.clipChildren = false
            card.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).withEndAction { card.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start() }.start()
            if (!block.pin) dots.forEachIndexed { i, d -> if (ed.floaters.none { it.first === d }) ed.floaters.add(d to dotTouch[i]) }
            placeDots()
            dots.forEachIndexed { i, d -> if (d.visibility == View.VISIBLE) { d.scaleX = 0f; d.scaleY = 0f; d.animate().scaleX(1f).scaleY(1f).setStartDelay(60L + i * 50).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start() } }
            ed.host.hint(L.t("videoSelHint"))
        } else {
            glow?.let { it.stop(); it.callback = null }; glow = null
            ed.floaters.removeAll { p -> dots.any { it === p.first } }
            placeDots()
        }
        flow.invalidate()
    }

    private fun placeDots() {
        val show = selected && !block.pin && card.width > 0
        val l = card.left + frame.left; val r = card.left + frame.right; val t = card.top + frame.top; val b = card.top + frame.bottom
        val my = (t + b) / 2
        val sd = side()
        val pos = listOf(l to my, r to my, (if (sd == "r") l else r) to b)
        val want = listOf(sd != "l", sd != "r", true)
        dots.forEachIndexed { i, d ->
            d.visibility = if (show && want[i]) View.VISIBLE else View.GONE
            val h = ctx.dp(13); val (x, y) = pos[i]
            d.layout(x - h, y - h, x + h, y + h)
        }
    }

    /** Cambiar el tamaño con las bolitas (el video no se deforma). Se pega al MISMO ancho que otra foto o video (línea roja). */
    private inner class SizeTouch(val which: Int) : View.OnTouchListener {
        private var x0 = 0f; private var l0 = 0; private var r0 = 0; private var screenL = 0; private var snapped = false
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val W = flow.width.takeIf { it > 0 } ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (block.pin) return false
                    x0 = e.rawX; l0 = card.left; r0 = card.right; snapped = false
                    val fl = IntArray(2); flow.getLocationOnScreen(fl); screenL = fl[0]
                    (v as StretchDot).grab(true); v.parent.requestDisallowInterceptTouchEvent(true)
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val minS = ctx.dp(120)
                    val fromLeft = which == 0 || (which == 2 && side() == "r")
                    var nl = l0; var nr = r0
                    if (fromLeft) nl = (l0 + dx).toInt().coerceIn(0, r0 - minS) else nr = (r0 + dx).toInt().coerceIn(l0 + minS, W)
                    // 📏 ¿mismo ancho que otra foto o video?
                    var match: android.graphics.RectF? = null
                    var best = ctx.dp(9).toFloat()
                    for (o in ed.views) {
                        val rr = when (o) { is FotoView -> o.edgesOnScreen(); is VideoCard -> if (o !== this@VideoCard) o.edgesOnScreen() else null; else -> null } ?: continue
                        val dd = Math.abs(rr.width() - (nr - nl)); if (dd < best) { best = dd; match = rr }
                    }
                    match?.let { m -> val w = Math.round(m.width()); if (fromLeft) nl = (nr - w).coerceAtLeast(0) else nr = (nl + w).coerceAtMost(W) }
                    val ok = match != null
                    if (ok != snapped) { snapped = ok; if (ok) { v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); (v as StretchDot).flash() } }
                    val nw = nr - nl
                    block.pct = (nw * 100f / W).coerceIn(10f, 100f)
                    if (block.pct > 75f && block.side.isNotEmpty()) { block.side = ""; liveSide = null }
                    if (block.side.isEmpty()) block.x = (nl * 100f / W).coerceIn(0f, 100f - block.pct)
                    flow.requestLayout()
                    if (match != null) {
                        val me = IntArray(2); frame.getLocationOnScreen(me)
                        val win = IntArray(2); flow.getLocationInWindow(win); val scr = IntArray(2); flow.getLocationOnScreen(scr)
                        val ex = screenL + (if (fromLeft) nl else nr).toFloat()
                        ed.host.showEdge(ex + (win[0] - scr[0]), minOf(match.top, me[1].toFloat()) + (win[1] - scr[1]), maxOf(match.bottom, (me[1] + frame.height).toFloat()) + (win[1] - scr[1]))
                    } else ed.host.showEdge(-1f, 0f, 0f)
                    ed.host.showGuides(-1f, -1f, "", if (ok) "📏 " + L.t("sameWidth") else "↔ ${Math.round(block.pct)}%")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (v as StretchDot).grab(false)
                    ed.host.showEdge(-1f, 0f, 0f); ed.host.showGuides(-1f, -1f, "", null)
                    ed.host.onDirty()
                }
            }
            return true
        }
    }

    /** Dónde está el video en la pantalla (para emparejar anchos). */
    fun edgesOnScreen(): android.graphics.RectF? {
        if (frame.width == 0 || !frame.isShown) return null
        val p = IntArray(2); frame.getLocationOnScreen(p)
        return android.graphics.RectF(p[0].toFloat(), p[1].toFloat(), (p[0] + frame.width).toFloat(), (p[1] + frame.height).toFloat())
    }

    init {
        col.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6); bottomMargin = ctx.dp(6) }
        frame.background = roundBg(0xFF000000.toInt(), ctx.dp(16).toFloat(), T.border, ctx.dp(1))
        frame.clipToOutline = true
        frame.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playBig.color = 0xFF16161A.toInt(); playBig.background = roundBg(0xE6FFFFFF.toInt(), ctx.dp(30).toFloat()); playBig.isClickable = false
        frame.addView(playBig, FrameLayout.LayoutParams(ctx.dp(54), ctx.dp(54), Gravity.CENTER))
        frame.addView(pinTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(ctx.dp(6), ctx.dp(4), 0, 0) })
        frame.setOnTouchListener(MoveTouch())
        card.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(4), ctx.dp(2), 0, 0) }
        bar.addView(IconView(ctx, MoreIcons.VIDEO, 6.5f).apply { color = T.muted; isClickable = false }, lp(ctx.dp(18), ctx.dp(18)))
        bar.addView(label.apply { setPadding(ctx.dp(6), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ctx.iconBtn(MoreIcons.FULL, 7.5f, T.muted) { full() }.apply { layoutParams = lp(ctx.dp(38), ctx.dp(38)) })
        bar.addView(ctx.iconBtn(MoreIcons.DOTS_V, 8f, T.muted) { options() }.apply { layoutParams = lp(ctx.dp(36), ctx.dp(38)) })
        card.addView(bar)
        edit.setRuns(block.beside); edit.hint = null; edit.minHeight = ctx.dp(40)
        ed.wire(edit)
        edit.onEdit = { block.beside = edit.runs(); ed.host.onDirty() }
        flow.addView(card); flow.addView(edit)
        for (d in dots) { d.visibility = View.GONE; flow.addView(d) }
        col.addView(flow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paintPin()
        load()
    }

    override fun flush() { if (edit.visibility == View.VISIBLE || edit.length() > 0) block.beside = edit.runs() }
    override fun edits() = if (edit.visibility == View.VISIBLE) listOf(edit) else emptyList()

    private fun paintPin() { pinTv.visibility = if (block.pin) View.VISIBLE else View.GONE }

    /**
     * Tocar = ver el video. Dejarlo presionado y arrastrar = moverlo (a la orilla: el texto va al lado).
     * Dos dedos = más grande o más chico. Anclado (📌) no se mueve.
     */
    private inner class MoveTouch : View.OnTouchListener {
        private var x0 = 0f; private var y0 = 0f; private var left0 = 0f
        private var moving = false; private var pinching = false; private var moved = false
        private var d0 = 0f; private var pct0 = 0f
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        private val longPress = Runnable {
            if (pinching) return@Runnable
            frame.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            ed.selectVideo(this@VideoCard)                  // ✨ brillo + bolitas, como las fotos
            if (!block.pin) startMove()
        }
        private fun startMove() {
            moving = true; left0 = card.left.toFloat(); dragLeft = left0
            frame.parent.requestDisallowInterceptTouchEvent(true)
            card.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.92f).setDuration(140).start()
        }
        private fun dist(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; moving = false; pinching = false; moved = false
                    // que la nota no "se robe" el dedo mientras esperamos el toque largo (si mueves el dedo, la nota sí baja)
                    frame.parent.requestDisallowInterceptTouchEvent(true)
                    if (!selected) frame.postDelayed(longPress, 380)
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (!block.pin && e.pointerCount == 2) {
                    frame.removeCallbacks(longPress); pinching = true; d0 = dist(e); pct0 = block.pct
                    frame.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching && e.pointerCount >= 2) {
                        val W = flow.width.takeIf { it > 0 } ?: return true
                        var p = (pct0 * dist(e) / maxOf(1f, d0)).coerceIn(30f, 100f)
                        if (Math.abs(p - 100f) < 3f) p = 100f
                        block.pct = p
                        if (p > 75f && block.side.isNotEmpty()) { block.side = ""; liveSide = null }
                        block.x = block.x.coerceIn(0f, 100f - p)
                        flow.requestLayout()
                        ed.host.showGuides(-1f, -1f, "", "↔ ${Math.round(p)}%")
                        moved = true; W.let {}
                    } else if (moving) {
                        val W = flow.width; val pw = card.width
                        dragLeft = (left0 + e.rawX - x0).coerceIn(0f, (W - pw).toFloat())
                        val edge = ctx.dp(10)
                        liveSide = when {
                            block.pct > 75f -> ""
                            dragLeft <= edge -> "l"
                            dragLeft >= W - pw - edge -> "r"
                            else -> ""
                        }
                        if (liveSide!!.isNotEmpty()) dragLeft = if (liveSide == "l") 0f else (W - pw).toFloat()
                        ed.host.showGuides(-1f, -1f, "", when (liveSide) { "l" -> "▣ ✎  " + L.t("textRight"); "r" -> "✎ ▣  " + L.t("textLeft"); else -> "✥" })
                        flow.requestLayout(); moved = true
                    } else if (Math.abs(e.rawX - x0) > slop * 2 || Math.abs(e.rawY - y0) > slop * 2) {
                        frame.removeCallbacks(longPress)
                        if (selected && !block.pin) startMove()
                        else if (Math.abs(e.rawY - y0) > Math.abs(e.rawX - x0)) { frame.parent.requestDisallowInterceptTouchEvent(false); moved = true }   // querías bajar la nota
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frame.removeCallbacks(longPress)
                    ed.host.showGuides(-1f, -1f, "", null)
                    if (moving) {
                        val W = flow.width.coerceAtLeast(1)
                        block.side = liveSide ?: block.side
                        if (block.side.isEmpty()) block.x = (dragLeft * 100f / W).coerceIn(0f, 100f - block.pct)
                        dragLeft = -1f; liveSide = null
                        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
                        flow.requestLayout(); ed.host.onDirty()
                    } else if (pinching) ed.host.onDirty()
                    else if (e.actionMasked == MotionEvent.ACTION_UP && !moved) toggle()
                    moving = false; pinching = false
                }
            }
            return true
        }
    }

    private fun toggleAnchor() {
        block.pin = !block.pin; paintPin(); ed.host.onDirty()
        ed.host.hint(if (block.pin) "📌 " + L.t("videoPinned") else L.t("videoUnpinned"))
        if (block.pin) Anim.sparkle(frame, 60f, 600)
    }

    private fun setWidth(p: Float) {
        android.transition.TransitionManager.beginDelayedTransition(ed, android.transition.ChangeBounds().setDuration(220))
        block.pct = p
        if (p > 75f && block.side.isNotEmpty()) block.side = ""
        block.x = if (block.side.isEmpty()) ((100f - p) / 2f) else 0f
        flow.requestLayout(); ed.host.onDirty()
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
            n.isClickable = false; n.isFocusable = false          // los toques los maneja el cuadro (mover, escoger, tocar = play/pausa)
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
        val many = ed.views.count { it is VideoCard }
        if (many > 1) extra.add(L.t("shrinkAll", "n" to many) to { ed.shrinkAllVideos() })
        extra.add(0, (if (block.pin) "📌  " + L.t("unanchor") else "📌  " + L.t("anchor")) to { toggleAnchor() })
        extra.add(1, ("↔  " + L.t("videoSize")) to {
            val sizes = listOf(100f, 75f, 50f, 40f)
            ctx.dialog().setTitle(L.t("videoSize")).setItems(sizes.map { "${it.toInt()}%" + if (Math.abs(it - block.pct) < 1f) "  ✓" else "" }.toTypedArray()) { _, k -> setWidth(sizes[k]) }.show()
        })
        if (block.side.isNotEmpty()) extra.add(2, ("⬇  " + L.t("textBelowVideo")) to { block.side = ""; block.x = (100f - block.pct) / 2f; flow.requestLayout(); ed.host.onDirty() })
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

    /** Primero pregunta cuánto achicar (y te dice cuánto va a pesar más o menos). */
    private fun shrink() {
        Shrink.askLevel(ctx, listOf(block.file)) { level -> shrinkWith(level) { a, b -> if (b > 0) ctx.toast(L.t("shrinkOk", "a" to MediaFiles.sizeText(a), "b" to MediaFiles.sizeText(b))) } }
    }

    /** Achica este video. [onDone] (antes, después); después = 0 si no se pudo o no ganó nada. */
    fun shrinkWith(level: Int, onDone: (Long, Long) -> Unit) {
        if (busy) { onDone(0, 0); return }
        busy = true; paintLabel(L.t("shrinking", "n" to 0))
        val before = File(Store.mediaDir, block.file).length().takeIf { it > 0 } ?: block.size
        Shrink.run(ctx, block.file, vw, vh, level, { p -> paintLabel(L.t("shrinking", "n" to p)) }) { n ->
            busy = false
            if (n == null) { ctx.toast(L.t("videoFail")); paintLabel(); onDone(before, 0); return@run }
            val after = File(Store.mediaDir, n).length()
            if (after <= 0 || (before > 0 && after >= before)) { File(Store.mediaDir, n).delete(); ctx.toast(L.t("shrinkNoGain")); paintLabel(); onDone(before, 0); return@run }
            replaceFile(n)
            Anim.sparkle(col, 40f, 500)
            onDone(before, after)
        }
    }
}

/**
 * ↻ La perilla para girar una foto con un dedo: un circulito de vidrio con una flecha que da la vuelta.
 * Al agarrarla crece y le sale un anillo de luz; marca el ángulo con una rayita, y brilla al "pegarse" en 0°, 45°, 90°…
 */
class RotKnob(ctx: Context) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    private var held = 0f
    private var glowK = 0f
    var angle = 0f; set(v) { field = v; invalidate() }
    private val accent = 0xFF4FC3F7.toInt()

    fun grab(on: Boolean) {
        android.animation.ValueAnimator.ofFloat(held, if (on) 1f else 0f).apply {
            duration = if (on) 160 else 260; interpolator = if (on) android.view.animation.DecelerateInterpolator() else android.view.animation.OvershootInterpolator(2f)
            addUpdateListener { held = it.animatedValue as Float; scaleX = 1f + 0.25f * held; scaleY = scaleX; invalidate() }
        }.start()
    }

    /** Destello cuando se pega en un ángulo redondo. */
    fun flash() {
        android.animation.ValueAnimator.ofFloat(1f, 0f).apply { duration = 380; addUpdateListener { glowK = it.animatedValue as Float; invalidate() } }.start()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        // minimalista: un circulito chiquito con una flechita que da la vuelta
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - d
        val ring = maxOf(held * 0.6f, glowK)
        if (ring > 0f) { p.style = android.graphics.Paint.Style.FILL; p.color = (accent and 0xFFFFFF) or ((70 * ring).toInt() shl 24); c.drawCircle(cx, cy, r + 3f * d * ring, p) }
        p.style = android.graphics.Paint.Style.FILL
        p.color = if (T.light) 0xE6FFFFFF.toInt() else 0xD91E1E22.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.3f * d
        p.color = if (held > 0.1f || glowK > 0.1f) accent else (if (T.light) 0x55000000 else 0x66FFFFFF); c.drawCircle(cx, cy, r, p)
        c.save(); c.rotate(angle, cx, cy)
        val ar = r * 0.46f
        p.color = if (held > 0.1f || glowK > 0.1f) accent else T.text; p.strokeWidth = 1.6f * d; p.strokeCap = android.graphics.Paint.Cap.ROUND
        c.drawArc(android.graphics.RectF(cx - ar, cy - ar, cx + ar, cy + ar), -50f, 280f, false, p)
        val ea = Math.toRadians(230.0)
        val ex = cx + (ar * Math.cos(ea)).toFloat(); val ey = cy + (ar * Math.sin(ea)).toFloat()
        p.style = android.graphics.Paint.Style.FILL
        c.drawPath(android.graphics.Path().apply { moveTo(ex - 2.4f * d, ey + 0.6f * d); lineTo(ex + 1.8f * d, ey - 2.2f * d); lineTo(ex + 1.6f * d, ey + 2.4f * d); close() }, p)
        c.restore()
    }
}

/** Bolita para estirar la foto. kind: 0 = lado (pastilla parada), 1 = abajo (pastilla acostada), 2 = esquina (circulito). */
class StretchDot(ctx: Context, private val kind: Int) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    private var held = 0f; private var glowK = 0f
    private val accent = 0xFF4FC3F7.toInt()
    private val rr = android.graphics.RectF()

    fun grab(on: Boolean) {
        android.animation.ValueAnimator.ofFloat(held, if (on) 1f else 0f).apply {
            duration = if (on) 140 else 260; interpolator = if (on) android.view.animation.DecelerateInterpolator() else android.view.animation.OvershootInterpolator(2f)
            addUpdateListener { held = it.animatedValue as Float; invalidate() }
        }.start()
    }
    fun flash() { android.animation.ValueAnimator.ofFloat(1f, 0f).apply { duration = 360; addUpdateListener { glowK = it.animatedValue as Float; invalidate() } }.start() }

    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val grow = 1f + 0.35f * held
        val (hw, hh) = when (kind) { 0 -> 3.2f * d to 9f * d; 1 -> 9f * d to 3.2f * d; else -> 5f * d to 5f * d }
        val w = hw * grow; val h = hh * grow
        val ring = maxOf(held * 0.7f, glowK)
        if (ring > 0f) { p.style = android.graphics.Paint.Style.FILL; p.color = (accent and 0xFFFFFF) or ((80 * ring).toInt() shl 24)
            rr.set(cx - w - 4 * d * ring, cy - h - 4 * d * ring, cx + w + 4 * d * ring, cy + h + 4 * d * ring); c.drawRoundRect(rr, 99f, 99f, p) }
        p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
        p.setShadowLayer(3f * d, 0f, 1f * d, 0x66000000)
        rr.set(cx - w, cy - h, cx + w, cy + h); c.drawRoundRect(rr, 99f, 99f, p)
        p.clearShadowLayer()
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.4f * d
        p.color = if (held > 0.1f || glowK > 0.1f) accent else 0x33000000
        c.drawRoundRect(rr, 99f, 99f, p)
    }
}
