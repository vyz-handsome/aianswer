#!/usr/bin/env python3
"""
Server Assistant (jalan di Termux): database SQLite, API untuk app owner + app user,
bot Telegram (hanya melayani owner), tunnel Cloudflare dengan URL otomatis dipublish ke Gist.

Dependensi: python + requests. Semua thread diawasi dan otomatis dihidupkan ulang kalau error;
run.sh menghidupkan ulang seluruh proses kalau server.py mati.
"""
import hashlib
import hmac
import json
import logging
import logging.handlers
import os
import re
import secrets
import signal
import sqlite3
import subprocess
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

import apkpatch

BASE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(BASE, "data")
os.makedirs(DATA, exist_ok=True)
CONFIG_PATH = os.environ.get("ASSISTANT_CONFIG", os.path.join(BASE, "config.json"))
DB_PATH = os.path.join(DATA, "assistant.db")
STATE_PATH = os.path.join(DATA, "state.json")
TEMPLATE_PATH = os.path.join(DATA, "template.apk")
BUILD_DIR = os.path.join(DATA, "build")
KEYSTORE = os.path.join(DATA, "keystore.jks")
KS_PASS_FILE = os.path.join(DATA, "ks.pass")

# Samakan dengan Models.kt di app.
MODEL_IDS = [
    "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.1-flash-lite",
    "gemini-2.5-flash", "mistral-small", "groq-qwen", "or-gemma",
]

HEARTBEAT_ONLINE_SEC = 45
LOCK_AFTER_FAILS = 5
LOCK_SECONDS = 15 * 60
IP_LOCK_AFTER_FAILS = 25
PAIR_TTL = 10 * 60
MAX_TEMPLATE_BYTES = 60 * 1024 * 1024

log = logging.getLogger("assistant")


def setup_logging():
    log.setLevel(logging.INFO)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    fh = logging.handlers.RotatingFileHandler(os.path.join(DATA, "server.log"), maxBytes=1_000_000, backupCount=3)
    fh.setFormatter(fmt)
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)
    log.addHandler(fh)
    log.addHandler(sh)


# --------------------------------------------------------------------------------------
# Config + state
# --------------------------------------------------------------------------------------

def load_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        c = json.load(f)
    c.setdefault("port", 8787)
    c.setdefault("tunnel_cmd", "cloudflared tunnel --no-autoupdate --url http://127.0.0.1:{port}")
    c.setdefault("package_prefix", "com.assistant.u")
    c["owner_id"] = int(c["owner_id"])
    for k in ("bot_token", "gist_token"):
        if not c.get(k):
            raise SystemExit("config.json: %s kosong" % k)
    return c


CFG = {}
_state_lock = threading.Lock()


def state_get(key, default=None):
    with _state_lock:
        try:
            with open(STATE_PATH, "r", encoding="utf-8") as f:
                return json.load(f).get(key, default)
        except (OSError, ValueError):
            return default


def state_set(**kw):
    with _state_lock:
        try:
            with open(STATE_PATH, "r", encoding="utf-8") as f:
                st = json.load(f)
        except (OSError, ValueError):
            st = {}
        st.update(kw)
        tmp = STATE_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(st, f)
        os.replace(tmp, STATE_PATH)


# --------------------------------------------------------------------------------------
# Database
# --------------------------------------------------------------------------------------

_db_local = threading.local()
_db_write_lock = threading.RLock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS apps(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  app_name TEXT NOT NULL,
  package TEXT NOT NULL,
  code TEXT NOT NULL,
  kind TEXT NOT NULL,                -- perm | once | timed
  duration_sec INTEGER DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'unused',  -- unused|active|ended|revoked|expired|deleted
  created_at REAL, first_used_at REAL, expires_at REAL, ended_at REAL,
  android_id TEXT, brand TEXT, model TEXT, android_version TEXT, app_version TEXT,
  last_seen REAL, last_login REAL, ai_count INTEGER DEFAULT 0, last_ai REAL,
  settings TEXT DEFAULT '{}', settings_v INTEGER DEFAULT 0,
  session_hash TEXT, build_status TEXT DEFAULT 'pending', bubble_on INTEGER, model_id TEXT,
  expiry_notified INTEGER DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_apps_pkg ON apps(package) WHERE status != 'deleted';
CREATE TABLE IF NOT EXISTS events(
  id INTEGER PRIMARY KEY AUTOINCREMENT, app_id INTEGER, ts REAL, type TEXT, detail TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_app ON events(app_id, id);
CREATE TABLE IF NOT EXISTS commands(
  id INTEGER PRIMARY KEY AUTOINCREMENT, app_id INTEGER, ts REAL, type TEXT, payload TEXT, delivered INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS fails(key TEXT PRIMARY KEY, count INTEGER, locked_until REAL, ts REAL);
CREATE TABLE IF NOT EXISTS owner_tokens(hash TEXT PRIMARY KEY, name TEXT, created_at REAL);
CREATE TABLE IF NOT EXISTS pair_codes(code TEXT PRIMARY KEY, expires REAL);
"""


def db():
    conn = getattr(_db_local, "conn", None)
    if conn is None:
        conn = sqlite3.connect(DB_PATH, timeout=30, check_same_thread=False, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA busy_timeout=30000")
        _db_local.conn = conn
    return conn


def init_db():
    c = db()
    c.executescript(SCHEMA)


def q(sql, args=()):
    return db().execute(sql, args).fetchall()


def q1(sql, args=()):
    r = db().execute(sql, args).fetchone()
    return r


def ex(sql, args=()):
    with _db_write_lock:
        return db().execute(sql, args)


def add_event(app_id, etype, detail=None):
    ex("INSERT INTO events(app_id,ts,type,detail) VALUES(?,?,?,?)",
       (app_id, time.time(), etype, json.dumps(detail, ensure_ascii=False) if detail is not None else None))


def sha(s):
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------------------
# Telegram
# --------------------------------------------------------------------------------------

def tg(method, http_timeout=40, files=None, **params):
    url = "https://api.telegram.org/bot%s/%s" % (CFG["bot_token"], method)
    if files:
        r = requests.post(url, data=params, files=files, timeout=http_timeout)
    else:
        r = requests.post(url, json=params, timeout=http_timeout)
    try:
        j = r.json()
    except ValueError:
        raise RuntimeError("Telegram balasan aneh: HTTP %s" % r.status_code)
    if not j.get("ok"):
        raise RuntimeError("Telegram error: %s" % j.get("description"))
    return j["result"]


def notify(text, markup=None):
    """Kirim pesan ke owner. Gagal tidak boleh menjatuhkan apa pun."""
    try:
        params = {"chat_id": CFG["owner_id"], "text": text[:4000], "disable_web_page_preview": True}
        if markup:
            params["reply_markup"] = markup
        tg("sendMessage", **params)
    except Exception as e:
        log.warning("notify gagal: %s", e)


def send_doc(path, filename, caption):
    try:
        with open(path, "rb") as f:
            tg("sendDocument", http_timeout=300, files={"document": (filename, f)},
               chat_id=str(CFG["owner_id"]), caption=caption[:1000])
        return True
    except Exception as e:
        log.warning("kirim dokumen gagal: %s", e)
        notify("Gagal mengirim APK: %s" % e)
        return False


# --------------------------------------------------------------------------------------
# Logika inti
# --------------------------------------------------------------------------------------

PKG_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")


def effective_status(a, now=None):
    now = now or time.time()
    st = a["status"]
    if st in ("revoked", "deleted", "ended", "expired"):
        return st
    if a["expires_at"] and now >= a["expires_at"]:
        return "expired"
    return st


def fmt_dur(sec):
    sec = int(sec)
    if sec % 86400 == 0:
        return "%d hari" % (sec // 86400)
    if sec % 3600 == 0:
        return "%d jam" % (sec // 3600)
    return "%d menit" % max(1, sec // 60)


def kind_label(a):
    if a["kind"] == "perm":
        return "Permanen"
    if a["kind"] == "once":
        return "Sekali pakai"
    return "Waktu %s" % fmt_dur(a["duration_sec"] or 0)


def fmt_ts(ts):
    if not ts:
        return "-"
    return time.strftime("%d/%m %H:%M", time.localtime(ts))


def app_public(a, now=None):
    now = now or time.time()
    return {
        "id": a["id"], "app_name": a["app_name"], "package": a["package"], "code": a["code"],
        "kind": a["kind"], "duration_sec": a["duration_sec"] or 0,
        "status": effective_status(a, now),
        "online": bool(a["last_seen"] and now - a["last_seen"] < HEARTBEAT_ONLINE_SEC and a["session_hash"]),
        "last_seen": a["last_seen"], "created_at": a["created_at"], "first_used_at": a["first_used_at"],
        "expires_at": a["expires_at"], "ai_count": a["ai_count"], "last_ai": a["last_ai"],
        "android_id": a["android_id"], "brand": a["brand"], "model": a["model"],
        "android_version": a["android_version"], "app_version": a["app_version"],
        "build_status": a["build_status"], "bubble_on": a["bubble_on"], "model_id": a["model_id"],
        "settings": json.loads(a["settings"] or "{}"),
    }


def gen_package(app_name):
    for _ in range(50):
        p = "%s%s" % (CFG["package_prefix"], secrets.token_hex(4))
        if not q1("SELECT 1 FROM apps WHERE package=? AND status!='deleted'", (p,)):
            return p
    raise RuntimeError("gagal membuat package unik")


def create_app(app_name, package, kind, duration_sec):
    app_name = (app_name or "").strip()
    if not app_name or len(app_name) > 30 or re.search(r"[\x00-\x1f]", app_name):
        raise ValueError("Nama app 1-30 karakter.")
    if kind not in ("perm", "once", "timed"):
        raise ValueError("Jenis harus perm/once/timed.")
    duration_sec = int(duration_sec or 0)
    if kind == "timed" and not (60 <= duration_sec <= 366 * 86400):
        raise ValueError("Durasi 1 menit sampai 366 hari.")
    if kind != "timed":
        duration_sec = 0
    package = (package or "").strip().lower()
    if not package or package == "-":
        package = gen_package(app_name)
    if not PKG_RE.match(package) or len(package) > 80:
        raise ValueError("Package name tidak valid (contoh: com.contoh.app).")
    if package == apkpatch.OLD_PACKAGE or package.startswith(apkpatch.OLD_PACKAGE + "."):
        raise ValueError("Package tidak boleh sama dengan app owner.")
    if q1("SELECT 1 FROM apps WHERE package=? AND status!='deleted'", (package,)):
        raise ValueError("Package itu sudah dipakai app lain.")
    code = "%04d" % secrets.randbelow(10000)
    try:
        cur = ex("INSERT INTO apps(app_name,package,code,kind,duration_sec,status,created_at) VALUES(?,?,?,?,?,'unused',?)",
                 (app_name, package, code, kind, duration_sec, time.time()))
    except sqlite3.IntegrityError:
        raise ValueError("Package itu sudah dipakai app lain.")
    app_id = cur.lastrowid
    add_event(app_id, "created", {"kind": kind, "duration": duration_sec})
    return q1("SELECT * FROM apps WHERE id=?", (app_id,))


_build_lock = threading.Lock()


def build_and_send(app_id, send=True):
    """Patch + sign APK lalu kirim ke Telegram owner. Jalan di thread sendiri."""
    a = q1("SELECT * FROM apps WHERE id=?", (app_id,))
    if not a:
        return
    ex("UPDATE apps SET build_status='building' WHERE id=?", (app_id,))
    try:
        if not os.path.isfile(TEMPLATE_PATH):
            raise apkpatch.PatchError("Template belum ada. Buka app owner > Panel Owner > Kirim template ke server.")
        discovery = state_get("discovery_url")
        if not discovery:
            raise apkpatch.PatchError("Alamat discovery (gist) belum siap. Tunggu tunnel menyala lalu coba lagi.")
        out = os.path.join(BUILD_DIR, "app_%d.apk" % app_id)
        os.makedirs(BUILD_DIR, exist_ok=True)
        with _build_lock:
            apkpatch.build_user_apk(TEMPLATE_PATH, out, a["package"], a["app_name"], discovery,
                                    KEYSTORE, KS_PASS_FILE, os.path.join(BUILD_DIR, "tmp_%d" % app_id))
        ex("UPDATE apps SET build_status='ok' WHERE id=?", (app_id,))
        add_event(app_id, "built", None)
        if send:
            safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", a["app_name"]).strip("_") or "app"
            cap = "%s\nPackage: %s\nKode: %s\nJenis: %s" % (a["app_name"], a["package"], a["code"], kind_label(a))
            send_doc(out, safe + ".apk", cap)
    except Exception as e:
        log.error("build app %s gagal: %s\n%s", app_id, e, traceback.format_exc())
        ex("UPDATE apps SET build_status=? WHERE id=?", ("error: %s" % str(e)[:200], app_id))
        notify("Build APK #%d gagal: %s" % (app_id, e))


def queue_command(app_id, ctype, payload=None):
    ex("INSERT INTO commands(app_id,ts,type,payload) VALUES(?,?,?,?)",
       (app_id, time.time(), ctype, json.dumps(payload or {}, ensure_ascii=False)))
    add_event(app_id, "cmd", {"type": ctype})


def set_setting(app_id, key, value):
    a = q1("SELECT settings FROM apps WHERE id=?", (app_id,))
    s = json.loads(a["settings"] or "{}")
    if key == "model":
        if value not in MODEL_IDS:
            raise ValueError("Model tidak dikenal.")
    elif key in ("accurate", "fallback"):
        value = bool(value)
    else:
        raise ValueError("Setting tidak dikenal.")
    s[key] = value
    ex("UPDATE apps SET settings=?, settings_v=settings_v+1 WHERE id=?", (json.dumps(s), app_id))
    add_event(app_id, "setting", {key: value})


def do_revoke(app_id):
    ex("UPDATE apps SET status='revoked', session_hash=NULL, ended_at=? WHERE id=?", (time.time(), app_id))
    add_event(app_id, "revoked", None)


def do_reset_device(app_id):
    ex("UPDATE apps SET android_id=NULL, brand=NULL, model=NULL, android_version=NULL, session_hash=NULL WHERE id=?", (app_id,))
    add_event(app_id, "reset_device", None)


def do_delete(app_id):
    ex("UPDATE apps SET status='deleted', session_hash=NULL WHERE id=?", (app_id,))
    add_event(app_id, "deleted", None)
    try:
        os.remove(os.path.join(BUILD_DIR, "app_%d.apk" % app_id))
    except OSError:
        pass


# --- login user -----------------------------------------------------------------------

def _fail_key_check(keys, now):
    for k in keys:
        r = q1("SELECT * FROM fails WHERE key=?", (k,))
        if r and r["locked_until"] and r["locked_until"] > now:
            return int(r["locked_until"] - now) + 1
    return 0


def _fail_register(key, limit, now):
    with _db_write_lock:
        r = q1("SELECT * FROM fails WHERE key=?", (key,))
        count = (r["count"] if r else 0)
        if r and now - (r["ts"] or 0) > 3600:
            count = 0
        count += 1
        locked = 0
        if count >= limit:
            locked = now + LOCK_SECONDS
            count = 0
        ex("INSERT INTO fails(key,count,locked_until,ts) VALUES(?,?,?,?) "
           "ON CONFLICT(key) DO UPDATE SET count=?, locked_until=?, ts=?",
           (key, count, locked, now, count, locked, now))
        return locked > 0, limit - count


def handle_login(body, ip):
    now = time.time()
    code = str(body.get("code", ""))[:8]
    package = str(body.get("package", ""))[:100]
    android_id = str(body.get("android_id", ""))[:64]
    if not android_id:
        return {"ok": False, "error": "ID perangkat tidak terbaca."}
    dev_key, ip_key = "dev:" + android_id, "ip:" + ip
    wait = _fail_key_check([dev_key, ip_key], now)
    if wait:
        return {"ok": False, "error": "Terlalu banyak salah. Coba lagi dalam %d menit." % ((wait + 59) // 60),
                "retry_after": wait}
    a = q1("SELECT * FROM apps WHERE package=? AND status!='deleted'", (package,))
    if not a or not hmac.compare_digest(a["code"].encode(), code.encode()):
        locked1, left1 = _fail_register(dev_key, LOCK_AFTER_FAILS, now)
        _fail_register(ip_key, IP_LOCK_AFTER_FAILS, now)
        if a:
            add_event(a["id"], "login_fail", {"android_id": android_id})
        if locked1:
            notify("Kode salah %dx dari satu HP (ID %s), dikunci 15 menit.%s" % (
                LOCK_AFTER_FAILS, android_id[:8], (" App: %s" % a["app_name"]) if a else ""))
            return {"ok": False, "error": "Terlalu banyak salah. Dikunci 15 menit.", "retry_after": LOCK_SECONDS}
        return {"ok": False, "error": "Kode salah. Sisa percobaan: %d." % left1}
    st = effective_status(a, now)
    if st == "revoked":
        return {"ok": False, "error": "Akses ini sudah dicabut oleh owner."}
    if st == "expired":
        return {"ok": False, "error": "Kode sudah kedaluwarsa."}
    if st == "ended":
        return {"ok": False, "error": "Kode sekali pakai ini sudah dipakai."}
    if a["android_id"] and a["android_id"] != android_id:
        add_event(a["id"], "wrong_device", {"android_id": android_id, "model": body.get("model")})
        notify("Kode app \"%s\" dicoba di HP lain (%s %s, ID %s). Ditolak. Reset HP lewat /list kalau memang pindah HP." % (
            a["app_name"], body.get("brand", ""), body.get("model", ""), android_id[:8]))
        return {"ok": False, "error": "Kode ini terkunci ke HP lain."}
    first = a["first_used_at"] is None
    expires = a["expires_at"]
    if first:
        expires = now + a["duration_sec"] if a["kind"] == "timed" else None
    token = secrets.token_urlsafe(32)
    ex("UPDATE apps SET android_id=?, brand=?, model=?, android_version=?, app_version=?, status='active', "
       "first_used_at=COALESCE(first_used_at,?), expires_at=?, last_login=?, last_seen=?, session_hash=? WHERE id=?",
       (android_id, str(body.get("brand", ""))[:40], str(body.get("model", ""))[:60],
        str(body.get("android_version", ""))[:20], str(body.get("app_version", ""))[:20],
        now, expires, now, now, sha(token), a["id"]))
    _fail_reset(dev_key)
    add_event(a["id"], "login", {"model": body.get("model"), "first": first})
    notify("Login: %s (%s %s, Android %s)%s" % (
        a["app_name"], body.get("brand", ""), body.get("model", ""), body.get("android_version", ""),
        " - pertama kali dipakai" if first else ""))
    a = q1("SELECT * FROM apps WHERE id=?", (a["id"],))
    return {"ok": True, "token": token, "kind": a["kind"], "expires_at": a["expires_at"], "server_time": now,
            "settings": json.loads(a["settings"] or "{}"), "settings_v": a["settings_v"]}


def _fail_reset(key):
    ex("DELETE FROM fails WHERE key=?", (key,))


def app_by_token(token):
    if not token:
        return None
    return q1("SELECT * FROM apps WHERE session_hash=? AND status!='deleted'", (sha(str(token)),))


def handle_heartbeat(body):
    now = time.time()
    a = app_by_token(body.get("token"))
    if not a:
        return {"ok": True, "valid": False, "reason": "Sesi tidak berlaku. Masukkan kode lagi.", "server_time": now}
    st = effective_status(a, now)
    if st != "active":
        reasons = {"revoked": "Akses dicabut oleh owner.", "expired": "Kode sudah kedaluwarsa.",
                   "ended": "Kode sekali pakai sudah selesai."}
        if st == "expired" and a["status"] != "expired":
            ex("UPDATE apps SET status='expired', session_hash=NULL, ended_at=? WHERE id=?", (now, a["id"]))
        else:
            ex("UPDATE apps SET session_hash=NULL WHERE id=?", (a["id"],))
        return {"ok": True, "valid": False, "reason": reasons.get(st, "Akses tidak berlaku."), "server_time": now}
    uses = body.get("uses") or []
    n = 0
    if isinstance(uses, list):
        for u in uses[:200]:
            if isinstance(u, dict):
                n += 1
                add_event(a["id"], "ai", {"src": str(u.get("src", ""))[:12], "model": str(u.get("model", ""))[:40],
                                          "level": str(u.get("level", ""))[:10], "t": u.get("t")})
    fields = {"last_seen": now}
    if "app_version" in body:
        fields["app_version"] = str(body["app_version"])[:20]
    if "bubble_on" in body:
        fields["bubble_on"] = 1 if body["bubble_on"] else 0
    if "model_id" in body:
        fields["model_id"] = str(body["model_id"])[:40]
    sets = ", ".join("%s=?" % k for k in fields)
    args = list(fields.values())
    if n:
        sets += ", ai_count=ai_count+?, last_ai=?"
        args += [n, now]
    args.append(a["id"])
    ex("UPDATE apps SET %s WHERE id=?" % sets, args)
    cmds = []
    for c in q("SELECT * FROM commands WHERE app_id=? AND delivered=0 ORDER BY id LIMIT 20", (a["id"],)):
        cmds.append({"type": c["type"], "payload": json.loads(c["payload"] or "{}")})
        ex("UPDATE commands SET delivered=1 WHERE id=?", (c["id"],))
        if c["type"] == "lock":
            ex("UPDATE apps SET session_hash=NULL WHERE id=?", (a["id"],))
    return {"ok": True, "valid": True, "server_time": now, "expires_at": a["expires_at"], "commands": cmds,
            "settings": json.loads(a["settings"] or "{}"), "settings_v": a["settings_v"]}


def handle_logout(body):
    now = time.time()
    a = app_by_token(body.get("token"))
    if not a:
        return {"ok": True}
    reason = str(body.get("reason", "logout"))[:20]
    if a["kind"] == "once":
        ex("UPDATE apps SET status='ended', ended_at=?, session_hash=NULL WHERE id=?", (now, a["id"]))
        add_event(a["id"], "ended", {"reason": reason})
        notify("Kode sekali pakai selesai: %s (%s)." % (a["app_name"], reason))
    else:
        ex("UPDATE apps SET session_hash=NULL WHERE id=?", (a["id"],))
        add_event(a["id"], "logout", {"reason": reason})
    return {"ok": True}


# --- owner ---------------------------------------------------------------------------

def owner_ok(headers):
    tok = headers.get("X-Owner-Token", "")
    if not tok:
        return False
    return q1("SELECT 1 FROM owner_tokens WHERE hash=?", (sha(tok),)) is not None


def new_pair_code():
    code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
    ex("DELETE FROM pair_codes WHERE expires<?", (time.time(),))
    ex("INSERT INTO pair_codes(code,expires) VALUES(?,?)", (code, time.time() + PAIR_TTL))
    return code


def pair_string(code):
    return "ASSISTANT-PAIR|%s|%s" % (state_get("discovery_url") or "", code)


def handle_pair(body, ip):
    now = time.time()
    wait = _fail_key_check(["pair:" + ip], now)
    if wait:
        return {"ok": False, "error": "Terlalu banyak percobaan. Tunggu sebentar."}
    code = str(body.get("code", "")).strip().upper()
    r = q1("SELECT * FROM pair_codes WHERE code=? AND expires>?", (code, now))
    if not r:
        _fail_register("pair:" + ip, 8, now)
        return {"ok": False, "error": "Kode pairing salah atau kedaluwarsa. Kirim /pair lagi ke bot."}
    ex("DELETE FROM pair_codes WHERE code=?", (code,))
    token = secrets.token_urlsafe(40)
    ex("INSERT INTO owner_tokens(hash,name,created_at) VALUES(?,?,?)", (sha(token), str(body.get("name", ""))[:40], now))
    notify("App owner berhasil dipasangkan: %s" % str(body.get("name", ""))[:40])
    return {"ok": True, "token": token}


def handle_owner(path, body, headers, raw=None):
    now = time.time()
    if path == "/v1/owner/ping":
        return {"ok": True, "server_time": now, "template": os.path.isfile(TEMPLATE_PATH),
                "discovery": state_get("discovery_url"), "url": state_get("tunnel_url")}
    if path == "/v1/owner/list":
        rows = q("SELECT * FROM apps WHERE status!='deleted' ORDER BY id DESC")
        return {"ok": True, "apps": [app_public(r, now) for r in rows], "template": os.path.isfile(TEMPLATE_PATH)}
    if path == "/v1/owner/generate":
        a = create_app(body.get("app_name"), body.get("package"), body.get("kind"), body.get("duration_sec"))
        threading.Thread(target=build_and_send, args=(a["id"],), daemon=True).start()
        return {"ok": True, "app": app_public(a), "note": "APK dibuat dan dikirim ke bot Telegram."}
    if path == "/v1/owner/template":
        if raw is None:
            return {"ok": False, "error": "Body kosong."}
        tmp = TEMPLATE_PATH + ".upload"
        with open(tmp, "wb") as f:
            f.write(raw)
        try:
            info = apkpatch.validate_template(tmp)
        except apkpatch.PatchError as e:
            os.remove(tmp)
            return {"ok": False, "error": str(e)}
        os.replace(tmp, TEMPLATE_PATH)
        notify("Template APK diperbarui (%.1f MB)." % (info["size"] / 1048576))
        return {"ok": True, "size": info["size"]}
    aid = body.get("id")
    a = q1("SELECT * FROM apps WHERE id=? AND status!='deleted'", (aid,)) if aid is not None else None
    if path in ("/v1/owner/info", "/v1/owner/command", "/v1/owner/revoke", "/v1/owner/reset", "/v1/owner/delete",
                "/v1/owner/resend", "/v1/owner/setting"):
        if not a:
            return {"ok": False, "error": "App tidak ditemukan."}
    if path == "/v1/owner/info":
        ev = q("SELECT * FROM events WHERE app_id=? ORDER BY id DESC LIMIT 40", (a["id"],))
        return {"ok": True, "app": app_public(a, now),
                "events": [{"ts": e["ts"], "type": e["type"], "detail": json.loads(e["detail"]) if e["detail"] else None}
                           for e in ev]}
    if path == "/v1/owner/command":
        t = body.get("type")
        if t not in ("stop_ai", "hide_bubble", "show_bubble", "message", "lock"):
            return {"ok": False, "error": "Perintah tidak dikenal."}
        payload = {}
        if t == "message":
            text = str(body.get("text", "")).strip()[:500]
            if not text:
                return {"ok": False, "error": "Pesan kosong."}
            payload = {"text": text}
        queue_command(a["id"], t, payload)
        return {"ok": True}
    if path == "/v1/owner/setting":
        try:
            set_setting(a["id"], body.get("key"), body.get("value"))
        except ValueError as e:
            return {"ok": False, "error": str(e)}
        return {"ok": True}
    if path == "/v1/owner/revoke":
        do_revoke(a["id"])
        return {"ok": True}
    if path == "/v1/owner/reset":
        do_reset_device(a["id"])
        return {"ok": True}
    if path == "/v1/owner/delete":
        do_delete(a["id"])
        return {"ok": True}
    if path == "/v1/owner/resend":
        threading.Thread(target=build_and_send, args=(a["id"],), daemon=True).start()
        return {"ok": True}
    return {"ok": False, "error": "Endpoint tidak dikenal."}


# --------------------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 60
    server_version = "Assistant"

    def log_message(self, fmt, *args):
        pass

    def _ip(self):
        return self.headers.get("CF-Connecting-IP") or self.client_address[0]

    def _send(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/health"):
            self._send(200, {"ok": True, "t": time.time()})
        else:
            self._send(404, {"ok": False, "error": "tidak ada"})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            path = self.path.split("?")[0]
            is_template = path == "/v1/owner/template"
            if length < 0 or length > (MAX_TEMPLATE_BYTES if is_template else 1_000_000):
                self._send(413, {"ok": False, "error": "Terlalu besar."})
                return
            raw = self.rfile.read(length) if length else b""
            body = {}
            if not is_template and raw:
                try:
                    body = json.loads(raw.decode("utf-8"))
                    if not isinstance(body, dict):
                        raise ValueError
                except ValueError:
                    self._send(400, {"ok": False, "error": "JSON tidak valid."})
                    return
            ip = self._ip()
            if path == "/v1/login":
                self._send(200, handle_login(body, ip))
            elif path == "/v1/heartbeat":
                self._send(200, handle_heartbeat(body))
            elif path == "/v1/logout":
                self._send(200, handle_logout(body))
            elif path == "/v1/owner/pair":
                self._send(200, handle_pair(body, ip))
            elif path.startswith("/v1/owner/"):
                now_t = time.time()
                if _fail_key_check(["ownerauth:" + ip], now_t):
                    self._send(429, {"ok": False, "error": "Terlalu banyak percobaan."})
                    return
                if not owner_ok(self.headers):
                    _fail_register("ownerauth:" + ip, 10, now_t)
                    self._send(401, {"ok": False, "error": "Tidak diizinkan. Pasangkan ulang app owner."})
                    return
                self._send(200, handle_owner(path, body, self.headers, raw if is_template else None))
            else:
                self._send(404, {"ok": False, "error": "Endpoint tidak dikenal."})
        except ValueError as e:
            self._send(400, {"ok": False, "error": str(e)})
        except Exception as e:
            log.error("handler error: %s\n%s", e, traceback.format_exc())
            try:
                self._send(500, {"ok": False, "error": "Kesalahan server."})
            except Exception:
                pass


class Srv(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 64


def run_http():
    srv = Srv(("127.0.0.1", CFG["port"]), Handler)
    log.info("HTTP siap di 127.0.0.1:%s", CFG["port"])
    srv.serve_forever()


# --------------------------------------------------------------------------------------
# Tunnel + publish URL ke Gist
# --------------------------------------------------------------------------------------

_tunnel_proc = None
_tunnel_lock = threading.Lock()
URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")


def gist_headers():
    return {"Authorization": "Bearer " + CFG["gist_token"], "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28"}


def publish_url(url):
    content = json.dumps({"v": 1, "url": url, "ts": int(time.time())})
    gid = state_get("gist_id")
    if gid:
        r = requests.patch("https://api.github.com/gists/" + gid, headers=gist_headers(), timeout=30,
                           json={"files": {"server.json": {"content": content}}})
        if r.status_code == 404:
            gid = None
        elif r.status_code >= 300:
            raise RuntimeError("Gist PATCH %s: %s" % (r.status_code, r.text[:200]))
        elif not state_get("discovery_url"):
            login = (r.json().get("owner") or {}).get("login") or CFG.get("github_user", "")
            state_set(discovery_url="https://gist.githubusercontent.com/%s/%s/raw/server.json" % (login, gid))
    if not gid:
        r = requests.post("https://api.github.com/gists", headers=gist_headers(), timeout=30,
                          json={"description": "assistant server", "public": False,
                                "files": {"server.json": {"content": content}}})
        if r.status_code >= 300:
            raise RuntimeError("Gist POST %s: %s" % (r.status_code, r.text[:200]))
        j = r.json()
        gid = j["id"]
        login = (j.get("owner") or {}).get("login") or CFG.get("github_user", "")
        state_set(gist_id=gid, discovery_url="https://gist.githubusercontent.com/%s/%s/raw/server.json" % (login, gid))
    state_set(tunnel_url=url, tunnel_ts=time.time())
    log.info("URL dipublish: %s", url)


def stop_tunnel():
    global _tunnel_proc
    with _tunnel_lock:
        p, _tunnel_proc = _tunnel_proc, None
    if p and p.poll() is None:
        try:
            p.terminate()
            p.wait(timeout=8)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass


def tunnel_once():
    """Jalankan satu siklus tunnel sampai mati/tidak sehat. Return saat harus dihidupkan ulang."""
    global _tunnel_proc
    cmd = CFG["tunnel_cmd"].format(port=CFG["port"]).split()
    log.info("Menjalankan tunnel: %s", " ".join(cmd))
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    with _tunnel_lock:
        _tunnel_proc = proc
    found = {"url": None}

    def reader():
        try:
            for line in proc.stdout:
                m = URL_RE.search(line)
                if m and not found["url"]:
                    found["url"] = m.group(0)
        except Exception:
            pass

    threading.Thread(target=reader, daemon=True).start()
    t0 = time.time()
    while not found["url"]:
        if proc.poll() is not None:
            raise RuntimeError("cloudflared berhenti sebelum URL muncul (kode %s)" % proc.returncode)
        if time.time() - t0 > 90:
            raise RuntimeError("URL tunnel tidak muncul dalam 90 detik")
        time.sleep(1)
    url = found["url"]
    # tunggu sampai bisa dijangkau dari luar
    ok = False
    for _ in range(40):
        try:
            if requests.get(url + "/health", timeout=8).status_code == 200:
                ok = True
                break
        except requests.RequestException:
            pass
        if proc.poll() is not None:
            raise RuntimeError("cloudflared berhenti")
        time.sleep(3)
    if not ok:
        raise RuntimeError("URL tunnel belum bisa dijangkau")
    for attempt in range(5):
        try:
            publish_url(url)
            break
        except Exception as e:
            log.warning("publish gagal (%s)", e)
            time.sleep(5 * (attempt + 1))
    else:
        raise RuntimeError("publish URL ke Gist gagal terus")
    notify("Server online.\nURL: %s\nDiscovery: %s" % (url, state_get("discovery_url")))
    bad = 0
    while True:
        time.sleep(30)
        if proc.poll() is not None:
            raise RuntimeError("cloudflared mati")
        try:
            r = requests.get(url + "/health", timeout=10)
            bad = 0 if r.status_code == 200 else bad + 1
        except requests.RequestException:
            bad += 1
        if bad >= 3:
            raise RuntimeError("tunnel tidak sehat 3x berturut-turut")


def run_tunnel():
    while True:
        try:
            tunnel_once()
        except Exception as e:
            log.error("tunnel: %s", e)
        finally:
            stop_tunnel()
        time.sleep(5)


# --------------------------------------------------------------------------------------
# Bot Telegram
# --------------------------------------------------------------------------------------

pending = {}  # state percakapan owner: {"step":..., ...}

HELP = (
    "Perintah:\n"
    "/generate - buat app user + password\n"
    "/list - daftar app user (ketuk untuk kontrol)\n"
    "/pair - kode untuk memasangkan app owner\n"
    "/status - status server\n"
    "/url - alamat server sekarang\n"
    "/info ID /stop ID /revoke ID /reset ID /pesan ID teks"
)


def kb(rows):
    return {"inline_keyboard": [[{"text": t, "callback_data": d} for t, d in row] for row in rows]}


def status_text(a, now=None):
    p = app_public(a, now)
    st = {"unused": "belum dipakai", "active": "aktif", "ended": "selesai", "revoked": "dicabut",
          "expired": "kedaluwarsa"}.get(p["status"], p["status"])
    dot = "🟢 online" if p["online"] else "⚪ offline"
    return p, st, dot


def app_card(a):
    now = time.time()
    p, st, dot = status_text(a, now)
    lines = [
        "#%d %s" % (p["id"], p["app_name"]),
        "Package: %s" % p["package"],
        "Kode: %s  ·  %s" % (p["code"], kind_label(a)),
        "Status: %s  ·  %s" % (st, dot),
        "Terakhir aktif: %s" % fmt_ts(p["last_seen"]),
        "Pemakaian AI: %d kali (terakhir %s)" % (p["ai_count"], fmt_ts(p["last_ai"])),
    ]
    if p["expires_at"]:
        lines.append("Berakhir: %s" % fmt_ts(p["expires_at"]))
    if p["android_id"]:
        lines.append("HP: %s %s, Android %s, app v%s" % (p["brand"], p["model"], p["android_version"], p["app_version"]))
        lines.append("ID: %s" % p["android_id"])
    if p["model_id"]:
        lines.append("Model AI: %s  ·  bubble %s" % (p["model_id"], "aktif" if p["bubble_on"] else "mati"))
    if p["build_status"] != "ok":
        lines.append("Build APK: %s" % p["build_status"])
    return "\n".join(lines)


def app_buttons(aid):
    return kb([
        [("⏹ Stop AI", "c:%d:stop_ai" % aid), ("🙈 Sembunyikan", "c:%d:hide_bubble" % aid), ("👁 Munculkan", "c:%d:show_bubble" % aid)],
        [("💬 Pesan", "m:%d" % aid), ("⚙️ Setting", "s:%d" % aid), ("🔒 Kunci paksa", "c:%d:lock" % aid)],
        [("📜 Log", "l:%d" % aid), ("♻️ Reset HP", "k:%d:reset" % aid), ("🚫 Cabut", "k:%d:revoke" % aid)],
        [("📦 Kirim ulang APK", "c:%d:resend" % aid), ("🗑 Hapus", "k:%d:delete" % aid)],
        [("🔄 Muat ulang", "a:%d" % aid), ("⬅ Daftar", "list")],
    ])


def send_list():
    rows = q("SELECT * FROM apps WHERE status!='deleted' ORDER BY id DESC LIMIT 40")
    if not rows:
        notify("Belum ada app user. Ketik /generate.")
        return
    now = time.time()
    buttons = []
    lines = []
    for a in rows:
        p, st, dot = status_text(a, now)
        lines.append("#%d %s · %s · %s · kode %s · AI %dx" % (p["id"], p["app_name"], st, "online" if p["online"] else "offline", p["code"], p["ai_count"]))
        buttons.append([("#%d %s" % (p["id"], p["app_name"][:24]), "a:%d" % p["id"])])
    notify("\n".join(lines), kb(buttons))


def send_card(aid):
    a = q1("SELECT * FROM apps WHERE id=? AND status!='deleted'", (aid,))
    if not a:
        notify("App #%s tidak ditemukan." % aid)
        return
    notify(app_card(a), app_buttons(a["id"]))


def parse_duration(s):
    m = re.fullmatch(r"\s*(\d+)\s*(m|menit|h|j|jam|d|hari)\s*", s.lower())
    if not m:
        return None
    n = int(m.group(1))
    unit = m.group(2)
    mult = 60 if unit in ("m", "menit") else 3600 if unit in ("h", "j", "jam") else 86400
    return n * mult


def start_generate(app_name, package, kind=None, duration=None):
    if kind is None:
        pending["gen"] = {"step": "kind", "name": app_name, "package": package}
        notify("Jenis password untuk \"%s\"?" % app_name, kb([
            [("Permanen", "g:perm"), ("Sekali pakai", "g:once")],
            [("Berbatas waktu", "g:timed")],
        ]))
        return
    finish_generate(app_name, package, kind, duration)


def finish_generate(app_name, package, kind, duration):
    pending.pop("gen", None)
    try:
        a = create_app(app_name, package, kind, duration)
    except ValueError as e:
        notify("Gagal: %s" % e)
        return
    notify("Dibuat: #%d %s\nPackage: %s\nKode: %s\nJenis: %s\nAPK sedang dibuat, sebentar lagi dikirim." % (
        a["id"], a["app_name"], a["package"], a["code"], kind_label(a)))
    threading.Thread(target=build_and_send, args=(a["id"],), daemon=True).start()


def do_command(aid, ctype, payload=None):
    a = q1("SELECT * FROM apps WHERE id=? AND status!='deleted'", (aid,))
    if not a:
        notify("App #%s tidak ditemukan." % aid)
        return
    if ctype == "resend":
        notify("Membuat ulang APK untuk %s ..." % a["app_name"])
        threading.Thread(target=build_and_send, args=(aid,), daemon=True).start()
        return
    queue_command(aid, ctype, payload)
    notify("Perintah \"%s\" dikirim ke %s. Dijalankan saat app online (maks sekitar 15 detik)." % (ctype, a["app_name"]))


def handle_callback(cb):
    data = cb.get("data", "")
    try:
        tg("answerCallbackQuery", callback_query_id=cb["id"])
    except Exception:
        pass
    parts = data.split(":")
    k = parts[0]
    if k == "list":
        send_list()
    elif k == "a":
        send_card(int(parts[1]))
    elif k == "c":
        do_command(int(parts[1]), parts[2])
    elif k == "m":
        pending["msg"] = {"id": int(parts[1])}
        notify("Ketik pesan untuk dikirim ke app (maks 500 karakter). /batal untuk membatalkan.")
    elif k == "l":
        a = q1("SELECT * FROM apps WHERE id=?", (int(parts[1]),))
        if a:
            ev = q("SELECT * FROM events WHERE app_id=? ORDER BY id DESC LIMIT 25", (a["id"],))
            lines = ["Log #%d %s:" % (a["id"], a["app_name"])]
            for e in ev:
                d = json.loads(e["detail"]) if e["detail"] else {}
                lines.append("%s  %s %s" % (fmt_ts(e["ts"]), e["type"], json.dumps(d, ensure_ascii=False) if d else ""))
            notify("\n".join(lines))
    elif k == "k":  # konfirmasi aksi berbahaya
        aid, what = int(parts[1]), parts[2]
        names = {"reset": "reset kunci HP", "revoke": "cabut akses", "delete": "hapus app"}
        notify("Yakin %s untuk #%d?" % (names.get(what, what), aid), kb([[("Ya", "y:%d:%s" % (aid, what)), ("Batal", "a:%d" % aid)]]))
    elif k == "y":
        aid, what = int(parts[1]), parts[2]
        if what == "revoke":
            do_revoke(aid)
            notify("Akses #%d dicabut." % aid)
        elif what == "reset":
            do_reset_device(aid)
            notify("Kunci HP #%d direset. HP berikutnya yang memakai kode akan terkunci." % aid)
        elif what == "delete":
            do_delete(aid)
            notify("App #%d dihapus dari daftar." % aid)
    elif k == "s":
        aid = int(parts[1])
        rows = [[("Model: %s" % m, "sm:%d:%s" % (aid, m))] for m in MODEL_IDS]
        rows.append([("Mode akurat ON", "sb:%d:accurate:1" % aid), ("OFF", "sb:%d:accurate:0" % aid)])
        rows.append([("Fallback ON", "sb:%d:fallback:1" % aid), ("OFF", "sb:%d:fallback:0" % aid)])
        rows.append([("⬅ Kembali", "a:%d" % aid)])
        notify("Setting jarak jauh untuk #%d:" % aid, kb(rows))
    elif k == "sm":
        try:
            set_setting(int(parts[1]), "model", parts[2])
            notify("Model diganti ke %s (diterapkan saat app online)." % parts[2])
        except ValueError as e:
            notify(str(e))
    elif k == "sb":
        try:
            set_setting(int(parts[1]), parts[2], parts[3] == "1")
            notify("%s = %s (diterapkan saat app online)." % (parts[2], "ON" if parts[3] == "1" else "OFF"))
        except ValueError as e:
            notify(str(e))
    elif k == "g":
        g = pending.get("gen")
        if not g:
            notify("Sesi /generate habis. Ulangi.")
            return
        if parts[1] == "timed":
            g["step"] = "dur"
            notify("Berapa lama? Pilih atau ketik, mis. 90m, 5j, 2d.", kb([
                [("1 jam", "gd:3600"), ("3 jam", "gd:10800"), ("6 jam", "gd:21600")],
                [("12 jam", "gd:43200"), ("1 hari", "gd:86400"), ("3 hari", "gd:259200")],
                [("7 hari", "gd:604800"), ("30 hari", "gd:2592000")],
            ]))
        else:
            finish_generate(g["name"], g["package"], parts[1], 0)
    elif k == "gd":
        g = pending.get("gen")
        if not g:
            notify("Sesi /generate habis. Ulangi.")
            return
        finish_generate(g["name"], g["package"], "timed", int(parts[1]))


def handle_message(msg):
    text = (msg.get("text") or "").strip()
    if not text:
        return
    if text.startswith("/"):
        cmd, _, rest = text.partition(" ")
        cmd = cmd.split("@")[0].lower()
        rest = rest.strip()
        if cmd in ("/start", "/help"):
            notify(HELP)
        elif cmd == "/batal":
            pending.clear()
            notify("Dibatalkan.")
        elif cmd == "/generate":
            pending.clear()
            if rest:
                parts = [x.strip() for x in rest.split("|")]
                name = parts[0]
                pkg = parts[1] if len(parts) > 1 else "-"
                kind = None
                dur = None
                if len(parts) > 2:
                    k = parts[2].lower()
                    kind = {"perm": "perm", "permanen": "perm", "once": "once", "sekali": "once",
                            "timed": "timed", "waktu": "timed"}.get(k)
                    if kind == "timed":
                        dur = parse_duration(parts[3]) if len(parts) > 3 else None
                        if not dur:
                            notify("Format: /generate Nama|package|waktu|2d (m/j/d)")
                            return
                start_generate(name, pkg, kind, dur)
            else:
                pending["gen"] = {"step": "name"}
                notify("Nama app? (contoh: Buku Pintar). /batal untuk batal.")
        elif cmd == "/list":
            send_list()
        elif cmd == "/pair":
            if not state_get("discovery_url"):
                notify("Server belum selesai menyiapkan alamat discovery. Coba lagi sebentar.")
                return
            code = new_pair_code()
            notify("Salin teks ini ke app owner (Pengaturan > Panel Owner), berlaku 10 menit, sekali pakai:\n\n%s" % pair_string(code))
        elif cmd == "/status":
            rows = q("SELECT * FROM apps WHERE status!='deleted'")
            now = time.time()
            online = sum(1 for r in rows if app_public(r, now)["online"])
            notify("Server hidup.\nURL: %s\nApp user: %d (online %d)\nTemplate: %s" % (
                state_get("tunnel_url"), len(rows), online, "ada" if os.path.isfile(TEMPLATE_PATH) else "BELUM ADA"))
        elif cmd == "/url":
            notify("URL: %s\nDiscovery: %s" % (state_get("tunnel_url"), state_get("discovery_url")))
        elif cmd in ("/info", "/stop", "/revoke", "/reset", "/pesan"):
            m = re.match(r"#?(\d+)\s*(.*)", rest)
            if not m:
                notify("Pakai: %s ID" % cmd)
                return
            aid, extra = int(m.group(1)), m.group(2)
            if cmd == "/info":
                send_card(aid)
            elif cmd == "/stop":
                do_command(aid, "stop_ai")
            elif cmd in ("/revoke", "/reset"):
                what = "revoke" if cmd == "/revoke" else "reset"
                notify("Yakin %s #%d?" % ("cabut akses" if what == "revoke" else "reset kunci HP", aid),
                       kb([[("Ya", "y:%d:%s" % (aid, what)), ("Batal", "a:%d" % aid)]]))
            elif cmd == "/pesan":
                if not extra:
                    notify("Pakai: /pesan ID teks")
                else:
                    do_command(aid, "message", {"text": extra[:500]})
        else:
            notify(HELP)
        return
    # teks biasa = jawaban langkah percakapan
    if "msg" in pending:
        aid = pending.pop("msg")["id"]
        do_command(aid, "message", {"text": text[:500]})
        return
    g = pending.get("gen")
    if g:
        if g["step"] == "name":
            g["name"] = text[:30]
            g["step"] = "pkg"
            notify("Package name? Kirim \"-\" untuk otomatis, atau ketik sendiri (contoh: com.contoh.app).")
        elif g["step"] == "pkg":
            g["package"] = text
            start_generate(g["name"], g["package"])
        elif g["step"] == "dur":
            dur = parse_duration(text)
            if not dur:
                notify("Format durasi salah. Contoh: 90m, 5j, 2d.")
            else:
                finish_generate(g["name"], g["package"], "timed", dur)
        return
    notify(HELP)


def run_bot():
    offset = state_get("tg_offset", 0)
    backoff = 2
    while True:
        try:
            ups = tg("getUpdates", http_timeout=45, offset=offset, timeout=30, allowed_updates=["message", "callback_query"])
            backoff = 2
            for u in ups:
                offset = u["update_id"] + 1
                state_set(tg_offset=offset)
                try:
                    if "message" in u:
                        m = u["message"]
                        if (m.get("from") or {}).get("id") == CFG["owner_id"]:
                            handle_message(m)
                    elif "callback_query" in u:
                        cb = u["callback_query"]
                        if (cb.get("from") or {}).get("id") == CFG["owner_id"]:
                            handle_callback(cb)
                except Exception as e:
                    log.error("bot handler: %s\n%s", e, traceback.format_exc())
                    notify("Kesalahan memproses perintah: %s" % e)
        except Exception as e:
            log.warning("bot poll: %s", e)
            time.sleep(backoff)
            backoff = min(backoff * 2, 60)


# --------------------------------------------------------------------------------------
# Pengawas kedaluwarsa + bersih-bersih
# --------------------------------------------------------------------------------------

def run_watch():
    last_clean = 0
    while True:
        try:
            now = time.time()
            for a in q("SELECT * FROM apps WHERE status='active' AND expires_at IS NOT NULL AND expires_at<=?", (now,)):
                ex("UPDATE apps SET status='expired', session_hash=NULL, ended_at=? WHERE id=?", (now, a["id"]))
                add_event(a["id"], "expired", None)
                notify("Kode kedaluwarsa: %s (#%d)." % (a["app_name"], a["id"]))
            if now - last_clean > 6 * 3600:
                last_clean = now
                ex("DELETE FROM events WHERE ts<?", (now - 30 * 86400,))
                ex("DELETE FROM commands WHERE delivered=1 AND ts<?", (now - 7 * 86400,))
                ex("DELETE FROM fails WHERE ts<?", (now - 86400,))
        except Exception as e:
            log.error("watch: %s", e)
        time.sleep(20)


# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------

def supervised(name, fn):
    def loop():
        while True:
            try:
                fn()
            except BaseException as e:  # noqa: B902 - apa pun yang membuat thread mati harus dihidupkan lagi
                log.error("thread %s mati: %s\n%s", name, e, traceback.format_exc())
            time.sleep(3)
    t = threading.Thread(target=loop, name=name, daemon=True)
    t.start()
    return t


def main():
    global CFG
    setup_logging()
    CFG = load_config()
    init_db()
    log.info("Assistant server mulai")
    signal.signal(signal.SIGTERM, lambda *_: (stop_tunnel(), os._exit(0)))
    supervised("http", run_http)
    supervised("watch", run_watch)
    supervised("bot", run_bot)
    supervised("tunnel", run_tunnel)
    notify("Server Assistant menyala.")
    try:
        while True:
            time.sleep(60)
    except KeyboardInterrupt:
        pass
    finally:
        stop_tunnel()


if __name__ == "__main__":
    main()
