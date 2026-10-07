# Reference TWRP versus OrangeFox build 26

Compared the user-supplied `TWRP_Neo8_Fix.img` against the OrangeFox image from
GitHub Actions run 37251003989 (commit 63361b7). Images were parsed as boot-v4
ramdisks; target binaries were inspected, never executed on the analysis host.

| Image | SHA-256 |
| --- | --- |
| TWRP reference | b99a36d62c1d4e17b88ed979acdd87e58e6325b62e5ff4dc3dd116c0e5f92c9d |
| OrangeFox build 26 | 0a6c2904e9a9bf58cdb6038bce24b1363ed6c1ba4d4853d41b75054af1407511 |

The TWRP properties identify `twrp_u9`, `eng.koaan`, SDK 36, dated 2026-04-08.
These identify the recovery build, not the installed ColorOS version. A
comparison of current TWRP-Test source alone does not identify this binary.

## Confirmed findings

- The packaged `system/etc/init/hw/init.rc` and `system/etc/ld.config.txt` are
  identical. The fastboot FunctionFS trigger in the device USB rc is unchanged
  apart from a final newline. Other USB changes concern MTP aliases.
- Both images lack `libfs_mgr_binder.so` despite their lpdumpd requiring it.
  This diagnostic-tool defect cannot by itself distinguish working TWRP from
  failing OrangeFox fastbootd.
- OrangeFox's liblp and libfs_mgr match the pinned device donor blobs exactly.
  Its executables are compiled from OrangeFox sources. The device tree's
  recursive PRODUCT_COPY_FILES includes these same library output paths.
- TWRP has different liblp and libfs_mgr binaries. libfs_mgr in OrangeFox also
  exports OPlus-specific helpers absent from the TWRP reference.
- Disassembly of liblp MetadataBuilder::UpdateBlockDeviceInfo confirms that
  TWRP accepts physical device size >= recorded size. The donor library in
  OrangeFox requires equality. This differs from the compiled source's check.
  No device metadata dump has yet established whether that condition occurs
  on the user's phone. It is not proof of the reported transport failure.

| Library | TWRP SHA-256 | OrangeFox/donor SHA-256 |
| --- | --- | --- |
| liblp.so | fd9bdb4e31261e0ac4bad39a19f28d28fb895ec894224e2a9ca0de101f24f683 | 8f7947f66a75d836787ab495e17b0aef284e67bb8ed507db5f304ae90c093d25 |
| libfs_mgr.so | ec810a7ea2a2a7cf0e3f486d50c1949a14ae7075092d4792e4e0e7cc2891f350 | 42c4003a4fdf10053f3635e6accdf816c141e4affc54c80e171a5b2589a595f2 |

## Build correction and validation boundary

Remove only those two hash-verified overrides from the disposable donor tree
before compilation. fastbootd is a recovery module depending on liblp and
libfs_mgr; their source-built recovery variants must supply these paths.
The image inspector rejects the old donor hashes and missing transitive tool
dependencies before upload. No partition-size limits, metadata, slot selection,
USB mode settings, or on-device block-device links are modified by this fix.

This resolves an identified source/binary packaging inconsistency. It is not
a verified fix for firmware installation. Both libraries are also used by
recovery/decryption, so startup, touch, decryption, and read-only metadata
inspection must be tested before any firmware flashing test. Keep the known
working recovery available. Static inspection does not prove runtime ABI or
flash safety, and the host transport error still lacks a matching device crash
log.
