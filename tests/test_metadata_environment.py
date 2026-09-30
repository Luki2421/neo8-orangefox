#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Test actual stock-environment preparation before any metadata operation."""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUBS = r'''
#include <cassert>
#include <string>
#include <vector>
#define LOGERR(...) ((void)0)
static std::vector<std::string> events;
static bool hook_result = true;
namespace android { namespace keystore {
bool setRecoveryKeyMintEnvironment(bool) __attribute__((weak));
#ifdef WITH_HOOK
bool setRecoveryKeyMintEnvironment(bool stock) {
    assert(stock); events.push_back("keymint-stock"); return hook_result;
}
#endif
}}
struct Manager {
    bool Mount_By_Path(const std::string& path, bool show_errors) {
        assert(!show_errors); events.push_back(path); return false;
    }
} PartitionManager;
'''
CASES = r'''
static bool attempt() {
    if (!PrepareNeo8StockMetadataEnvironment()) return false;
    events.push_back("metadata-operation"); return true;
}
int main() {
#ifdef WITH_HOOK
    assert(attempt());
    assert((events == std::vector<std::string>{"/system_root","/vendor","keymint-stock","metadata-operation"}));
    events.clear(); hook_result = false; assert(!attempt());
    assert((events == std::vector<std::string>{"/system_root","/vendor","keymint-stock"}));
#else
    assert(!attempt()); assert(events.empty());
#endif
}
'''

TOUCH_STUBS = r'''
#include <cassert>
#include <cstring>
#include <string>
#define LOGINFO(...) ((void)0)
#define PROPERTY_VALUE_MAX 92
static bool configured = true;
static int ready_after = 0, polls = 0;
static unsigned long elapsed = 0;
static int property_get(const char* key, char* value, const char*) {
    std::string name(key), result;
    if (name == "twrp.recovery.touch_service") result = configured ? "vendor.touch-aidl-1" : "";
    else {
        assert(name == "init.svc.vendor.touch-aidl-1");
        result = polls++ >= ready_after ? "running" : "stopped";
    }
    strcpy(value, result.c_str()); return result.size();
}
static int usleep(unsigned int value) { elapsed += value; return 0; }
'''
TOUCH_CASES = r'''
int main() {
    configured = false; Wait_For_Configured_Touch_Service(); assert(polls == 0 && elapsed == 0);
    configured = true; Wait_For_Configured_Touch_Service(); assert(polls == 1 && elapsed == 300000);
    polls = 0; elapsed = 0; ready_after = 10; Wait_For_Configured_Touch_Service();
    assert(polls == 11 && elapsed == 500000);
    polls = 0; elapsed = 0; ready_after = 1000; Wait_For_Configured_Touch_Service();
    assert(polls == 250 && elapsed == 5000000);
}
'''

PARTITION_STUBS = r'''
#include <cassert>
#include <string>
#define LOGINFO(...) ((void)0)
#define TW_KEYMASTER_VERSION_PROP "keymaster.version"
static bool keep = false, manifest = false;
static int unmounts = 0;
struct TWPartition { void UnMount(bool) { ++unmounts; } };
namespace android { namespace base {
static bool GetBoolProperty(const std::string& name, bool fallback) {
    assert(name == "twrp.recovery.keep_runtime_partitions" && !fallback); return keep;
}
static std::string GetProperty(const std::string&, const std::string& fallback) { return fallback; }
static void SetProperty(const std::string&, const std::string& version) { assert(version == "4.x"); }
}}
static std::string KM_Ver_From_Manifest(const std::string&) { return manifest ? "4.x" : ""; }
'''
PARTITION_CASES = r'''
int main() {
    TWPartition partition;
    for (bool k : {false,true}) for (bool m : {false,true}) for (bool present : {false,true}) {
        keep = k; manifest = m; unmounts = 0;
        Process_Keymaster_Version(present ? &partition : nullptr, false);
        assert(unmounts == (present && !keep ? 1 : 0));
    }
}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    args = parser.parse_args()
    text = (args.recovery_root / 'partitionmanager.cpp').read_text()
    start = text.index('static bool PrepareNeo8StockMetadataEnvironment() {')
    function = text[start:text.index('\n#endif', start)]
    # Ensure the production call precedes all metadata attempts in Decrypt_Data.
    body = text[text.index('void TWPartitionManager::Decrypt_Data() {'):]
    assert body.index('if (!PrepareNeo8StockMetadataEnvironment()) return;') < body.index('fscrypt_mount_metadata_encrypted(')
    with tempfile.TemporaryDirectory(prefix='neo8-metadata-test-', dir='/tmp') as directory:
        source = Path(directory) / 'metadata.cpp'
        source.write_text(STUBS + function + CASES)
        for hook in (False, True):
            binary = Path(directory) / ('hook' if hook else 'no-hook')
            flags = ['-DWITH_HOOK'] if hook else []
            subprocess.run(['g++', '-std=c++17', *flags, str(source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)
        main_text = (args.recovery_root / 'twrp.cpp').read_text()
        start = main_text.index('static void Wait_For_Configured_Touch_Service() {')
        end = main_text.index('\nstatic void Decrypt_Page(', start)
        touch_source = Path(directory) / 'touch.cpp'
        touch_source.write_text(TOUCH_STUBS + main_text[start:end] + TOUCH_CASES)
        touch_binary = Path(directory) / 'touch'
        subprocess.run(['g++', '-std=c++17', str(touch_source), '-o', str(touch_binary)], check=True)
        subprocess.run([str(touch_binary)], check=True, timeout=5)
        start = text.index('static inline bool Keep_Runtime_Partitions() {')
        end = text.index('\n#define AVB_MAGIC', start)
        partition_source = Path(directory) / 'partitions.cpp'
        partition_source.write_text(PARTITION_STUBS + text[start:end] + PARTITION_CASES)
        for force in (False, True):
            binary = Path(directory) / ('force' if force else 'normal')
            flags = ['-DTW_FORCE_KEYMASTER_VER'] if force else []
            subprocess.run(['g++', '-std=c++17', *flags, str(partition_source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)
    print('PASS: stock preparation precedes metadata; failed/missing hooks block the operation.')
    print('PASS: 4 touch-service wait cases; the startup thread never queries Binder.')
    print('PASS: 16 automatic Keymaster vendor-unmount cases preserve the opt-in runtime mount.')
    print('Host stubs do not verify Android service readiness or phone decryption.')

if __name__ == '__main__':
    main()
