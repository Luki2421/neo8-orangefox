#!/usr/bin/env python3
"""Exercise the actual post-decrypt MTP helper with host service stubs."""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUBS = r'''
#include <cassert>
#include <string>
#include <map>
#include <vector>
#define LOGINFO(...) ((void)0)
#define LOGERR(...) ((void)0)
static bool enabled = true, path_exists = true;
namespace android { namespace base {
bool GetBoolProperty(const std::string& name, bool fallback) {
    assert(name == "twrp.recovery.refresh_mtp_after_decrypt" && !fallback); return enabled;
}
}}
struct TWFunc { static bool Path_Exists(const std::string& p) {
    assert(p == "/data/media/0"); return path_exists;
}};
struct DataManager {
    static std::map<std::string, int> vars;
    static int GetIntValue(const std::string& k) { return vars[k]; }
};
std::map<std::string,int> DataManager::vars;
class TWPartition {
    friend class TWPartitionManager;
    bool Is_Decrypted = true, mounted = true;
    std::string Storage_Path = "/data/media/0";
protected:
    bool Has_Data_Media = true;
public:
    bool Is_Mounted() { return mounted; }
    bool IsUnlocked() const { return Is_Decrypted; }
    void SetScenario(int n) { Is_Decrypted = n != 4; mounted = n != 5; Has_Data_Media = n != 6; }
};
class TWPartitionManager {
public:
    void Refresh(TWPartition* dat);
    bool running = false, stop_ok = true, start_ok = true;
    std::vector<std::string> events;
    bool is_MTP_Enabled() { return running; }
    bool Disable_MTP() { events.push_back("stop"); if (!stop_ok) return false;
        running = false; DataManager::vars["tw_mtp_enabled"] = 0; return true; }
    bool Enable_MTP() { events.push_back("start"); running = start_ok;
        DataManager::vars["tw_mtp_enabled"] = start_ok; return start_ok; }
} PartitionManager;
'''
CASES = r'''
int main() {
    for (int scenario=0; scenario<12; ++scenario) {
        TWPartition dat; dat.SetScenario(scenario); PartitionManager = TWPartitionManager{};
        DataManager::vars = {{"tw_mtp_enabled",1}}; enabled = path_exists = true;
        if (scenario == 1) PartitionManager.running = true;
        if (scenario == 2) enabled = false;
        if (scenario == 3) DataManager::vars["tw_mtp_enabled"] = 0;
        if (scenario == 7) path_exists = false;
        if (scenario == 8) DataManager::vars["fox_use_pass"] = 1;
        if (scenario == 9) { DataManager::vars["fox_use_pass"] = 1; DataManager::vars["pass_open"] = 1; }
        if (scenario == 10) { PartitionManager.running = true; PartitionManager.stop_ok = false; }
        if (scenario == 11) PartitionManager.start_ok = false;
        PartitionManager.Refresh(&dat);
        std::vector<std::string> expected;
#if defined(TW_HAS_MTP) && defined(TW_NO_AUTO_DECRYPT)
        if (scenario == 0 || scenario == 9) expected = {"start"};
        if (scenario == 1) expected = {"stop", "start"};
        if (scenario == 10) expected = {"stop"};
        if (scenario == 11) expected = {"start", "stop"};
#endif
        assert(PartitionManager.events == expected);
        assert(dat.IsUnlocked() == (scenario != 4));
        PartitionManager.events.clear();
        Neo8_Refresh_MTP_After_Decrypt(false);
        assert(PartitionManager.events.empty());
    }
}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    args = parser.parse_args()
    text = (args.recovery_root / 'partitionmanager.cpp').read_text()
    start = text.index('static void Neo8_Refresh_MTP_After_Decrypt(')
    end = text.index('void TWPartitionManager::Post_Decrypt(', start)
    function = text[start:end]
    post = text[end:text.index('void TWPartitionManager::Parse_Users()', end)]
    call_start = post.index('Neo8_Refresh_MTP_After_Decrypt(')
    assert post.index('dat->Storage_Path = bind_path') < post.index('dat->Bind_Mount(false)') < call_start
    call = post[call_start:post.index(';', call_start) + 1]
    wrapper = 'void TWPartitionManager::Refresh(TWPartition* dat) {\n' + call + '\n}\n'
    with tempfile.TemporaryDirectory(prefix='neo8-mtp-') as tmp:
        source = Path(tmp) / 'mtp.cpp'
        source.write_text(STUBS + function + wrapper + CASES)
        for flags in ([], ['-DTW_HAS_MTP'], ['-DTW_NO_AUTO_DECRYPT'], ['-DTW_HAS_MTP', '-DTW_NO_AUTO_DECRYPT']):
            binary = Path(tmp) / ('test' + str(len(flags)) + ''.join(flags))
            subprocess.run(['g++', '-std=c++17', *flags, str(source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)
    print('PASS: MTP starts after unlock, refreshes existing servers, respects disabled/locked states, and reports failures independently of decryption.')

if __name__ == '__main__':
    main()
