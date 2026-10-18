package com.assistant.ai

/** Command slash di menu Tanya AI: /a /e /r /q /t /s /c, plus /h dan /n yang diproses di app. */
object ChatCommands {

    class Spec(val key: String, val chip: String, val usage: String, val desc: String)

    val ALL: List<Spec> = listOf(
        Spec("a", "/a Jawab", "/a", "Jawab langsung, jawabannya saja"),
        Spec("e", "/e Jelaskan", "/e", "Jawab + jelaskan langkah-langkahnya"),
        Spec("r", "/r Rangkum", "/r", "Rangkum materi (teks atau foto)"),
        Spec("q", "/q Kuis", "/q 10", "Buat kuis dari materi, jumlah soal opsional (maks 15, default 5)"),
        Spec("t", "/t Terjemah", "/t en", "Terjemahkan; kode bahasa opsional: id, en, ar, ja, zh, ko, es, fr, de"),
        Spec("s", "/s Sederhana", "/s", "Jelaskan dengan bahasa sederhana, seperti ke anak SMP"),
        Spec("c", "/c Cek", "/c", "Cek jawabanmu: kirim soal + jawabanmu, AI menilai dan mengoreksi"),
        Spec("n", "/n Baru", "/n", "Mulai chat baru"),
        Spec("h", "/h Bantuan", "/h", "Tampilkan bantuan ini")
    )

    /** key "?" berarti command tidak dikenal; name berisi teks command-nya. */
    class Parsed(
        val key: String,
        val name: String,
        val careful: Boolean,
        val arg: String,
        val body: String
    )

    private val LANGS = mapOf(
        "id" to "Bahasa Indonesia", "en" to "Bahasa Inggris", "ar" to "Bahasa Arab", "ja" to "Bahasa Jepang",
        "jp" to "Bahasa Jepang", "zh" to "Bahasa Mandarin", "ko" to "Bahasa Korea", "es" to "Bahasa Spanyol",
        "fr" to "Bahasa Prancis", "de" to "Bahasa Jerman"
    )

    /** Null kalau teksnya bukan command (tidak diawali "/"). */
    fun parse(typed: String): Parsed? {
        if (!typed.startsWith("/")) return null
        val token = typed.takeWhile { !it.isWhitespace() }
        var rest = typed.drop(token.length).trim()
        var name = token.drop(1).lowercase()
        val careful = name.endsWith("+")
        if (careful) name = name.dropLast(1)

        // "/q10" = "/q 10"
        var arg = ""
        if (name.length > 1 && name.drop(1).all { it.isDigit() }) {
            arg = name.drop(1)
            name = name.take(1)
        }
        val key = if (ALL.any { it.key == name }) name else "?"
        if (key == "q" && arg.isEmpty()) {
            val first = rest.takeWhile { !it.isWhitespace() }
            if (first.isNotEmpty() && first.all { it.isDigit() }) {
                arg = first
                rest = rest.drop(first.length).trim()
            }
        }
        if (key == "t") {
            val first = rest.takeWhile { !it.isWhitespace() }.lowercase()
            if (first in LANGS) {
                arg = first
                rest = rest.drop(first.length).trim()
            }
        }
        return Parsed(key, token, careful, arg, rest)
    }

    /** Dipakai kalau command diketik tanpa teks tapi ada foto terlampir. */
    fun defaultPrompt(key: String): String = when (key) {
        "a" -> "Jawab soal pada gambar ini."
        "e" -> "Jawab dan jelaskan soal pada gambar ini."
        "r" -> "Rangkum materi pada gambar ini."
        "q" -> "Buat kuis dari materi pada gambar ini."
        "t" -> "Terjemahkan teks pada gambar ini."
        "s" -> "Jelaskan materi pada gambar ini dengan bahasa sederhana."
        "c" -> "Periksa jawaban pada gambar ini."
        else -> "Tolong bantu jelaskan dan jawab soal atau materi pada gambar ini."
    }

    /** Instruksi tambahan untuk satu giliran, ditempel di belakang CHAT_INSTRUCTIONS. */
    fun modeInstructions(p: Parsed): String {
        val mode = when (p.key) {
            "a" -> "MODE JAWAB LANGSUNG: berikan hanya jawaban akhir (kalau pilihan ganda: huruf dan isinya) dalam satu atau dua " +
                "kalimat. Jangan beri penjelasan kecuali diminta."
            "e" -> "MODE JELASKAN: tulis jawaban pada baris pertama dengan format \"Jawaban: ...\", lalu langkah-langkah " +
                "bernomor yang singkat dan jelas (maksimal 8 langkah)."
            "r" -> "MODE RANGKUM: rangkum materi dari teks atau gambar dengan format: baris \"Ringkasan:\" (3 sampai 5 kalimat), " +
                "lalu \"Poin penting:\" (daftar dengan tanda -), lalu \"Istilah kunci:\" (istilah: arti singkat)."
            "q" -> {
                val n = (p.arg.toIntOrNull() ?: 5).coerceIn(1, 15)
                "MODE KUIS: buat $n soal pilihan ganda (A sampai D) dari materi yang diberikan (teks atau gambar). " +
                    "Tulis tiap soal dengan nomor, pertanyaan, lalu pilihan A sampai D di baris terpisah. " +
                    "Setelah semua soal, tulis \"Kunci jawaban:\" diikuti kunci tiap nomor."
            }
            "t" -> {
                val lang = LANGS[p.arg]
                if (lang != null) {
                    "MODE TERJEMAH: terjemahkan teks dari pengguna (atau teks pada gambar) ke $lang. Hanya tulis hasil terjemahan."
                } else {
                    "MODE TERJEMAH: terjemahkan teks dari pengguna (atau teks pada gambar) ke Bahasa Indonesia; kalau teksnya " +
                        "sudah Bahasa Indonesia, terjemahkan ke Bahasa Inggris. Hanya tulis hasil terjemahan."
                }
            }
            "s" -> "MODE SEDERHANA: jelaskan dengan bahasa yang sangat sederhana, seperti ke siswa SMP, pakai analogi sehari-hari, " +
                "singkat dan tidak bertele-tele."
            "c" -> "MODE CEK JAWABAN: pengguna mengirim soal beserta jawabannya sendiri (teks atau foto). Nilai dulu dengan satu " +
                "kata di baris pertama: \"Benar\", \"Salah\", atau \"Sebagian benar\". Lalu jelaskan letak kesalahannya (kalau ada) " +
                "dan tulis jawaban yang benar."
            else -> ""
        }
        val careful = if (p.careful) {
            "\nMODE TELITI: sebelum menjawab, hitung atau periksa ulang dengan cara yang berbeda dan pastikan hasilnya konsisten. " +
                "Kalau hasilnya berbeda, selesaikan sampai konsisten."
        } else {
            ""
        }
        return mode + careful
    }

    /** Teks bantuan lengkap (dialog /h dan tombol ?). */
    fun helpText(): String {
        val sb = StringBuilder()
        sb.append("Ketik command di awal pesan:\n\n")
        for (s in ALL) sb.append(s.usage.padEnd(7)).append("  ").append(s.desc).append('\n')
        sb.append("\nTambah + di belakang command (misal /a+ atau /e+) untuk mode teliti: AI mengecek ulang hitungannya. ")
        sb.append("Lebih lambat, tapi lebih aman untuk soal hitungan.\n\n")
        sb.append("Kirim foto: tekan Tambah foto, pilih foto, lalu tulis command atau caption-nya dan tekan Kirim. ")
        sb.append("Foto tidak langsung terkirim, jadi lu bisa menulis dulu. Kalau cuma command tanpa teks, AI memakai foto itu.\n\n")
        sb.append("Contoh:\n")
        sb.append("/e+ 3x + 5 = 20, berapa x?\n")
        sb.append("[foto soal] + /a\n")
        sb.append("/r [tempel teks materi]\n")
        sb.append("/q 10 [tempel materi]\n")
        sb.append("/t en Selamat pagi\n")
        sb.append("/c Soal: 7 x 8. Jawabanku: 54")
        return sb.toString()
    }
}
