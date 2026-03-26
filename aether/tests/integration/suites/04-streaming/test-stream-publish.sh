#!/bin/bash
# test-stream-publish.sh — Publish events, verify delivery
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_NAME="${STREAM_NAME:-test-events}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_publish_single_event() {
    local payload='{"key":"test-1","data":"hello-world","timestamp":'$(now_epoch)'}'
    local result
    result=$(stream_publish "$STREAM_NAME" "$payload")
    assert_ne "$result" "" "Publish single event returned response"
}

test_publish_batch() {
    local success=0 failure=0
    for i in $(seq 1 50); do
        local payload="{\"key\":\"batch-${i}\",\"data\":\"payload-${i}\",\"timestamp\":$(now_epoch)}"
        if stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>&1; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
    done
    log_info "Batch publish: success=${success}, failure=${failure}"
    assert_eq "$success" "50" "All 50 events published"
}

test_stream_info() {
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream info available for ${STREAM_NAME}"
}

test_stream_appears_in_list() {
    local streams
    streams=$(stream_list)
    assert_contains "$streams" "$STREAM_NAME" "Stream visible in list"
}

run_test "Cluster ready" test_cluster_ready
run_test "Publish single event" test_publish_single_event
run_test "Publish batch of 50" test_publish_batch
run_test "Stream info available" test_stream_info
run_test "Stream in list" test_stream_appears_in_list
print_summary
