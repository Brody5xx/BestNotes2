package com.luisell.bestnotes2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.Normalizer
import java.util.concurrent.Executors

/* ============================================================
   Store.kt — dónde viven las notas: una base de datos SQLite
   dentro del teléfono (rápida y segura) + la carpeta "media"
   para fotos, audios y videos.
   Al abrir la app se carga solo la lista (título, vista previa…);
   el contenido de una nota se lee cuando la abres.
   ============================================================ */

object Store {
    private lateinit var db: SQLiteDatabase
    lateinit var mediaDir: File
        private set
    /** Todo lo que escribe en la base de datos va por aquí, en orden y sin trabar la pantalla. */
    val io = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    val notes = ArrayList<NoteMeta>()
    val folders = ArrayList<Folder>()
    @Volatile var loaded = false
    private val listeners = ArrayList<() -> Unit>()

    fun init(ctx: Context) {
        if (::db.isInitialized) return
        db = Helper(ctx.applicationContext).writableDatabase
        mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
        appCtx = ctx.applicationContext
    }
    private var appCtx: Context? = null

    /** Carga la lista de notas (en segundo plano) y avisa cuando está lista. */
    private val waiting = ArrayList<() -> Unit>()
    private var loading = false

    fun load(done: () -> Unit) {
        if (loaded) { done(); return }
        waiting.add(done)
        if (loading) return
        loading = true
        val myGen = gen
        io.execute {
            val list = ArrayList<NoteMeta>()
            db.rawQuery(
                "SELECT id,title,title_color,color,folder,fav,pinned,deleted,deleted_at,locked,kind,created,updated,plain,thumb,ck_total,ck_done,has_media,pv FROM notes",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        NoteMeta(
                            c.getString(0), c.getString(1) ?: "", c.getInt(2), c.getInt(3), c.getString(4),
                            c.getInt(5) == 1, c.getInt(6) == 1, c.getInt(7) == 1, c.getLong(8), c.getInt(9) == 1,
                            c.getString(10) ?: "text", c.getLong(11), c.getLong(12), c.getString(13) ?: "",
                            c.getString(14), c.getInt(15), c.getInt(16), c.getInt(17) == 1, c.getString(18),
                        ),
                    )
                }
            }
            val fl = ArrayList<Folder>()
            db.rawQuery("SELECT id,name,color,ord FROM folders ORDER BY ord", null).use { c ->
                while (c.moveToNext()) fl.add(Folder(c.getString(0), c.getString(1) ?: "", c.getInt(2), c.getInt(3)))
            }
            list.forEach { it.folded = fold(it.title + "\n" + it.plain) }
            // notas de antes sin vista previa: se hace una sola vez
            val redo = appCtx?.getSharedPreferences("ajustes", 0)?.getInt("pvVer", 0) ?: 0
            for (m in list) if (m.pv == null || redo < 2) try {
                val (body, ex) = readBody(m.id)
                val exo = try { org.json.JSONObject(ex ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
                m.pv = previewJson(exo.optJSONArray("tr")?.let { runsFromJson(it) }, blocksFromJson(body), exo.optJSONArray("ink"))
                db.execSQL("UPDATE notes SET pv=? WHERE id=?", arrayOf(m.pv, m.id))
            } catch (_: Exception) {}
            main.post {
                if (myGen != gen) return@post          // llegó una recarga más nueva: esta se descarta
                notes.clear(); notes.addAll(list)
                folders.clear(); folders.addAll(fl)
                loaded = true; loading = false
                val w = waiting.toList(); waiting.clear()
                w.forEach { it() }
            }
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.toList().forEach { it() } }

    fun meta(id: String): NoteMeta? = notes.firstOrNull { it.id == id }

    /** Lee el contenido de una nota (en el hilo donde se llame). */
    fun readBody(id: String): Pair<String?, String?> {
        db.rawQuery("SELECT body,extra FROM notes WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        return null to null
    }

    private fun values(m: NoteMeta) = ContentValues().apply {
        put("id", m.id); put("title", m.title); put("title_color", m.titleColor); put("color", m.color)
        put("folder", m.folder); put("fav", if (m.fav) 1 else 0); put("pinned", if (m.pinned) 1 else 0)
        put("deleted", if (m.deleted) 1 else 0); put("deleted_at", m.deletedAt); put("locked", if (m.locked) 1 else 0)
        put("kind", m.kind); put("created", m.created); put("updated", m.updated); put("plain", m.plain)
        put("thumb", m.thumb); put("ck_total", m.checkTotal); put("ck_done", m.checkDone); put("has_media", if (m.hasMedia) 1 else 0); put("pv", m.pv)
    }

    /** Llena los datos de la lista a partir de los bloques. */
    fun fillMeta(m: NoteMeta, blocks: List<Block>, titleRuns: List<Run>? = null, ink: org.json.JSONArray? = null) {
        m.pv = previewJson(titleRuns, blocks, ink); m.pvCache = null; m.inkCache = null
        m.plain = blocksPlain(blocks)
        m.thumb = blocks.firstOrNull { it is Block.Img }?.let { (it as Block.Img).file }
            ?: blocks.firstOrNull { it is Block.Media && it.kind == "video" }?.let { MediaFiles.thumbName((it as Block.Media).file) }
        var t = 0; var d = 0
        blocks.forEach { b -> if (b is Block.ListB && b.kind == "check") b.items.forEach { if (runsPlain(it.runs).isNotBlank()) { t++; if (it.done) d++ } } }
        m.checkTotal = t; m.checkDone = d
        m.hasMedia = blocks.any { it is Block.Media }
        m.folded = fold(m.title + "\n" + m.plain)
    }

    /** Guarda la nota completa (lista + contenido). [extra] = lo que se guarda para después (dibujos, fondo…). */
    fun saveNote(m: NoteMeta, body: String?, extra: String? = null, keepExtra: Boolean = true) {
        if (notes.none { it === m }) {
            val i = notes.indexOfFirst { it.id == m.id }
            if (i >= 0) notes[i] = m else notes.add(m)
        }
        val v = values(m)
        if (body != null) v.put("body", body)
        if (!keepExtra || extra != null) v.put("extra", extra)
        io.execute {
            val n = db.update("notes", v, "id=?", arrayOf(m.id))
            if (n == 0) db.insert("notes", null, v)
        }
    }

    /** Crea una nota nueva ya guardada (reporte del día, resultado de la IA…). */
    fun createNote(title: String, blocks: MutableList<Block>, folder: String? = null): NoteMeta {
        val m = NoteMeta(newId()); m.title = title; m.folder = folder
        val b = normalize(blocks)
        fillMeta(m, b)
        saveNote(m, blocksJson(b), null)
        changed()
        return m
    }

    /** Guarda solo los datos de la lista (favorito, color, carpeta…). */
    fun saveMeta(m: NoteMeta) {
        val v = values(m)
        io.execute { db.update("notes", v, "id=?", arrayOf(m.id)) }
    }

    fun deleteForever(ids: Collection<String>) {
        notes.removeAll { it.id in ids }
        val list = ids.toList()
        io.execute {
            db.beginTransaction()
            try { list.forEach { db.delete("notes", "id=?", arrayOf(it)) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
            cleanMedia()
        }
    }

    /** Borra fotos/audios/videos que ya no usa ninguna nota. */
    fun cleanMedia() {
        val used = HashSet<String>()
        val re2 = Regex("\\\\?\"(?:f|wall)\\\\?\":\\\\?\"([^\"\\\\]+)")
        db.rawQuery("SELECT body,extra FROM notes", null).use { c ->
            val re = Regex("\"(?:f|wall)\":\"([^\"]+)\"")
            while (c.moveToNext()) {
                for (col in 0..1) c.getString(col)?.let { s -> re.findAll(s).forEach { used.add(it.groupValues[1]) } }
            }
        }
        // las fotos de las versiones viejas (🕘 historial) también se guardan
        appCtx?.let { ctx -> try { Versions.allText(ctx).forEach { s -> re2.findAll(s).forEach { used.add(it.groupValues[1]) } } } catch (_: Exception) {} }
        // y las fotos del 📖 registro
        appCtx?.let { ctx -> try { NoteLog.allText(ctx).forEach { s -> Regex("\\\\u0001([^\"\\\\]+)|\u0001([^\"]+)").findAll(s).forEach { m -> used.add(m.groupValues[1].ifEmpty { m.groupValues[2] }) } } } catch (_: Exception) {} }
        mediaDir.listFiles()?.forEach {
            val keep = it.name in used || (it.name.endsWith(".thumb.jpg") && it.name.removeSuffix(".thumb.jpg") in used)
            if (!keep && System.currentTimeMillis() - it.lastModified() > 60_000) it.delete()
        }
    }

    fun saveFolder(f: Folder) {
        if (folders.none { it === f }) folders.add(f)
        val v = ContentValues().apply { put("id", f.id); put("name", f.name); put("color", f.color); put("ord", f.ord) }
        io.execute { if (db.update("folders", v, "id=?", arrayOf(f.id)) == 0) db.insert("folders", null, v) }
    }

    fun deleteFolder(f: Folder) {
        folders.remove(f)
        notes.filter { it.folder == f.id }.forEach { it.folder = null; saveMeta(it) }
        io.execute { db.delete("folders", "id=?", arrayOf(f.id)) }
    }

    /** Varias escrituras juntas (para importar rápido). Corre en el hilo de [io]. */
    fun inTransaction(block: (SQLiteDatabase) -> Unit) {
        db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    fun rowValues(m: NoteMeta, body: String, extra: String?) = values(m).apply { put("body", body); put("extra", extra) }

    /** Fechas de cambio de todas las notas (para no pisar una nota más nueva al importar). */
    fun updatedMap(): Map<String, Long> {
        val out = HashMap<String, Long>()
        db.rawQuery("SELECT id,updated FROM notes", null).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getLong(1) }
        return out
    }

    /** Vuelve a leer todo desde la base de datos (después de importar). */
    fun reload(done: () -> Unit) { gen++; loaded = false; loading = false; load(done) }
    @Volatile private var gen = 0

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "notas.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE notes (
                id TEXT PRIMARY KEY, title TEXT, title_color INTEGER DEFAULT 0, color INTEGER DEFAULT 0,
                folder TEXT, fav INTEGER DEFAULT 0, pinned INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0,
                deleted_at INTEGER DEFAULT 0, locked INTEGER DEFAULT 0, kind TEXT, created INTEGER, updated INTEGER,
                plain TEXT, thumb TEXT, ck_total INTEGER DEFAULT 0, ck_done INTEGER DEFAULT 0, has_media INTEGER DEFAULT 0,
                body TEXT, extra TEXT, pv TEXT)""",
            )
            db.execSQL("CREATE TABLE folders (id TEXT PRIMARY KEY, name TEXT, color INTEGER, ord INTEGER DEFAULT 0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) try { db.execSQL("ALTER TABLE notes ADD COLUMN pv TEXT") } catch (_: Exception) {}
        }
    }
}

/** Quita acentos y pasa a minúsculas: "Cámara" → "camara" (para buscar sin fijarse en acentos). */
fun fold(s: String): String {
    val n = Normalizer.normalize(s, Normalizer.Form.NFD)
    val sb = StringBuilder(n.length)
    for (ch in n) if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch.lowercaseChar())
    return sb.toString()
}

/**
 * Busca [q] en [text] sin fijarse en acentos ni mayúsculas.
 * Devuelve las posiciones (inicio, fin) en el texto ORIGINAL.
 */
fun findAll(text: String, q: String): List<IntRange> {
    val fq = fold(q.trim())
    if (fq.isEmpty()) return emptyList()
    // Se arma el texto sin acentos recordando a qué letra original pertenece cada letra
    val sb = StringBuilder(text.length); val map = IntArray(text.length * 2 + 2); var k = 0
    for ((idx, ch) in text.withIndex()) {
        val n = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
        for (c in n) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            val low = c.lowercaseChar()
            if (k >= map.size - 1) break
            sb.append(low); map[k++] = idx
        }
    }
    val f = sb.toString(); val out = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val p = f.indexOf(fq, from)
        if (p < 0) break
        val a = map[p]; val b = map[p + fq.length - 1] + 1
        out.add(a until b)
        from = p + fq.length
    }
    return out
}
