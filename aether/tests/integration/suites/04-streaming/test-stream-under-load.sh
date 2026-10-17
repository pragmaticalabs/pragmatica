#!/bin/bash
# test-stream-under-load.sh — Sustained publish/consume, verify lag
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

STREAM_NAME="${STREAM_NAME:-load-test-stream}"
STREAM_DURATION="${STREAM_DURATION:-30}"  # 30 seconds default
STREAM_RPS="${STREAM_RPS:-10}"
# Cap RPS to prevent OOM from excessive concurrent requests
max_rps=100
if [ "$STREAM_RPS" -gt "$max_rps" ] 2>/dev/null; then
    STREAM_RPS="$max_rps"
fi
# Mid-flight ops tier — see aether/docs/specs/test-readiness-contract.md §4 (5.0%).
# Streaming reconfiguration during concurrent load; partition reassignment + retries.
MAX_ERROR_RATE="${MAX_ERROR_RATE:-5.0}"

test_cluster_ready() {
    wait_for_cluster_ready 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

# The stream must exist in the CATALOG before anything addresses it: `stream_publish`/`stream_info`
# resolve a name to its coordinate first, and the publish route is coordinate-shaped. Nothing in
# this suite created `load-test-stream`, so every publish below addressed a stream that was never
# registered. Mirrors the create+catalog-assert pattern of test-stream-replication.sh.
test_create_stream() {
    stream_create "$STREAM_NAME" 1 > /dev/null 2>&1 || true   # idempotent; a repeat is not a failure
    assert_ne "$(stream_coordinate "$STREAM_NAME" 2>/dev/null)" "" \
              "Stream ${STREAM_NAME} present in catalog before load"
}

test_sustained_stream_publish() {
    log_info "Sustained stream publish: ${STREAM_RPS} rps for ${STREAM_DURATION}s"

    # The publish route is `/streams/{namespace}/{stream}/{version}/publish` since the 2026-09-02
    # catalog migration (7a523c9e3). The flat `/streams/publish/{name}` used here has been a 400
    # ever since — measured as a 100.00% error rate in the 2026-09-23 baseline, reported as a
    # product-level "stream publish is broken" because `http_status` discarded the body.
    local coord
    coord=$(stream_coordinate "$STREAM_NAME") || {
        log_fail "stream_coordinate ${STREAM_NAME} failed — stream absent from the catalog"
        return 1
    }

    local interval
    interval=$(awk "BEGIN {printf \"%.4f\", 1.0/${STREAM_RPS}}" 2>/dev/null || echo "0.05")
    local end_time=$(($(now_epoch) + $STREAM_DURATION))
    local success=0 failure=0 count=0

    while [ "$(now_epoch)" -lt "$end_time" ]; do
        local payload="{\"key\":\"load-${count}\",\"data\":\"sustained-publish-${count}\",\"timestamp\":$(now_epoch)}"
        local status probe
        # Surface the response body for the FIRST failure only. A blind `http_status` is what made
        # the baseline's 100%-error run undiagnosable; dumping all ~300 bodies would flood the log
        # and bury it just as effectively. One body is the diagnosis; the rest are a count.
        if [ "$failure" -eq 0 ]; then probe=http_status_with_body; else probe=http_status; fi
        status=$("$probe" "${CLUSTER_ENDPOINT}/api/v1/streams/${coord}/publish" \
            -X POST \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "$payload")

        # Strict 2xx only — 3xx (redirects) is not a successful publish.
        if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
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
    count=$(cluster_member_count)
    assert_eq "$count" "5" "Cluster stable: 5 nodes after stream load"
    assert_cluster_healthy "Cluster healthy after stream load"
}

test_concurrent_publish_and_query() {
    # Publish while simultaneously querying stream info
    local pub_ok=true query_ok=true errfile
    errfile=$(mktemp)
    for i in $(seq 1 20); do
        local payload="{\"key\":\"concurrent-${i}\",\"data\":\"test\",\"timestamp\":$(now_epoch)}"
        # `2>&1` here discarded the `api ... status=NNN: <body>` diagnostic that `_api_call`
        # already emits on stderr, which is why the baseline's "Concurrent publish had failures"
        # carried no reason at all. Keep stderr and surface the first one.
        stream_publish "$STREAM_NAME" "$payload" > /dev/null 2>>"$errfile" || pub_ok=false
        stream_info "$STREAM_NAME" > /dev/null 2>>"$errfile" || query_ok=false
    done
    local diag
    diag=$(head -c 500 "$errfile" 2>/dev/null | tr -d '\n')
    rm -f "$errfile"

    # The diagnostics ride on the failure line rather than a standalone warning: the reason a
    # failure happened belongs to that failure, and a bare warning sitting next to a passing
    # assertion is exactly the demotion shape the test linter's R1 rule exists to catch.
    if [ "$pub_ok" = true ]; then
        log_pass "Concurrent publish succeeded"
    else
        log_fail "Concurrent publish had failures :: ${diag:-<no stderr captured>}"
        return 1
    fi

    if [ "$query_ok" = true ]; then
        log_pass "Concurrent query succeeded"
    else
        log_fail "Concurrent query had failures :: ${diag:-<no stderr captured>}"
        return 1
    fi
}

run_test "Cluster ready" test_cluster_ready
run_test "Create stream" test_create_stream
run_test "Sustained stream publish" test_sustained_stream_publish
run_test "Stream info after load" test_stream_info_after_load
run_test "Cluster stable" test_cluster_stable
run_test "Concurrent publish and query" test_concurrent_publish_and_query
print_summary
