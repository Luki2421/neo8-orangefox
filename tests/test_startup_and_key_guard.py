#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host tests of extracted, actual source functions with simulated Android services."""
import argparse
from pathlib import Path
import subprocess
import tempfile

def between(text, start, end):
    a = text.index(start)
    return text[a:text.index(end, a)]

STARTUP_STUBS = r'''
#include <cassert>
#include <string>
#include <unistd.h>
#define LOGINFO(...) ((void)0)
#define LOGERR(...) ((void)0)
#define TWRES "/twres/"
#define TW_IS_ENCRYPTED "enc"
#define TW_IS_FBE "fbe"
#define TW_CRYPTO_PWTYPE "pwtype"
#define FOX_ENCRYPTED_DEVICE "fox_enc"
static int decrypt_calls = 0, decrypt_pages = 0;
static int gGuiInitialized = 0;
static std::string loaded_page;
struct DataManager {
    static int GetIntValue(const std::string& key) { return key == "pwtype" ? 2 : 1; }
    static void GetValue(const std::string&, int& value) { value = 1; }
    static void SetValue(const std::string&, const std::string&) {}
    static void SetValue(const std::string&, int) {}
    static std::string GetSettingsStoragePath() { return "/data"; }
    static std::string GetCurrentStoragePath() { return "/data"; }
};
struct Manager {
    void Update_System_Details() {}
    bool Mount_Settings_Storage(bool) { return false; }
} PartitionManager;
struct PageManager {
    static int LoadPackage(const char*, const std::string&, const char* page) {
        loaded_page = page; return 0;
    }
    static void SelectPackage(const char*) {}
};
static void gui_err(const char*) {}
static int gui_startPage(const char* page, int, int) {
    if (std::string(page) == "decrypt") ++decrypt_pages;
    return 0;
}
static int tw_get_default_metadata(const char*) { return 0; }
static void Decrypt_Data() { ++decrypt_calls; }
'''

KEY_STUBS = r'''
#include <cassert>
#include <iostream>
#include <mutex>
#include <string>
#define WARNING 0
#define LOG(x) std::cerr
namespace km {
struct AuthorizationSet {
    int data[1] = {0};
    AuthorizationSet() = default;
    AuthorizationSet(const AuthorizationSet&) = default;
    const int* begin() const { return data; }
    const int* end() const { return data + 1; }
    void append(const int*, const int*) {}
};
}
static const char* kFn_keymaster_key_blob = "keymaster_key_blob";
static std::mutex key_upgrade_lock;
static int original_reads = 0;
static const std::string original_blob = "synthetic-original-key";
static bool readFileToString(const std::string& path, std::string* value) {
    assert(path == "/synthetic/keymaster_key_blob");
    ++original_reads; *value = original_blob; return true;
}
struct KeystoreOperation {
    bool valid, upgraded;
    explicit operator bool() const { return valid; }
    const char* getUpgradedBlob() const { return upgraded ? "synthetic-upgraded-key" : nullptr; }
};
struct Keystore {
    bool valid, upgraded;
    KeystoreOperation begin(std::string& value, const km::AuthorizationSet&, km::AuthorizationSet*) {
        assert(value == original_blob); return {valid, upgraded};
    }
};
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    parser.add_argument('--vold-root', type=Path, required=True)
    args = parser.parse_args()
    recovery, vold = args.recovery_root, args.vold_root
    page = between((recovery / 'twrp.cpp').read_text(),
                   'static void Decrypt_Page(', '\nstatic void process_fastbootd_mode(')
    gui = between((recovery / 'gui/gui.cpp').read_text(),
                  'extern "C" int gui_loadResources(', '\nextern "C" int gui_loadCustomResources(')
    manager = (recovery / 'partitionmanager.cpp').read_text()
    pos = manager.index('DataManager::SetValue(TW_IS_ENCRYPTED, 1);\n#ifndef TW_NO_AUTO_DECRYPT')
    begin = manager.rindex('\t#ifdef TW_INCLUDE_CRYPTO', 0, pos)
    end = manager.index('\n\t\tUpdate_System_Details();', pos)
    startup_block = manager[begin:end]
    key = between((vold / 'KeyStorage.cpp').read_text(),
                  'static KeystoreOperation BeginKeystoreOp(', '\nstatic bool encryptWithKeystoreKey(')
    with tempfile.TemporaryDirectory(prefix='neo8-source-test-') as directory:
        root = Path(directory)
        startup_source = root / 'startup.cpp'
        startup_source.write_text(STARTUP_STUBS + page + gui + '\nvoid StartupSetup() {\n' +
                                  startup_block + '\n}\n' + r'''
int main() {
    StartupSetup();
    Decrypt_Page(false, true);
#ifdef TW_NO_AUTO_DECRYPT
    assert(decrypt_calls == 0 && decrypt_pages == 0);
#else
    assert(decrypt_calls == 1 && decrypt_pages == 1);
#endif
    assert(gui_loadResources() == 0 && gGuiInitialized == 1);
#if defined(TW_NO_AUTO_DECRYPT) || defined(TW_FORCE_STOCK_THEME_ON_BOOT) || defined(TW_OEM_BUILD)
    assert(loaded_page == "main");
#else
    assert(loaded_page == "decrypt");
#endif
}
''')
        for no_auto in (False, True):
            for stock in (False, True):
                for oem in (False, True):
                    flags = ['-DTW_INCLUDE_CRYPTO']
                    for enabled, flag in ((no_auto, 'TW_NO_AUTO_DECRYPT'),
                                          (stock, 'TW_FORCE_STOCK_THEME_ON_BOOT'),
                                          (oem, 'TW_OEM_BUILD')):
                        if enabled:
                            flags += ['-D' + flag]
                    binary = root / 'startup'
                    subprocess.run(['g++', '-std=c++17', *flags, str(startup_source), '-o', str(binary)], check=True)
                    subprocess.run([str(binary)], check=True, timeout=5)
        key_source = root / 'key-guard.cpp'
        key_source.write_text(KEY_STUBS + key + r'''
int main() {
    km::AuthorizationSet params;
    for (bool valid : {false, true}) {
        for (bool upgraded : {false, true}) {
            Keystore store{valid, upgraded};
            auto operation = BeginKeystoreOp(store, "/synthetic", params, params, &params);
            assert(bool(operation) == valid);
            if (valid) assert(bool(operation.getUpgradedBlob()) == upgraded);
        }
    }
    assert(original_reads == 4 && original_blob == "synthetic-original-key");
}
''')
        binary = root / 'key-guard'
        # No persistent-write or key-deletion helper is provided to this harness.
        subprocess.run(['g++', '-std=c++17', str(key_source), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True, timeout=5)
    print('PASS: 8 startup/GUI flag combinations and 4 KeyMint-operation cases.')
    print('These are host tests with service stubs; Android linking and phone behavior are not verified.')

if __name__ == '__main__':
    main()
