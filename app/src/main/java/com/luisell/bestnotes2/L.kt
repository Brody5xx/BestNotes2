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

    fun init(ctx: Context) {
        choice = ctx.getSharedPreferences("ajustes", 0).getString("lang", "auto") ?: "auto"
        apply()
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
        "guide" to "Guía de la app", "devBy" to "Developer: Luisell Peña Rodríguez", "version" to "Versión {v}",
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
        "guide" to "App guide", "devBy" to "Developer: Luisell Peña Rodríguez", "version" to "Version {v}",
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
    )
}
