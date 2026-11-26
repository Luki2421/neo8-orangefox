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
        block = struct.pack('<2h', 12000, 12000) * 960  # 20 ms
        volume = Path('/tmp/aera-audio-volume')
        old_volume = volume.read_bytes() if volume.exists() else None
        try:
            volume.write_text('30')
            deadline = time.monotonic()
            for _ in range(50):  # 1 s paced like the browser sink
                good.sendall(block); deadline += 0.02; time.sleep(max(0, deadline - time.monotonic()))
            volume.write_text('60')  # AERA's volume keys
            for _ in range(10):  # 1 s in 100 ms bursts with jitter, like a decoder
                good.sendall(block * 5); deadline += 0.1
                time.sleep(max(0, deadline - time.monotonic() + (0.03 if _ % 2 else -0.03)))
            time.sleep(0.6); good.close(); time.sleep(0.5)
        finally:
            if old_volume is None:
                volume.unlink(missing_ok=True)
            else:
                volume.write_bytes(old_volume)
    finally:
        proc.terminate(); _, log = proc.communicate(timeout=5)
    assert proc.returncode == 0, log
    assert not fifo.exists(), 'FIFO left behind'
    data = out.read_bytes()
    assert data[:4] == b'RIFF' and data[8:16] == b'WAVEfmt ' and struct.unpack('<I', data[24:28])[0] == 48000
    samples = struct.unpack('<%dh' % ((len(data) - 44) // 2), data[44:44 + (len(data) - 44) // 2 * 2])
    assert len(samples) % 2 == 0
    first = next(i for i, v in enumerate(samples) if v)
    loud = samples[first:first + 192000]
    assert all(loud), 'silence spliced into the stream'
    assert not any(samples[first + 192000:]), 'audio after the stream ended'
    quiet, louder = 12000 * 15728 >> 16, 12000 * 31457 >> 16  # 80 % ceiling x 30 % / 60 %
    assert loud[0] == quiet and loud[-1] == louder, (loud[0], loud[-1])
    assert all(a <= b for a, b in zip(loud[::2], loud[2::2])), 'volume change must ramp, not jump around'
    assert 'client uid' in log and 'rejected' in log and 'accepted' in log, log
    return {'played_samples': len(loud), 'log': log.strip().splitlines()[-1]}


def check_channels(work, bridge):
    """Left-only PCM must stay left at rotation 0/270 and move right at 90."""
    results = {}
    rotation = Path('/tmp/aera-audio-rotation')
    old = rotation.read_bytes() if rotation.exists() else None
    try:
        for degrees in ('0', '270', '90'):
            rotation.write_text(degrees + '\n')
            fifo, out = work / f'rot{degrees}.wav', work / f'rot{degrees}.raw'
            player = work / 'player.py'
            proc = subprocess.Popen([str(bridge), 'bridge', str(fifo), '--accept-uid', str(os.getuid()), '--',
                                     sys.executable, str(player), str(fifo), str(out)],
                                    stderr=subprocess.PIPE, text=True)
            try:
                client = connect(); client.sendall(struct.pack('<4I', 0x41525041, 48000, 2, 16))
                block = struct.pack('<2h', 12000, 0) * 960
                deadline = time.monotonic()
                for _ in range(25):
                    client.sendall(block); deadline += 0.02; time.sleep(max(0, deadline - time.monotonic()))
                time.sleep(0.4); client.close(); time.sleep(0.3)
            finally:
                proc.terminate(); proc.communicate(timeout=5)
            data = out.read_bytes()[44:]
            frames = struct.unpack('<%dh' % (len(data) // 2), data[:len(data) // 4 * 4])
            left = sum(1 for v in frames[0::2] if v)
            right = sum(1 for v in frames[1::2] if v)
            results[degrees] = (left, right)
    finally:
        if old is None:
            rotation.unlink(missing_ok=True)
        else:
            rotation.write_bytes(old)
    assert results['0'][0] == 24000 and results['0'][1] == 0, results
    assert results['270'] == results['0'], results
    assert results['90'] == (0, 24000), results
    return results


def check_announcement(work, bridge):
    """The readiness line must wait for a slow player, not appear at once."""
    slow = work / 'slow_player.py'
    slow.write_text('import sys,time\nf=open(sys.argv[1],"rb");f.read(44)\ntime.sleep(1.5)\n'
                    'while True:\n    d=f.read(3840)\n    if not d: break\n    time.sleep(0.02)\n')
    fifo = work / 'slow.wav'
    proc = subprocess.Popen([str(bridge), 'bridge', str(fifo), '--accept-uid', str(os.getuid()), '--',
                             sys.executable, str(slow), str(fifo)], stderr=subprocess.PIPE, text=True)
    time.sleep(2.5)
    proc.terminate()
    _, log = proc.communicate(timeout=5)
    line = next(l for l in log.splitlines() if 'player is consuming at' in l)
    seconds = float(line.rsplit(' ', 1)[1].rstrip('s'))
    assert 1.4 <= seconds <= 2.2, line
    return seconds


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
        print('channels:', check_channels(work, work / 'bridge'))
        print('announcement after slow start:', check_announcement(work, work / 'bridge'))
    print('Neo8 audio checks passed:', report['files'])


if __name__ == '__main__':
    main()
