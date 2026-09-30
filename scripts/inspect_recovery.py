#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Inventory a development recovery image; never claim a successful phone test."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import subprocess
import tempfile

from boot_ramdisk import read_recovery

PARTITION_SIZE = 104857600

def resolve(entries, path):
    """Resolve links inside the in-memory ramdisk, with a bounded link count."""
    pending = list(PurePosixPath('/' + path.lstrip('/')).parts[1:])
    resolved = []
    links = 0
    while pending:
        component = pending.pop(0)
        if component in ('', '.'):
            continue
        if component == '..':
            if not resolved:
                raise ValueError('Symlink escapes ramdisk root')
            resolved.pop()
            continue
        candidate = '/'.join(resolved + [component])
        entry = entries.get(candidate)
        if entry and stat.S_ISLNK(entry['mode']):
            links += 1
            if links > 40:
                raise ValueError('Too many ramdisk symlinks')
            target = entry['data'].decode('utf-8')
            if target.startswith('/'):
                resolved = []
            pending = list(PurePosixPath(target).parts) + pending
            if pending and pending[0] == '/':
                pending.pop(0)
        else:
            resolved.append(component)
    name = '/'.join(resolved)
    return name, entries.get(name)

def elf_dependencies(content):
    if len(content) < 64 or content[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', content, 18)[0] != 183:
        raise ValueError('Expected a little-endian ARM64 ELF')
    # readelf reads metadata only; the target executable is never run.
    with tempfile.NamedTemporaryFile(prefix='neo8-inspect-', dir='/tmp') as file:
        file.write(content)
        file.flush()
        result = subprocess.run(['readelf', '-d', file.name], check=True, text=True,
                                capture_output=True, timeout=10)
    return re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', result.stdout)

def inspect(path):
    image, entries = read_recovery(path)
    if len(image) > PARTITION_SIZE:
        raise ValueError('Recovery exceeds the Neo8 partition size')
    name, executable = resolve(entries, 'system/bin/recovery')
    if not executable or not stat.S_ISREG(executable['mode']) or not executable['mode'] & 0o111:
        raise ValueError('Missing executable system/bin/recovery')
    needed = elf_dependencies(executable['data'])
    candidates = ['system/lib64', 'system/lib64/bootstrap', 'lib64',
                  'vendor/lib64', 'vendor/odm/lib64']
    libraries = {}
    for library in needed:
        libraries[library] = None
        for directory in candidates:
            target, entry = resolve(entries, directory + '/' + library)
            if entry and stat.S_ISREG(entry['mode']):
                libraries[library] = target
                break
    prop_files = [name for name in entries if name in ('default.prop', 'prop.default',
                  'system/etc/prop.default', 'system/build.prop')]
    metadata_property = []
    for filename in prop_files:
        metadata_property += [line.strip() for line in entries[filename]['data'].decode('utf-8').splitlines()
                              if line.strip().startswith('ro.crypto.metadata_init_delete_all_keys.enabled=')]
    menu_files = [name for name, entry in entries.items() if name.startswith('twres/')
                  and name.endswith('.xml') and b'neo8preparedecrypt' in entry['data']]
    service_files = [name for name, entry in entries.items() if name.endswith('.rc')
                     and b'service vendor.touch-aidl-1 ' in entry['data']]
    _, odm = resolve(entries, 'odm')
    return {'image_sha256': hashlib.sha256(image).hexdigest(), 'image_bytes': len(image),
            'partition_bytes': PARTITION_SIZE, 'header_version': 4, 'kernel_included': False,
            'ramdisk_entries': len(entries), 'recovery_executable': name,
            'recovery_direct_libraries': libraries,
            'unresolved_direct_libraries': [key for key, value in libraries.items() if value is None],
            'metadata_key_deletion_property': metadata_property,
            'manual_decryption_menu_files': menu_files, 'touch_service_files': service_files,
            'odm_directory_resolves': bool(odm and stat.S_ISDIR(odm['mode'])),
            'avb_verification': 'Not checked by this script',
            'ready_for_phone_test': False, 'touch_verified_on_phone': False,
            'decryption_verified_on_phone': False,
            'scope': 'Static development-image inventory. Runtime and recursive ELF dependencies still need review.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.image)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
