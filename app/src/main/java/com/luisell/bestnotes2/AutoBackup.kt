package com.luisell.bestnotes2

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/* ============================================================
   AutoBackup.kt — 💾 RESPALDO AUTOMÁTICO cada semana.
   Escoges una carpeta del teléfono y una contraseña una sola vez;
   después la app guarda ahí un respaldo cada 7 días, sola
   (se guardan los 3 últimos, los más viejos se borran).
   ============================================================ */
object AutoBackup {
    private const val JOB = 7301
    const val WEEK = 7 * 86_400_000L
    const val PREFIX = "BestNotes2_auto_"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ajustes", 0)
    fun on(ctx: Context) = prefs(ctx).getString("autoBkDir", null) != null && prefs(ctx).getString("autoBkPass", null) != null
    fun last(ctx: Context) = prefs(ctx).getLong("autoBkLast", 0L)

    fun enable(ctx: Context, tree: Uri, pass: String) {
        try { ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        prefs(ctx).edit().putString("autoBkDir", tree.toString()).putString("autoBkPass", pass).apply()
        schedule(ctx)
    }

    fun disable(ctx: Context) {
        prefs(ctx).getString("autoBkDir", null)?.let { u -> try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(u), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {} }
        prefs(ctx).edit().remove("autoBkDir").remove("autoBkPass").apply()
        try { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB) } catch (_: Exception) {}
    }

    /** Una vez al día Android pregunta "¿ya toca?" (solo hace el respaldo si pasaron 7 días). */
    fun schedule(ctx: Context) {
        if (!on(ctx)) return
        try {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB) != null) return
            js.schedule(JobInfo.Builder(JOB, ComponentName(ctx, AutoBackupJob::class.java))
                .setPeriodic(86_400_000L).setPersisted(true).setRequiresBatteryNotLow(true).build())
        } catch (_: Exception) {}
    }

    fun due(ctx: Context) = on(ctx) && System.currentTimeMillis() - last(ctx) >= WEEK

    /** Hace el respaldo ahora (en segundo plano). Devuelve true si salió bien. */
    @Synchronized
    fun run(ctx: Context): Boolean {
        val dir = prefs(ctx).getString("autoBkDir", null)?.let { Uri.parse(it) } ?: return false
        val pass = prefs(ctx).getString("autoBkPass", null) ?: return false
        val name = PREFIX + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"
        val tmp = File(File(ctx.cacheDir, "auto").apply { mkdirs() }, name)
        return try {
            var loaded = Store.loaded
            if (!loaded) { val l = java.util.concurrent.CountDownLatch(1); Store.load { l.countDown() }; l.await(30, java.util.concurrent.TimeUnit.SECONDS) }
            Backup.exportFile(ctx, pass, tmp) {}
            val cr = ctx.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(dir, DocumentsContract.getTreeDocumentId(dir))
            val doc = DocumentsContract.createDocument(cr, parent, "application/json", name) ?: return false
            cr.openOutputStream(doc)!!.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
            prefs(ctx).edit().putLong("autoBkLast", System.currentTimeMillis()).putLong("lastBackup", System.currentTimeMillis()).apply()
            cleanOld(ctx, dir)
            true
        } catch (_: Throwable) { false } finally { tmp.delete() }
    }

    /** Deja solo los 3 respaldos automáticos más nuevos. */
    private fun cleanOld(ctx: Context, tree: Uri) {
        val cr = ctx.contentResolver
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Pair<String, String>>()
        cr.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) { val n = c.getString(1) ?: continue; if (n.startsWith(PREFIX)) found.add(c.getString(0) to n) }
        }
        found.sortedByDescending { it.second }.drop(3).forEach { (id, _) -> try { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } catch (_: Exception) {} }
    }
}

class AutoBackupJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        if (!AutoBackup.due(this)) return false
        Thread { AutoBackup.run(applicationContext); jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}
