#!/system/bin/sh
# Speaker channel test: plays three beeps on the LEFT channel, then three on the
# RIGHT channel, through the same bridge and player as AERA's media features.
# Close the browser/media player first. Uses the current rotation and
# /sdcard/AERA/audio-channels like normal playback does.
B=/system/bin/aera-audio-bridge
FIFO=/tmp/neo8-agm/lrtest.wav
LOG=/tmp/neo8-audio-lr-test.log
mkdir -p /tmp/neo8-agm
echo "rotation: $(cat /tmp/aera-audio-rotation 2>/dev/null || echo 0), channels mode: $(cat /sdcard/AERA/audio-channels 2>/dev/null || echo auto)"
"$B" bridge "$FIFO" --accept-uid 0 -- /system/bin/sh /system/bin/neo8-audio-player.sh "$FIFO" > "$LOG" 2>&1 &
BRIDGE=$!
n=0
echo "starting audio (first start takes up to 20 s)..."
while [ "$n" -lt 80 ] && [ -d "/proc/$BRIDGE" ] && ! grep -q "player is consuming" "$LOG"; do sleep 0.5; n=$((n + 1)); done
if ! grep -q "player is consuming" "$LOG"; then
    echo "audio did not start; see $LOG"
    grep -E "bridge socket|neo8-audio:" "$LOG" | tail -5
    kill "$BRIDGE" 2>/dev/null
    exit 1
fi
sleep 1
echo ">>> LEFT channel: three beeps"
"$B" client 0.25 left
sleep 1.5
echo ">>> RIGHT channel: three beeps"
"$B" client 0.25 right
sleep 1
kill "$BRIDGE" 2>/dev/null
wait "$BRIDGE" 2>/dev/null
echo "done; log: $LOG"
