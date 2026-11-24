package com.luisell.bestnotes2

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/* ============================================================
   WorkActivity.kt — 🧤 MODO TRABAJO: botones GRANDES para usar
   con guantes o con prisa. Un toque y: dictas una nota, tomas
   una foto (a una nota nueva o a la última), marcas pendientes,
   ves las notas de este lugar o abres la calculadora de cable.
   La pantalla no se apaga mientras estás aquí.
   También se abre dejando presionado el ícono de la app.
   ============================================================ */
class WorkActivity : Activity() {
    companion object { private const val REQ_CAM = 91; private const val REQ_CAM_PERM = 92; private const val REQ_MIC = 93 }

    private lateinit var list: LinearLayout
    private var camFile: File? = null
    private var camToLast = false
    private var dict: Dictation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); L.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sc = ScrollView(this).apply { isFillViewport = true; background = Glass.background(this@WorkActivity) }
        edgeToEdge(sc)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(30)) }
        sc.addView(col)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(glassBtn(Icons.BACK) { finish() })
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        head.addView(tv("🧤 " + L.t("workTitle"), 26f, T.text, true))
        val clock = tv("", 14f, T.muted)
        head.addView(clock)
        top.addView(head, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(top)
        fun tick() { clock.text = java.text.SimpleDateFormat("EEEE d MMMM · h:mm a", L.locale).format(java.util.Date()).replaceFirstChar { it.uppercase() } }
        tick(); clock.postDelayed(object : Runnable { override fun run() { tick(); clock.postDelayed(this, 20_000) } }, 20_000)

        // los botones grandes (2 por fila)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, dp(6)) }
        col.addView(grid)
        var row: LinearLayout? = null
        var n = 0
        fun big(emoji: String, label: String, color: Int, go: () -> Unit) {
            if (n % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(row) }
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = roundBg(color, dp(28).toFloat()); elevation = dp(6).toFloat()
                setPadding(dp(8), dp(18), dp(8), dp(18))
                addView(tv(emoji, 40f).apply { gravity = Gravity.CENTER })
                addView(tv(label, 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
                setOnClickListener { v -> Anim.bump(v, 1.06f); v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); go() }
            }
            row!!.addView(b, LinearLayout.LayoutParams(0, dp(150), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            Anim.enter(b, 80L + n * 60, 30f)
            n++
        }
        big("🎙️", L.t("workDictate"), 0xFFE5484D.toInt()) { dictate() }
        big("📷", L.t("workPhotoNew"), 0xFF3A7BFF.toInt()) { photo(false) }
        big("🖼️", L.t("workPhotoLast"), 0xFF7A5CFF.toInt()) { photo(true) }
        big("📍", L.t("workHere"), 0xFF2FA866.toInt()) { startActivity(Intent(this, MainActivity::class.java).putExtra(Arrive.EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() }
        big("🔌", L.t("workCable"), 0xFFFF9F0A.toInt()) { startActivity(Intent(this, CalcActivity::class.java)) }
        big("🤖", L.t("askTitle"), 0xFFFF6FB5.toInt()) { AskNotes.show(this) { m -> startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)) } }

        // ☑️ los pendientes, con casillas grandes
        col.addView(tv("☑️  " + L.t("pending").uppercase(L.locale), 14f, T.muted, true).apply { letterSpacing = 0.1f; setPadding(dp(8), dp(18), 0, dp(6)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(sc)
    }

    override fun onResume() { super.onResume(); Store.load { loadTodo() } }
    override fun onPause() { super.onPause(); dict?.stop(); dict = null }

    private fun loadTodo() {
        Pending.load { rows ->
            list.removeAllViews()
            if (rows.isEmpty()) { list.addView(tv("🎉 " + L.t("stAllDone"), 17f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)) }); return@load }
            for ((i, r) in rows.take(30).withIndex()) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Glass.make(this@WorkActivity, 22f); setPadding(dp(14), dp(12), dp(10), dp(12)) }
                val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                txt.addView(tv(r.text, 18f, T.text, true).apply { maxLines = 3 })
                txt.addView(tv(r.note.title.ifBlank { L.t("untitled") }, 13f, T.muted))
                row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // ✅ un botón grande de "Hecho"
                val ok = tv("✓", 28f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = roundBg(0xFF30D158.toInt(), dp(28).toFloat()) }
                ok.setOnClickListener { v ->
                    Anim.bump(v, 1.3f); Anim.sparkle(v, 40f, 600); v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    row.animate().alpha(0f).translationX(row.width.toFloat()).setStartDelay(250).setDuration(300).withEndAction {
                        Pending.markDone(r) { Pending.celebrate(this, r); loadTodo() }
                    }.start()
                }
                row.addView(ok, LinearLayout.LayoutParams(dp(56), dp(56)))
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                Anim.enter(row, 200L + i * 40, 20f)
            }
        }
    }

    /* ---------- 🎙️ dictar una nota nueva ---------- */
    private fun dictate() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC); return }
        val said = StringBuilder()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(10), dp(20), dp(6)) }
        val mic = tv("🎙️", 54f).apply { gravity = Gravity.CENTER }
        box.addView(mic)
        val pulse = android.animation.ValueAnimator.ofFloat(1f, 1.18f).apply { addUpdateListener { val k = it.animatedValue as Float; mic.scaleX = k; mic.scaleY = k }; duration = 650; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE; start() }
        val live = tv(L.t("listening"), 20f, T.text).apply { setPadding(0, dp(14), 0, 0); minHeight = dp(120) }
        box.addView(live)
        fun paint(p: String) { live.text = (said.toString() + " " + p).trim().ifEmpty { L.t("listening") } }
        val d = Dictation(this, if (L.lang == "en") "en" else "es", onPartial = { paint(it) }, onFinal = { t -> if (said.isNotEmpty()) said.append(' '); said.append(t.trim()); paint("") }, onState = {})
        if (!d.available()) { toast(L.t("noDictation")); pulse.cancel(); return }
        dict = d; d.start()
        dialog().setTitle("🎙️ " + L.t("workDictate")).setView(box).setCancelable(false)
            .setPositiveButton(L.t("save")) { _, _ ->
                d.stop(); pulse.cancel(); dict = null
                val t = said.toString().trim()
                if (t.isEmpty()) { toast(L.t("vrepEmpty")); return@setPositiveButton }
                val first = t.split(Regex("(?<=[.!?])\\s")).first().take(50)
                Store.createNote(first, mutableListOf(Block.Text(mutableListOf(Run(t)))))
                Store.changed(); toast("✅ " + L.t("workSaved")); Confetti.burst(this)
            }
            .setNegativeButton(L.t("cancel")) { _, _ -> d.stop(); pulse.cancel(); dict = null }.show()
    }

    /* ---------- 📷 foto rápida ---------- */
    private fun photo(toLast: Boolean) {
        camToLast = toLast
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAM_PERM); return }
        val f = File(cacheDir, "trabajo_" + System.currentTimeMillis() + ".jpg"); camFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.archivos", f)
        try { startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_CAM) } catch (_: Exception) { toast(L.t("cantOpen")) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_CAM_PERM && ok) photo(camToLast)
        if (requestCode == REQ_MIC) { if (ok) dictate() else toast(L.t("micPerm")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAM) return
        val f = camFile ?: return
        if (resultCode != RESULT_OK || f.length() == 0L) { f.delete(); return }
        Loader.pool.execute {
            val r = try { Photos.import(this, android.net.Uri.fromFile(f)) } catch (_: Throwable) { null }
            f.delete()
            runOnUiThread {
                if (r == null) { toast(L.t("photoFail")); return@runOnUiThread }
                val stamp = "📅 " + java.text.SimpleDateFormat("EEE d MMM yyyy, h:mm a", L.locale).format(java.util.Date())
                val img = Block.Img(r.file, 90f, "", 5f, 0f, r.w, r.h, stamp, 0f, System.currentTimeMillis())
                val last = Store.notes.filter { !it.deleted && !it.locked }.maxByOrNull { it.updated }
                if (camToLast && last != null) {
                    Store.io.execute {
                        val (body, extra) = Store.readBody(last.id)
                        val blocks = blocksFromJson(body); blocks.add(img); blocks.add(Block.Text())
                        Store.main.post {
                            last.updated = System.currentTimeMillis()
                            Store.fillMeta(last, blocks); Store.saveNote(last, blocksJson(blocks), extra); Store.changed()
                            toast("📷 " + L.t("workAdded", "t" to last.title.ifBlank { L.t("untitled") }))
                        }
                    }
                } else {
                    Store.createNote("📷 " + java.text.SimpleDateFormat("d MMM, h:mm a", L.locale).format(java.util.Date()), mutableListOf(img, Block.Text()))
                    Store.changed(); toast("📷 " + L.t("workSaved"))
                }
                Anim.sparkle(window.decorView, 80f, 700)
            }
        }
    }
}
