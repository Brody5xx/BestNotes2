package com.luisell.bestnotes2

/* ============================================================
   HtmlImport.kt — convierte una nota de Best Notes (HTML) en
   bloques de Best Notes 2. Nunca falla: si encuentra algo que
   no conoce, se queda con su texto para que no se pierda nada.
   Las fotos, audios y videos que vienen dentro ("data:…base64")
   se guardan como archivos con [saveData].
   ============================================================ */
object HtmlImport {

    class Result(val blocks: MutableList<Block>, val missing: Int)

    private class Node(val tag: String, val attrs: Map<String, String>) {
        val children = ArrayList<Any>()          // Node o String (texto)
    }

    private val VOID = setOf("br", "img", "hr", "input", "meta", "wbr", "source", "col", "area", "base", "link", "embed", "track", "param")
    private val SKIP = setOf("script", "style", "button", "head", "title", "template", "noscript", "canvas", "select", "input")
    private val BLOCKS = setOf(
        "div", "p", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "article",
        "header", "footer", "table", "tr", "pre", "hr", "figure", "nav", "aside", "main", "dl", "dt", "dd",
        "img", "audio", "video", "tbody", "thead",
    )

    fun decodeEnt(s: String): String {
        if (s.indexOf('&') < 0) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            if (e.startsWith("#")) {
                val n = if (e.length > 1 && (e[1] == 'x' || e[1] == 'X')) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                if (n != null) try { String(Character.toChars(n)) } catch (_: Exception) { m.value } else m.value
            } else when (e) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> m.value
            }
        }
    }

    private val ATTR = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?")
    private fun attrsOf(s: String): Map<String, String> {
        val a = HashMap<String, String>()
        for (m in ATTR.findAll(s)) {
            val v = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            a[m.groupValues[1].lowercase()] = decodeEnt(v)
        }
        return a
    }

    private val TOKEN = Regex("<!--[\\s\\S]*?-->|<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>|[^<]+|<")

    private fun parseTree(html: String): Node {
        val root = Node("#root", emptyMap())
        val stack = ArrayList<Node>(); stack.add(root)
        for (m in TOKEN.findAll(html)) {
            val tok = m.value
            if (tok.startsWith("<!--")) continue
            val tagName = m.groups[2]?.value
            if (tagName != null) {
                val tag = tagName.lowercase()
                if (m.groupValues[1] == "/") {
                    var i = stack.size - 1
                    while (i > 0 && stack[i].tag != tag) i--
                    if (i > 0) while (stack.size > i) stack.removeAt(stack.size - 1)
                    continue
                }
                val rawAttrs = m.groupValues[3]
                val node = Node(tag, attrsOf(rawAttrs))
                stack.last().children.add(node)
                if (tag !in VOID && !rawAttrs.trimEnd().endsWith("/")) stack.add(node)
                continue
            }
            stack.last().children.add(decodeEnt(tok))
        }
        return root
    }

    /* ---------- colores CSS → número ---------- */
    fun cssColor(v: String?): Int {
        if (v.isNullOrBlank()) return 0
        val s = v.trim().lowercase()
        try {
            if (s.startsWith("#")) {
                val h = s.substring(1)
                return when (h.length) {
                    3 -> (0xFF shl 24) or (h.map { "$it$it".toInt(16) }.let { (it[0] shl 16) or (it[1] shl 8) or it[2] })
                    6 -> (0xFF000000.toInt()) or h.toInt(16)
                    8 -> { val rgb = h.substring(0, 6).toInt(16); val a = h.substring(6).toInt(16); (a shl 24) or rgb }
                    else -> 0
                }
            }
            val m = Regex("rgba?\\(([^)]*)\\)").find(s)
            if (m != null) {
                val p = m.groupValues[1].split(',', ' ', '/').filter { it.isNotBlank() }
                val r = p[0].trim().toFloat().toInt(); val g = p[1].trim().toFloat().toInt(); val b = p[2].trim().toFloat().toInt()
                val a = if (p.size > 3) (p[3].trim().removeSuffix("%").toFloat().let { if (p[3].contains('%')) it / 100f else it } * 255).toInt() else 255
                if (a == 0) return 0
                return (a.coerceIn(1, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
            return when (s) {
                "red" -> 0xFFFF0000.toInt(); "blue" -> 0xFF0000FF.toInt(); "green" -> 0xFF008000.toInt()
                "yellow" -> 0xFFFFFF00.toInt(); "orange" -> 0xFFFFA500.toInt(); "purple" -> 0xFF800080.toInt()
                "white" -> 0xFFFFFFFF.toInt(); "black" -> 0xFF000000.toInt(); "gray", "grey" -> 0xFF808080.toInt()
                else -> 0
            }
        } catch (_: Exception) { return 0 }
    }

    private fun cssOf(str: String?): Map<String, String> {
        val o = HashMap<String, String>()
        (str ?: "").split(';').forEach { p -> val i = p.indexOf(':'); if (i > 0) o[p.substring(0, i).trim().lowercase()] = p.substring(i + 1).trim() }
        return o
    }

    /** Estilo que agrega una etiqueta (nunca falla: lo que no conoce lo ignora). */
    private fun tagStyle(n: Node, st: St): St {
        var s = st
        when (n.tag) {
            "b", "strong" -> s = s.copy(b = true)
            "i", "em" -> s = s.copy(i = true)
            "u", "ins" -> s = s.copy(u = true)
            "s", "strike", "del" -> s = s.copy(s = true)
            "font" -> n.attrs["color"]?.let { c -> cssColor(c).let { if (it != 0) s = s.copy(color = it) } }
            "a" -> n.attrs["href"]?.let { h -> if (h.isNotBlank() && !h.trim().lowercase().startsWith("javascript:")) s = s.copy(href = h) }
            "mark" -> s = s.copy(bg = 0x73FFD60A)
            "h1" -> s = s.copy(b = true, size = 30); "h2" -> s = s.copy(b = true, size = 24); "h3" -> s = s.copy(b = true, size = 20)
            "h4", "h5", "h6", "th" -> s = s.copy(b = true)
        }
        val css = cssOf(n.attrs["style"])
        for ((k, v0) in css) {
            val v = v0.lowercase()
            when (k) {
                "font-size" -> Regex("^([0-9.]+)px").find(v)?.let { s = s.copy(size = it.groupValues[1].toFloat().toInt().coerceIn(5, 100)) }
                "color" -> cssColor(v0).let { if (it != 0) s = s.copy(color = it) }
                "background-color", "background" -> { val c = cssColor(v0); if (c != 0) s = s.copy(bg = c) }
                "font-weight" -> s = if (v == "bold" || (v.toIntOrNull() ?: 400) >= 600) s.copy(b = true) else if (v == "normal" || (v.toIntOrNull() ?: 700) <= 500) s.copy(b = false) else s
                "font-style" -> s = if (v == "italic") s.copy(i = true) else if (v == "normal") s.copy(i = false) else s
                "text-decoration", "text-decoration-line" -> { if ("underline" in v) s = s.copy(u = true); if ("line-through" in v) s = s.copy(s = true) }
            }
        }
        return s
    }

    /* ---------- armar los bloques ---------- */
    private class Emitter {
        val blocks = ArrayList<Block>()
        val lines = ArrayList<MutableList<Run>>()
        var cur = ArrayList<Run>()
        fun text(t: String, st: St) {
            val parts = t.replace("\r", "").replace("\u200B", "").split('\n')
            parts.forEachIndexed { k, p -> if (k > 0) br(); if (p.isNotEmpty()) cur.add(Run(p, st)) }
        }
        fun br() { lines.add(cur); cur = ArrayList() }
        fun edge() { if (cur.isNotEmpty()) br() }
        fun flushText() {
            edge()
            if (lines.isNotEmpty()) {
                val runs = ArrayList<Run>()
                lines.forEachIndexed { k, l -> if (k > 0) runs.add(Run("\n")); runs.addAll(l) }
                blocks.add(Block.Text(mergeRuns(runs)))
                lines.clear()
            }
        }
        fun add(b: Block) { flushText(); blocks.add(b) }
    }

    private var missing = 0

    private fun findTag(n: Node, tags: Set<String>): Node? {
        for (c in n.children) if (c is Node) { if (c.tag in tags) return c; findTag(c, tags)?.let { return it } }
        return null
    }

    private fun textOf(n: Node): String = buildString {
        for (c in n.children) if (c is String) append(c) else if (c is Node) append(textOf(c))
    }

    private fun imgBlock(wrap: Node?, img: Node): Block? {
        val file = img.attrs["data-file"]
        if (file.isNullOrEmpty()) { missing++; return null }
        val css = cssOf(wrap?.attrs?.get("style"))
        val pct = Regex("([0-9.]+)%").find(css["width"] ?: "")?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(10f, 100f) ?: if (wrap == null) 100f else 40f
        val fl = (css["float"] ?: "").trim()
        val side = if (fl == "left") "l" else if (fl == "right") "r" else ""
        fun num(v: String?) = v?.let { Regex("(-?[0-9.]+)%").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
        val ml = num(css["margin-left"]); val mr = num(css["margin-right"]); val dx = wrap?.attrs?.get("data-x")?.toFloatOrNull()
        var x = when {
            side == "r" && mr != null -> 100 - pct - mr
            side != "r" && ml != null -> ml
            dx != null -> dx
            side == "l" -> 0f
            side == "r" -> 100 - pct
            else -> (100 - pct) / 2
        }
        x = x.coerceIn(0f, 100 - minOf(pct, 100f))
        val y = (wrap?.attrs?.get("data-y")?.toFloatOrNull() ?: 0f).coerceAtLeast(0f)
        val stamp = wrap?.let { w -> findTag(w, setOf("svg"))?.let { sv -> findTag(sv, setOf("text"))?.let { textOf(it) } } } ?: ""
        return Block.Img(
            file, pct, side, x, y, img.attrs["width"]?.toIntOrNull() ?: 0, img.attrs["height"]?.toIntOrNull() ?: 0,
            stamp, wrap?.attrs?.get("data-rot")?.toFloatOrNull() ?: 0f, wrap?.attrs?.get("data-taken")?.toLongOrNull() ?: 0L,
        )
    }

    private fun mediaBlock(wrap: Node?, el: Node): Block? {
        val file = el.attrs["data-file"]
        if (file.isNullOrEmpty()) { missing++; return null }
        return Block.Media(
            if (el.tag == "video") "video" else "audio", file,
            wrap?.attrs?.get("data-dur")?.toDoubleOrNull() ?: 0.0, wrap?.attrs?.get("data-size")?.toLongOrNull() ?: 0L,
            wrap?.attrs?.get("data-muted") == "1",
        )
    }

    private fun listBlock(n: Node, kind: String, out: Emitter) {
        val l = Block.ListB(kind)
        var any = false
        for (li in n.children) {
            if (li !is Node) continue
            if (li.tag != "li") { walk(li, St.PLAIN, out); continue }
            val e = Emitter()
            for (c in li.children) walkInline(c, St.PLAIN, e, insideLi = true)
            e.edge()
            val runs = ArrayList<Run>()
            e.lines.forEachIndexed { k, line -> if (k > 0 && line.isNotEmpty()) runs.add(Run(" ")); runs.addAll(line) }
            val done = kind == "check" && Regex("\\bdone\\b").containsMatchIn(li.attrs["class"] ?: "")
            l.items.add(Item(mergeRuns(runs), done)); any = true
            // fotos o audios que estaban dentro del renglón: van después de la lista
            e.blocks.forEach { b -> if (b !is Block.Text) { out.add(l.copyIfNeeded()); out.add(b) } }
        }
        if (!any) l.items.add(Item(ArrayList()))
        if (l.items.isNotEmpty()) out.add(l)
    }

    private fun Block.ListB.copyIfNeeded(): Block = Block.ListB(kind, ArrayList(items)).also { items.clear() }

    /** Dentro de un renglón de lista: todo es texto (las fotos se guardan aparte). */
    private fun walkInline(c: Any, st: St, e: Emitter, insideLi: Boolean) {
        if (c is String) { e.text(if (insideLi) c.replace('\n', ' ') else c, st); return }
        val n = c as Node
        if (n.tag in SKIP) return
        when (n.tag) {
            "br" -> { e.br(); return }
            "img" -> { imgBlock(null, n)?.let { e.blocks.add(it) }; return }
            "audio", "video" -> { mediaBlock(null, n)?.let { e.blocks.add(it) }; return }
            "ul", "ol", "div", "p" -> { e.edge(); n.children.forEach { walkInline(it, st, e, insideLi) }; e.edge(); return }
        }
        val cls = n.attrs["class"] ?: ""
        if (n.tag == "div" && Regex("\\bimgwrap\\b").containsMatchIn(cls)) return
        val s2 = tagStyle(n, st)
        n.children.forEach { walkInline(it, s2, e, insideLi) }
    }

    private fun walk(c: Any, st: St, e: Emitter) {
        if (c is String) {
            if (c.isBlank() && c.contains('\n')) return            // espacios entre etiquetas
            e.text(c, st); return
        }
        val n = c as Node
        if (n.tag in SKIP) return
        val cls = n.attrs["class"] ?: ""
        when {
            n.tag == "br" -> e.br()
            n.tag == "hr" -> { e.edge(); e.text("———————", st); e.br() }
            n.tag == "div" && Regex("\\bimgwrap\\b").containsMatchIn(cls) -> {
                val img = findTag(n, setOf("img"))
                if (img != null) imgBlock(n, img)?.let { e.add(it) }
            }
            n.tag == "div" && Regex("\\b(audwrap|vidwrap)\\b").containsMatchIn(cls) -> {
                val el = findTag(n, setOf("audio", "video"))
                if (el != null) mediaBlock(n, el)?.let { e.add(it) }
            }
            n.tag == "img" -> imgBlock(null, n)?.let { e.add(it) }
            n.tag == "audio" || n.tag == "video" -> mediaBlock(null, n)?.let { e.add(it) }
            n.tag == "ul" && Regex("\\bchecklist\\b").containsMatchIn(cls) -> listBlock(n, "check", e)
            n.tag == "ul" -> listBlock(n, "ul", e)
            n.tag == "ol" -> listBlock(n, "ol", e)
            n.tag == "td" || n.tag == "th" -> { val s2 = tagStyle(n, st); n.children.forEach { walk(it, s2, e) }; e.text("  ", st) }
            n.tag in BLOCKS -> {
                val s2 = tagStyle(n, st)
                e.edge()
                n.children.forEach { walk(it, s2, e) }
                e.edge()
            }
            else -> { val s2 = tagStyle(n, st); n.children.forEach { walk(it, s2, e) } }
        }
    }

    /**
     * Saca las fotos/audios/videos "data:…base64,…" del HTML: los guarda con [saveData]
     * (que devuelve el nombre del archivo) y en su lugar deja data-file="nombre".
     */
    fun extractData(html: String, saveData: (String) -> String?): String {
        if (!html.contains("src=\"data:")) return html
        val sb = StringBuilder(minOf(html.length, 1 shl 20))
        var i = 0
        while (true) {
            val p = html.indexOf("src=\"data:", i)
            if (p < 0) { sb.append(html, i, html.length); break }
            val q = html.indexOf('"', p + 5)
            if (q < 0) { sb.append(html, i, html.length); break }
            sb.append(html, i, p)
            val uri = html.substring(p + 5, q)
            val name = try { saveData(uri) } catch (_: Throwable) { null }
            if (name != null) sb.append("data-file=\"").append(name).append('"') else sb.append("data-lost=\"1\"")
            i = q + 1
        }
        return sb.toString()
    }

    /** HTML de Best Notes → bloques. */
    fun convert(html: String?, isHtml: Boolean, saveData: (String) -> String?): Result {
        missing = 0
        val e = Emitter()
        if (!isHtml) {
            e.text(html ?: "", St.PLAIN)
        } else {
            val clean = extractData(html ?: "", saveData)
            val root = parseTree(clean)
            root.children.forEach { walk(it, St.PLAIN, e) }
        }
        e.flushText()
        // Si un renglón tenía el color de toda la línea con <div style>, ya quedó en su estilo.
        return Result(normalize(e.blocks), missing)
    }

    /** El color del título (Best Notes lo guardaba en titleHtml o en titleColor). */
    fun titleColor(titleHtml: String?, titleColor: String?): Int {
        val m = titleHtml?.let { Regex("color:\\s*([^;\"]+)").find(it) }
        return cssColor(m?.groupValues?.get(1) ?: titleColor)
    }
}
