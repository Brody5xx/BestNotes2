package com.luisell.bestnotes2

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONObject
import java.io.File

/* ============================================================
   Ocr.kt — 🔎 Leer el texto de las fotos (etiquetas, placas,
   seriales…). Todo pasa en el teléfono, sin internet. Lee
   letras normales y también chino.
   También guarda lo que encontró en cada foto para que el
   buscador encuentre la nota aunque la palabra solo esté
   escrita en una foto.
   ============================================================ */
object Ocr {
    private val rec by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val photoText = HashMap<String, String>()     // archivo de la foto → texto
    private val noteText = HashMap<String, String>()      // nota → texto de todas sus fotos
    private var loaded = false
    @Volatile private var running = false
    private var lastRun = 0L

    private fun file(ctx: Context) = File(ctx.filesDir, "texto_fotos.json")

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(file(ctx).takeIf { it.exists() }?.readText() ?: return)
            o.optJSONObject("f")?.let { f -> f.keys().forEach { photoText[it] = f.optString(it) } }
            o.optJSONObject("n")?.let { n -> n.keys().forEach { noteText[it] = n.optString(it) } }
        } catch (_: Exception) {}
    }
    @Synchronized private fun save(ctx: Context) {
        try {
            val o = JSONObject().put("f", JSONObject(photoText as Map<*, *>)).put("n", JSONObject(noteText as Map<*, *>))
            file(ctx).writeText(o.toString())
        } catch (_: Exception) {}
    }

    /** El texto de las fotos de una nota (para el buscador). */
    fun textOf(ctx: Context?, id: String): String { if (ctx != null) load(ctx); return synchronized(this) { noteText[id] ?: "" } }

    /** Lee una foto (espera; llámalo fuera de la pantalla). null = no se pudo. */
    fun readNow(ctx: Context, f: File): String? = try {
        val img = InputImage.fromFilePath(ctx, Uri.fromFile(f))
        Tasks.await(rec.process(img)).text.trim()
    } catch (_: Throwable) { null }

    /** Una palabra que se encontró en la foto, con su cajita (en píxeles del [android.graphics.Bitmap]). */
    class Word(val text: String, val box: android.graphics.RectF, val line: Int) {
        val cjk = text.any { Character.UnicodeScript.of(it.code) in CJK }
    }
    private val CJK = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

    /** La foto derechita (igual que la ves) para poder marcar las palabras encima. */
    private fun upright(f: File, max: Int): android.graphics.Bitmap? {
        val bm = Thumbs.decode(f, max) ?: return null
        val o = try { android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } catch (_: Exception) { 1 }
        val deg = when (o) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg == 0f) return bm
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return try { android.graphics.Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true) } catch (_: Throwable) { bm }
    }

    /** 🔎 Lee la foto palabra por palabra (para escoger cuáles copiar). */
    fun words(ctx: Context, f: File, onImage: (android.graphics.Bitmap) -> Unit, onDone: (android.graphics.Bitmap?, List<Word>) -> Unit) {
        Loader.pool.execute {
            val bm = upright(f, 2200)
            if (bm != null) Store.main.post { onImage(bm) }
            val out = ArrayList<Word>()
            if (bm != null) try {
                val r = Tasks.await(rec.process(InputImage.fromBitmap(bm, 0)))
                var line = 0
                for (b in r.textBlocks) for (l in b.lines) {
                    for (e in l.elements) { val bx = e.boundingBox ?: continue; out.add(Word(e.text, android.graphics.RectF(bx), line)) }
                    line++
                }
                val t = r.text.trim()
                if (t.isNotEmpty()) synchronized(this) { photoText[f.name] = t }
            } catch (_: Throwable) {}
            Store.main.post { onDone(bm, out) }
        }
    }

    /** Lee una foto y te da el texto en la pantalla. */
    fun read(ctx: Context, f: File, onDone: (String?) -> Unit) {
        Loader.pool.execute {
            val t = readNow(ctx, f)
            if (t != null) synchronized(this) { photoText[f.name] = t }
            Store.main.post { onDone(t) }
        }
    }

    /**
     * 🔎 Revisa (en segundo plano, de a poquito) las fotos que todavía no se leyeron,
     * para que el buscador también encuentre lo que está escrito en ellas.
     */
    fun indexLater(ctx: Context, skip: String? = null) {
        val app = ctx.applicationContext
        if (running || System.currentTimeMillis() - lastRun < 60_000) return
        running = true; lastRun = System.currentTimeMillis()
        Thread {
            try {
                load(app)
                var changed = false
                for (m in Store.notes.toList()) {
                    if (m.deleted || m.locked || m.id == skip || !m.hasMediaOrPhotos()) continue
                    val (body, _) = Store.readBody(m.id)
                    val files = blocksFromJson(body).filterIsInstance<Block.Img>().map { it.file }
                    if (files.isEmpty()) { if (synchronized(this) { noteText.remove(m.id) } != null) changed = true; continue }
                    val parts = ArrayList<String>()
                    for (fn in files) {
                        var t = synchronized(this) { photoText[fn] }
                        if (t == null) {
                            val f = File(Store.mediaDir, fn)
                            t = if (f.exists()) readNow(app, f) ?: "" else ""
                            synchronized(this) { photoText[fn] = t }
                            changed = true
                        }
                        if (t.isNotBlank()) parts.add(t)
                    }
                    val all = parts.joinToString("\n")
                    if (synchronized(this) { noteText[m.id] } != all) {
                        synchronized(this) { noteText[m.id] = all }
                        m.folded = fold(m.title + "\n" + m.plain + "\n" + all)
                        changed = true
                    }
                }
                if (changed) save(app)
            } catch (_: Throwable) {}
            running = false
        }.start()
    }

    private fun NoteMeta.hasMediaOrPhotos() = thumb != null || hasMedia
}
