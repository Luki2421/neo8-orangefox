#!/usr/bin/env bash
# Buduje libxzjni.so (arm64, NDK): natywny dekoder XZ dla rozpakowywania OTA - liblzma z xz-utils
# (statycznie) + cienka warstwa JNI z app/src/main/cpp/xzjni.c.
set -euo pipefail
API=26
NDK="${ANDROID_NDK_LATEST_HOME:-$ANDROID_NDK_HOME}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SRC="$GITHUB_WORKSPACE/app/src/main/cpp/xzjni.c"
W=/tmp/xzjnibuild; rm -rf $W; mkdir -p $W; cd $W

git clone -q --depth 1 -b v5.6.4 https://github.com/tukaani-project/xz.git
cmake -S xz -B build -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DENABLE_NLS=OFF -DXZ_NLS=OFF >/dev/null
cmake --build build --target liblzma -j"$(nproc)"

"$TC/aarch64-linux-android$API-clang" -O2 -shared -fPIC -Wall -Werror \
  -I xz/src/liblzma/api "$SRC" build/liblzma.a -Wl,--no-undefined -Wl,-z,max-page-size=16384 -o libxzjni.so
"$TC/llvm-strip" libxzjni.so

DST="$GITHUB_WORKSPACE/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DST"
cp libxzjni.so "$DST/"
file "$DST/libxzjni.so"
"$TC/llvm-nm" -D "$DST/libxzjni.so" | grep Java_
