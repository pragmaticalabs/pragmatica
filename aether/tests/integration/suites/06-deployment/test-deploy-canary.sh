#!/bin/bash
# test-deploy-canary.sh — Canary deployment via unified deploy command (v1 → v2 upgrade)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

BLUEPRINT_V1="org.pragmatica.aether.example:url-shortener:1.0.0"
BLUEPRINT_V2="org.pragmatica.aether.example:url-shortener:1.0.1"

test_canary_start() {
    deploy_cleanup
    # Register v2 blueprint (immediate deploy registers it in KV-Store)
    push_blueprint "$BLUEPRINT_V2"
    deploy_blueprint "$BLUEPRINT_V2"
    sleep 3
    # Now start canary strategy deploy
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
    deploy_complete "$did"
    sleep 3
    local status_result
    status_result=$(deploy_status "$did")
    assert_contains "$status_result" "COMPLETED" "Canary completed"
}

cleanup() {
    deploy_cleanup
}

run_test "Canary start" test_canary_start
run_test "Canary list" test_canary_list
run_test "Canary promote" test_canary_promote
run_test "Canary complete" test_canary_complete
cleanup
print_summary
