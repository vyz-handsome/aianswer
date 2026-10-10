package com.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Penyimpanan lokal pilihan model, key pribadi, dan log terakhir (hanya di HP, tidak masuk repo). */
object Prefs {
    private const val FILE = "study_overlay"
    private const val KEY_API = "api_key"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_LOG = "log"
    private const val KEY_BUBBLE_ON = "bubble_on"
    private const val KEY_KELAS = "kelas_index"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Model AI yang dipilih di Config (id dari [Models]). */
    fun modelId(c: Context): String {
        val saved = sp(c).getString(KEY_MODEL_ID, "").orEmpty()
        return if (Models.ALL.any { it.id == saved }) saved else Models.DEFAULT_ID
    }

    fun setModelId(c: Context, id: String) {
        sp(c).edit().putString(KEY_MODEL_ID, id).apply()
    }

    /** Key buatan user sendiri untuk satu penyedia (opsional; kalau kosong dipakai key bawaan app). */
    fun userKey(c: Context, p: Provider): String {
        val v = sp(c).getString("key_" + p.name, "").orEmpty().trim()
        if (v.isNotEmpty()) return v
        // Key Gemini dari versi lama app.
        return if (p == Provider.GEMINI) sp(c).getString(KEY_API, "").orEmpty().trim() else ""
    }

    fun setUserKey(c: Context, p: Provider, key: String) {
        val e = sp(c).edit().putString("key_" + p.name, key.trim())
        if (p == Provider.GEMINI) e.putString(KEY_API, "")
        e.apply()
    }

    /** Kelas terakhir yang dipilih di menu Latihan. */
    fun kelasIndex(c: Context): Int = sp(c).getInt(KEY_KELAS, 0)

    fun setKelasIndex(c: Context, index: Int) {
        sp(c).edit().putInt(KEY_KELAS, index).apply()
    }

    /** Bubble AI sengaja dihentikan lewat tombol Stop AI? (true = bubble boleh muncul saat layanan nyala) */
    fun bubbleOn(c: Context): Boolean = sp(c).getBoolean(KEY_BUBBLE_ON, true)

    fun setBubbleOn(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(KEY_BUBBLE_ON, on).apply()
    }

    // ------------------------------------------------------------------ pengaturan bubble

    /** Mode akurat: AI berpikir lebih lama dan mengecek ulang soal hitungan. Default: cepat. */
    fun accurate(c: Context): Boolean = sp(c).getBoolean("accurate", false)

    fun setAccurate(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("accurate", on).apply()
    }

    /** Kalau model utama kena batas (429) atau error server, coba model lain yang sudah punya key. */
    fun fallback(c: Context): Boolean = sp(c).getBoolean("fallback", true)

    fun setFallback(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("fallback", on).apply()
    }

    /** Ukuran bubble dalam persen (60 sampai 160). */
    fun bubbleScale(c: Context): Int = sp(c).getInt("bubble_scale", 100).coerceIn(60, 160)

    fun setBubbleScale(c: Context, v: Int) {
        sp(c).edit().putInt("bubble_scale", v.coerceIn(60, 160)).apply()
    }

    /** Keburaman bubble dalam persen (30 sampai 100; 100 = pekat). */
    fun bubbleAlpha(c: Context): Int = sp(c).getInt("bubble_alpha", 100).coerceIn(30, 100)

    fun setBubbleAlpha(c: Context, v: Int) {
        sp(c).edit().putInt("bubble_alpha", v.coerceIn(30, 100)).apply()
    }

    // Tema, bentuk, dan ikon bubble (indeks ke BubbleStyle.THEMES / SHAPES / ICONS).
    fun bubbleTheme(c: Context): Int = sp(c).getInt("bubble_theme", 0)

    fun setBubbleTheme(c: Context, v: Int) {
        sp(c).edit().putInt("bubble_theme", v).apply()
    }

    fun bubbleShape(c: Context): Int = sp(c).getInt("bubble_shape", 0)

    fun setBubbleShape(c: Context, v: Int) {
        sp(c).edit().putInt("bubble_shape", v).apply()
    }

    fun bubbleIcon(c: Context): Int = sp(c).getInt("bubble_icon", 0)

    fun setBubbleIcon(c: Context, v: Int) {
        sp(c).edit().putInt("bubble_icon", v).apply()
    }

    /** Gaya menu bawah: 0 = gelap + glow, 1 = bulat melayang, 2 = tetes cair, 3 = liquid glass (default). */
    fun navStyle(c: Context): Int = sp(c).getInt("nav_style_v2", 3).coerceIn(0, 3)

    fun setNavStyle(c: Context, v: Int) {
        sp(c).edit().putInt("nav_style_v2", v).apply()
    }

    /** Bubble menempel ke tepi kiri/kanan setelah digeser. */
    fun snapEdge(c: Context): Boolean = sp(c).getBoolean("snap_edge", true)

    fun setSnapEdge(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("snap_edge", on).apply()
    }

    /** Bubble setengah masuk ke tepi dan memudar kalau lama tidak disentuh. */
    fun autoMinimize(c: Context): Boolean = sp(c).getBoolean("auto_minimize", false)

    fun setAutoMinimize(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto_minimize", on).apply()
    }

    /** Kalau AI ragu di mode cepat, otomatis ulangi dengan mode akurat. */
    fun autoEscalate(c: Context): Boolean = sp(c).getBoolean("auto_escalate", true)

    fun setAutoEscalate(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto_escalate", on).apply()
    }

    /** Soal yang layarnya sama persis dengan yang barusan dijawab: pakai jawaban tersimpan. */
    fun cacheOn(c: Context): Boolean = sp(c).getBoolean("cache_on", true)

    fun setCacheOn(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("cache_on", on).apply()
    }

    /** Area soal yang dipilih (kiri, atas, kanan, bawah sebagai pecahan 0..1 dari layar), atau null = seluruh layar. */
    fun area(c: Context): FloatArray? {
        val parts = sp(c).getString("area", "").orEmpty().split(",")
        if (parts.size != 4) return null
        val v = parts.mapNotNull { it.trim().toFloatOrNull() }
        if (v.size != 4 || v[2] <= v[0] || v[3] <= v[1]) return null
        return floatArrayOf(v[0], v[1], v[2], v[3])
    }

    fun setArea(c: Context, a: FloatArray?) {
        val s = if (a == null) "" else a.joinToString(",")
        sp(c).edit().putString("area", s).apply()
    }

    // ------------------------------------------------------------------ riwayat jawaban

    class HistoryItem(
        val time: Long,
        val question: String,
        val answer: String,
        val confidence: String,
        val model: String,
        val source: String = "bubble",   // "bubble" atau "chat"
        val mark: Int = 0                // 1 = benar, -1 = salah, 0 = belum dicek
    )

    private const val HISTORY_MAX = 300

    /** Riwayat terbaru di urutan pertama (maksimal 300). */
    fun history(c: Context): List<HistoryItem> {
        val raw = sp(c).getString("history", "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryItem(
                    o.optLong("t"), o.optString("q"), o.optString("a"), o.optString("c"), o.optString("m"),
                    o.optString("s", "bubble"), o.optInt("k", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(c: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        for (h in list) {
            arr.put(
                JSONObject().put("t", h.time).put("q", h.question).put("a", h.answer)
                    .put("c", h.confidence).put("m", h.model).put("s", h.source).put("k", h.mark)
            )
        }
        sp(c).edit().putString("history", arr.toString()).apply()
    }

    @Synchronized
    fun addHistory(c: Context, item: HistoryItem) {
        val list = ArrayList(history(c))
        list.add(0, item)
        while (list.size > HISTORY_MAX) list.removeAt(list.size - 1)
        saveHistory(c, list)
    }

    /** Tandai satu riwayat benar (1), salah (-1), atau belum dicek (0). */
    @Synchronized
    fun setHistoryMark(c: Context, time: Long, mark: Int) {
        val list = history(c).map {
            if (it.time == time) HistoryItem(it.time, it.question, it.answer, it.confidence, it.model, it.source, mark) else it
        }
        saveHistory(c, list)
    }

    fun clearHistory(c: Context) {
        sp(c).edit().remove("history").apply()
    }

    fun log(c: Context): String = sp(c).getString(KEY_LOG, "").orEmpty()

    fun setLog(c: Context, text: String) {
        sp(c).edit().putString(KEY_LOG, text).apply()
    }
}
