package com.assistant.ai

/** Daftar kelas, mapel menurut kelas, dan tingkat kesulitan untuk menu Latihan. */
object Curriculum {
    val KELAS = listOf(
        "Kelas 1 SD", "Kelas 2 SD", "Kelas 3 SD", "Kelas 4 SD", "Kelas 5 SD", "Kelas 6 SD",
        "Kelas 7 SMP", "Kelas 8 SMP", "Kelas 9 SMP",
        "Kelas 10 SMA", "Kelas 11 SMA", "Kelas 12 SMA"
    )

    val LEVELS = listOf("Easy", "Medium", "Hard", "Very Hard", "Super Hard")

    /** Pilihan batas pelanggaran untuk mode disematkan. */
    val LIMITS = listOf(0, 1, 2, 3, 5)

    fun mapel(kelasIndex: Int): List<String> = when (kelasIndex) {
        0, 1 -> listOf(
            "Matematika", "Bahasa Indonesia", "Pendidikan Pancasila", "Bahasa Inggris"
        )
        2, 3, 4, 5 -> listOf(
            "Matematika", "Bahasa Indonesia", "IPAS (IPA dan IPS)", "IPA", "IPS",
            "Pendidikan Pancasila (PPKn)", "Bahasa Inggris"
        )
        6, 7, 8 -> listOf(
            "Matematika", "Bahasa Indonesia", "Bahasa Inggris", "IPA", "IPS",
            "Pendidikan Pancasila (PPKn)", "Informatika"
        )
        9 -> listOf(
            "Matematika", "Bahasa Indonesia", "Bahasa Inggris", "Fisika", "Kimia", "Biologi",
            "Sejarah", "Geografi", "Ekonomi", "Sosiologi", "Pendidikan Pancasila (PPKn)", "Informatika"
        )
        else -> listOf(
            "Matematika", "Matematika Lanjut", "Bahasa Indonesia", "Bahasa Inggris",
            "Fisika", "Kimia", "Biologi", "Sejarah", "Geografi", "Ekonomi", "Sosiologi",
            "Pendidikan Pancasila (PPKn)", "Informatika"
        )
    }
}
