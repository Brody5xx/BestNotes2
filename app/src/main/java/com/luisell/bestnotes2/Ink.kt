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
class Stroke(val t: String, val c: Int, val w: Float, val p: ArrayList<FloatArray> = ArrayList(), val txt: String = "") {
    fun toJson(): JSONObject {
        val a = JSONArray()
        for (q in p) a.put(JSONArray().put(Math.round(q[0] * 10000) / 10000.0).put(Math.round(q[1] * 10000) / 10000.0))
        val rgb = String.format("#%06x", c and 0xFFFFFF)
        val col = if ((c ushr 24) == 0xFF) rgb else rgb + String.format("%02x", c ushr 24)
        val o = JSONObject().put("t", t).put("c", col).put("w", w.toDouble()).put("p", a)
        if (txt.isNotEmpty()) o.put("x", txt)
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
            return Stroke(o.optString("t", "pen"), c, o.optDouble("w", 0.006).toFloat(), pts, o.optString("x", ""))
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
                txtP.color = s.c; txtP.textSize = s.w * W
                txtP.setShadowLayer(s.w * W * 0.08f, 0f, 0f, if ((s.c and 0xFFFFFF) > 0x888888) 0xAA000000.toInt() else 0x66FFFFFF)
                var y = s.p[0][1] * W
                for (line in s.txt.split('\n')) { cv.drawText(line, s.p[0][0] * W, y, txtP); y += txtP.textSize * 1.2f }
                return
            }
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
    private val redo = ArrayList<Stroke>()
    private var cleared: List<Stroke>? = null
    var drawing = false
        set(v) { field = v; isClickable = v }
    var tool = "pen"
    var color = 0xFFFF453A.toInt()
    /** 0 fino, 1 medio, 2 grueso */
    var size = 1
    var onChange: (() -> Unit)? = null
    var onPan: ((Float) -> Unit)? = null
    /** Tocar con la herramienta "texto": dónde (x, y en 0..1 del ancho). */
    var onTextAt: ((Float, Float) -> Unit)? = null

    private var cur: Stroke? = null
    private var panY = -1f
    private val clip = Rect()

    init { setLayerType(LAYER_TYPE_NONE, null) }

    fun set(l: List<Stroke>) { strokes.clear(); strokes.addAll(l); redo.clear(); cleared = null; invalidate() }

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
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!drawing) return false
        val W = width.toFloat(); if (W <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
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
                for (h in 0 until e.historySize) add(s, e.getHistoricalX(h) / W, e.getHistoricalY(h) / W, W)
                add(s, e.x / W, e.y / W, W)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 2) panY = -1f
            MotionEvent.ACTION_UP -> {
                cur?.let { strokes.add(it); redo.clear(); cleared = null; onChange?.invoke() }
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
        strokes.add(Stroke("text", color, sizeFrac, arrayListOf(floatArrayOf(x, y)), text)); redo.clear(); cleared = null
        invalidate(); onChange?.invoke()
    }

    fun canUndo() = strokes.isNotEmpty() || cleared != null
    fun canRedo() = redo.isNotEmpty()

    fun undo() {
        if (strokes.isEmpty() && cleared != null) { strokes.addAll(cleared!!); cleared = null }
        else if (strokes.isNotEmpty()) redo.add(strokes.removeAt(strokes.size - 1))
        else return
        invalidate(); onChange?.invoke()
    }

    fun redoOne() {
        if (redo.isEmpty()) return
        strokes.add(redo.removeAt(redo.size - 1)); invalidate(); onChange?.invoke()
    }

    fun clearAll() {
        if (strokes.isEmpty()) return
        cleared = ArrayList(strokes); strokes.clear(); redo.clear(); invalidate(); onChange?.invoke()
    }
}
