#!/bin/bash
# test-invocation-traces.sh — Make requests, verify traces captured
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster 60
    log_pass "Cluster ready"
}

test_generate_traceable_requests() {
    # Make several requests to generate traces
    for i in $(seq 1 10); do
        api_get "/api/status" > /dev/null 2>&1 || true
        api_get "/api/nodes" > /dev/null 2>&1 || true
    done
    sleep 2
    log_pass "Generated 20 traceable requests"
}

# Endpoint smoke check — must respond 200. Body content (whether traces are
# captured at all) is asserted in the field-level tests below.
test_traces_endpoint() {
    assert_http_status "${CLUSTER_ENDPOINT}/api/traces" "200" \
        "GET /api/traces returns 200" \
        -H "X-API-Key: ${API_KEY}"
}

# UNTESTABLE without product capability: tracing is not auto-enabled in the
# integration cluster, and there is no management endpoint to enable it or
# inject a deterministic trace. Without a way to *cause* a trace to exist,
# we cannot assert the trace's shape — empty body would silently pass.
test_traces_contain_request_id() {
    log_fail "TODO: trace injection mechanism not yet exposed via API — cannot assert requestId/traceId field shape until traces can be deterministically produced"
    return 1
}

test_traces_contain_duration() {
    log_fail "TODO: trace injection mechanism not yet exposed via API — cannot assert duration field shape until traces can be deterministically produced"
    return 1
}

test_traces_contain_depth() {
    log_fail "TODO: trace injection mechanism not yet exposed via API — cannot assert depth/span field shape until traces can be deterministically produced"
    return 1
}

run_test "Cluster ready" test_cluster_ready
run_test "Generate traceable requests" test_generate_traceable_requests
run_test "Traces endpoint" test_traces_endpoint
run_test "Traces contain requestId" test_traces_contain_request_id
run_test "Traces contain duration" test_traces_contain_duration
run_test "Traces contain depth" test_traces_contain_depth
print_summary
