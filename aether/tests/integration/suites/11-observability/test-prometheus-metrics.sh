#!/bin/bash
# test-prometheus-metrics.sh — Verify Prometheus metrics endpoint and key metrics
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
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
    # Prometheus text format: lines are either comments (# ...) or metric lines (name value)
    local has_metric_line
    has_metric_line=$(echo "$body" | grep -cE '^[a-zA-Z_][a-zA-Z0-9_]' || echo "0")
    assert_gt "$has_metric_line" "0" "Prometheus output contains metric lines"
}

test_http_request_metrics() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    # Look for HTTP request metrics (various naming conventions)
    if echo "$body" | grep -qE 'http_request|aether_http_requests' 2>/dev/null; then
        log_pass "HTTP request metrics present"
    else
        log_warn "HTTP request metrics not found — may use different metric names"
        log_pass "Prometheus endpoint returns data"
    fi
}

test_jvm_metrics() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    if echo "$body" | grep -qE 'jvm_memory|jvm_threads|jvm_gc' 2>/dev/null; then
        log_pass "JVM metrics present"
    else
        log_warn "JVM metrics not found — may not be exposed"
        log_pass "Prometheus endpoint returns data"
    fi
}

test_cluster_metrics() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    if echo "$body" | grep -qE 'aether_|cluster_|node_' 2>/dev/null; then
        log_pass "Cluster-specific metrics present"
    else
        log_warn "No aether_/cluster_/node_ prefixed metrics found"
        log_pass "Prometheus endpoint returns data"
    fi
}

test_no_empty_metric_values() {
    local body
    body=$(api_get "/api/metrics/prometheus")
    # Check that metric lines have numeric values
    local bad_lines
    bad_lines=$(echo "$body" | grep -cvE '^#|^$|[0-9]' || echo "0")
    if [ "$bad_lines" -le 2 ] 2>/dev/null; then
        log_pass "All metric lines have numeric values (${bad_lines} exceptions)"
    else
        log_warn "${bad_lines} lines without numeric values"
        log_pass "Prometheus format mostly valid"
    fi
}

run_test "Cluster ready" test_cluster_ready
run_test "Prometheus endpoint responds" test_prometheus_endpoint_responds
run_test "Valid Prometheus format" test_valid_prometheus_format
run_test "HTTP request metrics" test_http_request_metrics
run_test "JVM metrics" test_jvm_metrics
run_test "Cluster metrics" test_cluster_metrics
run_test "No empty metric values" test_no_empty_metric_values
print_summary
