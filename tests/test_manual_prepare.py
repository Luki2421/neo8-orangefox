#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise the actual GUI preparation handler with host service stubs."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

def check_automatic_prompt(root):
    page = ET.parse(root / 'gui/theme/portrait_hdpi/pages/main.xml').find(".//page[@name='main']")
    actions = [a for a in page.findall('action')
               if any(c.get('function') == 'page' and c.text == 'neo8_prepare_decrypt'
                      for c in a.findall('action'))]
    assert len(actions) == 1
    action = actions[0]
    def enabled(state):
        for cond in action.findall('condition'):
            same = state.get(cond.get('var1'), '') == cond.get('var2')
            if not (not same if cond.get('op') == '!=' else same):
                return False
        return True
    ready = dict(first_start='0', fox_use_pass='0', neo8_manual_decrypt='1', tw_is_encrypted='1')
    ready['property.twrp.neo8.auto_prompt_ready'] = '1'
    assert enabled(ready)
    for key, value in [('first_start', '1'), ('fox_use_pass', '1'),
                       ('neo8_manual_decrypt', '0'), ('tw_is_encrypted', '0'),
                       ('property.twrp.neo8.auto_prompt_ready', '0'),
                       ('property.twrp.neo8.auto_prompt_done', '1')]:
        assert not enabled(dict(ready, **{key: value})), key
    steps = [(c.get('function'), c.text) for c in action.findall('action')]
    assert steps == [('set', 'property.twrp.neo8.auto_prompt_done=1'), ('page', 'neo8_prepare_decrypt')]
    # The session guard is set before navigation, so cancellation or failure
    # followed by returning to main cannot immediately reopen the prompt.
    ready['property.twrp.neo8.auto_prompt_done'] = '1'
    assert not enabled(ready)
    vars_text = (root / 'gui/theme/portrait_hdpi/resources/vars.xml').read_text()
    assert 'name="neo8_auto_prompt_done"' not in vars_text  # no persistent preference

def check_startup_and_retry(root):
    startup = (root / 'twrp.cpp').read_text()
    reset = startup.index('property_set("twrp.neo8.startup_complete", "0")')
    complete = startup.index('property_set("twrp.neo8.startup_complete", "1")')
    assert reset < startup.index('gui_init();') < startup.index('gui_loadResources();')
    assert startup.index('process_recovery_mode(adb_bu_fifo,') < complete
    assert startup.index('TWFunc::Setup_Verity_Forced_Encryption();') < complete
    assert complete < startup.index('// Launch the main GUI')
    gui = (root / 'gui/gui.cpp').read_text()
    start = gui.index('extern "C" int gui_startPage(')
    end = gui.index('extern "C" void set_scale_values', start)
    function = gui[start:end]
    stubs = r'''
#include <cassert>
#include <cstring>
#include <map>
#include <string>
#define TW_NO_AUTO_DECRYPT
#define TW_OEM_BUILD
#define PROPERTY_VALUE_MAX 92
static int gGuiInitialized = 1, prompts = 0;
static std::map<std::string, std::string> properties;
static int property_get(const char* k, char* v, const char* def) {
    std::string s = properties.count(k) ? properties[k] : def;
    strcpy(v, s.c_str()); return s.size();
}
static int property_set(const char* k, const char* v) { properties[k] = v; return 0; }
static void enter_page() {
    if (properties["twrp.neo8.auto_prompt_ready"] == "1" &&
        properties["twrp.neo8.auto_prompt_done"] != "1") {
        properties["twrp.neo8.auto_prompt_done"] = "1";
        ++prompts;
    }
}
struct PageManager { static void SelectPackage(const char*) { enter_page(); } };
struct Input { void init() {} } input_handler;
static int runPages(const char*, int) { enter_page(); return 0; }
'''
    cases = r'''
int main() {
    // Loading or selecting a theme before startup completion cannot prepare data.
    for (int i=0; i<3; ++i) { enter_page(); gui_startPage("main", 1, 0); }
    assert(prompts == 0);
    property_set("twrp.neo8.startup_complete", "1");
    gui_startPage("main", 1, 0);
    assert(prompts == 1);
    // Selection, reload and returning from cancellation retain the session guard.
    for (int i=0; i<3; ++i) { enter_page(); gui_startPage("main", 1, 0); }
    assert(prompts == 1);
}
'''
    pm = (root / 'partitionmanager.cpp').read_text()
    begin = pm.index('auto try_metadata_environment = [&]() -> bool {')
    begin = pm.index('\n', begin) + 1
    guard = pm[begin:pm.index('\t\t\t\tif (!android::vold::fscrypt_mount_metadata_encrypted(', begin)]
    retry = r'''
#include <cassert>
#include <string>
#define LOGINFO(...) ((void)0)
struct Partition {
    std::string Decrypted_Block_Device;
    bool mounted = false;
    int de_calls = 0;
    bool Is_Mounted() { return mounted; }
    void Decrypt_FBE_DE() { ++de_calls; }
};
static bool reuse(Partition* Decrypt_Data) {
''' + guard + r'''
    return false;
}
int main() {
    for (bool has_mapping: {false, true}) for (bool mounted: {false, true}) {
        Partition p; p.mounted = mounted;
        if (has_mapping) p.Decrypted_Block_Device = "/dev/block/mapper/userdata";
        assert(reuse(&p) == (has_mapping && mounted));
        assert(p.de_calls == int(has_mapping && mounted));
    }
}
'''
    with tempfile.TemporaryDirectory(prefix='neo8-lifecycle-') as tmp:
        for name, code in [('startup', stubs + function + cases), ('retry', retry)]:
            source = Path(tmp) / (name + '.cpp')
            binary = Path(tmp) / name
            source.write_text(code)
            subprocess.run(['g++', '-std=c++17', str(source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)

STUBS = r'''
#include <cassert>
#include <map>
#include <string>
using std::string;
#define __unused
#define TW_NO_AUTO_DECRYPT
#define TW_CRYPTO_PWTYPE "tw_crypto_pwtype"
#define TW_IS_FBE "tw_is_fbe"
struct DataManager {
    static std::map<string, int> vars;
    static void SetValue(string k, int v) { vars[k]=v; }
    static void SetValue(string, const char*) {}
    static int GetIntValue(string k) { return vars[k]; }
};
std::map<string,int> DataManager::vars;
struct TWPartition {
    string Key_Directory="/metadata/vold/metadata_encryption";
    bool mounted=true;
    bool Is_Mounted() { return mounted; }
} data;
struct Manager {
    bool present=true;
    int calls=0, password_type=3, fbe=1;
    TWPartition* Find_Partition_By_Path(string) { return present ? &data : nullptr; }
    void Decrypt_Data() {
        calls++;
        DataManager::vars[TW_CRYPTO_PWTYPE]=password_type;
        DataManager::vars[TW_IS_FBE]=fbe;
    }
} PartitionManager;
static int errors=0;
void gui_err(const char*) { errors++; }
struct GUIAction {
    bool simulate=false;
    int result=-1, starts=0, simulations=0;
    void operation_start(const char*) { starts++; }
    void simulate_progress_bar() { simulations++; }
    void operation_end(int s) { result=s; }
    int neo8preparedecrypt(std::string);
};
'''

CASES = r'''
void reset() {
    DataManager::vars.clear();
    PartitionManager=Manager{};
    data=TWPartition{};
    errors=0;
}
int main() {
    for (int type: {1,2,3}) {
        reset(); PartitionManager.password_type=type;
        GUIAction action; action.neo8preparedecrypt("");
        assert(action.result==0 && action.starts==1 && PartitionManager.calls==1);
        assert(DataManager::GetIntValue(TW_CRYPTO_PWTYPE)==type && errors==0);
    }
    for (int scenario=0; scenario<6; scenario++) {
        reset();
        if (scenario==0) PartitionManager.present=false;
        if (scenario==1) data.Key_Directory="";
        if (scenario==2) PartitionManager.password_type=-1;
        if (scenario==3) PartitionManager.password_type=0;
        if (scenario==4) data.mounted=false;
        if (scenario==5) PartitionManager.fbe=0;
        GUIAction action; action.neo8preparedecrypt("");
        assert(action.result==1 && errors==1);
        if (scenario<2) assert(PartitionManager.calls==0);
    }
    reset(); GUIAction simulated; simulated.simulate=true;
    simulated.neo8preparedecrypt("");
    assert(simulated.result==0 && simulated.simulations==1 && PartitionManager.calls==0);
}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    args = parser.parse_args()
    check_automatic_prompt(args.recovery_root)
    check_startup_and_retry(args.recovery_root)
    text = (args.recovery_root / 'gui/action.cpp').read_text()
    start = text.index('int GUIAction::neo8preparedecrypt(')
    end = text.index('int GUIAction::decrypt(', start)
    actual = text[start:end]
    with tempfile.TemporaryDirectory(prefix='neo8-manual-test-') as directory:
        source = Path(directory) / 'test.cpp'
        binary = Path(directory) / 'test'
        source.write_text(STUBS + actual + CASES)
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Wno-unused-parameter',
                        str(source), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    print('PASS: 3 credential types, 6 preparation failures and simulation. No credential submission API is available to this handler.')
    print('PASS: automatic prompt eligibility, welcome/password guards, and no repeated prompt after returning to main.')
    print('PASS: no prompt during startup theme loading; one prompt after completion; reuse mounted metadata on retry.')

if __name__ == '__main__':
    main()
