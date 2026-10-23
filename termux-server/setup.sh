#!/data/data/com.termux/files/usr/bin/bash
# Setup sekali jalan untuk server Assistant di Termux.
cd "$(dirname "$0")" || exit 1
set -e
echo "== Setup server Assistant =="

echo "[1/5] Memasang paket (python, java, cloudflared, apksigner)..."
pkg update -y
pkg install -y python openjdk-17 cloudflared
pkg install -y apksigner || echo "PERINGATAN: paket apksigner tidak ada. Pembuatan APK user butuh apksigner."
pip install --upgrade requests

mkdir -p data

echo "[2/5] Data bot dan GitHub"
if [ -f config.json ]; then
  echo "config.json sudah ada, dilewati. (Hapus file itu kalau mau isi ulang.)"
else
  read -r -p "Token bot Telegram (dari @BotFather): " BOT_TOKEN
  read -r -p "Telegram ID owner [6613958744]: " OWNER_ID
  OWNER_ID=${OWNER_ID:-6613958744}
  read -r -p "Nama akun GitHub [vyz-handsome]: " GH_USER
  GH_USER=${GH_USER:-vyz-handsome}
  echo "Buat GitHub token (Settings > Developer settings > Personal access tokens) dengan izin: gist"
  read -r -s -p "GitHub token (izin gist): " GIST_TOKEN
  echo
  BOT_TOKEN="$BOT_TOKEN" OWNER_ID="$OWNER_ID" GH_USER="$GH_USER" GIST_TOKEN="$GIST_TOKEN" python3 - <<'PY'
import json, os
cfg = {
    "bot_token": os.environ["BOT_TOKEN"].strip(),
    "owner_id": int(os.environ["OWNER_ID"]),
    "github_user": os.environ["GH_USER"].strip(),
    "gist_token": os.environ["GIST_TOKEN"].strip(),
    "port": 8787,
}
with open("config.json", "w") as f:
    json.dump(cfg, f, indent=2)
os.chmod("config.json", 0o600)
PY
fi

echo "[3/5] Keystore untuk menandatangani APK user"
if [ ! -f data/keystore.jks ]; then
  head -c 24 /dev/urandom | base64 | tr -d '=+/\n' > data/ks.pass
  chmod 600 data/ks.pass
  keytool -genkeypair -keystore data/keystore.jks -alias assistant -keyalg RSA -keysize 2048 \
    -validity 36500 -storepass "$(cat data/ks.pass)" -keypass "$(cat data/ks.pass)" \
    -dname "CN=Assistant, O=Assistant, C=ID"
  chmod 600 data/keystore.jks
else
  echo "Keystore sudah ada."
fi

echo "[4/5] Auto-start saat HP menyala (butuh aplikasi Termux:Boot)"
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/assistant-server" <<BOOT
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
bash "$(pwd)/run.sh" &
BOOT
chmod +x "$HOME/.termux/boot/assistant-server"

echo "[5/5] Tes patcher"
python3 test_apkpatch.py

echo
echo "Selesai. Jalankan server:   bash run.sh"
echo "Lalu buka bot Telegram kamu dan kirim /start."
echo "Matikan penghemat baterai untuk Termux supaya server tidak dimatikan Android."
