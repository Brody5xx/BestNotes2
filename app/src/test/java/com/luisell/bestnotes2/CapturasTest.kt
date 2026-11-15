package com.luisell.bestnotes2

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
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
}
