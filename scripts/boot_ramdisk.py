#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read the kernel-free boot-v4/LZ4 ramdisk without extracting archive paths."""
import ctypes
import ctypes.util
from pathlib import Path, PurePosixPath
import struct

MAX_RAW = 512 * 1024 * 1024
BLOCK = 8 * 1024 * 1024

def legacy_lz4(data):
    if data[:4] != bytes.fromhex('02214c18'):
        raise ValueError('Expected legacy LZ4 compression')
    library = ctypes.CDLL(ctypes.util.find_library('lz4') or 'liblz4.so.1')
    library.LZ4_decompress_safe.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    library.LZ4_decompress_safe.restype = ctypes.c_int
    raw = bytearray()
    pos = 4
    while pos < len(data):
        if pos + 4 > len(data):
            raise ValueError('Truncated LZ4 size')
        size = struct.unpack_from('<I', data, pos)[0]
        pos += 4
        if size == 0:
            if pos != len(data):
                raise ValueError('Trailing LZ4 data')
            break
        if size > BLOCK + BLOCK // 255 + 16 or pos + size > len(data):
            raise ValueError('Invalid LZ4 block size')
        buffer = ctypes.create_string_buffer(BLOCK)
        chunk = data[pos:pos + size]
        count = library.LZ4_decompress_safe(chunk, buffer, size, BLOCK)
        if count < 0 or len(raw) + count > MAX_RAW:
            raise ValueError('Invalid or oversized LZ4 output')
        raw.extend(buffer.raw[:count])
        pos += size
    return bytes(raw)

def cpio_entries(data):
    entries = {}
    pos = 0
    while pos + 110 <= len(data):
        if data[pos:pos + 6] != b'070701':
            raise ValueError('Expected newc CPIO')
        fields = [int(data[pos + 6 + 8*i:pos + 14 + 8*i], 16) for i in range(13)]
        pos += 110
        size, namesize = fields[6], fields[11]
        if not 1 <= namesize <= 4096 or pos + namesize > len(data):
            raise ValueError('Invalid CPIO name')
        encoded = data[pos:pos + namesize]
        if encoded[-1:] != b'\0' or b'\0' in encoded[:-1]:
            raise ValueError('Invalid CPIO name terminator')
        name = encoded[:-1].decode('utf-8')
        pos = (pos + namesize + 3) & ~3
        if pos + size > len(data):
            raise ValueError('Truncated CPIO body')
        body = data[pos:pos + size]
        pos = (pos + size + 3) & ~3
        if name == 'TRAILER!!!':
            return entries
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts:
            raise ValueError('Unsafe CPIO path')
        normalized = str(path)
        if normalized in entries:
            raise ValueError('Duplicate CPIO path')
        entries[normalized] = {'mode': fields[1], 'data': body}
    raise ValueError('Missing CPIO trailer')

def read_recovery(path):
    data = Path(path).read_bytes()
    if len(data) < 4096 or data[:8] != b'ANDROID!':
        raise ValueError('Missing boot header')
    kernel_size, ramdisk_size = struct.unpack_from('<II', data, 8)
    header_size = struct.unpack_from('<I', data, 20)[0]
    version = struct.unpack_from('<I', data, 40)[0]
    signature_size = struct.unpack_from('<I', data, 1580)[0]
    if version != 4 or header_size != 1584 or kernel_size != 0 or signature_size != 0:
        raise ValueError('Expected kernel-free, unsigned boot header v4')
    if ramdisk_size <= 0 or 4096 + ramdisk_size > len(data):
        raise ValueError('Invalid ramdisk extent')
    entries = cpio_entries(legacy_lz4(data[4096:4096 + ramdisk_size]))
    return data, entries
