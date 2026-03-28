#!/bin/bash
# test-sql-connector.sh — Deploy url-shortener (@Sql + PostgreSQL), verify SQL operations
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/load.sh"

BLUEPRINT="${SQL_BLUEPRINT:-url-shortener}"
POOL_BURST="${POOL_BURST:-50}"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_deploy_sql_app() {
    deploy_blueprint "$BLUEPRINT" || log_warn "Blueprint deploy returned non-zero (may already exist)"
    wait_for_slices_active 1 120
    log_pass "SQL-backed app deployed"
}

test_create_short_url() {
    local payload='{"url":"https://example.com/integration-test","slug":"int-test-001"}'
    local result
    result=$(app_post "/shorten" "$payload")
    assert_ne "$result" "" "POST /shorten returns response"
}

test_resolve_short_url() {
    local status
    status=$(http_status "${APP_ENDPOINT}/int-test-001" -H "X-API-Key: ${API_KEY}")
    # Expect 200 or 301/302 redirect
    if [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
        log_pass "GET /int-test-001 returns ${status} (success)"
        return 0
    fi
    log_fail "GET /int-test-001 returns ${status} (expected 2xx or 3xx)"
    return 1
}

test_connection_pooling_rapid_requests() {
    local result
    result=$(burst_load "$POOL_BURST" "POST" "/shorten" \
        '{"url":"https://example.com/pool-test","slug":"pool-@@COUNTER@@"}')
    local success=${result%%:*}
    local failure=${result##*:}
    log_info "Pool burst: success=${success}, failure=${failure}"
    assert_gt "$success" "0" "At least some requests succeeded under pool burst"
    # Allow small failure rate but no total meltdown
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
