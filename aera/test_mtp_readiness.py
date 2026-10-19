#!/usr/bin/env python3
"""Execute patched MTP lifecycle methods with host FunctionFS/property doubles.

The regression is stale readiness between endpoint close and the next descriptor
write. This checks publication ordering and failed writes, not physical USB I/O.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile


HARNESS = r'''
#include <cassert>
#include <string>
#include <vector>
#include <sys/types.h>
static std::string ready = "1", failure;
static std::vector<std::string> events;
static int writes;
static int property_set(const char* name, const char* value) {
    assert(std::string(name) == "sys.usb.ffs.mtp.ready");
    ready = value; events.push_back(ready); return 0;
}
namespace android { namespace base {
[[maybe_unused]] static bool SetProperty(const std::string& name, const std::string& value) {
    return property_set(name.c_str(), value.c_str()) == 0;
}
}}
struct desc_v1 { char bytes[32]; } mtp_desc_v1, ptp_desc_v1;
struct desc_v2 { char bytes[64]; } mtp_desc_v2, ptp_desc_v2;
static char mtp_strings[16];
static ssize_t fake_write(int fd, const void*, size_t size) {
    assert(fd == 7 && ready == "0");
    ++writes;
    if (failure == "descriptors" && size != sizeof(mtp_strings)) return -1;
    if (failure == "v1" && size == sizeof(desc_v2)) return -1;
    if (failure == "strings" && size == sizeof(mtp_strings)) return -1;
    return size;
}
struct Endpoint {
    bool open = true;
    void reset() {
        // This models the point where FunctionFS can cease to be ACTIVE.
        assert(ready == "0"); open = false; events.push_back("close");
    }
};
class MtpFfsHandle {
public:
    Endpoint mIntr, mBulkIn, mBulkOut, mControl;
    void closeEndpoints();
    void closeConfig();
};
#define TEMP_FAILURE_RETRY(x) (x)
#define MTPE(...) ((void)0)
#define write fake_write
'''

MAIN = r'''
int main() {
    for (bool ptp : {false, true}) {
        for (const std::string scenario : {"ok", "v1", "descriptors", "strings"}) {
            failure = scenario; ready = "1"; events.clear(); writes = 0;
            bool result = writeDescriptors(7, ptp);
            bool expected = scenario == "ok" || scenario == "v1";
            assert(result == expected);
            assert(ready == (expected ? "1" : "0"));
            assert(events.front() == "0");
            if (expected) assert(events.size() == 2 && events.back() == "1");
            else assert(events.size() == 1);
        }
    }
    MtpFfsHandle handle;
    ready = "1"; events.clear();
    handle.closeEndpoints();
    assert(!handle.mIntr.open && !handle.mBulkIn.open && !handle.mBulkOut.open);
    assert(ready == "0" && events.front() == "0");
    // While the server waits 800ms, ADB becoming ready cannot satisfy both
    // init predicates: sys.usb.ffs.ready=1 AND sys.usb.ffs.mtp.ready=1.
    bool adb_ready = true;
    assert(!(adb_ready && ready == "1"));
    handle.closeConfig();
    assert(!handle.mControl.open && ready == "0");
    failure = "ok"; writes = 0;
    assert(writeDescriptors(7, false));
    assert(adb_ready && ready == "1");
    // Also cover a direct control-only close without closeEndpoints first.
    MtpFfsHandle control_only;
    ready = "1"; control_only.closeConfig();
    assert(ready == "0" && !control_only.mControl.open);
    puts("MTP: 8 descriptor cases, endpoint/control teardown and reconnect gap passed");
}
'''


def function(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end] + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    args = parser.parse_args()
    root = args.recovery_root / 'mtp/ffs'
    descriptors = (root / 'MtpDescriptors.cpp').read_text()
    handles = (root / 'MtpFfsHandle.cpp').read_text()
    code = function(descriptors, 'bool writeDescriptors(int fd, bool ptp)')
    code += function(handles, 'void MtpFfsHandle::closeEndpoints()')
    code += function(handles, 'void MtpFfsHandle::closeConfig()')
    with tempfile.TemporaryDirectory() as tmp:
        source, binary = Path(tmp) / 'test.cpp', Path(tmp) / 'test'
        source.write_text(HARNESS + code + MAIN)
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                        str(source), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    assert '.bcdVersion = htole16(0x0100),' in descriptors


if __name__ == '__main__':
    main()
