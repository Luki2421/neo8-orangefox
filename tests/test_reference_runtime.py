#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Apply the reference runtime to the real pinned checkout and reject regressions."""
import argparse
from pathlib import Path
import shutil
import stat
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from neo8_reference_runtime import (CRYPTO_OVERLAY, STRONGBOX_BINARY, STRONGBOX_LAUNCHER,
                                    STRONGBOX_RC, configure_reference_runtime,
                                    load_reference, validate_reference_runtime)
from neo8_service_config import (configure_qseecomd, preserve_ssg_ta_files,
                                 remove_duplicate_device_fragments,
                                 use_source_partition_libraries)
from secure_element_bundle import BUNDLE_FILES, LAUNCHER, SERVICE_RC

# device.mk destinations of the pinned tree.
MAPPINGS = (('recovery/root', ''), ('prebuilt/system', 'system'),
            ('prebuilt/system_ext', 'system_ext'), ('prebuilt/sbin', 'sbin'),
            ('prebuilt/vendor/bin', 'vendor/bin'), ('prebuilt/vendor/etc', 'vendor/etc'),
            ('prebuilt/vendor/firmware_mnt', 'vendor/firmware_mnt'),
            ('prebuilt/vendor/lib64', 'vendor/lib64'), ('prebuilt/vendor/odm', 'vendor/odm'))


def packed(device):
    entries = {}
    for source, destination in MAPPINGS:
        base = device / source
        for path in base.rglob('*'):
            if path.is_file() and not path.is_symlink():
                name = '/'.join(filter(None, [destination, path.relative_to(base).as_posix()]))
                entries[name] = {'data': path.read_bytes(), 'mode': path.lstat().st_mode}
    return entries


def rejected(function, expected):
    try:
        function()
    except ValueError as exc:
        assert expected in str(exc), str(exc)
        return
    raise AssertionError('Accepted invalid input: ' + expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--neo8-root', type=Path, required=True)
    parser.add_argument('--reference', type=Path, required=True)
    args = parser.parse_args()
    reference = load_reference(args.reference)
    cases = 0
    with tempfile.TemporaryDirectory() as temporary:
        device = Path(temporary) / 'RE6402L1'
        shutil.copytree(args.neo8_root / 'device/realme/RE6402L1', device, symlinks=True)
        before = packed(device)
        # The checkout alone reproduces the failure: the launcher is absent.
        assert LAUNCHER not in before
        rejected(lambda: validate_reference_runtime(before, reference), 'Missing regular Secure Element')
        cases += 2
        # Same order as prepare_neo8_build.py for the functions that touch these trees.
        use_source_partition_libraries(device)
        remove_duplicate_device_fragments(device)
        configure_qseecomd(device)
        preserve_ssg_ta_files(device)
        ours = {name: (device / 'prebuilt' / name).read_bytes() for name in (
            'vendor/etc/init/qseecomd.rc', 'vendor/etc/ssg/ta_config.json')}
        report = configure_reference_runtime(device, reference)
        assert report['secure_element_bundle_files'] == len(BUNDLE_FILES) == 61
        assert report['phone_decryption_verified'] is False
        # OrangeFox's own service configuration is left as it was.
        for name, data in ours.items():
            assert (device / 'prebuilt' / name).read_bytes() == data, name
        cases += 3
        entries = packed(device)
        result = validate_reference_runtime(entries, reference)
        assert result['files_verified'] == 61
        assert result['crypto_files_verified'] == len(CRYPTO_OVERLAY)
        assert b'/odm/bin/hw/neo8-secure-element-launcher.sh' in entries[SERVICE_RC]['data']
        assert entries[LAUNCHER]['mode'] & 0o111
        assert b'/system/bin/sh /system/bin/neo8-strongbox-launcher.sh' in entries[STRONGBOX_RC]['data']
        assert entries[STRONGBOX_BINARY]['mode'] & 0o111 and entries[STRONGBOX_LAUNCHER]['mode'] & 0o111
        # Nothing outside the declared additions and replacements changed.
        declared = set(BUNDLE_FILES) | set(CRYPTO_OVERLAY) | set(report['strongbox_files'])
        after_service_config = {n for n in set(before) | set(entries)
                                if before.get(n, {}).get('data') != entries.get(n, {}).get('data')}
        service_config_only = {'system/lib64/liblp.so', 'system/lib64/libfs_mgr.so',
                               'vendor/etc/init/qseecomd.rc', 'vendor/etc/ssg/ta_config.json',
                               'vendor/etc/vintf/manifest/android.hardware.gatekeeper-service-qti.xml',
                               'vendor/etc/vintf/manifest/se_omapi.xml'}
        unexpected = {n for n in after_service_config - declared - service_config_only
                      if not n.startswith('system/etc/firmware/neo8-ta/')}
        assert not unexpected, sorted(unexpected)
        cases += 8
        for name in (LAUNCHER, BUNDLE_FILES[5], CRYPTO_OVERLAY[2], STRONGBOX_BINARY):
            broken = dict(entries)
            del broken[name]
            rejected(lambda: validate_reference_runtime(broken, reference), name)
            cases += 1
        changed = dict(entries)
        changed[CRYPTO_OVERLAY[3]] = {'data': b'changed', 'mode': stat.S_IFREG | 0o644}
        rejected(lambda: validate_reference_runtime(changed, reference), 'differs from reference')
        plain = dict(entries)
        plain[STRONGBOX_LAUNCHER] = dict(entries[STRONGBOX_LAUNCHER], mode=stat.S_IFREG | 0o644)
        rejected(lambda: validate_reference_runtime(plain, reference), 'Non-executable StrongBox')
        rejected(lambda: configure_reference_runtime(device, reference), 'Refusing to overwrite')
        cases += 3
    with tempfile.NamedTemporaryFile() as wrong:
        data = bytearray(args.reference.read_bytes())
        data[-1] ^= 1
        wrong.write(data)
        wrong.flush()
        rejected(lambda: load_reference(Path(wrong.name)), 'does not match')
        cases += 1
    print(f'{cases} reference runtime checks passed; '
          f'unresolved StrongBox system libraries in checkout-only view: '
          f'{result["strongbox_unresolved_libraries"]}')


if __name__ == '__main__':
    main()
