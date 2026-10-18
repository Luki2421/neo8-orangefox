#!/usr/bin/env python3
"""USB init transition model and StrongBox packaging checks; no hardware emulation."""
import argparse
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'scripts'))
from boot_ramdisk import read_recovery
from secure_element_bundle import verified_reference
from runtime_fixes import (device_usb, init_sections, strongbox_files,
                           validate_runtime_fixes, STRONGBOX_LAUNCHER, PRIVATE_LIBS)


class UsbModel:
    def __init__(self, source):
        self.actions = []
        for block in init_sections(source):
            lines = block.splitlines()
            if lines and lines[0].startswith('on property:'):
                conditions = dict(re.findall(r'property:([^= ]+)=([^ ]+)', lines[0]))
                commands = [shlex.split(line) for line in lines[1:]
                            if line.strip() and not line.lstrip().startswith('#')]
                self.actions.append((conditions, commands))
        self.props = {'sys.usb.configfs': '1', 'sys.usb.controller': 'controller',
                      'sys.usb.ffs.ready': '0', 'sys.usb.ffs.mtp.ready': '0'}
        self.bound = False
        self.links = {}
        self.stopped = set()
        self.binds = 0

    def set(self, key, value):
        if self.props.get(key) == value:
            return
        self.props[key] = value
        # Select matching actions before executing commands, like a queued event.
        matched = [cmds for cond, cmds in self.actions if key in cond
                   and all(self.props.get(k) == v for k, v in cond.items())]
        for commands in matched:
            for args in commands:
                args = [re.sub(r'\$\{([^}]+)\}', lambda m: self.props.get(m[1], ''), a)
                        for a in args]
                op = args[0]
                if op == 'write' and args[1].endswith('/UDC'):
                    if args[2] == 'none':
                        self.bound = False
                    else:
                        assert not self.bound, 'UDC already bound'
                        assert self.links, 'empty USB configuration'
                        if 'ffs.mtp' in self.links.values():
                            assert self.props['sys.usb.ffs.mtp.ready'] == '1', 'MTP not ready'
                        if any(v in ('ffs.adb', 'ffs.fastboot') for v in self.links.values()):
                            assert self.props['sys.usb.ffs.ready'] == '1', 'ADB/fastboot not ready'
                        self.bound = True
                        self.binds += 1
                elif op in ('write', 'rm', 'symlink') and '/config/usb_gadget/' in args[1]:
                    assert not self.bound, 'mutating bound gadget'
                    if op == 'rm':
                        self.links.pop(args[1], None)
                    elif op == 'symlink':
                        assert args[2] not in self.links, 'function link already exists'
                        self.links[args[2]] = args[1].split('/')[-1]
                elif op == 'setprop':
                    self.props[args[1]] = args[2]
                elif op == 'stop':
                    self.stopped.add(args[1])


def check_usb(generic, device):
    source = generic + '\n' + device
    mounts = re.findall(r'^\s*mount functionfs (\S+)', source, re.M)
    assert sorted(mounts) == ['adb', 'fastboot', 'mtp'], mounts
    assert 'service adbd ' in generic and 'service fastbootd ' in generic
    assert 'mount configfs none /config' in generic
    cases = 0
    for order in (('sys.usb.ffs.ready', 'sys.usb.ffs.mtp.ready'),
                  ('sys.usb.ffs.mtp.ready', 'sys.usb.ffs.ready')):
        model = UsbModel(source)
        for mode, expected in (('adb', ['ffs.adb']), ('mtp,adb', ['ffs.adb', 'ffs.mtp']),
                               ('sideload', ['ffs.adb']), ('fastboot', ['ffs.fastboot']),
                               ('mtp', ['ffs.mtp']), ('adb', ['ffs.adb'])):
            model.set('sys.usb.config', 'none')
            assert not model.bound and not model.links
            assert {'adbd', 'fastbootd'} <= model.stopped
            assert model.props['sys.usb.ffs.ready'] == '0'
            assert model.props['sys.usb.ffs.mtp.ready'] == '0'
            model.set('sys.usb.config', mode)
            assert not model.bound, mode
            before = model.binds
            for prop in order:
                model.set(prop, '1')
                if mode == 'mtp,adb' and model.props[order[-1]] == '0':
                    assert not model.bound
            assert model.bound and model.binds == before + 1, mode
            assert sorted(model.links.values()) == expected, (mode, model.links)
            assert model.props['sys.usb.state'] == mode
            cases += 1
        # A direct transition from active ADB must also detach before changing links.
        model.set('sys.usb.config', 'mtp,adb')
        assert model.bound and sorted(model.links.values()) == ['ffs.adb', 'ffs.mtp']
    # Prove the model detects the old missing MTP readiness guard.
    bad = device.replace(' && property:sys.usb.ffs.mtp.ready=1', '')
    model = UsbModel(bad)
    try:
        model.set('sys.usb.config', 'mtp')
    except AssertionError as exc:
        assert str(exc) == 'MTP not ready'
    else:
        raise AssertionError('Missed premature MTP bind')
    print(f'PASS: {cases} USB mode transitions, both readiness orders and direct transitions')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--reference', required=True)
    ap.add_argument('--device', type=Path, required=True)
    ap.add_argument('--recovery-root', type=Path, required=True)
    args = ap.parse_args()
    reference = verified_reference(*read_recovery(args.reference))
    generic = (args.recovery_root / 'etc/init.rc').read_text()
    usb = (args.device / 'recovery/root/init.recovery.usb.rc').read_text()
    assert usb.encode() == device_usb(reference)
    check_usb(generic, usb)
    # Guard the producer side of the MTP ready handshake at the pinned revision.
    descriptors = (args.recovery_root / 'mtp/ffs/MtpDescriptors.cpp').read_text()
    ready = descriptors.index('property_set("sys.usb.ffs.mtp.ready", "1")')
    assert descriptors.index('write(fd, &mtp_strings') < ready
    assert descriptors.index('MTPE("Writing strings failed') < ready
    # Use reference system libraries only as a host fixture. CI separately checks
    # the closure against the actual compiled image's system libraries.
    entries = dict(reference)
    entries['system/etc/init/hw/init.rc'] = {'mode': 0o100644, 'data': generic.encode()}
    entries['init.recovery.usb.rc'] = {'mode': 0o100644, 'data': usb.encode()}
    files = strongbox_files(reference)
    for path, expected in files.items():
        actual = args.device / 'prebuilt' / path
        assert actual.read_bytes() == expected, path
        entries[path] = {'mode': actual.stat().st_mode, 'data': actual.read_bytes()}
    print(validate_runtime_fixes(entries, reference))
    # Every missing private dependency, executable or service must fail packaging.
    for path in files:
        broken = dict(entries)
        del broken[path]
        try:
            validate_runtime_fixes(broken, reference)
        except ValueError as exc:
            assert path in str(exc), str(exc)
        else:
            raise AssertionError('Accepted missing ' + path)
    broken = dict(entries)
    broken['system/etc/init/hw/init.rc'] = {'data': b'    write /config/usb_gadget/g1/UDC x\n'}
    try:
        validate_runtime_fixes(broken, reference)
    except ValueError as exc:
        assert 'Generic init' in str(exc)
    else:
        raise AssertionError('Accepted duplicate USB owner')
    broken = dict(entries)
    broken[STRONGBOX_LAUNCHER] = dict(entries[STRONGBOX_LAUNCHER], mode=0o100644)
    try:
        validate_runtime_fixes(broken, reference)
    except ValueError as exc:
        assert 'Non-executable' in str(exc)
    else:
        raise AssertionError('Accepted non-executable launcher')
    # Run the launcher with an executable stand-in, checking its child environment
    # and stderr capture without invoking any Android service on the host.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        service = root / 'service'
        service.write_text('#!/bin/sh\nprintf "%s\\n" "$LD_LIBRARY_PATH"\necho linker-test >&2\nexit 7\n')
        service.chmod(0o755)
        launcher = files[STRONGBOX_LAUNCHER].decode().replace('/system/bin/neo8-strongbox-service', str(service))
        launcher = launcher.replace('/tmp/neo8-strongbox.log', str(root / 'log'))
        subprocess.run(['sh', '-n'], input=launcher, text=True, check=True)
        result = subprocess.run(['sh', '-c', launcher], env={'LD_LIBRARY_PATH': '/stock/odm'}, timeout=5)
        assert result.returncode == 7
        log = (root / 'log').read_text()
        assert '/system/lib64:/' + PRIVATE_LIBS in log and 'linker-test' in log
        assert stat.S_IMODE((root / 'log').stat().st_mode) == 0o600
    print(f'PASS: StrongBox dependency closure, {len(files)} missing files, duplicate USB owner, launcher environment/error capture')


if __name__ == '__main__':
    main()
