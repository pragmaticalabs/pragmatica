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
    # Hard outer bound (#441 read-fragility class): --request-timeout caps the HTTP request
    # once it is in flight, but the CLI (a JVM) can still hang in connect/DNS/TLS phases
    # against a dead-but-routable endpoint — the 2026-07-15 cloud-jvm run hung 3.5h inside
    # a fully-silenced `cluster_leader → aether_failover → aether` chain after the whole
    # cluster-B fleet died. coreutils `timeout` closes that class for EVERY CLI call site;
    # when it is not installed the invocation runs unbounded exactly as before.
    if command -v timeout >/dev/null 2>&1; then
        timeout "$((timeout + 30))s" aether -c "${MGMT_SCHEME}://${host_port}" --api-key "${API_KEY}" "--request-timeout=${timeout}" ${cli_tls_flag} "$@"
    else
        aether -c "${MGMT_SCHEME}://${host_port}" --api-key "${API_KEY}" "--request-timeout=${timeout}" ${cli_tls_flag} "$@"
    fi
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

# Run a command with a hard wall-clock bound (#441 item 1). macOS ships no
# `timeout` by default (only via `brew install coreutils`, as `gtimeout`);
# Linux/remote hosts have GNU `timeout`. Falls back to a background-process +
# polled-wait + kill construct when neither exists, so callers always get a
# bounded wait regardless of platform. lib/suite.sh:106 calls bare `timeout`
# with no such fallback — safe there only because it's wrapped in
# `if timeout ...; then` (a missing binary just evaluates false); this helper
# is for callers like _cloud_running_vm_ips that must also cap wall-clock time
# on macOS dev boxes and turn "took too long" into an honest non-zero rc.
_run_with_timeout() {
    local secs="$1"
    shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "$secs" "$@"
        return $?
    fi
    if command -v gtimeout >/dev/null 2>&1; then
        gtimeout "$secs" "$@"
        return $?
    fi
    local outfile errfile rc pid waited=0
    outfile=$(mktemp)
    errfile=$(mktemp)
    "$@" >"$outfile" 2>"$errfile" &
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$secs" ]; then
            kill -9 "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
            cat "$outfile"
            cat "$errfile" >&2
            rm -f "$outfile" "$errfile"
            return 124
        fi
        sleep 1
        waited=$((waited + 1))
    done
    wait "$pid"
    rc=$?
    cat "$outfile"
    cat "$errfile" >&2
    rm -f "$outfile" "$errfile"
    return "$rc"
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
        # Sticky re-pin (#441 item 2): this whole function runs inside `$(...)`
        # at every call site (api_get et al.), so any `export CLUSTER_ENDPOINT=`
        # made here is scoped to that subshell and discarded the instant this
        # function returns — the NEXT call starts back at the (dead) pinned
        # CLUSTER_ENDPOINT and re-pays the full seed-scan + anchor-free hcloud
        # scan below. Worse, `run-tests.sh` runs each suites/**/test-*.sh as its
        # OWN `bash test_file` subprocess (run_suite's `for test_file in ...; do
        # bash "$test_file"; done`), so even a plain (non-subshelled) export
        # wouldn't survive from one test file to the next. Only a FILE-based
        # cache — the same idiom test-self-drain-quorum-loss.sh uses for its
        # SURVIVOR_IPS_FILE — persists across both boundaries. Scoped by
        # CLUSTER_ID so concurrent cluster-A/B runs never share an endpoint.
        # Re-probed with the same cheap `-m 2` curl before trust (mirrors the
        # _LABEL_DISCOVERED_ENDPOINT TTL+reprobe idiom below), so a stale/dead
        # cached IP degrades to the normal scan rather than being trusted blindly.
        local sticky_file="${TMPDIR:-/tmp}/aether-live-endpoint-${CLUSTER_ID:-default}"
        if [ -f "$sticky_file" ]; then
            local sticky_ep
            sticky_ep=$(cat "$sticky_file" 2>/dev/null || true)
            if [ -n "$sticky_ep" ] && curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${sticky_ep}/health/live" >/dev/null 2>&1; then
                echo "${sticky_ep}"
                return 0
            fi
        fi
        local n node_id node_ip endpoint
        for n in $(seq 0 $((NODE_COUNT - 1))); do
            node_id=$(to_node_id "node-$((n + 1))" 2>/dev/null || true)
            [ -z "$node_id" ] && continue
            node_ip=$(cloud_public_ip "$node_id" 2>/dev/null || true)
            [ -z "$node_ip" ] && continue
            endpoint="${MGMT_SCHEME:-http}://${node_ip}:${mgmt_port}"
            if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
                printf '%s' "${endpoint}" > "$sticky_file" 2>/dev/null || true
                echo "${endpoint}"
                return 0
            fi
        done
        # Seed IPs (bootstrap-state.json) are all dead/replaced. Enumerate the CURRENT
        # cluster VMs straight from the cloud provider — CTM auto-heal REPLACEMENT VMs
        # are NOT recorded in bootstrap-state.json, so the fixed seed loop above can
        # never find them. This is the anchor-free discovery that makes resolution
        # survive full owner replacement under chaos.
        # #441 Defect B follow-up: this used to run its own
        # `hcloud server list --selector "aether-cluster=${BOOTSTRAP_CLUSTER_NAME}"`
        # query directly — the SAME selector fixed in lib/cluster.sh's
        # _cloud_running_vm_ips, and broken for the same reason: provisioned VMs
        # (seed AND CTM replacement alike) are only ever reliably stamped
        # `aether-node-id`, not `aether-cluster` (product-side gap, #442 v2b).
        # Here that meant this anchor-free discovery step silently enumerated
        # ZERO candidate IPs even against a live cluster — masked because this
        # is only a PROBE loop (not a drain/reap decision): an empty/exhausted
        # result already fell through correctly to the loud dead-endpoint
        # log_error a few lines below. The defect was "misses real candidates,
        # degrades resolution quality" here rather than cluster.sh's false-reap,
        # but the root cause and fix are identical — reuse the canonical,
        # cluster-unambiguous enumeration instead of re-deriving the (broken)
        # selector a second time.
        #
        # `command -v` guard, not a hard dependency: lib/cluster.sh is sourced
        # alongside lib/common.sh by every real test entry point (run-tests.sh
        # and every suites/**/*.sh), but two common.sh-only scripts exist
        # (scripts/cleanup.sh, test/test-cloud-helpers.sh) that never reach this
        # cloud fallback branch in practice — keep that true structurally
        # instead of assuming it holds.
        if [ -n "${BOOTSTRAP_CLUSTER_NAME:-}" ] && command -v _cloud_running_vm_ips >/dev/null 2>&1; then
            local cur_ip cur_ips
            # `|| true`: _cloud_running_vm_ips returns 1 when hcloud itself is
            # missing/fails — this bare assignment must not abort the whole
            # script under `set -e` (run-tests.sh and every suite file that
            # reaches here run with `set -euo pipefail`); an empty result
            # degrades gracefully into the loud dead-endpoint fallback below,
            # exactly like a genuinely empty enumeration would.
            cur_ips=$(_cloud_running_vm_ips "${BOOTSTRAP_CLUSTER_NAME}" || true)
            for cur_ip in $cur_ips; do
                [ -z "$cur_ip" ] && continue
                endpoint="${MGMT_SCHEME:-http}://${cur_ip}:${mgmt_port}"
                if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
                    printf '%s' "${endpoint}" > "$sticky_file" 2>/dev/null || true
                    echo "${endpoint}"
                    return 0
                fi
            done
        fi
        # No surviving VM responded; fall back so the caller surfaces a curl failure.
        # #426 item 4: this was a silent fallback (comment-only) — a dead pinned
        # endpoint returned here reads downstream as an ordinary empty/absent API
        # response, indistinguishable from a genuine 404. Log it loud so a reader
        # of the run's output can tell "no VM answered" from "the API said so".
        log_error "_resolve_live_endpoint: no surviving cloud VM answered /health/live (seed IPs + live hcloud enumeration both exhausted) — falling back to the dead pinned endpoint ${CLUSTER_ENDPOINT}; callers will see a transport failure" >&2
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
    #
    # Retry (#426 item 4): a single SSH round-trip (remote_exec inside
    # _discover_endpoint_by_label) has no bound on remote COMMAND execution time —
    # ssh's ConnectTimeout=10 only guards connection setup, not a hung `docker ps`/
    # `docker port` on a contended daemon. On a fully-ULID cluster (every seed
    # replaced) that single call is the ONLY discovery path, so one transient
    # SSH/Docker hiccup used to cause total resolution failure for the whole call —
    # cascading into every downstream api_get reading a false "0"/"empty" state.
    # Retry a few times with a short pause before declaring the cluster unreachable.
    local discover_attempts="${ENDPOINT_DISCOVERY_ATTEMPTS:-3}"
    local discovered attempt=1
    while [ "$attempt" -le "$discover_attempts" ]; do
        if discovered=$(_discover_endpoint_by_label); then
            echo "$discovered"
            return 0
        fi
        [ "$attempt" -lt "$discover_attempts" ] && sleep 1
        attempt=$((attempt + 1))
    done
    # #426 item 4: was a silent fallback (comment-only, no logged diagnostic) — a
    # dead pinned endpoint returned here reads downstream as an ordinary
    # empty/absent API response, indistinguishable from a genuine 404.
    log_error "_resolve_live_endpoint: pinned endpoint dead, seed port range ${MGMT_PORT}-$((MGMT_PORT + NODE_COUNT - 1)) exhausted, and label discovery failed after ${discover_attempts} attempt(s) — falling back to the dead pinned endpoint ${CLUSTER_ENDPOINT}; callers will see a transport failure" >&2
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

# Like api_get, but the LAST line of stdout is a `__API_HTTP_STATUS:NNN__`
# marker carrying the raw HTTP status ("000" when curl never got a response —
# a transport failure, not a server-issued status). Callers that must
# distinguish a genuine 4xx/5xx response from an unreachable endpoint (rather
# than collapsing both to "empty body") should parse this instead of using
# api_get — see kv_lifecycle_state in lib/topology.sh (#426 item 2).
api_get_with_status() {
    local path="$1"
    local endpoint
    endpoint=$(_resolve_live_endpoint)
    _api_call GET "${endpoint}${path}" "" "1"
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
    local method="$1" url="$2" body="${3:-}" want_status="${4:-}"
    local response status body_only
    # Cloud poll-loop hygiene: a just-killed node's pinned endpoint is a DEAD
    # public IP. With the default `-m 30` (no connect cap), each call to a dead
    # host stalls the full 30s before failing, so a single wedged subtest's
    # poll loop (wait_for / wait_for_node_count) burns ~30s/iteration and starves
    # the rest of cluster-B. On cloud, fast-fail the connect so a dead endpoint
    # surfaces as a curl failure in ~3s and the caller can rotate to a live node
    # on the next poll (it re-refreshes MGMT_ENTRY_POINT). docker/local/remote keep
    # the original `-m 30` with no connect cap — byte-identical behaviour there.
    local conn_opts=()
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        conn_opts=(--connect-timeout "${CLOUD_API_CONNECT_TIMEOUT:-3}" -m "${CLOUD_API_MAX_TIME:-15}")
    else
        conn_opts=(-m 30)
    fi
    if [ -n "$body" ]; then
        response=$(curl -sk "${conn_opts[@]}" -X "$method" -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
            -d "$body" -w "\n__API_HTTP_STATUS:%{http_code}__" "$url" 2>&1)
    else
        response=$(curl -sk "${conn_opts[@]}" -X "$method" -H "X-API-Key: ${API_KEY}" \
            -w "\n__API_HTTP_STATUS:%{http_code}__" "$url" 2>&1)
    fi
    status=$(printf '%s' "$response" | grep -oE '__API_HTTP_STATUS:[0-9]+__' | sed 's/__API_HTTP_STATUS://;s/__//')
    body_only=$(printf '%s' "$response" | sed '$d')
    if [ -n "$status" ] && [ "$status" -ge 200 ] && [ "$status" -lt 400 ] 2>/dev/null; then
        printf '%s' "$body_only"
        # want_status: append the raw HTTP status as a trailing marker line so a
        # single command-substitution capture can recover it (printf -v inside a
        # $(...) caller would set the variable in the subshell, not the caller —
        # see api_get_with_status). "000" means curl never got a response at all.
        [ -n "$want_status" ] && printf '\n__API_HTTP_STATUS:%s__' "${status:-000}"
        return 0
    fi
    log_warn "api ${method} ${url#http://*/} status=${status:-000}: $(printf '%s' "$body_only" | head -c 300)" >&2
    [ -n "$want_status" ] && printf '%s\n__API_HTTP_STATUS:%s__' "$body_only" "${status:-000}"
    return 1
}

api_delete() {
    local path="$1"
    _api_call DELETE "${CLUSTER_ENDPOINT}${path}"
}

# ---------------------------------------------------------------------------
# Connectivity preflight (C7) — diagnose CLI-vs-curl reachability
# ---------------------------------------------------------------------------
# Probe the SAME management endpoint two independent ways — raw HTTP (curl, the
# api_get transport) and the `aether` CLI (which execs `java`) — and emit a
# diagnostic verdict BEFORE any suite runs. This converts the multi-hour
# misdiagnosis observed 2026-06-15 (macOS Local Network Privacy / TCC silently
# blocked the Homebrew java binary from the 192.168.x LAN cluster, so the CLI
# failed with `java.net.ConnectException: No route to host` while curl and the
# cluster were completely healthy — 00-smoke then failed and the aborted-all gate
# misattributed it to a dead cluster) into a one-line preflight verdict. See
# aether/docs/specs/harness-resilience-spec.md §6 C7.
#
# The four outcomes (curl × CLI):
#   curl OK  + CLI OK   → both transports reach the cluster → proceed (rc 0).
#   curl FAIL+ CLI FAIL → cluster genuinely unreachable both ways → let the
#                         existing flow surface the real failure; do NOT mask it
#                         (rc 0 — proceed so 00-smoke/the gate reports it normally).
#   curl OK  + CLI FAIL → THIS operator machine's CLI cannot reach the cluster
#                         while raw HTTP can (macOS Local Network Privacy for a LAN
#                         cluster, an HTTP proxy honoured only by curl, or IPv6
#                         preference in the JVM). Emit an actionable message and
#                         STOP (rc 1) so the suites are not run and the cascade is
#                         not misattributed.
#   curl FAIL+ CLI OK   → unusual (curl-only proxy/cert quirk, or a transient curl
#                         failure). Surface for investigation but do NOT stop
#                         (rc 0) — the CLI proves the cluster is reachable.
#
# Args: $1 = management endpoint (http[s]://host:port). $2 = human label (e.g. "Cluster A").
# Robustness: every probe is fully guarded so neither a curl failure nor a CLI
# failure (nor a missing `aether` binary) can abort this function under
# `set -euo pipefail` and hide its own verdict. Returns 1 ONLY for the
# curl-OK/CLI-fail operator-machine case; 0 otherwise.
connectivity_preflight() {
    local endpoint="${1:-${CLUSTER_ENDPOINT}}"
    local label="${2:-cluster}"
    local timeout="${PREFLIGHT_TIMEOUT:-10}"

    # --- Probe 1: raw HTTP via curl (the api_get transport). ---
    # `/api/nodes/status` is a genuine management read (same endpoint cluster_leader_http
    # uses), not a bare /health/live that a half-booted node answers — so a curl OK here
    # means the management API is genuinely serving over plain HTTP from this machine.
    local curl_ok=false
    if curl -sfk -m "$timeout" -H "X-API-Key: ${API_KEY}" "${endpoint}/api/nodes/status" >/dev/null 2>&1; then
        curl_ok=true
    fi

    # --- Probe 2: the `aether` CLI against the SAME endpoint. ---
    # Mirrors aether_failover's invocation (scheme, --api-key, --request-timeout,
    # optional --tls-skip-verify) so the comparison is apples-to-apples. The CLI execs
    # `java`; if Local Network Privacy / a proxy / IPv6 blocks java from the cluster LAN,
    # this fails while curl above succeeds — exactly the signal we want to surface.
    local cli_ok=false
    if command -v aether >/dev/null 2>&1; then
        # Strip any scheme the caller passed, then re-attach MGMT_SCHEME so the CLI form
        # matches the rest of the harness (aether -c <scheme>://host:port).
        local host_port="${endpoint#http://}"
        host_port="${host_port#https://}"
        local cli_tls_flag=""
        if [ "${MGMT_SCHEME}" = "https" ]; then
            cli_tls_flag="--tls-skip-verify"
        fi
        # shellcheck disable=SC2086
        if aether -c "${MGMT_SCHEME}://${host_port}" --api-key "${API_KEY}" \
                "--request-timeout=${timeout}" ${cli_tls_flag} status >/dev/null 2>&1; then
            cli_ok=true
        fi
    else
        # No CLI on PATH — cannot make the CLI-vs-curl distinction. Treat as a
        # non-blocking note rather than a verdict; the suites that need the CLI
        # will fail loudly on their own.
        log_warn "preflight [${label}]: 'aether' CLI not found on PATH — skipping CLI reachability probe"
        return 0
    fi

    # --- Verdict ---
    if [ "$curl_ok" = true ] && [ "$cli_ok" = true ]; then
        log_info "preflight [${label}]: OK — curl and CLI both reach ${endpoint}"
        return 0
    fi

    if [ "$curl_ok" = false ] && [ "$cli_ok" = false ]; then
        # Both transports failed → genuinely unreachable. Do NOT mask: let the
        # existing readiness/gate flow report it (00-smoke / the aborted-all gate).
        log_warn "preflight [${label}]: curl AND CLI both fail to reach ${endpoint} — cluster appears genuinely unreachable; deferring to the normal failure path"
        return 0
    fi

    if [ "$curl_ok" = true ] && [ "$cli_ok" = false ]; then
        # The actionable case: raw HTTP reaches the cluster but the CLI (java) cannot.
        log_error "preflight [${label}]: raw HTTP (curl) reaches ${endpoint} but the 'aether' CLI cannot."
        log_error "  This is THIS machine's CLI, not the cluster: curl/api_get and the cluster are healthy,"
        log_error "  but the java binary the CLI execs is being blocked from the cluster network."
        log_error "  Common causes and fixes:"
        log_error "    - macOS Local Network Privacy (TCC) blocking java on a LAN (192.168.x/10.x) cluster:"
        log_error "      grant Local Network access to your terminal app (System Settings > Privacy & Security >"
        log_error "      Local Network) and to the java binary the CLI runs."
        log_error "    - run the CLI from inside the cluster network (e.g. on the docker/remote host itself)."
        log_error "    - use public-IP / loopback-tunnelled endpoints the JVM is allowed to reach."
        log_error "  Stopping before suites run so this is NOT misattributed to a dead cluster."
        return 1
    fi

    # Remaining case: curl FAIL + CLI OK — unusual. The CLI proves the cluster is
    # reachable, so do not stop; surface it for investigation.
    log_warn "preflight [${label}]: the 'aether' CLI reaches ${endpoint} but raw HTTP (curl) does not — unusual (curl-only proxy/cert quirk or transient curl failure); proceeding, but investigate if suites that use api_get/curl fail"
    return 0
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
    local rc errfile
    errfile=$(mktemp)
    log_info "Waiting for: ${description} (timeout: ${timeout}s)"
    # Cloud poll-loop hygiene: predicates round-trip through _resolve_live_endpoint
    # → _api_call against the PINNED CLUSTER_ENDPOINT. When that endpoint is a
    # just-killed VM (a dead public IP), every poll re-scans/re-curls it; combined
    # with connect stalls this stretched a nominal 720s budget to 40+ min wall-clock
    # and starved the rest of cluster-B. On cloud we (1) resolve+export a live mgmt
    # endpoint ONCE up front so the per-poll _resolve_live_endpoint hits its
    # fast happy-path probe (CLUSTER_ENDPOINT live → one `-m 2` curl, no VM scan),
    # and (2) re-refresh ONLY when a poll's predicate fails — so a dead endpoint is
    # rotated to a live node on the next iteration rather than retried at full
    # timeout every poll. The overall scaled deadline is unchanged. _api_call's
    # cloud connect-timeout (see CLOUD_API_CONNECT_TIMEOUT) bounds the single
    # straggling call between a refresh and the next predicate. docker/local/remote
    # take neither branch — byte-identical behaviour there.
    #
    # #441 run 7 (2026-07-12): the mitigation above assumes a refresh is cheap
    # ("happy path" -m 2 probe) except right after a kill. That assumption breaks
    # when the WHOLE cluster is down (self-drain): every refresh takes the full
    # slow VM-scan/enumerate path, so a single iteration can cost far more than
    # `interval`. The previous deadline check compared a NOMINAL counter
    # (`elapsed += interval`, once per iteration) against `timeout`, silently
    # assuming each iteration costs exactly `interval` seconds — so a configured
    # 360s budget took a real wall-clock 1h50m to actually expire against a fully
    # dead cluster. Track the deadline against bash's `$SECONDS` (real elapsed
    # time regardless of how long a single predicate/refresh call takes) instead —
    # matching the wall-clock-ceiling pattern already used by
    # wait_for_node_removed/wait_for_container_exit elsewhere in this codebase.
    local start_seconds=$SECONDS
    local deadline=$((start_seconds + timeout))
    local cloud_poll="false"
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        cloud_poll="true"
        _refresh_mgmt_entry_point >/dev/null 2>&1 || true
    fi
    while [ "$SECONDS" -lt "$deadline" ]; do
        # Capture rc without tripping `set -e` from the caller — `eval` as a standalone
        # command would propagate its non-zero exit and abort the entire script when
        # the predicate is simply false. The `&& rc=0 || rc=$?` idiom swallows the exit
        # code into a captured variable, equivalent to the legacy `if eval; then`
        # protection without re-introducing the if/then nesting.
        eval "$check_cmd" > /dev/null 2>"$errfile" && rc=0 || rc=$?
        case "$rc" in
            0)
                log_pass "${description} ($((SECONDS - start_seconds))s)"
                rm -f "$errfile"
                return 0
                ;;
            2|127)
                # Bash parse error / command not found — predicate is buggy, not just false.
                # Surface it so a test author can fix the typo instead of waiting for timeout.
                log_warn "wait_for predicate emitted shell error (rc=${rc}): $(head -c 300 < "$errfile")"
                ;;
        esac
        # On cloud, a non-zero predicate may mean the pinned endpoint just died
        # (the node it pointed at was killed). Rotate to a live node BEFORE the
        # next poll so the dead endpoint isn't re-probed at full cost every
        # iteration. Cheap: succeeds via the `-m 2` happy-path probe when the
        # current endpoint is still live, scans VMs only when it genuinely died.
        if [ "$cloud_poll" = "true" ]; then
            _refresh_mgmt_entry_point >/dev/null 2>&1 || true
        fi
        # Skip the final sleep once the deadline has already passed — avoids
        # overshooting the wall-clock ceiling by an extra `interval` for no reason.
        [ "$SECONDS" -lt "$deadline" ] && sleep "$interval"
    done
    log_fail "${description} (timed out after $((SECONDS - start_seconds))s, budget ${timeout}s)"
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

# Cloud provider seam. Selects the compute/firewall backend the chaos primitives
# (cloud_kill_vm / cloud_partition_node / cloud_server_id, lib/cluster.sh) dispatch
# on. Only `hetzner` (via the `hcloud` CLI + Hetzner API) is implemented today;
# a future `aws` / `gcloud` branch slots into the same `case "$CLOUD_PROVIDER"`
# without touching call sites. Inferred from CLOUD_SOURCE_NAME when unset
# (`hetzner-*` → hetzner) so existing cloud configs keep working unchanged.
case "${CLOUD_PROVIDER:-}" in
    "" )
        case "$CLOUD_SOURCE_NAME" in
            hetzner-*|hetzner) CLOUD_PROVIDER="hetzner" ;;
            aws-*|aws)         CLOUD_PROVIDER="aws" ;;
            gcp-*|gcloud-*|gcp|gcloud) CLOUD_PROVIDER="gcp" ;;
            *)                 CLOUD_PROVIDER="hetzner" ;;
        esac
        ;;
esac
export CLOUD_PROVIDER

# Hetzner Cloud REST API base. Overridable for testing. No longer consumed by the
# cloud resolver helpers (cloud_server_id maps IP->id via the `hcloud` CLI, which
# talks to the API itself); retained as a single knob for any future raw-API probe.
HCLOUD_API="${HCLOUD_API:-https://api.hetzner.cloud/v1}"

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


# Return the Hetzner server ID (numeric) for a cluster node id — seed OR CTM
# replacement. IP-based and cluster-correct: resolve the node to its routable
# public IP via cloud_public_ip (cluster-scoped — bootstrap-state for seeds,
# this cluster's mgmt API for replacements), then map that IP to a server id with
# the `hcloud` CLI. Needed by the provider chaos primitives (poweroff / firewall
# apply) which address servers by id, not IP. Prints the id on stdout + rc 0;
# logs and returns non-zero on failure.
#
# Robustness: NEVER parse Hetzner's multi-line pretty-printed JSON with line-based
# grep (the old _hcloud_resolve_server bug — `"ipv4"`/`"ip"` land on separate
# lines so the regex never matched, the ip came back empty, and resolution bailed
# even for bootstrap seeds). `hcloud server list -o columns=id,ipv4 -o noheader`
# emits one `id<WS>ipv4` row per server with the JSON parsed internally; awk does
# an exact field match. Cluster disambiguation comes from cloud_public_ip (the IP
# is unique per VM), not from a global `aether-node-id` label scan (which matched
# the same seed id across BOTH clusters' VMs — the A/B ambiguity bug).
#
# Provider-agnostic shell: dispatches on CLOUD_PROVIDER so an aws/gcp branch slots
# in later. Hetzner is the only implemented backend today.
cloud_server_id() {
    local node_id="${1:-}"
    if [ -z "$node_id" ]; then
        log_fail "cloud_server_id: node id argument is required"
        return 2
    fi
    case "${CLOUD_PROVIDER:-hetzner}" in
        hetzner)
            if ! command -v hcloud >/dev/null 2>&1; then
                log_fail "cloud_server_id: hcloud CLI not found (required for provider 'hetzner')"
                return 2
            fi
            local ip
            ip=$(cloud_public_ip "$node_id") || {
                log_fail "cloud_server_id: could not resolve a public IP for '${node_id}' (cluster-scoped lookup failed)"
                return 1
            }
            # Map IP -> numeric server id. `hcloud -o columns -o noheader` prints
            # `id<WS>ipv4` per server; awk exact-matches column 2 and prints the id.
            # No manual JSON parsing — hcloud handles the (multi-line) API body.
            local sid
            sid=$(hcloud server list -o columns=id,ipv4 -o noheader 2>/dev/null \
                    | awk -v ip="$ip" '$2==ip{print $1; exit}')
            if [ -z "$sid" ]; then
                log_fail "cloud_server_id: no Hetzner server has public IP '${ip}' (node '${node_id}')"
                return 1
            fi
            printf '%s\n' "$sid"
            return 0
            ;;
        *)
            log_fail "cloud_server_id: provider '${CLOUD_PROVIDER}' not implemented (only 'hetzner')"
            return 2
            ;;
    esac
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
        # node-id reaches us from /api/nodes/status, not from bootstrap. Ask THIS
        # cluster's own management API for the node's advertised transport address.
        #
        # `GET /api/nodes/endpoint/<id>` (harness-resilience spec A1) returns
        # {"nodeId":..,"address":"host:port","reachable":bool} where `address` is the
        # node's own view of its cluster-transport endpoint. The advertise-host fix
        # makes `host` a routable public IP on cloud, so we take the host portion.
        #
        # CLUSTER-SCOPED + UNAMBIGUOUS: api_get resolves via _resolve_live_endpoint,
        # which addresses THIS cluster's live mgmt endpoint — so an A-vs-B
        # node-id collision (the seed `aether-node-id` label ambiguity) cannot occur:
        # the answer comes from the cluster that actually owns the node-id. This
        # replaces the old raw-curl + grep-the-label provider fallback, which (a)
        # could not parse Hetzner's multi-line pretty-printed JSON and (b) matched a
        # bare `aether-node-id=<seed-id>` label across BOTH clusters' VMs.
        #
        # Resolve by the ORIGINAL node_id (CTM ids do not carry `<source>-core-N`).
        local ep_body ep_addr
        ep_body=$(api_get "/api/nodes/endpoint/${node_id}" 2>/dev/null || true)
        if [ -n "$ep_body" ]; then
            ep_addr=$(json_value "$ep_body" "address")
            ip="${ep_addr%%:*}"   # strip ":port"; leave a bare host/IP
        fi
        if [ -n "$ip" ]; then
            printf '%s\n' "$ip"
            return 0
        fi
        log_fail "cloud_public_ip: no entry for '${target}' (input='${node_id}') in ${state_file} and this cluster's /api/nodes/endpoint/${node_id} returned no address"
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
