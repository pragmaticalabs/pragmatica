#!/bin/bash
# test-alerts.sh — Set alert threshold, trigger condition, verify alert fires
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

ALERT_NAME="integration-test-alert-$$"
ALERT_METRIC="test.integration.counter"
ALERT_SEVERITY="WARNING"
ALERT_MESSAGE="synthetic alert from integration test pid=$$"
# Captured by test_trigger_alert_condition; read by the downstream check_alerts_fired /
# alerts_have_fields tests so they can correlate the injected entry by id.
INJECTED_ALERT_ID=""

test_cluster_ready() {
    wait_for_cluster_ready 60
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

# Drive the alert path explicitly via POST /api/alerts/inject. The runtime does
# not publish a test-only metric, so threshold-driven firing on `${ALERT_METRIC}`
# can't be exercised end-to-end here. The injection endpoint exists for exactly
# this gap — see aether/docs/reference/management-api.md → POST /api/alerts/inject.
test_trigger_alert_condition() {
    local body
    body="{\"name\":\"${ALERT_NAME}\",\"severity\":\"${ALERT_SEVERITY}\",\"message\":\"${ALERT_MESSAGE}\",\"metric\":\"${ALERT_METRIC}\",\"value\":42.0}"
    local response
    if ! response=$(api_post "/api/alerts/inject" "$body"); then
        log_fail "POST /api/alerts/inject failed (api_post returned non-zero)"
        return 1
    fi
    # Server returns {alertId, name, severity, message, timestamp}. Grab alertId so
    # downstream tests can correlate the inject with the read-back entry.
    INJECTED_ALERT_ID=$(printf '%s' "$response" | grep -oE '"alertId"[[:space:]]*:[[:space:]]*"[^"]+"' | sed -E 's/.*"([^"]+)"$/\1/' | head -1)
    if [ -z "$INJECTED_ALERT_ID" ]; then
        log_fail "Inject response missing alertId field — response was: ${response}"
        return 1
    fi
    log_pass "Injected alert id=${INJECTED_ALERT_ID} name=${ALERT_NAME}"
}

# Verify the injected alert is visible via GET /api/alerts (active list). The
# injection endpoint is local-to-node by design (alerts are node-local state, not
# KV-replicated), but api_get hits the same endpoint cluster used for the POST
# via _resolve_live_endpoint — same target node, same in-memory store.
test_check_alerts_fired() {
    if [ -z "$INJECTED_ALERT_ID" ]; then
        log_fail "Pre-condition broken: INJECTED_ALERT_ID is empty (test_trigger_alert_condition must have failed)"
        return 1
    fi
    local alerts
    if ! alerts=$(api_get "/api/alerts"); then
        log_fail "GET /api/alerts failed (api_get returned non-zero)"
        return 1
    fi
    assert_contains "$alerts" "$INJECTED_ALERT_ID" \
        "GET /api/alerts must surface the injected alertId=${INJECTED_ALERT_ID}"
}

# With an injected alert present, assert each contract field carries a sensible
# value. Substring match (not literal "key":"value" syntax) because /api/alerts
# returns `AlertsResponse(Object active, Object history)` and the Object fields
# currently hold pre-serialized String JSON which Jackson re-encodes with
# escaped quotes — making the literal-form assertion brittle. Substring on
# values is sufficient to catch field omission. A post-RC1 refactor of
# `AlertManager.activeAlertsAsJson()` to return structured types (List<...>)
# would let assertions tighten back to literal "key":"value" form.
test_alerts_have_fields() {
    if [ -z "$INJECTED_ALERT_ID" ]; then
        log_fail "Pre-condition broken: INJECTED_ALERT_ID is empty (test_trigger_alert_condition must have failed)"
        return 1
    fi
    local alerts
    if ! alerts=$(api_get "/api/alerts"); then
        log_fail "GET /api/alerts failed (api_get returned non-zero)"
        return 1
    fi
    assert_contains "$alerts" "$ALERT_NAME" \
        "Injected alert exposes name='${ALERT_NAME}' value"
    assert_contains "$alerts" "$ALERT_SEVERITY" \
        "Injected alert exposes severity='${ALERT_SEVERITY}' value"
    assert_contains "$alerts" "$ALERT_MESSAGE" \
        "Injected alert exposes message='${ALERT_MESSAGE}' value"
    assert_contains "$alerts" "injected" \
        "Injected alert marked with source='injected' (distinguishes operator-driven from threshold-driven)"
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
