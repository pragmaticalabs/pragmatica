#!/bin/bash
# test-deploy-canary.sh — Canary deployment via unified deploy command (v1 → v2 upgrade)
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

test_canary_start() {
    deploy_cleanup
    # Baseline v1 must be active for canary upgrade to v2
    push_blueprint "$BLUEPRINT_V1"
    deploy_blueprint "$BLUEPRINT_V1"
    sleep 3
    push_blueprint "$BLUEPRINT_V2"
    publish_blueprint "$BLUEPRINT_V2"
    sleep 2
    local result
    result=$(deploy_start "$BLUEPRINT_V2" canary --traffic 5 --instances 1)
    assert_contains "$result" "deploymentId" "Canary started with deployment ID"
}

test_canary_list() {
    local list
    list=$(deploy_list)
    assert_contains "$list" "CANARY" "Active canary in deployment list"
}

test_canary_promote() {
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

test_canary_complete() {
    local deployments did
    deployments=$(deploy_list)
    did=$(deploy_extract_id "$deployments")
    local result
    result=$(deploy_complete "$did")
    assert_contains "$result" "COMPLETED" "Canary completed"
}

cleanup() {
    deploy_cleanup
}

run_test "Cluster ready" test_cluster_ready
run_test "Canary start" test_canary_start
run_test "Canary list" test_canary_list
run_test "Canary promote" test_canary_promote
run_test "Canary complete" test_canary_complete
cleanup
print_summary
