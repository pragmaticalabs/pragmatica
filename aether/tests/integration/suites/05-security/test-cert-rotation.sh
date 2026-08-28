#!/bin/bash
# test-cert-rotation.sh — Certificate rotation during load
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

LOAD_DURATION="${LOAD_DURATION:-60}"
LOAD_RPS="${LOAD_RPS:-5}"
# Mid-flight ops tier — see aether/docs/specs/test-readiness-contract.md §4 (5.0%).
# TLS cert rotation during concurrent load; handshake retries across the rotation window.
MAX_ERROR_RATE="${MAX_ERROR_RATE:-5.0}"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready for cert rotation test"
}

# Verify the TLS contract via /api/v1/certificates.
#
# Audit RC1-blocker #3: the previous body asserted `cluster_config` non-empty,
# which conflates "endpoint live" with "TLS active". Per P3 the certificates
# API now exposes a `tlsEnabled` boolean alongside `renewalStatus`. We branch
# on that field and assert state consistency between the two:
#   - tlsEnabled=true  ⇒ renewalStatus ∈ {HEALTHY, RENEWING, RENEWAL_FAILED}
#   - tlsEnabled=false ⇒ renewalStatus == NOT_CONFIGURED
#   - missing tlsEnabled field          ⇒ API contract violation (hard fail)
#   - tlsEnabled=true with NOT_CONFIGURED ⇒ contradictory state (hard fail)
test_tls_active() {
    local cert_info tls_enabled status
    cert_info=$(aether_json certs status)

    # Distinguish "cluster unreachable" (empty/error CLI output) from "field missing
    # from JSON body" (real API contract violation). When the cluster is in a degraded
    # state (e.g. silent-divergence cascade) the CLI returns empty and the cert test
    # would otherwise misattribute the failure to /api/v1/certificates.
    if [ -z "$cert_info" ] || [ "$cert_info" = "{}" ] || [ "$cert_info" = "null" ]; then
        log_fail "Cluster unreachable: /api/v1/certificates returned empty/null response (CLI got: '$(printf '%s' "$cert_info" | head -c 100)'). This is likely a network/auth failure, not a cert API issue."
        return 1
    fi

    # Cluster reachable — verify the contract.
    # NOTE: `certs status` is a 2-word subcommand. `aether_field <command> <field>` treats
    # `$1` as the WHOLE command (which is then word-split before being passed to picocli),
    # so the command must be a single quoted argument. Without quoting, `certs` becomes the
    # command, `status` becomes the field, and the literal `tlsEnabled` arg is silently
    # dropped — picocli then sees `--field status` (no value) and rejects the call with
    # "Expected parameter for option '--field' but found 'status'" because `status` is a
    # registered subcommand of `certs`.
    tls_enabled=$(aether_field "certs status" tlsEnabled)
    status=$(aether_field "certs status" renewalStatus)

    if [ -z "$tls_enabled" ]; then
        log_fail "TLS contract violation: /api/v1/certificates returned JSON body but missing tlsEnabled field (got: $(printf '%s' "$cert_info" | head -c 200))"
        return 1
    fi

    case "$tls_enabled" in
        true)
            case "$status" in
                HEALTHY|RENEWING|RENEWAL_FAILED)
                    log_pass "TLS enabled, renewalStatus=${status}"
                    ;;
                NOT_CONFIGURED)
                    log_fail "Contradictory TLS state: tlsEnabled=true but renewalStatus=NOT_CONFIGURED"
                    return 1
                    ;;
                *)
                    log_fail "TLS enabled but renewalStatus=${status} is not in {HEALTHY, RENEWING, RENEWAL_FAILED}"
                    return 1
                    ;;
            esac
            ;;
        false)
            if [ "$status" = "NOT_CONFIGURED" ]; then
                log_pass "TLS not configured for this cluster (renewalStatus=NOT_CONFIGURED)"
            else
                log_fail "TLS disabled but renewalStatus=${status} (expected NOT_CONFIGURED)"
                return 1
            fi
            ;;
        *)
            log_fail "Unexpected tlsEnabled value: '${tls_enabled}' (expected 'true' or 'false')"
            return 1
            ;;
    esac
}

# Rotate certificates while authenticated mgmt traffic flows through the TLS
# handshake; assert error rate stays below MAX_ERROR_RATE.
#
# Audit RC1-blocker #4: the previous body (a) declared a vacuous pass on
# NOT_CONFIGURED instead of skipping, and (b) drove load against /health/live
# (no auth, no cert path). The new shape:
#   - tlsEnabled=false  ⇒ clean skip_test (not a vacuous pass)
#   - tlsEnabled=true   ⇒ drive load against /api/v1/cluster/status (auth-gated,
#                          flows through the TLS handshake), POST the rotation
#                          directive, then assert error rate < MAX_ERROR_RATE.
# Load uses api_get-style _api_call semantics so 4xx/5xx surface as failures
# (start_load classifies status >=400 as failure, see load.sh:35).
test_rotation_under_load() {
    local cert_info tls_enabled
    cert_info=$(aether_json certs status)

    # Same distinction as test_tls_active: empty/null CLI output indicates the cluster
    # is unreachable, not that the /api/v1/certificates contract was violated.
    if [ -z "$cert_info" ] || [ "$cert_info" = "{}" ] || [ "$cert_info" = "null" ]; then
        log_fail "Cluster unreachable: /api/v1/certificates returned empty/null response (CLI got: '$(printf '%s' "$cert_info" | head -c 100)'). Cannot determine TLS state for rotation test."
        return 1
    fi

    tls_enabled=$(aether_field "certs status" tlsEnabled)

    if [ -z "$tls_enabled" ]; then
        log_fail "TLS contract violation: /api/v1/certificates returned JSON body but missing tlsEnabled field"
        return 1
    fi

    if [ "$tls_enabled" = "false" ]; then
        skip_test "Rotation under load" "TLS not configured (NOT_CONFIGURED renewalStatus)"
        return 0
    fi

    if [ "$tls_enabled" != "true" ]; then
        log_fail "Unexpected tlsEnabled value: '${tls_enabled}' (expected 'true' or 'false')"
        return 1
    fi

    # Drive load against an authenticated mgmt endpoint that flows through TLS.
    # APP_ENDPOINT is overridden to CLUSTER_ENDPOINT (mgmt host) so start_load's
    # internal http_status calls hit the TLS-served mgmt port.
    APP_ENDPOINT="${CLUSTER_ENDPOINT}" start_load "$LOAD_RPS" "$LOAD_DURATION" "GET" "/api/v1/cluster/status"
    sleep 5

    log_info "Triggering certificate rotation"
    local rotation_status
    rotation_status=$(http_status "${CLUSTER_ENDPOINT}/api/v1/config" \
        -X POST \
        -H "X-API-Key: ${ADMIN_API_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"tls":{"rotate":true}}')
    log_info "Cert rotation response: ${rotation_status}"
    if [ "$rotation_status" -lt 200 ] || [ "$rotation_status" -ge 400 ]; then
        log_fail "Cert rotation request failed: HTTP ${rotation_status}"
        # Continue to drain load so we don't leak background processes.
    fi

    # Wait for load to finish
    for pid in "${LOAD_PIDS[@]}"; do
        wait "$pid" || true  # background process may have exited normally before we wait
    done

    local result
    result=$(stop_load)
    assert_error_rate_below "$result" "$MAX_ERROR_RATE" "Error rate during cert rotation < ${MAX_ERROR_RATE}%"
}

test_cluster_healthy_after_rotation() {
    sleep 5
    assert_cluster_healthy "Cluster healthy after cert rotation"
}

test_all_nodes_present() {
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "${NODE_COUNT:-5}" "All ${NODE_COUNT:-5} nodes present after cert rotation"
}

run_test "Cluster ready" test_cluster_ready
run_test "TLS config active" test_tls_active
run_test "Cert rotation under load" test_rotation_under_load
run_test "Healthy after rotation" test_cluster_healthy_after_rotation
run_test "All nodes present" test_all_nodes_present
print_summary
