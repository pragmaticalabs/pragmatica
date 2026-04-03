#!/bin/bash
# test-soak-4h.sh — Sustained load soak test against deployed application
# Exercises the full request pipeline: HTTP → route registry → slice invocation → response
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

SOAK_DURATION="${SOAK_DURATION:-14400}"  # 4 hours default
SOAK_RPS="${SOAK_RPS:-100}"             # 100 rps default — exercises GC, Netty buffers, routing
SOAK_LOG="/tmp/sustained_load_soak.log"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-1.0}"
BLUEPRINT="${SOAK_BLUEPRINT:-org.pragmatica.aether.example:url-shortener:1.0.0}"

test_cluster_baseline() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "${NODE_COUNT:-5}" "Baseline: ${count} nodes (>= ${NODE_COUNT:-5})"
}

test_deploy_app() {
    # Push and deploy blueprint so app endpoint is available
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    wait_for_slices_active 1 120
    log_pass "App deployed and slices active"
}

test_app_reachable() {
    # Verify the app HTTP endpoint is serving requests
    local status
    status=$(http_status "${APP_ENDPOINT}/api/v1/urls/test" -H "X-API-Key: ${API_KEY}")
    # 404 is fine — endpoint exists but no shortened URL; anything other than connection failure
    if [ "$status" -gt 0 ] 2>/dev/null; then
        log_pass "App endpoint reachable (status: ${status})"
    else
        log_fail "App endpoint not reachable"
        return 1
    fi
}

test_soak_load() {
    local start_nodes
    start_nodes=$(cluster_node_count)

    log_info "Starting ${SOAK_DURATION}s soak at ${SOAK_RPS} rps against app endpoint"
    rm -f "$SOAK_LOG"

    # Dual load: app endpoint (full pipeline) + management health (cluster stability)
    start_sustained_load "$SOAK_RPS" "$SOAK_DURATION" "GET" "/api/v1/urls/soak-test" "" "$SOAK_LOG"
    APP_ENDPOINT="${CLUSTER_ENDPOINT}" start_sustained_load "10" "$SOAK_DURATION" "GET" "/health/live" "" "/tmp/sustained_health_soak.log"

    log_info "Soak running — ${SOAK_DURATION}s ($(( SOAK_DURATION / 60 ))min)"
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Soak error rate below ${MAX_ERROR_RATE}%"
}

test_no_node_drift() {
    local end_nodes
    end_nodes=$(cluster_node_count)
    assert_ge "$end_nodes" "${NODE_COUNT:-5}" "No node drift: ${end_nodes} nodes (>= ${NODE_COUNT:-5})"
}

test_cluster_still_healthy() {
    assert_cluster_healthy "Cluster still healthy after soak"
}

test_no_leader_change() {
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader still present after soak"
}

run_test "Cluster baseline" test_cluster_baseline
run_test "Deploy app" test_deploy_app
run_test "App reachable" test_app_reachable
run_test "Soak under load" test_soak_load
run_test "No node drift" test_no_node_drift
run_test "Cluster still healthy" test_cluster_still_healthy
run_test "Leader still present" test_no_leader_change
print_summary
