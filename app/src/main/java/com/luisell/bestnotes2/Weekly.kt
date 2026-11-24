package com.luisell.bestnotes2

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/* ============================================================
   Weekly.kt — 📅 RESUMEN DEL VIERNES. Cada viernes a las 4 PM
   te llega un aviso; lo tocas y la IA arma una nota con tu
   semana: lo que hiciste, lo que falta y los materiales.
   También se puede hacer en cualquier momento (IA → Resumen
   de la semana). Las notas bloqueadas no se usan.
   ============================================================ */
object Weekly {
    const val EXTRA = "resumenSemana"
    private const val ID = 7700

    fun on(ctx: Context) = ctx.getSharedPreferences("ajustes", 0).getBoolean("weeklyOn", true)
    fun set(ctx: Context, v: Boolean) { ctx.getSharedPreferences("ajustes", 0).edit().putBoolean("weeklyOn", v).apply(); schedule(ctx) }

    /** El próximo viernes a las 4 PM (o hoy, si es viernes y todavía no son las 4). */
    fun nextFriday(now: Calendar = Calendar.getInstance()): Long {
        val c = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 16); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY || !c.after(now)) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, ID, Intent(ctx, WeeklyReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(ctx))
        if (on(ctx)) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFriday(), pi(ctx))
    }

    /** 🔔 El aviso del viernes. */
    fun notify(ctx: Context) {
        L.init(ctx); Reminders.channel(ctx)
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val p = PendingIntent.getActivity(ctx, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, Reminders.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("📅 " + L.t("weekNotifT"))
            .setContentText(L.t("weekNotifB"))
            .setContentIntent(p).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(ID, n) } catch (_: SecurityException) {}
    }

    /** Las notas de los últimos 7 días (para la IA). */
    fun weekText(): Pair<Int, String> {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        val l = Store.notes.filter { !it.deleted && !it.locked && (it.updated >= since || it.created >= since) }.sortedBy { it.updated }
        val df = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.US)
        val txt = buildString {
            for (n in l) {
                append("## ").append(n.title.ifBlank { "Note" }).append(" (").append(df.format(java.util.Date(n.updated))).append(")\n")
                append(n.plain.take(1500)).append("\n")
                if (n.checkTotal > 0) append("(to-dos: ${n.checkDone} of ${n.checkTotal} done)\n")
                append("\n")
            }
        }.take(14000)
        return l.size to txt
    }

    private fun sys(lang: String) = "You write a weekly work summary from the user's notes of the last 7 days. " +
        "Use ONLY what is in the notes. Write it in ${Ai.langName(lang)}. Use exactly these sections, each title on its own line starting with \"# \": " +
        "1) what was done this week, 2) pending / still to do, 3) materials used, 4) next week. Under each, short bullet lines starting with \"- \" " +
        "(include places, devices and numbers when mentioned). If a section has nothing, write one line saying so. No ** symbols, no extra introduction."

    /** Arma el resumen con la IA y lo guarda como una nota nueva (y la abre). */
    fun make(act: Activity) {
        val (n, txt) = weekText()
        if (n == 0) { act.toast(L.t("weekNone")); return }
        val wait = act.dialog().setMessage("📅 " + L.t("weekWorking", "n" to n)).setCancelable(false).show()
        val lang = L.lang
        Thread {
            val r = Ai.ask(sys(lang), txt, 90000)?.let { Ai.clean(it) }
            act.runOnUiThread {
                wait.dismiss()
                if (r.isNullOrBlank()) { act.toast(L.t("aiFail")); return@runOnUiThread }
                val title = L.t("weekNoteTitle") + " · " + java.text.SimpleDateFormat("d MMM", L.locale).format(java.util.Date())
                val m = Store.createNote(title, aiToBlocks(r))
                Store.changed()
                Confetti.burst(act)
                act.startActivity(Intent(act, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id))
            }
        }.start()
    }
}

class WeeklyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Store.init(ctx)
        Weekly.notify(ctx)
        Weekly.schedule(ctx)          // y el del próximo viernes
    }
}
