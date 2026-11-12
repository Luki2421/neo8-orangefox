#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Use the phone-tested TWRP 1.1 Secure Element and StrongBox runtime in OrangeFox.

The pinned device checkout lacks files that only exist in the released image:
the Secure Element launcher, its private ABI libraries, the eSE/NFC transport
libraries and the TA packages. Without them Weaver cannot reach eSE1 and user
decryption with a pattern, PIN or password does not complete. The AERA branch
restores the same files from the same SHA-verified image.

Only the disposable build tree is changed. No recovery ELF, generic system
library, init ordering, fstab or OrangeFox service configuration is taken from
the reference image.
"""
import argparse
import hashlib
import json
from pathlib import Path
import stat

from boot_ramdisk import read_recovery
from inspect_recovery import elf_dependencies, resolve
from secure_element_bundle import (BUNDLE_FILES, REFERENCE_SHA, restore_secure_element_bundle,
                                   validate_secure_element_bundle, verified_reference)

# Crypto, Weaver, eSE and NFC files whose released content differs from the
# checkout. Files that OrangeFox configures itself (qseecomd.rc, ta_config.json,
# prepdecrypt.sh, touch, VINTF fragment selection) are deliberately not listed.
CRYPTO_OVERLAY = (
    'vendor/etc/init/android.hardware.security.secretkeeper-service-qti.rc',
    'vendor/etc/vintf/manifest/android.hardware.secure_element.xml',
    'vendor/lib64/hw/libqtigatekeeper.so',
    'vendor/odm/etc/init/android.hardware.weaver-service-tms.rc',
    'vendor/odm/etc/init/nfc-service-tms.rc',
    'vendor/odm/etc/libese-tms.conf',
    'vendor/odm/etc/vintf/manifest/android.hardware.weaver-service-tms.xml',
    'vendor/odm/etc/vintf/manifest/manifest_nfc_thn31.xml',
    'vendor/odm/etc/vintf/manifest/manifest_nfc_thn31_ese.xml',
    'vendor/odm/lib64/lib_android_keymaster_keymint_utils.so',
    'vendor/odm/lib64/vendor.oplus.hardware.keymint-V1-ndk.so',
)

STRONGBOX_SOURCE = 'vendor/odm/bin/hw/android.hardware.security.keymint-service-strongbox-tms'
STRONGBOX_BINARY = 'system/bin/neo8-strongbox-service'
STRONGBOX_LAUNCHER = 'system/bin/neo8-strongbox-launcher.sh'
STRONGBOX_RC = 'vendor/odm/etc/init/android.hardware.security.keymint-service-strongbox-tms-qcom.rc'
STRONGBOX_LIBS = 'system/lib64/neo8_strongbox'
REFERENCE_LIB_DIRS = ('system/lib64', 'vendor/lib64/hw', 'vendor/lib64', 'vendor/odm/lib64')


def load_reference(path):
    image, entries = read_recovery(path)
    if hashlib.sha256(image).hexdigest() != REFERENCE_SHA:
        raise ValueError('Reference does not match the phone-tested nullguard image')
    verified_reference(image, entries)
    for name in CRYPTO_OVERLAY + (STRONGBOX_SOURCE, STRONGBOX_RC):
        entry = entries.get(name)
        if not entry or not stat.S_ISREG(entry['mode']):
            raise ValueError('Missing regular reference file: ' + name)
    return entries


def strongbox_files(reference):
    """Pin the released executable and its non-system dependency closure.

    Same selection as the AERA branch: recovery system libraries keep
    precedence and a stock /odm mount cannot replace the program or its
    vendor dependencies.
    """
    files = {STRONGBOX_BINARY: reference[STRONGBOX_SOURCE]['data']}
    system_libraries = set()
    pending, seen = [STRONGBOX_SOURCE], set()
    while pending:
        path = pending.pop()
        if path in seen:
            continue
        seen.add(path)
        for name in elf_dependencies(reference[path]['data']):
            for directory in REFERENCE_LIB_DIRS:
                resolved, entry = resolve(reference, directory + '/' + name)
                if entry and stat.S_ISREG(entry['mode']):
                    break
            else:
                raise ValueError('Missing reference StrongBox dependency: ' + name)
            if directory == 'system/lib64':
                system_libraries.add(name)
            else:
                files[STRONGBOX_LIBS + '/' + name] = entry['data']
            pending.append(resolved)
    files[STRONGBOX_LAUNCHER] = b'''#!/system/bin/sh
# Keep this service independent of Android's /odm mount and linker namespace.
umask 077
exec >>/tmp/neo8-strongbox.log 2>&1
echo "Starting reference StrongBox from recovery with private vendor dependencies"
export LD_LIBRARY_PATH=/system/lib64:/system/lib64/neo8_strongbox
exec /system/bin/neo8-strongbox-service
'''
    source = reference[STRONGBOX_RC]['data'].decode().replace('\r\n', '\n')
    old = 'service vendor.keymint-strongbox /odm/bin/hw/android.hardware.security.keymint-service-strongbox-tms'
    if source.count(old) != 1:
        raise ValueError('Unexpected reference StrongBox service')
    files[STRONGBOX_RC] = source.replace(
        old, 'service vendor.keymint-strongbox /system/bin/sh /' + STRONGBOX_LAUNCHER).encode()
    return files, sorted(system_libraries)


def configure_reference_runtime(device, reference):
    """Apply after the OrangeFox service configuration of the device tree."""
    prebuilt = Path(device) / 'prebuilt'
    # Validate every destination before changing any file.
    for name in CRYPTO_OVERLAY:
        target = prebuilt / name
        if target.is_symlink() or not target.is_file():
            raise ValueError('Expected a regular checkout file: ' + name)
    files, system_libraries = strongbox_files(reference)
    for name in files:
        target = prebuilt / name
        if target.is_symlink() or (target.exists() and name != STRONGBOX_RC):
            raise ValueError('Refusing to overwrite StrongBox destination: ' + name)
    overlaid = []
    for name in CRYPTO_OVERLAY:
        entry, target = reference[name], prebuilt / name
        if target.read_bytes() != entry['data']:
            target.write_bytes(entry['data'])
            overlaid.append(name)
        target.chmod(entry['mode'] & 0o777)
    bundle = restore_secure_element_bundle(device, reference)
    for name, data in files.items():
        target = prebuilt / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.chmod(0o755 if name.startswith('system/bin/') else 0o644)
    return {'reference_sha256': REFERENCE_SHA,
            'secure_element_bundle_files': len(bundle),
            'crypto_files_replaced': overlaid,
            'strongbox_files': sorted(files),
            'strongbox_system_libraries_expected_from_build': system_libraries,
            'phone_decryption_verified': False}


def validate_reference_runtime(entries, reference):
    """Compare the packed ramdisk with the reference. Not a hardware test."""
    result = validate_secure_element_bundle(entries, reference)
    for name in CRYPTO_OVERLAY:
        actual, expected = entries.get(name), reference[name]
        if not actual or not stat.S_ISREG(actual['mode']) or actual['data'] != expected['data']:
            raise ValueError('Crypto runtime file differs from reference: ' + name)
    files, _ = strongbox_files(reference)
    for name, data in files.items():
        actual = entries.get(name)
        if not actual or not stat.S_ISREG(actual['mode']) or actual['data'] != data:
            raise ValueError('StrongBox runtime mismatch: ' + name)
        if name.startswith('system/bin/') and not actual['mode'] & 0o111:
            raise ValueError('Non-executable StrongBox runtime: ' + name)
    # Resolve the packed closure in launcher order. A missing recovery system
    # library is reported, because the Secure Element part is still usable.
    pending, seen, missing = [STRONGBOX_BINARY], set(), []
    while pending:
        path = pending.pop()
        if path in seen:
            continue
        seen.add(path)
        for name in elf_dependencies(entries[path]['data']):
            for directory in ('system/lib64', STRONGBOX_LIBS):
                resolved, entry = resolve(entries, directory + '/' + name)
                if entry and stat.S_ISREG(entry['mode']):
                    pending.append(resolved)
                    break
            else:
                missing.append(name)
    result.update({'crypto_files_verified': len(CRYPTO_OVERLAY),
                   'strongbox_files_verified': len(files),
                   'strongbox_dependency_files_checked': len(seen),
                   'strongbox_unresolved_libraries': sorted(set(missing)),
                   'phone_decryption_verified': False})
    return result


def main():
    parser = argparse.ArgumentParser(description='Verify the packed reference runtime in a built image.')
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    reference = load_reference(args.reference)
    _, entries = read_recovery(args.image)
    report = validate_reference_runtime(entries, reference)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
