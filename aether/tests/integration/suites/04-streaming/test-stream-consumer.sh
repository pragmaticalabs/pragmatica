#!/bin/bash
# test-stream-consumer.sh — Consumer receives events, analytics counts
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

test_publish_and_verify_count() {
    # RC1-blocker fix (audit 2026-05-21 §2.2 #2): the prior implementation
    # silenced `stream_publish ... > /dev/null 2>&1` and only asserted that the
    # read-back totalEvents was > 0. A broken publish path that quietly errored
    # on every call would still PASS because the read-side count check was
    # decoupled from publish outcomes.
    #
    # New behavior:
    #   1. Capture publish exit codes; track per-call success.
    #   2. Assert ALL publishes succeeded (success == expected).
    #   3. After replication settles, assert the read-back totalEvents is at
    #      least the published count (>= rather than == because the stream may
    #      retain events from earlier tests in the suite; the floor is the
    #      invariant under test).
    local publish_count=20
    local success=0
    local publish_errfile
    publish_errfile=$(mktemp)
    local i payload publish_rc
    for i in $(seq 1 "$publish_count"); do
        payload="{\"key\":\"consumer-test-${i}\",\"data\":\"msg-${i}\",\"timestamp\":$(now_epoch)}"
        if stream_publish "$STREAM_NAME" "$payload" >/dev/null 2>"$publish_errfile"; then
            success=$((success + 1))
        else
            publish_rc=$?
            log_fail "stream_publish ${STREAM_NAME} #${i} failed (rc=${publish_rc}): $(cat "$publish_errfile" 2>/dev/null | head -c 200)"
        fi
    done
    rm -f "$publish_errfile"
    assert_eq "$success" "$publish_count" "All ${publish_count} publishes succeeded"

    # Replication settle window — stream_info is read from the governor, which
    # observes the partition's totalEvents counter advanced by the local writer.
    # 2s is the same budget the prior test used; we keep it so the only behavior
    # change here is the strictness of the assertions.
    sleep 2

    local info
    info=$(stream_info "$STREAM_NAME") || {
        log_fail "stream_info ${STREAM_NAME} failed (exit non-zero)"
        return 1
    }
    if [ -z "$info" ]; then
        log_fail "stream_info ${STREAM_NAME} returned empty body"
        return 1
    fi

    # Endpoint may return flat object or {streams:[...]} array — match either.
    local msg_count
    msg_count=$(json_value "$info" "totalEvents")
    if [ -z "$msg_count" ] && echo "$info" | grep -q "\"streams\""; then
        msg_count=$(echo "$info" | grep -o "\"totalEvents\"[[:space:]]*:[[:space:]]*[0-9]*" | head -1 | sed 's/.*:[[:space:]]*//')
    fi
    msg_count="${msg_count:-0}"

    assert_ge "$msg_count" "$publish_count" "totalEvents (${msg_count}) >= published (${publish_count})"
}

test_stream_metadata() {
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream metadata retrievable"
    local name
    name=$(json_value "$info" "name")
    if [ -z "$name" ] && echo "$info" | grep -q "\"streams\""; then
        # Extract first name from streams array
        name=$(echo "$info" | grep -o "\"name\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | sed 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
    fi
    assert_ne "$name" "" "Stream name in metadata"
}

test_multiple_streams_isolation() {
    local other_stream="isolation-test"
    local payload='{"key":"isolated","data":"test","timestamp":'$(now_epoch)'}'
    stream_publish "$other_stream" "$payload" > /dev/null 2>&1

    local streams
    streams=$(stream_list)
    assert_contains "$streams" "$STREAM_NAME" "Original stream still exists"
}

run_test "Cluster ready" test_cluster_ready
run_test "Publish and verify count" test_publish_and_verify_count
run_test "Stream metadata" test_stream_metadata
run_test "Multiple streams isolation" test_multiple_streams_isolation
print_summary
