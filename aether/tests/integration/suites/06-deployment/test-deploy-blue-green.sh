#!/bin/bash
# test-deploy-blue-green.sh — Blue-green deployment via unified deploy command (v1 → v2 upgrade)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT_V2="org.pragmatica.aether.example:url-shortener:1.0.1"

test_blue_green_start() {
    deploy_cleanup
    push_blueprint "$BLUEPRINT_V2"
    deploy_blueprint "$BLUEPRINT_V2"
    sleep 3
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
    sleep 5
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
    sleep 5
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
    result=$(aether_failover deploy complete "$did" --format json 2>/dev/null)
    assert_contains "$result" "COMPLETED" "Blue-green completed"
}

cleanup() {
    deploy_cleanup
}

run_test "Blue-green start" test_blue_green_start
run_test "Blue-green promote (switch)" test_blue_green_promote
run_test "Blue-green complete" test_blue_green_complete
cleanup
print_summary
