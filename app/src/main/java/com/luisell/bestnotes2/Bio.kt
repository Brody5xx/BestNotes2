package com.luisell.bestnotes2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.CancellationSignal
import android.view.View

/* ============================================================
   Bio.kt — 👆 abrir con la HUELLA o la CARA (lo que tenga el teléfono).
   El PIN sigue siendo el respaldo: si la huella falla, escribes el PIN.
   Se puede apagar en el menú ☰ → "Huella o cara".
   ============================================================ */
object Bio {
    /** ¿El teléfono tiene huella/cara configurada? (Android 10 o más nuevo) */
    fun available(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val bm = ctx.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
            val ok = android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK) == ok
            else @Suppress("DEPRECATION") (bm.canAuthenticate() == ok)
        } catch (_: Throwable) { false }
    }

    fun enabled(ctx: Context) = ctx.getSharedPreferences("ajustes", 0).getBoolean("bio", true)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences("ajustes", 0).edit().putBoolean("bio", on).apply()

    /** ¿Se usa ahora? (el teléfono la tiene y no la apagaste) */
    fun use(ctx: Context) = enabled(ctx) && available(ctx)

    /** Pide la huella/cara. [onOk] si salió bien. */
    fun ask(ctx: Context, title: String, onOk: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            val b = android.hardware.biometrics.BiometricPrompt.Builder(ctx)
                .setTitle(title)
                .setSubtitle(L.t("bioSub"))
                .setNegativeButton(L.t("bioUsePin"), ctx.mainExecutor) { _, _ -> }
            // la cara abre sola, sin tener que tocar "Confirmar" después
            if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false)
            // acepta también la cara "normal" del teléfono (no solo la más segura)
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            val p = b.build()
            p.authenticate(CancellationSignal(), ctx.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { onOk() }
            })
        } catch (_: Throwable) {}
    }
}

/** El dibujito de la huella (para la tecla del teclado del PIN). */
class FingerView(ctx: Context) : View(ctx) {
    var color = T.text; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) * 0.42f; val cx = width / 2f; val cy = height / 2f + s * 0.08f
        p.color = color; p.strokeWidth = s * 0.09f
        // arcos de la huella, uno dentro de otro
        val arcs = listOf(1.0f to (200f to 140f), 0.78f to (190f to 160f), 0.56f to (180f to 180f), 0.34f to (170f to 200f), 0.12f to (160f to 220f))
        for ((r, a) in arcs) {
            val rr = s * r
            c.drawArc(RectF(cx - rr, cy - rr * 1.15f, cx + rr, cy + rr * 1.15f), a.first, a.second, false, p)
        }
    }
}
