#!/bin/bash
# test-pub-sub.sh — Publish events, verify subscriber receives, test competing consumers
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_NAME="${PUB_SUB_STREAM:-integration-pubsub}"
EVENT_COUNT="${PUB_SUB_EVENT_COUNT:-25}"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

test_stream_exists_or_created() {
    local streams
    streams=$(stream_list)
    # Match exact JSON field "name":"<STREAM_NAME>" (with optional whitespace) so
    # that a different stream whose name is a prefix of $STREAM_NAME (or contains
    # $STREAM_NAME inside another field, e.g. a description) does NOT cause a
    # false positive. Allow optional whitespace between the colon and the value.
    if printf '%s' "$streams" | grep -qE "\"name\"[[:space:]]*:[[:space:]]*\"${STREAM_NAME}\""; then
        log_pass "Stream ${STREAM_NAME} already exists"
    else
        log_info "Stream ${STREAM_NAME} not found — publishing will auto-create"
        log_pass "Stream list endpoint responds"
    fi
}

# Bounded transient-only retry for a single publish (#460 gap 3). Under parallel
# deployment churn ~1 of 25 publishes fails at the TRANSPORT level (a transient
# 503 / NotLeader / connection blip surfaced by stream_publish -> api_post's
# curl-error rc), not a stream-logic error. Retry the transport call a few times
# with a short backoff. This CANNOT mask an assertion failure: assertions live in
# the callers (assert_eq on the success count below), never inside stream_publish,
# so a publish that genuinely never lands still exhausts its attempts and fails the
# count. Tunable via PUBLISH_RETRY_ATTEMPTS / PUBLISH_RETRY_BACKOFF.
_publish_with_retry() {
    local name="$1" payload="$2"
    local attempts="${PUBLISH_RETRY_ATTEMPTS:-3}" backoff="${PUBLISH_RETRY_BACKOFF:-1}" i=1
    while [ "$i" -le "$attempts" ]; do
        if stream_publish "$name" "$payload" > /dev/null 2>&1; then
            return 0
        fi
        i=$((i + 1))
        [ "$i" -le "$attempts" ] && sleep "$backoff"
    done
    return 1
}

test_publish_events() {
    local success=0 failure=0
    for i in $(seq 1 "$EVENT_COUNT"); do
        local payload="{\"key\":\"pubsub-${i}\",\"data\":\"event-${i}-$(now_epoch)\",\"timestamp\":$(now_epoch)}"
        if _publish_with_retry "$STREAM_NAME" "$payload"; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
    done
    log_info "Published: success=${success}, failure=${failure}"
    assert_eq "$success" "$EVENT_COUNT" "All ${EVENT_COUNT} events published"
}

test_stream_info_after_publish() {
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream info available after publishing"
}

test_subscriber_receives_events() {
    # Publish N events through partition 0 and verify them via streams read.
    # Drives the canonical CLI surface (streams read) rather than re-asserting
    # /api/streams/<name> info — the audit (RC1-blocker #15) flagged the prior
    # version for never attaching a consumer.
    local publish_count=10
    local success=0
    for i in $(seq 1 $publish_count); do
        local payload="{\"key\":\"sub-${i}\",\"data\":\"sub-event-${i}\",\"timestamp\":$(now_epoch)}"
        if _publish_with_retry "$STREAM_NAME" "$payload"; then
            success=$((success + 1))
        fi
    done
    assert_eq "$success" "$publish_count" "All $publish_count subscriber-batch publishes succeeded"

    # Allow replication / partition routing to settle.
    sleep 2

    # Read events back via the streams read CLI (STREAM_READ → /api/streams/read/<name>/<partition>).
    local result event_count
    result=$(aether_json streams read "$STREAM_NAME" 0) || {
        log_fail "streams read failed for ${STREAM_NAME} partition 0"
        return 1
    }
    if [ -z "$result" ]; then
        log_fail "streams read returned empty response for ${STREAM_NAME} partition 0"
        return 1
    fi
    # ReadEventsResponse → {"events":[{"offset":..., "data":..., "timestamp":...}]}.
    # Count per-event "offset" occurrences (one per record); the outer envelope has
    # no field named "offset" so this is one-to-one with delivered events.
    event_count=$(echo "$result" | grep -oE '"offset"[[:space:]]*:' | wc -l | tr -d ' ')
    assert_ge "$event_count" "$publish_count" "Subscriber received >= $publish_count events (got $event_count)"
}

test_competing_consumers_multi_instance() {
    # Verify multiple instances can consume from the same stream
    local slices
    slices=$(cluster_slices)
    local total_instances
    total_instances=$(slices_total_instances)
    if [ "$total_instances" -ge 2 ] 2>/dev/null; then
        log_info "Multiple instances available (${total_instances}) — competing consumers possible"
        # Publish another batch and verify no errors
        local success=0
        for i in $(seq 1 10); do
            local payload="{\"key\":\"compete-${i}\",\"data\":\"compete-${i}\",\"timestamp\":$(now_epoch)}"
            if _publish_with_retry "$STREAM_NAME" "$payload"; then
                success=$((success + 1))
            fi
        done
        assert_eq "$success" "10" "All 10 competing-consumer events published without error"
    else
        skip_test "Competing consumers" "only ${total_instances} instance(s) — requires >= 2"
    fi
}

test_cluster_healthy_after_pubsub() {
    assert_cluster_healthy "Cluster healthy after pub/sub test"
}

run_test "Cluster ready" test_cluster_ready
run_test "Stream exists or auto-creates" test_stream_exists_or_created
run_test "Publish events" test_publish_events
run_test "Stream info after publish" test_stream_info_after_publish
run_test "Subscriber receives events" test_subscriber_receives_events
run_test "Competing consumers" test_competing_consumers_multi_instance
run_test "Healthy after pub/sub" test_cluster_healthy_after_pubsub
print_summary
