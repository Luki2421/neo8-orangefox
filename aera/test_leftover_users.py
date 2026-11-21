#!/usr/bin/env python3
"""Run the patched Neo8 leftover-process release with a fake /proc.

The Wi-Fi loader starts cnss-daemon and the Wi-Fi HAL from the stock vendor
under /tmp/aera-wifi as detached children, so AERA's init-service scan cannot
stop them and a ZIP install fails with "Unable to unmount
/tmp/aera-wifi/vendor: Device or resource busy". The patched code ends such
leftovers before the unmount. Nothing real is signalled here: /proc and kill()
are replaced by a fixture tree and a recorder.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

from test_mtp_readiness import function


HARNESS = r'''
#include <cassert>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <map>
#include <set>
#include <signal.h>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include "aera_image_flash_mounts.hpp"

static std::string logs, progress, PROC;
static void log_line(const char* format, ...) {
    char buffer[4096]; va_list args; va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args); va_end(args);
    logs += buffer;
}
#define LOGINFO(...) log_line(__VA_ARGS__)
#define LOGERR(...) log_line(__VA_ARGS__)
struct DataManager {
    static void SetValue(const std::string&, const std::string& value) { progress = value; }
};
static std::map<std::string, std::string> properties;
static std::vector<std::string> stopped;
namespace android { namespace base {
std::string GetProperty(const std::string& key, const std::string& fallback) {
    auto it = properties.find(key);
    return it == properties.end() ? fallback : it->second;
}
bool ReadFileToString(const std::string& path, std::string* out) {
    std::ifstream in(path);
    if (!in) return false;
    std::stringstream buffer; buffer << in.rdbuf(); *out = buffer.str();
    return true;
}
std::string Trim(const std::string& s) {
    size_t a = s.find_first_not_of(" \n\t"), b = s.find_last_not_of(" \n\t");
    return a == std::string::npos ? "" : s.substr(a, b - a + 1);
}
}}
static bool stop_result = true;
bool StopDynamicService(const std::string& name) { stopped.push_back(name); return stop_result; }
static int fake_usleep(unsigned) { return 0; }
#define usleep fake_usleep
// pid -> SIGTERM: ends on any signal, SIGKILL: only on SIGKILL, 0: never
static std::map<long, int> dies_on;
static std::vector<std::pair<long, int>> signals;
static int fake_kill(pid_t pid, int signal) {
    signals.push_back({pid, signal});
    auto it = dies_on.find(pid);
    if (it != dies_on.end() && it->second && (it->second == SIGTERM || signal == SIGKILL))
        system(("rm -rf " + PROC + "/" + std::to_string(pid)).c_str());
    return 0;
}
#define kill fake_kill
static DIR* fake_opendir(const char* path) {
    std::string p(path);
    if (p.compare(0, 5, "/proc") == 0) p = PROC + p.substr(5);
    return opendir(p.c_str());
}
#define opendir fake_opendir
'''

MAIN = r'''
static const dev_t VENDOR = makedev(0xfd, 5);
static void process(long pid, const char* comm, bool uses, int dies) {
    std::string dir = PROC + "/" + std::to_string(pid);
    system(("mkdir -p " + dir + "/fd").c_str());
    std::ofstream(dir + "/comm") << comm << "\n";
    std::ofstream(dir + "/maps") << "7f00-7f10 r-xp 00000000 "
        << (uses ? "fd:05" : "fd:09") << " 42 /tmp/aera-wifi/vendor/bin/" << comm << "\n";
    // Dangling links: stat() fails, so only maps decide.
    symlink("/nonexistent/exe", (dir + "/exe").c_str());
    symlink("/nonexistent/cwd", (dir + "/cwd").c_str());
    symlink("/nonexistent/root", (dir + "/root").c_str());
    dies_on[pid] = dies;
}
static void reset(const std::string& tmp) {
    system(("rm -rf " + tmp + "/proc && mkdir -p " + tmp + "/proc/self").c_str());
    PROC = tmp + "/proc";
    logs.clear(); progress.clear(); properties.clear(); stopped.clear();
    signals.clear(); dies_on.clear(); stop_result = true;
}
int main(int, char** argv) {
    const std::string tmp = argv[1];
    const std::set<dev_t> devices = {VENDOR};

    reset(tmp);  // nothing busy: no service stop, no signal
    process(300, "unrelated", false, SIGTERM);
    properties["init.svc.wpa_supplicant"] = "running";
    assert(Neo8StopLeftoverFlashUsers(devices));
    assert(stopped.empty() && signals.empty());

    reset(tmp);  // the reported case: detached Wi-Fi helpers
    process(400, "cnss-daemon", true, SIGTERM);
    process(401, "android.hardwar", true, SIGTERM);
    process(402, "unrelated", false, SIGTERM);
    process(1, "init", true, 0);
    process(getpid(), "recovery", true, 0);
    system(("mkdir -p " + PROC + "/12ab").c_str());
    properties["init.svc.wpa_supplicant"] = "running";
    assert(Neo8StopLeftoverFlashUsers(devices));
    assert(stopped == std::vector<std::string>{"wpa_supplicant"});
    assert(signals.size() == 2);
    for (auto& s : signals) assert((s.first == 400 || s.first == 401) && s.second == SIGTERM);
    assert(logs.find("Stopping cnss-daemon (pid 400)") != std::string::npos);

    reset(tmp);  // ignores SIGTERM, ends on SIGKILL; Wi-Fi service not running
    process(500, "stubborn", true, SIGKILL);
    properties["init.svc.wpa_supplicant"] = "stopped";
    assert(Neo8StopLeftoverFlashUsers(devices));
    assert(stopped.empty());
    assert(signals.size() == 2 && signals[0].second == SIGTERM && signals[1].second == SIGKILL);

    reset(tmp);  // cannot be ended: refuse, report it, never unmount
    process(600, "stuck", true, 0);
    assert(!Neo8StopLeftoverFlashUsers(devices));
    assert(progress == "Process keeps a dynamic partition busy: stuck");
    assert(logs.find("stuck (pid 600) still keeps a dynamic partition busy") != std::string::npos);

    reset(tmp);  // the Wi-Fi service refuses to stop: nothing is signalled
    process(700, "cnss-daemon", true, SIGTERM);
    properties["init.svc.wpa_supplicant"] = "running";
    stop_result = false;
    assert(!Neo8StopLeftoverFlashUsers(devices));
    assert(signals.empty());

    reset(tmp);  // no backing devices: nothing to release
    process(800, "cnss-daemon", true, SIGTERM);
    assert(Neo8StopLeftoverFlashUsers({}));
    assert(signals.empty());
    puts("leftover flash users: all cases passed");
    return 0;
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    args = parser.parse_args()
    manager = (args.recovery_root / 'partitionmanager.cpp').read_text()
    # The default argument '= {}' defeats brace matching; slice to the next function.
    begin = manager.index('bool QuiesceDynamicPartitionUsers(')
    quiesce = manager[begin:manager.index('void RestartDynamicServices(', begin)]
    # Leftovers are ended after the init services and before any unmount.
    services = quiesce.index('if (!StopDynamicService(service.name)) return false;')
    leftovers = quiesce.index('if (!Neo8StopLeftoverFlashUsers(backing_devices)) return false;')
    unmount = quiesce.index('Unmounting image-flash dependency')
    assert services < leftovers < unmount
    assert '#include <signal.h>' in manager
    code = function(manager, 'bool ProcessUsesFlashDevices(')
    code += function(manager, 'std::vector<pid_t> Neo8LeftoverFlashUsers(')
    code += function(manager, 'std::string Neo8ProcessName(')
    code += function(manager, 'bool Neo8StopLeftoverFlashUsers(')
    # Route the scanned /proc paths into the fixture tree.
    code = code.replace('"/proc/" + std::to_string', 'PROC + "/" + std::to_string')
    assert 'PROC + "/"' in code
    with tempfile.TemporaryDirectory() as tmp:
        source, binary = Path(tmp) / 'test.cpp', Path(tmp) / 'test'
        source.write_text(HARNESS + 'namespace {\n' + code + '}\n' + MAIN)
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                        '-I', str(args.recovery_root), str(source), '-o', str(binary)], check=True)
        subprocess.run([str(binary), tmp], check=True)


if __name__ == '__main__':
    main()
