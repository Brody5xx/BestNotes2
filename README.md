# Best Notes 2 🗒️

App de notas **100% nativa para Android (Kotlin)**, rápida y fluida.
Es la nueva versión de Best Notes. Mientras se termina se llama **Best Notes 2**
y se instala al lado de la app vieja (sin tocarla). Cuando tenga todo, vuelve a llamarse **Best Notes**.

Developer: **Luisell Peña Rodríguez**

## 📥 Descargar (siempre la última versión)

👉 https://github.com/Brody5xx/BestNotes2/releases/download/ultima-version/BestNotes2.apk

Cada cambio que se sube a este repositorio se construye solo con GitHub Actions
y reemplaza el archivo de ese enlace (tarda unos 4-6 minutos).
Está firmado siempre con la misma llave: cada versión nueva se instala **encima** sin perder nada.

## Pasar tus notas desde Best Notes
1. En Best Notes (la app vieja): menú ☰ → **Exportar todo para Best Notes 2**.
2. Pon una contraseña y compártelo con **Best Notes 2**.
3. En Best Notes 2 escribe la misma contraseña. Listo.

Se puede repetir cuando quieras: si una nota ya está, se queda la más nueva.

## Partes
1. ✅ Pantalla principal, notas de texto y checklist, búsqueda iluminada, importar.
2. Fotos (con su posición), colores y tamaños de letra.
3. Audios, videos y dibujar.
4. Dictado, IA (Pollinations), compartir como imagen o PDF, respaldo, bóveda, recordatorios,
   calculadora, reporte del día, guía, idiomas, bloqueo, etc.

## Archivos (app/src/main/java/com/luisell/bestnotes2)
- `MainActivity.kt` — pantalla principal, menú ☰, buscar, carpetas, etiquetas, papelera, importar.
- `NoteActivity.kt` — la pantalla de una nota (barra de formato, deshacer, ⋮ opciones).
- `Editor.kt` — el editor nativo por bloques (texto, listas, fotos, audio/video).
- `RichText.kt` — texto con estilos (negrita, colores, tamaños…).
- `Model.kt` — cómo se guarda una nota por dentro.
- `Store.kt` — la base de datos (SQLite) y la carpeta de fotos/audios/videos.
- `Backup.kt` + `HtmlImport.kt` — abrir el archivo de Best Notes y convertir las notas.
- `Pin.kt` — PIN de 4 números (el mismo de Best Notes).
- `L.kt` — textos en español e inglés.
- `GuideActivity.kt` — 📖 guía de la app. **Regla:** cada función nueva se explica aquí.
- `app/src/test` — pruebas automáticas (se corren antes de construir).
