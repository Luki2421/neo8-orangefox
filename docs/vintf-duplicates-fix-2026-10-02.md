# Duplicate device HAL declarations

The supplied startup logcat reports a fatal device VINTF merge error:
`android.hardware.gatekeeper.IGatekeeper/default` is declared in both
`/vendor/etc/vintf/manifest.xml` and its Gatekeeper fragment. ServiceManager
cannot load the device manifest. Weaver also aborts during registration;
the log alone does not establish that this is its only failure.

The pinned donor tree reproduces that conflict. Reviewing its combined
vendor/ODM manifests exposes another duplicate:
`android.se.omapi.ISecureElementService/default`, expressed once using
`fqname` and once using `interface`/`instance`.

## Change

During device preparation, remove only the redundant vendor Gatekeeper and
OMAPI fragments. Retain the main vendor manifest (including target-level and
sepolicy) and the ODM OMAPI fragment byte for byte. Require the expected
single instance, version 1 (including the implicit default), and unchanged
declaration structure before removing either file. Unexpected extra services
or changed versions stop preparation.

Check AIDL service identities across the combined vendor and ODM manifests
both during preparation and in the final ramdisk. Normalize both instance
syntaxes and treat different AIDL versions of the same instance as a
collision, matching the identity comparison in
[Android 16 libvintf](https://android.googlesource.com/platform/system/libvintf/+/refs/tags/android-16.0.0_r1/HalManifest.cpp).
This check covers this device's AIDL collisions, not all libvintf schema,
HIDL, compatibility-matrix, APEX or SKU rules. Overrides require review.

## Validation and limits

- 15 service configuration tests and 6 ramdisk reader tests pass locally.
- The checker rejects the original pinned donor manifests with the observed
  Gatekeeper error. After removing the two exact duplicates, their 27 AIDL
  service identities are unique.
- Regression cases cover a Gatekeeper duplicate, vendor/ODM OMAPI collision,
  differing AIDL versions, the ODM symlink, and refusal to discard an extra
  interface or a changed version.
- No crypto keys, firmware, SELinux rules or on-device partitions are changed
  by the preparation script. These changes affect the generated recovery.

The available `recovery.log` is from an older October 1 build. The new logcat
ends at startup log export rather than a recorded manual decrypt attempt.
The log also reports a missing NFC library and CryptoEng TA load failures;
those remain separate runtime findings, not claimed fixed by this change.
Full image build and a phone test are required before claiming decryption
works. Raw phone logs are not included in this repository.

## Follow-up: recovery's Make-installed OMAPI fragment

Build 36972545258 compiled successfully, but final image inspection caught
the OMAPI duplicate again. `bootable/recovery/Android.mk` explicitly requires
`se_omapi.xml`, whose prebuilt module installs into vendor independently of
the donor copy. Removing only the donor fragment was insufficient.

Device preparation now removes that one required-module entry after verifying
the retained ODM declaration. It preserves the `se_omapi` binary, init rc,
linked libraries and `TW_INCLUDE_OMAPI` flag. The image collision check stays
enabled. All 18 service configuration tests pass, including GNU Make
evaluation with OMAPI enabled/disabled and refusal to modify unexpected
source or a missing ODM declaration. The modified block was also evaluated
from the actual pinned recovery Android.mk. A new full build is required.
