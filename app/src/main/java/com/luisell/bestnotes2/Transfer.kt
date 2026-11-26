package com.luisell.bestnotes2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/* ============================================================
   Transfer.kt — 📲 pasar una nota COMPLETA a otro teléfono.

   🌐 LocalShare: tu teléfono se vuelve una pequeña página web
      (solo mientras la ventana está abierta). Enseña un QR; el
      otro teléfono lo escanea con su cámara normal y ve la nota
      igual que en un correo: letras, fotos y videos en calidad
      completa. No hace falta internet: solo estar en la misma
      Wi-Fi o conectar el otro teléfono a tu hotspot.
      El enlace lleva una clave secreta que cambia cada vez.

   📦 NotePack: la nota completa en un archivo (con fotos y
      videos en calidad completa) para mandarla por Quick Share.
      El otro teléfono lo abre con Best Notes y la nota aparece.
   ============================================================ */
object LocalShare {
    /** La dirección de este teléfono en la Wi-Fi o en su hotspot (null = no hay red). */
    fun ip(): String? = try {
        val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val cands = all.flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { n.name to it.hostAddress } }
        // primero la Wi-Fi o el hotspot (wlan, ap, swlan); los datos móviles no sirven aquí
        (cands.firstOrNull { (n, _) -> n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") } ?: cands.firstOrNull { (n, _) -> !n.startsWith("rmnet") && !n.startsWith("ccmni") })?.second
    } catch (_: Exception) { null }

    internal class Server(val token: String, val html: File, val files: Map<String, File>, val full: () -> File?, val onVisit: () -> Unit) {
        val socket = ServerSocket(0)
        @Volatile var on = true
        val port get() = socket.localPort
        fun start() {
            Thread {
                while (on) {
                    val s = try { socket.accept() } catch (_: Exception) { break }
                    Thread { try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} } }.start()
                }
            }.start()
        }
        fun stop() { on = false; try { socket.close() } catch (_: Exception) {} }

        private fun handle(s: Socket) {
            s.soTimeout = 20_000
            val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = inp.readLine() ?: return
            var range: String? = null
            while (true) { val h = inp.readLine() ?: break; if (h.isEmpty()) break; if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim() }
            val parts = line.split(' ')
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) return send(s, 405, "text/plain", null, "No")
            val path = java.net.URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
            val head = parts[0] == "HEAD"
            when {
                path == "/$token" || path == "/$token/" -> { onVisit(); sendFile(s, html, "text/html; charset=utf-8", null, head) }
                path.startsWith("/$token/m/") -> {
                    val f = files[path.removePrefix("/$token/m/")] ?: return send(s, 404, "text/plain", null, "404")
                    sendFile(s, f, mime(f.name), range, head)
                }
                path == "/$token/descargar" -> {
                    val f = full() ?: return send(s, 500, "text/plain", null, "Error")
                    sendFile(s, f, "text/html; charset=utf-8", null, head, "attachment; filename=\"" + f.name.replace("\"", "") + "\"")
                }
                else -> send(s, 404, "text/plain", null, "404")
            }
        }

        private fun mime(n: String) = when (n.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
            "mp4" -> "video/mp4"; "webm" -> "video/webm"; "3gp" -> "video/3gpp"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }

        private fun send(s: Socket, code: Int, type: String, extra: String?, body: String) {
            val b = body.toByteArray()
            val o = s.getOutputStream()
            o.write(("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n" + (extra ?: "") + "\r\n").toByteArray()); o.write(b); o.flush()
        }

        /** Manda un archivo (con "Range" para que el video se pueda adelantar). */
        private fun sendFile(s: Socket, f: File, type: String, range: String?, head: Boolean, disp: String? = null) {
            val len = f.length()
            var a = 0L; var b = len - 1; var partial = false
            if (range != null && range.startsWith("bytes=")) {
                val r = range.removePrefix("bytes=").split(',')[0].split('-')
                val x = r.getOrNull(0)?.toLongOrNull(); val y = r.getOrNull(1)?.toLongOrNull()
                if (x != null) { a = x; b = y ?: (len - 1) } else if (y != null) { a = len - y; b = len - 1 }
                a = a.coerceIn(0, maxOf(0, len - 1)); b = b.coerceIn(a, maxOf(0, len - 1)); partial = true
            }
            val n = if (len == 0L) 0 else b - a + 1
            val o = java.io.BufferedOutputStream(s.getOutputStream(), 1 shl 16)
            val sb = StringBuilder(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: $type\r\nContent-Length: $n\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n")
            if (partial) sb.append("Content-Range: bytes $a-$b/$len\r\n")
            if (disp != null) sb.append("Content-Disposition: $disp\r\n")
            sb.append("\r\n")
            o.write(sb.toString().toByteArray())
            if (!head && n > 0) f.inputStream().use { i ->
                i.skip(a); var left = n; val buf = ByteArray(1 shl 16)
                while (left > 0 && on) { val k = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (k < 0) break; o.write(buf, 0, k); left -= k }
            }
            o.flush()
        }
    }

    fun show(act: Activity, noteId: String) {
        val wait = act.dialog().setMessage("🌐 " + L.t("lsPreparing")).setCancelable(false).show()
        Thread {
            val items = try { Export.load(listOf(noteId)) } catch (_: Throwable) { emptyList() }
            val token = java.util.UUID.randomUUID().toString().replace("-", "").take(14)
            val dir = File(act.cacheDir, "pagina").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val files = HashMap<String, File>()
            val html = File(dir, "nota.html")
            val ok = items.isNotEmpty() && try {
                Export.page(act, items, html) { f -> files[f.name] = f; "/$token/m/" + Uri.encode(f.name) }
                // 📥 botón para guardar la nota completa (en un solo archivo) en el otro teléfono
                val t = html.readText().replace("<div class=\"f\">Best Notes</div>",
                    "<div class=\"f\"><a href=\"/$token/descargar\" style=\"display:inline-block;padding:12px 22px;border-radius:22px;background:#8b7cff;color:#fff;text-decoration:none;font-weight:600\">📥 ${L.t("lsSave")}</a><br><br>Best Notes</div>")
                html.writeText(t); true
            } catch (_: Throwable) { false }
            act.runOnUiThread {
                wait.dismiss()
                if (!ok) { act.toast(L.t(if (items.isEmpty()) "shareLocked" else "vFail")); return@runOnUiThread }
                val name = items[0].meta.title.ifBlank { L.t("untitled") }
                var fullFile: File? = null
                val srv = Server(token, html, files, {
                    synchronized(this) { fullFile ?: File(dir, Share.fileName(name, "html")).also { Export.page(act, items, it); fullFile = it } }
                }, { act.runOnUiThread { visits?.invoke() } })
                srv.start()
                screen(act, srv, name)
            }
        }.start()
    }

    private var visits: (() -> Unit)? = null

    private fun screen(act: Activity, srv: Server, name: String) {
        val d = blackFullDialog(act)
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        d.window?.attributes = d.window?.attributes?.apply { screenBrightness = 1f }
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Color.BLACK); setPadding(act.dp(20), act.dp(46), act.dp(20), act.dp(30)) }
        root.addView(act.tv("🌐 " + L.t("lsTitle"), 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        root.addView(act.tv(name, 14f, 0xB3FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(4), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val how = act.tv("", 14.5f, 0xE6FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(12), 0, act.dp(16)); setLineSpacing(0f, 1.25f) }
        root.addView(how)
        val card = FrameLayout(act).apply { background = roundBg(Color.WHITE, act.dp(26).toFloat()); setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14)); elevation = act.dp(10).toFloat() }
        val qv = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        card.addView(qv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val side = minOf(act.resources.displayMetrics.widthPixels - act.dp(70), act.dp(320))
        root.addView(card, LinearLayout.LayoutParams(side, side))
        val url = act.tv("", 13f, 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(10), 0, 0); setTextIsSelectable(true) }
        root.addView(url)
        val seen = act.tv("", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8)) }
        root.addView(seen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(12) })
        root.addView(android.view.View(act), LinearLayout.LayoutParams(1, 0, 1f))
        val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun pill(label: String, color: Int, go: () -> Unit) = btns.addView(act.tv(label, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(act.dp(18), act.dp(12), act.dp(18), act.dp(12)); background = if (color == 0) Glass.make(act, 22f, strong = true) else roundBg(color, act.dp(22).toFloat())
            setOnClickListener { v -> Anim.bump(v); go() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(act.dp(5), 0, act.dp(5), 0) })
        root.addView(btns)

        var n = 0
        visits = {
            n++
            seen.text = "👀 " + L.t("lsSeen", "n" to n)
            seen.background = roundBg(0xFF2FA866.toInt(), act.dp(18).toFloat())
            Anim.bump(seen, 1.2f)
            if (n == 1) Confetti.burst(act)
        }
        fun paint() {
            val ip = ip()
            btns.removeAllViews()
            if (ip == null) {
                how.text = "📶 " + L.t("lsNoWifi")
                qv.setImageDrawable(null); card.alpha = 0.25f; url.text = ""
                pill("🔁 " + L.t("lsRetry"), T.accent) { paint() }
                pill("📡 Hotspot", 0) { try { act.startActivity(Intent("android.settings.TETHER_SETTINGS")) } catch (_: Exception) { try { act.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {} } }
            } else {
                val u = "http://$ip:${srv.port}/${srv.token}/"
                how.text = L.t("lsHow")
                qv.setImageBitmap(QrShare.qr(u)); card.alpha = 1f; url.text = u
                if (n == 0) seen.text = "⏳ " + L.t("lsWaiting")
                pill(L.t("copy"), 0) { (act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("nota", u)); act.toast(L.t("copied")) }
                Anim.popIn(card)
            }
            pill(L.t("close"), 0) { d.dismiss() }
        }
        d.setContentView(root)
        d.setOnDismissListener { srv.stop(); visits = null }
        d.show()
        paint()
        // la QR (la pantalla del QR se ve sin cortarse; el código es grande y nítido)
        qv.post { (qv.drawable as? android.graphics.drawable.BitmapDrawable)?.paint?.isFilterBitmap = false; qv.invalidate() }
    }
}

object NotePack {
    /** La nota completa (con fotos y videos en calidad completa) en un archivo .zip. */
    fun make(ctx: Context, noteId: String): File? = try {
        val m = Store.meta(noteId)!!
        val (body, extra) = Store.readBody(noteId)
        val blocks = blocksFromJson(body)
        val names = blocks.flatMap { b -> when (b) { is Block.Img -> (listOf(b.file, b.orig) + Pins.parse(b.pins).map { it.f }).filter { it.isNotEmpty() }; is Block.Media -> listOf(b.file); else -> emptyList() } }.distinct()
        val dir = File(ctx.cacheDir, "paquete").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, Share.fileName(m.title.ifBlank { L.t("untitled") }, "zip"))
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            val j = JSONObject().put("app", "bestnotes-nota").put("v", 1).put("t", m.title).put("tc", m.titleColor).put("c", m.color).put("b", body ?: "[]").put("x", extra ?: "")
            z.putNextEntry(ZipEntry("nota.json")); z.write(j.toString().toByteArray()); z.closeEntry()
            for (n in names) {
                val f = File(Store.mediaDir, n); if (!f.isFile) continue
                z.putNextEntry(ZipEntry("m/$n").apply { method = ZipEntry.STORED; size = f.length(); compressedSize = f.length(); crc = java.util.zip.CRC32().also { c -> f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val k = i.read(buf); if (k < 0) break; c.update(buf, 0, k) } } }.value })
                f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        out
    } catch (_: Throwable) { null }

    fun send(act: Activity, noteId: String) {
        val wait = act.dialog().setMessage("📦 " + L.t("packMaking")).setCancelable(false).show()
        Thread {
            val f = make(act, noteId)
            act.runOnUiThread {
                wait.dismiss()
                if (f == null) { act.toast(L.t("vFail")); return@runOnUiThread }
                act.toast("📲 " + L.t("packHint"))
                Share.send(act, f, "application/zip", f.nameWithoutExtension)
            }
        }.start()
    }

    /** ¿Es un archivo de nota de Best Notes? (empieza como .zip y trae nota.json) */
    fun isPack(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { i ->
            ZipInputStream(i.buffered()).use { z -> var e = z.nextEntry; var ok = false; var k = 0; while (e != null && k++ < 3) { if (e.name == "nota.json") { ok = true; break }; e = z.nextEntry }; ok }
        } ?: false
    } catch (_: Throwable) { false }

    /** Guarda la nota del archivo (con todas sus fotos y videos) como una nota nueva. */
    fun receive(act: Activity, uri: Uri, open: (NoteMeta) -> Unit) {
        val wait = act.dialog().setMessage("📥 " + L.t("qrSaving")).setCancelable(false).show()
        Thread {
            val m = try {
                var j: JSONObject? = null
                val rename = HashMap<String, String>()
                act.contentResolver.openInputStream(uri)!!.use { i ->
                    ZipInputStream(i.buffered()).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            if (e.name == "nota.json") j = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                            else if (e.name.startsWith("m/")) {
                                val old = e.name.removePrefix("m/")
                                if (!Regex("^[\\w.\\-]+$").matches(old)) continue
                                val nf = "f" + newId() + "." + old.substringAfterLast('.', "jpg")
                                File(Store.mediaDir, nf).outputStream().use { z.copyTo(it) }
                                rename[old] = nf
                            }
                        }
                    }
                }
                val o = j ?: throw IllegalStateException()
                if (o.optString("app") != "bestnotes-nota") throw IllegalStateException()
                var body = o.optString("b", "[]")
                for ((a, b) in rename) body = body.replace(a, b)
                val meta = Store.createNote(o.optString("t"), blocksFromJson(body))
                meta.titleColor = o.optInt("tc"); meta.color = o.optInt("c")
                Store.saveMeta(meta)
                meta
            } catch (_: Throwable) { null }
            act.runOnUiThread {
                wait.dismiss()
                if (m == null) { act.toast(L.t("vFail")); return@runOnUiThread }
                Store.changed(); Confetti.burst(act); act.toast("📥 " + L.t("qrGot"))
                open(m)
            }
        }.start()
    }
}
