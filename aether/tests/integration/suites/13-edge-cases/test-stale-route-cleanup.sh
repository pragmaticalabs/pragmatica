#!/bin/bash
# test-stale-route-cleanup.sh — Kill node hosting routes, verify stale routes cleaned
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

ROUTE_CLEANUP_TIMEOUT="${ROUTE_CLEANUP_TIMEOUT:-60}"
BLUEPRINT="org.pragmatica.aether.test:test-echo:1.0.0"

test_cluster_ready() {
    wait_for_cluster 60
    # ClusterGeneration barrier: whatever churn a prior destructive suite left is
    # committed to a stable generation before we deploy. No rescale-fallback needed.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 60 || log_warn "pre-deploy snapshot not quiesced"
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 60 || log_warn "deploy did not quiesce"
    wait_for_slices_active 1 120 || log_warn "Slices not active after deploy"
    log_pass "Cluster ready with baseline blueprint deployed"
}

test_slices_deployed() {
    wait_for_slices_active 1 120
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices deployed: ${instances} instances"
}

test_app_routes_reachable() {
    # Probe the actual app-route surface — EchoSlice's /api/echo/health route
    # (under its declared prefix). Bare /health hits AppHttpServer's synthetic
    # liveness intercept (returns 200 regardless of slice deployment) and
    # therefore proves nothing about route wiring. The prefixed path goes
    # through the route table. app_route_wired (positive readiness)
    # distinguishes route-missing 404 from a real handler response. NO
    # management-API fallback: this test exists to prove the APP route is
    # wired; falling back to /api/status would swap subjects (mgmt port
    # healthy != app route reachable) and pass falsely.
    if ! app_route_wired "${APP_ENDPOINT}/api/echo/health" "${API_KEY}"; then
        log_fail "App route ${APP_ENDPOINT}/api/echo/health not wired (expected EchoSlice handler to respond)"
        return 1
    fi
    log_pass "App route /api/echo/health wired (positive readiness via app_route_wired)"
}

test_kill_node_hosting_routes() {
    local leader
    leader=$(cluster_leader)

    local nodes
    nodes=$(cluster_node_list)
    local victim
    victim=""
    for field in nodeId id; do
        local candidates
        candidates=$(echo "$nodes" | grep -o "\"${field}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | sed "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/")
        while IFS= read -r nid; do
            if [ -n "$nid" ] && [ "$nid" != "$leader" ]; then
                victim="$nid"
                break 2
            fi
        done <<< "$candidates"
    done

    if [ -z "$victim" ]; then
        victim="node-2"
    fi

    log_info "Killing node with potential routes: ${victim}"
    kill_node "$victim"
    # Legitimate chaos window: failure detection needs a few seconds.
    sleep 5

    # ClusterGeneration barrier: routes are fenced by epoch. When the next
    # generation quiesces, stale-route cleanup is complete.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" "$ROUTE_CLEANUP_TIMEOUT" || \
        log_warn "Post-kill quiescence not reached within ${ROUTE_CLEANUP_TIMEOUT}s"
    log_pass "Route cleanup fenced by generation advance"
}

test_no_502_504_after_cleanup() {
    # The killed node hosted APP routes — probing /health hits the synthetic
    # AppHttpServer liveness intercept (always 200) and would not surface a
    # stale-route 502/504. Probe the actual APP route (EchoSlice
    # /api/echo/health) instead so a stale-route 502/504 (from
    # sendNoRouteFound or upstream proxy) actually surfaces. Previous test
    # already waited for generation quiescence.
    local bad_status=0
    for i in $(seq 1 10); do
        local status
        status=$(http_status "${APP_ENDPOINT}/api/echo/health" -H "X-API-Key: ${API_KEY}")
        if [ "$status" = "502" ] || [ "$status" = "504" ]; then
            bad_status=$((bad_status + 1))
        fi
        sleep 1
    done
    assert_eq "$bad_status" "0" "No 502/504 on APP route /api/echo/health after cleanup (${bad_status}/10 bad)"
}

test_kv_store_routes_clean() {
    # Verify slices endpoint still responds correctly
    local slices
    slices=$(cluster_slices)
    assert_ne "$slices" "" "Slices endpoint responds after route cleanup"
}

test_recovery_complete() {
    wait_for_node_count 5 90
    assert_cluster_healthy "Cluster recovered after stale route cleanup"
}

run_test "Cluster ready" test_cluster_ready
run_test "Slices deployed" test_slices_deployed
run_test "App routes reachable" test_app_routes_reachable
run_test "Kill node hosting routes" test_kill_node_hosting_routes
run_test "No 502/504 after cleanup" test_no_502_504_after_cleanup
run_test "KV store routes clean" test_kv_store_routes_clean
run_test "Recovery complete" test_recovery_complete
print_summary
