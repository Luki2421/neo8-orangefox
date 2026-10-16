# Included in the hardware loader before inserting modules.
STOCK=/tmp/aera-wifi
SLOT=$(getprop ro.boot.slot_suffix)
case "$SLOT" in _a|_b) ;; *) echo "Invalid boot slot: $SLOT"; exit 1 ;; esac

mount_stock() {
    local name="$1" point="$STOCK/$1" block fs
    grep -q " $point " /proc/mounts && return 0
    mkdir -p "$point" || return 1
    for block in "/dev/block/by-name/$name" \
        "/dev/block/bootdevice/by-name/$name" \
        "/dev/block/mapper/$name$SLOT" "/dev/block/mapper/$name" \
        "/dev/block/by-name/$name$SLOT"; do
        [ -b "$block" ] || continue
        for fs in erofs ext4; do
            mount -t "$fs" -o ro "$block" "$point" 2>/dev/null && return 0
        done
    done
    echo "Cannot mount $name read-only under $point"
    return 1
}

# Never mount a partition over recovery's /vendor or /odm trees.
mount_stock vendor || exit 1
mount_stock vendor_dlkm || exit 1
mount_stock system_dlkm || exit 1
mount_stock odm || exit 1
mkdir -p "$STOCK/modem"
if ! grep -q " $STOCK/modem " /proc/mounts; then
    mount -t vfat -o ro "/dev/block/bootdevice/by-name/modem$SLOT" "$STOCK/modem" || exit 1
fi

# Expose only the Wi-Fi config subtrees expected by the proprietary services.
# The source mounts are read-only; no crypto libraries/manifests are covered.
bind_wifi_config() {
    local source="$1" target="$2"
    [ -d "$source" ] || return 0
    grep -q " $target " /proc/mounts && return 0
    [ ! -L "$target" ] || { echo "Refusing Wi-Fi target symlink: $target"; return 1; }
    mkdir -p "$target" || return 1
    mount --bind "$source" "$target" || return 1
    mount -o remount,bind,ro "$target" || return 1
}
bind_wifi_config "$STOCK/vendor/etc/wifi" /vendor/etc/wifi || exit 1
bind_wifi_config "$STOCK/odm/etc/wifi" /vendor/odm/etc/wifi || exit 1

# Reuse persist if decrypt has already mounted it. Otherwise expose calibration
# read-only using its named partition, never a guessed physical block index.
if ! grep -q ' /mnt/vendor/persist ' /proc/mounts; then
    mkdir -p /mnt/vendor/persist
    mount -t ext4 -o ro /dev/block/bootdevice/by-name/persist /mnt/vendor/persist || exit 1
fi

# This environment is applied ONLY to Wi-Fi subprocesses.
WIFI_LIBS="$STOCK/vendor/lib64:$STOCK/odm/lib64:/system/lib64:/system_ext/lib64:/vendor/lib64:/odm/lib64:/sbin/lib64"
