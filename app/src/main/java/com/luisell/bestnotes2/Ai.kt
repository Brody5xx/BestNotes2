package com.luisell.bestnotes2

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/* ============================================================
   Ai.kt — ✨ la IA gratis de Best Notes (Pollinations), NO Gemini.
   - Los pedidos van de uno en uno (si se mandan juntos, el servicio
     rechaza el segundo) y se reintenta esperando cada vez más.
   - Lo que escribes solo se manda cuando TÚ tocas un botón de IA.
   ============================================================ */
object Ai {
    private val lock = Object()
    private var last = 0L

    private fun post(body: JSONObject, timeout: Int): String? {
        val c = URL("https://text.pollinations.ai/openai").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15000; c.readTimeout = timeout
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            return j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()?.takeIf { it.isNotEmpty() }
        } finally { c.disconnect() }
    }

    private fun getSimple(system: String, text: String, seed: Int): String? {
        val url = "https://text.pollinations.ai/" + URLEncoder.encode(text, "UTF-8") +
            "?model=openai&private=true&referrer=bestnotes&seed=$seed&system=" + URLEncoder.encode(system, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 45000
            if (c.responseCode !in 200..299) return null
            return c.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
        } finally { c.disconnect() }
    }

    /** Pregunta a la IA. Corre en segundo plano (NO en la pantalla). Devuelve null si no se pudo. */
    fun ask(system: String, text: String, timeout: Int = 60000): String? = synchronized(lock) {
        val waits = longArrayOf(0, 5000, 10000, 16000)
        for (w in waits) {
            if (w > 0) Thread.sleep(w)
            val since = System.currentTimeMillis() - last
            if (since < 3000) Thread.sleep(3000 - since)            // un respiro entre un pedido y otro
            last = System.currentTimeMillis()
            val seed = (Math.random() * 1_000_000).toInt()
            try {
                val body = JSONObject().put("model", "openai").put("seed", seed).put("private", true).put("referrer", "bestnotes")
                    .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", text)))
                post(body, timeout)?.let { return@synchronized it }
                body.put("model", "openai-fast")          // por si el nombre del modelo cambió
                post(body, timeout)?.let { return@synchronized it }
            } catch (_: Exception) {}
            if (text.length < 4000) try { getSimple(system, text, seed)?.let { return@synchronized it } } catch (_: Exception) {}
        }
        null
    }

    /** 🌐 Idiomas para la IA: código, cómo se llama en su idioma (lo que ves), y en inglés (lo que entiende la IA). */
    val LANGS = listOf(
        Triple("es", "Español", "Spanish"), Triple("en", "English", "English"), Triple("fr", "Français", "French"),
        Triple("pt", "Português", "Portuguese"), Triple("it", "Italiano", "Italian"), Triple("de", "Deutsch", "German"),
        Triple("zh", "中文 (简体)", "Simplified Chinese"), Triple("zh-TW", "中文 (繁體)", "Traditional Chinese"), Triple("ja", "日本語", "Japanese"),
        Triple("ko", "한국어", "Korean"), Triple("ru", "Русский", "Russian"), Triple("ar", "العربية", "Arabic"),
        Triple("hi", "हिन्दी", "Hindi"), Triple("tl", "Tagalog", "Tagalog"), Triple("vi", "Tiếng Việt", "Vietnamese"),
        Triple("nl", "Nederlands", "Dutch"), Triple("pl", "Polski", "Polish"), Triple("tr", "Türkçe", "Turkish"), Triple("ht", "Kreyòl ayisyen", "Haitian Creole"),
    )
    fun langName(c: String) = LANGS.firstOrNull { it.first == c }?.third ?: "Spanish"
    fun langShow(c: String) = LANGS.firstOrNull { it.first == c }?.second ?: c

    /* ---------- Las mismas instrucciones de Best Notes ---------- */
    val HOW = mapOf(
        "formal" to "Rewrite it in a formal, polite and respectful tone.",
        "pro" to "Rewrite it in a clear, confident, professional tone suitable for work.",
        "biz" to "Rewrite it as polished business communication: concise, results-oriented, with a courteous corporate tone.",
        "friendly" to "Rewrite it in a warm, friendly and relaxed tone.",
        "funny" to "Rewrite it so it is funny and witty, keeping the same meaning. Light, good-natured humor.",
        "persuasive" to "Rewrite it to be persuasive and convincing, with a strong call to action when it fits.",
        "short" to "Rewrite it much shorter and more concise, keeping the key information.",
        "long" to "Rewrite it longer and more detailed, expanding naturally on the ideas without inventing facts.",
        "clear" to "Rewrite it in simple, clear words that are very easy to understand.",
        "fix" to "Correct spelling, grammar and punctuation only. Keep the wording and style as close as possible to the original.",
        "emoji" to "Rewrite it in a lively tone and add fitting emojis.",
        "report" to "Turn these rough field notes into a clear, professional WORK REPORT that a supervisor can understand. Use short section titles on their own line ending with a colon, " +
            "only for the sections that have information: Location, Work performed, Materials used, Issues found, Pending / next steps. Use \"- \" bullets inside sections. " +
            "Write in past tense, formal and precise. Keep every technical detail exactly (device names, cable types, room/door numbers, quantities, times). Do not invent facts. No markdown symbols like ** or #.",
        "pronote" to "Rewrite the whole note in a clear, polished, professional tone for work, fixing grammar and choosing formal vocabulary. Keep the same structure, all details and technical terms. Do not invent facts. No markdown symbols like ** or #.",
        "email" to "Write a professional email to my supervisor based on these notes. Start with a line \"Subject: ...\" then a blank line, a polite greeting, a concise body (use \"- \" bullets for lists of work or pending items), and a courteous closing without a name. Keep all technical details. Do not invent facts. No markdown symbols like ** or #.",
        "chat" to "Write a short, friendly but professional message for coworkers (for WhatsApp or a team chat) explaining this clearly so they understand exactly what was done and what they need to do. Keep key technical details. Use a few \"- \" bullets if helpful. No markdown symbols like ** or #.",
        "english" to "Translate this into clear, professional workplace English (if it is already English, polish it into professional English). Keep all technical details and numbers exactly. No markdown symbols like ** or #.",
        "todo" to "Extract every pending task, follow-up or thing still to do from these notes. Reply with ONE task per line, each starting with a verb, short and specific (include location or device when mentioned). No numbering, no bullets, no extra text. If there are none, reply with the single most logical next step.",
        "summary" to "Summarize this in 2 to 4 short professional sentences with the most important information for a supervisor. No markdown.",
        "steps" to "Explain this as clear, numbered step-by-step instructions (lines \"1. \", \"2. \"...) that a coworker could follow without asking questions. Keep all technical details exactly. Add a short \"Note:\" line at the end only if there is a safety or important warning in the text. No markdown symbols like ** or #.",
        "syn" to "The text is a word or short phrase. Give 6 to 8 more formal and professional alternatives that mean the same in this context, one per line, most common first. Only the alternatives, no numbering, no explanations.",
        "phrase" to "The text is an everyday, informal phrase. Show how to say it professionally at work: give 3 professional versions, one per line, from most common to most formal. Only the versions, no numbering, no explanations.",
    )

    /** Instrucción completa. [lang] = idioma del resultado ("" = el mismo del texto). */
    fun system(style: String, lang: String): String {
        val how = if (style == "translate") (if (lang.isNotEmpty()) "Translate the text into ${langName(lang)}. Keep the meaning and tone." else "If the text is written in Spanish, translate it into English; otherwise translate it into Spanish. Keep the meaning and tone.")
            else HOW[style] ?: HOW["pro"]!!
        val own = style in setOf("report", "pronote", "email", "chat", "english", "todo", "summary", "steps", "syn", "phrase")
        return "You are a writing assistant inside a notes app. " + how +
            (if (style == "translate" || style == "english") "" else if (lang.isNotEmpty()) " The ENTIRE answer must be written in ${langName(lang)} — every sentence, title and list item — translating naturally if the original is in another language (only technical terms, brand names, names and numbers stay as they are)." else " Always answer in the SAME language as the original text.") +
            (if (own) " Reply with ONLY the result, no introductions or explanations." else " Reply with ONLY the rewritten text: no quotes, no titles, no explanations, no options.")
    }

    /** Quita comillas de más y "**" que a veces pone la IA. */
    fun clean(t: String) = t.trim().removeSurrounding("\"").removeSurrounding("“", "”").replace("**", "").trim()
}
