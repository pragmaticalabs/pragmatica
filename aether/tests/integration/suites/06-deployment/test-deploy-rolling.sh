#!/bin/bash
# test-deploy-rolling.sh — Rolling deployment via unified deploy command (v1 → v2 upgrade)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT_V1="org.pragmatica.aether.example:url-shortener:1.0.0"
BLUEPRINT_V2="org.pragmatica.aether.example:url-shortener:1.0.1"

test_cluster_ready() {
    wait_for_cluster_ready 60
    wait_for_all_tasks_active 60 || log_warn "task groups not fully ACTIVE within 60s"
    log_pass "Cluster ready"
}

test_rolling_start() {
    deploy_cleanup
    # Establish a verified v1 baseline. A prior strategy test can leave v1.0.1 ACTIVE
    # (deploy_cleanup only aborts NON-terminal deployments, so a COMPLETED v2 stays
    # active); `deploy_blueprint v1` is the redeployment-safe downgrade. Barrier on
    # "current" — when v1 is already active the redeploy is a no-op that never advances
    # the generation, so "current+1" would warn-time-out without guaranteeing anything.
    push_blueprint "$BLUEPRINT_V1"
    deploy_blueprint "$BLUEPRINT_V1"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current" 30 || log_warn "v1 baseline did not quiesce"
    assert_active_version "$BLUEPRINT_V1" "Baseline v1 ACTIVE before rolling upgrade"
    push_blueprint "$BLUEPRINT_V2"
    publish_blueprint "$BLUEPRINT_V2"
    await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30 || log_warn "v2 publish did not quiesce"
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
    if ! await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30; then
        log_fail "Rolling promote of ${did} did not quiesce within 30s"
        return 1
    fi
    # Strict post-promote state check. Rolling promote() drives routing → ALL_NEW
    # and transitions PROMOTING (DeploymentManagerImpl.applyPromoteRouting,
    # DeploymentState.PROMOTING → DRAINING → COMPLETED). Accept any forward state;
    # reject pre-promote (PENDING/DEPLOYING/DEPLOYED/ROUTING) or failure terminals
    # (FAILED/ROLLED_BACK). The exact terminal at observation time is timing-sensitive,
    # so the set is the union of legal post-promote states.
    local status_result state
    status_result=$(deploy_status "$did")
    state=$(json_value "$status_result" "state")
    case "$state" in
        PROMOTING|DRAINING|COMPLETED)
            log_pass "Rolling promote of ${did} reached post-promote state=${state}"
            ;;
        *)
            log_fail "Rolling promote of ${did} did not reach a post-promote state — got state='${state}'; deploy_status: $(printf '%s' "$status_result" | head -c 500)"
            return 1
            ;;
    esac
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
    # Restore baseline v1.0.0 ACTIVE for any subsequent test in this suite.
    deploy_cleanup || true
    deploy_blueprint "$BLUEPRINT_V1" >/dev/null 2>&1 || \
        log_warn "cleanup: failed to revert active version to ${BLUEPRINT_V1}"
}

run_test "Cluster ready" test_cluster_ready
run_test "Rolling start" test_rolling_start
run_test "Rolling promote" test_rolling_promote
run_test "Rolling complete" test_rolling_complete
cleanup
print_summary
