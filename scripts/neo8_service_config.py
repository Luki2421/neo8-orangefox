#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Stage recovery service configuration without changing firmware or crypto keys."""
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET

REQUIRED_PROFILES = {'SCHED_SP_BACKGROUND', 'BlkIOBackground', 'NormalIoPriority'}

def profile_names(config):
    return {p['Name'] for group in ('Profiles', 'AggregateProfiles') for p in config.get(group, [])}

def configure_service_files(device, android):
    source = android / 'system/core/libprocessgroup/profiles/task_profiles.json'
    config = json.loads(source.read_text())
    names = profile_names(config)
    if not {'SCHED_SP_BACKGROUND', 'LowIoPriority', 'NormalIoPriority'} <= names:
        raise ValueError('Unexpected source task profiles')
    # AOSP logd still requests this name; the pinned OrangeFox core uses
    # LowIoPriority for the same blkio/background controller path.
    if 'BlkIOBackground' not in names:
        config.setdefault('AggregateProfiles', []).append(
            {'Name': 'BlkIOBackground', 'Profiles': ['LowIoPriority']})
    destination = device / 'recovery/root/system/etc/task_profiles.json'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(config, indent=2) + '\n')

    rc = device / 'recovery/root/init.recovery.qcom.rc'
    text = rc.read_text()
    marker = 'on early-init\n'
    if marker not in text:
        raise ValueError('Missing device early-init trigger')
    # Recovery mounts the controllers, but does not create these subgroups.
    # Prepare them before logd's on-init start; no userdata access is involved.
    text = text.replace(marker, marker +
                        '    mkdir /dev/cpuctl/background 0755 root root\n'
                        '    mkdir /dev/blkio/background 0755 root root\n', 1)
    rc.write_text(text)

    removed = []
    for name in ('android.hardware.health-service.qti.xml', 'boot-service.qti.xml',
                 'android.hardware.fastboot-service.example.xml'):
        fragment = device / 'prebuilt/system/etc/vintf/manifest' / name
        if ET.parse(fragment).getroot().get('type') != 'device':
            raise ValueError('Unexpected manifest type: ' + name)
        vendor = device / 'prebuilt/vendor/etc/vintf/manifest' / name
        if vendor.exists():
            # Preserve the existing vendor declaration/version; do not create
            # conflicting duplicate HAL instances from the stock system copy.
            if ET.parse(vendor).getroot().get('type') != 'device':
                raise ValueError('Unexpected vendor manifest type: ' + name)
        else:
            vendor.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(fragment, vendor)
        fragment.unlink()
        removed.append(name)
    return {'task_profiles_source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'task_profiles_installed': 'system/etc/task_profiles.json',
            'blkio_background_profile_present': True,
            'device_fragments_removed_from_framework': removed}
