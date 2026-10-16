#!/usr/bin/env python3
"""Check restored sources and reproduce the missing-launcher regression."""
import argparse
from pathlib import Path
import stat
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'scripts'))
from boot_ramdisk import read_recovery
from secure_element_bundle import (
    BUNDLE_FILES, LAUNCHER, PRIVATE_LIBS, verified_reference,
    restore_secure_element_bundle, validate_secure_element_bundle,
)


def source_entries(device):
    result = {}
    for name in BUNDLE_FILES:
        path = device / 'prebuilt' / name
        if path.exists():
            result[name] = {'data': path.read_bytes(), 'mode': path.lstat().st_mode}
    return result


def rejected(entries, reference, expected):
    try:
        validate_secure_element_bundle(entries, reference)
    except ValueError as exc:
        assert expected in str(exc), str(exc)
    else:
        raise AssertionError('Accepted broken Secure Element bundle')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--reference', required=True)
    parser.add_argument('--device', required=True, type=Path)
    args = parser.parse_args()
    reference = verified_reference(*read_recovery(args.reference))
    validate_secure_element_bundle(source_entries(args.device), reference)
    with tempfile.TemporaryDirectory() as tmp:
        device = Path(tmp)
        restore_secure_element_bundle(device, reference)
        entries = source_entries(device)
        validate_secure_element_bundle(entries, reference)
        # Every component is required, including an entirely absent launcher.
        for name in BUNDLE_FILES:
            broken = entries.copy()
            del broken[name]
            rejected(broken, reference, name)
        broken = entries.copy()
        name = 'vendor/odm/lib64/neo8_se_current/' + PRIVATE_LIBS[0]
        broken[name] = dict(entries[name], data=b'wrong ABI')
        rejected(broken, reference, 'reference mismatch')
        broken = entries.copy()
        broken[LAUNCHER] = dict(entries[LAUNCHER], mode=stat.S_IFDIR | 0o755)
        rejected(broken, reference, 'Missing regular')
        broken[LAUNCHER] = dict(entries[LAUNCHER], mode=stat.S_IFREG | 0o644)
        rejected(broken, reference, 'mode mismatch')
    print(f'SE bundle: prepared sources verified, {len(BUNDLE_FILES)} missing-file cases, '
          'wrong ABI, directory masquerading as script, non-executable launcher rejected')


if __name__ == '__main__':
    main()
