package com.luisell.bestnotes2

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/* ============================================================
   Reminders.kt — ⏰ recordatorios: un aviso a una hora para una
   nota o un pendiente. Suena aunque la app esté cerrada, y se
   vuelve a programar solo si apagas y prendes el teléfono.
   Al tocar el aviso se abre la nota.
   ============================================================ */
object Reminders {
    class R(val id: Int, val noteId: String, val text: String, val at: Long)

    private const val KEY = "recordatorios"
    const val CHANNEL = "recordatorios"

    fun all(ctx: Context): MutableList<R> {
        val out = ArrayList<R>()
        try {
            val a = JSONArray(ctx.getSharedPreferences("ajustes", 0).getString(KEY, "[]"))
            for (k in 0 until a.length()) a.optJSONObject(k)?.let { out.add(R(it.optInt("id"), it.optString("n"), it.optString("t"), it.optLong("at"))) }
        } catch (_: Exception) {}
        return out
    }

    private fun save(ctx: Context, l: List<R>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.noteId).put("t", it.text).put("at", it.at)) }
        ctx.getSharedPreferences("ajustes", 0).edit().putString(KEY, a.toString()).apply()
    }

    fun forNote(ctx: Context, noteId: String) = all(ctx).filter { it.noteId == noteId && it.at > System.currentTimeMillis() }

    fun add(ctx: Context, noteId: String, text: String, at: Long) {
        val l = all(ctx).filter { it.at > System.currentTimeMillis() - 86_400_000 }.toMutableList()
        val r = R((System.currentTimeMillis() % 1_000_000_000).toInt(), noteId, text, at)
        l.add(r); save(ctx, l)
        schedule(ctx, r)
    }

    fun remove(ctx: Context, id: Int) {
        val l = all(ctx); l.firstOrNull { it.id == id }?.let { cancel(ctx, it) }
        save(ctx, l.filter { it.id != id })
    }

    private fun pi(ctx: Context, r: R): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", r.id).putExtra("n", r.noteId).putExtra("t", r.text)
        return PendingIntent.getBroadcast(ctx, r.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(ctx: Context, r: R) {
        if (r.at <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi(ctx, r))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi(ctx, r))
    }

    private fun cancel(ctx: Context, r: R) { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, r)) }

    fun rescheduleAll(ctx: Context) { all(ctx).forEach { schedule(ctx, it) } }

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("reminders"), NotificationManager.IMPORTANCE_HIGH))
    }

    fun show(ctx: Context, id: Int, noteId: String, text: String) {
        L.init(ctx); channel(ctx)
        val open = Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("⏰ " + L.t("reminder"))
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) {}
        save(ctx, all(ctx).filter { it.id != id })
    }

    /** Horas rápidas: En 1 hora, Hoy 7 PM, Mañana 8 AM… */
    fun quick(): List<Pair<String, Long>> {
        val now = Calendar.getInstance()
        val out = ArrayList<Pair<String, Long>>()
        out.add(L.t("in1h") to now.timeInMillis + 3_600_000)
        out.add(L.t("in3h") to now.timeInMillis + 3 * 3_600_000)
        val t7 = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        if (t7.after(now)) out.add(L.t("today7") to t7.timeInMillis)
        val m8 = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        out.add(L.t("tomorrow8") to m8.timeInMillis)
        val m6 = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        out.add(L.t("tomorrow6") to m6.timeInMillis)
        return out
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Reminders.show(ctx, i.getIntExtra("id", 0), i.getStringExtra("n") ?: return, i.getStringExtra("t") ?: "")
    }
}

/** Al prender el teléfono: se vuelven a programar los recordatorios. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == "android.intent.action.MY_PACKAGE_REPLACED") { Reminders.rescheduleAll(ctx); Weekly.schedule(ctx); Arrive.refresh(ctx) }
    }
}
