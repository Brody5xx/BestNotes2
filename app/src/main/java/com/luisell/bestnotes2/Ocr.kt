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
