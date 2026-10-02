#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compile the actual KeystoreInfo implementation and test it on synthetic DBs."""
import argparse
import hashlib
from pathlib import Path
import sqlite3
import subprocess
import tempfile

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vold-root', type=Path, required=True)
    parser.add_argument('--sqlite-include', type=Path)
    args = parser.parse_args()
    vold = args.vold_root.resolve()
    with tempfile.TemporaryDirectory(prefix='neo8-test-') as directory:
        root = Path(directory)
        include = root / 'include/cutils'
        include.mkdir(parents=True)
        (include / 'multiuser.h').write_text('#include <sys/types.h>\nusing userid_t = unsigned int;\n')
        (root / 'main.cpp').write_text('''#include "KeystoreInfo.hpp"
int main(int argc, char** argv) {
    if (argc != 3) return 2;
    KeystoreInfo info;
    return info.backupDatabase(argv[1], argv[2]) ? 0 : 1;
}
''')
        binary = root / 'snapshot-test'
        command = ['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-Wno-unused-variable',
                   '-I' + str(root / 'include'), '-I' + str(vold)]
        if args.sqlite_include:
            command += ['-I' + str(args.sqlite_include.resolve())]
        command += [str(vold / 'KeystoreInfo.cpp'), str(root / 'main.cpp'),
                    '-l:libsqlite3.so.0', '-o', str(binary)]
        subprocess.run(command, check=True)
        def backup(source, destination, expected=True):
            rc = subprocess.run([str(binary), str(source), str(destination)], timeout=10).returncode
            assert rc == (0 if expected else 1), f'Unexpected snapshot result: {rc}'
        source = root / 'source.sqlite'
        destination = root / 'snapshot.sqlite'
        db = sqlite3.connect(source)
        db.execute('PRAGMA journal_mode=WAL')
        db.execute('PRAGMA wal_autocheckpoint=0')
        db.execute('CREATE TABLE synthetic_keys (value TEXT)')
        db.commit()
        db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
        db.execute("INSERT INTO synthetic_keys VALUES ('entry-only-in-wal')")
        db.commit()
        protected = [source, Path(str(source) + '-wal'), Path(str(source) + '-shm')]
        before = {str(path): digest(path) for path in protected}
        backup(source, destination)
        assert destination.stat().st_mode & 0o777 == 0o600, 'Snapshot permissions are too broad'
        with sqlite3.connect(destination) as snapshot:
            assert snapshot.execute('SELECT value FROM synthetic_keys').fetchall() == [('entry-only-in-wal',)]
            assert snapshot.execute('PRAGMA integrity_check').fetchone() == ('ok',)
        assert before == {str(path): digest(path) for path in protected}, 'Original DB or sidecar changed'
        backup(source, source, False)
        link = root / 'hardlink.sqlite'
        link.hardlink_to(source)
        backup(source, link, False)
        link.unlink()
        link.symlink_to(source)
        backup(source, link, False)
        missing = root / 'missing.sqlite'
        backup(missing, root / 'missing-copy.sqlite', False)
        assert not missing.exists(), 'Missing original database was created'
        bad = root / 'corrupt.sqlite'
        bad.write_bytes(b'synthetic-invalid-database')
        backup(bad, root / 'bad-copy.sqlite', False)
        assert bad.read_bytes() == b'synthetic-invalid-database'
        assert before == {str(path): digest(path) for path in protected}
        db.close()
        backup(source, root / 'without-wal.sqlite')
        print('PASS: WAL contents included, original DB/WAL/SHM unchanged, aliases and invalid inputs refused.')

if __name__ == '__main__':
    main()
