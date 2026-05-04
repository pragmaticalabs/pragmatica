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
# On cluster B (destructive, `restart: "no"` policy) node-1 is the stable CLI
# entry point — its host-mapped management port is what `run-tests.sh` re-pins
# `MGMT_ENTRY_POINT` to in every fresh suite subshell. If we kill node-1 and
# the container doesn't restart, every subsequent cluster-B suite times out
# its `wait_for_cluster` gate because there is no live mgmt endpoint at the
# pinned port.
#
# Selection order:
#   1. Explicit env override `MGMT_ENTRY_POINT_NODE` (per-suite escape hatch).
#   2. Cluster B default: node-1.
#   3. Cluster A or unspecified: empty (no pinning constraint).
mgmt_entry_point_node() {
    if [ -n "${MGMT_ENTRY_POINT_NODE:-}" ]; then
        printf '%s' "$MGMT_ENTRY_POINT_NODE"
        return 0
    fi
    if [ "${CLUSTER_ID:-}" = "b" ]; then
        printf 'node-1'
        return 0
    fi
    printf ''
}

# Pick a non-leader node ID from the known set (integration-test-1..5).
# Excludes BOTH the leader AND the pinned MGMT entry-point node (cluster B
# only — cluster A has no pinning constraint). Fails loudly if no candidate
# remains rather than silently returning the entry point — a kill of the
# entry point on cluster B's `restart: "no"` policy turns one suite failure
# into five (every subsequent cluster-B suite times out its mgmt gate).
pick_non_leader() {
    local leader="$1"
    local count="${2:-1}"
    local pinned
    pinned=$(mgmt_entry_point_node)
    local found=0
    for i in 1 2 3 4 5; do
        local candidate="node-$i"
        if [ "$candidate" = "$leader" ]; then
            continue
        fi
        if [ -n "$pinned" ] && [ "$candidate" = "$pinned" ]; then
            continue
        fi
        echo "$candidate"
        found=$((found + 1))
        if [ "$found" -ge "$count" ]; then
            return 0
        fi
    done
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
            body=$(curl -sf -m 2 -H "X-API-Key: ${API_KEY}" \
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
            diag=$(curl -sf -m 2 -H "X-API-Key: ${API_KEY}" \
                        "http://${TARGET_HOST}:${port}/health/ready" 2>&1 \
                        || echo '<no response or non-2xx>')
            log_warn "wait_for_all_nodes_ready: port=${port} body=${diag}"
        done
        return 1
    fi
    return 0
}

# Rotate MGMT_ENTRY_POINT to any surviving core node reachable on ports MGMT_PORT..MGMT_PORT+NODE_COUNT-1.
# Chaos tests that kill the current entry point call this AFTER the kill to restore CLI access.
# Normal tests don't need this — they rely on the pinned entry point + product forwarding.
rotate_mgmt_entry_point() {
    local base_port="${MGMT_PORT}"
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        local port=$((base_port + i))
        local endpoint="http://${TARGET_HOST}:${port}"
        if curl -sf -m 2 -H "X-API-Key: ${API_KEY}" "${endpoint}/health/live" >/dev/null 2>&1; then
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
    [ "$status" = "UP" ] || [ "$status" = "healthy" ]
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
    if [ -n "$LB_MGMT_ENDPOINT" ] && ! curl -sf -m 3 -H "X-API-Key: ${API_KEY}" "${LB_MGMT_ENDPOINT}/health/live" >/dev/null 2>&1; then
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
        "curl -sf ${endpoint}/health/live >/dev/null 2>&1" \
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
        "[ \$(json_value \"\$(curl -sf -H 'X-API-Key: ${API_KEY}' http://${TARGET_HOST}:${MGMT_PORT}/api/health 2>/dev/null)\" connectedPeers 2>/dev/null || echo 0) -ge 2 ]" \
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
    local deadline=$(($(date +%s) + timeout))
    local last_count="?"
    log_info "Waiting for: ${expected} nodes (timeout: ${timeout}s, fast poll)"
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local endpoint=""
        local base_port="${MGMT_PORT}"
        for i in $(seq 0 $((NODE_COUNT - 1))); do
            local port=$((base_port + i))
            local candidate="http://${TARGET_HOST}:${port}"
            if curl -sf -m 1 -H "X-API-Key: ${API_KEY}" "${candidate}/health/live" >/dev/null 2>&1; then
                endpoint="${candidate}"
                break
            fi
        done
        if [ -n "$endpoint" ]; then
            local gen
            gen=$(curl -sf -m 2 -H "X-API-Key: ${API_KEY}" \
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
                body=$(curl -sf -m 2 -H "X-API-Key: ${API_KEY}" \
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
    count=$(printf '%s' "$slices" | grep -o '"state"[[:space:]]*:[[:space:]]*"[LA][CO][AT][DI][EV][DE]*"' | wc -l | tr -d ' ')
    echo "${count:-0}"
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
    api_post "/api/blueprint/publish" "{\"artifact\":\"${artifact}\"}"
}

deploy_blueprint_file() {
    local filepath="$1"
    log_info "Deploying blueprint file: ${filepath}" >&2
    local content
    content=$(cat "$filepath")
    curl -sf -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/toml" \
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
    local node_id="$1"
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
        for i in $(seq 1 "${NODE_COUNT:-5}"); do
            cloud_ssh "node-${i}" "docker restart aether-node" 2>/dev/null || true
        done
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
    log_info "restart_all_nodes: cluster recovered (${NODE_COUNT:-5} nodes, leader elected, generation quiesced, all nodes ready)"
    return 0
}

kill_node() {
    local node_id="$1"
    # Pinned-entry-point guard: cluster B's compose file uses `restart: "no"` so
    # a killed container does not come back; killing the node bound to the
    # pinned MGMT host port permanently strands every subsequent suite (each
    # opens a fresh subshell and re-pins MGMT_ENTRY_POINT to the same dead
    # port). Refuse the kill — the caller should pick a different victim or
    # rotate the entry point first.
    local pinned
    pinned=$(mgmt_entry_point_node)
    if [ -n "$pinned" ] && [ "$node_id" = "$pinned" ]; then
        log_fail "kill_node: refusing to kill pinned MGMT entry-point node '${node_id}' on cluster ${CLUSTER_ID:-<none>} (restart policy 'no' would leave subsequent suites without a mgmt endpoint). Rotate MGMT_ENTRY_POINT_NODE or pick a different victim."
        return 1
    fi
    log_info "Killing node: ${node_id}"
    if [ "$CLOUD_MODE" = "true" ]; then
        # Cloud: each VM runs a single container named "aether-node"
        cloud_ssh "$node_id" "docker kill aether-node" 2>/dev/null
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
        cloud_ssh "$node_id" "docker start aether-node" 2>/dev/null
    else
        local name
        name=$(_docker_container_name "$node_id")
        remote_exec "docker start ${name}" 2>/dev/null
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

scale_cluster() {
    local target="$1"
    local leader
    leader=$(cluster_leader)
    log_info "Scaling cluster to ${target} nodes (leader: ${leader})" >&2
    # Must hit the leader — CTM.setDesiredSize() only activates on leader
    local result
    result=$(leader_api_post "/api/cluster/scale" "{\"coreCount\":${target},\"expectedVersion\":0}")
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
        # Use SSH tunnel for the request
        cloud_ssh "$leader" "curl -sf -X POST -H 'X-API-Key: ${API_KEY}' -H 'Content-Type: application/json' -d '${body}' http://localhost:8080${path}" 2>/dev/null
        return
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
        curl -sf -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
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
    printf '%s' "$tasks" | grep -o '"group"' | wc -l | tr -d ' '
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
        leader_url="http://${leader_ip}:${MGMT_PORT}"
    else
        local node_num
        node_num=$(echo "$leader" | sed 's/node-//')
        local port=$((MGMT_PORT + node_num - 1))
        leader_url="http://${TARGET_HOST}:${port}"
    fi
    curl -sf -X PUT -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
        -d "{\"targetNode\":\"${target}\"}" \
        "${leader_url}/api/cluster/tasks/reassign/${group}"
}

wait_for_all_tasks_active() {
    local timeout="${1:-60}"
    local min_active="${2:-5}"
    wait_for "all task groups ACTIVE" \
        "[ \$(json_count_matching \"\$(cluster_tasks)\" assignments status ACTIVE 2>/dev/null || echo 0) -ge ${min_active} ]" \
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
    remote_exec "docker ps --filter 'name=${name}' --filter 'status=running' -q" 2>/dev/null | grep -q .
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
        deploy_complete "$did" > /dev/null 2>&1 || \
        deploy_rollback "$did" > /dev/null 2>&1 || true
    done
    sleep 1 || true
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

    wait_for "${expected} nodes on ${endpoint}" \
        "[ \$(json_value \"\$(curl -sf -H 'X-API-Key: ${API_KEY}' ${endpoint}/api/cluster/topology 2>/dev/null)\" coreCount 2>/dev/null || echo 0) -ge ${expected} ]" \
        "$timeout"
}

# Wait for leader election on a given endpoint
wait_for_leader_on() {
    local endpoint="$1"
    local timeout="${2:-30}"

    wait_for "leader elected on ${endpoint}" \
        "json_contains \"\$(curl -sf -H 'X-API-Key: ${API_KEY}' ${endpoint}/api/cluster/topology 2>/dev/null)\" role ACTIVE" \
        "$timeout"
}
