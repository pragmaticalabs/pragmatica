#!/bin/bash
# test-stream-consumer.sh — Consumer receives events, analytics counts
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_NAME="${STREAM_NAME:-test-events}"
ISOLATION_STREAM="${ISOLATION_STREAM:-isolation-test}"

test_cluster_ready() {
    wait_for_cluster_ready 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

# Nothing in 04-streaming created `test-events`, and a publish has not minted a stream since
# #1224 — so `stream_publish`/`stream_info` resolved a coordinate for a stream that was never in
# the catalog and returned empty. Create both streams this file uses, and assert they landed.
test_create_streams() {
    stream_create "$STREAM_NAME" 1 > /dev/null 2>&1 || true        # idempotent
    stream_create "$ISOLATION_STREAM" 1 > /dev/null 2>&1 || true
    assert_ne "$(stream_coordinate "$STREAM_NAME" 2>/dev/null)" "" \
              "Stream ${STREAM_NAME} present in catalog"
    assert_ne "$(stream_coordinate "$ISOLATION_STREAM" 2>/dev/null)" "" \
              "Stream ${ISOLATION_STREAM} present in catalog"
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

    # Poll /info's totalEvents until it reaches the published count (30s budget; STREAM_COUNT_POLL_BUDGET_S).
    # /info reports the partition OWNERS' heads (#1478), so the total is meaningful from any node once the
    # writes are visible, but visibility lags the publish ack: the earlier fixed `sleep 2` and a single read
    # measured 14 of 20 on a run where the events were still landing. On timeout, quote the FULL /info body
    # (partitionDetails) and /replicas/0 so the next red discriminates a stuck visible offset from a ring-tail
    # undercount instead of reading only "got 14".
    #
    # totalEvents comes from /info (stream_status), NOT the metadata route stream_info hits: that body has no
    # such field, and an absent field must fail loudly rather than read as a measured 0.
    local poll_deadline=$(( SECONDS + ${STREAM_COUNT_POLL_BUDGET_S:-30} ))
    local info msg_count=""
    while :; do
        info=$(stream_status "$STREAM_NAME") || info=""
        msg_count=$(json_value "$info" "totalEvents") || msg_count=""
        if [ -n "$msg_count" ] && [ "$msg_count" -ge "$publish_count" ] 2>/dev/null; then
            break
        fi
        [ "$SECONDS" -ge "$poll_deadline" ] && break
        sleep 1
    done
    if [ -z "$msg_count" ] || ! [ "$msg_count" -ge "$publish_count" ] 2>/dev/null; then
        log_fail "totalEvents (${msg_count:-<absent>}) did not reach published (${publish_count}) within ${STREAM_COUNT_POLL_BUDGET_S:-30}s. /info body: $(printf '%s' "$info" | head -c 2000) || /replicas/0 body: $(stream_replicas "$STREAM_NAME" 0 2>&1 | head -c 2000)"
        return 1
    fi

    assert_ge "$msg_count" "$publish_count" "totalEvents (${msg_count}) >= published (${publish_count})"
}

test_stream_metadata() {
    # The metadata route names the stream in `stream`; there is no `name` field (#1478).
    local name
    name=$(stream_declared_name "$STREAM_NAME") || {
        log_fail "Stream metadata for ${STREAM_NAME} unavailable (stream field absent or request failed — see the line above)"
        return 1
    }
    assert_eq "$name" "$STREAM_NAME" "Stream name in metadata"
}

test_multiple_streams_isolation() {
    local payload='{"key":"isolated","data":"test","timestamp":'$(now_epoch)'}'
    stream_publish "$ISOLATION_STREAM" "$payload" > /dev/null \
        || log_warn "isolation publish to ${ISOLATION_STREAM} failed — the assertion below is about ${STREAM_NAME} surviving it, so continuing"

    local streams
    streams=$(stream_list)
    assert_contains "$streams" "$STREAM_NAME" "Original stream still exists"
}

run_test "Cluster ready" test_cluster_ready
run_test "Create streams" test_create_streams
run_test "Publish and verify count" test_publish_and_verify_count
run_test "Stream metadata" test_stream_metadata
run_test "Multiple streams isolation" test_multiple_streams_isolation
print_summary
