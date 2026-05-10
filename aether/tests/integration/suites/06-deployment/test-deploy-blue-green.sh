#!/bin/bash
# test-deploy-blue-green.sh — Blue-green deployment via unified deploy command (v1 → v2 upgrade)
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

test_blue_green_start() {
    deploy_cleanup
    # v1 must be currently active, v2 must be registered (but not active) for the upgrade
    push_blueprint "$BLUEPRINT_V1"
    deploy_blueprint "$BLUEPRINT_V1"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "v1 deploy did not quiesce"
    push_blueprint "$BLUEPRINT_V2"
    publish_blueprint "$BLUEPRINT_V2"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "v2 publish did not quiesce"
    local result
    result=$(deploy_start "$BLUEPRINT_V2" blue-green --instances 2)
    assert_contains "$result" "deploymentId" "Blue-green started with deployment ID"
}

test_blue_green_promote() {
    local deployments did
    deployments=$(deploy_list)
    did=$(deploy_extract_id "$deployments")
    assert_ne "$did" "" "Got deployment ID"
    deploy_promote "$did"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "promote did not quiesce"
    local status_result
    status_result=$(deploy_status "$did")
    log_info "Deployment status after promote (switch): $status_result"
}

test_blue_green_rollback() {
    local deployments did
    deployments=$(deploy_list)
    did=$(deploy_extract_id "$deployments")
    assert_ne "$did" "" "Got deployment ID"
    deploy_rollback "$did"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "rollback did not quiesce"
    local status_result
    status_result=$(deploy_status "$did")
    log_info "Deployment status after rollback (switch back): $status_result"
}

test_blue_green_complete() {
    local deployments did
    deployments=$(deploy_list)
    did=$(deploy_extract_id "$deployments")
    assert_ne "$did" "" "Got deployment ID"
    local result
    result=$(deploy_complete "$did")
    assert_contains "$result" "COMPLETED" "Blue-green completed"
}

cleanup() {
    # Restore baseline v1.0.0 ACTIVE so the next test (rolling) can cleanly upgrade.
    deploy_cleanup || true
    deploy_blueprint "$BLUEPRINT_V1" >/dev/null 2>&1 || \
        log_warn "cleanup: failed to revert active version to ${BLUEPRINT_V1}"
}

run_test "Cluster ready" test_cluster_ready
run_test "Blue-green start" test_blue_green_start
run_test "Blue-green promote (switch)" test_blue_green_promote
run_test "Blue-green complete" test_blue_green_complete
cleanup
print_summary
