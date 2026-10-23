# Server Assistant (Termux)

Database SQLite + API + bot Telegram + tunnel Cloudflare. Auto restart: `run.sh` menghidupkan ulang `server.py` kalau mati; di dalam server tiap thread (HTTP, bot, tunnel, pengawas) juga dihidupkan ulang sendiri, dan tunnel dicek kesehatannya tiap 30 detik.

## Pasang (sekali)
1. Install **Termux** dan (opsional) **Termux:Boot** dari F-Droid.
2. Buat bot di @BotFather, simpan tokennya. Buat GitHub token (izin `gist`).
3. Salin folder `termux-server` ke HP, lalu: `bash setup.sh` (isi token bot, Telegram ID owner, nama GitHub, GitHub token).
4. Jalankan: `bash run.sh` (stop: `bash stop.sh`). Matikan penghemat baterai untuk Termux.
5. Kirim `/start` ke bot. Server mengirim notifikasi "Server online" + URL.

## Pasang app owner
1. Build APK dari repo (GitHub Actions), pasang di HP owner.
2. Kirim `/pair` ke bot, salin teks `ASSISTANT-PAIR|...` ke app owner (Pengaturan > Panel Owner > Hubungkan).
3. Tekan **Kirim template ke server** (APK app owner dipakai sebagai template). Ulangi tiap memasang versi app baru.

## Pakai
- Bot: `/generate` (tombol), `/list`, `/pair`, `/status`, `/url`, `/info ID`, `/stop ID`, `/revoke ID`, `/reset ID`, `/pesan ID teks`.
- Bentuk cepat: `/generate Nama App|com.contoh.app|waktu|2d` (jenis: perm, sekali, waktu; satuan m/j/d).
- Tiap restart tunnel, URL baru ditulis ke Gist rahasia; app membaca alamat dari Gist, jadi tidak perlu update APK.

## Catatan
- Tes: `python3 test_apkpatch.py` dan `python3 test_server.py`.
- Data ada di `data/` (database, keystore, log). Backup folder itu; kalau keystore hilang, app user lama tidak bisa di-update dengan tanda tangan sama.
- `config.json` dan `data/` rahasia, sudah masuk .gitignore.
