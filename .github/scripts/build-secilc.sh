#!/usr/bin/env bash
# Buduje secilc (libsepol + CIL z SELinuxProject) dla Androida arm64 (NDK, statycznie).
# Franken kompiluje nim polityke SELinux dawcy AOSP z vendorem bazy dokladnie tak jak init przy starcie
# (/system/bin/secilc telefonu jest dla aplikacji zablokowany przez SELinux - error=13).
# Uzycie: build-secilc.sh [CC] [wyjscie]  (bez argumentow: NDK arm64 -> jniLibs/arm64-v8a/libsecilc.so)
set -euo pipefail
VER=3.11
API=34
if [ $# -ge 1 ]; then CC="$1"; OUT="$2"; STRIP=strip
else
  NDK="${ANDROID_NDK_LATEST_HOME:-$ANDROID_NDK_HOME}"
  TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  CC="$TC/aarch64-linux-android$API-clang"; STRIP="$TC/llvm-strip"
  OUT="$GITHUB_WORKSPACE/app/src/main/jniLibs/arm64-v8a/libsecilc.so"
fi
W=/tmp/secilcbuild; rm -rf $W; mkdir -p $W; cd $W
git clone -q --depth 1 -b $VER https://github.com/SELinuxProject/selinux.git
cd selinux
flex -o libsepol/cil/src/cil_lexer.c libsepol/cil/src/cil_lexer.l
S=libsepol
# secilc.c: #include <sepol/cil/cil.h> (po "make install" libsepol) - tu ze zrodel
mkdir -p inc/sepol && ln -sfn "$PWD/$S/cil/include/cil" inc/sepol/cil
$CC -O2 -static -D_GNU_SOURCE -DHAVE_REALLOCARRAY -Wl,-z,max-page-size=16384 \
  -Iinc -I$S/include -I$S/src -I$S/cil/include -I$S/cil/src \
  $S/src/*.c $S/cil/src/*.c secilc/secilc.c -o secilc.bin
$STRIP secilc.bin
mkdir -p "$(dirname "$OUT")"
cp secilc.bin "$OUT"
file "$OUT"; ls -l "$OUT"
