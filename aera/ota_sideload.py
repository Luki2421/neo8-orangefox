#!/usr/bin/env python3
"""Ship the reference update_engine_sideload that matches the reference liblp.

The recovery image carries liblp.so from the tested TWRP 1.1 reference, but AERA
builds its own update_engine_sideload from newer sources. That binary needs a newer
liblp (MetadataBuilder::GrowBlockDevicesToLiveSize), fails to link and every A/B
payload OTA stops with "CANNOT LINK EXECUTABLE". The reference pair is consistent,
so use the reference binary and prove in the packed image that it links.
"""
from pathlib import Path
import stat
import struct

SIDELOAD = 'system/bin/update_engine_sideload'
LIB_DIRS = ('system/lib64/', 'system_ext/lib64/', 'vendor/lib64/', 'sbin/lib64/')

PT_DYNAMIC = 2
DT_NEEDED, DT_STRTAB, DT_SYMTAB, DT_STRSZ, DT_HASH, DT_GNU_HASH = 1, 5, 6, 10, 4, 0x6ffffef5
STB_WEAK = 2
SHN_UNDEF = 0


def _elf(data):
    if data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
        raise ValueError('Expected a little-endian ELF64 file')
    phoff, = struct.unpack_from('<Q', data, 0x20)
    phentsize, phnum = struct.unpack_from('<HH', data, 0x36)
    segments = []
    for i in range(phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from(
            '<IIQQQQQQ', data, phoff + i * phentsize)
        segments.append((p_type, p_offset, p_vaddr, p_filesz))
    return segments


def _vaddr_to_offset(data, vaddr):
    phoff, = struct.unpack_from('<Q', data, 0x20)
    phentsize, phnum = struct.unpack_from('<HH', data, 0x36)
    for i in range(phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from(
            '<IIQQQQQQ', data, phoff + i * phentsize)
        if p_type == 1 and p_vaddr <= vaddr < p_vaddr + p_filesz:
            return p_offset + vaddr - p_vaddr
    raise ValueError('Dynamic address outside loadable segments')


def _cstr(data, offset):
    return data[offset:data.index(b'\0', offset)].decode()


def dynamic_info(data):
    """Return (needed libraries, defined symbols, undefined strong symbols)."""
    dynamic = [s for s in _elf(data) if s[0] == PT_DYNAMIC]
    if len(dynamic) != 1:
        raise ValueError('Expected one dynamic segment')
    _, offset, _, size = dynamic[0]
    tags, needed_offsets = {}, []
    for pos in range(offset, offset + size, 16):
        tag, value = struct.unpack_from('<qQ', data, pos)
        if tag == 0:
            break
        if tag == DT_NEEDED:
            needed_offsets.append(value)
        else:
            tags[tag] = value
    strtab = _vaddr_to_offset(data, tags[DT_STRTAB])
    symtab = _vaddr_to_offset(data, tags[DT_SYMTAB])
    # The symbol table ends where the next dynamic table starts; use the hash tables
    # to learn its length instead of guessing from section headers (stripped files).
    if DT_GNU_HASH in tags:
        count = _gnu_symbol_count(data, _vaddr_to_offset(data, tags[DT_GNU_HASH]))
    elif DT_HASH in tags:
        count, = struct.unpack_from('<I', data, _vaddr_to_offset(data, tags[DT_HASH]) + 4)
    else:
        raise ValueError('No dynamic hash table')
    defined, undefined = set(), set()
    for i in range(1, count):
        st_name, st_info, _, st_shndx, _, _ = struct.unpack_from('<IBBHQQ', data, symtab + i * 24)
        name = _cstr(data, strtab + st_name)
        if not name:
            continue
        if st_shndx == SHN_UNDEF:
            if st_info >> 4 != STB_WEAK:
                undefined.add(name)
        else:
            defined.add(name)
    return [_cstr(data, strtab + o) for o in needed_offsets], defined, undefined


def _gnu_symbol_count(data, offset):
    nbuckets, symoffset, bloom_size, _ = struct.unpack_from('<IIII', data, offset)
    buckets = offset + 16 + bloom_size * 8
    chains = buckets + nbuckets * 4
    highest = max(struct.unpack_from('<%dI' % nbuckets, data, buckets))
    if highest < symoffset:
        return symoffset
    index = highest
    while not struct.unpack_from('<I', data, chains + (index - symoffset) * 4)[0] & 1:
        index += 1
    return index + 1


def link_problems(binary, files):
    """Names of missing libraries and unresolved symbols, as the loader would see them."""
    needed, _, undefined = dynamic_info(binary)
    missing, loaded, queue, defined = [], set(), list(needed), set()
    while queue:
        name = queue.pop(0)
        if name in loaded:
            continue
        loaded.add(name)
        data = next((files[d + name] for d in LIB_DIRS if d + name in files), None)
        if data is None:
            missing.append(name)
            continue
        deps, exports, _ = dynamic_info(data)
        defined |= exports
        queue.extend(deps)
    unresolved = sorted(undefined - defined) if not missing else []
    return missing, unresolved


def configure_ota_sideload(device, reference):
    data = reference[SIDELOAD]['data']
    target = Path(device) / 'prebuilt' / SIDELOAD
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    target.chmod(0o755)
    return {'file': SIDELOAD, 'size': len(data)}


def validate_ota_sideload(entries, reference):
    entry = entries.get(SIDELOAD)
    if not entry or not stat.S_ISREG(entry['mode']) or not entry['mode'] & 0o111:
        raise ValueError('Missing executable ' + SIDELOAD)
    if entry['data'] != reference[SIDELOAD]['data']:
        raise ValueError(SIDELOAD + ' is not the reference binary; the AERA build won the copy')
    files = {p: e['data'] for p, e in entries.items() if stat.S_ISREG(e['mode'])}
    missing, unresolved = link_problems(entry['data'], files)
    if missing or unresolved:
        raise ValueError('update_engine_sideload cannot link: missing libraries %s, unresolved symbols %s'
                         % (missing, unresolved[:10]))
    return {'file': SIDELOAD, 'links': True}
