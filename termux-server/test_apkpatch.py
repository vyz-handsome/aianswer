#!/usr/bin/env python3
"""Tes patcher dengan APK sintetis (tanpa tanda tangan). Jalankan: python3 test_apkpatch.py"""
import os
import struct
import tempfile
import zipfile

import apkpatch as ap


def make_pool(strings, utf8):
    p = ap.StringPool(list(strings), ap.FLAG_UTF8 if utf8 else 0, [], b"")
    return ap.build_pool(p)


def make_manifest(utf8):
    strings = [
        "package", "name", "label", "value", "mode",
        ap.OLD_PACKAGE, ".MainActivity", ".Launcher_n0_c0", ap.OLD_LABEL,
        "assistant.mode", ap.MODE_OWNER, ap.DISCOVERY_PLACEHOLDER,
        "com.assistant.ai.OverlayService",
        ap.OLD_PACKAGE + ".androidx-startup",
        ap.OLD_PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        "android.permission.INTERNET",
        "Ünïcode ✓ label",
    ]
    pool = make_pool(strings, utf8)
    tail = b"\x80\x01\x08\x00\x08\x00\x00\x00" + b"\x00" * 8  # resource map kosong (dummy)
    body = pool + tail
    return struct.pack("<HHI", ap.RES_XML, 8, 8 + len(body)) + body


def make_arsc(utf8):
    pool = make_pool(["res/layout/a.xml", ap.OLD_LABEL, "Selamat datang"], utf8)
    pkg = struct.pack("<HHI", 0x0200, 288, 288) + b"\x00" * (288 - 8)
    body = pool + pkg
    return struct.pack("<HHII", ap.RES_TABLE, 12, 12 + len(body), 1) + body


def check(utf8):
    m = make_manifest(utf8)
    a = make_arsc(utf8)
    m2, rep = ap.patch_manifest(m, "com.tes.baru", "Buku Pintar", ap.MODE_USER, "https://x.test/server.json")
    hs = 8
    pool, psize = ap.parse_pool(m2, hs)
    s = pool.strings
    assert s[5] == "com.tes.baru", s[5]
    assert s[6] == "com.assistant.ai.MainActivity"
    assert s[7] == "com.assistant.ai.Launcher_n0_c0"
    assert s[8] == "Buku Pintar"
    assert s[10] == ap.MODE_USER
    assert s[11] == "https://x.test/server.json"
    assert s[12] == "com.assistant.ai.OverlayService"            # class tetap
    assert s[13] == "com.tes.baru.androidx-startup"               # authority ikut
    assert s[14] == "com.tes.baru.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    assert s[16] == "Ünïcode ✓ label"
    total = struct.unpack_from("<I", m2, 4)[0]
    assert total == len(m2), (total, len(m2))
    assert m2[hs + psize:].startswith(b"\x80\x01"), "chunk sesudah pool harus utuh"
    a2, n = ap.patch_arsc(a, "Buku Pintar")
    assert n == 1
    ap_pool, _ = ap.parse_pool(a2, 12)
    assert ap_pool.strings[1] == "Buku Pintar"
    assert struct.unpack_from("<I", a2, 4)[0] == len(a2)
    assert a2.endswith(b"\x00" * 100)
    return m2, a2


def main():
    for utf8 in (False, True):
        check(utf8)
    # zip: arsc harus stored + rata 4 byte
    m, a = make_manifest(True), make_arsc(True)
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "t.apk")
        with zipfile.ZipFile(src, "w") as z:
            z.writestr("AndroidManifest.xml", m)
            z.writestr("classes.dex", b"dex\n035\x00" + b"\x01" * 50)
            z.writestr("resources.arsc", a)
            z.writestr("META-INF/CERT.RSA", b"x")
            z.writestr("res/a.xml", b"<a/>" * 20)
        ap.validate_template(src)
        m2, _ = ap.patch_manifest(m, "com.x.y", "N", ap.MODE_USER, "https://d")
        a2, _ = ap.patch_arsc(a, "N")
        dst = os.path.join(d, "o.apk")
        ap.rewrite_apk(src, dst, m2, a2)
        with zipfile.ZipFile(dst) as z:
            names = z.namelist()
            assert "META-INF/CERT.RSA" not in names
            zi = z.getinfo("resources.arsc")
            assert zi.compress_type == zipfile.ZIP_STORED
            with open(dst, "rb") as f:
                f.seek(zi.header_offset)
                hdr = f.read(30)
                nl, el = struct.unpack_from("<HH", hdr, 26)
                assert (zi.header_offset + 30 + nl + el) % 4 == 0
            assert z.read("resources.arsc") == a2
            assert z.testzip() is None
    print("semua tes patcher lulus")


if __name__ == "__main__":
    main()
