#!/usr/bin/env python3
"""Let AERA's own screen timer and lock screen work on Neo8.

The donor TWRP configuration sets TW_NO_SCREEN_TIMEOUT. In AERA that flag compiles
the whole AeraScreenTimer (Blank, Wake, CheckTimeout) down to no-ops and hides the
screen-timeout setting. The power key then switches the UI to the lock screen but the
backlight never goes off. TW_NO_SCREEN_BLANK stays: the screen is turned off through
brightness 0, not a framebuffer blank.
"""
from pathlib import Path

LINE = 'TW_NO_SCREEN_TIMEOUT := true'


def enable_screen_timeout(device):
    path = Path(device) / 'BoardConfig.mk'
    lines = path.read_text().splitlines()
    kept = [line for line in lines if line.strip() != LINE]
    if len(lines) - len(kept) != 1:
        raise ValueError('Expected exactly one "%s" in the Neo8 BoardConfig' % LINE)
    path.write_text('\n'.join(kept) + '\n')
    return {'removed': LINE, 'kept': 'TW_NO_SCREEN_BLANK'}


def validate_screen_config(device):
    text = (Path(device) / 'BoardConfig.mk').read_text()
    if 'TW_NO_SCREEN_TIMEOUT' in text:
        raise ValueError('TW_NO_SCREEN_TIMEOUT is still set; the screen cannot turn off')
    if 'TW_BRIGHTNESS_PATH' not in text:
        raise ValueError('Screen turn-off needs TW_BRIGHTNESS_PATH')
