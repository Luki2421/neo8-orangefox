#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regress the missing logd profiles and misplaced VINTF fragments from phone logs."""
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from neo8_service_config import configure_service_files
from inspect_recovery import inspect_service_config

class ServiceConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.device = self.root / 'device'
        self.profiles = self.root / 'system/core/libprocessgroup/profiles/task_profiles.json'
        self.profiles.parent.mkdir(parents=True)
        self.profiles.write_text(json.dumps({'Profiles': [
            {'Name': 'LowIoPriority', 'Actions': [{'Name': 'JoinCgroup', 'Params': {'Controller': 'blkio', 'Path': 'background'}}]},
            {'Name': 'NormalIoPriority', 'Actions': []}],
            'AggregateProfiles': [{'Name': 'SCHED_SP_BACKGROUND', 'Profiles': ['LowIoPriority']}]}))
        self.rc = self.device / 'recovery/root/init.recovery.qcom.rc'
        self.rc.parent.mkdir(parents=True)
        self.rc.write_text('on early-init\n    start vendor.gatekeeper_default\n')
        self.names = ('android.hardware.health-service.qti.xml', 'boot-service.qti.xml', 'android.hardware.fastboot-service.example.xml')
        for name in self.names:
            p = self.device / 'prebuilt/system/etc/vintf/manifest' / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('<manifest version="9.0" type="device"/>')
        self.vendor = self.device / 'prebuilt/vendor/etc/vintf/manifest/android.hardware.health-service.qti.xml'
        self.vendor.parent.mkdir(parents=True)
        self.vendor.write_text('<manifest version="8.0" type="device"/>')

    def entries(self):
        content = (self.device / 'recovery/root/system/etc/task_profiles.json').read_bytes()
        return {'etc': {'mode': stat.S_IFLNK | 0o777, 'data': b'/system/etc'},
                'system/etc/task_profiles.json': {'mode': stat.S_IFREG | 0o644, 'data': content},
                'system/etc/vintf/manifest.xml': {'mode': stat.S_IFREG | 0o644, 'data': b'<manifest type="framework"/>'}}

    def test_stage_profiles_and_device_fragments(self):
        report = configure_service_files(self.device, self.root)
        self.assertEqual(len(report['device_fragments_removed_from_framework']), 3)
        self.assertEqual(self.vendor.read_text(), '<manifest version="8.0" type="device"/>')
        for name in self.names:
            self.assertFalse((self.device / 'prebuilt/system/etc/vintf/manifest' / name).exists())
            self.assertTrue((self.vendor.parent / name).exists())
        self.assertIn('mkdir /dev/cpuctl/background', self.rc.read_text())
        self.assertIn('mkdir /dev/blkio/background', self.rc.read_text())
        entries = self.entries()
        config = json.loads(entries['system/etc/task_profiles.json']['data'])
        alias = next(p for p in config['AggregateProfiles'] if p['Name'] == 'BlkIOBackground')
        self.assertEqual(alias['Profiles'], ['LowIoPriority'])
        self.assertTrue(inspect_service_config(entries)['required_task_profiles_present'])
        # The /etc symlink may be created at runtime by init, not in the CPIO.
        del entries['etc']
        self.assertTrue(inspect_service_config(entries)['required_task_profiles_present'])

    def test_missing_profile_file_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Missing recovery task_profiles'):
            inspect_service_config({})

    def test_missing_compatibility_profile_rejected(self):
        configure_service_files(self.device, self.root)
        entries = self.entries()
        entries['system/etc/task_profiles.json']['data'] = self.profiles.read_bytes()
        with self.assertRaisesRegex(ValueError, 'BlkIOBackground'):
            inspect_service_config(entries)

    def test_device_fragment_in_framework_rejected(self):
        configure_service_files(self.device, self.root)
        for partition in ('system', 'system_ext', 'product'):
            entries = self.entries()
            entries[partition + '/etc/vintf/manifest/health.xml'] = {
                'mode': stat.S_IFREG | 0o644, 'data': b'<manifest type="device"/>'}
            with self.assertRaisesRegex(ValueError, 'Device manifest in framework'):
                inspect_service_config(entries)

    def test_framework_fragment_preserved(self):
        p = self.device / 'prebuilt/system/etc/vintf/manifest/framework.xml'
        p.write_text('<manifest type="framework"/>')
        configure_service_files(self.device, self.root)
        self.assertTrue(p.exists())

    def test_unexpected_source_profiles_rejected(self):
        self.profiles.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Unexpected source task profiles'):
            configure_service_files(self.device, self.root)

if __name__ == '__main__':
    unittest.main()
