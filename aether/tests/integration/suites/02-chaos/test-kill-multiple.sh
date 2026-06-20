#!/bin/bash
# test-kill-multiple.sh — Kill 2 nodes, verify cluster survives with 3 (quorum)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

test_initial_state() {
    wait_for_cluster_ready 60
    # Wait for phase=NORMAL to bypass SWIM cold-boot suppression of NODE_FAILED events.
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still COLD_BOOT; chaos kill may produce UnknownObserved (no NODE_FAILED event)"
    wait_for_leader 60
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
}

test_kill_two_nodes() {
    # Wait for a leader before capture — chaos-tail instability can drop the
    # leader transiently between test_initial_state and this function. A bare
    # `leader=$(cluster_leader)` would return 1 under set -e and abort the
    # script silently. See test-kill-leader.sh for the original observation.
    if ! wait_for_leader 60; then
        log_fail "Pre-kill: cluster has no leader within 60s"
        return 1
    fi
    local leader
    leader=$(cluster_leader)

    # RE-RESOLVE-AT-KILL victim model (replaces the up-front `pick_non_leader 2`
    # SET + single shared baseline). WHY: the kill→assert window for victim 2 runs
    # AFTER victim 1's departure, during which CTM auto-heal may DELETE a
    # not-yet-killed pre-picked victim. If victim 2 is chosen up front and then
    # auto-heal removes it before we kill it, its NODE_LEFT/NODE_FAILED is invisible
    # against the single pre-kill baseline → "second victim within 90s" false fails
    # even though the cluster behaved correctly. We therefore (a) pick each victim
    # immediately before killing it, (b) capture that victim's departure baseline
    # PER-KILL right before the kill, and (c) on cloud, treat a victim that already
    # vanished (no server resolves) as a SATISFIED departure rather than waiting for
    # a fresh event that will never fire. The CONTRACT ("cluster survives concurrent
    # loss of 2 nodes") is preserved — true single-RTT simultaneity is already
    # impossible across separate VMs; what matters is the quorum/survival assertion
    # below, not the picking mechanism.
    KILLED_VICTIM1=""
    KILLED_VICTIM2=""

    # --- Victim 1: pick → per-kill baseline → kill → confirm departure ---
    local victim1
    victim1=$(pick_non_leader "$leader" 1) || {
        log_fail "Pre-kill: could not resolve first non-leader victim"
        return 1
    }
    KILLED_VICTIM1="$victim1"
    if ! _kill_and_confirm_departure "$victim1"; then
        return 1
    fi

    # --- Victim 2: re-pick (excluding victim 1) → per-kill baseline → kill ---
    # Re-derive the leader (a re-election may have moved it after victim 1's
    # departure) and EXCLUDE victim 1 so a momentary READY-flicker of the
    # just-killed node can't hand it back as victim 2.
    if ! wait_for_leader 60; then
        log_fail "Post-first-kill: cluster has no leader within 60s"
        return 1
    fi
    leader=$(cluster_leader)
    local victim2
    victim2=$(PICK_EXCLUDE="${PICK_EXCLUDE:-} ${victim1}" pick_non_leader "$leader" 1) || {
        log_fail "Pre-kill: could not resolve second non-leader victim (excluding ${victim1})"
        return 1
    }
    KILLED_VICTIM2="$victim2"
    if ! _kill_and_confirm_departure "$victim2"; then
        return 1
    fi

    # ClusterGeneration commits the post-kill membership view. After quiescence
    # the count reflects whatever CTM has done — just assert quorum.
    wait_for_node_count 5 240 || \
        log_warn "Post-kill quiescence not observed within 240s"
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "3" "Cluster survives with quorum after 2 kills (${count} nodes)"
}

# Kill one victim with kill-time re-resolution of its departure. Captures the
# departure baseline immediately before the kill (so the event poll is scoped to
# THIS kill's post-window, not a stale up-front baseline), then kills and confirms
# departure. On cloud, if the victim's VM no longer resolves the node is ALREADY
# gone (CTM auto-heal deleted it during a prior victim's recovery window) — that IS
# the departure, so we treat it as satisfied and do NOT wait for a fresh event that
# can never fire. Returns 0 on confirmed/satisfied departure, 1 otherwise.
_kill_and_confirm_departure() {
    local victim="$1"

    # Cloud-only fast path: a victim auto-heal already deleted resolves to no
    # server. cloud_kill_vm itself is idempotent here (returns 0 on "already gone"),
    # but we must ALSO short-circuit the event-wait — the departure already happened
    # before our baseline, so polling for a post-baseline NODE_LEFT would time out.
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        if ! cloud_server_id "$victim" >/dev/null 2>&1; then
            log_info "Victim ${victim} already gone (no server resolves) — CTM auto-heal removed it; treating as satisfied departure"
            return 0
        fi
    fi

    # Per-kill baseline: capture topology NOW, immediately before this kill, so the
    # event-driven barriers below scope their search to THIS kill's post-window. A
    # single up-front baseline (the old model) let auto-heal of an earlier victim
    # consume the only baseline and hide a later victim's departure event.
    local baseline
    baseline=$(topology_now)

    log_info "Killing node: ${victim}"
    kill_node "$victim"

    # Dual-signal departure (same rationale as the original single-baseline model):
    # if the victim never reached core-HEALTHY membership, no coreMemberIds-delta
    # event fires (correct product behavior), so fall back to membership-absence
    # (wait_for_node_removed: 404 OR absent from status).
    if ! wait_for_node_departure "$victim" "$baseline" 90 \
        && ! wait_for_node_removed "$victim" 90; then
        log_fail "No NODE_LEFT/NODE_FAILED event AND not removed from membership for victim ${victim} within 90s"
        return 1
    fi
    log_pass "Departure of ${victim} observed"
    return 0
}

test_quorum_maintained() {
    local health
    health=$(aether_field health status)
    assert_eq "$health" "healthy" "Cluster healthy after 2 kills"
}

test_leader_still_active() {
    local leader
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader active with 3 nodes: ${leader}"
}

test_auto_heal() {
    log_info "Waiting for CTM auto-heal to quiesce at the post-replacement generation..."
    wait_for_node_count 5 240 || {
        log_fail "Cluster did not quiesce after auto-heal"
        return 1
    }
    local count
    count=$(cluster_member_count)
    assert_eq "$count" "5" "Auto-heal restored cluster to exactly 5 nodes"
}

cleanup() {
    # Semantic baseline restore — see test-kill-leader.sh:cleanup() for the
    # rationale. `restore_cluster_baseline` re-enables auto-heal, resets the
    # CTM circuit breaker, scales to NODE_COUNT, then waits for N ON_DUTY
    # healthy cores + generation quiescence + soft phase=NORMAL.
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}

# Run cleanup on ANY exit path — including a `return 1` from inside a test
# function that propagates up through `set -e` and aborts the script. Without
# this trap, a failed kill / quorum assertion left the cluster in a degraded
# state, which then broke every subsequent test-*.sh in 02-chaos (observed
# 2026-05-22: 0p/6f cascade).
trap 'cleanup' EXIT

run_test "Initial 5 nodes" test_initial_state
run_test "Kill 2 nodes" test_kill_two_nodes
run_test "Quorum maintained with 3" test_quorum_maintained
run_test "Leader still active" test_leader_still_active
run_test "Auto-heal restores to 5" test_auto_heal
# cleanup runs via EXIT trap — guarantees baseline restore even if a run_test
# above triggers `set -e` abort via `return 1` from inside a test function.
print_summary
