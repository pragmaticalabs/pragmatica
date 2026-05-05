#!/bin/bash
# test-sql-connector.sh — Deploy test-persistence (@PgSql + PostgreSQL), verify SQL operations
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

BLUEPRINT="${SQL_BLUEPRINT:-org.pragmatica.aether.test:test-persistence:1.0.0}"
POOL_BURST="${POOL_BURST:-50}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_deploy_sql_app() {
    push_blueprint "$BLUEPRINT" || log_warn "Artifact push returned non-zero (may already exist)"
    deploy_blueprint "$BLUEPRINT" || log_warn "Blueprint deploy returned non-zero (may already exist)"
    # Wait for ≥1 active instance of test-persistence. Earlier tests in 08-resources may
    # leave 06-deployment's url-shortener slices in non-ACTIVE states (FAILED/TERMINATING),
    # which would make wait_for_all_target_instances_active hang because target_total counts
    # those non-active targets too. ≥1 active is sufficient: we re-target APP_ENDPOINT below.
    wait_for_slices_active 1 120
    # Cloud: the test-persistence slice has 3 instances spread across 5 nodes — node-1 (the
    # default APP_ENDPOINT host) may not host the slice. Find an active owner and re-target.
    if [ "${ENV_TYPE:-docker}" = "cloud" ]; then
        local owner owner_ip
        owner=$(slice_owner_for "$BLUEPRINT" 2>/dev/null || true)
        if [ -n "$owner" ]; then
            owner_ip=$(cloud_public_ip "$owner" 2>/dev/null || true)
            if [ -n "$owner_ip" ]; then
                APP_ENDPOINT="http://${owner_ip}:8070"
                log_info "Retargeted APP_ENDPOINT to slice owner ${owner} → ${APP_ENDPOINT}"
            fi
        fi
    fi
    log_pass "SQL-backed app deployed"
}

test_put_kv_pair() {
    local payload='{"value":"integration-test-value"}'
    local result
    result=$(http_status "${APP_ENDPOINT}/api/kv/test-key" \
        -X PUT \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -d "$payload")
    if [ "$result" -ge 200 ] && [ "$result" -lt 400 ] 2>/dev/null; then
        log_pass "PUT /api/kv/test-key returns ${result} (success)"
        return 0
    fi
    log_fail "PUT /api/kv/test-key returns ${result} (expected 2xx) at ${APP_ENDPOINT}"
    return 1
}

test_get_kv_pair() {
    local payload='{"value":"resolve-test-value"}'
    local put_status
    put_status=$(http_status "${APP_ENDPOINT}/api/kv/resolve-key" \
        -X PUT \
        -H "X-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -d "$payload")
    if [ "$put_status" -lt 200 ] || [ "$put_status" -ge 400 ] 2>/dev/null; then
        log_fail "PUT failed before GET test (status: ${put_status})"
        return 1
    fi
    local get_result
    get_result=$(curl -s -H "X-API-Key: ${API_KEY}" "${APP_ENDPOINT}/api/kv/resolve-key" 2>/dev/null)
    local value
    value=$(json_value "$get_result" "value")
    if [ "$value" = "resolve-test-value" ]; then
        log_pass "GET /api/kv/resolve-key returns expected value"
        return 0
    fi
    log_fail "GET /api/kv/resolve-key value mismatch: got '${value}', expected 'resolve-test-value'"
    return 1
}

test_connection_pooling_rapid_requests() {
    local success=0 failure=0
    for i in $(seq 1 "$POOL_BURST"); do
        local payload="{\"value\":\"pool-test-value-${i}\"}"
        local status
        status=$(http_status "${APP_ENDPOINT}/api/kv/pool-key-${i}" \
            -X PUT \
            -H "X-API-Key: ${API_KEY}" \
            -H "Content-Type: application/json" \
            -d "$payload")
        if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
            success=$((success + 1))
        else
            failure=$((failure + 1))
        fi
    done
    log_info "Pool burst: success=${success}, failure=${failure}"
    assert_gt "$success" "0" "At least some requests succeeded under pool burst"
    local threshold=$((POOL_BURST / 2))
    assert_gt "$success" "$threshold" "Majority of ${POOL_BURST} requests succeeded (>${threshold})"
}

test_cluster_healthy_after_sql_load() {
    assert_cluster_healthy "Cluster healthy after SQL load"
}

run_test "Cluster ready" test_cluster_ready
run_test "Deploy SQL app" test_deploy_sql_app
run_test "Put KV pair" test_put_kv_pair
run_test "Get KV pair" test_get_kv_pair
run_test "Connection pooling under rapid requests" test_connection_pooling_rapid_requests
run_test "Healthy after SQL load" test_cluster_healthy_after_sql_load
print_summary
