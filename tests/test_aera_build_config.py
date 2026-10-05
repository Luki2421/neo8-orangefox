#!/usr/bin/env python3
"""Evaluate the native AERA flag bridge against the generated Neo8 board."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-root', type=Path, required=True)
    android = parser.parse_args().android_root.resolve()
    board = (android / 'device/realme/RE6402L1/BoardConfig.mk').read_text()
    # Use actual make evaluation: renamed flags must reach the legacy C++
    # backend as well as AERA's independent native-UI configuration.
    assignments = '\n'.join(line for line in board.splitlines()
                            if re.match(r'^(?:AERA_|TW_|TWRP_)\w+\s*[:?+]?=', line))
    expected = {
        'TW_INCLUDE_CRYPTO': 'true', 'TW_INCLUDE_CRYPTO_FBE': 'true',
        'TW_INCLUDE_FBE_METADATA_DECRYPT': 'true', 'TW_USE_FSCRYPT_POLICY': '2',
        'TW_INCLUDE_FASTBOOTD': 'true', 'TW_INCLUDE_OMAPI': 'true',
        'AERA_DEFAULT_LANGUAGE': 'en', 'AERA_EXTRA_LANGUAGES': 'true',
        'AERA_UI_ADAPTIVE_RESOLUTION': 'true',
        'TW_CUSTOM_CPU_TEMP_PATH': '/sys/class/thermal/thermal_zone19/temp',
        'OF_USE_AIDL_BOOT_CONTROL': '1', 'OF_NO_RELOAD_AFTER_DECRYPTION': '1',
        'TW_NO_AUTO_DECRYPT': '', 'TW_SKIP_POST_GUI_FSTAB_SETUP': '',
    }
    with tempfile.TemporaryDirectory() as directory:
        makefile = Path(directory) / 'flags.mk'
        makefile.write_text(assignments + '\ninclude ' +
                           str(android / 'bootable/recovery/aera_config.mk') + '\n' +
                           '\n'.join('$(info ' + key + '=$(' + key + '))' for key in expected) +
                           '\nall:;@:\n')
        output = subprocess.check_output(['make', '--no-print-directory', '-f', str(makefile)], text=True)
    values = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    for key, value in expected.items():
        if values.get(key) != value:
            raise AssertionError(f'{key}: expected {value!r}, got {values.get(key)!r}')

    # build/make replaces the device's plugin list after BoardConfig is read.
    # Validate the final list against the actual native-UI plugin name.
    config = (android / 'build/make/core/config.mk').read_text()
    plugin = re.search(r'name:\s*"([^"]+)"',
                      (android / 'bootable/recovery/aeraui/build/Android.bp').read_text()).group(1)
    lists = re.findall(r'^\s*BUILD_BROKEN_PLUGIN_VALIDATION\s*:=\s*(.*)$', config, re.M)
    if not lists or plugin not in lists[-1].split():
        raise AssertionError('Native AERA UI plugin is missing from the final build allowlist: ' + plugin)
    print(f'PASS: {len(expected)} evaluated AERA/backend flags and native UI plugin configuration')


if __name__ == '__main__':
    main()
