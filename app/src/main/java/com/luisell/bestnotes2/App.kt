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
        Store.load { }          // la lista se empieza a cargar desde el primer momento
        // Fotos viejas que ya no usa ninguna nota (por ejemplo, después de recortar): se borran
        Store.io.execute { try { Store.cleanMedia() } catch (_: Exception) {} }
    }
}
