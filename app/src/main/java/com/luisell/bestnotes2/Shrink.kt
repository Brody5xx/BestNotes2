package com.luisell.bestnotes2

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/* ============================================================
   Shrink.kt — "Achicar" un video: lo vuelve a hacer más
   pequeño (lado corto de 720 px) para que pese menos y se
   mande más rápido por WhatsApp o correo. Se usa el chip de
   video del teléfono (rápido y no gasta mucha batería).
   ============================================================ */
@OptIn(UnstableApi::class)
object Shrink {
    /** [onProgress] 0..100; [onDone] recibe el archivo nuevo o null si no se pudo. */
    fun run(ctx: Context, video: String, w: Int, h: Int, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) {
        val name = "v" + newId() + ".mp4"
        val out = File(Store.mediaDir, name)
        val short = minOf(w, h).takeIf { it > 0 } ?: 720
        val k = if (short > 720) 720f / short else 1f
        val tw = ((if (w > 0) w else 1280) * k).toInt() / 2 * 2
        val th = ((if (h > 0) h else 720) * k).toInt() / 2 * 2
        val main = Handler(Looper.getMainLooper())
        main.post {
            var done = false
            val t = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { done = true; onDone(name) }
                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) { done = true; out.delete(); onDone(null) }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(Store.mediaDir, video))))
                .setEffects(Effects(listOf(), listOf(Presentation.createForWidthAndHeight(tw, th, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            try { t.start(item, out.path) } catch (e: Exception) { onDone(null); return@post }
            val ph = ProgressHolder()
            val tick = object : Runnable {
                override fun run() {
                    if (done) return
                    if (t.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(ph.progress)
                    main.postDelayed(this, 500)
                }
            }
            main.postDelayed(tick, 500)
        }
    }
}
