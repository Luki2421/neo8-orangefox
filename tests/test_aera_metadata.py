#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Test reference metadata-first ordering, fallback, touch and runtime mounts."""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUBS = r'''
#include <cassert>
#include <string>
#include <vector>
#define LOGINFO(...) ((void)0)
static std::vector<std::string> events;
static bool hook_result = true, first_result = true, retry_result = true, allow_retry = true;
static std::string environment = "recovery";
namespace android {
namespace keystore {
bool setRecoveryKeyMintEnvironment(bool) __attribute__((weak));
#ifdef WITH_HOOK
bool setRecoveryKeyMintEnvironment(bool stock) {
    events.push_back(stock ? "keymint-stock" : "keymint-restore"); return hook_result;
}
#endif
}
namespace base {
std::string GetProperty(const std::string& key, const std::string&) {
    assert(key == "twrp.keymint.metadata_env"); return environment;
}
bool GetBoolProperty(const std::string& key, bool) {
    assert(key == "twrp.keymint.allow_stock_retry"); return allow_retry;
}
void SetProperty(const std::string& key, const std::string& value) {
    assert(key == "twrp.keymint.metadata_env"); environment = value;
}
}}
static bool Mount_By_Path(const std::string& path, bool show_errors) {
    assert(!show_errors); events.push_back(path); return true;
}
'''
CASES = r'''
int main() {
    for (bool stock : {false, true}) for (bool success : {false, true})
    for (bool retry : {false, true}) for (bool hook_ok : {false, true})
    for (bool retry_ok : {false, true}) {
        events.clear(); environment = stock ? "stock" : "recovery";
        first_result = success; allow_retry = retry;
        hook_result = hook_ok; retry_result = retry_ok;
        attempt();
        std::vector<std::string> expected{"metadata-current"};
#ifdef WITH_HOOK
        bool switched = !success && !stock && retry && hook_ok;
        if (!success && !stock && retry) {
            expected.insert(expected.end(), {"/system_root", "/vendor", "keymint-stock"});
            if (hook_ok) expected.push_back("metadata-retry");
        }
        bool ready = success || (switched && retry_ok);
        if (!ready && (stock || switched)) expected.push_back("keymint-restore");
        assert(environment == (ready && (stock || switched) ? "stock" : "recovery"));
#else
        assert(environment == (stock ? "stock" : "recovery"));
#endif
        assert(events == expected);
    }
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
    configured = false; assert(!Wait_For_Configured_Touch_Service()); assert(polls == 0 && elapsed == 0);
    configured = true; assert(Wait_For_Configured_Touch_Service()); assert(polls == 1 && elapsed == 300000);
    polls = 0; elapsed = 0; ready_after = 10; assert(Wait_For_Configured_Touch_Service());
    assert(polls == 11 && elapsed == 500000);
    polls = 0; elapsed = 0; ready_after = 1000; assert(!Wait_For_Configured_Touch_Service());
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
    body = text[text.index('void TWPartitionManager::Decrypt_Data() {'):]
    body = body[:body.index('void TWPartitionManager::Setup_Settings_Storage_Partition')]
    # A mandatory environment switch here depended on keystore2 before /data
    # metadata was mounted and could prevent the credential page entirely.
    assert 'PrepareNeo8StockMetadataEnvironment' not in body
    before_mount = body[:body.index('fscrypt_mount_metadata_encrypted(')]
    assert 'setRecoveryKeyMintEnvironment(' not in before_mount
    assert 'Existing metadata key is unavailable; refusing key generation' in before_mount
    start = body.index('\t\t\tstd::string metadata_environment =')
    end = body.index('\n#endif', start)
    actual = body[start:end]
    with tempfile.TemporaryDirectory(prefix='neo8-metadata-test-') as directory:
        source = Path(directory) / 'metadata.cpp'
        source.write_text(STUBS + '''
static void attempt() {
    int attempts = 0;
    auto try_metadata_environment = [&]() {
        events.push_back(attempts == 0 ? "metadata-current" : "metadata-retry");
        return attempts++ == 0 ? first_result : retry_result;
    };
''' + actual + '\n}\n' + CASES)
        for hook in (False, True):
            binary = Path(directory) / ('hook' if hook else 'no-hook')
            flags = ['-DWITH_HOOK'] if hook else []
            subprocess.run(['g++', '-std=c++17', *flags, str(source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)
        main_text = (args.recovery_root / 'twrp.cpp').read_text()
        assert main_text.index('if (Wait_For_Configured_Touch_Service() &&') < main_text.index('DataManager::GetValue(AERA_COMPATIBILITY_DEVICE, Aera_Current_Device)')
        assert main_text.count('if (Wait_For_Configured_Touch_Service() &&') == 1
        start = main_text.index('static bool Wait_For_Configured_Touch_Service() {')
        end = main_text.index('\nstatic void Print_Prop(', start)
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
    print('PASS: 64 metadata environment cases; current environment first, stock retry only after failure, existing-key guard retained.')
    print('PASS: 4 touch-service wait cases; the startup thread never queries Binder.')
    print('PASS: 16 automatic Keymaster vendor-unmount cases preserve the opt-in runtime mount.')
    print('Host stubs do not verify Android service readiness or phone decryption.')

if __name__ == '__main__':
    main()
