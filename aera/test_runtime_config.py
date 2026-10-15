#!/usr/bin/env python3
"""Regression checks against the real donor and working TWRP ramdisk."""
import argparse
import copy
from pathlib import Path
import shutil
import stat
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'scripts'))
from boot_ramdisk import read_recovery
from runtime_config import restore_runtime_config, validate_runtime_files, is_manifest, PROFILE_FILES


def rejected(files, reason):
    try:
        validate_runtime_files(files)
    except ValueError as exc:
        assert reason in str(exc), str(exc)
    else:
        raise AssertionError('Validator accepted regression: ' + reason)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--donor', required=True, type=Path)
    ap.add_argument('--reference', required=True)
    args = ap.parse_args()
    _, entries = read_recovery(args.reference)
    reference = {p: e['data'] for p, e in entries.items() if stat.S_ISREG(e['mode'])}
    validate_runtime_files(reference)
    with tempfile.TemporaryDirectory() as tmp:
        device = Path(tmp) / 'device'
        # Only the configuration paths touched by this repair are needed.
        for subtree in ('system/etc/vintf', 'vendor/etc/vintf', 'vendor/odm/etc/vintf'):
            shutil.copytree(args.donor / 'device/realme/RE6402L1/prebuilt' / subtree,
                            device / 'prebuilt' / subtree)
        report = restore_runtime_config(device, entries)
        files = {p.relative_to(device / 'prebuilt').as_posix(): p.read_bytes()
                 for p in (device / 'prebuilt').rglob('*') if p.is_file()}
        validate_runtime_files(files)
        for path in PROFILE_FILES:
            assert files[path] == reference[path]
        for path, data in reference.items():
            if is_manifest(path) and 'android.system.keystore2-service' not in path:
                assert files[path] == data, path
        assert 'system/etc/vintf/manifest/android.hardware.health-service.qti.xml' not in files
        assert 'vendor/etc/vintf/manifest/android.hardware.gatekeeper-service-qti.xml' not in files
        assert 'vendor/odm/etc/vintf/manifest/android.hardware.security.sharedsecret3-service-strongbox-tms.xml' not in files

        broken = copy.copy(files)
        del broken['system/etc/task_profiles.json']
        rejected(broken, 'Missing runtime configuration')
        broken = copy.copy(files)
        broken['vendor/etc/vintf/manifest/duplicate-gatekeeper.xml'] = reference['vendor/etc/vintf/manifest.xml']
        rejected(broken, 'Duplicate HAL')
        broken = copy.copy(files)
        broken['system/etc/vintf/manifest/misplaced-health.xml'] = reference['vendor/etc/vintf/manifest/android.hardware.health-service.qti.xml']
        rejected(broken, 'Wrong VINTF namespace')
        broken = copy.copy(files)
        del broken['vendor/etc/vintf/manifest/android.hardware.security.onekeymint-service-qti.xml']
        rejected(broken, 'Missing required crypto HAL')
        print('Runtime regressions passed: missing profiles, duplicate HAL, namespace, KeyMint')
        print('Removed extra active fragments:', len(report['removed_extra_manifests']))


if __name__ == '__main__':
    main()
