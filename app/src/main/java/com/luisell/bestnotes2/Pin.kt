package com.luisell.bestnotes2

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout

/* ============================================================
   Pin.kt — el PIN de 4 números para bloquear notas.
   Se guarda solo una "huella" del número (no el número), con la
   MISMA fórmula que Best Notes: así tu PIN de siempre funciona
   con las notas bloqueadas que importes.
   ============================================================ */
object Pin {
    fun hash(p: String): String {
        var h = 2166136261L.toInt()
        for (r in 0 until 500) {
            val str = "misnotas:" + r + ":" + p + ":" + (h.toLong() and 0xffffffffL)
            for (ch in str) { h = h xor ch.code; h *= 16777619 }
        }
        return java.lang.Long.toString(h.toLong() and 0xffffffffL, 36)
    }

    fun saved(ctx: Context): String? = ctx.getSharedPreferences("ajustes", 0).getString("pinHash", null)?.takeIf { it.isNotEmpty() }

    /** Pide el PIN y llama [onOk] si es correcto. Si no hay PIN todavía, lo crea. */
    fun check(ctx: Context, onOk: () -> Unit) {
        val h = saved(ctx)
        if (h == null) { create(ctx, onOk); return }
        PinPad.show(ctx, L.t("pinToUnlock")) { v -> val ok = hash(v) == h; if (ok) ctx.mainLooperPost { onOk() }; ok }
    }

    /** Cambiar el PIN: primero el actual, luego el nuevo (dos veces). */
    fun change(ctx: Context, onOk: () -> Unit) {
        val h = saved(ctx) ?: run { create(ctx, onOk); return }
        PinPad.show(ctx, L.t("pinCurrent"), L.t("pinCurrentSub")) { v -> val ok = hash(v) == h; if (ok) ctx.mainLooperPost { create(ctx, onOk) }; ok }
    }

    fun create(ctx: Context, onOk: () -> Unit) {
        PinPad.show(ctx, L.t("pinCreate"), L.t("pinCreateSub")) { a ->
            ctx.mainLooperPost {
                PinPad.show(ctx, L.t("pinConfirm"), L.t("pinConfirmSub")) { b ->
                    if (a == b) {
                        ctx.getSharedPreferences("ajustes", 0).edit().putString("pinHash", hash(a)).apply()
                        ctx.mainLooperPost { onOk() }; true
                    } else { ctx.toast(L.t("pinMismatch")); false }
                }
            }
            true
        }
    }

    /** Un ratito después (cuando el teclado ya se cerró). */
    private fun Context.mainLooperPost(f: () -> Unit) { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(f, 220) }
}
