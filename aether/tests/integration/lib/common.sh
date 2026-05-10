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
    if ! curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${MGMT_ENTRY_POINT}/health/live" >/dev/null 2>&1; then
        # Failover: probe alternate live core endpoints.
        # docker/remote: nodes share TARGET_HOST with sequential mgmt ports (MGMT_PORT+0..N-1).
        # cloud: each node has its own VM public IP; resolve via cloud_public_ip per node-id.
        if [ "${ENV_TYPE:-docker}" = "cloud" ] && command -v cloud_public_ip >/dev/null 2>&1; then
            for n in $(seq 0 $((NODE_COUNT - 1))); do
                local node_ip
                node_ip=$(cloud_public_ip "node-$((n+1))" 2>/dev/null || echo "")
                [ -z "$node_ip" ] && continue
                if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${MGMT_SCHEME}://${node_ip}:${MGMT_PORT}/health/live" >/dev/null 2>&1; then
                    host_port="${node_ip}:${MGMT_PORT}"
                    break
                fi
            done
        else
            local base_port="${MGMT_PORT}"
            for i in $(seq 0 $((NODE_COUNT - 1))); do
                local port=$((base_port + i))
                if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "http://${TARGET_HOST}:${port}/health/live" >/dev/null 2>&1; then
                    host_port="${TARGET_HOST}:${port}"
                    break
                fi
            done
        fi
    fi
    local cli_tls_flag=""
    if [ "${MGMT_SCHEME}" = "https" ]; then
        cli_tls_flag="--tls-skip-verify"
    fi
    aether -c "${MGMT_SCHEME}://${host_port}" --api-key "${API_KEY}" "--request-timeout=${timeout}" ${cli_tls_flag} "$@"
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
    if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}/health/live" >/dev/null 2>&1; then
        echo "${CLUSTER_ENDPOINT}"
        return 0
    fi
    local base_port="${MGMT_PORT}"
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local port=$((base_port + i))
        local endpoint="http://${TARGET_HOST}:${port}"
        if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
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
    _api_call GET "${endpoint}${path}"
}

api_post() {
    local path="$1"
    local body="${2:-"{}"}"
    local endpoint
    endpoint=$(_resolve_live_endpoint)
    _api_call POST "${endpoint}${path}" "$body"
}

api_put() {
    local path="$1"
    local body="${2:-"{}"}"
    _api_call PUT "${CLUSTER_ENDPOINT}${path}" "$body"
}

# Wraps `curl -sf` semantics (empty stdout + non-zero exit on HTTP error) with stderr
# diagnostic logging. The original `curl -sf` was silently dropping HTTP error bodies,
# which made cloud failures (e.g. "NotLeader", "TaskGroupInactive") invisible.
_api_call() {
    local method="$1" url="$2" body="${3:-}"
    local response status body_only
    if [ -n "$body" ]; then
        response=$(curl -sk -X "$method" -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
            -d "$body" -w "\n__API_HTTP_STATUS:%{http_code}__" "$url" 2>&1)
    else
        response=$(curl -sk -X "$method" -H "X-API-Key: ${API_KEY}" \
            -w "\n__API_HTTP_STATUS:%{http_code}__" "$url" 2>&1)
    fi
    status=$(printf '%s' "$response" | grep -oE '__API_HTTP_STATUS:[0-9]+__' | sed 's/__API_HTTP_STATUS://;s/__//')
    body_only=$(printf '%s' "$response" | sed '$d')
    if [ -n "$status" ] && [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
        printf '%s' "$body_only"
        return 0
    fi
    log_warn "api ${method} ${url#http://*/} status=${status:-000}: $(printf '%s' "$body_only" | head -c 300)" >&2
    return 1
}

api_delete() {
    local path="$1"
    _api_call DELETE "${CLUSTER_ENDPOINT}${path}"
}

# Per-node HTTP helpers — for legitimate per-node state queries.
# Example: "is METRICS task group ACTIVE on node-2 specifically?"
# NOT a client-side failover mechanism. Management calls go through api_get/api_post → MGMT_ENTRY_POINT.
#
# Caller supplies the 0-based offset of the target core node (0 → MGMT_PORT, 1 → MGMT_PORT+1, ...).
node_api_get() {
    local offset="$1" path="$2"
    local port=$((MGMT_PORT + offset))
    _api_call GET "http://${TARGET_HOST}:${port}${path}"
}

node_api_post() {
    local offset="$1" path="$2" body="${3:-"{}"}"
    local port=$((MGMT_PORT + offset))
    _api_call POST "http://${TARGET_HOST}:${port}${path}" "$body"
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

# HTTP helpers — app HTTP (port 8070).
# Routes through _api_call so HTTP error bodies surface as warn diagnostics rather
# than being silently dropped by `curl -sf` (the trap `_api_call` was built to fix).
app_get() {
    local path="$1"
    _api_call GET "${APP_ENDPOINT}${path}"
}

app_post() {
    local path="$1"
    local body="${2:-"{}"}"
    _api_call POST "${APP_ENDPOINT}${path}" "$body"
}

# Raw curl (no -f) — returns status code
http_status() {
    local url="$1"; shift
    curl -sk -o /dev/null -w "%{http_code}" "$@" "$url"
}

# ---------------------------------------------------------------------------
# Wait for condition with timeout
# ---------------------------------------------------------------------------
wait_for() {
    local description="$1" check_cmd="$2" timeout="${3:-60}" interval="${4:-2}"
    # Scale timeouts on slower environments (cloud VMs have higher inter-node latency than
    # docker-localhost). TIMEOUT_SCALE=3 default for cloud, 1 elsewhere — set in run-tests.sh.
    timeout=$((timeout * ${TIMEOUT_SCALE:-1}))
    local elapsed=0 rc errfile
    errfile=$(mktemp)
    log_info "Waiting for: ${description} (timeout: ${timeout}s)"
    while [ "$elapsed" -lt "$timeout" ]; do
        # Capture rc without tripping `set -e` from the caller — `eval` as a standalone
        # command would propagate its non-zero exit and abort the entire script when
        # the predicate is simply false. The `&& rc=0 || rc=$?` idiom swallows the exit
        # code into a captured variable, equivalent to the legacy `if eval; then`
        # protection without re-introducing the if/then nesting.
        eval "$check_cmd" > /dev/null 2>"$errfile" && rc=0 || rc=$?
        case "$rc" in
            0)
                log_pass "${description} (${elapsed}s)"
                rm -f "$errfile"
                return 0
                ;;
            2|127)
                # Bash parse error / command not found — predicate is buggy, not just false.
                # Surface it so a test author can fix the typo instead of waiting for timeout.
                log_warn "wait_for predicate emitted shell error (rc=${rc}): $(head -c 300 < "$errfile")"
                ;;
        esac
        sleep "$interval"
        elapsed=$((elapsed + interval))
    done
    log_fail "${description} (timed out after ${timeout}s)"
    rm -f "$errfile"
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
    status=$(curl -sk -o /dev/null -w "%{http_code}" "$@" "$url")
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
# SSH / SCP helpers
# ---------------------------------------------------------------------------
# Single source of truth for SSH options. Used by remote_exec, remote_scp, and
# cloud_ssh so a future change (timeout, ProxyJump, ControlMaster) lands in one
# place. ServerAliveInterval+ServerAliveCountMax detect stalled TCP within ~60s
# and abort — without them, ssh/scp block indefinitely on half-closed sockets
# (observed: 90+ min stall on a stale connection).
SSH_OPTS=(-o StrictHostKeyChecking=no
          -o ConnectTimeout=10
          -o ServerAliveInterval=15
          -o ServerAliveCountMax=4)

remote_exec() {
    : "${AETHER_SSH_USER:?AETHER_SSH_USER must be set for remote_exec}"
    : "${AETHER_SSH_KEY:?AETHER_SSH_KEY must be set for remote_exec}"
    ssh -i "$AETHER_SSH_KEY" "${SSH_OPTS[@]}" \
        "${AETHER_SSH_USER}@${TARGET_HOST}" "$@"
}

# Copy a local file to a remote path on TARGET_HOST.
# Usage: remote_scp <local-src> <remote-dst>
# Fails loudly if AETHER_SSH_USER / AETHER_SSH_KEY are unset — no silent
# fallback to root, which previously masked auth failures as TCP timeouts.
remote_scp() {
    : "${AETHER_SSH_USER:?AETHER_SSH_USER must be set for remote_scp}"
    : "${AETHER_SSH_KEY:?AETHER_SSH_KEY must be set for remote_scp}"
    local src="$1" dst="$2"
    scp -q -i "$AETHER_SSH_KEY" "${SSH_OPTS[@]}" \
        "$src" "${AETHER_SSH_USER}@${TARGET_HOST}:${dst}"
}

# ---------------------------------------------------------------------------
# Environment type and cloud access
# ---------------------------------------------------------------------------
ENV_TYPE="${ENV_TYPE:-docker}"
export ENV_TYPE
CLOUD_MODE="${CLOUD_MODE:-false}"   # backward compat: true maps to ENV_TYPE=cloud
if [ "$CLOUD_MODE" = "true" ]; then ENV_TYPE="cloud"; fi
# Sync the reverse direction: kill_node, start_node, etc. still branch on CLOUD_MODE.
if [ "$ENV_TYPE" = "cloud" ]; then CLOUD_MODE="true"; fi
export CLOUD_MODE
# BASTION_IP is retained for backward-compat env templates but ignored under
# Option A (direct public-IP addressing). Bastion-via-private-network is Option B.
BASTION_IP="${BASTION_IP:-}"
if [ -n "$BASTION_IP" ] && [ "$ENV_TYPE" = "cloud" ]; then
    echo "[WARN]  BASTION_IP=${BASTION_IP} is set but cloud_ssh now uses direct public-IP addressing (Option A) — value ignored." >&2
fi
CLOUD_TIMEOUT_MULTIPLIER="${CLOUD_TIMEOUT_MULTIPLIER:-1}"
# Source name for cloud-provisioned nodes — matches `[source.<name>]` in cloud TOML.
# Bootstrap-state stores nodeIds in the form `<source>-<role>-<index>` (e.g.,
# `hetzner-eu-core-0`); the test harness uses friendly `node-N` IDs and translates
# to the bootstrap form via this prefix when looking up public IPs.
CLOUD_SOURCE_NAME="${CLOUD_SOURCE_NAME:-hetzner-eu}"

# Management API URL scheme. Defaults to http; switched to https by run-tests.sh
# when the cluster's bootstrap config has [operations.tls] auto_generate = true
# (cluster B in cloud mode).
MGMT_SCHEME="${MGMT_SCHEME:-http}"

# Translate a friendly Docker-style node id (node-N, 1-based) into the actual
# node id stored under NodeLifecycleKey at the runtime. On Docker, node ids ARE
# `node-N` so the input passes through unchanged. On cloud, runtime ids carry
# the bootstrap source prefix: `node-1` → `${CLOUD_SOURCE_NAME}-core-0`, etc.
#
# Use this whenever a test calls a management endpoint that takes a node-id path
# parameter (e.g. /api/node/drain/<id>, /api/node/lifecycle/<id>). Test helpers
# that go through SSH (cloud_ssh / kill_node) already translate internally; use
# this only when the node id reaches the runtime as-is.
to_node_id() {
    local node_id="$1"
    if [ "${CLOUD_MODE:-false}" != "true" ]; then
        echo "$node_id"
        return 0
    fi
    if [[ "$node_id" =~ ^node-([0-9]+)$ ]]; then
        local idx=$((${BASH_REMATCH[1]} - 1))
        echo "${CLOUD_SOURCE_NAME}-core-${idx}"
        return 0
    fi
    echo "$node_id"
}

# cloud_public_ip <node-id> — print the public IP of <node-id> by reading the
# bootstrap-state.json that `aether cluster bootstrap` writes under
# ~/.aether/clusters/<BOOTSTRAP_CLUSTER_NAME>/.
#
# <node-id> accepts two forms:
#   - "node-N"            (1-based fixture form used by tests)            →  translated to <CLOUD_SOURCE_NAME>-core-<N-1>
#   - "<source>-<role>-K" (raw bootstrap nodeId, e.g. hetzner-eu-core-2)  →  used as-is
#
# Cluster name resolution: $BOOTSTRAP_CLUSTER_NAME (set by run-tests.sh per cluster);
# falls back to CLOUD_BOOTSTRAP_CLUSTER for ad-hoc invocations.
#
# Returns the IP on stdout. Logs a failure (without exiting the caller) and returns
# non-zero if the state file is missing or the node has no recorded address.
cloud_public_ip() {
    local node_id="${1:-}"
    if [ -z "$node_id" ]; then
        log_fail "cloud_public_ip: node id argument is required"
        return 2
    fi
    local cluster="${BOOTSTRAP_CLUSTER_NAME:-${CLOUD_BOOTSTRAP_CLUSTER:-}}"
    if [ -z "$cluster" ]; then
        log_fail "cloud_public_ip: BOOTSTRAP_CLUSTER_NAME unset (run-tests.sh sets this per cluster)"
        return 2
    fi
    local state_file="${HOME}/.aether/clusters/${cluster}/bootstrap-state.json"
    if [ ! -f "$state_file" ]; then
        log_fail "cloud_public_ip: bootstrap-state.json not found at ${state_file}"
        return 1
    fi
    # Translate friendly node-N → bootstrap nodeId form.
    local target="$node_id"
    if [[ "$node_id" =~ ^node-([0-9]+)$ ]]; then
        local idx=$((${BASH_REMATCH[1]} - 1))
        target="${CLOUD_SOURCE_NAME}-core-${idx}"
    fi
    # Parse parallel arrays. The persisted JSON (BootstrapStateJson.appendStringList)
    # writes them as: "provisionedNodeIds": ["a", "b", ...]  / "collectedAddresses": ["1.2.3.4", ...]
    # — both flat string arrays in matching order.
    local ids_raw addrs_raw
    ids_raw=$(awk -v RS='' '{
        match($0, /"provisionedNodeIds"[[:space:]]*:[[:space:]]*\[[^]]*\]/);
        if (RSTART > 0) print substr($0, RSTART, RLENGTH);
    }' "$state_file")
    addrs_raw=$(awk -v RS='' '{
        match($0, /"collectedAddresses"[[:space:]]*:[[:space:]]*\[[^]]*\]/);
        if (RSTART > 0) print substr($0, RSTART, RLENGTH);
    }' "$state_file")
    if [ -z "$ids_raw" ] || [ -z "$addrs_raw" ]; then
        log_fail "cloud_public_ip: provisionedNodeIds or collectedAddresses missing from ${state_file}"
        return 1
    fi
    # Strip key + brackets, split into one quoted token per line, drop quotes.
    local ids addrs
    ids=$(printf '%s' "$ids_raw" | sed 's/.*\[//; s/\].*//' | tr ',' '\n' | sed 's/^[[:space:]]*"//; s/"[[:space:]]*$//')
    addrs=$(printf '%s' "$addrs_raw" | sed 's/.*\[//; s/\].*//' | tr ',' '\n' | sed 's/^[[:space:]]*"//; s/"[[:space:]]*$//')
    # Find the index of $target in $ids and return the parallel entry from $addrs.
    local pos=0 ip=""
    local id
    while IFS= read -r id; do
        pos=$((pos + 1))
        if [ "$id" = "$target" ]; then
            ip=$(printf '%s\n' "$addrs" | sed -n "${pos}p")
            break
        fi
    done <<< "$ids"
    if [ -z "$ip" ]; then
        log_fail "cloud_public_ip: no entry for '${target}' (input='${node_id}') in ${state_file}"
        return 1
    fi
    printf '%s\n' "$ip"
}

# Map a node id to its public IP — Option A (direct public-IP addressing).
# Backward-compat shim retained so existing call sites keep working unchanged.
cloud_node_ip() {
    cloud_public_ip "$1"
}

# SSH directly to a cloud node's public IP (Option A — no bastion / ProxyJump).
# Resolves the IP from bootstrap-state.json via cloud_public_ip; fails fast if the
# state file is absent so callers see the real cause instead of a misleading
# "ssh: name resolution" or "Connection refused".
#
# **User defaults to `root`** (configurable via CLOUD_SSH_USER) because cloud
# bootstrap installs Docker after creating the unprivileged `aether` user, so
# `aether` is not in the docker group and `docker ps` fails with permission
# denied. cloud-init runs as root and has full docker access. This matches the
# bootstrap-side default (handover 2026-04-12 §163: "Cloud sources now default
# to root for the SSH-back commands").
cloud_ssh() {
    local node_id="$1"; shift
    local target_ip
    target_ip=$(cloud_public_ip "$node_id") || return $?
    ssh "${SSH_OPTS[@]}" \
        -i "${AETHER_SSH_KEY}" \
        "${CLOUD_SSH_USER:-root}@${target_ip}" "$@"
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
