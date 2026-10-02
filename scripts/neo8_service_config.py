#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Stage recovery service configuration without changing firmware or crypto keys."""
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET

REQUIRED_PROFILES = {'SCHED_SP_BACKGROUND', 'BlkIOBackground', 'NormalIoPriority'}

def profile_names(config):
    return {p['Name'] for group in ('Profiles', 'AggregateProfiles') for p in config.get(group, [])}

def configure_qseecomd(device):
    """Run the pinned listener daemon in the existing recovery domain.

    init requires a valid domain transition even in permissive mode. Match
    the explicit recovery label used by the other device crypto services.
    Phone validation is still needed to confirm listener registration.
    """
    path = device / 'prebuilt/vendor/etc/init/qseecomd.rc'
    source = path.read_text()
    original = ('service vendor.qseecomd /vendor/bin/qseecomd\n'
                '    socket notify-topology stream 660 system drmrpc\n'
                '    class core\n'
                '    user root\n'
                '    group root drmrpc\n')
    if source.count(original) != 1 or 'seclabel' in source:
        raise ValueError('Unexpected pinned qseecomd init configuration')
    path.write_text(source.replace(original, original + '    seclabel u:r:recovery:s0\n', 1))
    return {'source_sha256': hashlib.sha256(source.encode()).hexdigest(),
            'service': 'vendor.qseecomd', 'seclabel': 'u:r:recovery:s0',
            'listener_registration_verified_on_phone': False}

def aidl_instances(hal):
    """Normalize the two instance syntaxes present in the pinned device tree."""
    instances = [(fq.text or '').strip() for fq in hal.findall('fqname')]
    for interface in hal.findall('interface'):
        name = interface.findtext('name', '').strip()
        instances.extend(name + '/' + (item.text or '').strip()
                         for item in interface.findall('instance'))
    if not instances or any(not re.fullmatch(r'\w+/[\w./-]+', value) for value in instances):
        raise ValueError('Unsupported AIDL instance declaration')
    return instances

def check_device_aidl_manifests(manifests):
    """Check AIDL instance collisions in the Neo8 vendor + ODM manifest set.

    This is not a replacement for libvintf. Overrides/SKU selection require
    explicit review. AIDL versions share one service identity in libvintf.
    """
    seen = {}
    for path, root in manifests:
        if root.tag != 'manifest' or root.get('type') != 'device':
            raise ValueError('Unexpected device manifest: ' + path)
        for hal in root.findall('hal'):
            if hal.get('format') != 'aidl':
                continue
            if hal.get('override', 'false') != 'false':
                raise ValueError('AIDL override needs explicit review: ' + path)
            package = hal.findtext('name', '').strip()
            for instance in aidl_instances(hal):
                key = package + '.' + instance
                if key in seen:
                    raise ValueError('Conflicting AIDL instance ' + key + ': ' + seen[key] + ' vs ' + path)
                seen[key] = path
    return seen

def remove_duplicate_device_fragments(device):
    # Remove only the two verified redundant fragments. Never discard unique
    # interfaces, change service versions, or alter manifest target/sepolicy.
    base = device / 'prebuilt/vendor'
    pairs = (
        ('etc/vintf/manifest/android.hardware.gatekeeper-service-qti.xml',
         'etc/vintf/manifest.xml', 'android.hardware.gatekeeper', 'IGatekeeper/default'),
        ('etc/vintf/manifest/se_omapi.xml',
         'odm/etc/vintf/manifest/secure_element_omapi_service.xml',
         'android.se.omapi', 'ISecureElementService/default'),
    )
    def matches(hal, package, instance):
        return (hal.attrib == {'format': 'aidl'} and
                all(child.tag in ('name', 'version', 'fqname', 'interface') for child in hal) and
                hal.findtext('name') == package and
                [v.text for v in hal.findall('version')] in ([], ['1']) and
                aidl_instances(hal) == [instance])
    pending = []
    for duplicate, retained, package, instance in pairs:
        fragment = ET.parse(base / duplicate).getroot()
        keeper = ET.parse(base / retained).getroot()
        if (fragment.get('type') != 'device' or keeper.get('type') != 'device' or
                len(fragment) != 1 or fragment[0].tag != 'hal' or
                not matches(fragment[0], package, instance) or
                not any(matches(hal, package, instance) for hal in keeper.findall('hal'))):
            raise ValueError('Unexpected duplicate HAL declarations: ' + duplicate)
        pending.append(base / duplicate)
    # Validate the resulting combined manifest before changing any source file.
    manifests = []
    for directory in (base / 'etc/vintf', base / 'odm/etc/vintf'):
        paths = [directory / 'manifest.xml'] + sorted((directory / 'manifest').glob('*.xml'))
        for path in paths:
            if path.is_file() and path not in pending:
                manifests.append((str(path.relative_to(base)), ET.parse(path).getroot()))
    instances = check_device_aidl_manifests(manifests)
    for path in pending:
        path.unlink()
    return {'removed': [str(path.relative_to(base)) for path in pending],
            'unique_aidl_instances': len(instances)}

def configure_fastboot_manifest(device, android):
    # This recovery-only Soong module regenerates the system-side fragment
    # even after the donor copy has been removed. Keep its executable and rc;
    # the device declaration is installed separately under vendor below.
    vendor = device / 'prebuilt/vendor/etc/vintf/manifest/android.hardware.fastboot-service.example.xml'
    manifest = ET.parse(vendor).getroot()
    if manifest.get('type') != 'device' or not any(
            hal.findtext('name') == 'android.hardware.fastboot' and
            hal.findtext('fqname') == 'IFastboot/default' for hal in manifest.findall('hal')):
        raise ValueError('Missing vendor fastboot HAL declaration')
    path = android / 'hardware/interfaces/fastboot/aidl/default/Android.bp'
    source = path.read_text()
    original = '\n'.join([
        '    name: "android.hardware.fastboot-service.example_recovery",',
        '    init_rc: ["android.hardware.fastboot-service.example_recovery.rc"],',
        '    vintf_fragments: ["android.hardware.fastboot-service.example.xml"],',
        '    recovery: true,',
    ])
    if source.count(original) != 1:
        raise ValueError('Unexpected recovery fastboot Soong module')
    replacement = original.replace(
        '    vintf_fragments: ["android.hardware.fastboot-service.example.xml"],',
        '    // Neo8 installs the device VINTF fragment in vendor, not system.')
    path.write_text(source.replace(original, replacement, 1))
    return {'soong_source_sha256': hashlib.sha256(source.encode()).hexdigest(),
            'framework_fragment_generation_disabled': True,
            'vendor_fragment': str(vendor.relative_to(device))}

def configure_omapi_manifest(device, android):
    # recovery's required modules install a second copy even after the donor
    # vendor fragment is removed. Keep the binary/rc and the ODM declaration.
    odm = device / 'prebuilt/vendor/odm/etc/vintf/manifest/secure_element_omapi_service.xml'
    instances = check_device_aidl_manifests([(str(odm), ET.parse(odm).getroot())])
    if set(instances) != {'android.se.omapi.ISecureElementService/default'}:
        raise ValueError('Missing ODM OMAPI declaration')
    path = android / 'bootable/recovery/Android.mk'
    source = path.read_text()
    original = ('        TWRP_REQUIRED_MODULES += \\\n'
                '            se_omapi \\\n'
                '            se_omapi.rc \\\n'
                '            se_omapi.xml\n')
    if source.count(original) != 1:
        raise ValueError('Unexpected recovery OMAPI required modules')
    replacement = ('        # Neo8 retains the single OMAPI declaration in ODM.\n'
                   '        TWRP_REQUIRED_MODULES += \\\n'
                   '            se_omapi \\\n'
                   '            se_omapi.rc\n')
    path.write_text(source.replace(original, replacement, 1))
    return {'make_source_sha256': hashlib.sha256(source.encode()).hexdigest(),
            'duplicate_vendor_fragment_installation_disabled': True,
            'retained_odm_fragment': str(odm.relative_to(device))}

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
            'device_fragments_removed_from_framework': removed,
            'device_manifest_duplicates': remove_duplicate_device_fragments(device)}
