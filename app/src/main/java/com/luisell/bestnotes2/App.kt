package com.luisell.bestnotes2

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        L.init(this)
        T.light = getSharedPreferences("ajustes", 0).getString("theme", "dark") == "light"
        AppLock.install(this)
        Reminders.channel(this)
        Store.load {           // la lista se empieza a cargar desde el primer momento
            try {
                Store.purgeTrash()                          // 🗑️ lo que lleva 30 días en la papelera se borra solo
                AutoBackup.schedule(this)                   // 💾 respaldo automático (si lo activaste)
                if (AutoBackup.due(this)) Thread { AutoBackup.run(this) }.start()
                NotesWidget.update(this)
            } catch (_: Exception) {}
        }
        Store.addListener { NotesWidget.later(this) }      // 🏠 el widget se actualiza solo
        // Fotos viejas que ya no usa ninguna nota (por ejemplo, después de recortar): se borran
        Store.io.execute { try { Store.cleanMedia() } catch (_: Exception) {} }
    }
}
