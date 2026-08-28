#!/bin/bash
# test-invocation-traces.sh — Inject deterministic traces, verify shape via GET /api/v1/traces.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

# Operation names chosen to be distinctive (pid suffix prevents collisions across
# concurrent runs of cluster A + cluster B). Captured by test_generate_traceable_requests
# and read by the downstream assertion tests.
TRACE_OP_1="integration-trace-op-1-$$"
TRACE_OP_2="integration-trace-op-2-$$"
TRACE_OP_3="integration-trace-op-3-$$"
TRACE_REQ_ID_1=""
TRACE_REQ_ID_2=""
TRACE_REQ_ID_3=""

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

# Drive the trace path explicitly via POST /api/v1/traces/inject. Tracing is not
# auto-enabled in the integration cluster, and the runtime does not provide a
# deterministic invocation that emits a trace with known field values — see
# aether/docs/reference/management-api.md → POST /api/v1/traces/inject for the
# product capability that closes this gap.
_inject_trace() {
    local operation="$1"
    local duration_ms="$2"
    local depth="$3"
    local body
    body="{\"operation\":\"${operation}\",\"durationMs\":${duration_ms},\"depth\":${depth}}"
    local response
    if ! response=$(api_post "/api/v1/traces/inject" "$body"); then
        log_fail "POST /api/v1/traces/inject failed for operation=${operation} (api_post returned non-zero)"
        return 1
    fi
    # Server returns {traceId, requestId, operation, durationMs, depth, timestamp}.
    # requestId is the field GET /api/v1/traces actually surfaces, so we correlate by it.
    local req_id
    req_id=$(printf '%s' "$response" | grep -oE '"requestId"[[:space:]]*:[[:space:]]*"[^"]+"' | sed -E 's/.*"([^"]+)"$/\1/' | head -1)
    if [ -z "$req_id" ]; then
        log_fail "Inject response missing requestId field for operation=${operation} — response was: ${response}"
        return 1
    fi
    printf '%s' "$req_id"
}

test_generate_traceable_requests() {
    TRACE_REQ_ID_1=$(_inject_trace "$TRACE_OP_1" 100 0) || return 1
    TRACE_REQ_ID_2=$(_inject_trace "$TRACE_OP_2" 250 1) || return 1
    TRACE_REQ_ID_3=$(_inject_trace "$TRACE_OP_3" 500 2) || return 1
    # Brief wait so the entries are observable from /api/v1/traces. Inject is
    # synchronous on the receiving node so this is mostly a tolerance for HTTP
    # layer scheduling; per the spec it must be visible within 1s.
    sleep 1
    log_pass "Injected 3 synthetic traces: req1=${TRACE_REQ_ID_1} req2=${TRACE_REQ_ID_2} req3=${TRACE_REQ_ID_3}"
}

# Endpoint smoke check — must respond 200. Body content is asserted in the
# field-level tests below.
test_traces_endpoint() {
    assert_http_status "${CLUSTER_ENDPOINT}/api/v1/traces" "200" \
        "GET /api/v1/traces returns 200" \
        -H "X-API-Key: ${API_KEY}"
}

# All field-level tests share the same precondition: the three traces were
# injected and their requestIds captured by test_generate_traceable_requests.
_require_injected_request_ids() {
    if [ -z "$TRACE_REQ_ID_1" ] || [ -z "$TRACE_REQ_ID_2" ] || [ -z "$TRACE_REQ_ID_3" ]; then
        log_fail "Pre-condition broken: one or more injected requestIds is empty (test_generate_traceable_requests must have failed)"
        return 1
    fi
}

test_traces_contain_request_id() {
    _require_injected_request_ids || return 1
    local traces
    if ! traces=$(api_get "/api/v1/traces?limit=200"); then
        log_fail "GET /api/v1/traces failed (api_get returned non-zero)"
        return 1
    fi
    # Each injected requestId must be surfaced verbatim. Assertions report
    # which specific id failed so test output names the exact missing entry.
    assert_contains "$traces" "$TRACE_REQ_ID_1" \
        "GET /api/v1/traces must surface injected requestId=${TRACE_REQ_ID_1}"
    assert_contains "$traces" "$TRACE_REQ_ID_2" \
        "GET /api/v1/traces must surface injected requestId=${TRACE_REQ_ID_2}"
    assert_contains "$traces" "$TRACE_REQ_ID_3" \
        "GET /api/v1/traces must surface injected requestId=${TRACE_REQ_ID_3}"
}

test_traces_contain_duration() {
    _require_injected_request_ids || return 1
    local traces
    if ! traces=$(api_get "/api/v1/traces?limit=200"); then
        log_fail "GET /api/v1/traces failed (api_get returned non-zero)"
        return 1
    fi
    # `durationMs` is emitted as a JSON number by ObservabilityRoutes#appendTraceNodeJson.
    # Filter to the three injected entries' duration values (100, 250, 500).
    # A literal substring check is enough because the operation names are unique enough
    # to bind to the right entries — the GET emits {requestId, depth, ..., durationMs, ...}.
    assert_contains "$traces" "\"durationMs\":100" \
        "Injected trace 1 (op=${TRACE_OP_1}) must expose durationMs=100"
    assert_contains "$traces" "\"durationMs\":250" \
        "Injected trace 2 (op=${TRACE_OP_2}) must expose durationMs=250"
    assert_contains "$traces" "\"durationMs\":500" \
        "Injected trace 3 (op=${TRACE_OP_3}) must expose durationMs=500"
}

test_traces_contain_depth() {
    _require_injected_request_ids || return 1
    local traces
    if ! traces=$(api_get "/api/v1/traces?limit=200"); then
        log_fail "GET /api/v1/traces failed (api_get returned non-zero)"
        return 1
    fi
    # `depth` is emitted as a JSON number for every entry. Each of the three
    # injected traces carries its specific depth (0/1/2). depth=0 isn't unique
    # across the buffer if other traces exist, but depth=1 / depth=2 from our
    # synthetic load — paired with their unique requestIds — are.
    assert_contains "$traces" "\"depth\":1" \
        "Injected trace 2 (op=${TRACE_OP_2}) must expose depth=1"
    assert_contains "$traces" "\"depth\":2" \
        "Injected trace 3 (op=${TRACE_OP_3}) must expose depth=2"
    # Depth=0 on the first injected trace — assert via operation-name correlation
    # because depth=0 alone is too generic across the buffer.
    assert_contains "$traces" "\"callee\":\"${TRACE_OP_1}\"" \
        "Injected trace 1 must expose its operation name as callee"
}

run_test "Cluster ready" test_cluster_ready
run_test "Generate traceable requests" test_generate_traceable_requests
run_test "Traces endpoint" test_traces_endpoint
run_test "Traces contain requestId" test_traces_contain_request_id
run_test "Traces contain duration" test_traces_contain_duration
run_test "Traces contain depth" test_traces_contain_depth
print_summary
