#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regress the lpdumpd linker failure reported from the phone."""
from pathlib import Path
import hashlib
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from inspect_recovery import inspect_partition_tools
from neo8_service_config import DONOR_PARTITION_LIBRARIES, use_source_partition_libraries


class PartitionToolDependenciesTests(unittest.TestCase):
    def setUp(self):
        self.entries = {}
        self.dependencies = {}
        for name in ('lpdump', 'lpdumpd', 'fastbootd'):
            self.add('system/bin/' + name)
        self.dependencies[b'system/bin/lpdumpd'] = ['libfs_mgr_binder.so']
        self.add('system/lib64/libfs_mgr_binder.so', ['libbinder.so'])
        self.add('system/lib64/libbinder.so')
        self.reader = patch('inspect_recovery.elf_dependencies', side_effect=self.dependencies.__getitem__)
        self.reader.start()
        self.addCleanup(self.reader.stop)

    def add(self, path, needed=()):
        self.entries[path] = {'mode': stat.S_IFREG | 0o755, 'data': path.encode()}
        self.dependencies[path.encode()] = list(needed)

    def test_complete_closure(self):
        report = inspect_partition_tools(self.entries)
        self.assertIn('system/lib64/libbinder.so', report['elf_dependencies'])
        self.assertFalse(report['runtime_verified'])

    def test_reported_missing_library_fails(self):
        del self.entries['system/lib64/libfs_mgr_binder.so']
        with self.assertRaisesRegex(ValueError, 'lpdumpd needs libfs_mgr_binder.so'):
            inspect_partition_tools(self.entries)

    def test_transitive_missing_library_fails(self):
        del self.entries['system/lib64/libbinder.so']
        with self.assertRaisesRegex(ValueError, 'libfs_mgr_binder.so needs libbinder.so'):
            inspect_partition_tools(self.entries)

    def test_vendor_copy_cannot_mask_missing_recovery_library(self):
        self.entries['vendor/lib64/libbinder.so'] = self.entries.pop('system/lib64/libbinder.so')
        with self.assertRaisesRegex(ValueError, 'needs libbinder.so'):
            inspect_partition_tools(self.entries)

    def test_symlinks_and_cycles(self):
        self.entries['lib64'] = {'mode': stat.S_IFLNK | 0o777, 'data': b'/system/lib64'}
        self.dependencies[b'system/lib64/libbinder.so'] = ['libfs_mgr_binder.so']
        inspect_partition_tools(self.entries)

    def test_non_executable_tool_fails(self):
        self.entries['system/bin/fastbootd']['mode'] = stat.S_IFREG | 0o644
        with self.assertRaisesRegex(ValueError, 'Missing executable partition tool'):
            inspect_partition_tools(self.entries)

    def test_image_gate_rejects_donor_override(self):
        self.add('system/lib64/liblp.so')
        digest = hashlib.sha256(self.entries['system/lib64/liblp.so']['data']).hexdigest()
        with patch.dict(DONOR_PARTITION_LIBRARIES, {'liblp.so': digest}):
            with self.assertRaisesRegex(ValueError, 'Donor partition library overrides'):
                inspect_partition_tools(self.entries)


class SourceLibrarySelectionTests(unittest.TestCase):
    def test_remove_only_verified_overrides(self):
        with tempfile.TemporaryDirectory() as directory:
            device = Path(directory)
            libs = device / 'prebuilt/system/lib64'
            libs.mkdir(parents=True)
            expected = {}
            for name in DONOR_PARTITION_LIBRARIES:
                data = name.encode()
                (libs / name).write_bytes(data)
                expected[name] = hashlib.sha256(data).hexdigest()
            (libs / 'libcrypto.so').write_bytes(b'preserve')
            with patch.dict(DONOR_PARTITION_LIBRARIES, expected):
                report = use_source_partition_libraries(device)
            self.assertEqual(list(libs.iterdir()), [libs / 'libcrypto.so'])
            self.assertFalse(report['phone_flash_and_decryption_verified'])

    def test_changed_blob_rejected_before_any_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            device = Path(directory)
            libs = device / 'prebuilt/system/lib64'
            libs.mkdir(parents=True)
            for name in DONOR_PARTITION_LIBRARIES:
                (libs / name).write_bytes(b'fixture')
            expected = {'liblp.so': hashlib.sha256(b'fixture').hexdigest(),
                        'libfs_mgr.so': hashlib.sha256(b'different').hexdigest()}
            with patch.dict(DONOR_PARTITION_LIBRARIES, expected):
                with self.assertRaisesRegex(ValueError, 'Unexpected donor partition library'):
                    use_source_partition_libraries(device)
            self.assertTrue(all((libs / name).is_file() for name in expected))


if __name__ == '__main__':
    unittest.main()
