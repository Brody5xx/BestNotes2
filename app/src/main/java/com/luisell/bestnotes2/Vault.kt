package com.luisell.bestnotes2

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.Base64InputStream
import android.util.Base64OutputStream
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/* ============================================================
   Vault.kt — la BÓVEDA: contraseñas, correos, enlaces, notas
   secretas, fotos, videos y archivos, todo CIFRADO con tu contraseña.

   - Mismo formato que la bóveda de Best Notes (se puede pasar de una a otra):
     meta.json, index.bin y f_<id>.bin en la carpeta "boveda".
   - La contraseña nunca se guarda. Las llaves viven en la memoria
     solo mientras la bóveda está abierta; al salir se cierra sola.
   ============================================================ */
object VaultBox {
    class WrongPass : Exception()

    class Keys(val enc: ByteArray, val mac: ByteArray)

    const val MAX_MB = 150
    private val rnd = SecureRandom()

    fun dir(ctx: Context) = File(ctx.filesDir, "boveda").apply { mkdirs() }
    fun exists(ctx: Context) = File(dir(ctx), "meta.json").exists()
    fun file(ctx: Context, name: String) = File(dir(ctx), name)
    fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    fun keys(pass: String, salt: ByteArray, iter: Int = 40000): Keys {
        val k = Backup.pbkdf2(pass.toByteArray(Charsets.UTF_8), salt, iter, 2)
        return Keys(k.copyOfRange(0, 32), k.copyOfRange(32, 64))
    }

    private fun mac(k: Keys, nonce: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(k.mac, "HmacSHA256"))
        update("mis-notas-v1".toByteArray()); update(nonce)            // (la "sal" va vacía, como en Best Notes)
    }

    /** Cifra bytes: nonce(12) + sello(32) + cifrado. */
    fun seal(k: Keys, data: ByteArray): ByteArray {
        val nonce = random(12)
        val ct = data.copyOf(); Backup.ChaCha(k.enc, nonce).xor(ct, 0, ct.size)
        val tag = mac(k, nonce).doFinal(ct)
        return nonce + tag + ct
    }

    fun unseal(k: Keys, data: ByteArray): ByteArray {
        if (data.size < 44) throw WrongPass()
        val nonce = data.copyOfRange(0, 12); val tag = data.copyOfRange(12, 44); val ct = data.copyOfRange(44, data.size)
        if (!MessageDigest.isEqual(mac(k, nonce).doFinal(ct), tag)) throw WrongPass()
        Backup.ChaCha(k.enc, nonce).xor(ct, 0, ct.size)
        return ct
    }

    /* ---------- textos guardados en base64 (igual que Best Notes) ---------- */
    fun writeSealed(ctx: Context, name: String, k: Keys, data: ByteArray) {
        val f = file(ctx, name); val t = File(f.path + ".tmp")
        t.writeText(Base64.encodeToString(seal(k, data), Base64.NO_WRAP)); t.renameTo(f)
    }
    fun readSealed(ctx: Context, name: String, k: Keys): ByteArray =
        unseal(k, Base64.decode(file(ctx, name).readText(), Base64.DEFAULT))

    /** Cifra un archivo grande (video) sin cargarlo todo en la memoria. */
    fun sealStream(ctx: Context, name: String, k: Keys, inp: InputStream): Long {
        val nonce = random(12)
        val cha = Backup.ChaCha(k.enc, nonce); val m = mac(k, nonce)
        val raw = File(ctx.cacheDir, "boveda_ct.tmp")
        var total = 0L
        FileOutputStream(raw).use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = inp.read(buf); if (n < 0) break
                total += n
                if (total > MAX_MB * 1048576L) { out.close(); raw.delete(); throw IllegalStateException("big") }
                cha.xor(buf, 0, n); m.update(buf, 0, n); out.write(buf, 0, n)
            }
        }
        val tag = m.doFinal()
        val f = file(ctx, name); val t = File(f.path + ".tmp")
        Base64OutputStream(FileOutputStream(t), Base64.NO_WRAP).use { o ->
            o.write(nonce); o.write(tag)
            raw.inputStream().use { it.copyTo(o, 1 shl 16) }
        }
        raw.delete(); t.renameTo(f)
        return total
    }

    /** Descifra un archivo grande a [out] (y revisa el sello al final). */
    fun unsealStream(ctx: Context, name: String, k: Keys, out: File) {
        BufferedInputStream(Base64InputStream(file(ctx, name).inputStream(), Base64.DEFAULT), 1 shl 16).use { inp ->
            val nonce = ByteArray(12); val tag = ByteArray(32)
            if (readFully(inp, nonce) < 12 || readFully(inp, tag) < 32) throw WrongPass()
            val cha = Backup.ChaCha(k.enc, nonce); val m = mac(k, nonce)
            FileOutputStream(out).use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = inp.read(buf); if (n < 0) break; m.update(buf, 0, n); cha.xor(buf, 0, n); o.write(buf, 0, n) }
            }
            if (!MessageDigest.isEqual(m.doFinal(), tag)) { out.delete(); throw WrongPass() }
        }
    }
    private fun readFully(i: InputStream, b: ByteArray): Int { var got = 0; while (got < b.size) { val n = i.read(b, got, b.size - got); if (n < 0) break; got += n }; return got }

    /* ---------- crear / abrir ---------- */
    fun create(ctx: Context, pass: String): Keys {
        val salt = random(16); val k = keys(pass, salt)
        val check = seal(k, "boveda-ok".toByteArray())
        file(ctx, "meta.json").writeText(JSONObject().put("v", 1).put("salt", b64(salt)).put("iter", 40000).put("check", b64(check)).toString())
        saveIndex(ctx, k, JSONArray())
        return k
    }
    fun open(ctx: Context, pass: String): Keys {
        val m = JSONObject(file(ctx, "meta.json").readText())
        val k = keys(pass, Base64.decode(m.getString("salt"), Base64.DEFAULT), m.optInt("iter", 40000))
        unseal(k, Base64.decode(m.getString("check"), Base64.DEFAULT))          // si la contraseña está mal, falla aquí
        return k
    }
    fun loadIndex(ctx: Context, k: Keys): JSONArray =
        if (!file(ctx, "index.bin").exists()) JSONArray() else JSONArray(String(readSealed(ctx, "index.bin", k), Charsets.UTF_8))
    fun saveIndex(ctx: Context, k: Keys, list: JSONArray) = writeSealed(ctx, "index.bin", k, list.toString().toByteArray(Charsets.UTF_8))

    fun changePass(ctx: Context, old: Keys, pass: String, list: JSONArray): Keys {
        val salt = random(16); val k2 = keys(pass, salt)
        for (i in 0 until list.length()) {
            val it = list.getJSONObject(i); if (!it.has("file")) continue
            val name = "f_" + it.getString("id") + ".bin"
            val tmp = File(ctx.cacheDir, "boveda_re.tmp")
            unsealStream(ctx, name, old, tmp)
            tmp.inputStream().use { s -> sealStream(ctx, name, k2, s) }
            tmp.delete()
        }
        saveIndex(ctx, k2, list)
        file(ctx, "meta.json").writeText(JSONObject().put("v", 1).put("salt", b64(salt)).put("iter", 40000).put("check", b64(seal(k2, "boveda-ok".toByteArray()))).toString())
        return k2
    }

    fun wipe(ctx: Context) { dir(ctx).deleteRecursively() }

    /** Para el respaldo de Best Notes: la bóveda viaja tal cual (sigue cifrada). "ok", "exists" o "none". */
    fun importRaw(ctx: Context, files: Map<String, String>): String {
        val meta = files["meta.json"] ?: return "none"
        val mine = file(ctx, "meta.json")
        if (mine.exists() && mine.readText() != meta) return "exists"
        for ((n, v) in files) if (Regex("^[\\w.\\-]+$").matches(n)) file(ctx, n).writeText(v)
        return "ok"
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36) + b64(random(6)).replace(Regex("[^a-zA-Z0-9]"), "").take(6)
}

class VaultActivity : Activity() {
    companion object {
        // [tipo, ícono, texto]
        val TYPES = listOf(
            Triple("pass", Icons.KEY, "vPass"), Triple("mail", Icons.MAIL, "vMail"), Triple("link", Icons.GLOBE, "vLink"),
            Triple("note", Icons.PENCIL, "vNote"), Triple("photo", Icons.IMAGE, "vPhoto"), Triple("video", MoreIcons.VIDEO, "vVideo"),
            Triple("file", MoreIcons.ATTACH, "vFile"),
        )
        val FIELDS = mapOf(
            "pass" to listOf("title", "user", "pass", "url", "note"), "mail" to listOf("title", "email", "pass", "note"),
            "link" to listOf("title", "url", "note"), "note" to listOf("title", "note"),
            "photo" to listOf("title", "note"), "video" to listOf("title", "note"), "file" to listOf("title", "note"),
        )
        private const val PICK = 41
    }

    private var keys: VaultBox.Keys? = null
    private var items = JSONArray()
    private lateinit var root: FrameLayout
    private var query = ""
    private var filter = "all"
    private var pickKind = "photo"
    private val tmpFiles = ArrayList<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        root = FrameLayout(this)
        edgeToEdge(root)
        setContentView(root)
        if (VaultBox.exists(this)) showLocked() else showCreate()
    }

    /** Al salir de la app, la bóveda se cierra sola. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !picking && keys != null) { lock(); showLocked() }
    }
    override fun onDestroy() { super.onDestroy(); cleanTmp() }

    private fun lock() { keys = null; items = JSONArray(); cleanTmp() }
    private fun cleanTmp() { tmpFiles.forEach { it.delete() }; tmpFiles.clear() }

    private fun bg(work: () -> Unit) = Thread(work).start()

    private fun screen(): LinearLayout {
        root.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        top.addView(glassBtn(Icons.BACK) { finish() })
        top.addView(tv(L.t("vault"), 22f, T.text, true).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, WRAP, 1f))
        col.addView(top)
        return col
    }

    private fun passField(hint: String) = EditText(this).apply {
        this.hint = hint; setTextColor(T.text); setHintTextColor(T.faint); isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        typeface = android.graphics.Typeface.DEFAULT
        background = roundBg(0x2E000000, dp(14).toFloat(), T.border, dp(1)); setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun bigBtn(text: String, onClick: () -> Unit) = tv(text, 16f, 0xFF161618.toInt(), true).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14)); pressBg(0xFFFFFFFF.toInt(), 22f)
        setOnClickListener { onClick() }
    }

    private fun lockCard(col: LinearLayout): LinearLayout {
        val sv = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(30), dp(24), dp(30)) }
        box.addView(IconView(this, MoreIcons.SHIELD, 26f).apply { color = 0xFF4FC3F7.toInt() }, lp(dp(96), dp(96)))
        sv.addView(box); col.addView(sv, lp(MATCH, 0, 1f))
        return box
    }

    /* ---------- crear ---------- */
    private fun showCreate() {
        val col = screen(); val box = lockCard(col)
        box.addView(tv(L.t("vCreateTitle"), 20f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6)) })
        box.addView(tv(L.t("vCreateMsg"), 14f, T.muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(18)) })
        val p1 = passField(L.t("vPassNew")); val p2 = passField(L.t("vPassAgain"))
        box.addView(p1, lp(MATCH, WRAP)); box.addView(p2, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val err = tv("", 14f, T.danger).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }; box.addView(err)
        lateinit var btn: TextView
        btn = bigBtn(L.t("vCreate")) {
            val a = p1.text.toString(); val b = p2.text.toString()
            if (a.length < 6) { err.text = L.t("vShort"); return@bigBtn }
            if (a != b) { err.text = L.t("vMismatch"); return@bigBtn }
            btn.isEnabled = false; btn.text = L.t("working")
            bg {
                val k = try { VaultBox.create(this, a) } catch (_: Exception) { null }
                runOnUiThread { if (k == null) { btn.isEnabled = true; btn.text = L.t("vCreate"); err.text = L.t("vFail") } else { keys = k; items = JSONArray(); showOpen() } }
            }
        }
        box.addView(btn, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        box.addView(tv("⚠️ " + L.t("vForgetWarn"), 13f, T.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
    }

    /* ---------- abrir ---------- */
    private fun showLocked() {
        val col = screen(); val box = lockCard(col)
        box.addView(tv(L.t("vLocked"), 20f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(18)) })
        val p = passField(L.t("vPassword")); box.addView(p, lp(MATCH, WRAP))
        val err = tv("", 14f, T.danger).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }; box.addView(err)
        lateinit var btn: TextView
        val go = {
            val pass = p.text.toString()
            if (pass.isNotEmpty() && btn.isEnabled) {
                btn.isEnabled = false; btn.text = L.t("vOpening"); err.text = ""
                bg {
                    var k: VaultBox.Keys? = null; var list = JSONArray(); var wrong = false
                    try { k = VaultBox.open(this, pass); list = VaultBox.loadIndex(this, k) } catch (_: VaultBox.WrongPass) { wrong = true; k = null } catch (_: Exception) { k = null }
                    runOnUiThread {
                        if (k == null) { btn.isEnabled = true; btn.text = L.t("vOpen"); err.text = L.t(if (wrong) "vWrong" else "vFail") }
                        else { keys = k; items = list; hideKb(p); showOpen() }
                    }
                }
            }
        }
        btn = bigBtn(L.t("vOpen")) { go() }
        p.setOnEditorActionListener { _, _, _ -> go(); true }
        box.addView(btn, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        box.addView(tv(L.t("vForgot"), 14f, T.muted).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(22), dp(10), dp(10))
            setOnClickListener { confirm(L.t("vReset"), L.t("vResetMsg"), L.t("vReset"), true) { VaultBox.wipe(this@VaultActivity); lock(); showCreate() } }
        })
        p.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun hideKb(v: View) = (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)

    /* ---------- la bóveda abierta ---------- */
    private lateinit var listBox: LinearLayout
    private lateinit var chips: LinearLayout

    private fun showOpen() {
        val col = screen()
        val top = col.getChildAt(0) as LinearLayout
        top.addView(glassBtn(Icons.LOCK) { lock(); showLocked() })
        top.addView(glassBtn(MoreIcons.DOTS_V) { v -> openMenu(v) })
        val search = EditText(this).apply {
            hint = L.t("vSearch"); setText(query); setTextColor(T.text); setHintTextColor(T.faint); isSingleLine = true
            background = Glass.make(this@VaultActivity, 22f); setPadding(dp(18), dp(12), dp(18), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString(); drawList() }
            })
        }
        col.addView(search, lp(MATCH, WRAP).apply { setMargins(dp(14), dp(2), dp(14), dp(8)) })
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(6)) }
        hs.addView(chips); col.addView(hs)
        val sv = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(100)) }
        sv.addView(listBox); col.addView(sv, lp(MATCH, 0, 1f))
        val fab = tv("+", 30f, 0xFF161618.toInt(), true).apply {
            gravity = Gravity.CENTER; pressBg(0xFFFFFFFF.toInt(), 30f); elevation = dp(6).toFloat()
            setOnClickListener { addMenu() }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(20), dp(28)) })
        drawList()
    }

    private fun typeOf(t: String) = TYPES.firstOrNull { it.first == t } ?: TYPES[3]
    private fun list(): List<JSONObject> = (0 until items.length()).map { items.getJSONObject(it) }

    private fun drawList() {
        chips.removeAllViews()
        val all = list()
        fun chip(id: String, label: String) {
            val on = filter == id
            chips.addView(tv(label, 13f, if (on) 0xFF161618.toInt() else T.text, true).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = if (on) roundBg(0xFFFFFFFF.toInt(), dp(16).toFloat()) else roundBg(0x14FFFFFF, dp(16).toFloat(), T.border, dp(1))
                setOnClickListener { filter = id; drawList() }
            }, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
        chip("all", L.t("vAll") + " · " + all.size)
        for ((t, _, label) in TYPES) { val n = all.count { it.optString("type") == t }; if (n > 0) chip(t, L.t(label + "s") + " · " + n) }
        listBox.removeAllViews()
        val q = query.trim().lowercase()
        val shown = all.filter { filter == "all" || it.optString("type") == filter }
            .filter { q.isEmpty() || listOf("title", "user", "email", "url", "note").any { k -> it.optString(k).lowercase().contains(q) } }
        if (shown.isEmpty()) {
            listBox.addView(tv(if (all.isEmpty()) L.t("vEmpty") else L.t("vNoMatch"), 15f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(60), dp(20), 0) })
            return
        }
        for (it in shown) listBox.addView(row(it), lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    private fun row(it: JSONObject): View {
        val (type, icon, _) = typeOf(it.optString("type"))
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(8), dp(12))
            background = Glass.make(this@VaultActivity, 18f)
            setOnClickListener { _ -> edit(it) }
        }
        r.addView(IconView(this, icon, 10f).apply { color = 0xFF4FC3F7.toInt(); isClickable = false; background = roundBg(0x224FC3F7, dp(20).toFloat()) }, lp(dp(40), dp(40)))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
        txt.addView(tv(it.optString("title").ifEmpty { L.t("untitled") }, 16f, T.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val sub = when (type) {
            "pass" -> it.optString("user").ifEmpty { it.optString("url") }
            "mail" -> it.optString("email"); "link" -> it.optString("url")
            "note" -> it.optString("note").replace('\n', ' ')
            else -> it.optJSONObject("file")?.let { f -> f.optString("name") + " · " + size(f.optLong("size")) } ?: ""
        }
        if (sub.isNotEmpty()) txt.addView(tv(sub, 13f, T.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        r.addView(txt, lp(0, WRAP, 1f))
        // copiar rápido la contraseña
        if ((type == "pass" || type == "mail") && it.optString("pass").isNotEmpty())
            r.addView(iconBtn(Icons.KEY, 8.5f, T.muted) { _ -> copy(it.optString("pass"), true) }.apply { layoutParams = lp(dp(40), dp(40)) })
        return r
    }

    private fun size(n: Long) = when { n < 1024 -> "$n B"; n < 1048576 -> "${n / 1024} KB"; else -> String.format(L.locale, "%.1f MB", n / 1048576.0) }

    private fun copy(text: String, secret: Boolean) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Best Notes", text)
        if (secret && android.os.Build.VERSION.SDK_INT >= 24)
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        toast(L.t(if (secret) "vCopiedPass" else "vCopied"))
    }

    private fun persist(after: () -> Unit = {}) {
        val k = keys ?: return
        val snapshot = JSONArray(items.toString())
        bg { try { VaultBox.saveIndex(this, k, snapshot) } catch (_: Exception) {}; runOnUiThread(after) }
    }

    /* ---------- agregar ---------- */
    private fun addMenu() {
        val labels = TYPES.map { L.t(it.third) }.toTypedArray()
        dialog().setTitle(L.t("vAdd")).setItems(labels) { _, i ->
            val t = TYPES[i].first
            if (t == "photo" || t == "video" || t == "file") pick(t)
            else edit(JSONObject().put("type", t))
        }.show()
    }

    private var picking = false
    private fun pick(kind: String) {
        pickKind = kind; picking = true
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(when (kind) { "photo" -> "image/*"; "video" -> "video/*"; else -> "*/*" })
        try { startActivityForResult(i, PICK) } catch (_: Exception) { picking = false }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        picking = false
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val k = keys ?: return
        var name = "archivo"; var sz = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) sz = c.getLong(it) }
            }
        }
        if (sz > VaultBox.MAX_MB * 1048576L) { toast(L.t("vTooBig", "n" to VaultBox.MAX_MB)); return }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val kind = pickKind
        val wait = dialog().setMessage(L.t("vSaving")).setCancelable(false).show()
        bg {
            val id = VaultBox.newId()
            val ok = try {
                val n = contentResolver.openInputStream(uri)!!.use { VaultBox.sealStream(this, "f_$id.bin", k, it) }
                val now = System.currentTimeMillis()
                val it = JSONObject().put("id", id).put("type", kind).put("title", name.substringBeforeLast('.').ifEmpty { name }).put("note", "")
                    .put("created", now).put("updated", now).put("file", JSONObject().put("name", name).put("mime", mime).put("size", n))
                val nl = JSONArray().put(it); for (i in 0 until items.length()) nl.put(items.get(i))
                VaultBox.saveIndex(this, k, nl); items = nl; true
            } catch (e: IllegalStateException) { null } catch (_: Exception) { false }
            runOnUiThread {
                wait.dismiss()
                when (ok) { true -> { toast(L.t("vSaved")); if (keys != null) drawList() }; null -> toast(L.t("vTooBig", "n" to VaultBox.MAX_MB)); else -> toast(L.t("vFail")) }
            }
        }
    }

    /* ---------- ver / editar ---------- */
    private fun fieldLabel(f: String) = L.t(when (f) { "title" -> "vfTitle"; "user" -> "vfUser"; "pass" -> "vfPass"; "url" -> "vfUrl"; "email" -> "vfEmail"; else -> "vfNote" })

    private fun edit(it0: JSONObject) {
        val isNew = !it0.has("id")
        val type = it0.optString("type", "note")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(4)) }
        val sv = ScrollView(this).apply { addView(box) }
        val inputs = LinkedHashMap<String, EditText>()
        for (f in FIELDS[type] ?: listOf("title", "note")) {
            box.addView(tv(fieldLabel(f), 12f, T.muted).apply { setPadding(dp(4), dp(8), 0, dp(3)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val e = EditText(this).apply {
                setText(it0.optString(f)); setTextColor(T.text); setHintTextColor(T.faint)
                background = roundBg(0x2E000000, dp(12).toFloat(), T.border, dp(1)); setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = when (f) {
                    "pass" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    "note" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                if (f == "note") { minLines = 3; gravity = Gravity.TOP or Gravity.START } else isSingleLine = true
            }
            inputs[f] = e
            row.addView(e, lp(0, WRAP, 1f))
            if (f == "pass") {
                var shown = false
                row.addView(iconBtn(Icons.EYE, 8.5f, T.muted) { v ->
                    shown = !shown
                    e.inputType = InputType.TYPE_CLASS_TEXT or if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                    e.setSelection(e.text.length); (v as IconView).kind = if (shown) Icons.EYE_OFF else Icons.EYE; v.invalidate()
                }.apply { layoutParams = lp(dp(40), dp(40)) })
                row.addView(tv("🎲", 18f).apply { gravity = Gravity.CENTER; setOnClickListener { e.setText(genPass()); toast(L.t("vGenerated")) } }, lp(dp(40), dp(40)))
            }
            if (f != "title" && f != "note") row.addView(iconBtn(MoreIcons.COPY, 8.5f, T.muted) { _ -> if (e.text.isNotEmpty()) copy(e.text.toString(), f == "pass") }.apply { layoutParams = lp(dp(40), dp(40)) })
            if (f == "url") row.addView(iconBtn(Icons.SEND, 8.5f, T.muted) { _ -> openUrl(e.text.toString()) }.apply { layoutParams = lp(dp(40), dp(40)) })
            box.addView(row)
        }
        it0.optJSONObject("file")?.let { f ->
            box.addView(tv("📎 " + f.optString("name") + " · " + size(f.optLong("size")), 13f, T.muted).apply { setPadding(dp(4), dp(12), 0, dp(4)) })
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(bigBtn(L.t(if (type == "photo") "vView" else "vOpenFile")) { openMedia(it0, false) }, lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
            acts.addView(tv(L.t("share"), 15f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(14), dp(10), dp(14)); pressBg(0x1AFFFFFF, 22f, T.border); setOnClickListener { openMedia(it0, true) } }, lp(0, WRAP, 1f))
            box.addView(acts, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        val title = (if (isNew) L.t("vNew") + " · " else "") + L.t(typeOf(type).third)
        val b = dialog().setTitle(title).setView(sv)
            .setPositiveButton(L.t("save")) { _, _ ->
                val now = System.currentTimeMillis()
                val it = if (isNew) JSONObject().put("id", VaultBox.newId()).put("type", type).put("created", now) else it0
                for ((f, e) in inputs) it.put(f, e.text.toString())
                it.put("updated", now)
                if (isNew) { val nl = JSONArray().put(it); for (i in 0 until items.length()) nl.put(items.get(i)); items = nl }
                persist(); drawList()
            }
            .setNegativeButton(L.t("cancel"), null)
        if (!isNew) b.setNeutralButton(L.t("del")) { _, _ ->
            confirm(L.t("vDelete"), it0.optString("title"), L.t("del"), true) {
                val nl = JSONArray(); for (o in list()) if (o.optString("id") != it0.optString("id")) nl.put(o); items = nl
                if (it0.has("file")) VaultBox.file(this, "f_" + it0.optString("id") + ".bin").delete()
                persist(); drawList()
            }
        }
        val d = b.show()
        d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(T.danger)
        if (isNew) { inputs["title"]?.requestFocus(); d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE) }
    }

    private fun genPass(): String {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*?-_"
        val r = SecureRandom()
        return (1..16).map { a[r.nextInt(a.length)] }.joinToString("")
    }

    private fun openUrl(u: String) {
        if (u.isBlank()) return
        val url = if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(u)) u else "https://$u"
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast(L.t("vFail")) }
    }

    /** Descifra la foto/video/archivo a un archivo temporal (se borra al cerrar la bóveda). */
    private fun openMedia(it: JSONObject, share: Boolean) {
        val k = keys ?: return
        val f = it.optJSONObject("file") ?: return
        val wait = dialog().setMessage(L.t("vOpening")).setCancelable(false).show()
        bg {
            val dir = File(cacheDir, "boveda_ver").apply { mkdirs() }
            val safe = f.optString("name", "archivo").replace(Regex("[^\\w.\\- ]"), "_").ifEmpty { "archivo" }
            val out = File(dir, safe)
            val ok = try { VaultBox.unsealStream(this, "f_" + it.optString("id") + ".bin", k, out); true } catch (_: Exception) { false }
            runOnUiThread {
                wait.dismiss()
                if (!ok) { toast(L.t("vFail")); return@runOnUiThread }
                tmpFiles.add(out)
                val mime = f.optString("mime", "application/octet-stream")
                if (!share && it.optString("type") == "photo") { showPhoto(out); return@runOnUiThread }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.archivos", out)
                val i = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                picking = true                     // para que la bóveda no se cierre mientras la ves
                try { startActivity(if (share) Intent.createChooser(i, null) else i) } catch (_: Exception) { picking = false; toast(L.t("vNoApp")) }
            }
        }
    }

    override fun onResume() { super.onResume(); picking = false }

    private fun showPhoto(f: File) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1; while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: run { toast(L.t("vFail")); return }
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF000000.toInt()); setOnClickListener { d.dismiss() } }
        d.setContentView(iv); d.show()
    }

    /* ---------- menú ⋮ ---------- */
    private fun openMenu(@Suppress("UNUSED_PARAMETER") v: View) {
        dialog().setItems(arrayOf(L.t("vChangePass"), L.t("vReset"))) { _, i ->
            if (i == 0) changePass()
            else confirm(L.t("vReset"), L.t("vResetMsg"), L.t("vReset"), true) { VaultBox.wipe(this); lock(); showCreate() }
        }.show()
    }

    private fun changePass() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val p1 = passField(L.t("vPassNew")); val p2 = passField(L.t("vPassAgain"))
        box.addView(p1); box.addView(p2, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        dialog().setTitle(L.t("vChangePass")).setView(box).setPositiveButton(L.t("ok")) { _, _ ->
            val a = p1.text.toString()
            if (a.length < 6) { toast(L.t("vShort")); return@setPositiveButton }
            if (a != p2.text.toString()) { toast(L.t("vMismatch")); return@setPositiveButton }
            val k = keys ?: return@setPositiveButton
            val wait = dialog().setMessage(L.t("working")).setCancelable(false).show()
            val snapshot = JSONArray(items.toString())
            bg {
                val k2 = try { VaultBox.changePass(this, k, a, snapshot) } catch (_: Exception) { null }
                runOnUiThread { wait.dismiss(); if (k2 != null) { keys = k2; toast(L.t("vPassChanged")) } else toast(L.t("vFail")) }
            }
        }.setNegativeButton(L.t("cancel"), null).show()
    }
}
