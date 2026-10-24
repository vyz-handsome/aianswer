#!/data/data/com.termux/files/usr/bin/bash
# Pasang bot WhatsApp (sekali saja). Jalankan dari folder termux-server:  bash wa-setup.sh
set -e
cd "$(dirname "$0")"
pkg install -y nodejs git >/dev/null
cd wa-bot
npm install --omit=dev
cd ..
python3 - <<'PY'
import json
p = "config.json"
c = json.load(open(p))
c["wa_enabled"] = True
c.setdefault("wa_number", "6285124343791")
json.dump(c, open(p, "w"), indent=2)
print("config.json: wa_enabled = true, wa_number =", c["wa_number"])
PY
echo
echo "Selesai. Restart server (bash stop.sh lalu bash run.sh)."
echo "Kode pairing muncul di: tail -f data/wa.log  (masukkan di WhatsApp > Perangkat tertaut > Tautkan dengan nomor telepon)."
