# The pinned helper returns 3 only when IWifi.start() fails. The kernel can
# still finish asynchronous driver startup after that call times out. Keep
# the init service (and its HAL/CNSS children) alive for a bounded retry.
# The service wrapper also enforces a 100-second overall loader timeout.
start_wifi_hal_with_retry() {
    local attempt=1 status
    while [ "$attempt" -le 3 ]; do
        echo "Wi-Fi HAL start attempt $attempt/3"
        if start_wifi_hal; then
            return 0
        else
            status=$?
        fi
        if [ "$status" -ne 3 ] || [ "$attempt" -eq 3 ]; then
            echo "Wi-Fi HAL startup failed: status=$status, attempt=$attempt"
            return "$status"
        fi
        echo "IWifi.start failed; allowing driver startup to settle for 8 seconds"
        sleep 8
        attempt=$((attempt + 1))
    done
}
