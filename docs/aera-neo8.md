# AERA Neo8 development port

Branch: `port/neo8-aera`. Workflow: `aera-build.yml`.

The Android 16 manifest and 35 AERA projects are pinned in `aera-sources.json`.
The manifest adapter switches the published AERA repositories from SSH to HTTPS
for unauthenticated CI fetches. Other upstream dependencies retain the revisions
selected by the pinned manifest; CI retains a fully resolved manifest.

The hardware base is the pinned RE6402L1 tree in `port-sources.json`. The adapter
retains its kernel, firmware, partition layout and native touch stack. Recovery
service configuration reuses the existing Neo8 task profiles, unique device
VINTF declarations, explicit QSEE/fastboot service domains and preserved signed
SSG firmware paths. Native AERA owns startup, the credential page, MTP and USB
mode transitions; OrangeFox XML UI and menu patches are not applied.

The vold port reuses the pinned Neo8 source files and the existing guarded
keystore snapshot, stock-property validation and empty-credential protector
patches. The AERA partition manager uses the Neo8 metadata-first environment
selection and existing-key check. Unknown credential metadata remains unknown.
Touch startup restores the device identity after the vendor service settles.

Host checks cover the manifest transformation, configuration flag conversion,
native-startup patch application, metadata environment selection, bounded touch
wait, vold protector parsing and service-stop failure handling. The existing
firmware-copy tests use synthetic files; CI validates the full donor firmware.
The WAL snapshot regression also runs in CI with SQLite development headers.

The second source review corrected the upstream build's obsolete GUI plugin
allowlist and switched the language settings to the native AERA variables,
including extra translations. A make-based integration check verifies the
actual crypto, fastboot, temperature and UI flags after alias translation.
Workflow path filters also cover the shared adapter, device files and crypto
patches so changes to those dependencies trigger a new AERA build.

This is a first bring-up, not a phone-validated release. Compilation, image
inspection and phone tests are separate gates. Check startup, native UI/touch,
PIN and empty-credential decryption, MTP, backup, normal system reboot and USB
transitions before treating the image as a replacement recovery. Network/GPU
features from unrelated device trees are not imported.

Upstream source: https://github.com/AERA-Recovery/android_bootable_recovery
