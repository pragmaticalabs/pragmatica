#!/bin/bash
# test-stream-publish.sh — Publish events, verify delivery
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_NAME="${STREAM_NAME:-test-events}"

test_cluster_ready() {
    wait_for_cluster_ready 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

# Nothing created `test-events` — and a publish has not minted a stream since #1224, so every
# `stream_publish`/`stream_info` below resolved a coordinate for a stream that was never in the
# catalog and returned empty. Mirrors test-stream-replication.sh's create+catalog-assert.
test_create_stream() {
    stream_create "$STREAM_NAME" 1 > /dev/null 2>&1 || true   # idempotent
    assert_ne "$(stream_coordinate "$STREAM_NAME" 2>/dev/null)" "" \
              "Stream ${STREAM_NAME} present in catalog"
}

test_publish_single_event() {
    local payload='{"key":"test-1","data":"hello-world","timestamp":'$(now_epoch)'}'
    local result
    result=$(stream_publish "$STREAM_NAME" "$payload")
    assert_ne "$result" "" "Publish single event returned response"
}

test_publish_batch() {
    local success=0 failure=0 errfile
    errfile=$(mktemp)
    for i in $(seq 1 50); do
        local payload="{\"key\":\"batch-${i}\",\"data\":\"payload-${i}\",\"timestamp\":$(now_epoch)}"
        # `2>&1` here discarded the `api ... status=NNN: <body>` diagnostic that `_api_call`
        # already emits on stderr — which is why the baseline's "expected '50', got '25'" carried
        # no reason for the 25 that failed.
        if stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>>"$errfile"; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
    done
    log_info "Batch publish: success=${success}, failure=${failure}"
    [ "$failure" -eq 0 ] || log_warn "Batch publish diagnostics (first 500B): $(head -c 500 "$errfile" 2>/dev/null | tr -d '\n')"
    rm -f "$errfile"
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
run_test "Create stream" test_create_stream
run_test "Publish single event" test_publish_single_event
run_test "Publish batch of 50" test_publish_batch
run_test "Stream info available" test_stream_info
run_test "Stream in list" test_stream_appears_in_list
print_summary
