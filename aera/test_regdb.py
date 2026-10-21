#!/usr/bin/env python3
"""Check regulatory database staging and validation on synthetic files."""
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from regdb import DEST, NAMES, configure_regdb, validate_regdb

DB = b'RGDB' + bytes(range(256))
SIG = b'\x30\x82\x01\x00' + bytes(300)


def expect_error(action):
    try:
        action()
    except ValueError:
        return
    raise SystemExit('Expected ValueError')


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / 'src').mkdir()
    (root / 'src' / NAMES[0]).write_bytes(DB)
    (root / 'src' / NAMES[1]).write_bytes(SIG)
    report = configure_regdb(root / 'dev', root / 'src')
    assert (root / 'dev/prebuilt' / DEST / NAMES[0]).read_bytes() == DB
    entries = {DEST + NAMES[0]: {'data': DB}, DEST + NAMES[1]: {'data': SIG}}
    validate_regdb(entries)
    expect_error(lambda: validate_regdb({DEST + NAMES[0]: {'data': DB}}))
    expect_error(lambda: validate_regdb({**entries, DEST + NAMES[0]: {'data': b'XXXX' + DB[4:]}}))
    expect_error(lambda: validate_regdb({**entries, DEST + NAMES[1]: {'data': b'short'}}))
    (root / 'src' / NAMES[1]).unlink()
    try:
        configure_regdb(root / 'd2', root / 'src')
    except FileNotFoundError:
        pass
    else:
        raise SystemExit('Missing signature was accepted')

print('regdb staging checks passed')
