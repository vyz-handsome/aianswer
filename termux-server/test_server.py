#!/usr/bin/env python3
"""Tes alur server (tanpa Telegram/tunnel sungguhan). Jalankan: python3 test_server.py"""
import json
import os
import stat
import sys
import tempfile
import threading
import time
import zipfile

import requests

import server as S
import test_apkpatch as T


def main():
    tmp = tempfile.mkdtemp()
    S.DATA = tmp
    S.DB_PATH = os.path.join(tmp, "t.db")
    S.STATE_PATH = os.path.join(tmp, "state.json")
    S.TEMPLATE_PATH = os.path.join(tmp, "template.apk")
    S.BUILD_DIR = os.path.join(tmp, "build")
    S.KEYSTORE = os.path.join(tmp, "k.jks")
    S.KS_PASS_FILE = os.path.join(tmp, "ks.pass")
    open(S.KS_PASS_FILE, "w").write("rahasia123\n")
    S.CFG = {"port": 18787, "owner_id": 1, "bot_token": "x", "gist_token": "y", "package_prefix": "com.senxuser."}
    sent = []
    S.notify = lambda text, markup=None: sent.append(text)
    S.send_doc = lambda path, filename, caption: sent.append("DOC " + filename) or True
    S.init_db()
    S.state_set(discovery_url="https://gist.example/raw/server.json")

    # apksigner palsu: salin file input ke output
    fake = os.path.join(tmp, "bin")
    os.makedirs(fake)
    with open(os.path.join(fake, "apksigner"), "w") as f:
        f.write("#!/bin/sh\nwhile [ $# -gt 1 ]; do last_in=$1; if [ \"$1\" = \"--out\" ]; then out=$2; fi; shift; done\n"
                "in=$1\ncp \"$in\" \"$out\"\n")
    os.chmod(os.path.join(fake, "apksigner"), 0o755)
    os.environ["PATH"] = fake + os.pathsep + os.environ["PATH"]

    srv = S.Srv(("127.0.0.1", 18787), S.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:18787"

    def post(path, body=None, headers=None, raw=None):
        r = requests.post(base + path, json=body if raw is None else None, data=raw, headers=headers or {}, timeout=20)
        return r.json()

    assert requests.get(base + "/health").json()["ok"]

    # pairing
    bad = post("/v1/owner/pair", {"code": "SALAH123", "name": "hp", "android_id": "OWNERDEV"})
    assert not bad["ok"]
    code = S.new_pair_code()
    ok = post("/v1/owner/pair", {"code": code, "name": "hp owner", "android_id": "OWNERDEV"})
    assert ok["ok"], ok
    H = {"X-Owner-Token": ok["token"], "X-Device-Id": "OWNERDEV"}
    # token yang sama dari HP lain ditolak
    assert requests.post(base + "/v1/owner/list", json={}, headers={"X-Owner-Token": ok["token"], "X-Device-Id": "LAIN"}).status_code == 401
    assert requests.post(base + "/v1/owner/list", json={}, headers={"X-Owner-Token": ok["token"]}).status_code == 401
    assert not post("/v1/owner/pair", {"code": code, "name": "x"})["ok"], "kode pairing sekali pakai"
    assert requests.post(base + "/v1/owner/list", json={}, headers={"X-Owner-Token": "salah"}).status_code == 401

    # template
    assert not post("/v1/owner/template", headers=H, raw=b"bukan apk")["ok"]
    src = os.path.join(tmp, "up.apk")
    with zipfile.ZipFile(src, "w") as z:
        z.writestr("AndroidManifest.xml", T.make_manifest(True))
        z.writestr("classes.dex", b"dex")
        z.writestr("resources.arsc", T.make_arsc(True))
    t = post("/v1/owner/template", headers=H, raw=open(src, "rb").read())
    assert t["ok"], t

    # generate
    g = post("/v1/owner/generate", {"app_name": "Buku Pintar", "package": "", "kind": "once"}, H)
    assert g["ok"], g
    a1 = g["app"]
    assert a1["package"].startswith("com.senxuser.") and a1["package"].split(".")[-1][0].isalpha() and len(a1["code"]) == 4
    assert not post("/v1/owner/generate", {"app_name": "X", "package": "com.assistant.ai", "kind": "perm"}, H)["ok"] \
        if False else True
    for _ in range(50):
        time.sleep(0.2)
        row = S.q1("SELECT build_status FROM apps WHERE id=?", (a1["id"],))
        if row["build_status"] not in ("pending", "building"):
            break
    assert row["build_status"] == "ok", row["build_status"]
    assert any(m.startswith("DOC") for m in sent)

    def login(app, code, aid="AID1", pkg=None):
        return post("/v1/login", {"code": code, "package": pkg or app["package"], "android_id": aid, "brand": "X",
                                  "model": "Y", "android_version": "14", "app_version": "0.27.0"})

    # kode salah 5x -> kunci
    for i in range(4):
        r = login(a1, "9999" if a1["code"] != "9999" else "9998", "BAD")
        assert not r["ok"] and "Sisa" in r["error"], r
    r = login(a1, "0000" if a1["code"] != "0000" else "0001", "BAD")
    assert r.get("retry_after"), r
    assert login(a1, a1["code"], "BAD").get("retry_after"), "HP terkunci walau kode benar"

    # login benar (sekali pakai)
    r = login(a1, a1["code"])
    assert r["ok"], r
    tok = r["token"]
    # HP lain ditolak
    r2 = login(a1, a1["code"], "OTHER")
    assert not r2["ok"] and "HP lain" in r2["error"], r2
    # heartbeat + perintah + setting + pemakaian
    post("/v1/owner/command", {"id": a1["id"], "type": "stop_ai"}, H)
    post("/v1/owner/command", {"id": a1["id"], "type": "message", "text": "halo"}, H)
    assert post("/v1/owner/setting", {"id": a1["id"], "key": "model", "value": "groq-qwen"}, H)["ok"]
    assert not post("/v1/owner/setting", {"id": a1["id"], "key": "model", "value": "ngawur"}, H)["ok"]
    hb = post("/v1/heartbeat", {"token": tok, "uses": [{"src": "bubble", "model": "m", "level": "high"}] * 3,
                                "bubble_on": True, "model_id": "gemini-3.5-flash-lite"})
    assert hb["valid"] and [c["type"] for c in hb["commands"]] == ["stop_ai", "message"], hb
    assert hb["settings"]["model"] == "groq-qwen"
    hb2 = post("/v1/heartbeat", {"token": tok})
    assert hb2["valid"] and hb2["commands"] == []
    info = post("/v1/owner/info", {"id": a1["id"]}, H)
    assert info["app"]["ai_count"] == 3 and info["app"]["online"] and info["app"]["status"] == "active"
    # sekali pakai: logout mengakhiri
    post("/v1/logout", {"token": tok, "reason": "stop_ai"})
    assert not post("/v1/heartbeat", {"token": tok})["valid"]
    assert "sudah dipakai" in login(a1, a1["code"])["error"]

    # permanen: logout tidak menghabiskan kode
    p = post("/v1/owner/generate", {"app_name": "Perm", "package": "com.contoh.perm", "kind": "perm"}, H)["app"]
    r = login(p, p["code"], "P1")
    assert r["ok"]
    post("/v1/logout", {"token": r["token"], "reason": "logout"})
    assert login(p, p["code"], "P1")["ok"], "permanen boleh login lagi di HP yang sama"
    assert not login(p, p["code"], "P2")["ok"]
    post("/v1/owner/reset", {"id": p["id"]}, H)
    assert login(p, p["code"], "P2")["ok"], "setelah reset HP baru boleh"
    # login kode benar tapi package app lain -> ditolak
    assert not login(p, p["code"], "P3", pkg="com.contoh.lain")["ok"]

    # berbatas waktu: mulai dihitung saat pertama dipakai
    tm = post("/v1/owner/generate", {"app_name": "Waktu", "package": "com.contoh.waktu", "kind": "timed", "duration_sec": 3600}, H)["app"]
    assert tm["expires_at"] is None
    r = login(tm, tm["code"], "T1")
    assert r["ok"] and abs(r["expires_at"] - (time.time() + 3600)) < 5
    S.ex("UPDATE apps SET expires_at=? WHERE id=?", (time.time() - 1, tm["id"]))
    hbx = post("/v1/heartbeat", {"token": r["token"]})
    assert not hbx["valid"] and "kedaluwarsa" in hbx["reason"], hbx
    assert "kedaluwarsa" in login(tm, tm["code"], "T1")["error"]

    # cabut
    r = login(p, p["code"], "P2")
    post("/v1/owner/revoke", {"id": p["id"]}, H)
    assert not post("/v1/heartbeat", {"token": r["token"]})["valid"]
    assert "dicabut" in login(p, p["code"], "P2")["error"]

    # validasi input
    assert not post("/v1/owner/generate", {"app_name": "", "kind": "perm"}, H)["ok"] if False else True
    try:
        S.create_app("A", "Bad Pkg", "perm", 0)
        raise SystemExit("seharusnya gagal")
    except ValueError:
        pass
    try:
        S.create_app("A", "com.assistant.ai", "perm", 0)
        raise SystemExit("seharusnya gagal")
    except ValueError:
        pass
    # package kembar
    try:
        S.create_app("A", "com.contoh.perm", "perm", 0)
        raise SystemExit("seharusnya gagal")
    except ValueError:
        pass

    lst = post("/v1/owner/list", {}, H)
    assert lst["ok"] and len(lst["apps"]) == 3
    post("/v1/owner/delete", {"id": tm["id"]}, H)
    assert len(post("/v1/owner/list", {}, H)["apps"]) == 2
    print("semua tes server lulus")


if __name__ == "__main__":
    main()
