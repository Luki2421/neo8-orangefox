#!/usr/bin/env python3
"""Prepare an experimental AERA build from pinned Neo8 TWRP 1.1 sources.

The known-good binary is a hardware reference, never a source of the AERA ELF.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import stat
import subprocess
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'scripts'))
from boot_ramdisk import read_recovery
from runtime_config import restore_runtime_config

REFERENCE_SHA = '80b0cbb97a969b4df1ec4c9e51056c4a6433e3536364e987666186f3c5fd7573'

def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True)

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--android-root', type=Path, required=True)
    ap.add_argument('--donor', type=Path, required=True)
    ap.add_argument('--reference', type=Path, required=True)
    ap.add_argument('--report', type=Path, required=True)
    args = ap.parse_args()
    root, donor = args.android_root.resolve(), args.donor.resolve()
    locks = json.loads((HERE / 'sources.lock.json').read_text())
    for repo, directory in [('MissMyTime/twrp_device_sm8850', donor),
                            ('AERA-Recovery/android_bootable_recovery', root / 'bootable/recovery'),
                            ('AERA-Recovery/android_system_vold', root / 'system/vold'),
                            ('AERA-Recovery/android_hardware_interfaces', root / 'hardware/interfaces')]:
        actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=directory, text=True).strip()
        if actual != locks[repo]['commit']:
            raise RuntimeError(f'Wrong revision for {repo}: {actual}')
    image, entries = read_recovery(args.reference)
    if hashlib.sha256(image).hexdigest() != REFERENCE_SHA:
        raise RuntimeError('Reference does not match the user-tested nullguard image')
    device = root / 'device/realme/RE6402L1'
    if device.exists():
        raise RuntimeError('Device destination must be empty')
    shutil.copytree(donor / 'device/realme/RE6402L1', device, symlinks=True)
    report = {'reference_sha256': REFERENCE_SHA, 'overlaid': [], 'not_in_reference': [],
              'status': 'experimental source integration; device testing required'}
    # Match the actual PRODUCT_COPY_FILES destinations. Do not copy system/bin/recovery
    # or generic AERA-built system libraries out of the reference image.
    mappings = [('recovery/root', ''), ('prebuilt/system', 'system'),
                ('prebuilt/system_ext', 'system_ext'), ('prebuilt/sbin', 'sbin'),
                ('prebuilt/vendor', 'vendor')]
    for source, destination in mappings:
        base = device / source
        for path in sorted(base.rglob('*')):
            if path.is_symlink() or not path.is_file():
                continue
            relative = path.relative_to(base).as_posix()
            target = '/'.join(filter(None, [destination, relative]))
            entry = entries.get(target)
            if entry is None or not stat.S_ISREG(entry['mode']):
                report['not_in_reference'].append(target)
                continue
            if target == 'system/bin/recovery':
                raise RuntimeError('Unexpected prebuilt recovery ELF')
            if path.read_bytes() != entry['data']:
                path.write_bytes(entry['data'])
                path.chmod(entry['mode'] & 0o777)
                report['overlaid'].append(target)
    (device / 'recovery.fstab').write_bytes(entries['system/etc/recovery.fstab']['data'])
    report['runtime_configuration'] = restore_runtime_config(device, entries)
    prop = device / 'system.prop'
    text = prop.read_text().replace('ro.crypto.metadata_init_delete_all_keys.enabled=true',
                                    'ro.crypto.metadata_init_delete_all_keys.enabled=false')
    prop.write_text(text)
    # Keep the Neo8 hardware configuration. Use AERA's supported build controls.
    with (device / 'BoardConfig.mk').open('a') as f:
        f.write('\n# Experimental AERA Neo8 integration\n'
                'AERA_INCLUDE_CRYPTO := true\nAERA_INCLUDE_CRYPTO_FBE := true\n'
                'AERA_INCLUDE_FBE_METADATA_DECRYPT := true\nAERA_USE_FSCRYPT_POLICY := 2\n'
                'AERA_INCLUDE_OMAPI := true\nAERA_UI_ADAPTIVE_RESOLUTION := true\n'
                'AERA_DEFAULT_LANGUAGE := en\nAERA_DEVICE_VERSION := Neo8-experimental\n')
    (device / 'vendorsetup.sh').write_text(
        'if [ "${AERA_BUILD_DEVICE:-}" = "RE6402L1" ]; then\n'
        '  export AERA_BUILD_STATUS=Unofficial AERA_BUILD_TYPE=Alpha\n'
        '  export AERA_AB_DEVICE=1 AERA_VIRTUAL_AB_DEVICE=1 AERA_VANILLA_BUILD=1\n'
        '  export AERA_DELETE_MAGISK_ADDON=1 AERA_PRODUCT_PREFIX=AERA\nfi\n')
    vold = root / 'system/vold'
    recovery = root / 'bootable/recovery'
    for patch, directory in [('0001-neo8-vold.patch', vold),
                             ('neo8-keystore-info.patch', vold),
                             ('neo8-keystore-stop.patch', vold),
                             ('0002-neo8-display-start.patch', recovery),
                             ('0003-apex-loop-lifetime.patch', recovery),
                             ('0004-startup-diagnostics.patch', recovery),
                             ('0005-fastboot-manifest-owner.patch', root / 'hardware/interfaces')]:
        run('git', 'apply', '--check', str(HERE / 'patches' / patch), cwd=directory)
        run('git', 'apply', str(HERE / 'patches' / patch), cwd=directory)
    shutil.copy2(donor / 'patches/common/files/system/vold/Weaver1.cpp', vold / 'Weaver1.cpp')
    # The nullguard's old instruction offsets are specific to the TWRP ELF.
    # AERA has no TWFunc::Init_Recovery at this revision: never binary-patch AERA.
    if 'TWFunc::Init_Recovery' in (recovery / 'twrp-functions.cpp').read_text():
        raise RuntimeError('AERA startup changed; re-review the original nullguard site')
    report['nullguard'] = 'Original vulnerable Init_Recovery routine absent in pinned AERA; no binary offsets reused'
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Prepared experimental sources; restored {len(report["overlaid"])} files from tested reference')

if __name__ == '__main__':
    main()
