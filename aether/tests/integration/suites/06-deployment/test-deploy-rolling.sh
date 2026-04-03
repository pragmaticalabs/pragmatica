#!/bin/bash
# test-deploy-rolling.sh — Rolling deployment via unified deploy command (v1 → v2 upgrade)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT_V2="org.pragmatica.aether.example:url-shortener:1.0.1"

test_rolling_start() {
    deploy_cleanup
    push_blueprint "$BLUEPRINT_V2"
    deploy_blueprint "$BLUEPRINT_V2"
    sleep 3
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
    deploy_complete "$did"
    sleep 3
    local status_result
    status_result=$(deploy_status "$did")
    assert_contains "$status_result" "COMPLETED" "Rolling deployment completed"
}

cleanup() {
    deploy_cleanup
}

run_test "Rolling start" test_rolling_start
run_test "Rolling promote" test_rolling_promote
run_test "Rolling complete" test_rolling_complete
cleanup
print_summary
