# Neo8 lpdumpd packaging

The phone log reports that `/system/bin/lpdumpd` exits before startup because
`libfs_mgr_binder.so` is unavailable. The existing OrangeFox relink list copies
`lpdump`, `lpdumpd`, and `liblpdump`, but omits this dependency. `libfs_mgr.so`
is a different library and must not be renamed to substitute for it.

The Neo8 build now explicitly builds and relinks the matching platform
`libfs_mgr_binder.so` and `libsnapshot.so`. The first inspection-gated build
(run 37587049561) compiled successfully but rejected the image because
`liblpdump.so` also required the missing `libsnapshot.so`; that image was not
uploaded for phone testing. Image inspection checks the recursive ARM64 ELF dependency
closure of lpdump, lpdumpd, and fastbootd in the packaged system library paths.
A missing tool or dependency fails inspection before the image upload step.
This is a file/dependency check, not a runtime linker or Binder test.

The `NO_SUCH_DEVICE_a` message comes from the alternate init file descriptor
for retrofit devices. The log shows init proceeds to execute lpdumpd; its fatal
failure is the missing library. No block-device alias or partition is changed.

After a successful build and inspection, the phone test is `lpdump --all` from
the recovery ADB shell. Keep the readout for reviewing super metadata, group
limits, and allocation. No resize, delete, format, or firmware flash is needed
for that diagnostic test.

This fixes a confirmed diagnostic-tool packaging defect. It does not establish
or claim to fix the separate fastbootd resize/transport failure.
