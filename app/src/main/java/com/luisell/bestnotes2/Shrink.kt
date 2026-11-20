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
    /** Qué tanto achicar: 0 = Normal (720p), 1 = Liviano (480p), 2 = Mínimo (360p, 15 cuadros, sonido mono: queda en KB). */
    class Info(val w: Int, val h: Int, val durMs: Long, val fps: Float, val bytes: Long)
    class Plan(val shortSide: Int, val fps: Float, val vbps: Long, val abps: Int, val mono: Boolean)

    /** Las medidas REALES del video (ancho y alto como se ve, cuánto dura, cuadros por segundo, cuánto pesa). */
    fun probe(video: String, w0: Int = 0, h0: Int = 0): Info {
        val src = File(Store.mediaDir, video)
        var vw = w0; var vh = h0; var rot = 0; var durMs = 0L; var fps = 30f
        try {
            val r = android.media.MediaMetadataRetriever(); r.setDataSource(src.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { vw = it }
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let { vh = it }
            rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            durMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.let { if (it > 1) fps = it }
            r.release()
        } catch (_: Exception) {}
        if (rot == 90 || rot == 270) { val t = vw; vw = vh; vh = t }      // como se ve en la pantalla
        if (vw <= 0 || vh <= 0) { vw = 1280; vh = 720 }
        return Info(vw, vh, durMs, fps, src.length())
    }

    fun plan(i: Info, level: Int): Plan {
        val (short, fps, k, cap, ab) = when (level) {
            2 -> listOf(360, 15f, 0.04, 260_000L, 32_000)
            1 -> listOf(480, 30f, 0.05, 1_200_000L, 64_000)
            else -> listOf(720, 30f, 0.07, 3_000_000L, 0)
        }
        short as Int; fps as Float; k as Double; cap as Long; ab as Int
        val sh = minOf(short, minOf(i.w, i.h))
        val scale = sh.toFloat() / minOf(i.w, i.h)
        val tw = (i.w * scale).toInt(); val th = (i.h * scale).toInt()
        val f = minOf(fps, i.fps)
        var v = (tw.toLong() * th * f * k).toLong().coerceIn(if (level == 2) 120_000L else 300_000L, cap)
        val orig = if (i.durMs > 0) i.bytes * 8 * 1000 / i.durMs else 0L
        if (orig > 0) v = minOf(v, (orig * 0.55).toLong()).coerceAtLeast(100_000L)
        return Plan(sh, f, v, ab, level == 2)
    }

    /** La ventanita: Normal / Liviano / Mínimo, cada uno con cuánto quedaría (para uno o varios videos). */
    fun askLevel(ctx: Context, files: List<String>, onPick: (Int) -> Unit) {
        Loader.pool.execute {
            val infos = files.map { probe(it) }
            val now = infos.sumOf { it.bytes }
            val est = (0..2).map { lv -> infos.sumOf { i -> minOf(estimate(i, lv), i.bytes) } }
            Store.main.post {
                val names = listOf(L.t("shrinkL0"), L.t("shrinkL1"), L.t("shrinkL2"))
                val subs = listOf(L.t("shrinkS0"), L.t("shrinkS1"), L.t("shrinkS2"))
                val levels = (0..2).filter { est[it] > 0 && est[it] < now * 0.92 }.ifEmpty { listOf(2) }
                val labels = levels.map { lv -> names[lv] + "  ·  ≈ " + MediaFiles.sizeText(est[lv]) + "\n" + subs[lv] }
                ctx.dialog().setTitle("🎬 " + L.t("shrinkHow"))
                    .setMessage(L.t("shrinkNow", "a" to MediaFiles.sizeText(now)))
                    .setItems(labels.toTypedArray()) { _, k -> onPick(levels[k]) }
                    .setNegativeButton(L.t("cancel"), null).show()
            }
        }
    }

    /** Cuánto va a pesar más o menos (bytes). */
    fun estimate(i: Info, level: Int): Long {
        if (i.durMs <= 0) return 0
        val p = plan(i, level)
        return (p.vbps + (if (p.abps > 0) p.abps else 128_000)) * i.durMs / 8000 + 30_000
    }

    /** [onProgress] 0..100; [onDone] recibe el archivo nuevo o null si no se pudo. */
    fun run(ctx: Context, video: String, w: Int, h: Int, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) = run(ctx, video, w, h, 0, onProgress, onDone)

    fun run(ctx: Context, video: String, w: Int, h: Int, level: Int, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) {
        val name = "v" + newId() + ".mp4"
        val out = File(Store.mediaDir, name)
        val info = probe(video, w, h)
        val pl = plan(info, level)
        val th = (info.h * pl.shortSide.toFloat() / minOf(info.w, info.h)).toInt() / 2 * 2
        val effects = ArrayList<androidx.media3.common.Effect>()
        effects.add(Presentation.createForHeight(th))
        if (info.fps > pl.fps + 1f) effects.add(androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(pl.fps))
        // 🔉 sonido: en Liviano y Mínimo también se achica (en Mínimo, una sola bocina = mono)
        val audio = ArrayList<androidx.media3.common.audio.AudioProcessor>()
        if (pl.mono) audio.add(androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(1, 1))
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(2, 1))
        })
        else if (pl.abps > 0) audio.add(androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(1, 1))
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(2, 2))
        })
        val main = Handler(Looper.getMainLooper())
        main.post {
            var done = false
            val base = androidx.media3.transformer.DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(pl.vbps.toInt()).build())
                .setEnableFallback(true).build()
            val enc = if (pl.abps <= 0) base else object : androidx.media3.transformer.Codec.EncoderFactory {
                override fun createForAudioEncoding(format: androidx.media3.common.Format) =
                    base.createForAudioEncoding(format.buildUpon().setAverageBitrate(pl.abps).setPeakBitrate(pl.abps).build())
                override fun createForVideoEncoding(format: androidx.media3.common.Format) = base.createForVideoEncoding(format)
            }
            val t = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(enc)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { done = true; onDone(name) }
                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) { done = true; out.delete(); onDone(null) }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(Store.mediaDir, video))))
                .setEffects(Effects(audio, effects))
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
