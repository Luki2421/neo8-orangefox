#!/usr/bin/env python3
"""Check the reference update_engine_sideload is staged and its link checker works."""
import argparse
from pathlib import Path
import stat
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'scripts'))
from boot_ramdisk import read_recovery
from ota_sideload import SIDELOAD, link_problems, validate_ota_sideload
from secure_element_bundle import verified_reference

if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--device', required=True, type=Path)
    ap.add_argument('--reference', required=True)
    args = ap.parse_args()
    image, entries = read_recovery(args.reference)
    reference = verified_reference(image, entries)
    staged = args.device / 'prebuilt' / SIDELOAD
    if staged.read_bytes() != reference[SIDELOAD]['data'] or not staged.stat().st_mode & 0o111:
        raise SystemExit('Reference update_engine_sideload was not staged as executable')
    files = {p: e['data'] for p, e in entries.items() if stat.S_ISREG(e['mode'])}
    # The checker must accept the working reference pair...
    if link_problems(files[SIDELOAD], files) != ([], []):
        raise SystemExit('Reference sideload does not link against the reference libraries')
    validate_ota_sideload(entries, entries)
    # ...and reject a missing library and a library that lacks the needed symbols.
    broken = dict(files)
    del broken['system/lib64/liblp.so']
    if link_problems(files[SIDELOAD], broken)[0] != ['liblp.so']:
        raise SystemExit('Missing liblp was not detected')
    broken = dict(files)
    broken['system/lib64/liblp.so'] = files['system/lib64/libbase.so']
    if not link_problems(files[SIDELOAD], broken)[1]:
        raise SystemExit('Unresolved liblp symbols were not detected')
    print('update_engine_sideload staging and link checks passed')
