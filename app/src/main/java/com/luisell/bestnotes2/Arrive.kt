package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/* ============================================================
   Arrive.kt — 🔔 AVISO AL LLEGAR. Cuando llegas a un lugar donde
   tienes notas con pendientes, el teléfono te avisa solo
   ("📍 Tienes 3 pendientes aquí"), aunque la app esté cerrada.
   Usa "geocercas" del teléfono: gastan muy poca batería.
   Todo se queda en tu teléfono.
   ============================================================ */
object Arrive {
    const val EXTRA = "llegaste"
    private const val ID = 7800

    fun on(ctx: Context) = ctx.getSharedPreferences("ajustes", 0).getBoolean("arriveOn", false)
    fun set(ctx: Context, v: Boolean) { ctx.getSharedPreferences("ajustes", 0).edit().putBoolean("arriveOn", v).apply(); refresh(ctx) }

    /** ¿Tiene permiso de ubicación "todo el tiempo"? */
    fun background(ctx: Context) = Places.allowed(ctx) &&
        (android.os.Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, ID, Intent(ctx, ArriveReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    /** Los lugares con pendientes (las notas cercanas se juntan en un solo lugar). */
    fun spots(ctx: Context): List<DoubleArray> {
        val pend = Store.notes.filter { !it.deleted && !it.locked && it.checkTotal > it.checkDone }.map { it.id }.toHashSet()
        val out = ArrayList<DoubleArray>()
        val r = FloatArray(1)
        for ((id, v) in Places.all(ctx)) {
            if (id !in pend) continue
            if (out.none { o -> Location.distanceBetween(o[0], o[1], v[0], v[1], r); r[0] < Places.RADIUS }) out.add(v)
        }
        return out.take(90)
    }

    /** Vuelve a poner las geocercas (o las quita si está apagado). */
    @SuppressLint("MissingPermission")
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val client = try { LocationServices.getGeofencingClient(app) } catch (_: Throwable) { return }
        try { client.removeGeofences(pi(app)) } catch (_: Throwable) {}
        if (!on(app) || !background(app)) return
        Store.load {
            val s = spots(app)
            if (s.isEmpty()) return@load
            val fences = s.mapIndexed { i, v ->
                Geofence.Builder().setRequestId("bn_$i").setCircularRegion(v[0], v[1], 200f)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER).setNotificationResponsiveness(60_000).build()
            }
            val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
            try { client.addGeofences(req, pi(app)) } catch (_: Throwable) {}
        }
    }

    private var lastLater = 0L
    /** Después de cambios (pendientes o lugares nuevos), pero sin hacerlo a cada rato. */
    fun later(ctx: Context) {
        if (!on(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastLater < 15_000) return
        lastLater = now
        Store.main.postDelayed({ refresh(ctx) }, 4000)
    }

    /** 🔔 Llegaste: cuenta los pendientes de aquí y avisa. */
    fun arrived(ctx: Context, at: Location) {
        val p = ctx.getSharedPreferences("ajustes", 0)
        val ids = Places.near(ctx, at)
        val notes = Store.notes.filter { it.id in ids && !it.deleted && !it.locked && it.checkTotal > it.checkDone }
        if (notes.isEmpty()) return
        // no avisar del mismo lugar más de una vez cada 3 horas
        val key = "arr_" + notes.map { it.id }.sorted().joinToString(",").hashCode()
        if (System.currentTimeMillis() - p.getLong(key, 0) < 3 * 3_600_000L) return
        p.edit().putLong(key, System.currentTimeMillis()).apply()
        val n = notes.sumOf { it.checkTotal - it.checkDone }
        L.init(ctx); Reminders.channel(ctx)
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = notes.take(4).joinToString("\n") { m -> "• " + m.title.ifBlank { L.t("untitled") } + "  (☐ ${m.checkTotal - m.checkDone})" }
        val nb = android.app.Notification.Builder(ctx, Reminders.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("📍 " + L.t("arriveNotif", "n" to n))
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(ID, nb) } catch (_: SecurityException) {}
    }
}

class ArriveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val ev = try { GeofencingEvent.fromIntent(i) } catch (_: Throwable) { null } ?: return
        if (ev.hasError() || ev.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val at = ev.triggeringLocation ?: return
        val app = ctx.applicationContext
        Store.init(app)
        val done = goAsync()
        Store.load { try { Arrive.arrived(app, at) } finally { done.finish() } }
    }
}
