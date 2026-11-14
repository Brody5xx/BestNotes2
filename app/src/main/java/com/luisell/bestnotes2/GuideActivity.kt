package com.luisell.bestnotes2

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/* ============================================================
   GuideActivity.kt — 📖 Guía de la app: qué hace cada cosa.
   ⚠️ REGLA: cada función nueva se explica aquí (ES + EN) y se
   agrega una línea arriba en NEWS.
   ============================================================ */
class GuideActivity : Activity() {
    private class Sec(val icon: Int, val es: Pair<String, List<String>>, val en: Pair<String, List<String>>)

    private val NEWS = listOf(
        "2026-11-14" to ("Parte 1: pantalla principal, texto, checklist, búsqueda iluminada e importar de Best Notes" to
            "Part 1: home screen, text, checklist, highlighted search and import from Best Notes"),
    )

    private val GUIDE = listOf(
        Sec(MoreIcons.IMPORT,
            "Pasar tus notas de Best Notes" to listOf(
                "En Best Notes (la app vieja): menú ☰ → «Exportar todo para Best Notes 2». Pon una contraseña y compártelo con «Best Notes 2» (o guárdalo).",
                "Aquí: escribe la misma contraseña y listo. También puedes abrirlo desde ☰ → Importar de Best Notes.",
                "Puedes hacerlo las veces que quieras: si una nota ya está, se queda la más nueva. Nada se duplica.",
                "Las fotos, audios, videos y dibujos vienen dentro del archivo y se guardan en el teléfono.",
            ),
            "Bring your notes from Best Notes" to listOf(
                "In Best Notes (the old app): menu ☰ → “Export everything to Best Notes 2”. Set a password and share it with “Best Notes 2” (or save it).",
                "Here: type the same password and that's it. You can also open it from ☰ → Import from Best Notes.",
                "You can do it as many times as you want: if a note is already here, the newest one is kept. Nothing is duplicated.",
                "Photos, audio, videos and drawings come inside the file and are saved on the phone.",
            )),
        Sec(Icons.SEARCH,
            "Búsqueda iluminada" to listOf(
                "Escribe arriba en «Buscar en tus notas». No importan los acentos ni las mayúsculas.",
                "Lo que buscas se ilumina en violeta en las tarjetas y también dentro de la nota.",
                "La tarjeta te muestra el pedacito del texto donde está la palabra.",
            ),
            "Highlighted search" to listOf(
                "Type at the top in “Search your notes”. Accents and capitals don't matter.",
                "What you search is highlighted in violet on the cards and inside the note.",
                "The card shows the part of the text where the word is.",
            )),
        Sec(MoreIcons.CHECKBOX,
            "Checklist" to listOf(
                "Botón ☑ abajo a la derecha (checklist nuevo) o el botón de checklist en la barra de la nota.",
                "Enter crea otro renglón. Enter en un renglón vacío termina la lista.",
                "Al marcar te pregunta «¿Ya terminaste esto?» y baja a «Completados». Si la desmarcas, vuelve a su lugar.",
                "Borrar al principio de un renglón lo vuelve texto normal.",
            ),
            "Checklist" to listOf(
                "☑ button at the bottom right (new checklist) or the checklist button in the note's bar.",
                "Enter makes another line. Enter on an empty line ends the list.",
                "Checking asks “Did you finish this?” and moves it to “Completed”. Unchecking puts it back in its place.",
                "Backspace at the start of a line turns it into normal text.",
            )),
        Sec(MoreIcons.BOLD,
            "Escribir y dar formato" to listOf(
                "Barra de abajo: checklist, viñetas, números, B (negrita), I (cursiva), U (subrayado), S (tachado), tamaño, color y resaltar.",
                "Selecciona texto y toca un botón. Si no seleccionas nada, lo próximo que escribas sale con ese formato.",
                "Deshacer y rehacer con las flechas de arriba. La nota se guarda sola.",
            ),
            "Writing and formatting" to listOf(
                "Bottom bar: checklist, bullets, numbers, B (bold), I (italic), U (underline), S (strikethrough), size, color and highlight.",
                "Select text and tap a button. With nothing selected, what you type next gets that format.",
                "Undo and redo with the arrows at the top. The note saves itself.",
            )),
        Sec(MoreIcons.FOLDER,
            "Organizar tus notas" to listOf(
                "Carpetas con color (☰ → Nueva carpeta; mantén presionada una carpeta para editarla o borrarla).",
                "Etiquetas: escribe #algo en una nota y sale en ☰ → Etiquetas.",
                "Favoritos ⭐, fijar arriba, color de la nota, papelera (puedes recuperar lo borrado).",
                "Mantén presionada una nota para seleccionar varias. Arriba: lista o cuadrícula, y ordenar.",
            ),
            "Organize your notes" to listOf(
                "Colored folders (☰ → New folder; long-press a folder to edit or delete it).",
                "Tags: write #something in a note and it shows in ☰ → Tags.",
                "Favorites ⭐, pin to top, note color, trash (you can recover what you deleted).",
                "Long-press a note to select several. At the top: list or grid, and sort.",
            )),
        Sec(Icons.LOCK,
            "Bloquear con PIN" to listOf(
                "Nota → ⋮ → Bloquear con PIN. Es el mismo PIN de Best Notes si importaste tus notas.",
                "Las notas bloqueadas no muestran su texto en la lista ni en la búsqueda.",
            ),
            "Lock with PIN" to listOf(
                "Note → ⋮ → Lock with PIN. It's the same PIN as Best Notes if you imported your notes.",
                "Locked notes don't show their text in the list or in search.",
            )),
        Sec(MoreIcons.INFO,
            "Lo que viene" to listOf(
                "Parte 2: fotos (mover, a un lado con el texto al lado, girar, recortar, sello) y más colores y tamaños.",
                "Parte 3: audios, videos y dibujar dentro de la nota.",
                "Parte 4: dictado, IA, compartir como imagen o PDF, respaldo, bóveda, recordatorios, calculadora, reporte del día, pendientes, materiales, bloqueo de la app y más idiomas.",
            ),
            "Coming next" to listOf(
                "Part 2: photos (move, beside the text, rotate, crop, stamp) and more colors and sizes.",
                "Part 3: audio, videos and drawing inside the note.",
                "Part 4: dictation, AI, share as image or PDF, backup, vault, reminders, calculator, daily report, to-dos, materials, app lock and more languages.",
            )),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val es = L.lang == "es"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        top.addView(iconBtn(Icons.BACK) { finish() })
        top.addView(tv(L.t("guide"), 20f, T.text, true))
        root.addView(top)
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(40)) }
        sv.addView(col)
        col.addView(tv(if (es) "Lo más nuevo" else "What's new", 14f, T.accent, true).apply { setPadding(0, dp(6), 0, dp(4)) })
        NEWS.forEach { (d, t) -> col.addView(tv("$d · " + if (es) t.first else t.second, 14f, T.muted).apply { setPadding(0, 0, 0, dp(6)) }) }
        for (s in GUIDE) {
            val (t, lines) = if (es) s.es else s.en
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = roundBg(T.card, dp(18).toFloat(), T.border, dp(1)) }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(IconView(this, s.icon, 9f).apply { color = T.accent; isClickable = false }, lp(dp(26), dp(26)))
            head.addView(tv(t, 17f, T.text, true).apply { setPadding(dp(10), 0, 0, 0) })
            card.addView(head)
            lines.forEach { card.addView(tv("•  $it", 14.5f, T.muted).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f) }) }
            col.addView(card, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        col.addView(tv(L.t("devBy"), 13f, T.muted).apply { setPadding(0, dp(20), 0, 0); gravity = Gravity.CENTER })
        root.addView(sv, lp(MATCH, 0, 1f))
        setContentView(root)
    }
}
