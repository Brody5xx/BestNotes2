package com.luisell.bestnotes2

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream

/* ============================================================
   PhotoDrawActivity.kt — ✏️ escribir y dibujar ENCIMA de una foto
   (flechas, círculos, marcar dónde va un cable…).
   "Listo" guarda una foto nueva con el dibujo; la original no se toca.
   ============================================================ */
class PhotoDrawActivity : Activity() {
    companion object { const val FILE = "archivo"; const val PINS = "pines" }
    private lateinit var pinsOver: PinsOverlay

    private lateinit var file: String
    private lateinit var ink: InkView
    private lateinit var img: ImageView
    private var bm: Bitmap? = null
    private val btns = HashMap<String, View>()
    private lateinit var objBar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        file = intent.getStringExtra(FILE) ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        root.setBackgroundColor(0xFF000000.toInt()); window.statusBarColor = 0xFF000000.toInt()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        top.addView(tv(L.t("cancel"), 16f, T.text).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { finish() } })
        top.addView(tv(L.t("drawOn"), 18f, T.text, true).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
        top.addView(tv(L.t("done"), 16f, T.accent, true).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { done() } })
        root.addView(top, lp(MATCH, WRAP))

        val area = FrameLayout(this)
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        area.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        ink = InkView(this).apply { drawing = true; objects = true; color = 0xFFFF453A.toInt() }
        area.addView(ink, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        // 🗺️ los pines de la foto también se ven aquí (encima del dibujo; los toques pasan al dibujo)
        pinsOver = PinsOverlay(this).apply { pins = Pins.parse(intent.getStringExtra(PINS)) }
        area.addView(pinsOver, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        // ✋ la barrita del texto / figura escogida (color, tamaño, fondo, copiar, borrar)
        objBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = Glass.make(this@PhotoDrawActivity, 24f, strong = true); setPadding(dp(6), dp(4), dp(6), dp(4)); elevation = dp(8).toFloat()
        }
        area.addView(objBar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(12)) })
        ink.onSelect = { st -> showObjBar(st) }
        ink.onEditText = { st -> editObjText(st) }
        root.addView(area, lp(MATCH, 0, 1f))
        ink.onChange = { paint() }
        ink.onMeasure = { st -> askText(L.t("measureQ")) { t -> ink.addMeasure(st, t.ifBlank { "?" }) } }
        ink.onTextAt = { x, y ->
            askText(L.t("writeOnPhoto")) { t -> if (t.isNotBlank()) { ink.addText(x, y, t.trim(), floatArrayOf(0.045f, 0.065f, 0.095f)[ink.size]) } }
        }
        area.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fit(r - l, b - t) }

        // Herramientas
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(0xFF1C1C1F.toInt()) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        fun b(k: String, icon: Int, act: () -> Unit) { val v = iconBtn(icon, 10.5f) { act() }; btns[k] = v; row.addView(v) }
        b("move", MoreIcons.MOVE) { ink.tool = "move"; paint(); hint(L.t("moveHint")) }
        b("pen", MoreIcons.PEN) { ink.tool = "pen"; ink.select(null); paint() }
        b("hl", MoreIcons.HILITE) { ink.tool = "hl"; ink.select(null); paint() }
        b("arrow", MoreIcons.ARROW) { ink.tool = "arrow"; paint(); hint(L.t("shapeDrag")) }
        b("circle", MoreIcons.CIRCLE) { ink.tool = "circle"; paint(); hint(L.t("shapeDrag")) }
        b("rect", MoreIcons.RECT) { ink.tool = "rect"; paint(); hint(L.t("shapeDrag")) }
        b("measure", MoreIcons.MEASURE) { ink.tool = "measure"; paint(); hint(L.t("measureDrag")) }
        b("text", MoreIcons.TEXT_T) { ink.tool = "text"; paint(); toast(L.t("tapToWrite")) }
        b("eraser", MoreIcons.ERASER) { ink.tool = "eraser"; ink.select(null); paint() }
        val dot = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) { p.style = android.graphics.Paint.Style.FILL; p.color = ink.color; c.drawCircle(width / 2f, height / 2f, dp(11).toFloat(), p); p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = T.text; c.drawCircle(width / 2f, height / 2f, dp(12).toFloat(), p) }
        }
        dot.setOnClickListener { pickColor(L.t("penColor"), ink.color, T.textColors) { c -> if (c != 0) { ink.color = c; if (ink.tool == "eraser") ink.tool = "pen"; paint() } } }
        btns["color"] = dot; row.addView(dot, lp(dp(44), dp(44)))
        val sz = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = T.text }
            override fun onDraw(c: Canvas) { c.drawCircle(width / 2f, height / 2f, dp(floatArrayOf(2.5f, 4.5f, 7f)[ink.size]).toFloat(), p) }
        }
        sz.setOnClickListener { ink.size = (ink.size + 1) % 3; sz.invalidate() }
        row.addView(sz, lp(dp(44), dp(44)))
        hs.addView(row)
        // ↶ ↷ deshacer y rehacer: SIEMPRE primero y siempre a la vista (fijos a la izquierda; las demás herramientas se deslizan)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF1C1C1F.toInt()) }
        val fixed = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(2), dp(4)) }
        fun fb(k: String, icon: Int, act: () -> Unit) { val v = iconBtn(icon, 10.5f) { act() }; btns[k] = v; fixed.addView(v) }
        fb("undo", Icons.UNDO) { ink.undo(); paint() }
        fb("redo", MoreIcons.REDO) { ink.redoOne(); paint() }
        bar.addView(fixed)
        bar.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) }, lp(dp(1), dp(30)))
        bar.addView(hs, lp(0, WRAP, 1f))
        root.addView(bar, lp(MATCH, WRAP))
        setContentView(root)
        paint()
        Loader.pool.execute {
            val b = Thumbs.decode(File(Store.mediaDir, file), Photos.MAX_SIDE)
            runOnUiThread { if (b == null) finish() else { bm = b; img.setImageBitmap(b); (area.width to area.height).let { fit(it.first, it.second) } } }
        }
    }

    /* ---------- ✋ lo escogido ---------- */
    private var barFor: String? = null
    private fun showObjBar(s: Stroke?) {
        if (s == null) {
            barFor = null
            if (objBar.visibility == View.VISIBLE) objBar.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(160).withEndAction { objBar.visibility = View.GONE }.start()
            return
        }
        val kind = s.t + (if (s.t == "text") s.bg.toString() else "")
        if (barFor == kind && objBar.visibility == View.VISIBLE) { (objBar.findViewWithTag<View>("dot"))?.invalidate(); return }
        barFor = kind
        objBar.removeAllViews()
        fun pill(label: String, go: () -> Unit) = objBar.addView(tv(label, 15f, T.text, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(11), dp(9), dp(11), dp(9))
            setOnClickListener { v -> Anim.bump(v); go() }
        })
        if (s.t == "text" || s.t == "measure") pill("✏️") { ink.sel?.let { editObjText(it) } }
        val dot = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) { p.style = android.graphics.Paint.Style.FILL; p.color = ink.sel?.c ?: ink.color; c.drawCircle(width / 2f, height / 2f, dp(10).toFloat(), p); p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = T.text; c.drawCircle(width / 2f, height / 2f, dp(11).toFloat(), p) }
        }.apply { tag = "dot" }
        dot.setOnClickListener { v -> Anim.bump(v); pickColor(L.t("penColor"), ink.sel?.c ?: ink.color, T.textColors) { c -> if (c != 0) { ink.color = c; ink.editSel { it.c = c }; dot.invalidate(); paint() } } }
        objBar.addView(dot, lp(dp(42), dp(42)))
        if (s.t == "text") {
            pill("A−") { ink.editSel { it.w = (it.w / 1.18f).coerceAtLeast(0.012f) } }
            pill("A+") { ink.editSel { it.w = (it.w * 1.18f).coerceAtMost(0.45f) } }
            pill(if (s.bg == 1) "🏷✓" else "🏷") { ink.editSel { it.bg = if (it.bg == 1) 0 else 1 } }
        } else {
            pill("−") { ink.editSel { it.w = (it.w / 1.3f).coerceAtLeast(0.002f) } }
            pill("+") { ink.editSel { it.w = (it.w * 1.3f).coerceAtMost(0.05f) } }
        }
        pill("⧉") { ink.duplicateSel() }
        objBar.addView(tv("🗑", 15f, T.danger, true).apply { gravity = Gravity.CENTER; setPadding(dp(11), dp(9), dp(11), dp(9)); setOnClickListener { v -> Anim.bump(v); ink.deleteSel(); paint() } })
        if (objBar.visibility != View.VISIBLE) {
            objBar.visibility = View.VISIBLE; objBar.alpha = 0f; objBar.translationY = dp(24).toFloat(); objBar.scaleX = 0.9f; objBar.scaleY = 0.9f
            objBar.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }
        if (s.t == "text") hint(L.t("objHint"))
    }

    private fun editObjText(s: Stroke) {
        askText(L.t(if (s.t == "measure") "measureQ" else "writeOnPhoto"), s.txt) { t ->
            if (t.isBlank() && s.t == "text") { ink.deleteSel(); paint() } else ink.editSel { it.txt = t.trim() }
        }
    }

    private var lastHint = ""
    private fun hint(m: String) { if (m != lastHint) { lastHint = m; toast(m) } }

    /** La hoja de dibujo queda exactamente encima de la foto. */
    private fun fit(W: Int, H: Int) {
        val b = bm ?: return
        if (W <= 0 || H <= 0) return
        val k = minOf(W.toFloat() / b.width, H.toFloat() / b.height)
        val lp = ink.layoutParams as FrameLayout.LayoutParams
        val w = (b.width * k).toInt(); val h = (b.height * k).toInt()
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; ink.post { ink.layoutParams = lp } }
        val pl = pinsOver.layoutParams as FrameLayout.LayoutParams
        if (pl.width != w || pl.height != h) { pl.width = w; pl.height = h; pinsOver.post { pinsOver.layoutParams = pl } }
    }

    private fun paint() {
        for (k in listOf("move", "pen", "hl", "arrow", "circle", "rect", "measure", "text", "eraser")) btns[k]?.background = if (ink.tool == k) roundBg(0x33FFFFFF, dp(22).toFloat()) else null
        btns["color"]?.invalidate()
        btns["undo"]?.alpha = if (ink.canUndo()) 1f else 0.35f
        btns["redo"]?.alpha = if (ink.canRedo()) 1f else 0.35f
    }

    private fun done() {
        val b = bm ?: return
        if (ink.strokes.isEmpty()) { finish(); return }
        val strokes = ArrayList(ink.strokes)
        Loader.pool.execute {
            // el dibujo se hace en su propia capa (así el borrador no borra la foto) y luego se pone encima
            val out = b.copy(Bitmap.Config.ARGB_8888, true)
            val layer = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layer)
            for (s in strokes) Stroke.draw(lc, s, b.width.toFloat())
            Canvas(out).drawBitmap(layer, 0f, 0f, null)
            val name = "f" + newId() + ".jpg"
            FileOutputStream(File(Store.mediaDir, name)).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            runOnUiThread {
                setResult(RESULT_OK, Intent().putExtra(CropActivity.OUT, name).putExtra(CropActivity.OUT_W, out.width).putExtra(CropActivity.OUT_H, out.height))
                finish()
            }
        }
    }
}
