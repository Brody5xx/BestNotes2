package com.luisell.bestnotes2

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
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

    @Test fun leerEnVozAltaReconoceElIdioma() {
        org.junit.Assert.assertEquals(java.util.Locale.SIMPLIFIED_CHINESE, Speaker.langOf("请在门口等我"))
        org.junit.Assert.assertEquals(java.util.Locale.US, Speaker.langOf("Pull the cable to the rack and test the camera"))
        org.junit.Assert.assertEquals("es", Speaker.langOf("Hay que pasar el cable por el techo").language)
        org.junit.Assert.assertEquals(3, Speaker.split("Hola. Prueba uno!\nOtra línea").size)
    }

    @Test fun textoEnFotoSeMueveYGuardaSuFondo() {
        val t = Stroke("text", 0xFF3A7BFF.toInt(), 0.06f, arrayListOf(floatArrayOf(0.2f, 0.3f)), "Cable aquí").also { it.bg = 1 }
        val back = Stroke.fromJson(t.toJson())!!
        org.junit.Assert.assertEquals(1, back.bg); org.junit.Assert.assertEquals("Cable aquí", back.txt)
        val c = back.copy(); c.p[0][0] += 0.1f; c.w *= 2
        org.junit.Assert.assertEquals(0.2f, back.p[0][0], 0.0001f)       // la copia no cambia la original (para deshacer)
        org.junit.Assert.assertEquals(0.12f, c.w, 0.0001f)
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
        // arriba de la foto queda un renglón vacío para poder escribir encima
        assertTrue(ed.blocks[0] is Block.Text && runsPlain((ed.blocks[0] as Block.Text).runs).isEmpty())
        assertTrue(ed.blocks[1] is Block.Img)
        assertEquals("al lado", runsPlain((ed.blocks[1] as Block.Img).beside))
        // quitar la foto: el texto de al lado se queda
        ed.removeImage(ed.views.first { it is FotoView } as FotoView); idle()
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

    /** El destello (las líneas blancas) se mueve junto con la foto. */
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun destelloSigueALaFoto() {
        val g = GlowBorder(3f, 12f)
        fun whiteBox(l: Int, t: Int): android.graphics.Rect {
            g.setBounds(l, t, l + 300, t + 400)
            val bm = android.graphics.Bitmap.createBitmap(1200, 1200, android.graphics.Bitmap.Config.ARGB_8888)
            g.draw(android.graphics.Canvas(bm))
            var x0 = 9999; var y0 = 9999; var x1 = -1; var y1 = -1
            for (y in 0 until 1200 step 2) for (x in 0 until 1200 step 2) { val c = bm.getPixel(x, y); if ((c ushr 24) > 200 && (c and 0xFF) > 230 && (c shr 16 and 0xFF) > 230) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) } }
            return android.graphics.Rect(x0, y0, x1, y1)
        }
        val a = whiteBox(50, 50)
        val b = whiteBox(700, 600)          // la misma foto, movida
        org.junit.Assert.assertTrue("debe haber líneas blancas", a.right > 0)
        org.junit.Assert.assertTrue("las líneas blancas deben estar dentro de la foto movida: $b", b.left >= 700 && b.top >= 600 && b.right <= 1000 && b.bottom <= 1000)
    }

    /** La perilla flota afuera de la foto (encima del texto): igual tiene que responder cada vez que la tocas. */
    @Test fun perillaRespondeAunqueEsteEncimaDelTexto() {
        reload()
        val bm = android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888)
        val r = Photos.saveNew(bm)
        val m = Store.createNote("Perilla", mutableListOf(Block.Text(mutableListOf(Run("Hola mundo"))), Block.Img(r.file, 50f, "", 0f, 0f, 60, 40)))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val f = ed.views.first { it is FotoView } as FotoView
        f.setSelected(true); idle()
        val k = ed.knobView!!
        val img = ed.blocks.first { it is Block.Img } as Block.Img
        repeat(2) { vez ->
            val l = IntArray(2); k.getLocationOnScreen(l)
            val x = l[0] + k.width / 2f; val y = l[1] + k.height / 2f
            fun ev(a: Int, dx: Float, dy: Float) = MotionEvent.obtain(0, 0, a, x + dx, y + dy, 0)     // en la prueba rawX = x
            val before = img.rot
            // el toque llega al editor (el padre), no a la perilla: el editor se lo pasa
            ed.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 0f, 0f))
            ed.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, -80f, 120f))
            ed.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, -80f, 120f))
            idle()
            org.junit.Assert.assertNotEquals("vez ${vez + 1}: la perilla debe girar la foto", before, img.rot)
        }
    }

    /** ⇔ Estirar a lo ancho: al llegar a la orilla de la otra foto se pega (quedan parejitas). Abajo: cambia el alto. */
    @Test fun estirarFotoSePegaALaOtra() {
        reload()
        val bm = android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888)
        val r1 = Photos.saveNew(bm); val r2 = Photos.saveNew(bm)
        val m = Store.createNote("Estirar", mutableListOf(Block.Text(mutableListOf(Run("Hola"))),
            Block.Img(r1.file, 60f, "", 25f, 0f, 60, 40), Block.Text(mutableListOf(Run("Medio"))), Block.Img(r2.file, 40f, "", 0f, 0f, 60, 40)))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val fs = ed.views.filterIsInstance<FotoView>()
        val f = fs[1]
        ed.selectImage(f); idle()
        fun drag(dot: View, dx: Float, dy: Float, up: Boolean = true) {
            val l = IntArray(2); dot.getLocationOnScreen(l)
            val x = l[0] + dot.width / 2f; val y = l[1] + dot.height / 2f
            ed.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
            ed.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, x + dx / 2, y + dy / 2, 0))
            ed.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0))
            if (up) ed.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x + dx, y + dy, 0))
            idle()
        }
        val right = f.dots[1]
        org.junit.Assert.assertEquals(View.VISIBLE, right.visibility)
        val W = ed.width - ed.paddingLeft - ed.paddingRight
        // casi hasta la orilla de la de arriba (le faltan 5 px): se pega exacto
        drag(right, W * 0.2f - 5f, 0f)
        assertEquals(60f, f.block.pct, 0.6f)
        org.junit.Assert.assertTrue("ahora tiene forma propia", f.block.ar > 0f)
        assertEquals("mismo ancho que la de arriba", fs[0].edgesOnScreen()!!.width(), f.edgesOnScreen()!!.width(), 2f)
        // abajo: más alta
        val ar0 = f.block.ar
        drag(f.dots[2], 0f, 150f)
        org.junit.Assert.assertTrue("más alta: ${f.block.ar} > $ar0", f.block.ar > ar0 + 0.05f)
        // se guarda
        assertEquals(f.block.ar, (Block.fromJson(f.block.toJson()) as Block.Img).ar, 0.001f)
    }

    /** 👔 La IA solo cambia las letras: las fotos y las listas se quedan en su lugar. */
    @Test fun iaSoloCambiaLasLetras() {
        reload()
        val bm = android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888)
        val r = Photos.saveNew(bm)
        val li = Block.ListB("ol", mutableListOf(Item(mutableListOf(Run("dos cables")))))
        val m = Store.createNote("Casa", mutableListOf(Block.Text(mutableListOf(Run("hola amigo\nfoto abajo"))), Block.Img(r.file, 50f, "", 25f, 0f, 60, 40), li))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val f = NoteActivity::class.java.getDeclaredMethod("applyPieces", Map::class.java).apply { isAccessible = true }
        f.invoke(act, mapOf(0 to "Hola, estimado", 1 to "La foto está abajo", 3 to "Dos cables"))
        idle()
        val bl = ed.blocks.filter { !(it is Block.Text && runsPlain(it.runs).isBlank()) }
        assertEquals("Hola, estimado\nLa foto está abajo", runsPlain((bl[0] as Block.Text).runs))
        org.junit.Assert.assertTrue("la foto sigue en su lugar", bl[1] is Block.Img && (bl[1] as Block.Img).file == r.file)
        assertEquals("Dos cables", runsPlain((bl[2] as Block.ListB).items[0].runs))
    }

    /** 🎬 El video se mueve como las fotos: lo dejas presionado, lo llevas a la orilla y el texto va al lado. */
    @Test fun videoSeMueveYTextoAlLado() {
        reload()
        val m = Store.createNote("Video", mutableListOf(Block.Text(mutableListOf(Run("Hola"))), Block.Media("video", "noexiste.mp4", pct = 50f, x = 25f)))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val vc = ed.views.first { it is VideoCard } as VideoCard
        val frame = find(vc.view, android.widget.FrameLayout::class.java).first { it.width > 0 && it.childCount >= 2 }
        val l = IntArray(2); frame.getLocationOnScreen(l)
        val x = l[0] + frame.width / 2f; val y = l[1] + frame.height / 2f
        frame.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        frame.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, x - 2000f, y, 0))
        frame.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x - 2000f, y, 0))
        idle()
        assertEquals("a la orilla izquierda: texto a la derecha", "l", vc.block.side)
        val back = Block.fromJson(vc.block.toJson()) as Block.Media
        assertEquals("l", back.side); assertEquals(50f, back.pct, 0.01f)
        org.junit.Assert.assertTrue("quedó escogido (brillo y bolitas)", ed.selVideo === vc)
        // 🔵 la bolita de la derecha lo agranda
        val dot = vc.dots[1]; dot.getLocationOnScreen(l)
        val dx = l[0] + dot.width / 2f; val dy = l[1] + dot.height / 2f
        ed.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, dx, dy, 0))
        ed.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, dx + 150f, dy, 0))
        ed.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, dx + 150f, dy, 0))
        idle()
        org.junit.Assert.assertTrue("más grande: ${vc.block.pct}", vc.block.pct > 55f)
        ed.selectImage(null); idle()
        org.junit.Assert.assertTrue(ed.selVideo == null)
    }

    /** Bajaste la foto: tocando el espacio de arriba escribes JUSTO arriba de la foto. */
    @Test fun escribirJustoArribaDeLaFoto() {
        reload()
        val bm = android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888)
        val r = Photos.saveNew(bm)
        val m = Store.createNote("Arriba", mutableListOf(Block.Text(mutableListOf(Run("Hola"))), Block.Img(r.file, 50f, "", 25f, 120f, 60, 40)))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val f = ed.views.first { it is FotoView } as FotoView
        val d = ctx.resources.displayMetrics.density
        ed.writeInGap(f, 120 * d - 5)              // tocar justo arriba de la foto
        idle()
        assertEquals(0f, (ed.blocks.first { it is Block.Img } as Block.Img).y)
        val t = ed.views[0] as TextoView
        assertTrue("el texto debe tener el cursor", t.edit.hasFocus())
        t.edit.text.insert(t.edit.selectionStart, "X")
        val txt = t.edit.text.toString()
        assertTrue("la X debe quedar en el último renglón, justo arriba de la foto: «$txt»", txt.endsWith("X"))
        assertTrue("debe haber renglones vacíos entre Hola y la X: «$txt»", txt.startsWith("Hola\n\n"))
    }

    /** ↕ Seleccionar TODA la nota (título + renglones de la lista) y ponerle negrita a todo junto. */
    @Test fun seleccionarVariasLineas() {
        reload()
        val m = Store.createNote("Casa", mutableListOf(Block.Text(mutableListOf(Run("Preguntas:"))),
            Block.ListB("ol", mutableListOf(Item(mutableListOf(Run("uno"))), Item(mutableListOf(Run("dos"))), Item(mutableListOf(Run("tres")))))))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val start = NoteActivity::class.java.getDeclaredMethod("multiStart", RichEdit::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        start.invoke(act, null, true, -1, -1, false); shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        @Suppress("UNCHECKED_CAST")
        val pieces = NoteActivity::class.java.getDeclaredMethod("mPieces").apply { isAccessible = true }.invoke(act) as List<Triple<android.widget.EditText, Int, Int>>
        assertEquals("título + 1 texto + 3 renglones", 5, pieces.size)
        val sty = NoteActivity::class.java.getDeclaredMethod("multiStyle", Function1::class.java).apply { isAccessible = true }
        sty.invoke(act, { s: St -> s.copy(b = !s.b) }); idle()
        NoteActivity::class.java.getDeclaredMethod("multiEnd").apply { isAccessible = true }.invoke(act); idle()
        act.finish(); waitIo()
        val saved = blocksFromJson(Store.readBody(m.id).first)
        val items = (saved.first { it is Block.ListB } as Block.ListB).items
        assertTrue("todos los renglones en negrita", items.all { it.runs.all { r -> r.st.b } })
        assertTrue((saved.first { it is Block.Text } as Block.Text).runs.all { it.st.b })
    }

    /** ☰ Líneas sueltas: renglón 1 sí, 2 no, 3 sí → se copian juntos. Y de corrido de un renglón a otro. */
    @Test fun lineasSueltasYDeCorrido() {
        reload()
        val m = Store.createNote("Lista", mutableListOf(Block.ListB("ul", mutableListOf(Item(mutableListOf(Run("línea 1"))), Item(mutableListOf(Run("línea 2"))), Item(mutableListOf(Run("línea 3")))))))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val c = NoteActivity::class.java
        fun f(n: String) = c.getDeclaredField(n).apply { isAccessible = true }
        c.getDeclaredMethod("multiStart", RichEdit::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(act, null, true, -1, -1, false)
        idle()
        @Suppress("UNCHECKED_CAST") val edits = f("mEdits").get(act) as List<android.widget.EditText>
        // edits: título, línea 1, línea 2, línea 3
        f("mLinesMode").setBoolean(act, true)
        val tog = c.getDeclaredMethod("toggleLine", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        (f("mRanges").get(act) as ArrayList<*>).clear(); tog.invoke(act, 1, 2); tog.invoke(act, 3, 2)
        c.getDeclaredMethod("multiCopy").apply { isAccessible = true }.invoke(act)
        val clip = (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
        assertEquals("línea 1\nlínea 3", clip)
        // de corrido: desde la mitad de la línea 1 hasta la mitad de la línea 3
        f("mLinesMode").setBoolean(act, false)
        f("mA").set(act, 1 to 3); f("mB").set(act, 3 to 5)
        c.getDeclaredMethod("multiCopy").apply { isAccessible = true }.invoke(act)
        val clip2 = (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
        assertEquals("ea 1\nlínea 2\nlínea", clip2)
        assertTrue(edits.size >= 4)
    }

    /** Dejar presionada una palabra y SOLTAR el dedo: la selección NO se quita. Un toque nuevo sí la quita. */
    @Test fun soltarElDedoNoQuitaLaSeleccion() {
        reload()
        val m = Store.createNote("Lista", mutableListOf(Block.ListB("ol", mutableListOf(Item(mutableListOf(Run("Two glass break wires"))), Item(mutableListOf(Run("Two cables")))))))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val c = NoteActivity::class.java
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val e = (ed.views.first { it is ListaView }).edits()[0]
        e.requestFocus()
        c.getDeclaredMethod("startFromNative", RichEdit::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(act, e, 4, 9)
        idle()
        val multi = c.getDeclaredField("multi").apply { isAccessible = true }
        assertTrue(multi.get(act) != null)
        // el MISMO dedo se mueve un poquito y se suelta (llega sin ACTION_DOWN)
        val tl = View::class.java.getDeclaredMethod("getListenerInfo").apply { isAccessible = true }.invoke(e).let { li -> li.javaClass.getDeclaredField("mOnTouchListener").apply { isAccessible = true }.get(li) as View.OnTouchListener }
        fun ev(a: Int) = android.view.MotionEvent.obtain(0, 0, a, 10f, 10f, 0)
        tl.onTouch(e, ev(android.view.MotionEvent.ACTION_MOVE)); tl.onTouch(e, ev(android.view.MotionEvent.ACTION_UP)); idle()
        assertTrue("soltar el dedo no debe quitar la selección", multi.get(act) != null)
        // un toque nuevo (DOWN + UP) sí la quita
        tl.onTouch(e, ev(android.view.MotionEvent.ACTION_DOWN)); tl.onTouch(e, ev(android.view.MotionEvent.ACTION_UP)); idle()
        assertTrue(multi.get(act) == null)
    }

    /** ⚓ "Anclar igual": la foto nueva toma el tamaño y lugar de la foto anclada de arriba. */
    @Test fun anclarIgualQueLaAnterior() {
        reload()
        val bm = android.graphics.Bitmap.createBitmap(60, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val r1 = Photos.saveNew(bm); val r2 = Photos.saveNew(bm)
        val m = Store.createNote("Fotos", mutableListOf(Block.Text(mutableListOf(Run("a"))),
            Block.Img(r1.file, 40f, "", 5f, 10f, 60, 100, pin = true), Block.Text(mutableListOf(Run("b"))), Block.Img(r2.file, 70f, "", 20f, 0f, 60, 100)))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val fotos = ed.views.filterIsInstance<FotoView>()
        ed.selectImage(fotos[1]); idle()
        // aparece el botón "Anclar igual"
        val labels = find(act.window.decorView, android.widget.TextView::class.java).map { it.text.toString() }
        assertTrue("debe salir «Anclar igual»: $labels", labels.any { it.contains(L.t("anchorSame")) })
        fotos[1].anchorLike(fotos[0]); idle()
        val b = fotos[1].block
        assertEquals(40f, b.pct); assertEquals(5f, b.x); assertEquals(10f, b.y); assertTrue(b.pin)
    }

    /** ⏎ Escribiendo al lado de la foto: al pasar el final de la foto, el Enter manda el cursor ABAJO de la foto. */
    @Test fun enterAlLadoPasaDebajoDeLaFoto() {
        reload()
        val bm = android.graphics.Bitmap.createBitmap(60, 30, android.graphics.Bitmap.Config.ARGB_8888)
        val r = Photos.saveNew(bm)
        val m = Store.createNote("Foto", mutableListOf(Block.Img(r.file, 30f, "l", 0f, 0f, 60, 30, beside = mutableListOf(Run("uno")))))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val f = ed.views.first { it is FotoView } as FotoView
        val e = f.edits().first()
        e.requestFocus()
        e.text.append("\ndos\ntres\ncuatro\ncinco\nseis")      // ya pasó el final de la foto (que es bajita)
        act.window.decorView.let { v -> v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2340) }
        e.setSelection(e.text.length); e.text.insert(e.text.length, "\n")
        idle(); shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        val i = ed.blocks.indexOfFirst { it is Block.Img }
        assertTrue("debe haber texto debajo de la foto", ed.blocks.getOrNull(i + 1) is Block.Text)
        assertTrue("el texto de al lado no termina en Enter: «${runsPlain((ed.blocks[i] as Block.Img).beside)}»", !runsPlain((ed.blocks[i] as Block.Img).beside).endsWith("\n"))
        assertTrue("el cursor debe estar abajo de la foto", ed.views.getOrNull(i + 1)?.edits()?.any { it.hasFocus() } == true)
    }

    /** ⌫ Dejar presionado borrar: al llegar arriba sigue borrando en el texto de AL LADO de la foto. */
    @Test fun borrarSigueAlLadoDeLaFoto() {
        reload()
        val bm = android.graphics.Bitmap.createBitmap(60, 40, android.graphics.Bitmap.Config.ARGB_8888)
        val r = Photos.saveNew(bm)
        val m = Store.createNote("Borrar", mutableListOf(Block.Img(r.file, 40f, "l", 0f, 0f, 60, 40, beside = mutableListOf(Run("Bzbz"))), Block.Text(mutableListOf(Run("")))))
        waitIo(); idle()
        val act = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id)).setup().visible().get()
        waitIo(); idle()
        val ed = find(act.window.decorView, EditorView::class.java).first()
        val fi = ed.blocks.indexOfFirst { it is Block.Img }
        val below = ed.views[fi + 1] as TextoView
        assertTrue(ed.backAtStartOfText(below)); idle()
        val f = ed.views.first { it is FotoView } as FotoView
        val side = f.edits().first()
        assertTrue("el cursor debe estar al lado de la foto", side.hasFocus())
        assertEquals("al final de «Bzbz»", 4, side.selectionStart)
    }
}
