#!/bin/bash
# test-transport-metrics.sh — Verify QUIC transport metrics
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_transport_metrics_endpoint() {
    local metrics
    metrics=$(api_get "/api/metrics/transport")
    assert_ne "$metrics" "" "Transport metrics endpoint returns data"
}

# Strict: a connection metric MUST be present. Any of the canonical names is acceptable
# (the API has used several historical naming conventions), but at least one must appear.
test_active_connections_metric() {
    local metrics
    metrics=$(api_get "/api/metrics/transport")
    if echo "$metrics" | grep -qiE 'active.?connections|connections.?active|connectionCount|"[^"]*connect[^"]*"[[:space:]]*:'; then
        log_pass "Active/connection metric present"
        return 0
    fi
    log_fail "No connection metric in transport response: $(echo "$metrics" | head -c 300)"
    return 1
}

test_messages_sent_metric() {
    local metrics
    metrics=$(api_get "/api/metrics/transport")
    if echo "$metrics" | grep -qiE 'messages.?sent|sent.?messages|messagesSent|bytes.?sent|bytesSent'; then
        log_pass "Messages/bytes sent metric present"
        return 0
    fi
    log_fail "No sent metric (messagesSent/bytesSent) in transport response: $(echo "$metrics" | head -c 300)"
    return 1
}

test_messages_received_metric() {
    local metrics
    metrics=$(api_get "/api/metrics/transport")
    if echo "$metrics" | grep -qiE 'messages.?received|received.?messages|messagesReceived|bytes.?received|bytesReceived'; then
        log_pass "Messages/bytes received metric present"
        return 0
    fi
    log_fail "No received metric (messagesReceived/bytesReceived) in transport response: $(echo "$metrics" | head -c 300)"
    return 1
}

# In a healthy cluster with inter-node QUIC traffic, at least one numeric value
# must be > 0 (e.g. messages exchanged during heartbeats / consensus). All-zero
# means transport metrics aren't actually being recorded.
test_transport_metrics_non_zero() {
    local metrics
    metrics=$(api_get "/api/metrics/transport")
    if echo "$metrics" | grep -qE ':[[:space:]]*[1-9][0-9]*(\.[0-9]+)?'; then
        log_pass "Transport metrics contain non-zero values"
        return 0
    fi
    log_fail "All transport metric values are zero — recording appears broken: $(echo "$metrics" | head -c 300)"
    return 1
}

run_test "Cluster ready" test_cluster_ready
run_test "Transport metrics endpoint" test_transport_metrics_endpoint
run_test "Active connections metric" test_active_connections_metric
run_test "Messages sent metric" test_messages_sent_metric
run_test "Messages received metric" test_messages_received_metric
run_test "Non-zero transport values" test_transport_metrics_non_zero
print_summary
