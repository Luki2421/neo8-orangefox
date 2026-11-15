#!/usr/bin/env python3
"""Neo8 speaker output for AERA's media features (browser, media player, streams, games).

AERA streams 48 kHz stereo S16 PCM to /system/bin/aera-audio-bridge when that file is
executable, otherwise to its silent null bridge. The Neo8 bridge feeds the stock
AudioReach player (agmplay) through a FIFO after an on-demand bring-up of the audio
DSP. Nothing runs at boot: the bring-up starts with the first media session.
"""
from pathlib import Path
import stat

HERE = Path(__file__).resolve().parent / 'audio'
ASSETS = {
    'system/bin/aera-audio-bridge': ('aera-audio-bridge', 0o755),
    'system/bin/neo8-audio-player.sh': ('neo8-audio-player.sh', 0o755),
    'system/etc/neo8-audio/libneo8agminit.so': ('libneo8agminit.so', 0o644),
}


def expected_files():
    return {path: (HERE / name).read_bytes() for path, (name, _) in ASSETS.items()}


def check_assets():
    files = expected_files()
    bridge = files['system/bin/aera-audio-bridge']
    if not bridge.startswith(b'\x7fELF') or bridge[4] != 2 or bridge[18] != 0xb7:
        raise ValueError('aera-audio-bridge must be an AArch64 ELF64 executable')
    for needle in (b'aera-browser-audio-v1', b'--browser-audio', b'/system/bin/neo8-audio-player.sh'):
        if needle not in bridge:
            raise ValueError('aera-audio-bridge lacks ' + needle.decode())
    shim = files['system/etc/neo8-audio/libneo8agminit.so']
    if not shim.startswith(b'\x7fELF') or b'libneo8agminit.so' not in shim or b'agm_init' not in shim:
        raise ValueError('Unexpected AGM init preload')
    if b'__gpr_cmd_async_send' in shim:
        raise ValueError('Diagnostic GPR tracing must not ship in the image')
    player = files['system/bin/neo8-audio-player.sh'].decode()
    for needle in ('exec chroot "$ROOT"', '/system/etc/neo8-audio/libneo8agminit.so',
                   'MI2S-LPAIF-RX-SECONDARY', 'mount -t "$fs" -o ro'):
        if needle not in player:
            raise ValueError('Audio player script lacks ' + needle)
    for forbidden in ('rm -rf', 'mount -o remount,rw', ' /vendor/lib', 'mount --bind "$STOCK/vendor" /vendor'):
        if forbidden in player:
            raise ValueError('Audio player script must not contain ' + forbidden)
    return files


def configure_audio(device):
    device = Path(device)
    files = check_assets()
    for path, data in files.items():
        target = device / 'prebuilt' / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.chmod(ASSETS[path][1])
    return {'files': sorted(files), 'bridge': '/system/bin/aera-audio-bridge',
            'started_by': 'AERA media sessions (on demand)'}


def validate_audio_image(entries):
    for path, data in check_assets().items():
        entry = entries.get(path)
        if not entry or not stat.S_ISREG(entry['mode']) or entry['data'] != data:
            raise ValueError('Missing or mismatched audio file: ' + path)
    for path in ('system/bin/aera-audio-bridge', 'system/bin/neo8-audio-player.sh'):
        if not entries[path]['mode'] & 0o111:
            raise ValueError('Non-executable audio file: ' + path)
    # Informational: the silent fallback is only installed with AERA's browser jail.
    null = entries.get('system/bin/aera-audio-null-bridge')
    return {'speaker_bridge': True,
            'silent_fallback_present': bool(null and null['data'].startswith(b'\x7fELF'))}
