#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise actual startup dispatch, storage registration and media setup together."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

STUBS = r'''
#include <cassert>
#include <cstring>
#include <map>
#include <string>
#include <vector>
using std::string;
#define LOGINFO(...) ((void)0)
#define LOGERR(...) ((void)0)
#define TW_IS_ENCRYPTED "tw_is_encrypted"
#define TW_IS_DECRYPTED "tw_is_decrypted"
#define TW_IS_FBE "tw_is_fbe"
#define TW_CRYPTO_PWTYPE "tw_crypto_pwtype"
#define TW_INTERNAL_PATH "tw_internal_path"
#define EXPAND(x) x
#define TW_EXTERNAL_STORAGE_PATH "/external_sd"
static int mounts=0, unmounts=0, probes=0, decrypts=0, full_setups=0;
static bool datamedia=false;
struct DataManager {
    static std::map<string,string> vars;
    static void SetValue(string k, string v) { vars[k]=v; }
    static void SetValue(string k, int v) { vars[k]=std::to_string(v); }
    static int GetIntValue(string k) { return vars[k].empty()?0:std::stoi(vars[k]); }
};
std::map<string,string> DataManager::vars;
struct TWFunc {
    static bool Path_Exists(string) { ++probes; return true; }
    static string to_string(int n) { return std::to_string(n); }
};
struct Exclusions { void add_absolute_dir(string) {} };
struct TWPartition {
    string Mount_Point="/data", Storage_Name="Data", Storage_Path, Symlink_Path, Symlink_Mount_Point;
    string Key_Directory="/metadata/vold/metadata_encryption";
    bool Has_Data_Media=false, Is_Storage=false, Is_Settings_Storage=false;
    bool Can_Be_Encrypted=false, Is_Encrypted=false, Is_Decrypted=false, Is_FBE=false;
    unsigned int MTP_Storage_ID=0;
    Exclusions backup_exclusions, wipe_exclusions;
    void ExcludeAll(string) {}
    void Make_Dir(string path, bool) { assert(path=="/sdcard" || path=="/emmc"); }
    bool Mount(bool) { ++mounts; return true; }
    void UnMount(bool) { ++unmounts; }
    void Setup_Data_Media(bool probe_media=true);
} data, external;
struct TWPartitionManager {
    bool present=true;
    std::vector<TWPartition*> Partitions{&external, &data};
    TWPartition* Find_Partition_By_Path(string p) { assert(p=="/data"); return present?&data:nullptr; }
    void Setup_Settings_Storage_Partition(TWPartition* p) {
        DataManager::SetValue("tw_storage_path", p->Storage_Path);
        DataManager::SetValue("tw_settings_path", p->Storage_Path);
    }
    void Setup_Fstab_Partitions(bool) { ++full_setups; }
    void Setup_Manual_Data();
    // Mirrors Decrypt_Data's entry guard, without any crypto implementation.
    void ExplicitDecrypt() { if (data.Is_Encrypted && !data.Is_Decrypted) ++decrypts; }
} PartitionManager;
struct Startup { bool fastboot=false; bool Get_Fastboot_Mode() { return fastboot; } } startup;
'''
CASES = r'''
void reset() {
    DataManager::vars.clear(); data=TWPartition{}; external=TWPartition{};
    external.Is_Storage=true; external.Mount_Point="/external_sd";
    PartitionManager=TWPartitionManager{}; startup.fastboot=false;
    mounts=unmounts=probes=decrypts=full_setups=0; datamedia=false;
}
int main() {
    reset(); StartupDispatch();
#ifdef TW_SKIP_POST_GUI_FSTAB_SETUP
#if defined(TW_INCLUDE_CRYPTO) && defined(TW_NO_AUTO_DECRYPT)
    assert(data.Is_Encrypted && !data.Is_Decrypted && data.Is_FBE);
    assert(datamedia && data.Has_Data_Media && data.Is_Storage && data.Is_Settings_Storage);
    assert(data.Storage_Path=="/data/media" && data.Symlink_Mount_Point=="/sdcard");
    assert(data.MTP_Storage_ID>65536 && data.MTP_Storage_ID!=external.MTP_Storage_ID);
    assert(DataManager::GetIntValue("neo8_manual_decrypt")==1);
    assert(DataManager::GetIntValue(TW_IS_ENCRYPTED)==1 && DataManager::GetIntValue(TW_IS_FBE)==1);
    assert(DataManager::GetIntValue(TW_IS_DECRYPTED)==0 && DataManager::GetIntValue(TW_CRYPTO_PWTYPE)==-1);
    assert(DataManager::vars["tw_storage_path"]=="/data/media");
    assert(decrypts==0);
    PartitionManager.ExplicitDecrypt(); assert(decrypts==1);
#else
    assert(!data.Is_Encrypted && !data.Is_Storage);
#endif
    assert(full_setups==0);
#else
    assert(full_setups==1);
#endif
    assert(mounts==0 && unmounts==0 && probes==0);
    reset(); startup.fastboot=true; StartupDispatch();
    assert(full_setups==0 && !data.Is_Encrypted && !data.Is_Storage);
    reset(); PartitionManager.present=false; PartitionManager.Setup_Manual_Data();
    assert(!data.Is_Encrypted && !data.Is_Storage);
    reset(); data.Key_Directory.clear(); PartitionManager.Setup_Manual_Data();
    assert(!data.Is_Encrypted && !data.Is_Storage);
    // Existing call sites still probe /media/0 with the default argument.
    reset(); data.Setup_Data_Media();
    assert(mounts==1 && unmounts==1 && probes==1 && data.Storage_Path=="/data/media/0");
}
'''

def extract(text, start, end):
    a=text.index(start)
    return text[a:text.index(end,a)]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recovery-root',type=Path,required=True)
    root=parser.parse_args().recovery_root
    startup=extract((root/'twrp.cpp').read_text(), '#ifndef TW_SKIP_POST_GUI_FSTAB_SETUP', '\n\t// Load up all the resources')
    setup=extract((root/'partitionmanager.cpp').read_text(), 'void TWPartitionManager::Setup_Manual_Data()', 'void TWPartitionManager::Setup_Fstab_Partitions(')
    media=extract((root/'partition.cpp').read_text(), 'void TWPartition::Setup_Data_Media(', 'void TWPartition::Find_Real_Block_Device(')
    theme=ET.parse(root/'gui/theme/portrait_hdpi/pages/advanced.xml')
    items=[x for x in theme.findall('.//listitem') if x.find('./action[@function="page"]') is not None and x.find('./action[@function="page"]').text=='neo8_prepare_decrypt']
    assert len(items)==1
    assert {(c.get('var1'),c.get('var2')) for c in items[0].findall('condition')}=={('neo8_manual_decrypt','1'),('tw_is_encrypted','1')}
    with tempfile.TemporaryDirectory(prefix='neo8-storage-') as directory:
        source=Path(directory)/'test.cpp'; binary=Path(directory)/'test'
        source.write_text(STUBS+media+setup+'\nvoid StartupDispatch() {\n'+startup+'\n}\n'+CASES)
        for skip in (False,True):
            for crypto in (False,True):
                for manual in (False,True):
                    for media_fix in (False,True):
                        flags=[f'-D{name}' for active,name in [(skip,'TW_SKIP_POST_GUI_FSTAB_SETUP'),(crypto,'TW_INCLUDE_CRYPTO'),(manual,'TW_NO_AUTO_DECRYPT'),(media_fix,'OF_FIX_DECRYPTION_ON_DATA_MEDIA')] if active]
                        subprocess.run(['g++','-std=c++17','-Wall','-Wextra',*flags,str(source),'-o',str(binary)],check=True)
                        subprocess.run([str(binary)],check=True,timeout=5)
    print('PASS: 16 real startup/media flag combinations; menu reachable, unique MTP IDs, no startup mounts or crypto, fastboot/missing-data guards, legacy media probing preserved.')
    print('Host stubs do not validate device KeyMint or actual password decryption.')

if __name__=='__main__':
    main()
