package com.assistant.ai

import android.content.Context

/** Pintu tunggal ke AI: memilih penyedia dan key sesuai model yang dipilih di Pengaturan. */
object AiClient {
    fun selected(c: Context): AiModel = Models.byId(Prefs.modelId(c))

    /** Key sendiri (kalau diisi di Pengaturan) didahulukan; kalau kosong dipakai key bawaan app. */
    fun activeKey(c: Context, m: AiModel = selected(c)): String {
        val own = Prefs.userKey(c, m.provider)
        return if (own.isNotEmpty()) own else Secrets.builtIn(m.provider)
    }

    /** Putuskan panggilan bubble yang sedang berjalan. Panggil dari thread background. */
    fun abort() {
        aborted = true
        GeminiClient.abort()
        OpenAiClient.abort()
    }

    private fun need(c: Context, m: AiModel): String {
        val key = activeKey(c, m)
        if (key.isEmpty()) {
            throw GeminiException(
                401,
                "${m.provider.label} belum punya API key. Buka menu Pengaturan lalu isi key-nya, atau pilih model Gemini."
            )
        }
        return key
    }

    /** Nama model yang benar-benar menjawab panggilan generate terakhir (bisa model cadangan). */
    @Volatile var lastLabel: String = ""

    @Volatile private var aborted = false

    private fun call(
        c: Context,
        m: AiModel,
        text: String,
        imageBase64: String?,
        instructions: String,
        json: Boolean,
        temperature: Double,
        fast: Boolean,
        maxTokens: Int,
        accurate: Boolean
    ): String {
        val key = need(c, m)
        return if (m.provider == Provider.GEMINI) {
            GeminiClient.generate(key, m.model, text, imageBase64, instructions, json, temperature, fast, maxTokens, accurate)
        } else {
            OpenAiClient.generate(m.provider, key, m.model, text, imageBase64, instructions, json, temperature, maxTokens)
        }
    }

    private fun retryable(e: Exception): Boolean = when (e) {
        is GeminiException -> e.code == 429 || e.code == 408 || e.code >= 500
        is java.io.IOException -> true
        else -> false
    }

    /** Model cadangan: satu model Gemini lain (kuota per model terpisah), lalu satu model tiap penyedia lain yang punya key. */
    private fun alternatives(c: Context, m: AiModel): List<AiModel> {
        val out = ArrayList<AiModel>()
        if (m.provider == Provider.GEMINI) {
            Models.ALL.firstOrNull { it.provider == Provider.GEMINI && it.id != m.id }?.let { out.add(it) }
        }
        for (p in Provider.values()) {
            if (p == m.provider) continue
            val first = Models.ALL.firstOrNull { it.provider == p } ?: continue
            if (activeKey(c, first).isNotEmpty()) out.add(first)
        }
        return out
    }

    fun generate(
        c: Context,
        text: String,
        imageBase64: String?,
        instructions: String,
        json: Boolean,
        temperature: Double = 0.0,
        fast: Boolean = false,
        maxTokens: Int = 0,
        accurate: Boolean = false,
        fallback: Boolean = false
    ): String {
        val m = selected(c)
        aborted = false
        lastLabel = m.label
        try {
            return call(c, m, text, imageBase64, instructions, json, temperature, fast, maxTokens, accurate)
        } catch (e: Exception) {
            if (!fallback || aborted || !retryable(e)) throw e
            for (alt in alternatives(c, m)) {
                if (aborted) break
                try {
                    val r = call(c, alt, text, imageBase64, instructions, json, temperature, fast, maxTokens, accurate)
                    lastLabel = alt.label
                    return r
                } catch (e2: Exception) {
                    // coba cadangan berikutnya
                }
            }
            throw e
        }
    }

    fun chat(c: Context, history: List<ChatTurn>, instructions: String): String {
        val m = selected(c)
        val key = need(c, m)
        return if (m.provider == Provider.GEMINI) {
            GeminiClient.chat(key, m.model, history, instructions)
        } else {
            OpenAiClient.chat(m.provider, key, m.model, history, instructions)
        }
    }

    /** Untuk tes koneksi: mengembalikan jawaban atau pesan error yang mudah dibaca. */
    fun ask(c: Context, input: String, instructions: String): String =
        try {
            generate(c, input, null, instructions, false)
        } catch (e: GeminiException) {
            e.message.orEmpty()
        }
}
