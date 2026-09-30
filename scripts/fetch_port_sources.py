#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fetch pinned public source code; exclude Neo8 firmware prebuilts."""
import argparse
import json
from pathlib import Path
import subprocess

PROJECT = Path(__file__).resolve().parents[1]

def git(root, *args):
    subprocess.run(['git', '-C', str(root), *args], check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sources = json.loads((PROJECT / 'port-sources.json').read_text())
    for name in ('recovery', 'vold', 'neo8'):
        source = sources[name]
        root = output / name
        root.mkdir()
        git(root, 'init', '--quiet')
        git(root, 'remote', 'add', 'origin', source['url'])
        git(root, 'fetch', '--depth=1', '--no-tags', '--filter=blob:none', 'origin', source['commit'])
        if name == 'neo8':
            git(root, 'sparse-checkout', 'set', '--no-cone',
                *('/' + path for path in source['files']))
        git(root, 'checkout', '--detach', '--quiet', 'FETCH_HEAD')

if __name__ == '__main__':
    main()
