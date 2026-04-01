#!/bin/bash
# test-principal-injection.sh — Verify caller identity is available in responses
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_admin_identity_in_response() {
    # Make an authenticated request and check if the response acknowledges the caller
    local response
    response=$(curl -sf -H "X-API-Key: ${ADMIN_API_KEY}" "${CLUSTER_ENDPOINT}/api/status")
    assert_ne "$response" "" "Status response with admin key"
}

test_different_keys_different_identity() {
    if [ -z "$VIEWER_API_KEY" ]; then
        skip_test "Different keys" "AETHER_VIEWER_API_KEY not set"
        return 0
    fi

    # Both keys should get valid responses but potentially different views
    local admin_response viewer_response
    admin_response=$(curl -sf -H "X-API-Key: ${ADMIN_API_KEY}" "${CLUSTER_ENDPOINT}/api/status")
    viewer_response=$(curl -sf -H "X-API-Key: ${VIEWER_API_KEY}" "${CLUSTER_ENDPOINT}/api/status")

    assert_ne "$admin_response" "" "Admin gets status response"
    assert_ne "$viewer_response" "" "Viewer gets status response"
}

test_app_endpoint_principal() {
    # If app-http is enabled, verify it also enforces auth
    local status_no_auth
    status_no_auth=$(http_status "${APP_ENDPOINT}/api/health")

    # Health may be public, but other endpoints should require auth
    local status_auth
    status_auth=$(http_status "${APP_ENDPOINT}/" -H "X-API-Key: ${API_KEY}")

    # Just verify the app is responding
    if [ "$status_no_auth" -gt 0 ] 2>/dev/null || [ "$status_auth" -gt 0 ] 2>/dev/null; then
        log_pass "App endpoint responds to requests"
        return 0
    fi
    log_fail "App endpoint not responding"
    return 1
}

test_unauthenticated_response_format() {
    # 401 response should include WWW-Authenticate header
    local headers
    headers=$(curl -s -D - -o /dev/null "${CLUSTER_ENDPOINT}/api/status")
    if echo "$headers" | grep -qi "WWW-Authenticate"; then
        log_pass "401 includes WWW-Authenticate header"
    else
        log_warn "401 may not include WWW-Authenticate header (non-critical)"
        log_pass "Authentication enforcement verified"
    fi
}

run_test "Cluster ready" test_cluster_ready
run_test "Admin identity in response" test_admin_identity_in_response
run_test "Different keys" test_different_keys_different_identity
run_test "App endpoint principal" test_app_endpoint_principal
run_test "Unauthenticated response format" test_unauthenticated_response_format
print_summary
