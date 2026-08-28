#!/bin/bash
# test-principal-injection.sh — Verify caller identity is correctly resolved and
# surfaced by the management API (whoami) and that auth-required routes enforce
# 401-without-key / 200-with-admin-key strictly.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

# RC1-blocker #5: admin's whoami must resolve to a real api-key principal with
# authenticated=true and authorizationRole=ADMIN. The previous shape asserted
# only that the body was non-empty, which is satisfied even when auth silently
# falls through to anonymous.
test_admin_identity_in_response() {
    local principal authenticated role
    principal=$(API_KEY="${ADMIN_API_KEY}" aether_field whoami principal)
    authenticated=$(API_KEY="${ADMIN_API_KEY}" aether_field whoami authenticated)
    role=$(API_KEY="${ADMIN_API_KEY}" aether_field whoami authorizationRole)

    assert_eq "$authenticated" "true" "Admin whoami: authenticated == true"
    assert_eq "$role" "ADMIN" "Admin whoami: authorizationRole == ADMIN"

    # principal must be non-empty, scoped to api-key:, and NOT anonymous.
    if [ -z "$principal" ]; then
        log_fail "Admin whoami: principal is empty"
        return 1
    fi
    if [ "$principal" = "anonymous" ]; then
        log_fail "Admin whoami: principal resolved to 'anonymous' (auth silently bypassed)"
        return 1
    fi
    case "$principal" in
        api-key:*) log_pass "Admin whoami: principal '${principal}' is scoped to api-key:" ;;
        *) log_fail "Admin whoami: principal '${principal}' is not prefixed with 'api-key:'"; return 1 ;;
    esac
}

# RC1-blocker #6: different API keys must resolve to DIFFERENT principals AND
# their declared roles. The previous shape compared nothing — both calls could
# return the same anonymous identity and the test would still pass.
test_different_keys_different_identity() {
    if [ -z "$VIEWER_API_KEY" ]; then
        skip_test "Different keys" "AETHER_VIEWER_API_KEY not set"
        return 0
    fi

    local admin_who admin_role viewer_who viewer_role
    admin_who=$(API_KEY="${ADMIN_API_KEY}"  aether_field whoami principal)
    admin_role=$(API_KEY="${ADMIN_API_KEY}" aether_field whoami authorizationRole)
    viewer_who=$(API_KEY="${VIEWER_API_KEY}"  aether_field whoami principal)
    viewer_role=$(API_KEY="${VIEWER_API_KEY}" aether_field whoami authorizationRole)

    # Strict inequality — two distinct keys MUST surface as two distinct principals.
    assert_ne "$admin_who" "$viewer_who" "Admin and Viewer keys resolve to distinct principals"
    # Each side must also carry its declared role; otherwise principals could differ
    # but roles could be uniformly anonymous/empty and the test would still pass.
    assert_eq "$admin_role"  "ADMIN"  "Admin key resolves to authorizationRole ADMIN"
    assert_eq "$viewer_role" "VIEWER" "Viewer key resolves to authorizationRole VIEWER"
}

# RC1-blocker #7: auth enforcement on /api/v1/nodes/status — formerly accepted any
# positive HTTP code on any endpoint as a pass; now strictly checks 401 without
# key and 200 with admin key on the SAME auth-required path.
test_app_endpoint_principal() {
    # No API key → must be rejected with 401 (auth required).
    assert_http_status "${CLUSTER_ENDPOINT}/api/v1/nodes/status" "401" \
        "GET /api/v1/nodes/status without API key returns 401"

    # Admin API key → must be accepted with 200.
    assert_http_status "${CLUSTER_ENDPOINT}/api/v1/nodes/status" "200" \
        "GET /api/v1/nodes/status with admin API key returns 200" \
        -H "X-API-Key: ${ADMIN_API_KEY}"
}

# RC1-blocker #8: an unauthenticated request must produce a strict 401 response
# AND include a WWW-Authenticate header (RFC 7235). The previous shape emitted
# log_warn then log_pass on header miss (demotion) and never asserted the status
# code — it would have passed on 200, 500, or any other status.
test_unauthenticated_response_format() {
    # Strict status assertion — no API key → must be 401.
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" "${CLUSTER_ENDPOINT}/api/v1/nodes/status")
    assert_eq "$status" "401" "Unauthenticated GET /api/v1/nodes/status returns 401"

    # Strict header assertion — RFC 7235 mandates WWW-Authenticate on 401 responses.
    local headers
    headers=$(curl -s -D - -o /dev/null "${CLUSTER_ENDPOINT}/api/v1/nodes/status")
    if echo "$headers" | grep -qi "WWW-Authenticate"; then
        log_pass "401 response includes WWW-Authenticate header"
    else
        log_fail "401 response missing WWW-Authenticate header (RFC 7235 violation)"
        return 1
    fi
}

run_test "Cluster ready" test_cluster_ready
run_test "Admin identity in response" test_admin_identity_in_response
run_test "Different keys" test_different_keys_different_identity
run_test "App endpoint principal" test_app_endpoint_principal
run_test "Unauthenticated response format" test_unauthenticated_response_format
print_summary
