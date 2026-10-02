#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regress the missing logd profiles and misplaced VINTF fragments from phone logs."""
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from neo8_service_config import (configure_service_files, configure_fastboot_manifest,
                                 configure_omapi_manifest, remove_duplicate_device_fragments)
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
        self.base = self.device / 'prebuilt/vendor'
        self.main = self.base / 'etc/vintf/manifest.xml'
        self.gatekeeper = self.vendor.parent / 'android.hardware.gatekeeper-service-qti.xml'
        gatekeeper = ('<hal format="aidl"><name>android.hardware.gatekeeper</name>'
                      '<fqname>IGatekeeper/default</fqname></hal>')
        self.main.write_text('<manifest version="8.0" type="device" target-level="202404">' +
                             gatekeeper + '<sepolicy><version>202404</version></sepolicy></manifest>')
        self.gatekeeper.write_text('<manifest version="9.0" type="device">' + gatekeeper + '</manifest>')
        self.omapi = self.vendor.parent / 'se_omapi.xml'
        self.omapi.write_text('<manifest type="device"><hal format="aidl"><name>android.se.omapi</name>'
                              '<fqname>ISecureElementService/default</fqname></hal></manifest>')
        self.odm = self.base / 'odm/etc/vintf/manifest/secure_element_omapi_service.xml'
        self.odm.parent.mkdir(parents=True)
        self.odm.write_text('<manifest type="device"><hal format="aidl"><name>android.se.omapi</name>'
                            '<version>1</version><interface><name>ISecureElementService</name>'
                            '<instance>default</instance></interface></hal></manifest>')

    def add_manifests(self, entries):
        for path in self.base.rglob('*.xml'):
            entries['vendor/' + str(path.relative_to(self.base))] = {
                'mode': stat.S_IFREG | 0o644, 'data': path.read_bytes()}
        return entries

    def entries(self):
        content = (self.device / 'recovery/root/system/etc/task_profiles.json').read_bytes()
        return {'etc': {'mode': stat.S_IFLNK | 0o777, 'data': b'/system/etc'},
                'system/etc/task_profiles.json': {'mode': stat.S_IFREG | 0o644, 'data': content},
                'system/etc/vintf/manifest.xml': {'mode': stat.S_IFREG | 0o644, 'data': b'<manifest type="framework"/>'}}

    def test_stage_profiles_and_device_fragments(self):
        main, odm = self.main.read_bytes(), self.odm.read_bytes()
        report = configure_service_files(self.device, self.root)
        self.assertEqual(len(report['device_manifest_duplicates']['removed']), 2)
        self.assertFalse(self.gatekeeper.exists())
        self.assertFalse(self.omapi.exists())
        self.assertEqual(self.main.read_bytes(), main)
        self.assertEqual(self.odm.read_bytes(), odm)
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

    def test_phone_gatekeeper_collision_rejected_in_image(self):
        duplicate = self.gatekeeper.read_bytes()
        configure_service_files(self.device, self.root)
        entries = self.add_manifests(self.entries())
        entries['vendor/etc/vintf/manifest/gatekeeper.xml'] = {
            'mode': stat.S_IFREG | 0o644, 'data': duplicate}
        with self.assertRaisesRegex(ValueError, 'Conflicting AIDL instance android.hardware.gatekeeper'):
            inspect_service_config(entries)

    def test_cross_partition_omapi_collision_rejected_in_image(self):
        duplicate = self.omapi.read_bytes()
        configure_service_files(self.device, self.root)
        entries = self.add_manifests(self.entries())
        entries['vendor/etc/vintf/manifest/se_omapi.xml'] = {
            'mode': stat.S_IFREG | 0o644, 'data': duplicate}
        with self.assertRaisesRegex(ValueError, 'Conflicting AIDL instance android.se.omapi'):
            inspect_service_config(entries)

    def test_odm_alias_not_counted_twice(self):
        configure_service_files(self.device, self.root)
        entries = self.add_manifests(self.entries())
        entries['odm'] = {'mode': stat.S_IFLNK | 0o777, 'data': b'/vendor/odm'}
        self.assertEqual(inspect_service_config(entries)['unique_device_aidl_instances'], 2)

    def test_aidl_version_difference_does_not_hide_collision(self):
        configure_service_files(self.device, self.root)
        entries = self.add_manifests(self.entries())
        entries['vendor/etc/vintf/manifest/gatekeeper-v2.xml'] = {
            'mode': stat.S_IFREG | 0o644,
            'data': b'<manifest type="device"><hal format="aidl"><name>android.hardware.gatekeeper</name>'
                    b'<version>2</version><interface><name>IGatekeeper</name><instance>default</instance>'
                    b'</interface></hal></manifest>'}
        with self.assertRaisesRegex(ValueError, 'Conflicting AIDL instance'):
            inspect_service_config(entries)

    def test_unique_interface_not_silently_removed(self):
        self.omapi.write_text(self.omapi.read_text().replace('</hal>',
                             '<fqname>ISecureElementService/other</fqname></hal>'))
        with self.assertRaisesRegex(ValueError, 'Unexpected duplicate HAL declarations'):
            remove_duplicate_device_fragments(self.device)
        self.assertTrue(self.gatekeeper.exists())
        self.assertTrue(self.omapi.exists())

    def test_unexpected_retained_version_rejected(self):
        self.odm.write_text(self.odm.read_text().replace('<version>1', '<version>2'))
        with self.assertRaisesRegex(ValueError, 'Unexpected duplicate HAL declarations'):
            remove_duplicate_device_fragments(self.device)
        self.assertTrue(self.gatekeeper.exists())

    def fastboot_fixture(self):
        vendor = self.vendor.parent / 'android.hardware.fastboot-service.example.xml'
        vendor.write_text('<manifest type="device"><hal format="aidl">'
                          '<name>android.hardware.fastboot</name>'
                          '<fqname>IFastboot/default</fqname></hal></manifest>')
        path = self.root / 'hardware/interfaces/fastboot/aidl/default/Android.bp'
        path.parent.mkdir(parents=True)
        path.write_text('cc_binary {\n'
                        '    name: "android.hardware.fastboot-service.example_recovery",\n'
                        '    init_rc: ["android.hardware.fastboot-service.example_recovery.rc"],\n'
                        '    vintf_fragments: ["android.hardware.fastboot-service.example.xml"],\n'
                        '    recovery: true,\n'
                        '    srcs: ["Fastboot.cpp", "main.cpp"],\n}\n')
        return path, vendor

    def omapi_fixture(self):
        path = self.root / 'bootable/recovery/Android.mk'
        path.parent.mkdir(parents=True)
        path.write_text('ifeq ($(TW_INCLUDE_OMAPI), true)\n'
                        '        LOCAL_CFLAGS += -DTW_INCLUDE_OMAPI\n'
                        '        LOCAL_SHARED_LIBRARIES += android.se.omapi-V1-ndk\n'
                        '        TWRP_REQUIRED_MODULES += \\\n'
                        '            se_omapi \\\n'
                        '            se_omapi.rc \\\n'
                        '            se_omapi.xml\n'
                        'endif\n'
                        'all:\n\t@echo $(TWRP_REQUIRED_MODULES)\n')
        return path

    def test_make_keeps_omapi_binary_and_rc_without_duplicate_fragment(self):
        path = self.omapi_fixture()
        original_odm = self.odm.read_bytes()
        def required_modules(enabled):
            return subprocess.check_output(['make', '--no-print-directory', '-f', str(path),
                                            'TW_INCLUDE_OMAPI=' + enabled], text=True).split()
        self.assertEqual(required_modules('true'), ['se_omapi', 'se_omapi.rc', 'se_omapi.xml'])
        configure_omapi_manifest(self.device, self.root)
        self.assertEqual(required_modules('true'), ['se_omapi', 'se_omapi.rc'])
        self.assertEqual(required_modules('false'), [])
        self.assertIn('-DTW_INCLUDE_OMAPI', path.read_text())
        self.assertIn('android.se.omapi-V1-ndk', path.read_text())
        self.assertEqual(self.odm.read_bytes(), original_odm)

    def test_omapi_make_fix_requires_retained_declaration(self):
        path = self.omapi_fixture()
        original = path.read_bytes()
        self.odm.write_text('<manifest type="device"/>')
        with self.assertRaisesRegex(ValueError, 'Missing ODM OMAPI'):
            configure_omapi_manifest(self.device, self.root)
        self.assertEqual(path.read_bytes(), original)

    def test_unexpected_omapi_make_rule_rejected(self):
        path = self.omapi_fixture()
        path.write_text(path.read_text().replace('se_omapi.xml', 'different.xml'))
        original = path.read_bytes()
        with self.assertRaisesRegex(ValueError, 'Unexpected recovery OMAPI'):
            configure_omapi_manifest(self.device, self.root)
        self.assertEqual(path.read_bytes(), original)

    def test_soong_fragment_source_fixed_without_removing_service(self):
        path, vendor = self.fastboot_fixture()
        original_vendor = vendor.read_bytes()
        configure_fastboot_manifest(self.device, self.root)
        self.assertNotIn('vintf_fragments:', path.read_text())
        self.assertIn('init_rc:', path.read_text())
        self.assertIn('recovery: true,', path.read_text())
        self.assertIn('"Fastboot.cpp", "main.cpp"', path.read_text())
        self.assertEqual(vendor.read_bytes(), original_vendor)

    def test_soong_fix_requires_vendor_declaration(self):
        path, vendor = self.fastboot_fixture()
        original = path.read_bytes()
        vendor.write_text('<manifest type="device"/>')
        with self.assertRaisesRegex(ValueError, 'Missing vendor fastboot'):
            configure_fastboot_manifest(self.device, self.root)
        self.assertEqual(path.read_bytes(), original)

    def test_unexpected_soong_module_rejected(self):
        path, _ = self.fastboot_fixture()
        path.write_text(path.read_text().replace('recovery: true', 'recovery: false'))
        original = path.read_bytes()
        with self.assertRaisesRegex(ValueError, 'Unexpected recovery fastboot'):
            configure_fastboot_manifest(self.device, self.root)
        self.assertEqual(path.read_bytes(), original)

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
