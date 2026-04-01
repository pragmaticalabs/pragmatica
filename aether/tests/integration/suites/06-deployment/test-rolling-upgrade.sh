#!/bin/bash
# test-rolling-upgrade.sh — Rolling upgrade version under load
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

ARTIFACT_VERSION="${ARTIFACT_VERSION:-1.0.0-alpha}"
NEW_VERSION="${NEW_VERSION:-0.25.1}"
ARTIFACT_BASE="org.pragmatica.aether.example:url-shortener-url-shortener"
LOAD_DURATION="${LOAD_DURATION:-30}"
LOAD_RPS="${LOAD_RPS:-5}"
MAX_ERROR_RATE="${MAX_ERROR_RATE:-5.0}"

setup_v2_artifacts() {
    log_info "Uploading v${NEW_VERSION} artifacts for deployment strategy test"

    local project_root
    project_root="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

    local analytics_jar="${project_root}/examples/url-shortener/target/url-shortener-analytics-${ARTIFACT_VERSION}.jar"
    local shortener_jar="${project_root}/examples/url-shortener/target/url-shortener-url-shortener-${ARTIFACT_VERSION}.jar"
    local blueprint_jar="${project_root}/examples/url-shortener/target/url-shortener-${ARTIFACT_VERSION}-blueprint.jar"

    aether_failover artifact deploy -g org.pragmatica.aether.example -a url-shortener-analytics -v "${NEW_VERSION}" "$analytics_jar" > /dev/null 2>&1
    aether_failover artifact deploy -g org.pragmatica.aether.example -a url-shortener-url-shortener -v "${NEW_VERSION}" "$shortener_jar" > /dev/null 2>&1
    aether_failover artifact deploy -g org.pragmatica.examples -a url-shortener -v "${NEW_VERSION}" "$blueprint_jar" > /dev/null 2>&1

    sleep 10
    log_info "v${NEW_VERSION} artifacts uploaded"
}

test_cluster_ready() {
    wait_for_cluster 60
    wait_for_node_count 5 30
}

test_start_rolling_update() {
    setup_v2_artifacts
    local body="{\"artifactBase\":\"${ARTIFACT_BASE}\",\"version\":\"${NEW_VERSION}\",\"instances\":3}"
    local result
    result=$(rolling_update_start "$body")
    assert_ne "$result" "" "Rolling update started"

    # Extract update ID
    UPDATE_ID=$(echo "$result" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for field in ['updateId', 'id', 'deploymentId']:
    if field in data:
        print(data[field])
        sys.exit(0)
print('')
" 2>/dev/null)
    log_info "Update ID: ${UPDATE_ID}"
}

test_rolling_update_under_load() {
    # Start load during the update (management endpoint)
    start_mgmt_load "$LOAD_RPS" "$LOAD_DURATION" "/api/health"

    # Adjust routing to shift all traffic to new version, then complete
    sleep 5
    api_post "/api/rolling-update/${UPDATE_ID}/routing" "{\"routing\":\"0:1\"}" > /dev/null 2>&1
    sleep 5
    api_post "/api/rolling-update/${UPDATE_ID}/complete" "{}" > /dev/null 2>&1

    # Wait for rolling update to complete
    wait_for "rolling update complete" \
        "rolling_update_status '${UPDATE_ID:-unknown}' | grep -qi 'complete\|finished\|done'" 60 5

    # Wait for load to finish
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Rolling update error rate < ${MAX_ERROR_RATE}%"
}

test_all_nodes_updated() {
    wait_for_node_count 5 30
}

test_cluster_healthy_after_update() {
    assert_cluster_healthy "Cluster healthy after rolling update"
}

test_slices_active_after_update() {
    wait_for_slices_active 1 60
    local instances
    instances=$(slices_total_instances)
    assert_gt "$instances" "0" "Slices active after rolling update"
}

run_test "Cluster ready" test_cluster_ready
run_test "Start rolling update" test_start_rolling_update
run_test "Rolling update under load" test_rolling_update_under_load
run_test "All nodes updated" test_all_nodes_updated
run_test "Cluster healthy" test_cluster_healthy_after_update
run_test "Slices active" test_slices_active_after_update
print_summary
