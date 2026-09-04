#!/bin/bash
# cluster.sh — Cluster lifecycle operations for Aether integration tests

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LIB_DIR}/common.sh"
source "${LIB_DIR}/generation.sh"

# ---------------------------------------------------------------------------
# Cluster queries (CLI-based)
# ---------------------------------------------------------------------------
# cluster_member_count — generation snapshot member count (includes JOINING).
# See aether/docs/specs/test-readiness-contract.md §2.1.
# cluster_member_count — generation snapshot member count (includes JOINING).
# See aether/docs/specs/test-readiness-contract.md §2.1.
#
# Thin wrapper over _cluster_member_count_checked() preserving the legacy
# "always succeeds, defaults to 0" contract for this function's ~40 existing
# bare-assignment callers (`count=$(cluster_member_count)` under `set -e`
# would abort the caller's script if this ever returned non-zero). Callers
# that need to tell "genuinely 0" from "read failed" should call
# _cluster_member_count_checked directly (see wait_for_node_count,
# wait_for_sustained_node_count — #441 item 3).
cluster_member_count() {
    _cluster_member_count_checked || echo 0
}

# Honest-read companion (#441 item 3): distinguishes "the cluster genuinely
# has 0 members" from "the transport/API read failed" — run-10/run-11's
# forensic root cause was cluster_member_count()'s legacy contract collapsing
# both to "0", which downstream polls read as "count is low" even when the
# read itself is what failed (a healthy 5-node cluster measured as 0 during
# hcloud/API contention). Prints nothing and returns 1 ONLY when BOTH the
# generation snapshot AND the topology fallback reads fail outright;
# otherwise this is byte-identical to the original cluster_member_count body
# (same member-count / desiredSize / coreCount precedence), including the
# case where a snapshot legitimately reports 0 members.
_cluster_member_count_checked() {
    # Query core node count via the generation snapshot rather than the topology
    # endpoint. `/api/v1/cluster/topology` `coreCount` is filtered to ON_DUTY+HEALTHY
    # members only, so during a CTM scale-up the freshly-provisioned overlay-only
    # node (no host port mapping) lags arbitrarily — first as JOINING then while
    # SWIM reaches HEALTHY. The test host can't reach the new node directly, so
    # it never sees `coreCount` reflect the actual cluster size during the
    # convergence window.
    #
    # `/api/v1/cluster/generation` exposes the authoritative snapshot member set:
    #   - `core.members[]`   — every member admitted to the snapshot, including
    #                          JOINING / non-HEALTHY ones (CTM has placed them
    #                          in the cluster manifest)
    #   - `core.desiredSize` — the configured target after the most recent
    #                          committed scale request
    #
    # Primary signal is `members.length` (ground truth: what the cluster
    # considers its membership). `desiredSize` is consulted ONLY when the
    # snapshot carries no members at all (cold boot, pre-publication). Falls
    # back to topology.coreCount only if the generation endpoint is unreachable / empty.
    local gen gen_ok="false"
    if gen=$(direct_api_get "/api/v1/cluster/generation" 2>/dev/null); then
        gen_ok="true"
    fi
    local desired members observed=0
    if [ "$gen_ok" = "true" ] && [ -n "$gen" ]; then
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
        # desiredSize is a CONFIG value, not an observation: it flips the instant
        # the scale config write lands, long before any node is provisioned. Taking
        # max(members, desired) here made scale tests "converge" on the config flip
        # with zero real provisioning (2026-06-11 Wave-9 gate: scale 5→7→5 inside
        # the 15s reconciler debounce never provisioned a node, yet the poll
        # reported 7). Consult desired ONLY as a cold-boot fallback when the
        # snapshot carries no members at all.
        if [ "$observed" -eq 0 ] && [ "$desired" -gt 0 ] 2>/dev/null; then
            observed="$desired"
        fi
    fi
    if [ "$observed" -gt 0 ] 2>/dev/null; then
        echo "$observed"
        return 0
    fi
    # Fallback: topology endpoint (legacy behaviour, used when generation
    # snapshot is unavailable — cold cluster pre-projection).
    local response response_ok="false" fallback
    if response=$(direct_api_get "/api/v1/cluster/topology" 2>/dev/null); then
        response_ok="true"
    fi
    if [ "$gen_ok" != "true" ] && [ "$response_ok" != "true" ]; then
        # Both reads genuinely failed (transport/API) — the count is UNKNOWN,
        # not zero. Empty stdout + rc 1 lets callers skip this iteration
        # instead of treating it as an observed low count.
        return 1
    fi
    # `json_value` can emit MULTIPLE lines when the degraded response repeats
    # the `coreCount` key (its sed `p` prints once per match), and emits empty
    # on a malformed/partial body. Either leaks into the caller's
    # `[ $(cluster_member_count) -eq N ]` predicate as multiple tokens / empty,
    # tripping `[: too many arguments` (rc=2). Coerce to a single integer here:
    # first line only, digits only, default 0 — so the value is always one int.
    fallback=$(json_value "$response" "coreCount" 2>/dev/null | head -1 | tr -cd '0-9')
    printf '%s\n' "${fallback:-0}"
    return 0
}

cluster_leader() {
    # Fail-fast: distinguish "no leader elected" (field=="none") from "API returned valid
    # data" (field==<NodeId>). Empty stdout + non-zero exit code lets callers branch on the
    # difference, rather than silently using "" as the leader and producing misleading
    # "(leader: )" log lines that mask cluster-down conditions.
    local result rc
    result=$(aether_field status cluster.leaderId 2>/dev/null)
    rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$result" ] || [ "$result" = "none" ]; then
        return 1
    fi
    printf '%s\n' "$result"
}

# Raw-HTTP (api_get) counterparts to cluster_leader / ready_core_count, for use by
# the cluster-B unrecoverability gate (run_cluster_b_suites). The CLI path
# (aether_failover) can be unavailable for reasons unrelated to cluster health — e.g.
# macOS Local Network Privacy blocking the java binary from a LAN-hosted cluster, where
# curl/api_get still work. A RELIABILITY gate must not false-trigger on CLI degradation,
# so it reads through the curl path (_resolve_live_endpoint) instead. These mirror the
# semantics of their CLI siblings but never shell out to `aether`.
cluster_leader_http() {
    local body
    body=$(api_get "/api/v1/nodes/status" 2>/dev/null) || return 1
    [ -z "$body" ] && return 1
    local leader
    leader=$(printf '%s' "$body" \
        | grep -oE '"leaderId"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | head -1 \
        | sed 's/.*"leaderId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
    if [ -z "$leader" ] || [ "$leader" = "none" ]; then
        return 1
    fi
    printf '%s\n' "$leader"
}

ready_core_count_http() {
    local body
    body=$(api_get "/api/v1/nodes/lifecycle" 2>/dev/null) || { echo 0; return 0; }
    [ -z "$body" ] && { echo 0; return 0; }
    printf '%s' "$body" \
        | grep -oE '"state"[[:space:]]*:[[:space:]]*"READY"' \
        | wc -l | tr -d ' '
}

# Single-shot count assertion: blocks until the leader publishes a snapshot at the
# current epoch (so KV writes that just landed are reflected), then reads count.
#
# Use after a state-changing action (scale_cluster, kill_node, etc.) when the test
# wants the FINAL count without polling. Without this, `cluster_member_count` may
# return the pre-action snapshot — the leader republishes the generation only
# after the action's membership churn settles.
#
# Args: optional timeout in seconds (default 30).
# Falls back to the plain `cluster_member_count` if the await endpoint is unreachable
# (cold cluster pre-projection) so the call is always safe.
cluster_node_count_quiesced() {
    local timeout="${1:-30}"
    local endpoint="${CLUSTER_ENDPOINT:-}"
    if [ -n "$endpoint" ]; then
        await_generation_quiesced "$endpoint" "current" "$timeout" >/dev/null 2>&1 || true
    fi
    cluster_member_count
}

# Spec §4.4 / §10 P7: tests must consume the same operator-visible signals.
# `clusterPhase` is published by HealthReconciler via consensus on ClusterPhaseKey
# and projected to every node. Empty/default → "COLD_BOOT".
cluster_phase() {
    aether_field status clusterPhase
}

# Count nodes whose derived membership state is ON_DUTY. H-series MembershipView
# derives ON_DUTY at read-time from SWIM health rather than persisting an explicit
# NodeLifecycleKey KV atom — so /api/v1/nodes/lifecycle no longer carries ON_DUTY entries.
# /api/v1/cluster/topology `coreCount` is the authoritative operator-visible count of
# cores that are ON_DUTY+HEALTHY (as noted in cluster_member_count comment above).
# Used by `restore_cluster_baseline` to assert the cluster has converged to N healthy
# cores AFTER CTM auto-heal, without requiring those cores to be the original five
# compose nodes (CTM replacements come up with fresh NodeIds).
#
# cluster_active_core_count — topology snapshot ON_DUTY+reachable core count.
# See aether/docs/specs/test-readiness-contract.md §2.2.
#
# Returns max(coreCount, coreNodes.length) from /api/v1/cluster/topology.
#
# Why max():
#   - `coreCount` is `reachableOnDutyCount(membershipView, snapshot, self)`: counts
#     view.onDutyPeers() filtered by reachability. STRICT — requires explicit
#     ON_DUTY lifecycle state in the KV-backed MembershipView.
#   - `coreNodes` array length is `allNodeIds | !passive ∧ healthy(topology) ∧
#     liveLifecycle(view)` (ClusterTopologyRoutes:151-153). LESS STRICT — accepts
#     any non-terminal lifecycle as long as topology hint is healthy.
#
#   In a stable cluster they agree. They diverge when a CTM auto-heal replacement
#   becomes leader after the original was killed: the replacement joined SWIM
#   late, never observed the survivors transitioning to ON_DUTY in KV, so
#   `view.onDutyPeers()` from its perspective contains only self (coreCount=1).
#   The survivors ARE healthy and ARE live (coreNodes lists all 4), the
#   MembershipView projection just hasn't caught up.
#
#   `restore_cluster_baseline`'s "N-1 healthy cores" hard barrier is an
#   operational invariant — coreNodes is the right signal. Falling back to
#   coreCount alone leaves the cleanup stuck for the full 1200s budget on every
#   suite that kills the leader (observed 2026-05-22 in 02-chaos after
#   test-kill-leader: coreCount=1 stable, coreNodes=4 stable, timeout-then-cascade).
# Count of cores currently REPORTING READY (NodeReportedState=READY), via the same
# server-side state filter pick_non_leader consumes: `aether nodes lifecycle --state
# READY --format json`. Counting from the identical source guarantees the readiness
# barrier in restore_cluster_baseline and the candidate enumeration in pick_non_leader
# agree on "how many READY nodes exist" — no presence-vs-READY skew between them.
# Prints a non-negative integer (0 on transport failure / empty body).
ready_core_count() {
    local payload
    payload=$(aether_failover nodes lifecycle --state READY --format json 2>/dev/null || true)
    [ -z "$payload" ] && { echo 0; return 0; }
    local n
    n=$(printf '%s' "$payload" \
        | grep -o '"nodeId"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | grep -c '"nodeId"' \
        || true)
    [ -z "$n" ] && n=0
    echo "$n"
}

cluster_active_core_count() {
    local topology core_count core_nodes_count
    topology=$(api_get "/api/v1/cluster/topology" 2>/dev/null || true)
    if [ -z "$topology" ]; then
        echo 0
        return 0
    fi
    core_count=$(printf '%s' "$topology" \
        | grep -o '"coreCount"[[:space:]]*:[[:space:]]*[0-9]*' \
        | head -1 \
        | grep -o '[0-9]*$' \
        || echo 0)
    # Count `coreNodes` array entries by counting quoted strings inside `"coreNodes":[...]`.
    # Single-line regex tolerates either Jackson layout (with/without spaces, with/without
    # following fields). Falls back to 0 if the field is absent or empty.
    core_nodes_count=$(printf '%s' "$topology" \
        | grep -oE '"coreNodes"[[:space:]]*:[[:space:]]*\[[^]]*\]' \
        | head -1 \
        | grep -oE '"[^"]+"' \
        | grep -cv '^"coreNodes"$' \
        || true)
    # Defensive: handle any non-numeric result (sed/grep failures on empty input).
    [ -z "$core_count" ] && core_count=0
    [ -z "$core_nodes_count" ] && core_nodes_count=0
    if [ "$core_nodes_count" -gt "$core_count" ] 2>/dev/null; then
        echo "$core_nodes_count"
    else
        echo "$core_count"
    fi
}

# Whether the cluster currently has quorum (leader committed AND ≥ ⌈N/2⌉+1 ON_DUTY nodes).
# Returns "true" or "false" (cluster.quorate field on StatusResponse).
cluster_quorate() {
    aether_field status cluster.quorate
}

# Per-node work-state as reported by the node itself (NodeReportedState, v2). One
# of: SYNCING, READY, DRAINING — or UNKNOWN if the node is untracked (not yet seen
# by SWIM-fed membership). There is no terminal lifecycle state: a removed node is
# simply ABSENT from membership.
node_lifecycle_state() {
    local target_node="$1"
    aether_json status 2>/dev/null \
        | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"'"$target_node"'"[^}]*"derivedStatus"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
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
# Source of truth: `aether nodes lifecycle --state READY --format json` — the
# server-side state filter returns the lifecycle entries already restricted to
# nodes reporting READY (NodeReportedState). Nodes that are SYNCING, DRAINING, or
# have left membership are dropped.
# Leader re-derivation still rides on `/api/v1/nodes/status` because lifecycle
# carries no leader identity — the caller must `wait_for_leader` before invoking
# us, and we additionally cross-check against `cluster.leaderId` from
# `/api/v1/nodes/status` to close the MGMT_ENTRY_POINT round-robin race the
# previous design called out (separate payloads now, but per-call atomicity is
# preserved within each fetch).
# Falls back to empty (fail-closed) if either call fails.
pick_non_leader() {
    local leader="$1"
    local count="${2:-1}"
    local pinned
    pinned=$(mgmt_entry_point_node)

    # Fail-fast: an empty or "none" leader argument means the caller hasn't actually
    # observed a stable leader. Returning a candidate here is dangerous — the very
    # node we pick might *be* the leader by the time the caller kills it (the
    # caller's `cluster_leader` call could have raced re-election). The caller
    # should `wait_for_leader` before invoking us.
    if [ -z "$leader" ] || [ "$leader" = "none" ]; then
        log_fail "pick_non_leader: refusing to pick — caller passed leader='${leader}' (call wait_for_leader first)" >&2
        return 1
    fi

    # Convergence-retry loop. A kill/heal in a PRIOR block can leave the cluster
    # mid-converge at the moment we're asked to pick: a just-killed node may still
    # report READY (not yet evicted from the SWIM-fed membership), and its ULID
    # replacement may not have joined yet. Rather than assert on that momentary
    # undercount, poll until `count` LIVE non-leader candidates exist (dead-but-READY
    # excluded by the docker-ps liveness guard) or a deadline passes. This makes
    # pick_non_leader WAIT for membership to converge; it only ever waits longer than
    # the old single-pass — never picks fewer, and never streams partial output on
    # failure (candidates are buffered and emitted only on full success).
    #
    # Override the wait with PICK_NON_LEADER_TIMEOUT (default 120s — raised from
    # 60s to ride out post-Kill_2 re-election READY flicker: after 5→3 survivors a
    # surviving non-leader can briefly drop out of --state READY during re-sync,
    # and leader + pinned-MGMT exclusion can transiently cap candidates).
    local deadline=$(( $(date +%s) + ${PICK_NON_LEADER_TIMEOUT:-120} ))
    local attempt_found=0
    local attempt_list=""
    local last_reason="no candidates yet"
    local last_diag="(no pass completed)"
    while :; do
        attempt_found=0
        attempt_list=""
        local pass_diag="endpoint=${MGMT_ENTRY_POINT}"

        # Re-derive leader from /api/v1/nodes/status each pass to close the
        # MGMT_ENTRY_POINT round-robin race: a fast re-election between the
        # caller's read and ours must not let us hand back the new leader as a
        # "non-leader" victim. `"leaderId":null` (empty parse) falls back to the
        # caller-supplied leader.
        local status_payload
        status_payload=$(api_get "/api/v1/nodes/status" 2>/dev/null || true)
        if [ -z "$status_payload" ]; then
            last_reason="/api/v1/nodes/status returned empty body"
            last_diag="${pass_diag} status_payload=EMPTY"
        else
            local derived_leader
            derived_leader=$(printf '%s' "$status_payload" \
                | grep -o '"leaderId"[[:space:]]*:[[:space:]]*"[^"]*"' \
                | head -1 \
                | sed 's/.*"leaderId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/' || true)
            if [ -n "$derived_leader" ] && [ "$derived_leader" != "none" ]; then
                leader="$derived_leader"
            fi

            # Candidate enumeration: server-side READY filter via the aether CLI.
            # The response is a JSON array of `{nodeId, state, updatedAt}` triplets,
            # all already READY post-filter — extract `nodeId` with grep+sed (no jq).
            # `aether_failover` is called directly (not `aether_json`) so the
            # `nodes lifecycle` parent+sub pair is passed as two distinct args.
            local lifecycle_payload
            lifecycle_payload=$(aether_failover nodes lifecycle --state READY --format json 2>/dev/null || true)
            if [ -z "$lifecycle_payload" ]; then
                last_reason="'nodes lifecycle --state READY' returned empty body"
                last_diag="${pass_diag} leader=${leader} lifecycle_payload=EMPTY"
            else
                local current_members
                current_members=$(printf '%s' "$lifecycle_payload" \
                    | grep -o '"nodeId"[[:space:]]*:[[:space:]]*"[^"]*"' \
                    | sed 's/"nodeId"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/' || true)

                # DIAG (pick_non_leader 1/N): raw READY set this endpoint returns, pre-filter.
                local raw_ready
                raw_ready=$(printf '%s' "$current_members" | grep -cve '^$' || true)
                pass_diag="${pass_diag} leader=${leader} pinned=${pinned:-none} rawReady=${raw_ready} readyList=[$(printf '%s' "$current_members" | grep -v '^$' | tr '\n' ',')]"

                local candidate
                # Read candidates on FD 3, NOT stdin: the docker-ps liveness guard below
                # calls remote_exec (ssh), and ssh reads/consumes stdin — on stdin this
                # silently eats the rest of the here-string after the FIRST candidate, so
                # the loop evaluated only 1 of N and pick_non_leader 2 always returned 1/2
                # regardless of how many READY non-leaders existed. FD 3 isolates the loop
                # input from ssh's stdin. (Confirmed via DIAG: rawReady=4 but 1 evaluated.)
                while IFS= read -r candidate <&3; do
                    [ -z "$candidate" ] && continue
                    if [ "$candidate" = "$leader" ]; then pass_diag="${pass_diag} ${candidate}=EXCL-leader"; continue; fi
                    if [ -n "$pinned" ] && [ "$candidate" = "$pinned" ]; then pass_diag="${pass_diag} ${candidate}=EXCL-pinned"; continue; fi
                    # Caller-scoped exclusion: PICK_EXCLUDE holds whitespace-separated
                    # NodeIds the caller needs to stay alive (e.g. the slice owner its
                    # load is pinned to via retarget_app_endpoint_to_active_slice — the
                    # harness has no LB, so killing the pinned owner measures the
                    # missing LB, not the product).
                    local _excl _excluded=""
                    for _excl in ${PICK_EXCLUDE:-}; do
                        if [ "$candidate" = "$_excl" ]; then _excluded=1; break; fi
                    done
                    if [ -n "$_excluded" ]; then pass_diag="${pass_diag} ${candidate}=EXCL-caller"; continue; fi
                    # Docker-mode liveness guard: lifecycle may still report a
                    # just-killed node as READY before membership evicts it.
                    # NodeId == container_name (post-migration); `docker ps` (no -a)
                    # returns running only — the "live candidate" assertion.
                    if [ "${CLOUD_MODE:-false}" != "true" ]; then
                        local _alive_name
                        _alive_name=$(remote_exec "docker ps --filter 'name=^${candidate}\$' --format '{{.Names}}' | head -1" 2>/dev/null || true)
                        if [ -z "$_alive_name" ]; then
                            pass_diag="${pass_diag} ${candidate}=DEAD-docker"
                            continue
                        fi
                    fi
                    pass_diag="${pass_diag} ${candidate}=LIVE"
                    attempt_list="${attempt_list}${candidate}"$'\n'
                    attempt_found=$((attempt_found + 1))
                    if [ "$attempt_found" -ge "$count" ]; then break; fi
                done 3<<< "$current_members"

                if [ "$attempt_found" -ge "$count" ]; then
                    printf '%s' "$attempt_list" | grep -v '^$' | head -n "$count"
                    return 0
                fi
                last_reason="only ${attempt_found}/${count} live non-leader candidates (leader=${leader})"
                last_diag="$pass_diag"
            fi
        fi

        if [ "$(date +%s)" -ge "$deadline" ]; then
            break
        fi
        sleep 2
    done

    # Deadline passed: a genuine non-convergence (not a transient), surfaced loudly.
    log_fail "pick_non_leader: ${last_reason} after ${PICK_NON_LEADER_TIMEOUT:-120}s waiting for convergence (pinned=${pinned:-<none>}, cluster=${CLUSTER_ID:-<none>})" >&2
    log_fail "pick_non_leader DIAG (last pass): ${last_diag}" >&2
    return 1
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
    # @deprecated alias — folded into wait_for_cluster_ready (per-node /health/ready
    # check is now item 4 of the canonical readiness contract).
    # See aether/docs/specs/test-readiness-contract.md §1.4.
    wait_for_cluster_ready "$@"
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
    # Short-circuit when the pinned endpoint still answers /health/live — the pinned
    # endpoint is a direct node port (post-nginx-removal), so this is the node itself
    # responding. If alive, no rotation needed.
    if curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" "${MGMT_ENTRY_POINT}/health/live" >/dev/null 2>&1; then
        return 0
    fi
    # Pinned endpoint is dead. Delegate to the SINGLE resolver: _refresh_mgmt_entry_point
    # wraps _resolve_live_endpoint (cloud per-VM scan / docker seed ports / label discovery
    # — the one place that logic lives) and exports MGMT_ENTRY_POINT + CLUSTER_ENDPOINT to
    # the resolved live endpoint. This replaces the cloud/docker/label scan that was
    # duplicated here.
    if _refresh_mgmt_entry_point; then
        log_info "Rotated MGMT_ENTRY_POINT to ${MGMT_ENTRY_POINT}" >&2
        return 0
    fi
    log_warn "rotate_mgmt_entry_point: no surviving core node reachable (cloud VMs / docker seeds / label discovery all exhausted)" >&2
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
    # Read health via the HTTP mgmt path (api_get → _resolve_live_endpoint), NOT the
    # CLI (`aether_field health status`). The CLI can land on a node that was just
    # drained/killed and raise a ConnectException (false negative — Quorum_preserved
    # regression), whereas api_get rotates to any live core. /api/v1/health returns
    # {"status":"healthy",...}; parse `status` with the same grep/sed idiom the other
    # *_http helpers use. Falls back to the CLI only if the HTTP path yields nothing
    # (keeps behavior on docker/remote where api_get and CLI agree).
    health=$(cluster_health_status_http)
    if [ -z "$health" ]; then
        health=$(aether_field health status)
    fi
    assert_eq "$health" "healthy" "$desc"
}

# Raw-HTTP health status — the `status` field of GET /api/v1/health ("healthy" when the
# node is ready + quorate). HTTP sibling of `aether_field health status`; rotates to
# a live core via api_get/_resolve_live_endpoint so a killed/drained pinned node does
# not produce a false negative. Prints the status string (empty on transport failure).
cluster_health_status_http() {
    local body
    body=$(api_get "/api/v1/health" 2>/dev/null) || return 0
    [ -z "$body" ] && return 0
    printf '%s' "$body" \
        | grep -oE '"status"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | head -1 \
        | sed 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/'
}

is_cluster_ready() {
    # Snapshot-only readiness predicate (no wait, no polling). Cites the canonical
    # contract: aether/docs/specs/test-readiness-contract.md §1.1.
    #
    # Delegates to the private composite _cluster_is_ready snapshot used inside
    # wait_for_cluster_ready. Kept as an alias for callers that intentionally
    # want a one-shot check (e.g., predicate consumed by another `wait_for`).
    _cluster_is_ready
}

# wait_for_cluster_ready [timeout] [expected_count] — canonical "cluster is ready" gate.
# Composite: generation members ≥ expected, leader elected, active cores ≥ expected-1.
# See aether/docs/specs/test-readiness-contract.md §1.
#
# **Default `expected = NODE_COUNT`** — strict full-size cluster, matching the pre-Wave 5
# canonical behavior. Property 3 (active core floor) uses `expected-1` to tolerate the
# RC2 MembershipView convergence lag while still requiring the cluster's self-reported
# member count to reach the configured size. Chaos suites that legitimately operate at
# N-1 (mid-recovery) can pass `NODE_COUNT-1` explicitly.
wait_for_cluster_ready() {
    local timeout="${1:-120}"
    local expected="${2:-${NODE_COUNT:-5}}"
    # Fast-path snapshot — if the cluster is already ready, skip the entire wait_for harness.
    # Saves the 2s wait_for interval on the happy path (which is most tests).
    if _cluster_is_ready "$expected" 2>/dev/null; then
        log_pass "cluster ready (${expected}+ members, canonical §1.1) (0s)"
        return 0
    fi
    # Fast-fail probe — if NO core in MGMT_PORT..MGMT_PORT+N-1 responds to /health/live,
    # the cluster is categorically unreachable and no amount of waiting will help. Bail
    # out in ~1s instead of waiting the full timeout. This caps the cascading slowdown
    # across downstream tests when one suite leaves the cluster in a broken state.
    #
    # The probe also refreshes MGMT_ENTRY_POINT/CLUSTER_ENDPOINT to a live core if the
    # pinned one is dead — required after chaos-suite kills where the entry-point host
    # port (e.g. 5161 for node-1) stays dead while CTM provisions a replacement at a
    # different port (5156+/5166+). Without this, subsequent suites probe the dead pin
    # and fast-fail even though the cluster is operationally healthy.
    if ! _refresh_mgmt_entry_point; then
        # Describe the probed address space accurately per env: docker/remote scan
        # the fixed seed host-port range on TARGET_HOST; cloud scans the per-VM
        # public IPs at the uniform CLOUD_MGMT_PORT (_resolve_live_endpoint handles
        # both). Reporting the docker port range on cloud was misleading — cloud
        # never probes those ports.
        local probed
        if [ "${ENV_TYPE:-docker}" = "cloud" ]; then
            probed="${NODE_COUNT} cloud VM(s) at port ${CLOUD_MGMT_PORT:-8080}"
        else
            probed="${MGMT_PORT}..$((MGMT_PORT + NODE_COUNT - 1)) on ${TARGET_HOST}"
        fi
        log_fail "cluster ready (${expected}+ members, canonical §1.1) — no live core in ${probed} responds to /health/live; cluster appears down, fast-failing"
        return 1
    fi
    # Slow path — cluster is reachable but not yet at full readiness; enter the polling loop.
    wait_for "cluster ready (${expected}+ members, canonical §1.1)" "_cluster_is_ready ${expected}" "$timeout"
}

# Composite snapshot predicate backing wait_for_cluster_ready / is_cluster_ready.
# Returns 0 when ALL three cluster-side properties hold simultaneously; returns 1 otherwise.
# See test-readiness-contract.md §1.1. Arg 1: expected node count (default NODE_COUNT).
#
# The contract is purely cluster-side (member count + leader + active core floor) — no
# per-node port probing. The per-node /health/ready iteration that an earlier revision
# tried to layer on top broke under CTM auto-heal: replacement nodes are provisioned at
# ports outside the configured MGMT_PORT..MGMT_PORT+N-1 range, so any test that ran after
# a chaos suite kill-then-recover saw "wrong port" failures even though the cluster was
# operationally healthy. The cluster's own self-view (generation + topology) is the
# canonical source of truth; tests that need per-node readiness verification can probe
# /health/ready directly with the appropriate port list.
_cluster_is_ready() {
    local expected="${1:-${NODE_COUNT:-5}}"
    # Check 1: generation snapshot members ≥ expected (strict-N when expected=NODE_COUNT,
    # survivor-floor when caller passes N-1).
    local count
    count=$(cluster_member_count)
    [ -n "$count" ] && [ "$count" -ge "$expected" ] 2>/dev/null || return 1

    # Check 2: leader elected (non-empty, non-"none").
    local leader
    leader=$(cluster_leader 2>/dev/null)
    [ -n "$leader" ] && [ "$leader" != "none" ] || return 1

    # Check 3: active core floor ≥ expected - 1 (tolerate one lagging FSM commit).
    local active floor
    active=$(cluster_active_core_count 2>/dev/null)
    floor=$(( expected - 1 ))
    [ -n "$active" ] && [ "$active" -ge "$floor" ] 2>/dev/null || return 1

    return 0
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
    # @deprecated alias — kept as a shim for any caller missed during the RC1
    # rename. New code MUST call wait_for_cluster_ready directly. See
    # aether/docs/specs/test-readiness-contract.md §1.4.
    wait_for_cluster_ready "$@"
}

# Wait for cluster using direct node access (before LB is available)
wait_for_cluster_direct() {
    # connected_peers_direct() coerces the /api/v1/health connectedPeers capture to a
    # single integer (default 0), so the predicate is always `[ <int> -ge 2 ]` and
    # can never word-split into "too many arguments" on a degraded/empty body.
    wait_for "cluster healthy (direct)" \
        '[ "$(connected_peers_direct)" -ge 2 ]' \
        "${1:-120}"
}

# Single-integer connectedPeers reading via direct node /api/v1/health. Always emits
# exactly one integer (first match, digits only, 0 when empty/malformed) so it is
# safe to embed unquoted-or-quoted in a numeric `[ ]` test.
connected_peers_direct() {
    local body n
    body=$(curl -sfk -H "X-API-Key: ${API_KEY}" "http://${TARGET_HOST}:${MGMT_PORT}/api/v1/health" 2>/dev/null || true)
    n=$(json_value "$body" connectedPeers 2>/dev/null | head -1 | tr -cd '0-9')
    printf '%s\n' "${n:-0}"
}

wait_for_node_count() {
    local expected="$1" timeout="${2:-120}"
    # _cluster_member_count_checked() (not cluster_member_count()) — #441 item
    # 3a: a failed read prints nothing, so the quoted substitution yields
    # `[ "" -eq N ]` (rc 2, "integer expression expected"), which wait_for's
    # own case statement already treats as "predicate emitted a shell error"
    # (log_warn + keep polling) rather than a real false — i.e. a failed read
    # SKIPS this iteration instead of being read as "count is 0, not yet
    # satisfied". A successful read behaves exactly as before.
    wait_for "${expected} nodes" "[ \"\$(_cluster_member_count_checked)\" -eq ${expected} ]" "$timeout"
}

# Cloud-aware default catch-window for CTM auto-heal, in seconds (pre-TIMEOUT_SCALE).
#
# Docker / remote replacement is a container spin-up (DockerComputeProvider /
# CTM) that joins in seconds-to-tens-of-seconds, so the historical 180s base is
# generous. A CLOUD source (`--env cloud`) replaces a killed node with a
# brand-new VM: measured on Hetzner at ~187s end-to-end (kill→VM-created ≈ +32s,
# VM→cluster-join ≈ +177s). The 180s base was a coin-flip away from timing out
# on a healthy heal even before VM-boot jitter, so the cloud base is raised to
# 300s. Both bases are still scaled by TIMEOUT_SCALE inside the wait loop
# (1 docker, 2 remote, 3 cloud), matching every other timeout in this lib.
#
# Override with AETHER_AUTOHEAL_TIMEOUT to pin an explicit base for either env.
autoheal_default_timeout() {
    if [ -n "${AETHER_AUTOHEAL_TIMEOUT:-}" ]; then
        printf '%s\n' "$AETHER_AUTOHEAL_TIMEOUT"
        return 0
    fi
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        printf '%s\n' 300
    else
        printf '%s\n' 180
    fi
}

# Cloud-aware default sustain window, in seconds (pre-TIMEOUT_SCALE).
#
# Auto-heal transiently flaps to the target count during convergence (the
# replacement joins the snapshot, then a stale member is still being reaped, or
# SWIM briefly double-counts), so a single instantaneous count==target reading
# is a FALSE PASS. The sustain window requires the count to hold >= target for
# this many consecutive seconds before the wait succeeds. Override with
# AETHER_AUTOHEAL_SUSTAIN.
autoheal_default_sustain() {
    if [ -n "${AETHER_AUTOHEAL_SUSTAIN:-}" ]; then
        printf '%s\n' "$AETHER_AUTOHEAL_SUSTAIN"
        return 0
    fi
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        printf '%s\n' 30
    else
        printf '%s\n' 15
    fi
}

# Wait until cluster_member_count reaches `expected` AND holds >= `expected` for
# a sustained window, guarding against a transient flap-to-target false pass.
#
# Args:
#   expected  — target member count (required)
#   timeout   — total deadline in seconds, pre-TIMEOUT_SCALE
#               (default: autoheal_default_timeout — cloud-aware)
#   sustain   — required consecutive seconds at >= expected, pre-TIMEOUT_SCALE
#               (default: 0 — preserves plain wait_for_node_count semantics for
#                any caller that wants reach-once behavior)
#
# Both timeout and sustain are scaled by TIMEOUT_SCALE here (mirroring wait_for /
# await_generation_quiesced), so this helper polls cluster_member_count directly
# rather than delegating to wait_for — that keeps the scale applied exactly once
# and lets the same loop track both the "first reached" and "held for N" phases.
#
# Returns 0 once the count has been >= expected continuously for the scaled
# sustain window; 1 if the scaled deadline elapses first. A sustain of 0 makes
# the first >= expected reading succeed immediately (identical to a reach-once
# wait), so existing call sites that omit it are unchanged.
wait_for_sustained_node_count() {
    local expected="$1"
    local base_timeout="${2:-$(autoheal_default_timeout)}"
    local base_sustain="${3:-0}"
    local scale="${TIMEOUT_SCALE:-1}"
    local timeout=$((base_timeout * scale))
    local sustain=$((base_sustain * scale))
    local deadline=$(($(date +%s) + timeout))
    local interval=2
    # Epoch at which the count first held >= expected; -1 means "not currently
    # at/above target" (resets on any dip below, so a flap restarts the window).
    local held_since=-1
    local count now elapsed_sustained
    log_info "Waiting for: ${expected} nodes sustained ${sustain}s (timeout: ${timeout}s)"
    while [ "$(date +%s)" -lt "$deadline" ]; do
        # #441 item 3b: a failed read (transport/API — both generation and
        # topology unreachable) must PAUSE the sustain window, not reset it —
        # run-11's forensic root cause was a single bad read resetting
        # held_since, so 900s of polling with occasional transport flaps never
        # accumulated 90 clean seconds even though the cluster was healthy the
        # whole time. Skip straight to the next poll without touching
        # held_since or comparing against `expected`; only a genuinely
        # observed count below target (the `elif` below) resets the window.
        if ! count=$(_cluster_member_count_checked); then
            log_info "Read failed (transport); pausing sustain window (not resetting)"
            sleep "$interval"
            continue
        fi
        if [ "$count" -ge "$expected" ] 2>/dev/null; then
            now=$(date +%s)
            if [ "$held_since" -lt 0 ]; then
                held_since="$now"
            fi
            elapsed_sustained=$((now - held_since))
            if [ "$elapsed_sustained" -ge "$sustain" ]; then
                log_pass "${expected} nodes sustained ${elapsed_sustained}s (count=${count})"
                return 0
            fi
        elif [ "$held_since" -ge 0 ]; then
            # Dipped below target before the sustain window completed — this is
            # exactly the transient flap that would have been a false pass under
            # a reach-once wait. Reset and keep waiting for a stable count.
            log_info "Count dipped to ${count} (< ${expected}) before sustaining; resetting stability window"
            held_since=-1
        fi
        sleep "$interval"
    done
    log_fail "wait_for_sustained_node_count: ${expected} nodes not sustained ${sustain}s within ${timeout}s (last count=${count:-?})"
    return 1
}

# Faster variant of wait_for_node_count for tight scaling polls (test-02/03 scale up/down).
# `cluster_member_count` round-trips through `_resolve_live_endpoint` (one curl probe) and
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
# count of `"nodeId"` occurrences in core.members[]) from /api/v1/cluster/generation,
# falling back to `coreCount` from /api/v1/cluster/topology if generation is empty.
# Mirrors `cluster_member_count` — see that helper for the full rationale; the short
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
        # Seed slots all dead (every compose seed replaced by a CTM ULID node on an
        # ephemeral host port) → fall back to the label-based discovery the general
        # resolver (_resolve_live_endpoint) already uses. Without this the fast poll
        # only ever sees the fixed seed ports (e.g. 5161..5165) and returns '?' once the
        # cluster has fully migrated to CTM replacements — the 03-scaling remote failure.
        # TTL-cached in _discover_endpoint_by_label, so the per-iteration SSH cost is
        # paid at most once per DISCOVER_TTL.
        if [ -z "$endpoint" ]; then
            endpoint=$(_discover_endpoint_by_label 2>/dev/null) || endpoint=""
        fi
        if [ -n "$endpoint" ]; then
            local gen
            gen=$(curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" \
                        "${endpoint}/api/v1/cluster/generation" 2>/dev/null) || gen=""
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
                # desiredSize is config, not observation (see cluster_member_count):
                # max(members, desired) made scale waits return on the config write
                # itself with zero real provisioning. Cold-boot fallback only.
                if [ "$observed" -eq 0 ] && [ "$desired" -gt 0 ] 2>/dev/null; then
                    observed="$desired"
                fi
            fi
            if [ "$observed" -eq 0 ] 2>/dev/null; then
                # Fallback to topology.coreCount for cold-boot cases where the
                # generation snapshot has not yet been projected to KV.
                local body
                body=$(curl -sfk -m 2 -H "X-API-Key: ${API_KEY}" \
                            "${endpoint}/api/v1/cluster/topology" 2>/dev/null) || body=""
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
    # Single-read predicate: capture cluster_leader once per iteration. The prior
    # form `[ -n "$(cluster_leader)" ] && [ "$(cluster_leader)" != 'none' ]` made
    # two gateway round-trips per probe; under round-robin MGMT_ENTRY_POINT the
    # two calls could land on different backends during a re-election and yield
    # inconsistent reads — non-deterministic pass/fail at the predicate level.
    wait_for "leader elected" 'lid=$(cluster_leader); [ -n "$lid" ] && [ "$lid" != "none" ]' "$timeout"
}

# wait_for_leader_change: wait until cluster_leader returns a NodeId different
# from `$1` (the prior leader), AND that view is stable across multiple
# consecutive reads. The nginx mgmt-gateway round-robins /api/* requests across
# all NODE_COUNT cores; under round-robin a single "lucky" backend reporting the
# new leader makes a one-shot predicate pass while sibling backends still echo
# the killed NodeId. The very next `cluster_leader` call from the test body
# then races back to a stale backend and reads the old leader, tripping the
# downstream assertion. Stability check: NODE_COUNT+1 consecutive reads must
# all agree on the same non-old leader (probes ≥ surviving-backend-count covers
# the round-robin cycle even when one slot is the dead leader's port).
# Observed 2026-05-22 on test-kill-leader: wait_for_leader_change returned in
# 0s on a one-shot predicate, immediate `cluster_leader` returned the killed
# NodeId from a gateway round-robin sibling.
wait_for_leader_change() {
    local old_leader="$1"
    local timeout="${2:-150}"
    local probes=$((${NODE_COUNT:-5} + 1))
    wait_for "leader changed from ${old_leader} (${probes}-stable)" \
        "_stable_leader_change_probe \"${old_leader}\" ${probes}" \
        "$timeout"
}

# Internal: 0 iff $probes consecutive cluster_leader reads agree on the same
# non-empty, non-"none", non-old-leader NodeId. Used by wait_for_leader_change.
_stable_leader_change_probe() {
    local old_leader="$1"
    local probes="$2"
    local first lid i
    first=$(cluster_leader 2>/dev/null) || return 1
    [ -z "$first" ] && return 1
    [ "$first" = "none" ] && return 1
    [ "$first" = "$old_leader" ] && return 1
    for i in $(seq 2 "$probes"); do
        lid=$(cluster_leader 2>/dev/null) || return 1
        [ "$lid" != "$first" ] && return 1
    done
    return 0
}

# Spec §4.5 / §10: a leader is "committed" once `LeaderKey` is observable in KV
# (i.e. `aether status` reports a non-empty leaderId). Operationally indistinguishable
# from `wait_for_leader` for the moment — kept as a separate helper so call sites
# document intent ("we need consensus-committed leader, not just a candidate").
wait_for_leader_committed() {
    local timeout="${1:-60}"
    # Single-read predicate — see wait_for_leader for rationale.
    wait_for "leader committed" \
        'lid=$(cluster_leader); [ -n "$lid" ] && [ "$lid" != "none" ]' \
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
    # Server-side multi-state filter (CLI: `aether slices --state LOADED+ACTIVE`); avoids
    # the prior raw-JSON `(LOADED|ACTIVE)` grep that was sensitive to whitespace and
    # would silently miss new states. Count `"state"` occurrences in the restricted
    # response body — the filter has already removed any non-LOADED/ACTIVE entries
    # server-side, so the inner grep just tallies what remains.
    local slices
    slices=$(aether_json slices --state "LOADED+ACTIVE")
    local count
    count=$(printf '%s' "$slices" | grep -oE '"state"[[:space:]]*:' | wc -l | tr -d ' ')
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

# Resolve the TARGET_HOST host port that maps to a given in-container port for a
# named container. NodeId == container_name post-migration, so this works for both
# compose seeds (fixed host ports) and CTM-provisioned ULID replacements (ephemeral
# host ports) — a ULID name ends in a letter and has no numeric slot, so the old
# `base + numeric-suffix` arithmetic produced an empty/garbage port for replacements.
#
# Mirrors _discover_endpoint_by_label (lib/common.sh): `docker port <name> <p>/tcp`
# prints `0.0.0.0:NNNNN` / `[::]:NNNNN` / `127.0.0.1:NNNNN` (port identical across
# address families — take the first line); fall back to inspect-based HostPort lookup
# when `docker port` emits nothing. Prints the host port on stdout, empty when the
# container has no host-published mapping for that in-container port (e.g. a ULID
# replacement publishes only mgmt 8080, never app 8070).
#
# Args: $1 — container name (NodeId); $2 — in-container port (e.g. 8070 app, 8080 mgmt)
host_port_for_container() {
    local name="$1" inport="$2"
    [ -z "$name" ] && return 1
    [ "${CLOUD_MODE:-false}" = "true" ] && return 1
    remote_exec "hp=\$(docker port \"${name}\" ${inport}/tcp 2>/dev/null | sed -n '1s/.*:\\([0-9][0-9]*\\)\$/\\1/p'); [ -z \"\$hp\" ] && hp=\$(docker inspect -f '{{range \$p := index .NetworkSettings.Ports \"${inport}/tcp\"}}{{\$p.HostPort}} {{end}}' \"${name}\" 2>/dev/null | tr ' ' '\\n' | grep -m1 '[0-9]'); printf '%s' \"\$hp\"" 2>/dev/null || true
}

# First (lowest-numbered) live compose-seed's host port that maps to in-container
# <inport>. Used as a retarget fallback when the slice owner is a ULID replacement
# with no host-published app port: at ACTIVE state the route has propagated cluster-
# wide, so any surviving seed serves it. Seeds are named aether-<cluster>-node-{1..N}
# (CLUSTER_NAME prefix, default `aether-b-node-`); returns the first with a mapping.
first_seed_host_app_port() {
    local inport="$1"
    [ "${CLOUD_MODE:-false}" = "true" ] && return 1
    local prefix="${CLUSTER_NAME:-aether-${CLUSTER_ID:-b}-node-}"
    local n hp
    for n in $(seq 1 "${NODE_COUNT:-5}"); do
        hp=$(host_port_for_container "${prefix}${n}" "$inport")
        if [ -n "$hp" ]; then
            printf '%s' "$hp"
            return 0
        fi
    done
    return 1
}

# Every RUNNING cluster-node container's host-mapped APP endpoint, one per line.
#
# Why this exists: `APP_ENDPOINT` is a single pinned host:port (the nginx LB, or one node).
# The LB PINS — measured 2026-08-13, all 42 failure bodies from one suite carried the SAME slice
# `"instance"` id — and a pinned NODE is simply dead once a chaos suite kills it. Both shapes
# produce the same signature: a healthy cluster that the harness cannot reach, reported as a
# product failure. `02y-stream-crash` published 0/40 events and `02w-entity-crash` never saw its
# slice answer, both against clusters that were fine.
#
# Enumerated DYNAMICALLY rather than derived as `<prefix><1..N>`: CTM-provisioned replacements are
# ULID-named (`aether-b-node-01KZZ…`) and DO publish an app port — on an ephemeral high port
# (36647) rather than the compose-fixed 8080..8084 range, which is why a name-pattern sweep misses
# exactly the nodes a chaos suite just created. If those own the partitions under test, every
# request is refused and no reachable endpoint can serve it.
#
# Newline-separated stdout rather than a global array: bash 3.2 (macOS) errors on `"${arr[@]}"`
# for an EMPTY array under `set -u`, so a string keeps callers free of that trap.
# Returns non-zero and prints nothing when no endpoint resolves.
node_app_endpoints() {
    local inport="${APP_PORT:-8070}"
    [ "${CLOUD_MODE:-false}" = "true" ] && return 1

    local prefix="${CLUSTER_NAME:-aether-${CLUSTER_ID:-b}-node-}"
    local names name hp out="" saved_ifs

    names=$(remote_exec "docker ps --filter name='${prefix}' --filter status=running --format '{{.Names}}'" 2>/dev/null | tr -d '\r')
    [ -z "$names" ] && return 1

    # NOT a `while read` loop, and the lookup's stdin is closed — both deliberate.
    # `host_port_for_container` -> `remote_exec` -> `ssh` with no `-n`, and ssh SLURPS STDIN.
    # Inside a stdin-fed loop it eats the remaining container names, so the loop runs exactly
    # ONCE: measured 2026-08-14, a healthy 5-node cluster reported "1 per-node app endpoint
    # resolved" and the suite degraded to the single-endpoint behaviour this helper exists to
    # replace. Iterating a newline-split list keeps the names out of stdin entirely; `</dev/null`
    # is the belt to that braces, and protects any future caller that reintroduces a read loop.
    saved_ifs="$IFS"
    IFS=$'\n'
    for name in $names; do
        [ -z "$name" ] && continue
        hp=$(host_port_for_container "$name" "$inport" </dev/null) || hp=""
        if [ -n "$hp" ]; then
            out="${out}http://${TARGET_HOST}:${hp}
"
        fi
    done
    IFS="$saved_ifs"

    [ -z "$out" ] && return 1
    printf '%s' "$out"
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
    local coords="$1" probe_path="${2:-}" probe_timeout="${3:-30}"
    # The app-HTTP port is a property of the cluster config, NOT a per-caller value.
    # The in-container/logical app port is APP_PORT (8070, lib/common.sh) on every
    # node in every env. Callers historically passed a literal `8080` here — the
    # docker cluster-B HOST mapping (host:8080 -> in-container 8070) — but on cloud
    # 8080 is the MANAGEMENT port, so the cloud branch pinned app load to mgmt and
    # every request returned 404 ("File not found: <app route>"). Resolving the port
    # authoritatively from APP_PORT eliminates that whole class: no caller can
    # re-target the mgmt port. (Docker resolves the host mapping of this in-container
    # port via host_port_for_container; cloud uses it as the logical port directly.)
    local app_port="${APP_PORT:-8070}"
    # Exported observation (global, NOT local): the owner NodeId the app load is
    # pinned to. The harness has no LB — callers that kill nodes while this load
    # runs must pass it via PICK_EXCLUDE to pick_non_leader, or the test measures
    # the missing LB instead of the product.
    RETARGETED_SLICE_OWNER=""
    # Identify a node that currently hosts an ACTIVE instance of the artifact so
    # we can probe / PUT against that node directly rather than racing route-table
    # propagation across the cluster.
    local owner
    owner=$(slice_owner_for "$coords" 2>/dev/null || true)
    if [ -z "$owner" ]; then
        # FACET-1: the blueprint may be transiently UNLOADING (mid-rebalance from a prior
        # suite's chaos / cross-suite churn) — briefly NO ACTIVE owner. Wait a bounded
        # window for (re)activation rather than immediately falling back to a stale
        # endpoint, which makes the load measure the missing LB not the product (the
        # cloud 100%-404 facet). Blueprint readiness is the TEST's concern (here), not the
        # generic restore_cluster_baseline gate (which gates only core wholeness).
        wait_for "ACTIVE owner for ${coords} (blueprint (re)activation)" \
            "[ -n \"\$(slice_owner_for '${coords}' 2>/dev/null)\" ]" "${probe_timeout}" >/dev/null 2>&1 || true
        owner=$(slice_owner_for "$coords" 2>/dev/null || true)
    fi
    if [ -z "$owner" ]; then
        # Diagnostic dump: surface the slice list so a future failure shows whether
        # /api/v1/slices is empty (deploy didn't propagate), all instances are still
        # LOADING (timing window — caller didn't await ACTIVE), or the artifact
        # prefix doesn't match (coords mismatch between blueprint and slice).
        local diag
        diag=$(cluster_slices 2>/dev/null \
                   | tr -d '\n' \
                   | grep -oE '"artifact"[[:space:]]*:[[:space:]]*"[^"]*"|"state"[[:space:]]*:[[:space:]]*"[A-Z_]*"' \
                   | tr '\n' ' ')
        # Fallback port (docker only): set APP_ENDPOINT to the first live seed's
        # host-mapped app port so that if the caller ignores this error, load hits a
        # real port rather than the dead default LB port (e.g. TARGET_HOST:9090).
        # On cloud there is no seed-port mapping so APP_ENDPOINT is left unchanged.
        if [ "${ENV_TYPE:-docker}" != "cloud" ]; then
            local _fallback_port
            _fallback_port=$(first_seed_host_app_port "${APP_PORT:-8070}" 2>/dev/null || true)
            if [ -n "$_fallback_port" ]; then
                APP_ENDPOINT="http://${TARGET_HOST}:${_fallback_port}"
            fi
        fi
        log_error "retarget: no ACTIVE owner found for ${coords} — /api/v1/slices: ${diag:-<empty>}; APP_ENDPOINT=${APP_ENDPOINT}"
        return 1
    fi
    RETARGETED_SLICE_OWNER="$owner"
    if [ "${ENV_TYPE:-docker}" = "cloud" ]; then
        # Cloud: each node has its own public IP at the same logical app port.
        local owner_ip
        owner_ip=$(cloud_public_ip "$owner" 2>/dev/null || true)
        if [ -z "$owner_ip" ]; then
            log_warn "retarget: cloud_public_ip(${owner}) returned empty; APP_ENDPOINT unchanged. (Owner reported by /api/v1/slices is not in bootstrap-state.json — node may have been replaced by CTM and not re-recorded.)"
            return 1
        fi
        APP_ENDPOINT="http://${owner_ip}:${app_port}"
    else
        # Docker/remote: resolve the owner's host-mapped app port by CONTAINER-NAME
        # lookup (NodeId == container_name post-migration). `docker port <name> 8070/tcp`
        # works uniformly for compose seeds (host-mapped 8070..8074 / 8080..8084) AND
        # CTM-provisioned ULID replacements — the old `port + numeric-suffix` derivation
        # returned EMPTY for a ULID-named owner (id ends in a letter), leaving APP_ENDPOINT
        # at the dead LB default and the load probing a dead port (false 100% error rate).
        # In-container app port is APP_PORT (8070) for every node (DockerConfig
        # DEFAULT_APP_PORT_BASE; compose maps `<host>:8070`). The host-side mapping
        # varies by cluster (A -> :8070, B -> :8080), which is exactly why the app
        # port must be resolved from the in-container APP_PORT, never a caller value.
        local owner_app_port
        owner_app_port=$(host_port_for_container "$owner" "$app_port")
        if [ -z "$owner_app_port" ]; then
            # ULID replacements publish only the mgmt port (8080), not 8070 — they carry
            # no host-mapped app port at all. At ACTIVE state the slice route has propagated
            # cluster-wide, so any surviving seed with a host-mapped app port serves it. Fall
            # back to the lowest-numbered live seed's host app port rather than guessing.
            owner_app_port=$(first_seed_host_app_port "$app_port")
            if [ -z "$owner_app_port" ]; then
                log_warn "retarget: owner '${owner}' has no host-mapped app port (8070) and no seed app port resolvable; APP_ENDPOINT unchanged"
                return 1
            fi
            log_info "retarget: owner ${owner} has no host app port (ULID replacement); routing via seed app port ${owner_app_port}"
        fi
        APP_ENDPOINT="http://${TARGET_HOST}:${owner_app_port}"
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
# whose `detail` field contains "No route found for " (title is "Not Found"). AppHttpServer also returns
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
            if printf '%s' "$body" | grep -q '"detail":"No route found for '; then
                return 1
            fi
            return 0
            ;;
        4*) return 0 ;;
        *) return 1 ;;
    esac
}

slices_active_instances() {
    # Use the server-side `?state=ACTIVE` filter (CLI: `aether slices --state ACTIVE`)
    # so the response already restricts instances[] to ACTIVE; a single state-marker
    # grep counts them. Equivalent in cardinality to the prior client-side grep
    # against the unfiltered list, but the filter is now an authoritative contract
    # (uppercase normalisation, instance-level filter) instead of a regex over the
    # raw JSON. See `aether/docs/reference/management-api.md#get-apislices`.
    local slices
    slices=$(aether_json slices --state ACTIVE)
    local count
    count=$(printf '%s' "$slices" | grep -o '"state"[[:space:]]*:[[:space:]]*"ACTIVE"' | wc -l | tr -d ' ')
    echo "${count:-0}"
}

# Assert that the currently ACTIVE slice routing is at the version embedded in a
# blueprint coordinate (group:artifact:VERSION). Used by 06-deployment strategy
# tests to verify the v1 baseline is genuinely ACTIVE before a v1→v2 strategy
# `deploy_start` — the strategy `/api/v1/deploy` path has no redeployment escape
# hatch, so re-deploying the already-active version returns SameVersionDeployment
# (HTTP 500) and the response carries no deploymentId. Reuses the same
# `aether_json slices --state ACTIVE` + version-grep contract already inlined in
# the blue-green rollback verification.
assert_active_version() {
    local coords="$1" desc="$2"
    local version="${coords##*:}"  # extract "1.0.0" from group:artifact:1.0.0
    local slices_json active_count
    slices_json=$(aether_json slices --state ACTIVE)
    active_count=$(printf '%s' "$slices_json" | grep -oc "\"version\"[[:space:]]*:[[:space:]]*\"${version}\"" || true)
    if [ "${active_count:-0}" -ge 1 ]; then
        log_pass "${desc} (${active_count} ACTIVE slice(s) at ${version})"
        return 0
    fi
    log_fail "${desc}: no ACTIVE slice at version ${version}. slices: $(printf '%s' "$slices_json" | head -c 500)"
    return 1
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

# slices_target_total_for <blueprint-coords> / slices_active_instances_for <coords>
# — blueprint-SCOPED counterparts to slices_target_total / slices_active_instances.
#
# WHY: the global pair sums targetInstances / counts ACTIVE across ALL deployed
# slices. test-self-drain S20 re-pushes ONLY the echo blueprint after a volume
# wipe, then asserts convergence — but if a sibling blueprint (e.g. persistence,
# 3 instances) is also present, the global slices_target_total returns echo(3) +
# persistence(3) = 6 and the assert false-fails at "5/6 active" even though the
# redeployed blueprint is fully ACTIVE. Scoping the assert to the blueprint that
# was actually redeployed kills that false fail.
#
# Both filter cluster_slices to the slice block whose artifact shares <coords>'s
# group:artifact prefix, using the SAME awk walk + ${coords%:*} prefix match as
# slice_owner_for (the blueprint coord org:test-echo:1.0.0 shares the
# org:test-echo prefix with the slice artifact org:test-echo-echo-slice:1.0.0).
# Read from the unfiltered cluster_slices (full response shape: slice-level
# artifact + targetInstances, nested instances[] with state) — the same source
# slice_owner_for trusts — so the awk sees every field it needs in one body.
slices_target_total_for() {
    local coords="$1"
    local prefix="${coords%:*}"
    cluster_slices \
        | awk -v prefix="$prefix" '
            BEGIN { in_match = 0; total = 0 }
            /"artifact"[[:space:]]*:[[:space:]]*"/ {
                line = $0
                sub(/.*"artifact"[[:space:]]*:[[:space:]]*"/, "", line)
                sub(/".*/, "", line)
                in_match = (index(line, prefix) == 1)
                next
            }
            in_match && /"targetInstances"[[:space:]]*:[[:space:]]*[0-9]+/ {
                line = $0
                sub(/.*"targetInstances"[[:space:]]*:[[:space:]]*/, "", line)
                sub(/[^0-9].*/, "", line)
                total += line + 0
            }
            END { print total }
        '
}

slices_active_instances_for() {
    local coords="$1"
    local prefix="${coords%:*}"
    cluster_slices \
        | awk -v prefix="$prefix" '
            BEGIN { in_match = 0; active = 0 }
            /"artifact"[[:space:]]*:[[:space:]]*"/ {
                line = $0
                sub(/.*"artifact"[[:space:]]*:[[:space:]]*"/, "", line)
                sub(/".*/, "", line)
                in_match = (index(line, prefix) == 1)
                next
            }
            in_match && /"state"[[:space:]]*:[[:space:]]*"ACTIVE"/ {
                active += 1
            }
            END { print active }
        '
}

push_blueprint() {
    # Push a blueprint artifact. The server-side PUT /repository/... endpoint is now
    # idempotent (RC1): both fresh uploads and duplicates return HTTP 200 with a JSON
    # body whose `status` field is "uploaded" or "already-present" — both count as
    # success. This helper:
    #   1. invokes `aether artifacts push --format json` so the CLI emits a
    #      machine-readable summary on stdout;
    #   2. validates the exit code (fail-closed on non-zero);
    #   3. parses the top-level `.status` field via jq to confirm the response shape
    #      and surface "mixed" / "already-present" outcomes in the test log;
    #   4. retries on transient leader-unavailable signals captured from stderr.
    # Returns 0 on either upload outcome, non-zero only on a real failure or an
    # unparseable response (defence in depth — a misbehaving server that emits a
    # 200 with a non-JSON body is treated as failure).
    local coords="$1"
    local attempts="${PUSH_BLUEPRINT_ATTEMPTS:-3}"
    local i=1
    while [ "$i" -le "$attempts" ]; do
        log_info "Pushing blueprint artifacts: ${coords} (attempt ${i}/${attempts})" >&2
        local errfile
        errfile=$(mktemp)
        local out err rc
        if out=$(aether_failover artifacts push --format json "$coords" 2>"$errfile"); then
            rc=0
        else
            rc=$?
        fi
        err=$(cat "$errfile" 2>/dev/null || echo "")
        rm -f "$errfile"
        if [ "$rc" -eq 0 ]; then
            # Parse the status field. Use jq when available; fall back to a grep
            # extractor (no external dep) so the integration suite still works on
            # minimal CI images. Either way, an unparseable response = failure.
            local status
            if command -v jq >/dev/null 2>&1; then
                status=$(printf '%s' "$out" | jq -r '.status // empty' 2>/dev/null || echo "")
            else
                status=$(printf '%s' "$out" | grep -oE '"status"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"status"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')
            fi
            case "$status" in
                uploaded|already-present|mixed)
                    log_info "push_blueprint ${coords}: status=${status}" >&2
                    printf '%s' "$out"
                    return 0
                    ;;
                "")
                    log_warn "push_blueprint ${coords}: CLI exited 0 but response had no parseable .status field. Body: $(printf '%s' "$out" | head -c 300)" >&2
                    return 1
                    ;;
                *)
                    log_warn "push_blueprint ${coords}: unexpected status='${status}'. Body: $(printf '%s' "$out" | head -c 300)" >&2
                    return 1
                    ;;
            esac
        fi
        # Transient leader / not-yet-ready errors → retry.
        if printf '%s%s' "$out" "$err" | grep -qiE 'NotLeader|leader unavailable|503|temporarily|timeout|connection refused'; then
            log_warn "push_blueprint ${coords}: transient error on attempt ${i}: $(printf '%s' "$err" | head -c 200)" >&2
            sleep 2
            i=$((i + 1))
            continue
        fi
        # Terminal failure: surface the stderr so the caller sees WHY the push failed.
        log_warn "push_blueprint ${coords}: failed with rc=${rc}: $(printf '%s' "$err" | head -c 300)" >&2
        return "$rc"
    done
    log_warn "push_blueprint ${coords}: exhausted ${attempts} attempts" >&2
    return 1
}

deploy_blueprint() {
    local artifact="$1"
    log_info "Deploying blueprint: ${artifact}" >&2
    # Single-shot: preceding await_generation_quiesced (in the test/runner) guarantees
    # the cluster is settled. Retries hid the actual race; the ClusterGeneration gate
    # replaces the compensation loop with a deterministic barrier.
    aether_failover blueprints deploy "$artifact" 2>/dev/null \
        || api_post "/api/v1/blueprints/deploy" "{\"artifact\":\"${artifact}\"}"
}

publish_blueprint() {
    # Registers a blueprint in the cluster registry without making it active.
    # Required when starting a strategy-based deploy upgrade — the upgrade target
    # version must be in the registry, but should NOT be the currently active
    # version (otherwise SameVersionDeployment is returned).
    local artifact="$1"
    log_info "Publishing blueprint (no instances): ${artifact}" >&2
    local result
    result=$(api_post "/api/v1/blueprints/publish" "{\"artifact\":\"${artifact}\"}")
    local rc=$?
    printf '%s' "$result"
    [ $rc -ne 0 ] && return $rc
    # KV-Store consensus commits at the leader but follower nodes apply asynchronously;
    # /api/v1/deploy may land on a STRATEGIES owner whose local state machine hasn't yet
    # processed the Put. Poll the cluster's blueprint list until the entry is visible
    # to absorb the propagation gap (5s budget × scaled).
    # Fail-closed: previously this wait was 5s + log_warn-and-continue, which let the
    # deploy proceed against a not-yet-propagated blueprint and 404'd downstream with a
    # confusing error. The visibility gate is load-bearing — if the blueprint doesn't
    # appear within the budget, the deploy WILL fail; surfacing it here gives the test
    # author a precise diagnostic instead of a downstream 404 chase.
    if ! wait_for "blueprint ${artifact} visible" \
            "api_get /api/v1/blueprints 2>/dev/null | grep -q \"${artifact}\"" \
            10 1; then
        log_fail "publish_blueprint: ${artifact} not visible in /api/v1/blueprints after publish (propagation gap)"
        return 1
    fi
    return 0
}

# Publish a blueprint and ABORT the calling test if the publish is REFUSED (#566 acceptance 4).
#
# `publish_blueprint` already returns non-zero on an HTTP error, but that return is easy to drop:
# `run_test` deliberately invokes each test function as `if "$fn"; then` so a `set -e` abort inside
# a test cannot skip `cleanup()` and leave the cluster degraded for the rest of the suite. A very
# useful property — with the side effect that `set -e` is masked for the WHOLE test body, so an
# unchecked non-zero return from any helper is silently ignored and execution continues.
#
# That is how a refused publish became an unrelated-looking failure two steps later: a 409
# datasource-ownership conflict on `POST /api/v1/blueprints/publish` left the blueprint unregistered,
# the test carried on to `deploy_start`, and the suite reported `output does not contain
# deploymentId` while the actual cause (the 409, with its explanatory message) sat further up the
# log as a warning. Callers that must not proceed past a failed publish use THIS helper; the raw
# `publish_blueprint` remains for callers that legitimately inspect the outcome themselves.
publish_blueprint_or_fail() {
    local artifact="$1" result
    if ! result=$(publish_blueprint "$artifact"); then
        log_fail "Publish of ${artifact} was REFUSED — aborting before deploy so the real cause is the reported failure: $(printf '%s' "$result" | head -c 300)"
        return 1
    fi
    printf '%s' "$result"
}

deploy_blueprint_file() {
    local filepath="$1"
    log_info "Deploying blueprint file: ${filepath}" >&2
    local content
    content=$(cat "$filepath")
    curl -sfk -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/toml" \
        -d "$content" "${CLUSTER_ENDPOINT}/api/v1/blueprints"
}

list_blueprints() {
    aether_json blueprints list 2>/dev/null || api_get "/api/v1/blueprints"
}

# ---------------------------------------------------------------------------
# Node operations
# ---------------------------------------------------------------------------
# DEPRECATED — kept as a thin pass-through after the NodeId == container_name
# migration. Both compose-fixed and CTM-auto-heal containers now use the NodeId
# verbatim as the container name (aether-{a,b}-node-{1..5} for compose,
# aether-{a,b}-node-{6,7,...} for CTM extensions), so this helper is a no-op.
# Retained so any caller missed during the migration still resolves correctly.
_docker_container_name() {
    local node_id="$1"
    printf '%s' "$node_id"
}

# DEPRECATED — kept as a thin pass-through after the NodeId == container_name
# migration. Returns the running container name iff a container with that name
# exists on TARGET_HOST; empty otherwise. Previously did a label lookup; now a
# direct name match is the canonical path (label filter is redundant when name
# is authoritative).
_docker_container_by_node_id_label() {
    local node_id="$1"
    remote_exec "docker ps --filter 'name=^${node_id}\$' --format '{{.Names}}' | head -1"
}

# Resolve container name on TARGET_HOST by aether.node-id label.
# DEPRECATED — after the NodeId == container_name migration, the NodeId IS the
# container name (compose-fixed aether-{a,b}-node-{1..5} + CTM extensions
# aether-{a,b}-node-{6,7,...}). Retained as a thin wrapper that probes by name
# directly. The previous label-+-network filter scoping is now redundant
# (container names are globally unique on a single Docker host), but we keep
# the cluster-network filter as defence-in-depth so a stale orphan from the
# sibling cluster cannot match.
_docker_container_by_node_id_label() {
    local node_id="$1"
    local network_filter=""
    if [ -n "${CLUSTER_ID:-}" ]; then
        network_filter="--filter network=aether-${CLUSTER_ID}-network"
    fi
    remote_exec "docker ps --filter 'name=^${node_id}\$' ${network_filter} --format '{{.Names}}' | head -1"
}

# Tear down any CTM-provisioned replacement containers on the remote host so the
# cluster settles back to the fixed compose-node set. Called between disruption
# tests to avoid phantom-sixth-node inflation.
#
# Post-migration: CTM containers are named `aether-<cluster>-node-N` with
# N >= 6 (compose owns 1..5). The label `aether.provisioned-by=ctm` is the
# authoritative selector — names sometimes still match the compose pattern
# for slots claimed during CTM scale-up, so a name-based filter alone would
# be ambiguous.
drop_ctm_replacements() {
    if [ "$CLOUD_MODE" = "true" ]; then
        return 0
    fi
    # Capture stderr separately so SSH transport errors (rc!=0) surface as warnings
    # rather than being swallowed by the legacy `2>/dev/null` wrapper. The inner
    # `2>/dev/null` on `docker rm -f $(docker ps -aq ...)` stays — that one
    # legitimately silences "no matching containers" when the cluster is clean.
    # Post-migration filter: `aether.provisioned-by=ctm` label set by
    # DockerComputeProvider.buildRunCommand on every auto-heal container.
    # Compose-fixed nodes don't carry this label so the sweep can't mistakenly
    # evict them. Cluster scoping via `aether.cluster=${CLUSTER_ID}` is added
    # whenever the harness has it so the sweep on cluster A can't touch
    # cluster B's auto-heal replacements.
    local cluster_filter=""
    if [ -n "${CLUSTER_ID:-}" ]; then
        cluster_filter="--filter label=aether.cluster=${CLUSTER_ID}"
    fi
    local err_file rc
    err_file=$(mktemp -t drop_ctm.XXXXXX)
    remote_exec "docker rm -f \$(docker ps -aq --filter label=aether.provisioned-by=ctm ${cluster_filter}) 2>/dev/null || true" >/dev/null 2>"$err_file"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "drop_ctm_replacements: remote_exec rc=${rc}: $(head -c 300 < "$err_file")"
    fi
    rm -f "$err_file"
    return 0
}

# Label-scoped zombie sweep. Identifies any container carrying
# `aether.cluster=<cluster_id>` whose name is NOT in the compose-fixed allowlist
# (`aether-<cluster_id>-node-{1..5}`, plus `forge-postgres` for cluster A) and
# removes it. Idempotent — no-op when the host is already clean.
#
# Trap-safe: any SSH/docker failure is logged at WARN and swallowed; the runner
# proceeds to `up -d` regardless. The fixed-name allowlist is intentionally
# hardcoded against compose YAML rather than parsed at runtime — the compose
# files are authoritative and seldom-changed, and runtime parsing on the remote
# would itself add a failure surface.
# ---------------------------------------------------------------------------
# Capture-before-heal log streamers (remote only)
# ---------------------------------------------------------------------------
# Auto-heal destroys a dying node's container WITH its logs (`docker rm`), which made the
# run2/run4 pre-kill deaths (node-5, node-3) undiagnosable. A remote-side daemon
# (scripts/log-streamer.sh) keeps an appending `docker logs -f` attached to every aether-*
# container — including auto-heal replacements, via a 5s re-scan — so the FILES survive
# container removal. capture_node_logs (run-tests.sh) fetches them as streamed-*.log.
start_log_streamers() {
    [ "$ENV_TYPE" = "remote" ] || return 0

    if ! remote_scp "${SCRIPT_DIR}/scripts/log-streamer.sh" "/tmp/aether-log-streamer.sh"; then
        log_warn "log-streamer deploy failed — pre-removal node logs will not survive auto-heal this run"
        return 0
    fi
    # Restart clean: a previous run's daemon, streams, and files must not pollute this run's
    # evidence (the failure-log dir had exactly that staleness bug on the harness side).
    # `-[f]` is the self-exclusion idiom: a bare `pkill -f 'docker logs -f aether-'` matches the
    # REMOTE SHELL RUNNING THIS VERY CHAIN (the pattern is part of its command line) and kills it
    # mid-chain — run5 hit exactly that, and it is the recorded "pgrep matches your own waiter"
    # class. The bracketed pattern still matches real streams but not its own literal self.
    remote_exec "[ -f /tmp/aether-node-logs/daemon.pid ] && kill \$(cat /tmp/aether-node-logs/daemon.pid) 2>/dev/null; \
pkill -f 'docker logs -[f] aether-' 2>/dev/null; \
rm -rf /tmp/aether-node-logs && mkdir -p /tmp/aether-node-logs; \
chmod +x /tmp/aether-log-streamer.sh; \
nohup /tmp/aether-log-streamer.sh >/dev/null 2>&1 & echo \$! > /tmp/aether-node-logs/daemon.pid" \
        || { log_warn "log-streamer start failed — continuing without capture-before-heal"; return 0; }
    log_info "log streamers started on ${TARGET_HOST} (files under /tmp/aether-node-logs survive docker rm)"
}

stop_log_streamers() {
    [ "$ENV_TYPE" = "remote" ] || return 0
    remote_exec "[ -f /tmp/aether-node-logs/daemon.pid ] && kill \$(cat /tmp/aether-node-logs/daemon.pid) 2>/dev/null; \
pkill -f 'docker logs -[f] aether-' 2>/dev/null; true" >/dev/null 2>&1 || true
}

cleanup_cluster_zombies() {
    local cluster_id="$1"
    if [ -z "$cluster_id" ]; then
        log_warn "cleanup_cluster_zombies: cluster_id is required"
        return 0
    fi
    local allowlist="aether-${cluster_id}-node-1|aether-${cluster_id}-node-2|aether-${cluster_id}-node-3|aether-${cluster_id}-node-4|aether-${cluster_id}-node-5|aether-${cluster_id}-mgmt-gateway|forge-postgres"
    local err_file rc names_out
    err_file=$(mktemp -t zombies.XXXXXX)
    names_out=$(remote_exec "docker ps -a --filter 'label=aether.cluster=${cluster_id}' --format '{{.Names}}' | grep -Ev '^(${allowlist})\$' || true" 2>"$err_file")
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "cleanup_cluster_zombies(${cluster_id}): list rc=${rc}: $(head -c 300 < "$err_file")"
        rm -f "$err_file"
        return 0
    fi
    rm -f "$err_file"
    if [ -z "$names_out" ]; then
        log_info "cleanup_cluster_zombies(${cluster_id}): no zombies"
        return 0
    fi
    local zombie
    # FD 3, not stdin: remote_exec (ssh) inside the loop consumes stdin — on a stdin
    # here-string it would eat the remaining names after the first, removing only ONE
    # zombie per call and leaving survivors that silently invalidate the next run.
    while IFS= read -r zombie <&3; do
        [ -z "$zombie" ] && continue
        log_info "cleanup_cluster_zombies(${cluster_id}): removing zombie ${zombie}"
        remote_exec "docker rm -f ${zombie} >/dev/null 2>&1 || true" >/dev/null 2>&1 || \
            log_warn "cleanup_cluster_zombies(${cluster_id}): docker rm -f ${zombie} failed"
    done 3<<< "$names_out"
    # Post-state verification — any survivor under the label is reported but not
    # treated as fatal (next compose up will re-attempt).
    local remaining
    remaining=$(remote_exec "docker ps -a --filter 'label=aether.cluster=${cluster_id}' --format '{{.Names}}' | grep -Ev '^(${allowlist})\$' || true" 2>/dev/null)
    if [ -n "$remaining" ]; then
        log_warn "cleanup_cluster_zombies(${cluster_id}): survivors after sweep: $(echo "$remaining" | tr '\n' ',' | sed 's/,$//')"
    fi
    return 0
}

# ===========================================================================
# Provider-agnostic cloud chaos primitives
# ---------------------------------------------------------------------------
# Each VM hosts ONE Aether node; there is no single Docker host on cloud, and
# CTM-provisioned replacement VMs do NOT carry the operator SSH key — so SSH-based
# chaos (docker kill / pkill over cloud_ssh) cannot reach them. These primitives
# drive chaos through the PROVIDER's compute + firewall APIs instead, addressing
# VMs by server-id (resolved IP-based via cloud_server_id: node-id -> public IP
# (cluster-scoped, bootstrap-state or mgmt API) -> Hetzner server-id via the hcloud
# CLI's IP column — handles both bootstrap seeds and CTM replacements).
#
# Observation stays on the mgmt API (cloud_running_cores), which is reachable from
# the runner for every node regardless of SSH key.
#
# All primitives dispatch on $CLOUD_PROVIDER (default `hetzner`). Hetzner uses the
# `hcloud` CLI. A future aws/gcp backend adds a `case` branch, not a rewrite.
# ===========================================================================

# Hetzner cluster transport ports, read from the cloud TOML `[operations.ports]`
# (cluster + swim). Partition firewalls must block these two; mgmt (8080) and SSH
# (22) stay OPEN so the harness can keep observing the isolated node via the API.
#
# Resolution order:
#   1. explicit env overrides CLOUD_CLUSTER_PORT / CLOUD_SWIM_PORT (CI / tests)
#   2. parse the active cloud TOML (CLOUD_TOML / env/cloud-hetzner*.toml)
#   3. documented defaults cluster=6000, swim=cluster+100 (=6100)
# Prints "<cluster_port> <swim_port>" on stdout. Always rc 0 (defaults on miss).
_cloud_transport_ports() {
    local cluster_port="${CLOUD_CLUSTER_PORT:-}" swim_port="${CLOUD_SWIM_PORT:-}"
    if [ -z "$cluster_port" ] || [ -z "$swim_port" ]; then
        # Find a cloud TOML to read [operations.ports] from. Prefer an explicit
        # CLOUD_TOML; otherwise the canonical env file next to this lib.
        local toml="${CLOUD_TOML:-}"
        if [ -z "$toml" ] || [ ! -f "$toml" ]; then
            local cand
            for cand in \
                "${SCRIPT_DIR:-}/env/cloud-hetzner.toml" \
                "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." 2>/dev/null && pwd)/env/cloud-hetzner.toml"; do
                if [ -n "$cand" ] && [ -f "$cand" ]; then toml="$cand"; break; fi
            done
        fi
        if [ -n "$toml" ] && [ -f "$toml" ]; then
            # Grab the `cluster = NNNN` / `swim = NNNN` lines from the ports block.
            # The keys are unique enough across the file to grep directly.
            [ -z "$cluster_port" ] && cluster_port=$(grep -oE '^[[:space:]]*cluster[[:space:]]*=[[:space:]]*[0-9]+' "$toml" 2>/dev/null | head -1 | grep -oE '[0-9]+$')
            [ -z "$swim_port" ]    && swim_port=$(grep -oE '^[[:space:]]*swim[[:space:]]*=[[:space:]]*[0-9]+' "$toml" 2>/dev/null | head -1 | grep -oE '[0-9]+$')
        fi
    fi
    # Documented defaults: cluster=6000; swim = cluster + 100.
    [ -z "$cluster_port" ] && cluster_port=6000
    [ -z "$swim_port" ] && swim_port=$((cluster_port + 100))
    printf '%s %s\n' "$cluster_port" "$swim_port"
}

# cloud_kill_vm <node_id> — destroy the victim VM (provider compute API).
#
# This is the AUTO-HEAL kill path: every suite caller (02-chaos/*, 02z-killonly,
# 12-network swim/quic, 13-edge-cases, self-drain-quorum-loss) expects the node to
# be GONE and a brand-new replacement to be provisioned by CTM auto-heal — NO test
# revives the same node after a kill (start_node / cloud_revive_vm have zero suite
# callers; see start_node's header). So a hard DELETE is the faithful "node gone"
# model: peers observe the VM vanish via SWIM timeout → NODE_FAILED → CTM auto-heal,
# AND the account's server quota is freed.
#
# Why delete, not poweroff: a powered-off victim LINGERS as a stopped Hetzner server
# consuming the per-project server quota. Across a run's multiple kill tests these
# accumulate (a live run showed 4 `off` servers), and once the quota is exhausted the
# leader's provisionReplacement fails repeatedly (observed priorFailureCount=41) so
# auto-heal can never rebuild the cluster — it sat at 3/5 for 45 min. Deleting the
# victim keeps the quota clear so each replacement provision has headroom.
#
# Same-node restart (poweroff/keep) is provided by the separate cloud_stop_vm /
# cloud_revive_vm pair for any test that genuinely needs the original VM back.
#
# Idempotent: deleting an already-gone server id (server-id resolution fails because
# the VM no longer exists) is a no-op-with-warning, NOT a failure — a double-kill or a
# kill of an already-auto-healed node must not abort the suite. Resolves the VM by
# server-id (seed OR CTM replacement). Returns non-zero only on a real provider error
# against a live server.
cloud_kill_vm() {
    local node_id="${1:-}"
    if [ -z "$node_id" ]; then
        log_fail "cloud_kill_vm: node id argument is required"
        return 2
    fi
    case "${CLOUD_PROVIDER:-hetzner}" in
        hetzner)
            if ! command -v hcloud >/dev/null 2>&1; then
                log_fail "cloud_kill_vm: hcloud CLI not found (required for provider 'hetzner')"
                return 2
            fi
            local sid
            sid=$(cloud_server_id "$node_id" 2>/dev/null) || {
                # No live server resolved → already gone (deleted/replaced). Idempotent
                # no-op: the post-condition ("victim VM absent") already holds.
                log_warn "cloud_kill_vm: no server resolves for '${node_id}' — already deleted/replaced (idempotent no-op)"
                return 0
            }
            log_info "cloud_kill_vm: deleting ${node_id} (hetzner server ${sid})"
            local out rc
            out=$(hcloud server delete "$sid" 2>&1); rc=$?
            if [ "$rc" -ne 0 ]; then
                # `not found` ⇒ the server was deleted between resolve and delete
                # (concurrent auto-heal). Treat as success — the VM is gone either way.
                if printf '%s' "$out" | grep -qiE 'not found|does not exist'; then
                    log_warn "cloud_kill_vm: server ${sid} (node '${node_id}') already gone at delete time (idempotent no-op)"
                    return 0
                fi
                log_fail "cloud_kill_vm: hcloud server delete ${sid} (node '${node_id}') failed (rc=${rc}): ${out}"
                return "$rc"
            fi
            return 0
            ;;
        *)
            log_fail "cloud_kill_vm: provider '${CLOUD_PROVIDER}' not implemented (only 'hetzner')"
            return 2
            ;;
    esac
}

# cloud_stop_vm <node_id> — abrupt VM power-off WITHOUT deleting (provider API).
#
# Non-destructive sibling of cloud_kill_vm, paired with cloud_revive_vm: a hard
# poweroff (NOT graceful shutdown) so peers observe the node vanish via SWIM timeout,
# while the VM (and its identity) is PRESERVED so cloud_revive_vm can power it back on
# for a same-node rejoin. Use this — not cloud_kill_vm — in any test that calls
# start_node / cloud_revive_vm on the SAME node afterwards. (No such suite exists
# today; this primitive keeps the stop/revive contract available for restart_all_nodes'
# best-effort seed power-on and any future same-identity-restart test.)
# Resolves the VM by server-id (seed OR CTM replacement). Idempotent for an
# already-stopped server. Returns non-zero with a clear log on a real provider error.
cloud_stop_vm() {
    local node_id="${1:-}"
    if [ -z "$node_id" ]; then
        log_fail "cloud_stop_vm: node id argument is required"
        return 2
    fi
    case "${CLOUD_PROVIDER:-hetzner}" in
        hetzner)
            if ! command -v hcloud >/dev/null 2>&1; then
                log_fail "cloud_stop_vm: hcloud CLI not found (required for provider 'hetzner')"
                return 2
            fi
            local sid
            sid=$(cloud_server_id "$node_id") || {
                log_fail "cloud_stop_vm: could not resolve server id for '${node_id}' (cannot power off)"
                return 1
            }
            log_info "cloud_stop_vm: powering off ${node_id} (hetzner server ${sid})"
            local out rc
            out=$(hcloud server poweroff "$sid" 2>&1); rc=$?
            if [ "$rc" -ne 0 ]; then
                log_fail "cloud_stop_vm: hcloud server poweroff ${sid} (node '${node_id}') failed (rc=${rc}): ${out}"
                return "$rc"
            fi
            return 0
            ;;
        *)
            log_fail "cloud_stop_vm: provider '${CLOUD_PROVIDER}' not implemented (only 'hetzner')"
            return 2
            ;;
    esac
}

# cloud_revive_vm <node_id> — power a previously-STOPPED VM back on (provider API).
#
# Partner of cloud_stop_vm (NOT cloud_kill_vm — kill DELETES the VM, which cannot be
# revived). Intended for tests that need same-identity rejoin. NOTE: by the time this
# runs, CTM auto-heal may already have DECOMMISSIONED the node and provisioned a
# replacement — in which case the revived VM rejoins as a stale identity and the
# cluster sees a transient N+1 state (same caveat as start_node's docker path).
# Prefer waiting on CTM auto-heal for normal recovery; use this only when the test
# explicitly stopped the VM (cloud_stop_vm) and needs the original back.
cloud_revive_vm() {
    local node_id="${1:-}"
    if [ -z "$node_id" ]; then
        log_fail "cloud_revive_vm: node id argument is required"
        return 2
    fi
    case "${CLOUD_PROVIDER:-hetzner}" in
        hetzner)
            if ! command -v hcloud >/dev/null 2>&1; then
                log_fail "cloud_revive_vm: hcloud CLI not found (required for provider 'hetzner')"
                return 2
            fi
            local sid
            sid=$(cloud_server_id "$node_id" 2>/dev/null) || {
                # No live server resolves → the VM was DELETED (cloud_kill_vm) and CTM
                # auto-heal has provisioned a replacement that carries the load. In the
                # best-effort recovery model (restart_all_nodes ignores this rc), reviving
                # a deleted node is a benign no-op — mirror cloud_kill_vm's idempotent
                # not-found path (log_warn + return 0) so it does NOT emit a spurious
                # [FAIL] that records an otherwise-passing recovery test as FAIL. A STOPPED
                # VM (cloud_stop_vm) still resolves, so the stop/revive contract is intact:
                # this path only triggers for a genuinely deleted node.
                log_warn "cloud_revive_vm: no server resolves for '${node_id}' — already deleted/replaced (idempotent no-op)"
                return 0
            }
            log_info "cloud_revive_vm: powering on ${node_id} (hetzner server ${sid})"
            local out rc
            out=$(hcloud server poweron "$sid" 2>&1); rc=$?
            if [ "$rc" -ne 0 ]; then
                log_fail "cloud_revive_vm: hcloud server poweron ${sid} (node '${node_id}') failed (rc=${rc}): ${out}"
                return "$rc"
            fi
            return 0
            ;;
        *)
            log_fail "cloud_revive_vm: provider '${CLOUD_PROVIDER}' not implemented (only 'hetzner')"
            return 2
            ;;
    esac
}

# Deterministic firewall name for a node's partition. Sanitizes the node id (the
# trailing ULID can be long but is alnum-safe; replace any non-alnum just in case)
# so the name is idempotent across calls — create/apply/delete all key on it.
_cloud_partition_fw_name() {
    local node_id="${1:-}"
    local cluster="${BOOTSTRAP_CLUSTER_NAME:-${CLOUD_BOOTSTRAP_CLUSTER:-aether}}"
    local safe
    safe=$(printf '%s-%s' "$cluster" "$node_id" | sed 's/[^A-Za-z0-9-]/-/g')
    printf 'aether-partition-%s' "$safe"
}

# cloud_partition_node <node_id> — isolate a VM's cluster transport via a provider
# firewall (network PARTITION chaos), idempotently.
#
# Blocks the cluster/QUIC port and the SWIM port (cluster+100) in BOTH directions —
# tcp AND udp (Aether cluster transport is QUIC = UDP; SWIM uses udp) — so the node
# can neither reach peers nor be reached on transport. KEEPS the mgmt port (8080)
# and SSH (22) reachable so the harness can still observe the isolated node via the
# management API.
#
# Hetzner firewall semantics (verified against docs.hetzner.com/cloud/firewalls):
#   - Firewalls are STATEFUL: a reply to an allowed connection is auto-permitted.
#   - INBOUND is deny-by-default — only listed ports are reachable. So omitting
#     cluster+swim from the inbound allow-list blocks peers→node transport.
#   - OUTBOUND is allow-all UNTIL the first outbound rule is added, after which
#     outbound also becomes deny-by-default. To block node→peers transport WITHOUT
#     cutting the node's other egress (DB, app, package mirrors), we add outbound
#     ALLOW rules spanning every port EXCEPT the two transport ports (ranges
#     1..cluster-1, cluster+1..swim-1, swim+1..65535) for tcp+udp. The two transport
#     ports fall in the gaps and are therefore denied. This yields a TRUE
#     bidirectional transport partition.
#   - CAVEAT (documented, validated on live cluster): rule changes apply only to NEW
#     connections — EXISTING long-lived QUIC peer links established before the
#     firewall was applied stay up until they naturally break. Tests should allow for
#     SWIM-timeout-driven teardown of the stale links (the same detection window a
#     real partition incurs) rather than expecting an instant cutover.
#
# The firewall is named deterministically per node (_cloud_partition_fw_name) so
# re-running is a no-op create + idempotent rule/apply. The resolved firewall id is
# recorded to /tmp/aether-partition-fw-${node_id}.id for cloud_heal_partition.
cloud_partition_node() {
    local node_id="${1:-}"
    if [ -z "$node_id" ]; then
        log_fail "cloud_partition_node: node id argument is required"
        return 2
    fi
    case "${CLOUD_PROVIDER:-hetzner}" in
        hetzner)
            if ! command -v hcloud >/dev/null 2>&1; then
                log_fail "cloud_partition_node: hcloud CLI not found (required for provider 'hetzner')"
                return 2
            fi
            local sid
            sid=$(cloud_server_id "$node_id") || {
                log_fail "cloud_partition_node: could not resolve server id for '${node_id}' (cannot apply firewall)"
                return 1
            }
            local mgmt_port="${CLOUD_MGMT_PORT:-8080}"
            local ssh_port="${CLOUD_SSH_PORT:-22}"
            local fw_name
            fw_name=$(_cloud_partition_fw_name "$node_id")

            # 1. Create the firewall idempotently. `hcloud firewall create` fails if
            #    the name already exists — treat "already exists" as success and
            #    resolve the existing id. Rules are set explicitly below (an empty
            #    rule set would deny-all inbound, also cutting mgmt+ssh).
            local out rc fw_id
            out=$(hcloud firewall create --name "$fw_name" 2>&1); rc=$?
            if [ "$rc" -ne 0 ] && ! printf '%s' "$out" | grep -qiE 'already exists|uniqueness'; then
                log_fail "cloud_partition_node: hcloud firewall create '${fw_name}' failed (rc=${rc}): ${out}"
                return "$rc"
            fi
            fw_id=$(hcloud firewall describe "$fw_name" -o 'format={{.ID}}' 2>/dev/null)
            if [ -z "$fw_id" ]; then
                log_fail "cloud_partition_node: could not resolve firewall id for '${fw_name}' after create"
                return 1
            fi

            # 2. Compute the transport ports and the three outbound allow-ranges that
            #    bracket them (everything except cluster_port and swim_port). `port` in
            #    a Hetzner rule accepts "N" or "LO-HI"; we use ranges to exclude exactly
            #    the two transport ports. Guard the degenerate gaps (if a range would be
            #    empty/inverted it is simply omitted — only happens for nonsensical
            #    port=1 configs, which the cloud TOML never uses).
            local ports_line cluster_port swim_port lo hi
            ports_line=$(_cloud_transport_ports)
            cluster_port="${ports_line%% *}"
            swim_port="${ports_line##* }"
            # Order the two blocked ports so the range math is direction-independent.
            local p1 p2
            if [ "$cluster_port" -le "$swim_port" ]; then p1="$cluster_port"; p2="$swim_port"; else p1="$swim_port"; p2="$cluster_port"; fi
            # Build the outbound allow-ranges: [1, p1-1], [p1+1, p2-1], [p2+1, 65535].
            local out_ranges=""
            [ "$p1" -gt 1 ] && out_ranges="${out_ranges} 1-$((p1 - 1))"
            [ $((p1 + 1)) -le $((p2 - 1)) ] && out_ranges="${out_ranges} $((p1 + 1))-$((p2 - 1))"
            [ $((p2 + 1)) -le 65535 ] && out_ranges="${out_ranges} $((p2 + 1))-65535"

            # Build the rules JSON. Inbound: allow mgmt+ssh (tcp); transport denied by
            # omission. Outbound: allow tcp+udp on every range EXCEPT the transport
            # ports (which become denied once any outbound rule exists).
            local rules_json rng proto out_rules=""
            for rng in $out_ranges; do
                for proto in tcp udp; do
                    out_rules="${out_rules}  {\"direction\":\"out\",\"protocol\":\"${proto}\",\"port\":\"${rng}\",\"destination_ips\":[\"0.0.0.0/0\",\"::/0\"]},
"
                done
            done
            rules_json="[
  {\"direction\":\"in\",\"protocol\":\"tcp\",\"port\":\"${mgmt_port}\",\"source_ips\":[\"0.0.0.0/0\",\"::/0\"]},
  {\"direction\":\"in\",\"protocol\":\"tcp\",\"port\":\"${ssh_port}\",\"source_ips\":[\"0.0.0.0/0\",\"::/0\"]},
${out_rules%,
}
]"
            local rules_file
            rules_file=$(mktemp -t aether-fw-rules.XXXXXX)
            printf '%s\n' "$rules_json" > "$rules_file"
            out=$(hcloud firewall replace-rules "$fw_name" --rules-file "$rules_file" 2>&1); rc=$?
            rm -f "$rules_file"
            if [ "$rc" -ne 0 ]; then
                log_fail "cloud_partition_node: hcloud firewall replace-rules '${fw_name}' failed (rc=${rc}): ${out} (blocking cluster ${cluster_port}+swim ${swim_port} in+out, keeping mgmt ${mgmt_port}+ssh ${ssh_port})"
                return "$rc"
            fi

            # 3. Apply to the target server idempotently. Re-applying an
            #    already-applied firewall returns an "already applied" error we treat
            #    as success.
            out=$(hcloud firewall apply-to-resource "$fw_name" --type server --server "$sid" 2>&1); rc=$?
            if [ "$rc" -ne 0 ] && ! printf '%s' "$out" | grep -qiE 'already applied|already_applied|already exists'; then
                log_fail "cloud_partition_node: hcloud firewall apply-to-resource '${fw_name}' -> server ${sid} failed (rc=${rc}): ${out}"
                return "$rc"
            fi

            # Record the id for cleanup (cloud_heal_partition reads this).
            printf '%s\n' "$fw_id" > "/tmp/aether-partition-fw-${node_id}.id" 2>/dev/null || true
            log_info "cloud_partition_node: ${node_id} (server ${sid}) partitioned via firewall '${fw_name}' (id ${fw_id}) — blocking cluster ${cluster_port}+swim ${swim_port} in+out (tcp+udp), keeping mgmt ${mgmt_port}+ssh ${ssh_port}"
            return 0
            ;;
        *)
            log_fail "cloud_partition_node: provider '${CLOUD_PROVIDER}' not implemented (only 'hetzner')"
            return 2
            ;;
    esac
}

# cloud_heal_partition <node_id> — remove a node's partition firewall (idempotent).
#
# Detaches the firewall from the server (if applied) then deletes it. No-op if the
# firewall does not exist (already healed / never partitioned). Restores full
# transport connectivity for the node.
cloud_heal_partition() {
    local node_id="${1:-}"
    if [ -z "$node_id" ]; then
        log_fail "cloud_heal_partition: node id argument is required"
        return 2
    fi
    case "${CLOUD_PROVIDER:-hetzner}" in
        hetzner)
            if ! command -v hcloud >/dev/null 2>&1; then
                log_fail "cloud_heal_partition: hcloud CLI not found (required for provider 'hetzner')"
                return 2
            fi
            local fw_name
            fw_name=$(_cloud_partition_fw_name "$node_id")
            local fw_id
            fw_id=$(hcloud firewall describe "$fw_name" -o 'format={{.ID}}' 2>/dev/null || true)
            if [ -z "$fw_id" ]; then
                # No firewall by that name — already healed / never partitioned.
                rm -f "/tmp/aether-partition-fw-${node_id}.id" 2>/dev/null || true
                log_info "cloud_heal_partition: no partition firewall '${fw_name}' for ${node_id} (already healed)"
                return 0
            fi
            # Detach from the node's server (if still applied), then delete. Resolve
            # the server id best-effort — if the VM is gone (CTM replaced it) the
            # detach is unnecessary and delete still succeeds.
            local sid out rc
            sid=$(cloud_server_id "$node_id" 2>/dev/null || true)
            if [ -n "$sid" ]; then
                out=$(hcloud firewall remove-from-resource "$fw_name" --type server --server "$sid" 2>&1); rc=$?
                if [ "$rc" -ne 0 ] && ! printf '%s' "$out" | grep -qiE 'not applied|not_found|not found'; then
                    log_warn "cloud_heal_partition: detach '${fw_name}' from server ${sid} returned rc=${rc}: ${out} (proceeding to delete)"
                fi
            fi
            out=$(hcloud firewall delete "$fw_name" 2>&1); rc=$?
            if [ "$rc" -ne 0 ] && ! printf '%s' "$out" | grep -qiE 'not_found|not found'; then
                log_fail "cloud_heal_partition: hcloud firewall delete '${fw_name}' failed (rc=${rc}): ${out}"
                return "$rc"
            fi
            rm -f "/tmp/aether-partition-fw-${node_id}.id" 2>/dev/null || true
            log_info "cloud_heal_partition: removed partition firewall '${fw_name}' for ${node_id}"
            return 0
            ;;
        *)
            log_fail "cloud_heal_partition: provider '${CLOUD_PROVIDER}' not implemented (only 'hetzner')"
            return 2
            ;;
    esac
}

# cloud_running_cores — enumerate live READY cores via the mgmt API.
#
# Provider-agnostic replacement for the single-host `docker ps` core enumeration:
# on cloud there is no shared Docker daemon, so liveness comes from the cluster's
# own membership. Reads /api/v1/nodes/lifecycle through _resolve_live_endpoint
# (api_get) and prints the node-id of every entry whose state is READY, one per
# line. Empty output (rc 0) when the API is unreachable or no core is READY — the
# caller decides whether that is fatal.
#
# The suites' single-host helpers (running_core_containers / container_for_node)
# call this in the follow-up rewire.
cloud_running_cores() {
    # Enumerate CORE members from the generation snapshot's core.members[] (the
    # ground-truth membership — includes SYNCING/JOINING members CTM has admitted),
    # NOT /api/v1/nodes/lifecycle filtered by --state READY. On cloud the READY lifecycle
    # projection lags the leader's membership view by MINUTES (poller-proven: deficit=0,
    # actualCoreCount=5, CONVERGED — while --state READY reads 3-4), which false-failed
    # Pick_3's "5 running core containers" precondition on a genuinely-whole cluster.
    # core.members[] each carry a bare "nodeId"; communities[] use governorNodeId and
    # partitions[] ownerNodeId, so a bare "nodeId" match yields exactly the core members
    # (identical source + idiom to cluster_member_count). Empty output (rc 0) when the API
    # is unreachable — the caller decides whether that is fatal.
    local gen
    gen=$(direct_api_get "/api/v1/cluster/generation" 2>/dev/null || true)
    [ -z "$gen" ] && return 0
    printf '%s' "$gen" \
        | grep -oE '"nodeId"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | sed 's/.*"nodeId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/' \
        | sort -u
    return 0
}

## DEPRECATED for routine cleanup — prefer `restore_cluster_baseline`. This
## helper forces the cluster back to the FIXED compose-node set (5 cores with
## original NodeIds), which fights the product model: killed nodes go
## DECOMMISSIONED and CTM auto-provisions replacements with new NodeIds. The
## semantic-cleanup helper (`restore_cluster_baseline` above) asserts the
## product invariant "N ON_DUTY healthy cores" and lets CTM keep replacements.
##
## Kept callable for the (few) tests that genuinely need hard-restart
## semantics — e.g. recovery scenarios that explicitly require the original
## NodeIds to rejoin after a crash, or harness-level cluster reset between
## entire test suites (not per-test cleanup).
##
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
##
## Re-establish the echo blueprint baseline after a destructive recovery. Shared
## by BOTH restart_all_nodes branches (#426 review follow-up — the redeploy was
## previously wired into the docker/compose branch only; the cloud branch
## returns earlier and never restored the baseline, yet cloud is where the
## motivating 2026-07-08 run-4 incident actually ran: CTM-replaced/revived VMs
## converge to an empty artifact repository exactly like a `compose down -v`
## wipe does, so the gap is not compose-specific). Idempotent: pushing/deploying
## an already-present artifact is a no-op per push_blueprint's contract.
_reestablish_echo_baseline() {
    local baseline_bp="${ECHO_BLUEPRINT:-org.pragmatica.aether.test:test-echo:1.0.0}"
    log_info "restart_all_nodes: re-establishing ${baseline_bp} baseline after destructive recovery"
    if ! push_blueprint "$baseline_bp" >/dev/null; then
        log_fail "restart_all_nodes: failed to re-push ${baseline_bp} after recovery"
        return 1
    fi
    # Capture rc + stderr instead of `... >/dev/null 2>&1 || true` (#426 review
    # follow-up, house rule: transport/API failures must stay LOUD). Still
    # non-fatal here — the scoped wait_for below is the authoritative gate — but
    # a failing deploy call must not vanish silently.
    local deploy_out deploy_rc
    deploy_out=$(deploy_blueprint "$baseline_bp" 2>&1)
    deploy_rc=$?
    if [ "$deploy_rc" -ne 0 ]; then
        log_warn "restart_all_nodes: deploy_blueprint ${baseline_bp} returned rc=${deploy_rc} (non-fatal — readiness wait below is authoritative): ${deploy_out}"
    fi
    if ! wait_for "echo blueprint all target instances ACTIVE after restart (scoped to ${baseline_bp})" \
        "[ \$(slices_active_instances_for '${baseline_bp}') -ge \$(slices_target_total_for '${baseline_bp}') ] && [ \$(slices_target_total_for '${baseline_bp}') -gt 0 ]" \
        120; then
        log_fail "restart_all_nodes: ${baseline_bp} did not reach all-target-instances ACTIVE after post-recovery redeploy ($(slices_active_instances_for "$baseline_bp")/$(slices_target_total_for "$baseline_bp") active) — deployment baseline not restored"
        return 1
    fi
    log_info "restart_all_nodes: ${baseline_bp} baseline restored — all $(slices_target_total_for "$baseline_bp") target instance(s) ACTIVE"
    return 0
}

# #441 review v2: corroboration helper for the S20 full-drain decision. Emits
# the public IPs of Hetzner VMs in RUNNING power state for a cluster (one per
# line), via the hcloud CLI — a signal path completely independent of the mgmt
# HTTP API. NOT used as a liveness verdict by itself: a self-drained node's VM
# stays powered ON with an exited container (`Runtime.halt(2)` kills the
# container's JVM; the VM itself never reboots), so "VM running" is the NORMAL
# state during a confirmed full drain, not evidence the cluster is alive.
# (v1 of this helper counted running VMs and treated count>=1 as "must not
# reap" — that made S20 permanently unable to recover the real post-self-drain
# state, since surviving VMs always stay powered on.) Callers must corroborate
# further by probing each returned IP's mgmt port directly. rc distinguishes
# the query itself SUCCEEDING (0 — even an empty result is a successful
# query) from corroboration being UNAVAILABLE (1 — no hcloud CLI or the query
# itself failed).
#
# #441 Defect B (run 8): an rc=0 EMPTY result is NOT equivalent to "confirmed
# zero VMs" and must NEVER be treated as reap-confirmation by a caller — it is
# indistinguishable from an enumeration mechanism that failed to match a live
# VM (exactly what happened here before the label-key+pattern fix below: the
# selector matched nothing while 6 VMs were running). Full-drain confirmation
# requires a NON-EMPTY list whose members ALL fail their mgmt probe; an empty
# list is UNKNOWN and callers must abort loudly rather than reap on it.
_cloud_running_vm_ips() {
    local cluster_name="$1"
    if ! command -v hcloud >/dev/null 2>&1; then
        return 1
    fi
    # #441 Defect B: the previous `--selector "aether-cluster=${cluster_name}"`
    # query structurally never matched — provisioned VMs (seed AND CTM
    # replacement alike) are only ever stamped with `aether-node-id`;
    # `aether-cluster` is a separate label that CTM does not currently set on
    # every VM (product-side gap, tracked separately as #442 v2b). That made
    # this function's "0 running VMs" result look trustworthy even while VMs
    # were fully alive, and the caller (restart_all_nodes) treated that
    # false-empty as confirmed-dead and reaped + rebootstrapped a LIVE
    # cluster (run 8: 6 live VMs, 3 mgmt endpoints answering 200, reaped
    # anyway).
    #
    # Interim fix: query by label KEY presence (`-l aether-node-id`, matches
    # regardless of whether `aether-cluster` is ever stamped) and post-filter
    # each row against two independent, cluster-unambiguous membership tests:
    #   - CTM auto-heal replacements are named `aether-cloud-<cluster>-node-*`
    #     (the cluster name is embedded in the `aether-node-id` VALUE itself,
    #     so this match can never fold in a sibling cluster's replacement).
    #   - Original bootstrap seeds are named `<CLOUD_SOURCE_NAME>-core-N`,
    #     which does NOT embed a cluster name — CLOUD_SOURCE_NAME defaults to
    #     the SAME "hetzner-eu" for every cluster (lib/common.sh, unset by any
    #     per-cluster override), and cluster A/B run concurrently in a cloud
    #     gate, so two sibling clusters' seed VMs carry IDENTICAL
    #     `aether-node-id` label values by construction (confirmed: matching
    #     by that string, even cross-checked against THIS cluster's own
    #     provisionedNodeIds list, does NOT disambiguate — both clusters'
    #     bootstrap-state.json list the same logical names). Seeds are
    #     therefore matched by IP ADDRESS instead, against THIS cluster's own
    #     `collectedAddresses` from bootstrap-state.json — the actual
    #     provisioned IPs are globally unique per VM, so this can never
    #     collide with a sibling cluster's seed.
    # The test-PG VM is excluded unconditionally by name prefix, independent
    # of its labels — it happens not to carry aether-node-id today, but that
    # must not be load-bearing.
    local seed_ips
    seed_ips=$(_cloud_seed_ips "$cluster_name")
    local listing hcloud_rc
    # #441 item 1: under hcloud API contention this call was observed to hang
    # well past any reasonable per-iteration budget (run 10: wait_for_node_count
    # spun 1019s against a 540s deadline chasing an unbounded enumeration here).
    # Bound it hard so a slow/contended API call degrades to "corroboration
    # UNAVAILABLE" (rc 1, handled below exactly like a missing hcloud CLI)
    # instead of stalling the whole poll loop. _run_with_timeout (lib/common.sh)
    # returns 124 on a forced kill; treated the same as any other non-zero rc.
    listing=$(_run_with_timeout 10 hcloud server list -l aether-node-id -o columns=name,status,ipv4,labels -o noheader 2>/dev/null)
    hcloud_rc=$?
    if [ "$hcloud_rc" -ne 0 ]; then
        return 1
    fi
    local name status ip labels_blob node_id line
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        name=$(printf '%s' "$line" | awk '{print $1}')
        status=$(printf '%s' "$line" | awk '{print $2}')
        ip=$(printf '%s' "$line" | awk '{print $3}')
        [ "$status" = "running" ] || continue
        case "$name" in
            aether-test-pg*) continue ;;
        esac
        labels_blob=$(printf '%s' "$line" | sed -E 's/^[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+//')
        # `|| true`: grep -oE legitimately returns 1 on a non-matching row (should
        # not happen given the `-l aether-node-id` selector, but this loop body
        # must never depend on caller-side `set -e` masking to stay alive — see
        # the run_test() if-masking discussion elsewhere in this harness; a bare
        # failing command substitution here would abort the whole script under
        # `set -euo pipefail` whenever this function is invoked outside a masked
        # context).
        node_id=$(printf '%s' "$labels_blob" | grep -oE 'aether-node-id=[^,[:space:]]+' | sed 's/aether-node-id=//' || true)
        [ -z "$node_id" ] && continue
        case "$node_id" in
            "aether-cloud-${cluster_name}-node-"*)
                printf '%s\n' "$ip"
                ;;
            *)
                if printf '%s\n' "$seed_ips" | grep -qxF "$ip"; then
                    printf '%s\n' "$ip"
                fi
                ;;
        esac
    done <<< "$listing"
    return 0
}

# #441 Defect B: the ORIGINAL bootstrap seed IPs for THIS cluster, read
# straight from its own bootstrap-state.json `collectedAddresses` (same
# file/array this cluster's `aether cluster bootstrap` run wrote; same
# file cloud_public_ip in lib/common.sh reads — deliberately duplicated
# here rather than reused, since that helper resolves a single node-id's
# IP, not the full address list, and this file should not reach into
# lib/common.sh's parsing internals). Matching by IP rather than by
# `aether-node-id` label VALUE is deliberate: CLOUD_SOURCE_NAME (and hence
# the seed label naming scheme "<source>-core-N") is shared by every
# cluster in a run, so two sibling clusters' seed VMs carry identical
# label values — only the actual provisioned IP is guaranteed unique per
# VM. Prints one IP per line; prints nothing (not an error) when the state
# file is missing/unparseable — callers must fall back to the
# CTM-replacement name-pattern match alone in that case, not treat this as
# fatal.
_cloud_seed_ips() {
    local cluster_name="$1"
    local state_file="${HOME}/.aether/clusters/${cluster_name}/bootstrap-state.json"
    [ -f "$state_file" ] || return 0
    local addrs_raw
    addrs_raw=$(awk -v RS='' '{
        match($0, /"collectedAddresses"[[:space:]]*:[[:space:]]*\[[^]]*\]/);
        if (RSTART > 0) print substr($0, RSTART, RLENGTH);
    }' "$state_file")
    [ -z "$addrs_raw" ] && return 0
    # `|| true`: the final `grep -v` legitimately returns 1 when every entry is
    # filtered out (e.g. an all-blank-string array) — a bare pipeline failure
    # here must not depend on the caller's `set -e` masking context (same
    # rationale as the `|| true` in _cloud_running_vm_ips above).
    printf '%s' "$addrs_raw" | sed 's/.*\[//; s/\].*//' | tr ',' '\n' \
        | sed 's/^[[:space:]]*"//; s/"[[:space:]]*$//' | grep -v '^[[:space:]]*$' || true
    return 0
}

# Serialized-cluster reap (cloud): delete every VM belonging to ONE named
# cluster, with a converge loop — CTM auto-heal can respawn a replacement VM
# mid-reap (a leader can survive long enough to provision one more), so a
# single enumerate+delete pass is not authoritative. Loop: enumerate, delete
# all matches in parallel, re-enumerate; converged only after 3 CONSECUTIVE
# empty checks (a single empty enumeration is UNKNOWN, not proof of absence —
# see the #441 Defect B note on _cloud_running_vm_ips), capped at 40 sweeps.
#
# Enumeration mirrors _cloud_running_vm_ips (same `-l aether-node-id`
# key-presence selector + per-row post-filter, NOT a bare
# `--selector aether-cluster=...` query — see the Defect B rationale there),
# with one deliberate difference: NO `status == running` filter. Powered-off/
# starting VMs still count against the Hetzner account server limit, which is
# exactly what this reap exists to free (concurrent A+B pinned the limit and
# every 03-scaling scale-up failed with 403 resource_limit_exceeded).
# Per-row membership tests (any one admits the row):
#   1. `aether-node-id` label VALUE matches the CTM replacement pattern
#      `aether-cloud-<cluster>-node-*` (cluster name embedded — unambiguous).
#   2. exact `aether-cluster=<cluster>` label match (stamped reliably
#      post-#442 v2b; exact match can never fold in the PG VM — its value is
#      `test-pg`, never a test cluster's name — nor a sibling cluster).
#   3. seed-IP membership against THIS cluster's own bootstrap-state.json
#      `collectedAddresses` (_cloud_seed_ips) — seed label VALUES are shared
#      across sibling clusters; only their IPs are unique.
# The shared test-PG VM is excluded unconditionally by name prefix, same as
# _cloud_running_vm_ips — its labels must not be load-bearing.
reap_cloud_cluster() {
    local cluster_name="${1:-}"
    if [ -z "$cluster_name" ]; then
        log_fail "reap_cloud_cluster: cluster name required — refusing an unscoped reap (would risk resources outside the target cluster, including the shared test-PG VM)"
        return 1
    fi
    if ! command -v hcloud >/dev/null 2>&1; then
        log_fail "reap_cloud_cluster: hcloud CLI not found (required for provider 'hetzner')"
        return 1
    fi
    local seed_ips
    seed_ips=$(_cloud_seed_ips "$cluster_name")
    local max_sweeps=40 sweep=0 empty_streak=0
    local listing hcloud_rc line sid name ip labels_blob node_id member ids names count pid out
    local pids=()
    while [ "$sweep" -lt "$max_sweeps" ]; do
        sweep=$((sweep + 1))
        hcloud_rc=0
        listing=$(_run_with_timeout 10 hcloud server list -l aether-node-id -o columns=id,name,status,ipv4,labels -o noheader 2>/dev/null) || hcloud_rc=$?
        if [ "$hcloud_rc" -ne 0 ]; then
            # Enumeration failure is UNKNOWN, not empty — reset the streak so
            # convergence is only declared on 3 consecutive SUCCESSFUL empty queries.
            log_warn "reap_cloud_cluster(${cluster_name}): sweep ${sweep}/${max_sweeps}: enumeration failed (rc=${hcloud_rc}) — retrying"
            empty_streak=0
            sleep 3
            continue
        fi
        ids=""
        names=""
        while IFS= read -r line; do
            [ -z "$line" ] && continue
            sid=$(printf '%s' "$line" | awk '{print $1}')
            name=$(printf '%s' "$line" | awk '{print $2}')
            ip=$(printf '%s' "$line" | awk '{print $4}')
            case "$name" in
                aether-test-pg*) continue ;;
            esac
            labels_blob=$(printf '%s' "$line" | sed -E 's/^[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+//')
            # `|| true`: same rationale as _cloud_running_vm_ips — a non-matching
            # row must never abort the script under `set -euo pipefail`.
            node_id=$(printf '%s' "$labels_blob" | grep -oE 'aether-node-id=[^,[:space:]]+' | sed 's/aether-node-id=//' || true)
            member=false
            case "$node_id" in
                "aether-cloud-${cluster_name}-node-"*) member=true ;;
            esac
            # Exact-boundary label match; cluster names are [a-z0-9-] so the
            # interpolation is ERE-safe.
            if [ "$member" = false ] \
                && printf '%s' "$labels_blob" | grep -qE "(^|[,[:space:]])aether-cluster=${cluster_name}"'([,[:space:]]|$)'; then
                member=true
            fi
            if [ "$member" = false ] && [ -n "$ip" ] \
                && printf '%s\n' "$seed_ips" | grep -qxF "$ip"; then
                member=true
            fi
            [ "$member" = true ] || continue
            ids="${ids}${sid}"$'\n'
            names="${names}${name} "
        done <<< "$listing"
        ids=$(printf '%s' "$ids" | grep -v '^$' || true)
        if [ -z "$ids" ]; then
            empty_streak=$((empty_streak + 1))
            log_info "reap_cloud_cluster(${cluster_name}): sweep ${sweep}/${max_sweeps}: no matching VMs (${empty_streak}/3 consecutive empty checks)"
            if [ "$empty_streak" -ge 3 ]; then
                log_info "reap_cloud_cluster(${cluster_name}): converged — cluster fully reaped"
                return 0
            fi
            sleep 2
            continue
        fi
        empty_streak=0
        count=$(printf '%s\n' "$ids" | grep -c '^' || true)
        log_info "reap_cloud_cluster(${cluster_name}): sweep ${sweep}/${max_sweeps}: deleting ${count} VM(s): ${names% }"
        pids=()
        while IFS= read -r sid; do
            [ -z "$sid" ] && continue
            (
                if out=$(hcloud server delete "$sid" 2>&1); then
                    log_info "reap_cloud_cluster(${cluster_name}): deleted server ${sid}"
                else
                    log_warn "reap_cloud_cluster(${cluster_name}): hcloud server delete ${sid} failed: ${out}"
                fi
            ) &
            pids+=("$!")
        done <<< "$ids"
        for pid in "${pids[@]+"${pids[@]}"}"; do
            wait "$pid" 2>/dev/null || true
        done
        # Brief settle before re-enumerating: deletions are async on the API
        # side, and auto-heal (if a leader briefly survives) can respawn a
        # replacement that only the NEXT sweep will see.
        sleep 3
    done
    log_fail "reap_cloud_cluster(${cluster_name}): did not converge after ${max_sweeps} sweeps — VMs may remain (sweep manually: tools/cloud-reaper.sh --cluster ${cluster_name} --strict-cluster --destroy --force)"
    return 1
}

## #598: idempotently ensure a database exists on the shared test-PG VM. Same SSH
## docker-exec pattern as tools/provision-test-pg.sh's smoke test (the PG VM runs
## postgres in the `aether-pg` container; no local psql required). Read-only against
## the VM apart from the CREATE DATABASE itself; NEVER drops or alters anything.
## Requires the PG firewall to be open (caller runs after pg-firewall.sh open) and
## the ambient PG_HOST/PG_USER/PG_PASSWORD env vars run-tests.sh already relies on.
ensure_cloud_pg_database() {
    local dbname="$1"
    local pg_ssh_user="${PG_VM_SSH_USER:-root}"
    if [ -z "${PG_HOST:-}" ] || [ -z "${PG_USER:-}" ] || [ -z "${PG_PASSWORD:-}" ]; then
        log_warn "ensure_cloud_pg_database(${dbname}): PG_HOST/PG_USER/PG_PASSWORD not set"
        return 1
    fi
    local ssh_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o LogLevel=ERROR -i "$AETHER_SSH_KEY")
    local exists
    exists=$(ssh "${ssh_opts[@]}" "${pg_ssh_user}@${PG_HOST}" \
        "docker exec -e PGPASSWORD='${PG_PASSWORD}' aether-pg psql -U '${PG_USER}' -d postgres -tAc \"SELECT 1 FROM pg_database WHERE datname='${dbname}'\"" 2>&1) || {
        log_warn "ensure_cloud_pg_database(${dbname}): existence probe failed: $(printf '%s' "$exists" | head -c 200)"
        return 1
    }
    if [ "$(printf '%s' "$exists" | tr -d '[:space:]')" = "1" ]; then
        log_info "ensure_cloud_pg_database(${dbname}): already present"
        return 0
    fi
    local created
    created=$(ssh "${ssh_opts[@]}" "${pg_ssh_user}@${PG_HOST}" \
        "docker exec -e PGPASSWORD='${PG_PASSWORD}' aether-pg psql -U '${PG_USER}' -d postgres -c 'CREATE DATABASE ${dbname}'" 2>&1) || {
        log_warn "ensure_cloud_pg_database(${dbname}): CREATE DATABASE failed: $(printf '%s' "$created" | head -c 200)"
        return 1
    }
    log_info "ensure_cloud_pg_database(${dbname}): created"
    return 0
}

## #441 S20: cluster-scoped reap + fresh bootstrap — the cloud analog of the
## docker/compose branch's `docker compose down -v && up -d` full reset. This is
## the ONLY path capable of recovering a CONFIRMED FULL self-drain: every core
## has halted (`--restart no` on all launch paths, by design — a self-fenced
## node must never auto-return on VM reboot), so there is no leader and no CTM
## (CTM itself runs on a leader that no longer exists) to auto-heal, and
## `cloud_revive_vm` is poweron-only — a no-op against a VM that is still
## powered ON (self-drain halts the container/JVM, not the VM) or against a VM
## the chaos-kill primitive already DELETED. Cloud-init also does not re-run on
## an already-provisioned VM, so there is no lighter-weight recovery available.
##
## NEVER touches the shared test-PG VM or its firewall (tools/pg-firewall.sh):
## the reap call below passes --strict-cluster, which restricts cloud-reaper.sh's
## Hetzner label selector to an EXACT `aether-cluster=<name>` match only — no
## `aether-node-id` orphan-capture query at all. The PG VM's aether-cluster
## label (whatever it is, even empty) can never equal this cluster's exact
## name, so it is excluded by construction. (#441 review fix: an earlier
## revision of this comment claimed the PG VM "carries neither label" — that
## was WRONG, contradicted by a documented incident where an UNSCOPED
## catch-all reap DID delete the PG VM+firewall, proving it carries at least
## one aether-* label. Without --strict-cluster, cloud-reaper.sh's
## orphan-capture post-filter keeps any aether-node-id-labeled row whose
## aether-cluster label is EMPTY — exactly the shape a mislabeled PG resource
## could take — so --strict-cluster is required here, not optional.)
_cloud_full_drain_recover() {
    local cluster_name="${BOOTSTRAP_CLUSTER_NAME:-}"
    if [ -z "$cluster_name" ]; then
        log_fail "_cloud_full_drain_recover: BOOTSTRAP_CLUSTER_NAME unset — refusing an unscoped reap (would risk resources outside this cluster, including the shared test-PG VM)"
        return 1
    fi

    # #441 review WARNING b: prove the re-bootstrap is POSSIBLE before
    # destroying anything. On a --skip-deploy rerun, CLOUD_TOML_A/B are never
    # exported (run-tests.sh only exports them inside its deploy branch), so
    # reaping first and discovering the missing TOML second destroys a cluster
    # with no way to rebuild it. Preflight the TOML selection first; reap only
    # once this is guaranteed to succeed.
    local toml
    case "${CLUSTER_ID:-}" in
        a) toml="${CLOUD_TOML_A:-}" ;;
        b) toml="${CLOUD_TOML_B:-}" ;;
        *) log_fail "_cloud_full_drain_recover: unrecognized CLUSTER_ID='${CLUSTER_ID:-}' (expected 'a' or 'b') — cannot select a bootstrap TOML"; return 1 ;;
    esac
    if [ -z "$toml" ] || [ ! -f "$toml" ]; then
        log_fail "_cloud_full_drain_recover: no readable bootstrap TOML for cluster '${cluster_name}' (CLUSTER_ID='${CLUSTER_ID:-}', resolved path='${toml}') — run-tests.sh must export CLOUD_TOML_A/CLOUD_TOML_B (not exported on --skip-deploy reruns; full-drain recovery is not possible there — refusing before destroying anything)"
        return 1
    fi

    # Locate tools/cloud-reaper.sh relative to THIS file (lib/cluster.sh), not the
    # caller's SCRIPT_DIR — SCRIPT_DIR points at the calling suite's own
    # suites/NN-name/ directory, not aether/tests/integration, so it cannot be
    # used to reach the top-level tools/ dir. Same BASH_SOURCE-relative pattern
    # as _cloud_transport_ports above. lib/ -> integration -> tests -> aether ->
    # pragmatica -> tools.
    local reaper
    reaper="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../tools" 2>/dev/null && pwd)/cloud-reaper.sh"
    if [ ! -x "$reaper" ]; then
        log_fail "_cloud_full_drain_recover: tools/cloud-reaper.sh not found or not executable (resolved path: ${reaper})"
        return 1
    fi
    log_warn "_cloud_full_drain_recover: full self-drain confirmed (0 active cores) — reaping all VMs for cluster '${cluster_name}' (no partial-recovery path exists once every core has halted)"
    local reap_out reap_rc
    reap_out=$("$reaper" --cluster "$cluster_name" --strict-cluster --destroy --force 2>&1)
    reap_rc=$?
    # Reaper rc contract: 0=clean, 1=usage/setup error (fatal — e.g. missing
    # HCLOUD_TOKEN, bad invocation), 2=one-or-more-deletions-failed (orphans
    # remain, but the reap otherwise ran — fresh VMs from the rebootstrap below
    # get new IDs and won't collide with lingering orphans, so this is a
    # degraded-but-proceedable state, not fatal).
    case "$reap_rc" in
        0) log_info "_cloud_full_drain_recover: reap of '${cluster_name}' clean" ;;
        2) log_warn "_cloud_full_drain_recover: reap of '${cluster_name}' left orphan resources behind (rc=2) — proceeding to rebootstrap anyway: ${reap_out}" ;;
        *) log_fail "_cloud_full_drain_recover: cloud-reaper.sh --cluster ${cluster_name} --strict-cluster --destroy --force failed (rc=${reap_rc}): ${reap_out}"; return 1 ;;
    esac

    # Bare `aether` resolved from PATH — the gate run environment prepends its
    # own CLI shim to PATH; a hardcoded path would silently bypass it. PG env
    # vars (PG_USER/PG_PASSWORD/PG_HOST/PG_DB) referenced via ${env:...} in the
    # TOML are already ambient in this shell (run-tests.sh inherits them from
    # the operator's environment; this subshell inherits them from run-tests.sh).
    log_info "_cloud_full_drain_recover: fresh bootstrap of '${cluster_name}' from ${toml}"
    local boot_out boot_rc
    boot_out=$(aether cluster bootstrap "$toml" --cluster "$cluster_name" --yes --wait --timeout 300 2>&1)
    boot_rc=$?
    if [ "$boot_rc" -ne 0 ]; then
        log_fail "_cloud_full_drain_recover: aether cluster bootstrap '${cluster_name}' failed (rc=${boot_rc}): ${boot_out}"
        return 1
    fi
    log_info "_cloud_full_drain_recover: bootstrap complete: $(printf '%s' "$boot_out" | tail -3)"

    # The fresh cluster's VMs have new IPs — the pinned CLUSTER_ENDPOINT/
    # MGMT_ENTRY_POINT from before the drain is stale.
    if ! _refresh_mgmt_entry_point; then
        log_fail "_cloud_full_drain_recover: no live endpoint to pin after rebootstrap"
        return 1
    fi
    return 0
}

restart_all_nodes() {
    log_info "Restoring cluster to baseline (CLUSTER_NAME=${CLUSTER_NAME:-aether-b-node-})..."
    if [ "$CLOUD_MODE" = "true" ]; then
        # Cloud has NO single Docker host and NO docker-compose project — each node
        # is its own VM. The old SSH-based `docker restart` / JVM pkill+relaunch loop
        # could not reach CTM-provisioned replacement VMs (no operator SSH key), and
        # there is nothing to "compose up". Recovery model on cloud:
        #   1. Power any powered-off seed VMs back on (cloud_revive_vm, best-effort —
        #      a still-running VM's poweron is a harmless no-op; a CTM-replaced node
        #      may no longer have a same-id VM, which is fine — its replacement is
        #      already carrying the load).
        #   2. Let CTM auto-heal + SWIM converge, then wait on the SAME mgmt-API
        #      readiness barriers the docker path uses (node count, leader, quiesce,
        #      ready). The mgmt API is reachable for every node regardless of SSH key.
        # We deliberately do NOT hard-fail when a seed VM cannot be revived: on cloud
        # the operational invariant is "N healthy cores" (CTM may satisfy it with
        # replacements), which the readiness barriers below assert authoritatively.
        local i node_id
        for i in $(seq 1 "${NODE_COUNT:-5}"); do
            node_id=$(to_node_id "node-${i}" 2>/dev/null || true)
            [ -z "$node_id" ] && continue
            cloud_revive_vm "$node_id" 2>/dev/null \
                || log_info "restart_all_nodes: cloud_revive_vm ${node_id} did not power on a VM (already running or CTM-replaced — proceeding)"
        done
        # Re-pin the mgmt endpoint to a live core, then wait on readiness via the API.
        _refresh_mgmt_entry_point || log_warn "restart_all_nodes: no live endpoint to re-pin (proceeding with ${CLUSTER_ENDPOINT})"
        local floor=$(( ${NODE_COUNT:-5} - 1 ))

        # #441 S20: the poweron loop above assumes a PARTIAL drain (some seed VMs
        # stopped, CTM auto-heals the rest back to floor) — it cannot recover a
        # FULL self-drain (every core halted; see _cloud_full_drain_recover's
        # header for why). Probe with a SHORT bounded wait first — do NOT burn
        # the full 600s budget before deciding — then only escalate to the
        # destructive reap+rebootstrap on a CONFIRMED full drain (0 active
        # cores). A genuine partial shortfall (some but not enough cores back)
        # keeps today's behavior: keep waiting on the remaining budget.
        local short_wait=120 full_budget=600
        if ! wait_for "${floor}+ healthy cores present (cloud baseline, ${short_wait}s probe)" \
            "[ \$(cluster_active_core_count) -ge ${floor} ]" "$short_wait"; then
            local active_now
            active_now=$(cluster_active_core_count)
            if [ "${active_now:-0}" -eq 0 ]; then
                # #441 review WARNING a: cluster_active_core_count returns a
                # CLEAN rc=0 "0" on BOTH a genuine 0-active-core reading AND an
                # unreachable mgmt API (transport failure) — the two are
                # structurally indistinguishable from that call alone.
                # Confirming "full drain" on a merely-unreachable read would
                # reap a cluster that is still LIVE. Re-probe the same
                # endpoint directly to tell them apart (empty response =
                # transport failure, non-empty = a clean parse that legitimately
                # says 0), then corroborate an ambiguous transport-failure case
                # before treating this as destroy-eligible.
                local topology_probe
                topology_probe=$(api_get "/api/v1/cluster/topology" 2>/dev/null || true)
                if [ -n "$topology_probe" ]; then
                    log_warn "restart_all_nodes: mgmt API cleanly reports 0 active cores — confirmed full self-drain, proceeding to reap+rebootstrap"
                    if ! _cloud_full_drain_recover; then
                        log_fail "restart_all_nodes: cloud full-drain recovery (cluster-scoped reap + rebootstrap) failed"
                        return 1
                    fi
                else
                    # #441 review v2: VM power state alone CANNOT distinguish a
                    # drained cluster from a live one — a self-drained node's
                    # VM stays powered ON (`Runtime.halt(2)` kills the
                    # container's JVM; the VM itself never reboots), so
                    # "hcloud shows running VMs" is the NORMAL state during a
                    # confirmed full drain, not evidence of life (v1 of this
                    # branch treated running>=1 as "refuse to reap", which
                    # made S20 permanently unable to recover the real
                    # post-self-drain state). Corroborate with a per-VM MGMT
                    # PORT probe instead: hcloud reachability + VM existence
                    # rules out "our own egress is broken", then each running
                    # VM's mgmt port tells us whether a node process is
                    # actually listening there.
                    local running_ips running_ips_rc vm_count=0 ip
                    running_ips=$(_cloud_running_vm_ips "${BOOTSTRAP_CLUSTER_NAME:-}")
                    running_ips_rc=$?
                    if [ "$running_ips_rc" -ne 0 ]; then
                        log_fail "restart_all_nodes: mgmt API unreachable AND hcloud corroboration unavailable — cannot distinguish a full self-drain from a transport outage on a LIVE cluster; refusing to reap. Operator: check mgmt connectivity and hcloud credentials manually."
                        return 1
                    elif [ -z "$running_ips" ]; then
                        # #441 Defect B (run 8): an EMPTY hcloud enumeration is
                        # UNKNOWN, not confirmation of death — it is exactly the
                        # shape a matching failure produces (the run-8 incident:
                        # 6 VMs running, 3 mgmt endpoints answering 200, yet the
                        # old cluster-label selector matched nothing, and this
                        # branch reaped + rebootstrapped a SECOND cluster over
                        # the live one). Full-drain confirmation requires a
                        # NON-EMPTY enumerated set whose members ALL fail their
                        # mgmt probe (the `else` branch below) — never a bare
                        # empty result, no matter how well-corroborated the
                        # enumeration mechanism is believed to be. Abort loudly;
                        # an operator can tell "genuinely 0 VMs" apart from
                        # "enumeration broken" with a direct
                        # `hcloud server list -l aether-node-id` and re-run once
                        # resolved — that decision must not be automated here.
                        log_fail "restart_all_nodes: mgmt API unreachable AND hcloud enumeration for '${BOOTSTRAP_CLUSTER_NAME}' returned ZERO VMs — this is UNKNOWN, not a confirmed drain (an empty enumeration is indistinguishable from a matching failure); refusing to reap. Operator: verify with 'hcloud server list -l aether-node-id' whether the cluster's VMs still exist before taking any destructive action."
                        return 1
                    else
                        # #441 run 9 Defect 2: HTTP 200 from /health/live is NOT proof
                        # of a recoverable cluster — ManagementServer returns 200
                        # whenever status=="UP", REGARDLESS of `ready` (see
                        # LivenessResponse). A CTM replacement provisioned in-flight
                        # at quorum death boots into an empty world, is cold-boot-
                        # suppressed from ever self-draining, and answers this probe
                        # with {"status":"UP","state":"JOINING","ready":false}
                        # FOREVER — a straggler, not a live cluster. Parse the
                        # payload's `ready`/`state` fields (not just the status code)
                        # to tell the two apart.
                        local ready_ip="" ready_state="" straggler_count=0 straggler_evidence="" body ready_val state_val
                        for ip in $running_ips; do
                            vm_count=$((vm_count + 1))
                            body=$(curl -sk -m 2 -H "X-API-Key: ${API_KEY}" "${MGMT_SCHEME:-http}://${ip}:${CLOUD_MGMT_PORT:-8080}/health/live" 2>/dev/null)
                            [ -z "$body" ] && continue
                            ready_val=$(json_value "$body" "ready")
                            state_val=$(json_value "$body" "state")
                            if [ "$ready_val" = "true" ]; then
                                ready_ip="$ip"
                                ready_state="$state_val"
                                break
                            fi
                            straggler_count=$((straggler_count + 1))
                            straggler_evidence="${straggler_evidence}${straggler_evidence:+, }${ip}(state=${state_val:-?},ready=${ready_val:-?})"
                        done
                        if [ -n "$ready_ip" ]; then
                            log_warn "restart_all_nodes: mgmt API unreachable at the pinned endpoint, BUT VM ${ready_ip} answers /health/live with ready=true (state=${ready_state:-?}) — cluster is LIVE behind a stale pinned endpoint, NOT a full drain; re-pinning and continuing on a bounded progress window instead of reaping"
                            if ! _refresh_mgmt_entry_point; then
                                log_fail "restart_all_nodes: found a ready VM (${ready_ip}) but could not re-pin the mgmt entry point — aborting without reaping"
                                return 1
                            fi
                            # #441 run 9 Defect 2(c): a single ready core surrounded by
                            # stragglers can sit at a fixed active-core count forever —
                            # don't burn the whole remaining budget on that possibility.
                            # Bound the wait; if the bounded window shows ZERO progress
                            # in active-core count, this was a straggler-skewed "LIVE"
                            # read after all — fall through to full-drain confirmation
                            # instead of waiting out the full budget.
                            local progress_window=300
                            local before_count after_count
                            before_count=$(cluster_active_core_count)
                            if wait_for "${floor}+ healthy cores present (cloud baseline, ${progress_window}s progress window)" \
                                "[ \$(cluster_active_core_count) -ge ${floor} ]" "$progress_window"; then
                                log_info "restart_all_nodes: reached ${floor}+ healthy cores within the bounded progress window"
                            else
                                after_count=$(cluster_active_core_count)
                                if [ "${after_count:-0}" -gt "${before_count:-0}" ]; then
                                    log_info "restart_all_nodes: bounded progress window exhausted but active-core count progressed (${before_count:-0} -> ${after_count:-0}) — continuing on the remaining budget"
                                    if ! wait_for "${floor}+ healthy cores present (cloud baseline, remaining budget)" \
                                        "[ \$(cluster_active_core_count) -ge ${floor} ]" $((full_budget - short_wait - progress_window)); then
                                        log_fail "restart_all_nodes: cloud cluster did not reach ${floor}+ healthy cores within ${full_budget}s total (current=$(cluster_active_core_count))"
                                        return 1
                                    fi
                                else
                                    log_warn "restart_all_nodes: bounded progress window (${progress_window}s) showed ZERO progress in active-core count (stuck at ${after_count:-0}) after re-pinning to '${ready_ip}' — straggler-skewed LIVE read, not a recoverable cluster; falling through to full-drain confirmation"
                                    if ! _cloud_full_drain_recover; then
                                        log_fail "restart_all_nodes: cloud full-drain recovery (cluster-scoped reap + rebootstrap) failed"
                                        return 1
                                    fi
                                fi
                            fi
                        elif [ "$straggler_count" -gt 0 ]; then
                            log_warn "restart_all_nodes: mgmt API unreachable, hcloud shows ${vm_count} running VM(s) for '${BOOTSTRAP_CLUSTER_NAME}'; ${straggler_count} answer /health/live but NONE report ready=true (${straggler_evidence}) — straggler evidence (e.g. a CTM replacement provisioned in-flight at quorum death, cold-boot-suppressed from self-draining, forever JOINING), not a recoverable cluster; proceeding to reap+rebootstrap (reap is cluster-scoped and includes straggler VMs)"
                            if ! _cloud_full_drain_recover; then
                                log_fail "restart_all_nodes: cloud full-drain recovery (cluster-scoped reap + rebootstrap) failed"
                                return 1
                            fi
                        else
                            log_warn "restart_all_nodes: mgmt API unreachable, hcloud shows ${vm_count} running VM(s) for '${BOOTSTRAP_CLUSTER_NAME}', but NONE answer their mgmt port directly (probed ${vm_count} VM(s), 0 listening) — VMs staying powered on with an exited container is the expected post-self-drain state; proceeding to reap+rebootstrap"
                            if ! _cloud_full_drain_recover; then
                                log_fail "restart_all_nodes: cloud full-drain recovery (cluster-scoped reap + rebootstrap) failed"
                                return 1
                            fi
                        fi
                    fi
                fi
            else
                log_info "restart_all_nodes: ${active_now}/${floor}+ active cores after ${short_wait}s probe — partial recovery in progress (not a full drain), continuing on the remaining budget"
                if ! wait_for "${floor}+ healthy cores present (cloud baseline, remaining budget)" \
                    "[ \$(cluster_active_core_count) -ge ${floor} ]" $((full_budget - short_wait)); then
                    log_fail "restart_all_nodes: cloud cluster did not reach ${floor}+ healthy cores within ${full_budget}s total (current=$(cluster_active_core_count))"
                    return 1
                fi
            fi
        fi
        if ! wait_for_leader 120; then
            log_fail "restart_all_nodes: no leader elected within 120s on cloud baseline restore"
            return 1
        fi
        if ! await_generation_quiesced "${CLUSTER_ENDPOINT}" "current" 180; then
            log_warn "restart_all_nodes: generation did not quiesce within 180s on cloud (proceeding — readiness barrier is authoritative)"
        fi
        if ! wait_for_cluster_ready 120 "${floor}"; then
            log_warn "restart_all_nodes: cloud cluster not fully ready within 120s (proceeding — downstream suite has its own readiness gate)"
        fi
        log_info "restart_all_nodes: cloud cluster recovered (>=${floor} healthy cores, leader elected)"
        # #426 review follow-up (SEVERE): this branch used to return here,
        # before the echo-baseline redeploy — cloud never got the rebaseline
        # even though cloud is where the motivating incident happened.
        _reestablish_echo_baseline || return 1
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
    # Pre-clean cluster B by NAME PREFIX `aether-b-node-`, not by the CTM label alone.
    # Mid-suite, B's ON_DUTY members are a mix of compose-fixed nodes (aether-b-node-1..5) and
    # CTM auto-heal replacements named `aether-b-node-<ULID>`. A replacement provisioned before
    # bootstrap can carry `aether.cluster=default` (DockerComputeProvider.clusterOrDefault
    # fallback — see docker-compose-b.yml AETHER_CLUSTER_NAME note), so the old
    # `--filter label=aether.cluster=b` sweep MISSED it: it outlived `down -v` (not a compose
    # service), kept holding a node's name/network-alias, and the following `up -d` then failed
    # to (re)create that compose node — the cluster returned as a sub-quorum MINORITY (observed
    # 2026-06-30 S20: only 2/5, nodes 3+5). The name prefix `aether-b-node-` matches BOTH compose
    # nodes AND every CTM replicant, and CANNOT match cluster A (`aether-a-node-*`), so it is a
    # safe, comprehensive reset.
    # DO NOT add `--remove-orphans`: clusters A and B share one default compose project (both are
    # `up`'d from ~ with no -p), so B's `up -d` lists A's containers as orphans — `--remove-orphans`
    # would DELETE cluster A + forge-postgres. Project isolation (-p per cluster) is the proper
    # structural fix, tracked separately.
    local restart_out restart_rc
    if [ -f "$compose" ] && [ "${prefix}" = "aether-b-node-" ]; then
        restart_out=$(remote_exec "docker rm -f \$(docker ps -aq --filter name='aether-b-node-') 2>/dev/null || true; cd ~ && docker compose -f docker-compose-b.yml down -v && docker compose -f docker-compose-b.yml up -d" 2>&1)
        restart_rc=$?
    else
        # Fallback for non-standard cluster names — best-effort start of exited containers.
        restart_out=$(remote_exec "docker rm -f \$(docker ps -a -q --filter label=aether.provisioned-by=ctm) 2>/dev/null; docker ps -a --filter 'name=${prefix}' --filter 'status=exited' -q | xargs -r docker start" 2>&1)
        restart_rc=$?
    fi
    if [ "$restart_rc" -ne 0 ]; then
        log_fail "restart_all_nodes: compose cycle returned rc=${restart_rc}. Output: ${restart_out}"
        return 1
    fi
    # Container-count guard (compose-b path). `docker compose up -d` can return 0 yet leave fewer
    # than NODE_COUNT containers running when a transient name/alias conflict blocks a subset on
    # the first attempt — the exact S20 symptom (rc=0, but 2/5 up). Verify the RUNNING container
    # count directly via docker (authoritative — independent of the management API, which is
    # unreachable while the cluster is sub-quorum); on a shortfall, force-recreate once, then
    # capture per-container status + tail logs and fail loud before the management-API waits below
    # (which would otherwise misreport a missing-container problem as a Rabia convergence failure).
    if [ "${prefix}" = "aether-b-node-" ]; then
        local want="${NODE_COUNT:-5}" running
        running=$(remote_exec "docker ps --filter name='aether-b-node-' --filter status=running -q | wc -l" 2>/dev/null | tail -n1 | tr -dc '0-9')
        if [ "${running:-0}" -lt "$want" ]; then
            log_warn "restart_all_nodes: only ${running:-0}/${want} aether-b-node containers running after up -d — force-recreating"
            remote_exec "docker rm -f \$(docker ps -aq --filter name='aether-b-node-') 2>/dev/null || true; cd ~ && docker compose -f docker-compose-b.yml up -d --force-recreate" >/dev/null 2>&1
            running=$(remote_exec "docker ps --filter name='aether-b-node-' --filter status=running -q | wc -l" 2>/dev/null | tail -n1 | tr -dc '0-9')
        fi
        if [ "${running:-0}" -lt "$want" ]; then
            local diag
            diag=$(remote_exec "docker ps -a --filter name='aether-b-node-' --format '{{.Names}} {{.Status}}'; for c in \$(docker ps -aq --filter name='aether-b-node-' --filter status=exited); do echo \"--- \$c ---\"; docker logs --tail 15 \$c 2>&1; done" 2>&1)
            log_fail "restart_all_nodes: only ${running:-0}/${want} aether-b-node containers running after force-recreate. Diagnostics: ${diag}"
            return 1
        fi
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
    if ! wait_for_cluster_ready 90; then
        log_fail "restart_all_nodes: cluster not ready within 90s — next test would hit a half-warm node"
        return 1
    fi
    # SWIM cold-boot guard (non-fatal): `phase=COLD_BOOT` causes
    # `SwimProtocol.emitFaultyOrUnknown` to emit `UnknownObserved` (NOT
    # `FaultyObserved`) for any peer not yet in `everSeenHealthy`, suppressing
    # NODE_LEFT/NODE_FAILED events. After D.3 (2026-05-11) the post-restart cluster
    # enters RECOVERING (not COLD_BOOT) because nodes were Healthy in the prior
    # NORMAL period — RECOVERING bypasses suppression and emits FaultyObserved like
    # NORMAL. We still wait for NORMAL to ensure HealthReconciler writes lifecycle
    # transitions and CTM auto-heal is re-enabled before the next chaos test fires.
    # Non-fatal: a hard fail cascades into broken cleanup state for every subsequent
    # test.
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
    # Re-establish the blueprint baseline wiped by `docker compose down -v` above —
    # that command performs a full VOLUME wipe (see the "Why" comment near the top
    # of this function), so the artifact repository and every deployed slice are
    # gone and the cluster returns at a fresh generation with zero slices. Without
    # this, ANY test that runs after a destructive restart_all_nodes() — directly
    # (e.g. S20 in test-self-drain-quorum-loss.sh) or indirectly via
    # restore_cluster_baseline's step-0 leader-unreachable escalation — inherits an
    # empty artifact repository, and load/echo traffic silently 404s instead of
    # measuring the scenario under test (#426 item 5: this exact gap left no ACTIVE
    # echo owner for kill-under-load's retarget to resolve on 2026-07-08, correctly
    # triggering the loud-abort added in 71a4aa599 rather than a silent 100%-error
    # false read). restore_cluster_baseline itself deliberately does NOT restore
    # blueprint state (see its step-8 design comment — that exclusion is
    # intentional and scoped to non-destructive baseline checks); this is the
    # destructive-recovery counterpart, scoped to the one function that actually
    # wipes the volumes. See _reestablish_echo_baseline above for the (now
    # runtime-agnostic) redeploy logic shared with the cloud branch.
    if ! _reestablish_echo_baseline; then
        return 1
    fi
    log_info "restart_all_nodes: cluster recovered (${NODE_COUNT:-5} nodes, leader elected, generation quiesced, all nodes ready)"
    return 0
}

kill_node() {
    local node_id="$1"
    # Defensive guard: refuse to operate on an empty node_id. Tests typically call
    # `kill_node "$(pick_non_leader ...)"` — if `pick_non_leader` failed and
    # returned empty on stdout (e.g. /api/v1/nodes/lifecycle had no ON_DUTY members),
    # without this guard `docker kill <prefix>-` would target a non-existent
    # container, log a confusing "No such container", and quietly proceed as if
    # the kill landed. Fail loudly so the test surfaces the upstream issue.
    if [ -z "$node_id" ]; then
        log_fail "kill_node: empty node_id (caller likely captured a failed pick_non_leader stderr write — check the previous FAIL banner)"
        return 1
    fi
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
        # Provider-API kill: DELETE the node's VM (cloud_kill_vm). This replaces
        # the old SSH-based `docker kill` / `pkill` paths, which could NOT reach
        # CTM-provisioned replacement VMs (they lack the operator SSH key →
        # Permission denied). A VM delete is an abrupt, irreversible kill — peers
        # detect the departure via SWIM timeout → NODE_FAILED → CTM auto-heal,
        # which provisions a brand-new replacement (every kill_node caller expects
        # a replacement, not a same-node revive). Deleting (vs powering off) also
        # frees the provider server quota so the replacement provision has headroom
        # — a powered-off victim lingers and exhausts the quota across a run's
        # multiple kills, starving auto-heal. Provider-agnostic (cloud_kill_vm
        # dispatches on CLOUD_PROVIDER); runtime (container/jvm) is irrelevant once
        # we destroy the whole VM.
        if ! cloud_kill_vm "$node_id"; then
            log_fail "kill_node: cloud VM delete of '${node_id}' failed"
            return 1
        fi
    else
        # NodeId == container_name (post-migration): the NodeId IS the docker
        # container name, by construction. Compose-fixed nodes are named
        # aether-{a,b}-node-{1..5}; CTM auto-heal replacements continue the
        # sequence (aether-{a,b}-node-6, -7, ...). `docker kill ${node_id}` Just
        # Works — no NodeId→container mapping required.
        local name="$node_id"
        log_info "  (container=${name})"
        local kill_out kill_rc=0
        kill_out=$(remote_exec "docker kill ${name} 2>&1" 2>&1) || kill_rc=$?
        if [ "$kill_rc" -ne 0 ]; then
            log_fail "kill_node: docker kill of '${node_id}' (container=${name}) failed (rc=${kill_rc}): ${kill_out}"
            return "$kill_rc"
        fi
        if [ -n "$kill_out" ] && ! echo "$kill_out" | grep -q "^${name}$"; then
            log_warn "kill_node: docker kill ${name} output: ${kill_out}"
        fi
    fi
}

# DEPRECATED for chaos-test recovery — prefer waiting for CTM auto-heal via
# `wait_for "${target} ON_DUTY healthy cores" ...` or `restore_cluster_baseline`.
# Restarting the killed container brings the original NodeId back, but the
# cluster has already DECOMMISSIONED that ID (single-writer rule on
# NodeLifecycleKey) and CTM has provisioned a replacement — the cluster sees
# the restarted container as a stale identity and the test ends up in a
# "killed+restarted+replaced" 6-node state.
#
# Kept callable for tests that genuinely need same-ID rejoin semantics
# (currently 15-delegation/test-02-reassignment.sh, which restarts a scaling
# node before it has been DECOMMISSIONED).
start_node() {
    local node_id="$1"
    log_info "Starting node: ${node_id}"
    if [ "$CLOUD_MODE" = "true" ]; then
        # Provider-API revive: power the node's VM back on (cloud_revive_vm). Replaces
        # the old SSH-based JVM-replay / `docker start`, which could not reach
        # CTM-provisioned replacement VMs. NOTE: CTM auto-heal may already have
        # DECOMMISSIONED this node and provisioned a replacement by now — in which
        # case the revived VM rejoins as a stale identity (transient N+1). For normal
        # chaos recovery prefer waiting on CTM auto-heal (restore_cluster_baseline);
        # this same-identity revive is for tests that explicitly need the original
        # VM back. See cloud_revive_vm's header for the full caveat.
        if ! cloud_revive_vm "$node_id"; then
            log_fail "start_node: cloud VM poweron of '${node_id}' failed"
            return 1
        fi
    else
        # NodeId == container_name (post-migration) — see kill_node for rationale.
        local name="$node_id"
        # Capture stderr — `2>/dev/null` previously hid failures (container not found,
        # docker daemon error, race with rm). Caller checks $? for success/failure.
        local start_out
        start_out=$(remote_exec "docker start ${name}" 2>&1) \
            || { log_warn "start_node: docker start ${name} failed: ${start_out}"; return 1; }
    fi
}

get_node_lifecycle() {
    api_get "/api/v1/nodes/lifecycle"
}

# Node lifecycle transitions. Canonical REST contract is path-parameterized
# (`/api/v1/nodes/{action}/{id}` — see ManagementRoute.NODE_DRAIN/NODE_ACTIVATE/
# NODE_SHUTDOWN). Earlier body-based duplicates were removed
# (audit 2026-05-21 §1.1) — they shadowed these with a different request shape.
drain_node() {
    local node_id="$1"
    log_info "Draining node: ${node_id}"
    api_post "/api/v1/nodes/drain/${node_id}" "{}"
}

activate_node() {
    local node_id="$1"
    log_info "Activating node: ${node_id}"
    api_post "/api/v1/nodes/activate/${node_id}" "{}"
}

shutdown_node() {
    local node_id="$1"
    log_info "Shutting down node: ${node_id}"
    api_post "/api/v1/nodes/shutdown/${node_id}" "{}"
}

# Phase 3 PR-C (cluster-convergence-reconciler) — operator escape hatch for
# silent-divergence cases (e.g., cluster B 02-chaos cascade where a peer is
# stuck in JOINING). Wraps POST /api/v1/nodes/lifecycle/commands with
# type=FORCE_DECOMMISSION, source=OPERATOR. Use sparingly until Phase 4-5
# reconciler lands and surfaces the same recovery via RECONCILER source.
force_decommission_node() {
    local node_id="$1"
    local reason="${2:-test-harness cleanup}"
    log_info "Force-decommissioning node: ${node_id} (reason: ${reason})"
    api_post "/api/v1/nodes/lifecycle/commands" \
        "{\"type\":\"FORCE_DECOMMISSION\",\"nodeId\":\"${node_id}\",\"reason\":\"${reason}\"}"
}

# ---------------------------------------------------------------------------
# Scaling
# ---------------------------------------------------------------------------

# Seed cluster config into KV-Store if not already present.
# Required before scale operations — the scale API reads ClusterConfigValue from KV-Store.
# Seed cluster config into KV-Store if not already present, OR reconcile coreMax
# when a config already exists but its stored coreMax is below the TOML's.
#
# Background: the cluster bootstraps with coreMax = coreCount (= 5) when no operator
# config has been planted, so a plain "already present → return 0" no-op left coreMax
# pinned at 5. Scale-up to 7 then returns HTTP 400 InvalidCoreMax(5,7) and 03-scaling
# never provisions. This reconciles idempotently: if the stored coreMax is below the
# TOML max, re-apply the config (PUT) with expectedVersion = stored configVersion so a
# concurrent change on the shared cluster surfaces as VersionConflict rather than a
# silent clobber. Immutable fields (cluster.name) must match the stored config — the
# config_file is the same TOML the cluster was seeded from, so coreMax is the only diff.
seed_cluster_config() {
    # Cloud env: `aether cluster bootstrap` already planted the AUTHORITATIVE cluster
    # config (cloud source profile + [cloud.credentials] + [database] + [infrastructure.ssh]).
    # Planting/reconciling the Docker-oriented cluster-config.toml here would CLOBBER it:
    # CTM then renders scale-up/replacement nodes from a docker stub with no cloud source,
    # so they provision with no usable config, crash on boot, never join, and the reconciler
    # over-provisions until the provider hits its server limit (#336). The bootstrap config's
    # coreMax (9) already covers every cloud-B scale target (max 7), so no reconcile is needed.
    # seed_cluster_config is the Docker-path equivalent of the cloud bootstrap — skip on cloud.
    if [ "${ENV_TYPE:-docker}" = "cloud" ]; then
        log_info "seed_cluster_config: skipped on cloud (bootstrap planted the authoritative config; #336 clobber guard)"
        return 0
    fi
    local config_file="${1:-${LIB_DIR}/../cluster-config.toml}"
    local toml_content
    toml_content=$(cat "$config_file")
    # Align the planted [cluster].name with THIS cluster's compose identity (CLUSTER_ID=a/b),
    # the Docker equivalent of the cloud path's `aether cluster bootstrap --cluster <name>`
    # (which overrides the TOML's [cluster].name). The shared cluster-config.toml hardcodes
    # name="integration-test"; planting that verbatim makes KV cluster.name diverge from the
    # compose env/label ("b"). CTM then mints replacements whose aether.cluster label + container
    # name derive from the KV name ("integration-test") while their compose-fixed siblings and the
    # harness name/label filters use "b" — so replacements miss cleanup/discovery filters and leak.
    # (Before the provider fix, this divergence also tripped the boot label-consistency guard and
    # crash-looped every replacement.) CLUSTER_ID unset → leave the TOML name untouched (ad-hoc use).
    if [ -n "${CLUSTER_ID:-}" ]; then
        toml_content=$(printf '%s' "$toml_content" \
            | awk -v id="$CLUSTER_ID" '
                !done && /^[[:space:]]*name[[:space:]]*=/ { print "name = \"" id "\""; done=1; next }
                { print }')
    fi
    # Desired coreMax from the TOML's [cluster.core] max (the operator intent).
    local toml_max
    toml_max=$(printf '%s' "$toml_content" \
        | grep -E '^[[:space:]]*max[[:space:]]*=' \
        | head -1 \
        | grep -oE '[0-9]+' | head -1)

    local body status _cfg_ep
    _cfg_ep=$(_resolve_live_endpoint)
    body=$(curl -sk -H "X-API-Key: ${API_KEY}" "${_cfg_ep}/api/v1/cluster/config" \
        -w $'\n__STATUS__:%{http_code}' 2>/dev/null || true)
    status="${body##*__STATUS__:}"
    body="${body%$'\n'__STATUS__:*}"

    if [ "$status" = "200" ]; then
        local stored_max stored_version
        stored_max=$(printf '%s' "$body" \
            | grep -oE '"coreMax"[[:space:]]*:[[:space:]]*[0-9]+' \
            | head -1 | grep -oE '[0-9]+$')
        stored_version=$(printf '%s' "$body" \
            | grep -oE '"configVersion"[[:space:]]*:[[:space:]]*[0-9]+' \
            | head -1 | grep -oE '[0-9]+$')
        # Reconcile only when we can compare and the stored max is genuinely below TOML.
        if [ -n "$toml_max" ] && [ -n "$stored_max" ] \
           && [ "$stored_max" -lt "$toml_max" ] 2>/dev/null; then
            log_info "Reconciling cluster config: stored coreMax=${stored_max} < TOML max=${toml_max} (configVersion=${stored_version:-?})"
            local escaped_toml json_body
            escaped_toml=$(escape_json "$toml_content")
            json_body="{\"tomlContent\":\"${escaped_toml}\",\"expectedVersion\":${stored_version:-0}}"
            local apply_out
            apply_out=$(leader_api_post "/api/v1/cluster/config" "$json_body" 2>&1) || true
            # Surface VersionConflict / ImmutableFieldChange rather than masking them:
            # a failed reconcile means scale-up will still 400, so the caller must know.
            if printf '%s' "$apply_out" | grep -qiE 'VersionConflict|Immutable|error|"title"'; then
                log_warn "Cluster config reconcile may have failed: $(printf '%s' "$apply_out" | head -c 300)"
            else
                log_info "Cluster config reconciled (coreMax -> ${toml_max})"
            fi
            return 0
        fi
        log_info "Cluster config already present (coreMax=${stored_max:-?} >= TOML max=${toml_max:-?})"
        return 0
    fi
    log_info "Seeding cluster config from ${config_file}"
    local json_body
    local escaped_toml
    escaped_toml=$(escape_json "$toml_content")
    json_body="{\"tomlContent\":\"${escaped_toml}\",\"expectedVersion\":0}"
    # Must hit the leader — CTM only runs on leader
    leader_api_post "/api/v1/cluster/config" "$json_body"
}

# Operator-triggered reset of the CTM provisioning circuit breaker. Calls the
# leader-routed POST /api/v1/cluster/topology/circuit-breaker/reset; the server
# returns the prior consecutive-failure count (audit log). Use between
# disruptive tests when restart_all_nodes did not converge to phase=NORMAL
# (i.e., CTM may be circuit-tripped from prior provisioning failures).
# Hard 15s timeout — call is a single KV-side reset, not a provisioning op.
reset_provisioning_circuit() {
    local result rc ep
    ep=$(_resolve_live_endpoint)
    result=$(curl -sk -m 15 -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
                  -d '{}' "${ep}/api/v1/cluster/topology/circuit-breaker/reset" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "reset_provisioning_circuit: POST failed rc=${rc}: $(printf '%s' "$result" | head -c 200)"
        return 1
    fi
    log_info "reset_provisioning_circuit: ${result}"
    return 0
}

# Read-only snapshot of the #336 provisioning-diagnostics surface
# (GET /api/v1/cluster/provisioning). Surfaces the leader's last provisioning
# decision: reason / circuit-breaker state / membership deficit / latches /
# last-failure. Mirrors reset_provisioning_circuit's curl idiom (same endpoint
# family, same 15s hard timeout, same X-API-Key) but as a GET. Echoes the body
# truncated to ~600 chars on stdout.
#
# WHY: restore_cluster_baseline's terminal convergence gate (step 8 below) is a
# LOUD failure on non-convergence. When it fires we need the operator-visible
# "why didn't the cluster heal" signal inline in the failure log, not buried in
# a separate manual probe — this is exactly the observability surface the
# risk-first/observability-first hardening policy mandates for any
# provisioning/reconciler diagnosis. Tolerant by design: the endpoint may be
# unreachable mid-chaos (no leader, killed entry-point), so a transport failure
# returns empty + log_warn rather than aborting the caller.
provisioning_snapshot() {
    local ep body rc
    ep=$(_resolve_live_endpoint)
    body=$(curl -sk -m 15 -H "X-API-Key: ${API_KEY}" "${ep}/api/v1/cluster/provisioning" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "provisioning_snapshot: GET failed rc=${rc}: $(printf '%s' "$body" | head -c 200)"
        return 0
    fi
    printf '%s' "$body" | head -c 600
    return 0
}

# cluster_no_deficit — returns 0 iff the LEADER's provisioning view reports a WHOLE
# cluster: no missing cores (deficit==0) AND full membership reached. This is the
# lag-free authority for "all N cores present". The harness's own counts
# (cluster_active_core_count, ready_core_count) are fed by the SWIM-projection /
# per-node lifecycle query, which can lag the leader's membership view by 10s-100s of
# seconds on a genuinely-whole cluster — a present, counted core routinely reads
# not-yet-READY for a while (see restore step 5b). Gating terminal convergence on the
# leader's deficit (the #336 provisioning surface) instead of ready_core_count avoids
# falsely DEGRADING a healthy cluster, and is exactly the signal that matters for the
# next test's "N running cores" precondition (a killed-not-replaced node shows
# deficit>0, so the gate correctly waits for CTM auto-heal to bring deficit→0).
# Quiet (no logging) — called in a wait_for poll loop; provisioning_snapshot is the
# loud diagnostic for the failure path.
cluster_no_deficit() {
    local ep snap
    ep=$(_resolve_live_endpoint) || return 1
    snap=$(curl -sk -m 10 -H "X-API-Key: ${API_KEY}" "${ep}/api/v1/cluster/provisioning" 2>/dev/null) || return 1
    [ -n "$snap" ] || return 1
    printf '%s' "$snap" | grep -q '"deficit"[[:space:]]*:[[:space:]]*0' \
        && printf '%s' "$snap" | grep -q '"reachedFullMembership"[[:space:]]*:[[:space:]]*true'
}

# Operator-controlled toggle of CTM auto-heal (deficit-driven replacement
# provisioning). Distinct from the failure-driven circuit breaker — operators
# disable auto-heal during disruption-budget testing, planned maintenance
# windows, or any scenario where the cluster should not automatically rebuild
# after node loss. All three helpers below hit the leader-routed
# /api/v1/cluster/topology/auto-heal{,/enable,/disable} endpoints. Curl is used
# for parity with reset_provisioning_circuit (same shape, same 15s timeout) —
# the integration test harness does not wire the `aether` CLI binary into the
# cluster.sh helper layer.

# Disable CTM auto-heal. Idempotent and verify-after:
#   - Short-circuits with success if the cluster already reports auto-heal disabled.
#   - Issues the disable via the aether CLI (canonical management surface; handles
#     leader-forwarding internally).
#   - Re-reads the state and fails if the post-state is not the expected one
#     (defence-in-depth: CLI may exit 0 while a transient leader change leaves
#     state unchanged).
# Returns 0 on success (state is now disabled), 1 on CLI/transport failure or
# state-not-applied.
disable_auto_heal() {
    local pre_state result rc post_state
    pre_state=$(aether_failover cluster topology auto-heal status --format value --field enabled 2>/dev/null || echo "unknown")
    if [ "$pre_state" = "false" ]; then
        log_info "disable_auto_heal: already disabled (idempotent no-op)"
        return 0
    fi

    result=$(aether_failover cluster topology auto-heal disable 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "disable_auto_heal: CLI failed rc=${rc}: $(printf '%s' "$result" | head -c 200)"
        return 1
    fi
    log_info "disable_auto_heal: ${result}"

    post_state=$(aether_failover cluster topology auto-heal status --format value --field enabled 2>/dev/null || echo "unknown")
    if [ "$post_state" != "false" ]; then
        log_warn "disable_auto_heal: CLI returned success but post-state is '${post_state}' (expected 'false')"
        return 1
    fi
    return 0
}

# Enable CTM auto-heal. Symmetric to disable_auto_heal (idempotent, verify-after).
enable_auto_heal() {
    local pre_state result rc post_state
    pre_state=$(aether_failover cluster topology auto-heal status --format value --field enabled 2>/dev/null || echo "unknown")
    if [ "$pre_state" = "true" ]; then
        log_info "enable_auto_heal: already enabled (idempotent no-op)"
        return 0
    fi

    result=$(aether_failover cluster topology auto-heal enable 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "enable_auto_heal: CLI failed rc=${rc}: $(printf '%s' "$result" | head -c 200)"
        return 1
    fi
    log_info "enable_auto_heal: ${result}"

    post_state=$(aether_failover cluster topology auto-heal status --format value --field enabled 2>/dev/null || echo "unknown")
    if [ "$post_state" != "true" ]; then
        log_warn "enable_auto_heal: CLI returned success but post-state is '${post_state}' (expected 'true')"
        return 1
    fi
    return 0
}

# Print the current auto-heal enabled state ("true"/"false") on stdout.
# Returns 0 if the endpoint responded with a parseable boolean, 1 otherwise.
# Designed for use in test predicates like:
#   if [ "$(auto_heal_enabled)" = "false" ]; then ...
auto_heal_enabled() {
    local value rc
    value=$(aether_failover cluster topology auto-heal status --format value --field enabled 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log_warn "auto_heal_enabled: CLI failed rc=${rc}: $(printf '%s' "$value" | head -c 200)" >&2
        return 1
    fi
    if [ "$value" != "true" ] && [ "$value" != "false" ]; then
        log_warn "auto_heal_enabled: unexpected value '${value}'" >&2
        return 1
    fi
    printf '%s' "$value"
    return 0
}

# Semantic cluster-baseline restore (replaces `restart_all_nodes`).
#
# `restart_all_nodes` modeled the cluster as a fixed set of five compose
# containers with stable NodeIds — it executed `docker compose down/up`,
# dropped CTM-provisioned replacements, and waited for the ORIGINAL five
# cores to come back. This fights the product: killed nodes go DECOMMISSIONED
# (single-writer rule on NodeLifecycleKey — see spec §4.3 P4) and CTM auto-
# heal provisions replacements with new NodeIds. The cluster IS elastic;
# tests need to assert the post-state in product terms.
#
# `restore_cluster_baseline` consumes operator-visible signals only:
#   1. Re-enable CTM auto-heal (tests that exercised disruption budget may
#      have disabled it; the next suite expects deficits to self-heal).
#   2. Reset the CTM provisioning circuit breaker (a previous suite that
#      tripped it would block the auto-heal we just re-enabled).
#   3. Reactivate any DRAINING nodes left behind by an intentional drain test.
#   4. Set desired cluster size to NODE_COUNT (default 5) via /api/v1/cluster/scale.
#   5. Wait for exactly NODE_COUNT ON_DUTY healthy cores — ANY NodeIds, not
#      the original compose set. CTM is free to keep replacements; what we
#      care about is the operator-visible invariant "5 healthy cores".
#   6. Await ClusterGeneration quiescence so any in-flight reassignment commits
#      before the next suite reads cluster state.
#   7. Soft phase=NORMAL barrier (log_warn on miss; some pre-D.3 paths can
#      take longer than the budget under cumulative load).
#
# Returns 0 on full success, 1 if any of the hard barriers (steps 4-6) fail.
# Steps 1-3 are best-effort (log_warn) — they are pre-conditions for the
# hard barriers, and if those barriers pass anyway the cluster IS at
# baseline regardless of which pre-condition was actually needed.
## #628: the seven 02-chaos cleanups all downgraded a failed baseline restore to a
## warning, and `run_suite` had no gate between test FILES — a broken cluster then
## failed every downstream scenario on ITS subject, not the cluster's (rawReady=1
## on 2026-08-21; "got 0 containers" on 2026-08-22). On failure this logs FAIL and
## flags the suite runner via SUITE_RESTORE_FAILED_MARKER: run_suite aborts the
## remaining files (the same quarantine semantics the between-suites gate applies)
## and captures evidence IMMEDIATELY — the 2026-08-22 window was destroyed before
## the once-per-suite capture ran. Returns 0 always: the flag is the signal, and a
## nonzero rc from an EXIT trap would change the test file's own verdict.
restore_cluster_baseline_or_flag() {
    if restore_cluster_baseline; then
        return 0
    fi
    log_fail "restore_cluster_baseline FAILED — cluster is NOT at baseline"
    if [ -n "${SUITE_RESTORE_FAILED_MARKER:-}" ]; then
        touch "$SUITE_RESTORE_FAILED_MARKER" 2>/dev/null \
            || log_warn "could not flag the suite runner (marker write failed) — subsequent tests may inherit cluster churn"
    else
        log_warn "no suite runner to flag (standalone run) — subsequent tests may inherit cluster churn"
    fi
    return 0
}

restore_cluster_baseline() {
    local target="${NODE_COUNT:-5}"
    log_info "Restoring cluster to baseline (semantic): ${target} healthy cores, READY"

    # 0. API-reachability gate. Cluster B uses `restart: "no"` so a prior failed test
    # may have left the entry-point's reach-set without a healthy leader. If the
    # management API doesn't respond, escalate to a full restart (restart_all_nodes)
    # before giving up. Prior behaviour was to log_warn and
    # return 1, which left cluster B unrecoverable for every subsequent suite in
    # the run -- the 2026-05-22 cascade (02-chaos 0p/6f → 03-scaling 0p/3f → ...)
    # originated here. restart_all_nodes is provider-aware: docker/remote run a
    # compose down/up cycle; cloud powers powered-off VMs back on (cloud_revive_vm)
    # and waits on CTM auto-heal + mgmt-API readiness (there is no compose project on
    # cloud — the old `docker compose ... aether-b-network` path is a no-op there and
    # failed with `No resource found for project aether`).
    # Reliability gate reads through the BOUNDED curl path (cluster_leader_http), never the
    # CLI: the CLI-path cluster_leader can hang/degrade for reasons unrelated to cluster
    # health (macOS LNP, JVM connect-phase hangs against a dead fleet — the 2026-07-15
    # 3.5h silent restore hang), exactly the hazard the _http counterparts were built for.
    if ! cluster_leader_http >/dev/null 2>&1; then
        log_warn "restore_cluster_baseline: no leader reachable via management API — escalating to full cluster restart (restart_all_nodes)"
        if ! restart_all_nodes; then
            log_warn "restore_cluster_baseline: restart_all_nodes also failed; cluster is unrecoverable for this run"
            return 1
        fi
        # restart_all_nodes already waits for node count + leader + ON_DUTY internally
        # (see its tail). Re-check the leader gate so we don't fall through into the
        # restore steps with a half-converged cluster.
        if ! cluster_leader_http >/dev/null 2>&1; then
            log_warn "restore_cluster_baseline: leader still unreachable after restart"
            return 1
        fi
        log_info "restore_cluster_baseline: full cluster restart recovered the cluster — continuing restore"
    fi

    # 0b. Re-pin the management endpoint to a LIVE core. Chaos may have killed the
    # node CLUSTER_ENDPOINT was pinned to (cluster B is restart:"no" → it stays dead),
    # while CTM provisions replacements on fresh host ports. The leader-bound helpers
    # below (reset_provisioning_circuit, scale_cluster, await_generation_quiesced →
    # generation_current) target ${CLUSTER_ENDPOINT}; without this refresh they hit the
    # dead port and fail with curl rc=7 — which restore historically misreported as
    # "generation did not quiesce within 180s" (the #126 misdiagnosis: the cluster was
    # quiesced and healthy on a live node we simply never reached). _resolve_live_endpoint
    # rotates to any live seed or CTM replacement; it preserves the pin when it is up.
    _refresh_mgmt_entry_point || log_warn "restore_cluster_baseline: no live endpoint to re-pin (proceeding with ${CLUSTER_ENDPOINT})"

    # 1. Auto-heal — tests that ran disruption-budget or manual-only-recovery
    # scenarios may have disabled it. Idempotent: enabling an already-enabled
    # toggle is a no-op on the server side.
    enable_auto_heal || log_warn "restore_cluster_baseline: enable_auto_heal failed (proceeding)"

    # 2. Circuit breaker — a previous suite that exhausted CTM provisioning
    # slots will have tripped the breaker; leaving it tripped means step 5's
    # wait will time out even though desired size is 5.
    reset_provisioning_circuit || log_warn "restore_cluster_baseline: reset_provisioning_circuit failed (proceeding)"

    # 3. Reactivate any DRAINING node a test explicitly drained. Parse
    # /api/v1/nodes/lifecycle for state=DRAINING entries and POST activate. The
    # parser tolerates both Jackson field orderings (see pick_non_leader for
    # the same idiom).
    local lifecycle draining
    lifecycle=$(api_get "/api/v1/nodes/lifecycle" 2>/dev/null || true)
    if [ -n "$lifecycle" ]; then
        draining=$(printf '%s' "$lifecycle" \
            | grep -oE '"nodeId":"[^"]+","state":"DRAINING"|"state":"DRAINING","nodeId":"[^"]+"' \
            | grep -oE '"nodeId":"[^"]+"' \
            | sed 's/"nodeId":"\([^"]*\)"/\1/' || true)
        if [ -n "$draining" ]; then
            while IFS= read -r node_id; do
                [ -z "$node_id" ] && continue
                log_info "restore_cluster_baseline: reactivating DRAINING node ${node_id}"
                activate_node "$node_id" >/dev/null 2>&1 || \
                    log_warn "restore_cluster_baseline: activate_node ${node_id} failed (proceeding)"
            done <<< "$draining"
        fi
    fi

    # 4. Desired size — covers tests that left the cluster scaled to a non-5
    # value (e.g. 03-scaling, or a suite that disabled auto-heal and let
    # nodes go DECOMMISSIONED).
    if ! scale_cluster "$target"; then
        log_warn "restore_cluster_baseline: scale_cluster ${target} FAILED — the cluster was not resized and later tests may inherit un-reconciled replacement nodes (see #594; the scale_cluster warning above carries the HTTP status and body)"
    fi

    local floor=$((target - 1))

    # 4b. ACTIVE RECOVERY (cloud) — re-kick the things that UNWEDGE a stuck-below-target
    # cluster, then wait on a BOUNDED budget instead of the 600s (×TIMEOUT_SCALE=3 ⇒
    # 1800s on cloud) presence barrier below. Why this exists: on a live Hetzner run a
    # 2-node burst (Kill_2) left the cluster at 3/5 because the leader's
    # provisionReplacement kept failing (priorFailureCount=41 — the provider server quota
    # was exhausted by lingering powered-off victims; Fix 1 addresses the root by deleting
    # victims). When the cluster is stuck below target, the operator action that recovers
    # it is exactly `scale_cluster <target>` (forces CTM provisioning) + circuit-breaker
    # reset (clears the failure latch). The fixed 600s×3 step-5 wait then consumed ~1800s
    # before the runner could even decide to skip — killing the whole run before suites
    # 03/05/12/13. Here we (a) issue the active re-kick if we're below floor, (b) wait on
    # a cloud-aware BOUNDED timeout, and (c) on miss, MARK the cluster degraded and return
    # 1 PROMPTLY so run-tests.sh's unrecoverability gate quarantines the remaining suites
    # fast (skip-with-reason) rather than hanging. docker/remote skip this block entirely
    # and keep the unbounded 600s barrier — byte-identical there.
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        # Bounded base budget for the active-recovery presence wait (pre-TIMEOUT_SCALE).
        # ~4 min base × cloud TIMEOUT_SCALE(3) ⇒ ~12 min ceiling — generous enough for a
        # genuine VM-provision heal (~187s measured) plus jitter and a second
        # provision attempt, but a far cry from the 1800s that killed the run. Override
        # with AETHER_RESTORE_RECOVERY_TIMEOUT.
        local recover_base="${AETHER_RESTORE_RECOVERY_TIMEOUT:-240}"
        local cur
        cur=$(cluster_active_core_count)
        if [ "${cur:-0}" -lt "$floor" ] 2>/dev/null; then
            log_warn "restore_cluster_baseline: cloud cluster below floor (${cur:-0}/${floor}) — active recovery: reset circuit + re-scale to ${target}"
            # Reset the breaker FIRST (clears the failure latch that blocks the scale's
            # provisioning), then re-issue the scale to force CTM to provision the deficit.
            reset_provisioning_circuit || log_warn "restore_cluster_baseline: active-recovery reset_provisioning_circuit failed (proceeding)"
            scale_cluster "$target" || log_warn "restore_cluster_baseline: active-recovery scale_cluster ${target} failed (proceeding to bounded wait)"
        fi
        if ! wait_for "${floor}+ healthy cores present (cloud active recovery, target=${target})" \
            "[ \$(cluster_active_core_count) -ge ${floor} ]" "$recover_base"; then
            # Still degraded after bounded active recovery. Do NOT hang on the 1800s
            # barrier and do NOT escalate further — return degraded so the runner's
            # post-suite gate (ready/leader check) SKIPS remaining suites quickly. Each
            # subsequent suite still attempts to run only if a LATER restore recovers;
            # the runner records skipped suites explicitly.
            log_fail "restore_cluster_baseline: cloud cluster did not reach ${floor}+ healthy cores within bounded active-recovery budget (current=$(cluster_active_core_count), base=${recover_base}s × scale) — marking DEGRADED and returning so the run continues to the next suite"
            return 1
        fi
        # Below: the cloud path falls through to the SAME step-5b READY / step-6 quiesce /
        # step-7 phase barriers as docker/remote (those are already cloud-aware via
        # TIMEOUT_SCALE and bounded at 300/180/180s — none is the 1800s offender), but
        # SKIPS the unbounded 600s presence wait of step 5 (already satisfied above).
    else
        # 5. Hard barrier — at least N-1 healthy cores PRESENT. Operational invariant
        # (quorum is 3, so 4 has 1 of spare). Post-chaos, the CTM replacement IS alive in
        # generation within seconds (Auto-heal_restores_to_5 confirms) but the entry-point's
        # membership view may stay at 4 for a while — the SWIM-fed projection lags. `>= N-1`
        # accepts the operational invariant and unblocks the downstream cluster B suite
        # cascade. NOTE: presence is necessary but NOT sufficient — a present node can still
        # be SYNCING (re-syncing consensus) rather than READY. Step 5b waits for READY.
        if ! wait_for "${floor}+ healthy cores present (target=${target})" \
            "[ \$(cluster_active_core_count) -ge ${floor} ]" 600; then
            log_fail "restore_cluster_baseline: failed to converge to ${floor}+ healthy cores within 600s (current=$(cluster_active_core_count))"
            return 1
        fi
    fi

    # 5b. READY barrier — wait until N nodes REPORT READY (NodeReportedState=READY)
    # before declaring the baseline restored. Presence (step 5) only proves the cores
    # are in membership; a just-(re)provisioned or re-syncing core reports SYNCING
    # first and only flips to READY once it has caught up on consensus. Without this
    # barrier the next suite's `pick_non_leader --state READY` can find too few
    # candidates (the `pick_non_leader: only 1/2 candidates` wedge) because the cluster
    # is present-but-not-yet-READY. We count `aether nodes lifecycle --state READY` and
    # require >= floor READY (same N-1 tolerance as presence — one core may still be
    # catching up and that is acceptable for baseline). Soft-fail (log_warn): READY is a
    # convergence property that can lag under cumulative load; the downstream suite's own
    # readiness gate is the hard backstop, and pick_non_leader fails loudly if it still
    # can't find a candidate.
    # 5b. READY barrier (ADVISORY, NON-COUNTING — #17). ready_core_count is the SWIM-fed
    # `nodes lifecycle --state READY` projection that lags the leader's authoritative
    # membership by minutes on cloud; step 8 (leader deficit gate) is the REAL barrier.
    # Poll briefly so the next suite's `pick_non_leader --state READY` has candidates, but
    # do NOT use wait_for — its timeout emits a COUNTED [FAIL] (lib/common.sh:657) that
    # misreports READY lag as a product failure (it false-failed cluster-B twice, 900s each,
    # while step 8 reported deficit=0 immediately after). aether_failover inside
    # ready_core_count self-resolves a live endpoint, so this loop needs no poll hygiene.
    local _ready_budget=$(( 120 * ${TIMEOUT_SCALE:-1} ))
    # #441 wall-clock audit: ready_core_count self-resolves a live endpoint per call
    # (see comment above), so a dead/slow cluster makes a single call cost far more
    # than the nominal 5s sleep — the previous `_ready_waited += 5` counter assumed
    # otherwise and could silently balloon this advisory wait well past its budget.
    # Track real elapsed time via $SECONDS (same fix as lib/common.sh wait_for).
    local _ready_start=$SECONDS
    local _ready_deadline=$(( _ready_start + _ready_budget ))
    log_info "Advisory wait: ${floor}+ cores reporting READY (target=${target}, budget ${_ready_budget}s, NON-FATAL — leader deficit gate is authoritative)"
    while [ "$(ready_core_count)" -lt "${floor}" ] && [ "$SECONDS" -lt "${_ready_deadline}" ]; do
        sleep 5
    done
    local _ready_waited=$(( SECONDS - _ready_start ))
    local _ready_now; _ready_now=$(ready_core_count)
    if [ "${_ready_now}" -lt "${floor}" ]; then
        log_warn "restore_cluster_baseline: only ${_ready_now} core(s) reporting READY within ${_ready_budget}s (target=${target}); ADVISORY only (ready_core_count lags) — step 8 leader-deficit gate is authoritative; pick_non_leader --state READY may poll longer for a candidate"
    else
        log_info "restore_cluster_baseline: ${_ready_now}/${target} cores reporting READY (advisory barrier satisfied in ${_ready_waited}s)"
    fi

    # 6. Generation quiescence — ensures any in-flight slice/task reassignment
    # triggered by the convergence above has committed before the next suite
    # reads cluster state. Hard fail: if generation never quiesces the next
    # test sees mid-reassignment epoch flicker.
    # 180s budget (raised from 90s): under a 2-concurrent-replacement Kill_2, two
    # fresh replacements legitimately need >90s to all reach SWIM-HEALTHY; quiesce
    # is correctly DEGRADED while any core carries a non-HEALTHY SWIM hint
    # (ClusterGenerationProjector) — correct product behavior, not a bug. Consensus
    # catch-up for the fresh replacements is the dominant cost.
    local _quiesce_rc=0
    await_generation_quiesced "${CLUSTER_ENDPOINT}" "current" 180 || _quiesce_rc=$?
    if [ "$_quiesce_rc" -eq 2 ]; then
        log_fail "restore_cluster_baseline: cluster unreachable from ${CLUSTER_ENDPOINT} — could not read generation epoch (transport failure, NOT a quiescence timeout)"
        return 1
    elif [ "$_quiesce_rc" -ne 0 ]; then
        log_fail "restore_cluster_baseline: generation did not quiesce within 180s"
        return 1
    fi

    # 7. Soft phase=NORMAL — same rationale as the legacy restart_all_nodes
    # tail. Pre-D.3 (phase-split) paths can take >180s under cluster A+B
    # concurrent load and a hard fail cascades into broken cleanup state.
    if ! wait_for_phase "NORMAL" 180; then
        log_warn "restore_cluster_baseline: phase did not reach NORMAL within 180s; subsequent destructive tests may see SWIM cold-boot suppression (UnknownObserved instead of FaultyObserved)"
    fi

    # 8. TERMINAL CONVERGENCE GATE — the REAL exit barrier. Steps 5/5b/6/7 above
    # are deliberately N-1-tolerant FAST intermediate unblocks (a present-but-not-
    # quite-full cluster is good enough to stop blocking the cluster-B cascade), but
    # they NEVER assert the cluster is actually whole: step 5/5b stop at floor=N-1
    # cores and step 5b's READY wait is a soft log_warn, so a permanently-4-core
    # cluster passes them silently. Worse, NONE of 5/5b/6/7 looks at the deployed
    # blueprint — restore could return 0 with slices echoing UNLOADING / under target,
    # and the final log line falsely claimed "${target} healthy cores READY". The next
    # suite then ran against a half-recovered cluster (4 cores, partial slices) and
    # its failures were misattributed to that suite rather than to incomplete recovery.
    #
    # This gate converts that SILENT half-recovery into a LOUD, diagnosable failure. It
    # waits — on a generous, cloud-scaled budget — for the LEADER's authoritative
    # wholeness signal: NO core deficit (cluster_no_deficit: deficit==0 &&
    # reachedFullMembership — the lag-free "all ${target} cores present" signal, NOT the
    # SWIM-laggy ready_core_count which reads N-1 on a healthy cluster). It does NOT
    # assert per-blueprint slice-ACTIVE: blueprints ACCUMULATE across suites (e.g. 13
    # leaves test-persistence deployed) and rebalance for 10s+ after any chaos, so a
    # GLOBAL "all deployed slices ACTIVE" wrongly DEGRADES a healthy cluster on a
    # mid-rebalancing / non-baseline blueprint. Blueprint readiness is the consuming
    # test's concern — retarget_app_endpoint_to_active_slice now waits for its own
    # blueprint's ACTIVE owner (facet 1) rather than relying on this generic gate.
    # ~300s base × TIMEOUT_SCALE(3 cloud) ⇒ ~15min ceiling — generous enough for genuine
    # cloud auto-heal (~187s/VM); docker/local scale 1× (~5min). Override base via
    # AETHER_RESTORE_RECOVERY_TIMEOUT (shared with the step-4b active-recovery budget).
    local converge_base="${AETHER_RESTORE_RECOVERY_TIMEOUT:-240}"
    [ "$converge_base" -lt 300 ] 2>/dev/null && converge_base=300
    if ! wait_for "cluster WHOLE (leader deficit=0, all ${target} cores present) — terminal convergence" \
        "cluster_no_deficit" \
        "$converge_base"; then
        # DEGRADED return (matches steps 4b/5/6's contract): the runner's post-suite
        # gate quarantines remaining suites. The provisioning snapshot pins WHY the heal
        # stalled (reason/breaker/deficit/last-failure) so the next cloud stall is
        # diagnosable in minutes, not hours — #336 observability surface.
        local snap
        snap=$(provisioning_snapshot)
        log_fail "restore_cluster_baseline: cluster did not reach full core membership — cores present=$(cluster_active_core_count)/${target} READY=$(ready_core_count)/${target}, slices ACTIVE=$(slices_active_instances)/$(slices_target_total) (slice counts informational; gate=leader-deficit). provisioning: ${snap}"
        return 1
    fi

    log_info "restore_cluster_baseline: cluster at baseline (${target} cores present, leader deficit=0, generation quiesced)"
    return 0
}

# _extract_problem_detail — pull the `detail` field out of an RFC 9457
# application/problem+json body (or any JSON body shaped {"detail": "..."}).
# Same jq-with-grep-fallback idiom as push_blueprint's `.status` parse above:
# jq when available, a grep/sed extractor otherwise so the integration suite
# still runs on minimal CI images. Echoes the empty string (never fails) when
# the field is absent or the body isn't JSON — callers fall back to the raw
# body in that case.
_extract_problem_detail() {
    local body="$1"
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$body" | jq -r '.detail // empty' 2>/dev/null
    else
        printf '%s' "$body" | grep -oE '"detail"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 \
            | sed -E 's/.*"detail"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/'
    fi
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
    local url rc http_status
    local body_file
    body_file=$(mktemp -t scale_cluster.XXXXXX)
    # Capture HTTP status separately from body so we can distinguish transport
    # failures (rc!=0) from server-side errors (rc=0 + 4xx/5xx + JSON error body).
    # Pre-fix: curl without -f and without a status check returned rc=0 for
    # `{"error":"quorum unavailable"}`, so callers spun the full timeout waiting
    # for a scale that the server had already refused.
    #
    # Single transport for ALL environments (docker/remote/cloud): POST to a live
    # mgmt endpoint resolved by _resolve_live_endpoint. The mgmt API FORWARDS the
    # scale request to the consensus leader server-side (proven by the
    # `503 Management forward failed` log line when no leader), so the harness does
    # NOT need to reach the leader's own port. The previous cloud branch SSH'd to
    # the leader VM and curled localhost:8080 — that path could not reach a
    # CTM-provisioned leader (no operator SSH key) and returned `status:000`,
    # wedging 03-scaling. Dropping it makes cloud use the same forwarding path that
    # already works for docker/remote.
    local scale_ep
    scale_ep=$(_resolve_live_endpoint)
    url="${scale_ep}/api/v1/cluster/scale"
    http_status=$(curl -sk -m 90 -o "$body_file" -w '%{http_code}' \
                      -X POST -H "X-API-Key: ${API_KEY}" -H "Content-Type: application/json" \
                      -d "{\"role\":\"core\",\"count\":${target},\"expectedVersion\":0}" "$url")
    rc=$?
    local body
    body=$(head -c 500 "$body_file" 2>/dev/null)
    rm -f "$body_file"
    if [ "$rc" -ne 0 ]; then
        log_warn "scale_cluster: POST /api/v1/cluster/scale rc=${rc} (likely 90s timeout — cluster degraded; CTM circuit breaker may be tripped). Body: ${body}"
        return 1
    fi
    if [ -z "$http_status" ] || [ "$http_status" -lt 200 ] 2>/dev/null || [ "$http_status" -ge 300 ] 2>/dev/null; then
        # #837: distinguish an EXPECTED typed refusal (404/409 — e.g. no cluster config
        # stored after a volume wipe, or a version conflict) from a genuine 5xx failure.
        # Both used to log identically ("REJECTED with HTTP ...") with the body truncated
        # to 500 chars and never parsed, so an operator reading the log had to eyeball raw
        # JSON to find the actual reason. Surface `detail` explicitly either way.
        local detail
        detail=$(_extract_problem_detail "$body")
        case "$http_status" in
            404|409)
                log_warn "scale_cluster: POST /api/v1/cluster/scale refused as expected (HTTP ${http_status}, typed) — the cluster was NOT rescaled. detail: ${detail:-<none — body: ${body}>}"
                ;;
            *)
                log_warn "scale_cluster: POST /api/v1/cluster/scale FAILED with HTTP ${http_status:-<empty>} — the cluster was NOT rescaled. detail: ${detail:-<none — body: ${body}>}"
                ;;
        esac
        return 1
    fi
    log_info "Scale result: HTTP ${http_status} ${body}" >&2
    return 0
}

# POST to the leader node — finds leader via CLI, targets its management port
leader_api_post() {
    # Targets the consensus leader directly via its management port. CTM (cluster
    # topology manager) is leader-bound, so /api/v1/cluster/scale must reach the leader
    # for auto-provisioning to actually run.
    local path="$1"
    local body="${2:-"{}"}"
    if [ "$CLOUD_MODE" = "true" ]; then
        # Cloud: post to any live mgmt endpoint and let the server FORWARD to the
        # consensus leader (same forwarding contract scale_cluster relies on). The
        # previous SSH-tunnel-to-leader path could not reach a CTM-provisioned leader
        # VM (no operator SSH key → Permission denied) and is the exact SSH coupling
        # this work removes. api_post resolves a live core via _resolve_live_endpoint.
        api_post "$path" "$body"
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
    api_post "/api/v1/config" "$body"
}

config_export() {
    aether_json config
}

config_get_key() {
    local key="$1"
    api_get "/api/v1/config/${key}"
}

# ---------------------------------------------------------------------------
# Schema
# ---------------------------------------------------------------------------
schema_status() {
    local datasource="${1:-}"
    if [ -n "$datasource" ]; then
        api_get "/api/v1/schema/status/${datasource}"
    else
        api_get "/api/v1/schema/status"
    fi
}

schema_migrate() {
    local datasource="$1"
    api_post "/api/v1/schema/migrate/${datasource}" "{}"
}

schema_retry() {
    local datasource="$1"
    api_post "/api/v1/schema/retry/${datasource}" "{}"
}

schema_history() {
    local datasource="$1"
    api_get "/api/v1/schema/history/${datasource}"
}

schema_baseline() {
    local datasource="$1"
    api_post "/api/v1/schema/baseline/${datasource}" "{}"
}

schema_undo() {
    local datasource="$1"
    api_post "/api/v1/schema/undo/${datasource}" "{}"
}

# ---------------------------------------------------------------------------
# Streams
# ---------------------------------------------------------------------------
stream_list() {
    aether_json "streams list" 2>/dev/null || api_get "/api/v1/streams"
}

stream_info() {
    local name="$1"
    api_get "/api/v1/streams/${name}"
}

stream_publish() {
    local name="$1" body="$2"
    api_post "/api/v1/streams/publish/${name}" "$body"
}

# ---------------------------------------------------------------------------
# Control-plane readiness gate
# ---------------------------------------------------------------------------
# Historical name retained for the ~11 suite callers that use it as their
# "Cluster ready" precondition. The distributed task-group assignment model it
# originally polled (`GET /api/v1/cluster/tasks`, counting `assignments[].status ==
# ACTIVE`) was REMOVED: control-plane components (CDM, scaling, streaming, …) are
# now LEADER-PINNED — they activate on the elected leader rather than being
# "assigned" to task groups across nodes. The `/api/v1/cluster/tasks` endpoint is
# gone (404).
#
# New semantics: "cluster ready for control-plane ops" == a leader is elected AND
# the expected number of nodes are healthy/ON_DUTY. That is exactly the composite
# contract `wait_for_cluster_ready` already enforces (member count ≥ expected,
# leader elected, active core floor ≥ expected-1; see test-readiness-contract.md
# §1.1), so we delegate to it rather than re-deriving readiness here.
#
# Arg mapping (preserves the legacy call sites unchanged):
#   $1 timeout      — passed straight through.
#   $2 min_active   — was "min task groups ACTIVE"; now "min healthy nodes". Callers
#                     pass the cluster's expected node count (default NODE_COUNT).
wait_for_all_tasks_active() {
    local timeout="${1:-60}"
    local expected="${2:-${NODE_COUNT:-5}}"
    wait_for_cluster_ready "$timeout" "$expected"
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
    #
    # Stderr capture: distinguishes "no match" (grep rc=1 against empty stdout, ssh ok)
    # from "ssh dead" (rc!=0 from ssh itself). Pre-fix outer `2>/dev/null` ate both
    # silently, leaving stale containers and dead SSH sessions indistinguishable
    # from a legitimate "container not running" result.
    local err_file ssh_rc docker_out
    err_file=$(mktemp -t container_running.XXXXXX)
    docker_out=$(remote_exec "docker ps --filter 'name=${name}' --filter 'status=running' -q" 2>"$err_file")
    ssh_rc=$?
    if [ "$ssh_rc" -ne 0 ]; then
        log_warn "container_running: remote_exec rc=${ssh_rc} for '${name}': $(head -c 300 < "$err_file")"
        rm -f "$err_file"
        return 1
    fi
    rm -f "$err_file"
    printf '%s' "$docker_out" | grep -q . || return 1
    # Resolve the mgmt host port by CONTAINER-NAME lookup (NodeId == container_name).
    # `docker port <name> 8080/tcp` is ULID-safe: the old `MGMT_PORT + numeric-suffix`
    # derivation produced an empty/garbage offset for a CTM-minted ULID name (ends in a
    # letter), so the /health/live probe targeted a wrong port. In-container mgmt port is
    # 8080 for every node (MANAGEMENT_PORT=8080). Seeds map a fixed host port; replacements
    # map an ephemeral one — both resolvable via docker port / inspect HostPort.
    local port
    port=$(host_port_for_container "$name" 8080)
    if [ -z "$port" ]; then
        # No host-published mgmt mapping (or name not resolvable) — fall back to the
        # docker-only check, which already passed above.
        return 0
    fi
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
    api_post "/api/v1/deploy" "$body"
}

deploy_list() {
    api_get "/api/v1/deploy"
}

deploy_status() {
    local deployment_id="$1"
    api_get "/api/v1/deploy/${deployment_id}"
}

deploy_promote() {
    local deployment_id="$1"
    log_info "Promoting deployment: ${deployment_id}" >&2
    api_post "/api/v1/deploy/promote/${deployment_id}" "{}"
}

deploy_rollback() {
    local deployment_id="$1"
    log_info "Rolling back deployment: ${deployment_id}" >&2
    api_post "/api/v1/deploy/rollback/${deployment_id}" "{}"
}

deploy_complete() {
    local deployment_id="$1"
    log_info "Completing deployment: ${deployment_id}" >&2
    api_post "/api/v1/deploy/complete/${deployment_id}" "{}"
}

deploy_cleanup() {
    # Complete or rollback any active deployments via the LB management endpoint.
    local deployments
    deployments=$(deploy_list 2>/dev/null)
    # Extract deployment IDs that are not in terminal states.
    # The `grep -o ... || true` guard is load-bearing: under `set -euo pipefail`
    # an empty deploy list (the expected steady state) makes grep exit 1, which
    # propagates through pipefail and triggers errexit BEFORE the trailing
    # `return 0` below. Without the guard, every caller starting from a clean
    # cluster (e.g. test-deploy-blue-green / canary / rolling) aborts silently
    # with no PASS/FAIL/print_summary — exactly the 06-deployment failure
    # signature observed across many sessions.
    (printf '%s' "$deployments" | grep -o '"deploymentId"[[:space:]]*:[[:space:]]*"[^"]*"' || true) | sed 's/.*"deploymentId"[[:space:]]*:[[:space:]]*"//' | sed 's/"$//' | while read -r did; do
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
        "v=\$(json_value \"\$(curl -sfk -H 'X-API-Key: ${API_KEY}' ${endpoint}/api/v1/cluster/topology 2>/dev/null)\" coreCount 2>/dev/null); [ \"\${v:--1}\" -ge ${expected} ]" \
        "$timeout"
}

# Wait for leader election on a given endpoint
wait_for_leader_on() {
    local endpoint="$1"
    local timeout="${2:-30}"

    # Check `leaderId` field on /api/v1/nodes/status — the prior `role:ACTIVE` check matched any
    # topology entry with `role=ACTIVE` (a per-node attribute), so the predicate was
    # satisfied whenever ANY node reported its own role as ACTIVE, NOT when a leader
    # was elected. `/api/v1/nodes/status` returns `cluster.leaderId` populated from the elected
    # consensus leader (`ClusterConfigRoutes` is the model). The grep targets a
    # quoted non-empty value, so `"leaderId":null` and `"leaderId":""` both correctly
    # fail the predicate; `"leaderId":"node-3"` passes.
    wait_for "leader elected on ${endpoint}" \
        "curl -sfk -H 'X-API-Key: ${API_KEY}' ${endpoint}/api/v1/nodes/status 2>/dev/null | grep -qE '\"leaderId\"[[:space:]]*:[[:space:]]*\"[^\"]+\"'" \
        "$timeout"
}
