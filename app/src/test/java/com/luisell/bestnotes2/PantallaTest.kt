package com.luisell.bestnotes2

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pruebas de pantalla: importar, abrir notas y escribir (sin teléfono). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PantallaTest {
    private val ctx get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun waitIo() {
        val done = java.util.concurrent.CountDownLatch(1)
        Store.io.execute { done.countDown() }
        done.await(); idle()
    }
    private fun reload() {
        var ok = false
        Store.reload { ok = true }
        var n = 0
        while (!ok && n++ < 500) { Thread.sleep(10); idle() }
        assertTrue("la lista se cargó", ok)
    }

    private fun importar(): Backup.Summary {
        val f = File(ctx.cacheDir, "r.json")
        f.writeText(javaClass.classLoader!!.getResource("respaldo.json")!!.readText())
        try { Backup.importFile(ctx, Uri.fromFile(f), "mala") {}; fail("debía fallar") } catch (_: Backup.WrongPassword) {}
        val s = Backup.importFile(ctx, Uri.fromFile(f), "prueba") {}
        reload()
        return s
    }

    private fun <T : View> find(v: View, cls: Class<T>, out: MutableList<T> = ArrayList()): MutableList<T> {
        if (cls.isInstance(v)) out.add(cls.cast(v)!!)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), cls, out)
        return out
    }

    @Test fun importaTodo() {
        reload(); Store.deleteForever(Store.notes.map { it.id }); waitIo()
        val s = importar()
        assertEquals(6, s.total); assertEquals(6, s.written); assertEquals(0, s.missing)
        val n1 = Store.meta("n1")!!
        assertEquals("Ticket de prueba", n1.title)
        assertEquals(0xFFFF453A.toInt(), n1.titleColor)
        assertTrue(n1.fav && n1.pinned); assertEquals("f1", n1.folder)
        assertEquals(0xFF43A047.toInt(), n1.color)
        assertNotNull(n1.thumb); assertTrue(File(Store.mediaDir, n1.thumb!!).length() > 50)
        assertEquals(1, n1.checkDone); assertEquals(2, n1.checkTotal)
        assertTrue(n1.plain.contains("cámara"))
        val (body, _) = Store.readBody("n1")
        val blocks = blocksFromJson(body)
        val img = blocks.first { it is Block.Img } as Block.Img
        assertEquals("l", img.side); assertEquals(40f, img.pct); assertEquals("14 nov 2026 · Sitio A", img.stamp)
        assertEquals("el texto de al lado de la foto", "Texto al lado", runsPlain(img.beside))
        assertTrue(Store.meta("n2")!!.hasMedia)
        assertTrue(Store.meta("n3")!!.deleted)
        assertTrue(Store.meta("n4")!!.locked)
        assertEquals("linea uno\nlinea dos", Store.meta("n6")!!.plain)
        assertTrue(Store.readBody("n5").second!!.contains("ink"))
        assertEquals("Trabajo", Store.folders.first { it.id == "f1" }.name)
        assertEquals("co7naf", Pin.saved(ctx))
        // importar otra vez no duplica nada
        val again = importar()
        assertEquals(0, again.written)
        assertEquals(6, Store.notes.size)
    }

    @Test fun pantallaPrincipalYNota() {
        importar()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val rv = find(main.window.decorView, androidx.recyclerview.widget.RecyclerView::class.java).first()
        assertEquals(6, rv.adapter!!.itemCount)           // la tarjeta de pendientes + todas menos la de la papelera

        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1").putExtra(NoteActivity.EXTRA_Q, "camara")).setup().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        assertTrue(ed.views.size >= 4)
        val first = ed.views[0] as TextoView
        assertTrue("búsqueda iluminada", first.edit.text.getSpans(0, first.edit.length(), SearchS::class.java).isNotEmpty())
        // escribir y poner en negrita
        first.edit.requestFocus(); first.edit.setSelection(0)
        first.edit.text.insert(0, "Hola ")
        first.edit.setSelection(0, 4)
        first.edit.applyStyle { it.copy(b = true) }
        assertTrue(Rich.runs(first.edit.text)[0].st.b)
        // convertir el renglón en checklist
        first.edit.setSelection(2)
        ed.toggleList("check")
        idle()
        assertTrue(ed.blocks[0] is Block.ListB)
        // Enter en la lista crea otro renglón
        val lv = ed.views[0] as ListaView
        val e0 = lv.edits()[0]
        e0.setSelection(4); e0.text.insert(4, "\n")
        idle()
        assertEquals(2, (ed.blocks[0] as Block.ListB).items.size)
        // guardar
        act.finish(); waitIo()
        val saved = blocksFromJson(Store.readBody("n1").first)
        assertTrue(saved[0] is Block.ListB)
        assertEquals("Hola", runsPlain((saved[0] as Block.ListB).items[0].runs))
        assertTrue((saved[0] as Block.ListB).items[0].runs[0].st.b)
    }

    @Test fun notaNuevaChecklist() {
        reload()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_NEW, "check")).setup().get()
        idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val lv = ed.views[0] as ListaView
        val e = lv.edits()[0]
        e.text.insert(0, "Comprar cable")
        e.setSelection(e.length()); e.text.insert(e.length(), "\n"); idle()
        val e2 = lv.edits().last(); e2.text.insert(0, "Switch"); idle()
        // Enter en renglón vacío termina la lista
        e2.setSelection(e2.length()); e2.text.insert(e2.length(), "\n"); idle()
        val e3 = lv.edits().last(); e3.setSelection(0); e3.text.insert(0, "\n"); idle()
        act.finish(); waitIo()
        val n = Store.notes.maxByOrNull { it.created }!!
        assertEquals("Comprar cable\nSwitch", n.plain.trim())
        assertEquals(2, n.checkTotal)
    }

    @Test fun buscarYDeshacer() {
        reload(); Store.deleteForever(Store.notes.map { it.id }); waitIo()
        importar()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val search = find(main.window.decorView, android.widget.EditText::class.java).first()
        search.setText("CAMARA"); idle()
        val rv = find(main.window.decorView, androidx.recyclerview.widget.RecyclerView::class.java).first()
        assertEquals(1, rv.adapter!!.itemCount)
        search.setText("wifi"); idle()
        assertEquals("la nota bloqueada no se busca por dentro", 0, rv.adapter!!.itemCount)
        search.setText("#trabajo"); idle()
        assertEquals(1, rv.adapter!!.itemCount)

        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n6")).setup().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val t = ed.views[0] as TextoView
        t.edit.requestFocus(); t.edit.setSelection(t.edit.length())
        t.edit.text.insert(t.edit.length(), " extra")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals("linea uno\nlinea dos extra", t.edit.text.toString())
        val undo = act.javaClass.getDeclaredMethod("undo").apply { isAccessible = true }
        undo.invoke(act); idle()
        val t2 = ed.views[0] as TextoView
        assertEquals("linea uno\nlinea dos", t2.edit.text.toString())
        // borrar al principio de un renglón de checklist lo vuelve texto
        ed.views[0].focusAt(0, 0); idle()
        ed.toggleList("check"); idle()
        val lv = ed.views.first { it is ListaView } as ListaView
        val e = lv.edits()[0]; e.requestFocus(); e.setSelection(0)
        e.onBackAtStart!!.invoke(e); idle()
        assertTrue(ed.blocks.none { it is Block.ListB })
        act.finish(); waitIo()
    }

    @Test fun fotos() {
        reload(); Store.deleteForever(Store.notes.map { it.id }); waitIo()
        importar()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n6")).setup().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val t = ed.views[0] as TextoView
        t.edit.requestFocus(); t.edit.setSelection(9)               // después de "linea uno"
        val name = Store.meta("n1")!!.thumb!!
        ed.insertImages(listOf(Block.Img(name, 50f, "", 25f, 0f, 40, 30, "📅 sello", 0f, 1L)))
        idle()
        assertEquals(listOf("Text", "Img", "Text"), ed.blocks.map { it.javaClass.simpleName })
        assertEquals("linea uno", runsPlain((ed.blocks[0] as Block.Text).runs))
        assertEquals("linea dos", runsPlain((ed.blocks[2] as Block.Text).runs))
        val f = ed.views[1] as FotoView
        ed.selectImage(f); idle()
        assertTrue(f.selected)
        // a un lado: aparece la caja de texto al lado
        val m = FotoView::class.java.getDeclaredMethod("setSide", String::class.java).apply { isAccessible = true }
        m.invoke(f, "l"); idle()
        assertEquals(1, f.edits().size)
        f.edits()[0].setText("al lado")
        ed.moveBlock(f, -1); idle()
        assertTrue(ed.blocks[0] is Block.Img)
        assertEquals("al lado", runsPlain((ed.blocks[0] as Block.Img).beside))
        // quitar la foto: el texto de al lado se queda
        ed.removeImage(ed.views[0] as FotoView); idle()
        assertTrue(ed.blocks.none { it is Block.Img })
        assertTrue(blocksPlain(ed.blocks).startsWith("al lado"))
        // contorno y tamaño se guardan
        val t2 = ed.views[0] as TextoView
        t2.edit.requestFocus(); t2.edit.setSelection(0, 2)
        t2.edit.applyStyle { it.copy(o = 0xFFFF7A2A.toInt(), size = 64) }
        act.finish(); waitIo()
        val saved = blocksFromJson(Store.readBody("n6").first)
        val r0 = (saved[0] as Block.Text).runs[0]
        assertEquals(0xFFFF7A2A.toInt(), r0.st.o); assertEquals(64, r0.st.size)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun fotoDeVerdad() {
        reload()
        val png = File(ctx.cacheDir, "p.png")
        val bm = android.graphics.Bitmap.createBitmap(300, 200, android.graphics.Bitmap.Config.ARGB_8888)
        png.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val r = Photos.import(ctx, Uri.fromFile(png))
        assertNotNull("la foto se pudo poner", r)
        assertEquals(300, r!!.w); assertEquals(200, r.h)
        assertTrue(File(Store.mediaDir, r.file).length() > 0)
        val rot = Photos.rotate90(r.file)!!
        assertEquals(200, rot.w); assertEquals(300, rot.h)
        val c = Photos.crop(r.file, 0f, 0f, 0.5f, 0.5f)!!
        assertEquals(150, c.w)
    }

    @Test fun dibujoAudioYVideo() {
        reload()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_NEW, "draw")).setup().get()
        idle()
        val ink = find(act.window.decorView, InkView::class.java).first()
        assertTrue("empieza en modo dibujo", ink.drawing)
        ink.layout(0, 0, 1000, 2000)
        fun ev(a: Int, x: Float, y: Float) = android.view.MotionEvent.obtain(0, 0, a, x, y, 0).also { ink.dispatchTouchEvent(it); it.recycle() }
        ev(android.view.MotionEvent.ACTION_DOWN, 100f, 100f); ev(android.view.MotionEvent.ACTION_MOVE, 200f, 150f); ev(android.view.MotionEvent.ACTION_MOVE, 300f, 220f); ev(android.view.MotionEvent.ACTION_UP, 300f, 220f)
        assertEquals(1, ink.strokes.size)
        val ed = find(act.window.decorView, EditorView::class.java).first()
        ed.insertImages(listOf(Block.Media("audio", "noexiste.m4a", 12.0, 1000), Block.Media("video", "noexiste.mp4", 3.0, 5000)))
        idle()
        assertTrue(ed.views.any { it is MediaView }); assertTrue(ed.views.any { it is VideoCard })
        act.finish(); waitIo()
        val n = Store.notes.maxByOrNull { it.created }!!
        val ex = org.json.JSONObject(Store.readBody(n.id).second!!)
        assertEquals(1, ex.getJSONArray("ink").length())
        val back = Stroke.listFrom(ex.getJSONArray("ink"))
        assertEquals(3, back[0].p.size)
        assertTrue(n.hasMedia)
    }

    @Test fun pendientesYCompartir() {
        reload(); Store.deleteForever(Store.notes.map { it.id }); waitIo()
        importar()
        var rows: List<Pending.Row> = emptyList()
        Pending.load { rows = it }
        waitIo(); idle()
        assertEquals(listOf("Cable"), rows.map { it.text })
        Pending.markDone(rows[0]) {}
        waitIo(); idle(); waitIo()
        assertEquals(2, Store.meta("n1")!!.checkDone)
        val blocks = blocksFromJson(Store.readBody("n1").first)
        val txt = Share.text("Ticket", blocks)
        assertTrue(txt.contains("☑ Cable") && txt.contains("☑ Switch"))
        assertEquals("Ticket de prueba.pdf", Share.fileName("Ticket de prueba", "pdf"))
        // abrir la nota y compartir como imagen y PDF no se cae
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1")).setup().get()
        waitIo(); idle()
        val page = act.javaClass.getDeclaredField("page").apply { isAccessible = true }.get(act) as android.view.View
        page.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        page.layout(0, 0, 1080, page.measuredHeight)
        val img = Share.image(act, page, minOf(page.height, 1500), T.bg, "Ticket")
        assertTrue(img.length() > 0)          // (el PDF no se puede probar aquí: Robolectric no hace PDFs)
        // recordatorio guardado
        Reminders.add(ctx, "n1", "Cable", System.currentTimeMillis() + 3_600_000)
        assertEquals(1, Reminders.forNote(ctx, "n1").size)
        assertEquals("co7naf", Pin.hash("1234"))
    }

    @Test fun moverFotoAlLado() {
        reload(); Store.deleteForever(Store.notes.map { it.id }); waitIo()
        importar()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n6")).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        ed.views[0].focusAt(-1, -1)
        ed.insertImages(listOf(Block.Img(Store.meta("n1")!!.thumb!!, 40f, "", 30f, 0f, 40, 30)))
        idle()
        val f = ed.views.first { it is FotoView } as FotoView
        val root = act.window.decorView
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(2200, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2200); idle()
        val box = FotoView::class.java.getDeclaredField("box").apply { isAccessible = true }.get(f) as android.view.View
        fun ev(a: Int, x: Float, y: Float) = android.view.MotionEvent.obtain(0, 0, a, x, y, 0).also { box.dispatchTouchEvent(it); it.recycle() }
        ev(android.view.MotionEvent.ACTION_DOWN, 50f, 50f); ev(android.view.MotionEvent.ACTION_UP, 50f, 50f); idle()
        assertTrue("se selecciona", f.selected)
        ev(android.view.MotionEvent.ACTION_DOWN, 50f, 50f)
        ev(android.view.MotionEvent.ACTION_MOVE, -200f, 52f); ev(android.view.MotionEvent.ACTION_MOVE, -900f, 55f)
        ev(android.view.MotionEvent.ACTION_UP, -900f, 55f); idle()
        assertEquals("pegada a la izquierda = texto al lado", "l", f.block.side)
        assertEquals(1, f.edits().size)
        act.finish(); waitIo()
    }

    @Test fun bovedaDeBestNotes() {
        val v = org.json.JSONObject(javaClass.classLoader!!.getResource("boveda.json")!!.readText())
        val files = HashMap<String, String>()
        val fo = v.getJSONObject("files"); fo.keys().forEach { files[it] = fo.getString(it) }
        VaultBox.wipe(ctx)
        assertEquals("ok", VaultBox.importRaw(ctx, files))
        try { VaultBox.open(ctx, "otra"); fail("debía fallar") } catch (_: VaultBox.WrongPass) {}
        val k = VaultBox.open(ctx, v.getString("pass"))
        val list = VaultBox.loadIndex(ctx, k)
        assertEquals("Secreta-123", list.getJSONObject(0).getString("pass"))
        val out = File(ctx.cacheDir, "x.txt")
        VaultBox.unsealStream(ctx, "f_abc.bin", k, out)
        assertEquals("hola archivo", out.readText())
        // guardar algo nuevo y volverlo a leer
        VaultBox.sealStream(ctx, "f_new.bin", k, "contenido grande".byteInputStream())
        VaultBox.unsealStream(ctx, "f_new.bin", k, out)
        assertEquals("contenido grande", out.readText())
        // cambiar la contraseña
        list.getJSONObject(0).put("file", org.json.JSONObject().put("name", "a.txt"))
        val k2 = VaultBox.changePass(ctx, k, "nueva123", list)
        VaultBox.open(ctx, "nueva123")
        VaultBox.unsealStream(ctx, "f_abc.bin", k2, out)
        assertEquals("hola archivo", out.readText())
        // la calculadora y la bóveda abren sin errores
        Robolectric.buildActivity(CalcActivity::class.java).setup()
        Robolectric.buildActivity(VaultActivity::class.java).setup()
    }

    @Test fun colorDelTituloSeGuarda() {
        importar()
        var act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1")).setup().get()
        waitIo(); idle()
        val t = NoteActivity::class.java.getDeclaredField("title").apply { isAccessible = true }.get(act) as TitleEdit
        t.requestFocus(); t.setSelection(0, 3)
        val style = NoteActivity::class.java.getDeclaredMethod("style", Function1::class.java).apply { isAccessible = true }
        style.invoke(act, { s: St -> s.copy(color = 0xFF30D158.toInt()) })
        t.setSelection(3, 6)
        style.invoke(act, { s: St -> s.copy(color = 0xFFBF5AF2.toInt(), o = 0xFFFF375F.toInt()) })
        act.finish(); idle(); waitIo()
        // al volver a entrar, los colores siguen
        act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "n1")).setup().get()
        waitIo(); idle()
        val t2 = NoteActivity::class.java.getDeclaredField("title").apply { isAccessible = true }.get(act) as TitleEdit
        val runs = Rich.runs(t2.text)
        assertEquals(0xFF30D158.toInt(), runs[0].st.color)
        assertTrue(runs.any { it.st.color == 0xFFBF5AF2.toInt() && it.st.o == 0xFFFF375F.toInt() })
        // y la tarjeta de inicio también
        assertTrue(Store.meta("n1")!!.pv!!.contains("\"t\""))
    }

    @Test fun respaldoIdaYVuelta() {
        importar()
        val n1 = Store.meta("n1")!!
        val (body0, _) = Store.readBody("n1")
        val foto = Regex("\"f\":\"([^\"]+)\"").find(body0!!)!!.groupValues[1]
        File(ctx.filesDir, "historial").mkdirs(); File(ctx.filesDir, "historial/n1.json").writeText("[]")
        val out = File(ctx.cacheDir, "bk.json")
        Backup.exportFile(ctx, "secreta1", out) {}
        assertTrue(out.length() > 100)
        // se borra la nota y su foto, y se vuelve a traer del respaldo
        Store.deleteForever(listOf("n1")); waitIo()
        File(Store.mediaDir, foto).delete()
        reload()
        assertEquals(null, Store.meta("n1"))
        try { Backup.importFile(ctx, Uri.fromFile(out), "otra") {}; fail("debía fallar") } catch (_: Backup.WrongPassword) {}
        Backup.importFile(ctx, Uri.fromFile(out), "secreta1") {}
        reload()
        val back = Store.meta("n1")!!
        assertEquals(n1.title, back.title)
        assertEquals(body0, Store.readBody("n1").first)
        assertTrue("la foto volvió", File(Store.mediaDir, foto).length() > 0)
    }

    @Test fun compartirVariasNotas() {
        importar()
        val items = Export.load(Store.notes.map { it.id })
        assertTrue("las de PIN no se mandan", items.none { it.meta.locked })
        val page = File(ctx.cacheDir, "p.html")
        Export.page(ctx, items, page)
        val html = page.readText()
        assertTrue(html.contains("<img src=\"data:image"))
        assertTrue(html.contains("<audio") || html.contains("<video"))
        assertTrue(html.contains("Ticket"))
        assertTrue(Export.text(items).contains("☐"))
    }
}
