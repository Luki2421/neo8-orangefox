#!/usr/bin/env python3
"""Exercise production no-lock detection and parsers on synthetic protector files."""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUBS = r'''
#include <cassert>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <fstream>
#include <filesystem>
#include <string>
#include <vector>
#include <sys/stat.h>
using userid_t = uint32_t;
namespace fs = std::filesystem;
static std::string sandbox, active_handle="123456789abcdef0";
static int unwrap_calls=0;
static std::string map_path(const std::string& p) {
    return p.rfind("/data/",0)==0 ? sandbox+p : p;
}
static int mapped_stat(const char* p, struct stat* s) { return stat(map_path(p).c_str(),s); }
static int mapped_lstat(const char* p, struct stat* s) { return lstat(map_path(p).c_str(),s); }
namespace android {
namespace base {
bool ReadFileToString(const std::string& path, std::string* out) {
    struct stat st{};
    if (mapped_stat(path.c_str(),&st)!=0 || !S_ISREG(st.st_mode)) return false;
    std::ifstream in(map_path(path),std::ios::binary);
    if (!in) return false;
    *out=std::string(std::istreambuf_iterator<char>(in),{}); return !in.bad();
}}
namespace vold { bool pathExists(const std::string& p) {
    struct stat st{}; return mapped_stat(p.c_str(),&st)==0;
}}
}
#define stat(p,s) mapped_stat(p,s)
#define lstat(p,s) mapped_lstat(p,s)
struct KeystoreInfo { std::string getHandle(userid_t) { return active_handle; } };
struct password_data_struct {
    int password_type; unsigned char scryptN,scryptR,scryptP;
    int salt_len; void* salt; int handle_len; void* password_handle;
};
struct weaver_data_struct { unsigned char version; int slot; };
bool Decrypt_User_Synth_Pass(userid_t, const std::string& password) {
    assert(password=="!"); ++unwrap_calls; return true;
}
static void put(const std::string& p,const std::string& data) {
    fs::create_directories(fs::path(map_path(p)).parent_path());
    std::ofstream out(map_path(p),std::ios::binary); out.write(data.data(),data.size());
}
static std::string be32(uint32_t x) {
    return {char(x>>24),char(x>>16),char(x>>8),char(x)};
}
static const std::string directory="/data/system_de/0/spblob/";
static void reset() {
    fs::remove_all(sandbox+"/data"); fs::create_directories(sandbox+directory);
    active_handle="123456789abcdef0"; unwrap_calls=0;
}
static std::string blob() { std::string b(64,'x'); b[0]=3; b[1]=0; return b; }
static void protector(const std::string& prefix) {
    put(prefix+".spblob",blob()); put(prefix+".secdis",std::string(16384,'s'));
}
'''

CASES = r'''
int main(int argc,char** argv) {
    assert(argc==2); sandbox=argv[1]; std::string filename;
    for (int variant=0;variant<3;++variant) {
        reset(); active_handle=std::string(16-variant,'a');
        protector(directory+std::string(variant,'0')+active_handle);
        assert(Get_Password_Type(0,filename)==0);
        assert(Decrypt_User(0,"!") && unwrap_calls==1);
        assert(!Decrypt_User(0,"1234") && unwrap_calls==1);
    }
    for (int bad=0;bad<11;++bad) {
        reset(); std::string prefix=directory+active_handle; protector(prefix);
        if (bad==0) active_handle="";
        if (bad==1) active_handle="0";
        if (bad==2) active_handle="../bad";
        if (bad==3) fs::remove(map_path(prefix+".spblob"));
        if (bad==4) put(prefix+".spblob",std::string(1,'x'));
        if (bad==5) { auto b=blob(); b[1]=1; put(prefix+".spblob",b); }
        if (bad==6) put(prefix+".pwd","");
        if (bad==7) fs::create_directory(map_path(prefix+".pwd"));
        if (bad==8) fs::remove(map_path(prefix+".secdis"));
        if (bad==9) put(prefix+".secdis","short");
        if (bad==10) { fs::remove(map_path(prefix+".spblob")); fs::create_symlink("missing",map_path(prefix+".spblob")); }
        assert(Get_Password_Type(0,filename)==-1);
        assert(!Decrypt_User(0,"!") && unwrap_calls==0);
    }
    for (auto pair : {std::pair<int,int>{1,2},{2,1},{3,3},{4,1},{-1,-1},{9,-1}}) {
        reset(); std::string prefix=directory+active_handle; protector(prefix);
        std::string pwd=be32(pair.first)+std::string("\x0b\x03\x01",3)+be32(16)+std::string(16,'s')+be32(0);
        put(prefix+".pwd",pwd);
        assert(Get_Password_Type(0,filename)==pair.second);
        assert(!Decrypt_User(0,"!") && unwrap_calls==0);
        // Every truncation of PasswordData is rejected without out-of-bounds reads.
        for (size_t n=0;n<pwd.size();++n) {
            put(prefix+".pwd",pwd.substr(0,n)); password_data_struct data{};
            assert(!Get_Password_Data(directory,active_handle,&data));
        }
    }
    reset(); protector(directory+active_handle);
    put(directory+active_handle+".weaver",std::string(1,'\1')+be32(258));
    assert(Get_Password_Type(0,filename)==0);
    weaver_data_struct wd{};
    assert(Get_Weaver_Data(directory,active_handle,&wd) && wd.version==1 && wd.slot==258);
    put(directory+active_handle+".weaver",std::string(1,'\1')+be32(0));
    assert(Get_Weaver_Data(directory,active_handle,&wd) && wd.slot==0);
    put(directory+active_handle+".weaver",std::string(1,'\1'));
    assert(!Get_Weaver_Data(directory,active_handle,&wd));
    assert(Get_Password_Type(0,filename)==-1);
    reset(); protector("/data/system_de/0/"+active_handle);
    fs::remove_all(sandbox+directory);
    assert(Get_Password_Type(0,filename)==0); // donor's direct system_de layout
    CheckDefaultToken();
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vold-root', type=Path, required=True)
    args = parser.parse_args()
    text = (args.vold_root / 'Decrypt.cpp').read_text()
    def part(start, end):
        a = text.index(start)
        return text[a:text.index(end, a + len(start))]
    functions = part('bool Get_Spblob_Data(', '/* C++ replacement for')
    functions += part('bool Get_Password_Data(', '/* C++ replacement for')
    functions += part('bool Get_Weaver_Data(', 'namespace android {')
    functions += part('static bool Neo8_Default_Protector(', 'bool Decrypt_User_Synth_Pass(')
    functions += part('extern "C" int Get_Password_Type(', 'extern "C" bool Decrypt_User(')
    # Compile the real default branch; the unrelated Gatekeeper path follows it.
    functions += part('extern "C" bool Decrypt_User(', '\tif (stat("/data/system_de/0/spblob"') + '\treturn false;\n}\n'
    token = part('unsigned char password_token[PASSWORD_TOKEN_SIZE]', '\tif (Password != "!")')
    literal = part('\t\tstd::string defpassword = "default-password";', '\n\t}')
    functions += '\nvoid CheckDefaultToken() {\n#define PASSWORD_TOKEN_SIZE 32\n' + token + literal
    functions += r'''
        assert(memcmp(password_token,"default-password",16)==0);
        for (int i=16;i<32;++i) assert(password_token[i]==0);
    }
'''
    sp = part('bool Decrypt_User_Synth_Pass(', 'extern "C" int Get_Password_Type(')
    assert 'copySqliteDb()' not in sp
    assert sp.index('Neo8_Default_Protector(') < sp.index('memcpy(password_token,')
    with tempfile.TemporaryDirectory(prefix='neo8-no-lock-') as tmp:
        source = Path(tmp) / 'test.cpp'
        source.write_text(STUBS + functions + CASES)
        binary = Path(tmp) / 'test'
        subprocess.run(['g++', '-std=c++17', '-fsanitize=undefined', '-fno-sanitize-recover=all',
                        str(source), '-o', str(binary)], check=True)
        subprocess.run([str(binary), tmp + '/data-root'], check=True, timeout=10,
                       stdout=subprocess.DEVNULL)
    print('PASS: active no-lock protectors, padded handles, malformed metadata, PIN/pattern/password guards, exact 32-byte default token and Weaver big-endian slot parsing.')


if __name__ == '__main__':
    main()
