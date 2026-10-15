#!/usr/bin/env python3
"""Compile the actual patched loop functions with deterministic syscall doubles.

This checks descriptor lifetime, nonzero loop IDs and error cleanup on the host;
it does not claim to mount a phone APEX or test Android decryption.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

HARNESS = r'''
#include <cassert>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <fcntl.h>
#include <linux/fs.h>
#include <linux/loop.h>
#include <map>
#include <regex>
#include <string>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/mount.h>
#include <unistd.h>
#include <vector>
static std::map<int, std::string> opened;
static std::string failure;
static int next_fd, free_calls, mounted;
static bool attached, detached;
static int fail() { errno = EIO; return -1; }
static int fake_open(const char* path, int) {
    int fd = ++next_fd; opened[fd] = path; return fd;
}
static int fake_close(int fd) { assert(opened.erase(fd) == 1); return 0; }
static int fake_fstat(int fd, struct stat* info) {
    assert(opened.count(fd));
    if (failure == "stat") return fail();
    info->st_size = 8192; return 0;
}
// Keep this double so running the unpatched source detects use-after-close.
[[maybe_unused]] static off_t fake_lseek(int fd, off_t, int) {
    assert(opened.count(fd)); return 8192;
}
static int fake_ioctl(int fd, unsigned long op, ...) {
    assert(opened.count(fd));
    va_list ap; va_start(ap, op);
    int result = 0;
    if (op == LOOP_CTL_GET_FREE) {
        ++free_calls;
        result = failure == "no_loop" ? fail() : 7;
    } else if (op == LOOP_SET_FD) {
        assert(opened[fd] == "/dev/block/loop7");
        assert(opened.count(va_arg(ap, int)));
        if (failure == "attach") result = fail();
        else attached = true;
    } else if (op == LOOP_SET_STATUS64) {
        auto info = va_arg(ap, struct loop_info64*);
        assert(info->lo_sizelimit == 8192);
        if (failure == "status") result = fail();
    } else if (op == LOOP_CLR_FD) {
        assert(attached); detached = true;
    } else if (op == BLKFLSBUF && failure == "flush") result = fail();
    va_end(ap); return result;
}
static int fake_mknod(const char* path, mode_t, dev_t dev) {
    assert(std::string(path) == "/dev/block/loop7");
    assert(major(dev) == 7 && minor(dev) == 7);
    return failure == "node" ? fail() : 0;
}
static int fake_mkdir(const char*, mode_t mode) {
    assert(mode == 0755);
    if (failure == "exists") { errno = EEXIST; return -1; }
    return failure == "directory" ? fail() : 0;
}
static int fake_mount(const char* source, const char*, const char*, unsigned long flags, const void*) {
    assert(std::string(source) == "/dev/block/loop7");
    assert(flags == MS_RDONLY); ++mounted;
    return failure == "mount" ? fail() : 0;
}
static size_t fake_strlcpy(char* target, const char* source, size_t size) {
    snprintf(target, size, "%s", source); return strlen(source);
}
namespace TWFunc { static bool Path_Exists(const std::string&) { return false; } }
class twrpApex {
public:
    bool mountApexOnLoopbackDevices(std::vector<std::string>);
    bool loadApexImage(std::string, size_t);
    std::string unzipImage(std::string file) {
        return file == "missing" ? "" : "/tmp/test.apex";
    }
};
#define LOOP_CONTROL "/dev/loop-control"
#define LOOP_BLOCK_DEVICE_DIR "/dev/block/"
#define APEX_BASE "/apex/"
#define LOGERR(...) ((void)0)
#define LOGINFO(...) ((void)0)
#define open fake_open
#define close fake_close
#define fstat fake_fstat
#define lseek fake_lseek
#define ioctl fake_ioctl
#define mknod fake_mknod
#define mkdir fake_mkdir
#define mount fake_mount
#define strlcpy fake_strlcpy
'''

MAIN = r'''
int main() {
    for (const std::string scenario : {"ok", "exists", "no_loop", "node", "stat",
                                      "attach", "status", "flush", "directory", "mount"}) {
        failure = scenario; opened.clear(); next_fd = 10;
        attached = detached = false; free_calls = mounted = 0;
        twrpApex apex;
        const bool result = apex.mountApexOnLoopbackDevices({"missing", "present"});
        assert(result == (scenario == "ok" || scenario == "exists"));
        assert(opened.empty());
        assert(free_calls == 1);
        if (attached && !result) assert(detached);
        if (result) assert(mounted == 1 && !detached);
    }
    puts("APEX loop: 10 descriptor/allocation/cleanup scenarios passed");
}
'''


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--recovery-root', required=True, type=Path)
    args = parser.parse_args()
    source = (args.recovery_root / 'twrpApex.cpp').read_text()
    start = source.index('bool twrpApex::mountApexOnLoopbackDevices(')
    end = source.index('bool twrpApex::Unmount()', start)
    with tempfile.TemporaryDirectory() as tmp:
        test = Path(tmp) / 'test.cpp'
        binary = Path(tmp) / 'test'
        test.write_text(HARNESS + source[start:end] + MAIN)
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                        str(test), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
