#!/bin/bash
# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
#
# test-partition-quorum-gate.sh — Spec §16 rows S05 + S06.
#
# Scenarios:
#   S05: 2-vs-3 partition. The aggregator-quorum gate (Step 4 of the
#        topology-observation refactor) blocks DECOMMISSIONED writes for
#        peers whose UNREACHABLE state has not yet been quorum-confirmed.
#        A brief partition window (< self-drain threshold = 8s, ≤ 1
#        periodic aggregator cycle = 5s) does not give the gate enough
#        time to confirm UNREACHABLE-quorum, so the FSM cells
#        `(OnDuty, SwimFaulty)` and `(OnDuty, TransportUnreachable)` MUST
#        nop and the minority NodeIds MUST remain non-DECOMMISSIONED.
#   S06: After heal, the cluster returns to 5 ON_DUTY healthy cores
#        within a bounded window (SWIM + QUIC + periodic-emission
#        reconvergence).
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
# Why brief and not 15s+:
#   A sustained partition (≥ 8s on minority side) triggers SelfDrainCoordinator
#   (Step 5) — the minority takes itself out, the majority correctly
#   decommissions via aggregator-confirmed quorum, and CTM auto-heals.
#   That contract is tested separately in Step 9
#   (`test-self-drain-quorum-loss.sh`). This test isolates the GATE
#   behavior by keeping the partition window strictly below 8s, so
#   self-drain CANNOT fire and the gate is the sole arbiter.
#
# Acceptance contract (spec §16 rows S05, S06):
#   S05: Throughout the partition window, the majority leader's KV view
#        of the minority NodeIds (queried via /api/nodes/lifecycle/<id>)
#        MUST NOT progress to DECOMMISSIONED.
#   S06: Within ${HEAL_BUDGET_S}s of partition heal, the cluster MUST
#        report 5 ON_DUTY healthy cores.
#
# Regression coverage for the topology-observation refactor:
#   * Step 2 (event vocabulary): if (OnDuty, TransportUnreachable) cells
#     were missing/wired wrong, the FSM would drop the event or fall
#     through to a default that DOES decommission. The S05 assertion
#     would catch the false-positive write.
#   * Step 3 (aggregator → FSM wiring): if the snapshot listener fired
#     without the gate (i.e., naively followed the snapshot), brief
#     partitions would decommission. S05 catches.
#   * Step 4 (gate): if the gate's cold-start fallback (no snapshot →
#     allow) leaked into the steady-state path, the gate would permit
#     decommission on every UNREACHABLE event regardless of quorum.
#     S05 catches.
#   * Step 6 (MembershipView): if /api/nodes/status projected DECOMMISSIONED
#     from a transient SWIM-only signal that hadn't reached KV, the
#     downstream count would drift. S06 catches via the post-heal
#     ON_DUTY count assertion.

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
PARTITION_DURATION_S=5

# Post-heal recovery budget. SWIM reconvergence + QUIC fresh handshake +
# periodic observation cycle + KV consensus apply: empirically ~10-20s on
# remote Docker. 30s gives 10-20s headroom.
HEAL_BUDGET_S=30

NETWORK_NAME="aether-${CLUSTER_ID:-b}-network"

MINORITY_FILE="/tmp/s05-minority-ids.$$"
LEADER_FILE="/tmp/s05-leader.$$"

# Query the lifecycle endpoint for a specific node and extract the reported
# state string. Returns one of SYNCING / READY / DRAINING (NodeReportedState),
# or empty when the node is unknown to membership (404). v2 has no terminal
# lifecycle state — a removed node is simply ABSENT (empty here, and gone from
# /api/nodes/status cluster.nodes[]).
#
# WHY the lifecycle endpoint (not raw /api/nodes/status parsing): the lifecycle
# endpoint is leader-forwarded and reports the authoritative per-node reported
# state. During a partition the minority's SWIM state on the majority side decays,
# but the gate must keep the minority PRESENT (not removed) for the whole
# self-drain window. If the gate is doing its job, the minority nodes remain
# present (and READY) for the entire partition window — premature removal shows up
# as absence from /api/nodes/status (see node_absent_from_status).
kv_lifecycle_state() {
    local target="$1"
    local body
    body=$(api_get "/api/nodes/lifecycle/${target}" 2>/dev/null || true)
    if [ -z "$body" ]; then
        return 0
    fi
    printf '%s' "$body" \
        | grep -o '"state"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | head -1 \
        | sed 's/"state"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/'
}

# Resolve a NodeId to the Docker container name carrying its
# aether.node-id label. Returns empty string when no container matches.
# Scoped to the test's CLUSTER_ID so cluster-A containers (also on the
# same daemon during interleaved suites) are not selected.
container_for_node() {
    local nid="$1"
    local cluster_filter=""
    if [ -n "${CLUSTER_ID:-}" ]; then
        cluster_filter="--filter label=aether.cluster=${CLUSTER_ID}"
    fi
    remote_exec "docker ps --filter 'label=aether.node-id=${nid}' ${cluster_filter} --format '{{.Names}}' | head -1" 2>/dev/null || true
}

# Disconnect a container from the cluster network. Container process
# remains alive but loses all peer reachability. stderr captured (silent
# stderr is a known trap per project memory).
disconnect_node_from_network() {
    local container="$1"
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
connect_node_to_network() {
    local container="$1"
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

test_partition_does_not_decommission_within_window() {
    local m1 m2 c1 c2
    m1=$(sed -n '1p' "$MINORITY_FILE")
    m2=$(sed -n '2p' "$MINORITY_FILE")
    c1=$(container_for_node "$m1")
    c2=$(container_for_node "$m2")
    if [ -z "$c1" ] || [ -z "$c2" ]; then
        log_fail "Cannot resolve containers for minority nodes (${m1}=${c1:-<empty>}, ${m2}=${c2:-<empty>})"
        return 1
    fi

    # Pre-partition baseline: both minority nodes MUST currently report as
    # READY. If they don't, the test premise is invalid (we'd be asserting
    # "node is not prematurely removed" on a node that wasn't a healthy
    # member to begin with).
    local pre1 pre2
    pre1=$(kv_lifecycle_state "$m1")
    pre2=$(kv_lifecycle_state "$m2")
    assert_eq "$pre1" "READY" "Pre-partition: ${m1} reports READY"
    assert_eq "$pre2" "READY" "Pre-partition: ${m2} reports READY"

    log_info "Injecting 2-vs-3 partition for ${PARTITION_DURATION_S}s (< 8s self-drain threshold; gate is sole arbiter)"
    disconnect_node_from_network "$c1" || return 1
    disconnect_node_from_network "$c2" || return 1

    # Continuous monitoring during the partition: poll the majority leader's
    # membership view at ~1Hz and FAIL the test the instant either minority
    # NodeId is REMOVED from membership. v2 has no DECOMMISSIONED lifecycle
    # state — premature removal manifests as the node disappearing from
    # /api/nodes/status cluster.nodes[] (node_absent_from_status). The gate
    # must keep the partitioned-but-not-confirmed-dead minority PRESENT for
    # the whole self-drain window. The 1Hz cadence is fine because the gate's
    # failure mode would manifest within 1-2 detection cycles (≈1s each).
    local deadline=$((SECONDS + PARTITION_DURATION_S))
    while [ $SECONDS -lt $deadline ]; do
        if node_absent_from_status "$m1"; then
            log_fail "S05 violation: ${m1} was REMOVED from membership within the ${PARTITION_DURATION_S}s partition window. The aggregator-quorum gate (Step 4) failed to block the removal — likely regression in ReachabilityGate.isConfirmedUnreachable or the snapshot-supplier wiring at AetherNode."
            return 1
        fi
        if node_absent_from_status "$m2"; then
            log_fail "S05 violation: ${m2} was REMOVED from membership within the ${PARTITION_DURATION_S}s partition window. The aggregator-quorum gate (Step 4) failed to block the removal — likely regression in ReachabilityGate.isConfirmedUnreachable or the snapshot-supplier wiring at AetherNode."
            return 1
        fi
        sleep 1
    done

    log_pass "S05: ${m1} and ${m2} remained PRESENT in membership throughout ${PARTITION_DURATION_S}s partition (gate blocked premature removal)"

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
run_test "Partition holds gate within ${PARTITION_DURATION_S}s (S05: no false decommission)" test_partition_does_not_decommission_within_window
run_test "Cluster heals to 5 healthy cores within ${HEAL_BUDGET_S}s (S06: partition heal)" test_cluster_heals_to_5_onduty
print_summary
