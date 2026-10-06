#!/system/bin/sh
# Publish all stock version inputs before either KeyMint HAL may start.
RP=/system/bin/resetprop
setprop twrp.keymint.bootstrap_ready 0 || exit 1
[ -x "$RP" ] || exit 1

valid_patch() {
    case "$1" in
        20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;;
        *) return 1 ;;
    esac
    year=${1%%-*}
    rest=${1#*-}
    month=${rest%%-*}
    day=${rest#*-}
    month=${month#0}
    day=${day#0}
    [ "$year" -lt 2099 ] && [ "$month" -ge 1 ] && [ "$month" -le 12 ] || return 1
    case "$month" in
        4|6|9|11) limit=30 ;;
        2)
            limit=28
            if [ $((year % 400)) -eq 0 ] || { [ $((year % 4)) -eq 0 ] && [ $((year % 100)) -ne 0 ]; }; then
                limit=29
            fi ;;
        *) limit=31 ;;
    esac
    [ "$day" -ge 1 ] && [ "$day" -le "$limit" ]
}

os=$(getprop twrp.keymint.osver)
ospatch=$(getprop twrp.keymint.ospatch)
venpatch=$(getprop twrp.keymint.venpatch)
case "$os" in
    16|16.0|16.0.0|17|17.0|17.0.0) ;;
    *) echo "Neo8 KeyMint bootstrap: missing or unsupported stock OS"; exit 1 ;;
esac
valid_patch "$ospatch" && valid_patch "$venpatch" || {
    echo "Neo8 KeyMint bootstrap: invalid stock patch levels"
    exit 1
}

publish() {
    "$RP" -n "$1" "$2" || return 1
    [ "$(getprop "$1")" = "$2" ]
}

publish ro.build.version.release "$os" || exit 1
publish ro.build.version.release_or_codename "$os" || exit 1
publish ro.build.version.security_patch "$ospatch" || exit 1
publish ro.vendor.build.security_patch "$venpatch" || exit 1
echo "Neo8 KeyMint bootstrap: stock os=$os patch=$ospatch vendor=$venpatch"
# Separate from crypto.ready: prepdecrypt still has to wait for the HALs.
setprop twrp.keymint.bootstrap_ready 1 || exit 1
