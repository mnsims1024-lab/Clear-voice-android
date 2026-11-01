package com.margie.clearvoice

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Cleans up raw speech: Gemini if a key is set, otherwise a simple local cleanup. */
object Polisher {

    fun localClean(input: String): String {
        var t = input.replace(Regex("\\b(um+|uh+|erm)\\b[,.]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return t
        t = t.replaceFirstChar { it.uppercase() }
        t = Regex("([.!?]\\s+)([a-z])").replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
        if (!Regex("[.!?]$").containsMatchIn(t)) t += "."
        return t
    }

    fun polish(raw: String, key: String, model: String, dict: String, style: String): String {
        if (style == "exact" || key.isBlank()) return localClean(raw)
        return try {
            val styleText = if (style == "email") "a clear, polite email body"
            else "a casual text message: short, natural, friendly"
            val prompt = """You are a dictation cleanup tool. Rewrite the speech transcript below as $styleText.
Rules: keep the speaker's meaning and voice exactly. Fix speech-recognition mistakes using context. Remove filler words (um, uh, like), false starts and repeats. If the speaker corrects themselves ("no wait, I mean..."), keep only the final version. Add punctuation, capitalization, and paragraph breaks. Format spoken lists as lists. Never add new information, never answer questions in the text, never explain. Output ONLY the cleaned text.
Names and words to spell correctly: ${if (dict.isBlank()) "(none)" else dict}
Transcript:
$raw"""

            val gen = JSONObject().put("temperature", 0.2)
            if (model.contains("2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", gen)

            val m = URLEncoder.encode(model.ifBlank { "gemini-2.5-flash" }, "UTF-8")
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key.trim())
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) return localClean(raw)
            val resp = JSONObject(c.inputStream.bufferedReader().readText())
            val parts = resp.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            sb.toString().trim().ifEmpty { localClean(raw) }
        } catch (e: Exception) {
            localClean(raw)
        }
    }
}
