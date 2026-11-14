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
    companion object { const val FILE = "archivo" }

    private lateinit var file: String
    private lateinit var ink: InkView
    private lateinit var img: ImageView
    private var bm: Bitmap? = null
    private val btns = HashMap<String, View>()

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
        ink = InkView(this).apply { drawing = true; color = 0xFFFF453A.toInt() }
        area.addView(ink, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        root.addView(area, lp(MATCH, 0, 1f))
        ink.onChange = { paint() }
        ink.onTextAt = { x, y ->
            askText(L.t("writeOnPhoto")) { t -> if (t.isNotBlank()) { ink.addText(x, y, t.trim(), floatArrayOf(0.045f, 0.065f, 0.095f)[ink.size]) } }
        }
        area.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fit(r - l, b - t) }

        // Herramientas
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(0xFF1C1C1F.toInt()) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        fun b(k: String, icon: Int, act: () -> Unit) { val v = iconBtn(icon, 10.5f) { act() }; btns[k] = v; row.addView(v) }
        b("pen", MoreIcons.PEN) { ink.tool = "pen"; paint() }
        b("hl", MoreIcons.HILITE) { ink.tool = "hl"; paint() }
        b("text", MoreIcons.TEXT_T) { ink.tool = "text"; paint(); toast(L.t("tapToWrite")) }
        b("eraser", MoreIcons.ERASER) { ink.tool = "eraser"; paint() }
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
        b("undo", Icons.UNDO) { ink.undo(); paint() }
        b("redo", MoreIcons.REDO) { ink.redoOne(); paint() }
        hs.addView(row)
        root.addView(hs, lp(MATCH, WRAP))
        setContentView(root)
        paint()
        Loader.pool.execute {
            val b = Thumbs.decode(File(Store.mediaDir, file), Photos.MAX_SIDE)
            runOnUiThread { if (b == null) finish() else { bm = b; img.setImageBitmap(b); (area.width to area.height).let { fit(it.first, it.second) } } }
        }
    }

    /** La hoja de dibujo queda exactamente encima de la foto. */
    private fun fit(W: Int, H: Int) {
        val b = bm ?: return
        if (W <= 0 || H <= 0) return
        val k = minOf(W.toFloat() / b.width, H.toFloat() / b.height)
        val lp = ink.layoutParams as FrameLayout.LayoutParams
        val w = (b.width * k).toInt(); val h = (b.height * k).toInt()
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; ink.post { ink.layoutParams = lp } }
    }

    private fun paint() {
        for (k in listOf("pen", "hl", "text", "eraser")) btns[k]?.background = if (ink.tool == k) roundBg(0x33FFFFFF, dp(22).toFloat()) else null
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
