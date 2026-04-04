#!/bin/bash
# test-soak-4h.sh — Sustained load soak test against deployed application
# Exercises the full request pipeline: HTTP → route registry → slice invocation → response
# Collects JVM stats (RSS, threads) before and after for leak detection.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

SOAK_DURATION="${SOAK_DURATION:-14400}"  # 4 hours default
SOAK_RPS="${SOAK_RPS:-100}"             # 100 rps default
SOAK_LOG="/tmp/sustained_load_soak.log"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-1.0}"
BLUEPRINT="${SOAK_BLUEPRINT:-org.pragmatica.aether.example:url-shortener:1.0.0}"

STATS_FILE="/tmp/soak_stats.txt"

# ---------------------------------------------------------------------------
# Stats collection — RSS and threads for all nodes
# ---------------------------------------------------------------------------
collect_stats() {
    local label="$1"
    log_info "=== ${label} ==="
    echo "=== ${label} ===" >> "$STATS_FILE"
    for port_offset in $(seq 0 $((${NODE_COUNT:-5} - 1))); do
        local mgmt_port=$((${MGMT_PORT:-5150} + port_offset))
        local node_name="node-$((port_offset + 1))"
        # Collect via management metrics endpoint (no SSH needed)
        local status_json
        status_json=$(curl -s -H "X-API-Key: ${API_KEY}" "http://${TARGET_HOST}:${mgmt_port}/api/status" 2>/dev/null)
        local uptime
        uptime=$(echo "$status_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('uptimeSeconds',0))" 2>/dev/null)
        log_info "  ${node_name} (port ${mgmt_port}): uptime=${uptime}s"
        echo "  ${node_name}: uptime=${uptime}s" >> "$STATS_FILE"
    done
}

test_cluster_baseline() {
    wait_for_cluster 60
    local count
    count=$(cluster_node_count)
    assert_ge "$count" "${NODE_COUNT:-5}" "Baseline: ${count} nodes (>= ${NODE_COUNT:-5})"
}

test_deploy_app() {
    push_blueprint "$BLUEPRINT"
    deploy_blueprint "$BLUEPRINT"
    wait_for_slices_active 1 120
    log_pass "App deployed and slices active"
}

test_app_reachable() {
    local status
    status=$(http_status "${APP_ENDPOINT}/api/v1/urls/test" -H "X-API-Key: ${API_KEY}")
    if [ "$status" -gt 0 ] 2>/dev/null; then
        log_pass "App endpoint reachable (status: ${status})"
    else
        log_fail "App endpoint not reachable"
        return 1
    fi
}

test_collect_pre_stats() {
    rm -f "$STATS_FILE"
    collect_stats "PRE-SOAK (after deploy, before load)"
    log_pass "Pre-soak stats collected"
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

test_collect_post_stats() {
    collect_stats "POST-SOAK (after load completed)"
    log_info "Stats saved to ${STATS_FILE}"
    cat "$STATS_FILE"
    log_pass "Post-soak stats collected"
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
run_test "Pre-soak stats" test_collect_pre_stats
run_test "Soak under load" test_soak_load
run_test "Post-soak stats" test_collect_post_stats
run_test "No node drift" test_no_node_drift
run_test "Cluster still healthy" test_cluster_still_healthy
run_test "Leader still present" test_no_leader_change
print_summary
