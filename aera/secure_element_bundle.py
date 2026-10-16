#!/usr/bin/env python3
"""Restore the complete reference SE launcher, private ABI and TA dependencies.

The donor checkout lacks files added to the tested release image. Overlaying
only filenames already in that checkout leaves init pointing at a missing script.
Keep this allowlist separate from generic AERA system libraries and recovery ELF.
"""
import hashlib
from pathlib import Path
import stat

REFERENCE_SHA = '80b0cbb97a969b4df1ec4c9e51056c4a6433e3536364e987666186f3c5fd7573'
LAUNCHER = 'vendor/odm/bin/hw/neo8-secure-element-launcher.sh'
SERVICE_RC = 'vendor/odm/etc/init/secure_element-service-tms.rc'
PRIVATE_LIBS = (
    'android.hardware.secure_element-V1-ndk.so',
    'android.hardware.secure_element@1.0.so',
    'android.hardware.secure_element@1.1.so',
    'android.hardware.secure_element@1.2.so',
    'libGPMTEEC_vendor.so', 'libGPTEE_vendor.so', 'libdmabufheap.so',
    'libminkdescriptor.so', 'libtms_seimpl_tee.so',
    'tms-cos-dl-common.so', 'tms-cos-dl-tee.so',
)
# These are also used by the private COS libraries, including via dlopen.
TRANSPORT_LIBS = (
    '7816-3-T1.so', 'android.hardware.nfc-V1-ndk.so',
    'android.hardware.nfc@1.0.so', 'libtms_log_record.so',
    'nfc_nci.thn31nfc.tms.so', 'tms-cos-dl-ree.so',
    'vendor.tms.tmsnfc_aidl-V1-ndk.so',
)
TA_IDS = (
    '05B04A44-BF30-42DF-9E2F-B366B980ED19',
    '32552B22-89FE-42B4-8A45-A0C4E2DB0326',
    'F2AC60D6-C56F-4C25-8724-1CD66BA01D10',
    'FD719D50-FFFB-11EB-9A03-0242AC130003',
)
BUNDLE_FILES = (
    LAUNCHER, SERVICE_RC, 'system/etc/neo8-ta_config.json',
    *(f'vendor/odm/lib64/neo8_se_current/{name}' for name in PRIVATE_LIBS),
    *(f'vendor/odm/lib64/{name}' for name in TRANSPORT_LIBS),
    *(f'system/etc/neo8-firmware/{ta}.{ext}' for ta in TA_IDS
      for ext in ('mdt', *(f'b{i:02}' for i in range(9)))),
)


def verified_reference(image, entries):
    if hashlib.sha256(image).hexdigest() != REFERENCE_SHA:
        raise ValueError('Wrong Secure Element reference image')
    for path in BUNDLE_FILES:
        entry = entries.get(path)
        if not entry or not stat.S_ISREG(entry['mode']):
            raise ValueError('Missing regular reference bundle file: ' + path)
    return entries


def restore_secure_element_bundle(device, entries):
    restored = []
    for path in BUNDLE_FILES:
        entry = entries[path]
        if not stat.S_ISREG(entry['mode']):
            raise ValueError('Not a regular bundle file: ' + path)
        target = Path(device) / 'prebuilt' / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_symlink():
            raise ValueError('Unexpected bundle destination symlink: ' + path)
        target.write_bytes(entry['data'])
        target.chmod(entry['mode'] & 0o777)
        restored.append(path)
    return restored


def validate_secure_element_bundle(entries, reference):
    """Exact file/mode checks; not an Android linker namespace or hardware test."""
    for path in BUNDLE_FILES:
        actual, expected = entries.get(path), reference[path]
        if not actual or not stat.S_ISREG(actual['mode']):
            raise ValueError('Missing regular Secure Element bundle file: ' + path)
        if actual['data'] != expected['data']:
            raise ValueError('Secure Element reference mismatch: ' + path)
        if (actual['mode'] & 0o777) != (expected['mode'] & 0o777):
            raise ValueError('Secure Element mode mismatch: ' + path)
    if b'/odm/bin/hw/neo8-secure-element-launcher.sh' not in entries[SERVICE_RC]['data']:
        raise ValueError('Secure Element service does not use the private ABI launcher')
    if not entries[LAUNCHER]['mode'] & 0o111:
        raise ValueError('Secure Element launcher is not executable')
    return {'reference_sha256': REFERENCE_SHA, 'files_verified': len(BUNDLE_FILES)}
