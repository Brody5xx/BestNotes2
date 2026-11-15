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
        "2026-11-14" to ("Panel de estilo de la letra abajo (colores de un toque, resaltar, contorno y tachado) para que no lo tapen Cortar/Copiar/Pegar; rueda de colores y tamaño de letra nuevos; los paneles ya salen abajo" to
            "Text style panel at the bottom (one-tap colors, highlight, outline and strike) so Cut/Copy/Paste don't cover it; new color wheel and font size; panels now open at the bottom"),
        "2026-11-14" to ("📖 Registro que se actualiza solo (ya no se llenan versiones automáticas), la calculadora ya no abre el teclado del teléfono y panel de Apariencia nuevo con vista previa" to
            "📖 Log that updates by itself (no more automatic versions), the calculator no longer opens the phone keyboard and new Appearance panel with preview"),
        "2026-11-14" to ("Barra de abajo de dos filas como en Best Notes: dibujar, agregar (foto, video, audio, IA), B I U, resaltar, listas, tamaño «Aa 17» y el círculo de color (color, resaltar, contorno y tachado). Ícono de girar arreglado" to
            "Two-row bottom bar like Best Notes: draw, add (photo, video, audio, AI), B I U, highlight, lists, “Aa 17” size and the color circle (color, highlight, outline and strikethrough). Rotate icon fixed"),
        "2026-11-14" to ("Parte 4c-2: barra de arriba como en Best Notes (historial, ortografía, fondo de la nota y calculadora), historial y versiones de cada nota, revisar ortografía una por una, panel de la foto como antes y paneles nuevos de vidrio con animaciones" to
            "Part 4c-2: top bar like Best Notes (history, spelling, note background and calculator), history and versions of each note, spell check one by one, photo panel like before and new animated glass panels"),
        "2026-11-14" to ("Parte 4c-1: calculadora (gráficas, tabla, divisas y horas de trabajo), bóveda cifrada, fondos de pantalla, la misma fila de botones de Best Notes, brillo azul fino con dos líneas blancas que trazan la orilla de la foto seleccionada y animaciones más lentas" to
            "Part 4c-1: calculator (graphs, table, currency and work hours), encrypted vault, wallpapers, the same button row as Best Notes, thin blue glow with two white lines tracing the edge of the selected photo and slower animations"),
        "2026-11-14" to ("Parte 4b: el diseño de vidrio de Best Notes con sus animaciones (estrellitas, círculo al abrir), fotos que acomodan el texto en vivo, IA (asistente, reescribir, palabras y frases profesionales, ortografía), reporte del día, lista de materiales y pasar audios a texto" to
            "Part 4b: Best Notes glass design with its animations (sparkles, circle when opening), photos that move the text live, AI (assistant, rewrite, professional words and phrases, spelling), daily report, materials list and audio to text"),
        "2026-11-14" to ("Parte 4a: mover fotos como antes (brillo azul, cruz roja, escribir al lado), compartir como imagen o PDF, dictar, pendientes, recordatorios, bloqueo de la app y formato de hora" to
            "Part 4a: move photos like before (blue glow, red cross, write beside), share as image or PDF, dictation, to-dos, reminders, app lock and time format"),
        "2026-11-14" to ("Parte 3: grabar y escuchar audios, videos dentro de la nota (sin sonido y achicar), dibujar en la nota y encima de las fotos" to
            "Part 3: record and play audio, videos inside the note (mute and shrink), draw on the note and on top of photos"),
        "2026-11-14" to ("Parte 2: fotos (mover, a un lado con texto, tamaño, girar, recortar, sello, anclar), rueda de colores, tamaños de 5 a 100 y contorno de letras" to
            "Part 2: photos (move, beside text, size, rotate, crop, stamp, pin), color wheel, sizes 5 to 100 and letter outline"),
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
        Sec(Icons.IMAGE,
            "Fotos" to listOf(
                "Botón de la foto en la barra de abajo: Cámara o Galería (puedes escoger varias). Se pone donde está el cursor.",
                "Toca la foto: brilla en azul y abajo sale su panel. Con un dedo la mueves a donde quieras: sale una cruz roja y cuántos px se movió.",
                "Pégala a la orilla izquierda o derecha y suéltala: el texto se escribe AL LADO de la foto.",
                "Con dos dedos la haces más grande o más chica y la giras (cerca de derecho se endereza sola).",
                "Panel de la foto: Recortar, Dibujar encima, Sello, Girar 90°, Ver, Subir, Bajar y Quitar; Izquierda / Sola / Derecha; giro de 0° a 90°; y la barra del tamaño.",
                "«Anclar» (debajo de la foto) la deja fija para que no se mueva sin querer.",
                "Sello: cada foto sale con una franja abajo con la fecha y hora en que se tomó y el sitio (el título de la nota). Si lo quitas, las próximas fotos salen sin sello.",
                "Las fotos se achican solas a un tamaño razonable: la nota pesa menos y abre más rápido.",
            ),
            "Photos" to listOf(
                "Photo button in the bottom bar: Camera or Gallery (you can pick several). It goes where the cursor is.",
                "Tap the photo: it glows blue and its panel shows at the bottom. With one finger you move it anywhere: a red cross and how many px it moved show.",
                "Stick it to the left or right edge and let go: the text is written NEXT TO the photo.",
                "With two fingers you make it bigger or smaller and rotate it (near straight it straightens itself).",
                "Photo panel: Crop, Draw on top, Stamp, Rotate 90°, View, Up, Down and Remove; Left / Alone / Right; rotation from 0° to 90°; and the size bar.",
                "“Anchor” (below the photo) keeps it fixed so it doesn't move by accident.",
                "Stamp: each photo gets a strip at the bottom with the date and time it was taken and the site (the note title). If you remove it, next photos come without a stamp.",
                "Photos are resized automatically: the note weighs less and opens faster.",
            )),
        Sec(Icons.STAR,
            "IA (la gratis de Best Notes)" to listOf(
                "Botón ✨ en la barra de la nota. Solo se manda tu texto cuando tocas un botón de IA.",
                "Asistente de trabajo: con TODA la nota hace reporte de trabajo, toda la nota profesional, email para el jefe, mensaje para compañeros, inglés profesional, lista de tareas, resumen o paso a paso. Sale en español y en inglés.",
                "Reescribir con IA: lo seleccionado (o el renglón del cursor) en profesional, formal, de negocios, más claro, más corto, más largo, amigable, convincente, gracioso, con emojis, o traducido. «Usar este» lo cambia.",
                "Palabra más profesional y Frase profesional: selecciona y escoge una de las opciones (para ir aprendiendo).",
                "Revisar ortografía: arregla toda la nota renglón por renglón.",
            ),
            "AI (Best Notes' free AI)" to listOf(
                "✨ button in the note's bar. Your text is only sent when you tap an AI button.",
                "Work assistant: with the WHOLE note it makes a work report, the whole note professional, an email to the boss, a message for coworkers, professional English, a to-do list, a summary or step by step. It comes in Spanish and English.",
                "Rewrite with AI: the selection (or the cursor's line) as professional, formal, business, clearer, shorter, longer, friendly, persuasive, funny, with emojis, or translated. “Use this” replaces it.",
                "More professional word and Professional phrase: select and pick one of the options (to keep learning).",
                "Spell check: fixes the whole note line by line.",
            )),
        Sec(Icons.REPORT,
            "Reporte del día y Lista de materiales" to listOf(
                "☰ → Reporte del día: escoge el día con ‹ ›, quita las notas que no quieras, el idioma (Español, English o Los dos) y si van las fotos. La IA arma el reporte: Resumen, Trabajo realizado (por sitio), Pendientes y Materiales (como checklist) y Notas para el supervisor. Nunca inventa; si la IA falla, pone tus notas tal cual.",
                "☰ → Lista de materiales: «Buscar en mis notas» (Hoy, Esta semana o Todas) y la IA saca lo que hay que pedir. Cada material con su total y para dónde va. Toca el círculo: ○ Por pedir → 🛒 Pedido → ✅ Llegó. Con ＋ agregas a mano; ⋮ → Mandar a mi jefe o Guardar como nota.",
            ),
            "Daily report and Materials list" to listOf(
                "☰ → Daily report: pick the day with ‹ ›, unselect notes you don't want, the language (Español, English or Both) and whether photos go in. The AI writes the report: Summary, Work completed (by site), Pending and Materials (as checklists) and Notes for the supervisor. It never makes things up; if the AI fails, it puts your notes as written.",
                "☰ → Materials list: “Search my notes” (Today, This week or All) and the AI pulls out what must be ordered. Each material with its total and where it goes. Tap the circle: ○ To order → 🛒 Ordered → ✅ Arrived. ＋ adds by hand; ⋮ → Send to my boss or Save as note.",
            )),
        Sec(MoreIcons.IMPORT,
            "Compartir" to listOf(
                "Nota → ⋮ → Compartir: Imagen (tal como se ve, con fotos, colores y dibujos), PDF, Solo texto o Mensaje de texto (SMS).",
                "Escoges la app: Gmail, WhatsApp, Outlook, mensajes…",
                "En el texto, los checklist salen con ☐ y ☑.",
            ),
            "Share" to listOf(
                "Note → ⋮ → Share: Image (as it looks, with photos, colors and drawings), PDF, Text only or Text message (SMS).",
                "You pick the app: Gmail, WhatsApp, Outlook, messages…",
                "In the text, checklists come with ☐ and ☑.",
            )),
        Sec(MoreIcons.TEXT_T,
            "Dictar (voz a texto)" to listOf(
                "Botón del micrófono → Dictar (o ⋮ → Dictar). Habla y se escribe solo donde está el cursor.",
                "En la barrita cambias entre ES y EN. ■ Parar termina.",
            ),
            "Dictation (voice to text)" to listOf(
                "Microphone button → Dictate (or ⋮ → Dictate). Speak and it types where the cursor is.",
                "In the small bar switch between ES and EN. ■ Stop ends it.",
            )),
        Sec(MoreIcons.CHECKBOX,
            "Pendientes y recordatorios" to listOf(
                "Arriba en la pantalla principal sale la tarjeta de Pendientes: todo lo que falta de todas tus notas. «Ver todo» los muestra todos (también en ☰ → Pendientes).",
                "Toca la casilla para marcarlo como hecho (en su nota baja a «Completados»).",
                "⏰ Recordatorio: nota → ⋮ → Recordatorio, o la campanita en Pendientes. En 1 hora, Hoy 7 PM, Mañana 8 AM… o escoger día y hora.",
                "El aviso suena aunque la app esté cerrada; al tocarlo se abre la nota. Las notas con recordatorio muestran un reloj.",
            ),
            "To-dos and reminders" to listOf(
                "At the top of the home screen there's the To-dos card: everything left from all your notes. “See all” shows them all (also ☰ → To-dos).",
                "Tap the box to mark it done (in its note it moves to “Completed”).",
                "⏰ Reminder: note → ⋮ → Reminder, or the clock in To-dos. In 1 hour, Today 7 PM, Tomorrow 8 AM… or pick a day and time.",
                "The alert rings even when the app is closed; tapping it opens the note. Notes with a reminder show a clock.",
            )),
        Sec(MoreIcons.MIC,
            "Notas de voz" to listOf(
                "Botón del micrófono en la barra de abajo → Grabar ahora. Sale una barra con el tiempo: Pausa, Cancelar o ✓ Listo.",
                "El audio queda dentro de la nota: ▶ para escucharlo, la barra para adelantar y 1x / 1.5x / 2x para la velocidad.",
                "«Subir un audio»: pon una nota de voz de WhatsApp o de la grabadora.",
                "⋮ del audio → Pasar a texto: se hace dentro del teléfono, sin internet (Android 13 o más nuevo).",
            ),
            "Voice notes" to listOf(
                "Microphone button in the bottom bar → Record now. A bar with the time shows: Pause, Cancel or ✓ Done.",
                "The audio stays inside the note: ▶ to play it, the bar to skip ahead and 1x / 1.5x / 2x for speed.",
                "“Upload an audio”: add a WhatsApp or recorder voice note.",
                "Audio ⋮ → Turn into text: it runs inside the phone, no internet (Android 13 or newer).",
            )),
        Sec(MoreIcons.VIDEO,
            "Videos" to listOf(
                "Botón del video → Grabar video o Galería. Se ve dentro de la nota con su portada: tócalo para verlo ahí mismo.",
                "El botón de las esquinas lo abre en pantalla completa.",
                "⋮ del video: Quitar el sonido (por ejemplo, antes de enviarlo) y Achicar (pesa menos y se manda más rápido).",
            ),
            "Videos" to listOf(
                "Video button → Record video or Gallery. It shows inside the note with its cover: tap it to watch it right there.",
                "The corners button opens it full screen.",
                "Video ⋮: Remove the sound (for example, before sending it) and Shrink (smaller and faster to send).",
            )),
        Sec(MoreIcons.PEN,
            "Dibujar" to listOf(
                "Botón del lápiz en la barra de abajo (o el lápiz en la pantalla principal para una nota de dibujo nueva).",
                "Un dedo dibuja; dos dedos mueven la nota. Lápiz, marcador, borrador, color, grosor, deshacer, rehacer y borrar todo.",
                "✓ Listo vuelve a escribir. El dibujo se queda encima de la nota.",
                "En una foto: tócala → Dibujar encima. Puedes dibujar, marcar y escribir texto encima; se guarda una foto nueva.",
            ),
            "Drawing" to listOf(
                "Pencil button in the bottom bar (or the pencil on the home screen for a new drawing note).",
                "One finger draws; two fingers move the note. Pen, marker, eraser, color, thickness, undo, redo and clear all.",
                "✓ Done goes back to writing. The drawing stays on top of the note.",
                "On a photo: tap it → Draw on top. You can draw, mark and write text on it; a new photo is saved.",
            )),
        Sec(MoreIcons.PALETTE,
            "Colores y tamaños" to listOf(
                "Color de letra, resaltar y contorno de letras: rueda de colores, colores básicos, Recientes y Favoritos (Guardar este).",
                "Tamaño de letra de 5 a 100, con vista previa.",
                "El título también puede tener color: toca el título y luego el botón de color (o ⋮ → Color del título).",
            ),
            "Colors and sizes" to listOf(
                "Text color, highlight and letter outline: color wheel, basic colors, Recent and Favorites (Save this one).",
                "Font size from 5 to 100, with preview.",
                "The title can have color too: tap the title and then the color button (or ⋮ → Title color).",
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
        Sec(Icons.CALC,
            "Calculadora" to listOf(
                "Arriba en la pantalla principal (🧮) o en la nota: ⋮ → Calculadora.",
                "Calc: como una calculadora científica. 2nd cambia las teclas (sin⁻¹, eˣ, √…). ENTER vacío repite lo último. Toca un resultado viejo para usarlo.",
                "Y= y Gráfica: escribe hasta 4 funciones (por ejemplo x²−4). En la gráfica mueve con un dedo y acerca con dos. ⌖ sigue la línea y te dice x y y.",
                "Tabla: los valores de tus funciones desde «Inicio», de «Paso» en «Paso».",
                "Divisas: cambio de moneda con las tasas del día (necesita internet una vez; luego recuerda las últimas).",
                "Horas: pon entrada, salida y descanso de cada día; suma la semana (los turnos de noche cuentan bien).",
                "Desde una nota, «Poner en la nota» escribe el resultado donde estaba el cursor.",
            ),
            "Calculator" to listOf(
                "At the top of the home screen (🧮) or in a note: ⋮ → Calculator.",
                "Calc: like a scientific calculator. 2nd changes the keys (sin⁻¹, eˣ, √…). Empty ENTER repeats the last one. Tap an old result to use it.",
                "Y= and Graph: type up to 4 functions (for example x²−4). Move the graph with one finger and zoom with two. ⌖ follows the line and shows x and y.",
                "Table: the values of your functions from “Start”, “Step” by “Step”.",
                "Currency: exchange with today's rates (needs internet once; then it remembers the last ones).",
                "Hours: set start, end and break for each day; it adds up the week (night shifts count right).",
                "From a note, “Insert in note” writes the result where the cursor was.",
            )),
        Sec(MoreIcons.SHIELD,
            "Bóveda" to listOf(
                "Botón del escudo arriba (o ☰ → Bóveda). La primera vez creas una contraseña.",
                "Guarda contraseñas, correos, enlaces, notas secretas, fotos, videos y archivos. Todo se cifra con tu contraseña.",
                "🎲 crea una contraseña segura. El botón de copiar la copia sin mostrarla.",
                "Al salir de la app la bóveda se cierra sola.",
                "Si pasas tus notas desde Best Notes, la bóveda viene también y se abre con la misma contraseña.",
                "⚠️ Si olvidas la contraseña no hay forma de recuperar lo de adentro.",
            ),
            "Vault" to listOf(
                "Shield button at the top (or ☰ → Vault). The first time you create a password.",
                "Keep passwords, emails, links, secret notes, photos, videos and files. Everything is encrypted with your password.",
                "🎲 creates a strong password. The copy button copies it without showing it.",
                "When you leave the app, the vault locks itself.",
                "If you bring your notes from Best Notes, the vault comes too and opens with the same password.",
                "⚠️ If you forget the password, there is no way to get back what's inside.",
            )),
        Sec(MoreIcons.SUN,
            "Apariencia y fondos" to listOf(
                "Botón ☀️ arriba: escoge el fondo de Best Notes, un degradado o tu propia foto.",
                "Tu foto se puede difuminar para que las letras se lean mejor.",
            ),
            "Appearance and backgrounds" to listOf(
                "☀️ button at the top: pick the Best Notes background, a gradient or your own photo.",
                "Your photo can be blurred so the text is easier to read.",
            )),
        Sec(MoreIcons.HISTORY,
            "Historial y versiones" to listOf(
                "En la nota, arriba: el reloj con flecha 🕘 (entre deshacer y rehacer).",
                "📖 Registro: UNA sola lista que se va actualizando sola con todo lo que escribes (renglones y fotos), aunque después lo borres.",
                "En el Registro: «Poner todo en la nota» o «Vaciar».",
                "«Guardar versión ahora» guarda una copia exacta de la nota. Solo se guardan versiones cuando tú lo tocas.",
                "Toca una versión para verla, restaurarla o borrarla.",
                "Puedes proteger el historial con tu PIN.",
            ),
            "History and versions" to listOf(
                "In the note, at the top: the clock with an arrow 🕘 (between undo and redo).",
                "📖 Log: ONE single list that updates by itself with everything you write (lines and photos), even if you delete it later.",
                "In the Log: “Put all in the note” or “Clear”.",
                "“Save version now” keeps an exact copy of the note. Versions are only saved when you tap it.",
                "Tap a version to see it, restore it or delete it.",
                "You can protect the history with your PIN.",
            )),
        Sec(Icons.SPELL,
            "Ortografía y fondo de la nota" to listOf(
                "El botón de ortografía (arriba, al lado de rehacer) revisa toda la nota con internet. Las palabras con error se marcan en rojo.",
                "Abajo ves la palabra, por qué está mal y sugerencias: tócala para cambiarla, o «Ignorar». ‹ › para moverte.",
                "El botón del cuadrito con montañas pone una foto o un degradado detrás del texto de la nota.",
            ),
            "Spelling and note background" to listOf(
                "The spelling button (top, next to redo) checks the whole note using the internet. Words with mistakes turn red.",
                "Below you see the word, why it's wrong and suggestions: tap one to change it, or “Ignore”. ‹ › to move.",
                "The frame-with-mountains button puts a photo or a gradient behind the note's text.",
            )),
        Sec(MoreIcons.INFO,
            "Lo que viene" to listOf(
                "Respaldo, plantillas, modo claro y más idiomas.",
            ),
            "Coming next" to listOf(
                "Backup, templates, light mode and more languages.",
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
