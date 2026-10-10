#!/usr/bin/env python3
"""Enkripsi API key untuk Secrets.kt (pengaburan dua lapis, bukan keamanan sungguhan).

Pakai: python3 tools/enc_key.py   (tempel key saat diminta; hasilnya ditempel ke blob provider di Secrets.kt)
Pecah hasilnya jadi potongan string pendek kalau mau, selama digabung urut.
"""
import base64
import getpass

K1 = bytes([115, 116, 52, 100, 121, 45, 48, 118, 51, 114, 108, 52, 121, 35, 107, 49])
K2 = bytes([65, 73, 33, 98, 117, 98, 98, 108, 101, 46, 50, 48, 50, 54, 126, 107, 50])


def xor(b, k):
    return bytes(x ^ k[i % len(k)] for i, x in enumerate(b))


key = getpass.getpass("API key: ").strip().encode()
s1 = xor(key, K1)
s2 = base64.b64encode(s1)
s3 = s2[::-1]
s4 = xor(s3, K2)
print(base64.b64encode(s4).decode())
