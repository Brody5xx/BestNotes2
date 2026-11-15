package com.luisell.bestnotes2

import android.content.Context
import java.util.Locale

/* ============================================================
   L.kt — todos los textos de la app, en español e inglés.
   Se escoge solo según el idioma del teléfono, o en ☰ → Idioma.
   Para agregar un texto: ponlo en ES y en EN con la misma llave.
   ============================================================ */
object L {
    /** "auto", "es" o "en" */
    var choice = "auto"
        private set
    var lang = "es"
        private set

    /** Formato de la hora: "auto", "12" o "24". */
    var hourFmt = "auto"
        private set
    var is24 = false
        private set

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("ajustes", 0)
        choice = p.getString("lang", "auto") ?: "auto"
        hourFmt = p.getString("hourFmt", "auto") ?: "auto"
        is24 = when (hourFmt) { "24" -> true; "12" -> false; else -> android.text.format.DateFormat.is24HourFormat(ctx) }
        apply()
    }

    fun setHourFmt(ctx: Context, f: String) { ctx.getSharedPreferences("ajustes", 0).edit().putString("hourFmt", f).apply(); init(ctx) }

    /** La hora con el formato escogido: 3:05 PM o 15:05. */
    fun time(t: Long): String = java.text.SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", locale).format(java.util.Date(t))

    /** "Hoy 7:00 PM", "Mañana 8:00 AM" o "vie 21 nov, 7:00 PM". */
    fun when_(t: Long): String {
        val c = java.util.Calendar.getInstance(); val d = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val sameDay = { a: java.util.Calendar, b: java.util.Calendar -> a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR) }
        if (sameDay(c, d)) return t("today") + " " + time(t)
        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        if (sameDay(c, d)) return t("tomorrow") + " " + time(t)
        return java.text.SimpleDateFormat(if (lang == "es") "EEE d MMM" else "EEE, MMM d", locale).format(java.util.Date(t)) + ", " + time(t)
    }

    fun set(ctx: Context, c: String) {
        choice = c
        ctx.getSharedPreferences("ajustes", 0).edit().putString("lang", c).apply()
        apply()
    }

    private fun apply() {
        lang = if (choice == "auto") (if (Locale.getDefault().language == "es") "es" else "en") else choice
    }

    val locale: Locale get() = if (lang == "es") Locale("es") else Locale.US

    fun t(k: String, vararg args: Pair<String, Any>): String {
        var s = (if (lang == "es") ES[k] else EN[k]) ?: ES[k] ?: k
        for ((a, b) in args) s = s.replace("{$a}", b.toString())
        return s
    }

    private val ES = mapOf(
        "appName" to "Best Notes 2",
        "all" to "Todas las notas", "fav" to "Favoritos", "trash" to "Papelera", "folders" to "Carpetas", "tags" to "Etiquetas",
        "ok" to "Aceptar", "cancel" to "Cancelar", "done" to "Listo", "save" to "Guardar", "del" to "Borrar", "remove" to "Quitar",
        "recover" to "Recuperar", "delForever" to "Borrar para siempre", "emptyTrash" to "Vaciar papelera",
        "searchPh" to "Buscar en tus notas", "untitled" to "Sin título", "titlePh" to "Título", "bodyPh" to "Escribe aquí…",
        "noNotes" to "Todavía no hay notas.\nToca + para crear una.", "noMatch" to "No hay notas con «{q}»",
        "trashEmpty" to "La papelera está vacía", "folderEmpty" to "Esta carpeta está vacía", "noFav" to "No tienes favoritos todavía",
        "newNote" to "Nota", "newCheck" to "Checklist",
        "newFolder" to "Nueva carpeta", "folderName" to "Nombre de la carpeta", "editFolder" to "Editar carpeta",
        "deleteFolder" to "Borrar carpeta", "deleteFolderMsg" to "Las notas de «{n}» no se borran: quedan sin carpeta.",
        "moveToFolder" to "Mover a carpeta", "noFolder" to "Sin carpeta", "folderColor" to "Color de la carpeta",
        "noteColor" to "Color de la nota", "pinTop" to "Fijar arriba", "unpin" to "Quitar de arriba",
        "favAdd" to "Agregar a favoritos", "favRemove" to "Quitar de favoritos", "moveTrash" to "Mover a la papelera",
        "movedTrash" to "Se movió a la papelera", "undo" to "Deshacer", "redo" to "Rehacer",
        "sortBy" to "Ordenar por", "sortUpdated" to "Última modificación", "sortCreated" to "Fecha de creación", "sortAlpha" to "Alfabético (A-Z)",
        "viewList" to "Ver en lista", "viewGrid" to "Ver en cuadrícula",
        "selected" to "{n} seleccionadas", "selected1" to "1 seleccionada",
        "language" to "Idioma", "langAuto" to "Automático (idioma del teléfono)",
        "guide" to "Guía de la app", "guideSub" to "Todo lo que hace la app, explicado", "nNotes" to "{n} notas", "nNotes1" to "1 nota", "devBy" to "Developer: Luisell Peña Rodríguez", "version" to "Versión {v}",
        "import" to "Importar de Best Notes", "importSub" to "El archivo de «Exportar todo para Best Notes 2»",
        "importPass" to "Contraseña del archivo", "importPassHint" to "La que pusiste al exportar en Best Notes",
        "importing" to "Importando tus notas…", "importStep" to "{a} de {b} notas",
        "importDone" to "Listo: {n} notas importadas ({u} nuevas o actualizadas)",
        "importWrongPass" to "Contraseña incorrecta. Prueba otra vez.", "importBadFile" to "Este archivo no es un respaldo de Best Notes.",
        "importFail" to "No se pudo importar: {e}", "importKeepNewer" to "Si una nota ya estaba y es más nueva aquí, se queda la de aquí.",
        "importMediaFail" to "{n} fotos, audios o videos no venían en el archivo.",
        "noteOptions" to "Opciones de la nota", "wordCount" to "{w} palabras · {c} letras",
        "checklist" to "Checklist", "bullets" to "Viñetas", "numbers" to "Números",
        "ckDoneQ" to "¿Ya terminaste esto?", "ckDoneYes" to "Sí, terminé", "completed" to "Completados ({n})",
        "uncheckAll" to "Desmarcar todo", "deleteChecked" to "Borrar los completados",
        "size" to "Tamaño de letra", "textColor" to "Color de letra", "hilite" to "Resaltar", "noColor" to "Sin color",
        "lockPin" to "Bloquear con PIN", "unlock" to "Quitar el bloqueo", "lockedNote" to "Nota bloqueada",
        "pinCreate" to "Crea un PIN de 4 números", "pinConfirm" to "Confirma tu PIN", "pinMismatch" to "Los PIN no coinciden. Otra vez.",
        "pinToUnlock" to "Escribe tu PIN", "pinWrong" to "PIN incorrecto", "lockRemoved" to "Se quitó el bloqueo", "lockedOk" to "Nota bloqueada con PIN",
        "audio" to "Nota de voz", "video" to "Video", "drawing" to "Dibujo",
        "mediaSoon" to "Toca para abrirlo. Escucharlo dentro de la nota llega en la parte 3.",
        "drawSoon" to "Esta nota tiene un dibujo: se verá aquí en la parte 3 (no se perdió).",
        "photoMissing" to "Foto que no venía en el archivo",
        "today" to "Hoy", "yesterday" to "Ayer",
        "emptyTrashMsg" to "Se borran para siempre {n} notas.", "delForeverMsg" to "Se borra para siempre. No se puede recuperar.",
        "recovered" to "Nota recuperada", "inTrash" to "Esta nota está en la papelera.",
        "openWith" to "Abrir con…", "cantOpen" to "No hay una app para abrir esto",
        "tagsEmpty" to "Escribe #algo en una nota para crear una etiqueta",
        "progress" to "{d}/{t}",
        "info" to "Información", "created" to "Creada", "modified" to "Modificada",
        "besidePh" to "Escribe al lado de la foto…", "imgLeft" to "Izquierda", "imgAlone" to "Sola", "imgRight" to "Derecha",
        "imgRotate" to "Girar", "crop" to "Recortar", "reset" to "Volver", "stamp" to "Sello", "imgPin" to "Anclar", "imgUnpin" to "Soltar",
        "moveUp" to "Subir", "moveDown" to "Bajar", "view" to "Ver",
        "addPhoto" to "Poner foto", "camera" to "Cámara", "gallery" to "Galería", "addingPhotos" to "Poniendo la foto…", "photoFail" to "No se pudo poner la foto",
        "titleColor" to "Color del título", "outline" to "Contorno de letras", "apply" to "Aplicar", "sizeNormal" to "Normal",
        "colorsBasic" to "Colores", "colorsRecent" to "Recientes", "colorsFav" to "Favoritos", "colorsFavAdd" to "Guardar este",
        "colorsFavHint" to "Mantén presionado un favorito para quitarlo",
        "ai" to "IA", "aiWorking" to "La IA está escribiendo…", "aiFail" to "La IA no respondió. Revisa el internet y prueba otra vez.", "close" to "Cerrar",
        "copy" to "Copiar", "copied" to "Copiado", "aiAssistant" to "Asistente de trabajo", "aiAssistantSub" to "Reporte, email, resumen, pasos… con toda la nota",
        "aiRewrite" to "Reescribir con IA", "aiRewriteSub" to "Lo seleccionado o el renglón del cursor", "aiWord" to "Palabra más profesional",
        "aiWordSub" to "Selecciona una palabra", "aiPhrase" to "Frase profesional", "aiPhraseSub" to "Cómo decirlo en el trabajo (para ir aprendiendo)",
        "aiSpell" to "Revisar ortografía", "aiSpellSub" to "Toda la nota", "aiEmpty" to "La nota está vacía", "aiSelect" to "Pon el cursor en el texto o selecciónalo",
        "aiSelectWord" to "Selecciona primero la palabra o frase", "aiReport" to "Reporte de trabajo", "aiProNote" to "Toda la nota profesional", "aiEmail" to "Email para el jefe",
        "aiChat" to "Mensaje para compañeros", "aiEnglish" to "En inglés profesional", "aiTodo" to "Lista de tareas", "aiSummary" to "Resumen", "aiSteps" to "Paso a paso",
        "aiAddChecklist" to "Agregar como checklist", "aiNewNote" to "Nota nueva", "aiAppend" to "Agregar al final", "aiReplaceNote" to "Reemplazar la nota",
        "aiUse" to "Usar este", "aiBelow" to "Poner debajo", "aiApplyFix" to "Arreglar la nota", "aiFixed" to "Listo: ortografía arreglada",
        "rwPro" to "Profesional", "rwFormal" to "Formal", "rwBiz" to "De negocios", "rwFix" to "Solo arreglar ortografía", "rwClear" to "Más claro y sencillo",
        "rwShort" to "Más corto", "rwLong" to "Más largo", "rwFriendly" to "Amigable", "rwPersuasive" to "Convincente", "rwFunny" to "Gracioso", "rwEmoji" to "Con emojis",
        "rwTranslate" to "Traducir (español ↔ inglés)",
        "report" to "Reporte del día", "reportHint" to "La IA arma tu reporte de trabajo del día", "reportNotes" to "Notas del día", "reportNone" to "No hay notas de este día",
        "reportLang" to "Idioma", "both" to "Los dos", "reportMake" to "Hacer el reporte", "reportInclude" to "Incluir fotos", "reportTitle" to "Reporte del día · {d}",
        "reportFallback" to "La IA no respondió: puse tus notas tal cual para que no salga vacío.",
        "materials" to "Lista de materiales", "materialsHint" to "Lo que hay que pedir, sacado de tus notas", "matSearch" to "Buscar en mis notas",
        "matToday" to "Hoy", "matWeek" to "Esta semana", "matAll" to "Todas", "matEmpty" to "Toca «Buscar en mis notas» y la IA saca lo que hay que pedir o comprar.",
        "matToOrder" to "Por pedir", "matOrdered" to "Pedido", "matArrived" to "Llegó", "matAdd" to "Agregar a mano", "matItem" to "Material", "matQty" to "Cantidad",
        "matSite" to "Sitio", "matBoss" to "Mandar a mi jefe", "matSaveNote" to "Guardar como nota", "matFound" to "Encontré {n} materiales", "matNone" to "No encontré materiales para pedir",
        "matClear" to "Borrar la lista", "transcribe" to "Pasar a texto", "transcribing" to "Pasando el audio a texto…", "transcribeFail" to "No se pudo pasar a texto",
        "transcribeNote" to "Se hace dentro del teléfono (sin internet). ¿En qué idioma habla el audio?", "transcribeNo" to "Este teléfono no puede pasar audios a texto por dentro (necesita Android 13 y el reconocimiento de voz de Google sin internet). Puedes usar Dictar mientras hablas.",
        "pending" to "Pendientes", "pendingHint" to "Todo lo que te falta, de todas tus notas", "pendingEmpty" to "¡No tienes pendientes! 🎉", "seeAll" to "Ver todo",
        "reminder" to "Recordatorio", "reminders" to "Recordatorios", "in1h" to "En 1 hora", "in3h" to "En 3 horas", "today7" to "Hoy 7 PM",
        "tomorrow8" to "Mañana 8 AM", "tomorrow6" to "Mañana 6 AM", "pickDateTime" to "Escoger día y hora", "removeReminder" to "Quitar",
        "reminderSet" to "Te aviso: {w}", "tomorrow" to "Mañana",
        "share" to "Compartir", "shareImg" to "Imagen (tal como se ve)", "sharePdf" to "PDF", "shareText" to "Solo texto", "shareSms" to "Mensaje de texto (SMS)",
        "shareHint" to "Gmail, WhatsApp, Outlook, mensajes…", "preparing" to "Preparando…", "shareFail" to "No se pudo preparar. Prueba otra vez.",
        "dictate" to "Dictar (voz a texto)", "dictateSub" to "Hablas y se escribe solo", "listening" to "Escuchando…", "stop" to "Parar",
        "noDictation" to "Este teléfono no tiene reconocimiento de voz",
        "hourFmt" to "Formato de hora", "hourAuto" to "Como el teléfono", "lockApp" to "Bloquear la app con PIN", "lockAppOn" to "La app pide tu PIN al abrirla",
        "lockAppOff" to "La app ya no pide PIN", "changePin" to "Cambiar el PIN", "pinChanged" to "PIN cambiado", "unlockApp" to "Escribe tu PIN para entrar",
        "anchored" to "Anclada", "anchoredHint" to "La foto está anclada. Toca «Anclada» para moverla",
        "photoMoved" to "Foto movida. Toca Anclar para fijarla", "writeBeside" to "Ahora puedes escribir al lado de la foto",
        "textRight" to "texto a la derecha", "textLeft" to "texto a la izquierda",
        "drawHint" to "Un dedo dibuja · dos dedos mueven la nota", "penColor" to "Color del lápiz", "clearDrawing" to "Borrar todo el dibujo",
        "clearDrawingMsg" to "Se borra todo el dibujo de esta nota. Con Deshacer lo recuperas.",
        "recordNow" to "Grabar ahora", "audioFile" to "Subir un audio", "audioFileSub" to "Nota de voz de WhatsApp, grabadora…",
        "recFail" to "No se pudo grabar", "pause" to "Pausa", "resume" to "Seguir", "micPerm" to "Para grabar, permite usar el micrófono",
        "recordVideo" to "Grabar video", "addingVideo" to "Poniendo el video…", "addingAudio" to "Poniendo el audio…", "videoFail" to "No se pudo",
        "drawOn" to "Dibujar encima", "writeOnPhoto" to "Escribe en la foto", "tapToWrite" to "Toca donde quieres escribir",
        "fileMissing" to "no vino en el archivo", "muted" to "sin sonido", "mute" to "Quitar el sonido", "shrink" to "Achicar (pesa menos)",
        "working" to "Trabajando…", "shrinking" to "Achicando… {n}%", "mutedOk" to "Listo: el video ya no tiene sonido",
        "shrinkNoGain" to "Este video ya es pequeño", "shrinkOk" to "Listo: de {a} a {b}", "newDraw" to "Dibujo",
        // 🧮 calculadora
        "calc" to "Calculadora", "calcSub" to "Gráficas, divisas y horas de trabajo", "calcGraph" to "Gráfica", "calcTable" to "Tabla",
        "calcInsert" to "Poner en la nota", "calcError" to "Error", "calcStart" to "Inicio", "calcStep" to "Paso",
        "calcNoY" to "Escribe una función en Y= para ver la tabla", "calcFx" to "Divisas", "fxSearch" to "Buscar moneda o país",
        "fxLoading" to "Buscando las tasas del día…", "fxFail" to "No se pudieron bajar las tasas. Revisa el internet.",
        "fxUpdated" to "Tasas de hoy: {d}", "fxOffline" to "Sin internet: tasas del {d}",
        "hrsTab" to "Horas", "hrsTotal" to "Total de la semana", "hrsBreak" to "Descanso (min)", "hrsClear" to "Borrar la semana",
        "hrsHint" to "Pon la hora de entrada y de salida de cada día. Si sales después de la medianoche, también se cuenta bien.",
        "hrsIn" to "Entrada", "hrsOut" to "Salida", "calcCopied" to "Resultado copiado (pégalo donde quieras)", "calcInserted" to "Listo, se puso en la nota",
        // 🛡️ bóveda
        "vault" to "Bóveda", "vaultHint" to "Contraseñas, fotos y archivos cifrados",
        "vPass" to "Contraseña", "vMail" to "Correo", "vLink" to "Enlace", "vNote" to "Nota secreta", "vPhoto" to "Foto", "vVideo" to "Video", "vFile" to "Archivo",
        "vPasss" to "Contraseñas", "vMails" to "Correos", "vLinks" to "Enlaces", "vNotes" to "Notas", "vPhotos" to "Fotos", "vVideos" to "Videos", "vFiles" to "Archivos",
        "vCreateTitle" to "Crea tu bóveda", "vCreateMsg" to "Todo lo que guardes aquí se cifra con tu contraseña. Nadie lo puede ver sin ella.",
        "vPassNew" to "Contraseña (mínimo 6)", "vPassAgain" to "Repite la contraseña", "vCreate" to "Crear bóveda",
        "vShort" to "La contraseña debe tener al menos 6 letras o números", "vMismatch" to "Las contraseñas no son iguales",
        "vFail" to "Algo salió mal. Inténtalo otra vez.", "vForgetWarn" to "Si olvidas la contraseña, no hay forma de recuperar lo que hay adentro.",
        "vLocked" to "La bóveda está cerrada", "vPassword" to "Contraseña", "vOpening" to "Abriendo…", "vOpen" to "Abrir",
        "vWrong" to "Contraseña incorrecta", "vForgot" to "¿Olvidaste la contraseña?", "vReset" to "Borrar la bóveda",
        "vResetMsg" to "Se borra TODO lo que hay en la bóveda y no se puede deshacer. Después puedes crear una nueva.",
        "vSearch" to "Buscar en la bóveda", "vAll" to "Todo", "vEmpty" to "La bóveda está vacía.\nToca + para guardar algo.", "vNoMatch" to "No se encontró nada",
        "vCopiedPass" to "Contraseña copiada", "vCopied" to "Copiado", "vAdd" to "¿Qué quieres guardar?",
        "vTooBig" to "Es muy grande (máximo {n} MB)", "vSaving" to "Cifrando y guardando…", "vSaved" to "Guardado en la bóveda",
        "vfTitle" to "Título", "vfUser" to "Usuario", "vfPass" to "Contraseña", "vfUrl" to "Página web", "vfEmail" to "Correo", "vfNote" to "Nota",
        "vGenerated" to "Contraseña segura creada", "vView" to "Ver", "vOpenFile" to "Abrir", "vNew" to "Nuevo", "vDelete" to "¿Borrar de la bóveda?",
        "vNoApp" to "No hay una app para abrir esto", "vChangePass" to "Cambiar contraseña", "vPassChanged" to "Contraseña cambiada",
        "vImported" to "La bóveda también se pasó (ábrela con su misma contraseña).", "vImportExists" to "La bóveda no se pasó porque aquí ya tienes otra bóveda.",
        // ☀️ apariencia
        "appearance" to "Apariencia", "wallPhoto" to "Tu foto", "wallBlur" to "Difuminar mi foto",
        "lightSoon" to "El modo claro llega pronto",
        // ⋮ panel, historial, ortografía, fondo de la nota
        "favShort" to "Favorito", "pinShort" to "Fijar", "colorShort" to "Color", "pinnedOk" to "Fijada arriba 📌", "unpinnedOk" to "Ya no está fijada",
        "favAdded" to "Añadida a favoritos ⭐", "favRemoved" to "Quitada de favoritos",
        "history" to "Historial", "historySub" to "Registro y versiones de esta nota", "spellCheck" to "Revisar ortografía", "spellCheckSub" to "Una por una, con sugerencias",
        "noteWall" to "Fondo de la nota", "noteWallSub" to "Una foto o un degradado detrás del texto", "aiFree" to "Gratis, sin cuenta",
        "recordShort" to "Grabar", "dictShort" to "Dictar", "fileShort" to "Archivo", "editShort" to "Editar", "wallNone" to "Quitar",
        "saveVersionNow" to "Guardar versión ahora", "versionSaved" to "Versión guardada 🕘", "versionExists" to "Esa versión ya está guardada",
        "noVersions" to "Toca «Guardar versión ahora» para guardar una copia exacta de la nota.",
        "latest" to "la más nueva", "versionAuto" to "Automática", "versionMine" to "Guardada por ti", "emptyNote" to "(nota vacía)",
        "protectHist" to "Proteger el historial con PIN", "histWithPin" to "El historial pide PIN 🔒", "histNoPin" to "El historial ya no pide PIN",
        "restore" to "Restaurar", "versionRestoreTitle" to "¿Volver a esta versión?", "versionRestoreMsg" to "Lo que tienes ahora no se pierde: queda en el 📖 Registro.",
        "versionDelTitle" to "¿Borrar esta versión?", "versionDeleted" to "Versión borrada", "versionRestored" to "Listo, volviste a esa versión ✨",
        "spellChecking" to "Revisando la ortografía…", "spellFail" to "No se pudo revisar. Revisa el internet.", "spellNone" to "¡Todo bien escrito!",
        "spellDone" to "¡Listo! Ya revisaste todo", "spellTitle" to "ORTOGRAFÍA", "spellNoSug" to "Sin sugerencias", "spellIgnore" to "Ignorar",
        "insert" to "Agregar a la nota", "textStyle" to "Estilo de la letra", "strike" to "Tachado",
        "histIntro" to "El Registro guarda solo todo lo que escribes, aunque lo borres. Las versiones se guardan cuando tú quieras.",
        "record" to "Registro", "recLines" to "{n} renglón(es) · se actualiza solo", "savedVersions" to "Versiones guardadas",
        "recIntro" to "Todo lo que has escrito en esta nota, aunque lo hayas borrado", "putAllInNote" to "Poner todo en la nota",
        "putAllMsg" to "La nota se cambia por todo lo del registro (puedes deshacer con ↶).", "putAllDone" to "Listo, todo el registro está en la nota ✨",
        "recClear" to "Vaciar", "recClearMsg" to "Se borra el registro de esta nota. La nota no se toca.", "recCleared" to "Registro vaciado",
        "recEmpty" to "Todavía no hay nada. Empieza a escribir y se guarda solo.",
        "appearanceSub" to "Escoge el fondo de tu app",
    )

    private val EN = mapOf(
        "appName" to "Best Notes 2",
        "all" to "All notes", "fav" to "Favorites", "trash" to "Trash", "folders" to "Folders", "tags" to "Tags",
        "ok" to "OK", "cancel" to "Cancel", "done" to "Done", "save" to "Save", "del" to "Delete", "remove" to "Remove",
        "recover" to "Recover", "delForever" to "Delete forever", "emptyTrash" to "Empty trash",
        "searchPh" to "Search your notes", "untitled" to "Untitled", "titlePh" to "Title", "bodyPh" to "Write here…",
        "noNotes" to "No notes yet.\nTap + to create one.", "noMatch" to "No notes with “{q}”",
        "trashEmpty" to "Trash is empty", "folderEmpty" to "This folder is empty", "noFav" to "No favorites yet",
        "newNote" to "Note", "newCheck" to "Checklist",
        "newFolder" to "New folder", "folderName" to "Folder name", "editFolder" to "Edit folder",
        "deleteFolder" to "Delete folder", "deleteFolderMsg" to "Notes in “{n}” are not deleted: they stay without a folder.",
        "moveToFolder" to "Move to folder", "noFolder" to "No folder", "folderColor" to "Folder color",
        "noteColor" to "Note color", "pinTop" to "Pin to top", "unpin" to "Unpin",
        "favAdd" to "Add to favorites", "favRemove" to "Remove from favorites", "moveTrash" to "Move to trash",
        "movedTrash" to "Moved to trash", "undo" to "Undo", "redo" to "Redo",
        "sortBy" to "Sort by", "sortUpdated" to "Last modified", "sortCreated" to "Date created", "sortAlpha" to "Alphabetical (A-Z)",
        "viewList" to "List view", "viewGrid" to "Grid view",
        "selected" to "{n} selected", "selected1" to "1 selected",
        "language" to "Language", "langAuto" to "Automatic (phone language)",
        "guide" to "App guide", "guideSub" to "Everything the app does, explained", "nNotes" to "{n} notes", "nNotes1" to "1 note", "devBy" to "Developer: Luisell Peña Rodríguez", "version" to "Version {v}",
        "import" to "Import from Best Notes", "importSub" to "The file from “Export everything to Best Notes 2”",
        "importPass" to "File password", "importPassHint" to "The one you used when exporting in Best Notes",
        "importing" to "Importing your notes…", "importStep" to "{a} of {b} notes",
        "importDone" to "Done: {n} notes imported ({u} new or updated)",
        "importWrongPass" to "Wrong password. Try again.", "importBadFile" to "This file is not a Best Notes backup.",
        "importFail" to "Could not import: {e}", "importKeepNewer" to "If a note was already here and is newer here, this one is kept.",
        "importMediaFail" to "{n} photos, audio or videos were not in the file.",
        "noteOptions" to "Note options", "wordCount" to "{w} words · {c} characters",
        "checklist" to "Checklist", "bullets" to "Bullets", "numbers" to "Numbers",
        "ckDoneQ" to "Did you finish this?", "ckDoneYes" to "Yes, done", "completed" to "Completed ({n})",
        "uncheckAll" to "Uncheck all", "deleteChecked" to "Delete completed",
        "size" to "Font size", "textColor" to "Text color", "hilite" to "Highlight", "noColor" to "No color",
        "lockPin" to "Lock with PIN", "unlock" to "Remove lock", "lockedNote" to "Locked note",
        "pinCreate" to "Create a 4-digit PIN", "pinConfirm" to "Confirm your PIN", "pinMismatch" to "PINs don't match. Try again.",
        "pinToUnlock" to "Enter your PIN", "pinWrong" to "Wrong PIN", "lockRemoved" to "Lock removed", "lockedOk" to "Note locked with PIN",
        "audio" to "Voice note", "video" to "Video", "drawing" to "Drawing",
        "mediaSoon" to "Tap to open it. Playing inside the note comes in part 3.",
        "drawSoon" to "This note has a drawing: it will show here in part 3 (it was not lost).",
        "photoMissing" to "Photo that was not in the file",
        "today" to "Today", "yesterday" to "Yesterday",
        "emptyTrashMsg" to "{n} notes will be deleted forever.", "delForeverMsg" to "It will be deleted forever. It can't be recovered.",
        "recovered" to "Note recovered", "inTrash" to "This note is in the trash.",
        "openWith" to "Open with…", "cantOpen" to "No app can open this",
        "tagsEmpty" to "Write #something in a note to create a tag",
        "progress" to "{d}/{t}",
        "info" to "Information", "created" to "Created", "modified" to "Modified",
        "besidePh" to "Write next to the photo…", "imgLeft" to "Left", "imgAlone" to "Alone", "imgRight" to "Right",
        "imgRotate" to "Rotate", "crop" to "Crop", "reset" to "Reset", "stamp" to "Stamp", "imgPin" to "Pin", "imgUnpin" to "Unpin",
        "moveUp" to "Up", "moveDown" to "Down", "view" to "View",
        "addPhoto" to "Add photo", "camera" to "Camera", "gallery" to "Gallery", "addingPhotos" to "Adding the photo…", "photoFail" to "Couldn't add the photo",
        "titleColor" to "Title color", "outline" to "Letter outline", "apply" to "Apply", "sizeNormal" to "Normal",
        "colorsBasic" to "Colors", "colorsRecent" to "Recent", "colorsFav" to "Favorites", "colorsFavAdd" to "Save this one",
        "colorsFavHint" to "Long-press a favorite to remove it",
        "ai" to "AI", "aiWorking" to "The AI is writing…", "aiFail" to "The AI didn't answer. Check the internet and try again.", "close" to "Close",
        "copy" to "Copy", "copied" to "Copied", "aiAssistant" to "Work assistant", "aiAssistantSub" to "Report, email, summary, steps… with the whole note",
        "aiRewrite" to "Rewrite with AI", "aiRewriteSub" to "The selection or the cursor's line", "aiWord" to "More professional word",
        "aiWordSub" to "Select a word", "aiPhrase" to "Professional phrase", "aiPhraseSub" to "How to say it at work (to keep learning)",
        "aiSpell" to "Spell check", "aiSpellSub" to "The whole note", "aiEmpty" to "The note is empty", "aiSelect" to "Put the cursor in the text or select it",
        "aiSelectWord" to "First select the word or phrase", "aiReport" to "Work report", "aiProNote" to "Whole note, professional", "aiEmail" to "Email to the boss",
        "aiChat" to "Message for coworkers", "aiEnglish" to "In professional English", "aiTodo" to "To-do list", "aiSummary" to "Summary", "aiSteps" to "Step by step",
        "aiAddChecklist" to "Add as checklist", "aiNewNote" to "New note", "aiAppend" to "Add at the end", "aiReplaceNote" to "Replace the note",
        "aiUse" to "Use this", "aiBelow" to "Put below", "aiApplyFix" to "Fix the note", "aiFixed" to "Done: spelling fixed",
        "rwPro" to "Professional", "rwFormal" to "Formal", "rwBiz" to "Business", "rwFix" to "Only fix spelling", "rwClear" to "Clearer and simpler",
        "rwShort" to "Shorter", "rwLong" to "Longer", "rwFriendly" to "Friendly", "rwPersuasive" to "Persuasive", "rwFunny" to "Funny", "rwEmoji" to "With emojis",
        "rwTranslate" to "Translate (Spanish ↔ English)",
        "report" to "Daily report", "reportHint" to "The AI writes your daily work report", "reportNotes" to "Notes of the day", "reportNone" to "No notes on this day",
        "reportLang" to "Language", "both" to "Both", "reportMake" to "Make the report", "reportInclude" to "Include photos", "reportTitle" to "Daily report · {d}",
        "reportFallback" to "The AI didn't answer: I put your notes as written so it's not empty.",
        "materials" to "Materials list", "materialsHint" to "What needs ordering, from your notes", "matSearch" to "Search my notes",
        "matToday" to "Today", "matWeek" to "This week", "matAll" to "All", "matEmpty" to "Tap “Search my notes” and the AI pulls out what needs to be ordered or bought.",
        "matToOrder" to "To order", "matOrdered" to "Ordered", "matArrived" to "Arrived", "matAdd" to "Add by hand", "matItem" to "Material", "matQty" to "Quantity",
        "matSite" to "Site", "matBoss" to "Send to my boss", "matSaveNote" to "Save as note", "matFound" to "Found {n} materials", "matNone" to "No materials to order found",
        "matClear" to "Clear the list", "transcribe" to "Turn into text", "transcribing" to "Turning the audio into text…", "transcribeFail" to "Couldn't turn it into text",
        "transcribeNote" to "It runs inside the phone (no internet). What language is the audio in?", "transcribeNo" to "This phone can't turn audio into text by itself (it needs Android 13 and Google's offline speech recognition). You can use Dictate while you speak.",
        "pending" to "To-dos", "pendingHint" to "Everything left to do, from all your notes", "pendingEmpty" to "No to-dos left! 🎉", "seeAll" to "See all",
        "reminder" to "Reminder", "reminders" to "Reminders", "in1h" to "In 1 hour", "in3h" to "In 3 hours", "today7" to "Today 7 PM",
        "tomorrow8" to "Tomorrow 8 AM", "tomorrow6" to "Tomorrow 6 AM", "pickDateTime" to "Pick day and time", "removeReminder" to "Remove",
        "reminderSet" to "I'll remind you: {w}", "tomorrow" to "Tomorrow",
        "share" to "Share", "shareImg" to "Image (as it looks)", "sharePdf" to "PDF", "shareText" to "Text only", "shareSms" to "Text message (SMS)",
        "shareHint" to "Gmail, WhatsApp, Outlook, messages…", "preparing" to "Preparing…", "shareFail" to "Couldn't prepare it. Try again.",
        "dictate" to "Dictate (voice to text)", "dictateSub" to "Speak and it types itself", "listening" to "Listening…", "stop" to "Stop",
        "noDictation" to "This phone has no speech recognition",
        "hourFmt" to "Time format", "hourAuto" to "Like the phone", "lockApp" to "Lock the app with PIN", "lockAppOn" to "The app asks for your PIN when opened",
        "lockAppOff" to "The app no longer asks for a PIN", "changePin" to "Change PIN", "pinChanged" to "PIN changed", "unlockApp" to "Enter your PIN to get in",
        "anchored" to "Anchored", "anchoredHint" to "The photo is anchored. Tap “Anchored” to move it",
        "photoMoved" to "Photo moved. Tap Anchor to fix it in place", "writeBeside" to "Now you can write next to the photo",
        "textRight" to "text on the right", "textLeft" to "text on the left",
        "drawHint" to "One finger draws · two fingers move the note", "penColor" to "Pen color", "clearDrawing" to "Clear the whole drawing",
        "clearDrawingMsg" to "The whole drawing of this note is deleted. Undo brings it back.",
        "recordNow" to "Record now", "audioFile" to "Upload an audio", "audioFileSub" to "WhatsApp voice note, recorder…",
        "recFail" to "Couldn't record", "pause" to "Pause", "resume" to "Resume", "micPerm" to "To record, allow the microphone",
        "recordVideo" to "Record video", "addingVideo" to "Adding the video…", "addingAudio" to "Adding the audio…", "videoFail" to "Couldn't do it",
        "drawOn" to "Draw on top", "writeOnPhoto" to "Write on the photo", "tapToWrite" to "Tap where you want to write",
        "fileMissing" to "was not in the file", "muted" to "no sound", "mute" to "Remove the sound", "shrink" to "Shrink (smaller file)",
        "working" to "Working…", "shrinking" to "Shrinking… {n}%", "mutedOk" to "Done: the video has no sound now",
        "shrinkNoGain" to "This video is already small", "shrinkOk" to "Done: from {a} to {b}", "newDraw" to "Drawing",
        "calc" to "Calculator", "calcSub" to "Graphs, currency and work hours", "calcGraph" to "Graph", "calcTable" to "Table",
        "calcInsert" to "Insert in note", "calcError" to "Error", "calcStart" to "Start", "calcStep" to "Step",
        "calcNoY" to "Type a function in Y= to see the table", "calcFx" to "Currency", "fxSearch" to "Search currency or country",
        "fxLoading" to "Getting today's rates…", "fxFail" to "Couldn't get the rates. Check your internet.",
        "fxUpdated" to "Today's rates: {d}", "fxOffline" to "No internet: rates from {d}",
        "hrsTab" to "Hours", "hrsTotal" to "Week total", "hrsBreak" to "Break (min)", "hrsClear" to "Clear the week",
        "hrsHint" to "Set the start and end time of each day. Shifts that pass midnight are counted right too.",
        "hrsIn" to "Start", "hrsOut" to "End", "calcCopied" to "Result copied (paste it anywhere)", "calcInserted" to "Done, it's in the note",
        "vault" to "Vault", "vaultHint" to "Encrypted passwords, photos and files",
        "vPass" to "Password", "vMail" to "Email", "vLink" to "Link", "vNote" to "Secret note", "vPhoto" to "Photo", "vVideo" to "Video", "vFile" to "File",
        "vPasss" to "Passwords", "vMails" to "Emails", "vLinks" to "Links", "vNotes" to "Notes", "vPhotos" to "Photos", "vVideos" to "Videos", "vFiles" to "Files",
        "vCreateTitle" to "Create your vault", "vCreateMsg" to "Everything you keep here is encrypted with your password. Nobody can see it without it.",
        "vPassNew" to "Password (at least 6)", "vPassAgain" to "Repeat the password", "vCreate" to "Create vault",
        "vShort" to "The password needs at least 6 characters", "vMismatch" to "The passwords don't match",
        "vFail" to "Something went wrong. Try again.", "vForgetWarn" to "If you forget the password, there is no way to get back what's inside.",
        "vLocked" to "The vault is locked", "vPassword" to "Password", "vOpening" to "Opening…", "vOpen" to "Open",
        "vWrong" to "Wrong password", "vForgot" to "Forgot the password?", "vReset" to "Erase the vault",
        "vResetMsg" to "EVERYTHING in the vault is erased and can't be undone. Then you can create a new one.",
        "vSearch" to "Search the vault", "vAll" to "All", "vEmpty" to "The vault is empty.\nTap + to keep something.", "vNoMatch" to "Nothing found",
        "vCopiedPass" to "Password copied", "vCopied" to "Copied", "vAdd" to "What do you want to keep?",
        "vTooBig" to "It's too big (max {n} MB)", "vSaving" to "Encrypting and saving…", "vSaved" to "Saved in the vault",
        "vfTitle" to "Title", "vfUser" to "Username", "vfPass" to "Password", "vfUrl" to "Website", "vfEmail" to "Email", "vfNote" to "Note",
        "vGenerated" to "Strong password created", "vView" to "View", "vOpenFile" to "Open", "vNew" to "New", "vDelete" to "Delete from the vault?",
        "vNoApp" to "No app can open this", "vChangePass" to "Change password", "vPassChanged" to "Password changed",
        "vImported" to "The vault came too (open it with the same password).", "vImportExists" to "The vault wasn't moved because you already have another vault here.",
        "appearance" to "Appearance", "wallPhoto" to "Your photo", "wallBlur" to "Blur my photo",
        "lightSoon" to "Light mode is coming soon",
        "favShort" to "Favorite", "pinShort" to "Pin", "colorShort" to "Color", "pinnedOk" to "Pinned on top 📌", "unpinnedOk" to "Not pinned anymore",
        "favAdded" to "Added to favorites ⭐", "favRemoved" to "Removed from favorites",
        "history" to "History", "historySub" to "Log and versions of this note", "spellCheck" to "Check spelling", "spellCheckSub" to "One by one, with suggestions",
        "noteWall" to "Note background", "noteWallSub" to "A photo or a gradient behind the text", "aiFree" to "Free, no account",
        "recordShort" to "Record", "dictShort" to "Dictate", "fileShort" to "File", "editShort" to "Edit", "wallNone" to "Remove",
        "saveVersionNow" to "Save version now", "versionSaved" to "Version saved 🕘", "versionExists" to "That version is already saved",
        "noVersions" to "Tap “Save version now” to keep an exact copy of the note.",
        "latest" to "newest", "versionAuto" to "Automatic", "versionMine" to "Saved by you", "emptyNote" to "(empty note)",
        "protectHist" to "Protect history with PIN", "histWithPin" to "History asks for the PIN 🔒", "histNoPin" to "History doesn't ask for the PIN anymore",
        "restore" to "Restore", "versionRestoreTitle" to "Go back to this version?", "versionRestoreMsg" to "What you have now isn't lost: it stays in the 📖 Log.",
        "versionDelTitle" to "Delete this version?", "versionDeleted" to "Version deleted", "versionRestored" to "Done, you went back to that version ✨",
        "spellChecking" to "Checking spelling…", "spellFail" to "Couldn't check. Check your internet.", "spellNone" to "Everything is spelled right!",
        "spellDone" to "Done! You checked everything", "spellTitle" to "SPELLING", "spellNoSug" to "No suggestions", "spellIgnore" to "Ignore",
        "insert" to "Add to the note", "textStyle" to "Text style", "strike" to "Strikethrough",
        "histIntro" to "The Log automatically keeps everything you write, even if you delete it. Versions are saved whenever you want.",
        "record" to "Log", "recLines" to "{n} line(s) · updates by itself", "savedVersions" to "Saved versions",
        "recIntro" to "Everything you've written in this note, even if you deleted it", "putAllInNote" to "Put all in the note",
        "putAllMsg" to "The note is replaced by everything in the log (you can undo with ↶).", "putAllDone" to "Done, the whole log is in the note ✨",
        "recClear" to "Clear", "recClearMsg" to "This note's log is deleted. The note isn't touched.", "recCleared" to "Log cleared",
        "recEmpty" to "Nothing yet. Start writing and it saves by itself.",
        "appearanceSub" to "Pick your app background",
    )
}
