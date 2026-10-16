#!/usr/bin/env python3
"""Host checks for Neo8 WLAN assembly and real shell dependency traversal.

insmod is mocked. These tests do not assert that the phone's radio works.
"""
import argparse
from copy import deepcopy
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'scripts'))
from boot_ramdisk import read_recovery
from secure_element_bundle import verified_reference
from wifi_config import expected_files, make_loader, validate_wifi_image, FIRMWARE_DIRS, BUILT_BINARIES


def check_module_traversal(loader):
    begin = loader.index('module_loaded() {')
    end = loader.index('\nload_with_dependencies /', begin)
    functions = loader[begin:end]
    for label, dep, files, fail, success, order in (
        ('bare names', 'main.ko: dep.ko\ndep.ko:\n', ('main', 'dep'), '', True, ['dep.ko', 'main.ko']),
        ('path names', 'kernel/main.ko: kernel/dep.ko\nkernel/dep.ko:\n', ('main', 'dep'), '', True, ['dep.ko', 'main.ko']),
        ('nested dependency', 'main.ko: kernel/dep.ko\nkernel/dep.ko:\n', ('main', 'kernel/dep'), '', True, ['dep.ko', 'main.ko']),
        ('missing dependency', 'main.ko: missing.ko\n', ('main',), '', False, []),
        ('failed dependency', 'main.ko: dep.ko\n', ('main', 'dep'), 'dep.ko', False, ['dep.ko']),
        ('cycle', 'main.ko: dep.ko\ndep.ko: main.ko\n', ('main', 'dep'), '', False, []),
        ('shared dependency', 'main.ko: a.ko b.ko\na.ko: dep.ko\nb.ko: dep.ko\ndep.ko:\n',
         ('main', 'a', 'b', 'dep'), '', True, ['dep.ko', 'a.ko', 'b.ko', 'main.ko']),
    ):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            modules = root / 'vendor_dlkm/lib/modules'
            modules.mkdir(parents=True)
            (root / 'loaded').write_text('')
            (root / 'seen').mkdir()
            (modules / 'modules.dep').write_text(dep)
            for name in files:
                (modules / (name + '.ko')).parent.mkdir(parents=True, exist_ok=True)
                (modules / (name + '.ko')).touch()
            script = functions.replace('/tmp/aera-wifi', str(root)).replace('/proc/modules', str(root / 'loaded'))
            mock = '''
insmod() {
    name=$(basename "$1")
    echo "$name" >> "$TEST_ROOT/order"
    [ "$name" != "$FAIL_MODULE" ] || return 1
    echo "${name%.ko} 1 0 - Live 0" >> "$TEST_ROOT/loaded"
}
'''
            result = subprocess.run(['sh', '-c', mock + script + '\nload_with_dependencies main.ko'],
                env={'PATH': '/usr/bin:/bin', 'SEEN_DIR': str(root / 'seen'),
                     'TEST_ROOT': tmp, 'FAIL_MODULE': fail}, capture_output=True, text=True, timeout=5)
            assert (result.returncode == 0) == success, (label, result.stdout, result.stderr)
            actual = (root / 'order').read_text().splitlines() if (root / 'order').exists() else []
            assert actual == order, (label, actual)
    # Re-enabling WLAN must not insert an already loaded qcacld under its alias.
    with tempfile.TemporaryDirectory() as tmp:
        loaded = Path(tmp) / 'loaded'
        loaded.write_text('wlan 1 0 - Live 0\n')
        subprocess.run(['sh', '-c', functions.replace('/proc/modules', str(loaded)) +
                        '\nmodule_loaded qca_cld3_wcn7750.ko'], check=True)
    print('PASS: 7 shell dependency scenarios and qcacld loaded-name alias')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True, type=Path)
    parser.add_argument('--reference', required=True)
    args = parser.parse_args()
    reference = verified_reference(*read_recovery(args.reference))
    expected = expected_files(reference)
    for path, data in expected.items():
        assert (args.device / 'prebuilt' / path).read_bytes() == data, path
        if path.endswith('.sh'):
            subprocess.run(['sh', '-n', str(args.device / 'prebuilt' / path)], check=True)
    board = (args.device / 'BoardConfig.mk').read_text()
    assert 'AERA_ENABLE_WLAN := 1' in board
    assert 'BOARD_WPA_SUPPLICANT_DRIVER := NL80211' in board
    loader = make_loader(reference)
    # Source regression checks for the specific decryption-clobber risk.
    assert '\nmount_partition /vendor' not in loader
    assert '\nmount_partition /odm' not in loader
    assert '/dev/block/sda2' not in loader
    assert 'chmod 0770 /data/vendor/wifi' not in loader
    check_module_traversal(loader)
    fixture = {p: {'mode': 0o100755, 'data': data} for p, data in expected.items()}
    fixture['system/etc/ueventd.rc'] = {'mode': 0o100644, 'data': ' '.join(FIRMWARE_DIRS).encode()}
    fixture['system/bin/recovery'] = {'mode': 0o100755, 'data': b'WLAN: triggering supplicant bring-up'}
    for name in BUILT_BINARIES:
        fixture['system/bin/' + name] = {'mode': 0o100755, 'data': b'\x7fELFtest'}
    validate_wifi_image(fixture, reference)
    cases = []
    for path in (*expected, *('system/bin/' + n for n in BUILT_BINARIES)):
        broken = deepcopy(fixture)
        del broken[path]
        cases.append(broken)
    broken = deepcopy(fixture)
    broken['system/bin/recovery']['data'] = b'Wi-Fi unavailable'
    cases.append(broken)
    broken = deepcopy(fixture)
    broken['vendor/etc/init/duplicate.rc'] = {'mode': 0o100644, 'data': b'service wpa_supplicant /other\n'}
    cases.append(broken)
    for broken in cases:
        try:
            validate_wifi_image(broken, reference)
        except ValueError:
            pass
        else:
            raise AssertionError('Accepted broken Wi-Fi image fixture')
    print(f'PASS: source assembly and {len(cases)} rejected broken image fixtures')


if __name__ == '__main__':
    main()
