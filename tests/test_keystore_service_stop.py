#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise actual Neo8 service-stop functions with simulated Android services."""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUBS = r'''
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>
#define PROPERTY_VALUE_MAX 92
#define ALOGI(...) ((void)0)
#define ALOGE(...) ((void)0)
static std::map<std::string,std::string> states;
static std::map<std::string,std::string> props;
static bool stop_ks = true, stop_km = true, source_ready = true;
static bool snapshot_ok = true, binder_ks = true, binder_km = true;
static int backups = 0, unlinks = 0, commands = 0, sleeps = 0;
static int property_set(const char* key, const char* value) {
    std::string k(key), v(value);
    if (k == "ctl.stop") {
        if ((v == "keystore2" && stop_ks) || (v == "vendor.keymint" && stop_km))
            states[v] = "stopped";
    } else if (k == "ctl.start") {
        states[v] = "running";
    } else props[k] = v;
    return 0;
}
static int property_get(const char* key, char* value, const char*) {
    std::string name(key);
    assert(name.rfind("init.svc.", 0) == 0);
    std::string state = states[name.substr(9)];
    std::strcpy(value, state.c_str());
    return state.size();
}
static int fake_access(const char* path, int) {
    assert(std::string(path) == "/data/misc/keystore/persistent.sqlite");
    if (!source_ready) errno = ENOENT;
    return source_ready ? 0 : -1;
}
static int fake_unlink(const char* path) {
    assert(std::string(path).rfind("/tmp/",0) == 0); ++unlinks; return 0;
}
static int fake_chmod(const char* path, int mode) {
    assert(std::string(path).rfind("/tmp/",0) == 0 && mode == 0600); return 0;
}
static int fake_usleep(unsigned int value) { assert(value == 100000); ++sleeps; return 0; }
static int fake_system(const char* command) {
    assert(states["keystore2"] == "stopped" && states["vendor.keymint"] == "stopped");
    assert(std::string(command).rfind("/system/bin/resetprop ",0) == 0);
    ++commands; return 0;
}
static void* AServiceManager_checkService(const char* name) {
    bool available = std::string(name).find("keystore2") != std::string::npos ? binder_ks : binder_km;
    return available ? reinterpret_cast<void*>(1) : nullptr;
}
struct KeystoreInfo {
    bool backupDatabase(const char* source, const char* destination) {
        assert(states["keystore2"] == "stopped");
        assert(std::string(source) == "/data/misc/keystore/persistent.sqlite");
        assert(std::string(destination) == "/tmp/misc/keystore/persistent.sqlite");
        ++backups; return snapshot_ok;
    }
};
namespace android { namespace base {
static std::string GetProperty(const std::string& key, const std::string& fallback) {
    auto found = props.find(key); return found == props.end() ? fallback : found->second;
}
}}
static std::string readRecoveryBuildProperty(const std::vector<std::string>&, const std::string& name) {
    auto found = props.find("stock-file:" + name);
    return found == props.end() ? "" : found->second;
}
#define R_OK 4
#define access fake_access
#define unlink fake_unlink
#define chmod fake_chmod
#define usleep fake_usleep
#define system fake_system
static void reset() {
    states = {{"keystore2","running"},{"vendor.keymint","running"}};
    props = {{"twrp.keymint.osver","16"}, {"twrp.keymint.ospatch","2026-08-01"},
             {"twrp.keymint.venpatch","2026-08-01"}};
    stop_ks = stop_km = source_ready = snapshot_ok = binder_ks = binder_km = true;
    backups = unlinks = commands = sleeps = 0;
}
'''

CASES = r'''
int main() {
    reset(); assert(syncKeystore2DbForDecrypt()); assert(backups == 1 && unlinks == 3);
    reset(); stop_ks = false; assert(!syncKeystore2DbForDecrypt());
    assert(backups == 0 && unlinks == 0 && sleeps == 50);
    assert(states["keystore2"] == "running");
    reset(); source_ready = false; assert(!syncKeystore2DbForDecrypt());
    assert(backups == 0 && unlinks == 0 && sleeps == 0);
    reset(); snapshot_ok = false; assert(!syncKeystore2DbForDecrypt());
    assert(backups == 1 && states["keystore2"] == "running");
    reset(); binder_ks = false; assert(!syncKeystore2DbForDecrypt());
    assert(backups == 1 && sleeps == 100);
    for (bool stock : {false, true}) {
        reset(); assert(setRecoveryKeyMintEnvironment(stock));
        assert(commands == 4 && props["twrp.keymint.ready"] == "1");
    }
    for (int blocked : {0,1,2}) {
        reset(); stop_ks = blocked == 1; stop_km = blocked == 0;
        assert(!setRecoveryKeyMintEnvironment(true));
        assert(commands == 0 && sleeps == 50 && props["twrp.keymint.ready"] == "0");
        assert(states["keystore2"] == "running" && states["vendor.keymint"] == "running");
    }
    reset(); props.clear(); assert(!setRecoveryKeyMintEnvironment(true));
    assert(commands == 0 && sleeps == 0);
    reset(); props = {{"ro.bootimage.build.version.release", "99.87.36"},
                     {"ro.bootimage.build.version.security_patch", "2099-12-31"}};
    assert(!setRecoveryKeyMintEnvironment(true)); assert(commands == 0 && sleeps == 0);
    for (const char* version : {"99.87.36", "15", "16;echo invalid"}) {
        reset(); props["twrp.keymint.osver"] = version;
        assert(!setRecoveryKeyMintEnvironment(true)); assert(commands == 0 && sleeps == 0);
    }
    for (const char* patch : {"", "2099-12-31", "2026-13-01", "2026-02-29", "2026-00-00", "2026-08-01;"}) {
        for (const char* key : {"twrp.keymint.ospatch", "twrp.keymint.venpatch"}) {
            reset(); props[key] = patch;
            assert(!setRecoveryKeyMintEnvironment(true)); assert(commands == 0 && sleeps == 0);
        }
    }
    reset(); props = {{"stock-file:ro.build.version.release", "16.0"},
                     {"stock-file:ro.build.version.security_patch", "2024-02-29"},
                     {"stock-file:ro.vendor.build.security_patch", "2024-02-29"}};
    assert(setRecoveryKeyMintEnvironment(true)); assert(commands == 4);
    reset(); binder_km = false; assert(!setRecoveryKeyMintEnvironment(true));
    assert(commands == 4 && sleeps == 100);
    reset(); binder_ks = false; assert(!setRecoveryKeyMintEnvironment(true));
    assert(commands == 4 && sleeps == 100);
}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vold-root', type=Path, required=True)
    args = parser.parse_args()
    text = (args.vold_root / 'Decrypt.cpp').read_text()
    start = text.index('\tbool syncKeystore2DbForDecrypt() {')
    end = text.index('\n\t/* C++ replacement', start)
    with tempfile.TemporaryDirectory(prefix='neo8-stop-test-', dir='/tmp') as directory:
        source = Path(directory) / 'stop.cpp'
        source.write_text(STUBS + text[start:end] + CASES)
        binary = Path(directory) / 'stop'
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', str(source), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True, timeout=5)
    print('PASS: 30 actual-function cases for service stops and stock-property validation.')
    print('Host stubs do not verify Android or phone decryption.')

if __name__ == '__main__':
    main()
