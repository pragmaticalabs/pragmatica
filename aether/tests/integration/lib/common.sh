#!/bin/bash
# common.sh — Shared functions for Aether integration tests
# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

# Environment
: "${TARGET_HOST:?TARGET_HOST must be set}"

MGMT_PORT="${MGMT_PORT:-5150}"
APP_PORT="${APP_PORT:-8070}"
CLUSTER_ENDPOINT="${CLUSTER_ENDPOINT:-http://${TARGET_HOST}:${MGMT_PORT}}"
APP_ENDPOINT="${APP_ENDPOINT:-http://${TARGET_HOST}:${APP_PORT}}"
API_KEY="${AETHER_API_KEY:-}"
ADMIN_API_KEY="${AETHER_ADMIN_API_KEY:-${API_KEY}}"
VIEWER_API_KEY="${AETHER_VIEWER_API_KEY:-}"
OPERATOR_API_KEY="${AETHER_OPERATOR_API_KEY:-${API_KEY}}"

# ---------------------------------------------------------------------------
# Aether CLI
# ---------------------------------------------------------------------------
AETHER_CLI="aether -c ${TARGET_HOST}:${MGMT_PORT}"
NODE_COUNT="${NODE_COUNT:-5}"

# Try CLI command against available nodes (failover on connection failure)
# Tries ports MGMT_PORT, MGMT_PORT+1, ... MGMT_PORT+NODE_COUNT-1
aether_failover() {
    local base_port="${MGMT_PORT}"
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local port=$((base_port + i))
        local result
        result=$(aether -c "${TARGET_HOST}:${port}" "$@" 2>/dev/null) && { echo "$result"; return 0; }
    done
    return 1
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
# HTTP helpers — management API (port 5150)
# Retained for tests that need raw HTTP access (status codes, custom headers)
# ---------------------------------------------------------------------------
api_get() {
    local path="$1"
    curl -sf -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}${path}"
}

api_post() {
    local path="$1" body="${2:-{}}"
    curl -sf -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "$body" "${CLUSTER_ENDPOINT}${path}"
}

api_put() {
    local path="$1" body="${2:-{}}"
    curl -sf -X PUT -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "$body" "${CLUSTER_ENDPOINT}${path}"
}

api_delete() {
    local path="$1"
    curl -sf -X DELETE -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}${path}"
}

# HTTP helpers — app HTTP (port 8070)
app_get() {
    local path="$1"
    curl -sf -H "X-API-Key: ${API_KEY}" "${APP_ENDPOINT}${path}"
}

app_post() {
    local path="$1" body="${2:-{}}"
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
    actual=$(echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin)${field})" 2>/dev/null)
    assert_eq "$actual" "$expected" "$desc"
}

# ---------------------------------------------------------------------------
# JSON helpers (retained for suites that parse raw JSON responses)
# ---------------------------------------------------------------------------
json_field() {
    local json="$1" field="$2"
    echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin)${field})" 2>/dev/null
}

json_len() {
    local json="$1"
    echo "$json" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null
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
# Test runner
# ---------------------------------------------------------------------------
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0

run_test() {
    local name="$1" fn="$2"
    echo ""
    log_step "=== TEST: ${name} ==="
    if "$fn"; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
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
    [ "$TESTS_FAILED" -eq 0 ]
}

# ---------------------------------------------------------------------------
# Timestamps
# ---------------------------------------------------------------------------
now_epoch() { date +%s; }
elapsed_since() { echo $(( $(now_epoch) - $1 )); }
