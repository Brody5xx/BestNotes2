package com.luisell.bestnotes2

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/* ============================================================
   Backup.kt — abre el archivo "Exportar todo para Best Notes 2"
   de la app vieja y mete todas las notas aquí.

   El archivo viene protegido igual que en Best Notes:
     contraseña → PBKDF2-SHA256 (40,000 vueltas) → 2 llaves
     ChaCha20 para cifrar + sello HMAC-SHA256.
   Se lee por pedazos (no todo de una vez) para que archivos con
   muchos videos no llenen la memoria.

   Se puede importar las veces que quieras: si una nota ya está,
   se queda la versión más nueva.
   ============================================================ */
object Backup {
    class WrongPassword : Exception()
    class BadFile : Exception()

    class Summary(var total: Int = 0, var written: Int = 0, var missing: Int = 0, var vault: String = "")

    /* ---------- cifrado ---------- */
    private fun hmac(key: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    /** PBKDF2-SHA256 (igual que respaldo.js). */
    fun pbkdf2(pass: ByteArray, salt: ByteArray, iter: Int, blocks: Int): ByteArray {
        val mac = if (pass.isEmpty()) hmacEmpty() else hmac(pass)
        val out = ByteArray(32 * blocks)
        for (b in 1..blocks) {
            mac.reset()
            mac.update(salt); mac.update(byteArrayOf(0, 0, 0, b.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iter) {
                u = mac.doFinal(u)
                for (j in 0 until 32) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (b - 1) * 32, 32)
        }
        return out
    }

    /** HMAC con llave vacía (Java no deja una llave de 0 bytes: se usa una de 64 ceros, que es lo mismo). */
    private fun hmacEmpty(): Mac = hmac(ByteArray(64))

    /** ChaCha20 (RFC 7539) que se puede usar por pedazos. */
    class ChaCha(key: ByteArray, nonce: ByteArray) {
        private val st = IntArray(16)
        private val x = IntArray(16)
        private val ks = ByteArray(64)
        private var used = 64
        init {
            st[0] = 0x61707865; st[1] = 0x3320646e; st[2] = 0x79622d32; st[3] = 0x6b206574
            for (i in 0 until 8) st[4 + i] = le(key, i * 4)
            st[12] = 0
            for (i in 0 until 3) st[13 + i] = le(nonce, i * 4)
        }
        private fun le(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8) or
            ((b[i + 2].toInt() and 255) shl 16) or ((b[i + 3].toInt() and 255) shl 24)
        private fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }
        private fun next() {
            System.arraycopy(st, 0, x, 0, 16)
            repeat(10) {
                qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
                qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = x[i] + st[i]
                ks[i * 4] = v.toByte(); ks[i * 4 + 1] = (v ushr 8).toByte(); ks[i * 4 + 2] = (v ushr 16).toByte(); ks[i * 4 + 3] = (v ushr 24).toByte()
            }
            st[12]++
            used = 0
        }
        fun xor(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) {
                if (used == 64) next()
                b[i] = (b[i].toInt() xor ks[used++].toInt()).toByte()
            }
        }
    }

    /* ---------- leer el archivo ---------- */
    private const val DATA_KEY = "\"data\":\""

    /** Lee la parte de arriba del archivo (hasta "data":") y devuelve el JSON de esos campos. */
    private fun readHeader(inp: InputStream): JSONObject {
        val head = ByteArrayOutputStream()
        val key = DATA_KEY.toByteArray()
        var match = 0
        while (true) {
            val c = inp.read()
            if (c < 0 || head.size() > 1_000_000) throw BadFile()
            head.write(c)
            match = if (c.toByte() == key[match]) match + 1 else if (c.toByte() == key[0]) 1 else 0
            if (match == key.size) break
        }
        val s = head.toString("UTF-8")
        val txt = s.substring(0, s.length - DATA_KEY.length).trimEnd().removeSuffix(",") + "}"
        return try { JSONObject(txt) } catch (_: Exception) { throw BadFile() }
    }

    /**
     * Importa el archivo. [onProgress] recibe cuántas notas van.
     * Lanza [WrongPassword] o [BadFile].
     */
    fun importFile(ctx: Context, uri: Uri, password: String, onProgress: (Int) -> Unit): Summary {
        val cr = ctx.contentResolver
        var head: JSONObject
        (cr.openInputStream(uri) ?: throw BadFile()).use { raw ->
            head = readHeader(BufferedInputStream(raw, 1 shl 16))
        }
        if (head.optString("app") != "mis-notas") throw BadFile()
        if (!head.has("salt")) {
            // Por si "data" no viniera al final: los demás campos están después
            (cr.openInputStream(uri) ?: throw BadFile()).use { raw ->
                val b = BufferedInputStream(raw, 1 shl 16); readHeader(b)
                while (true) { val c = b.read(); if (c < 0) throw BadFile(); if (c == '"'.code) break }
                val rest = b.readBytes().toString(Charsets.UTF_8)
                val more = JSONObject("{" + rest.trimStart().removePrefix(","))
                more.keys().forEach { head.put(it, more.get(it)) }
            }
        }
        val salt = Base64.decode(head.optString("salt"), Base64.DEFAULT)
        val nonce = Base64.decode(head.optString("nonce"), Base64.DEFAULT)
        val tag = Base64.decode(head.optString("tag"), Base64.DEFAULT)
        val keys = pbkdf2(password.toByteArray(Charsets.UTF_8), salt, head.optInt("iter", 40000), 2)
        val mac = hmac(keys.copyOfRange(32, 64))
        mac.update("mis-notas-v1".toByteArray()); mac.update(salt); mac.update(nonce)
        val cha = ChaCha(keys.copyOfRange(0, 32), nonce)

        // Se descifra a un archivo temporal mientras se calcula el sello
        val tmp = File(ctx.cacheDir, "importar.json")
        (cr.openInputStream(uri) ?: throw BadFile()).use { raw ->
            val inp = BufferedInputStream(raw, 1 shl 16)
            readHeader(inp)
            FileOutputStream(tmp).use { out ->
                val chunk = ByteArray(1 shl 16); var n = 0
                fun flush(all: Boolean) {
                    val take = if (all) n else n - n % 4
                    if (take <= 0) return
                    val bytes = Base64.decode(chunk, 0, take, Base64.DEFAULT)
                    mac.update(bytes)
                    cha.xor(bytes, 0, bytes.size)
                    out.write(bytes)
                    System.arraycopy(chunk, take, chunk, 0, n - take); n -= take
                }
                while (true) {
                    val c = inp.read()
                    if (c < 0 || c == '"'.code) break
                    if (c == '\\'.code) continue
                    if (c <= ' '.code) continue
                    chunk[n++] = c.toByte()
                    if (n == chunk.size) flush(false)
                }
                flush(true)
            }
        }
        if (!MessageDigest.isEqual(mac.doFinal(), tag)) { tmp.delete(); throw WrongPassword() }
        try {
            return readNotes(ctx, tmp, onProgress)
        } finally { tmp.delete() }
    }

    /* ---------- de JSON a notas ---------- */
    private fun readAny(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> { val o = JSONObject(); r.beginObject(); while (r.hasNext()) o.put(r.nextName(), readAny(r) ?: JSONObject.NULL); r.endObject(); o }
        JsonToken.BEGIN_ARRAY -> { val a = JSONArray(); r.beginArray(); while (r.hasNext()) a.put(readAny(r) ?: JSONObject.NULL); r.endArray(); a }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextDouble()
        JsonToken.BOOLEAN -> r.nextBoolean()
        else -> { r.nextNull(); null }
    }

    /** Guarda una foto/audio/video "data:…;base64,…" como archivo. Mismo contenido = mismo archivo. */
    fun saveDataUri(uri: String): String? {
        val comma = uri.indexOf(',')
        if (!uri.startsWith("data:") || comma < 0) return null
        val mime = uri.substring(5, comma).substringBefore(';').lowercase()
        val ext = when {
            mime.contains("png") -> "png"; mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("webp") -> "webp"; mime.contains("gif") -> "gif"; mime.contains("svg") -> "svg"
            mime.startsWith("video/webm") -> "webm"; mime.startsWith("video") -> "mp4"
            mime.contains("webm") -> "weba"; mime.contains("ogg") -> "ogg"; mime.contains("mpeg") -> "mp3"
            mime.contains("wav") -> "wav"; mime.startsWith("audio") -> "m4a"
            else -> "bin"
        }
        val md = MessageDigest.getInstance("SHA-1")
        md.update(uri.toByteArray(Charsets.ISO_8859_1))
        val name = md.digest().joinToString("") { "%02x".format(it) }.substring(0, 24) + "." + ext
        val f = File(Store.mediaDir, name)
        if (!f.exists() || f.length() == 0L) {
            val bytes = if (uri.substring(5, comma).contains(";base64")) Base64.decode(uri.substring(comma + 1), Base64.DEFAULT)
            else java.net.URLDecoder.decode(uri.substring(comma + 1), "UTF-8").toByteArray()
            val t = File(Store.mediaDir, "$name.tmp")
            FileOutputStream(t).use { it.write(bytes) }
            t.renameTo(f)
        }
        return name
    }

    private fun readNotes(ctx: Context, file: File, onProgress: (Int) -> Unit): Summary {
        val sum = Summary()
        val existing = Store.updatedMap()
        val prefs = ctx.getSharedPreferences("ajustes", 0)
        JsonReader(InputStreamReader(file.inputStream().buffered(1 shl 16), Charsets.UTF_8)).use { r ->
            r.beginObject()
            val walls = HashMap<String, String>()
            val pendingWalls = ArrayList<Pair<String, String>>()     // nota → archivo del fondo
            while (r.hasNext()) {
                when (r.nextName()) {
                    "notes" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            val o = try { readAny(r) as? JSONObject } catch (e: OutOfMemoryError) { null }
                            if (o == null) continue
                            sum.total++
                            try { if (importNote(o, existing, sum)) sum.written++ } catch (_: OutOfMemoryError) { sum.missing++ } catch (_: Exception) {}
                            if (sum.total % 5 == 0) onProgress(sum.total)
                        }
                        r.endArray()
                    }
                    "folders" -> {
                        val a = readAny(r) as? JSONArray ?: JSONArray()
                        for (k in 0 until a.length()) {
                            val f = a.optJSONObject(k) ?: continue
                            val id = f.optString("id"); if (id.isEmpty()) continue
                            Store.inTransaction { db ->
                                val v = android.content.ContentValues().apply {
                                    put("id", id); put("name", f.optString("name")); put("color", (f.opt("color") as? Number)?.toInt() ?: HtmlImport.cssColor(f.optString("color"))); put("ord", k)
                                }
                                db.insertWithOnConflict("folders", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                            }
                        }
                    }
                    "walls" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val id = r.nextName()
                            if (r.peek() == JsonToken.STRING) saveDataUri(r.nextString())?.let { walls[id] = it } else r.skipValue()
                        }
                        r.endObject()
                    }
                    "media" -> {
                        // 📦 respaldo de Best Notes 2: las fotos, audios y videos van aparte
                        r.beginObject()
                        while (r.hasNext()) {
                            val name = r.nextName()
                            val f = File(Store.mediaDir, name)
                            if (!Regex("^[\\w.\\-]+$").matches(name) || (f.exists() && f.length() > 0)) { r.skipValue(); continue }
                            val b64 = try { r.nextString() } catch (_: OutOfMemoryError) { sum.missing++; continue }
                            try { val t = File(Store.mediaDir, "$name.tmp"); t.writeBytes(Base64.decode(b64, Base64.DEFAULT)); t.renameTo(f) } catch (_: Throwable) { sum.missing++ }
                        }
                        r.endObject()
                    }
                    "files" -> {
                        // 🕘 versiones y 📖 registros de cada nota
                        r.beginObject()
                        while (r.hasNext()) {
                            val name = r.nextName(); val txt = r.nextString()
                            if (!Regex("^(historial|registro)/[\\w.\\-]+\\.json$").matches(name)) continue
                            val f = File(ctx.filesDir, name); if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(txt) }
                        }
                        r.endObject()
                    }
                    "vault" -> {
                        // 🛡️ la bóveda viaja tal cual (sigue cifrada con su contraseña)
                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            val files = HashMap<String, String>()
                            r.beginObject()
                            while (r.hasNext()) { val n = r.nextName(); if (r.peek() == JsonToken.STRING) files[n] = r.nextString() else r.skipValue() }
                            r.endObject()
                            sum.vault = try { VaultBox.importRaw(ctx, files) } catch (_: Exception) { "" }
                        } else r.skipValue()
                    }
                    "calc" -> {
                        // 🧮 lo que la calculadora recordaba (historial, Y=, divisas, horas)
                        val c = readAny(r) as? JSONObject
                        if (c != null && prefs.getString("calcState", null).isNullOrEmpty()) prefs.edit().putString("calcState", c.toString()).apply()
                    }
                    "settings" -> {
                        val s = readAny(r) as? JSONObject
                        val ph = s?.optString("pinHash")
                        if (!ph.isNullOrEmpty() && ph != "null" && prefs.getString("pinHash", null).isNullOrEmpty()) prefs.edit().putString("pinHash", ph).apply()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            // Fondos de las notas: se guardan para la parte 2/4
            if (walls.isNotEmpty()) Store.inTransaction { db ->
                for ((id, f) in walls) {
                    db.rawQuery("SELECT extra FROM notes WHERE id=?", arrayOf(id)).use { c ->
                        if (c.moveToFirst()) {
                            val ex = try { JSONObject(c.getString(0) ?: "{}") } catch (_: Exception) { JSONObject() }
                            ex.put("wall", f)
                            db.execSQL("UPDATE notes SET extra=? WHERE id=?", arrayOf(ex.toString(), id))
                        }
                    }
                }
            }
            pendingWalls.clear()
        }
        onProgress(sum.total)
        return sum
    }

    /** Una nota de un respaldo de Best Notes 2 (tal cual, sin convertir). */
    private fun importBn2(id: String, b: JSONObject): Boolean {
        val m = NoteMeta(id)
        m.title = b.optString("title"); m.titleColor = b.optInt("titleColor"); m.color = b.optInt("color")
        m.folder = b.optString("folder").ifEmpty { null }; m.fav = b.optBoolean("fav"); m.pinned = b.optBoolean("pinned")
        m.deleted = b.optBoolean("deleted"); m.deletedAt = b.optLong("deletedAt"); m.locked = b.optBoolean("locked")
        m.kind = b.optString("kind", "text"); m.created = b.optLong("created"); m.updated = b.optLong("updated")
        val body = b.optString("body", "[]"); val extra = b.optString("extra").ifEmpty { null }
        val exo = try { JSONObject(extra ?: "{}") } catch (_: Exception) { JSONObject() }
        Store.fillMeta(m, blocksFromJson(body), exo.optJSONArray("tr")?.let { runsFromJson(it) }, exo.optJSONArray("ink"))
        Store.inTransaction { db ->
            db.insertWithOnConflict("notes", null, Store.rowValues(m, body, extra), android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
        return true
    }

    /* =========================================================
       💾 CREAR UN RESPALDO de Best Notes 2 (mismo cifrado que Best Notes)
       ========================================================= */
    fun exportFile(ctx: Context, password: String, out: File, onProgress: (String) -> Unit) {
        val plain = File(ctx.cacheDir, "respaldo_plano.tmp")
        val ctFile = File(ctx.cacheDir, "respaldo_ct.tmp")
        try {
            // 1) todo en JSON (por pedazos)
            java.io.BufferedWriter(java.io.OutputStreamWriter(FileOutputStream(plain), Charsets.UTF_8), 1 shl 16).use { w ->
                fun str(s: String) = JSONObject.quote(s)
                w.write("{\"v\":1,\"bn2\":1,\"made\":${System.currentTimeMillis()},")
                // notas
                w.write("\"notes\":[")
                val used = HashSet<String>()
                val re = Regex("\"(?:f|wall)\":\"([^\"]+)\"")
                var first = true; var n = 0
                val ids = Store.notes.map { it.id }
                for (id in ids) {
                    val m = Store.meta(id) ?: continue
                    val (body, extra) = Store.readBody(id)
                    listOf(body, extra).forEach { t -> t?.let { re.findAll(it).forEach { x -> used.add(x.groupValues[1]) } } }
                    val b = JSONObject().put("title", m.title).put("titleColor", m.titleColor).put("color", m.color).put("folder", m.folder ?: "")
                        .put("fav", m.fav).put("pinned", m.pinned).put("deleted", m.deleted).put("deletedAt", m.deletedAt).put("locked", m.locked)
                        .put("kind", m.kind).put("created", m.created).put("updated", m.updated).put("body", body ?: "[]").put("extra", extra ?: "")
                    if (!first) w.write(",")
                    first = false
                    w.write(JSONObject().put("id", id).put("updated", m.updated).put("title", m.title).put("bn2", b).toString())
                    if (++n % 10 == 0) onProgress(L.t("bkNotes", "n" to n))
                }
                w.write("],")
                // carpetas
                val fa = JSONArray()
                for (f in Store.folders) fa.put(JSONObject().put("id", f.id).put("name", f.name).put("color", f.color))
                w.write("\"folders\":" + fa + ",")
                // versiones y registros (y las fotos que usan)
                w.write("\"files\":{")
                var ff = true
                for (dir in listOf("historial", "registro")) for (f in File(ctx.filesDir, dir).listFiles() ?: emptyArray()) {
                    if (!f.name.endsWith(".json")) continue
                    val txt = f.readText()
                    Regex("\\\\?\"(?:f|wall)\\\\?\":\\\\?\"([^\"\\\\]+)").findAll(txt).forEach { used.add(it.groupValues[1]) }
                    Regex("\\\\u0001([^\"\\\\]+)").findAll(txt).forEach { used.add(it.groupValues[1]) }
                    if (!ff) w.write(","); ff = false
                    w.write(str("$dir/${f.name}") + ":" + str(txt))
                }
                w.write("},")
                // ajustes, calculadora y bóveda
                val p = ctx.getSharedPreferences("ajustes", 0)
                val st = JSONObject()
                p.getString("pinHash", null)?.let { st.put("pinHash", it) }
                w.write("\"settings\":$st,")
                p.getString("calcState", null)?.let { w.write("\"calc\":$it,") }
                val vd = File(ctx.filesDir, "boveda")
                if (File(vd, "meta.json").exists()) {
                    w.write("\"vault\":{")
                    var fv = true
                    for (f in vd.listFiles() ?: emptyArray()) {
                        if (!f.isFile || f.name.endsWith(".tmp")) continue
                        if (!fv) w.write(","); fv = false
                        if (f.name.endsWith(".bin")) {                     // base64 (sin comillas): va directo
                            w.write(str(f.name) + ":\""); f.reader().use { r -> r.copyTo(w) }; w.write("\"")
                        } else w.write(str(f.name) + ":" + str(f.readText()))
                    }
                    w.write("},")
                }
                // fotos, audios y videos (al final, por pedazos)
                w.write("\"media\":{")
                var fm = true; var k = 0
                for (name in used) {
                    val f = File(Store.mediaDir, name)
                    if (!f.isFile) continue
                    if (!fm) w.write(","); fm = false
                    w.write(str(name) + ":\""); w.flush()
                    // base64 directo al archivo (sin cargar todo el video en la memoria)
                    val bo = object : java.io.OutputStream() {
                        override fun write(b: Int) { w.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) w.write(b[i].toInt()) }
                    }
                    android.util.Base64OutputStream(bo, Base64.NO_WRAP or Base64.NO_CLOSE).use { b64 -> f.inputStream().use { it.copyTo(b64, 1 shl 16) } }
                    w.write("\"")
                    if (++k % 5 == 0) onProgress(L.t("bkMedia", "n" to k))
                }
                w.write("}}")
            }
            // 2) cifrar (ChaCha20 + sello), igual que Best Notes
            onProgress(L.t("bkLocking"))
            val rnd = java.security.SecureRandom()
            val salt = ByteArray(16).also { rnd.nextBytes(it) }; val nonce = ByteArray(12).also { rnd.nextBytes(it) }
            val keys = pbkdf2(password.toByteArray(Charsets.UTF_8), salt, 40000, 2)
            val mac = hmac(keys.copyOfRange(32, 64)); mac.update("mis-notas-v1".toByteArray()); mac.update(salt); mac.update(nonce)
            val cha = ChaCha(keys.copyOfRange(0, 32), nonce)
            FileOutputStream(ctFile).use { o ->
                plain.inputStream().use { i ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = i.read(buf); if (n < 0) break; cha.xor(buf, 0, n); mac.update(buf, 0, n); o.write(buf, 0, n) }
                }
            }
            plain.delete()
            val tag = mac.doFinal()
            fun b64(x: ByteArray) = Base64.encodeToString(x, Base64.NO_WRAP)
            val head = JSONObject().put("app", "mis-notas").put("v", 1).put("kdf", "pbkdf2-sha256").put("iter", 40000)
                .put("cipher", "chacha20+hmac-sha256").put("from", "Best Notes 2")
                .put("created", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                .put("salt", b64(salt)).put("nonce", b64(nonce)).put("tag", b64(tag)).toString()
            FileOutputStream(out).use { o ->
                o.write((head.dropLast(1) + ",\"data\":\"").toByteArray())
                android.util.Base64OutputStream(object : java.io.FilterOutputStream(o) { override fun close() { flush() } }, Base64.NO_WRAP).use { b -> ctFile.inputStream().use { it.copyTo(b, 1 shl 16) } }
                o.write("\"}".toByteArray())
            }
        } finally { plain.delete(); ctFile.delete() }
    }

    /** Texto de un campo, o "" si no viene o viene vacío (null). */
    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun importNote(o: JSONObject, existing: Map<String, Long>, sum: Summary): Boolean {
        val id = o.str("id"); if (id.isEmpty()) return false
        val updated = o.optDouble("updated", 0.0).toLong()
        val have = existing[id]
        if (have != null && have >= updated) return false               // la de aquí es igual o más nueva
        o.optJSONObject("bn2")?.let { b -> return importBn2(id, b) }
        val conv = HtmlImport.convert(o.str("content"), o.optBoolean("html", true), ::saveDataUri)
        sum.missing += conv.missing
        val m = NoteMeta(id)
        m.title = HtmlImport.decodeEnt(o.str("title")).replace(Regex("<[^>]*>"), "")
        m.titleColor = HtmlImport.titleColor(o.str("titleHtml"), o.str("titleColor"))
        m.color = HtmlImport.cssColor(o.str("color"))
        m.folder = o.str("folderId").ifEmpty { null }
        m.fav = o.optBoolean("fav"); m.pinned = o.optBoolean("pinned")
        m.deleted = o.optBoolean("deleted"); m.deletedAt = if (m.deleted) updated else 0L
        m.locked = o.optBoolean("locked")
        m.kind = o.str("kind").ifEmpty { "text" }
        m.created = o.optDouble("created", updated.toDouble()).toLong()
        m.updated = if (updated > 0) updated else System.currentTimeMillis()
        Store.fillMeta(m, conv.blocks, null, o.optJSONArray("ink"))
        val extra = JSONObject()
        o.optJSONArray("ink")?.let { if (it.length() > 0) extra.put("ink", it) }
        if (o.optBoolean("histLocked")) extra.put("histLocked", true)
        val body = blocksJson(conv.blocks)
        Store.inTransaction { db ->
            db.insertWithOnConflict("notes", null, Store.rowValues(m, body, if (extra.length() > 0) extra.toString() else null),
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
        return true
    }
}
