#!/data/data/com.termux/files/usr/bin/bash
# Menjalankan server Assistant dan menghidupkannya ulang otomatis kalau mati karena error apa pun.
# Pakai:  bash run.sh        (stop: bash stop.sh)
cd "$(dirname "$0")" || exit 1
mkdir -p data

PIDFILE=data/run.pid
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; then
  echo "Server sudah berjalan (PID $(cat "$PIDFILE")). Stop dulu dengan: bash stop.sh"
  exit 0
fi
echo $$ > "$PIDFILE"

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock

CHILD=""
cleanup() {
  [ -n "$CHILD" ] && kill "$CHILD" 2>/dev/null
  pkill -f "cloudflared tunnel" 2>/dev/null
  rm -f "$PIDFILE"
  exit 0
}
trap cleanup INT TERM HUP

if [ ! -f config.json ]; then
  echo "config.json belum ada. Jalankan dulu: bash setup.sh"
  rm -f "$PIDFILE"
  exit 1
fi

fails=0
while true; do
  pkill -f "cloudflared tunnel" 2>/dev/null
  if [ -f data/stdout.log ] && [ "$(wc -c < data/stdout.log)" -gt 5000000 ]; then
    tail -c 1000000 data/stdout.log > data/stdout.log.tmp && mv data/stdout.log.tmp data/stdout.log
  fi
  start=$(date +%s)
  echo "[$(date '+%F %T')] menjalankan server.py" >> data/stdout.log
  python3 server.py >> data/stdout.log 2>&1 &
  CHILD=$!
  wait "$CHILD"
  code=$?
  CHILD=""
  now=$(date +%s)
  echo "[$(date '+%F %T')] server.py berhenti (kode $code), dihidupkan ulang" >> data/stdout.log
  if [ $((now - start)) -lt 20 ]; then
    fails=$((fails + 1))
    wait_s=$((fails * 5))
    [ "$wait_s" -gt 60 ] && wait_s=60
    sleep "$wait_s"
  else
    fails=0
    sleep 3
  fi
done
