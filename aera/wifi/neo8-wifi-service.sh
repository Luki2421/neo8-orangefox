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

# Session-only supplicant configuration, separate from Android's saved networks.
cat > /tmp/recovery/wpa_supplicant.conf <<'EOF'
ctrl_interface=/tmp/recovery/sockets
update_config=0
ap_scan=1
EOF
# This wpa_supplicant build has no -f (CONFIG_DEBUG_FILE) and prints usage and
# exits if given it. The control socket path comes from the config, so -C is not
# needed. Output goes to /tmp/neo8-wifi-service.log via the redirect above.
exec /system/bin/wpa_supplicant -dd -t -Dnl80211 -iwlan0 \
    -c/tmp/recovery/wpa_supplicant.conf
