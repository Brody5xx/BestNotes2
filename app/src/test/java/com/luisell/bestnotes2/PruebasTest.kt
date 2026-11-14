package com.luisell.bestnotes2

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pruebas automáticas: se corren solas en GitHub antes de construir el APK. */
class PruebasTest {
    private val vec = JSONObject(javaClass.classLoader!!.getResource("vec.json")!!.readText())

    @Test fun pinIgualQueBestNotes() {
        assertEquals(vec.getString("pin1234"), Pin.hash("1234"))
        assertEquals(vec.getString("pin0000"), Pin.hash("0000"))
    }

    @Test fun abreElRespaldoDeBestNotes() {
        val f = JSONObject(vec.getString("file"))
        val dec = java.util.Base64.getDecoder()
        val salt = dec.decode(f.getString("salt")); val nonce = dec.decode(f.getString("nonce")); val tag = dec.decode(f.getString("tag"))
        val ct = dec.decode(f.getString("data"))
        val keys = Backup.pbkdf2("clave segura ñ".toByteArray(), salt, f.getInt("iter"), 2)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keys.copyOfRange(32, 64), "HmacSHA256")) }
        mac.update("mis-notas-v1".toByteArray()); mac.update(salt); mac.update(nonce); mac.update(ct)
        assertTrue("el sello debe coincidir", mac.doFinal().contentEquals(tag))
        val c = Backup.ChaCha(keys.copyOfRange(0, 32), nonce)
        // por pedazos, como en la app
        var p = 0
        while (p < ct.size) { val n = minOf(37, ct.size - p); c.xor(ct, p, n); p += n }
        assertEquals(vec.getString("plain"), String(ct, Charsets.UTF_8))
    }

    @Test fun convierteElHtml() {
        val html = "<div><b>Negrita</b> y <span style=\"color: #ff453a; font-size: 24px;\">rojo</span></div><div><br></div>" +
            "<ul class=\"checklist\"><li>uno</li><li class=\"done\">dos</li></ul><div>fin &amp; listo</div>"
        val r = HtmlImport.convert(html, true) { null }
        val b = r.blocks
        assertTrue(b[0] is Block.Text)
        val t0 = b[0] as Block.Text
        assertEquals("Negrita y rojo\n", runsPlain(t0.runs))
        assertTrue(t0.runs[0].st.b)
        assertEquals(24, t0.runs.last { it.text.contains("rojo") }.st.size)
        val l = b[1] as Block.ListB
        assertEquals("check", l.kind)
        assertEquals(2, l.items.size)
        assertTrue(l.items[1].done)
        assertEquals("fin & listo", runsPlain((b[2] as Block.Text).runs))
        // ida y vuelta por JSON
        val again = blocksFromJson(blocksJson(b))
        assertEquals(blocksPlain(b), blocksPlain(again))
    }

    @Test fun buscaSinAcentos() {
        val r = findAll("La Cámara del sótano", "camara")
        assertEquals(1, r.size)
        assertEquals(3, r[0].first)
        assertEquals("Cámara", "La Cámara del sótano".substring(r[0].first, r[0].last + 1))
    }

    @Test fun textoDeLaIa() {
        val r = ReportAi.blocks("## Resumen\nTodo bien.\n## Trabajo realizado\n- Sitio A: cámara\n## Pendientes\n- Cambiar cable\n## Materiales\n- 2 x Switch\n## Notas\n- Ninguna")
        val lists = r.filterIsInstance<Block.ListB>()
        assertEquals(listOf("ul", "check", "check", "ul"), lists.map { it.kind })
        val b = aiToBlocks("Ubicación:\n- uno\n- dos\n1. paso\ntexto")
        assertTrue(b.any { it is Block.ListB && it.kind == "ul" && it.items.size == 2 })
        assertTrue(b.any { it is Block.ListB && it.kind == "ol" })
        val m = Materials.parse("Aquí está: [{\"item\":\"Cable Cat 6\",\"qty\":3,\"site\":\"Sitio A\"}] listo")!!
        assertEquals("Cable Cat 6", m.getJSONObject(0).getString("item"))
    }

    @Test fun calculadora() {
        Formula.deg = false; Formula.ans = 0.0
        fun c(t: String) = Formula.eval(t)!!
        assertEquals(14.0, c("2+3×4"), 1e-9)
        assertEquals(20.0, c("(2+3)4"), 1e-9)
        assertEquals(3.0, c("√(9"), 1e-9)                // cierra el paréntesis solo
        assertEquals(-8.0, c("−2³"), 1e-9)
        assertEquals(120.0, c("5!"), 1e-9)
        assertEquals(0.25, c("25%"), 1e-9)
        assertEquals(2 * Math.PI, c("2π"), 1e-9)
        assertEquals(1000.0, c("10^(3)"), 1e-9)
        Formula.deg = true
        assertEquals(0.5, c("sin(30)"), 1e-9)
        assertEquals(30.0, c("sin⁻¹(0.5)"), 1e-9)
        Formula.deg = false
        assertEquals(12.0, Formula.compile("x²+3")(3.0), 1e-9)
        assertEquals(null, Formula.eval("2++"))
        assertEquals("0.3333333333", Formula.fmt(1.0 / 3))
        assertEquals("1.5ᴇ12", Formula.fmt(1.5e12))
        assertEquals("−4", Formula.fmt(-4.0))
    }
}
