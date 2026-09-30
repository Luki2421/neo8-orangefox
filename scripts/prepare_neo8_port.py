#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Apply pinned source changes. Does not build, package, decrypt or flash recovery."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]

def run(*args, cwd):
    return subprocess.run(args, cwd=cwd, check=True, text=True, capture_output=True).stdout.strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    parser.add_argument('--vold-root', type=Path, required=True)
    parser.add_argument('--neo8-root', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    sources = json.loads((PROJECT / 'port-sources.json').read_text())
    roots = {'recovery': args.recovery_root.resolve(), 'vold': args.vold_root.resolve(),
             'neo8': args.neo8_root.resolve()}
    for name, root in roots.items():
        if run('git', 'rev-parse', 'HEAD', cwd=root) != sources[name]['commit']:
            raise RuntimeError(f'{name}: source commit does not match port-sources.json')
        if run('git', 'status', '--porcelain', '--untracked-files=no', cwd=root):
            raise RuntimeError(f'{name}: use a clean source checkout')
    copies = []
    for relative, expected in sources['neo8']['files'].items():
        path = roots['neo8'] / relative
        content = path.read_bytes()
        if hashlib.sha256(content).hexdigest() != expected:
            raise RuntimeError(f'Upstream content changed: {relative}')
        target = roots['vold'] / path.name
        if target.is_symlink() or not target.is_file():
            raise RuntimeError(f'Unexpected vold destination: {path.name}')
        copies.append((target, content))
    patches = [(roots['recovery'], PROJECT / 'patches/fox16/neo8-core.patch'),
               (roots['vold'], PROJECT / 'patches/fox16/neo8-keystore-info.patch')]
    # Verify every patch and source file before the first mutation.
    for root, patch in patches:
        run('git', 'apply', '--check', str(patch), cwd=root)
    guard = PROJECT / 'patches/fox16/neo8-keystore-stop.patch'
    decrypt = next(content for target, content in copies if target.name == 'Decrypt.cpp')
    with tempfile.TemporaryDirectory(prefix='neo8-guard-check-', dir='/tmp') as directory:
        temporary = Path(directory)
        (temporary / 'Decrypt.cpp').write_bytes(decrypt)
        run('git', 'apply', '--check', str(guard), cwd=temporary)
    for root, patch in patches:
        run('git', 'apply', str(patch), cwd=root)
    for target, content in copies:
        target.write_bytes(content)
    # This patch is based on the pinned Neo8 Decrypt.cpp copied above.
    run('git', 'apply', str(guard), cwd=roots['vold'])
    for name in ('recovery', 'vold'):
        run('git', 'diff', '--check', cwd=roots[name])
    report = {'sources': sources, 'source_changes_applied': True,
              'compilation_attempted': False, 'recovery_image_created': False,
              'device_tree_integrated': False, 'decryption_verified_on_phone': False}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('Source changes applied. Device integration and Android compilation are still required.')

if __name__ == '__main__':
    main()
