#!/bin/bash
# test-sql-connector.sh — Deploy url-shortener (@Sql + PostgreSQL), verify SQL operations
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

BLUEPRINT="${SQL_BLUEPRINT:-org.pragmatica.aether.example:url-shortener:1.0.0}"
POOL_BURST="${POOL_BURST:-50}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_deploy_sql_app() {
    push_blueprint "$BLUEPRINT" || log_warn "Artifact push returned non-zero (may already exist)"
    deploy_blueprint "$BLUEPRINT" || log_warn "Blueprint deploy returned non-zero (may already exist)"
    wait_for_slices_active 1 120
    log_pass "SQL-backed app deployed"
}

test_create_short_url() {
    local payload='{"url":"https://example.com/integration-test"}'
    local result
    result=$(app_post "/api/v1/urls/" "$payload")
    assert_ne "$result" "" "POST /api/v1/urls/ returns response"
}

test_resolve_short_url() {
    local payload='{"url":"https://example.com/resolve-test"}'
    local create_result
    create_result=$(app_post "/api/v1/urls/" "$payload")
    local short_code
    short_code=$(echo "$create_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('shortCode',''))" 2>/dev/null)
    if [ -z "$short_code" ]; then
        log_fail "Could not extract shortCode from create response"
        return 1
    fi
    local status
    status=$(http_status "${APP_ENDPOINT}/api/v1/urls/${short_code}" -H "X-API-Key: ${API_KEY}")
    if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
        log_pass "GET /api/v1/urls/${short_code} returns ${status} (success)"
        return 0
    fi
    log_fail "GET /api/v1/urls/${short_code} returns ${status} (expected 2xx or 3xx)"
    return 1
}

test_connection_pooling_rapid_requests() {
    local success=0 failure=0
    for i in $(seq 1 "$POOL_BURST"); do
        local payload="{\"url\":\"https://example.com/pool-test-${i}\"}"
        local status
        status=$(http_status "${APP_ENDPOINT}/api/v1/urls/" \
            -X POST \
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
run_test "Create short URL" test_create_short_url
run_test "Resolve short URL" test_resolve_short_url
run_test "Connection pooling under rapid requests" test_connection_pooling_rapid_requests
run_test "Healthy after SQL load" test_cluster_healthy_after_sql_load
print_summary
