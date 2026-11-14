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

    companion object { const val PICK_FILE = 41 }

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
        header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(4), 0) }
        col.addView(header, lp(MATCH, dp(56)))
        titleTv = tv("", 22f, T.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

        // Buscar
        val sb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundBg(T.card, dp(24).toFloat(), T.border, dp(1)); setPadding(dp(14), 0, dp(4), 0)
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
        col.addView(sb, lp(MATCH, dp(48)).apply { setMargins(dp(14), dp(4), dp(14), dp(8)) })

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
            color = T.text; pressBg(T.cardStrong, 26f, T.border); elevation = dp(4).toFloat()
            setOnClickListener { newNote("check") }
            contentDescription = L.t("newCheck")
        }
        val pen = IconView(this, MoreIcons.PEN, 10f).apply {
            color = T.text; pressBg(T.cardStrong, 26f, T.border); elevation = dp(4).toFloat()
            setOnClickListener { newNote("draw") }
            contentDescription = L.t("newDraw")
        }
        fabs.addView(pen, lp(dp(52), dp(52)).apply { marginEnd = dp(12) })
        fabs.addView(ck, lp(dp(52), dp(52)).apply { marginEnd = dp(12) })
        val fab = IconView(this, MoreIcons.PLUS, 12f).apply {
            color = 0xFF16161A.toInt(); pressBg(T.text, 30f); elevation = dp(6).toFloat()
            setOnClickListener { newNote("text") }
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
        if (selected.isNotEmpty()) {
            header.addView(iconBtn(Icons.CLOSE) { selected.clear(); refresh() })
            header.addView(tv(if (selected.size == 1) L.t("selected1") else L.t("selected", "n" to selected.size), 18f, T.text, true), lp(0, WRAP, 1f))
            if (view == "trash") {
                header.addView(iconBtn(MoreIcons.RESTORE) { selRecover() })
                header.addView(iconBtn(Icons.TRASH, 11f, T.danger) { selDeleteForever() })
            } else {
                header.addView(iconBtn(Icons.PIN) { selPin() })
                header.addView(iconBtn(MoreIcons.STAR5) { selFav() })
                header.addView(iconBtn(MoreIcons.FOLDER) { selFolder() })
                header.addView(iconBtn(MoreIcons.PALETTE) { selColor() })
                header.addView(iconBtn(Icons.TRASH) { selTrash() })
            }
            fabBox.visibility = View.GONE
            return
        }
        fabBox.visibility = if (view == "trash") View.GONE else View.VISIBLE
        header.addView(iconBtn(Icons.MENU) { openDrawer() })
        titleTv.text = viewName()
        (titleTv.parent as? ViewGroup)?.removeView(titleTv)
        header.addView(titleTv, lp(0, WRAP, 1f).apply { marginStart = dp(6) })
        if (view == "trash") {
            header.addView(tv(L.t("emptyTrash"), 14f, T.danger, true).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { emptyTrash() } })
        } else {
            val grid = lm.spanCount == 2
            header.addView(iconBtn(if (grid) MoreIcons.LISTV else MoreIcons.GRID, 10f) { toggleLayout() })
            header.addView(iconBtn(MoreIcons.SORT, 10f) { pickSort() })
        }
    }

    private fun viewName(): String = when {
        view == "all" -> L.t("appName")
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

    fun refresh() {
        selected.retainAll(Store.notes.map { it.id }.toSet())
        val items = visible()
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
        dialog().setTitle(L.t("sortBy")).setSingleChoiceItems(arrayOf(L.t("sortUpdated"), L.t("sortCreated"), L.t("sortAlpha")), cur) { d, i ->
            prefs.edit().putString("sort", keys[i]).apply(); d.dismiss(); refresh()
        }.show()
    }

    /* ---------- abrir y crear ---------- */
    private fun newNote(kind: String) {
        val i = Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_NEW, kind)
        if (view.startsWith("f:")) i.putExtra(NoteActivity.EXTRA_FOLDER, view.substring(2))
        startActivity(i)
    }

    private fun open(n: NoteMeta) {
        val go = {
            val i = Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)
            if (query.isNotBlank()) i.putExtra(NoteActivity.EXTRA_Q, query.trim())
            startActivity(i)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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
        val scrim = View(this).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f; setOnClickListener { closeDrawer() } }
        val panelW = minOf(dp(310), (resources.displayMetrics.widthPixels * 0.84f).toInt())
        val sv = ScrollView(this).apply { setBackgroundColor(T.sheet); isVerticalScrollBarEnabled = false }
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(18), dp(10), dp(24)) }
        sv.addView(p)
        p.addView(tv("Best Notes 2", 24f, T.text, true).apply { setPadding(dp(14), dp(4), 0, dp(14)) })
        fun count(f: (NoteMeta) -> Boolean) = Store.notes.count(f)
        fun nav(icon: Int, label: String, v: String, n: Int, dot: Int = 0) {
            val r = menuRow(this, icon, label, null, T.text, dot) { view = v; selected.clear(); closeDrawer(); refresh(); list.scrollToPosition(0) }
            (r as LinearLayout).addView(tv(if (n > 0) "$n" else "", 13f, T.muted))
            if (view == v) r.background = roundBg(0x1FFFFFFF, dp(14).toFloat())
            p.addView(r)
        }
        nav(Icons.NOTEBOOK, L.t("all"), "all", count { !it.deleted })
        nav(MoreIcons.STAR5, L.t("fav"), "fav", count { !it.deleted && it.fav })
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
        p.addView(menuRow(this, Icons.BOOK, L.t("guide")) { closeDrawer(); startActivity(Intent(this, GuideActivity::class.java)) })
        p.addView(sep())
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        p.addView(tv(L.t("devBy") + "\n" + L.t("version", "v" to (ver ?: "")), 12.5f, T.muted).apply { setPadding(dp(14), dp(8), dp(8), 0) })

        val box = FrameLayout(this)
        box.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(sv, FrameLayout.LayoutParams(panelW, MATCH))
        sv.translationX = -panelW.toFloat()
        root.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
        drawer = box
        scrim.animate().alpha(1f).setDuration(180).start()
        sv.animate().translationX(0f).setDuration(200).start()
    }

    private fun sep() = View(this).apply { setBackgroundColor(T.border) }.also { it.layoutParams = lp(MATCH, dp(1)).apply { setMargins(dp(14), dp(8), dp(14), dp(8)) } }

    private fun closeDrawer() {
        val d = drawer ?: return
        drawer = null
        val box = d as FrameLayout
        box.getChildAt(0).animate().alpha(0f).setDuration(160).start()
        val sv = box.getChildAt(1)
        sv.animate().translationX(-sv.width.toFloat()).setDuration(180).withEndAction { root.removeView(box) }.start()
    }

    private fun folderMenu(f: Folder) {
        dialog().setTitle(f.name).setItems(arrayOf(L.t("editFolder"), L.t("deleteFolder"))) { _, i ->
            if (i == 0) folderDialog(f)
            else confirm(L.t("deleteFolder"), L.t("deleteFolderMsg", "n" to f.name), L.t("del"), true) {
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

    private fun pickLang() {
        val keys = arrayOf("auto", "es", "en")
        dialog().setTitle(L.t("language")).setSingleChoiceItems(arrayOf(L.t("langAuto"), "Español", "English"), keys.indexOf(L.choice)) { d, i ->
            d.dismiss(); L.set(this, keys[i]); recreate()
        }.show()
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
                    (if (s.missing > 0) "\n\n" + L.t("importMediaFail", "n" to s.missing) else "")
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
        val tf = DateFormat.getTimeInstance(DateFormat.SHORT, L.locale)
        return when {
            DateUtils.isToday(t) -> tf.format(Date(t))
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
        val title = tv("", 16.5f, T.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
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
            foot.addView(date, lp(0, WRAP, 1f))
            foot.addView(icons)
            card.addView(foot, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    inner class NotesAdapter : RecyclerView.Adapter<VH>() {
        private var items: List<NoteMeta> = emptyList()
        init { setHasStableIds(true) }
        fun set(l: List<NoteMeta>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(parent.context)
            card.layoutParams = StaggeredGridLayoutManager.LayoutParams(MATCH, WRAP).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            return VH(card)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val n = items[position]
            h.id = n.id
            val grid = lm.spanCount == 2
            val sel = n.id in selected
            val base = if (n.color != 0) blendCard(n.color) else T.card
            h.card.background = roundBg(base, dp(18).toFloat(), if (sel) T.accent else if (n.color != 0) (n.color and 0x00FFFFFF) or 0x66000000 else T.border, dp(if (sel) 2.5f else 1f))
            h.title.text = highlighted(n.title.ifEmpty { "" })
            h.title.visibility = if (n.title.isEmpty()) View.GONE else View.VISIBLE
            h.title.setTextColor(if (n.titleColor != 0) n.titleColor else T.text)
            if (n.locked) {
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

    private fun blendCard(c: Int): Int = (c and 0x00FFFFFF) or 0x2E000000
}
