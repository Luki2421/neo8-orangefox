# Recovery fastboot HAL domain

The recovery-only fastboot AIDL module has an explicit init service label of
`hal_fastboot_default`. The Neo8 ramdisk uses the recovery domain for its service
manager and other HALs. Build integration now assigns the same recovery domain
to this one recovery-only fastboot HAL, retaining its system UID, group,
executable and AIDL interface declaration.

The integration validates the original init service before modifying either its
rc or the Soong manifest installation. It leaves fastbootd's own service label,
normal Android HAL modules, USB descriptors and the SELinux policy unchanged.
The generated image must contain exactly one matching fastboot HAL init service
with the configured domain.

Source tests validate the change. Device validation should first use only
`fastboot devices` and `fastboot getvar is-userspace`, without flashing partitions.
