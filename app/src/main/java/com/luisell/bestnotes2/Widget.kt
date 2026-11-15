package com.luisell.bestnotes2

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/* ============================================================
   Widget.kt — 🏠 el widget de la pantalla de inicio.
   Muestra tus notas fijadas 📌 (o las últimas que usaste) y un botón +
   para crear una nota nueva. Se actualiza solo cuando cambias algo.
   ============================================================ */
class NotesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { Store.init(ctx); Store.load { NotesWidget.update(ctx) } }

    companion object {
        const val EXTRA_OPEN = "abrir_nota"
        const val EXTRA_NEW = "nota_nueva"
        private val rows = listOf(Triple(R.id.r0, R.id.t0, R.id.s0), Triple(R.id.r1, R.id.t1, R.id.s1), Triple(R.id.r2, R.id.t2, R.id.s2), Triple(R.id.r3, R.id.t3, R.id.s3))
        private var pending = false

        /** Lo pide la app cada vez que cambia algo (con una pausita para no hacerlo mil veces). */
        fun later(ctx: Context) {
            if (pending) return
            pending = true
            Store.main.postDelayed({ pending = false; update(ctx) }, 1500)
        }

        /** Las notas que se ven: primero las fijadas, luego las más recientes. */
        fun pick(): List<NoteMeta> = Store.notes.filter { !it.deleted }.sortedWith(compareByDescending<NoteMeta> { it.pinned }.thenByDescending { it.updated }).take(4)

        fun update(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, NotesWidget::class.java))
                if (ids.isEmpty()) return
                val v = RemoteViews(ctx.packageName, R.layout.widget)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val home = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(ctx, 900, home, flags))
                v.setOnClickPendingIntent(R.id.w_new, PendingIntent.getActivity(ctx, 901, Intent(home).putExtra(EXTRA_NEW, true), flags))
                val list = pick()
                v.setViewVisibility(R.id.w_empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.w_empty, L.t("widgetEmpty"))
                for ((i, r) in rows.withIndex()) {
                    val n = list.getOrNull(i)
                    if (n == null) { v.setViewVisibility(r.first, View.GONE); continue }
                    v.setViewVisibility(r.first, View.VISIBLE)
                    v.setTextViewText(r.second, (if (n.pinned) "📌 " else "") + (if (n.locked) "🔒 " else "") + n.title.ifBlank { L.t("untitled") })
                    val sub = when {
                        n.locked -> L.t("lockedNote")
                        n.checkTotal > 0 -> "✓ ${n.checkDone}/${n.checkTotal} · " + n.plain.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                        else -> n.plain.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifEmpty { L.when_(n.updated) }
                    }
                    v.setTextViewText(r.third, sub)
                    v.setOnClickPendingIntent(r.first, PendingIntent.getActivity(ctx, 910 + i, Intent(home).putExtra(EXTRA_OPEN, n.id).setData(android.net.Uri.parse("bestnotes2://nota/" + n.id)), flags))
                }
                mgr.updateAppWidget(ids, v)
            } catch (_: Throwable) {}
        }
    }
}
