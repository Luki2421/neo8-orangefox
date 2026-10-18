#!/usr/bin/env python3
"""Neo8 USB ownership and mount-independent StrongBox runtime files."""
from pathlib import Path
import re
import stat

from inspect_recovery import elf_dependencies, resolve

STRONGBOX_SOURCE = 'vendor/odm/bin/hw/android.hardware.security.keymint-service-strongbox-tms'
STRONGBOX_BINARY = 'system/bin/neo8-strongbox-service'
STRONGBOX_LAUNCHER = 'system/bin/neo8-strongbox-launcher.sh'
STRONGBOX_RC = 'vendor/odm/etc/init/android.hardware.security.keymint-service-strongbox-tms-qcom.rc'
PRIVATE_LIBS = 'system/lib64/neo8_strongbox'
REFERENCE_LIB_DIRS = ('system/lib64', 'vendor/lib64/hw', 'vendor/lib64', 'vendor/odm/lib64')


def init_sections(source):
    return re.split(r'(?=^(?:on |service |import ))', source, flags=re.M)


def fix_generic_usb(source):
    """Keep gadget creation/services; delegate mode switching and FFS to device rc."""
    result, removed = [], 0
    for block in init_sections(source):
        header = block.splitlines()[0] if block else ''
        if header.startswith('on ') and 'property:sys.usb.config=' in header and 'property:sys.usb.configfs=1' in header:
            removed += 1
            continue
        result.append(block)
    if removed != 4:
        raise ValueError(f'Expected 4 generic configfs mode actions, found {removed}')
    source = ''.join(result)
    lines = source.splitlines(keepends=True)
    mounts = [line for line in lines if line.strip().startswith('mount functionfs ')]
    if len(mounts) != 2:
        raise ValueError('Expected generic adb/fastboot FunctionFS mounts')
    source = ''.join(line for line in lines if line not in mounts)
    return source


def device_usb(reference):
    source = reference['init.recovery.usb.rc']['data'].decode()
    result = []
    for block in init_sections(source):
        lines = block.splitlines()
        if not lines or not lines[0].startswith('on ') or 'property:sys.usb.config=' not in lines[0]:
            result.append(block)
            continue
        # Always detach before changing descriptors or function links, including
        # direct mode changes which did not pass through sys.usb.config=none.
        if any('/config/usb_gadget/g1/' in line for line in lines[1:]):
            if re.search(r'property:sys.usb.config=mtp(?:,adb)?(?: |$)', lines[0]):
                # AERA's MtpDescriptors.cpp publishes this only after both
                # FunctionFS descriptors and strings have been written.
                lines[0] += ' && property:sys.usb.ffs.mtp.ready=1'
            prefix = ['    write /config/usb_gadget/g1/UDC "none"']
            if 'property:sys.usb.config=none ' in lines[0]:
                prefix += ['    stop adbd', '    stop fastbootd']
            else:
                prefix += ['    rm /config/usb_gadget/g1/configs/b.1/f1',
                           '    rm /config/usb_gadget/g1/configs/b.1/f2']
                lines = [lines[0]] + [line for line in lines[1:] if line.strip() not in (
                    'rm /config/usb_gadget/g1/configs/b.1/f1',
                    'rm /config/usb_gadget/g1/configs/b.1/f2')]
            lines[1:1] = prefix
        result.append('\n'.join(lines) + '\n')
    return ''.join(result).encode()


def strongbox_files(reference):
    """Pin the release executable and its non-system dependency closure.

    Generic AERA system libraries retain precedence, particularly libbinder.
    Stock /odm mounts cannot substitute the executable or vendor dependencies.
    """
    files = {STRONGBOX_BINARY: reference[STRONGBOX_SOURCE]['data']}
    pending = [STRONGBOX_SOURCE]
    seen = set()
    while pending:
        path = pending.pop()
        if path in seen:
            continue
        seen.add(path)
        for name in elf_dependencies(reference[path]['data']):
            for directory in REFERENCE_LIB_DIRS:
                resolved, entry = resolve(reference, directory + '/' + name)
                if entry and stat.S_ISREG(entry['mode']):
                    break
            else:
                raise ValueError('Missing reference StrongBox dependency: ' + name)
            if directory != 'system/lib64':
                files[PRIVATE_LIBS + '/' + name] = entry['data']
            pending.append(resolved)
    files[STRONGBOX_LAUNCHER] = b'''#!/system/bin/sh
# Keep this service independent of Android's /odm mount and linker namespace.
umask 077
exec >>/tmp/neo8-strongbox.log 2>&1
echo "Starting reference StrongBox from recovery with private vendor dependencies"
export LD_LIBRARY_PATH=/system/lib64:/system/lib64/neo8_strongbox
exec /system/bin/neo8-strongbox-service
'''
    source = reference[STRONGBOX_RC]['data'].decode().replace('\r\n', '\n')
    old = 'service vendor.keymint-strongbox /odm/bin/hw/android.hardware.security.keymint-service-strongbox-tms'
    if source.count(old) != 1:
        raise ValueError('Unexpected reference StrongBox service')
    files[STRONGBOX_RC] = source.replace(old,
        'service vendor.keymint-strongbox /system/bin/sh /' + STRONGBOX_LAUNCHER).encode()
    return files


def configure_runtime_fixes(device, recovery, reference):
    generic = Path(recovery) / 'etc/init.rc'
    generic.write_text(fix_generic_usb(generic.read_text()))
    usb = Path(device) / 'recovery/root/init.recovery.usb.rc'
    usb.write_bytes(device_usb(reference))
    files = strongbox_files(reference)
    for path, data in files.items():
        dest = Path(device) / 'prebuilt' / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        dest.chmod(0o755 if path.startswith('system/bin/') else 0o644)
    return {'usb_mode_owner': 'init.recovery.usb.rc', 'strongbox_files': sorted(files)}


def validate_runtime_fixes(entries, reference):
    generic = entries['system/etc/init/hw/init.rc']['data'].decode()
    if re.search(r'^\s*write /config/usb_gadget/g1/UDC', generic, re.M):
        raise ValueError('Generic init still owns USB mode switching')
    if re.search(r'^\s*mount functionfs ', generic, re.M):
        raise ValueError('Duplicate generic FunctionFS mount')
    if entries['init.recovery.usb.rc']['data'] != device_usb(reference):
        raise ValueError('Wrong device USB init')
    files = strongbox_files(reference)
    for path, data in files.items():
        actual = entries.get(path)
        if not actual or not stat.S_ISREG(actual['mode']) or actual['data'] != data:
            raise ValueError('StrongBox runtime mismatch: ' + path)
        if path.startswith('system/bin/') and not actual['mode'] & 0o111:
            raise ValueError('Non-executable StrongBox runtime: ' + path)
    # Resolve the complete actual packed-image closure, using launcher order.
    pending, seen = [STRONGBOX_BINARY], set()
    while pending:
        path = pending.pop()
        if path in seen:
            continue
        seen.add(path)
        for name in elf_dependencies(entries[path]['data']):
            for directory in ('system/lib64', PRIVATE_LIBS):
                resolved, entry = resolve(entries, directory + '/' + name)
                if entry and stat.S_ISREG(entry['mode']):
                    pending.append(resolved)
                    break
            else:
                raise ValueError('Missing packed StrongBox dependency: ' + name)
    return {'usb_single_owner': True, 'strongbox_dependency_files_checked': len(seen)}
