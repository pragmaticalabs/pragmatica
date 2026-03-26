#!/bin/bash
# test-route-security.sh — Public/auth/role enforcement on management API
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_health_public_no_auth() {
    # Health probes are always public
    assert_http_status "${CLUSTER_ENDPOINT}/health/live" "200" "Liveness probe — no auth required"
    assert_http_status "${CLUSTER_ENDPOINT}/health/ready" "200" "Readiness probe — no auth required"
}

test_status_requires_auth() {
    # Without API key, should get 401
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" "${CLUSTER_ENDPOINT}/api/status")
    assert_eq "$status" "401" "GET /api/status without auth returns 401"
}

test_status_with_auth() {
    assert_http_status "${CLUSTER_ENDPOINT}/api/status" "200" "GET /api/status with auth returns 200" \
        -H "X-API-Key: ${ADMIN_API_KEY}"
}

test_status_invalid_key() {
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "X-API-Key: invalid-key-12345" "${CLUSTER_ENDPOINT}/api/status")
    assert_eq "$status" "403" "Invalid API key returns 403"
}

test_viewer_can_read() {
    if [ -z "$VIEWER_API_KEY" ]; then
        skip_test "Viewer can read" "AETHER_VIEWER_API_KEY not set"
        return 0
    fi
    assert_http_status "${CLUSTER_ENDPOINT}/api/status" "200" "Viewer can GET /api/status" \
        -H "X-API-Key: ${VIEWER_API_KEY}"
    assert_http_status "${CLUSTER_ENDPOINT}/api/nodes" "200" "Viewer can GET /api/nodes" \
        -H "X-API-Key: ${VIEWER_API_KEY}"
}

test_viewer_cannot_mutate() {
    if [ -z "$VIEWER_API_KEY" ]; then
        skip_test "Viewer cannot mutate" "AETHER_VIEWER_API_KEY not set"
        return 0
    fi
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST -H "X-API-Key: ${VIEWER_API_KEY}" -H "Content-Type: application/json" \
        -d '{"targetNodes":3}' "${CLUSTER_ENDPOINT}/api/scale")
    assert_eq "$status" "403" "Viewer cannot POST /api/scale (403)"
}

test_admin_can_deploy() {
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/blueprint/validate" \
        -X POST \
        -H "X-API-Key: ${ADMIN_API_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"artifact":"test-validate"}')
    # Accept 200 or 400 (validation may fail, but auth should pass)
    if [ "$status" -ne 401 ] && [ "$status" -ne 403 ] 2>/dev/null; then
        log_pass "Admin can POST /api/blueprint/validate (status: ${status})"
        return 0
    fi
    log_fail "Admin denied for /api/blueprint/validate (status: ${status})"
    return 1
}

test_operator_can_scale() {
    if [ -z "$OPERATOR_API_KEY" ]; then
        skip_test "Operator can scale" "AETHER_OPERATOR_API_KEY not set"
        return 0
    fi
    local current_count
    current_count=$(cluster_node_count)
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/scale" \
        -X POST \
        -H "X-API-Key: ${OPERATOR_API_KEY}" \
        -H "Content-Type: application/json" \
        -d "{\"targetNodes\":${current_count}}")
    # Operator should be allowed (200) or rejected for business reasons (400), not for auth
    if [ "$status" -ne 401 ] && [ "$status" -ne 403 ] 2>/dev/null; then
        log_pass "Operator can POST /api/scale (status: ${status})"
        return 0
    fi
    log_fail "Operator denied for /api/scale (status: ${status})"
    return 1
}

run_test "Health probes — no auth" test_health_public_no_auth
run_test "Status requires auth" test_status_requires_auth
run_test "Status with valid auth" test_status_with_auth
run_test "Invalid API key rejected" test_status_invalid_key
run_test "Viewer can read" test_viewer_can_read
run_test "Viewer cannot mutate" test_viewer_cannot_mutate
run_test "Admin can deploy" test_admin_can_deploy
run_test "Operator can scale" test_operator_can_scale
print_summary
