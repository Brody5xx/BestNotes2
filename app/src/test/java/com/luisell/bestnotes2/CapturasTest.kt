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
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
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
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1")).setup().visible().get()
        waitIo(); idle(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        shot(act.window.decorView, "3_nota")
        val ed = act.window.decorView.let { v -> fun find(x: View): EditorView? { if (x is EditorView) return x; if (x is android.view.ViewGroup) for (i in 0 until x.childCount) find(x.getChildAt(i))?.let { return it }; return null }; find(v)!! }
        val foto = ed.views.first { it is FotoView } as FotoView
        ed.selectImage(foto); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        shot(act.window.decorView, "4_foto")
    }
}
