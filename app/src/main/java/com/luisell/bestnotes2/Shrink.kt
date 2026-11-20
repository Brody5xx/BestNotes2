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
        val src = File(Store.mediaDir, video)
        // 📏 medidas REALES del video (ancho, alto, si viene girado, cuánto dura y qué tan "pesado" es por segundo)
        var vw = w; var vh = h; var rot = 0; var durMs = 0L; var fps = 30f
        try {
            val r = android.media.MediaMetadataRetriever(); r.setDataSource(src.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { vw = it }
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let { vh = it }
            rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            durMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (android.os.Build.VERSION.SDK_INT >= 23) r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.let { if (it > 1) fps = it }
            r.release()
        } catch (_: Exception) {}
        if (rot == 90 || rot == 270) { val t = vw; vw = vh; vh = t }      // como se ve en la pantalla
        if (vw <= 0 || vh <= 0) { vw = 1280; vh = 720 }
        // el lado corto a 720 (sin deformar)
        val short = minOf(vw, vh)
        val k = if (short > 720) 720f / short else 1f
        val th = (vh * k).toInt() / 2 * 2
        val tw = (vw * k).toInt() / 2 * 2
        // 🎯 cuántos datos por segundo: lo justo para 720p a 30 cuadros, y siempre bastante menos que el original
        val origBps = if (durMs > 0) (src.length() * 8 * 1000 / durMs) else 0L
        var bps = (tw.toLong() * th * 30 * 0.07).toLong().coerceIn(700_000L, 3_000_000L)
        if (origBps > 0) bps = minOf(bps, (origBps * 0.55).toLong()).coerceAtLeast(400_000L)
        val effects = ArrayList<androidx.media3.common.Effect>()
        effects.add(Presentation.createForHeight(th))
        if (fps > 31f) effects.add(androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(30f))   // 60 → 30 cuadros
        val main = Handler(Looper.getMainLooper())
        main.post {
            var done = false
            val enc = androidx.media3.transformer.DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(bps.toInt()).build())
                .setEnableFallback(true).build()
            val t = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(enc)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { done = true; onDone(name) }
                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) { done = true; out.delete(); onDone(null) }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(Store.mediaDir, video))))
                .setEffects(Effects(listOf(), effects))
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
