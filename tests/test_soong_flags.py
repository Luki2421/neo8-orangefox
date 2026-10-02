#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Evaluate the real Make -> Soong export, not manually supplied C++ defines."""
import argparse
import itertools
from pathlib import Path
import subprocess
import tempfile

FLAGS = ('TW_NO_AUTO_DECRYPT', 'TW_FORCE_STOCK_THEME_ON_BOOT')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    parser.add_argument('--vendor-root', type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='neo8-soong-test-') as directory:
        root = Path(directory)
        (root / 'bootable').mkdir()
        (root / 'bootable/recovery').symlink_to(args.recovery_root.resolve(), target_is_directory=True)
        (root / 'vendor').symlink_to(args.vendor_root.resolve(), target_is_directory=True)
        makefile = root / 'Makefile'
        for values in itertools.product(('', 'false', 'true'), repeat=2):
            makefile.write_text(
                '\n'.join(f'{flag} := {value}' for flag, value in zip(FLAGS, values)) +
                '\ninclude vendor/config/BoardConfigSoong.mk\n'
                '.PHONY: check\ncheck:\n'
                '\t@echo "$(SOONG_CONFIG_twrpVarsPlugin)"\n' +
                ''.join(f'\t@echo "{flag}=$(SOONG_CONFIG_twrpVarsPlugin_{flag})"\n'
                        for flag in FLAGS))
            result = subprocess.run(['make', '--no-print-directory', '-s', 'check'],
                                    cwd=root, check=True, text=True, capture_output=True)
            lines = result.stdout.splitlines()
            exported = lines[0].split()
            for flag, value in zip(FLAGS, values):
                if flag not in exported or f'{flag}={value}' not in lines[1:]:
                    raise AssertionError(f'{flag}={value!r} did not reach twrpVarsPlugin: {lines}')
    print('PASS: 9 real Make -> Soong flag combinations, including the Neo8 true/true profile.')


if __name__ == '__main__':
    main()
