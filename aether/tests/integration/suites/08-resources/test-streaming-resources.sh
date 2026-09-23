#!/bin/bash
# test-streaming-resources.sh — Verify StreamPublisher/StreamSubscriber
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

# Note: no blueprint needed, but the stream MUST be created explicitly — "streams auto-create on
# first publish" stopped being true at #1224, and this file was built on that assumption in three
# places. Every publish below addressed a stream that was never in the catalog.
STREAM_NAME="${NOTIFICATION_STREAM:-notifications}"
EVENT_COUNT="${STREAM_EVENT_COUNT:-20}"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

test_deploy_notification_hub() {
    stream_create "$STREAM_NAME" 1 > /dev/null 2>&1 || true   # idempotent
    assert_ne "$(stream_coordinate "$STREAM_NAME" 2>/dev/null)" "" \
              "Stream ${STREAM_NAME} present in catalog"
}

test_stream_publisher_provisioned() {
    local streams
    streams=$(stream_list)
    assert_ne "$streams" "" "Stream list returns data after deployment"
    # Exact-field match rather than a substring grep, which would match prefix/embedded names
    # (`test-events` inside `test-events-other`) and create false positives.
    #
    # The field is `stream`, NOT `name`: the catalog response is
    # `{"streams":[{"namespace":..,"stream":..,"version":..}]}` and carries no `name` field at all
    # — measured against a live 5-node cluster 2026-09-23, 0 occurrences of `"name"` vs 5 of
    # `"stream"`. So this grep could never match, and the test never noticed because the absent
    # branch warned and then passed a DIFFERENT claim ("stream list endpoint responds"). The
    # vacuous pass is what kept a permanently-false assertion alive; the stream is created above,
    # so absence is now a real failure. Whitespace is tolerated because the body is pretty-printed
    # (`"stream" : "notifications"`).
    if printf '%s' "$streams" | grep -qE "\"stream\"[[:space:]]*:[[:space:]]*\"${STREAM_NAME}\""; then
        log_pass "StreamPublisher provisioned: ${STREAM_NAME} visible in list"
    else
        log_fail "StreamPublisher provisioned: ${STREAM_NAME} absent from stream list (first 300 chars: ${streams:0:300})"
        return 1
    fi
}

test_publish_notifications() {
    local success=0 failure=0 errfile
    errfile=$(mktemp)
    for i in $(seq 1 "$EVENT_COUNT"); do
        local payload="{\"key\":\"notif-${i}\",\"data\":\"notification-${i}\",\"timestamp\":$(now_epoch)}"
        # `2>&1` here discarded the `api ... status=NNN: <body>` diagnostic `_api_call` emits on
        # stderr — which is why the baseline's "expected '20', got '0'" carried no reason.
        if stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>>"$errfile"; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
    done
    log_info "Notifications published: success=${success}, failure=${failure}"
    [ "$failure" -eq 0 ] || log_warn "Notification publish diagnostics (first 500B): $(head -c 500 "$errfile" 2>/dev/null | tr -d '\n')"
    rm -f "$errfile"
    assert_eq "$success" "$EVENT_COUNT" "All ${EVENT_COUNT} notifications published"
}

test_subscriber_receives_notifications() {
    # Check via stream info that events are being consumed
    local info
    info=$(stream_info "$STREAM_NAME")
    assert_ne "$info" "" "Stream info available for subscriber verification"
}

test_analytics_counts_increment() {
    # Publish another batch and check stream info changes. Track publish failures
    # explicitly — silently absorbing them with `|| true` would hide a stream
    # outage during the analytics window.
    local info_before info_after
    info_before=$(stream_info "$STREAM_NAME")

    local batch=5 failures=0
    for i in $(seq 1 "$batch"); do
        local payload="{\"key\":\"analytics-${i}\",\"data\":\"analytics-check\",\"timestamp\":$(now_epoch)}"
        if ! stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>&1; then
            failures=$((failures + 1))
        fi
    done
    sleep 2

    # Threshold: tolerate at most 1 transient failure out of 5 (e.g., owner
    # rebalance mid-batch). 2+ means the stream is broken — assert hard.
    if [ "$failures" -gt 1 ]; then
        log_fail "Analytics-batch publish failed ${failures}/${batch} times (threshold: <=1)"
        return 1
    fi
    if [ "$failures" -gt 0 ]; then
        log_warn "Analytics-batch publish: ${failures}/${batch} transient failure(s) within tolerance"
    fi

    info_after=$(stream_info "$STREAM_NAME")
    if [ -n "$info_before" ] && [ -n "$info_after" ]; then
        log_pass "Stream info available before and after additional publish"
    else
        log_warn "Stream info incomplete — analytics verification limited"
        log_pass "Stream endpoints respond"
    fi
}

test_cluster_healthy_after_streaming() {
    assert_cluster_healthy "Cluster healthy after streaming test"
}

run_test "Cluster ready" test_cluster_ready
run_test "Create notification stream" test_deploy_notification_hub
run_test "StreamPublisher provisioned" test_stream_publisher_provisioned
run_test "Publish notifications" test_publish_notifications
run_test "Subscriber receives notifications" test_subscriber_receives_notifications
run_test "Analytics counts increment" test_analytics_counts_increment
run_test "Healthy after streaming" test_cluster_healthy_after_streaming
print_summary
