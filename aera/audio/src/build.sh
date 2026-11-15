#!/bin/sh
# Rebuilds the two prebuilt audio helpers. Needs: pip install ziglang==0.17.1
# The bridge is a static musl executable. The preload is loaded by bionic inside the
# stock player, so it links against nothing but stubs carrying the right sonames.
set -eu
cd "$(dirname "$0")"
ZIG="python3 -m ziglang cc -target aarch64-linux-musl -Wall -Wl,-z,max-page-size=16384"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
for lib in agm log dl; do
    $ZIG -nostdlib -shared -fPIC -Wl,-soname,lib$lib.so -o "$tmp/lib$lib.so" stub_$lib.c
done
$ZIG -static -Os -s -o ../aera-audio-bridge aera-audio-bridge.c -lm
$ZIG -nostdlib -shared -fPIC -O2 -Wl,-soname,libneo8agminit.so -o ../libneo8agminit.so \
    agminit.c "$tmp/libagm.so" "$tmp/liblog.so" "$tmp/libdl.so"
