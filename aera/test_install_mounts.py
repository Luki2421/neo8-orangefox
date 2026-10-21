#!/usr/bin/env python3
"""Run patched ZIP selection/vendor gate and unmount code with host doubles.

No real mounts or partitions are changed. These regressions model a busy vendor
on payload OTA, mixed ZIP dispatch, nested Wi-Fi mounts, and preserved errno.
Physical OTA/update_engine and on-device service lifetimes require device tests.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

from test_mtp_readiness import function


HARNESS = r'''
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mntent.h>
#include <set>
#include <string>
#include <vector>
#include <cstdarg>

static std::string logs;
static void log_info(const char* format, ...) {
    char buffer[4096]; va_list args; va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args); va_end(args);
    logs += buffer;
}
#define LOGINFO(...) log_info(__VA_ARGS__)
namespace msg { enum { kError }; }
struct Msg {
    Msg(int, const char*) {}
    template<class T> Msg& operator()(const T&) { return *this; }
};
template<class T> void gui_msg(const T&) {}
#define TW_NAS_MOUNT_POINT "/mnt/nas"
#define TW_NAS_MOUNTED "nas"
#define TW_DONT_UNMOUNT_SYSTEM "system"
static int protect_system = 0, data_fallbacks = 0;
struct DataManager {
    static void SetValue(const char*, int) {}
    static void GetValue(const char*, int& value) { value = protect_system; }
};
struct TWFunc {
    static std::string Get_Android_Root_Path() { return "/system"; }
    static void Exec_Cmd(const std::string&) { ++data_fallbacks; }
};
static void fake_sleep(int) {}
#define sleep fake_sleep

static std::set<std::string> mounted;
static std::vector<std::string> attempts;
static std::string mount_table, busy_path, disappeared_path;
static bool open_error = false, read_error = false;
static FILE* fixture_mounts(const char* path, const char* mode) {
    assert(std::string(path) == "/proc/mounts" && std::string(mode) == "r");
    if (open_error) { errno = EACCES; return nullptr; }
    FILE* file = tmpfile(); assert(file);
    assert(fwrite(mount_table.data(), 1, mount_table.size(), file) == mount_table.size());
    rewind(file); return file;
}
static int fixture_ferror(FILE*) { return read_error ? 1 : 0; }
static int fake_umount(const char* path, int flags) {
    // In particular, child cleanup must never request MNT_DETACH/MNT_FORCE.
    assert(flags == 0);
    std::string target(path); attempts.push_back(target);
    if (target == disappeared_path) { mounted.erase(target); errno = EINVAL; return -1; }
    if (target == busy_path) { errno = EBUSY; return -1; }
    if (!mounted.erase(target)) { errno = EINVAL; return -1; }
    return 0;
}
#define setmntent fixture_mounts
#define ferror fixture_ferror
#define umount2 fake_umount

struct TWPartition {
    std::string Mount_Point, Current_File_System = "ext4", Symlink_Mount_Point;
    bool Is_Storage = false; int MTP_Storage_ID = 0;
    bool Is_Mounted() {
        // A mount-table scan is allowed to overwrite errno after umount2.
        errno = ENOENT;
        return mounted.count(Mount_Point);
    }
    bool UnMount(bool Display_Error, int flags = 0);
};
static int vendor_attempts = 0;
static TWPartition vendor;
struct Manager {
    std::string Get_Android_Root_Path() { return "/system"; }
    void Remove_MTP_Storage(int) {}
    bool Is_Mounted_By_Path(const std::string& path) {
        assert(path == "/vendor"); return vendor.Is_Mounted();
    }
    bool UnMount_By_Path(const std::string& path, bool display) {
        assert(path == "/vendor"); ++vendor_attempts;
        return vendor.UnMount(display);
    }
} PartitionManager;

#define UPDATE_BINARY_NAME "META-INF/com/google/android/update-binary"
#define AB_OTA "payload_properties.txt"
struct ZipEntry64 {};
using ZipArchiveHandle = const std::set<std::string>*;
static int FindEntry(ZipArchiveHandle zip, const std::string& name, ZipEntry64*) {
    return zip->count(name) ? 0 : -1;
}
enum zip_type { UNKNOWN_ZIP_TYPE, UPDATE_BINARY_ZIP_TYPE, AB_OTA_ZIP_TYPE, TWRP_THEME_ZIP_TYPE };
'''

MAIN = r'''
static void reset() {
    mounted = {"/vendor"}; attempts.clear(); mount_table.clear();
    busy_path.clear(); disappeared_path.clear(); logs.clear();
    open_error = read_error = false; vendor_attempts = data_fallbacks = protect_system = 0;
    vendor = TWPartition(); vendor.Mount_Point = "/vendor";
}
static void add_mount(const std::string& path) {
    mounted.insert(path); mount_table += "device " + path + " ext4 ro 0 0\n";
}
int main() {
    struct Case { std::set<std::string> entries; zip_type type; };
    const std::vector<Case> cases = {
        {{}, UNKNOWN_ZIP_TYPE}, {{"payload.bin"}, UNKNOWN_ZIP_TYPE},
        {{"ui.xml"}, TWRP_THEME_ZIP_TYPE},
        {{AB_OTA, "payload.bin"}, AB_OTA_ZIP_TYPE},
        {{AB_OTA, "ui.xml"}, AB_OTA_ZIP_TYPE},
        {{UPDATE_BINARY_NAME}, UPDATE_BINARY_ZIP_TYPE},
        {{UPDATE_BINARY_NAME, AB_OTA, "payload.bin"}, UPDATE_BINARY_ZIP_TYPE}
    };
    int count = 0;
    for (const auto& sample : cases) for (int requested : {0, 1})
    for (bool busy : {false, true}) for (bool present : {false, true}) {
        reset(); if (!present) mounted.clear();
        if (busy) busy_path = "/vendor";
        assert(GetInstallZipType(&sample.entries) == sample.type);
        int result = vendor_gate(&sample.entries, requested);
        bool should_unmount = requested && present && sample.type != AB_OTA_ZIP_TYPE;
        assert(vendor_attempts == (should_unmount ? 1 : 0));
        assert(result == (should_unmount && busy ? -1 : 0));
        if (sample.type == AB_OTA_ZIP_TYPE) assert(mounted.count("/vendor") == present);
        ++count;
    }
    reset();
    add_mount("/vendor"); add_mount("/vendor/etc/wifi");
    add_mount("/vendor/etc/wifi/nested"); add_mount("/vendor_dlkm/keep");
    add_mount("/odm/etc/wifi"); add_mount("/tmp/aera-wifi/vendor");
    assert(vendor.UnMount(false));
    assert((attempts == std::vector<std::string>{"/vendor/etc/wifi/nested", "/vendor/etc/wifi", "/vendor"}));
    assert(mounted.count("/vendor_dlkm/keep") && mounted.count("/odm/etc/wifi") && mounted.count("/tmp/aera-wifi/vendor"));

    reset(); add_mount("/vendor/etc/wifi"); busy_path = "/vendor/etc/wifi";
    assert(!vendor.UnMount(false));
    assert((attempts == std::vector<std::string>{busy_path}));
    assert(mounted.count("/vendor") && logs.find("errno=16") != std::string::npos);

    reset(); busy_path = "/vendor";
    assert(!vendor.UnMount(false));
    assert(logs.find("errno=16") != std::string::npos); // not overwritten ENOENT

    reset(); add_mount("/vendor/etc/wifi"); disappeared_path = "/vendor/etc/wifi";
    assert(vendor.UnMount(false));

    reset(); open_error = true; assert(!vendor.UnMount(false)); assert(attempts.empty());
    reset(); read_error = true; assert(!vendor.UnMount(false)); assert(attempts.empty());

    reset(); vendor.Mount_Point = "/odm"; add_mount("/odm"); add_mount("/odm/etc/wifi");
    assert(vendor.UnMount(false));
    assert((attempts == std::vector<std::string>{"/odm/etc/wifi", "/odm"}));

    reset(); vendor.Mount_Point = "/data"; add_mount("/data"); add_mount("/data/keep");
    open_error = true; assert(vendor.UnMount(false));
    assert((attempts == std::vector<std::string>{"/data"})); assert(mounted.count("/data/keep"));
    reset(); vendor.Mount_Point = "/system"; add_mount("/system"); protect_system = 1;
    assert(vendor.UnMount(false)); assert(attempts.empty());
    reset(); mounted.clear(); open_error = true;
    assert(vendor.UnMount(false)); assert(attempts.empty());
    printf("Installer: %d ZIP/vendor cases; nested mounts, busy failures, errno and scope passed\n", count);
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root', type=Path, required=True)
    args = parser.parse_args()
    install = (args.recovery_root / 'twrpinstall/twinstall.cpp').read_text()
    partition = (args.recovery_root / 'partition.cpp').read_text()
    code = function(partition, 'static bool UnmountVendorSubdirectories(')
    code += function(partition, 'bool TWPartition::UnMount(')
    code += function(install, 'static zip_type GetInstallZipType(')
    gate = function(install, 'if (unmount_vendor && package_type != AB_OTA_ZIP_TYPE)')
    code += ('int vendor_gate(ZipArchiveHandle zip, int unmount_vendor) {\n'
             'const zip_type package_type = GetInstallZipType(zip);\n' + gate + 'return 0;\n}\n')
    # The tested selector must also control the real installer's dispatch.
    assert install.count('const zip_type package_type = GetInstallZipType(Zip);') == 1
    assert install.index('GetInstallZipType(Zip);') < install.index('if (unmount_vendor &&')
    for kind in ('UPDATE_BINARY_ZIP_TYPE', 'AB_OTA_ZIP_TYPE', 'TWRP_THEME_ZIP_TYPE'):
        assert f'if (package_type == {kind})' in install
    assert 'if (unmount_vendor) {' not in install
    with tempfile.TemporaryDirectory() as tmp:
        source, binary = Path(tmp) / 'test.cpp', Path(tmp) / 'test'
        source.write_text(HARNESS + code + MAIN)
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                        str(source), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == '__main__':
    main()
