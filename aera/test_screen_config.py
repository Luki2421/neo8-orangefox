#!/usr/bin/env python3
"""Check the screen-timeout flag is removed, on synthetic and (optionally) real configs."""
import argparse
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from screen_config import enable_screen_timeout, validate_screen_config

BASE = 'TW_BRIGHTNESS_PATH := "/x"\nTW_NO_SCREEN_BLANK := true\n%sTW_THEME := portrait_hdpi\n'


def expect_error(action):
    try:
        action()
    except ValueError:
        return
    raise SystemExit('Expected ValueError')


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--device', type=Path, help='prepared device tree to validate')
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        device = Path(tmp)
        (device / 'BoardConfig.mk').write_text(BASE % 'TW_NO_SCREEN_TIMEOUT := true\n')
        expect_error(lambda: validate_screen_config(device))
        enable_screen_timeout(device)
        validate_screen_config(device)
        text = (device / 'BoardConfig.mk').read_text()
        assert 'TW_NO_SCREEN_BLANK := true' in text and 'TW_THEME' in text
        expect_error(lambda: enable_screen_timeout(device))
        (device / 'BoardConfig.mk').write_text(BASE % 'TW_NO_SCREEN_TIMEOUT := true\n' * 2)
        expect_error(lambda: enable_screen_timeout(device))
    if args.device:
        validate_screen_config(args.device)
    print('screen timeout configuration checks passed')
