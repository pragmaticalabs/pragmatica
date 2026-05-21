#!/bin/bash
# test-deploy-blue-green.sh — Blue-green deployment via unified deploy command (v1 → v2 upgrade)
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
    if ! await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30; then
        log_fail "Blue-green promote of ${did} did not quiesce within 30s"
        return 1
    fi
    # Strict post-promote (switch) state check. Blue-green promote() always uses
    # VersionRouting.ALL_NEW (DeploymentManagerImpl.computePromoteRouting), so the
    # FSM transitions to PROMOTING; async ClusterDeploymentManager reconciliation
    # may advance to DRAINING/COMPLETED before this assertion runs.
    local status_result state
    status_result=$(deploy_status "$did")
    state=$(json_value "$status_result" "state")
    case "$state" in
        PROMOTING|DRAINING|COMPLETED)
            log_pass "Blue-green promote (switch) of ${did} reached post-promote state=${state}"
            ;;
        *)
            log_fail "Blue-green promote (switch) of ${did} did not reach a post-promote state — got state='${state}'; deploy_status: $(printf '%s' "$status_result" | head -c 500)"
            return 1
            ;;
    esac
}

test_blue_green_rollback() {
    # Self-contained rollback scenario: start a fresh v2 blue-green deployment off the
    # cleanup()-restored v1 baseline, then rollback before promote. Validates the
    # DEPLOYED → ROLLING_BACK → ROLLED_BACK path. Cannot piggyback on the prior
    # promote+complete sequence because `complete` is terminal (DeploymentState
    # validTransitions forbid COMPLETED → ROLLING_BACK) and `deploy_list` filters
    # terminal deployments — so a fresh start is required. Runs AFTER complete:
    # the prior cleanup() call has restored v1 ACTIVE; v2 was already pushed during
    # test_blue_green_start, but re-publish defensively so the deploy is reproducible.
    deploy_cleanup
    push_blueprint "$BLUEPRINT_V1"
    deploy_blueprint "$BLUEPRINT_V1"
    if ! await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30; then
        log_fail "Rollback setup: v1 baseline deploy did not quiesce"
        return 1
    fi
    push_blueprint "$BLUEPRINT_V2"
    publish_blueprint "$BLUEPRINT_V2"
    if ! await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30; then
        log_fail "Rollback setup: v2 publish did not quiesce"
        return 1
    fi
    local start_result did
    start_result=$(deploy_start "$BLUEPRINT_V2" blue-green --instances 2)
    did=$(deploy_extract_id "$start_result")
    assert_ne "$did" "" "Captured deployment ID for rollback scenario"
    # Capture pre-rollback state: deployment should be in DEPLOYING/DEPLOYED before
    # promote. Rollback from a non-terminal pre-promote state.
    local pre_state pre_status
    pre_status=$(deploy_status "$did")
    pre_state=$(json_value "$pre_status" "state")
    log_info "Pre-rollback deployment ${did} state=${pre_state}"
    deploy_rollback "$did"
    if ! await_generation_quiesced "$CLUSTER_ENDPOINT" "current+1" 30; then
        log_fail "Rollback of ${did} did not quiesce within 30s"
        return 1
    fi
    # Assert post-rollback state. rollback() transitions ROLLING_BACK (sync); the
    # async reconciliation in DeploymentManagerImpl/CDM may advance to ROLLED_BACK.
    local status_result state
    status_result=$(deploy_status "$did")
    state=$(json_value "$status_result" "state")
    case "$state" in
        ROLLING_BACK|ROLLED_BACK)
            log_pass "Rollback of ${did} reached terminal/transitional state=${state} (pre=${pre_state})"
            ;;
        *)
            log_fail "Rollback of ${did} did not reach a post-rollback state — got state='${state}' (pre=${pre_state}); deploy_status: $(printf '%s' "$status_result" | head -c 500)"
            return 1
            ;;
    esac
    # Verify the cluster's active routing was returned to v1 (the prior version).
    # rollback() emits VersionRouting.ALL_OLD + SliceTarget(oldVersion); after quiesce
    # the SliceTargetKey.currentVersion is back to v1.
    local v1_version
    v1_version="${BLUEPRINT_V1##*:}"  # extract "1.0.0" from coordinate
    local slices_json active_v1_count
    slices_json=$(aether_json slices --state ACTIVE)
    active_v1_count=$(printf '%s' "$slices_json" | grep -oc "\"version\"[[:space:]]*:[[:space:]]*\"${v1_version}\"" || true)
    if [ "${active_v1_count:-0}" -lt 1 ]; then
        log_fail "Rollback of ${did} did not restore v1 active routing — no ACTIVE slice at version ${v1_version} observed. slices: $(printf '%s' "$slices_json" | head -c 500)"
        return 1
    fi
    log_pass "Rollback restored v1 (${v1_version}) ACTIVE — ${active_v1_count} slice(s) at v1"
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
run_test "Blue-green rollback (switch back)" test_blue_green_rollback
cleanup
print_summary
