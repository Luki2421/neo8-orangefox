#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Add swap only on a disposable GitHub-hosted runner, reserving build disk space."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

from neo8_preflight import memory

GIB = 1024 ** 3
TARGET_SWAP = 15 * GIB
DISK_RESERVE = 16 * GIB


def required_swap(free_bytes, swap_bytes):
    additional = max(0, TARGET_SWAP - swap_bytes)
    if additional and free_bytes - additional < DISK_RESERVE:
        raise RuntimeError('Insufficient disk for swap plus 16 GiB build reserve')
    return additional


def prepare(android_root):
    if not (os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and platform.system() == 'Linux'):
        raise RuntimeError('Swap setup is restricted to disposable GitHub-hosted Linux runners')
    root = android_root.resolve(strict=True)
    before = memory()
    if before['SwapTotal'] is None:
        raise RuntimeError('Cannot read existing swap size')
    disk_before = shutil.disk_usage(root).free
    additional = required_swap(disk_before, before['SwapTotal'])
    if additional:
        # O_EXCL also refuses symlinks and files left by an earlier invocation.
        swap_file = root / '.neo8-ci.swap'
        fd = os.open(swap_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
        subprocess.run(['fallocate', '--length', str(additional), str(swap_file)], check=True)
        subprocess.run(['sudo', 'mkswap', str(swap_file)], check=True)
        subprocess.run(['sudo', 'swapon', str(swap_file)], check=True)
    after = memory()
    if after['SwapTotal'] is None or after['SwapTotal'] < TARGET_SWAP - GIB // 1024:
        raise RuntimeError('Swap activation did not provide the requested capacity')
    return {'memory_before': before, 'memory_after': after,
            'added_swap_bytes': additional, 'disk_free_before': disk_before,
            'disk_free_after': shutil.disk_usage(root).free,
            'minimum_build_disk_reserve_bytes': DISK_RESERVE}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-root', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    report = prepare(args.android_root)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
