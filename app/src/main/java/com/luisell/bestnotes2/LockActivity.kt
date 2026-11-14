package com.luisell.bestnotes2

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

/* ============================================================
   LockActivity.kt — 🔒 bloqueo de la app con PIN.
   Si está prendido (☰ → Bloquear la app con PIN), la app pide el PIN
   al abrirla y al volver después de más de 1 minuto afuera.
   ============================================================ */
object AppLock {
    private var bgAt = 0L
    private var started = 0
    @Volatile var unlocked = false

    fun on(a: android.content.Context) = a.getSharedPreferences("ajustes", 0).getBoolean("appLock", false) && Pin.saved(a) != null
    fun set(a: android.content.Context, v: Boolean) { a.getSharedPreferences("ajustes", 0).edit().putBoolean("appLock", v).apply(); if (v) unlocked = true }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started == 0 && bgAt > 0 && System.currentTimeMillis() - bgAt > 60_000) unlocked = false
                started++
            }
            override fun onActivityStopped(a: Activity) { started--; if (started == 0) bgAt = System.currentTimeMillis() }
            override fun onActivityResumed(a: Activity) {
                if (a is LockActivity || unlocked || !on(a)) return
                a.startActivity(android.content.Intent(a, LockActivity::class.java))
                a.overridePendingTransition(0, 0)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

class LockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        edgeToEdge(root)
        root.addView(IconView(this, Icons.LOCK, 22f).apply { color = T.text; isClickable = false }, lp(dp(90), dp(90)))
        root.addView(tv(L.t("unlockApp"), 18f, T.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(20)) })
        root.addView(tv(L.t("unlock"), 16f, 0xFF16161A.toInt(), true).apply {
            setPadding(dp(28), dp(12), dp(28), dp(12)); pressBg(T.text, 24f); setOnClickListener { ask() }
        })
        setContentView(root)
        ask()
    }

    private fun ask() { Pin.check(this) { AppLock.unlocked = true; finish(); overridePendingTransition(0, 0) } }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finishAffinity() }
}
