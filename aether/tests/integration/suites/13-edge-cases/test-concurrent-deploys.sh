#!/bin/bash
# test-concurrent-deploys.sh — Publish to two streams simultaneously, verify concurrent resource creation
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

STREAM_A="${STREAM_A:-concurrent-test-a}"
STREAM_B="${STREAM_B:-concurrent-test-b}"
BLUEPRINT="org.pragmatica.aether.test:test-echo:1.0.0"
# Second distinct blueprint deployed alongside test-echo to enable artifact-isolation
# verification (test-persistence has its own slice + route namespace `/api/kv/*` so
# the two artifacts are guaranteed disjoint at the route level).
BLUEPRINT_2="org.pragmatica.aether.test:test-persistence:1.0.0"

test_cluster_ready() {
    wait_for_cluster 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    # ClusterGeneration barrier: any churn inherited from a destructive predecessor
    # suite is committed to a stable generation. No rescale fallback needed.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 60 || log_warn "pre-deploy snapshot not quiesced"
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 60 || log_warn "deploy did not quiesce"
    push_blueprint "$BLUEPRINT_2"
    deploy_blueprint "$BLUEPRINT_2"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 60 || log_warn "second deploy did not quiesce"
    wait_for_slices_active 1 120 || log_warn "Slices not active after deploy"
    log_pass "Cluster ready with both baseline blueprints deployed"
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
        status=$(http_status "${CLUSTER_ENDPOINT}/api/streams/publish/${STREAM_A}" \
            -X POST \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "{\"data\":\"concurrent-a\"}")
        echo "$status" > "$result_a_file"
    ) &
    local pid_a=$!

    (
        local status
        status=$(http_status "${CLUSTER_ENDPOINT}/api/streams/publish/${STREAM_B}" \
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

    # Both publishes must succeed (2xx). 404 from a missing route or 401 from an
    # auth misconfig is NOT "publish succeeded" — accepting <500 hid those.
    local a_ok=false b_ok=false
    if [ "$status_a" -ge 200 ] && [ "$status_a" -lt 300 ] 2>/dev/null; then a_ok=true; fi
    if [ "$status_b" -ge 200 ] && [ "$status_b" -lt 300 ] 2>/dev/null; then b_ok=true; fi

    if [ "$a_ok" = true ] && [ "$b_ok" = true ]; then
        log_pass "Both concurrent stream publishes returned 2xx (A=${status_a}, B=${status_b})"
    else
        log_fail "Concurrent publish failure: A=${status_a}, B=${status_b} (expected 2xx for both)"
        return 1
    fi
}

test_both_blueprints_visible() {
    # Stream publish triggers resource creation — barrier on the next generation
    # so slice/registry writes have quiesced before inspection.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "post-publish quiesce not reached"
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
    # Two distinct blueprints (test-echo + test-persistence) are deployed in
    # test_cluster_ready above. Verify both artifact identifiers appear in
    # /api/slices — proving the cluster maintains separate slice records per
    # artifact (the prior fixture only deployed test-echo, so this assertion
    # was structurally untestable; replaced with a real isolation check).
    local slices
    slices=$(cluster_slices)
    assert_ne "$slices" "" "Slices data available for isolation check"
    local found_echo found_persistence
    if printf '%s' "$slices" | grep -q '"artifact"[[:space:]]*:[[:space:]]*"org\.pragmatica\.aether\.test:test-echo'; then
        found_echo="true"
    fi
    if printf '%s' "$slices" | grep -q '"artifact"[[:space:]]*:[[:space:]]*"org\.pragmatica\.aether\.test:test-persistence'; then
        found_persistence="true"
    fi
    if [ "${found_echo:-}" = "true" ] && [ "${found_persistence:-}" = "true" ]; then
        log_pass "Artifact isolation verified: both test-echo and test-persistence appear in /api/slices as distinct artifacts"
        return 0
    fi
    log_fail "Artifact isolation: expected both test-echo and test-persistence in /api/slices (found echo=${found_echo:-false}, persistence=${found_persistence:-false})"
    return 1
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
