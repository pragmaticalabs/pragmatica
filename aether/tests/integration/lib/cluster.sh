#!/bin/bash
# cluster.sh — Cluster lifecycle operations for Aether integration tests

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR}/common.sh"
source "${LIB_DIR}/generation.sh"

# ---------------------------------------------------------------------------
# Cluster queries (CLI-based)
# ---------------------------------------------------------------------------
cluster_node_count() {
    # Query core node count via the generation snapshot rather than the topology
    # endpoint. `/api/cluster/topology` `coreCount` is filtered to ON_DUTY+HEALTHY
    # members only, so during a CTM scale-up the freshly-provisioned overlay-only
    # node (no host port mapping) lags arbitrarily — first as JOINING then while
    # SWIM reaches HEALTHY. The test host can't reach the new node directly, so
    # it never sees `coreCount` reflect the actual cluster size during the
    # convergence window.
    #
    # `/api/cluster/generation` exposes the authoritative snapshot member set:
    #   - `core.members[]`   — every member admitted to the snapshot, including
    #                          JOINING / non-HEALTHY ones (CTM has placed them
    #                          in the cluster manifest)
    #   - `core.desiredSize` — the configured target after the most recent
    #                          committed scale request
    #
    # Primary signal is `members.length` (ground truth: what the cluster
    # considers its membership). `desiredSize` is consulted as a tie-breaker
    # only when the snapshot has not yet been published at all (cold boot)
    # or when it lags desired during the brief admit window. Falls back to
    # topology.coreCount only if the generation endpoint is unreachable / empty.
    local gen
    gen=$(direct_api_get "/api/cluster/generation" 2>/dev/null)
    local desired members observed=0
    if [ -n "$gen" ]; then
        # Count occurrences of `"nodeId"` inside the response — each
        # ClusterGenerationMember carries exactly one such field; communities[]
        # uses governorNodeId, partitions[] uses ownerNodeId, so a bare
        # "nodeId" key count gives an exact snapshot member tally regardless
        # of array nesting.
        members=$(printf '%s' "$gen" | grep -o '"nodeId"' | wc -l | tr -d ' ')
        members="${members:-0}"
        desired=$(printf '%s' "$gen" \
            | grep -o '"desiredSize"[[:space:]]*:[[:space:]]*[0-9]*' \
            | head -1 | grep -o '[0-9]*$' || true)
        desired="${desired:-0}"
        observed="$members"
        if [ "$desired" -gt "$observed" ] 2>/dev/null; then
            observed="$desired"
        fi
    fi
    if [ "$observed" -gt 0 ] 2>/dev/null; then
        echo "$observed"
        return 0
    fi
    # Fallback: topology endpoint (legacy behaviour, used when generation
    # snapshot is unavailable — cold cluster pre-projection).
    local response
    response=$(direct_api_get "/api/cluster/topology" 2>/dev/null)
    json_value "$response" "coreCount" 2>/dev/null || echo "0"
}

cluster_leader() {
    aether_field status cluster.leaderId
}

# Single-shot count assertion: blocks until the leader publishes a snapshot at the
# current epoch (so KV writes that just landed are reflected), then reads count.
#
# Use after a state-changing action (scale_cluster, kill_node, etc.) when the test
# wants the FINAL count without polling. Without this, `cluster_node_count` may
# return the pre-action snapshot — particularly for scale-down, where the
# `max(members, desired)` heuristic biases toward the larger (stale) members count.
#
# Args: optional timeout in seconds (default 30).
# Falls back to the plain `cluster_node_count` if the await endpoint is unreachable
# (cold cluster pre-projection) so the call is always safe.
cluster_node_count_quiesced() {
    local timeout="${1:-30}"
    local endpoint="${CLUSTER_ENDPOINT:-}"
    if [ -n "$endpoint" ]; then
        await_generation_quiesced "$endpoint" "current" "$timeout" >/dev/null 2>&1 || true
    fi
    cluster_node_count
}

# Spec §4.4 / §10 P7: tests must consume the same operator-visible signals.
# `clusterPhase` is published by HealthReconciler via consensus on ClusterPhaseKey
# and projected to every node. Empty/default → "BOOTING".
cluster_phase() {
    aether_field status clusterPhase
}

# Whether the cluster currently has quorum (leader committed AND ≥ ⌈N/2⌉+1 ON_DUTY nodes).
# Returns "true" or "false" (cluster.quorate field on StatusResponse).
cluster_quorate() {
    aether_field status cluster.quorate
}

# Per-node lifecycle state from the per-node NodeLifecycleKey atom in KV-Store
# (HealthReconciler is sole writer — see spec §4.3 P4). One of:
# JOINING, ON_DUTY, DRAINING, DECOMMISSIONED, SHUTTING_DOWN — or UNKNOWN if no atom yet.
node_lifecycle_state() {
    local target_node="$1"
    aether_json status 2>/dev/null \
        | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"'"$target_node"'"[^}]*"lifecycleState"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
        | head -1
}

cluster_status() {
    aether_json status
}

cluster_health() {
    aether_json health
}

cluster_events() {
    aether_json events
}

cluster_node_list() {
    aether_json status 2>/dev/null
}

# Resolve the pinned MGMT entry-point node ID for the active cluster.
#
# HISTORICAL CONTEXT: prior to the mgmt-gateway sidecar (aether-{a,b}-mgmt-gateway),
# MGMT_ENTRY_POINT was pinned to node-1's host-mapped port. Cluster B used
# `restart: "no"`, so killing node-1 stranded every subsequent suite at a dead
# port -- forcing a pile of "if leader == pinned, skip this test" gates.
#
# Now that nginx sidecars own ports 5150 (A) and 5160 (B) and round-robin to
# any healthy core via proxy_next_upstream, there is no pinned node and any
# core (including node-1, including the current leader) is a valid kill target.
# This helper therefore returns empty by default. Callers that historically
# relied on the pinned-node return value treat empty as "no pinning constraint"
# (see pick_non_leader / kill_node / wait_for_replacement_of usage).
#
# Selection order:
#   1. Explicit env override `MGMT_ENTRY_POINT_NODE` (per-suite escape hatch
#      preserved for any future cloud-mode pinning -- cloud has no gateway yet).
#   2. Otherwise: empty.
mgmt_entry_point_node() {
    if [ -n "${MGMT_ENTRY_POINT_NODE:-}" ]; then
        printf '%s' "$MGMT_ENTRY_POINT_NODE"
        return 0
    fi
    printf ''
}

# Pick a non-leader node from the cluster's CURRENT live membership.
# Always excludes the leader. Additionally excludes any explicitly pinned MGMT
# entry-point node (MGMT_ENTRY_POINT_NODE env override -- empty in normal
# docker/remote runs since the mgmt-gateway sidecar removed the need for
# client-side node pinning). Fails loudly if no candidate remains.
#
# Source of truth: `/api/nodes/lifecycle` filtered to state=ON_DUTY. This
# excludes nodes that were drained / killed / decommissioned by earlier
# suites — those are NOT valid kill targets because:
#  (a) their KV state is DECOMMISSIONED (single-writer rule) so they can't
#      be re-admitted to the cluster even when the container restarts,
#  (b) the surviving cluster has already removed them from its SWIM members
#      map, so killing them produces no Ping timeout and no FAULTY edge.
# Falls back to the static `node-1..5` list only if the API call fails
# (e.g. cluster not yet ready / pre-bootstrap).
pick_non_leader() {
    local leader="$1"
    local count="${2:-1}"
    local pinned
    pinned=$(mgmt_entry_point_node)

    local current_members
    current_members=$(api_get "/api/nodes/lifecycle" 2>/dev/null \
        | grep -oE '"nodeId":"[^"]+","state":"ON_DUTY"|"state":"ON_DUTY","nodeId":"[^"]+"' \
        | grep -oE '"nodeId":"[^"]+"' \
        | sed 's/"nodeId":"\([^"]*\)"/\1/' || true)
    if [ -z "$current_members" ]; then
        # Fail-closed: previously fell back to a hardcoded `node-1..5` list, which
        # silently picked DECOMMISSIONED victims — kill_node became a no-op, NODE_FAILED
        # never fired, and the suite "passed" against a stale cluster snapshot. If
        # /api/nodes/lifecycle has no ON_DUTY members, the test premise (a healthy
        # cluster from which we can pick a non-leader) is broken.
        log_fail "pick_non_leader: /api/nodes/lifecycle returned no ON_DUTY members — cannot select victim"
        return 1
    fi

    local found=0
    local candidate
    while IFS= read -r candidate; do
        [ -z "$candidate" ] && continue
        if [ "$candidate" = "$leader" ]; then continue; fi
        if [ -n "$pinned" ] && [ "$candidate" = "$pinned" ]; then continue; fi
        echo "$candidate"
        found=$((found + 1))
        if [ "$found" -ge "$count" ]; then return 0; fi
    done <<< "$current_members"

    if [ "$found" -lt "$count" ]; then
        log_fail "pick_non_leader: only ${found}/${count} candidates available (leader=${leader}, pinned=${pinned:-<none>}, cluster=${CLUSTER_ID:-<none>})"
        return 1
    fi
}

# Wait for every node (ports MGMT_PORT..MGMT_PORT+NODE_COUNT-1) to report
# /health/ready=UP locally. Each node's readiness gates on consensus + quorum +
# routes being ready on THAT node — so an UP across all 5 ports means no
# half-warm node remains after a kill/resurrect cycle.
#
# Why this matters: `restart_all_nodes` rotates MGMT_ENTRY_POINT in its own
# subshell and validates against the rotated node. The next test script runs
# in a fresh subshell that re-pins MGMT_ENTRY_POINT to the suite default
# (run-tests.sh:253-257) — typically the node we just killed and resurrected.
# Without per-node readiness confirmation here, the next test's first poll
# hits a still-warming node and drags a ~15-30s convergence into a multi-
# minute timeout cascade (60s healthy + 120s leader + JVM cold-start cost
# in `aether status` per iter).
#
# Uses raw curl to keep per-iter cost ~50ms (vs ~5-15s/iter for `aether status`).
wait_for_all_nodes_ready() {
    local timeout="${1:-120}"
    local deadline=$(($(date +%s) + timeout))
    local pending=()
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        pending+=($((MGMT_PORT + i)))
    done
    while [ "$(date +%s)" -lt "$deadline" ] && [ "${#pending[@]}" -gt 0 ]; do
        local still_pending=()
        for port in "${pending[@]}"; do
            local body
            body=$(curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" \
                        "http://${TARGET_HOST}:${port}/health/ready" 2>/dev/null) || {
                still_pending+=("$port")
                continue
            }
            if printf '%s' "$body" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
                continue
            fi
            still_pending+=("$port")
        done
        # `set -u` rejects "${still_pending[@]}" when the array is empty (all nodes ready).
        # Guard explicitly: if everything is ready, clear pending and break out.
        if [ "${#still_pending[@]}" -eq 0 ]; then
            pending=()
            break
        fi
        pending=("${still_pending[@]}")
        sleep 1
    done
    if [ "${#pending[@]}" -gt 0 ]; then
        log_warn "wait_for_all_nodes_ready: not ready on ports: ${pending[*]}"
        # Diagnostic: dump full /health/ready body for each pending node so we can see
        # which ComponentHealth (consensus / routes / quorum) is DOWN. Without this,
        # all we know is "not ready" — useless for nailing down the actual bug.
        for port in "${pending[@]}"; do
            local diag
            diag=$(curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" \
                        "http://${TARGET_HOST}:${port}/health/ready" 2>&1 \
                        || echo '<no response or non-2xx>')
            log_warn "wait_for_all_nodes_ready: port=${port} body=${diag}"
        done
        return 1
    fi
    return 0
}

# Rotate MGMT_ENTRY_POINT to any surviving core node reachable on the cluster's mgmt port.
# Chaos tests that kill the current entry point call this AFTER the kill to restore CLI access.
#
# Docker/remote: the mgmt-gateway sidecar (aether-{a,b}-mgmt-gateway) already
# provides core-independent management access on the entry-point port. If the
# gateway responds to /gateway/live, this function is a no-op -- rotating to a
# direct core port would actually REGRESS the test by re-pinning to one core
# and re-introducing the pinned-leader problem we just removed.
# Cloud: each node has its own public VM IP; mgmt port is uniform (8080 per
# cloud-hetzner.toml). No gateway yet on cloud, so rotation still applies.
rotate_mgmt_entry_point() {
    # Short-circuit when the docker mgmt-gateway sidecar is healthy. The
    # gateway is the canonical entry point on docker/remote envs; rotating
    # off it would re-pin MGMT_ENTRY_POINT to a single core's direct port.
    if [ "${ENV_TYPE:-docker}" != "cloud" ]; then
        if curl -sfk -m 2 "${MGMT_ENTRY_POINT}/gateway/live" >/dev/null 2>&1; then
            return 0
        fi
    fi
    if [ "${ENV_TYPE:-docker}" = "cloud" ]; then
        # Cloud uses CLOUD_MGMT_PORT (default 8080); MGMT_PORT is docker's host-mapped
        # port range (5150-5159) and not applicable to per-VM cloud nodes.
        local mgmt_port="${CLOUD_MGMT_PORT:-8080}"
        for i in $(seq 0 $((NODE_COUNT - 1))); do
            local node_id ip
            node_id=$(to_node_id "node-$((i + 1))" 2>/dev/null || true)
            [ -z "$node_id" ] && continue
            ip=$(cloud_public_ip "$node_id" 2>/dev/null || true)
            [ -z "$ip" ] && continue
            local endpoint="http://${ip}:${mgmt_port}"
            if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
                export MGMT_ENTRY_POINT="$endpoint"
                export CLUSTER_ENDPOINT="$endpoint"
                log_info "Rotated MGMT_ENTRY_POINT to ${endpoint} (cloud)" >&2
                return 0
            fi
        done
        log_warn "rotate_mgmt_entry_point: no surviving core node reachable on ${NODE_COUNT} cloud VMs at port ${mgmt_port}" >&2
        return 1
    fi
    local base_port="${MGMT_PORT}"
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local port=$((base_port + i))
        local endpoint="http://${TARGET_HOST}:${port}"
        if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
            export MGMT_ENTRY_POINT="$endpoint"
            export CLUSTER_ENDPOINT="$endpoint"
            log_info "Rotated MGMT_ENTRY_POINT to ${endpoint}" >&2
            return 0
        fi
    done
    log_warn "rotate_mgmt_entry_point: no surviving core node on ports ${base_port}..${base_port}+$((NODE_COUNT - 1))" >&2
    return 1
}

cluster_slices() {
    aether_json slices
}

cluster_config() {
    aether_json config
}

# ---------------------------------------------------------------------------
# Health checks
# ---------------------------------------------------------------------------
is_cluster_healthy() {
    local status
    status=$(aether_field health status)
    # Pin to canonical "healthy" (matches the assertion done by `assert_cluster_healthy`
    # below). Previously accepted "UP" OR "healthy" — the dual-acceptance hid a
    # transient bootstrap value and let the test "pass" before the cluster reached
    # its post-quorum health state.
    [ "$status" = "healthy" ]
}

assert_cluster_healthy() {
    local desc="$1"
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "$desc"
}

is_cluster_ready() {
    # Fix J: gate cluster-readiness on BOTH node count AND a real elected leader.
    # The previous count-only check returned TRUE for "5 nodes connected, no leader",
    # which silently masked failures into downstream `await-quiesced` 500s — a leaderless
    # cluster has CTM dormant, so blueprint/scale/task ops are no-ops that look like passes
    # via `none == none` matches in `cluster_leader` comparisons. Requiring a non-empty,
    # non-"none" leaderId restores fail-fast behaviour for genuine leader-election regressions.
    local count
    count=$(cluster_node_count)
    [ -n "$count" ] && [ "$count" -ge "${NODE_COUNT:-5}" ] 2>/dev/null || return 1
    local leader
    leader=$(cluster_leader 2>/dev/null)
    [ -n "$leader" ] && [ "$leader" != "none" ]
}

# ---------------------------------------------------------------------------
# Endpoint discovery
# ---------------------------------------------------------------------------

# Discover LB endpoints from cluster status API.
# Sets LB_APP_ENDPOINT and LB_MGMT_ENDPOINT from the elected LB node info.
# Falls back to direct node access if no LB is configured.
discover_endpoints() {
    local cluster_endpoint="$1"
    local host_port="${cluster_endpoint#http://}"
    host_port="${host_port#https://}"

    LB_APP_ENDPOINT=$(aether -c "$host_port" status --format value --field appEndpoint 2>/dev/null || true)
    LB_MGMT_ENDPOINT=$(aether -c "$host_port" status --format value --field mgmtEndpoint 2>/dev/null || true)

    # Validate reachability — the LB endpoint is reported with the internal cluster
    # hostname, which is unreachable from the test host. Fall back to direct access.
    # Connection-level probe only; the CLI can't bypass DNS failures.
    if [ -n "$LB_MGMT_ENDPOINT" ] && ! curl -sfk -m 3 -H "X-API-Key: ${API_KEY}" "${LB_MGMT_ENDPOINT}/health/live" >/dev/null 2>&1; then
        LB_APP_ENDPOINT=""
        LB_MGMT_ENDPOINT=""
    fi

    # When no LB or LB unreachable, leave LB_APP_ENDPOINT empty so the caller
    # picks its CLUSTER_*_APP_DIRECT fallback (correct app HTTP port). Mgmt may
    # be reused as-is for management API since direct mgmt also serves /api/*.
    if [ -z "$LB_MGMT_ENDPOINT" ]; then
        LB_MGMT_ENDPOINT="$cluster_endpoint"
    fi
}

wait_for_lb_ready() {
    local endpoint="$1"
    local timeout="${2:-120}"
    wait_for "LB ready at ${endpoint}" \
        "curl -sfk ${endpoint}/health/live >/dev/null 2>&1" \
        "$timeout"
}

# ---------------------------------------------------------------------------
# Wait helpers
# ---------------------------------------------------------------------------
wait_for_cluster() {
    wait_for "cluster healthy" "is_cluster_ready" "${1:-120}"
}

# Wait for cluster using direct node access (before LB is available)
wait_for_cluster_direct() {
    wait_for "cluster healthy (direct)" \
        "[ \$(json_value \"\$(curl -sfk -H 'X-API-Key: ${API_KEY}' http://${TARGET_HOST}:${MGMT_PORT}/api/health 2>/dev/null)\" connectedPeers 2>/dev/null || echo 0) -ge 2 ]" \
        "${1:-120}"
}

wait_for_node_count() {
    local expected="$1" timeout="${2:-120}"
    wait_for "${expected} nodes" "[ \$(cluster_node_count) -eq ${expected} ]" "$timeout"
}

# Faster variant of wait_for_node_count for tight scaling polls (test-02/03 scale up/down).
# `cluster_node_count` round-trips through `_resolve_live_endpoint` (one curl probe) and
# then through `api_get` (a second curl to fetch topology). On Hetzner remote each curl
# can spend up to 2s on a stalled endpoint — combined with the 2s `wait_for` interval
# and JSON parsing, an iteration can take 4-6s, so a 300s timeout only buys ~50-75
# iterations. This helper bypasses the double probe by curling a known-live port
# directly with a tight 2s timeout, polling once per second.
#
# Spec deviation justification (per project rule "prefer aether CLI"): this is a
# tight polling loop where CLI/double-probe overhead dominates. Used only inside
# scaling tests; functional cluster ops still go through the CLI / api_get layer.
#
# Endpoint discovery rotates through MGMT_PORT..MGMT_PORT+NODE_COUNT-1, picking the
# first one that answers /health/live within 1s. JSON path: max(`core.desiredSize`,
# count of `"nodeId"` occurrences in core.members[]) from /api/cluster/generation,
# falling back to `coreCount` from /api/cluster/topology if generation is empty.
# Mirrors `cluster_node_count` — see that helper for the full rationale; the short
# version is that topology.coreCount filters to ON_DUTY+HEALTHY and lags during
# CTM scale-up while the generation snapshot reflects the committed cluster
# membership including JOINING peers (overlay-only, not host-port-mapped).
wait_for_node_count_fast() {
    local expected="$1" timeout="${2:-120}"
    # The fast poll picks an endpoint by hopping ports on a single host — Docker-only
    # by construction. On cloud, each node has its own VM IP, so port-hop never finds
    # a candidate and last_count stays '?'. Fall through to the slow, cloud-aware
    # `wait_for_node_count` instead of producing a misleading FAIL log every run.
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        wait_for_node_count "$expected" "$timeout"
        return $?
    fi
    local deadline=$(($(date +%s) + timeout))
    local last_count="?"
    log_info "Waiting for: ${expected} nodes (timeout: ${timeout}s, fast poll)"
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local endpoint=""
        local base_port="${MGMT_PORT}"
        for i in $(seq 0 $((NODE_COUNT - 1))); do
            local port=$((base_port + i))
            local candidate="http://${TARGET_HOST}:${port}"
            if curl -sfk -m 1 -H "X-API-Key: ${API_KEY}" "${candidate}/health/live" >/dev/null 2>&1; then
                endpoint="${candidate}"
                break
            fi
        done
        if [ -n "$endpoint" ]; then
            local gen
            gen=$(curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" \
                        "${endpoint}/api/cluster/generation" 2>/dev/null) || gen=""
            local desired members observed=0
            if [ -n "$gen" ]; then
                # `|| true` guards each pipeline against `set -euo pipefail` aborts
                # when the snapshot has not yet been published (cold cluster).
                desired=$(printf '%s' "$gen" \
                    | grep -o '"desiredSize"[[:space:]]*:[[:space:]]*[0-9]*' \
                    | head -1 | grep -o '[0-9]*$' || true)
                desired="${desired:-0}"
                members=$(printf '%s' "$gen" | grep -o '"nodeId"' | wc -l | tr -d ' ')
                members="${members:-0}"
                observed="$members"
                if [ "$desired" -gt "$observed" ] 2>/dev/null; then
                    observed="$desired"
                fi
            fi
            if [ "$observed" -eq 0 ] 2>/dev/null; then
                # Fallback to topology.coreCount for cold-boot cases where the
                # generation snapshot has not yet been projected to KV.
                local body
                body=$(curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" \
                            "${endpoint}/api/cluster/topology" 2>/dev/null) || body=""
                if [ -n "$body" ]; then
                    observed=$(printf '%s' "$body" \
                        | grep -o '"coreCount"[[:space:]]*:[[:space:]]*[0-9]*' \
                        | head -1 \
                        | grep -o '[0-9]*$' || true)
                    observed="${observed:-0}"
                fi
            fi
            last_count="$observed"
            if [ "$last_count" = "$expected" ]; then
                log_pass "${expected} nodes (fast poll)"
                return 0
            fi
        fi
        sleep 1
    done
    log_fail "wait_for_node_count_fast: expected ${expected}, last seen '${last_count}' after ${timeout}s"
    return 1
}

wait_for_leader() {
    # Fix I: cluster-B destructive suites observe legitimate cold-boot leader-election
    # of 60–120s after `restart_all_nodes` (5x JVM cold start + QUIC mesh formation +
    # Rabia activation + LeaderElectionState.QuorumWaiting re-entry). The historical
    # 60s default was one cache-miss away from failing. Cluster-A non-destructive
    # suites continue to enforce the 60s budget as a fast-fail signal for real
    # election regressions.
    #
    # Selection order:
    #   1. Explicit env override `WAIT_FOR_LEADER_TIMEOUT` (per-suite escape hatch).
    #   2. Caller-supplied positional argument (callers like test-kill-leader pass 150).
    #   3. Cluster-B floor: 120s when CLUSTER_ID=b.
    #   4. Default: 60s (cluster A and unspecified).
    local default_timeout=60
    if [ "${CLUSTER_ID:-}" = "b" ]; then
        default_timeout=120
    fi
    local timeout="${WAIT_FOR_LEADER_TIMEOUT:-${1:-${default_timeout}}}"
    # When running on cluster B, never accept a caller-supplied timeout below the
    # cluster-B floor — destructive suites pass `wait_for_leader 60` literally and
    # those callers should inherit the bump without per-site edits.
    if [ "${CLUSTER_ID:-}" = "b" ] && [ -z "${WAIT_FOR_LEADER_TIMEOUT:-}" ] && [ "$timeout" -lt 120 ] 2>/dev/null; then
        timeout=120
    fi
    wait_for "leader elected" "[ -n \"\$(cluster_leader)\" ] && [ \"\$(cluster_leader)\" != 'none' ]" "$timeout"
}

# Spec §4.5 / §10: a leader is "committed" once `LeaderKey` is observable in KV
# (i.e. `aether status` reports a non-empty leaderId). Operationally indistinguishable
# from `wait_for_leader` for the moment — kept as a separate helper so call sites
# document intent ("we need consensus-committed leader, not just a candidate").
wait_for_leader_committed() {
    local timeout="${1:-60}"
    wait_for "leader committed" \
        "[ -n \"\$(cluster_leader)\" ] && [ \"\$(cluster_leader)\" != 'none' ]" \
        "$timeout"
}

# Spec §4.4 P7: wait for cluster.quorate=true on the StatusResponse JSON.
# `quorate` requires both a committed leader AND ≥ ⌈N/2⌉+1 ON_DUTY nodes — the
# operator-visible signal published by TopologyObserver, not a derived predicate.
wait_for_quorum() {
    local timeout="${1:-60}"
    wait_for "cluster quorate" "[ \"\$(cluster_quorate)\" = 'true' ]" "$timeout"
}

# Spec §5 / §10: wait for ClusterPhaseKey to converge to the expected phase
# (BOOTING / NORMAL / RECOVERING). Use this in place of ad-hoc sleeps after
# cluster bring-up — `wait_for_phase 'NORMAL' 60` proves the cluster left
# cold-boot mode and CTM is allowed to operate.
wait_for_phase() {
    local expected="$1" timeout="${2:-60}"
    wait_for "cluster phase=${expected}" \
        "[ \"\$(cluster_phase)\" = '${expected}' ]" \
        "$timeout"
}

# Spec §4.3 P4: wait for a specific node's NodeLifecycleKey atom to converge
# to the expected state. Use after `drain_node node-X` to confirm the FSM
# advanced (DRAINING / DECOMMISSIONED) without sleeping for a guessed window.
wait_for_node_lifecycle() {
    local target_node="$1" expected="$2" timeout="${3:-60}"
    wait_for "node ${target_node} lifecycle=${expected}" \
        "[ \"\$(node_lifecycle_state ${target_node})\" = '${expected}' ]" \
        "$timeout"
}

wait_for_slices_active() {
    local min_instances="${1:-1}" timeout="${2:-120}"
    wait_for "slices active (>= ${min_instances} instances)" \
        "[ \$(slices_active_instances) -ge ${min_instances} ]" "$timeout"
}

# Wait for every declared target instance across all deployed slices to reach ACTIVE.
# Unlike wait_for_slices_active (min count), this ensures EVERY node hosting the
# slice has completed activation (routes propagated, endpoints published). Use this
# when a test hits a specific node directly (e.g. port-mapped) — otherwise the first
# ACTIVE may race against a node still in ACTIVATING, yielding 404 on route lookup.
wait_for_all_target_instances_active() {
    local timeout="${1:-120}"
    wait_for "all slice target instances ACTIVE" \
        "[ \$(slices_active_instances) -ge \$(slices_target_total) ] && [ \$(slices_target_total) -gt 0 ]" \
        "$timeout"
}

# ---------------------------------------------------------------------------
# Slice operations
# ---------------------------------------------------------------------------
slices_total_instances() {
    local slices
    slices=$(cluster_slices)
    # Count running instances (LOADED or ACTIVE state)
    local count
    # Strict alternation — the prior `[LA][CO][AT][DI][EV][DE]*` character-class regex
    # over-matched (any 6+ char permutation of those letters: e.g., FAILED substrings,
    # future state names, garbage). Use explicit alternation so adding a new state
    # like `LOADING` requires deliberate inclusion here.
    count=$(printf '%s' "$slices" | grep -oE '"state"[[:space:]]*:[[:space:]]*"(LOADED|ACTIVE)"' | wc -l | tr -d ' ')
    echo "${count:-0}"
}

# slice_owner_for <blueprint-coords> — print the nodeId of any node hosting an ACTIVE
# instance of any slice belonging to <blueprint-coords>. Empty stdout if none found.
# Used by 08-resources tests on cloud to retarget APP_ENDPOINT from the (possibly-non-
# hosting) default node-1 to a node that actually has the slice.
#
# Response shape (from SliceRoutes::buildClusterSlicesResponse →
# ManagementApiResponses.ClusterSliceInfo):
#   { "slices": [
#       { "artifact": "<group>:<artifact>:<version>",
#         "targetInstances": N, "minInstances": M, "currentVersion": "...",
#         "instances": [
#           { "nodeId": "hetzner-eu-core-3", "state": "ACTIVE", "failureReason": "" },
#           ...
#         ] },
#       ...
#   ] }
# The nodeId lives INSIDE each instances[] object. Earlier versions of this helper
# did `tr '{' '\n'` and ANDed `artifact` + `state` + `nodeId` greps — but after the
# split, the slice-level record carries `artifact` (no nodeId / state) and each
# instance-level record carries `nodeId` + `state` (no artifact). The intersection is
# empty, so the helper returned no owner even when ACTIVE instances existed → the
# `08-resources` retarget warned and tested against the wrong VM.
#
# Awk-based parser walks the response top-to-bottom, tracks the most recent
# `"artifact":"<prefix>..."` line, and emits the first nodeId whose subsequent
# `"state":"ACTIVE"` falls within that artifact's slice block. The blueprint coord
# `org:test-persistence:1.0.0` shares the `org:test-persistence` prefix with the slice
# artifact `org:test-persistence-persistence-slice:1.0.0`; matching on the
# `${coords%:*}` (group:artifact) prefix catches both forms.
slice_owner_for() {
    local coords="$1"
    local prefix="${coords%:*}"
    cluster_slices \
        | awk -v prefix="$prefix" '
            BEGIN { in_match = 0; pending_node = "" }
            # Track when we enter a slice block whose artifact matches the prefix.
            /"artifact"[[:space:]]*:[[:space:]]*"/ {
                line = $0
                sub(/.*"artifact"[[:space:]]*:[[:space:]]*"/, "", line)
                sub(/".*/, "", line)
                in_match = (index(line, prefix) == 1)
                pending_node = ""
                next
            }
            # Within a matching slice, capture the nodeId until we see its state.
            in_match && /"nodeId"[[:space:]]*:[[:space:]]*"/ {
                line = $0
                sub(/.*"nodeId"[[:space:]]*:[[:space:]]*"/, "", line)
                sub(/".*/, "", line)
                pending_node = line
                next
            }
            in_match && pending_node != "" && /"state"[[:space:]]*:[[:space:]]*"ACTIVE"/ {
                print pending_node
                exit 0
            }
            # Reset pending_node when we cross to the next instance without ACTIVE.
            in_match && /"state"[[:space:]]*:[[:space:]]*"/ {
                pending_node = ""
            }
        '
}

# Retarget APP_ENDPOINT to a node that hosts an ACTIVE slice belonging to <coords>.
# After SliceState.ROUTING was introduced, ACTIVE means routes have propagated
# cluster-wide — but tests pinning APP_ENDPOINT to node-1 still 404 when the slice
# isn't placed on node-1 (3 instances on 5 nodes is the typical 08-resources case).
# Cloud-only: noop on docker / remote where APP_ENDPOINT is the LB.
#
# Args:
#   $1 — blueprint coords (groupId:artifactId:version) used by slice_owner_for
#   $2 — optional app HTTP port (default 8070)
#   $3 — optional probe path (e.g. /api/kv/diag) — when supplied, polls APP_ENDPOINT
#        with this path until http_status < 500 (catches the brief window where the
#        owner reported ACTIVE but the local route table hasn't been hit yet)
#   $4 — probe timeout seconds (default 30)
#
# Returns 0 on success, 1 if no owner found (caller should fall through). Always
# leaves APP_ENDPOINT at a usable value (original LB on docker, retargeted on cloud).
retarget_app_endpoint_to_active_slice() {
    local coords="$1" port="${2:-8070}" probe_path="${3:-}" probe_timeout="${4:-30}"
    # Identify a node that currently hosts an ACTIVE instance of the artifact so
    # we can probe / PUT against that node directly rather than racing route-table
    # propagation across the cluster.
    local owner
    owner=$(slice_owner_for "$coords" 2>/dev/null || true)
    if [ -z "$owner" ]; then
        # Diagnostic dump: surface the slice list so a future failure shows whether
        # /api/slices is empty (deploy didn't propagate), all instances are still
        # LOADING (timing window — caller didn't await ACTIVE), or the artifact
        # prefix doesn't match (coords mismatch between blueprint and slice).
        local diag
        diag=$(cluster_slices 2>/dev/null \
                   | tr -d '\n' \
                   | grep -oE '"artifact"[[:space:]]*:[[:space:]]*"[^"]*"|"state"[[:space:]]*:[[:space:]]*"[A-Z_]*"' \
                   | tr '\n' ' ')
        log_warn "retarget: no ACTIVE owner found for ${coords}; APP_ENDPOINT unchanged. /api/slices: ${diag:-<empty>}"
        return 1
    fi
    if [ "${ENV_TYPE:-docker}" = "cloud" ]; then
        # Cloud: each node has its own public IP at the same logical app port.
        local owner_ip
        owner_ip=$(cloud_public_ip "$owner" 2>/dev/null || true)
        if [ -z "$owner_ip" ]; then
            log_warn "retarget: cloud_public_ip(${owner}) returned empty; APP_ENDPOINT unchanged. (Owner reported by /api/slices is not in bootstrap-state.json — node may have been replaced by CTM and not re-recorded.)"
            return 1
        fi
        APP_ENDPOINT="http://${owner_ip}:${port}"
    else
        # Docker/remote: TARGET_HOST host-maps each node's app port consecutively
        # (cluster A: 8070..8074; cluster B: 8080..8084). The `port` parameter is
        # the base (node-1's host port); derive the owner's port from the node-id's
        # numeric suffix. Without this retarget, the test always probes node-1, and
        # if the slice ACTIVATED on a different node node-1's NodeRoutesKey snapshot
        # may not yet contain the route — PUTs land as 404 from sendNoRouteFound
        # rather than reaching the slice handler.
        local owner_idx
        owner_idx=$(echo "$owner" | grep -oE '[0-9]+$')
        if [ -z "$owner_idx" ]; then
            log_warn "retarget: could not parse node-index from owner '${owner}'; APP_ENDPOINT unchanged"
            return 1
        fi
        local owner_port=$((port + owner_idx - 1))
        APP_ENDPOINT="http://${TARGET_HOST}:${owner_port}"
    fi
    log_info "retarget: APP_ENDPOINT -> ${APP_ENDPOINT} (slice owner ${owner})"
    # Probe the path until the slice route is wired (positive readiness).
    #
    # Probe semantics: GET against probe_path. The check is "the slice handler ran"
    # — proven by 2xx, or 4xx whose body is NOT sendNoRouteFound's route-missing
    # problem+json. 503 / 5xx / route-missing-404 mean keep waiting. PUT is not
    # used because the slice may reject the payload (500) even when the route is
    # wired.
    if [ -n "$probe_path" ]; then
        wait_for "app endpoint route ${probe_path} wired (positive readiness)" \
            "app_route_wired \"${APP_ENDPOINT}${probe_path}\"" \
            "$probe_timeout"
    fi
    return 0
}

# Positive readiness check: returns 0 iff the request was handled by an actual slice
# route (not by sendNoRouteFound's route-missing fallback or the bootstrap 503 path).
#
# This is the honest replacement for the old "<500" probe which accepted 404 from
# sendNoRouteFound (route not registered) AND 404 from the slice's own NotFound
# handler (key missing) as success — false positive when the route table on the
# polled node hadn't caught up to the freshly-committed NodeRoutesKey.
#
# Distinguishes via response body: sendNoRouteFound emits a problem+json document
# whose `title` field contains "No route found for ". AppHttpServer also returns
# 503 when its registry has the route but the local snapshot lags — that is
# correctly treated as "not ready, retry".
#
# Args:
#   $1 — full URL to probe
#   $2 — optional X-API-Key override (defaults to $API_KEY)
app_route_wired() {
    local url="$1" api_key="${2:-${API_KEY:-}}"
    local response status body
    response=$(curl -sk -w $'\n__STATUS__:%{http_code}' -H "X-API-Key: ${api_key}" "$url" 2>/dev/null) || return 1
    status="${response##*__STATUS__:}"
    body="${response%$'\n'__STATUS__:*}"
    case "$status" in
        2*) return 0 ;;
        503) return 1 ;;
        5*) return 1 ;;
        404)
            if printf '%s' "$body" | grep -q '"title":"No route found for '; then
                return 1
            fi
            return 0
            ;;
        4*) return 0 ;;
        *) return 1 ;;
    esac
}

slices_active_instances() {
    local slices
    slices=$(cluster_slices)
    local count
    count=$(printf '%s' "$slices" | grep -o '"state"[[:space:]]*:[[:space:]]*"ACTIVE"' | wc -l | tr -d ' ')
    echo "${count:-0}"
}

slices_target_total() {
    local slices
    slices=$(cluster_slices)
    local total=0
    while IFS= read -r n; do
        total=$((total + n))
    done < <(printf '%s' "$slices" | grep -o '"targetInstances"[[:space:]]*:[[:space:]]*[0-9]*' | grep -o '[0-9]*$')
    echo "$total"
}

push_blueprint() {
    local coords="$1"
    log_info "Pushing blueprint artifacts: ${coords}" >&2
    aether_failover artifact push "$coords" 2>/dev/null
}

deploy_blueprint() {
    local artifact="$1"
    log_info "Deploying blueprint: ${artifact}" >&2
    # Single-shot: preceding await_generation_quiesced (in the test/runner) guarantees
    # the cluster is settled. Retries hid the actual race; the ClusterGeneration gate
    # replaces the compensation loop with a deterministic barrier.
    aether_failover blueprint deploy "$artifact" 2>/dev/null \
        || api_post "/api/blueprint/deploy" "{\"artifact\":\"${artifact}\"}"
}

publish_blueprint() {
    # Registers a blueprint in the cluster registry without making it active.
    # Required when starting a strategy-based deploy upgrade — the upgrade target
    # version must be in the registry, but should NOT be the currently active
    # version (otherwise SameVersionDeployment is returned).
    local artifact="$1"
    log_info "Publishing blueprint (no instances): ${artifact}" >&2
    local result
    result=$(api_post "/api/blueprint/publish" "{\"artifact\":\"${artifact}\"}")
    local rc=$?
    printf '%s' "$result"
    [ $rc -ne 0 ] && return $rc
    # KV-Store consensus commits at the leader but follower nodes apply asynchronously;
    # /api/deploy may land on a STRATEGIES owner whose local state machine hasn't yet
    # processed the Put. Poll the cluster's blueprint list until the entry is visible
    # to absorb the propagation gap (5s budget × scaled).
    # Fail-closed: previously this wait was 5s + log_warn-and-continue, which let the
    # deploy proceed against a not-yet-propagated blueprint and 404'd downstream with a
    # confusing error. The visibility gate is load-bearing — if the blueprint doesn't
    # appear within the budget, the deploy WILL fail; surfacing it here gives the test
    # author a precise diagnostic instead of a downstream 404 chase.
    if ! wait_for "blueprint ${artifact} visible" \
            "api_get /api/blueprints 2>/dev/null | grep -q \"${artifact}\"" \
            10 1; then
        log_fail "publish_blueprint: ${artifact} not visible in /api/blueprints after publish (propagation gap)"
        return 1
    fi
    return 0
}

deploy_blueprint_file() {
    local filepath="$1"
    log_info "Deploying blueprint file: ${filepath}" >&2
    local content
    content=$(cat "$filepath")
    curl -sfk -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/toml" \
        -d "$content" "${CLUSTER_ENDPOINT}/api/blueprint"
}

list_blueprints() {
    aether_json blueprint list 2>/dev/null || api_get "/api/blueprints"
}

# ---------------------------------------------------------------------------
# Node operations
# ---------------------------------------------------------------------------
_docker_container_name() {
    # Remote Docker compose files name containers `aether-<cluster_id>-<node_id>`
    # (aether-a-node-1, aether-b-node-2, ...). Fall back for older single-cluster
    # environments that just use `aether-<node_id>`.
    #
    # CTM-provisioned replacement containers carry their own `aether-core-...`
    # prefix from DockerComputeProvider (see drop_ctm_replacements); their name
    # IS the node_id. Detect and pass through unchanged — without this the
    # kill_node target was synthesized as `aether-b-aether-core-node-X-<uuid>`,
    # which doesn't exist; `docker kill` returned "No such container" and the
    # test silently believed the kill landed.
    local node_id="$1"
    case "$node_id" in
        aether-core-*) printf '%s' "$node_id"; return ;;
    esac
    if [ -n "${CLUSTER_ID:-}" ]; then
        printf 'aether-%s-%s' "$CLUSTER_ID" "$node_id"
    else
        printf 'aether-%s' "$node_id"
    fi
}

# Tear down any CTM-provisioned `aether-core-*` replacement containers on the
# remote host so the cluster settles back to the fixed compose-node set.
# Called between disruption tests to avoid phantom-sixth-node inflation.
drop_ctm_replacements() {
    if [ "$CLOUD_MODE" = "true" ]; then
        return 0
    fi
    remote_exec "docker rm -f \$(docker ps -aq --filter name=aether-core-) 2>/dev/null || true" 2>/dev/null
}

## Restart all stopped containers of the active cluster + drop any CTM-provisioned
## replacements. This is a TEST LIFECYCLE primitive (not race compensation) — destructive
## suites kill nodes; before the next suite runs we bring the cluster back to baseline.
##
## After container restoration, wait for the topology to settle to the expected
## NODE_COUNT (default 5). SWIM-driven membership reconciliation can take 15-30s
## to remove phantoms left by CTM auto-heal — without this barrier the next suite
## sees `expected 5, got 6` and cascades into a flood of false failures.
## Enforced recovery assertions — any of these failing marks the current suite's
## cleanup as FAILED rather than quietly warning. A destructive suite that leaves
## the cluster unable to elect a leader or reach target node count represents
## either a harness bug (wrong tear-down sequence) or a real product reliability
## regression (cluster can't recover from the scenario this suite exercised).
## Either way, subsequent suites must not inherit a broken cluster under a warn.
restart_all_nodes() {
    log_info "Restoring cluster to baseline (CLUSTER_NAME=${CLUSTER_NAME:-aether-b-node-})..."
    if [ "$CLOUD_MODE" = "true" ]; then
        # Two cloud modes are supported (run-tests.sh exports CLOUD_RUNTIME):
        #   container — VM runs a single `aether-node` Docker container
        #   jvm       — VM runs `java -jar /opt/aether/aether-node.jar ...` directly
        # Without this dispatch, JVM-mode VMs (no Docker installed) hit
        # `bash: docker: command not found` and `restart_all_nodes` reports 5/5
        # failures — every cluster B chaos suite then cascades into harness-induced
        # failure rather than exercising the product.
        local failed=0
        for i in $(seq 1 "${NODE_COUNT:-5}"); do
            if [ "${CLOUD_RUNTIME:-container}" = "jvm" ]; then
                # JVM mode: capture cmdline → kill → relaunch. Mirrors kill_node +
                # start_node's JVM branches. The captured cmdline is written to
                # /tmp/aether-jvm-cmd-${node_id}.txt on the local test runner so
                # destructive tests later in the suite can use it.
                local cmd_file="/tmp/aether-jvm-cmd-node-${i}.txt"
                local jvm_cmd
                jvm_cmd=$(cloud_ssh "node-${i}" "ps -o command= -C java | grep -F 'aether-node.jar' | head -1" 2>&1)
                local cap_rc=$?
                if [ "$cap_rc" -ne 0 ] || [ -z "$jvm_cmd" ]; then
                    log_warn "restart_all_nodes: could not capture JVM cmdline for node-${i} (rc=${cap_rc}): ${jvm_cmd}"
                    failed=$((failed + 1))
                    continue
                fi
                printf '%s\n' "$jvm_cmd" > "$cmd_file"
                local kill_out
                kill_out=$(cloud_ssh "node-${i}" "pkill -KILL -f 'aether-node.jar'" 2>&1) \
                    || { log_warn "restart_all_nodes: node-${i} JVM kill failed: ${kill_out}"; failed=$((failed + 1)); continue; }
                local start_out
                start_out=$(cloud_ssh "node-${i}" "nohup ${jvm_cmd} >/var/log/aether-node.out 2>&1 </dev/null &" 2>&1) \
                    || { log_warn "restart_all_nodes: node-${i} JVM relaunch failed: ${start_out}"; failed=$((failed + 1)); continue; }
            else
                # Container mode: aggregate per-node `docker restart` failures.
                local restart_out
                restart_out=$(cloud_ssh "node-${i}" "docker restart aether-node" 2>&1) \
                    || { log_warn "restart_all_nodes: node-${i} restart failed: ${restart_out}"; failed=$((failed + 1)); }
            fi
        done
        if [ "$failed" -gt 0 ]; then
            log_fail "restart_all_nodes: ${failed}/${NODE_COUNT:-5} cloud nodes failed to restart"
            return 1
        fi
        return 0
    fi
    # Why: `docker start` on exited containers re-uses identical NodeIds / addresses,
    # which triggers a 5-way simultaneous QUIC handshake storm on boot. The storm
    # causes peerLinks to flap, consensus messages to drop (QuicClusterNetwork.broadcast
    # only sends to peers in peerLinks at that instant), and Rabia proposals to starve.
    # `docker-compose down -v && up -d` performs an orchestrated tear-down that clears
    # peerLinks before restart — avoids the double-initiate race and gives clean boot.
    local prefix="${CLUSTER_NAME:-aether-b-node-}"
    local compose="${COMPOSE_FILE:-${SCRIPT_DIR:-/tmp}/docker-compose-b.yml}"
    # Surface stderr from the remote compose tear-down/up. Prior form swallowed it via
    # `2>/dev/null`, hiding a real bug where compose would fail (network in use, CTM
    # container port collision, etc) and leave containers running from the previous
    # broken state — yet the test harness believed it had reset the cluster and
    # interpreted subsequent "no leader elected" as a Rabia convergence failure rather
    # than the real cause: the compose cycle never ran.
    local restart_out restart_rc
    if [ -f "$compose" ] && [ "${prefix}" = "aether-b-node-" ]; then
        restart_out=$(remote_exec "docker rm -f \$(docker ps -a -q --filter name=aether-core) 2>/dev/null || true; cd ~ && docker compose -f docker-compose-b.yml down -v && docker compose -f docker-compose-b.yml up -d" 2>&1)
        restart_rc=$?
    else
        # Fallback for non-standard cluster names — best-effort start of exited containers.
        restart_out=$(remote_exec "docker rm -f \$(docker ps -a -q --filter name=aether-core) 2>/dev/null; docker ps -a --filter 'name=${prefix}' --filter 'status=exited' -q | xargs -r docker start" 2>&1)
        restart_rc=$?
    fi
    if [ "$restart_rc" -ne 0 ]; then
        log_fail "restart_all_nodes: compose cycle returned rc=${restart_rc}. Output: ${restart_out}"
        return 1
    fi
    # Rotate entry point — the previous pinned node may have been killed during the suite.
    rotate_mgmt_entry_point 2>/dev/null || true
    # Strict recovery assertions — no more log_warn pass-through.
    # SLA budget post-Wave-3: cold compose cycle + 5x JVM boot + QUIC mesh + leader + ON_DUTY
    # is ~60-90s on remote infra; bump to 120s for headroom (was 60s — observed timeouts).
    if ! wait_for_node_count "${NODE_COUNT:-5}" 120; then
        log_fail "restart_all_nodes: cluster failed to reach ${NODE_COUNT:-5} nodes within 120s"
        return 1
    fi
    if ! wait_for_leader 120; then
        log_fail "restart_all_nodes: no leader elected within 120s after container restart — cluster did not recover from the destructive scenario"
        return 1
    fi
    # Final quiescence barrier — cluster has nodes + leader; confirm snapshot converged.
    if ! await_generation_quiesced "${CLUSTER_ENDPOINT}" "current" 90; then
        log_fail "restart_all_nodes: cluster leader and node count recovered but generation did not quiesce within 90s"
        return 1
    fi
    # Per-node readiness — guarantees the next test passes its first poll regardless
    # of which port run-tests.sh re-pins MGMT_ENTRY_POINT to in its fresh subshell.
    if ! wait_for_all_nodes_ready 90; then
        log_fail "restart_all_nodes: not all nodes reported /health/ready=UP within 90s — next test would hit a half-warm node"
        return 1
    fi
    # SWIM cold-boot guard (non-fatal): `phase=BOOTING` causes
    # `SwimProtocol.emitFaultyOrUnknown` to emit `UnknownObserved` (NOT
    # `FaultyObserved`) for any peer not yet in `everSeenHealthy`, suppressing
    # NODE_LEFT/NODE_FAILED events. We try to wait for NORMAL but do NOT fail-close
    # — the cluster's NORMAL transition can take >180s under cluster A+B concurrent
    # load (5s stable window + leader-side aggregation cycles) and a hard fail
    # cascades into broken cleanup state for every subsequent test. Subsequent chaos
    # tests will still detect their own NORMAL phase via the cluster's behaviour.
    if ! wait_for_phase "NORMAL" 180; then
        log_warn "restart_all_nodes: cluster did not reach phase=NORMAL within 180s — chaos kills in next test may produce UnknownObserved (no NODE_FAILED event); proceeding with warn"
        # If NORMAL didn't arrive, CTM may be circuit-tripped from prior provisioning
        # failures. Root cause is slot-deadline timing: CTM's provisioning slot uses a
        # 60s wallclock timeout that on remote runs can expire before CTM observes the
        # replacement's actual Rabia/SWIM join, even though the join itself succeeds
        # (PEERS is a seed list, not an allowlist; QuicClusterNetwork#handleHello
        # accepts unknown peers and TopologyObserver#addNode is open from gossip).
        # After 3 such expiries the breaker trips and handleDeficit halts. Operator-
        # triggered reset bypasses the auto-recovery triggers (which require the NORMAL
        # transition that didn't happen).
        reset_provisioning_circuit || true
    fi
    log_info "restart_all_nodes: cluster recovered (${NODE_COUNT:-5} nodes, leader elected, generation quiesced, all nodes ready)"
    return 0
}

kill_node() {
    local node_id="$1"
    # Defensive guard: if a suite explicitly set MGMT_ENTRY_POINT_NODE (escape
    # hatch for cloud env where the mgmt-gateway sidecar isn't deployed yet),
    # refuse to kill that node -- otherwise the suite's own pinning request
    # would be silently violated. In normal docker/remote runs the mgmt-gateway
    # sidecar owns the entry-point port so mgmt_entry_point_node() returns
    # empty and any core (including the leader) is a valid victim.
    local pinned
    pinned=$(mgmt_entry_point_node)
    if [ -n "$pinned" ] && [ "$node_id" = "$pinned" ]; then
        log_fail "kill_node: refusing to kill explicitly pinned MGMT entry-point node '${node_id}' (MGMT_ENTRY_POINT_NODE='${MGMT_ENTRY_POINT_NODE:-}'). Unset the override or pick a different victim."
        return 1
    fi
    log_info "Killing node: ${node_id}"
    if [ "$CLOUD_MODE" = "true" ]; then
        # Two cloud modes are supported (run-tests.sh exports CLOUD_RUNTIME):
        #   container — VM runs a single `aether-node` Docker container
        #   jvm       — VM runs `java -jar /opt/aether/aether-node.jar ...` directly
        # Without this dispatch, JVM-mode VMs (which have no Docker installed by
        # cloud-init's appendJvmInstall path) hit `bash: docker: command not found`,
        # the kill never happens, and wait_for_node_departure times out at 60s.
        if [ "${CLOUD_RUNTIME:-container}" = "jvm" ]; then
            # Capture the running command line on the local test runner (NOT on the
            # VM — the VM-side process is about to die) so start_node can replay it.
            # cloud-init launched the JVM with `nohup java -jar ... &` (see
            # UserDataTemplate::appendJvmRun). `ps -o command= -C java` prints the
            # full argv of any java process; head -1 picks the aether-node JVM
            # (cloud nodes run only one java process per VM).
            local cmd_file="/tmp/aether-jvm-cmd-${node_id}.txt"
            local jvm_cmd
            jvm_cmd=$(cloud_ssh "$node_id" "ps -o command= -C java | grep -F 'aether-node.jar' | head -1" 2>&1)
            local cap_rc=$?
            if [ $cap_rc -ne 0 ] || [ -z "$jvm_cmd" ]; then
                log_warn "kill_node: could not capture JVM cmdline for '${node_id}' (rc=${cap_rc}): ${jvm_cmd}. start_node will fail to relaunch."
                : > "$cmd_file"
            else
                printf '%s\n' "$jvm_cmd" > "$cmd_file"
            fi
            local kill_out
            # SIGTERM (default) gives the JVM ~5s to drain SWIM/QUIC; if the test
            # needs hard-kill semantics (matches SIGKILL Docker behavior), peers
            # still detect via SWIM timeout. pkill returns 1 when no process matched,
            # which is a real failure (we expected a running JVM) — surface it.
            kill_out=$(cloud_ssh "$node_id" "pkill -KILL -f 'aether-node.jar'" 2>&1)
            local kill_rc=$?
            if [ $kill_rc -ne 0 ]; then
                log_fail "kill_node: cloud JVM kill of '${node_id}' failed (rc=${kill_rc}): ${kill_out}"
                return $kill_rc
            fi
        else
            # Container mode: each VM runs a single container named "aether-node".
            # The container was launched with `--restart unless-stopped` (older cloud-
            # init template) or `--restart no` (current). For the unless-stopped case
            # a plain `docker kill` is auto-restarted within ~2s, faster than SWIM's
            # failure-detection threshold — peers never observe the gap, no
            # NODE_LEFT/NODE_FAILED events are emitted, and tests waiting for those
            # events time out. Disable the restart policy first so the kill is
            # authoritative; start_node re-enables. Stderr is captured (NOT discarded
            # — silent stderr is a trap) so a docker permission-denied or SSH failure
            # aborts the test loudly instead of producing the previous symptom:
            # container survives, cluster sees no failure, NODE_FAILED events never
            # appear, test fails with no signal of what went wrong.
            local kill_out
            kill_out=$(cloud_ssh "$node_id" "set -e; docker update --restart=no aether-node >/dev/null; docker kill aether-node" 2>&1)
            local kill_rc=$?
            if [ $kill_rc -ne 0 ]; then
                log_fail "kill_node: cloud kill of '${node_id}' failed (rc=${kill_rc}): ${kill_out}"
                return $kill_rc
            fi
        fi
    else
        local name
        name=$(_docker_container_name "$node_id")
        log_info "  (container=${name})"
        local out
        out=$(remote_exec "docker kill ${name} 2>&1" 2>&1)
        if [ -n "$out" ] && ! echo "$out" | grep -q "^${name}$"; then
            log_warn "kill_node: docker kill ${name} output: ${out}"
        fi
    fi
}

start_node() {
    local node_id="$1"
    log_info "Starting node: ${node_id}"
    if [ "$CLOUD_MODE" = "true" ]; then
        if [ "${CLOUD_RUNTIME:-container}" = "jvm" ]; then
            # Replay the cmdline captured by kill_node on the local test runner.
            # The /tmp/aether-jvm-cmd-${node_id}.txt side-channel survives VM-side
            # process death, so even if the VM had been hard-rebooted between kill
            # and start the runner-side file is still authoritative.
            local cmd_file="/tmp/aether-jvm-cmd-${node_id}.txt"
            if [ ! -s "$cmd_file" ]; then
                log_fail "start_node: cannot relaunch JVM on '${node_id}' — ${cmd_file} is missing or empty (kill_node failed to capture cmdline). Cluster will be permanently short by one node."
                return 1
            fi
            local jvm_cmd
            jvm_cmd=$(cat "$cmd_file")
            # Re-execute under nohup with stdout/stderr redirected so SSH can return
            # promptly. The JVM is daemonized — same end state as cloud-init's
            # original `nohup ... &`. The remote shell quoting wraps the captured
            # command verbatim; the cmdline does not contain single quotes (cloud-init
            # uses `--key=value` form), so single-quote wrapping is safe.
            local start_out
            start_out=$(cloud_ssh "$node_id" "nohup ${jvm_cmd} >/var/log/aether-node.out 2>&1 </dev/null &" 2>&1)
            local start_rc=$?
            if [ $start_rc -ne 0 ]; then
                log_fail "start_node: cloud JVM relaunch of '${node_id}' failed (rc=${start_rc}): ${start_out}"
                return $start_rc
            fi
        else
            # Container mode: re-enable the restart policy that kill_node disabled,
            # then start. Same reasoning as kill_node: capture stderr so a docker /
            # SSH failure surfaces in the test output instead of leaving the
            # container stopped and a downstream "wait for 5 nodes" timing out
            # cryptically.
            local start_out
            start_out=$(cloud_ssh "$node_id" "set -e; docker update --restart=unless-stopped aether-node >/dev/null; docker start aether-node" 2>&1)
            local start_rc=$?
            if [ $start_rc -ne 0 ]; then
                log_fail "start_node: cloud start of '${node_id}' failed (rc=${start_rc}): ${start_out}"
                return $start_rc
            fi
        fi
    else
        local name
        name=$(_docker_container_name "$node_id")
        # Capture stderr — `2>/dev/null` previously hid failures (container not found,
        # docker daemon error, race with rm). Caller checks $? for success/failure.
        local start_out
        start_out=$(remote_exec "docker start ${name}" 2>&1) \
            || { log_warn "start_node: docker start ${name} failed: ${start_out}"; return 1; }
    fi
}

drain_node() {
    local node_id="$1"
    log_info "Draining node: ${node_id}"
    api_post "/api/node/drain" "{\"nodeId\":\"${node_id}\"}"
}

activate_node() {
    local node_id="$1"
    log_info "Activating node: ${node_id}"
    api_post "/api/node/activate" "{\"nodeId\":\"${node_id}\"}"
}

shutdown_node() {
    local node_id="$1"
    log_info "Shutting down node: ${node_id}"
    api_post "/api/node/shutdown" "{\"nodeId\":\"${node_id}\"}"
}

get_node_lifecycle() {
    api_get "/api/nodes/lifecycle"
}

drain_node() {
    local node_id="$1"
    api_post "/api/node/drain/${node_id}" "{}"
}

activate_node() {
    local node_id="$1"
    api_post "/api/node/activate/${node_id}" "{}"
}

# ---------------------------------------------------------------------------
# Scaling
# ---------------------------------------------------------------------------

# Seed cluster config into KV-Store if not already present.
# Required before scale operations — the scale API reads ClusterConfigValue from KV-Store.
seed_cluster_config() {
    local config_file="${1:-${LIB_DIR}/../cluster-config.toml}"
    local status
    status=$(http_status "${CLUSTER_ENDPOINT}/api/cluster/config" \
        -H "X-API-Key: ${API_KEY}")
    if [ "$status" = "200" ]; then
        log_info "Cluster config already present"
        return 0
    fi
    log_info "Seeding cluster config from ${config_file}"
    local toml_content
    toml_content=$(cat "$config_file")
    local json_body
    local escaped_toml
    escaped_toml=$(escape_json "$toml_content")
    json_body="{\"tomlContent\":\"${escaped_toml}\",\"expectedVersion\":0}"
    # Must hit the leader — CTM only runs on leader
    leader_api_post "/api/cluster/config" "$json_body"
}

# Operator-triggered reset of the CTM provisioning circuit breaker. Calls the
# leader-routed POST /api/cluster/topology/circuit-breaker/reset; the server
# returns the prior consecutive-failure count (audit log). Use between
# disruptive tests when restart_all_nodes did not converge to phase=NORMAL
# (i.e., CTM may be circuit-tripped from prior provisioning failures).
# Hard 15s timeout — call is a single KV-side reset, not a provisioning op.
reset_provisioning_circuit() {
    local result rc
    result=$(curl -sk -m 15 -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
                  -d '{}' "${CLUSTER_ENDPOINT}/api/cluster/topology/circuit-breaker/reset" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "reset_provisioning_circuit: POST failed rc=${rc}: $(printf '%s' "$result" | head -c 200)"
        return 1
    fi
    log_info "reset_provisioning_circuit: ${result}"
    return 0
}

# Operator-controlled toggle of CTM auto-heal (deficit-driven replacement
# provisioning). Distinct from the failure-driven circuit breaker — operators
# disable auto-heal during disruption-budget testing, planned maintenance
# windows, or any scenario where the cluster should not automatically rebuild
# after node loss. All three helpers below hit the leader-routed
# /api/cluster/topology/auto-heal{,/enable,/disable} endpoints. Curl is used
# for parity with reset_provisioning_circuit (same shape, same 15s timeout) —
# the integration test harness does not wire the `aether` CLI binary into the
# cluster.sh helper layer.

# Disable CTM auto-heal. Returns 0 on success, 1 on transport failure.
disable_auto_heal() {
    local result rc
    result=$(curl -sk -m 15 -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
                  -d '{}' "${CLUSTER_ENDPOINT}/api/cluster/topology/auto-heal/disable" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "disable_auto_heal: POST failed rc=${rc}: $(printf '%s' "$result" | head -c 200)"
        return 1
    fi
    log_info "disable_auto_heal: ${result}"
    return 0
}

# Enable CTM auto-heal. Returns 0 on success, 1 on transport failure.
enable_auto_heal() {
    local result rc
    result=$(curl -sk -m 15 -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
                  -d '{}' "${CLUSTER_ENDPOINT}/api/cluster/topology/auto-heal/enable" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "enable_auto_heal: POST failed rc=${rc}: $(printf '%s' "$result" | head -c 200)"
        return 1
    fi
    log_info "enable_auto_heal: ${result}"
    return 0
}

# Print the current auto-heal enabled state ("true"/"false") on stdout.
# Returns 0 if the endpoint responded with a parseable boolean, 1 otherwise.
# Designed for use in test predicates like:
#   if [ "$(auto_heal_enabled)" = "false" ]; then ...
auto_heal_enabled() {
    local result rc value
    result=$(curl -sk -m 15 -H "X-API-Key: ${API_KEY}" \
                  "${CLUSTER_ENDPOINT}/api/cluster/topology/auto-heal" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "auto_heal_enabled: GET failed rc=${rc}: $(printf '%s' "$result" | head -c 200)" >&2
        return 1
    fi
    # Parse "enabled":true|false from the JSON body.
    value=$(printf '%s' "$result" | grep -oE '"enabled"[[:space:]]*:[[:space:]]*(true|false)' | grep -oE '(true|false)' | head -1)
    if [ -z "$value" ]; then
        log_warn "auto_heal_enabled: could not parse enabled field from response: $(printf '%s' "$result" | head -c 200)" >&2
        return 1
    fi
    printf '%s' "$value"
    return 0
}

scale_cluster() {
    local target="$1"
    local leader
    leader=$(cluster_leader)
    log_info "Scaling cluster to ${target} nodes (leader: ${leader})" >&2
    # Must hit the leader — CTM.setDesiredSize() only activates on leader.
    # Direct timed POST. 90s budget (raised from 30s 2026-05-11): on cluster B
    # downstream of 02-chaos the consensus put for ClusterConfig can take 15-60s
    # under elevated quorum latency (per investigation 2026-05-11 — Seed step 14s
    # + scaled-in-config Put). 30s rejected legitimate slow-but-eventual-success
    # calls as test failures; 90s lets them complete while still bounding genuinely
    # stuck states (no leader / partition / consensus deadlock).
    local endpoint url result rc
    if [ "$CLOUD_MODE" = "true" ]; then
        local leader_ip
        leader_ip=$(cloud_node_ip "$leader" 2>/dev/null || echo "")
        if [ -z "$leader_ip" ]; then
            log_warn "scale_cluster: cannot resolve cloud IP for leader '${leader}'; falling back to MGMT entry point"
            endpoint="${CLUSTER_ENDPOINT}"
        else
            endpoint="http://${leader_ip}:8080"
        fi
        url="${endpoint}/api/cluster/scale"
        result=$(cloud_ssh "$leader" "curl -sk -m 90 -X POST -H 'X-API-Key: ${API_KEY}' -H 'Content-Type: application/json' -d '{\"coreCount\":${target},\"expectedVersion\":0}' http://localhost:8080/api/cluster/scale" 2>&1)
        rc=$?
    else
        url="${CLUSTER_ENDPOINT}/api/cluster/scale"
        result=$(curl -sk -m 90 -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
                      -d "{\"coreCount\":${target},\"expectedVersion\":0}" "$url" 2>&1)
        rc=$?
    fi
    if [ "$rc" -ne 0 ]; then
        log_warn "scale_cluster: POST /api/cluster/scale rc=${rc} (likely 90s timeout — cluster degraded; CTM circuit breaker may be tripped). Output: $(printf '%s' "$result" | head -c 300)"
        return 1
    fi
    log_info "Scale result: ${result}" >&2
}

# POST to the leader node — finds leader via CLI, targets its management port
leader_api_post() {
    # Targets the consensus leader directly via its management port. CTM (cluster
    # topology manager) is leader-bound, so /api/cluster/scale must reach the leader
    # for auto-provisioning to actually run.
    local path="$1"
    local body="${2:-"{}"}"
    if [ "$CLOUD_MODE" = "true" ]; then
        # Cloud: SSH-tunnel to the leader via bastion
        local leader
        leader=$(cluster_leader)
        if [ -z "$leader" ] || [ "$leader" = "none" ]; then
            log_warn "No leader available, falling back to api_post" >&2
            api_post "$path" "$body"
            return
        fi
        local leader_ip
        leader_ip=$(cloud_node_ip "$leader")
        # Use SSH tunnel for the request. Capture stderr so SSH transport errors and
        # curl error bodies surface — `2>/dev/null` previously discarded both, leaving
        # callers staring at empty stdout with no clue whether SSH timed out, the
        # leader 401'd, or the path 404'd.
        local ssh_out ssh_rc
        ssh_out=$(cloud_ssh "$leader" "curl -sk -X POST -H 'X-API-Key: ${API_KEY}' -H 'Content-Type: application/json' -d '${body}' http://localhost:8080${path}" 2>&1)
        ssh_rc=$?
        if [ "$ssh_rc" -ne 0 ]; then
            log_warn "leader_post: SSH to leader '${leader}' failed (rc=${ssh_rc}): ${ssh_out}"
            return "$ssh_rc"
        fi
        printf '%s' "$ssh_out"
        return 0
    fi
    local leader
    leader=$(cluster_leader)
    if [ -z "$leader" ] || [ "$leader" = "none" ]; then
        log_warn "No leader available, falling back to direct_api_post" >&2
        direct_api_post "$path" "$body"
        return
    fi
    # Resolve leader to a host-visible port only if it's a fixture compose node
    # (node-1..5). CTM-provisioned replacements carry ids like `aether-core-node-0-XXX`
    # and are only reachable on the Docker overlay network — we cannot dial them from
    # the test host, so we hit the compose endpoint and let the server-side route
    # forward to the consensus leader internally.
    if [[ "$leader" =~ ^node-([0-9]+)$ ]]; then
        local node_num="${BASH_REMATCH[1]}"
        local port=$((MGMT_PORT + node_num - 1))
        curl -sfk -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
            -d "$body" "http://${TARGET_HOST}:${port}${path}"
        return
    fi
    log_info "Leader '${leader}' is not host-exposed; dispatching via cluster endpoint for internal forwarding" >&2
    direct_api_post "$path" "$body"
}

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
config_apply() {
    local body="$1"
    log_info "Applying config"
    api_post "/api/config" "$body"
}

config_export() {
    aether_json config
}

config_get_key() {
    local key="$1"
    api_get "/api/config/${key}"
}

# ---------------------------------------------------------------------------
# Schema
# ---------------------------------------------------------------------------
schema_status() {
    local datasource="${1:-}"
    if [ -n "$datasource" ]; then
        api_get "/api/schema/status/${datasource}"
    else
        api_get "/api/schema/status"
    fi
}

schema_migrate() {
    local datasource="$1"
    api_post "/api/schema/migrate/${datasource}" "{}"
}

schema_retry() {
    local datasource="$1"
    api_post "/api/schema/retry/${datasource}" "{}"
}

schema_history() {
    local datasource="$1"
    api_get "/api/schema/history/${datasource}"
}

schema_baseline() {
    local datasource="$1"
    api_post "/api/schema/baseline/${datasource}" "{}"
}

schema_undo() {
    local datasource="$1"
    api_post "/api/schema/undo/${datasource}" "{}"
}

# ---------------------------------------------------------------------------
# Streams
# ---------------------------------------------------------------------------
stream_list() {
    aether_json streams 2>/dev/null || api_get "/api/streams"
}

stream_info() {
    local name="$1"
    api_get "/api/streams/${name}"
}

stream_publish() {
    local name="$1" body="$2"
    api_post "/api/streams/publish/${name}" "$body"
}

# ---------------------------------------------------------------------------
# Task Delegation
# ---------------------------------------------------------------------------
cluster_tasks() {
    api_get "/api/cluster/tasks"
}

task_assignment_count() {
    local tasks
    tasks=$(cluster_tasks)
    # Count `"group":"<name>"` value-pairs, not bare `"group"` tokens. The prior
    # `grep -o '"group"' | wc -l` over-counted: any nested object with a `"group"`
    # field (error responses, audit entries, future schema additions) inflated the
    # count. Requiring a string value ensures we only match assignment records.
    printf '%s' "$tasks" | grep -oE '"group"[[:space:]]*:[[:space:]]*"[^"]+"' | wc -l | tr -d ' '
}

task_group_status() {
    local group="$1"
    local tasks
    tasks=$(cluster_tasks)
    # Extract status for the matching group from JSON
    printf '%s' "$tasks" | grep -o "\"group\"[[:space:]]*:[[:space:]]*\"${group}\"[^}]*\"status\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | grep -o '"status"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"status"[[:space:]]*:[[:space:]]*"//' | sed 's/"$//' || echo "UNASSIGNED"
}

task_group_node() {
    local group="$1"
    local tasks
    tasks=$(cluster_tasks)
    printf '%s' "$tasks" | grep -o "\"group\"[[:space:]]*:[[:space:]]*\"${group}\"[^}]*\"assignedTo\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | grep -o '"assignedTo"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"assignedTo"[[:space:]]*:[[:space:]]*"//' | sed 's/"$//'
}

reassign_task_group() {
    # TaskAssignmentCoordinator is leader-bound, so we must hit the leader directly.
    local group="$1" target="$2"
    local leader leader_url
    leader=$(cluster_leader)
    if [ -z "$leader" ] || [ "$leader" = "none" ]; then
        log_warn "No leader available for reassign" >&2
        return 1
    fi
    if [ "$ENV_TYPE" = "cloud" ]; then
        local leader_ip
        leader_ip=$(cloud_public_ip "$leader") || return 1
        # Cloud uses fixed mgmt port 8080 on each VM (no per-node offset). The exported
        # MGMT_PORT is docker-specific (5150 / 5160 host-mapped), unusable on cloud.
        leader_url="http://${leader_ip}:${CLOUD_MGMT_PORT:-8080}"
    else
        local node_num
        node_num=$(echo "$leader" | sed 's/node-//')
        local port=$((MGMT_PORT + node_num - 1))
        leader_url="http://${TARGET_HOST}:${port}"
    fi
    # Use _api_call so HTTP errors (NOT_LEADER, INVALID_NODE, etc.) surface as warnings
    # on stderr instead of being silently swallowed by curl -sf.
    _api_call PUT "${leader_url}/api/cluster/tasks/reassign/${group}" "{\"targetNode\":\"${target}\"}"
}

wait_for_all_tasks_active() {
    local timeout="${1:-60}"
    local min_active="${2:-5}"
    # Use `-1` sentinel on parse error instead of `|| echo 0`. Previously a json parse
    # failure produced "0", and `[ 0 -ge ${min_active} ]` was simply false — the predicate
    # behaved the same as "0 active so far", so a broken cluster looked like "warming up"
    # and ate the whole timeout. With `-1`, the predicate `[ -1 -ge N ]` is false for any
    # positive N (same outcome), but the sentinel is now distinguishable in diagnostic
    # output if the predicate is ever instrumented.
    wait_for "all task groups ACTIVE" \
        "[ \$(json_count_matching \"\$(cluster_tasks)\" assignments status ACTIVE 2>/dev/null || echo -1) -ge ${min_active} ]" \
        "$timeout"
}

wait_for_task_active() {
    local group="$1" timeout="${2:-30}"
    wait_for "task group ${group} ACTIVE" \
        "[ \"\$(task_group_status ${group})\" = 'ACTIVE' ]" \
        "$timeout"
}

# Predicate that requires both an exact node assignment AND ACTIVE status. Use
# this after `reassign_task_group target` to avoid the stale-ACTIVE race where
# `wait_for_task_active` returns immediately on the prior ACTIVE entry before
# consensus has propagated the new assignment, leading the test to read the old
# `task_group_node` value.
wait_for_task_assigned() {
    local group="$1" target="$2" timeout="${3:-30}"
    wait_for "task group ${group} ACTIVE on ${target}" \
        "[ \"\$(task_group_node ${group})\" = '${target}' ] && [ \"\$(task_group_status ${group})\" = 'ACTIVE' ]" \
        "$timeout"
}

# ---------------------------------------------------------------------------
# Docker container helpers on target host
# ---------------------------------------------------------------------------
list_aether_containers() {
    remote_exec "docker ps --filter 'name=aether-' --format '{{.Names}}'"
}

container_running() {
    local name="$1"
    # Two-stage: docker reports "running" AND the JVM responds to /health/live.
    # Previously checked docker-only — a JVM that started, OOM'd, and is in restart-loop
    # would still report `status=running` for the brief seconds the container is up,
    # making the helper unreliable as a "node is operational" signal. The /health/live
    # probe (port 8080 + offset by node id) is the canonical liveness signal.
    remote_exec "docker ps --filter 'name=${name}' --filter 'status=running' -q" 2>/dev/null | grep -q . || return 1
    local offset port
    offset=$(printf '%s' "$name" | grep -oE '[0-9]+$' | head -1)
    if [ -z "$offset" ]; then
        # Cannot derive port from name — fall back to docker-only check (already passed).
        return 0
    fi
    port=$((MGMT_PORT + offset - 1))
    curl -sfk -m 2 -H "X-API-Key: ${API_KEY:-}" "http://${TARGET_HOST}:${port}/health/live" >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# Deployment operations (unified)
# ---------------------------------------------------------------------------
deploy_start() {
    local coords="$1" strategy="$2"; shift 2
    log_info "Starting ${strategy} deployment: ${coords}" >&2
    # Compose the strategy body that DeployCommand would build (extra args ignored — CLI
    # only passes them through HTTP body, the bash test layer just composes JSON itself).
    local strategy_upper instances=2 traffic=10 manual=false
    case "$strategy" in
        blue-green) strategy_upper="BLUE_GREEN" ;;
        canary) strategy_upper="CANARY" ;;
        rolling) strategy_upper="ROLLING" ;;
        *) strategy_upper=$(echo "$strategy" | tr '[:lower:]' '[:upper:]') ;;
    esac
    while [ $# -gt 0 ]; do
        case "$1" in
            --instances) instances="$2"; shift 2 ;;
            --traffic) traffic="$2"; shift 2 ;;
            --manual-approval) manual=true; shift ;;
            *) shift ;;
        esac
    done
    local strategy_body
    case "$strategy_upper" in
        BLUE_GREEN)
            strategy_body="\"blueGreen\":{\"drainTimeoutMs\":30000}" ;;
        CANARY)
            strategy_body="\"canary\":{\"stages\":[{\"trafficPercent\":${traffic},\"observationMinutes\":10}]}" ;;
        ROLLING)
            strategy_body="\"rolling\":{\"requireManualApproval\":${manual}}" ;;
    esac
    local body="{\"blueprint\":\"${coords}\",\"strategy\":\"${strategy_upper}\",\"instances\":${instances},${strategy_body},\"thresholds\":{\"maxErrorRate\":0.1,\"maxLatencyMs\":1000}}"
    api_post "/api/deploy" "$body"
}

deploy_list() {
    api_get "/api/deploy"
}

deploy_status() {
    local deployment_id="$1"
    api_get "/api/deploy/${deployment_id}"
}

deploy_promote() {
    local deployment_id="$1"
    log_info "Promoting deployment: ${deployment_id}" >&2
    api_post "/api/deploy/promote/${deployment_id}" "{}"
}

deploy_rollback() {
    local deployment_id="$1"
    log_info "Rolling back deployment: ${deployment_id}" >&2
    api_post "/api/deploy/rollback/${deployment_id}" "{}"
}

deploy_complete() {
    local deployment_id="$1"
    log_info "Completing deployment: ${deployment_id}" >&2
    api_post "/api/deploy/complete/${deployment_id}" "{}"
}

deploy_cleanup() {
    # Complete or rollback any active deployments via the LB management endpoint.
    local deployments
    deployments=$(deploy_list 2>/dev/null)
    # Extract deployment IDs that are not in terminal states
    printf '%s' "$deployments" | grep -o '"deploymentId"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"deploymentId"[[:space:]]*:[[:space:]]*"//' | sed 's/"$//' | while read -r did; do
        # Skip if in terminal state (check the surrounding context)
        if printf '%s' "$deployments" | grep -q "\"deploymentId\"[[:space:]]*:[[:space:]]*\"${did}\"[^}]*\"state\"[[:space:]]*:[[:space:]]*\"COMPLETED\""; then continue; fi
        if printf '%s' "$deployments" | grep -q "\"deploymentId\"[[:space:]]*:[[:space:]]*\"${did}\"[^}]*\"state\"[[:space:]]*:[[:space:]]*\"ROLLED_BACK\""; then continue; fi
        if printf '%s' "$deployments" | grep -q "\"deploymentId\"[[:space:]]*:[[:space:]]*\"${did}\"[^}]*\"state\"[[:space:]]*:[[:space:]]*\"FAILED\""; then continue; fi
        echo "$did"
    done | while read -r did; do
        # Cleanup: prefer ROLLBACK first (restores to baseline) then complete as last
        # resort. The previous order (complete first) caused 06-deployment test cascade:
        # test-deploy-canary's cleanup completed 1.0.1 → 1.0.1 became active → next
        # test (test-deploy-rolling) tried to deploy 1.0.1 again and got 500 "already
        # active". Rolling back leaves 1.0.0 active and each test starts from a known
        # baseline. Capture stderr so a stuck deployment surfaces.
        local complete_err rollback_err
        rollback_err=$(deploy_rollback "$did" 2>&1 >/dev/null) && continue
        complete_err=$(deploy_complete "$did" 2>&1 >/dev/null) && continue
        log_warn "deploy_cleanup: deployment ${did} stuck — rollback failed (${rollback_err}); complete failed (${complete_err})"
    done
    sleep 1
    return 0
}

# Extract deployment ID from the most recent entry in deploy list
deploy_extract_id() {
    local deployments="$1"
    json_value "$deployments" "deploymentId"
}

# ---------------------------------------------------------------------------
# Endpoint-scoped topology waits (still useful for initial cluster bring-up,
# where await_generation_quiesced may have no snapshot to compare against yet).
# ---------------------------------------------------------------------------

# Wait for specific node count on a given endpoint
wait_for_node_count_on() {
    local endpoint="$1"
    local expected="$2"
    local timeout="${3:-120}"

    # Default empty/missing to "-1" so the integer compare `[ N -ge expected ]` is always
    # syntactically valid. Previously used `|| echo -1` but `json_value` returns rc=0 with
    # EMPTY OUTPUT when the field is absent (early bootstrap before topology is published),
    # so `||` never fired and `[ -ge 5 ]` was a "unary operator expected" syntax error
    # that wait_for's prior `2>&1` mask hid as "predicate false" — silently looping until
    # timeout even on healthy clusters that just hadn't yet emitted the field.
    wait_for "${expected} nodes on ${endpoint}" \
        "v=\$(json_value \"\$(curl -sfk -H 'X-API-Key: ${API_KEY}' ${endpoint}/api/cluster/topology 2>/dev/null)\" coreCount 2>/dev/null); [ \"\${v:--1}\" -ge ${expected} ]" \
        "$timeout"
}

# Wait for leader election on a given endpoint
wait_for_leader_on() {
    local endpoint="$1"
    local timeout="${2:-30}"

    # Check `leaderId` field on /api/status — the prior `role:ACTIVE` check matched any
    # topology entry with `role=ACTIVE` (a per-node attribute), so the predicate was
    # satisfied whenever ANY node reported its own role as ACTIVE, NOT when a leader
    # was elected. `/api/status` returns `cluster.leaderId` populated from the elected
    # consensus leader (`ClusterConfigRoutes` is the model). The grep targets a
    # quoted non-empty value, so `"leaderId":null` and `"leaderId":""` both correctly
    # fail the predicate; `"leaderId":"node-3"` passes.
    wait_for "leader elected on ${endpoint}" \
        "curl -sfk -H 'X-API-Key: ${API_KEY}' ${endpoint}/api/status 2>/dev/null | grep -qE '\"leaderId\"[[:space:]]*:[[:space:]]*\"[^\"]+\"'" \
        "$timeout"
}
