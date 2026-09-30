#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Integrate the pinned Neo8 tree for a compilation experiment, not a release."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

PROJECT = Path(__file__).resolve().parents[1]

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
    shutil.copytree(donor / 'device/realme/RE6402L1', device)
    board = device / 'BoardConfig.mk'
    text = board.read_text().replace('soong-libguitwrp_defaults', 'soong-libfoxui_defaults')
    text = text.replace('TW_DEFAULT_LANGUAGE := zh_CN', 'TW_DEFAULT_LANGUAGE := en')
    text = text.replace('TW_DEFAULT_TIMEZONE := "Asia/Shanghai"', 'TW_DEFAULT_TIMEZONE := "Europe/Warsaw"')
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
              'remaining': ['manual metadata preparation UI', 'proven v3 touch stack comparison',
                            'Android runtime partition preservation', 'built image inspection']}
    (PROJECT / 'artifacts/neo8-build').mkdir(parents=True, exist_ok=True)
    (PROJECT / 'artifacts/neo8-build/device-integration.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Device integrated for compilation. Image release is deliberately blocked pending runtime integration.')

if __name__ == '__main__':
    main()
