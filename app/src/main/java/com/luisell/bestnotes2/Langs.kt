package com.luisell.bestnotes2

import org.json.JSONObject

/* ============================================================
   Langs.kt — 🌍 más idiomas. Español e inglés vienen en la app;
   los otros se traducen UNA vez con la IA gratis (Pollinations)
   y se guardan en el teléfono. Si algo falta, sale en inglés.
   ============================================================ */
object Langs {
    private val NAMES = mapOf(
        "pt" to "Brazilian Portuguese", "fr" to "French", "de" to "German", "it" to "Italian", "ca" to "Catalan", "nl" to "Dutch",
        "sv" to "Swedish", "nb" to "Norwegian Bokmål", "da" to "Danish", "fi" to "Finnish", "pl" to "Polish", "cs" to "Czech",
        "sk" to "Slovak", "hu" to "Hungarian", "ro" to "Romanian", "hr" to "Croatian", "el" to "Greek", "bg" to "Bulgarian",
        "ru" to "Russian", "uk" to "Ukrainian", "tr" to "Turkish", "ar" to "Arabic", "he" to "Hebrew", "fa" to "Persian",
        "ur" to "Urdu", "hi" to "Hindi", "bn" to "Bengali", "id" to "Indonesian", "ms" to "Malay", "fil" to "Filipino",
        "vi" to "Vietnamese", "th" to "Thai", "ko" to "Korean", "ja" to "Japanese", "zh-Hans" to "Simplified Chinese",
        "zh-Hant" to "Traditional Chinese", "sw" to "Swahili",
    )

    /** Traduce (o termina de traducir) [code]. [onProgress] recibe 0..100. Devuelve true si quedó casi todo. Corre en segundo plano. */
    fun translate(code: String, onProgress: (Int) -> Unit): Boolean {
        val f = L.langFile(code) ?: return false
        val done = try { JSONObject(f.takeIf { it.exists() }?.readText() ?: "{}") } catch (_: Exception) { JSONObject() }
        // los textos de la app + la guía + las plantillas
        Templates.builtIn().forEach { it.blocks() }
        val en = L.enMap() + GuideActivity.enStrings() + Templates.EN_STRINGS
        val todo = en.keys.filter { !done.has(it) }
        val name = NAMES[code] ?: code
        val sys = "You are a professional mobile-app translator. Translate the VALUES of the JSON object from English to $name. " +
            "Keep exactly the same keys. Keep emojis, symbols, line breaks (\\n) and placeholders like {n}, {d}, {q}, {t}, {e} exactly as they are. " +
            "Do not translate the app name \"Best Notes\". Use short, natural words for buttons. Return ONLY the JSON object, nothing else."
        var fails = 0
        for ((i, chunk) in todo.chunked(45).withIndex()) {
            val src = JSONObject(); chunk.forEach { src.put(it, en[it]) }
            val out = Ai.ask(sys, src.toString(), 90000)
            val got = try { val s = out!!; JSONObject(s.substring(s.indexOf('{'), s.lastIndexOf('}') + 1)) } catch (_: Exception) { null }
            if (got == null) fails++ else for (k in chunk) got.optString(k).takeIf { it.isNotBlank() }?.let { done.put(k, it) }
            f.writeText(done.toString())                       // se guarda poco a poco (si se corta, sigue después)
            onProgress(((i + 1) * 100 / maxOf(1, (todo.size + 44) / 45)).coerceAtMost(100))
            if (fails >= 4) break
        }
        return done.length() >= en.size * 0.8
    }
}
