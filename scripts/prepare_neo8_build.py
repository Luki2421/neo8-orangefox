#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Integrate the pinned Neo8 tree for a compilation experiment, not a release."""
import argparse
import json
import hashlib
from pathlib import Path
import shutil
import subprocess

PROJECT = Path(__file__).resolve().parents[1]

GATEKEEPER_HAL = ('    <hal format="aidl">\n'
                  '        <name>android.hardware.gatekeeper</name>\n'
                  '        <fqname>IGatekeeper/default</fqname>\n'
                  '    </hal>\n')

def fix_vendor_manifest(device):
    """Drop the IGatekeeper entry duplicated by its own VINTF fragment.

    On the phone servicemanager logged 'VINTF parse error ... HAL
    "android.hardware.gatekeeper" has a conflict' for manifest.xml vs
    manifest/android.hardware.gatekeeper-service-qti.xml. With the device
    manifest unreadable, AServiceManager_addService() returns -3 and the boot,
    health and weaver HALs abort. The fragment keeps the declaration.
    """
    vintf = device / 'prebuilt/vendor/etc/vintf'
    manifest = vintf / 'manifest.xml'
    fragment = vintf / 'manifest/android.hardware.gatekeeper-service-qti.xml'
    text = manifest.read_text()
    if text.count(GATEKEEPER_HAL) != 1:
        raise RuntimeError('Unexpected pinned vendor VINTF manifest')
    if '<fqname>IGatekeeper/default</fqname>' not in fragment.read_text():
        raise RuntimeError('Gatekeeper VINTF fragment missing; refusing to drop manifest entry')
    manifest.write_text(text.replace(GATEKEEPER_HAL, '', 1))

def configure_runtime(device):
    touch = device / 'prebuilt/vendor/odm/etc/init/vendor-oplus-hardware-touch-V2-service.rc'
    text = touch.read_text()
    if text.count('    class hal\n') != 1 or 'LD_LIBRARY_PATH' in text:
        raise RuntimeError('Unexpected pinned touch init configuration')
    startup = 'on property:servicemanager.ready=true\n    start vendor.touch-aidl-1'
    if text.count(startup) != 1:
        raise RuntimeError('Unexpected pinned touch startup trigger')
    text = text.replace('    class hal\n',
                        '    class hal\n    disabled\n    setenv LD_LIBRARY_PATH /system/lib64:/vendor/odm/lib64:/vendor/lib64:/vendor/odm/firmware/tp/u9\n', 1)
    text = text.replace(startup,
                        'on property:servicemanager.ready=true\n    exec u:r:recovery:s0 root root -- /system/bin/sh /system/bin/neo8-touch-props.sh\n    start vendor.touch-aidl-1', 1)
    reader = device / 'prebuilt/vendor/bin/prepdecrypt.sh'
    script = reader.read_text()
    if not script.startswith('#!/sbin/sh\n'):
        raise RuntimeError('Unexpected stock property reader')
    reader_rc = device / 'prebuilt/vendor/etc/init/prepdecrypt.rc'
    rc = reader_rc.read_text()
    original = 'service prepdecrypt.vendor /vendor/bin/prepdecrypt.sh'
    if rc.count(original) != 1:
        raise RuntimeError('Unexpected stock property reader service')
    # Keep this recovery helper accessible when stock /vendor is mounted.
    destination = device / 'recovery/root/system/bin/neo8-prepdecrypt.sh'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(script.replace('#!/sbin/sh', '#!/system/bin/sh', 1))
    destination.chmod(0o755)
    reader_rc.write_text(rc.replace(original, 'service prepdecrypt.vendor /system/bin/neo8-prepdecrypt.sh', 1))
    touch.write_text(text)
    fix_vendor_manifest(device)
    return {'touch_library_order_matches_v3': True,
            'vendor_manifest_gatekeeper_duplicate_removed': True,
            'touch_program': 'pinned Neo8 native service; differs from u9/v3',
            'touch_phone_test_required': True,
            'stock_reader_preserved_in_recovery_system': True,
            'stock_reader_sha256': hashlib.sha256(destination.read_bytes()).hexdigest()}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-root', type=Path, required=True)
    parser.add_argument('--neo8-root', type=Path, required=True)
    args = parser.parse_args()
    android = args.android_root.resolve()
    donor = args.neo8_root.resolve()
    sources = json.loads((PROJECT / 'port-sources.json').read_text())
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=donor, text=True).strip()
    if actual != sources['neo8']['commit']:
        raise RuntimeError('Unexpected device source revision')
    device = android / 'device/realme/RE6402L1'
    if device.exists():
        raise RuntimeError('Refusing to overwrite an existing device tree')
    recovery = android / 'bootable/recovery'
    for name in ('neo8-manual-menu.patch', 'neo8-runtime.patch'):
        patch = PROJECT / 'patches/fox16' / name
        subprocess.run(['git', 'apply', '--check', str(patch)], cwd=recovery, check=True)
        subprocess.run(['git', 'apply', str(patch)], cwd=recovery, check=True)
    subprocess.run(['git', 'diff', '--check'], cwd=recovery, check=True)
    shutil.copytree(donor / 'device/realme/RE6402L1', device)
    runtime = configure_runtime(device)
    board = device / 'BoardConfig.mk'
    text = board.read_text().replace('soong-libguitwrp_defaults', 'soong-libfoxui_defaults')
    text = text.replace('TW_DEFAULT_LANGUAGE := zh_CN', 'TW_DEFAULT_LANGUAGE := en')
    text = text.replace('TW_DEFAULT_TIMEZONE := "Asia/Shanghai"', 'TW_DEFAULT_TIMEZONE := "Europe/Warsaw"')
    # OrangeFox 16 runs Setup_Fstab_Partitions() only after gui_init(), so this flag
    # skipped it entirely: no /data setup, no /sdcard, no APEX, and the manual
    # decrypt menu entry (neo8_manual_decrypt) stayed hidden. TW_NO_AUTO_DECRYPT
    # already keeps startup from decrypting, so the setup itself is safe to run.
    skip_flag = 'TW_SKIP_POST_GUI_FSTAB_SETUP := true'
    if skip_flag not in text:
        raise RuntimeError(f'Expected {skip_flag!r} in BoardConfig.mk')
    if 'TW_NO_AUTO_DECRYPT := true' not in text:
        raise RuntimeError('Refusing to enable fstab setup without TW_NO_AUTO_DECRYPT')
    text = text.replace(skip_flag, 'TW_SKIP_POST_GUI_FSTAB_SETUP := false')
    board.write_text(text)
    props = device / 'system.prop'
    text = props.read_text().replace('ro.crypto.metadata_init_delete_all_keys.enabled=true',
                                     'ro.crypto.metadata_init_delete_all_keys.enabled=false')
    props.write_text(text)
    rc = device / 'recovery/root/init.recovery.qcom.rc'
    lines = rc.read_text().splitlines()
    # Bound otherwise indefinite waits; do not change service startup order here.
    for index, line in enumerate(lines):
        if line.strip().startswith('wait ') and len(line.strip().split()) == 2:
            lines[index] = line + ' 2'
    rc.write_text('\n'.join(lines) + '\n')
    (device / 'vendorsetup.sh').write_text('''# Neo8 OrangeFox compilation experiment.
export FOX_BUILD_DEVICE=RE6402L1
export FOX_AB_DEVICE=1
export FOX_VIRTUAL_AB_DEVICE=1
export FOX_VANILLA_BUILD=1
export OF_DISABLE_MIUI_SPECIFIC_FEATURES=1
export FOX_VARIANT=Unofficial
export FOX_MAINTAINER_PATCH_VERSION=4
''')
    report = {'device_tree_integrated': True, 'source_revision': actual,
              'device': 'RMX8899 / RE6402L1', 'compilation_experiment_only': True,
              'flashable_release': False, 'phone_decryption_verified': False,
              'manual_metadata_menu_integrated': True,
              'runtime_configuration': runtime,
              'stock_keymint_before_first_metadata_operation': True,
              'automatic_fstab_runtime_partition_preservation': True,
              'remaining': ['full Android compilation', 'built image inspection',
                            'phone startup, native touch and decryption test']}
    (PROJECT / 'artifacts/neo8-build').mkdir(parents=True, exist_ok=True)
    (PROJECT / 'artifacts/neo8-build/device-integration.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Development profile integrated. Image inspection and phone startup/touch/decryption tests remain.')

if __name__ == '__main__':
    main()
