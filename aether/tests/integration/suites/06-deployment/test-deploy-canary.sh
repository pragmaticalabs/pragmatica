#!/bin/bash
# test-deploy-canary.sh — Canary deployment via unified deploy command (v1 → v2 upgrade)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT_V1="org.pragmatica.aether.example:url-shortener:1.0.0"
BLUEPRINT_V2="org.pragmatica.aether.example:url-shortener:1.0.1"

# Persist the deployment ID across test stages. `deploy_list` can omit an entry once its
# state transitions to terminal (COMPLETED/ROLLED_BACK/FAILED), so relying on the list to
# re-discover the ID after each stage is brittle. The start response is authoritative.
DEPLOYMENT_ID=""

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
    # ClusterGeneration barrier replaces sleep 3 (registry propagation race).
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "v1 deploy did not quiesce"
    push_blueprint "$BLUEPRINT_V2"
    publish_blueprint "$BLUEPRINT_V2"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "v2 publish did not quiesce"
    local result
    result=$(deploy_start "$BLUEPRINT_V2" canary --traffic 5 --instances 1)
    assert_contains "$result" "deploymentId" "Canary started with deployment ID"
    DEPLOYMENT_ID=$(deploy_extract_id "$result")
    assert_ne "$DEPLOYMENT_ID" "" "Captured deployment ID from start response"
}

test_canary_list() {
    local list
    list=$(deploy_list)
    assert_contains "$list" "CANARY" "Active canary in deployment list"
}

test_canary_promote() {
    assert_ne "$DEPLOYMENT_ID" "" "Have deployment ID"
    deploy_promote "$DEPLOYMENT_ID"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "promote did not quiesce"
    local status_result
    status_result=$(deploy_status "$DEPLOYMENT_ID" 2>/dev/null || echo "")
    log_info "Deployment status after promote: $status_result"
}

test_canary_complete() {
    assert_ne "$DEPLOYMENT_ID" "" "Have deployment ID"
    # Wait for promote's post-quiescence state before asserting terminal state.
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 30 || log_warn "promote snapshot not quiesced"
    local result
    result=$(deploy_complete "$DEPLOYMENT_ID" 2>/dev/null || echo "")
    # If the canary has already auto-advanced to COMPLETED (promote resolved ALL_NEW),
    # the deployment is a no-op but deploy_status still reflects COMPLETED.
    local status_check
    status_check=$(deploy_status "$DEPLOYMENT_ID" 2>/dev/null || echo "")
    if printf '%s' "$status_check" | grep -q '"state"[[:space:]]*:[[:space:]]*"COMPLETED"'; then
        log_pass "Canary in COMPLETED state"
        return 0
    fi
    assert_contains "$result" "COMPLETED" "Canary completed"
}

cleanup() {
    deploy_cleanup || true
}

run_test "Cluster ready" test_cluster_ready
run_test "Canary start" test_canary_start
run_test "Canary list" test_canary_list
run_test "Canary promote" test_canary_promote
run_test "Canary complete" test_canary_complete
cleanup
print_summary
