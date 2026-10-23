#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")" || exit 1
if [ -f data/run.pid ]; then
  kill "$(cat data/run.pid)" 2>/dev/null
  sleep 1
fi
pkill -f "python3 server.py" 2>/dev/null
pkill -f "cloudflared tunnel" 2>/dev/null
rm -f data/run.pid
command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock
echo "Server dihentikan."
