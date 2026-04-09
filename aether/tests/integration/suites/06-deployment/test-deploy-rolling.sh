#!/bin/bash
# test-deploy-rolling.sh — Rolling deployment via unified deploy command (v1 → v2 upgrade)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT_V1="org.pragmatica.aether.example:url-shortener:1.0.0"
BLUEPRINT_V2="org.pragmatica.aether.example:url-shortener:1.0.1"

test_cluster_ready() {
    wait_for_cluster 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

test_rolling_start() {
    deploy_cleanup
    push_blueprint "$BLUEPRINT_V1"
    deploy_blueprint "$BLUEPRINT_V1"
    sleep 3
    push_blueprint "$BLUEPRINT_V2"
    publish_blueprint "$BLUEPRINT_V2"
    sleep 2
    local result
    result=$(deploy_start "$BLUEPRINT_V2" rolling --instances 2)
    assert_contains "$result" "deploymentId" "Rolling deployment started with deployment ID"
}

test_rolling_promote() {
    local deployments did
    deployments=$(deploy_list)
    did=$(deploy_extract_id "$deployments")
    assert_ne "$did" "" "Got deployment ID"
    deploy_promote "$did"
    sleep 5
    local status_result
    status_result=$(deploy_status "$did")
    log_info "Deployment status after promote: $status_result"
}

test_rolling_complete() {
    local deployments did
    deployments=$(deploy_list)
    did=$(deploy_extract_id "$deployments")
    assert_ne "$did" "" "Got deployment ID"
    local result
    result=$(deploy_complete "$did")
    assert_contains "$result" "COMPLETED" "Rolling deployment completed"
}

cleanup() {
    deploy_cleanup
}

run_test "Cluster ready" test_cluster_ready
run_test "Rolling start" test_rolling_start
run_test "Rolling promote" test_rolling_promote
run_test "Rolling complete" test_rolling_complete
cleanup
print_summary
