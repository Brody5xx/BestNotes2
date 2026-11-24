package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* ============================================================
   Places.kt — 📍 Notas por lugar. Cada nota recuerda DÓNDE se
   escribió (solo en tu teléfono, nunca se manda a ningún lado).
   Cuando llegas a un sitio, la pantalla principal te muestra
   las notas y pendientes de ese lugar.
   ============================================================ */
object Places {
    /** Qué tan cerca cuenta como "el mismo lugar" (metros). */
    const val RADIUS = 250f
    private val map = HashMap<String, DoubleArray>()       // nota → [lat, lng]
    private var loaded = false
    @Volatile var lastHere: Location? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "lugares.json")
    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(file(ctx).takeIf { it.exists() }?.readText() ?: return)
            o.keys().forEach { k -> o.optJSONArray(k)?.let { a -> map[k] = doubleArrayOf(a.optDouble(0), a.optDouble(1)) } }
        } catch (_: Exception) {}
    }
    @Synchronized private fun save(ctx: Context) {
        try { val o = JSONObject(); map.forEach { (k, v) -> o.put(k, JSONArray().put(v[0]).put(v[1])) }; file(ctx).writeText(o.toString()) } catch (_: Exception) {}
    }

    fun allowed(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun has(ctx: Context, id: String): Boolean { load(ctx); return synchronized(this) { map.containsKey(id) } }

    /** Dónde estás ahora (lo más reciente que sepa el teléfono). */
    @SuppressLint("MissingPermission")
    fun here(ctx: Context, onDone: (Location?) -> Unit) {
        if (!allowed(ctx)) { onDone(null); return }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = try { lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.time } } catch (_: Exception) { null }
        // si lo último que sabe es viejo (más de 3 minutos), pide uno nuevo
        if (best != null && System.currentTimeMillis() - best.time < 180_000) { lastHere = best; onDone(best); return }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val prov = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
            if (prov != null) try {
                lm.getCurrentLocation(prov, null, ctx.mainExecutor) { l -> val r = l ?: best; if (r != null) lastHere = r; onDone(r) }
                return
            } catch (_: Exception) {}
        }
        if (best != null) lastHere = best
        onDone(best)
    }

    /** Guarda dónde se escribió la nota (solo la primera vez). */
    fun remember(ctx: Context, id: String) {
        if (!allowed(ctx) || has(ctx, id)) return
        val app = ctx.applicationContext
        here(app) { l -> if (l != null) { synchronized(this) { map[id] = doubleArrayOf(l.latitude, l.longitude) }; save(app) } }
    }

    /** 📍 Guardar esta nota en el lugar donde estás AHORA (aunque ya tuviera otro). */
    fun setHere(ctx: Context, id: String, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        here(app) { l -> if (l == null) onDone(false) else { load(app); synchronized(this) { map[id] = doubleArrayOf(l.latitude, l.longitude) }; save(app); onDone(true) } }
    }
    /** Quitarle el lugar a una nota. */
    fun forget(ctx: Context, id: String) { load(ctx); synchronized(this) { map.remove(id) }; save(ctx) }

    /** Todas las notas que tienen lugar (nota → [lat, lng]). */
    fun all(ctx: Context): Map<String, DoubleArray> { load(ctx); return synchronized(this) { HashMap(map) } }

    /** Las notas escritas cerca de [l]. */
    fun near(ctx: Context, l: Location?): Set<String> {
        if (l == null) return emptySet()
        load(ctx)
        val r = FloatArray(1)
        return synchronized(this) { map.filter { (_, v) -> Location.distanceBetween(l.latitude, l.longitude, v[0], v[1], r); r[0] <= RADIUS }.keys.toSet() }
    }
}
