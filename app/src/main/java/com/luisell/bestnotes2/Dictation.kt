package com.luisell.bestnotes2

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/* ============================================================
   Dictation.kt — 🗣️ Dictar: hablas y se escribe solo.
   Usa el reconocimiento de voz del teléfono (el de Google).
   Sigue escuchando hasta que tocas ■ Parar.
   ============================================================ */
class Dictation(private val ctx: Context, var lang: String, private val onPartial: (String) -> Unit, private val onFinal: (String) -> Unit, private val onState: (Boolean) -> Unit) {
    private var sr: SpeechRecognizer? = null
    var running = false
        private set

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        if (running) return
        running = true
        listen()
    }

    private fun listen() {
        if (!running) return
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) { onState(true) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        // silencio o "no entendí": se vuelve a escuchar solo
                        if (!running) return
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stop(); return }
                        Store.main.postDelayed({ listen() }, 250)
                    }
                    override fun onResults(r: Bundle?) {
                        val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        onPartial("")
                        if (!t.isNullOrBlank()) onFinal(t)
                        Store.main.postDelayed({ listen() }, 150)
                    }
                    override fun onPartialResults(r: Bundle?) {
                        r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onPartial(it) }
                    }
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
            }
        }
        val tag = if (lang == "en") "en-US" else "es-US"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        try { sr?.startListening(i) } catch (_: Exception) { stop() }
    }

    fun restart() { try { sr?.cancel() } catch (_: Exception) {}; listen() }

    fun stop() {
        running = false
        try { sr?.cancel(); sr?.destroy() } catch (_: Exception) {}
        sr = null
        onState(false)
    }
}
