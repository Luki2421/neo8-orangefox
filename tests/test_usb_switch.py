#!/usr/bin/env python3
"""Compile the production USB handlers with controlled init acknowledgements."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

STUBS = r'''
#include <cassert>
#include <chrono>
#include <cstring>
#include <map>
#include <string>
#include <vector>
#define __unused
#define LOGERR(...) ((void)0)
static bool ordered = true;
static std::string failure;
static std::vector<std::string> events;
static int status = -1, errors = 0;
namespace android { namespace base {
bool GetBoolProperty(const char* key, bool fallback) {
    assert(std::string(key) == "twrp.recovery.ordered_usb_switch" && !fallback);
    return ordered;
}
std::string GetProperty(const char*, const char*) { return "not-ready"; }
bool SetProperty(const char* key, const char* value) {
    std::string event = std::string("set:") + key + "=" + value;
    events.push_back(event);
    return failure != event;
}
bool WaitForProperty(const char* key, const char* value, std::chrono::milliseconds timeout) {
    assert(timeout.count() == 3000);
    std::string event = std::string("wait:") + key + "=" + value;
    events.push_back(event);
    return failure != event;
}
}}
struct DataManager {
    static std::map<std::string, int> values;
    static void SetValue(const char* key, int value) { values[key] = value; }
};
std::map<std::string, int> DataManager::values;
void gui_err(const char*) { ++errors; }
class GUIAction {
public:
    bool simulate = false;
    void operation_start(const char*) { status = -1; }
    void operation_end(int value) { status = value; }
    int enableadb(std::string);
    int enablefastboot(std::string);
};
'''

CASES = r'''
int main() {
    for (const std::string mode : {"adb", "fastboot"}) {
        const std::vector<std::string> expected = {
            "set:sys.usb.config=none", "wait:sys.usb.state=none",
            "wait:init.svc.fastbootd=stopped", "wait:init.svc.adbd=stopped",
            "wait:sys.usb.ffs.ready=0", "set:sys.usb.config=" + mode,
            "wait:init.svc." + std::string(mode == "adb" ? "adbd" : "fastbootd") + "=running",
            "wait:sys.usb.ffs.ready=1", "wait:sys.usb.state=" + mode
        };
        for (int failed = -1; failed < (int)expected.size(); ++failed) {
            ordered = true; events.clear(); errors = 0;
            DataManager::values = {{"tw_enable_adb", mode != "adb"},
                                   {"tw_enable_fastboot", mode != "fastboot"}};
            const auto initial = DataManager::values;
            failure = failed < 0 ? "" : expected[failed];
            GUIAction action;
            int result = mode == "adb" ? action.enableadb("") : action.enablefastboot("");
            if (failed < 0) {
                assert(result == 0 && status == 0 && errors == 0 && events == expected);
                assert(DataManager::values["tw_enable_adb"] == (mode == "adb"));
                assert(DataManager::values["tw_enable_fastboot"] == (mode == "fastboot"));
            } else {
                assert(result == 1 && status == 1 && errors == 1);
                assert(DataManager::values == initial);
                assert(events == std::vector<std::string>(expected.begin(), expected.begin() + failed + 1));
            }
        }
        failure.clear(); events.clear(); ordered = false;
        assert(Neo8_Switch_USB_Mode(mode.c_str()));
        assert(events == std::vector<std::string>({"set:sys.usb.config=none", "set:sys.usb.config=" + mode}));
        events.clear(); ordered = true;
        GUIAction simulated; simulated.simulate = true;
        assert((mode == "adb" ? simulated.enableadb("") : simulated.enablefastboot("")) == 0);
        assert(events.empty());
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    args = parser.parse_args()
    text = (args.recovery_root / 'gui/action.cpp').read_text()
    start = text.index('static bool Neo8_Switch_USB_Mode(')
    end = text.index('int GUIAction::unmapsuperdevices(', start)
    assert '#include <chrono>' in text
    theme = ET.parse(args.recovery_root / 'gui/theme/portrait_hdpi/pages/main.xml')
    page = theme.find('.//page[@name="fastboot"]')
    for mode in ('adb', 'fastboot'):
        buttons = [b for b in page.findall('button')
                   if b.find('action[@function="enable' + mode + '"]') is not None]
        assert len(buttons) == 1
        assert len(buttons[0].findall('action')) == 1, 'UI must not force success after failed switch'
    with tempfile.TemporaryDirectory(prefix='neo8-usb-switch-') as tmp:
        source = Path(tmp) / 'usb.cpp'
        source.write_text(STUBS + text[start:end] + CASES)
        binary = Path(tmp) / 'usb'
        subprocess.run(['g++', '-std=c++17', '-Wall', str(source), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True, timeout=5)
    print('PASS: both USB directions wait for teardown and readiness; all failures stop the sequence, UI reports failure, simulation leaves USB untouched.')


if __name__ == '__main__':
    main()
