package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* ============================================================
   Plan.kt — 🗺️ PLANO CON PINES. Una foto de un plano (o de un
   cuarto) con puntitos encima: 📹 cámaras, 🚪 puertas, 🗄️ racks…
   Cada pin tiene su nombre, una nota y su propia foto.
   Los pines se guardan dentro de la foto de la nota (en 0..1),
   así se ven igual aunque la foto cambie de tamaño.
   ============================================================ */
object Pins {
    class P(var x: Float, var y: Float, var k: String, var t: String, var d: String = "", var f: String = "", var e: String = "") {
        /** El dibujito del pin: el que tú escogiste, o el de su tipo. */
        val emoji get() = e.ifEmpty { kind(k).emoji }
    }
    class Kind(val id: String, val emoji: String, val color: Int, val es: String, val en: String) {
        val label get() = if (L.lang == "es") es else en
    }
    val KINDS = listOf(
        Kind("cam", "📹", 0xFF0A84FF.toInt(), "Cámara", "Camera"),
        Kind("door", "🚪", 0xFFFF9F0A.toInt(), "Puerta", "Door"),
        Kind("rack", "🗄️", 0xFFBF5AF2.toInt(), "Rack", "Rack"),
        Kind("jack", "🔌", 0xFF30D158.toInt(), "Jack / toma", "Jack / outlet"),
        Kind("reader", "💳", 0xFF64D2FF.toInt(), "Lector", "Reader"),
        Kind("warn", "⚠️", 0xFFFF453A.toInt(), "Problema", "Problem"),
        Kind("pin", "📍", 0xFFFF6FB5.toInt(), "Otro", "Other"),
    )
    fun kind(id: String) = KINDS.firstOrNull { it.id == id } ?: KINDS.last()

    fun parse(s: String?): MutableList<P> {
        val out = ArrayList<P>()
        if (s.isNullOrBlank()) return out
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(P(o.optDouble("x").toFloat(), o.optDouble("y").toFloat(), o.optString("k", "pin"), o.optString("t"), o.optString("d"), o.optString("f"), o.optString("e"))) }
        } catch (_: Exception) {}
        return out
    }
    fun json(l: List<P>): String = if (l.isEmpty()) "" else JSONArray().also { a ->
        l.forEach { p -> a.put(JSONObject().put("x", Math.round(p.x * 10000) / 10000.0).put("y", Math.round(p.y * 10000) / 10000.0).put("k", p.k).put("t", p.t).apply { if (p.d.isNotEmpty()) put("d", p.d); if (p.f.isNotEmpty()) put("f", p.f); if (p.e.isNotEmpty()) put("e", p.e) }) }
    }.toString()

    /** ✂ Recortaste la foto: los pines se acomodan (y se quitan los que quedaron afuera). */
    fun crop(s: String, l: Float, t: Float, r: Float, b: Float): String {
        if (s.isBlank() || r - l <= 0f || b - t <= 0f) return s
        val out = parse(s).mapNotNull { p -> val x = (p.x - l) / (r - l); val y = (p.y - t) / (b - t); if (x in 0f..1f && y in 0f..1f) p.also { it.x = x; it.y = y } else null }
        return json(out)
    }
    /** ⟳ Giraste la foto 90° (a la derecha): los pines giran con ella. */
    fun rotate90(s: String): String { if (s.isBlank()) return s; return json(parse(s).onEach { p -> val x = p.x; p.x = 1f - p.y; p.y = x }) }

    /** El texto de los pines (para buscar y para la IA). */
    fun plain(s: String?): String = parse(s).joinToString("\n") { p -> p.emoji + " " + p.t + (if (p.d.isNotEmpty()) ": " + p.d else "") }

    /** Emojis para escoger (también se puede escribir cualquiera). */
    val EMOJIS = listOf("📍", "⭐", "🔥", "💡", "⚡", "🔋", "🔌", "🔧", "🪛", "🧰", "🪜", "🔦", "📶", "📡", "🖥️", "💻", "🖨️", "☎️", "📞", "🔊",
        "🔒", "🔑", "🚨", "🚧", "🧯", "🚿", "🚽", "🛗", "🚪", "🪟", "🛏️", "🛋️", "🏢", "🏠", "🅿️", "🚗", "🌳", "📦", "🗑️", "🧱",
        "✅", "❌", "❓", "❗", "💬", "📝", "📷", "🎥", "🔔", "❤️")

    /** El panel para escoger el emoji del pin (o escribir cualquiera). */
    fun pickEmoji(act: Activity, cur: String, onPick: (String) -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(12), 0, act.dp(12), act.dp(4)) }
        val type = android.widget.EditText(act).apply {
            hint = L.t("planEmojiType"); setText(cur); setTextColor(T.text); setHintTextColor(T.faint); textSize = 22f; gravity = Gravity.CENTER; isSingleLine = true
            background = Glass.make(act, 16f); setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
        }
        box.addView(type)
        val grid = android.widget.GridLayout(act).apply { columnCount = 8; setPadding(0, act.dp(10), 0, 0) }
        var sh: Sheet? = null
        val cell = (act.resources.displayMetrics.widthPixels - act.dp(60)) / 8
        for ((i, em) in EMOJIS.withIndex()) {
            val v = act.tv(em, 26f).apply {
                gravity = Gravity.CENTER
                background = if (em == cur) roundBg((0x55 shl 24) or (T.accent and 0xFFFFFF), act.dp(14).toFloat()) else null
                setOnClickListener { x -> Anim.bump(x, 1.35f); sh?.dismiss(); onPick(em) }
            }
            grid.addView(v, android.widget.GridLayout.LayoutParams().apply { width = cell; height = cell })
            v.scaleX = 0.3f; v.scaleY = 0.3f; v.alpha = 0f
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(100L + i * 8).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        }
        box.addView(grid)
        sh = Sheet(act, "😀 " + L.t("planEmoji"), L.t("planEmojiSub")).custom(box)
            .done(L.t("done")) {
                // lo escrito: solo el primer emoji (o lo primero que escribiste)
                val t = type.text.toString().trim()
                val one = if (t.isEmpty()) "" else { val it = java.text.BreakIterator.getCharacterInstance(); it.setText(t); t.substring(0, it.next().coerceAtLeast(1)) }
                sh?.dismiss(); if (one.isNotEmpty()) onPick(one)
            }.show()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    /**
     * Dibuja un pin (una gotita con su dibujito) con la punta en (x, y).
     * [s] = tamaño (radio de la cabeza), [label] = el nombre debajo (o null), [pop] = 0..1 para la animación al aparecer.
     */
    fun draw(c: Canvas, x: Float, y: Float, s: Float, p: P, label: Boolean, pop: Float = 1f, selected: Boolean = false) {
        val k = kind(p.k)
        c.save(); c.translate(x, y); c.scale(pop, pop)
        // la sombrita
        fill.color = 0x40000000; c.drawOval(-s * 0.55f, -s * 0.18f, s * 0.55f, s * 0.18f, fill)
        if (selected) { fill.color = (0x55 shl 24) or (k.color and 0xFFFFFF); c.drawCircle(0f, -s * 1.55f, s * 1.45f, fill) }
        // la gota
        path.reset()
        path.moveTo(0f, 0f)
        path.cubicTo(-s * 0.35f, -s * 0.6f, -s, -s * 0.95f, -s, -s * 1.55f)
        path.arcTo(-s, -s * 2.55f, s, -s * 0.55f, 180f, 180f, false)
        path.cubicTo(s, -s * 0.95f, s * 0.35f, -s * 0.6f, 0f, 0f)
        path.close()
        fill.color = k.color; c.drawPath(path, fill)
        fill.color = Color.WHITE; c.drawCircle(0f, -s * 1.55f, s * 0.74f, fill)
        txt.textSize = s * 0.95f; txt.color = Color.BLACK
        c.drawText(p.emoji, 0f, -s * 1.55f + s * 0.33f, txt)
        if (label && p.t.isNotEmpty()) {
            txt.textSize = s * 0.78f; txt.isFakeBoldText = true
            val w = txt.measureText(p.t) + s * 0.8f
            fill.color = 0xE6000000.toInt()
            c.drawRoundRect(-w / 2, s * 0.25f, w / 2, s * 1.35f, s * 0.55f, s * 0.55f, fill)
            txt.color = Color.WHITE; c.drawText(p.t, 0f, s * 1.05f, txt)
            txt.isFakeBoldText = false
        }
        // tiene foto: un puntito blanco arriba a la derecha
        if (p.f.isNotEmpty()) { fill.color = Color.WHITE; c.drawCircle(s * 0.78f, -s * 2.25f, s * 0.3f, fill); fill.color = k.color; c.drawCircle(s * 0.78f, -s * 2.25f, s * 0.18f, fill) }
        c.restore()
    }
}

/** Los pines encima de la foto en la nota (solo se ven; para tocarlos se abre el plano). */
class PinsOverlay(ctx: Context) : View(ctx) {
    var pins: List<Pins.P> = emptyList()
        set(v) { field = v; visibility = if (v.isEmpty()) GONE else VISIBLE; invalidate() }
    init { isClickable = false; isFocusable = false }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val s = (minOf(width, height) / 22f).coerceIn(6 * d, 11 * d)
        for (p in pins) Pins.draw(c, p.x * width, p.y * height, s, p, width > 140 * d)
    }
}

/** 🗺️ La pantalla del plano: zoom, poner pines, moverlos y llenarlos. */
class PlanActivity : Activity() {
    companion object { const val FILE = "archivo"; const val PINS = "pines"; const val OUT = "pinesOut"; private const val REQ_CAM = 81; private const val REQ_GAL = 82; private const val REQ_PERM = 83 }

    private lateinit var view: PlanView
    private lateinit var countTv: TextView
    private lateinit var hintTv: TextView
    private val chips = HashMap<String, TextView>()
    private var addKind: String? = null
    private var photoFor: Pins.P? = null
    private var camFile: File? = null
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        val file = intent.getStringExtra(FILE) ?: run { finish(); return }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0B0D.toInt()) }
        edgeToEdge(root); window.statusBarColor = 0xFF0B0B0D.toInt()
        view = PlanView(this)
        view.pins = Pins.parse(intent.getStringExtra(PINS))
        view.onTapPin = { p -> pinSheet(p) }
        view.onTapEmpty = { x, y -> addAt(x, y) }
        view.onMoved = { changed = true; paintCount() }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(34), dp(8), dp(6)) }
        top.addView(tv(L.t("cancel"), 16f, Color.WHITE).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); background = Glass.make(this@PlanActivity, 20f, strong = true); setOnClickListener { finish() } })
        top.addView(tv("🗺️ " + L.t("planTitle"), 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(tv(L.t("done"), 16f, Color.WHITE, true).apply { setPadding(dp(16), dp(10), dp(16), dp(10)); background = roundBg(T.accent, dp(20).toFloat()); setOnClickListener { finishOk() } })
        col.addView(top)
        countTv = tv("", 13.5f, Color.WHITE, true).apply { setPadding(dp(14), dp(7), dp(14), dp(7)); background = Glass.make(this@PlanActivity, 16f, strong = true) }
        col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(countTv) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ⬇ los tipos de pin
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(26)) }
        hintTv = tv("👆 " + L.t("planHint"), 13.5f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); background = Glass.make(this@PlanActivity, 16f, strong = true) }
        bottom.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), 0, dp(16), dp(10)); addView(hintTv) })
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(10), 0, dp(10), 0) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, k) in Pins.KINDS.withIndex()) {
            val c = tv(k.emoji + " " + k.label, 14f, Color.WHITE, true).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10)); background = Glass.make(this@PlanActivity, 20f, strong = true)
                setOnClickListener { v -> Anim.bump(v); pickKind(k.id) }
            }
            chips[k.id] = c
            row.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            Anim.enter(c, 120L + i * 50, 24f)
        }
        hs.addView(row); bottom.addView(hs)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        paintCount()
        Loader.pool.execute {
            val dm = resources.displayMetrics
            val b = Thumbs.decode(File(Store.mediaDir, file), maxOf(dm.widthPixels, dm.heightPixels) * 2)
            runOnUiThread { if (b == null) { toast(L.t("cantOpen")); finish() } else view.setBitmap(b) }
        }
    }

    private fun pickKind(id: String) {
        addKind = if (addKind == id) null else id
        for ((k, c) in chips) c.background = if (k == addKind) roundBg(Pins.kind(k).color, dp(20).toFloat()) else Glass.make(this, 20f, strong = true)
        hintTv.text = if (addKind != null) "👇 " + L.t("planWhere", "k" to Pins.kind(addKind!!).label) else "👆 " + L.t("planHint")
        Anim.bump(hintTv, 1.06f)
    }

    private fun addAt(x: Float, y: Float) {
        val k = addKind ?: run { Anim.bump(hintTv, 1.12f); return }
        val n = view.pins.count { it.k == k } + 1
        val p = Pins.P(x, y, k, Pins.kind(k).label + " " + n)
        view.add(p); changed = true; paintCount()
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        if (k == "pin") view.postDelayed({ Pins.pickEmoji(this, "") { e -> p.e = e; view.invalidate(); paintCount(); pinSheet(p, fresh = true) } }, 450)
        else view.postDelayed({ pinSheet(p, fresh = true) }, 450)
    }

    private fun paintCount() {
        val l = view.pins
        countTv.text = if (l.isEmpty()) L.t("planEmpty") else l.groupBy { it.emoji }.map { (e, g) -> "$e ${g.size}" }.joinToString("   ")
    }

    /** La hoja de un pin: nombre, nota, foto, tipo, borrar. */
    private fun pinSheet(p: Pins.P, fresh: Boolean = false) {
        view.selected = p
        val k = Pins.kind(p.k)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(6)) }
        fun field(hint: String, value: String, multi: Boolean) = android.widget.EditText(this).apply {
            this.hint = hint; setText(value); setTextColor(T.text); setHintTextColor(T.faint); textSize = 15.5f
            background = Glass.make(this@PlanActivity, 14f); setPadding(dp(14), dp(10), dp(14), dp(10))
            if (multi) { minLines = 2; maxLines = 6 } else isSingleLine = true
        }
        val name = field(L.t("planName"), p.t, false)
        val note = field(L.t("planNote"), p.d, true)
        box.addView(name); box.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // 📷 la foto del pin
        if (p.f.isNotEmpty()) {
            val iv = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundBg(0, dp(14).toFloat()) }
            Thumbs.load(p.f, dp(320)) { bm -> iv.setImageBitmap(bm) }
            iv.setOnClickListener { PhotoViewer.show(this, p.f) }
            box.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply { topMargin = dp(10) })
        }
        var sh: Sheet? = null
        var deleted = false
        fun save() {
            val t = name.text.toString().trim(); val d = note.text.toString().trim()
            if (t != p.t || d != p.d) { p.t = t; p.d = d; changed = true; view.invalidate() }
        }
        sh = Sheet(this, p.emoji + " " + p.t.ifEmpty { k.label }, L.t("planPinSub"))
            .custom(box)
            .quick(Icons.SCAN, L.t("camera"), Sheet.PINK) { save(); photoFor = p; takePhoto() }
            .quick(Icons.IMAGE, L.t("gallery"), Sheet.BLUE) { save(); photoFor = p; try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_GAL) } catch (_: Exception) { toast(L.t("cantOpen")) } }
            .quick(MoreIcons.PALETTE, L.t("planKind"), Sheet.VIOLET) { save(); kindMenu(p) }
            .quick(Icons.EMOJI, L.t("planEmoji"), Sheet.AMBER) { save(); Pins.pickEmoji(this, p.e) { e -> p.e = e; changed = true; view.invalidate(); paintCount(); pinSheet(p) } }
            .quick(Icons.TRASH, L.t("del"), Sheet.RED) { deleted = true; view.remove(p); changed = true; paintCount() }
            .done(L.t("done")) { save(); sh?.dismiss() }
        // ✍️ lo que escribiste se guarda aunque cierres el panel deslizándolo
        sh!!.onClose = { if (!deleted) save(); if (view.selected === p) view.selected = null; paintCount() }
        sh!!.show()
        if (fresh) name.post { name.requestFocus(); name.selectAll() }
    }

    private fun kindMenu(p: Pins.P) {
        val sh = Sheet(this, L.t("planKind"))
        for (k in Pins.KINDS) sh.item(MoreIcons.TEXT_T, k.emoji + "  " + k.label, Sheet.SKY) {
            p.k = k.id; changed = true
            if (k.id == "pin") Pins.pickEmoji(this, p.e) { e -> p.e = e; view.invalidate(); paintCount(); pinSheet(p) }
            else { p.e = ""; view.invalidate(); paintCount(); pinSheet(p) }
        }
        sh.show()
    }

    private fun takePhoto() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_PERM); return }
        val f = File(cacheDir, "pin_" + System.currentTimeMillis() + ".jpg"); camFile = f
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.archivos", f)
        try { startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_CAM) } catch (_: Exception) { toast(L.t("cantOpen")) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) takePhoto()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val p = photoFor ?: return
        if (resultCode != RESULT_OK) return
        val uri = when (requestCode) { REQ_CAM -> camFile?.let { android.net.Uri.fromFile(it) }; REQ_GAL -> data?.data; else -> null } ?: return
        Loader.pool.execute {
            val r = try { Photos.import(this, uri) } catch (_: Throwable) { null }
            if (requestCode == REQ_CAM) camFile?.delete()
            runOnUiThread {
                if (r == null) { toast(L.t("photoFail")); return@runOnUiThread }
                p.f = r.file; changed = true; view.invalidate(); Anim.sparkle(view, 40f, 500)
                pinSheet(p)
            }
        }
    }

    private fun finishOk() {
        setResult(RESULT_OK, Intent().putExtra(OUT, Pins.json(view.pins)))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Sheet.closeTop(this)) return
        if (changed) confirm(L.t("planSaveQ"), null, L.t("save")) { finishOk() } else super.onBackPressed()
    }
}

/** El plano: se agranda con dos dedos, se mueve con un dedo, y tiene los pines encima. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PlanView(ctx: Context) : View(ctx) {
    var pins: MutableList<Pins.P> = ArrayList()
    var selected: Pins.P? = null
        set(v) { field = v; invalidate() }
    var onTapPin: ((Pins.P) -> Unit)? = null
    var onTapEmpty: ((Float, Float) -> Unit)? = null
    var onMoved: (() -> Unit)? = null
    private var bm: Bitmap? = null
    private val m = Matrix(); private val inv = Matrix()
    private var base = 1f
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val d = resources.displayMetrics.density
    private val pops = HashMap<Pins.P, Float>()

    fun setBitmap(b: Bitmap) { bm = b; fit(); alpha = 0f; animate().alpha(1f).setDuration(300).start(); invalidate() }
    private fun fit() {
        val b = bm ?: return
        if (width == 0) return
        val top = 130 * d; val bottom = 120 * d; val h = height - top - bottom
        base = minOf(width.toFloat() / b.width, h / b.height)
        m.reset(); m.postScale(base, base); m.postTranslate((width - b.width * base) / 2, top + (h - b.height * base) / 2)
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    fun add(p: Pins.P) {
        pins.add(p); pops[p] = 0f
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 520; interpolator = android.view.animation.BounceInterpolator(); addUpdateListener { pops[p] = it.animatedValue as Float; invalidate() }; addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { pops.remove(p) } }); start() }
    }
    fun remove(p: Pins.P) {
        ValueAnimator.ofFloat(1f, 0f).apply { duration = 220; addUpdateListener { pops[p] = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { pins.remove(p); pops.remove(p); if (selected === p) selected = null; invalidate(); onMoved?.invoke() } }); start() }
    }

    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }
    private val r = RectF()
    private fun imgRect(): RectF { val b = bm; r.set(0f, 0f, (b?.width ?: 1).toFloat(), (b?.height ?: 1).toFloat()); m.mapRect(r); return RectF(r) }
    private fun pinSize() = (12 * d * Math.sqrt((scale() / base).toDouble()).toFloat()).coerceIn(11 * d, 22 * d)

    override fun onDraw(c: Canvas) {
        val b = bm ?: return
        c.drawBitmap(b, m, bmpP)
        val ir = imgRect(); val s = pinSize()
        for (p in pins) Pins.draw(c, ir.left + p.x * ir.width(), ir.top + p.y * ir.height() - (if (p === drag) 14 * d else 0f), s, p, true, pops[p] ?: 1f, p === selected)
    }

    /** El pin debajo del dedo (se toca la cabeza, que está arriba de la punta). */
    private fun hit(x: Float, y: Float): Pins.P? {
        val ir = imgRect(); val s = pinSize()
        return pins.lastOrNull { p -> val px = ir.left + p.x * ir.width(); val py = ir.top + p.y * ir.height() - s * 1.5f; Math.hypot((x - px).toDouble(), (y - py).toDouble()) < s * 1.8f }
    }

    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var moved = false; private var multi = false
    private var drag: Pins.P? = null
    private var downPin: Pins.P? = null
    private val longPress = Runnable { downPin?.let { p -> drag = p; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); invalidate() } }
    private val sg = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: android.view.ScaleGestureDetector): Boolean {
            val now = scale(); val k = (now * g.scaleFactor).coerceIn(base, base * 8) / now
            m.postScale(k, k, g.focusX, g.focusY); clamp(); invalidate(); return true
        }
    })
    private fun clamp() {
        if (scale() <= base * 1.001f) { fit(); return }
        val ir = imgRect()
        val dx = if (ir.left > width * 0.5f) width * 0.5f - ir.left else if (ir.right < width * 0.5f) width * 0.5f - ir.right else 0f
        val dy = if (ir.top > height * 0.5f) height * 0.5f - ir.top else if (ir.bottom < height * 0.5f) height * 0.5f - ir.bottom else 0f
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bm == null) return true
        sg.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; moved = false; multi = false; drag = null
                downPin = hit(e.x, e.y)
                if (downPin != null) postDelayed(longPress, 320)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multi = true; removeCallbacks(longPress); drag = null; lastX = (e.getX(0) + e.getX(1)) / 2; lastY = (e.getY(0) + e.getY(1)) / 2 }
            MotionEvent.ACTION_MOVE -> {
                if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > 10 * d) { moved = true; if (drag == null) removeCallbacks(longPress) }
                val dp = drag
                if (dp != null) {
                    // ✋ mover el pin (deja presionado y arrastra)
                    val ir = imgRect()
                    dp.x = ((e.x - ir.left) / ir.width()).coerceIn(0f, 1f); dp.y = ((e.y + 14 * d - ir.top) / ir.height()).coerceIn(0f, 1f)
                    invalidate()
                } else if (multi && e.pointerCount >= 2) {
                    val x = (e.getX(0) + e.getX(1)) / 2; val y = (e.getY(0) + e.getY(1)) / 2
                    m.postTranslate(x - lastX, y - lastY); clamp(); lastX = x; lastY = y; invalidate()
                } else if (!multi && moved && scale() > base * 1.01f) {
                    m.postTranslate(e.x - lastX, e.y - lastY); clamp(); lastX = e.x; lastY = e.y; invalidate()
                } else { lastX = e.x; lastY = e.y }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val dp = drag; drag = null
                if (dp != null) { invalidate(); onMoved?.invoke(); return true }
                if (!moved && !multi) {
                    val p = downPin
                    if (p != null) onTapPin?.invoke(p)
                    else {
                        val ir = imgRect()
                        if (ir.contains(e.x, e.y)) onTapEmpty?.invoke((e.x - ir.left) / ir.width(), (e.y - ir.top) / ir.height())
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); drag = null; invalidate() }
        }
        return true
    }
}
