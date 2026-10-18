package com.assistant.ai

/** Pengaturan satu sesi latihan (dikirim dari menu Latihan ke TestActivity). */
class TestConfig(
    val kelas: String,
    val mapel: String,
    val level: String,
    val pg: Int,
    val essay: Int,
    val minutes: Int,   // 0 = tanpa waktu
    val pinned: Boolean,
    val limit: Int      // batas pelanggaran (hanya mode disematkan)
)

/** Satu soal beserta jawaban dan penilaian pengguna. */
class Question(
    val isEssay: Boolean,
    val text: String,
    val options: List<String>,
    val answerIndex: Int,    // PG: indeks jawaban benar
    val key: String,         // essay: poin penting yang dinilai
    val explanation: String  // PG: penjelasan; essay: contoh jawaban
) {
    var chosen: Int = -1
    var essayAnswer: String = ""
    var score: Double? = null  // essay: 0.0 sampai 1.0, null = belum dinilai
    var feedback: String = ""
}
