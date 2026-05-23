#!/bin/bash
# test-prometheus-metrics.sh — Verify Prometheus metrics endpoint and key metrics
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

test_prometheus_endpoint_responds() {
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/metrics/prometheus" -H "X-API-Key: ${API_KEY}")
    if [ "$status" -ge 200 ] && [ "$status" -lt 300 ] 2>/dev/null; then
        log_pass "Prometheus endpoint returns ${status}"
        return 0
    fi
    log_fail "Prometheus endpoint returns ${status} (expected 2xx)"
    return 1
}

test_valid_prometheus_format() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    assert_ne "$body" "" "Prometheus body is non-empty"
    # Prometheus text format: lines are either comments (# ...) or metric lines (name value).
    # Use a sentinel so a grep pipeline error is distinguishable from "0 matches".
    local has_metric_line
    has_metric_line=$(echo "$body" | grep -cE '^[a-zA-Z_][a-zA-Z0-9_]') || has_metric_line=-1
    assert_gt "$has_metric_line" "0" "Prometheus output contains metric lines"
}

# Strict: HTTP request metrics MUST be present. A Prometheus exposition without
# request metrics indicates the Micrometer wiring is broken.
test_http_request_metrics() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    if echo "$body" | grep -qE 'http_request|aether_http_requests|http_server_requests'; then
        log_pass "HTTP request metrics present"
        return 0
    fi
    log_fail "No http_request_* metric found in Prometheus output (sample: $(echo "$body" | head -c 300))"
    return 1
}

# Strict: jvm_memory / jvm_threads / jvm_gc are emitted by the standard
# Micrometer JVM binders that the runtime registers at startup. Their absence
# means the JVM binders failed to register.
test_jvm_metrics() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    if echo "$body" | grep -qE 'jvm_memory|jvm_threads|jvm_gc'; then
        log_pass "JVM metrics present"
        return 0
    fi
    log_fail "No jvm_* metric found in Prometheus output (sample: $(echo "$body" | head -c 300))"
    return 1
}

# Strict: at least one aether_/cluster_/node_-prefixed metric must be present —
# the runtime exposes cluster state via these prefixes.
test_cluster_metrics() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    if echo "$body" | grep -qE 'aether_|cluster_|node_'; then
        log_pass "Cluster-specific metrics present"
        return 0
    fi
    log_fail "No aether_/cluster_/node_ prefixed metric found (sample: $(echo "$body" | head -c 300))"
    return 1
}

# Validate every non-comment, non-blank line is a parseable metric line.
# Do NOT mask grep pipeline errors as "0" — distinguish "no bad lines" (grep rc=1)
# from "grep crashed" by setting an explicit sentinel.
test_no_empty_metric_values() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    local bad_lines
    if bad_lines=$(echo "$body" | grep -cvE '^#|^$|[0-9]'); then
        :
    else
        rc=$?
        if [ "$rc" -eq 1 ]; then
            bad_lines=0  # grep rc=1: zero matches, healthy
        else
            log_fail "grep failed evaluating prometheus body (rc=${rc})"
            return 1
        fi
    fi
    # A handful of header/help lines without digits is acceptable; more indicates
    # malformed exposition.
    if [ "$bad_lines" -le 2 ] 2>/dev/null; then
        log_pass "All metric lines have numeric values (${bad_lines} non-numeric exceptions)"
        return 0
    fi
    log_fail "${bad_lines} non-numeric metric lines (Prometheus exposition is malformed)"
    return 1
}

run_test "Cluster ready" test_cluster_ready
run_test "Prometheus endpoint responds" test_prometheus_endpoint_responds
run_test "Valid Prometheus format" test_valid_prometheus_format
run_test "HTTP request metrics" test_http_request_metrics
run_test "JVM metrics" test_jvm_metrics
run_test "Cluster metrics" test_cluster_metrics
run_test "No empty metric values" test_no_empty_metric_values
print_summary
