#!/usr/bin/env python3
"""Restore Neo8 runtime configuration from the SHA-verified working ramdisk.

Only build-tree files are changed. This never mounts or edits phone partitions.
"""
import json
from pathlib import Path
import stat
import xml.etree.ElementTree as ET

MANIFEST_DIRS = (
    'system/etc/vintf', 'vendor/etc/vintf', 'vendor/odm/etc/vintf',
)
PROFILE_FILES = (
    'system/etc/cgroups.json',
    'system/etc/task_profiles.json',
    'system/etc/task_profiles/task_profiles_30.json',
)
FASTBOOT_NAME = 'android.hardware.fastboot-service.example.xml'


def is_manifest(path):
    return any(path == base + '/manifest.xml' or
               (path.startswith(base + '/manifest/') and path.endswith('.xml'))
               for base in MANIFEST_DIRS)


def restore_runtime_config(device, entries):
    """Curate active VINTF files; leave firmware, binaries and init scripts alone."""
    device = Path(device)
    prebuilt = device / 'prebuilt'
    fastboot = prebuilt / 'system/etc/vintf/manifest' / FASTBOOT_NAME
    fastboot_data = fastboot.read_bytes()
    fastboot_xml = ET.fromstring(fastboot_data)
    if (fastboot_xml.get('type') != 'device' or
            fastboot_xml.findtext('hal/name') != 'android.hardware.fastboot'):
        raise ValueError('Unexpected fastboot manifest; review the donor')

    restored, removed = [], []
    for base in MANIFEST_DIRS:
        for path in (prebuilt / base).rglob('*.xml'):
            relative = path.relative_to(prebuilt).as_posix()
            if is_manifest(relative) and relative not in entries:
                path.unlink()
                removed.append(relative)

    for relative, entry in entries.items():
        if not is_manifest(relative) and relative not in PROFILE_FILES:
            continue
        # AERA builds its own keystore2 service and matching framework fragment.
        if relative == 'system/etc/vintf/manifest/android.system.keystore2-service.xml':
            continue
        if not stat.S_ISREG(entry['mode']):
            raise ValueError('Expected regular reference configuration: ' + relative)
        path = prebuilt / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(entry['data'])
        path.chmod(entry['mode'] & 0o777)
        restored.append(relative)

    # Keep AERA's fastboot HAL declaration, but in the device namespace.
    target = prebuilt / 'vendor/etc/vintf/manifest' / FASTBOOT_NAME
    target.write_bytes(fastboot_data)
    files = {p.relative_to(prebuilt).as_posix(): p.read_bytes()
             for p in prebuilt.rglob('*') if p.is_file() and not p.is_symlink()
             and (is_manifest(p.relative_to(prebuilt).as_posix()) or
                  p.relative_to(prebuilt).as_posix() in PROFILE_FILES)}
    validate_runtime_files(files)
    return {'removed_extra_manifests': sorted(removed),
            'restored_configuration': sorted(restored),
            'relocated_fastboot_manifest': target.relative_to(prebuilt).as_posix()}


def validate_runtime_files(files):
    """Reject the actual runtime failures seen in the first AERA phone test."""
    for path in PROFILE_FILES:
        if path not in files:
            raise ValueError('Missing runtime configuration: ' + path)
        json.loads(files[path])
    profiles = json.loads(files['system/etc/task_profiles.json'])
    names = {p['Name'] for category in ('Profiles', 'AggregateProfiles')
             for p in profiles.get(category, [])}
    if 'SCHED_SP_BACKGROUND' not in names:
        raise ValueError('Missing logd scheduling profile')

    seen = {}
    for path, data in sorted(files.items()):
        if not is_manifest(path):
            continue
        root = ET.fromstring(data)
        expected = 'framework' if path.startswith('system/') else 'device'
        if root.tag != 'manifest' or root.get('type') != expected:
            raise ValueError('Wrong VINTF namespace: ' + path)
        for hal in root.findall('hal'):
            # Check fqname and legacy interface notation. Treat different
            # versions of the same instance conservatively as a conflict.
            instances = [fq.text for fq in hal.findall('fqname')]
            instances += [interface.findtext('name') + '/' + instance.text
                          for interface in hal.findall('interface')
                          for instance in interface.findall('instance')]
            for instance in instances:
                key = (expected, hal.get('format', 'hidl'), hal.findtext('name'), instance)
                if key in seen:
                    raise ValueError(f'Duplicate HAL {key}: {seen[key]} and {path}')
                seen[key] = path
    for name, instance in (
            ('android.hardware.gatekeeper', 'IGatekeeper/default'),
            ('android.hardware.security.keymint', 'IKeyMintDevice/default')):
        if ('device', 'aidl', name, instance) not in seen:
            raise ValueError('Missing required crypto HAL: ' + name)
    return {'manifest_count': sum(is_manifest(p) for p in files),
            'hal_instance_count': len(seen), 'profiles_checked': list(PROFILE_FILES)}
