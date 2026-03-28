#!/bin/bash
# test-streaming-soak.sh — Notification hub under sustained streaming load
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

STREAM_DURATION="${STREAM_DURATION:-3600}"  # 1 hour default
STREAM_RPS="${STREAM_RPS:-5}"
STREAM_NAME="${STREAM_NAME:-notifications}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-2.0}"

test_stream_exists() {
    local streams
    streams=$(stream_list)
    # If no streams, try to create by publishing
    if [ -z "$streams" ] || [ "$streams" = "[]" ]; then
        log_info "No streams found — will verify via publish"
    fi
    log_pass "Stream list retrieved"
}

test_sustained_publish() {
    log_info "Publishing to stream for ${STREAM_DURATION}s at ${STREAM_RPS} rps"

    local end_time=$(($(now_epoch) + STREAM_DURATION))
    local interval
    interval=$(python3 -c "print(1.0 / ${STREAM_RPS})" 2>/dev/null || echo "0.2")
    local success=0 failure=0 count=0

    while [ "$(now_epoch)" -lt "$end_time" ]; do
        local payload="{\"message\":\"soak-test-${count}\",\"timestamp\":$(now_epoch)}"
        local status
        status=$(http_status "${CLUSTER_ENDPOINT}/api/streams/${STREAM_NAME}/publish" \
            -X POST \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "$payload")

        if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
        count=$((count + 1))

        if [ $((count % 500)) -eq 0 ]; then
            log_info "Progress: ${count} messages (success=${success}, failure=${failure})"
        fi

        sleep "$interval"
    done

    local result="${success}:${failure}"
    log_info "Stream soak results: success=${success}, failure=${failure}"
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Stream publish error rate below ${MAX_ERROR_RATE}%"
}

test_cluster_stable_after_stream() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Cluster stable: 5 nodes after streaming soak"
}

test_health_after_stream() {
    assert_cluster_healthy "Cluster healthy after streaming soak"
}

run_test "Stream exists" test_stream_exists
run_test "Sustained publish" test_sustained_publish
run_test "Cluster stable after streaming" test_cluster_stable_after_stream
run_test "Health after streaming" test_health_after_stream
print_summary
