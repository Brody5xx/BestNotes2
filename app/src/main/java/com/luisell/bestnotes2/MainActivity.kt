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

    companion object { const val PICK_FILE = 41; const val REQ_WALL = 42 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        buildUi()
        Store.addListener(listener)
        Store.load { refresh() }
        handleIntent(intent)
    }

    override fun onDestroy() { Store.removeListener(listener); super.onDestroy() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    override fun onResume() { super.onResume(); if (Store.loaded) refresh() }

    /* ---------- pantalla ---------- */
    private fun buildUi() {
        root = FrameLayout(this)
        edgeToEdge(root)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), 0) }
        col.addView(header, lp(MATCH, dp(60)))
        // Título grande y cuántas notas (como "All notes / 35 notes" en Best Notes)
        titleTv = tv("", 34f, T.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(18), dp(10), dp(18), 0) }
        countTv = tv("", 15f, T.muted).apply { setPadding(dp(18), 0, dp(18), dp(6)) }
        col.addView(titleTv, lp(MATCH, WRAP)); col.addView(countTv, lp(MATCH, WRAP))

        // Buscar
        val sb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Glass.make(this@MainActivity, 24f); setPadding(dp(16), 0, dp(4), 0)
        }
        sb.addView(IconView(this, Icons.SEARCH, 8.5f).apply { color = T.muted; isClickable = false }, lp(dp(22), dp(22)))
        search = EditText(this).apply {
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
            color = 0xFF16161A.toInt(); pressBg(T.text, 30f); elevation = dp(8).toFloat()
            setOnClickListener { pop(it); newNote("text", it) }
            contentDescription = L.t("newNote")
        }
        fabs.addView(fab, lp(dp(60), dp(60)))
        root.addView(fabs, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(24)) })
        fabBox = fabs
        setContentView(root)
        root.isFocusableInTouchMode = true; root.requestFocus()
        paintHeader()
    }

    private lateinit var fabBox: View

    private fun paintHeader() {
        header.removeAllViews()
        titleTv.text = viewName()
        val total = visibleCount
        countTv.text = if (total == 1) L.t("nNotes1") else L.t("nNotes", "n" to total)
        if (selected.isNotEmpty()) {
            header.addView(glassBtn(Icons.CLOSE) { selected.clear(); refresh() })
            header.addView(tv(if (selected.size == 1) L.t("selected1") else L.t("selected", "n" to selected.size), 17f, T.text, true).apply { setPadding(dp(6), 0, 0, 0) }, lp(0, WRAP, 1f))
            if (view == "trash") {
                header.addView(glassBtn(MoreIcons.RESTORE) { selRecover() })
                header.addView(glassBtn(Icons.TRASH, 10f, T.danger) { selDeleteForever() })
            } else {
                header.addView(glassBtn(Icons.PIN, 9f) { selPin() })
                header.addView(glassBtn(MoreIcons.STAR5, 9f) { selFav() })
                header.addView(glassBtn(MoreIcons.FOLDER, 9f) { selFolder() })
                header.addView(glassBtn(MoreIcons.PALETTE, 9f) { selColor() })
                header.addView(glassBtn(Icons.TRASH, 9f) { selTrash() })
            }
            fabBox.visibility = View.GONE
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
            spread(); header.addView(glassBtn(MoreIcons.SUN) { appearance() })
        }
    }

    private fun viewName(): String = when {
        view == "all" -> L.t("all")
        view == "fav" -> L.t("fav")
        view == "trash" -> L.t("trash")
        view.startsWith("f:") -> Store.folders.firstOrNull { it.id == view.substring(2) }?.name ?: L.t("all")
        view.startsWith("t:") -> "#" + view.substring(2)
        else -> L.t("all")
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

    fun refresh() {
        selected.retainAll(Store.notes.map { it.id }.toSet())
        val items = visible()
        visibleCount = items.size
        adapter.set(items)
        empty.visibility = if (items.isEmpty() && Store.loaded) View.VISIBLE else View.GONE
        empty.text = when {
            query.isNotBlank() -> L.t("noMatch", "q" to query.trim())
            view == "trash" -> L.t("trashEmpty")
            view == "fav" -> L.t("noFav")
            view.startsWith("f:") -> L.t("folderEmpty")
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
    private fun selPin() { val l = sel(); val v = !l.all { it.pinned }; l.forEach { it.pinned = v; Store.saveMeta(it) }; done() }
    private fun selFav() { val l = sel(); val v = !l.all { it.fav }; l.forEach { it.fav = v; Store.saveMeta(it) }; done() }
    private fun selTrash() {
        val now = System.currentTimeMillis()
        sel().forEach { it.deleted = true; it.deletedAt = now; Store.saveMeta(it) }
        toast(L.t("movedTrash")); done()
    }
    private fun selRecover() { sel().forEach { it.deleted = false; Store.saveMeta(it) }; done() }
    private fun selDeleteForever() {
        confirm(L.t("delForever"), L.t("delForeverMsg"), L.t("del"), true) { Store.deleteForever(selected.toList()); done() }
    }
    private fun selFolder() {
        val names = listOf(L.t("noFolder")) + Store.folders.map { it.name }
        dialog().setTitle(L.t("moveToFolder")).setItems(names.toTypedArray()) { _, i ->
            val f = if (i == 0) null else Store.folders[i - 1].id
            sel().forEach { it.folder = f; Store.saveMeta(it) }; done()
        }.show()
    }
    private fun selColor() {
        lateinit var d: AlertDialog
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
        // Arriba: el logo, BEST NOTES 2 y "Listo" (como en Best Notes)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(4), dp(10)) }
        top.addView(android.widget.ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); clipToOutline = true; background = roundBg(0, dp(10).toFloat()) }, lp(dp(40), dp(40)))
        top.addView(tv("BEST\nNOTES 2", 15f, T.text, true).apply { letterSpacing = 0.18f; setPadding(dp(10), 0, 0, 0); setLineSpacing(0f, 0.9f) }, lp(0, WRAP, 1f))
        top.addView(tv(L.t("done"), 14f, 0xFF16161A.toInt(), true).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); pressBg(T.text, 18f); setOnClickListener { closeDrawer() } })
        p.addView(top)
        // Tarjeta blanca de la guía (como "App guide")
        val guide = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(10), dp(12))
            pressBg(0xF2FFFFFF.toInt(), 18f); setOnClickListener { closeDrawer(); startActivity(Intent(this@MainActivity, GuideActivity::class.java)) }
        }
        guide.addView(IconView(this, Icons.BOOK, 9f).apply { color = 0xFF16161A.toInt(); isClickable = false }, lp(dp(26), dp(26)))
        val gcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        gcol.addView(tv(L.t("guide"), 15f, 0xFF16161A.toInt(), true)); gcol.addView(tv(L.t("guideSub"), 12f, 0xFF55555A.toInt()))
        guide.addView(gcol, lp(0, WRAP, 1f)); guide.addView(tv("›", 20f, 0xFF16161A.toInt()))
        p.addView(guide, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
        fun count(f: (NoteMeta) -> Boolean) = Store.notes.count(f)
        fun nav(icon: Int, label: String, v: String, n: Int, dot: Int = 0) {
            val r = menuRow(this, icon, label, null, T.text, dot) { view = v; selected.clear(); closeDrawer(); refresh(); list.scrollToPosition(0) }
            (r as LinearLayout).addView(tv(if (n > 0) "$n" else "", 13f, T.muted))
            if (view == v) r.background = roundBg(0x1FFFFFFF, dp(14).toFloat())
            p.addView(r)
        }
        nav(Icons.NOTEBOOK, L.t("all"), "all", count { !it.deleted })
        nav(MoreIcons.STAR5, L.t("fav"), "fav", count { !it.deleted && it.fav })
        p.addView(menuRow(this, MoreIcons.CHECKBOX, L.t("pending"), L.t("pendingHint")) { closeDrawer(); startActivity(Intent(this, PendingActivity::class.java)) }.also { r ->
            val n = Store.notes.filter { !it.deleted && !it.locked }.sumOf { maxOf(0, it.checkTotal - it.checkDone) }
            if (n > 0) (r as LinearLayout).addView(tv("$n", 13f, 0xFF16161A.toInt(), true).apply { gravity = Gravity.CENTER; minWidth = dp(24); setPadding(dp(7), dp(2), dp(7), dp(2)); background = roundBg(T.text, dp(12).toFloat()) })
        })
        p.addView(menuRow(this, MoreIcons.SHIELD, L.t("vault"), L.t("vaultHint")) { closeDrawer(); startActivity(Intent(this, VaultActivity::class.java)) })
        p.addView(menuRow(this, Icons.CALC, L.t("calc"), L.t("calcSub")) { closeDrawer(); startActivity(Intent(this, CalcActivity::class.java)) })
        p.addView(menuRow(this, Icons.REPORT, L.t("report"), L.t("reportHint")) { closeDrawer(); startActivity(Intent(this, ReportActivity::class.java)) })
        p.addView(menuRow(this, Icons.BOX, L.t("materials"), L.t("materialsHint")) { closeDrawer(); startActivity(Intent(this, MaterialsActivity::class.java)) })
        p.addView(sep())
        p.addView(tv(L.t("folders"), 13f, T.muted, true).apply { setPadding(dp(14), dp(6), 0, dp(4)) })
        for (f in Store.folders) {
            nav(MoreIcons.FOLDER, f.name, "f:" + f.id, count { !it.deleted && it.folder == f.id }, if (f.color != 0) f.color else 0)
            p.getChildAt(p.childCount - 1).setOnLongClickListener { closeDrawer(); folderMenu(f); true }
        }
        p.addView(menuRow(this, MoreIcons.PLUS, L.t("newFolder")) { closeDrawer(); folderDialog(null) })
        val tags = HashMap<String, Int>()
        Store.notes.filter { !it.deleted && !it.locked }.forEach { n -> tagsOf(n.title + " " + n.plain).forEach { tags[it] = (tags[it] ?: 0) + 1 } }
        if (tags.isNotEmpty()) {
            p.addView(sep())
            p.addView(tv(L.t("tags"), 13f, T.muted, true).apply { setPadding(dp(14), dp(6), 0, dp(4)) })
            tags.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { t -> nav(Icons.TAG, "#$t", "t:$t", tags[t] ?: 0) }
        }
        p.addView(sep())
        nav(Icons.TRASH, L.t("trash"), "trash", count { it.deleted })
        p.addView(sep())
        p.addView(menuRow(this, MoreIcons.IMPORT, L.t("import"), L.t("importSub")) { closeDrawer(); pickFile() })
        p.addView(menuRow(this, Icons.GLOBE, L.t("language"), when (L.choice) { "es" -> "Español"; "en" -> "English"; else -> L.t("langAuto") }) { closeDrawer(); pickLang() })
        p.addView(menuRow(this, Icons.CLOCK, L.t("hourFmt"), when (L.hourFmt) { "12" -> "12 h (3:00 PM)"; "24" -> "24 h (15:00)"; else -> L.t("hourAuto") }) { closeDrawer(); pickHourFmt() })
        p.addView(menuRow(this, Icons.LOCK, L.t("lockApp"), if (AppLock.on(this)) "✓ " + L.t("lockAppOn") else null) { closeDrawer(); toggleAppLock() })
        if (Pin.saved(this) != null) p.addView(menuRow(this, Icons.KEY, L.t("changePin")) { closeDrawer(); Pin.check(this) { Pin.create(this) { toast(L.t("pinChanged")) } } })
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
    }

    /* ---------- ☀️ Apariencia: el fondo de la app (panel bonito) ---------- */
    private fun appearance() {
        val cur = Wall.current(this)
        val opts = listOf("best" to "Best Notes") + Wall.GRADS.map { it[0] as String to (if (L.lang == "es") it[1] else it[2]) as String } +
            ("photo" to L.t("wallPhoto"))
        val sh = Sheet(this, "☀️ " + L.t("appearance"), L.t("appearanceSub"))
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
                (m.parent as? View)?.foreground = if (on) roundBg(0, dp(18).toFloat(), 0xFF4FC3F7.toInt(), dp(3)) else roundBg(0, dp(18).toFloat(), 0x26FFFFFF, dp(1))
            }
        }
        for ((id, name) in opts) {
            val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(4), dp(6), dp(10)) }
            val th = FrameLayout(this).apply {
                background = if (id == "photo" && !Wall.photoFile(this@MainActivity).exists()) roundBg(0x1AFFFFFF, dp(18).toFloat())
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
                card.addView(View(this).apply { background = roundBg(0x59FFFFFF, dp(2).toFloat()) }, lp((cell * 0.6f).toInt(), dp(3)).apply { topMargin = dp(5) })
                card.addView(View(this).apply { background = roundBg(0x59FFFFFF, dp(2).toFloat()) }, lp((cell * 0.35f).toInt(), dp(3)).apply { topMargin = dp(4) })
                mini.addView(card, lp(MATCH, WRAP).apply { bottomMargin = dp(6) })
            }
            if (id == "photo" && !Wall.photoFile(this).exists()) th.addView(tv("🖼️", 28f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
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
                selected = id; Wall.set(this, id); paint(); fadeWall()
            }
            grid.addView(v)
        }
        sh.custom(grid)
        // difuminar mi foto
        val sw = android.widget.Switch(this).apply {
            text = "✨  " + L.t("wallBlur"); setTextColor(T.text); textSize = 15.5f; isChecked = Wall.blur(this@MainActivity); setPadding(dp(14), dp(10), dp(14), dp(6))
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt()))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF4FC3F7.toInt(), 0x55FFFFFF))
            setOnCheckedChangeListener { _, on -> Wall.setBlur(this@MainActivity, on); if (Wall.current(this@MainActivity) == "photo") fadeWall() }
        }
        sh.custom(sw)
        sh.custom(tv("🌙 " + L.t("lightSoon"), 12.5f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(4)) })
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
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try { startActivityForResult(i, REQ_WALL) } catch (_: Exception) { toast(L.t("cantOpen")) }
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
    private fun folderDialog(f: Folder?) {
        var color = f?.color ?: T.noteColors[5]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
        val e = EditText(this).apply {
            setText(f?.name ?: ""); hint = L.t("folderName"); setTextColor(T.text); setHintTextColor(T.faint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(e, lp(MATCH, WRAP).apply { setMargins(dp(14), 0, dp(14), dp(6)) })
        val holder = FrameLayout(this)
        fun row() { holder.removeAllViews(); holder.addView(colorRow(T.noteColors, false, color) { c -> color = c; row() }) }
        row()
        box.addView(holder)
        val d = dialog().setTitle(L.t(if (f == null) "newFolder" else "editFolder")).setView(box)
            .setPositiveButton(L.t("save")) { _, _ ->
                val name = e.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val ff = f ?: Folder(newId(), name, color, Store.folders.size)
                ff.name = name; ff.color = color
                Store.saveFolder(ff); Store.changed()
            }.setNegativeButton(L.t("cancel"), null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show(); e.requestFocus()
    }

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
        val keys = arrayOf("auto", "es", "en")
        val sh = Sheet(this, "🌐 " + L.t("language"))
        val labels = arrayOf(L.t("langAuto"), "Español", "English")
        for (i in 0..2) sh.quick(Icons.GLOBE, labels[i], intArrayOf(Sheet.GRAY, Sheet.ORANGE, Sheet.BLUE)[i], keys[i] == L.choice) { L.set(this, keys[i]); recreate() }
        sh.show()
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
        if (requestCode == REQ_WALL && resultCode == RESULT_OK) data?.data?.let { u ->
            Thread {
                val ok = try { contentResolver.openInputStream(u)!!.use { inp -> Wall.photoFile(this).outputStream().use { inp.copyTo(it) } }; true } catch (_: Exception) { false }
                runOnUiThread { if (ok) { Wall.set(this, "photo"); fadeWall() } else toast(L.t("cantOpen")) }
            }.start()
        }
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri != null) { i.action = null; Store.load { askPassword(uri) } }
    }

    private fun askPassword(uri: Uri) {
        askText(L.t("importPass"), "", L.t("importPassHint"), password = true) { pass -> runImport(uri, pass) }
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
            catch (_: Backup.BadFile) { "!" + L.t("importBadFile") }
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
        head.addView(tv("${todo.size}", 13f, 0xFF16161A.toInt(), true).apply { gravity = Gravity.CENTER; minWidth = dp(24); setPadding(dp(7), dp(2), dp(7), dp(2)); background = roundBg(T.text, dp(12).toFloat()) })
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
            return VH(card)
        }

        override fun onBindViewHolder(hh: RecyclerView.ViewHolder, position: Int) {
            if (hh is HeadVH) { bindHead(hh); return }
            val h = hh as VH
            val n = items[position - off()]
            h.id = n.id
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
                    h.img.background = roundBg(0x10FFFFFF, dp(12).toFloat())
                    h.img.setImageDrawable(null)
                    h.img.layoutParams.height = dp(if (grid) 120 else 150)
                    val want = n.id
                    Thumbs.load(th, dp(220)) { bm -> if (h.id == want) h.img.setImageBitmap(bm) }
                } else h.img.visibility = View.GONE
            }
            h.date.text = dateText(n.updated)
            h.icons.removeAllViews()
            if (n.checkTotal > 0 && !n.locked) h.icons.addView(tv("✓ " + L.t("progress", "d" to n.checkDone, "t" to n.checkTotal), 12f, if (n.checkDone == n.checkTotal) 0xFF30D158.toInt() else T.faint).apply { setPadding(0, 0, dp(6), 0) })
            fun ic(k: Int, c: Int = T.faint) = h.icons.addView(IconView(this@MainActivity, k, 6.5f).apply { color = c; isClickable = false }, lp(dp(18), dp(18)))
            if (n.hasMedia) ic(Icons.MIC)
            if (n.locked) ic(Icons.LOCK)
            if (n.fav) ic(MoreIcons.STAR5_ON, 0xFFFFD60A.toInt())
            if (n.pinned && view != "trash") ic(Icons.PIN)
            if (n.id in remindIds) ic(Icons.CLOCK)
            h.card.setOnClickListener {
                if (selected.isNotEmpty()) { if (!selected.remove(n.id)) selected.add(n.id); notifyItemChanged(h.bindingAdapterPosition); paintHeader() }
                else open(n)
            }
            h.card.setOnLongClickListener {
                if (!selected.remove(n.id)) selected.add(n.id)
                notifyItemChanged(h.bindingAdapterPosition); paintHeader(); true
            }
        }
    }

    /** Arma la nota en chiquito dentro de la tarjeta. */
    private fun fillPreview(h: VH, n: NoteMeta, blocks: List<Block>, grid: Boolean) {
        val box = h.content
        var lines = if (grid) 10 else 4
        val sc = 14.5f / 17f
        fun rich(runs: List<Run>, max: Int): RichView = RichView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f); setTextColor(0xD9FFFFFF.toInt()); setLineSpacing(0f, 1.08f)
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
                    val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = roundBg(0x10FFFFFF, dp(12).toFloat()) }
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
                        play.addView(IconView(this, MoreIcons.PLAY, 8f).apply { color = 0xFF161618.toInt(); isClickable = false }, FrameLayout.LayoutParams(dp(40), dp(40)))
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
                            background = roundBg(0x1AFFFFFF, dp(20).toFloat(), 0x26FFFFFF, dp(1))
                        }
                        val play = FrameLayout(this).apply { background = roundBg(0xFFFFFFFF.toInt(), dp(14).toFloat()) }
                        play.addView(IconView(this, MoreIcons.PLAY, 6f).apply { color = 0xFF161618.toInt(); isClickable = false }, FrameLayout.LayoutParams(dp(28), dp(28)))
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
