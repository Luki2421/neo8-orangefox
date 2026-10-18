#!/system/bin/sh
# OrangeFox's Wi-Fi network test calls "busybox ping"; this image has no busybox,
# only toybox. Forward every applet to the matching toybox command.
[ "$#" -gt 0 ] || exit 1
exec /system/bin/toybox "$@"
