# QSEE listener daemon startup

The fresh phone logs identify build neo8-ci-13 and show that the manual
decrypt action reaches metadata preparation. It then waits for keystore2.
The keystore process is waiting in the default KeyMint getHardwareInfo call
and repeatedly reports the missing StrongBox shared-secret service. The
property dump contains no running qseecomd or registered-listeners flag.
The user can manually start StrongBox; that alone does not prove successful
shared-secret negotiation or decryption.

The supplied alternative prepare_neo8_build.py proposes an explicit recovery
SELinux label for qseecomd. Incorporate only that change into the current
integration. The pinned donor qseecomd service lacks a seclabel, unlike its
KeyMint/Gatekeeper services. Android init requires a service domain transition
even in permissive mode; an explicit label uses the existing recovery domain.
Reference: [Android init service.cpp](https://android.googlesource.com/platform/system/core/+/696882455bb856cfb4d042b3fef07fada95891d0/init/service.cpp).

The logs do not contain an explicit qseecomd domain-transition failure, so this
remains a configuration fix with a plausible connection to the observed hang,
not a confirmed sole cause. Validate qseecomd startup, listener registration,
StrongBox service registration, and keystore2 progress on the phone.

The uploaded script also removes the current storage initialization patch,
profile/manifest fixes, and Make/Soong fragment fixes. Those reversions are
not incorporated. The current metadata-first ordering and data-key protections
remain in place. Only the generated recovery service rc gains one seclabel;
no device SELinux policy, crypto key or data partition is modified by this script.

Validation: 21 service-configuration tests pass, including preservation of
the complete daemon definition and start/restart triggers, and refusal to
overwrite an existing label or an unexpected executable. The transformation
also applies to the actual pinned donor qseecomd.rc. Full compilation and a
phone test are still required. Raw user logs are not published.
