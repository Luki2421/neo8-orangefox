# Stable SSG TA search path

Mounting stock vendor over the ramdisk vendor directory hides the donor's
`vendor/firmware_mnt/image` files. Preserve the four split TA packages from the
pinned donor in `/system/etc/firmware/neo8-ta`, inside the recovery ramdisk, and
prepend that directory to the donor SSG search configuration. Stock system is
mounted separately at `/system_root`.

The integration copies the existing signed files without modifying their bytes.
It retains the original files and fallback search paths. It does not mount or
write the modem partition and does not change metadata decryption ordering.

Build preparation requires all 40 expected package parts. Final image inspection
checks the configured path, readable regular-file copies and byte equality with
the packaged donor originals. Unit tests cover missing parts, changed contents,
symlinks, conflicting destinations and availability after hiding vendor.

These checks validate packaging; successful credential decryption still requires
a test on the device.
