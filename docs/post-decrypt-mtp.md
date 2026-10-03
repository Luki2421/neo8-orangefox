# MTP after data decryption

Recovery startup defers MTP while data is encrypted, even if `tw_mtp_enabled` is
set. The Neo8 profile now opts into starting configured MTP after `Post_Decrypt`
has updated the media path and attempted its bind mount. An existing MTP server
is restarted to refresh storage registration and USB configuration.

The helper requires mounted, decrypted data with an existing media path. It
respects the MTP setting and the OrangeFox password lock. Failed MTP startup is
logged and cleaned up without changing the decryption result. The USB paths use
the existing recovery enable/disable methods, including ADB in the MTP mode.

Host tests exercise disabled settings, unavailable media, locks, service failures
and builds without MTP. Actual USB enumeration still requires a device test.
