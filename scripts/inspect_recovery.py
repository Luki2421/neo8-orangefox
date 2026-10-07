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
import xml.etree.ElementTree as ET

from neo8_service_config import (REQUIRED_PROFILES, profile_names,
                                 check_device_aidl_manifests, TA_RECOVERY_PATH,
                                 DONOR_PARTITION_LIBRARIES)

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
    with tempfile.NamedTemporaryFile(prefix='neo8-inspect-') as file:
        file.write(content)
        file.flush()
        result = subprocess.run(['readelf', '-d', file.name], check=True, text=True,
                                capture_output=True, timeout=10)
    return re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', result.stdout)

def inspect_service_config(entries):
    # /etc is created as a symlink by recovery init on this device. Validate
    # its target in the archive as well as an existing archive link if present.
    profile_path = 'etc/task_profiles.json' if 'etc' in entries else 'system/etc/task_profiles.json'
    name, entry = resolve(entries, profile_path)
    if not entry or not stat.S_ISREG(entry['mode']):
        raise ValueError('Missing recovery task_profiles.json')
    config = json.loads(entry['data'])
    missing = REQUIRED_PROFILES - profile_names(config)
    if missing:
        raise ValueError('Missing task profiles: ' + ', '.join(sorted(missing)))
    checked = []
    for path, entry in entries.items():
        if not stat.S_ISREG(entry['mode']) or not path.endswith('.xml'):
            continue
        if any(path.startswith(part + '/etc/vintf/manifest/') or
               path == part + '/etc/vintf/manifest.xml'
               for part in ('system', 'system_ext', 'product')):
            manifest = ET.fromstring(entry['data'])
            if manifest.tag == 'manifest' and manifest.get('type') != 'framework':
                raise ValueError('Device manifest in framework directory: ' + path)
            checked.append(path)
    device_manifests = []
    # /odm can be an init-created symlink to /vendor/odm. Resolve/deduplicate
    # archive paths so the same file is never counted twice.
    visited = set()
    for partition in ('vendor', 'odm'):
        directory, _ = resolve(entries, partition + '/etc/vintf')
        if partition == 'odm' and not any(p.startswith(directory + '/') for p in entries):
            directory, _ = resolve(entries, 'vendor/odm/etc/vintf')
        for path, entry in sorted(entries.items()):
            if path in visited or not stat.S_ISREG(entry['mode']):
                continue
            if path == directory + '/manifest.xml' or (
                    path.startswith(directory + '/manifest/') and path.endswith('.xml')):
                visited.add(path)
                device_manifests.append((path, ET.fromstring(entry['data'])))
    instances = check_device_aidl_manifests(device_manifests)
    return {'task_profiles_path': name, 'required_task_profiles_present': True,
            'framework_manifests_checked': checked,
            'device_manifests_checked': sorted(visited),
            'unique_device_aidl_instances': len(instances)}

def inspect_fastboot_service(entries):
    """Check the installed rc, including duplicate definitions across files."""
    services = []
    for path, entry in sorted(entries.items()):
        if not path.endswith('.rc') or not stat.S_ISREG(entry['mode']):
            continue
        current = None
        for line in entry['data'].decode('utf-8').splitlines():
            words = line.split('#', 1)[0].split()
            if not words:
                continue
            if not line[0].isspace():
                current = None
                if words[:2] == ['service', 'vendor.fastboot-default']:
                    current = [words]
                    services.append((path, current))
            elif current is not None:
                current.append(words)
    if len(services) != 1:
        raise ValueError('Expected exactly one recovery fastboot HAL init service')
    path, lines = services[0]
    expected = [
        ['service', 'vendor.fastboot-default', '/system/bin/hw/android.hardware.fastboot-service.example_recovery'],
        ['class', 'hal'], ['seclabel', 'u:r:recovery:s0'],
        ['user', 'system'], ['group', 'system'],
        ['interface', 'aidl', 'android.hardware.fastboot.IFastboot/default'],
    ]
    if lines != expected:
        raise ValueError('Unexpected packaged recovery fastboot HAL init service: ' + path)
    return {'init_path': path, 'domain': 'u:r:recovery:s0', 'unique_service': True}

def inspect_ssg_ta_files(entries):
    """Verify TA copies and their search path in the actual packaged ramdisk."""
    _, config = resolve(entries, 'vendor/etc/ssg/ta_config.json')
    if not config or not stat.S_ISREG(config['mode']):
        raise ValueError('Missing SSG TA configuration')
    # The donor configuration allows comments and trailing commas.
    first_path = r'"ta_paths"\s*:\s*\[\s*\{\s*"path"\s*:\s*"' + re.escape(TA_RECOVERY_PATH) + '"'
    if not re.search(first_path, config['data'].decode('utf-8')):
        raise ValueError('Recovery TA path must be first in SSG configuration')
    prefix = 'vendor/firmware_mnt/image/'
    pattern = r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}\.(?:mdt|b[0-9]{2})'
    names = sorted(path[len(prefix):] for path in entries
                   if path.startswith(prefix) and re.fullmatch(pattern, path[len(prefix):]))
    stems = {name.rsplit('.', 1)[0] for name in names}
    expected = {stem + '.' + suffix for stem in stems
                for suffix in ['mdt'] + ['b' + str(i).zfill(2) for i in range(9)]}
    if len(stems) != 4 or set(names) != expected:
        raise ValueError('Incomplete packaged SSG TA sources')
    hashes = {}
    for name in names:
        _, source = resolve(entries, prefix + name)
        # Do not accept a link that could lead back into the hidden vendor tree.
        target = entries.get(TA_RECOVERY_PATH.lstrip('/') + '/' + name)
        if not source or not stat.S_ISREG(source['mode']) or not target or not stat.S_ISREG(target['mode']):
            raise ValueError('Missing regular recovery TA copy: ' + name)
        if target['mode'] & 0o444 != 0o444 or target['data'] != source['data']:
            raise ValueError('Unreadable or changed recovery TA copy: ' + name)
        hashes[name] = hashlib.sha256(target['data']).hexdigest()
    return {'recovery_search_path': TA_RECOVERY_PATH, 'files_sha256': hashes}

def inspect_partition_tools(entries):
    """Require the ELF dependency closure of the partition tools in the ramdisk.

    This checks packaged files, not ABI/symbol compatibility or runtime Binder
    registration. Do not resolve against stock /vendor or a host filesystem.
    """
    roots = ('system/bin/lpdump', 'system/bin/lpdumpd', 'system/bin/fastbootd')
    directories = ('system/lib64', 'system/lib64/bootstrap', 'lib64')
    checked = {}
    for library, rejected_hash in DONOR_PARTITION_LIBRARIES.items():
        _, entry = resolve(entries, 'system/lib64/' + library)
        if entry and hashlib.sha256(entry['data']).hexdigest() == rejected_hash:
            raise ValueError('Donor partition library overrides source build: ' + library)

    def visit(path):
        name, entry = resolve(entries, path)
        if name in checked:
            return
        if not entry or not stat.S_ISREG(entry['mode']):
            raise ValueError('Missing partition tool or library: ' + path)
        needed = elf_dependencies(entry['data'])
        checked[name] = needed
        for library in needed:
            for directory in directories:
                target, dependency = resolve(entries, directory + '/' + library)
                if dependency and stat.S_ISREG(dependency['mode']):
                    visit(target)
                    break
            else:
                raise ValueError('Missing ramdisk dependency: ' + name + ' needs ' + library)

    for path in roots:
        _, entry = resolve(entries, path)
        if not entry or not entry['mode'] & 0o111:
            raise ValueError('Missing executable partition tool: ' + path)
        visit(path)
    return {'executables': list(roots), 'elf_dependencies': checked,
            'runtime_verified': False}


def inspect(path):
    image, entries = read_recovery(path)
    if len(image) > PARTITION_SIZE:
        raise ValueError('Recovery exceeds the Neo8 partition size')
    name, executable = resolve(entries, 'system/bin/recovery')
    if not executable or not stat.S_ISREG(executable['mode']) or not executable['mode'] & 0o111:
        raise ValueError('Missing executable system/bin/recovery')
    service_config = inspect_service_config(entries)
    fastboot_service = inspect_fastboot_service(entries)
    ssg_ta_files = inspect_ssg_ta_files(entries)
    partition_tools = inspect_partition_tools(entries)
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
            'service_configuration': service_config,
            'fastboot_hal_service': fastboot_service,
            'ssg_ta_files': ssg_ta_files,
            'partition_tools': partition_tools,
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
