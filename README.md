# Assistant — v0.25.0

## Perubahan v0.25.0
- Menambahkan tombol **Log Out / Kunci aplikasi** di menu **Pengaturan → Akun & keamanan**. Tombol menampilkan kembali layar Password Code; kode diminta lagi saat aplikasi dibuka.
- Menambahkan tombol mikrofon di menu **Tanya AI**. Ucapan bahasa Indonesia diubah menjadi teks, lalu dikirim sebagai pertanyaan ke AI. Fitur memakai layanan pengenalan suara yang tersedia di perangkat.

# Assistant

## Versi 0.24.1

- Memperbaiki error kompilasi pada layar Password Code: menggunakan `FrameLayout.LayoutParams` yang valid untuk konten di dalam `ScrollView`.
- Nomor versi build dinaikkan ke 22 (`0.24.1`).

## Versi 0.24.0

- Menambahkan layar awal **Password Code** dengan gaya Liquid Glass putih.
- Kode akses lokal adalah `2580`; kode salah menampilkan umpan balik, sedangkan kode benar membuat lapisan login fade-out untuk menampilkan menu Assistant yang asli di bawahnya.
- Gerbang ini hanya pembatas tampilan di perangkat, bukan perlindungan keamanan yang kuat: kode tertanam di APK dan dapat ditemukan dengan memeriksa aplikasinya.

Bubble "AI" mengambang yang muncul di atas web/aplikasi apa pun. Ketuk bubble tiap soal: app mengambil screenshot, minta AI menentukan jawaban, lalu bubble berubah jadi huruf jawaban (A/B/C/D). Kamu yang menekan sendiri jawaban dan tombol lanjutnya.

**Soal essay** terdeteksi otomatis. Bubble melebar jadi kartu berisi jawaban: bisa digulir, digeser lewat judulnya, dan disalin dengan tombol *Salin*. Ketuk ✕ (atau ketuk bubble lagi) untuk menutup. Di mode Auto, app berhenti di soal essay supaya kamu mengetik jawabannya sendiri; ketuk bubble lagi untuk lanjut ke soal berikutnya.

**Stop dan sembunyikan**: tombol *Stop AI* di app menghentikan proses yang sedang jalan (termasuk panggilan Gemini yang masih menunggu) dan menutup kartu essay. Ketuk 2 kali bubble untuk menyembunyikannya (sekalian menghentikan AI). Untuk memunculkan lagi, ketuk 2 kali di tempat bubble tadi: ada area transparan yang sedikit lebih luas dari bubble. Selama bubble tersembunyi, area itu menangkap ketukan, jadi geser dulu bubble ke tempat yang aman sebelum disembunyikan. Tombol *Munculkan bubble lagi* di app juga bisa dipakai.

Tidak ada popup. Status ada di label bubble ("…" = lagi berpikir, "!" = ada masalah) dan di Log pada app.

### Cara kerja
- `OverlayService.kt`: Accessibility Service. Menaruh bubble (TYPE_ACCESSIBILITY_OVERLAY, tanpa izin
  "tampil di atas aplikasi lain"), bisa digeser. Layar dibaca **hanya saat bubble diketuk**.
  Kolom password dan kolom isian dilewati dari teks.
  Loop otomatis: baca layar, tanya Gemini (JSON), klik pilihan, klik Berikutnya, ulangi.
  Berhenti kalau soal habis, pindah app, layar nggak berubah, atau setelah 100 soal.
- `GeminiClient.kt`: panggilan ke Gemini API. `Prefs.kt`: API key dan nama model (disimpan lokal di HP).
- `MainActivity.kt`: layar pengaturan (aktifkan layanan, isi API key, tes koneksi).

### Pemasangan di HP
1. Buat API key gratis di https://aistudio.google.com (Get API key). Install APK, buka app, isi API key lalu Simpan, dan tap *Tes koneksi*.
2. Tap *Buka pengaturan Aksesibilitas* lalu aktifkan "Assistant".
3. Android 13+ untuk APK di luar Play Store: kalau muncul "Restricted setting", buka Info app, titik tiga
   kanan atas, *Allow restricted settings*, lalu aktifkan lagi.

### Keterbatasan
- Hanya membaca teks. Soal berupa gambar, canvas, atau aplikasi yang memblokir pembacaan layar
  (FLAG_SECURE) tidak terbaca.
- Teks layar dikirim ke Google Gemini. Di paket gratis, Google boleh memakai data itu untuk meningkatkan modelnya. Jangan dipakai di layar yang berisi data sensitif.
- Jangan taruh API key di kode atau di GitHub.

Gunakan untuk kuis latihan dan materi yang memang boleh kamu pakai, bukan untuk ujian atau tes yang dinilai.

### Build
Push ke GitHub (branch `main`), workflow "Build APK" akan membuat APK debug.
Toolchain: Android Gradle Plugin 9.2.0, Gradle 9.4.1, JDK 17.


## Versi 0.11.0

- **Dua menu di bawah**: *Beranda* (kontrol bubble, mode, pengaturan) dan *Tanya AI* (chat biasa dengan Gemini, pakai API key dan model yang sama; tombol *Chat baru* menghapus percakapan).
- **Config** dipisah: tombol Config di Beranda membuka dialog untuk API key dan nama model. Di luar Config ada tiga tombol: *Tes koneksi AI*, *Start AI* (memasang bubble), dan *Stop AI* (menutup bubble sepenuhnya, dihapus dari layar, bukan disembunyikan). Layanan Aksesibilitas tetap menyala di pengaturan Android (app tidak bisa menyalakannya sendiri), tapi tanpa bubble tidak ada yang dikerjakan.
- **Ikon launcher Assistant** memakai gambar vektor lampu bercahaya dengan percikan. Enam pilihan warna tersedia; nama aplikasi tetap Assistant.
- Ketuk 2x bubble tetap menyembunyikan sementara.

## Versi 0.12.0

- **Tanya AI bisa kirim gambar**: tombol klip memilih foto dari galeri (diperkecil otomatis), bisa dikirim bersama teks atau sendirian. Gambar terbaru ikut dipakai untuk pertanyaan lanjutan.
- **Khusus belajar**: AI diinstruksikan menolak dengan sopan semua hal di luar belajar (teks maupun gambar). Ini aturan lewat instruksi ke model, jadi kadang masih bisa lolos di kasus yang samar.

## Versi 0.13.0

- **Pilihan model AI** di Config (dropdown): Gemini 3.5 Flash-Lite (utama), 3.8 Flash, 3.7 Flash, 3.1 Flash-Lite, 2.5 Flash, Mistral Small, Qwen 3.8 27B (Groq), dan Gemma 4 31B (OpenRouter, gratis). Semuanya bisa membaca gambar.
- **Key Gemini bawaan** tertanam terenkripsi dua lapis di `Secrets.kt` (hanya pengaburan, bukan keamanan sungguhan). Provider lain belum punya key bawaan, jadi pengguna isi key sendiri di Config. Key sendiri selalu didahulukan di atas key bawaan.
- Menambah key bawaan: `python3 tools/enc_key.py`, lalu tempel hasilnya ke blob provider di `Secrets.kt`.
- Saran: jadikan repo GitHub ini **private**, karena algoritma dekripsi ada di kode.

## Versi 0.14.0: menu Latihan

- Tab baru **Latihan** (ulangan/tes). Urutan pilihan: kelas, mapel (daftar menyesuaikan kelas, atau ketik mapel sendiri), kesulitan (Easy, Medium, Hard, Very Hard, Super Hard), jumlah pilihan ganda dan essay (0 = tidak ada), waktu dalam menit (0 = tanpa waktu), dan mode ujian.
- Soal dibuat AI per batch kecil sesuai pilihan. Setelah dikumpulkan: nilai dihitung (essay dinilai AI, bisa dinilai ulang kalau gagal), lalu soal yang salah dibahas: salahnya di mana dan cara menjawab yang benar.
- **Mode Normal**: tiap soal punya tombol petunjuk di samping pertanyaan untuk melihat jawaban dan penjelasan singkat.
- **Mode Disematkan (ketat)**: memakai penyematan layar Android (screen pinning, harus diaktifkan di Pengaturan > Keamanan). Membuka jendela lain atau keluar dari layar tes dihitung pelanggaran; melewati batas yang dipilih (0, 1, 2, 3, atau 5) langsung auto submit. Keluar paksa dari penyematan juga langsung auto submit. Waktu habis juga auto submit.
- Selama latihan di kedua mode: bubble AI dimatikan, Tanya AI dikunci, dan screenshot layar tes diblokir (FLAG_SECURE).
- Batasan: penyematan layar Android bisa dilepas pengguna dengan gesture sistem; app hanya bisa mendeteksinya dan menghitungnya sebagai auto submit. Deteksi "AI lain" berbasis hilangnya fokus layar, bukan mengenali app-nya.

## Versi 0.14.1

- Perbaikan layar tes: teks pertanyaan terpotong jadi satu baris saat tombol petunjuk tampil (masalah `baselineAligned` di baris berbobot). Semua baris horizontal sekarang `baselineAligned=false`.
- Soal selalu dibuat baru oleh AI tiap sesi (tidak ada bank soal di app). Sebelumnya suhu (temperature) AI 0 sehingga hasilnya cenderung sama untuk pilihan yang sama; sekarang 1.0 plus kode variasi dan pemilihan subtopik acak.
- Kualitas pilihan ganda: aturan pengecoh yang masuk akal, opsi sebanding, tanpa "semua benar/salah", jawaban benar diacak, penjelasan memuat kesalahan umum. Tiap batch PG diperiksa ulang oleh AI yang menyelesaikan soal tanpa melihat kunci; soal yang kuncinya berbeda atau meragukan dibuang dan diganti.

## Versi 0.14.2: perbaikan Auto + lebih cepat

- **Bug "Gagal menekan pilihan"**: pilihan sebenarnya sudah kepencet, tapi app salah menganggap gagal karena pencarian tombol *Berikutnya* memakai pencarian teks bawaan Android, yang di Chrome/WebView sering mengembalikan kosong. Sekarang kalau pencarian bawaan kosong, app menelusuri pohon node langsung (hanya node yang terlihat). Cek "layar sudah bereaksi" juga berbasis perubahan node yang bisa ditekan (pilihan jadi nonaktif/terpilih, tombol lanjut muncul), bukan cuma menunggu tombol. Kalau ketukan sudah terkirim tapi perubahan belum terbaca, app lanjut ke Berikutnya, bukan langsung berhenti.
- **Lebih cepat**: Gemini 3.x dipanggil dengan `thinkingLevel: minimal` (2.5 Flash: `thinkingBudget: 0`), batas output 900 token, `reasoning` dipendekkan jadi satu kalimat, JPEG screenshot kualitas 75. Setelah menekan Berikutnya, app menunggu sampai soal benar-benar ganti (bukan jeda tetap), jadi jeda antar soal lebih pendek.
- Jawaban AI yang JSON-nya rusak/terpotong dicoba sekali lagi sebelum berhenti.

## Versi 0.15.0

- **Mode Auto dihapus** (jawab + Berikutnya otomatis): terlalu sering gagal di Chrome/WebView. Menu Mode di Beranda ikut dihapus, sekarang hanya mode jawaban (bubble menampilkan huruf, atau kartu untuk essay). Izin gesture di layanan Aksesibilitas ikut dicabut karena nggak dipakai lagi.
- Pengaturan cepat dari 0.14.2 (thinking minimal, output dibatasi, reasoning satu kalimat) tetap dipakai.

## Versi 0.16.0

- **Perbaikan compile**: konstanta `AI_MAX_TOKENS` yang hilang di 0.15.0 dikembalikan.
- **Mode Cepat / Akurat** (Config): akurat = thinking sedang + AI menghitung ulang soal hitungan dengan cara lain. Kalau hasil hitung ulang beda, bubble menampilkan dua huruf dan tanda tanya (misal `B/C?`).
- **Warna keyakinan**: hijau = yakin, kuning = agak ragu, merah = menebak.
- **Menu tekan lama bubble**: Jelaskan soal (langkah demi langkah), Terjemahkan layar (ke Indonesia, atau ke Inggris kalau sudah Indonesia), Pilih area soal / Ubah / Hapus area.
- **Pilih area soal**: seret kotak di layar; screenshot dipotong ke area itu sebelum dikirim ke AI (lebih cepat, lebih akurat).
- **Riwayat jawaban**: tombol di Beranda (100 terakhir: soal ringkas, jawaban, keyakinan, model).
- **Ukuran dan kepekatan bubble** diatur di Config.
- **Cadangan otomatis** (Config): kalau model utama kena 429 / error server / koneksi putus, app mencoba satu model Gemini lain, lalu model Mistral / Groq / OpenRouter yang sudah punya key.

## Versi 0.17.0 (Tahap 1)

- **Lima menu**: Beranda, Tanya AI, Latihan, Riwayat (+ Statistik), Pengaturan.
- **Tanya AI pakai command**: `/a` jawab langsung, `/e` jawab + jelaskan, `/r` rangkum materi, `/q [n]` kuis, `/t [kode]` terjemah, `/s` bahasa sederhana, `/c` cek jawabanmu, `/n` chat baru, `/h` bantuan. Tambah `+` (misal `/e+`) untuk mode teliti. Ada chip command di atas kolom ketik dan tombol `?` untuk bantuan.
- **Foto + caption**: foto dilampirkan dulu (belum terkirim); tulis caption atau command, lalu tekan Kirim.
- **Riwayat**: sekarang menu sendiri, bisa dicari, difilter (Ragu, Salah), dan ditandai benar/salah. Jawaban `/a` dan `/e` dari Tanya AI ikut tercatat (maksimal 300).
- **Statistik**: ringkasan, grafik 7 hari, sebaran keyakinan AI, akurasi dari tanda benar/salah, dan streak.
- **Pengaturan**: model + key, mode akurat, cadangan otomatis, ukuran/kepekatan bubble, serta tampilan dan warna ikon aplikasi.

## Versi 0.18.0 (Tahap 2)

- **Tema bubble**: 5 warna, 3 bentuk (bulat, kotak membulat, kotak), 7 ikon saat diam, dengan pratinjau langsung di Pengaturan. Warna keyakinan (hijau/kuning/merah) tetap muncul di atas tema.
- **Tempel ke tepi**: setelah digeser, bubble menempel ke sisi kiri atau kanan layar.
- **Auto-minimize** (opsional): setelah 5 detik tidak disentuh, bubble setengah masuk ke tepi dan memudar. Sentuhan pertama hanya memunculkannya lagi (belum menjalankan AI).
- **Auto-eskalasi**: kalau di mode cepat AI menjawab "agak ragu" atau "ragu", app otomatis mengulang sekali dengan mode akurat (ikon sementara naik).
- **Cache soal**: kalau layar sama persis dengan soal yang barusan dijawab (dicek per blok piksel, ketat), jawaban tersimpan dipakai langsung dan bubble menampilkan `↺`. Tekan lama bubble lalu pilih Jawab ulang untuk mengabaikan cache.
- **Ekspor riwayat**: CSV (Excel/Sheets), teks, atau salin ke clipboard.
- **Kuis ulang**: 10 soal acak dari riwayat (yang AI ragu, yang sudah ditandai benar, atau semua), tanpa jawaban yang ditandai salah.
- Soal di riwayat sekarang ditulis lebih lengkap (maksimal 25 kata) supaya layak dipakai untuk kuis ulang.

## Versi 0.19.0 (rombak tampilan, bagian 1: menu bawah)

- **Menu bawah baru** (`NavBarView`, digambar sendiri): bar melayang dengan ikon garis buatan sendiri (tanpa file gambar) dan label. Tiga gaya yang bisa dipilih di Pengaturan > Tampilan:
  - **Gelap + glow**: bar gelap, tab aktif menyala dengan garis cahaya yang meluncur di tepi atas.
  - **Bulat melayang** (default): bar putih dengan lekukan, bulatan warna meluncur antar tab dengan pantulan (overshoot), cincin denyut, dan warna berganti halus per tab.
  - **Tetes cair**: bar pastel yang berganti warna tiap tab, bulatan muncul dari bar sambil bulatan lama tenggelam, ditambah percikan kecil.
- Pindah tab sekarang diiringi getar halus, dan halaman baru masuk sambil meluncur dan memudar.

## Versi 0.20.0 (menu bawah: liquid glass)

- **Gaya baru "Liquid glass"** (sekarang jadi default; gaya lama tetap ada di Pengaturan > Tampilan > Gaya menu bawah). Teks label dan ikon sama dengan gaya lain.
- Kapsul kaca bening berembun dengan "aurora" warna di dalamnya, tepi bercahaya (rim light), garis kilau di atas, bayangan dalam di bawah, dan bayangan luar yang lembut.
- **Lensa kaca** untuk tab aktif: meluncur dengan pantulan (overshoot), melar saat bergerak, membesar saat disentuh, tepi dengan sedikit pergeseran warna (cyan/magenta) dan kilau di sudut. Warnanya mengikuti warna tab.
- **Bisa diseret**: tahan lalu geser di menu, lensa mengikuti jari dan warnanya bergeser halus; lepas di dekat tab untuk pindah.
- Ikon dan label yang dekat lensa menyala, naik sedikit, dan memantul "kenyal" (jelly) saat tab berganti.
- Catatan: efeknya digambar dengan Canvas (simulasi kaca), bukan blur sungguhan dari konten di belakang menu, karena menu ini ada di bawah konten, bukan menumpuk di atasnya.

## Versi 0.21.0 (popup liquid glass)

- **Semua dialog** (bantuan Tanya AI, ekspor riwayat, konfirmasi hapus, kuis ulang) memakai tema kaca: kartu bening dengan tepi bercahaya, kilau tipis di atas, dan tombol pil kaca.
- **Blur latar** (Android 12+): latar app dikaburkan di belakang dialog dengan radius tetap (14dp, tidak dianimasikan) supaya ringan. Kalau blur dimatikan sistem (misal penghemat baterai) atau Android lebih lama, dialog otomatis memakai kaca yang lebih pekat tanpa blur.
- **Pemilih kaca** menggantikan dropdown Spinner: model AI (Pengaturan) dan Kelas, Mapel, Kesulitan, Batas pelanggaran (Latihan). Pilihan aktif ditandai dan daftar langsung tergulir ke sana.
- **Animasi muncul/hilang** lewat animasi jendela sistem (membesar halus + memudar masuk, mengecil + memudar keluar), jadi tidak ada hitungan per frame di app.
- **Popup di atas layar** (menu tekan lama bubble dan panel jawaban/penjelasan/terjemahan) ikut bergaya kaca dengan animasi masuk/keluar memakai layer cache. Blur latar sengaja tidak dipakai di sini supaya soal di belakang panel tetap terbaca.
