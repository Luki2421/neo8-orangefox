#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reject malformed ramdisks and resolve ramdisk links without filesystem extraction."""
import ctypes
import ctypes.util
from pathlib import Path
import stat
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from boot_ramdisk import cpio_entries, legacy_lz4, read_recovery
from inspect_recovery import resolve

def entry(name, body=b'', mode=stat.S_IFREG | 0o644):
    encoded = name.encode() + b'\0'
    fields = [1, mode, 0, 0, 1, 0, len(body), 0, 0, 0, 0, len(encoded), 0]
    header = b'070701' + ''.join(f'{value:08x}' for value in fields).encode()
    data = header + encoded
    data += b'\0' * (-len(data) % 4)
    data += body
    return data + b'\0' * (-len(data) % 4)

def compress(data):
    library = ctypes.CDLL(ctypes.util.find_library('lz4') or 'liblz4.so.1')
    library.LZ4_compress_default.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    library.LZ4_compress_default.restype = ctypes.c_int
    output = ctypes.create_string_buffer(len(data) + len(data)//255 + 16)
    length = library.LZ4_compress_default(data, output, len(data), len(output))
    assert length > 0
    return bytes.fromhex('02214c18') + struct.pack('<I', length) + output.raw[:length]

class ReaderTests(unittest.TestCase):
    def test_valid_boot_header_and_archive(self):
        raw = entry('system/file', b'content') + entry('TRAILER!!!')
        packed = compress(raw)
        header = bytearray(4096)
        header[:8] = b'ANDROID!'
        struct.pack_into('<II', header, 8, 0, len(packed))
        struct.pack_into('<I', header, 20, 1584)
        struct.pack_into('<I', header, 40, 4)
        with tempfile.NamedTemporaryFile() as file:
            file.write(header + packed); file.flush()
            image, files = read_recovery(file.name)
            self.assertEqual(files['system/file']['data'], b'content')
            struct.pack_into('<I', header, 8, 1024)
            file.seek(0); file.write(header); file.flush()
            with self.assertRaisesRegex(ValueError, 'kernel-free'):
                read_recovery(file.name)

    def test_lz4_truncation_and_oversized_block(self):
        for value in (bytes.fromhex('02214c18') + b'\1',
                      bytes.fromhex('02214c18') + struct.pack('<I', 0xffffffff),
                      compress(b'valid content')[:-2]):
            with self.assertRaises(ValueError): legacy_lz4(value)

    def test_unsafe_and_duplicate_paths(self):
        for name in ('/absolute', '../parent', 'safe/../../parent'):
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                cpio_entries(entry(name) + entry('TRAILER!!!'))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            cpio_entries(entry('a/b') + entry('a//b') + entry('TRAILER!!!'))

    def test_missing_trailer_and_truncated_body(self):
        with self.assertRaisesRegex(ValueError, 'Missing CPIO trailer'):
            cpio_entries(entry('file', b'abc'))
        with self.assertRaisesRegex(ValueError, 'Truncated CPIO body'):
            cpio_entries(entry('file', b'abc')[:-4])

    def test_directory_links(self):
        files = cpio_entries(entry('odm', b'/vendor/odm', stat.S_IFLNK|0o777) +
                             entry('vendor/odm/bin/service', b'ELF') + entry('TRAILER!!!'))
        name, file = resolve(files, 'odm/bin/service')
        self.assertEqual(name, 'vendor/odm/bin/service')
        self.assertEqual(file['data'], b'ELF')

    def test_symlink_loops_and_root_escape(self):
        for target in ('loop', '../escape'):
            files = cpio_entries(entry('loop', target.encode(), stat.S_IFLNK|0o777) + entry('TRAILER!!!'))
            with self.assertRaises(ValueError): resolve(files, 'loop')

if __name__ == '__main__':
    unittest.main()
