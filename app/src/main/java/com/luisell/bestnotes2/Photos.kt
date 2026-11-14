package com.luisell.bestnotes2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date

/* ============================================================
   Photos.kt — fotos para las notas:
   - Al ponerla se achica a un tamaño razonable (máx. 2048 px):
     la nota pesa menos, abre más rápido y se manda más rápido.
   - Se endereza sola (las fotos del teléfono vienen "giradas").
   - Sello: fecha y hora en que se tomó + el sitio (título de la nota).
   - Girar y recortar crean un archivo nuevo (la foto original no se toca).
   ============================================================ */
object Photos {
    const val MAX_SIDE = 2048

    class Result(val file: String, val w: Int, val h: Int, val taken: Long)

    /** Copia la foto [uri] a la carpeta de la app, achicada y derecha. */
    fun import(ctx: Context, uri: Uri): Result? {
        val cr = ctx.contentResolver
        var orient = ExifInterface.ORIENTATION_NORMAL
        var taken = 0L
        try {
            cr.openInputStream(uri)?.use { inp ->
                val ex = ExifInterface(inp)
                orient = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val d = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
                if (d != null) taken = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(d)?.time ?: 0L
            }
        } catch (_: Exception) {}
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        if (o.outWidth <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= MAX_SIDE) s *= 2
        var bm = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
        val big = maxOf(bm.width, bm.height)
        if (big > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / big
            bm = Bitmap.createScaledBitmap(bm, (bm.width * k).toInt(), (bm.height * k).toInt(), true)
        }
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        if (!m.isIdentity) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        val name = save(bm)
        return Result(name, bm.width, bm.height, if (taken > 0) taken else System.currentTimeMillis())
    }

    private fun save(bm: Bitmap): String {
        val name = "f" + newId() + ".jpg"
        val f = File(Store.mediaDir, name)
        FileOutputStream(f).use { bm.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return name
    }

    /** Gira la foto 90° (a la derecha). Devuelve el archivo nuevo. */
    fun rotate90(file: String): Result? {
        val bm = BitmapFactory.decodeFile(File(Store.mediaDir, file).path) ?: return null
        val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(90f) }, true)
        return Result(save(r), r.width, r.height, 0L)
    }

    /** Recorta la foto. [l],[t],[r],[b] van de 0 a 1. */
    fun crop(file: String, l: Float, t: Float, r: Float, b: Float): Result? {
        val bm = BitmapFactory.decodeFile(File(Store.mediaDir, file).path) ?: return null
        val x = (l * bm.width).toInt().coerceIn(0, bm.width - 1)
        val y = (t * bm.height).toInt().coerceIn(0, bm.height - 1)
        val w = ((r - l) * bm.width).toInt().coerceIn(1, bm.width - x)
        val h = ((b - t) * bm.height).toInt().coerceIn(1, bm.height - y)
        val c = Bitmap.createBitmap(bm, x, y, w, h)
        return Result(save(c), c.width, c.height, 0L)
    }

    /** Texto del sello, igual que en Best Notes: 📅 fecha y hora · 📍 sitio. */
    fun stampText(taken: Long, site: String): String {
        val f = SimpleDateFormat(if (L.lang == "es") "EEE, d MMM yyyy, h:mm a" else "EEE, MMM d, yyyy, h:mm a", L.locale)
        val s = site.trim().replace('\n', ' ')
        return "📅 " + f.format(Date(if (taken > 0) taken else System.currentTimeMillis())) + if (s.isNotEmpty()) "  ·  📍 $s" else ""
    }

    fun stampOn(ctx: Context) = ctx.getSharedPreferences("ajustes", 0).getBoolean("stampOn", true)
    fun setStampOn(ctx: Context, on: Boolean) { ctx.getSharedPreferences("ajustes", 0).edit().putBoolean("stampOn", on).apply() }
}
