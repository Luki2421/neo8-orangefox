#!/usr/bin/env python3
"""Run the production bootstrap with a property store and a simulated init gate."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / 'scripts'))
from prepare_neo8_build import configure_keymint_bootstrap


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        fake = self.root / 'property-tool'
        fake.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
p = pathlib.Path(os.environ['TEST_PROPS'])
d = json.loads(p.read_text())
kind = pathlib.Path(sys.argv[0]).name
a = sys.argv[1:]
if kind == 'resetprop':
    assert a.pop(0) == '-n'
if kind == 'getprop':
    print(d.get(a[0], ''))
else:
    if a[0] == os.environ.get('TEST_FAIL_PROP'):
        sys.exit(1)
    if a[0] != os.environ.get('TEST_IGNORE_PROP'):
        d[a[0]] = a[1]
    if a == ['twrp.keymint.bootstrap_ready', '1']:
        # A HAL launched by init must see the entire stock tuple, never 16/17 mixed.
        assert d['ro.build.version.release'] == d['twrp.keymint.osver']
        assert d['ro.build.version.release_or_codename'] == d['twrp.keymint.osver']
        assert d['ro.build.version.security_patch'] == d['twrp.keymint.ospatch']
        assert d['ro.vendor.build.security_patch'] == d['twrp.keymint.venpatch']
    p.write_text(json.dumps(d))
''')
        fake.chmod(0o755)
        for name in ('getprop', 'setprop', 'resetprop'):
            (self.root / name).symlink_to(fake)
        self.script = self.root / 'bootstrap.sh'
        self.script.write_text((PROJECT / 'files/neo8-keymint-bootstrap.sh').read_text().replace(
            'RP=/system/bin/resetprop', 'RP="' + str(self.root / 'resetprop') + '"'))
        self.db = self.root / 'props.json'
        self.initial = {'ro.build.version.release': '16',
                        'ro.build.version.release_or_codename': '16',
                        'ro.build.version.security_patch': '2025-06-05',
                        'ro.vendor.build.security_patch': '2026-05-01',
                        'twrp.keymint.osver': '17',
                        'twrp.keymint.ospatch': '2026-09-01',
                        'twrp.keymint.venpatch': '2026-08-05'}

    def run_helper(self, props, **extra):
        self.db.write_text(json.dumps(props))
        env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                   TEST_PROPS=str(self.db), **extra)
        result = subprocess.run(['sh', str(self.script)], env=env, capture_output=True, timeout=15)
        return result.returncode, json.loads(self.db.read_text())

    def test_initial_hal_receives_stock_values(self):
        for version in ('16', '16.0', '16.0.0', '17', '17.0', '17.0.0'):
            with self.subTest(version=version):
                code, props = self.run_helper(dict(self.initial, **{'twrp.keymint.osver': version}))
                self.assertEqual(code, 0)
                self.assertEqual(props['twrp.keymint.bootstrap_ready'], '1')
                self.assertEqual(props['ro.build.version.release_or_codename'], version)
                self.assertNotIn('crypto.ready', props)

    def test_incomplete_or_spoofed_inputs_never_open_gate(self):
        invalid = [('twrp.keymint.osver', v) for v in ('', '99.87.36', '17;id')]
        invalid += [(key, val) for key in ('twrp.keymint.ospatch', 'twrp.keymint.venpatch')
                    for val in ('', '2099-12-31', '2026-02-29', '2026-04-31', '2026-00-01',
                                '2026-13-01', '2026-09-00', '2026-09-01;id')]
        for key, val in invalid:
            with self.subTest(key=key, value=val):
                code, props = self.run_helper(dict(self.initial, **{key: val}))
                self.assertNotEqual(code, 0)
                self.assertEqual(props['twrp.keymint.bootstrap_ready'], '0')
                self.assertEqual(props['ro.build.version.release'], '16')

    def test_failed_or_ignored_property_write_never_opens_gate(self):
        for failure in ('TEST_FAIL_PROP', 'TEST_IGNORE_PROP'):
            for key in ('ro.build.version.release', 'ro.build.version.release_or_codename',
                        'ro.build.version.security_patch', 'ro.vendor.build.security_patch'):
                code, props = self.run_helper(self.initial, **{failure: key})
                self.assertNotEqual(code, 0)
                self.assertEqual(props['twrp.keymint.bootstrap_ready'], '0')

    def test_leap_day(self):
        code, props = self.run_helper(dict(self.initial, **{'twrp.keymint.ospatch': '2024-02-29'}))
        self.assertEqual(code, 0)
        self.assertEqual(props['twrp.keymint.bootstrap_ready'], '1')

    def test_init_and_reader_have_no_start_before_bootstrap(self):
        device = self.root / 'device'
        km = device / 'prebuilt/vendor/etc/init/android.hardware.security.onekeymint-service-qti.rc'
        sb = device / 'prebuilt/vendor/odm/etc/init/android.hardware.security.keymint-service-strongbox-tms-qcom.rc'
        qcom = device / 'recovery/root/init.recovery.qcom.rc'
        for path in (km, sb, qcom):
            path.parent.mkdir(parents=True, exist_ok=True)
        km.write_text('on init\n    start vendor.keymint\n\nservice vendor.keymint /vendor/bin/hw/keymint\n    class early_hal\n')
        sb.write_text('service vendor.keymint-strongbox /odm/bin/hw/strongbox\n    class early_hal\n')
        qcom.write_text('on property:vendor.sys.listeners.registered=true\n    start vendor.ssgtzd\n    start vendor.keymint-strongbox\n')
        result = configure_keymint_bootstrap(device, 'finish() {\n\twait_for_crypto_services\n\tsetprop crypto.ready 1\n}\n')
        self.assertIn('on property:twrp.keymint.bootstrap_ready=1\n    start vendor.keymint', km.read_text())
        self.assertNotIn('on init', km.read_text())
        for path in (km, sb):
            self.assertIn('    disabled\n', path.read_text())
        self.assertIn('on property:vendor.sys.listeners.registered=true && property:twrp.keymint.bootstrap_ready=1\n    start vendor.keymint-strongbox', qcom.read_text())
        self.assertEqual(qcom.read_text().count('start vendor.keymint-strongbox'), 1)
        self.assertLess(result.index('neo8-keymint-bootstrap.sh'), result.index('\twait_for_crypto_services'))
        self.assertIn('finish_error', result)


if __name__ == '__main__':
    unittest.main()
