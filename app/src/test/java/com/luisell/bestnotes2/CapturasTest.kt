package com.luisell.bestnotes2

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Fotos de las pantallas (solo para revisar el diseño aquí; no se corre en GitHub). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapturasTest {
    private val ctx get() = org.robolectric.RuntimeEnvironment.getApplication()
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun waitIo() { val l = java.util.concurrent.CountDownLatch(1); Store.io.execute { l.countDown() }; l.await(); idle() }

    /** Al terminar cada prueba se olvidan las pantallas viejas (si no, Android las "espera" y se traba la siguiente). */
    @org.junit.After fun limpiar() { (Store::class.java.getDeclaredField("listeners").apply { isAccessible = true }.get(Store) as MutableList<*>).clear() }

    private fun shot(v: View, name: String) {
        val w = 1080; val h = 2340
        if (v.width != w) { v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, h) }
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        // la ventana pinta el fondo
        Glass.background(ctx).apply { setBounds(0, 0, w, h); draw(c) }
        v.draw(c)
        File("/tmp/capturas").mkdirs()
        File("/tmp/capturas/$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun capturas() {
        if (System.getenv("CAPTURAS") == null) return
        var ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val f = File(ctx.cacheDir, "r.json"); f.writeText(javaClass.classLoader!!.getResource("respaldo.json")!!.readText())
        Backup.importFile(ctx, Uri.fromFile(f), "prueba") {}
        ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        waitIo(); idle(); Thread.sleep(200); waitIo(); idle()
        shot(main.window.decorView, "1_inicio")
        val open = MainActivity::class.java.getDeclaredMethod("openDrawer").apply { isAccessible = true }
        open.invoke(main); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        shot(main.window.decorView, "2_menu")
        MainActivity::class.java.getDeclaredMethod("closeDrawer").apply { isAccessible = true }.invoke(main)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        MainActivity::class.java.getDeclaredMethod("appearance").apply { isAccessible = true }.invoke(main)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(main.window.decorView, "2b_apariencia")
        Sheet.closeTop(main); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1")).setup().visible().get()
        waitIo(); idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        shot(act.window.decorView, "3_nota")
        // escribir algo para que el registro tenga renglones
        act.window.decorView.let { v -> fun f(x: View): RichEdit? { if (x is RichEdit) return x; if (x is android.view.ViewGroup) for (i in 0 until x.childCount) f(x.getChildAt(i))?.let { return it }; return null }; f(v) }?.let { e ->
            e.requestFocus(); e.text.append(" nuevo"); idle()
            NoteActivity::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(act); waitIo()
            e.text.replace(0, e.text.length, "Otra cosa"); idle()
            NoteActivity::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(act); waitIo()
        }
        val ed = act.window.decorView.let { v -> fun find(x: View): EditorView? { if (x is EditorView) return x; if (x is android.view.ViewGroup) for (i in 0 until x.childCount) find(x.getChildAt(i))?.let { return it }; return null }; find(v)!! }
        val foto = ed.views.first { it is FotoView } as FotoView
        ed.selectImage(foto); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        shot(act.window.decorView, "4_foto")
        val gf = FotoView::class.java.getDeclaredField("glow").apply { isAccessible = true }
        val g = gf.get(foto) as android.graphics.drawable.Drawable?
        ed.selectImage(null); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        NoteActivity::class.java.getDeclaredMethod("options").apply { isAccessible = true }.invoke(act)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(act.window.decorView, "10_menu")
        Sheet.closeTop(act); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        NoteActivity::class.java.getDeclaredMethod("showHistory").apply { isAccessible = true }.invoke(act)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(act.window.decorView, "11_historial")
        Sheet.closeTop(act); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        NoteActivity::class.java.getDeclaredMethod("viewLog").apply { isAccessible = true }.invoke(act)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(act.window.decorView, "11b_registro")
        Sheet.closeTop(act); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        NoteActivity::class.java.getDeclaredMethod("wallMenu").apply { isAccessible = true }.invoke(act)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(act.window.decorView, "12_fondo")
        Sheet.closeTop(act); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        NoteActivity::class.java.getDeclaredMethod("colorMenu").apply { isAccessible = true }.invoke(act)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(act.window.decorView, "13_estilo")
        act.pickColor("Color", 0xFF0A84FF.toInt(), T.textColors) {}
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(act.window.decorView, "14_rueda")
        Sheet.closeTop(act); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        act.pickSize(17) {}
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(act.window.decorView, "15_tamano")
        Sheet.closeTop(act); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val tf = NoteActivity::class.java.getDeclaredField("title").apply { isAccessible = true }.get(act) as TitleEdit
        tf.requestFocus(); idle()
        tf.setText(Rich.build(listOf(Run("Ticket ", St.PLAIN.copy(color = 0xFFFFD60A.toInt(), o = 0xFFFF375F.toInt())), Run("de prueba", St.PLAIN.copy(color = 0xFF64D2FF.toInt())))))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        shot(act.window.decorView, "16_titulo")
        NoteActivity::class.java.getDeclaredField("dirty").apply { isAccessible = true }.setBoolean(act, true)
        NoteActivity::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(act); waitIo()
        val (_, ex) = Store.readBody("n1")
        org.junit.Assert.assertTrue("título con estilos guardado", ex!!.contains("\"tr\""))
        val main2 = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        waitIo(); idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        shot(main2.window.decorView, "17_inicio")
        val sf = MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (sf.get(main2) as MutableSet<String>).addAll(listOf("n1", "n2"))
        MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(main2)
        main2.shareNotes(listOf("n1", "n2"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        shot(main2.window.decorView, "18_compartir")
        Sheet.closeTop(main2); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        MainActivity::class.java.getDeclaredMethod("trashSnack", List::class.java).apply { isAccessible = true }.invoke(main2, listOf("n2"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        shot(main2.window.decorView, "19_deshacer")
    }

    @Test fun cierreDelMenu() {
        if (System.getenv("CAPTURAS") == null) return
        var ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        waitIo(); idle()
        val v = main.window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2340)
        MainActivity::class.java.getDeclaredMethod("openDrawer").apply { isAccessible = true }.invoke(main)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2340)
        // se arma la animación a mano, cuadro por cuadro
        val box = (main.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.view.ViewGroup).let { it.getChildAt(it.childCount - 1) as android.view.ViewGroup }
        val holder = box.getChildAt(1) as android.view.ViewGroup
        holder.pivotX = 0f; holder.pivotY = holder.height / 2f
        val line = box.getChildAt(2); line.pivotY = line.height / 2f
        val flash = box.getChildAt(3)
        flash.translationX = 30f - 180f + 8f; flash.translationY = 30f + holder.height / 2f - 180f
        val parts = MainActivity.CloseParts(holder, holder.getChildAt(1), line, flash, holder.width.toFloat(), 30f)
        for ((i, t) in listOf(0.2f, 0.4f, 0.55f, 0.72f, 0.84f, 0.9f).withIndex()) {
            main.closeFrame(parts, t); shot(v, "cierre_$i")
        }
    }

    @Test fun capturasCalcYBoveda() {
        if (System.getenv("CAPTURAS") == null) return
        val ca = Robolectric.buildActivity(CalcActivity::class.java).setup().visible().get()
        idle()
        val tab = CalcActivity::class.java.getDeclaredMethod("setTab", String::class.java).apply { isAccessible = true }
        val ins = CalcActivity::class.java.getDeclaredMethod("insert", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val ent = CalcActivity::class.java.getDeclaredMethod("enter").apply { isAccessible = true }
        ins.invoke(ca, "2+3×4", true); ent.invoke(ca); ins.invoke(ca, "√(81", true); idle()
        shot(ca.window.decorView, "5_calc")
        tab.invoke(ca, "y"); ins.invoke(ca, "x²−4", true); idle()
        tab.invoke(ca, "graph"); idle(); shot(ca.window.decorView, "6_grafica")
        tab.invoke(ca, "table"); idle(); shot(ca.window.decorView, "6b_tabla")
        tab.invoke(ca, "hrs"); idle(); shot(ca.window.decorView, "7_horas")
        tab.invoke(ca, "fx"); idle(); shot(ca.window.decorView, "8_divisas")
        VaultBox.wipe(ctx)
        val va = Robolectric.buildActivity(VaultActivity::class.java).setup().visible().get(); idle()
        shot(va.window.decorView, "9_boveda")
    }

    @Test fun capturasClaro() {
        if (System.getenv("CAPTURAS") == null) return
        var ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val f = File(ctx.cacheDir, "r.json"); f.writeText(javaClass.classLoader!!.getResource("respaldo.json")!!.readText())
        Backup.importFile(ctx, Uri.fromFile(f), "prueba") {}
        ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        T.light = true
        try {
            val main = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
            waitIo(); idle(); Thread.sleep(200); waitIo(); idle()
            shot(main.window.decorView, "20_claro_inicio")
            MainActivity::class.java.getDeclaredMethod("appearance").apply { isAccessible = true }.invoke(main)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
            shot(main.window.decorView, "21_claro_apariencia")
            val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1")).setup().visible().get()
            waitIo(); idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            shot(act.window.decorView, "22_claro_nota")
            NoteActivity::class.java.getDeclaredMethod("options").apply { isAccessible = true }.invoke(act)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
            shot(act.window.decorView, "23_claro_menu")
            Sheet.closeTop(act); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            NoteActivity::class.java.getDeclaredMethod("openTemplates").apply { isAccessible = true }.invoke(act)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
            shot(act.window.decorView, "24_claro_plantillas")
            val ca = Robolectric.buildActivity(CalcActivity::class.java).setup().visible().get(); idle()
            shot(ca.window.decorView, "25_claro_calc")
        } finally { T.light = false }
        // 🌍 derecha a izquierda (árabe)
        L.set(ctx, "ar")
        try {
            val m2 = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
            waitIo(); idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            shot(m2.window.decorView, "26_rtl")
        } finally { L.set(ctx, "auto") }
    }

    @Test fun capturasVentanas() {
        if (System.getenv("CAPTURAS") == null) return
        var ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get(); idle()
        fun last(name: String) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
            val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()!!
            shot(d.window!!.decorView, name); d.dismiss(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        }
        MainActivity::class.java.getDeclaredMethod("transferMenu").apply { isAccessible = true }.invoke(main)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500)); shot(main.window.decorView, "36_transferir"); Sheet.closeTop(main); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        main.confirm("Vaciar papelera", "Se borrarán 3 notas para siempre.", "Vaciar", true) {}; last("30_confirmar")
        main.dialog().setTitle("Mover a carpeta").setSingleChoiceItems(arrayOf("Sin carpeta", "Ideas", "Recetas"), 1) { _, _ -> }.setNegativeButton("Cancelar", null).show(); last("31_lista")
        Pin.create(main) {}; last("32_pin")
        main.askText("Nueva carpeta", hint = "Nombre") {}; last("35_texto")
        main.askText("Contraseña del archivo", "secreta123", password = true) {}
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        org.robolectric.shadows.ShadowDialog.getLatestDialog()!!.window!!.decorView.let { dv ->
            fun <T> find(x: View, c: Class<T>): List<T> { val o = ArrayList<T>(); if (c.isInstance(x)) o.add(c.cast(x)); if (x is android.view.ViewGroup) for (k in 0 until x.childCount) o.addAll(find(x.getChildAt(k), c)); return o }
            shot(dv, "37_pass_oculta")
            find(dv, IconView::class.java).first { it.kind == Icons.EYE }.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        }
        last("38_pass_visible")
        T.light = true
        try { Pin.change(main) {}; last("33_pin_claro"); main.confirm("¿Borrar?", "No se puede deshacer.", "Borrar", true) {}; last("34_confirmar_claro") } finally { T.light = false }
    }

    @Test fun capturasNuevas() {
        if (System.getenv("CAPTURAS") == null) return
        var ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val f = File(ctx.cacheDir, "r.json"); f.writeText(javaClass.classLoader!!.getResource("respaldo.json")!!.readText())
        Backup.importFile(ctx, Uri.fromFile(f), "prueba") {}
        ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        // 🕘 línea de tiempo con 3 ratos de trabajo
        var (body, _) = Store.readBody("n1")
        val b0 = blocksFromJson(body)
        val s0 = Timeline.snap("Ticket de prueba", null, blocksJson(b0), null)
        val b1 = b0.toMutableList().apply { add(Block.Text(mutableListOf(Run("Cambiar la batería del control\nRevisar el cable de la puerta 2")))) }
        val s1 = Timeline.snap("Ticket de prueba", null, blocksJson(b1), null)
        val b2 = b1.filter { it !is Block.Img }.toMutableList().apply { add(Block.ListB("check", mutableListOf(Item(mutableListOf(Run("Llevar escalera")), false)))) }
        val s2 = Timeline.snap("Ticket de prueba", null, blocksJson(b2), null)
        val now = System.currentTimeMillis()
        Timeline.clear(ctx, "n1")
        Timeline.record(ctx, "n1", now - 26 * 3600_000L, s0, s1)
        Timeline.record(ctx, "n1", now - 3600_000L, s1, s2)
        Timeline.record(ctx, "n1", now - 600_000L, s2, Timeline.snap("Ticket de prueba", runsJson(listOf(Run("Ticket de prueba", St(color = 0xFFFF5252.toInt())))), blocksJson(b2), null))
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1")).setup().visible().get()
        waitIo(); idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        NoteActivity::class.java.getDeclaredMethod("showHistory").apply { isAccessible = true }.invoke(act)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1800))
        shot(act.window.decorView, "40_linea")
        Sheet.closeTop(act); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val o = Timeline.load(ctx, "n1")[1]
        val d = Timeline.diff(o.getJSONObject("pre"), o.getJSONObject("post"))
        NoteActivity::class.java.getDeclaredMethod("viewSession", org.json.JSONObject::class.java, Timeline.Diff::class.java).apply { isAccessible = true }.invoke(act, o, d)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1800))
        shot(act.window.decorView, "41_cambios")
        // 📄 escáner con una "foto" de un papel chueco
        val bm = android.graphics.Bitmap.createBitmap(1200, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bm).apply {
            drawColor(0xFF5D4037.toInt())
            val paper = android.graphics.Path().apply { moveTo(260f, 220f); lineTo(1010f, 330f); lineTo(930f, 1420f); lineTo(170f, 1300f); close() }
            drawPath(paper, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEBE6.toInt() })
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textSize = 46f }
            save(); rotate(8f, 600f, 800f)
            for (k in 0 until 9) drawText("Línea de ejemplo número ${k + 1}", 300f, 470f + k * 95f, tp)
            restore()
        }
        val r = Photos.saveNew(bm)
        val sa = Robolectric.buildActivity(ScanActivity::class.java, Intent(ctx, ScanActivity::class.java).putExtra(ScanActivity.FILE, r.file)).setup().visible().get()
        Thread.sleep(1500); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200))
        shot(sa.window.decorView, "42_escaner")
        ScanActivity::class.java.getDeclaredMethod("flatten").apply { isAccessible = true }.invoke(sa)
        repeat(30) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        shot(sa.window.decorView, "43_escaneado")
        // 🏠 widget
        val v = android.widget.RemoteViews(ctx.packageName, R.layout.widget)
        try {
            val m = NotesWidget.Companion::class.java  // solo para que se cargue
            val box = android.widget.FrameLayout(ctx)
            val w = v.apply(ctx, box)
            NotesWidget.pick().forEachIndexed { i, n -> w.findViewById<android.widget.TextView>(listOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3)[i]).text = (if (n.pinned) "📌 " else "") + n.title.ifBlank { "Sin título" }; w.findViewById<android.widget.TextView>(listOf(R.id.s0, R.id.s1, R.id.s2, R.id.s3)[i]).text = n.plain.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
            box.addView(w, android.widget.FrameLayout.LayoutParams(1000, 640))
            box.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY)); box.layout(0, 0, 1080, 700)
            val out = android.graphics.Bitmap.createBitmap(1080, 700, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply { drawColor(0xFF3A5A7A.toInt()); box.draw(this) }
            File("/tmp/capturas/44_widget.png").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    @Test fun capturasFotoGirada() {
        if (System.getenv("CAPTURAS") == null) return
        var ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val f = File(ctx.cacheDir, "r.json"); f.writeText(javaClass.classLoader!!.getResource("respaldo.json")!!.readText())
        Backup.importFile(ctx, Uri.fromFile(f), "prueba") {}
        ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1")).setup().visible().get()
        waitIo(); idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        fun <T> find(x: View, c: Class<T>): T? { if (c.isInstance(x)) return c.cast(x); if (x is android.view.ViewGroup) for (k in 0 until x.childCount) find(x.getChildAt(k), c)?.let { return it }; return null }
        val ed = find(act.window.decorView, EditorView::class.java)!!
        val foto = ed.views.filterIsInstance<FotoView>().first()
        ed.selectImage(foto); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        // 🤏 girar con dos dedos: la foto debe seguir a los dedos sin brincar
        val box = FotoView::class.java.getDeclaredField("box").apply { isAccessible = true }.get(foto) as View
        val touch = box.let { v -> val f2 = View::class.java.getDeclaredMethod("getListenerInfo").apply { isAccessible = true }.invoke(v); f2.javaClass.getDeclaredField("mOnTouchListener").apply { isAccessible = true }.get(f2) as View.OnTouchListener }
        val cx = box.width / 2f; val cy = box.height / 2f; val rad = box.width / 3f
        fun send(action: Int, n: Int, deg: Double) {
            // dónde están los dedos en la pantalla → coordenadas dentro de la foto (como lo hace Android)
            val scr = floatArrayOf((cx - rad * Math.cos(Math.toRadians(deg))).toFloat(), (cy - rad * Math.sin(Math.toRadians(deg))).toFloat(), (cx + rad * Math.cos(Math.toRadians(deg))).toFloat(), (cy + rad * Math.sin(Math.toRadians(deg))).toFloat())
            val inv = android.graphics.Matrix(); box.matrix.invert(inv); inv.mapPoints(scr)
            val props = Array(n) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val co = Array(n) { MotionEvent.PointerCoords().apply { x = scr[it * 2]; y = scr[it * 2 + 1]; pressure = 1f; size = 1f } }
            touch.onTouch(box, MotionEvent.obtain(0, 0, action, n, props, co, 0, 0, 1f, 1f, 0, 0, 0, 0))
        }
        send(MotionEvent.ACTION_DOWN, 1, 0.0)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0.0)
        val seen = ArrayList<Float>()
        for (k in 1..20) { send(MotionEvent.ACTION_MOVE, 2, k * 2.0); idle(); seen.add(foto.block.rot) }
        System.err.println("GIRO " + seen)
        org.junit.Assert.assertEquals("la foto debe quedar girada 40°", 40f, foto.block.rot, 1.5f)
        for (i in 1 until seen.size) org.junit.Assert.assertTrue("no debe brincar: $seen", seen[i] >= seen[i - 1] - 0.5f)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 40.0)
        foto.setRot(0f); idle()
        for ((deg, name) in listOf(30f to "50_girada30", 90f to "51_girada90")) {
            foto.setRot(deg); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
            shot(act.window.decorView, name)
        }
    }

    @Test fun capturasTextoAlrededor() {
        if (System.getenv("CAPTURAS") == null) return
        var ok = false; Store.reload { ok = true }; while (!ok) { Thread.sleep(10); idle() }
        val bm = android.graphics.Bitmap.createBitmap(600, 450, android.graphics.Bitmap.Config.ARGB_8888); android.graphics.Canvas(bm).drawColor(0xFF3949AB.toInt())
        val r = Photos.saveNew(bm)
        val m = Store.createNote("Alrededor", mutableListOf(
            Block.Img(r.file, 45f, "l", 0f, 40f, 600, 450, beside = mutableListOf(Run("Texto al lado de la foto que baja junto con ella"))),
            Block.Text(mutableListOf(Run("Texto debajo de la foto")))))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        shot(act.window.decorView, "60_alrededor")
        fun <T> find(x: View, c: Class<T>): T? { if (c.isInstance(x)) return c.cast(x); if (x is android.view.ViewGroup) for (k in 0 until x.childCount) find(x.getChildAt(k), c)?.let { return it }; return null }
        val ed = find(act.window.decorView, EditorView::class.java)!!
        org.junit.Assert.assertTrue("arriba de la foto debe haber un renglón para escribir", ed.blocks.first() is Block.Text)
    }
}
