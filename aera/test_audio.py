#!/usr/bin/env python3
"""Checks the Neo8 audio assets and the bridge protocol (bridge built for the host)."""
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audio_config import ASSETS, check_assets, configure_audio, validate_audio_image

SOURCE = Path(__file__).resolve().parent / 'audio/src/aera-audio-bridge.c'
NAME = b'\0aera-browser-audio-v1'


def connect():
    for _ in range(100):
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            s.connect(NAME)
            return s
        except OSError:
            s.close()
            time.sleep(0.02)
    raise AssertionError('bridge did not listen')


def check_protocol(work):
    cc = shutil.which('cc') or shutil.which('gcc')
    assert cc, 'host C compiler needed'
    bridge = work / 'bridge'
    subprocess.run([cc, '-O1', '-Wall', '-Werror', '-o', str(bridge), str(SOURCE), '-lm'], check=True)
    fifo, out = work / 'stream.wav', work / 'played.raw'
    # Fake player: reads the FIFO at real-time speed, like agmplay.
    player = work / 'player.py'
    player.write_text('import sys,time\nf=open(sys.argv[1],"rb");o=open(sys.argv[2],"wb")\n'
                      'o.write(f.read(44));o.flush()\nwhile True:\n'
                      '    d=f.read(3840)\n    if not d: break\n    o.write(d);o.flush();time.sleep(0.02)\n')
    proc = subprocess.Popen([str(bridge), 'bridge', str(fifo), '--accept-uid', str(os.getuid()), '--',
                             sys.executable, str(player), str(fifo), str(out)],
                            stderr=subprocess.PIPE, text=True)
    try:
        # Wrong hello is rejected, a correct one is streamed.
        bad = connect(); bad.sendall(struct.pack('<4I', 0x41525041, 44100, 2, 16))
        bad.settimeout(2)
        try:
            assert bad.recv(1) == b''
        except ConnectionResetError:
            pass  # closed with unread data: also a rejection
        bad.close()
        good = connect(); good.sendall(struct.pack('<4I', 0x41525041, 48000, 2, 16))
        block = struct.pack('<2h', 12000, 12000) * 960  # 20 ms, paced like AERA's clients
        deadline = time.monotonic()
        for _ in range(50):
            good.sendall(block); deadline += 0.02; time.sleep(max(0, deadline - time.monotonic()))
        time.sleep(0.5); good.close(); time.sleep(0.5)
    finally:
        proc.terminate(); _, log = proc.communicate(timeout=5)
    assert proc.returncode == 0, log
    assert not fifo.exists(), 'FIFO left behind'
    data = out.read_bytes()
    assert data[:4] == b'RIFF' and data[8:16] == b'WAVEfmt ' and struct.unpack('<I', data[24:28])[0] == 48000
    samples = struct.unpack('<%dh' % ((len(data) - 44) // 2), data[44:44 + (len(data) - 44) // 2 * 2])
    assert len(samples) % 2 == 0
    loud = sum(1 for s in samples if s)
    # Default gain is 25 % of full scale; frames are never split.
    assert set(s for s in samples if s) == {12000 * 64 >> 8}, set(samples)
    assert loud == 96000, loud
    first = next(i for i, s in enumerate(samples) if s)
    assert all(samples[first:first + loud]), 'silence spliced into a paced stream'
    assert 'client uid' in log and 'rejected' in log and 'accepted' in log, log
    return {'played_samples': loud, 'log': log.strip().splitlines()[-1]}


def main():
    files = check_assets()
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        report = configure_audio(work / 'device')
        for path, (_, mode) in ASSETS.items():
            target = work / 'device/prebuilt' / path
            assert target.read_bytes() == files[path] and target.stat().st_mode & 0o777 == mode
        entries = {p: {'mode': 0o100755, 'data': d} for p, d in files.items()}
        entries['system/bin/aera-audio-null-bridge'] = {'mode': 0o100755, 'data': b'\x7fELF'}
        validate_audio_image(entries)
        for broken in ('system/bin/aera-audio-bridge', 'system/bin/neo8-audio-player.sh'):
            damaged = dict(entries); damaged.pop(broken)
            try:
                validate_audio_image(damaged)
            except ValueError:
                pass
            else:
                raise AssertionError('validator accepted image without ' + broken)
        print('protocol:', check_protocol(work))
    print('Neo8 audio checks passed:', report['files'])


if __name__ == '__main__':
    main()
