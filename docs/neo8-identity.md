# Neo8 recovery identity experiment

The pinned donor's `neo8-touch-props.sh` sets product properties to `u9` before
starting the touch HAL. This also changes the device name seen by package
installers. The build target remains `RE6402L1`.

Keep the donor's touch startup setup, then run a recovery-owned identity helper
after the existing bounded touch-service wait reports running and completes its
300 ms settling delay. The helper restores only the 24 identity fields changed
by the donor script, using the Neo8 values in the donor's `system.prop`.
It verifies each write and reports failure if resetprop fails or a readback differs.

Run this before OrangeFox caches the compatibility device and prints its startup
banner. A touch-service timeout skips the helper. No package assertions are
removed and no alternative device aliases are accepted. Platform, fingerprints,
security patch levels and metadata decryption ordering remain unchanged.

This is an experimental startup transition: init's `running` state does not
prove that a proprietary HAL has finished consuming properties. Device testing
must check touch at startup and after screen wake, identity properties, and
decryption. A later touch HAL restart or property reread also needs observation.
Keep the preceding recovery image available until this transition is verified.
