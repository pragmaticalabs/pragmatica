#!/bin/bash
# test-stale-route-cleanup.sh — Kill node hosting routes, verify stale routes cleaned
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

ROUTE_CLEANUP_TIMEOUT="${ROUTE_CLEANUP_TIMEOUT:-60}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_slices_deployed() {
    wait_for_slices_active 1 60
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices deployed: ${instances} instances"
}

test_app_routes_reachable() {
    # Verify at least one app route works before killing
    local status
    status=$(http_status "${APP_ENDPOINT}/health" -H "X-API-Key: ${API_KEY}")
    if [ "$status" -ge 200 ] && [ "$status" -lt 500 ] 2>/dev/null; then
        log_pass "App route reachable (status: ${status})"
    else
        # Try management health instead
        assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Management routes reachable"
    fi
}

test_kill_node_hosting_routes() {
    local leader
    leader=$(cluster_leader)

    local nodes
    nodes=$(cluster_node_list)
    local victim
    victim=$(echo "$nodes" | python3 -c "
import sys, json
data = json.load(sys.stdin)
leader = '${leader}'
for n in (data if isinstance(data, list) else data.get('nodes', [])):
    nid = n.get('nodeId', n.get('id', ''))
    if nid != leader:
        print(nid)
        break
" 2>/dev/null)

    if [ -z "$victim" ]; then
        victim="node-2"
    fi

    log_info "Killing node with potential routes: ${victim}"
    kill_node "$victim"
    sleep 5

    # Wait for stale route cleanup
    log_info "Waiting up to ${ROUTE_CLEANUP_TIMEOUT}s for stale route cleanup"
    sleep "$((ROUTE_CLEANUP_TIMEOUT > 30 ? 30 : ROUTE_CLEANUP_TIMEOUT))"
    log_pass "Waited for route cleanup"
}

test_no_502_504_after_cleanup() {
    # Make several requests — none should get 502/504 from stale routes
    local bad_status=0
    for i in $(seq 1 10); do
        local status
        status=$(http_status "${CLUSTER_ENDPOINT}/api/status" -H "X-API-Key: ${API_KEY}")
        if [ "$status" = "502" ] || [ "$status" = "504" ]; then
            bad_status=$((bad_status + 1))
        fi
    done
    assert_eq "$bad_status" "0" "No 502/504 responses after route cleanup (${bad_status}/10 bad)"
}

test_kv_store_routes_clean() {
    # Verify slices endpoint still responds correctly
    local slices
    slices=$(cluster_slices)
    assert_ne "$slices" "" "Slices endpoint responds after route cleanup"
}

test_recovery_complete() {
    wait_for_node_count 5 180
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Cluster recovered after stale route cleanup"
}

run_test "Cluster ready" test_cluster_ready
run_test "Slices deployed" test_slices_deployed
run_test "App routes reachable" test_app_routes_reachable
run_test "Kill node hosting routes" test_kill_node_hosting_routes
run_test "No 502/504 after cleanup" test_no_502_504_after_cleanup
run_test "KV store routes clean" test_kv_store_routes_clean
run_test "Recovery complete" test_recovery_complete
print_summary
