package com.luisell.bestnotes2

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/* ============================================================
   Media.kt — 🎙️ audios y 🎬 videos:
   - grabar audio (AAC, buena calidad y poco peso)
   - escuchar dentro de la nota (un solo audio a la vez)
   - copiar videos/audios del teléfono a la nota
   - 🔇 quitar el sonido a un video y "Achicar" (pesa menos)
   ============================================================ */
object MediaFiles {
    class Info(val file: String, val dur: Double, val size: Long, val w: Int = 0, val h: Int = 0)

    fun extOf(ctx: Context, uri: Uri, video: Boolean): String {
        val t = (ctx.contentResolver.getType(uri) ?: "").lowercase()
        return when {
            t.contains("webm") -> if (video) "webm" else "weba"
            t.contains("3gpp") -> "3gp"
            t.contains("ogg") || t.contains("opus") -> "ogg"
            t.contains("mpeg") && !video -> "mp3"
            t.contains("wav") -> "wav"
            t.contains("aac") -> "aac"
            t.contains("quicktime") -> "mov"
            video -> "mp4"
            else -> "m4a"
        }
    }

    /** Copia un audio o video a la carpeta de la app. */
    fun copyIn(ctx: Context, uri: Uri, video: Boolean): Info? {
        val name = (if (video) "v" else "a") + newId() + "." + extOf(ctx, uri, video)
        val f = File(Store.mediaDir, name)
        ctx.contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(f).use { inp.copyTo(it, 1 shl 16) } } ?: return null
        if (f.length() == 0L) { f.delete(); return null }
        return info(name)
    }

    fun info(name: String): Info {
        val f = File(Store.mediaDir, name)
        var dur = 0.0; var w = 0; var h = 0
        try {
            MediaMetadataRetriever().apply {
                setDataSource(f.path)
                dur = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                release()
            }
        } catch (_: Exception) {}
        return Info(name, dur, f.length(), w, h)
    }

    /** Portada del video (se guarda como "<video>.thumb.jpg"). */
    fun thumbName(video: String) = "$video.thumb.jpg"

    fun makeThumb(video: String): Boolean {
        val out = File(Store.mediaDir, thumbName(video))
        if (out.exists() && out.length() > 0) return true
        return try {
            val r = MediaMetadataRetriever(); r.setDataSource(File(Store.mediaDir, video).path)
            var bm: Bitmap? = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
            r.release()
            val b = bm ?: return false
            val k = 720f / maxOf(b.width, b.height)
            if (k < 1f) bm = Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
            FileOutputStream(out).use { bm!!.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            true
        } catch (_: Exception) { false }
    }

    fun sizeText(bytes: Long): String = if (bytes >= 1_000_000) String.format(L.locale, "%.1f MB", bytes / 1_000_000.0) else "${maxOf(1, bytes / 1000)} KB"

    fun timeText(sec: Double): String { val s = sec.toInt(); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    /** 🔇 Copia el video sin la pista de sonido (no se vuelve a comprimir: es rápido). */
    fun removeAudio(video: String): String? {
        val src = File(Store.mediaDir, video)
        val name = "v" + newId() + ".mp4"
        val out = File(Store.mediaDir, name)
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(src.path)
            var vt = -1
            for (i in 0 until ex.trackCount) if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { vt = i; break }
            if (vt < 0) return null
            val fmt = ex.getTrackFormat(vt)
            mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                MediaMetadataRetriever().apply { setDataSource(src.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { mux.setOrientationHint(it) }; release() }
            } catch (_: Exception) {}
            val t = mux.addTrack(fmt)
            ex.selectTrack(vt)
            mux.start()
            val buf = ByteBuffer.allocate(if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                info.size = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                info.offset = 0; info.presentationTimeUs = ex.sampleTime
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mux.writeSampleData(t, buf, info)
                ex.advance()
            }
            mux.stop()
            return name
        } catch (_: Exception) {
            out.delete(); return null
        } finally {
            try { mux?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}

/** Convierte un audio (m4a, mp3, ogg…) en WAV de 16 kHz mono para pasarlo a texto. Máximo [maxSec] segundos. */
object Wav {
    fun fromFile(f: File, maxSec: Int = 600): ByteArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            var t = -1
            for (i in 0 until ex.trackCount) if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { t = i; break }
            if (t < 0) return null
            ex.selectTrack(t)
            val fmt = ex.getTrackFormat(t)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            var acc = 0.0                                   // para bajar a 16 kHz
            val step = { r: Int -> r / 16000.0 }
            var limit = 16000L * maxSec
            var written = 0L
            while (!outDone && written < limit) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat; rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sh = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = sh.remaining() / ch
                    val k = step(rate)
                    for (fI in 0 until frames) {
                        acc += 1.0
                        if (acc >= k) {
                            acc -= k
                            var sum = 0
                            for (c in 0 until ch) sum += sh.get(fI * ch + c).toInt()
                            val v = (sum / ch).toShort()
                            out.write(v.toInt() and 0xFF); out.write((v.toInt() shr 8) and 0xFF)
                            written++
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            codec.stop(); codec.release()
            val pcm = out.toByteArray()
            if (pcm.isEmpty()) return null
            return header(pcm.size) + pcm
        } catch (_: Exception) { return null } finally { ex.release() }
    }

    private fun header(n: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + n); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(n)
        return b.array()
    }
}

/**
 * 🎙️→📝 Pasar un audio a texto DENTRO del teléfono (sin internet).
 * Usa el reconocimiento de voz del teléfono (Android 13 o más nuevo).
 */
object Transcriber {
    fun available(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    /** [onDone] recibe el texto (o null). Se llama en la pantalla. */
    fun run(ctx: Context, file: File, lang: String, onDone: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { onDone(null); return }
        Thread {
            val wav = Wav.fromFile(file)
            Store.main.post {
                if (wav == null || wav.size <= 44) { onDone(null); return@post }
                start(ctx, wav.copyOfRange(44, wav.size), lang, onDone)
            }
        }.start()
    }

    @android.annotation.TargetApi(33)
    private fun start(ctx: Context, pcm: ByteArray, lang: String, onDone: (String?) -> Unit) {
        val sr = try { android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (_: Exception) { onDone(null); return }
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val text = StringBuilder()
        var finished = false
        fun end() {
            if (finished) return
            finished = true
            try { sr.destroy() } catch (_: Exception) {}
            try { pipe[0].close() } catch (_: Exception) {}
            onDone(text.toString().trim().ifEmpty { null })
        }
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { end() }
            override fun onResults(r: android.os.Bundle?) {
                r?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text.append(it).append(' ') }
                end()
            }
            override fun onPartialResults(r: android.os.Bundle?) {}
            override fun onEvent(t: Int, b: android.os.Bundle?) {}
            override fun onSegmentResults(r: android.os.Bundle) {
                r.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) text.append(it.trim()).append(". ") }
            }
            override fun onEndOfSegmentedSession() { end() }
        })
        val tag = if (lang == "en") "en-US" else "es-US"
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            .putExtra(android.speech.RecognizerIntent.EXTRA_SEGMENTED_SESSION, android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE)
        try { sr.startListening(i) } catch (_: Exception) { end(); return }
        // el audio se le pasa por un "tubo", como si alguien estuviera hablando
        Thread {
            try { android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { o -> var p = 0; while (p < pcm.size) { val n = minOf(32000, pcm.size - p); o.write(pcm, p, n); p += n } } } catch (_: Exception) {}
        }.start()
        Store.main.postDelayed({ end() }, 15 * 60_000L)
    }
}

/** 🎙️ Grabadora (una a la vez). */
class Recorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var file: String? = null
        private set
    var startedAt = 0L
    private var pausedTotal = 0L
    private var pausedAt = 0L
    var paused = false
        private set

    fun start(): Boolean {
        val name = "a" + newId() + ".m4a"
        val f = File(Store.mediaDir, name)
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.path)
            r.prepare(); r.start()
            rec = r; file = name; startedAt = System.currentTimeMillis(); pausedTotal = 0; paused = false
            true
        } catch (_: Exception) { f.delete(); false }
    }

    fun elapsed(): Long = (if (paused) pausedAt else System.currentTimeMillis()) - startedAt - pausedTotal

    fun togglePause() {
        val r = rec ?: return
        if (!paused) { r.pause(); pausedAt = System.currentTimeMillis(); paused = true }
        else { r.resume(); pausedTotal += System.currentTimeMillis() - pausedAt; paused = false }
    }

    /** Termina. Devuelve el archivo (o null si salió mal). */
    fun stop(): String? {
        val r = rec ?: return null
        rec = null
        return try { r.stop(); r.release(); file } catch (_: Exception) { r.release(); file?.let { File(Store.mediaDir, it).delete() }; null }
    }

    fun cancel() { stop()?.let { File(Store.mediaDir, it).delete() } }
}

/** 🔊 Un solo audio sonando a la vez en toda la app. */
object AudioPlayer {
    private var mp: MediaPlayer? = null
    var file: String? = null
        private set
    private val listeners = ArrayList<() -> Unit>()
    var speed = 1f

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun tell() = listeners.toList().forEach { it() }

    fun isPlaying(f: String) = file == f && (mp?.isPlaying == true)
    fun position(f: String): Int = if (file == f) (try { mp?.currentPosition } catch (_: Exception) { 0 } ?: 0) else 0

    fun toggle(f: String) {
        val p = mp
        if (file == f && p != null) {
            if (p.isPlaying) p.pause() else { applySpeed(p); p.start() }
            tell(); return
        }
        stop()
        try {
            val n = MediaPlayer()
            n.setDataSource(File(Store.mediaDir, f).path)
            n.setOnCompletionListener { stop() }
            n.prepare()
            applySpeed(n)
            n.start()
            mp = n; file = f
        } catch (_: Exception) { mp = null; file = null }
        tell()
    }

    private fun applySpeed(p: MediaPlayer) {
        try { p.playbackParams = p.playbackParams.setSpeed(speed) } catch (_: Exception) {}
    }

    fun setSpeedNow(s: Float) { speed = s; mp?.let { if (it.isPlaying) applySpeed(it) }; tell() }

    fun seek(f: String, ms: Int) { if (file == f) try { mp?.seekTo(ms) } catch (_: Exception) {} }

    fun stop() {
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release(); mp = null; file = null
        tell()
    }
}
