package com.luisell.bestnotes2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/* ============================================================
   Anim.kt — ✨ animacioncitas para que todo se sienta vivo:
   - tarjetas que llegan una tras otra,
   - botones que se hunden al tocarlos y rebotan,
   - estrellitas cuando terminas un pendiente o abres con el PIN,
   - cosas que flotan suavecito.
   ============================================================ */
object Anim {
    /** Llega desde abajo, un poquito chiquito, y rebota al llegar. */
    fun enter(v: View, delay: Long = 0, fromY: Float = 36f) {
        val d = v.resources.displayMetrics.density
        v.alpha = 0f; v.translationY = fromY * d; v.scaleX = 0.94f; v.scaleY = 0.94f
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(420)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    /** Aparece "inflándose" desde cero. */
    fun popIn(v: View, delay: Long = 0) {
        v.scaleX = 0f; v.scaleY = 0f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(delay).setDuration(380).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    /** Un saltito (para cuando algo cambia). */
    fun bump(v: View, k: Float = 1.18f) {
        v.animate().cancel()
        v.animate().scaleX(k).scaleY(k).setDuration(110).setInterpolator(DecelerateInterpolator())
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start() }.start()
    }

    /** Al tocar se hunde un poquito y al soltar rebota (no quita el toque normal). */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View, k: Float = 0.96f) {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(k).scaleY(k).setDuration(110).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    x.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    /** Flota suavecito para arriba y para abajo (mientras se vea). */
    fun float(v: View, ampDp: Float = 6f) {
        val d = v.resources.displayMetrics.density
        (v.getTag(R.id.w_root) as? ValueAnimator)?.cancel()
        val a = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 3200; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { if (!v.isAttachedToWindow) cancel() else v.translationY = Math.sin((it.animatedValue as Float).toDouble()).toFloat() * ampDp * d }
        }
        v.setTag(R.id.w_root, a); a.start()
    }

    /** ✨ Estrellitas que salen de una vista. */
    fun sparkle(v: View, radiusDp: Float = 46f, ms: Long = 650) {
        val act = v.context as? Activity ?: return
        val deco = act.window.decorView as? ViewGroup ?: return
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val d = v.resources.displayMetrics.density
        val sp = SparkleView(act, Fx.ring(act, loc[0] + v.width / 2f, loc[1] + v.height / 2f, radiusDp * d))
        deco.addView(sp, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sp.play(ms)
    }
}
