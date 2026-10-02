#!/system/bin/sh
# Restore the Neo8 identity after the touch service's initial compatibility setup.
# Keep this allowlist limited to the fields changed by neo8-touch-props.sh.
RP=/system/bin/resetprop
[ -x "$RP" ] || exit 1
setprop twrp.neo8.identity_restored 0 || exit 1

restore_property() {
    "$RP" -n "$1" "$2" || return 1
    [ "$(getprop "$1")" = "$2" ]
}

for scope in '' system. vendor. odm. product. system_ext.; do
    restore_property "ro.product.${scope}device" RE6402L1 || exit 1
    restore_property "ro.product.${scope}name" RMX8899 || exit 1
    restore_property "ro.product.${scope}model" RMX8899 || exit 1
    restore_property "ro.product.${scope}manufacturer" realme || exit 1
done

# Fingerprints, security patch levels and KeyMint inputs are left untouched.
setprop twrp.neo8.identity_restored 1 || exit 1
exit 0
