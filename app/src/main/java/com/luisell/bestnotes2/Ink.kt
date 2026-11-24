package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/* ============================================================
   Ink.kt — ✏️ dibujar. Mismo formato que Best Notes:
     trazo = { t: "pen" | "hl" | "eraser" | "text", c: color,
               w: grosor, p: [[x, y], …] }
   x, y y el grosor van divididos entre el ancho de la hoja:
   así el dibujo se ve igual en cualquier teléfono.
   "text" = un texto escrito encima (se usa en las fotos).
   ============================================================ */
class Stroke(val t: String, var c: Int, var w: Float, val p: ArrayList<FloatArray> = ArrayList(), var txt: String = "") {
    /** Texto con fondo de color (como una etiqueta): 0 no, 1 sí. */
    var bg = 0
    fun copy() = Stroke(t, c, w, ArrayList(p.map { it.copyOf() }), txt).also { it.bg = bg }
    fun toJson(): JSONObject {
        val a = JSONArray()
        for (q in p) a.put(JSONArray().put(Math.round(q[0] * 10000) / 10000.0).put(Math.round(q[1] * 10000) / 10000.0))
        val rgb = String.format("#%06x", c and 0xFFFFFF)
        val col = if ((c ushr 24) == 0xFF) rgb else rgb + String.format("%02x", c ushr 24)
        val o = JSONObject().put("t", t).put("c", col).put("w", w.toDouble()).put("p", a)
        if (txt.isNotEmpty()) o.put("x", txt)
        if (bg != 0) o.put("b", bg)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Stroke? {
            val pa = o.optJSONArray("p") ?: return null
            val pts = ArrayList<FloatArray>()
            for (k in 0 until pa.length()) { val q = pa.optJSONArray(k) ?: continue; pts.add(floatArrayOf(q.optDouble(0).toFloat(), q.optDouble(1).toFloat())) }
            if (pts.isEmpty()) return null
            val cs = o.optString("c", "#ffffff")
            // los colores se guardan como #rrggbb o #rrggbbaa (igual que en una página web)
            val c = try {
                if (cs.length == 9 && cs.startsWith("#")) (cs.substring(7, 9).toInt(16) shl 24) or cs.substring(1, 7).toInt(16)
                else HtmlImport.cssColor(cs).let { if (it == 0) 0xFFFFFFFF.toInt() else it }
            } catch (_: Exception) { 0xFFFFFFFF.toInt() }
            return Stroke(o.optString("t", "pen"), c, o.optDouble("w", 0.006).toFloat(), pts, o.optString("x", "")).also { it.bg = o.optInt("b", 0) }
        }

        fun listFrom(a: JSONArray?): ArrayList<Stroke> {
            val out = ArrayList<Stroke>()
            if (a != null) for (k in 0 until a.length()) a.optJSONObject(k)?.let { fromJson(it)?.let { s -> out.add(s) } }
            return out
        }

        fun listJson(l: List<Stroke>): JSONArray = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }

        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        private val path = Path()

        /** Dibuja un trazo. [W] = ancho de la hoja en píxeles. */
        fun draw(cv: Canvas, s: Stroke, W: Float) {
            if (s.t == "text") {
                txtP.textSize = s.w * W
                if (s.bg == 1) {
                    // 🏷 con fondo: una etiqueta de color y las letras en blanco o negro (lo que se lea mejor)
                    bounds(s, W, tmp); val pad = s.w * W * 0.28f
                    tmp.inset(-pad, -pad * 0.6f); pill.color = s.c
                    cv.drawRoundRect(tmp, s.w * W * 0.35f, s.w * W * 0.35f, pill)
                    txtP.clearShadowLayer(); txtP.color = if (lum(s.c) > 0.6f) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
                } else {
                    txtP.color = s.c
                    txtP.setShadowLayer(s.w * W * 0.08f, 0f, 0f, if ((s.c and 0xFFFFFF) > 0x888888) 0xAA000000.toInt() else 0x66FFFFFF)
                }
                var y = s.p[0][1] * W
                for (line in s.txt.split('\n')) { cv.drawText(line, s.p[0][0] * W, y, txtP); y += txtP.textSize * 1.2f }
                return
            }
            if (s.t in SHAPES && s.p.size >= 2) { drawShape(cv, s, W); return }
            pen.xfermode = if (s.t == "eraser") clear else null
            pen.color = if (s.t == "eraser") 0xFF000000.toInt() else s.c
            if (s.t == "hl") pen.alpha = (0.35f * 255).toInt()
            pen.strokeWidth = s.w * W
            val p = s.p
            path.reset()
            path.moveTo(p[0][0] * W, p[0][1] * W)
            if (p.size == 1) path.lineTo(p[0][0] * W + 0.1f, p[0][1] * W)
            for (i in 1 until p.size - 1) {
                val mx = (p[i][0] + p[i + 1][0]) / 2 * W; val my = (p[i][1] + p[i + 1][1]) / 2 * W
                path.quadTo(p[i][0] * W, p[i][1] * W, mx, my)
            }
            if (p.size > 1) path.lineTo(p.last()[0] * W, p.last()[1] * W)
            cv.drawPath(path, pen)
            pen.xfermode = null
        }

        /** ➜ ◯ ▭ ↔ Figuras: van de un punto a otro (flecha, círculo, cuadro y medida con su texto). */
        val SHAPES = setOf("arrow", "circle", "rect", "measure")
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun drawShape(cv: Canvas, s: Stroke, W: Float) {
            val x1 = s.p[0][0] * W; val y1 = s.p[0][1] * W; val x2 = s.p.last()[0] * W; val y2 = s.p.last()[1] * W
            pen.xfermode = null; pen.color = s.c; pen.strokeWidth = s.w * W
            val sw = s.w * W
            when (s.t) {
                "arrow" -> {
                    cv.drawLine(x1, y1, x2, y2, pen)
                    val a = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()); val h = maxOf(sw * 4.5f, 14f)
                    for (k in listOf(-0.5, 0.5)) cv.drawLine(x2, y2, x2 - (h * Math.cos(a + k)).toFloat(), y2 - (h * Math.sin(a + k)).toFloat(), pen)
                }
                "circle" -> cv.drawOval(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), pen)
                "rect" -> cv.drawRoundRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), sw * 1.5f, sw * 1.5f, pen)
                "measure" -> {
                    cv.drawLine(x1, y1, x2, y2, pen)
                    // las rayitas de las puntas (como una cinta de medir)
                    val a = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()) + Math.PI / 2; val h = maxOf(sw * 3f, 10f)
                    val dx = (h * Math.cos(a)).toFloat(); val dy = (h * Math.sin(a)).toFloat()
                    cv.drawLine(x1 - dx, y1 - dy, x1 + dx, y1 + dy, pen); cv.drawLine(x2 - dx, y2 - dy, x2 + dx, y2 + dy, pen)
                    if (s.txt.isNotEmpty()) {
                        txtP.clearShadowLayer(); txtP.textSize = maxOf(sw * 5f, W * 0.04f); txtP.color = 0xFFFFFFFF.toInt()
                        val tw = txtP.measureText(s.txt); val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
                        val pad = txtP.textSize * 0.35f
                        pill.color = s.c
                        cv.drawRoundRect(mx - tw / 2 - pad, my - txtP.textSize * 0.8f - pad, mx + tw / 2 + pad, my + txtP.textSize * 0.25f + pad, txtP.textSize, txtP.textSize, pill)
                        cv.drawText(s.txt, mx - tw / 2, my, txtP)
                    }
                }
            }
        }

        private val tmp = android.graphics.RectF()
        private fun lum(c: Int) = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f

        /** El cuadro que ocupa un texto o una figura (en píxeles). */
        fun bounds(s: Stroke, W: Float, out: android.graphics.RectF) {
            if (s.t == "text") {
                txtP.textSize = s.w * W
                val lines = s.txt.split('\n')
                val tw = lines.maxOf { txtP.measureText(it) }
                val x = s.p[0][0] * W; val top = s.p[0][1] * W - txtP.textSize * 0.95f
                out.set(x, top, x + tw, top + txtP.textSize * (1.2f * (lines.size - 1) + 1.2f))
                return
            }
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (q in s.p) { l = minOf(l, q[0] * W); r = maxOf(r, q[0] * W); t = minOf(t, q[1] * W); b = maxOf(b, q[1] * W) }
            out.set(l, t, r, b)
        }

        /** Lo más bajo que llega el dibujo (en píxeles). */
        fun bottom(l: List<Stroke>, W: Float): Float {
            var b = 0f
            for (s in l) for (q in s.p) b = maxOf(b, q[1] * W + s.w * W)
            return b
        }
    }
}

/**
 * La hoja de dibujo. Va encima de la nota (o de la foto).
 * En modo dibujo: un dedo dibuja; dos dedos mueven la nota ([onPan]).
 * Fuera del modo dibujo solo muestra el dibujo y deja tocar lo de abajo.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class InkView(ctx: Context) : View(ctx) {
    val strokes = ArrayList<Stroke>()
    /** Para deshacer / rehacer: cómo estaba todo antes de cada cambio. */
    private val past = ArrayList<List<Stroke>>()
    private val future = ArrayList<List<Stroke>>()
    private fun commit() { past.add(ArrayList(strokes)); if (past.size > 80) past.removeAt(0); future.clear() }
    var drawing = false
        set(v) { field = v; isClickable = v }
    var tool = "pen"
    var color = 0xFFFF453A.toInt()
    /** 0 fino, 1 medio, 2 grueso */
    var size = 1
    var onChange: (() -> Unit)? = null
    var onPan: ((Float) -> Unit)? = null
    /** ↔ Terminaste una medida: pregunta el texto (ej. "3 ft") y llama a [addMeasure]. */
    var onMeasure: ((Stroke) -> Unit)? = null
    fun addMeasure(s: Stroke, label: String) {
        commit(); val m = Stroke("measure", s.c, s.w, s.p, label.trim()); strokes.add(m); invalidate(); onChange?.invoke()
        if (objects) select(m)
    }
    /** Tocar con la herramienta "texto": dónde (x, y en 0..1 del ancho). */
    var onTextAt: ((Float, Float) -> Unit)? = null

    private var cur: Stroke? = null
    private var panY = -1f
    private val clip = Rect()

    init { setLayerType(LAYER_TYPE_NONE, null) }

    fun set(l: List<Stroke>) { strokes.clear(); strokes.addAll(l); past.clear(); future.clear(); sel = null; invalidate() }

    private fun baseWidth(): Float = floatArrayOf(4f, 7f, 12f)[size.coerceIn(0, 2)] * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val W = width.toFloat()
        if (W <= 0 || (strokes.isEmpty() && cur == null)) return
        // Se trabaja solo en el pedazo que se ve (así el borrador no gasta memoria)
        if (!c.getClipBounds(clip)) return
        val sc = c.saveLayer(clip.left.toFloat(), clip.top.toFloat(), clip.right.toFloat(), clip.bottom.toFloat(), null)
        for (s in strokes) Stroke.draw(c, s, W)
        cur?.let { Stroke.draw(c, it, W) }
        c.restoreToCount(sc)
        drawSel(c, W)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!drawing) return false
        val W = width.toFloat(); if (W <= 0) return false
        if (objects && objTouch(e, W)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                if (tool == "move") return true
                if (tool == "text") { onTextAt?.invoke(e.x / W, e.y / W); return true }
                val w = when (tool) { "hl" -> baseWidth() * 3; "eraser" -> baseWidth() * 4; else -> baseWidth() } / W
                cur = Stroke(tool, if (tool == "eraser") 0xFF000000.toInt() else color, w, arrayListOf(floatArrayOf(e.x / W, e.y / W)))
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> { cur = null; panY = e.rawY; invalidate() }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2 && panY >= 0) {
                    // se usa la posición en la pantalla (la hoja se mueve debajo de los dedos)
                    val y = e.rawY
                    onPan?.invoke(panY - y)
                    panY = y
                    return true
                }
                val s = cur ?: return true
                // figura: solo importan el principio y donde está el dedo ahora
                if (s.t in Stroke.SHAPES) { if (s.p.size < 2) s.p.add(floatArrayOf(e.x / W, e.y / W)) else s.p[1] = floatArrayOf(e.x / W, e.y / W); invalidate(); return true }
                for (h in 0 until e.historySize) add(s, e.getHistoricalX(h) / W, e.getHistoricalY(h) / W, W)
                add(s, e.x / W, e.y / W, W)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 2) panY = -1f
            MotionEvent.ACTION_UP -> {
                val done = cur
                if (done != null && done.t in Stroke.SHAPES && done.p.size < 2) { cur = null; invalidate(); return true }      // un toque sin arrastrar no hace figura
                if (done != null && done.t == "measure") { cur = null; onMeasure?.invoke(done); invalidate(); return true }    // ↔ primero pregunta cuánto mide
                cur?.let { commit(); strokes.add(it); onChange?.invoke(); if (objects && it.t in Stroke.SHAPES) select(it) }
                cur = null; panY = -1f; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { cur = null; panY = -1f; invalidate() }
        }
        return true
    }

    private fun add(s: Stroke, x: Float, y: Float, W: Float) {
        val l = s.p.last()
        if (Math.abs(x - l[0]) * W < 1.5f && Math.abs(y - l[1]) * W < 1.5f) return
        s.p.add(floatArrayOf(x, y))
    }

    fun addText(x: Float, y: Float, text: String, sizeFrac: Float) {
        commit(); val t = Stroke("text", color, sizeFrac, arrayListOf(floatArrayOf(x, y)), text); strokes.add(t)
        invalidate(); onChange?.invoke()
        if (objects) select(t)
    }

    fun canUndo() = past.isNotEmpty()
    fun canRedo() = future.isNotEmpty()

    fun undo() {
        if (past.isEmpty()) return
        future.add(ArrayList(strokes)); strokes.clear(); strokes.addAll(past.removeAt(past.size - 1))
        select(null); invalidate(); onChange?.invoke()
    }

    fun redoOne() {
        if (future.isEmpty()) return
        past.add(ArrayList(strokes)); strokes.clear(); strokes.addAll(future.removeAt(future.size - 1))
        select(null); invalidate(); onChange?.invoke()
    }

    fun clearAll() {
        if (strokes.isEmpty()) return
        commit(); strokes.clear(); select(null); invalidate(); onChange?.invoke()
    }

    /* ============================================================
       ✋ Textos y figuras como "objetos" (en las fotos): tócalos para
       escogerlos; luego los mueves, los agrandas con la bolita o con
       dos dedos, les cambias el color, el tamaño, el fondo…
       ============================================================ */
    var objects = false
    var sel: Stroke? = null
        private set
    var onSelect: ((Stroke?) -> Unit)? = null
    /** Tocaste otra vez un texto escogido: para cambiarle las letras. */
    var onEditText: ((Stroke) -> Unit)? = null
    private val dpx = resources.displayMetrics.density
    private val rr = android.graphics.RectF()
    private var selAnim: android.animation.ValueAnimator? = null
    private var selT = 1f

    fun select(s: Stroke?) {
        if (sel === s) return
        sel = s
        if (s != null) {
            selAnim?.cancel()
            selAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 380; interpolator = android.view.animation.OvershootInterpolator(2f); addUpdateListener { selT = it.animatedValue as Float; invalidate() }; start() }
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate(); onSelect?.invoke(s)
    }

    /** Cambiar el objeto escogido (se puede deshacer). */
    fun editSel(f: (Stroke) -> Unit) {
        val s = sel ?: return
        val i = strokes.indexOf(s); if (i < 0) return
        commit(); val cp = s.copy(); f(cp); strokes[i] = cp; sel = cp
        invalidate(); onChange?.invoke(); onSelect?.invoke(cp)
    }
    fun deleteSel() { val s = sel ?: return; commit(); strokes.remove(s); select(null); invalidate(); onChange?.invoke() }
    fun duplicateSel() {
        val s = sel ?: return
        commit(); val cp = s.copy(); for (q in cp.p) { q[0] += 0.04f; q[1] += 0.04f }; strokes.add(cp); invalidate(); onChange?.invoke(); select(cp)
    }

    private fun isObj(s: Stroke) = s.t == "text" || s.t in Stroke.SHAPES

    private fun segDist(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - x1) * dx + (py - y1) * dy) / l2).coerceIn(0f, 1f)
        return Math.hypot((px - x1 - t * dx).toDouble(), (py - y1 - t * dy).toDouble()).toFloat()
    }

    /** El texto o la figura que está debajo del dedo (el de más arriba primero). */
    private fun hitObj(x: Float, y: Float, W: Float): Stroke? {
        val tol = 20 * dpx
        for (i in strokes.indices.reversed()) {
            val s = strokes[i]; if (!isObj(s) || s.p.isEmpty()) continue
            if (s.t != "text" && s.p.size < 2) continue
            Stroke.bounds(s, W, rr)
            val hit = when (s.t) {
                "text" -> { rr.inset(-tol / 2, -tol / 2); rr.contains(x, y) }
                "arrow", "measure" -> segDist(x, y, s.p[0][0] * W, s.p[0][1] * W, s.p[1][0] * W, s.p[1][1] * W) < tol
                "rect" -> { val inside = android.graphics.RectF(rr).apply { inset(tol, tol) }; rr.inset(-tol, -tol); rr.contains(x, y) && !(inside.width() > 0 && inside.height() > 0 && inside.contains(x, y)) }
                else -> {   // ◯ cerca de la orilla del círculo
                    val rx = maxOf(rr.width() / 2, 1f); val ry = maxOf(rr.height() / 2, 1f)
                    val nx = (x - rr.centerX()) / rx; val ny = (y - rr.centerY()) / ry
                    Math.abs(Math.sqrt((nx * nx + ny * ny).toDouble()) - 1) * minOf(rx, ry) < tol
                }
            }
            if (hit) return s
        }
        return null
    }

    /** Dónde están las bolitas del objeto: texto = abajo a la derecha (tamaño); figuras = sus dos puntas. */
    private fun handles(s: Stroke, W: Float): List<FloatArray> {
        if (s.t == "text") { Stroke.bounds(s, W, rr); val p = 8 * dpx; return listOf(floatArrayOf(rr.right + p, rr.bottom + p)) }
        if (s.p.size < 2) return emptyList()
        return listOf(floatArrayOf(s.p[0][0] * W, s.p[0][1] * W), floatArrayOf(s.p[1][0] * W, s.p[1][1] * W))
    }

    private var mode = 0            // 0 nada, 1 mover, 2 bolita, 3 dos dedos
    private var handle = 0
    private var downX = 0f; private var downY = 0f
    private var started = false
    private var wasSel = false
    private var orig: List<FloatArray> = emptyList()
    private var origW = 0f
    private var pinch0 = 0f
    private var anchorX = 0f; private var anchorY = 0f; private var corner0 = 0f

    /** Copia el objeto antes del primer cambio (así "deshacer" lo devuelve como estaba). */
    private fun begin() {
        val s = sel ?: return
        val i = strokes.indexOf(s); if (i < 0) return
        commit(); val cp = s.copy(); strokes[i] = cp; sel = cp
        started = true
    }
    private fun remember(W: Float) {
        val s = sel ?: return
        orig = s.p.map { it.copyOf() }; origW = s.w
        Stroke.bounds(s, W, rr); anchorX = rr.left; anchorY = rr.top
    }

    private fun objTouch(e: MotionEvent, W: Float): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 0; started = false
                val x = e.x; val y = e.y
                val s0 = sel
                if (s0 != null) {
                    val hs = handles(s0, W)
                    for ((k, h) in hs.withIndex()) if (Math.hypot((x - h[0]).toDouble(), (y - h[1]).toDouble()) < 26 * dpx) {
                        parent.requestDisallowInterceptTouchEvent(true)
                        mode = 2; handle = k; downX = x; downY = y; remember(W)
                        corner0 = Math.hypot((h[0] - anchorX).toDouble(), (h[1] - anchorY).toDouble()).toFloat().coerceAtLeast(1f)
                        return true
                    }
                }
                val pick = tool !in setOf("pen", "hl", "eraser")
                val o = if (pick) hitObj(x, y, W) else null
                if (o != null) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    wasSel = o === sel; select(o); mode = 1; downX = x; downY = y; remember(W)
                    return true
                }
                if (sel != null) { select(null); if (tool == "text" || tool == "move" || tool in Stroke.SHAPES) { mode = -1; return true } }
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == 1 || mode == 2) {
                    if (!started) begin()
                    remember(W); mode = 3
                    pinch0 = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat().coerceAtLeast(1f)
                    return true
                }
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val s = sel
                if (mode <= 0 || s == null) return mode != 0
                if (mode == 3) {
                    if (e.pointerCount < 2) return true
                    val k = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat() / pinch0
                    scaleTo(s, k, W); invalidate(); return true
                }
                val dx = e.x - downX; val dy = e.y - downY
                if (!started) { if (Math.hypot(dx.toDouble(), dy.toDouble()) < 6 * dpx) return true; begin() }
                val c = sel ?: return true
                if (mode == 1) for (i in c.p.indices) { c.p[i][0] = orig[i][0] + dx / W; c.p[i][1] = orig[i][1] + dy / W }
                else if (c.t == "text") {
                    val h0 = handles(c, W)
                    val d = Math.hypot((downX + dx - anchorX).toDouble(), (downY + dy - anchorY).toDouble()).toFloat()
                    c.w = (origW * d / corner0).coerceIn(0.012f, 0.45f)
                    if (h0.isEmpty()) return true
                } else { c.p[handle][0] = orig[handle][0] + dx / W; c.p[handle][1] = orig[handle][1] + dy / W }
                invalidate(); return true
            }
            MotionEvent.ACTION_POINTER_UP -> { if (mode == 3) { mode = -1 }; return mode != 0 }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 0) return false
                val s = sel
                if (mode == 1 && !started && wasSel && s != null && (s.t == "text" || s.t == "measure") && e.actionMasked == MotionEvent.ACTION_UP) onEditText?.invoke(s)
                if (started) { onChange?.invoke(); onSelect?.invoke(sel) }
                mode = 0; started = false; invalidate()
                return true
            }
        }
        return mode != 0
    }

    /** Agrandar / achicar con dos dedos (desde el centro). */
    private fun scaleTo(s: Stroke, k: Float, W: Float) {
        if (s.t == "text") {
            val nw = (origW * k).coerceIn(0.012f, 0.45f)
            // que crezca desde el centro
            s.w = origW; Stroke.bounds(s, W, rr); val cx = rr.centerX(); val cy = rr.centerY()
            s.w = nw; s.p[0][0] = orig[0][0]; s.p[0][1] = orig[0][1]; Stroke.bounds(s, W, rr)
            s.p[0][0] += (cx - rr.centerX()) / W; s.p[0][1] += (cy - rr.centerY()) / W
            return
        }
        var cx = 0f; var cy = 0f; for (q in orig) { cx += q[0]; cy += q[1] }; cx /= orig.size; cy /= orig.size
        for (i in s.p.indices) { s.p[i][0] = cx + (orig[i][0] - cx) * k; s.p[i][1] = cy + (orig[i][1] - cy) * k }
    }

    private val selP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val knobP = Paint(Paint.ANTI_ALIAS_FLAG)
    /** ✨ El cuadro de hormiguitas que se mueven alrededor de lo escogido, con sus bolitas. */
    private fun drawSel(c: Canvas, W: Float) {
        val s = sel ?: return
        if (!objects || strokes.indexOf(s) < 0) return
        Stroke.bounds(s, W, rr)
        val pad = (if (s.t == "text") 8f else 12f) * dpx
        rr.inset(-pad, -pad)
        val k = 0.85f + 0.15f * selT
        val cx = rr.centerX(); val cy = rr.centerY()
        rr.set(cx - rr.width() / 2 * k, cy - rr.height() / 2 * k, cx + rr.width() / 2 * k, cy + rr.height() / 2 * k)
        val acc = T.accent
        selP.pathEffect = null; selP.strokeWidth = 7 * dpx; selP.color = (0x33 shl 24) or (acc and 0xFFFFFF)
        c.drawRoundRect(rr, 10 * dpx, 10 * dpx, selP)
        val phase = (android.os.SystemClock.uptimeMillis() % 1000) / 1000f * 16 * dpx
        selP.pathEffect = android.graphics.DashPathEffect(floatArrayOf(9 * dpx, 7 * dpx), -phase)
        selP.strokeWidth = 2 * dpx; selP.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(rr, 10 * dpx, 10 * dpx, selP)
        selP.pathEffect = null
        for (h in handles(s, W)) {
            knobP.color = 0x55000000; c.drawCircle(h[0], h[1] + 1.5f * dpx, 11 * dpx * selT.coerceAtMost(1.2f), knobP)
            knobP.color = 0xFFFFFFFF.toInt(); c.drawCircle(h[0], h[1], 10 * dpx * selT.coerceAtMost(1.2f), knobP)
            knobP.color = acc; c.drawCircle(h[0], h[1], 6 * dpx * selT.coerceAtMost(1.2f), knobP)
        }
        postInvalidateOnAnimation()
    }
}


/** ✏️ El dibujo en chiquito (para la tarjeta de la pantalla de inicio). */
class InkThumb(ctx: Context, private val strokes: List<Stroke>) : View(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        val W = MeasureSpec.getSize(w).toFloat()
        val bottom = Stroke.bottom(strokes, W)
        val maxH = (170 * resources.displayMetrics.density)
        setMeasuredDimension(W.toInt(), minOf(bottom, maxH).toInt().coerceAtLeast((24 * resources.displayMetrics.density).toInt()))
    }
    override fun onDraw(c: Canvas) {
        val W = width.toFloat(); if (W <= 0) return
        val bottom = Stroke.bottom(strokes, W)
        val sc = if (bottom > height) height / bottom else 1f
        val sv = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        c.translate((W - W * sc) / 2, 0f); c.scale(sc, sc)
        for (s in strokes) Stroke.draw(c, s, W)
        c.restoreToCount(sv)
    }
}
