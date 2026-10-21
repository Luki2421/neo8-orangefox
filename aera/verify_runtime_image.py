#!/usr/bin/env python3
"""Check the packed image, not just the device sources, before retaining it."""
import argparse
import json
from pathlib import Path
import stat
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'scripts'))
from boot_ramdisk import read_recovery
from runtime_config import validate_runtime_files
from secure_element_bundle import verified_reference, validate_secure_element_bundle
from ota_sideload import validate_ota_sideload
from regdb import validate_regdb
from wifi_config import validate_wifi_image
from runtime_fixes import validate_runtime_fixes

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--reference', required=True)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    _, entries = read_recovery(args.image)
    files = {path: e['data'] for path, e in entries.items() if stat.S_ISREG(e['mode'])}
    report = validate_runtime_files(files)
    reference = verified_reference(*read_recovery(args.reference))
    report['secure_element_bundle'] = validate_secure_element_bundle(entries, reference)
    report['wifi'] = validate_wifi_image(entries, reference)
    report['ota_sideload'] = validate_ota_sideload(entries, reference)
    report['regdb'] = validate_regdb(entries)
    report['usb_and_strongbox'] = validate_runtime_fixes(entries, reference)
    etc = entries.get('etc', {})
    if not stat.S_ISLNK(etc.get('mode', 0)) or etc['data'] != b'/system/etc':
        raise ValueError('Expected /etc -> /system/etc for logd profiles')
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('Packed recovery runtime configuration verified')
