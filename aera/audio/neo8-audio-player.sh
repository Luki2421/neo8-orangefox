#!/system/bin/sh
# Started by /system/bin/aera-audio-bridge with the FIFO path. Brings up the Neo8
# speaker path on demand (stock partitions READ-ONLY, audio kernel modules, ADSP,
# stock helper daemons in a chroot under /tmp) and then becomes the stock player.
# Every step is idempotent: a second media session only starts the player.
# Verified on the device with diagnostic probes 1-7 (Nov 2026).
export PATH=/system/bin:/sbin:/vendor/bin
FIFO="$1"
[ -p "$FIFO" ] || { echo "neo8-audio: no stream FIFO"; exit 64; }
up() { cut -d' ' -f1 /proc/uptime; }
echo "neo8-audio: bring-up starts (uptime $(up)s)"

STOCK=/tmp/aera-wifi
ROOT=/tmp/neo8-audio-root
AGM=/tmp/neo8-agm
SLOT=$(getprop ro.boot.slot_suffix)
case "$SLOT" in _a|_b) ;; *) echo "neo8-audio: invalid boot slot $SLOT"; exit 1 ;; esac

# Same read-only mounts as the Wi-Fi loader; never over recovery's /vendor or /odm.
mount_stock() {
    local name="$1" point="$STOCK/$1" block fs
    grep -q " $point " /proc/mounts && return 0
    mkdir -p "$point" || return 1
    for block in "/dev/block/by-name/$name" "/dev/block/bootdevice/by-name/$name" \
        "/dev/block/mapper/$name$SLOT" "/dev/block/mapper/$name" \
        "/dev/block/by-name/$name$SLOT" "/dev/block/bootdevice/by-name/$name$SLOT"; do
        [ -b "$block" ] || continue
        for fs in erofs ext4 vfat; do
            mount -t "$fs" -o ro "$block" "$point" 2>/dev/null && return 0
        done
    done
    echo "neo8-audio: cannot mount $name read-only"
    return 1
}
for p in vendor vendor_dlkm system_dlkm odm dsp; do mount_stock "$p" || exit 1; done
mkdir -p "$STOCK/modem"
grep -q " $STOCK/modem " /proc/mounts || \
    mount -t vfat -o ro "/dev/block/bootdevice/by-name/modem$SLOT" "$STOCK/modem" || exit 1

SEEN_DIR=/tmp/neo8-audio-seen; mkdir -p "$SEEN_DIR"
module_loaded() {
    local module="${1##*/}"
    module="${module%.ko}"
    module="$(echo "$module" | tr '-' '_')"
    grep -q "^${module} " /proc/modules
}
find_module() {
    local requested="$1" relative base root path
    base="${requested##*/}"
    relative="${requested#*/lib/modules/}"
    case "$relative" in /*|../*|*/../*) relative="$base" ;; esac
    for root in $STOCK/vendor_dlkm/lib/modules $STOCK/system_dlkm/lib/modules \
        $STOCK/vendor/lib/modules $STOCK/odm/lib/modules; do
        for path in "$root/$relative" "$root/$base"; do
            [ -f "$path" ] && { echo "$path"; return 0; }
        done
    done
    return 1
}
dependency_line() {
    local base depfile line
    base="$(basename "$1")"
    for depfile in $STOCK/vendor/lib/modules/modules.dep $STOCK/vendor_dlkm/lib/modules/modules.dep \
        $STOCK/system_dlkm/lib/modules/modules.dep $STOCK/odm/lib/modules/modules.dep; do
        [ -f "$depfile" ] || continue
        line="$(awk -v name="$base" '{n=$1; sub(/^.*\//,"",n); if(n==name ":") print}' "$depfile" 2>/dev/null | head -n 1)"
        [ -n "$line" ] && { echo "${line#*:}"; return 0; }
    done
    return 1
}
load_with_dependencies() {
    local requested="$1" base deps dep path
    base="$(basename "$requested")"
    module_loaded "$base" && return 0
    [ -e "$SEEN_DIR/$base" ] && { echo "neo8-audio: module dependency cycle: $base"; return 1; }
    : > "$SEEN_DIR/$base"
    deps="$(dependency_line "$base")"
    for dep in $deps; do
        load_with_dependencies "$dep" || { rm -f "$SEEN_DIR/$base"; return 1; }
    done
    module_loaded "$base" && { rm -f "$SEEN_DIR/$base"; return 0; }
    path="$(find_module "$requested")" || { echo "neo8-audio: missing module $requested"; rm -f "$SEEN_DIR/$base"; return 1; }
    insmod "$path" 2>/dev/null || module_loaded "$base" || { echo "neo8-audio: failed module $path"; rm -f "$SEEN_DIR/$base"; return 1; }
    rm -f "$SEEN_DIR/$base"
}
# The machine driver only registers the card once the DP/BT/WSA codecs exist (probe 3).
for m in frpc-adsprpc q6_notifier_dlkm spf_core_dlkm gpr_dlkm audio_pkt_dlkm audpkt_ion_dlkm \
    q6_dlkm adsp_loader_dlkm audio_prm_dlkm q6_pdr_dlkm pinctrl_lpi_dlkm swr_dlkm swr_ctrl_dlkm \
    snd_event_dlkm wcd_core_dlkm mbhc_dlkm wcd9xxx_dlkm stub_dlkm oplus_audio_extend \
    oplus_audio_netlink oplus_audio_daemon oplus_audio_tfa98xx_v6 oplus_audio_aw882xx \
    lpass_cdc_dlkm lpass_cdc_wsa2_macro_dlkm lpass_cdc_wsa_macro_dlkm lpass_cdc_va_macro_dlkm \
    lpass_cdc_rx_macro_dlkm lpass_cdc_tx_macro_dlkm lpass_bt_swr_dlkm wcd9378_dlkm \
    wcd9378_slave_dlkm wcd939x_dlkm wcd939x_slave_dlkm wcd938x_dlkm wcd938x_slave_dlkm \
    swr_dmic_dlkm swr_haptics_dlkm hdmi_dlkm btpower btfmcodec bt_fm_swr btfm_slim_codec \
    simple_amp_dlkm qmp_dlkm wsa885x_i2c_dlkm machine_dlkm; do
    load_with_dependencies "$m.ko" || echo "neo8-audio: module $m not loaded"
done
echo "neo8-audio: modules done (uptime $(up)s)"

# Chroot with the stock vendor tree. Targets are new paths under $ROOT only.
bind() {
    local src="$1" dst="$ROOT$2"
    [ -e "$src" ] || return 0
    grep -q " $dst " /proc/mounts && return 0
    mkdir -p "$dst" 2>/dev/null
    if [ "$3" = r ]; then
        mount --rbind "$src" "$dst" 2>/dev/null || mount -o rbind "$src" "$dst" 2>/dev/null || mount --bind "$src" "$dst"
    else
        mount --bind "$src" "$dst"
    fi
}
mkdir -p "$ROOT"
bind /system /system
bind /system_ext /system_ext
bind "$STOCK/vendor" /vendor
bind "$STOCK/odm" /odm
bind /dev /dev r
bind /proc /proc
bind /sys /sys r
# Scratch /data for calibration deltas; the real /data is never exposed.
grep -q " $ROOT/data " /proc/mounts || { mkdir -p "$ROOT/data" && mount -t tmpfs tmpfs "$ROOT/data"; }
mkdir -p "$ROOT/data/vendor/audio/acdbdata/delta" "$ROOT/data/vendor/audio_dsp" "$ROOT/data/vendor/audio/memlog"
bind /mnt/vendor/persist /mnt/vendor/persist
bind "$STOCK/dsp" /vendor/dsp
bind "$STOCK/modem" /vendor/firmware_mnt
mkdir -p "$ROOT/linkerconfig"
[ -d /apex ] && bind /apex /apex
bind /tmp /tmp

start_daemon() {
    local name="$1" old; shift
    old=$(cat "/tmp/neo8-audio-$name.pid" 2>/dev/null)
    [ -n "$old" ] && [ -d "/proc/$old" ] && return 0
    LD_LIBRARY_PATH=/vendor/lib64:/odm/lib64:/system/lib64:/system_ext/lib64 \
        chroot "$ROOT" "$@" >"/tmp/neo8-audio-$name.log" 2>&1 &
    echo $! > "/tmp/neo8-audio-$name.pid"
    echo "neo8-audio: started $name"
}
start_daemon pd-mapper /vendor/bin/pd-mapper

ADSP=""
for r in /sys/class/remoteproc/*; do
    case "$(cat "$r/name" 2>/dev/null)" in *adsp*) ADSP=$r ;; esac
done
[ -n "$ADSP" ] || { echo "neo8-audio: no ADSP remoteproc"; exit 1; }
if [ "$(cat "$ADSP/state" 2>/dev/null)" != running ]; then
    sleep 1
    echo start > "$ADSP/state"
fi
n=0
while [ "$(cat "$ADSP/state" 2>/dev/null)" != running ] && [ "$n" -lt 30 ]; do sleep 0.5; n=$((n + 1)); done
[ "$(cat "$ADSP/state" 2>/dev/null)" = running ] || { echo "neo8-audio: ADSP did not start"; exit 1; }

start_daemon audioadsprpcd /vendor/bin/audioadsprpcd audiopd adsp
start_daemon adsprpcd /vendor/bin/adsprpcd rootpd adsp
n=0
while grep -q "no soundcards" /proc/asound/cards 2>/dev/null && [ "$n" -lt 80 ]; do sleep 0.5; n=$((n + 1)); done
grep -q "no soundcards" /proc/asound/cards 2>/dev/null && { echo "neo8-audio: no sound card"; exit 1; }
echo "neo8-audio: sound card ready (uptime $(up)s)"

# The client library is replaced by the engine itself in the player process.
mkdir -p "$AGM"
cmp -s "$STOCK/vendor/lib64/libagm.so" "$AGM/libagmclient.so" || cp "$STOCK/vendor/lib64/libagm.so" "$AGM/libagmclient.so" || exit 1
cmp -s /system/etc/neo8-audio/libneo8agminit.so "$AGM/libneo8agminit.so" || cp /system/etc/neo8-audio/libneo8agminit.so "$AGM/libneo8agminit.so" || exit 1
chmod 755 "$AGM/libagmclient.so" "$AGM/libneo8agminit.so"
cat > "$AGM/play.sh" <<'PLAY'
#!/system/bin/sh
export LD_LIBRARY_PATH=/tmp/neo8-agm:/vendor/lib64:/odm/lib64:/system/lib64:/system_ext/lib64
LD_PRELOAD=/tmp/neo8-agm/libagmclient.so:/tmp/neo8-agm/libneo8agminit.so exec /vendor/bin/agmplay "$@"
PLAY

# Both TFA98xx amplifiers on the speaker profile.
/system/bin/aera-audio-bridge set 0 TFA_CHIP_SELECTOR 3 >/dev/null
/system/bin/aera-audio-bridge set 0 "TFA Profile" speaker >/dev/null
echo "neo8-audio: starting the player (uptime $(up)s)"
exec chroot "$ROOT" /system/bin/sh /tmp/neo8-agm/play.sh "$FIFO" -D 100 -d 100 -i MI2S-LPAIF-RX-SECONDARY -dkv 0xA2000001
