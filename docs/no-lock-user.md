# Android 16 user without a screen credential

The Neo8 startup preparation can now unlock user 0 automatically when the active
synthetic-password protector represents an empty lockscreen credential. A PIN,
pattern, or password continues to use the credential page.

Android 16 stores no PasswordData file for a newly created empty-credential
protector. Detection requires a nonzero handle from the locksettings database,
the matching LSKF-based spblob, and matching Weaver or secdiscardable metadata.
An existing PasswordData file, unknown credential type, missing protector,
unreadable metadata, or malformed file is not accepted as an empty credential.
The existing metadata mapping and DE initialization must succeed first.

The default token is `default-password` padded with zero bytes to 32 bytes,
matching AOSP. The default branch unwraps the synthetic password; it does not
send a placeholder directly to the CE-key unlock routine. It uses the existing
guarded SQLite backup rather than an additional uncoordinated database copy.
PasswordData lengths are checked before allocation, and Weaver slots are read
as a version byte followed by a big-endian 32-bit value.

After successful CE unlock and media-path availability, the GUI returns to the
main page, and the existing post-decrypt MTP path runs. Unknown metadata remains
unknown instead of being converted to the no-password type. Older empty-LSKF
protectors that still contain PasswordData are intentionally not auto-submitted.

Reference: AOSP Android 16
[SyntheticPasswordManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/locksettings/SyntheticPasswordManager.java),
particularly `createLskfBasedProtector`, `unlockLskfBasedProtector`, `stretchLskf`,
and `loadWeaverSlot`.

Host checks cover valid and malformed protectors, credential-protected users,
default-token bytes, slot decoding, GUI routing, and failure propagation. Device
validation still needs both configured-credential and no-screen-lock sessions,
including media access, MTP, and reboot. No keys are enrolled, removed, or replaced
by the new detection code.
