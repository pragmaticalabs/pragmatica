#!/bin/bash
# test-certificate-status.sh — Verify certificate status fields
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

# Fetch the renewalStatus once; tests use it to decide whether TLS is configured
# (NOT_CONFIGURED → expiry/expiry-validity asserts are vacuous and skip cleanly,
# anything else → strict assertions on real cert fields).
_cert_renewal_status() {
    local cert
    cert=$(api_get "/api/certificate") || return 1
    local renewal
    renewal=$(json_value "$cert" "renewalStatus")
    [ -z "$renewal" ] && renewal=$(json_value "$cert" "renewal")
    [ -z "$renewal" ] && renewal=$(json_value "$cert" "autoRenew")
    printf '%s' "$renewal"
}

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

# The endpoint must always return a non-empty body — even with TLS disabled it
# returns `renewalStatus: NOT_CONFIGURED`. Empty = endpoint broken.
test_certificate_endpoint() {
    local cert
    if ! cert=$(api_get "/api/certificate"); then
        log_fail "GET /api/certificate failed (api_get returned non-zero)"
        return 1
    fi
    assert_ne "$cert" "" "Certificate endpoint returns non-empty body"
}

# Strict: when TLS is configured, expiresAt MUST be present and parse as an
# ISO-8601-ish timestamp (digits + T separator). When TLS is NOT_CONFIGURED,
# the field is correctly absent — that's a pass.
test_expires_at_field() {
    local cert
    cert=$(api_get "/api/certificate") || { log_fail "cert endpoint failed"; return 1; }
    local renewal
    renewal=$(_cert_renewal_status)
    local expires_at
    expires_at=$(json_value "$cert" "expiresAt")
    [ -z "$expires_at" ] && expires_at=$(json_value "$cert" "expiry")
    [ -z "$expires_at" ] && expires_at=$(json_value "$cert" "notAfter")
    if [ "$renewal" = "NOT_CONFIGURED" ]; then
        if [ -z "$expires_at" ]; then
            log_pass "TLS NOT_CONFIGURED — expiresAt correctly absent"
            return 0
        fi
        log_fail "TLS NOT_CONFIGURED but expiresAt='${expires_at}' is set"
        return 1
    fi
    if [ -z "$expires_at" ]; then
        log_fail "expiresAt field missing from /api/certificate when TLS configured (renewal=${renewal:-unknown})"
        return 1
    fi
    # Cheap parseability check: ISO-8601 starts with 4 digits and contains 'T'.
    if [[ "$expires_at" =~ ^[0-9]{4} ]] && [[ "$expires_at" == *T* ]]; then
        log_pass "expiresAt present and ISO-8601 parseable: ${expires_at}"
        return 0
    fi
    log_fail "expiresAt present but not ISO-8601 parseable: '${expires_at}'"
    return 1
}

# Strict: when TLS is configured, secondsUntilExpiry MUST be > 0 (expired cert
# is a real failure). NOT_CONFIGURED → 0 is acceptable.
test_seconds_until_expiry() {
    local cert
    cert=$(api_get "/api/certificate") || { log_fail "cert endpoint failed"; return 1; }
    local seconds
    seconds=$(json_value "$cert" "secondsUntilExpiry")
    [ -z "$seconds" ] && seconds=$(json_value "$cert" "ttlSeconds")
    [ -z "$seconds" ] && seconds=$(json_value "$cert" "remainingSeconds")
    local renewal
    renewal=$(_cert_renewal_status)

    if [ "$renewal" = "NOT_CONFIGURED" ]; then
        # 0 (or absent) is fine when TLS is not configured.
        log_pass "TLS NOT_CONFIGURED — no expiry to check"
        return 0
    fi

    if [ -z "$seconds" ]; then
        log_fail "secondsUntilExpiry/ttlSeconds/remainingSeconds missing when TLS configured (renewal=${renewal:-unknown})"
        return 1
    fi
    if [ "$seconds" -gt 0 ] 2>/dev/null; then
        log_pass "secondsUntilExpiry > 0: ${seconds}s"
        return 0
    fi
    log_fail "secondsUntilExpiry=${seconds} (cert expired or invalid) with renewal=${renewal}"
    return 1
}

# Strict: renewalStatus MUST be present and one of the known values. The whole
# point of this endpoint is to surface this field.
test_renewal_status_field() {
    local renewal
    renewal=$(_cert_renewal_status)
    if [ -z "$renewal" ]; then
        log_fail "renewalStatus / renewal / autoRenew field missing from /api/certificate"
        return 1
    fi
    case "$renewal" in
        NOT_CONFIGURED|HEALTHY|RENEWING|FAILED|PENDING|true|false)
            log_pass "renewalStatus present with known value: ${renewal}"
            return 0
            ;;
    esac
    log_fail "renewalStatus has unexpected value: '${renewal}'"
    return 1
}

# Strict: cert must not be in the expired state when TLS is configured.
test_certificate_not_expired() {
    local cert
    cert=$(api_get "/api/certificate") || { log_fail "cert endpoint failed"; return 1; }
    local renewal
    renewal=$(_cert_renewal_status)
    if [ "$renewal" = "NOT_CONFIGURED" ]; then
        log_pass "TLS NOT_CONFIGURED — no expiry check needed"
        return 0
    fi
    local cert_seconds cert_expired
    cert_seconds=$(json_value "$cert" "secondsUntilExpiry")
    [ -z "$cert_seconds" ] && cert_seconds=$(json_value "$cert" "ttlSeconds")
    cert_expired=$(json_value "$cert" "expired")
    if [ "$cert_expired" = "true" ]; then
        log_fail "Certificate.expired=true (renewal=${renewal})"
        return 1
    fi
    if [ -z "$cert_seconds" ]; then
        log_fail "Cannot verify expiry: no secondsUntilExpiry/ttlSeconds field (renewal=${renewal})"
        return 1
    fi
    if [ "$cert_seconds" -gt 0 ] 2>/dev/null; then
        log_pass "Certificate is not expired (secondsUntilExpiry=${cert_seconds})"
        return 0
    fi
    log_fail "Certificate appears expired: secondsUntilExpiry=${cert_seconds}, renewal=${renewal}"
    return 1
}

run_test "Cluster ready" test_cluster_ready
run_test "Certificate endpoint" test_certificate_endpoint
run_test "expiresAt field" test_expires_at_field
run_test "secondsUntilExpiry > 0" test_seconds_until_expiry
run_test "renewalStatus field" test_renewal_status_field
run_test "Certificate not expired" test_certificate_not_expired
print_summary
