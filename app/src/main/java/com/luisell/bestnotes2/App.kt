package com.luisell.bestnotes2

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        L.init(this)
        Store.load { }          // la lista se empieza a cargar desde el primer momento
    }
}
