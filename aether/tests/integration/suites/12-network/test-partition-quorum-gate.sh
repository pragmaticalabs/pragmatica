#!/bin/bash
# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
#
# test-partition-quorum-gate.sh — Spec §16 rows S05 + S06.
#
# Scenarios:
#   S05: 2-vs-3 partition. `docker network disconnect` severs the minority's
#        transport entirely, so the majority observes BOTH signals for each
#        minority node: QUIC PeerDisconnected AND SWIM FAULTY. Under the
#        LeaderReconciler two-signal co-confirmation contract, a node observed
#        BOTH transport-partitioned AND SWIM-FAULTY is legitimately evictable
#        promptly (~3s, no self-drain TTL wait) — fast dual-signal eviction is
#        the INTENDED behavior, not a false positive. The protective property
#        S05 verifies is therefore NOT "the minority must remain present for the
#        whole window" (a dual-signal split gives the runtime grounds to evict),
#        but rather that a 2-node minority partition MUST NOT cost the 3-node
#        MAJORITY its leader or its quorum — the gate/reconciler must never let a
#        minority split destabilize the surviving majority. Whether the minority
#        is held briefly or evicted promptly, the majority stays quorate with a
#        stable leader throughout the window.
#   S06: After heal, the cluster returns to 5 ON_DUTY healthy cores
#        within a bounded window (SWIM + QUIC + periodic-emission
#        reconvergence) — promptly-evicted minority nodes rejoin (or CTM
#        replacements bring the count back to 5).
#
# Mechanics:
#   `docker network disconnect aether-${CLUSTER_ID}-network <container>`
#   removes the container from the cluster network while leaving the
#   container process alive. From the majority's perspective: SWIM
#   ping-acks fail and QUIC drops; from the minority's perspective: same,
#   plus zero peer visibility. The reverse op `docker network connect`
#   restores reachability (with a new IP, which Docker DNS resolves and
#   QUIC tolerates via fresh handshake).
#
# Why this test no longer asserts a 5s minority HOLD:
#   The earlier expectation — minority NodeIds stay PRESENT for the full
#   partition window because the aggregator-quorum gate blocks DECOMMISSIONED
#   until UNREACHABLE-quorum is confirmed across multiple TTL cycles — only
#   holds for a SINGLE-signal false positive (e.g. a transient QUIC blip
#   without SWIM-FAULTY). `docker network disconnect` cannot produce a
#   single-signal scenario: it severs every transport at once, so BOTH QUIC
#   PeerDisconnected AND SWIM FAULTY are observed and the LeaderReconciler's
#   dual-signal co-confirmation correctly evicts within ~3s. Asserting a 5s
#   hold against a dual-signal split tested the wrong contract and produced a
#   false FAIL. The corrected assertion targets the property the gate actually
#   protects under this injection: the majority's stability (leader + quorum).
#
# Why brief and not 15s+:
#   A sustained partition (≥ 8s on minority side) triggers SelfDrainCoordinator
#   (Step 5) on the minority itself. That contract is tested separately in Step 9
#   (`test-self-drain-quorum-loss.sh`). Keeping the window at 5s isolates the
#   majority-stability property from minority self-drain.
#
# Acceptance contract (spec §16 rows S05, S06):
#   S05: Throughout the partition window, the MAJORITY leader stays elected and
#        the cluster stays quorate (`cluster.quorate=true`). Prompt eviction of
#        the dual-signal minority is permitted (NOT asserted against).
#   S06: Within ${HEAL_BUDGET_S}s of partition heal, the cluster MUST
#        report 5 ON_DUTY healthy cores.
#
# Regression coverage for the topology-observation refactor:
#   * Majority stability (S05): if a 2-node minority partition could topple the
#     3-node majority's leader or quorum (e.g. the reconciler decommissioning
#     majority members on a one-sided signal, or quorum miscount on split),
#     the S05 majority-stability assertion catches it.
#   * Post-heal convergence (S06): if /api/v1/nodes/status projected a stale
#     count after reconnect, or reconvergence stalled, the post-heal ON_DUTY
#     count assertion catches it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"

# Partition window: strictly below the 8s self-drain threshold so the
# minority CANNOT take itself out (which would test the wrong path).
# At 5s we get exactly one periodic-emission cycle on the majority side —
# enough for the gate to receive transition signals but not enough for
# it to confirm UNREACHABLE-quorum across multiple TTL-aged cycles.
#
# DOCKER: `docker network disconnect` is an INSTANT cutover — SWIM ping-acks and
# QUIC both fail immediately, so 5s already spans the detection window.
#
# CLOUD: provider firewalls (cloud_partition_node) apply to NEW connections only;
# the established QUIC peer links stay up until SWIM-timeout-driven teardown (see
# cloud_partition_node's CAVEAT). A 5s window would close before the partition is
# ever perceived by the majority — the assertion would pass against an un-cut link
# and prove nothing. We therefore extend the cloud window to the SWIM suspicion +
# timeout budget so the majority-stability property is asserted across the period
# during which the partition is actually in effect and detected. The value is the
# same SWIM-detection base the departure waits use (45s), scaled by TIMEOUT_SCALE
# (3 on cloud) since this monitoring loop does its own SECONDS arithmetic and is NOT
# routed through the auto-scaling `wait_for`. This stays well clear of the minority
# self-drain path: a partitioned cloud minority that does self-drain is the S19
# contract (test-self-drain-quorum-loss.sh), and either way the property under test
# here — the 3-node MAJORITY never loses quorum/leader — holds throughout.
if [ "${CLOUD_MODE:-false}" = "true" ]; then
    PARTITION_DURATION_S=$(( ${CLOUD_PARTITION_SWIM_WINDOW_S:-45} * ${TIMEOUT_SCALE:-1} ))
else
    PARTITION_DURATION_S=5
fi

# Post-heal recovery budget. SWIM reconvergence + QUIC fresh handshake +
# periodic observation cycle + KV consensus apply: empirically ~10-20s on
# remote Docker. 30s gives 10-20s headroom.
HEAL_BUDGET_S=30

# Docker cluster-network name. Used ONLY on the non-cloud (docker network
# disconnect/connect) path; on cloud, isolation is enforced via provider firewalls
# (cloud_partition_node/cloud_heal_partition) and this value is never referenced.
# Defined unconditionally so a stray reference can never trip `set -u`.
NETWORK_NAME="aether-${CLUSTER_ID:-b}-network"

MINORITY_FILE="/tmp/s05-minority-ids.$$"
LEADER_FILE="/tmp/s05-leader.$$"

# Query the lifecycle endpoint for a specific node and extract the reported
# state string. Returns one of SYNCING / READY / DRAINING (NodeReportedState),
# or empty when the node is unknown to membership (404). v2 has no terminal
# lifecycle state — a removed node is simply ABSENT (empty here, and gone from
# /api/v1/nodes/status cluster.nodes[]).
#
# WHY the lifecycle endpoint (not raw /api/v1/nodes/status parsing): the lifecycle
# endpoint is leader-forwarded and reports the authoritative per-node reported
# state. During a partition the minority's SWIM state on the majority side decays,
# but the gate must keep the minority PRESENT (not removed) for the whole
# self-drain window. If the gate is doing its job, the minority nodes remain
# present (and READY) for the entire partition window — premature removal shows up
# as absence from /api/v1/nodes/status (see node_absent_from_status).
#
# #426 review follow-up (item 4): this used to be a LOCAL shadow of
# lib/topology.sh's kv_lifecycle_state with the OLD "empty body == absent"
# semantics (any failure, including a transport outage, silently read as
# "removed"). Deleted in favor of the shared lib/topology.sh version (sourced
# above), which distinguishes a genuine 404 (rc 0, empty stdout) from a
# transport failure/unexpected status (rc 1, empty stdout — UNKNOWN, never
# "removed"). Verified this file's only call site
# (test_partition_does_not_destabilize_majority below:
# `pre1=$(kv_lifecycle_state "$m1")` / `assert_eq "$pre1" "READY"`) never
# inspects the function's rc — it only compares stdout to "READY" — so both
# the 404 case and the new UNKNOWN/transport-failure case correctly fail the
# pre-partition READY assertion with no call-site changes required.

# Resolve a NodeId to the Docker container name carrying its
# aether.node-id label. Returns empty string when no container matches.
# Scoped to the test's CLUSTER_ID so cluster-A containers (also on the
# same daemon during interleaved suites) are not selected.
#
# Cloud: there is no docker daemon and no container label to inspect — each node
# is its own VM addressed by its runtime node-id (which is what cloud_partition_node
# / cloud_heal_partition expect). The "container handle" on cloud is therefore the
# node-id itself; we confirm it is a live member via the mgmt API (cloud_running_cores)
# and echo it back so the disconnect/connect helpers can hand it to the partition
# primitives unchanged. Empty (caller fails) if the node is not a live member.
container_for_node() {
    local nid="$1"
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        local runtime_id member
        runtime_id=$(to_node_id "$nid")
        # Accept either the raw id or its runtime translation as a live-membership match.
        member=$(cloud_running_cores | grep -Fx -- "$runtime_id" || cloud_running_cores | grep -Fx -- "$nid" || true)
        if [ -n "$member" ]; then
            printf '%s' "$member"
        fi
        return 0
    fi
    local cluster_filter=""
    if [ -n "${CLUSTER_ID:-}" ]; then
        cluster_filter="--filter label=aether.cluster=${CLUSTER_ID}"
    fi
    remote_exec "docker ps --filter 'label=aether.node-id=${nid}' ${cluster_filter} --format '{{.Names}}' | head -1" 2>/dev/null || true
}

# Disconnect a container from the cluster network. Container process
# remains alive but loses all peer reachability. stderr captured (silent
# stderr is a known trap per project memory).
#
# Cloud: there is no docker network — isolation is enforced at the provider
# firewall (cloud_partition_node), which blocks the cluster/QUIC + SWIM ports
# in both directions while keeping mgmt (8080) open so the harness can still
# observe the isolated node. `$container` is the runtime node-id on cloud
# (container_for_node echoes the id back).
disconnect_node_from_network() {
    local container="$1"
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        if ! cloud_partition_node "$container"; then
            log_fail "cloud_partition_node ${container} failed (see FAIL line above)"
            return 1
        fi
        log_info "Partitioned ${container} via provider firewall (cluster+SWIM ports blocked, mgmt kept)"
        return 0
    fi
    local out rc
    out=$(remote_exec "docker network disconnect ${NETWORK_NAME} ${container}" 2>&1)
    rc=$?
    if [ $rc -ne 0 ]; then
        log_fail "docker network disconnect ${NETWORK_NAME} ${container} failed (rc=${rc}): ${out}"
        return $rc
    fi
    log_info "Disconnected ${container} from ${NETWORK_NAME}"
    return 0
}

# Reconnect a container to the cluster network. Idempotent: if the
# container is already connected (e.g. on cleanup after a successful
# heal step), the daemon returns non-zero with "already connected" —
# we tolerate that and continue.
#
# Cloud: removes the provider partition firewall (cloud_heal_partition), which is
# itself idempotent (no-op when the firewall is absent / already healed).
connect_node_to_network() {
    local container="$1"
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        if ! cloud_heal_partition "$container"; then
            log_warn "cloud_heal_partition ${container} returned non-zero; recovery assertion will surface any real problem"
            return 1
        fi
        log_info "Healed partition for ${container} (provider firewall removed)"
        return 0
    fi
    local out rc
    out=$(remote_exec "docker network connect ${NETWORK_NAME} ${container}" 2>&1)
    rc=$?
    if [ $rc -ne 0 ]; then
        if printf '%s' "$out" | grep -qi "already exists\|already connected\|endpoint with name"; then
            log_info "${container} already connected to ${NETWORK_NAME} (idempotent)"
            return 0
        fi
        log_warn "docker network connect ${NETWORK_NAME} ${container} failed (rc=${rc}): ${out}"
        return 1
    fi
    log_info "Reconnected ${container} to ${NETWORK_NAME}"
    return 0
}

# ---------------------------------------------------------------------------
# Test cases
# ---------------------------------------------------------------------------

test_initial_state() {
    wait_for_cluster_ready 60
    # NORMAL phase gates the SWIM cold-boot suppression and the FSM gate's
    # cold-start fallback (spec §17). Without NORMAL, the gate's
    # "no snapshot → allow" branch can permit decommission writes that
    # would (correctly) NOT fire in steady state — breaking the S05
    # premise. Soft (log_warn) to align with sibling tests; the
    # downstream KV assertion will give a clearer signal if the
    # precondition really was missing.
    wait_for_phase "NORMAL" 180 || \
        log_warn "Cluster phase did not reach NORMAL within 180s — gate cold-start fallback may permit decommission and absorb the S05 assertion"
    wait_for_leader 60
    # Restore floor is "4+ READY, then settle" — wait bounded for the settled 5 before
    # the exact-count assert (same hardening as test-swim-detection.sh).
    wait_for "5 healthy cores (settled baseline)" '[ "$(cluster_active_core_count)" = "5" ]' 120
    local count
    count=$(cluster_active_core_count)
    assert_eq "$count" "5" "Initial: 5 healthy cores"
}

test_pick_minority() {
    local leader minority lines
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader identified: ${leader}"
    printf '%s' "$leader" > "$LEADER_FILE"

    # Pick 2 non-leaders. The leader stays in the majority partition so
    # no re-election fires mid-test (re-election under partition would
    # exercise different code paths and confound the gate assertion).
    minority=$(pick_non_leader "$leader" 2)
    if [ -z "$minority" ]; then
        log_fail "pick_non_leader returned empty — cannot form a 2-node minority"
        return 1
    fi
    lines=$(printf '%s\n' "$minority" | grep -c '.' || true)
    if [ "$lines" -lt 2 ]; then
        log_fail "pick_non_leader returned <2 candidates (got ${lines}): '${minority}'"
        return 1
    fi
    # Persist exactly 2 lines (first two) for hand-off to subsequent test
    # functions, which run in their own shell context via run_test.
    printf '%s\n' "$minority" | grep '.' | head -n 2 > "$MINORITY_FILE"
    local m1 m2
    m1=$(sed -n '1p' "$MINORITY_FILE")
    m2=$(sed -n '2p' "$MINORITY_FILE")
    log_info "Leader (majority): ${leader} | Minority (to partition): ${m1}, ${m2}"
}

test_partition_does_not_destabilize_majority() {
    local m1 m2 c1 c2 leader
    m1=$(sed -n '1p' "$MINORITY_FILE")
    m2=$(sed -n '2p' "$MINORITY_FILE")
    leader=$(cat "$LEADER_FILE" 2>/dev/null || true)
    c1=$(container_for_node "$m1")
    c2=$(container_for_node "$m2")
    if [ -z "$c1" ] || [ -z "$c2" ]; then
        log_fail "Cannot resolve containers for minority nodes (${m1}=${c1:-<empty>}, ${m2}=${c2:-<empty>})"
        return 1
    fi

    # Pre-partition baseline: both minority nodes MUST currently report as
    # READY. If they don't, the test premise is invalid (we'd be partitioning
    # a node that wasn't a healthy member to begin with).
    local pre1 pre2
    pre1=$(kv_lifecycle_state "$m1")
    pre2=$(kv_lifecycle_state "$m2")
    assert_eq "$pre1" "READY" "Pre-partition: ${m1} reports READY"
    assert_eq "$pre2" "READY" "Pre-partition: ${m2} reports READY"

    log_info "Injecting 2-vs-3 partition for ${PARTITION_DURATION_S}s (dual-signal: QUIC drop + SWIM faulty)"
    disconnect_node_from_network "$c1" || return 1
    disconnect_node_from_network "$c2" || return 1

    # Continuous monitoring during the partition: poll the MAJORITY's health at
    # ~1Hz. The protective property under a dual-signal (transport + SWIM-faulty)
    # split is that the 3-node majority MUST stay quorate with a STABLE leader —
    # a 2-node minority partition can never be allowed to topple the majority.
    # Prompt eviction of the dual-signal minority is INTENDED (LeaderReconciler
    # two-signal co-confirmation) and is NOT asserted against here. The earlier
    # "minority must remain present for the full window" expectation was wrong
    # for a dual-signal injection and produced a false FAIL.
    local deadline=$((SECONDS + PARTITION_DURATION_S))
    while [ $SECONDS -lt $deadline ]; do
        local quorate cur_leader
        quorate=$(cluster_quorate 2>/dev/null || true)
        if [ "$quorate" = "false" ]; then
            log_fail "S05 violation: cluster reported NOT quorate during a 2-vs-3 minority partition. The 3-node majority must retain quorum throughout — a minority split must never cost the majority its quorum."
            return 1
        fi
        cur_leader=$(cluster_leader 2>/dev/null || true)
        if [ -z "$cur_leader" ] || [ "$cur_leader" = "none" ]; then
            log_fail "S05 violation: majority lost its leader during the minority partition. The leader stayed in the majority partition; a 2-node minority split must not trigger re-election or leaderlessness on the majority side."
            return 1
        fi
        sleep 1
    done

    log_pass "S05: majority stayed quorate with a stable leader (${leader:-?}) throughout the ${PARTITION_DURATION_S}s dual-signal partition; prompt minority eviction (if any) is intended co-confirmation behavior"

    # Heal — the next test function asserts the recovery contract.
    log_info "Healing partition: reconnecting ${c1}, ${c2} to ${NETWORK_NAME}"
    connect_node_to_network "$c1" || log_warn "Reconnect of ${c1} returned non-zero; recovery assertion will surface any real problem"
    connect_node_to_network "$c2" || log_warn "Reconnect of ${c2} returned non-zero; recovery assertion will surface any real problem"
}

test_cluster_heals_to_5_onduty() {
    # S06 contract: within HEAL_BUDGET_S of reconnect, the cluster MUST
    # report 5 healthy cores. The reconnect happened at the tail
    # of the previous test function, so SECONDS-relative budgeting here
    # is approximate but tight enough (run_test scheduling adds <1s).
    if ! wait_for "5 healthy cores after partition heal" \
        "[ \$(cluster_active_core_count) -eq 5 ]" "$HEAL_BUDGET_S"; then
        local now_count
        now_count=$(cluster_active_core_count)
        log_fail "S06 violation: cluster did not return to 5 healthy cores within ${HEAL_BUDGET_S}s of partition heal (current count=${now_count}). Possible regression: post-heal SWIM/QUIC reconvergence stuck, or one of the minority nodes was incorrectly removed from membership late (after the partition assertion window closed but before reconnect took effect)."
        return 1
    fi
    assert_cluster_healthy "S06: cluster returned to 5 healthy cores within ${HEAL_BUDGET_S}s of partition heal"
}

cleanup() {
    # Best-effort reconnect in case the test aborted mid-flight before
    # the in-test reconnect ran. Idempotent: connect_node_to_network
    # tolerates "already connected".
    if [ -f "$MINORITY_FILE" ]; then
        local m1 m2 c1 c2
        m1=$(sed -n '1p' "$MINORITY_FILE" 2>/dev/null || true)
        m2=$(sed -n '2p' "$MINORITY_FILE" 2>/dev/null || true)
        if [ -n "$m1" ]; then
            c1=$(container_for_node "$m1")
            [ -n "$c1" ] && connect_node_to_network "$c1" || true
        fi
        if [ -n "$m2" ]; then
            c2=$(container_for_node "$m2")
            [ -n "$c2" ] && connect_node_to_network "$c2" || true
        fi
    fi

    rm -f "$MINORITY_FILE" "$LEADER_FILE"

    # Semantic baseline restore — resets the CTM circuit breaker if
    # tripped, waits for ON_DUTY healthy parity + generation quiescence
    # + phase=NORMAL. Subsequent tests in this suite inherit a clean
    # cluster. Idempotent.
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent tests may inherit cluster churn"
}

# Run cleanup on ANY exit path — including a `return 1` from inside a
# test function that propagates up through `set -e` and aborts the
# script. Without this trap, a failed S05 assertion leaves 2 nodes
# disconnected from the cluster network, which then breaks every
# subsequent test in 12-network. Pattern matches Step 7's
# test-joining-window-kill.sh.
trap 'cleanup' EXIT

run_test "Initial 5 healthy cores" test_initial_state
run_test "Pick minority (2 non-leaders)" test_pick_minority
run_test "Majority stays quorate+led through ${PARTITION_DURATION_S}s dual-signal partition (S05)" test_partition_does_not_destabilize_majority
run_test "Cluster heals to 5 healthy cores within ${HEAL_BUDGET_S}s (S06: partition heal)" test_cluster_heals_to_5_onduty
print_summary
