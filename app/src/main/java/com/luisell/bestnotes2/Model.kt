package com.luisell.bestnotes2

import org.json.JSONArray
import org.json.JSONObject

/* ============================================================
   Model.kt — cómo se guarda una nota por dentro.
   Una nota es una lista de "bloques", uno debajo del otro:
     - Texto  (varios renglones con negrita, colores, tamaños…)
     - Lista  (checklist, viñetas o números)
     - Foto   (archivo en la carpeta "media" de la app)
     - Media  (audio o video)
   Cada pedazo de texto ("Run") lleva su estilo ("St").
   ============================================================ */

/** Estilo de un pedazo de texto. color/bg = 0 significa "sin color". */
data class St(
    val b: Boolean = false, val i: Boolean = false, val u: Boolean = false, val s: Boolean = false,
    val size: Int = 0, val color: Int = 0, val bg: Int = 0, val href: String? = null,
    /** Contorno de las letras (color del borde; 0 = sin contorno). */
    val o: Int = 0,
) {
    fun isPlain() = this == PLAIN

    fun toJson(): JSONObject? {
        if (isPlain()) return null
        val o = JSONObject()
        if (b) o.put("b", 1); if (i) o.put("i", 1); if (u) o.put("u", 1); if (s) o.put("s", 1)
        if (size > 0) o.put("z", size)
        if (color != 0) o.put("c", color)
        if (bg != 0) o.put("g", bg)
        if (href != null) o.put("h", href)
        if (this.o != 0) o.put("o", this.o)
        return o
    }

    companion object {
        val PLAIN = St()
        fun fromJson(o: JSONObject?): St {
            if (o == null) return PLAIN
            return St(
                o.optInt("b") == 1, o.optInt("i") == 1, o.optInt("u") == 1, o.optInt("s") == 1,
                o.optInt("z"), o.optInt("c"), o.optInt("g"), if (o.has("h")) o.optString("h") else null, o.optInt("o"),
            )
        }
    }
}

/** Un pedazo de texto con un solo estilo. */
data class Run(val text: String, val st: St = St.PLAIN)

fun runsPlain(runs: List<Run>): String = buildString { runs.forEach { append(it.text) } }

/** Junta pedazos seguidos que tienen el mismo estilo y quita los vacíos. */
fun mergeRuns(runs: List<Run>): MutableList<Run> {
    val out = ArrayList<Run>()
    for (r in runs) {
        if (r.text.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.st == r.st) out[out.size - 1] = Run(last.text + r.text, r.st) else out.add(r)
    }
    return out
}

/** Los pedazos entre [a] y [b] (posiciones en el texto plano). */
fun sliceRuns(runs: List<Run>, a: Int, b: Int): MutableList<Run> {
    val out = ArrayList<Run>(); var i = 0
    for (r in runs) {
        val s = i; val e = i + r.text.length
        val x = maxOf(a, s); val y = minOf(b, e)
        if (x < y) out.add(Run(r.text.substring(x - s, y - s), r.st))
        i = e
    }
    return out
}

fun runsJson(runs: List<Run>): JSONArray {
    val a = JSONArray()
    for (r in runs) {
        val x = JSONArray().put(r.text)
        r.st.toJson()?.let { x.put(it) }
        a.put(x)
    }
    return a
}

fun runsFromJson(a: JSONArray?): MutableList<Run> {
    val out = ArrayList<Run>()
    if (a == null) return out
    for (k in 0 until a.length()) {
        val x = a.optJSONArray(k) ?: continue
        out.add(Run(x.optString(0), St.fromJson(x.optJSONObject(1))))
    }
    return out
}

/** Un renglón de una lista. [done] = marcado; [pos] = dónde estaba antes de marcarlo. */
class Item(var runs: MutableList<Run>, var done: Boolean = false, var pos: Int = -1)

sealed class Block {
    class Text(var runs: MutableList<Run> = ArrayList()) : Block()

    /** kind = "check" (checklist), "ul" (viñetas) u "ol" (números). */
    class ListB(var kind: String, val items: MutableList<Item> = ArrayList()) : Block()

    /** Foto: archivo en /media. pct = ancho en % de la nota; side = "l", "r" o "" (sola);
     *  x = borde izquierdo en %; y = cuánto se bajó (dp). stamp = texto del sello.
     *  beside = el texto que va al lado de la foto (cuando side es "l" o "r").
     *  pin = anclada (no se mueve con el dedo). */
    class Img(
        var file: String, var pct: Float = 40f, var side: String = "", var x: Float = 30f, var y: Float = 0f,
        var w: Int = 0, var h: Int = 0, var stamp: String = "", var rot: Float = 0f, var taken: Long = 0L,
        var beside: MutableList<Run> = ArrayList(), var pin: Boolean = false,
        /** ar = forma estirada (alto/ancho). 0 = la forma original de la foto. */
        var ar: Float = 0f,
        /** 🗺️ Pines del plano (JSON; "" = sin pines). */
        var pins: String = "",
        /** ↔️ La foto como era ANTES de editarla (dibujar, recortar…). "" = nunca se editó. */
        var orig: String = "",
    ) : Block() {
        /** Se llama antes de cambiar la foto por una editada: guarda la limpia (solo la primera vez). */
        fun keepOrig() { if (orig.isEmpty()) orig = file }
    }

    /** Audio o video (kind = "audio" / "video"). */
    class Media(var kind: String, var file: String, var dur: Double = 0.0, var size: Long = 0L, var muted: Boolean = false,
        /** Solo videos: ancho en %, borde izquierdo en %, lado del texto ("l"/"r"/""), texto al lado y anclado. */
        var pct: Float = 100f, var x: Float = 0f, var side: String = "", var beside: MutableList<Run> = ArrayList(), var pin: Boolean = false,
    ) : Block()

    fun toJson(): JSONObject = when (this) {
        is Text -> JSONObject().put("t", "x").put("r", runsJson(runs))
        is ListB -> {
            val a = JSONArray()
            for (it in items) {
                val o = JSONObject().put("r", runsJson(it.runs))
                if (it.done) o.put("d", 1)
                if (it.pos >= 0) o.put("p", it.pos)
                a.put(o)
            }
            JSONObject().put("t", kind).put("it", a)
        }
        is Img -> JSONObject().put("t", "img").put("f", file).put("pct", pct.toDouble()).put("side", side)
            .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w).put("h", h).put("stamp", stamp)
            .put("rot", rot.toDouble()).put("tk", taken).put("bs", runsJson(beside)).put("pin", if (pin) 1 else 0).apply { if (ar > 0f) put("ar", ar.toDouble()); if (pins.isNotEmpty()) put("pn", pins); if (orig.isNotEmpty()) put("of", orig) }
        is Media -> JSONObject().put("t", "media").put("k", kind).put("f", file).put("dur", dur)
            .put("sz", size).put("mu", if (muted) 1 else 0).apply {
                if (pct < 100f) put("pct", pct.toDouble()); if (x != 0f) put("x", x.toDouble()); if (side.isNotEmpty()) put("side", side)
                if (beside.isNotEmpty()) put("bs", runsJson(beside)); if (pin) put("pin", 1)
            }
    }

    /** Copia independiente (para deshacer / rehacer). */
    fun copy(): Block = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject): Block = when (val t = o.optString("t")) {
            "check", "ul", "ol" -> {
                val l = ListB(t)
                val a = o.optJSONArray("it") ?: JSONArray()
                for (k in 0 until a.length()) {
                    val x = a.optJSONObject(k) ?: continue
                    l.items.add(Item(runsFromJson(x.optJSONArray("r")), x.optInt("d") == 1, x.optInt("p", -1)))
                }
                if (l.items.isEmpty()) l.items.add(Item(ArrayList()))
                l
            }
            "img" -> Img(
                o.optString("f"), o.optDouble("pct", 40.0).toFloat(), o.optString("side"),
                o.optDouble("x", 30.0).toFloat(), o.optDouble("y", 0.0).toFloat(), o.optInt("w"), o.optInt("h"),
                o.optString("stamp"), o.optDouble("rot", 0.0).toFloat(), o.optLong("tk"),
                runsFromJson(o.optJSONArray("bs")), o.optInt("pin") == 1, o.optDouble("ar", 0.0).toFloat(), o.optString("pn"), o.optString("of"),
            )
            "media" -> Media(o.optString("k", "audio"), o.optString("f"), o.optDouble("dur", 0.0), o.optLong("sz"), o.optInt("mu") == 1,
                o.optDouble("pct", 100.0).toFloat(), o.optDouble("x", 0.0).toFloat(), o.optString("side"), runsFromJson(o.optJSONArray("bs")), o.optInt("pin") == 1)
            else -> Text(runsFromJson(o.optJSONArray("r")))
        }
    }
}

fun blocksJson(blocks: List<Block>): String {
    val a = JSONArray(); blocks.forEach { a.put(it.toJson()) }; return a.toString()
}

fun blocksFromJson(s: String?): MutableList<Block> {
    val out = ArrayList<Block>()
    if (!s.isNullOrEmpty()) {
        try {
            val a = JSONArray(s)
            for (k in 0 until a.length()) a.optJSONObject(k)?.let { out.add(Block.fromJson(it)) }
        } catch (_: Exception) {}
    }
    return normalize(out)
}

/** Texto plano de toda la nota (para buscar, contar palabras y la vista previa). */
fun blocksPlain(blocks: List<Block>): String = buildString {
    for (b in blocks) {
        when (b) {
            is Block.Text -> { append(runsPlain(b.runs)); append('\n') }
            is Block.ListB -> b.items.forEach { append(runsPlain(it.runs)); append('\n') }
            is Block.Img -> { if (b.beside.isNotEmpty()) { append(runsPlain(b.beside)); append('\n') }; if (b.pins.isNotEmpty()) { append(Pins.plain(b.pins)); append('\n') } }
            is Block.Media -> if (b.beside.isNotEmpty()) { append(runsPlain(b.beside)); append('\n') }
            else -> {}
        }
    }
}.trimEnd()

/**
 * Deja los bloques en orden: junta textos seguidos y listas seguidas del mismo tipo,
 * y se asegura de que al final haya un bloque de texto (para seguir escribiendo).
 */
fun normalize(blocks: MutableList<Block>): MutableList<Block> {
    val out = ArrayList<Block>()
    for (b in blocks) {
        val last = out.lastOrNull()
        if (b is Block.Text && last is Block.Text) {
            // un renglón vacío no deja un salto de línea de más
            last.runs = if (runsPlain(last.runs).isEmpty()) b.runs.toMutableList() else mergeRuns(last.runs + Run("\n") + b.runs)
        } else if (b is Block.ListB && last is Block.ListB && last.kind == b.kind) {
            last.items.addAll(b.items)
        } else if (b is Block.ListB && b.items.isEmpty()) {
            continue
        } else out.add(b)
    }
    if (out.isEmpty() || out.last() !is Block.Text) out.add(Block.Text())
    // si la nota empieza con una foto, arriba queda un renglón para poder escribir encima
    if (out.first() is Block.Img) out.add(0, Block.Text())
    return out
}

/** Datos de una nota para la lista (sin el contenido pesado). */
class NoteMeta(
    val id: String,
    var title: String = "",
    var titleColor: Int = 0,
    var color: Int = 0,
    var folder: String? = null,
    var fav: Boolean = false,
    var pinned: Boolean = false,
    var deleted: Boolean = false,
    var deletedAt: Long = 0L,
    var locked: Boolean = false,
    var kind: String = "text",
    var created: Long = System.currentTimeMillis(),
    var updated: Long = System.currentTimeMillis(),
    var plain: String = "",
    var thumb: String? = null,
    var checkTotal: Int = 0,
    var checkDone: Int = 0,
    var hasMedia: Boolean = false,
    /** Vista previa para la tarjeta: título con estilos + los primeros bloques (JSON). */
    var pv: String? = null,
) {
    /** La vista previa ya leída (para no leer el JSON cada vez). */
    @Volatile var pvCache: Pair<List<Run>?, List<Block>>? = null
    @Volatile var inkCache: List<Stroke>? = null
    /** Texto para buscar: sin acentos y en minúsculas (se calcula una vez). */
    @Volatile var folded: String? = null
}

class Folder(val id: String, var name: String, var color: Int, var ord: Int = 0)

/* Etiquetas en cualquier idioma: # seguido de letras (igual que en Best Notes) */
val TAG_RE = Regex("#([^\\s#.,;:!?¡¿()\\[\\]{}\"'<>/\\\\。、，！？]+)")

fun tagsOf(text: String): Set<String> = TAG_RE.findAll(text).map { it.groupValues[1] }.toSet()

fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) +
    java.lang.Long.toString((Math.random() * 1679616).toLong(), 36)


/** Corta los pedacitos de texto a [max] letras (conservando los estilos). */
fun runsTake(runs: List<Run>, max: Int): MutableList<Run> {
    val out = ArrayList<Run>(); var n = 0
    for (r in runs) { if (n >= max) break; val t = r.text.take(max - n); out.add(Run(t, r.st)); n += t.length }
    return out
}

/** La vista previa de la tarjeta: el título con estilos y los primeros bloques (cortos). */
fun previewJson(titleRuns: List<Run>?, blocks: List<Block>, ink: org.json.JSONArray? = null): String {
    val o = org.json.JSONObject()
    // ✏️ el dibujo (con menos puntos, para que la tarjeta vaya rápido)
    if (ink != null && ink.length() > 0) {
        var total = 0
        for (k in 0 until ink.length()) total += ink.optJSONObject(k)?.optJSONArray("p")?.length() ?: 0
        val step = maxOf(1, total / 1500)
        val small = org.json.JSONArray()
        for (k in 0 until minOf(ink.length(), 400)) {
            val st = ink.optJSONObject(k) ?: continue
            val pa = st.optJSONArray("p") ?: continue
            val np = org.json.JSONArray()
            for (q in 0 until pa.length()) if (q % step == 0 || q == pa.length() - 1) np.put(pa.get(q))
            small.put(org.json.JSONObject(st.toString()).put("p", np))
        }
        o.put("ink", small)
    }
    if (titleRuns != null && titleRuns.any { !it.st.isPlain() }) o.put("t", runsJson(titleRuns))
    val a = org.json.JSONArray(); var chars = 0; var imgs = 0; var media = 0
    for (b in blocks) {
        if (a.length() >= 10 || chars > 900) break
        when (b) {
            is Block.Text -> {
                val txt = runsPlain(b.runs); if (txt.isBlank()) continue
                val r = runsTake(b.runs, 500); chars += minOf(500, txt.length)
                a.put(Block.Text(r).toJson())
            }
            is Block.ListB -> {
                val l = Block.ListB(b.kind, b.items.filter { runsPlain(it.runs).isNotBlank() }.sortedBy { if (b.kind == "check" && it.done) 1 else 0 }
                    .take(8).map { Item(runsTake(it.runs, 120), it.done) }.toMutableList())
                if (l.items.isEmpty()) continue
                chars += l.items.sumOf { runsPlain(it.runs).length }; a.put(l.toJson())
            }
            is Block.Img -> if (imgs++ < 2) a.put(Block.Img(b.file, beside = runsTake(b.beside, 200)).toJson())
            is Block.Media -> if (media++ < 3) a.put(b.toJson())
        }
    }
    o.put("b", a)
    return o.toString()
}

/** El dibujo de la vista previa (o null). */
fun previewInk(pv: String): org.json.JSONArray? = try { org.json.JSONObject(pv).optJSONArray("ink") } catch (_: Exception) { null }

/** Lee la vista previa: (título con estilos o null, bloques). */
fun previewParse(pv: String): Pair<List<Run>?, List<Block>> = try {
    val o = org.json.JSONObject(pv)
    (o.optJSONArray("t")?.let { runsFromJson(it) }) to blocksFromJson(o.optJSONArray("b")?.toString() ?: "[]")
} catch (_: Exception) { null to emptyList() }
