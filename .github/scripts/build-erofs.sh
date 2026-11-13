#!/usr/bin/env bash
# Buduje mkfs.erofs i fsck.erofs (z --extract) ze zrodel dla Androida arm64 (NDK, statycznie, z lz4).
set -euo pipefail
API=34
NDK="${ANDROID_NDK_LATEST_HOME:-$ANDROID_NDK_HOME}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TC/aarch64-linux-android$API-clang"
export AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
W=/tmp/erofsbuild; rm -rf $W; mkdir -p $W; cd $W

git clone -q --depth 1 -b v1.10.0 https://github.com/lz4/lz4.git
make -s -C lz4/lib CC="$CC" AR="$AR" liblz4.a

git clone -q --depth 1 -b v1.8.10 https://git.kernel.org/pub/scm/linux/kernel/git/xiang/erofs-utils.git
cd erofs-utils
./autogen.sh >/dev/null
export PKG_CONFIG_LIBDIR=/nonexistent PKG_CONFIG_PATH=
./configure --host=aarch64-linux-android --disable-fuse --disable-multithreading \
  --without-uuid --without-selinux --without-zlib --without-libdeflate --without-libzstd --without-qpl --without-xxhash \
  LZ4_CFLAGS="-I$W/lz4/lib" LZ4_LIBS="-L$W/lz4/lib -llz4" \
  CPPFLAGS="-I$W/lz4/lib" LDFLAGS="-static -L$W/lz4/lib" | grep -i "lz4" || true
grep -n "LZ4" config.h || true
make -s -j"$(nproc)"
$STRIP mkfs/mkfs.erofs fsck/fsck.erofs

DST="$GITHUB_WORKSPACE/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DST"
cp mkfs/mkfs.erofs "$DST/libmkfs_erofs.so"
cp fsck/fsck.erofs "$DST/libfsck_erofs.so"
file "$DST"/*
