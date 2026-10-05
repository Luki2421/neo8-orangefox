#!/usr/bin/env python3
"""Integrate the pinned Neo8 hardware and guarded crypto into native AERA."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

from prepare_neo8_build import configure_runtime
from neo8_service_config import (configure_service_files, configure_fastboot_manifest,
                                 configure_omapi_manifest, configure_qseecomd,
                                 preserve_ssg_ta_files)

PROJECT = Path(__file__).resolve().parents[1]

def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()

def apply(root, path):
    subprocess.run(['git', 'apply', '--check', str(path)], cwd=root, check=True)
    subprocess.run(['git', 'apply', str(path)], cwd=root, check=True)

def configure_device(android, donor):
    device = android / 'device/realme/RE6402L1'
    if device.exists():
        raise RuntimeError('Refusing to overwrite an existing device tree')
    shutil.copytree(donor / 'device/realme/RE6402L1', device)
    report = configure_runtime(device)
    report['services'] = configure_service_files(device, android)
    report['fastboot'] = configure_fastboot_manifest(device, android)
    report['omapi'] = configure_omapi_manifest(device, android)
    report['qseecomd'] = configure_qseecomd(device)
    report['ssg'] = preserve_ssg_ta_files(device)
    board = device / 'BoardConfig.mk'
    text = board.read_text()
    # Native AERA owns startup and its PIN screen. Do not carry the old XML
    # menu's "skip setup" flags, theme positions, or automatic-decrypt blocker.
    obsolete = {'TW_NO_AUTO_DECRYPT', 'TW_SKIP_POST_GUI_FSTAB_SETUP',
                'TW_FORCE_STOCK_THEME_ON_BOOT', 'TW_CUSTOM_CPU_POS',
                'TW_CUSTOM_CLOCK_POS', 'TW_CUSTOM_BATTERY_POS', 'TW_STATUS_ICONS_ALIGN'}
    text = '\n'.join(line for line in text.splitlines()
                     if line.split(':=')[0].strip() not in obsolete) + '\n'
    text = text.replace('TW_DEFAULT_LANGUAGE := zh_CN', 'AERA_DEFAULT_LANGUAGE := en')
    text = text.replace('TW_EXTRA_LANGUAGES := true', 'AERA_EXTRA_LANGUAGES := true')
    # AERA's public switches map to the inherited TWRP backend in aera_config.mk.
    config = (android / 'bootable/recovery/aera_config.mk').read_text()
    aliases = dict((legacy, public) for public, legacy in
                   re.findall(r'aera-map-config,([A-Z0-9_]+),(TW_[A-Z0-9_]+)', config))
    legacy_list = config.split('aera-legacy-tw-options :=', 1)[1].split('$(foreach', 1)[0]
    aliases.update({'TW_' + key: key for key in re.findall(r'\b[A-Z][A-Z0-9_]+\b', legacy_list)})
    for legacy, public in aliases.items():
        text = re.sub(r'(?m)^' + legacy + r'(?=\s*[:?+]?=)', 'AERA_' + public, text)
    text += '''
# Native AERA geometry; the physical panel is handled by adaptive rendering.
AERA_BUILD_DEVICE := RE6402L1
AERA_UI_ADAPTIVE_RESOLUTION := true
AERA_SCREEN_H := 2354
AERA_STATUS_H := 144
AERA_STATUS_INDENT_LEFT := 128
AERA_STATUS_INDENT_RIGHT := 128
AERA_CUSTOM_CPU_TEMP_PATH := /sys/class/thermal/thermal_zone19/temp
AERA_FL_PATH1 := /sys/class/leds/white:flash-1
AERA_AB_DEVICE_WITH_RECOVERY_PARTITION := 1
AERA_USE_AIDL_BOOT_CONTROL := 1
AERA_NO_RELOAD_AFTER_DECRYPTION := 1
AERA_SKIP_LEGACY_PATCH_PROCESS := 1
'''
    board.write_text(text)
    (device / 'vendorsetup.sh').write_text('''# Neo8 AERA development build.
export AERA_BUILD_DEVICE=RE6402L1
export AERA_AB_DEVICE=1
export AERA_VIRTUAL_AB_DEVICE=1
export AERA_VANILLA_BUILD=1
export AERA_PRODUCT_PREFIX=AERA
export AERA_BUILD_STATUS=Unofficial
export AERA_BUILD_TYPE=Beta
export AERA_USE_DMSETUP=1
export AERA_TARGET_DEVICES="RE6402L1,RMX8899"
''')
    props = device / 'system.prop'
    props.write_text(props.read_text().replace('ro.crypto.metadata_init_delete_all_keys.enabled=true',
                                               'ro.crypto.metadata_init_delete_all_keys.enabled=false'))
    # Preserve the shared module-ready/MTP contracts and match AERA's crypto hooks.
    for path in device.rglob('*.rc'):
        text = path.read_text()
        text = text.replace('twrp.mount_to_decrypt', 'aera.mount_to_decrypt')
        text = text.replace('ro.twrp.fastbootd', 'ro.aera.fastbootd')
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if line.strip().startswith('wait ') and len(line.split()) == 2:
                lines[index] = line + ' 2'
        path.write_text('\n'.join(lines) + '\n')
    return report

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-root', type=Path, required=True)
    parser.add_argument('--neo8-root', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    android, donor = args.android_root.resolve(), args.neo8_root.resolve()
    pins = json.loads((PROJECT / 'aera-sources.json').read_text())
    neo8 = json.loads((PROJECT / 'port-sources.json').read_text())['neo8']
    for path in ('bootable/recovery', 'system/vold', 'hardware/interfaces', 'system/core', 'build/make'):
        if git(android / path, 'rev-parse', 'HEAD') != pins['projects'][path]['commit']:
            raise RuntimeError('Unexpected source revision: ' + path)
        if git(android / path, 'status', '--porcelain', '--untracked-files=no'):
            raise RuntimeError('Use a clean source checkout: ' + path)
    if git(donor, 'rev-parse', 'HEAD') != neo8['commit']:
        raise RuntimeError('Unexpected donor revision')
    vold = android / 'system/vold'
    copies = []
    for relative, expected in neo8['files'].items():
        data = (donor / relative).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('Unexpected donor file: ' + relative)
        copies.append((vold / Path(relative).name, data))
    apply(vold, PROJECT / 'patches/fox16/neo8-keystore-info.patch')
    for target, content in copies:
        target.write_bytes(content)
    for name in ('neo8-keystore-stop.patch', 'neo8-stock-properties.patch', 'neo8-no-lock-vold.patch'):
        apply(vold, PROJECT / 'patches/fox16' / name)
    apply(android / 'bootable/recovery', PROJECT / 'patches/aera16/neo8-recovery.patch')
    apply(android / 'build/make', PROJECT / 'patches/aera16/neo8-build-plugins.patch')
    report = configure_device(android, donor)
    for path in ('bootable/recovery', 'system/vold', 'hardware/interfaces', 'build/make'):
        git(android / path, 'diff', '--check')
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps({'sources': pins, 'runtime': report,
        'native_aera_ui': True, 'phone_validation': 'pending'}, indent=2) + '\n')
    print('Neo8 integrated with native AERA; compilation and phone validation remain.')

if __name__ == '__main__':
    main()
