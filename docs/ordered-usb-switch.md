# Ordered recovery USB switching

The fastboot page previously wrote `sys.usb.config=none` followed immediately by
the selected mode. Property writes acknowledge requests; they do not wait for
init to unbind the gadget, stop services, or close FunctionFS endpoints.

The Neo8 profile enables `twrp.recovery.ordered_usb_switch`. For the two fastboot
page buttons, the handler waits for the `none` state, both USB services to stop,
and FunctionFS readiness to reset before requesting the selected mode. It then
waits for the selected service, FunctionFS readiness, and the target USB state.
Each wait has a three-second limit. A timeout is logged with the property and
observed value, and the sequence stops at that point.

The GUI changes the button state only when the handler succeeds. Simulation
does not write USB properties. The existing fastboot HAL configuration, USB
IDs, MTP refresh helper, and decryption code are unchanged by this patch.

Host tests compile the actual handlers and cover both directions, each failed
property write or acknowledgement, the profile opt-out, and simulation. These
tests check ordering and error handling; they do not emulate USB enumeration or
Windows drivers. Runtime verification must check both `fastboot devices` and
`adb devices` across repeated mode changes on the device.
