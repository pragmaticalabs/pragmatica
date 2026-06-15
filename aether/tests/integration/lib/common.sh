#!/bin/bash
# common.sh — Shared functions for Aether integration tests

LIB_DIR_COMMON="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR_COMMON}/json.sh"

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

# Environment
: "${TARGET_HOST:?TARGET_HOST must be set}"

# MGMT_PORT = base of the per-node direct mgmt port range (set by run-tests.sh:
# 5151 for cluster A, 5161 for cluster B). MGMT_PORT+i resolves to node-{i+1}'s
# direct host-mapped mgmt port (gateway-bypass; used by per-node probes such as
# wait_for_cluster_ready and rotate_mgmt_entry_point on cloud env).
MGMT_PORT="${MGMT_PORT:-5151}"
APP_PORT="${APP_PORT:-8070}"
LB_PORT="${LB_PORT:-9090}"
LB_MGMT_PORT="${LB_MGMT_PORT:-9091}"
# MGMT_ENTRY_POINT is the cluster's stable management endpoint. On docker/remote
# this resolves to the nginx mgmt-gateway sidecar (aether-{a,b}-mgmt-gateway,
# host ports 5150 / 5160). The gateway round-robins /api/* across all 5 cores
# and skips dead upstreams via proxy_next_upstream, so the endpoint survives
# killing any single core including the leader. On cloud (no gateway yet) it
# resolves to a specific VM and rotate_mgmt_entry_point() handles failover.
MGMT_ENTRY_POINT="${MGMT_ENTRY_POINT:-http://${TARGET_HOST}:5150}"
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
    local timeout="${AETHER_CLI_TIMEOUT:-30}"
    local host_port="${MGMT_ENTRY_POINT#*://}"
    if ! curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${MGMT_ENTRY_POINT}/health/live" >/dev/null 2>&1; then
        # Pinned endpoint is dead (e.g. a chaos test killed it). Delegate the live-node
        # scan to the SINGLE resolver rather than re-implementing it here. _resolve_live_endpoint
        # is the one place cloud (per-VM <publicIp>:CLOUD_MGMT_PORT), docker seed-port, and
        # label-discovery resolution lives, so the CLOUD_MGMT_PORT and #33 fixes apply
        # automatically. This removes the latent bug where the old inline cloud scan used
        # MGMT_PORT (the docker host-mapped range, always wrong on cloud) instead of
        # CLOUD_MGMT_PORT. Per-call override only: MGMT_ENTRY_POINT stays pinned for the
        # next invocation, so forwarding bugs still surface on the happy path.
        local live
        if live=$(_resolve_live_endpoint); then
            host_port="${live#*://}"
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
    # Split $command on spaces so multi-word subcommands like "cluster topology" pass as
    # separate args to picocli — quoting them as one string makes picocli see a literal
    # "cluster topology" token that matches no subcommand.
    # shellcheck disable=SC2086
    aether_failover $command --format value --field "$field"
}

# Query a CLI command and return full JSON output
# Usage: aether_json <command> [extra-args...]
# Example: aether_json status
aether_json() {
    local command="$1"; shift
    # Split $command on spaces (see aether_field for rationale).
    # shellcheck disable=SC2086
    aether_failover $command --format json "$@"
}

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
# Prefix log lines with `[SUITE/TEST]` when SUITE_TAG (and optionally TEST_TAG) is set
# by `run_suite`/`run_test`. Without this, parallel cluster A suites interleave their
# output and attribution becomes impossible — e.g. session 2026-05-10c misattributed a
# 4256s "deploy" duration to the wrong suite because the stdout of two parallel suites
# arrived in undefined order. Empty when no SUITE_TAG (e.g. lib code that runs outside
# a suite context).
_log_prefix() {
    if [ -n "${SUITE_TAG:-}" ]; then
        printf '[%s%s] ' "$SUITE_TAG" "${TEST_TAG:+/$TEST_TAG}"
    fi
}

log_info()  { echo -e "${GREEN}[INFO]${NC}  $(_log_prefix)$1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $(_log_prefix)$1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_pass()  { echo -e "${GREEN}[PASS]${NC}  $(_log_prefix)$1"; }
# log_fail: cosmetic-only used to lie about results. Now increments a per-test
# latch (TEST_FAIL_COUNT) that run_test consults — so any test that emits a
# [FAIL] line is recorded as failing even if the test function happened to
# `return 0` afterwards (a real bug pattern: helpers report failure via log_fail
# without latching, the caller's last command was an unrelated `true`, and
# the test was counted as PASS).
log_fail()  {
    echo -e "${RED}[FAIL]${NC}  $(_log_prefix)$1"
    TEST_FAIL_COUNT=$(( ${TEST_FAIL_COUNT:-0} + 1 ))
}
log_step()  { echo -e "${BLUE}[STEP]${NC}  $(_log_prefix)$1"; }

# ---------------------------------------------------------------------------
# HTTP helpers — management API
# Retained for tests that need raw HTTP access (status codes, custom headers)
# ---------------------------------------------------------------------------
# Discover a live cluster management endpoint via the Docker daemon label index,
# bypassing the fixed seed host-port range (MGMT_PORT..MGMT_PORT+N-1).
#
# WHY this exists: destructive suites (02-chaos, 03-scaling) kill compose-seed
# nodes; CTM auto-heals by provisioning KSUID-named replacement containers
# (aether-<cluster>-node-<ksuid>, label aether.provisioned-by=ctm). Over a suite
# ALL five original seeds can be replaced. Replacements publish their in-container
# management port (8080) to an *ephemeral* host port chosen by Docker
# (DockerComputeProvider.buildRunCommand `-p 8080`), NOT to the deterministic
# 5161..5165 seed slots. So once every seed is gone, the seed-range port scan in
# _resolve_live_endpoint / aether_failover / rotate_mgmt_entry_point finds nothing
# and every management call returns '' (http_code 000) — the `got ''` cascade.
#
# This helper asks the remote Docker daemon for any RUNNING container labeled
# `aether.cluster=${CLUSTER_ID}`, resolves its host-published 8080/tcp port via
# `docker port`, and prints `http://${TARGET_HOST}:<host-port>` for the first one
# that answers /health/live. Every node (seed OR replacement) runs management on
# in-container port 8080 (Dockerfile EXPOSE 8080 + MANAGEMENT_PORT=8080), so this
# survives arbitrary seed replacement.
#
# Docker/remote only: cloud nodes have per-VM public IPs (rotate_mgmt_entry_point
# handles cloud failover via cloud_public_ip). Returns the URL on stdout / rc 0 on
# success; rc 1 (no output) when no live labeled container is reachable.
#
# Result is cached for DISCOVER_TTL seconds (default 5) so the common case — every
# api_get calling _resolve_live_endpoint — doesn't pay an SSH round-trip per call.
# The cache is invalidated implicitly because the cached URL is re-probed with a
# cheap local curl before reuse.
_LABEL_DISCOVERED_ENDPOINT=""
_LABEL_DISCOVERED_AT=0
_discover_endpoint_by_label() {
    [ "${ENV_TYPE:-docker}" = "cloud" ] && return 1
    local cluster="${CLUSTER_ID:-}"
    [ -z "$cluster" ] && return 1

    # Cache hit: reuse a previously discovered endpoint if it still answers and the
    # entry is fresh. The local curl probe is ~50ms vs a ~300ms+ SSH round-trip.
    local now ttl="${DISCOVER_TTL:-5}"
    now=$(date +%s)
    if [ -n "$_LABEL_DISCOVERED_ENDPOINT" ] && [ $((now - _LABEL_DISCOVERED_AT)) -lt "$ttl" ]; then
        if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${_LABEL_DISCOVERED_ENDPOINT}/health/live" >/dev/null 2>&1; then
            echo "$_LABEL_DISCOVERED_ENDPOINT"
            return 0
        fi
    fi

    # Single SSH round-trip: list running containers for this cluster and, for each,
    # emit the host port that maps to in-container 8080/tcp. `docker port <name> 8080/tcp`
    # prints lines like `0.0.0.0:33191` / `[::]:33191` / `127.0.0.1:33191`; the trailing
    # port is identical across the address families, so we take the port from the FIRST
    # line regardless of bind address (`sed 's/.*://'`). The previous parser grepped
    # specifically for `0.0.0.0:` and silently dropped containers whose 8080 was published
    # only on `[::]:` (IPv6-only mapping) or `127.0.0.1:` — which left every fixed-seed-dead
    # cluster running on CTM replacements unreachable (rc=7 wedge, #33). If `docker port`
    # emits nothing (rare daemon states), fall back to the inspect-based HostPort lookup so
    # any RUNNING labeled container with a published 8080 is still discoverable.
    # Compose seeds map 8080 to their fixed slot (e.g. node-1 -> 5161); CTM replacements map
    # to a Docker-chosen ephemeral host port. Either way the host port is reachable from the
    # test runner via ${TARGET_HOST}:<port>.
    local listing
    listing=$(remote_exec "docker ps --filter 'label=aether.cluster=${cluster}' --format '{{.Names}}' | while read -r n; do hp=\$(docker port \"\$n\" 8080/tcp 2>/dev/null | sed -n '1s/.*:\\([0-9][0-9]*\\)\$/\\1/p'); [ -z \"\$hp\" ] && hp=\$(docker inspect -f '{{range \$p := index .NetworkSettings.Ports \"8080/tcp\"}}{{\$p.HostPort}} {{end}}' \"\$n\" 2>/dev/null | tr ' ' '\\n' | grep -m1 '[0-9]'); [ -n \"\$hp\" ] && echo \"\$hp\"; done" 2>/dev/null) || return 1
    [ -z "$listing" ] && return 1

    local hp endpoint
    while IFS= read -r hp; do
        [ -z "$hp" ] && continue
        endpoint="http://${TARGET_HOST}:${hp}"
        if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
            _LABEL_DISCOVERED_ENDPOINT="$endpoint"
            _LABEL_DISCOVERED_AT="$now"
            echo "$endpoint"
            return 0
        fi
    done <<< "$listing"
    return 1
}

# Resolve an endpoint that actually responds to /health/live. Preserves the pinned
# CLUSTER_ENDPOINT when it's up; rotates once to any live core node when the pinned
# endpoint is dead (e.g., during chaos-suite recovery where the pinned node was killed).
#
# Resolution order:
#   1. pinned CLUSTER_ENDPOINT (happy path — preserves forwarding-bug detection)
#   2a. cloud: per-VM public-IP scan (each node is <publicIp>:CLOUD_MGMT_PORT,
#       resolved via cloud_public_ip; there is NO local docker to inspect and no
#       fixed host-port range on TARGET_HOST — see the cloud branch below)
#   2b. docker/remote: fixed seed host-port range MGMT_PORT..MGMT_PORT+N-1
#       (surviving compose seeds)
#   3. docker/remote: label discovery (CTM KSUID replacements with ephemeral host
#      ports) — the only path that survives full seed replacement; see
#      _discover_endpoint_by_label.
_resolve_live_endpoint() {
    if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${CLUSTER_ENDPOINT}/health/live" >/dev/null 2>&1; then
        echo "${CLUSTER_ENDPOINT}"
        return 0
    fi
    # Cloud: the pinned endpoint is dead. Each node lives on its OWN VM public IP at
    # the uniform CLOUD_MGMT_PORT (8080) — there is no shared TARGET_HOST port range
    # and no local/remote docker daemon to label-discover against. Resolve a live
    # node by walking the provisioned VM IPs (cloud_public_ip reads bootstrap-state.json).
    # Without this branch the resolver falls through to the docker-only TARGET_HOST
    # port scan (which on cloud probes the cluster-B docker ports 5161..5165 — all
    # dead) and then _discover_endpoint_by_label (which returns 1 immediately on
    # cloud), so every read after the pinned node dies returns '' and the whole
    # suite cascades into false failures.
    if [ "${ENV_TYPE:-docker}" = "cloud" ] && command -v cloud_public_ip >/dev/null 2>&1; then
        # Cloud per-node mgmt port is uniform 8080 (operations.ports.management in
        # cloud-hetzner*.toml; bootstrap emits mgmt=<publicIp>:8080). Do NOT fall back
        # to MGMT_PORT — it is ALWAYS set to the docker host-mapped range (5151/5161)
        # even on cloud (run-tests.sh exports it), so `${CLOUD_MGMT_PORT:-${MGMT_PORT:-8080}}`
        # never reached the 8080 default and scanned the dead docker port → false
        # "cluster appears down" after a chaos kill. run-tests.sh now exports
        # CLOUD_MGMT_PORT=8080 in the cloud env case; this `:-8080` is the safety net.
        local mgmt_port="${CLOUD_MGMT_PORT:-8080}"
        local n node_id node_ip endpoint
        for n in $(seq 0 $((NODE_COUNT - 1))); do
            node_id=$(to_node_id "node-$((n + 1))" 2>/dev/null || true)
            [ -z "$node_id" ] && continue
            node_ip=$(cloud_public_ip "$node_id" 2>/dev/null || true)
            [ -z "$node_ip" ] && continue
            endpoint="${MGMT_SCHEME:-http}://${node_ip}:${mgmt_port}"
            if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
                echo "${endpoint}"
                return 0
            fi
        done
        # No surviving VM responded; fall back so the caller surfaces a curl failure.
        echo "${CLUSTER_ENDPOINT}"
        return 1
    fi
    # Fast-path: a previously label-discovered replacement endpoint that is still
    # fresh and still answers. Skips the fixed-seed port scan (which costs ~2s/dead
    # port = up to ~10s/call once every seed is replaced) on the common steady-state
    # where the cluster has fully migrated to CTM replacements. _discover_endpoint_by_label
    # re-probes the cached URL with a cheap local curl before reuse, so a stale entry
    # falls through to a fresh discovery rather than returning a dead endpoint.
    local now ttl="${DISCOVER_TTL:-5}"
    now=$(date +%s)
    if [ -n "$_LABEL_DISCOVERED_ENDPOINT" ] && [ $((now - _LABEL_DISCOVERED_AT)) -lt "$ttl" ]; then
        local cached
        if cached=$(_discover_endpoint_by_label); then
            echo "$cached"
            return 0
        fi
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
    # Seed range exhausted — fall back to Docker label discovery so a cluster whose
    # seeds were all replaced by CTM (KSUID containers on ephemeral host ports) is
    # still reachable for management. This is the robust last resort: it returns a
    # usable host:port whenever ANY live aether-${CLUSTER_ID} container exists (seed
    # OR replacement), even when every fixed seed port is dead (#33).
    local discovered
    if discovered=$(_discover_endpoint_by_label); then
        echo "$discovered"
        return 0
    fi
    echo "${CLUSTER_ENDPOINT}"  # fall back; caller will see curl failure
    return 1
}

# Refresh the *exported* MGMT_ENTRY_POINT / CLUSTER_ENDPOINT to a live core node.
# Differs from _resolve_live_endpoint: callers don't use $(...) command substitution,
# so the export survives into subsequent helpers in the same shell. Used by
# wait_for_cluster_ready's fast-fail probe and any caller that needs the env vars
# themselves to be live (not just the return string).
#
# Returns 0 if a live endpoint was found (and exported); 1 if every probed port is
# dead. Leaves env vars unchanged on failure so callers can still log the original
# pinned endpoint in the error message.
_refresh_mgmt_entry_point() {
    local live
    if live=$(_resolve_live_endpoint); then
        export MGMT_ENTRY_POINT="${live}"
        export CLUSTER_ENDPOINT="${live}"
        return 0
    fi
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
        response=$(curl -sk -m 30 -X "$method" -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
            -d "$body" -w "\n__API_HTTP_STATUS:%{http_code}__" "$url" 2>&1)
    else
        response=$(curl -sk -m 30 -X "$method" -H "X-API-Key: ${API_KEY}" \
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
# Caller supplies the 0-based offset of the target core node (0 → first node, 1 → second, ...).

# Resolve the management base URL for the core node at 0-based $offset.
# docker/remote: nodes are collocated on TARGET_HOST with a host-mapped per-node
#   port range, so node K is TARGET_HOST:(MGMT_PORT + K).
# cloud: each node is its own VM with mgmt on the uniform CLOUD_MGMT_PORT; resolve
#   the VM's public IP via to_node_id (offset → node-(K+1) → runtime id) → cloud_public_ip.
# Prints the base URL (e.g. http://1.2.3.4:8080) on stdout; rc 1 if the cloud IP
# lookup fails. Scheme honours MGMT_SCHEME (https on cloud-B with TLS auto-gen).
node_base_url() {
    local offset="$1"
    if [ "${ENV_TYPE:-docker}" = "cloud" ] && command -v cloud_public_ip >/dev/null 2>&1; then
        # Uniform cloud mgmt port 8080; never fall back to MGMT_PORT (always the
        # docker host-mapped range even on cloud — see _resolve_live_endpoint).
        local mgmt_port="${CLOUD_MGMT_PORT:-8080}"
        local node_id node_ip
        node_id=$(to_node_id "node-$((offset + 1))" 2>/dev/null || true)
        [ -z "$node_id" ] && return 1
        node_ip=$(cloud_public_ip "$node_id" 2>/dev/null || true)
        [ -z "$node_ip" ] && return 1
        printf '%s://%s:%s\n' "${MGMT_SCHEME:-http}" "$node_ip" "$mgmt_port"
        return 0
    fi
    printf 'http://%s:%s\n' "${TARGET_HOST}" "$((MGMT_PORT + offset))"
}

node_api_get() {
    local offset="$1" path="$2"
    local base
    base=$(node_base_url "$offset") || return 1
    _api_call GET "${base}${path}"
}

node_api_post() {
    local offset="$1" path="$2" body="${3:-"{}"}"
    local base
    base=$(node_base_url "$offset") || return 1
    _api_call POST "${base}${path}" "$body"
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

# Like http_status but captures the response body on non-2xx and surfaces it as
# a log_warn. Same stdout contract as http_status (status code only) so callers
# can drop-in replace when they need diagnostic visibility on failure. Body is
# truncated to the first 500 bytes (newlines stripped) — enough to surface a
# problem+json `detail` / exception summary without flooding the log.
http_status_with_body() {
    local url="$1"; shift
    local body_file status
    body_file=$(mktemp)
    status=$(curl -sk -o "$body_file" -w "%{http_code}" "$@" "$url")
    case "$status" in
        2*) ;; # success — no diagnostic dump
        *)
            local body
            body=$(head -c 500 "$body_file" 2>/dev/null | tr -d '\n')
            # Diagnostic WARN must go to stderr: this helper's contract is "stdout = status
            # code only" (callers do status=$(http_status_with_body ...)). Echoing the WARN to
            # stdout pollutes the captured status with the warn text, so a correct non-2xx
            # (e.g. an expected 409) false-fails numeric comparisons. Keep it visible in the
            # run log (which captures stderr) without leaking into the command substitution.
            log_warn "http ${status} ${url} :: body=${body:-<empty>}" >&2
            ;;
    esac
    rm -f "$body_file"
    printf '%s' "$status"
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
# node id used at the runtime.
#
# - Docker: NodeId == container_name; compose names containers `aether-<CLUSTER_ID>-node-<N>`
#   (CLUSTER_ID is "a" or "b"). `node-1` → `aether-${CLUSTER_ID}-node-1`. If
#   the caller already passes the full form, it's returned unchanged. If
#   CLUSTER_ID is unset (legacy single-cluster scripts), the input passes
#   through verbatim.
# - Cloud: runtime ids carry the bootstrap source prefix: `node-1` →
#   `${CLOUD_SOURCE_NAME}-core-0`, etc.
#
# Use this whenever a test calls a management endpoint that takes a node-id path
# parameter (e.g. /api/nodes/drain/<id>, /api/node/lifecycle/<id>). Test helpers
# that go through SSH (cloud_ssh / kill_node) already translate internally; use
# this only when the node id reaches the runtime as-is.
to_node_id() {
    local node_id="$1"
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        if [[ "$node_id" =~ ^node-([0-9]+)$ ]]; then
            local idx=$((${BASH_REMATCH[1]} - 1))
            echo "${CLOUD_SOURCE_NAME}-core-${idx}"
            return 0
        fi
        echo "$node_id"
        return 0
    fi
    # Docker path: pre-prefixed full forms pass through unchanged.
    case "$node_id" in
        aether-*) echo "$node_id"; return 0 ;;
    esac
    if [[ "$node_id" =~ ^node-([0-9]+)$ ]] && [ -n "${CLUSTER_ID:-}" ]; then
        echo "aether-${CLUSTER_ID}-node-${BASH_REMATCH[1]}"
        return 0
    fi
    echo "$node_id"
}

# Map a node's runtime id (as reported by the management API, e.g. in
# scheduled-tasks `registeredBy` or a SliceNotLocal retry hint) to the 0-based core
# offset that node_api_get/node_api_post / node_base_url expect.
#
#   docker/remote: `aether-{a|b}-node-N` or bare `node-N` (N is 1-based) → offset N-1
#   cloud:         `<source>-core-K`     (K is already 0-based)          → offset K
#
# Prints the offset on stdout, rc 0 on success; rc 1 if the id matches no known form.
_registered_by_to_offset() {
    local id="$1"
    if [[ "$id" =~ ^(aether-[ab]-)?node-([0-9]+)$ ]]; then
        echo "$(( ${BASH_REMATCH[2]} - 1 ))"
        return 0
    fi
    if [[ "$id" =~ ^[A-Za-z0-9-]+-core-([0-9]+)$ ]]; then
        echo "${BASH_REMATCH[1]}"
        return 0
    fi
    return 1
}


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
        # bootstrap-state.json only records the nodes provisioned at bootstrap. A
        # CTM-provisioned REPLACEMENT VM (cloud auto-heal) is NOT in that file — its
        # node-id reaches us from /api/nodes/status, not from bootstrap. Fall back to
        # the Hetzner API, which is the authoritative live inventory: every Aether VM
        # (bootstrap OR CTM replacement) carries an `aether-node-id` label (the same
        # label cloud-reaper.sh keys on). Look the IP up by that label. Bounded,
        # no-jq (grep/sed, per the harness no-extra-deps idiom), and only attempted
        # when the static lookup missed AND a token is available — so the happy path
        # (bootstrap nodes) never pays an API round-trip.
        if [ -n "${HCLOUD_TOKEN:-}" ]; then
            local hz_api="${HCLOUD_API:-https://api.hetzner.cloud/v1}"
            local enc body
            enc=$(printf 'aether-node-id=%s' "$target" | sed 's/=/%3D/')
            body=$(curl -sS -m 10 -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
                        "${hz_api}/servers?label_selector=${enc}&per_page=50" 2>/dev/null || true)
            if [ -n "$body" ]; then
                # Extract the first server's primary IPv4: ...public_net":{"ipv4":{"ip":"1.2.3.4",...
                # The response is one flat JSON document; grab the first ipv4 ip string.
                ip=$(printf '%s' "$body" \
                    | grep -o '"ipv4"[[:space:]]*:[[:space:]]*{[^}]*"ip"[[:space:]]*:[[:space:]]*"[^"]*"' \
                    | head -1 \
                    | sed 's/.*"ip"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
            fi
            if [ -n "$ip" ]; then
                printf '%s\n' "$ip"
                return 0
            fi
        fi
        log_fail "cloud_public_ip: no entry for '${target}' (input='${node_id}') in ${state_file}${HCLOUD_TOKEN:+ and no Hetzner server carries label aether-node-id=${target}}"
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
    # Stamp TEST_TAG so log_* lines emitted by the test function are attributable
    # to the correct (suite, test) pair under cluster A's parallel execution.
    # Cleared at function exit so logs emitted by surrounding scaffolding don't
    # leak the last test's name.
    export TEST_TAG="$sanitized_name"
    echo ""
    log_step "=== TEST: ${name} ==="

    if [[ "${COLLECT_METRICS:-false}" == "true" ]]; then
        collect_metrics_before "$sanitized_name"
    fi

    # H2 latch: log_fail increments TEST_FAIL_COUNT. Reset to 0 here so each test
    # starts clean (any harness-scope log_fail calls before run_test are not
    # attributed to this test). After the test function runs, treat the test as
    # PASS only if BOTH the function returned 0 AND no [FAIL] lines were emitted.
    # Without this, helpers that emit `log_fail "..."` without propagating a
    # non-zero return (e.g. early in a long test, before later success-coded
    # commands) caused the suite to record PASS while the logs screamed FAIL.
    TEST_FAIL_COUNT=0
    local t_start t_elapsed fn_rc
    t_start=$(date +%s)
    # set -e abort guard: when the test script runs under `set -euo pipefail`, a
    # failing command inside "$fn" (including an unhandled non-zero return from a
    # helper like cluster_leader) propagates abort up through the function up
    # through this caller up through the whole script — skipping cleanup() and
    # leaving the cluster degraded for every subsequent test-*.sh in the suite.
    # The `if/else` form makes "$fn" a condition: set -e is masked, we capture
    # the return code, and the script keeps running so EXIT traps + explicit
    # cleanup() at the end of the test file can still execute.
    if "$fn"; then
        fn_rc=0
    else
        fn_rc=$?
    fi
    if [ "$fn_rc" -eq 0 ] && [ "${TEST_FAIL_COUNT:-0}" -eq 0 ]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        if [ "$fn_rc" -eq 0 ] && [ "${TEST_FAIL_COUNT:-0}" -gt 0 ]; then
            log_warn "run_test: '${name}' function returned 0 but emitted ${TEST_FAIL_COUNT} [FAIL] line(s) — recording as FAIL"
        fi
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
    unset TEST_TAG
    # Clear the latch so any scaffolding between tests doesn't carry stale state
    # into the next run_test invocation (defence in depth — run_test resets at top too).
    TEST_FAIL_COUNT=0
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
