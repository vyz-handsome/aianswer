#!/usr/bin/env python3
"""
Patcher APK Assistant (tanpa apktool, murni Python).

Template = APK owner yang di-build GitHub Actions. Patcher membuat salinan "app user":
  - ganti package name (AndroidManifest.xml biner)
  - ganti nama app (label launcher di manifest + app_name di resources.arsc)
  - ganti mode meta-data "assistant_mode_owner" -> "assistant_mode_user"
  - isi alamat discovery (gist) ke meta-data
  - tanda tangan ulang pakai apksigner (v2/v3), jadi bisa dipasang.

Hanya mengubah string pool, jadi aman dan cepat (detik). Semua index string tetap,
tidak ada resource yang digeser.
"""
import os
import re
import struct
import subprocess
import sys
import zipfile

OLD_PACKAGE = "com.assistant.ai"
OLD_LABEL = "Assistant"
MODE_OWNER = "assistant_mode_owner"
MODE_USER = "assistant_mode_user"
DISCOVERY_PLACEHOLDER = "DISCOVERY_URL_PLACEHOLDER_ASSISTANT_V1"

RES_STRING_POOL = 0x0001
RES_TABLE = 0x0002
RES_XML = 0x0003

FLAG_UTF8 = 1 << 8


class PatchError(Exception):
    pass


# --------------------------------------------------------------------------------------
# ResStringPool
# --------------------------------------------------------------------------------------

class StringPool:
    def __init__(self, strings, flags, style_offsets, style_data):
        self.strings = strings
        self.flags = flags
        self.style_offsets = style_offsets  # list[int], dipertahankan apa adanya
        self.style_data = style_data        # bytes mentah bagian style

    @property
    def utf8(self):
        return bool(self.flags & FLAG_UTF8)


def _read_len16(buf, off):
    n = struct.unpack_from("<H", buf, off)[0]
    off += 2
    if n & 0x8000:
        n2 = struct.unpack_from("<H", buf, off)[0]
        off += 2
        n = ((n & 0x7FFF) << 16) | n2
    return n, off


def _read_len8(buf, off):
    n = buf[off]
    off += 1
    if n & 0x80:
        n = ((n & 0x7F) << 8) | buf[off]
        off += 1
    return n, off


def parse_pool(buf, start):
    """Baca string pool yang chunk-nya mulai di `start`. Return (StringPool, chunk_size)."""
    ctype, hsize, size = struct.unpack_from("<HHI", buf, start)
    if ctype != RES_STRING_POOL:
        raise PatchError("bukan string pool (type=0x%04x)" % ctype)
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from("<IIIII", buf, start + 8)
    offs_base = start + hsize
    str_offsets = struct.unpack_from("<%dI" % string_count, buf, offs_base)
    style_offsets = list(struct.unpack_from("<%dI" % style_count, buf, offs_base + 4 * string_count)) if style_count else []
    utf8 = bool(flags & FLAG_UTF8)
    data_base = start + strings_start
    strings = []
    for so in str_offsets:
        p = data_base + so
        if utf8:
            _, p = _read_len8(buf, p)       # panjang dalam unit UTF-16 (diabaikan)
            blen, p = _read_len8(buf, p)
            strings.append(bytes(buf[p:p + blen]).decode("utf-8", errors="surrogatepass"))
        else:
            n, p = _read_len16(buf, p)
            strings.append(bytes(buf[p:p + 2 * n]).decode("utf-16-le", errors="surrogatepass"))
    style_data = b""
    if style_count:
        style_data = bytes(buf[start + styles_start:start + size])
    return StringPool(strings, flags, style_offsets, style_data), size


def _enc_len16(n):
    if n > 0x7FFF:
        return struct.pack("<HH", 0x8000 | (n >> 16), n & 0xFFFF)
    return struct.pack("<H", n)


def _enc_len8(n):
    if n > 0x7F:
        return bytes([0x80 | (n >> 8), n & 0xFF])
    return bytes([n])


def build_pool(pool):
    """Bangun ulang chunk string pool dengan isi pool.strings."""
    data = bytearray()
    offsets = []
    for s in pool.strings:
        offsets.append(len(data))
        if pool.utf8:
            raw = s.encode("utf-8", errors="surrogatepass")
            u16len = len(s.encode("utf-16-le", errors="surrogatepass")) // 2
            data += _enc_len8(u16len) + _enc_len8(len(raw)) + raw + b"\x00"
        else:
            raw = s.encode("utf-16-le", errors="surrogatepass")
            data += _enc_len16(len(raw) // 2) + raw + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    header_size = 28
    string_count = len(pool.strings)
    style_count = len(pool.style_offsets)
    strings_start = header_size + 4 * string_count + 4 * style_count
    styles_start = (strings_start + len(data)) if style_count else 0
    size = strings_start + len(data) + (len(pool.style_data) if style_count else 0)
    out = bytearray()
    out += struct.pack("<HHI", RES_STRING_POOL, header_size, size)
    out += struct.pack("<IIIII", string_count, style_count, pool.flags, strings_start, styles_start)
    out += struct.pack("<%dI" % string_count, *offsets)
    if style_count:
        out += struct.pack("<%dI" % style_count, *pool.style_offsets)
    out += data
    if style_count:
        out += pool.style_data
    return bytes(out)


# --------------------------------------------------------------------------------------
# AndroidManifest.xml biner
# --------------------------------------------------------------------------------------

_CLASS_RE = re.compile(r"^" + re.escape(OLD_PACKAGE) + r"\.[A-Z][A-Za-z0-9_$.]*$")


def patch_manifest(data, package, label, mode, discovery):
    """Return (bytes_baru, laporan). Hanya string pool yang diubah."""
    data = bytes(data)
    ctype, hsize, size = struct.unpack_from("<HHI", data, 0)
    if ctype != RES_XML:
        raise PatchError("AndroidManifest.xml bukan XML biner")
    pool, psize = parse_pool(data, hsize)
    rest = data[hsize + psize:]
    strings = pool.strings
    report = {"package": 0, "label": 0, "mode": 0, "discovery": 0, "relative": 0, "other": []}

    if OLD_PACKAGE not in strings:
        raise PatchError("package %s tidak ditemukan di manifest (template salah?)" % OLD_PACKAGE)

    new = []
    for s in strings:
        t = s
        if s.startswith(".") and len(s) > 1 and " " not in s:
            # nama relatif (.MainActivity) harus tetap menunjuk ke class lama
            t = OLD_PACKAGE + s
            report["relative"] += 1
        elif s == OLD_PACKAGE:
            t = package
            report["package"] += 1
        elif s == OLD_LABEL:
            t = label
            report["label"] += 1
        elif s == MODE_OWNER:
            t = mode
            report["mode"] += 1
        elif s == DISCOVERY_PLACEHOLDER:
            t = discovery
            report["discovery"] += 1
        elif OLD_PACKAGE in s:
            if _CLASS_RE.match(s) and not s.endswith("_PERMISSION"):
                t = s  # nama class tetap
            else:
                # authority provider / permission kustom ikut package baru (hindari bentrok dengan app owner)
                t = s.replace(OLD_PACKAGE, package)
                report["other"].append((s, t))
        new.append(t)
    pool.strings = new
    body = build_pool(pool) + rest
    out = struct.pack("<HHI", RES_XML, hsize, hsize + len(body)) + data[8:hsize] + body
    # header XML = 8 byte, jadi data[8:hsize] kosong
    if report["package"] == 0:
        raise PatchError("package tidak terganti")
    return out, report


# --------------------------------------------------------------------------------------
# resources.arsc
# --------------------------------------------------------------------------------------

def patch_arsc(data, label):
    data = bytes(data)
    ctype, hsize, size, pkg_count = struct.unpack_from("<HHII", data, 0)
    if ctype != RES_TABLE:
        raise PatchError("resources.arsc tidak valid")
    pool, psize = parse_pool(data, hsize)
    changed = 0
    new = []
    for s in pool.strings:
        if s == OLD_LABEL:
            new.append(label)
            changed += 1
        else:
            new.append(s)
    if not changed:
        return data, 0
    pool.strings = new
    body = build_pool(pool) + data[hsize + psize:]
    out = struct.pack("<HHII", RES_TABLE, hsize, hsize + len(body), pkg_count) + data[12:hsize] + body
    return out, changed


# --------------------------------------------------------------------------------------
# ZIP
# --------------------------------------------------------------------------------------

def _align_extra(pos, name_len, align=4):
    """Panjang extra supaya data entri jatuh di kelipatan `align`."""
    base = pos + 30 + name_len
    for n in (0, 4, 5, 6, 7, 8):
        if (base + n) % align == 0:
            return n
    return 0


def _extra(n):
    if n == 0:
        return b""
    return struct.pack("<HH", 0xD935, n - 4) + b"\x00" * (n - 4)


def rewrite_apk(src, dst_unsigned, manifest_bytes, arsc_bytes):
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst_unsigned, "w") as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", ".MF")):
                continue  # tanda tangan lama dibuang
            if info.is_dir():
                continue
            if name == "AndroidManifest.xml":
                payload = manifest_bytes
                stored = False
            elif name == "resources.arsc":
                payload = arsc_bytes
                stored = True  # wajib tidak dikompres + rata 4 byte (targetSdk 30+)
            else:
                payload = zin.read(name)
                stored = info.compress_type == zipfile.ZIP_STORED
            zi = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
            zi.external_attr = 0o644 << 16
            zi.create_system = 3
            if stored:
                zi.compress_type = zipfile.ZIP_STORED
                pos = zout.fp.tell()
                zi.extra = _extra(_align_extra(pos, len(name.encode("utf-8"))))
            else:
                zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, payload)


def find_apksigner():
    for name in ("apksigner", "apksigner.bat"):
        for d in os.environ.get("PATH", "").split(os.pathsep):
            p = os.path.join(d, name)
            if os.path.isfile(p) and os.access(p, os.X_OK):
                return p
    return None


def sign_apk(unsigned, signed, keystore, ks_pass_file, alias="assistant"):
    tool = find_apksigner()
    if not tool:
        raise PatchError("apksigner tidak ditemukan. Jalankan: pkg install apksigner")
    cmd = [tool, "sign", "--ks", keystore, "--ks-key-alias", alias,
           "--ks-pass", "file:" + ks_pass_file, "--key-pass", "file:" + ks_pass_file,
           "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
           "--out", signed, unsigned]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        raise PatchError("apksigner gagal: " + (r.stderr or r.stdout).strip()[:400])
    try:
        os.remove(signed + ".idsig")
    except OSError:
        pass


def validate_template(path):
    """Cek template: APK owner yang belum dipatch. Return info dict atau raise PatchError."""
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            for need in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
                if need not in names:
                    raise PatchError("bukan APK Assistant (tidak ada %s)" % need)
            data = z.read("AndroidManifest.xml")
    except zipfile.BadZipFile:
        raise PatchError("file bukan ZIP/APK yang valid")
    ctype, hsize, _ = struct.unpack_from("<HHI", data, 0)
    pool, _ = parse_pool(data, hsize)
    s = set(pool.strings)
    if OLD_PACKAGE not in s:
        raise PatchError("package template bukan %s" % OLD_PACKAGE)
    if MODE_OWNER not in s or DISCOVERY_PLACEHOLDER not in s:
        raise PatchError("template tidak punya meta-data mode/discovery (pakai build v0.27.0 ke atas)")
    return {"size": os.path.getsize(path)}


def build_user_apk(template, out_path, package, label, discovery, keystore, ks_pass_file, workdir):
    """Patch + tanda tangan. Return out_path."""
    os.makedirs(workdir, exist_ok=True)
    with zipfile.ZipFile(template) as z:
        manifest = z.read("AndroidManifest.xml")
        arsc = z.read("resources.arsc")
    m2, rep = patch_manifest(manifest, package, label, MODE_USER, discovery)
    if rep["mode"] == 0 or rep["discovery"] == 0:
        raise PatchError("meta-data mode/discovery tidak ditemukan di template")
    a2, _ = patch_arsc(arsc, label)
    unsigned = os.path.join(workdir, "unsigned.apk")
    rewrite_apk(template, unsigned, m2, a2)
    sign_apk(unsigned, out_path, keystore, ks_pass_file)
    try:
        os.remove(unsigned)
    except OSError:
        pass
    return out_path


if __name__ == "__main__":
    if len(sys.argv) < 7:
        print("pakai: apkpatch.py template.apk out.apk package label discovery_url keystore ks_pass_file")
        sys.exit(1)
    t, o, pk, lb, dsc, ks, kp = sys.argv[1:8]
    build_user_apk(t, o, pk, lb, dsc, ks, kp, os.path.dirname(os.path.abspath(o)) or ".")
    print("OK", o)
