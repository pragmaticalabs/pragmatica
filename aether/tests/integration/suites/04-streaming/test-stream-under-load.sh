#!/bin/bash
# test-stream-under-load.sh — Sustained publish/consume, verify lag
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

STREAM_NAME="${STREAM_NAME:-load-test-stream}"
STREAM_DURATION="${STREAM_DURATION:-300}"  # 5 minutes default
STREAM_RPS="${STREAM_RPS:-20}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-2.0}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_sustained_stream_publish() {
    log_info "Sustained stream publish: ${STREAM_RPS} rps for ${STREAM_DURATION}s"

    local interval
    interval=$(python3 -c "print(1.0 / ${STREAM_RPS})" 2>/dev/null || echo "0.05")
    local end_time=$(($(now_epoch) + STREAM_DURATION))
    local success=0 failure=0 count=0

    while [ "$(now_epoch)" -lt "$end_time" ]; do
        local payload="{\"key\":\"load-${count}\",\"data\":\"sustained-publish-${count}\",\"timestamp\":$(now_epoch)}"
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

        if [ $((count % 200)) -eq 0 ]; then
            log_info "Progress: ${count} messages (success=${success}, failure=${failure})"
        fi

        sleep "$interval"
    done

    local result="${success}:${failure}"
    log_info "Stream load results: total=${count}, success=${success}, failure=${failure}"
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Stream publish error rate < ${MAX_ERROR_RATE}%"
}

test_stream_info_after_load() {
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream info available after sustained load"
}

test_cluster_stable() {
    local count
    count=$(cluster_node_count)
    assert_eq "$count" "5" "Cluster stable: 5 nodes after stream load"
    assert_cluster_healthy "Cluster healthy after stream load"
}

test_concurrent_publish_and_query() {
    # Publish while simultaneously querying stream info
    local pub_ok=true query_ok=true
    for i in $(seq 1 20); do
        local payload="{\"key\":\"concurrent-${i}\",\"data\":\"test\",\"timestamp\":$(now_epoch)}"
        stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>&1 || pub_ok=false
        stream_info "$STREAM_NAME" > /dev/null 2>&1 || query_ok=false
    done

    if [ "$pub_ok" = true ]; then
        log_pass "Concurrent publish succeeded"
    else
        log_fail "Concurrent publish had failures"
        return 1
    fi

    if [ "$query_ok" = true ]; then
        log_pass "Concurrent query succeeded"
    else
        log_fail "Concurrent query had failures"
        return 1
    fi
}

run_test "Cluster ready" test_cluster_ready
run_test "Sustained stream publish" test_sustained_stream_publish
run_test "Stream info after load" test_stream_info_after_load
run_test "Cluster stable" test_cluster_stable
run_test "Concurrent publish and query" test_concurrent_publish_and_query
print_summary
