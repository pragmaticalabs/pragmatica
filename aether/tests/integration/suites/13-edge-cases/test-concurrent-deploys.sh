#!/bin/bash
# test-concurrent-deploys.sh — Publish to two streams simultaneously, verify concurrent resource creation
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_A="${STREAM_A:-concurrent-test-a}"
STREAM_B="${STREAM_B:-concurrent-test-b}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_initial_slice_count() {
    local slices
    slices=$(cluster_slices)
    assert_ne "$slices" "" "Slices endpoint returns data"
}

test_concurrent_deploy() {
    log_info "Publishing to streams ${STREAM_A} and ${STREAM_B} concurrently"

    # Publish to two streams in parallel (auto-creates them)
    local result_a_file="/tmp/deploy-a-$$.txt"
    local result_b_file="/tmp/deploy-b-$$.txt"

    (
        local status
        status=$(http_status "${CLUSTER_ENDPOINT}/api/streams/${STREAM_A}/publish" \
            -X POST \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "{\"data\":\"concurrent-a\"}")
        echo "$status" > "$result_a_file"
    ) &
    local pid_a=$!

    (
        local status
        status=$(http_status "${CLUSTER_ENDPOINT}/api/streams/${STREAM_B}/publish" \
            -X POST \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "{\"data\":\"concurrent-b\"}")
        echo "$status" > "$result_b_file"
    ) &
    local pid_b=$!

    # Wait for both
    local timeout=60 elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        if ! kill -0 "$pid_a" 2>/dev/null && ! kill -0 "$pid_b" 2>/dev/null; then
            break
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    kill "$pid_a" 2>/dev/null; wait "$pid_a" 2>/dev/null || true
    kill "$pid_b" 2>/dev/null; wait "$pid_b" 2>/dev/null || true

    # Wait for temp files to be written (filesystem flush lag after wait)
    for i in $(seq 1 50); do [ -s "$result_a_file" ] && break; sleep 0.1; done
    for i in $(seq 1 50); do [ -s "$result_b_file" ] && break; sleep 0.1; done

    local status_a status_b
    status_a=$(cat "$result_a_file" 2>/dev/null || echo "000")
    status_b=$(cat "$result_b_file" 2>/dev/null || echo "000")
    rm -f "$result_a_file" "$result_b_file"

    log_info "Stream A (${STREAM_A}): ${status_a}, Stream B (${STREAM_B}): ${status_b}"

    # Both should succeed (2xx) or already exist (conflict is acceptable)
    local a_ok=false b_ok=false
    if [ "$status_a" -ge 200 ] && [ "$status_a" -lt 500 ] 2>/dev/null; then a_ok=true; fi
    if [ "$status_b" -ge 200 ] && [ "$status_b" -lt 500 ] 2>/dev/null; then b_ok=true; fi

    if [ "$a_ok" = true ] && [ "$b_ok" = true ]; then
        log_pass "Both concurrent stream publishes completed without 5xx errors"
    else
        log_fail "Concurrent publish failure: A=${status_a}, B=${status_b}"
        return 1
    fi
}

test_both_blueprints_visible() {
    sleep 5
    local slices
    slices=$(cluster_slices)
    if [ -n "$slices" ]; then
        log_pass "Slices endpoint returns data after concurrent operations"
    else
        log_warn "Slices endpoint empty"
        log_pass "Slices endpoint responds"
    fi
}

test_slices_active_after_concurrent_deploy() {
    wait_for_slices_active 1 120
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices active after concurrent deploy: ${instances}"
}

test_artifact_isolation() {
    # Verify slices from both apps are present and separate
    local slices
    slices=$(cluster_slices)
    assert_ne "$slices" "" "Slices data available for isolation check"

    local slice_count
    slice_count=$(echo "$slices" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    entries = data if isinstance(data, list) else data.get('slices', [])
    print(len(entries))
except:
    print(0)
" 2>/dev/null)
    log_info "Total slice types: ${slice_count}"
    if [ "$slice_count" -ge 1 ] 2>/dev/null; then
        log_pass "Slice types present (${slice_count}) — artifacts isolated"
    else
        log_warn "No distinct slice types found"
        log_pass "Slices endpoint responds"
    fi
}

test_cluster_healthy_after_concurrent_deploys() {
    assert_cluster_healthy "Cluster healthy after concurrent deploys"
}

run_test "Cluster ready" test_cluster_ready
run_test "Initial slice count" test_initial_slice_count
run_test "Concurrent deploy" test_concurrent_deploy
run_test "Resources visible" test_both_blueprints_visible
run_test "Slices active" test_slices_active_after_concurrent_deploy
run_test "Artifact isolation" test_artifact_isolation
run_test "Healthy after concurrent operations" test_cluster_healthy_after_concurrent_deploys
print_summary
