#!/system/bin/sh
# Start the cold WLAN bring-up in the background once data has been decrypted,
# so the firmware wait is over before the user opens Wi-Fi. Runs only after the
# Secure Element, Weaver and KeyMint work for decryption has finished.
# Opt out: create an empty file AERA/no-wifi-prewarm in internal storage.
export PATH=/system/bin:/sbin:/vendor/bin
exec >>/tmp/neo8-wifi-service.log 2>&1
MEDIA=/data/media/0
uptime_s() { cut -d' ' -f1 /proc/uptime; }

# The property can be set shortly before user storage is readable.
waited=0
while [ ! -d "$MEDIA" ] && [ "$waited" -lt 5 ]; do
    sleep 1
    waited=$((waited + 1))
done
if [ -e "$MEDIA/AERA/no-wifi-prewarm" ]; then
    echo "Wi-Fi pre-warm skipped: $MEDIA/AERA/no-wifi-prewarm exists"
    exit 0
fi
case "$(getprop init.svc.wpa_supplicant)" in
    running|restarting)
        echo "Wi-Fi pre-warm skipped: service already active"
        exit 0 ;;
esac
echo "Pre-warming Neo8 Wi-Fi after data decryption (uptime $(uptime_s)s)"
# Same request the UI makes; init then starts the on-demand service.
setprop sys.aera.wlan.up 1
