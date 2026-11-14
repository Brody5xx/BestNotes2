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

    private fun pad(ctx: Context, title: String, onDone: (String) -> Unit) {
        val e = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            gravity = Gravity.CENTER; textSize = 28f; letterSpacing = 0.6f
            setTextColor(T.text)
        }
        val box = FrameLayout(ctx).apply { setPadding(ctx.dp(40), ctx.dp(8), ctx.dp(40), 0); addView(e) }
        val d = ctx.dialog().setTitle(title).setView(box).setNegativeButton(L.t("cancel"), null).create()
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable) {
                if (s.length == 4) { val v = s.toString(); e.postDelayed({ d.dismiss(); onDone(v) }, 120) }
            }
        })
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show(); e.requestFocus()
    }

    /** Pide el PIN y llama [onOk] si es correcto. Si no hay PIN todavía, lo crea. */
    fun check(ctx: Context, onOk: () -> Unit) {
        val h = saved(ctx)
        if (h == null) { create(ctx, onOk); return }
        pad(ctx, L.t("pinToUnlock")) { v -> if (hash(v) == h) onOk() else ctx.toast(L.t("pinWrong")) }
    }

    fun create(ctx: Context, onOk: () -> Unit) {
        pad(ctx, L.t("pinCreate")) { a ->
            pad(ctx, L.t("pinConfirm")) { b ->
                if (a == b) {
                    ctx.getSharedPreferences("ajustes", 0).edit().putString("pinHash", hash(a)).apply()
                    onOk()
                } else { ctx.toast(L.t("pinMismatch")); create(ctx, onOk) }
            }
        }
    }
}
