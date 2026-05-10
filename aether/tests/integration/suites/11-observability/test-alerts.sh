#!/bin/bash
# test-alerts.sh — Set alert threshold, trigger condition, verify alert fires
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

ALERT_NAME="integration-test-alert-$$"
ALERT_METRIC="test.integration.counter"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

# Smoke: thresholds endpoint must respond 200 (covered earlier by alerts coverage,
# but kept explicit). An empty list IS a valid response — there may be zero
# thresholds — so we assert HTTP status, not body content.
test_thresholds_endpoint() {
    assert_http_status "${CLUSTER_ENDPOINT}/api/thresholds" "200" \
        "GET /api/thresholds returns 200" \
        -H "X-API-Key: ${API_KEY}"
}

# Strict: POST a threshold, then GET /api/thresholds and verify it appears.
# This converts "endpoint responds" into "endpoint accepts and persists my write".
test_set_alert_threshold() {
    # Server contract: ThresholdRequest{metric, warning, critical} — see AlertRoutes.java.
    # The prior body shape `{metric,operator,value,severity,name}` was wrong; server
    # rejected with 500 "Missing metric, warning, or critical field". The pre-strict
    # assertion warn-then-pass demotion silently swallowed it.
    local body
    body="{\"metric\":\"${ALERT_METRIC}\",\"warning\":1,\"critical\":5}"
    local create_result
    if ! create_result=$(api_post "/api/thresholds" "$body"); then
        log_fail "POST /api/thresholds failed (api_post returned non-zero)"
        return 1
    fi
    # Read back and verify our threshold appears in the list (matched by metric name —
    # the server response shape uses the metric as the identity, not a synthetic name).
    local thresholds
    if ! thresholds=$(api_get "/api/thresholds"); then
        log_fail "GET /api/thresholds failed after creation"
        return 1
    fi
    assert_contains "$thresholds" "$ALERT_METRIC" \
        "Created threshold for metric '${ALERT_METRIC}' is visible in /api/thresholds"
}

# Generating load is mechanical — the real assertion is whether an alert is
# emitted, which lives in test_check_alerts_fired below.
test_trigger_alert_condition() {
    for i in $(seq 1 20); do
        api_get "/api/status" > /dev/null 2>&1 || true
    done
    sleep 5
    log_pass "Generated load to trigger alert"
}

# UNTESTABLE without product wiring: the test threshold targets a metric
# (`test.integration.counter`) that the runtime does not actually publish, so
# no alert can ever fire. Honestly testing "alert fires when condition holds"
# requires either (a) a synthetic metric we can drive from the test, or
# (b) an alert-injection management endpoint. Neither exists today.
test_check_alerts_fired() {
    log_fail "TODO: alert-firing assertion requires synthetic metric injection or a test-controllable threshold target — no product mechanism today"
    return 1
}

# UNTESTABLE for the same reason as above: there are no alerts to inspect, so
# field-shape assertions cannot run without first being able to produce one.
test_alerts_have_fields() {
    log_fail "TODO: alert-entry shape assertion is gated on the same alert-injection capability missing for test_check_alerts_fired"
    return 1
}

test_cluster_healthy_after_alerts() {
    assert_cluster_healthy "Cluster healthy after alert tests"
}

run_test "Cluster ready" test_cluster_ready
run_test "Thresholds endpoint" test_thresholds_endpoint
run_test "Set alert threshold" test_set_alert_threshold
run_test "Trigger alert condition" test_trigger_alert_condition
run_test "Check alerts fired" test_check_alerts_fired
run_test "Alert entries have fields" test_alerts_have_fields
run_test "Healthy after alerts" test_cluster_healthy_after_alerts
print_summary
