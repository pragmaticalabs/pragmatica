#!/bin/bash
# common.sh — Shared functions for Aether integration tests

LIB_DIR_COMMON="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR_COMMON}/json.sh"

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

# Environment
: "${TARGET_HOST:?TARGET_HOST must be set}"

MGMT_PORT="${MGMT_PORT:-5150}"
APP_PORT="${APP_PORT:-8070}"
LB_PORT="${LB_PORT:-9090}"
LB_MGMT_PORT="${LB_MGMT_PORT:-9091}"
# Witness (operator entry point) — stable node that tests MUST NOT kill.
# Tests hit MGMT_ENTRY_POINT for every management call; the product's HttpForwardRequest
# routes the request to the appropriate node. Exercises the forwarding contract rather
# than relying on client-side port-hopping.
# Default cluster-B witness: port 5165 (aether-b-witness). Cluster A is non-destructive —
# its tests can keep targeting MGMT_PORT (5150).
MGMT_ENTRY_POINT="${MGMT_ENTRY_POINT:-http://${TARGET_HOST}:${MGMT_PORT}}"
# App traffic → LB public port; management API → MGMT_ENTRY_POINT (witness or LB).
CLUSTER_ENDPOINT="${CLUSTER_ENDPOINT:-${MGMT_ENTRY_POINT}}"
APP_ENDPOINT="${APP_ENDPOINT:-http://${TARGET_HOST}:${LB_PORT}}"
LB_ENDPOINT="${LB_ENDPOINT:-http://${TARGET_HOST}:${LB_PORT}}"
# Direct node access (legitimate per-node queries — e.g., "is METRICS ACTIVE on node-2?").
# NOT a client-side failover mechanism. Management calls go through MGMT_ENTRY_POINT.
DIRECT_ENDPOINT="http://${TARGET_HOST}:${MGMT_PORT}"
API_KEY="${AETHER_API_KEY:-aether-integration-test-key}"
ADMIN_API_KEY="${AETHER_ADMIN_API_KEY:-${API_KEY}}"
VIEWER_API_KEY="${AETHER_VIEWER_API_KEY:-}"
OPERATOR_API_KEY="${AETHER_OPERATOR_API_KEY:-${API_KEY}}"
export AETHER_API_KEY="${API_KEY}"

# ---------------------------------------------------------------------------
# Aether CLI
# ---------------------------------------------------------------------------
AETHER_CLI="aether -c ${TARGET_HOST}:${LB_PORT}"
NODE_COUNT="${NODE_COUNT:-5}"

# Run an Aether CLI command against MGMT_ENTRY_POINT (the pinned operator node).
# The cluster's HttpForwardRequest mechanism routes the command to the appropriate
# node internally.
#
# Resilience: during destructive suites the pinned node may be temporarily dead
# (killed by a chaos test). If MGMT_ENTRY_POINT does not respond to /health/live
# within 2s, rotate once to any live core node and use that for this call only —
# the per-call override keeps the pinned-endpoint contract for the next invocation,
# so forwarding bugs still surface on the happy path.
aether_failover() {
    local timeout="${AETHER_CLI_TIMEOUT:-5}"
    local host_port="${MGMT_ENTRY_POINT#http://}"
    if ! curl -sf -m 2 -H "X-API-Key: ${API_KEY}" "${MGMT_ENTRY_POINT}/health/live" >/dev/null 2>&1; then
        local base_port="${MGMT_PORT}"
        for i in $(seq 0 $((NODE_COUNT - 1))); do
            local port=$((base_port + i))
            if curl -sf -m 2 -H "X-API-Key: ${API_KEY}" "http://${TARGET_HOST}:${port}/health/live" >/dev/null 2>&1; then
                host_port="${TARGET_HOST}:${port}"
                break
            fi
        done
    fi
    aether -c "${host_port}" --api-key "${API_KEY}" "--request-timeout=${timeout}" "$@"
}

# Query a CLI command and extract a single field (--format value --field)
# Usage: aether_field <command> <field>
# Example: aether_field status cluster.nodeCount
aether_field() {
    local command="$1" field="$2"
    aether_failover "$command" --format value --field "$field"
}

# Query a CLI command and return full JSON output
# Usage: aether_json <command> [extra-args...]
# Example: aether_json status
aether_json() {
    local command="$1"; shift
    aether_failover "$command" --format json "$@"
}

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
log_info()  { echo -e "${GREEN}[INFO]${NC}  $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_pass()  { echo -e "${GREEN}[PASS]${NC}  $1"; }
log_fail()  { echo -e "${RED}[FAIL]${NC}  $1"; }
log_step()  { echo -e "${BLUE}[STEP]${NC}  $1"; }

# ---------------------------------------------------------------------------
# HTTP helpers — management API
# Retained for tests that need raw HTTP access (status codes, custom headers)
# ---------------------------------------------------------------------------
# Resolve an endpoint that actually responds to /health/live. Preserves the pinned
# CLUSTER_ENDPOINT when it's up; rotates once to any live core node when the pinned
# endpoint is dead (e.g., during chaos-suite recovery where the pinned node was killed).
_resolve_live_endpoint() {
    if curl -sf -m 2 -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}/health/live" >/dev/null 2>&1; then
        echo "${CLUSTER_ENDPOINT}"
        return 0
    fi
    local base_port="${MGMT_PORT}"
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local port=$((base_port + i))
        local endpoint="http://${TARGET_HOST}:${port}"
        if curl -sf -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
            echo "${endpoint}"
            return 0
        fi
    done
    echo "${CLUSTER_ENDPOINT}"  # fall back; caller will see curl failure
    return 1
}

api_get() {
    local path="$1"
    local endpoint
    endpoint=$(_resolve_live_endpoint)
    curl -sf -H "X-API-Key: ${API_KEY}" "${endpoint}${path}"
}

api_post() {
    local path="$1"
    local body="${2:-"{}"}"
    local endpoint
    endpoint=$(_resolve_live_endpoint)
    curl -sf -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "$body" "${endpoint}${path}"
}

api_put() {
    local path="$1"
    local body="${2:-"{}"}"
    curl -sf -X PUT -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "$body" "${CLUSTER_ENDPOINT}${path}"
}

api_delete() {
    local path="$1"
    curl -sf -X DELETE -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}${path}"
}

# Per-node HTTP helpers — for legitimate per-node state queries.
# Example: "is METRICS task group ACTIVE on node-2 specifically?"
# NOT a client-side failover mechanism. Management calls go through api_get/api_post → MGMT_ENTRY_POINT.
#
# Caller supplies the 0-based offset of the target core node (0 → MGMT_PORT, 1 → MGMT_PORT+1, ...).
node_api_get() {
    local offset="$1" path="$2"
    local port=$((MGMT_PORT + offset))
    curl -sf -H "X-API-Key: ${API_KEY}" "http://${TARGET_HOST}:${port}${path}"
}

node_api_post() {
    local offset="$1" path="$2" body="${3:-"{}"}"
    local port=$((MGMT_PORT + offset))
    curl -sf -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "$body" "http://${TARGET_HOST}:${port}${path}"
}

# Back-compat shims — forward to the MGMT_ENTRY_POINT, no client-side failover.
# Existing callers using direct_api_get/direct_api_post as a "bypass LB" mechanism
# were really just hitting the cluster's management API; witness handles that via forwarding.
direct_api_get() {
    api_get "$1"
}

direct_api_post() {
    api_post "$1" "${2:-"{}"}"
}

# HTTP helpers — app HTTP (port 8070)
app_get() {
    local path="$1"
    curl -sf -H "X-API-Key: ${API_KEY}" "${APP_ENDPOINT}${path}"
}

app_post() {
    local path="$1"
    local body="${2:-"{}"}"
    curl -sf -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "$body" "${APP_ENDPOINT}${path}"
}

# Raw curl (no -f) — returns status code
http_status() {
    local url="$1"; shift
    curl -s -o /dev/null -w "%{http_code}" "$@" "$url"
}

# ---------------------------------------------------------------------------
# Wait for condition with timeout
# ---------------------------------------------------------------------------
wait_for() {
    local description="$1" check_cmd="$2" timeout="${3:-60}" interval="${4:-2}"
    local elapsed=0
    log_info "Waiting for: ${description} (timeout: ${timeout}s)"
    while [ "$elapsed" -lt "$timeout" ]; do
        if eval "$check_cmd" > /dev/null 2>&1; then
            log_pass "${description} (${elapsed}s)"
            return 0
        fi
        sleep "$interval"
        elapsed=$((elapsed + interval))
    done
    log_fail "${description} (timed out after ${timeout}s)"
    return 1
}

# ---------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------
assert_eq() {
    local actual="$1" expected="$2" desc="$3"
    if [ "$actual" = "$expected" ]; then
        log_pass "$desc"
        return 0
    fi
    log_fail "${desc}: expected '${expected}', got '${actual}'"
    return 1
}

assert_ne() {
    local actual="$1" unexpected="$2" desc="$3"
    if [ "$actual" != "$unexpected" ]; then
        log_pass "$desc"
        return 0
    fi
    log_fail "${desc}: expected NOT '${unexpected}', got '${actual}'"
    return 1
}

assert_gt() {
    local actual="$1" threshold="$2" desc="$3"
    if [ "$actual" -gt "$threshold" ] 2>/dev/null; then
        log_pass "$desc"
        return 0
    fi
    log_fail "${desc}: expected > ${threshold}, got '${actual}'"
    return 1
}

assert_ge() {
    local actual="$1" threshold="$2" desc="$3"
    if [ "$actual" -ge "$threshold" ] 2>/dev/null; then
        log_pass "$desc"
        return 0
    fi
    log_fail "${desc}: expected >= ${threshold}, got '${actual}'"
    return 1
}

assert_contains() {
    local haystack="$1" needle="$2" desc="$3"
    if echo "$haystack" | grep -q "$needle"; then
        log_pass "$desc"
        return 0
    fi
    log_fail "${desc}: output does not contain '${needle}'"
    return 1
}

assert_http_status() {
    local url="$1" expected="$2" desc="$3"; shift 3
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" "$@" "$url")
    assert_eq "$status" "$expected" "$desc"
}

assert_json_field() {
    local json="$1" field="$2" expected="$3" desc="$4"
    local actual
    actual=$(json_value "$json" "$field")
    assert_eq "$actual" "$expected" "$desc"
}

# ---------------------------------------------------------------------------
# JSON helpers (shell-based, see lib/json.sh)
# ---------------------------------------------------------------------------
json_field() {
    local json="$1" field="$2"
    json_value "$json" "$field"
}

json_len() {
    local json="$1"
    json_array_length "$json"
}

# ---------------------------------------------------------------------------
# SSH helper
# ---------------------------------------------------------------------------
remote_exec() {
    : "${AETHER_SSH_USER:?AETHER_SSH_USER must be set for remote_exec}"
    : "${AETHER_SSH_KEY:?AETHER_SSH_KEY must be set for remote_exec}"
    ssh -i "$AETHER_SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
        "${AETHER_SSH_USER}@${TARGET_HOST}" "$@"
}

# ---------------------------------------------------------------------------
# Environment type and cloud access
# ---------------------------------------------------------------------------
ENV_TYPE="${ENV_TYPE:-docker}"
CLOUD_MODE="${CLOUD_MODE:-false}"   # backward compat: true maps to ENV_TYPE=cloud
if [ "$CLOUD_MODE" = "true" ]; then ENV_TYPE="cloud"; fi
BASTION_IP="${BASTION_IP:-}"
CLOUD_TIMEOUT_MULTIPLIER="${CLOUD_TIMEOUT_MULTIPLIER:-1}"

# Map node-N to private IP (cloud mode only)
cloud_node_ip() {
    local node_id="$1"
    local num
    num=$(echo "$node_id" | sed 's/node-//')
    echo "10.0.1.1${num}"
}

# SSH to a cloud node via the bastion (LB VM)
cloud_ssh() {
    local node_id="$1"; shift
    local ip
    ip=$(cloud_node_ip "$node_id")
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
        -J "${AETHER_SSH_USER}@${BASTION_IP}" \
        -i "${AETHER_SSH_KEY}" \
        "${AETHER_SSH_USER}@${ip}" "$@"
}

# ---------------------------------------------------------------------------
# Node metrics collection (opt-in via COLLECT_METRICS=true)
# ---------------------------------------------------------------------------
METRICS_DIR="${METRICS_DIR:-/tmp/aether-test-metrics}"

# Collect thread count, RSS, heap info from all running nodes
collect_node_metrics() {
    local label="$1"
    local timestamp
    timestamp=$(date +%Y%m%d_%H%M%S)
    local outfile="${METRICS_DIR}/${timestamp}_${label}.txt"

    mkdir -p "$METRICS_DIR"

    echo "=== Node Metrics: ${label} (${timestamp}) ===" > "$outfile"

    for i in $(seq 1 "$NODE_COUNT"); do
        local container="aether-node-$i"
        echo "" >> "$outfile"
        echo "--- ${container} ---" >> "$outfile"

        # Thread count + RSS + VmSize
        remote_exec "docker exec ${container} sh -c 'cat /proc/1/status 2>/dev/null | grep -E \"Threads|VmRSS|VmSize|VmPeak\"'" >> "$outfile" 2>/dev/null || true

        # Java heap info (ZGC)
        remote_exec "docker exec ${container} jcmd 1 GC.heap_info" >> "$outfile" 2>/dev/null || true

        echo "" >> "$outfile"
    done

    log_info "Metrics saved: $outfile"
}

# Wrapper: collect before test
collect_metrics_before() {
    local test_name="$1"
    collect_node_metrics "before-${test_name}"
}

# Wrapper: collect after test
collect_metrics_after() {
    local test_name="$1"
    collect_node_metrics "after-${test_name}"
}

# Print summary of metrics diff (before vs after)
print_metrics_summary() {
    local test_name="$1"
    local before_file after_file
    before_file=$(ls -t "${METRICS_DIR}"/*_before-"${test_name}".txt 2>/dev/null | head -1)
    after_file=$(ls -t "${METRICS_DIR}"/*_after-"${test_name}".txt 2>/dev/null | head -1)

    if [[ -f "$before_file" && -f "$after_file" ]]; then
        echo -e "${BLUE}=== Metrics Delta: ${test_name} ===${NC}"
        echo "Before: $before_file"
        echo "After:  $after_file"
        # Show side-by-side thread counts
        paste <(grep "Threads:" "$before_file") <(grep "Threads:" "$after_file") | \
            awk '{printf "  Threads: %s -> %s\n", $2, $4}'
        paste <(grep "VmRSS:" "$before_file") <(grep "VmRSS:" "$after_file") | \
            awk '{printf "  RSS: %s kB -> %s kB\n", $2, $4}'
    fi
}

# ---------------------------------------------------------------------------
# Test runner
# ---------------------------------------------------------------------------
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0

run_test() {
    local name="$1" fn="$2"
    local sanitized_name
    sanitized_name=$(echo "$name" | tr ' /' '_' | tr -cd '[:alnum:]_-')
    echo ""
    log_step "=== TEST: ${name} ==="

    if [[ "${COLLECT_METRICS:-false}" == "true" ]]; then
        collect_metrics_before "$sanitized_name"
    fi

    local t_start t_elapsed
    t_start=$(date +%s)
    if "$fn"; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
    t_elapsed=$(( $(date +%s) - t_start ))
    log_info "duration: ${sanitized_name}=${t_elapsed}s"
    # Emit per-test duration to the timings aggregator (issue #174).
    if [ -n "${QUIESCED_TIMINGS_FILE:-}" ]; then
        printf 'test_%s=%s\n' "$sanitized_name" "$t_elapsed" >> "$QUIESCED_TIMINGS_FILE"
    fi

    if [[ "${COLLECT_METRICS:-false}" == "true" ]]; then
        collect_metrics_after "$sanitized_name"
        print_metrics_summary "$sanitized_name"
    fi
}

skip_test() {
    local name="$1" reason="$2"
    echo ""
    log_warn "=== SKIP: ${name} — ${reason} ==="
    TESTS_SKIPPED=$((TESTS_SKIPPED + 1))
}

print_summary() {
    echo ""
    echo "========================================"
    echo "  PASSED:  ${TESTS_PASSED}"
    echo "  FAILED:  ${TESTS_FAILED}"
    echo "  SKIPPED: ${TESTS_SKIPPED}"
    echo "========================================"

    if [[ "${COLLECT_METRICS:-false}" == "true" ]]; then
        echo "  METRICS: ${METRICS_DIR}"
    fi

    [ "$TESTS_FAILED" -eq 0 ]
}

# ---------------------------------------------------------------------------
# Timestamps
# ---------------------------------------------------------------------------
now_epoch() { date +%s; }
elapsed_since() { echo $(( $(now_epoch) - $1 )); }
