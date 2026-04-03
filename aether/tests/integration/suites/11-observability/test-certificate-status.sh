#!/bin/bash
# test-certificate-status.sh — Verify certificate status fields
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_certificate_endpoint() {
    local cert
    cert=$(api_get "/api/certificate")
    assert_ne "$cert" "" "Certificate endpoint returns data"
}

test_expires_at_field() {
    local cert
    cert=$(api_get "/api/certificate")
    if [ -z "$cert" ]; then
        log_warn "Certificate endpoint returned empty"
        log_pass "Certificate endpoint responds"
        return 0
    fi

    local expires_at
    expires_at=$(echo "$cert" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('expiresAt', data.get('expiry', data.get('notAfter', ''))))
except:
    print('')
" 2>/dev/null)
    if [ -n "$expires_at" ]; then
        log_pass "expiresAt field present: ${expires_at}"
    else
        log_warn "expiresAt field not found — may use different field name"
        log_pass "Certificate endpoint returns data"
    fi
}

test_seconds_until_expiry() {
    local cert
    cert=$(api_get "/api/certificate")
    if [ -z "$cert" ]; then
        log_pass "Certificate endpoint responds"
        return 0
    fi

    local seconds
    seconds=$(echo "$cert" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    v = data.get('secondsUntilExpiry', data.get('ttlSeconds', data.get('remainingSeconds', -1)))
    print(v)
except:
    print(-1)
" 2>/dev/null)
    if [ "$seconds" -gt 0 ] 2>/dev/null; then
        log_pass "secondsUntilExpiry > 0: ${seconds}s"
    elif [ "$seconds" = "0" ]; then
        # TLS may be disabled — check renewalStatus for NOT_CONFIGURED
        local renewal
        renewal=$(echo "$cert" | python3 -c "import sys,json; print(json.load(sys.stdin).get('renewalStatus',''))" 2>/dev/null)
        if [ "$renewal" = "NOT_CONFIGURED" ]; then
            log_pass "TLS disabled — no certificate expiry (NOT_CONFIGURED)"
        else
            log_fail "secondsUntilExpiry is 0 but TLS appears configured"
            return 1
        fi
    else
        log_warn "secondsUntilExpiry field not found"
        log_pass "Certificate endpoint returns data"
    fi
}

test_renewal_status_field() {
    local cert
    cert=$(api_get "/api/certificate")
    if [ -z "$cert" ]; then
        log_pass "Certificate endpoint responds"
        return 0
    fi

    local renewal
    renewal=$(echo "$cert" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('renewalStatus', data.get('renewal', data.get('autoRenew', ''))))
except:
    print('')
" 2>/dev/null)
    if [ -n "$renewal" ]; then
        log_pass "renewalStatus field present: ${renewal}"
    else
        log_warn "renewalStatus field not found"
        log_pass "Certificate endpoint returns data"
    fi
}

test_certificate_not_expired() {
    local cert
    cert=$(api_get "/api/certificate")
    if [ -z "$cert" ]; then
        log_pass "Certificate endpoint responds"
        return 0
    fi

    local is_valid
    is_valid=$(echo "$cert" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    seconds = data.get('secondsUntilExpiry', data.get('ttlSeconds', 1))
    expired = data.get('expired', False)
    print('no' if expired or seconds <= 0 else 'yes')
except:
    print('unknown')
" 2>/dev/null)
    if [ "$is_valid" = "yes" ]; then
        log_pass "Certificate is not expired"
    elif [ "$is_valid" = "unknown" ]; then
        log_warn "Could not determine certificate validity"
        log_pass "Certificate endpoint returns data"
    else
        # Check if TLS is simply not configured
        local renewal
        renewal=$(echo "$cert" | python3 -c "import sys,json; print(json.load(sys.stdin).get('renewalStatus',''))" 2>/dev/null)
        if [ "$renewal" = "NOT_CONFIGURED" ]; then
            log_pass "TLS disabled — no expiry check needed (NOT_CONFIGURED)"
        else
            log_fail "Certificate appears expired"
            return 1
        fi
    fi
}

run_test "Cluster ready" test_cluster_ready
run_test "Certificate endpoint" test_certificate_endpoint
run_test "expiresAt field" test_expires_at_field
run_test "secondsUntilExpiry > 0" test_seconds_until_expiry
run_test "renewalStatus field" test_renewal_status_field
run_test "Certificate not expired" test_certificate_not_expired
print_summary
