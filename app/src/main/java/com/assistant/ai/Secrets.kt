package com.assistant.ai

import android.util.Base64

/**
 * Key bawaan app, disimpan terenkripsi dua lapis (XOR + Base64, dibalik, XOR + Base64) supaya tidak muncul
 * sebagai teks biasa di repo atau APK. Ini hanya pengaburan, bukan keamanan sungguhan: siapa pun yang
 * membongkar app dan membaca kode ini tetap bisa memulihkannya.
 *
 * Untuk mengganti key: jalankan `python3 tools/enc_key.py`, lalu tempel hasilnya ke blob provider yang sesuai.
 */
object Secrets {
    private val K1 = byteArrayOf(
115,116,52,100,121,45,48,118,51,114,108,52,121,35,107,49
    )
    private val K2 = byteArrayOf(
65,73,33,98,117,98,98,108,101,46,50,48,50,54,126,107,50
    )

    // Kosong = provider itu belum punya key bawaan (pengguna isi key sendiri di Config).
    private val GEMINI = listOf(
        "fC5yLhkoICIkd3dhYgYTL34H",
        "HG8SMwktD1V2cElnZyYhfBIY",
        "UCseBFRYI2l8ZkpSPR9hJCdw",
        "UTYzCi0qaFVaa2ANOXggHEgv"
    ).joinToString("")
    private const val MISTRAL = ""
    private const val GROQ = ""
    private const val OPENROUTER = ""

    private val cache = HashMap<Provider, String>()

    fun builtIn(p: Provider): String = synchronized(cache) {
        cache.getOrPut(p) {
            val blob = when (p) {
                Provider.GEMINI -> GEMINI
                Provider.MISTRAL -> MISTRAL
                Provider.GROQ -> GROQ
                Provider.OPENROUTER -> OPENROUTER
            }
            if (blob.isEmpty()) "" else decode(blob)
        }
    }

    private fun xor(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor key[it % key.size].toInt()).toByte() }

    private fun decode(blob: String): String = try {
        val s4 = Base64.decode(blob, Base64.DEFAULT)
        val s3 = xor(s4, K2)
        val s2 = s3.reversedArray()
        val s1 = Base64.decode(s2, Base64.DEFAULT)
        String(xor(s1, K1), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}
