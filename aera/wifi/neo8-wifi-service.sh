#!/system/bin/sh
# Init owns the wrapper, supplicant and the loader's HAL/CNSS descendants.
export PATH=/system/bin:/sbin:/vendor/bin
umask 077
mkdir -p /tmp/recovery/sockets
exec >>/tmp/neo8-wifi-service.log 2>&1
echo "Starting Neo8 Wi-Fi on demand"

# Bound the complete loader, including synchronous calls to the vendor HAL.
if ! /system/bin/toybox timeout -k 5 100 /system/bin/sh /system/bin/neo8-wifi-loader.sh; then
    echo "Neo8 Wi-Fi setup failed; see /tmp/neo8-wifi-loader.log" >>/tmp/recovery.log
    exit 1
fi

# AERA's browser renders through the Adreno GPU (/dev/kgsl-3d0). The stock
# vendor_dlkm is mounted by the loader above; load KGSL from it. Best effort: the
# browser is optional, so a failure here must not stop Wi-Fi.
KGSL=/tmp/aera-wifi/vendor_dlkm/lib/modules/msm_kgsl.ko
if [ -f "$KGSL" ] && ! grep -q '^msm_kgsl ' /proc/modules; then
    if /system/bin/toybox insmod "$KGSL" >>/tmp/neo8-gpu.log 2>&1; then
        echo "KGSL loaded" >>/tmp/neo8-gpu.log
    else
        echo "KGSL load failed; the browser will be unavailable" >>/tmp/neo8-gpu.log
    fi
fi

# Session-only supplicant configuration, separate from Android's saved networks.
cat > /tmp/recovery/wpa_supplicant.conf <<'EOF'
ctrl_interface=/tmp/recovery/sockets
update_config=0
ap_scan=1
EOF
# Android's wpa_supplicant drops from root to the wifi user (uid/gid 1010) before
# it opens the config, so the umask 077 files above would give "Permission denied".
chown 1010:1010 /tmp/recovery /tmp/recovery/sockets /tmp/recovery/wpa_supplicant.conf
chmod 770 /tmp/recovery /tmp/recovery/sockets
chmod 660 /tmp/recovery/wpa_supplicant.conf
# toybox ping (used by the recovery's connection test through the busybox shim)
# opens an unprivileged ICMP socket, which the kernel allows only for gids in this range.
echo 0 2147483647 >/proc/sys/net/ipv4/ping_group_range

# This wpa_supplicant build has no -f (CONFIG_DEBUG_FILE) and prints usage and
# exits if given it. The control socket path comes from the config, so -C is not
# needed. Output goes to /tmp/neo8-wifi-service.log via the redirect above.
exec /system/bin/wpa_supplicant -dd -t -Dnl80211 -iwlan0 \
    -c/tmp/recovery/wpa_supplicant.conf
