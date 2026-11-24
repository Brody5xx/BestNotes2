package com.luisell.bestnotes2

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import java.io.File
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/* ============================================================
   MainActivity.kt — la pantalla principal:
   ☰ menú · buscar (ilumina las palabras) · lista o cuadrícula
   · + nota nueva · ☑ checklist nuevo · mantener presionado
   para seleccionar varias.
   ============================================================ */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("ajustes", 0) }
    private lateinit var root: FrameLayout
    private lateinit var header: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var lm: StaggeredGridLayoutManager
    private val adapter = NotesAdapter()
    private var view = "all"
    private var query = ""
    private val selected = LinkedHashSet<String>()
    private var drawer: View? = null
    private val listener: () -> Unit = { refresh() }

    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        buildUi()
        Store.addListener(listener)
        Store.load { refresh() }
        handleIntent(intent)
        if (savedInstanceState == null && !introShown && android.os.Build.FINGERPRINT != "robolectric") { introShown = true; intro() }
    }

    override fun onDestroy() { Store.removeListener(listener); introRunning = false; if (host === this) host = null; super.onDestroy() }
    override fun onStart() { super.onStart(); host = this }
    override fun onStop() { if (host === this) host = null; super.onStop() }

    /**
     * 📷 Con la cámara de fondo, la nota se abre ENCIMA y usa la misma cámara de aquí (que nunca se apaga ni se cambia):
     * solo se esconden las notas de esta pantalla. Así abrir y cerrar es suave, sin congelarse.
     */
    fun fadeUi(show: Boolean, ms: Long) {
        if (!::root.isInitialized) return
        root.animate().cancel()
        if (ms <= 0) { root.alpha = if (show) 1f else 0f; return }
        root.animate().alpha(if (show) 1f else 0f).setDuration(ms).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    /** Lo que falta de la presentación cuando ya pusiste el PIN. */
    private var afterLock: (() -> Unit)? = null

    override fun onResume() {
        super.onResume(); if (Store.loaded) refresh()
        // 🔎 leer (de a poquito) lo escrito en las fotos para el buscador
        if (android.os.Build.FINGERPRINT != "robolectric") Store.load { Ocr.indexLater(this) }
        if (android.os.Build.FINGERPRINT != "robolectric") Store.load { checkNear() }
        afterLock?.let { f -> if (AppLock.unlocked) root.postDelayed({ f() }, 120) }
        pendingTrash?.let { ids -> pendingTrash = null; root.postDelayed({ trashSnack(ids) }, 350) }
    }

    /* ---------- pantalla ---------- */
    private fun buildUi() {
        root = FrameLayout(this)
        edgeToEdge(root)
        // cambio de tema con la cámara detrás: desde el primer instante se ve la pantalla de antes (sin negro)
        snap?.let { if (Wall.current(this) == "phone" && Translucent.camera(this)) { window.setWindowAnimations(0); window.setBackgroundDrawable(android.graphics.drawable.BitmapDrawable(resources, it)) } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), 0) }
        col.addView(header, lp(MATCH, dp(60)))
        // Título grande y cuántas notas (como "All notes / 35 notes" en Best Notes)
        titleTv = tv("", 34f, T.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(18), dp(10), dp(18), 0) }
        countTv = tv("", 15f, T.muted).apply { setPadding(dp(18), 0, dp(18), dp(6)) }
        col.addView(titleTv, lp(MATCH, WRAP)); col.addView(countTv, lp(MATCH, WRAP))
        nearPill = tv("", 14f, T.text, true).apply {
            visibility = View.GONE; setPadding(dp(14), dp(9), dp(14), dp(9)); background = Glass.make(this@MainActivity, 18f)
            setOnClickListener { Anim.press(it); view = "near"; selected.clear(); refresh(); list.scrollToPosition(0) }
        }
        col.addView(nearPill, lp(WRAP, WRAP).apply { setMargins(dp(14), dp(2), dp(14), dp(6)) })

        // Buscar
        val sb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.make(this@MainActivity, 24f); setPadding(dp(16), 0, dp(4), 0)
        }
        sb.addView(IconView(this, Icons.SEARCH, 8.5f).apply { color = T.muted; isClickable = false }, lp(dp(22), dp(22)))
        search = EditText(this).apply { niceCursor()
            background = null; hint = L.t("searchPh"); setHintTextColor(T.faint); setTextColor(T.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1; isSingleLine = true
            setPadding(dp(10), 0, 0, 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        sb.addView(search, lp(0, MATCH, 1f))
        val clear = iconBtn(Icons.CLOSE, 8f, T.muted) { search.setText(""); hideKb() }
        clear.visibility = View.GONE
        sb.addView(clear, lp(dp(40), dp(40)))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) { query = s.toString(); clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE; refresh() }
        })
        col.addView(sb, lp(MATCH, dp(50)).apply { setMargins(dp(14), dp(4), dp(14), dp(10)) })

        val fl = FrameLayout(this)
        lm = StaggeredGridLayoutManager(if (prefs.getString("layout", "grid") == "grid") 2 else 1, StaggeredGridLayoutManager.VERTICAL)
        list = RecyclerView(this).apply {
            layoutManager = lm; adapter = this@MainActivity.adapter
            setPadding(dp(10), 0, dp(10), dp(110)); clipToPadding = false
            itemAnimator = null
            setHasFixedSize(false)
        }
        fl.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        empty = tv("", 16f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(30), dp(80), dp(30), 0) }
        fl.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(fl, lp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // Botones de nota nueva
        val fabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ck = IconView(this, MoreIcons.CHECKBOX, 10f).apply {
            color = T.text; background = Glass.make(this@MainActivity, 26f, strong = true)
            setOnClickListener { pop(it); newNote("check", it) }
            contentDescription = L.t("newCheck")
        }
        val pen = IconView(this, MoreIcons.PEN, 10f).apply {
            color = T.text; background = Glass.make(this@MainActivity, 26f, strong = true)
            setOnClickListener { pop(it); newNote("draw", it) }
            contentDescription = L.t("newDraw")
        }
        fabs.addView(pen, lp(dp(52), dp(52)).apply { marginEnd = dp(12) })
        fabs.addView(ck, lp(dp(52), dp(52)).apply { marginEnd = dp(12) })
        val fab = IconView(this, MoreIcons.PLUS, 12f).apply {
            color = T.onPill; pressBg(T.text, 30f); elevation = dp(8).toFloat()
            setOnClickListener { pop(it); newNote("text", it) }
            contentDescription = L.t("newNote")
        }
        fabs.addView(fab, lp(dp(60), dp(60)))
        root.addView(fabs, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(24)) })
        fabBox = fabs
        // ✨ los botones de nota nueva entran rebotando, uno tras otro
        Anim.popIn(fab, 250); Anim.popIn(ck, 330); Anim.popIn(pen, 410)
        // al bajar en la lista se esconden; al subir regresan
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 12 && !fabsHidden) { fabsHidden = true; fabs.animate().translationY(dp(120).toFloat()).alpha(0f).setDuration(220).start() }
                else if (dy < -12 && fabsHidden) { fabsHidden = false; fabs.animate().translationY(0f).alpha(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start() }
            }
        })
        setContentView(root)
        root.isFocusableInTouchMode = true; root.requestFocus()
        paintHeader()
        themeReveal()
    }

    /* ---------- ☀️/🌙 cambiar de modo con un círculo que crece desde el botón ---------- */
    /** 🎬 La presentación al abrir la app: el logo se infla girando, salen estrellitas y se abre la pantalla. */
    private fun intro() {
        // la presentación tapa todo (con la cámara o translúcido, el fondo de siempre: así no se ven las notas antes del PIN)
        val ov = FrameLayout(this).apply { background = if (Wall.current(this@MainActivity) == "phone") Wall.drawable(this@MainActivity, Wall.defaultId()) else Glass.background(this@MainActivity); isClickable = true; elevation = dp(30).toFloat() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val logo = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); clipToOutline = true; background = roundBg(0, dp(30).toFloat()); elevation = dp(12).toFloat() }
        col.addView(logo, lp(dp(116), dp(116)))
        val name = tv("BEST NOTES", 22f, T.text, true).apply { letterSpacing = 0.3f; gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0) }
        col.addView(name)
        ov.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        logo.scaleX = 0.2f; logo.scaleY = 0.2f; logo.rotation = -35f; logo.alpha = 0f
        name.alpha = 0f; name.translationY = dp(20).toFloat(); name.letterSpacing = 0.8f
        logo.animate().scaleX(1f).scaleY(1f).rotation(0f).alpha(1f).setDuration(620).setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
            .withEndAction { Anim.sparkle(logo, 110f, 800) }.start()
        name.animate().alpha(1f).translationY(0f).setStartDelay(260).setDuration(520).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        android.animation.ValueAnimator.ofFloat(0.8f, 0.3f).apply { startDelay = 260; duration = 620; addUpdateListener { name.letterSpacing = it.animatedValue as Float } }.start()
        introRunning = true
        var gone = false
        val reallyLeave = {
            if (!gone) {
                gone = true; introRunning = false; afterLock = null
                ov.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(380).setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { root.removeView(ov); dropHeader() }.start()
            }
        }
        val leave = {
            // 🔒 con PIN: primero la presentación, DESPUÉS pide el PIN (la presentación se queda tapando las notas)
            if (!gone && afterLock == null && AppLock.on(this) && !AppLock.unlocked) {
                afterLock = reallyLeave
                startActivity(Intent(this, LockActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } else if (afterLock == null) reallyLeave()
        }
        ov.setOnClickListener { leave() }
        ov.postDelayed({ leave() }, 1250)
    }

    companion object {
        /** La presentación sale una sola vez cada vez que se abre la app. */
        private var introShown = false
        /** La pantalla principal mientras se ve (la nota usa su cámara de fondo). */
        var host: MainActivity? = null
        /** ¿La nota puede usar la cámara de la pantalla principal (que está debajo)? */
        fun sharesCamera(a: android.app.Activity) = a is NoteActivity && host != null && Wall.current(a) == "phone" && Translucent.camera(a)
        /** Mientras sale la presentación, el candado espera (sale después). */
        @Volatile var introRunning = false
        const val PICK_FILE = 41; const val REQ_WALL = 42; const val REQ_SAVE_BK = 43; const val REQ_AUTO_DIR = 44; const val REQ_CAM_BG = 45; const val REQ_LOC = 46
        /** Notas que se acaban de mandar a la papelera desde una nota (para "Deshacer"). */
        var pendingTrash: List<String>? = null
        private var snap: android.graphics.Bitmap? = null
        private var camSnap = false
        private var sx = 0; private var sy = 0
    }

    private fun switchTheme(light: Boolean, from: View?) {
        if (T.light == light) return
        val dv = window.decorView
        try {
            val bm = android.graphics.Bitmap.createBitmap(dv.width, dv.height, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bm)
            // 📷 con la cámara detrás: primero la imagen de la cámara, después la pantalla encima
            val cam = CameraBg.of(this)
            camSnap = false
            cam?.frame()?.let { f -> val l = IntArray(2); cam.getLocationInWindow(l); cv.drawBitmap(f, null, android.graphics.Rect(l[0], l[1], l[0] + cam.width, l[1] + cam.height), null); camSnap = true }
            cam?.stillHidden(true)
            dv.draw(cv); snap = bm
            cam?.stillHidden(false)
            val loc = IntArray(2)
            if (from != null) { from.getLocationInWindow(loc); sx = loc[0] + from.width / 2; sy = loc[1] + from.height / 2 } else { sx = dv.width - dp(40); sy = dp(60) }
        } catch (_: Throwable) { snap = null }
        prefs.edit().putString("theme", if (light) "light" else "dark").apply()
        if (!prefs.getBoolean("themeTip", false)) { prefs.edit().putBoolean("themeTip", true).apply(); toast(L.t("themeTip")) }
        T.light = light
        Sheet.closeTop(this)
        // 📷 con la cámara detrás: sin la animación del sistema al rearmar la pantalla (esa se veía negra)
        if (Wall.current(this) == "phone" && Translucent.camera(this)) window.setWindowAnimations(0)
        recreate()
    }

    private fun themeReveal() {
        val bm = snap ?: return
        snap = null
        val dv = window.decorView as ViewGroup
        if (Wall.current(this) == "phone" && Translucent.camera(this)) {
            // 📷 cámara detrás: la foto de antes va ENCIMA de todo (también de fondo de la ventana, para que nunca se vea negro)
            // y se desvanece cuando la cámara ya prendió
            window.setBackgroundDrawable(android.graphics.drawable.BitmapDrawable(resources, bm))
            val cover = ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.FIT_XY; isClickable = false }
            dv.addView(cover, ViewGroup.LayoutParams(MATCH, MATCH))
            cover.postDelayed({
                cover.animate().alpha(0f).setDuration(450).withEndAction { dv.removeView(cover); window.setBackgroundDrawable(Glass.background(this)) }.start()
            }, 650)
            return
        }
        val old = ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.FIT_XY }
        dv.addView(old, 0, ViewGroup.LayoutParams(MATCH, MATCH))          // lo de antes queda DEBAJO
        root.background = Glass.background(this)
        root.post {
            val r = Math.hypot(maxOf(sx, root.width - sx).toDouble(), maxOf(sy, root.height - sy).toDouble()).toFloat()
            val a = android.view.ViewAnimationUtils.createCircularReveal(root, sx, sy, 0f, r)
            a.duration = 650; a.interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(x: android.animation.Animator) { dv.removeView(old); root.background = null; bm.recycle() }
            })
            a.start()
            val sp = SparkleView(this, Fx.ring(this, sx.toFloat(), sy.toFloat(), r * 0.35f))
            dv.addView(sp, ViewGroup.LayoutParams(MATCH, MATCH)); sp.play(800)
        }
    }

    private lateinit var fabBox: View

    private fun paintHeader() {
        header.removeAllViews()
        paintNear()
        val hMode = (if (selected.isNotEmpty()) "sel" else view.takeIf { it == "trash" } ?: ""); val hChanged = hMode != headerMode; headerMode = hMode
        val newTitle = viewName()
        if (newTitle != lastTitle && lastTitle.isNotEmpty()) {
            titleTv.alpha = 0f; titleTv.translationX = -dp(30).toFloat()
            titleTv.animate().alpha(1f).translationX(0f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
            countTv.alpha = 0f; countTv.animate().alpha(1f).setStartDelay(120).setDuration(380).start()
        }
        lastTitle = newTitle
        titleTv.text = newTitle
        val total = visibleCount
        countTv.text = (if (total == 1) L.t("nNotes1") else L.t("nNotes", "n" to total)) + if (view == "trash") "  ·  " + L.t("trashAuto") else ""
        if (selected.isNotEmpty()) {
            header.addView(glassBtn(Icons.CLOSE) { selected.clear(); refresh() })
            header.addView(View(this), lp(0, 1, 1f))
            titleTv.text = if (selected.size == 1) L.t("selected1") else L.t("selected", "n" to selected.size)
            if (view == "trash") {
                header.addView(glassBtn(MoreIcons.RESTORE) { selRecover() })
                header.addView(glassBtn(Icons.TRASH, 10f, T.danger) { selDeleteForever() })
            } else {
                header.addView(glassBtn(Icons.PIN, 9f) { selPin() })
                header.addView(glassBtn(MoreIcons.STAR5, 9f) { selFav() })
                header.addView(glassBtn(MoreIcons.FOLDER, 9f) { selFolder() })
                header.addView(glassBtn(MoreIcons.PALETTE, 9f) { selColor() })
                header.addView(glassBtn(MoreIcons.IMPORT, 9f) { shareNotes(selected.toList()) { selected.clear(); refresh() } })
                header.addView(glassBtn(Icons.TRASH, 9f) { selTrash() })
            }
            // botones un poquito más chicos para que quepan todos
            for (i in 0 until header.childCount) header.getChildAt(i).let { v -> if (v is IconView) v.layoutParams = lp(dp(42), dp(42)).apply { marginStart = dp(2); marginEnd = dp(2) } }
            fabBox.visibility = View.GONE
            if (hChanged) dropHeader()
            return
        }
        fabBox.visibility = if (view == "trash") View.GONE else View.VISIBLE
        // Fila de botones redondos de vidrio (como en Best Notes)
        val spread = { header.addView(View(this), lp(0, 1, 1f)) }
        header.addView(glassBtn(Icons.MENU) { openDrawer() })
        if (view == "trash") {
            spread()
            header.addView(tv(L.t("emptyTrash"), 14f, T.danger, true).apply { setPadding(dp(16), dp(10), dp(16), dp(10)); background = Glass.make(this@MainActivity, 22f); setOnClickListener { emptyTrash() } })
        } else {
            // la misma fila de Best Notes: ☰ · calculadora · bóveda · ordenar · vista · ☀️ apariencia
            val grid = lm.spanCount == 2
            spread(); header.addView(glassBtn(Icons.CALC) { startActivity(Intent(this, CalcActivity::class.java)) })
            spread(); header.addView(glassBtn(MoreIcons.SHIELD) { startActivity(Intent(this, VaultActivity::class.java)) })
            spread(); header.addView(glassBtn(MoreIcons.SORT) { pickSort() })
            spread(); header.addView(glassBtn(if (grid) MoreIcons.LISTV else MoreIcons.GRID) { toggleLayout() })
            // ☀️/🌙 un toque cambia claro ↔ oscuro; dejándolo presionado salen los fondos
            // un toque: claro ↔ oscuro · dejándolo presionado: 🖼 fondo de pantalla (la bolita de abajo lo recuerda)
            val sunBox = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
            val sunBtn = glassBtn(if (T.light) MoreIcons.MOON else MoreIcons.SUN) { v ->
                switchTheme(!T.light, v)
                val n = prefs.getInt("sunTaps", 0)
                if (!prefs.getBoolean("wallUsed", false) && n < 4) { prefs.edit().putInt("sunTaps", n + 1).apply(); root.postDelayed({ toast(L.t("themeTip")) }, 700) }
            }.apply { setOnLongClickListener { v -> prefs.edit().putBoolean("wallUsed", true).apply(); v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); appearance(); true } }
            sunBox.addView(sunBtn, FrameLayout.LayoutParams(MATCH, MATCH))
            // 🖼 bolita con un cuadrito de foto en la esquina: "aquí también está el fondo"
            val badge = FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF48FB1.toInt(), 0xFFB388FF.toInt())).apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(1), if (T.light) 0xFFFFFFFF.toInt() else 0xFF1C1C20.toInt()) }
                isClickable = false; elevation = dp(2).toFloat()
                addView(IconView(this@MainActivity, MoreIcons.WALLPAPER, 3.4f).apply { color = 0xFFFFFFFF.toInt(); isClickable = false }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            sunBox.addView(badge, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.BOTTOM or Gravity.END))
            // mientras no lo hayas descubierto, la bolita "late" de vez en cuando
            if (!prefs.getBoolean("wallUsed", false)) {
                val beat = object : Runnable { override fun run() {
                    if (!badge.isAttachedToWindow || prefs.getBoolean("wallUsed", false)) return
                    badge.animate().scaleX(1.35f).scaleY(1.35f).setDuration(220).withEndAction { badge.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start() }.start()
                    badge.postDelayed(this, 4200)
                } }
                badge.postDelayed(beat, 1800)
            }
            spread(); header.addView(sunBox, lp(dp(44), dp(44)).apply { marginStart = dp(3); marginEnd = dp(3) })
            // 💫 Nova (la IA) en la esquina de arriba, siempre moviéndose
            spread(); header.addView(AiOrb(this).apply { setOnClickListener { burst(); Anim.sparkle(this, 34f, 650); postDelayed({ aiHub() }, 160) } }, lp(dp(44), dp(44)))
            // que quepan los 7 en cualquier teléfono: si la pantalla es angosta, los botones se achican un poquito
            val wDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            if (wDp < 400) for (i in 0 until header.childCount) header.getChildAt(i).let { v -> if (v.layoutParams.width > 0) v.layoutParams = lp(dp(40), dp(40)).apply { marginStart = dp(1); marginEnd = dp(1) } }
        }
        if (hChanged) dropHeader()
    }

    /** 💫 Lo que hace la IA desde la pantalla principal. */
    private fun aiHub() {
        Sheet(this, "✨ " + L.t("ai"), L.t("aiFree"))
            .item(Icons.REPORT, L.t("report"), Sheet.VIOLET, L.t("reportHint")) { startActivity(Intent(this, ReportActivity::class.java)) }
            .item(Icons.BOX, L.t("materials"), Sheet.AMBER, L.t("materialsHint")) { startActivity(Intent(this, MaterialsActivity::class.java)) }
            .item(MoreIcons.PEN, L.t("aiInNote"), Sheet.SKY, L.t("aiInNoteSub")) { startActivity(Intent(this, NoteActivity::class.java)) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) {
            if (Places.allowed(this)) openNear() else toast(L.t("nearPerm"))
            return
        }
        if (requestCode == REQ_CAM_BG) {
            // con permiso: cámara en vivo; sin permiso: el fondo del teléfono (como antes)
            if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) toast(L.t("camPermWall"))
            Wall.set(this, "phone"); recreate()
        }
    }

    private var headerMode: String? = null

    /** ✨ Los botones de arriba caen uno tras otro con un rebotito. */
    private fun dropHeader() {
        var k = 0
        for (i in 0 until header.childCount) {
            val v = header.getChildAt(i); if (v.layoutParams.width == 0) continue
            v.alpha = 0f; v.translationY = -dp(26).toFloat(); v.scaleX = 0.7f; v.scaleY = 0.7f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(45L * k++).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
    }

    private fun viewName(): String = when {
        view == "all" -> L.t("all")
        view == "fav" -> L.t("fav")
        view == "trash" -> L.t("trash")
        view.startsWith("f:") -> Store.folders.firstOrNull { it.id == view.substring(2) }?.name ?: L.t("all")
        view.startsWith("t:") -> "#" + view.substring(2)
        view == "near" -> "📍 " + L.t("nearTitle")
        else -> L.t("all")
    }

    /* ---------- 📍 notas de este lugar ---------- */
    private var nearIds: Set<String> = emptySet()
    private lateinit var nearPill: TextView
    private var nearAsked = 0L

    /** Mira dónde estás y, si hay notas de aquí, muestra el aviso "📍 N notas de este lugar". */
    private fun checkNear() {
        if (!Places.allowed(this) || System.currentTimeMillis() - nearAsked < 30_000) { paintNear(); return }
        nearAsked = System.currentTimeMillis()
        Places.here(this) { l -> nearIds = Places.near(this, l).filter { id -> Store.notes.any { it.id == id && !it.deleted } }.toSet(); paintNear(); if (view == "near") refresh() }
    }
    private fun paintNear() {
        if (!::nearPill.isInitialized) return
        val n = nearIds.size
        val show = n > 0 && view == "all" && selected.isEmpty() && query.isBlank()
        if (show) {
            val pend = Store.notes.filter { it.id in nearIds && !it.locked }.sumOf { maxOf(0, it.checkTotal - it.checkDone) }
            nearPill.text = "📍 " + L.t("nearHere", "n" to n) + (if (pend > 0) "  ·  ☐ $pend" else "") + "  ›"
            if (nearPill.visibility != View.VISIBLE) { nearPill.visibility = View.VISIBLE; nearPill.alpha = 0f; nearPill.translationY = -dp(10).toFloat(); nearPill.animate().alpha(1f).translationY(0f).setDuration(350).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start() }
        } else nearPill.visibility = View.GONE
    }
    /** Ver las notas de aquí (la primera vez pide el permiso de ubicación). */
    private fun openNear() {
        if (!Places.allowed(this)) {
            // 📍 primero se explica qué hace (con un dibujito), y después se pide el permiso
            Sheet(this, "📍 " + L.t("nearTitle"), L.t("nearSub"))
                .custom(tv(L.t("nearExplain"), 15f, T.text).apply { setLineSpacing(0f, 1.25f); setPadding(dp(22), dp(4), dp(22), dp(12)) })
                .quick(Icons.PIN, L.t("nearOn"), Sheet.GREEN) { requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC) }
                .show()
            return
        }
        view = "near"; selected.clear(); nearAsked = 0L; checkNear(); refresh(); list.scrollToPosition(0)
        toast(L.t("nearHow"))
    }

    private fun hideKb() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    /* ---------- qué notas se ven ---------- */
    private fun visible(): List<NoteMeta> {
        val fq = fold(query.trim())
        var l = Store.notes.filter { n ->
            when {
                view == "trash" -> n.deleted
                n.deleted -> false
                view == "fav" -> n.fav
                view.startsWith("f:") -> n.folder == view.substring(2)
                view.startsWith("t:") -> tagsOf(n.plain + " " + n.title).contains(view.substring(2))
                view == "near" -> n.id in nearIds
                else -> true
            }
        }
        if (fq.isNotEmpty()) l = l.filter { n -> (if (n.locked) fold(n.title) else (n.folded ?: fold(n.title + "\n" + n.plain))).contains(fq) }
        val sort = prefs.getString("sort", "updated")
        val cmp: Comparator<NoteMeta> = when (sort) {
            "created" -> compareByDescending { it.created }
            "alpha" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.ifEmpty { it.plain } }
            else -> compareByDescending { if (view == "trash") it.deletedAt.takeIf { d -> d > 0 } ?: it.updated else it.updated }
        }
        return l.sortedWith(compareByDescending<NoteMeta> { if (view == "trash") false else it.pinned }.then(cmp))
    }

    private var visibleCount = 0
    private lateinit var countTv: TextView

    /** Saltito al tocar un botón. */
    private fun pop(v: View) { v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start() }.start() }

    /** ✨ Para la animación de las tarjetas: cuáles ya se vieron en esta vista. */
    private val shownCards = HashSet<String>()
    private var cardWave = 0
    private var shownKey = ""
    private var lastTitle = ""
    private var fabsHidden = false

    fun refresh() {
        // al cambiar de carpeta, de vista o de búsqueda, las tarjetas vuelven a llegar con animación
        val key = view + "|" + query.isBlank() + "|" + lm.spanCount
        if (key != shownKey) { shownKey = key; shownCards.clear() }
        cardWave = 0
        selected.retainAll(Store.notes.map { it.id }.toSet())
        val items = visible()
        visibleCount = items.size
        adapter.set(items)
        val showEmpty = items.isEmpty() && Store.loaded
        if (showEmpty && empty.visibility != View.VISIBLE) { empty.alpha = 0f; empty.animate().alpha(1f).setDuration(500).start(); Anim.float(empty) }
        empty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        empty.text = when {
            query.isNotBlank() -> L.t("noMatch", "q" to query.trim())
            view == "trash" -> L.t("trashEmpty")
            view == "fav" -> L.t("noFav")
            view.startsWith("f:") -> L.t("folderEmpty")
            view == "near" -> L.t("nearEmpty")
            else -> L.t("noNotes")
        }
        paintHeader()
        if (Store.loaded && query.isBlank()) loadTodo()
    }

    private fun toggleLayout() {
        val grid = lm.spanCount == 2
        lm.spanCount = if (grid) 1 else 2
        prefs.edit().putString("layout", if (grid) "list" else "grid").apply()
        adapter.notifyDataSetChanged(); paintHeader()
    }

    private fun pickSort() {
        val keys = arrayOf("updated", "created", "alpha")
        val cur = keys.indexOf(prefs.getString("sort", "updated"))
        val sh = Sheet(this, L.t("sortBy"))
        val icons = intArrayOf(Icons.CLOCK, Icons.CALENDAR, MoreIcons.TEXT_T)
        val cols = intArrayOf(Sheet.SKY, Sheet.ORANGE, Sheet.VIOLET)
        val labels = arrayOf(L.t("sortUpdated"), L.t("sortCreated"), L.t("sortAlpha"))
        for (i in 0..2) sh.quick(icons[i], labels[i], cols[i], i == cur) { prefs.edit().putString("sort", keys[i]).apply(); refresh() }
        sh.show()
    }

    /* ---------- abrir y crear ---------- */
    private var touchX = -1f; private var touchY = -1f

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { touchX = ev.rawX; touchY = ev.rawY }
        return super.dispatchTouchEvent(ev)
    }

    private fun newNote(kind: String, from: View? = null) {
        val i = Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_NEW, kind)
        if (touchX >= 0) i.putExtra("x", touchX).putExtra("y", touchY)
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (view.startsWith("f:")) i.putExtra(NoteActivity.EXTRA_FOLDER, view.substring(2))
        startActivity(i)
    }

    private fun open(n: NoteMeta) {
        val go = {
            val i = Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)
            if (query.isNotBlank()) i.putExtra(NoteActivity.EXTRA_Q, query.trim())
            if (touchX >= 0) i.putExtra("x", touchX).putExtra("y", touchY)
            startActivity(i)
            overridePendingTransition(0, 0)
        }
        if (n.locked) Pin.check(this) { go() } else go()
    }

    /* ---------- seleccionar varias ---------- */
    private fun sel(): List<NoteMeta> = Store.notes.filter { it.id in selected }
    private fun done() { selected.clear(); Store.changed() }
    private fun selPin() { val l = sel(); val v = !l.all { it.pinned }; cardsFx(selected) { Anim.bump(it, 1.06f) }; l.forEach { it.pinned = v; Store.saveMeta(it) }; done() }
    private fun selFav() {
        val l = sel(); val v = !l.all { it.fav }
        if (v) cardsFx(selected) { Anim.sparkle(it, 70f, 700); Anim.bump(it, 1.06f) }    // ⭐ estrellitas al marcar favorita
        l.forEach { it.fav = v; Store.saveMeta(it) }; done()
    }
    /** ✨ Las tarjetas de [ids] hacen algo bonito; [after] corre cuando terminan. */
    private fun cardsFx(ids: Collection<String>, fx: (View) -> Unit) {
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            val h = list.getChildViewHolder(c) as? VH ?: continue
            if (h.id in ids) fx(h.card)
        }
    }

    private fun selTrash() {
        // 🗑️ las tarjetas se encogen, giran y salen volando antes de irse a la papelera
        val flying = selected.toList()
        var k = 0
        cardsFx(flying) { c ->
            c.animate().scaleX(0.4f).scaleY(0.4f).rotation(if (k++ % 2 == 0) -12f else 12f).translationY(-dp(60).toFloat()).alpha(0f)
                .setDuration(320).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
        }
        list.postDelayed({ selTrashNow() }, 300)
    }

    private fun selTrashNow() {
        val now = System.currentTimeMillis()
        val ids = selected.toList()
        sel().forEach { it.deleted = true; it.deletedAt = now; Store.saveMeta(it) }
        done()
        trashSnack(ids)
    }

    /* ---------- 🗑️ "Se movió a la papelera · Deshacer" ---------- */
    private var snackView: View? = null
    private fun trashSnack(ids: List<String>) {
        snack(L.t("movedTrash"), L.t("undo")) {
            ids.forEach { id -> Store.meta(id)?.let { it.deleted = false; Store.saveMeta(it) } }
            Store.changed()
        }
    }

    private fun snack(msg: String, action: String?, onAction: (() -> Unit)?) {
        snackView?.let { root.removeView(it) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(6), dp(6), dp(6))
            background = android.graphics.drawable.LayerDrawable(arrayOf(roundBg(if (T.light) 0xF21C1C1E.toInt() else 0xF2303036.toInt(), dp(26).toFloat()), Glass.make(this@MainActivity, 26f)))
            elevation = dp(10).toFloat()
        }
        bar.addView(tv(msg, 15f, if (T.light) 0xFFFFFFFF.toInt() else T.text), lp(0, WRAP, 1f))
        lateinit var hide: Runnable
        if (action != null && onAction != null) bar.addView(tv(action, 15f, 0xFF64D2FF.toInt(), true).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12)); pressBg(0, 20f)
            setOnClickListener { onAction(); root.removeCallbacks(hide); hide.run() }
        })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(100)) })
        snackView = bar
        bar.translationY = dp(80).toFloat(); bar.alpha = 0f
        bar.animate().translationY(0f).alpha(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        hide = Runnable { bar.animate().translationY(dp(60).toFloat()).alpha(0f).setDuration(220).withEndAction { root.removeView(bar); if (snackView === bar) snackView = null }.start() }
        root.postDelayed(hide, 4500)
    }
    private fun selRecover() { sel().forEach { it.deleted = false; Store.saveMeta(it) }; done() }
    private fun selDeleteForever() {
        confirm(L.t("delForever"), L.t("delForeverMsg"), L.t("del"), true) { Store.deleteForever(selected.toList()); done() }
    }
    private fun selFolder() = pickFolder(sel().map { it.folder }.distinct().let { if (it.size == 1) it[0] else "?" }) { f -> sel().forEach { it.folder = f; Store.saveMeta(it) }; done() }
    private fun selColor() {
        lateinit var d: GlassDialog
        val v = colorRow(T.noteColors, true, -1) { c -> d.dismiss(); sel().forEach { it.color = c; Store.saveMeta(it) }; done() }
        d = dialog().setTitle(L.t("noteColor")).setView(v).setNegativeButton(L.t("cancel"), null).show()
    }
    private fun emptyTrash() {
        val ids = Store.notes.filter { it.deleted }.map { it.id }
        if (ids.isEmpty()) return
        confirm(L.t("emptyTrash"), L.t("emptyTrashMsg", "n" to ids.size), L.t("emptyTrash"), true) { Store.deleteForever(ids); Store.changed() }
    }

    /* ---------- menú ☰ ---------- */
    private fun openDrawer() {
        hideKb()
        val scrim = View(this).apply { setBackgroundColor(0x66000000); alpha = 0f; setOnClickListener { closeDrawer() } }
        val panelW = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.82f).toInt())
        val sv = ScrollView(this).apply { background = Glass.make(this@MainActivity, 28f, strong = true); isVerticalScrollBarEnabled = false; clipToOutline = true }
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(24)) }
        sv.addView(p)
        // Arriba, como en Best Notes: ← · logo · B E S T  N O T E S · Listo
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(12)) }
        top.addView(glassBtn(Icons.BACK, 8.5f) { closeDrawer() }, lp(dp(42), dp(42)))
        top.addView(android.widget.ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); clipToOutline = true; background = roundBg(0, dp(11).toFloat()) }, lp(dp(42), dp(42)).apply { marginStart = dp(8) })
        top.addView(tv("BEST\nNOTES", 15f, T.text, true).apply { letterSpacing = 0.32f; setPadding(dp(10), 0, 0, 0); setLineSpacing(0f, 0.95f) }, lp(0, WRAP, 1f))
        top.addView(tv(L.t("done"), 14f, T.onPill, true).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); pressBg(T.text, 18f); setOnClickListener { closeDrawer() } })
        p.addView(top)
        // Tarjeta blanca de la guía (con su etiqueta NUEVO)
        val guide = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(10), dp(14))
            pressBg(0xF2FFFFFF.toInt(), 20f); setOnClickListener { closeDrawer(); startActivity(Intent(this@MainActivity, GuideActivity::class.java)) }
        }
        guide.addView(IconView(this, Icons.BOOK, 9f).apply { color = 0xFF16161A.toInt(); isClickable = false }, lp(dp(28), dp(28)))
        val gcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
        gcol.addView(tv(L.t("guide"), 15f, 0xFF16161A.toInt(), true)); gcol.addView(tv(L.t("guideSub"), 12f, 0xFF55555A.toInt()))
        guide.addView(gcol, lp(0, WRAP, 1f))
        guide.addView(tv(L.t("newBadge"), 11f, 0xFFFFFFFF.toInt(), true).apply { setPadding(dp(9), dp(4), dp(9), dp(4)); background = roundBg(0xFF16161A.toInt(), dp(12).toFloat()) })
        guide.addView(tv("›", 20f, 0xFF16161A.toInt()).apply { setPadding(dp(8), 0, 0, 0) })
        p.addView(guide, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        fun count(f: (NoteMeta) -> Boolean) = Store.notes.count(f)
        fun nav(icon: Int, label: String, v: String, n: Int, dot: Int = 0): LinearLayout {
            val r = menuRow(this, icon, label, null, T.text, dot) { view = v; selected.clear(); closeDrawer(); refresh(); list.scrollToPosition(0) } as LinearLayout
            r.addView(tv(if (n > 0) "$n" else "0", 13f, T.muted))
            if (view == v) r.background = android.graphics.drawable.LayerDrawable(arrayOf(roundBg(tw(0x1F), dp(18).toFloat(), tw(0x33), dp(1)), Glass.make(this, 18f)))
            p.addView(r)
            return r
        }
        fun section(title: String, plus: (() -> Unit)? = null) {
            val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(6), dp(2)) }
            h.addView(tv(title.uppercase(L.locale), 12.5f, T.muted, true).apply { letterSpacing = 0.16f }, lp(0, WRAP, 1f))
            if (plus != null) h.addView(iconBtn(MoreIcons.PLUS, 9f) { plus() }, lp(dp(38), dp(38)))
            p.addView(h)
        }
        // 1) Todas · Favoritos · Papelera
        nav(Icons.NOTEBOOK, L.t("all"), "all", count { !it.deleted })
        nav(MoreIcons.STAR5, L.t("fav"), "fav", count { !it.deleted && it.fav })
        nav(Icons.TRASH, L.t("trash"), "trash", count { it.deleted })
        p.addView(menuRow(this, Icons.PIN, L.t("nearTitle"), if (Places.allowed(this)) L.t("nearSub") else L.t("nearOff")) { closeDrawer(); openNear() }.also { r -> if (view == "near") r.background = roundBg(tw(0x1F), dp(18).toFloat()) })
        // 2) CARPETAS  ＋   (cada una con su color, cuántas notas y ⋯)
        p.addView(sep())
        section(L.t("folders")) { closeDrawer(); folderDialog(null) }
        for (f in Store.folders) {
            val r = nav(MoreIcons.FOLDER, f.name, "f:" + f.id, count { !it.deleted && it.folder == f.id }, if (f.color != 0) f.color else 0)
            r.addView(iconBtn(MoreIcons.DOTS_V, 7.5f, T.muted) { closeDrawer(); folderMenu(f) }.apply { rotation = 90f }, lp(dp(34), dp(34)))
            r.setOnLongClickListener { closeDrawer(); folderMenu(f); true }
        }
        // etiquetas (#)
        val tags = HashMap<String, Int>()
        Store.notes.filter { !it.deleted && !it.locked }.forEach { n -> tagsOf(n.title + " " + n.plain).forEach { tags[it] = (tags[it] ?: 0) + 1 } }
        if (tags.isNotEmpty()) {
            section(L.t("tags"))
            tags.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { t -> nav(Icons.TAG, "#$t", "t:$t", tags[t] ?: 0) }
        }
        p.addView(sep())
        // 3) Respaldo · Transferir
        p.addView(menuRow(this, Icons.BOX, L.t("backup"), lastBackupText()) { closeDrawer(); backupMenu() })
        p.addView(menuRow(this, Icons.SWAP, L.t("transfer"), L.t("transferSub")) { closeDrawer(); transferMenu() })
        // 4) Pendientes · Materiales · Reporte · Plantilla · Bóveda · Calculadora
        p.addView(menuRow(this, MoreIcons.CHECKBOX, L.t("pending"), L.t("pendingHint")) { closeDrawer(); startActivity(Intent(this, PendingActivity::class.java)) }.also { r ->
            val n = Store.notes.filter { !it.deleted && !it.locked }.sumOf { maxOf(0, it.checkTotal - it.checkDone) }
            if (n > 0) (r as LinearLayout).addView(tv("$n", 13f, T.onPill, true).apply { gravity = Gravity.CENTER; minWidth = dp(24); setPadding(dp(7), dp(2), dp(7), dp(2)); background = roundBg(T.text, dp(12).toFloat()) })
        })
        p.addView(menuRow(this, Icons.BOX, L.t("materials"), L.t("materialsHint")) { closeDrawer(); startActivity(Intent(this, MaterialsActivity::class.java)) })
        p.addView(menuRow(this, Icons.REPORT, L.t("report"), L.t("reportHint")) { closeDrawer(); startActivity(Intent(this, ReportActivity::class.java)) })
        p.addView(menuRow(this, Icons.REPORT, L.t("tplNew"), L.t("templatesSub")) { closeDrawer(); templateSheet({ t ->
            val m = Store.createNote(t.title, t.blocks(), view.takeIf { it.startsWith("f:") }?.substring(2))
            startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id))
        }) })
        p.addView(menuRow(this, MoreIcons.SHIELD, L.t("vault"), L.t("vaultHint")) { closeDrawer(); startActivity(Intent(this, VaultActivity::class.java)) })
        p.addView(menuRow(this, Icons.CALC, L.t("calc"), L.t("calcSub")) { closeDrawer(); startActivity(Intent(this, CalcActivity::class.java)) })
        // 5) PIN · bloqueo · huella
        if (Pin.saved(this) != null) p.addView(menuRow(this, Icons.KEY, L.t("changePin")) { closeDrawer(); Pin.change(this) { toast(L.t("pinChanged")) } })
        p.addView(menuRow(this, Icons.LOCK, L.t("lockApp"), if (AppLock.on(this)) "✓ " + L.t("lockAppOn") else null) { closeDrawer(); toggleAppLock() })
        if (Bio.available(this)) p.addView(menuRow(this, MoreIcons.SHIELD, L.t("bioTitle"), if (Bio.enabled(this)) "✓ " + L.t("bioOn") else L.t("bioOff")) {
            closeDrawer(); val on = !Bio.enabled(this); Bio.setEnabled(this, on); toast(L.t(if (on) "bioOn" else "bioOff"))
        })
        // 6) Idioma · Formato de hora
        p.addView(menuRow(this, Icons.GLOBE, L.t("language"), when (L.choice) { "es" -> "Español"; "en" -> "English"; else -> L.t("langAuto") }) { closeDrawer(); pickLang() })
        p.addView(menuRow(this, Icons.CLOCK, L.t("hourFmt"), when (L.hourFmt) { "12" -> "12 h (3:00 PM)"; "24" -> "24 h (15:00)"; else -> L.t("hourAuto") }) { closeDrawer(); pickHourFmt() })
        p.addView(sep())
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        p.addView(tv(L.t("devBy") + "\n" + L.t("version", "v" to (ver ?: "")), 12.5f, T.muted).apply { setPadding(dp(14), dp(8), dp(8), 0) })

        val box = FrameLayout(this)
        box.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        // el panel va dentro de una "caja" con una capa blanca encima (para el destello al cerrar)
        val holder = FrameLayout(this).apply { clipToOutline = true; background = roundBg(0, dp(28).toFloat()) }
        holder.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        val wash = View(this).apply { background = roundBg(0xFFFFFFFF.toInt(), dp(28).toFloat()); alpha = 0f; isClickable = false }
        holder.addView(wash, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(holder, FrameLayout.LayoutParams(panelW, MATCH).apply { setMargins(dp(10), dp(10), 0, dp(10)) })
        // la línea de luz que va con el borde del panel
        val line = View(this).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0xEEFFFFFF.toInt(), 0x00FFFFFF)); alpha = 0f }
        box.addView(line, FrameLayout.LayoutParams(dp(14), MATCH).apply { setMargins(0, dp(14), 0, dp(14)) })
        // el destello: una luz redonda que brilla al final, en el centro de la izquierda
        val flash = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x80FFFFFF.toInt(), 0x00FFFFFF); gradientRadius = dp(46).toFloat()
            }
            alpha = 0f
        }
        box.addView(flash, FrameLayout.LayoutParams(dp(120), dp(120)))
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
        drawer = box
        // Se abre creciendo desde la izquierda (blanco al principio, como en Best Notes), con la línea de luz en el borde
        holder.pivotX = 0f; holder.scaleX = 0.02f; wash.alpha = 0.85f
        scrim.animate().alpha(1f).setDuration(320).start()
        val va = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(520)
        va.interpolator = android.view.animation.DecelerateInterpolator(1.6f)
        va.addUpdateListener {
            val k = it.animatedValue as Float
            holder.scaleX = 0.02f + 0.98f * k
            wash.alpha = 0.85f * (1 - k) * (1 - k)
            line.translationX = dp(10) + panelW * holder.scaleX - dp(7); line.alpha = if (k < 0.85f) 1f else (1 - k) / 0.15f
        }
        va.start()
        // ✨ las opciones del menú llegan una tras otra desde la izquierda
        for (i in 0 until p.childCount) {
            val v = p.getChildAt(i)
            v.alpha = 0f; v.translationX = -dp(40).toFloat()
            v.animate().alpha(1f).translationX(0f).setStartDelay(120L + 22L * minOf(i, 24)).setDuration(360).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
    }

    /* ---------- ☀️ Apariencia: el fondo de la app (panel bonito) ---------- */
    private fun appearance() {
        val cur = Wall.current(this)
        val opts = (if (T.light) emptyList() else listOf("best" to "Best Notes")) + Wall.GRADS.map { it[0] as String to (if (L.lang == "es") it[1] else it[2]) as String } +
            ("photo" to L.t("wallPhoto")) + ("phone" to L.t("wallPhone"))
        val sh = Sheet(this, "☀️ " + L.t("appearance"), L.t("appearanceSub"))
        // 🌙 oscuro / ☀️ claro
        fun setTheme(light: Boolean) { switchTheme(light, null) }
        sh.quick(MoreIcons.MOON, L.t("themeDark"), 0xFF8B7CFF.toInt(), !T.light, keepOpen = true) { setTheme(false) }
        sh.quick(MoreIcons.SUN, L.t("themeLight"), 0xFFFFB74D.toInt(), T.light, keepOpen = true) { setTheme(true) }
        val grid = android.widget.GridLayout(this).apply { columnCount = 3; setPadding(dp(6), dp(2), dp(6), dp(4)) }
        val cell = (resources.displayMetrics.widthPixels - dp(16 + 16 + 12 + 36 + 14)) / 3
        val marks = HashMap<String, View>()
        val names = HashMap<String, TextView>()
        var selected = cur
        fun paint() {
            for ((id, m) in marks) {
                val on = id == selected
                m.animate().scaleX(if (on) 1f else 0f).scaleY(if (on) 1f else 0f).alpha(if (on) 1f else 0f).setDuration(260)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                names[id]?.setTextColor(if (on) 0xFF4FC3F7.toInt() else T.muted)
                (m.parent as? View)?.foreground = if (on) roundBg(0, dp(18).toFloat(), 0xFF4FC3F7.toInt(), dp(3)) else roundBg(0, dp(18).toFloat(), tw(0x26), dp(1))
            }
        }
        for ((id, name) in opts) {
            val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(4), dp(6), dp(10)) }
            val th = FrameLayout(this).apply {
                background = if (id == "photo" && !Wall.photoFile(this@MainActivity).exists()) roundBg(tw(0x1A), dp(18).toFloat())
                    else if (id == "phone") android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xFF5B86E5.toInt(), 0xFF36D1DC.toInt(), 0xFFB06AB3.toInt()))
                    else Wall.drawable(this@MainActivity, id)
                clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat()) }
                }
            }
            // una mini pantalla encima, para ver cómo quedan las notas
            val mini = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(8)) }
            mini.addView(View(this).apply { background = roundBg(0xCCFFFFFF.toInt(), dp(3).toFloat()) }, lp(cell / 2, dp(6)).apply { bottomMargin = dp(8) })
            for (k in 0 until 2) {
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)); background = Glass.make(this@MainActivity, 9f) }
                card.addView(View(this).apply { background = roundBg(0xB3FFFFFF.toInt(), dp(2).toFloat()) }, lp((cell * 0.45f).toInt(), dp(4)))
                card.addView(View(this).apply { background = roundBg(tw(0x59), dp(2).toFloat()) }, lp((cell * 0.6f).toInt(), dp(3)).apply { topMargin = dp(5) })
                card.addView(View(this).apply { background = roundBg(tw(0x59), dp(2).toFloat()) }, lp((cell * 0.35f).toInt(), dp(3)).apply { topMargin = dp(4) })
                mini.addView(card, lp(MATCH, WRAP).apply { bottomMargin = dp(6) })
            }
            if (id == "photo" && !Wall.photoFile(this).exists()) {
                // 🖼 moderno: degradado suave + círculo de vidrio con el ícono de foto y un ＋ (escoger de la galería)
                th.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF2A2440.toInt(), 0xFF1E3A4A.toInt(), 0xFF3A2236.toInt())).apply { cornerRadius = dp(18).toFloat() }
                val c = FrameLayout(this).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0x66F48FB1, 0x664FC3F7)).apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(1), 0x66FFFFFF) }
                    addView(IconView(this@MainActivity, Icons.IMAGE, 8.5f).apply { color = 0xFFFFFFFF.toInt(); isClickable = false }, FrameLayout.LayoutParams(MATCH, MATCH))
                }
                th.addView(c, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
                val plus = FrameLayout(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
                    elevation = dp(3).toFloat()
                    addView(IconView(this@MainActivity, MoreIcons.PLUS, 4f).apply { color = 0xFF16161A.toInt(); isClickable = false }, FrameLayout.LayoutParams(MATCH, MATCH))
                }
                th.addView(plus, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER).apply { leftMargin = dp(36); topMargin = dp(36) })
                c.post { c.animate().scaleX(1.06f).scaleY(1.06f).setDuration(1400).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).withEndAction { c.animate().scaleX(1f).scaleY(1f).setDuration(1400).start() }.start() }
            }
            else if (id == "phone") {
                // 📷 translúcido = cámara en vivo: el degradado de siempre con un lente que "enfoca" y un puntito rojo de EN VIVO
                th.addView(mini.apply { alpha = 0.7f }, FrameLayout.LayoutParams(MATCH, WRAP))
                val lens = FrameLayout(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; colors = intArrayOf(0xFF4FC3F7.toInt(), 0xFF1A237E.toInt()); gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(26).toFloat(); setStroke(dp(3), 0xCCFFFFFF.toInt()) }
                }
                th.addView(lens, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
                lens.post { lens.animate().scaleX(0.86f).scaleY(0.86f).setDuration(900).withEndAction { lens.animate().scaleX(1f).scaleY(1f).setDuration(900).start() }.start() }
                val live = tv("●", 11f, 0xFFFF3B30.toInt(), true)
                th.addView(live, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(9), 0) })
                live.animate().alpha(0.2f).setDuration(700).withEndAction { live.animate().alpha(1f).setDuration(700).start() }.start()
            }
            else th.addView(mini, FrameLayout.LayoutParams(MATCH, WRAP))
            // ✓ de seleccionado
            val mark = tv("✓", 15f, 0xFF161618.toInt(), true).apply { gravity = Gravity.CENTER; background = roundBg(0xFF4FC3F7.toInt(), dp(13).toFloat()); scaleX = 0f; scaleY = 0f; alpha = 0f }
            th.addView(mark, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(7), dp(7)) })
            marks[id] = mark
            v.addView(th, lp(cell, (cell * 1.25f).toInt()))
            val nm = tv(name, 12.5f, T.muted, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0); maxLines = 1 }
            names[id] = nm; v.addView(nm)
            v.setOnClickListener {
                th.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction { th.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start() }.start()
                if (id == "photo" && (selected == "photo" || !Wall.photoFile(this).exists())) { Sheet.closeTop(this); Store.main.postDelayed({ pickWallPhoto() }, 250); return@setOnClickListener }
                // 📷 translúcido usa la cámara: primero el permiso
                if (id == "phone" && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Sheet.closeTop(this); requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAM_BG); return@setOnClickListener
                }
                val was = selected
                selected = id; Wall.set(this, id); paint()
                // el modo translúcido y la cámara necesitan volver a armar la ventana
                if (id == "phone" || was == "phone") { Sheet.closeTop(this); Store.main.postDelayed({ recreate() }, 300) } else fadeWall()
            }
            grid.addView(v)
        }
        sh.custom(grid)
        // difuminar mi foto
        val sw = android.widget.Switch(this).apply {
            text = "✨  " + L.t("wallBlur"); setTextColor(T.text); textSize = 15.5f; isChecked = Wall.blur(this@MainActivity); setPadding(dp(14), dp(10), dp(14), dp(6))
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt()))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF4FC3F7.toInt(), tw(0x55)))
            setOnCheckedChangeListener { _, on -> Wall.setBlur(this@MainActivity, on); if (Wall.current(this@MainActivity) == "photo") fadeWall() }
        }
        sh.custom(sw)
        sh.show()
        grid.post { paint() }
    }

    /** El fondo nuevo aparece suavecito (se mezcla con el de antes). */
    private fun fadeWall() {
        val old = window.decorView.background ?: android.graphics.drawable.ColorDrawable(T.bg)
        val td = android.graphics.drawable.TransitionDrawable(arrayOf(old, Glass.background(this)))
        td.isCrossFadeEnabled = true
        window.setBackgroundDrawable(td); td.startTransition(700)
    }

    private fun pickWallPhoto() {
        pickOnePhoto(REQ_WALL)
    }

    private fun sep() = View(this).apply { setBackgroundColor(T.border) }.also { it.layoutParams = lp(MATCH, dp(1)).apply { setMargins(dp(14), dp(8), dp(14), dp(8)) } }

    /**
     * Al cerrar (como en Best Notes): el panel se pone blanco y se aplasta hacia la izquierda,
     * queda una barra de luz, la barra se achica hacia el CENTRO de la izquierda y termina
     * en un destello ✦ — mientras quedan estrellitas que titilan.
     */
    private fun closeDrawer() {
        val d = drawer ?: return
        drawer = null
        val box = d as FrameLayout
        val scrim = box.getChildAt(0); val holder = box.getChildAt(1) as FrameLayout; val wash = holder.getChildAt(1)
        val line = box.getChildAt(2); val flash = box.getChildAt(3)
        val loc = IntArray(2); holder.getLocationInWindow(loc)
        val rl = IntArray(2); root.getLocationInWindow(rl)
        val w = holder.width.toFloat(); val h = holder.height.toFloat()
        val left = (loc[0] - rl[0]).toFloat(); val top = (loc[1] - rl[1]).toFloat()
        val sp = SparkleView(this, Fx.menuTrail(this, left, top, w, h, root.width.toFloat()))
        root.addView(sp, FrameLayout.LayoutParams(MATCH, MATCH))
        sp.play(1400)
        // el destello va en el centro de la izquierda
        flash.translationX = left - dp(60) + dp(3); flash.translationY = top + h / 2 - dp(60)
        holder.pivotX = 0f; holder.pivotY = h / 2
        line.pivotY = (line.height / 2).toFloat()
        scrim.animate().alpha(0f).setDuration(820).start()
        val parts = CloseParts(holder, wash, line, flash, w, left)
        val va = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(1150)
        va.interpolator = android.view.animation.LinearInterpolator()
        va.addUpdateListener { closeFrame(parts, it.animatedValue as Float) }
        va.addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { root.removeView(box) } })
        va.start()
    }

    class CloseParts(val holder: View, val wash: View, val line: View, val flash: View, val w: Float, val left: Float)

    /** Un cuadro de la animación de cierre ([t] de 0 a 1). */
    fun closeFrame(p: CloseParts, t: Float) {
        val holder = p.holder; val wash = p.wash; val line = p.line; val flash = p.flash; val w = p.w; val left = p.left
        val bar = dp(5).toFloat()                                       // grueso de la barra de luz
        when {
            t < 0.42f -> {                                          // 1) se aplasta y se pone blanco
                val k = t / 0.42f; val e = k * k * (3 - 2 * k)
                holder.scaleX = maxOf(bar / w, 1f - e * (1f - bar / w))
                wash.alpha = minOf(1f, e * 1.3f)
                holder.scaleY = 1f
                line.alpha = 1f; line.translationX = left + w * holder.scaleX - dp(7)
            }
            t < 0.62f -> {                                          // 2) queda una barra de luz brillando
                holder.scaleX = bar / w; wash.alpha = 1f; holder.scaleY = 1f
                line.alpha = 1f; line.translationX = left - dp(5)
            }
            else -> {                                               // 3) la barra se achica hacia el centro
                val k = ((t - 0.62f) / 0.26f).coerceIn(0f, 1f); val e = k * k
                holder.scaleX = bar / w; holder.scaleY = maxOf(0.001f, 1f - e)
                line.scaleY = maxOf(0.001f, 1f - e); line.translationX = left - dp(5)
                line.alpha = 1f - maxOf(0f, (k - 0.7f) / 0.3f)
                holder.alpha = if (k < 0.9f) 1f else (1 - k) / 0.1f
            }
        }
        // 4) el destello: se prende cuando la barra llega al centro y se apaga creciendo un poquito
        if (t > 0.78f) {
            val k = ((t - 0.78f) / 0.22f).coerceIn(0f, 1f)
            flash.alpha = if (k < 0.3f) k / 0.3f else (1 - k) / 0.7f
            val sc = 0.35f + 1.1f * k
            flash.scaleX = sc; flash.scaleY = sc
        }
    }

    private fun folderMenu(f: Folder) {
        Sheet(this, "📁 " + f.name)
            .item(MoreIcons.PEN, L.t("editFolder"), Sheet.ORANGE) { folderDialog(f) }
            .item(Icons.TRASH, L.t("deleteFolder"), danger = true) {
                confirm(L.t("deleteFolder"), L.t("deleteFolderMsg", "n" to f.name), L.t("del"), true) {
                    Store.deleteFolder(f); if (view == "f:" + f.id) view = "all"; Store.changed()
                }
            }.show()
    }

    /** Crear o editar una carpeta (nombre + color). */
    private fun folderDialog(f: Folder?) = folderEditDialog(f) {}

    private fun pickHourFmt() {
        val keys = arrayOf("auto", "12", "24")
        val sh = Sheet(this, "🕒 " + L.t("hourFmt"))
        val labels = arrayOf(L.t("hourAuto"), "3:00 PM", "15:00")
        for (i in 0..2) sh.quick(if (i == 0) Icons.SETTINGS else Icons.CLOCK, labels[i], if (i == 0) Sheet.GRAY else if (i == 1) Sheet.SKY else Sheet.GREEN, keys[i] == L.hourFmt) { L.setHourFmt(this, keys[i]); refresh() }
        sh.show()
    }

    private fun toggleAppLock() {
        if (AppLock.on(this)) Pin.check(this) { AppLock.set(this, false); toast(L.t("lockAppOff")) }
        else if (Pin.saved(this) == null) Pin.create(this) { AppLock.set(this, true); toast(L.t("lockAppOn")) }
        else Pin.check(this) { AppLock.set(this, true); toast(L.t("lockAppOn")) }
    }

    private fun pickLang() {
        val sh = Sheet(this, "🌍 " + L.t("language"), L.t("langSub"))
        sh.item(Icons.GLOBE, L.t("langAuto"), Sheet.GRAY, if (L.choice == "auto") "✓" else null) { L.set(this, "auto"); recreate() }
        for ((code, name) in L.LANGS) {
            val ready = L.hasLang(code)
            val sub = when { L.choice == code -> "✓"; ready -> null; else -> "✨ " + L.t("langAi") }
            sh.item(Icons.GLOBE, name, if (ready) Sheet.SKY else Sheet.VIOLET, sub) {
                if (ready) {
                    L.set(this, code); recreate()
                    if (code != "es" && code != "en") Thread { try { Langs.translate(code) {} } catch (_: Exception) {} }.start()   // completa lo nuevo
                } else translateLang(code, name)
            }
        }
        sh.show()
    }

    /** Traduce la app a otro idioma con la IA (una sola vez). */
    private fun translateLang(code: String, name: String) {
        confirm(name, L.t("langAiMsg"), L.t("langAiGo")) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(20), dp(24), dp(10)) }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            box.addView(tv("✨ " + name, 17f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12)) })
            box.addView(bar, lp(MATCH, WRAP))
            val pct = tv(L.t("langAiWorking"), 14f, T.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
            box.addView(pct)
            val d = dialog().setView(box).setCancelable(false).show()
            Thread {
                val ok = try { Langs.translate(code) { p -> runOnUiThread { bar.progress = p; pct.text = L.t("langAiWorking") + "  $p%" } } } catch (_: Exception) { false }
                runOnUiThread {
                    d.dismiss()
                    if (ok) { L.set(this, code); recreate() } else toast(L.t("langAiFail"))
                }
            }.start()
        }
    }

    /* ---------- importar de Best Notes ---------- */
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, PICK_FILE) } catch (_: Exception) { toast(L.t("cantOpen")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE && resultCode == RESULT_OK) data?.data?.let { askPassword(it) }
        if (requestCode == REQ_AUTO_DIR && resultCode == RESULT_OK) data?.data?.let { autoBackupPass(it) }
        if (requestCode == REQ_SAVE_BK && resultCode == RESULT_OK) data?.data?.let { u ->
            val f = backupFile ?: return@let
            Thread {
                val ok = try { contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }; true } catch (_: Exception) { false }
                runOnUiThread { toast(L.t(if (ok) "backupSaved" else "vFail")) }
            }.start()
        }
        if (requestCode == REQ_WALL && resultCode == RESULT_OK) data?.data?.let { u ->
            Thread {
                val ok = try { contentResolver.openInputStream(u)!!.use { inp -> Wall.photoFile(this).outputStream().use { inp.copyTo(it) } }; true } catch (_: Exception) { false }
                runOnUiThread { if (ok) { Wall.set(this, "photo"); fadeWall() } else toast(L.t("cantOpen")) }
            }.start()
        }
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        // 🏠 desde el widget
        i.getStringExtra(NotesWidget.EXTRA_OPEN)?.let { id -> i.removeExtra(NotesWidget.EXTRA_OPEN); Store.load { Store.meta(id)?.takeIf { !it.deleted }?.let { open(it) } }; return }
        if (i.getBooleanExtra(NotesWidget.EXTRA_NEW, false)) { i.removeExtra(NotesWidget.EXTRA_NEW); Store.load { newNote("text") }; return }
        // 📥 algo que te mandaron desde otra app (texto, enlace o fotos)
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) {
            val type = i.type ?: ""
            val streams = ArrayList<Uri>()
            if (i.action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION") (i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.let { streams.addAll(it) }
            } else (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))?.let { streams.add(it) }
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            if (type.startsWith("image/") || (streams.isEmpty() && !text.isNullOrBlank())) {
                val subj = i.getStringExtra(Intent.EXTRA_SUBJECT)
                i.action = null
                Store.load { root.postDelayed({ shareIn(text, subj, if (type.startsWith("image/")) streams else emptyList()) }, 300) }
                return
            }
        }
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri != null) { i.action = null; Store.load { askPassword(uri) } }
    }

    /* ---------- 📥 guardar en una nota lo que llega de otra app ---------- */
    private fun shareIn(text: String?, subj: String?, images: List<Uri>) {
        val what = listOfNotNull(text?.trim()?.takeIf { it.isNotEmpty() }?.let { "“" + it.take(80) + (if (it.length > 80) "…" else "") + "”" }, if (images.isNotEmpty()) "📷 × ${images.size}" else null).joinToString("  ")
        val sh = Sheet(this, "📥 " + L.t("shareInTitle"), what)
        sh.quick(MoreIcons.PLUS, L.t("shareInNew"), Sheet.GREEN) { shareInto(null, text, subj, images) }
        val recent = Store.notes.filter { !it.deleted && !it.locked }.sortedWith(compareByDescending<NoteMeta> { it.pinned }.thenByDescending { it.updated }).take(8)
        if (recent.isNotEmpty()) sh.custom(tv(L.t("shareInAdd").uppercase(L.locale), 13f, T.muted, true).apply { letterSpacing = 0.1f; setPadding(dp(14), dp(10), dp(14), dp(6)) })
        for (n in recent) sh.item(MoreIcons.PEN, n.title.ifBlank { L.t("untitled") }, Sheet.SKY, dateText(n.updated)) { shareInto(n, text, subj, images) }
        sh.show()
    }

    private fun shareInto(target: NoteMeta?, text: String?, subj: String?, images: List<Uri>) {
        toast(L.t("shareInSaving"))
        val app = applicationContext
        Thread {
            val blocks = ArrayList<Block>()
            val t = text?.trim().orEmpty()
            if (t.isNotEmpty()) {
                // si es un enlace, se guarda como enlace que se puede tocar
                val url = Regex("^https?://\\S+$").matches(t)
                blocks.add(Block.Text(mutableListOf(if (url) Run(t, St(href = t, u = true)) else Run(t))))
            }
            for (u in images) {
                val r = try { Photos.import(app, u) } catch (_: Throwable) { null } ?: continue
                val pct = if (images.size > 1) 48f else 70f
                blocks.add(Block.Img(r.file, pct, "", (100f - pct) / 2, 0f, r.w, r.h, "", 0f, r.taken))
            }
            if (blocks.isEmpty()) { runOnUiThread { toast(L.t("photoFail")) }; return@Thread }
            val m: NoteMeta = if (target == null) {
                val title = subj?.trim()?.takeIf { it.isNotEmpty() && it != t } ?: ""
                val r = arrayOfNulls<NoteMeta>(1)
                val l = java.util.concurrent.CountDownLatch(1)
                runOnUiThread { r[0] = Store.createNote(title.take(120), blocks); l.countDown() }
                l.await(); r[0]!!
            } else {
                val (body, _) = Store.readBody(target.id)
                val all = blocksFromJson(body); all.addAll(blocks)
                val nb = normalize(all)
                runOnUiThread { target.updated = System.currentTimeMillis(); Store.fillMeta(target, nb); Store.saveNote(target, blocksJson(nb)); Store.changed() }
                target
            }
            runOnUiThread {
                toast(L.t("shareInDone"))
                startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id))
            }
        }.start()
    }

    /* ---------- 💾 respaldo automático ---------- */
    private fun autoBackupMenu() {
        if (AutoBackup.on(this)) {
            val last = AutoBackup.last(this)
            Sheet(this, "🔁 " + L.t("autoBk"), L.t("autoBkOnSub", "d" to (if (last == 0L) L.t("backupNever") else dateText(last))))
                .quick(Icons.CHECK, L.t("autoBkNow"), Sheet.GREEN) {
                    toast(L.t("bkWorking"))
                    Thread { val ok = AutoBackup.run(applicationContext); runOnUiThread { toast(L.t(if (ok) "autoBkDone" else "vFail")) } }.start()
                }
                .quick(Icons.CLOSE, L.t("autoBkOff"), Sheet.RED) { AutoBackup.disable(this); toast(L.t("autoBkOffDone")) }
                .custom(tv(L.t("autoBkHint"), 13.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(4), dp(18), dp(10)) })
                .show()
            return
        }
        dialog().setTitle("🔁 " + L.t("autoBk")).setMessage(L.t("autoBkExplain"))
            .setPositiveButton(L.t("autoBkPick")) { _, _ ->
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_AUTO_DIR) } catch (_: Exception) { toast(L.t("cantOpen")) }
            }.setNegativeButton(L.t("cancel"), null).show()
    }

    private fun autoBackupPass(dir: Uri) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(h: String) = EditText(this).apply {
            hint = h; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; typeface = android.graphics.Typeface.DEFAULT
        }
        val p1 = field(L.t("vPassNew")); val p2 = field(L.t("vPassAgain"))
        box.addView(p1); box.addView(p2, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(tv("⚠️ " + L.t("backupPassWarn"), 12.5f, T.muted).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        dialog().setTitle(L.t("autoBkPassTitle")).setView(box).setPositiveButton(L.t("ok")) { _, _ ->
            val a = p1.text.toString()
            when {
                a.length < 6 -> toast(L.t("vShort"))
                a != p2.text.toString() -> toast(L.t("vMismatch"))
                else -> {
                    AutoBackup.enable(this, dir, a)
                    toast(L.t("bkWorking"))
                    Thread { val ok = AutoBackup.run(applicationContext); runOnUiThread { toast(L.t(if (ok) "autoBkReady" else "vFail")) } }.start()
                }
            }
        }.setNegativeButton(L.t("cancel"), null).show()
    }

    /* ---------- 📲 pasar las notas a otro teléfono ---------- */
    private fun transferMenu() {
        Sheet(this, "📲 " + L.t("transfer"), L.t("transferIntro"))
            .item(Icons.SEND, L.t("transferMake"), Sheet.GREEN, L.t("transferMakeSub")) { askBackupPass() }
            .item(MoreIcons.IMPORT, L.t("transferGet"), Sheet.BLUE, L.t("transferGetSub")) { pickFile() }
            .custom(tv(L.t("transferHow"), 13.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(8), dp(18), dp(10)) })
            .show()
    }

    /* ---------- 💾 respaldo ---------- */
    private fun lastBackupText(): String {
        val t = prefs.getLong("lastBackup", 0L)
        return if (t == 0L) L.t("backupNever") else L.t("backupLast", "d" to dateText(t))
    }

    private fun backupMenu() {
        Sheet(this, "💾 " + L.t("backup"), lastBackupText())
            .quick(Icons.CHECK, L.t("backupMake"), Sheet.GREEN) { askBackupPass() }
            .quick(MoreIcons.RESTORE, L.t("backupRestore"), Sheet.BLUE) { pickFile() }
            .quick(MoreIcons.HISTORY, L.t("autoBk"), Sheet.VIOLET, on = AutoBackup.on(this)) { autoBackupMenu() }
            .custom(tv(L.t("backupHint"), 13.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(4), dp(18), dp(10)) })
            .custom(tv("☁️ " + L.t("gBackupHint"), 13f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(2), dp(18), dp(10)) })
            .show()
    }

    private fun askBackupPass() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        fun field(h: String) = EditText(this).apply {
            hint = h; setTextColor(T.text); setHintTextColor(T.faint); isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; typeface = android.graphics.Typeface.DEFAULT
        }
        val p1 = field(L.t("vPassNew")); val p2 = field(L.t("vPassAgain"))
        box.addView(p1); box.addView(p2)
        box.addView(tv("⚠️ " + L.t("backupPassWarn"), 12.5f, T.muted).apply { setPadding(0, dp(10), 0, 0) })
        dialog().setTitle(L.t("backupMake")).setView(box).setPositiveButton(L.t("ok")) { _, _ ->
            val a = p1.text.toString()
            when {
                a.length < 6 -> toast(L.t("vShort"))
                a != p2.text.toString() -> toast(L.t("vMismatch"))
                else -> makeBackup(a)
            }
        }.setNegativeButton(L.t("cancel"), null).show()
    }

    private var backupFile: File? = null

    private fun makeBackup(pass: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(20), dp(24), dp(10)) }
        box.addView(ProgressBar(this))
        val step = tv(L.t("bkWorking"), 15f, T.text).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        box.addView(step)
        val d = dialog().setView(box).setCancelable(false).show()
        val name = "BestNotes_respaldo_" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
        val out = File(File(cacheDir, "respaldo").apply { mkdirs(); listFiles()?.forEach { it.delete() } }, name)
        Thread {
            val ok = try { Backup.exportFile(this, pass, out) { m -> runOnUiThread { step.text = m } }; true } catch (_: Throwable) { false }
            runOnUiThread {
                d.dismiss()
                if (!ok) { toast(L.t("vFail")); return@runOnUiThread }
                backupFile = out
                prefs.edit().putLong("lastBackup", System.currentTimeMillis()).apply()
                val size = out.length()
                Sheet(this, "✅ " + L.t("backupReady"), name + " · " + (if (size > 1048576) String.format(L.locale, "%.1f MB", size / 1048576.0) else "${size / 1024} KB"))
                    .quick(MoreIcons.IMPORT, L.t("backupSave"), Sheet.GREEN) {
                        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
                        try { startActivityForResult(i, REQ_SAVE_BK) } catch (_: Exception) { toast(L.t("cantOpen")) }
                    }
                    .quick(MoreIcons.DRIVE, "Google Drive", Sheet.AMBER) {
                        // ☁️ directo a Google Drive (con fotos y videos); si no está Drive, se escoge dónde
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.archivos", out)
                        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try { startActivity(Intent(send).setPackage("com.google.android.apps.docs")) } catch (_: Exception) { startActivity(Intent.createChooser(send, null)) }
                    }
                    .quick(Icons.SEND, L.t("share"), Sheet.BLUE) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.archivos", out)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    }
                    .custom(tv(L.t("backupKeep"), 13.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(4), dp(18), dp(10)) })
                    .show()
            }
        }.start()
    }

    private fun askPassword(uri: Uri) {
        askText(L.t("importPass"), "", L.t("importPassHint"), password = true) { pass -> runImport(uri, pass) }
    }

    /** Nombre, tamaño y cómo empieza el archivo (para saber qué pasó si no se pudo abrir). */
    private fun fileInfo(uri: Uri): String {
        var name = "?"; var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: "?" }
                    c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                }
            }
        } catch (_: Exception) {}
        val start = try { contentResolver.openInputStream(uri)?.use { i -> val b = ByteArray(40); val n = i.read(b); if (n > 0) String(b, 0, n, Charsets.UTF_8).replace(Regex("[^\\x20-\\x7E]"), "·") else "" } ?: "" } catch (_: Exception) { "" }
        val sz = if (size < 0) "?" else if (size > 1048576) String.format(L.locale, "%.1f MB", size / 1048576.0) else "${size / 1024} KB"
        return "📄 $name · $sz" + (if (size == 0L) "\n" + L.t("importEmpty") else "") + (if (start.isNotEmpty()) "\n«$start…»" else "")
    }

    private fun runImport(uri: Uri, pass: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(20), dp(24), dp(10)) }
        box.addView(ProgressBar(this))
        val step = tv(L.t("importing"), 15f, T.text).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        box.addView(step)
        val d = dialog().setView(box).setCancelable(false).show()
        Thread {
            val res: String = try {
                val s = Backup.importFile(this, uri, pass) { n -> runOnUiThread { step.text = L.t("importing") + "\n" + n } }
                L.t("importDone", "n" to s.total, "u" to s.written) + "\n\n" + L.t("importKeepNewer") +
                    (if (s.missing > 0) "\n\n" + L.t("importMediaFail", "n" to s.missing) else "") +
                    (when (s.vault) { "ok" -> "\n\n🛡️ " + L.t("vImported"); "exists" -> "\n\n🛡️ " + L.t("vImportExists"); else -> "" })
            } catch (_: Backup.WrongPassword) { "!" + L.t("importWrongPass") }
            catch (_: Backup.BadFile) { "!" + L.t("importBadFile") + "\n\n" + fileInfo(uri) }
            catch (e: Throwable) { "!" + L.t("importFail", "e" to (e.message ?: e.javaClass.simpleName)) }
            runOnUiThread {
                d.dismiss()
                if (res.startsWith("!") && res.contains(L.t("importWrongPass"))) {
                    dialog().setMessage(res.substring(1)).setPositiveButton(L.t("ok")) { _, _ -> askPassword(uri) }.setNegativeButton(L.t("cancel"), null).show()
                } else {
                    dialog().setMessage(res.removePrefix("!")).setPositiveButton(L.t("ok"), null).show()
                    Store.reload { view = "all"; refresh() }
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            Sheet.closeTop(this) -> {}
            drawer != null -> closeDrawer()
            selected.isNotEmpty() -> { selected.clear(); refresh() }
            query.isNotEmpty() -> search.setText("")
            view != "all" -> { view = "all"; refresh() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /* ---------- tarjetas ---------- */
    private fun dateText(t: Long): String {
        val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
        return when {
            DateUtils.isToday(t) -> L.time(t)
            DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> L.t("yesterday")
            c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM, L.locale).format(Date(t))
        }
    }

    /** Pedacito del texto donde está lo que buscas, con la palabra iluminada. */
    private fun snippet(text: String, maxChars: Int): CharSequence {
        val q = query.trim()
        if (q.isEmpty()) return text.take(maxChars)
        val found = findAll(text, q)
        if (found.isEmpty()) return text.take(maxChars)
        val first = found[0].first
        var a = maxOf(0, first - 40)
        if (a > 0) { val sp = text.lastIndexOf(' ', first - 1); if (sp in a until first) a = sp + 1 }
        val piece = (if (a > 0) "…" else "") + text.substring(a, minOf(text.length, a + maxChars))
        val shift = if (a > 0) 1 - a else 0
        val ss = SpannableString(piece)
        found.forEach { r -> val s = r.first + shift; val e = r.last + 1 + shift; if (s >= 0 && e <= piece.length) ss.setSpan(SearchS(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return ss
    }

    private fun highlighted(text: String): CharSequence {
        if (query.isBlank()) return text
        val ss = SpannableString(text)
        findAll(text, query).forEach { ss.setSpan(SearchS(), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return ss
    }

    inner class VH(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        val img = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
        val title = RichView(this@MainActivity).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16.5f); setTextColor(T.text); typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(1), dp(2), dp(1))
        }
        /** La nota en chiquito: texto con estilos, listas, fotos, notas de voz y videos. */
        val content = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val body = tv("", 14.5f, T.muted).apply { ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }
        val foot = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val date = tv("", 12f, T.faint)
        val icons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        var id: String? = null

        init {
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.addView(img, lp(MATCH, dp(120)).apply { bottomMargin = dp(10) })
            card.addView(title, lp(MATCH, WRAP))
            card.addView(body, lp(MATCH, WRAP).apply { topMargin = dp(4) })
            card.addView(content, lp(MATCH, WRAP).apply { topMargin = dp(2) })
            foot.addView(date, lp(0, WRAP, 1f))
            foot.addView(icons)
            card.addView(foot, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    /* ---------- ✅ tarjeta de pendientes arriba (como en Best Notes) ---------- */
    private var todo: List<Pending.Row> = emptyList()
    private var remindIds: Set<String> = emptySet()
    private fun showTodo() = view == "all" && query.isBlank() && selected.isEmpty() && todo.isNotEmpty()

    inner class HeadVH(val box: LinearLayout) : RecyclerView.ViewHolder(box)

    private fun bindHead(h: HeadVH) {
        val b = h.box
        b.removeAllViews()
        val open = prefs.getBoolean("homeTodoOpen", true)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(IconView(this, MoreIcons.CHECKBOX, 8.5f).apply { color = 0xFF30D158.toInt(); isClickable = false }, lp(dp(26), dp(26)))
        head.addView(tv(L.t("pending"), 17f, T.text, true).apply { setPadding(dp(8), 0, dp(8), 0) })
        head.addView(tv("${todo.size}", 13f, T.onPill, true).apply { gravity = Gravity.CENTER; minWidth = dp(24); setPadding(dp(7), dp(2), dp(7), dp(2)); background = roundBg(T.text, dp(12).toFloat()) })
        head.addView(tv(if (open) "  ▴" else "  ▾", 16f, T.muted), lp(0, WRAP, 1f))
        head.addView(tv(L.t("seeAll") + " ›", 14f, T.muted, true).apply { setPadding(dp(8), dp(6), 0, dp(6)); setOnClickListener { startActivity(Intent(this@MainActivity, PendingActivity::class.java)) } })
        head.setOnClickListener { prefs.edit().putBoolean("homeTodoOpen", !open).apply(); adapter.notifyItemChanged(0) }
        b.addView(head)
        if (open) for (r in todo.take(3)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(0, dp(6), 0, 0) }
            val box = ListaView.Mark(this, "check", false, 0)
            box.setOnClickListener { dialog().setMessage(L.t("ckDoneQ") + "\n\n" + r.text).setPositiveButton(L.t("ckDoneYes")) { _, _ -> Pending.markDone(r) {} }.setNegativeButton(L.t("cancel"), null).show() }
            row.addView(box, lp(dp(34), dp(34)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { open(r.note) } }
            col.addView(tv(r.text, 15f, T.text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0) })
            col.addView(tv(r.note.title.ifEmpty { L.t("untitled") }, 12.5f, T.muted, true))
            row.addView(col, lp(0, WRAP, 1f))
            b.addView(row)
        }
    }

    private fun loadTodo() {
        remindIds = Reminders.all(this).filter { it.at > System.currentTimeMillis() }.map { it.noteId }.toSet()
        Pending.load { rows -> val had = showTodo(); todo = rows; if (had != showTodo() || had) adapter.notifyDataSetChanged() }
    }

    inner class NotesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<NoteMeta> = emptyList()
        init { setHasStableIds(true) }
        fun set(l: List<NoteMeta>) { items = l; notifyDataSetChanged() }
        private fun off() = if (showTodo()) 1 else 0
        override fun getItemCount() = items.size + off()
        override fun getItemViewType(position: Int) = if (position < off()) 1 else 0
        override fun getItemId(position: Int) = if (position < off()) -7L else items[position - off()].id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val box = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = Glass.make(this@MainActivity, 24f) }
                box.layoutParams = StaggeredGridLayoutManager.LayoutParams(MATCH, WRAP).apply { setMargins(dp(5), dp(5), dp(5), dp(8)); isFullSpan = true }
                return HeadVH(box)
            }
            val card = LinearLayout(parent.context)
            card.layoutParams = StaggeredGridLayoutManager.LayoutParams(MATCH, WRAP).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            Anim.press(card)                          // ✨ se hunde al tocarla y rebota
            return VH(card)
        }

        override fun onBindViewHolder(hh: RecyclerView.ViewHolder, position: Int) {
            if (hh is HeadVH) { bindHead(hh); return }
            val h = hh as VH
            val n = items[position - off()]
            h.id = n.id
            // ✨ las tarjetas llegan una tras otra la primera vez que se ven
            if (shownCards.add(n.id)) { h.card.rotation = 0f; Anim.enter(h.card, 40L * minOf(cardWave++, 10)) } else { h.card.animate().cancel(); h.card.alpha = 1f; h.card.translationY = 0f; h.card.scaleX = 1f; h.card.scaleY = 1f; h.card.rotation = 0f }
            val grid = lm.spanCount == 2
            val sel = n.id in selected
            h.card.background = Glass.make(this@MainActivity, 22f, tint = n.color, bar = n.color != 0, selected = sel)
            h.title.text = highlighted(n.title.ifEmpty { "" })
            h.title.visibility = if (n.title.isEmpty()) View.GONE else View.VISIBLE
            h.title.setTextColor(if (n.titleColor != 0) n.titleColor else T.text)
            h.content.removeAllViews(); h.content.visibility = View.GONE
            val pv = n.pv
            if (!n.locked && query.isBlank() && pv != null) {
                // ✨ la tarjeta se ve como la nota (colores, contorno, tamaños, fotos, voz y video)
                val (tr, blocks) = n.pvCache ?: previewParse(pv).also { n.pvCache = it }
                if (tr != null && n.title.isNotEmpty()) { h.title.setTextColor(T.text); h.title.setRuns(tr, 16.5f / 24f) }
                h.body.visibility = View.GONE; h.img.visibility = View.GONE
                if (blocks.isEmpty() && n.title.isEmpty()) { h.body.visibility = View.VISIBLE; h.body.text = L.t("untitled") }
                h.content.visibility = View.VISIBLE
                fillPreview(h, n, blocks, grid)
            } else if (n.locked) {
                h.body.text = L.t("lockedNote"); h.body.maxLines = 1
                h.img.visibility = View.GONE
            } else {
                h.body.maxLines = if (grid) 9 else 3
                val txt = n.plain.trim()
                h.body.text = snippet(txt.take(1200), if (grid) 400 else 220)
                h.body.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
                if (n.title.isEmpty() && txt.isEmpty()) { h.body.visibility = View.VISIBLE; h.body.text = L.t("untitled") }
                val th = n.thumb
                if (th != null) {
                    h.img.visibility = View.VISIBLE
                    h.img.background = roundBg(tw(0x10), dp(12).toFloat())
                    h.img.setImageDrawable(null)
                    h.img.layoutParams.height = dp(if (grid) 120 else 150)
                    val want = n.id
                    Thumbs.load(th, dp(220)) { bm -> if (h.id == want) h.img.setImageBitmap(bm) }
                } else h.img.visibility = View.GONE
            }
            h.date.text = if (n.deleted) "🗑️ " + L.t("trashDaysLeft", "n" to Store.daysLeft(n)) else dateText(n.updated)
            h.icons.removeAllViews()
            if (n.checkTotal > 0 && !n.locked) h.icons.addView(tv("✓ " + L.t("progress", "d" to n.checkDone, "t" to n.checkTotal), 12f, if (n.checkDone == n.checkTotal) 0xFF30D158.toInt() else T.faint).apply { setPadding(0, 0, dp(6), 0) })
            fun ic(k: Int, c: Int = T.faint) = h.icons.addView(IconView(this@MainActivity, k, 6.5f).apply { color = c; isClickable = false }, lp(dp(18), dp(18)))
            if (n.hasMedia) ic(Icons.MIC)
            if (n.locked) ic(Icons.LOCK)
            if (n.fav) ic(MoreIcons.STAR5_ON, 0xFFFFD60A.toInt())
            if (n.pinned && view != "trash") ic(Icons.PIN)
            if (n.id in remindIds) ic(Icons.CLOCK)
            h.card.setOnClickListener {
                if (selected.isNotEmpty()) { if (!selected.remove(n.id)) selected.add(n.id); Anim.bump(h.card, 1.04f); notifyItemChanged(h.bindingAdapterPosition); paintHeader() }
                else open(n)
            }
            h.card.setOnLongClickListener {
                if (!selected.remove(n.id)) selected.add(n.id)
                Anim.bump(h.card, 1.05f)
                h.card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                notifyItemChanged(h.bindingAdapterPosition); paintHeader(); true
            }
        }
    }

    /** Arma la nota en chiquito dentro de la tarjeta. */
    private fun fillPreview(h: VH, n: NoteMeta, blocks: List<Block>, grid: Boolean) {
        val box = h.content
        // ✏️ el dibujo de la nota (va primero, porque está encima de todo)
        val ink = n.inkCache ?: (n.pv?.let { previewInk(it) }?.let { Stroke.listFrom(it) } ?: emptyList<Stroke>()).also { n.inkCache = it }
        if (ink.isNotEmpty()) box.addView(InkThumb(this, ink).apply { background = roundBg(tw(0x0D), dp(12).toFloat()) }, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        var lines = if (grid) 10 else 4
        val sc = 14.5f / 17f
        fun rich(runs: List<Run>, max: Int): RichView = RichView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f); setTextColor((T.text and 0xFFFFFF) or 0xD9000000.toInt()); setLineSpacing(0f, 1.08f)
            maxLines = maxOf(1, max); ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(1), dp(3), dp(1))
            setRuns(runs, sc)
        }
        for (b in blocks) {
            if (lines <= 0) break
            when (b) {
                is Block.Text -> {
                    val v = rich(b.runs, lines)
                    box.addView(v, lp(MATCH, WRAP).apply { topMargin = dp(4) })
                    lines -= minOf(lines, runsPlain(b.runs).count { it == '\n' } + 1 + runsPlain(b.runs).length / 28)
                }
                is Block.ListB -> {
                    var k = 0
                    for (it in b.items) {
                        if (lines <= 0) break
                        k++
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                        val mark = when (b.kind) {
                            "check" -> tv(if (it.done) "☑" else "☐", 15f, if (it.done) 0xFF30D158.toInt() else T.muted)
                            "ol" -> tv("$k.", 13.5f, T.muted, true)
                            else -> tv("•", 15f, T.muted, true)
                        }
                        row.addView(mark.apply { setPadding(0, 0, dp(6), 0) })
                        val t = rich(it.runs, 2)
                        if (it.done) { t.paintFlags = t.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG; t.alpha = 0.55f }
                        row.addView(t, lp(0, WRAP, 1f))
                        box.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(3) })
                        lines--
                    }
                }
                is Block.Img -> {
                    val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundBg(tw(0x10), dp(12).toFloat()) }
                    box.addView(iv, lp(MATCH, dp(if (grid) 120 else 150)).apply { topMargin = dp(6); bottomMargin = dp(2) })
                    val want = n.id
                    Thumbs.load(b.file, dp(220)) { bm -> if (h.id == want) iv.setImageBitmap(bm) }
                    if (b.beside.isNotEmpty()) box.addView(rich(b.beside, 2), lp(MATCH, WRAP).apply { topMargin = dp(2) })
                    lines -= 3
                }
                is Block.Media -> {
                    if (b.kind == "video") {
                        val fr = FrameLayout(this).apply { clipToOutline = true; background = roundBg(0xFF000000.toInt(), dp(12).toFloat()) }
                        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                        fr.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                        val want = n.id
                        Thumbs.load(MediaFiles.thumbName(b.file), dp(220)) { bm -> if (h.id == want && bm != null) iv.setImageBitmap(bm) }
                        val play = FrameLayout(this).apply { background = roundBg(0xE6FFFFFF.toInt(), dp(20).toFloat()) }
                        play.addView(IconView(this, MoreIcons.PLAY, 8f).apply { color = T.onPill; isClickable = false }, FrameLayout.LayoutParams(dp(40), dp(40)))
                        fr.addView(play, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
                        if (b.dur > 0) fr.addView(tv(MediaFiles.timeText(b.dur), 11.5f, Color.WHITE, true).apply {
                            setPadding(dp(7), dp(2), dp(7), dp(2)); background = roundBg(0x99000000.toInt(), dp(8).toFloat())
                        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
                        box.addView(fr, lp(MATCH, dp(if (grid) 110 else 140)).apply { topMargin = dp(6) })
                        lines -= 3
                    } else {
                        // 🎙️ nota de voz: una pastillita como el reproductor
                        val pill = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(12), dp(6))
                            background = roundBg(tw(0x1A), dp(20).toFloat(), tw(0x26), dp(1))
                        }
                        val play = FrameLayout(this).apply { background = roundBg(T.pill, dp(14).toFloat()) }
                        play.addView(IconView(this, MoreIcons.PLAY, 6f).apply { color = T.onPill; isClickable = false }, FrameLayout.LayoutParams(dp(28), dp(28)))
                        pill.addView(play, lp(dp(28), dp(28)))
                        // ondita
                        val wave = object : View(this) {
                            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B7CFF.toInt(); strokeCap = android.graphics.Paint.Cap.ROUND; strokeWidth = dp(2.5f).toFloat() }
                            override fun onDraw(c: android.graphics.Canvas) {
                                val n2 = (width / dp(6)).coerceAtLeast(1)
                                for (i in 0 until n2) { val hh = height * (0.25f + 0.6f * Math.abs(Math.sin(i * 1.7 + b.file.hashCode())).toFloat()); val x = dp(3) + i * dp(6f).toFloat()
                                    c.drawLine(x, (height - hh) / 2, x, (height + hh) / 2, p) }
                            }
                        }
                        pill.addView(wave, lp(0, dp(20), 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
                        pill.addView(IconView(this, MoreIcons.MIC, 6f).apply { color = T.muted; isClickable = false }, lp(dp(16), dp(16)))
                        pill.addView(tv(MediaFiles.timeText(b.dur), 12.5f, T.muted, true).apply { setPadding(dp(3), 0, 0, 0) })
                        box.addView(pill, lp(MATCH, WRAP).apply { topMargin = dp(6) })
                        lines -= 2
                    }
                }
            }
        }
    }

    private fun blendCard(c: Int): Int = (c and 0x00FFFFFF) or 0x2E000000
}
