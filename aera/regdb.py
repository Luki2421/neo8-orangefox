#!/usr/bin/env python3
"""Ship the signed wireless regulatory database for cfg80211.

Without regulatory.db the kernel logs "failed to load regulatory.db" and the WCN7750
driver runs on the world regulatory domain. cfg80211 asks for regulatory.db and its
detached signature regulatory.db.p7s; ueventd serves them from /etc/firmware/, which is
already in the recovery's firmware_directories. The files come from the distribution
wireless-regdb package on the build runner (signed with a key compiled into the kernel).
"""
from pathlib import Path

NAMES = ('regulatory.db', 'regulatory.db.p7s')
DEST = 'system/etc/firmware/'
MAGIC = b'RGDB'


def check_pair(db, signature):
    if db[:4] != MAGIC or len(db) < 64:
        raise ValueError('regulatory.db has no RGDB header')
    if len(signature) < 256 or signature[:1] != b'\x30':
        raise ValueError('regulatory.db.p7s is not a DER PKCS#7 signature')


def configure_regdb(device, source):
    source = Path(source)
    data = {name: (source / name).read_bytes() for name in NAMES}
    check_pair(data[NAMES[0]], data[NAMES[1]])
    for name, content in data.items():
        target = Path(device) / 'prebuilt' / DEST / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        target.chmod(0o644)
    return {'directory': '/' + DEST, 'sizes': {n: len(c) for n, c in data.items()}}


def validate_regdb(entries):
    data = {}
    for name in NAMES:
        entry = entries.get(DEST + name)
        if not entry:
            raise ValueError('Missing ' + DEST + name)
        data[name] = entry['data']
    check_pair(data[NAMES[0]], data[NAMES[1]])
    return {'files': [DEST + n for n in NAMES]}
