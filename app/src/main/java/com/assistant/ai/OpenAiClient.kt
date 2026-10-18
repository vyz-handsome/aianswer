package com.assistant.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Klien untuk API berformat OpenAI (Mistral, Groq, OpenRouter). Blocking: panggil dari thread background. */
object OpenAiClient {
    @Volatile private var active: HttpURLConnection? = null

    /** Putuskan panggilan bubble yang sedang berjalan. Panggil dari thread background. */
    fun abort() {
        active?.let { runCatching { it.disconnect() } }
    }

    fun generate(
        p: Provider,
        key: String,
        model: String,
        text: String,
        imageBase64: String?,
        instructions: String,
        json: Boolean,
        temperature: Double = 0.0,
        maxTokens: Int = 0
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", instructions))
        messages.put(JSONObject().put("role", "user").put("content", userContent(text, imageBase64)))
        return send(p, key, model, messages, json, true, temperature, maxTokens)
    }

    /** Chat banyak giliran. Hanya 2 gambar terbaru yang dikirim ulang. Tidak ikut diputus oleh [abort]. */
    fun chat(p: Provider, key: String, model: String, history: List<ChatTurn>, instructions: String): String {
        val withImage = history.indices.filter { !history[it].imageBase64.isNullOrEmpty() }.takeLast(2).toSet()
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", instructions))
        for ((i, turn) in history.withIndex()) {
            if (turn.role == "model") {
                messages.put(JSONObject().put("role", "assistant").put("content", turn.text))
            } else {
                val img = if (i in withImage) turn.imageBase64 else null
                messages.put(JSONObject().put("role", "user").put("content", userContent(turn.text, img)))
            }
        }
        return send(p, key, model, messages, false, false)
    }

    private fun userContent(text: String, imageBase64: String?): Any {
        if (imageBase64.isNullOrEmpty()) return text
        val arr = JSONArray()
        arr.put(JSONObject().put("type", "text").put("text", text))
        arr.put(
            JSONObject().put("type", "image_url").put(
                "image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
            )
        )
        return arr
    }

    private fun send(
        p: Provider,
        key: String,
        model: String,
        messages: JSONArray,
        json: Boolean,
        trackForAbort: Boolean,
        temperature: Double = 0.0,
        maxTokens: Int = 0
    ): String {
        val body = JSONObject().put("model", model).put("messages", messages)
        if (maxTokens > 0) body.put("max_tokens", maxTokens)
        if (json) {
            body.put("temperature", temperature)
            // Tidak semua model gratis di OpenRouter mendukung response_format; di sana andalkan instruksi di prompt.
            if (p != Provider.OPENROUTER) body.put("response_format", JSONObject().put("type", "json_object"))
        }
        val payload = body.toString()

        val conn = URL(p.endpoint).openConnection() as HttpURLConnection
        try {
            if (trackForAbort) active = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val msg = try {
                    val o = JSONObject(resp)
                    o.optJSONObject("error")?.optString("message") ?: o.optString("message").ifEmpty { null }
                } catch (_: Exception) { null }
                val friendly = when (code) {
                    401, 403 -> "Akses ditolak ($code). Cek API key ${p.label} di Pengaturan. ${msg.orEmpty()}"
                    402 -> "Saldo atau kuota ${p.label} habis (402). ${msg.orEmpty()}"
                    404 -> "Model \"$model\" tidak ditemukan di ${p.label} (404); mungkin sudah tidak gratis lagi. Pilih model lain. ${msg.orEmpty()}"
                    413 -> "Permintaan terlalu besar untuk ${p.label} (413). ${msg.orEmpty()}"
                    429 -> "Kena batas pemakaian gratis ${p.label} (429). Tunggu sebentar lalu coba lagi. ${msg.orEmpty()}"
                    else -> "Error $code dari ${p.label}: ${msg ?: resp.take(300)}"
                }
                throw GeminiException(code, friendly)
            }
            val answer = parseText(resp)
            if (answer.isBlank()) throw GeminiException(-1, "Respons kosong dari AI.")
            return answer
        } finally {
            if (trackForAbort && active === conn) active = null
            conn.disconnect()
        }
    }

    private fun parseText(json: String): String {
        val message = JSONObject(json)
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val content = message.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    sb.append(content.optJSONObject(i)?.optString("text").orEmpty())
                }
                sb.toString()
            }
            else -> ""
        }
        // Beberapa model "thinking" menyertakan proses berpikir di dalam teks.
        return text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }
}
