#!/usr/bin/env python3
"""Patch ONLY the release-1.1 Neo8 recovery null-pointer bug. Linux + liblz4.
Usage: python3 build_nullguard.py TWRP_Neo8_Fix.img output.img
No phone access or flashing is performed. No signature/AVB policy is changed.
"""
import ctypes, ctypes.util, hashlib, pathlib, struct, sys
EXPECTED='b99a36d62c1d4e17b88ed979acdd87e58e6325b62e5ff4dc3dd116c0e5f92c9d'
SITE=0x192328; CAVE=0x3b1100; SKIP=0x1912c8; RESUME=0x19232c

def branch(src,dst):
    delta=dst-src
    assert delta%4==0 and -(1<<27)<=delta<(1<<27)
    return struct.pack('<I',0x14000000|((delta//4)&0x3ffffff))

def patch_elf(raw):
    assert hashlib.sha256(raw).hexdigest()=='896e8998ce76036e44fcaed2fd8335926091e1a3dbdb5a9471e100dbf1ec1092'
    b=bytearray(raw)
    assert b[SITE:SITE+8].hex()=='210080520cbbfe97'
    assert b[CAVE:CAVE+16]==bytes(16)
    assert b[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',b,18)[0]==183
    # Append 16 bytes to the executable PT_LOAD in unused padding before RW data.
    phoff=struct.unpack_from('<Q',b,32)[0]
    entsize,count=struct.unpack_from('<HH',b,54)
    matches=[]
    for i in range(count):
        pos=phoff+i*entsize
        typ,flags,off,va,pa,fs,ms,align=struct.unpack_from('<II6Q',b,pos)
        if typ==1 and flags==5 and off==0x11c000:
            assert off==va==pa and fs==ms==0x295100 and off+fs==CAVE
            matches.append(pos)
    assert len(matches)==1
    ph=matches[0]
    struct.pack_into('<QQ',b,ph+32,0x295110,0x295110)
    b[SITE:SITE+4]=branch(SITE,CAVE)
    # x0 is TWPartition*. Null skips Mount; non-null restores the displaced
    # mov w1,#1 then returns to the ORIGINAL bl Mount, preserving LR semantics.
    stub=struct.pack('<I',0xb5000040)+branch(CAVE+4,SKIP)+bytes.fromhex('21008052')+branch(CAVE+12,RESUME)
    b[CAVE:CAVE+16]=stub
    return bytes(b)

def lz4lib():
    lib=ctypes.CDLL(ctypes.util.find_library('lz4') or 'liblz4.so.1')
    lib.LZ4_decompress_safe.argtypes=[ctypes.c_char_p,ctypes.c_void_p,ctypes.c_int,ctypes.c_int]
    lib.LZ4_decompress_safe.restype=ctypes.c_int
    lib.LZ4_compressBound.argtypes=[ctypes.c_int];lib.LZ4_compressBound.restype=ctypes.c_int
    lib.LZ4_compress_HC.argtypes=[ctypes.c_char_p,ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_int]
    lib.LZ4_compress_HC.restype=ctypes.c_int
    return lib

def decompress(rd,lib):
    assert rd[:4]==bytes.fromhex('02214c18')
    pos=4;out=[]
    while pos<len(rd):
        n=struct.unpack_from('<I',rd,pos)[0];pos+=4
        if n==0:
            assert pos==len(rd);break
        assert 0<n<=len(rd)-pos
        buf=ctypes.create_string_buffer(8*1024*1024)
        size=lib.LZ4_decompress_safe(rd[pos:pos+n],buf,n,len(buf));assert size>0
        out.append(buf.raw[:size]);pos+=n
    return b''.join(out)

def cpio_entries(data):
    pos=0
    while pos+110<=len(data):
        assert data[pos:pos+6] in (b'070701',b'070702')
        fields=[int(data[pos+6+i*8:pos+14+i*8],16) for i in range(13)]
        size,ns=fields[6],fields[11]
        name=data[pos+110:pos+110+ns-1].decode()
        start=(pos+110+ns+3)&~3
        if name=='TRAILER!!!':return
        yield name,start,size
        pos=(start+size+3)&~3
    raise ValueError('No CPIO trailer')

def main(src,dst):
    original=pathlib.Path(src).read_bytes()
    assert hashlib.sha256(original).hexdigest()==EXPECTED,'Wrong or damaged source image'
    assert len(original)==104857600 and original[:8]==b'ANDROID!'
    assert struct.unpack_from('<I',original,40)[0]==4
    assert struct.unpack_from('<I',original,1580)[0]==0,'Unexpected boot signature'
    lib=lz4lib();rdsize=struct.unpack_from('<I',original,12)[0]
    cpio=decompress(original[4096:4096+rdsize],lib);patched=bytearray(cpio)
    entries=list(cpio_entries(cpio));target=[x for x in entries if x[0]=='system/bin/recovery'];assert len(target)==1
    _,start,size=target[0];exe=patch_elf(cpio[start:start+size]);assert len(exe)==size
    patched[start:start+size]=exe
    assert patched[:start]==cpio[:start] and patched[start+size:]==cpio[start+size:]
    chunks=[bytes.fromhex('02214c18')]
    for pos in range(0,len(patched),8*1024*1024):
        block=bytes(patched[pos:pos+8*1024*1024]);bound=lib.LZ4_compressBound(len(block));buf=ctypes.create_string_buffer(bound)
        n=lib.LZ4_compress_HC(block,buf,len(block),bound,9);assert n>0
        chunks.extend([struct.pack('<I',n),buf.raw[:n]])
    compressed=b''.join(chunks)
    assert decompress(compressed,lib)==patched
    image=bytearray(original[:4096]);struct.pack_into('<I',image,12,len(compressed));image.extend(compressed)
    image.extend(bytes((-len(image))%4096));original_size=len(image)
    # Preserve the original unsigned AVB metadata and all properties/policy.
    magic,major,minor,old_size,offset,vbsize=struct.unpack_from('>4sIIQQQ',original,len(original)-64)
    assert magic==b'AVBf'
    vb=bytearray(original[offset:offset+vbsize]);assert vb[:4]==b'AVB0'
    auth,aux=struct.unpack_from('>QQ',vb,12);alg=struct.unpack_from('>I',vb,28)[0]
    assert auth==0 and alg==0,'Signed AVB metadata not supported'
    desc_offset,desc_size=struct.unpack_from('>QQ',vb,96)
    pos=256+auth+desc_offset;end=pos+desc_size;found=0
    while pos<end:
        tag,following=struct.unpack_from('>QQ',vb,pos)
        if tag==2:
            oldhashsize=struct.unpack_from('>Q',vb,pos+16)[0]
            hash_alg=bytes(vb[pos+24:pos+56]).rstrip(b'\0')
            namelen,saltlen,digestlen=struct.unpack_from('>III',vb,pos+56)
            name=bytes(vb[pos+132:pos+132+namelen]);saltstart=pos+132+namelen
            salt=bytes(vb[saltstart:saltstart+saltlen]);digeststart=saltstart+saltlen
            assert name==b'recovery' and hash_alg==b'sha256' and digestlen==32 and oldhashsize==old_size
            assert hashlib.sha256(salt+original[:old_size]).digest()==vb[digeststart:digeststart+32]
            struct.pack_into('>Q',vb,pos+16,original_size)
            vb[digeststart:digeststart+32]=hashlib.sha256(salt+image).digest();found+=1
        pos+=16+following
    assert found==1 and pos==end
    image.extend(vb);assert len(image)<len(original)-4096
    image.extend(bytes(len(original)-64-len(image)))
    footer=bytearray(original[-64:]);struct.pack_into('>QQ',footer,12,original_size,original_size);image.extend(footer)
    assert len(image)==len(original)
    pathlib.Path(dst).write_bytes(image)
    print('Output:',dst,'\nSHA256:',hashlib.sha256(image).hexdigest(),'\nModified CPIO files: system/bin/recovery only',flush=True)
if __name__=='__main__':
    if len(sys.argv)!=3:raise SystemExit(__doc__)
    main(sys.argv[1],sys.argv[2])
