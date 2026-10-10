package com.assistant.ai

/** Penyedia AI. Gemini pakai API sendiri; sisanya memakai format OpenAI-compatible (chat/completions). */
enum class Provider(val label: String, val endpoint: String, val keyUrl: String) {
    GEMINI("Google Gemini", "", "aistudio.google.com/apikey"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1/chat/completions", "console.mistral.ai"),
    GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions", "console.groq.com/keys"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter.ai/keys")
}

/** Satu pilihan di menu model. Semua model di daftar ini bisa membaca gambar (dibutuhkan bubble dan chat foto). */
class AiModel(val id: String, val label: String, val provider: Provider, val model: String)

object Models {
    const val DEFAULT_ID = "gemini-3.5-flash-lite"

    val ALL: List<AiModel> = listOf(
        AiModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite (utama, cepat)", Provider.GEMINI, "gemini-3.5-flash-lite"),
        AiModel("gemini-3.8-flash", "Gemini 3.8 Flash (lebih pintar)", Provider.GEMINI, "gemini-3.8-flash"),
        AiModel("gemini-3.7-flash", "Gemini 3.7 Flash", Provider.GEMINI, "gemini-3.7-flash"),
        AiModel("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", Provider.GEMINI, "gemini-3.1-flash-lite"),
        AiModel("gemini-2.5-flash", "Gemini 2.5 Flash", Provider.GEMINI, "gemini-2.5-flash"),
        AiModel("mistral-small", "Mistral Small (terbaru)", Provider.MISTRAL, "mistral-small-latest"),
        AiModel("groq-qwen", "Qwen 3.8 27B (Groq, cepat)", Provider.GROQ, "qwen/qwen3.8-27b"),
        AiModel("or-gemma", "Gemma 4 31B (OpenRouter, gratis)", Provider.OPENROUTER, "google/gemma-4-31b-it:free")
    )

    fun byId(id: String): AiModel = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
