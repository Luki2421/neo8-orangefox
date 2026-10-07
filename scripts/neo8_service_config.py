#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Stage recovery service configuration without changing firmware or crypto keys."""
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET

REQUIRED_PROFILES = {'SCHED_SP_BACKGROUND', 'BlkIOBackground', 'NormalIoPriority'}
TA_RECOVERY_PATH = '/system/etc/firmware/neo8-ta'

# These donor blobs overwrite the recovery variants built for fastbootd/vold.
# Hashes are from the pinned device tree and confirmed in OrangeFox CI build 26.
DONOR_PARTITION_LIBRARIES = {
    'liblp.so': '8f7947f66a75d836787ab495e17b0aef284e67bb8ed507db5f304ae90c093d25',
    'libfs_mgr.so': '42c4003a4fdf10053f3635e6accdf816c141e4affc54c80e171a5b2589a595f2',
}

def use_source_partition_libraries(device):
    """Remove only verified donor overrides in the disposable build tree.

    fastbootd is recovery:true and depends on both modules, so Soong installs
    their matching recovery variants. Do not substitute a platform/vendor blob.
    """
    paths = []
    for name, expected in DONOR_PARTITION_LIBRARIES.items():
        path = device / 'prebuilt/system/lib64' / name
        if path.is_symlink() or not path.is_file():
            raise ValueError('Missing regular donor partition library: ' + name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Unexpected donor partition library: ' + name)
        paths.append(path)
    # Validate the complete set before removing any overrides.
    for path in paths:
        path.unlink()
    return {'removed_prebuilt_overrides': dict(DONOR_PARTITION_LIBRARIES),
            'provider': 'system/core recovery variants built with fastbootd',
            'phone_flash_and_decryption_verified': False}

def profile_names(config):
    return {p['Name'] for group in ('Profiles', 'AggregateProfiles') for p in config.get(group, [])}

def preserve_ssg_ta_files(device):
    """Keep the donor's signed TA files accessible when stock vendor is mounted."""
    config_path = device / 'prebuilt/vendor/etc/ssg/ta_config.json'
    original = config_path.read_text()
    marker = '  "ta_paths": [\n'
    if original.count(marker) != 1 or TA_RECOVERY_PATH in original:
        raise ValueError('Unexpected pinned SSG TA search paths')
    source = device / 'prebuilt/vendor/firmware_mnt/image'
    destination = device / 'recovery/root' / TA_RECOVERY_PATH.lstrip('/')
    # Only the existing UUID-named split TA packages, not peripheral firmware.
    pattern = r'[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}\.(?:mdt|b[0-9]{2})'
    files = sorted(p for p in source.iterdir() if re.fullmatch(pattern, p.name))
    stems = {p.stem for p in files}
    if len(stems) != 4:
        raise ValueError('Unexpected pinned SSG TA package count')
    for stem in stems:
        expected = {stem + '.mdt'} | {stem + '.b' + str(i).zfill(2) for i in range(9)}
        if {p.name for p in files if p.stem == stem} != expected:
            raise ValueError('Incomplete pinned SSG TA package: ' + stem)
    if any(p.is_symlink() or not p.is_file() for p in files):
        raise ValueError('SSG TA source must be a regular file')
    if destination.exists():
        raise ValueError('Refusing to overwrite existing recovery TA directory')
    hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    destination.mkdir(parents=True)
    for path in files:
        shutil.copyfile(path, destination / path.name)
        (destination / path.name).chmod(0o644)
    # ssgtzd starts from the ramdisk and reads this configuration before the
    # stock /vendor mount. Its later TA opens must use the persistent path.
    config_path.write_text(original.replace(marker, marker +
                           '    { "path": "' + TA_RECOVERY_PATH + '"},\n', 1))
    return {'recovery_search_path': TA_RECOVERY_PATH, 'files_sha256': hashes,
            'copied_bytes': sum(p.stat().st_size for p in files),
            'source_files_modified': False}

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
    rc_path = path.parent / 'android.hardware.fastboot-service.example_recovery.rc'
    rc_source = rc_path.read_text()
    expected_rc = ('service vendor.fastboot-default /system/bin/hw/android.hardware.fastboot-service.example_recovery\n'
                   '    class hal\n'
                   '    seclabel u:r:hal_fastboot_default:s0\n'
                   '    user system\n'
                   '    group system\n'
                   '    interface aidl android.hardware.fastboot.IFastboot/default\n')
    if rc_source.rstrip() != expected_rc.rstrip():
        raise ValueError('Unexpected recovery fastboot init service')
    replacement = original.replace(
        '    vintf_fragments: ["android.hardware.fastboot-service.example.xml"],',
        '    // Neo8 installs the device VINTF fragment in vendor, not system.')
    path.write_text(source.replace(original, replacement, 1))
    # Neo8's recovery service manager runs in the recovery domain. Use the
    # same explicit domain as its other ramdisk HALs for this recovery-only HAL.
    rc_path.write_text(rc_source.replace('seclabel u:r:hal_fastboot_default:s0',
                                        'seclabel u:r:recovery:s0', 1))
    return {'soong_source_sha256': hashlib.sha256(source.encode()).hexdigest(),
            'init_source_sha256': hashlib.sha256(rc_source.encode()).hexdigest(),
            'recovery_hal_domain': 'u:r:recovery:s0',
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
