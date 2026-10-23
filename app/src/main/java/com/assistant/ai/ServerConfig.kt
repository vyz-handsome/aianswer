package com.assistant.ai

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Alamat server Termux berubah tiap tunnel restart. Sumber kebenarannya
 * adalah raw content Gist rahasia yang ditulis otomatis oleh
 * scripts/gist_update.py di backend. App baca Gist itu, simpan hasilnya
 * di cache lokal, dan kalau internet/Gist lagi tidak bisa diakses, app
 * tetap pakai URL lama dari cache (bukan langsung dianggap logout) —
 * sesuai keputusan: "server mati sebentar -> app tetap jalan sementara".
 *
 * GIST_RAW_URL diisi SEKALI oleh owner setelah backend pertama kali
 * jalan dan Gist-nya kebuat (ID-nya muncul di log gist_update.py).
 * Ini bagian yang di-"patch" per build kalau suatu saat server pindah Gist.
 */
object ServerConfig {
    // TODO(owner): ganti dengan URL raw Gist asli, contoh:
    // "https://gist.githubusercontent.com/USERNAME/GIST_ID/raw/server_url.txt"
    private const val GIST_RAW_URL = ""

    private const val PREF = "server_config"
    private const val KEY_CACHED_BASE_URL = "cached_base_url"

    /** URL server yang diketahui app saat ini (cache lokal, bisa kosong kalau belum pernah berhasil fetch). */
    fun cachedBaseUrl(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_CACHED_BASE_URL, "").orEmpty()

    private fun saveCachedBaseUrl(c: Context, url: String) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_CACHED_BASE_URL, url).apply()
    }

    /**
     * Refresh cache dari Gist di background thread. Fire-and-forget:
     * dipanggil tiap app dibuka supaya cache selalu coba diperbarui,
     * tapi caller TIDAK perlu menunggu hasil ini untuk tetap jalan
     * pakai cache lama.
     */
    fun refreshInBackground(c: Context) {
        if (GIST_RAW_URL.isBlank()) return
        val appCtx = c.applicationContext
        Thread {
            try {
                val conn = (URL(GIST_RAW_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().readText().trim()
                    if (body.startsWith("https://")) {
                        saveCachedBaseUrl(appCtx, body)
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Diam saja: cache lama tetap dipakai.
            }
        }.start()
    }
}
