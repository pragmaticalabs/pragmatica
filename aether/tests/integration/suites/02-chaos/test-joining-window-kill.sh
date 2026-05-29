#!/bin/bash
# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
#
# test-joining-window-kill.sh — Spec §16 row S01.
#
# Scenario:
#   A node is killed BEFORE it ever transitions to SWIM HEALTHY (i.e. it is in
#   the JOINING window). The intent: a killed replacement R must be removed from
#   the cluster FAST — well inside the S01 budget — rather than lingering as a
#   phantom member until some slow detector notices.
#
# v2 signal (membership redesign):
#   v2 has NO NodeLifecycleKey DECOMMISSIONED state and NO decommission-reason
#   domain event. A killed node simply LEAVES the SWIM-fed membership. The
#   v2-correct "node was removed" signal (already verified elsewhere) is twofold,
#   either being sufficient:
#     - /api/nodes/lifecycle/<R> returns HTTP 404 (LIFECYCLE_NOT_FOUND), AND/OR
#     - R is absent from /api/nodes/status cluster.nodes[].
#
# Acceptance contract (spec §16 row S01):
#   "R is removed from membership within the S01 budget" of kill, observable via
#   the dual v2 signal above. Budget is 90s (see DECOMMISSION_BUDGET_S below): a
#   JOINING-window kill is reclaimed by SWIM departure/failure detection inside
#   that budget. The SLA semantics are preserved — the budget value is unchanged
#   — only the observed signal is re-expressed for v2 (membership-absence in
#   place of the retired DECOMMISSIONED lifecycle write + reason event).
#
# Test outline:
#   1. Cluster B at 5 ON_DUTY/HEALTHY cores, NORMAL phase.
#   2. Kill a non-leader V to trigger CTM auto-heal.
#   3. As soon as DockerComputeProvider starts the replacement R, the container
#      carries label `aether.node-id=<R>` — visible to `docker ps` before R has
#      a SWIM HEALTHY state. We poll the label set to discover R's NodeId.
#   4. Kill R immediately (target: within ~2-3s of its container start, well
#      before SWIM would have marked it HEALTHY).
#   5. Record kill timestamp; assert R is REMOVED from membership (404 from
#      /api/nodes/lifecycle/<R> OR absent from /api/nodes/status cluster.nodes[])
#      within the 90s S01 budget.
#   6. Hygiene: pick_non_leader() must NOT return R after its removal.
#
# Why this catches a regression:
#   * If SWIM departure detection regresses: R will not be removed within the
#     budget — it lingers in cluster.nodes[] and /api/nodes/lifecycle keeps
#     returning a live state — and the budget assertion fails.
#   * If the /api/nodes/status projection regresses (e.g. stale membership
#     overlay): R stays visible in cluster.nodes[] past the budget.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"
source "${SCRIPT_DIR}/../../lib/generation.sh"

# Acceptance budget per spec §16 row S01.
# Updated 2026-05-19c: empirical floor under remote Docker is ~17s, not the
# theoretical ~5s. Dominant delay is QUIC drop detection for SIGKILL'd peers
# (no TCP RST; QUIC-over-UDP detects via idle-timeout / failed-send).
#
# Updated 2026-05 (S01 timing tuning): raised to 60s. For a JOINING-window kill the
# transport-aggregator quorum path frequently does NOT fire within ~17s — a peer
# killed before establishing connections leaves the aggregator with no quorum to
# reach UNREACHABLE — so the decommission is driven by the reconciler JoiningTimeout
# rule instead, which reclaims a SWIM-faulty/absent JOINING peer at JOIN_DEADLINE ×
# 0.75 = 45s (JoiningTimeout.BUDGET_MULTIPLIER; the FSM join deadline stays 60s). The
# replacement can also race into ON_DUTY before the kill — then cleanup uses the slower
# (ON_DUTY, SwimDeparted) ~61s path. 90s covers both fallbacks plus the ~17s fast path;
# the smoking-gun reason assertion below pins which one fired.
DECOMMISSION_BUDGET_S=90

# Maximum time we'll wait for CTM to provision a replacement container after
# the priming kill. CTM circuit breaker, slot deadlines, leader-forwarding, and
# remote SSH latency dominate this number — generous to keep the test focused
# on the JOINING-window assertion, not on CTM provisioning speed.
REPLACEMENT_DISCOVERY_TIMEOUT_S=90

# Files we use to ferry state between test functions (each test function runs
# in its own shell context via run_test, so we cannot rely on environment vars
# alone for hand-off between functions).
PRELABEL_SNAPSHOT_FILE="/tmp/s01-prelabel-snapshot.$$"
REPLACEMENT_NODE_ID_FILE="/tmp/s01-replacement-nodeid.$$"
KILL_TIMESTAMP_FILE="/tmp/s01-kill-timestamp.$$"
PRIMING_VICTIM_FILE="/tmp/s01-priming-victim.$$"
# Marker written if R raced past JOINING into ON_DUTY before the kill landed.
# When present, the smoking-gun log assertion is skipped: the ON_DUTY cells
# for both `transport-failure` and `swim-faulty` are gated by aggregator quorum
# (ClusterMembershipReducer.java:184,203), so the kill is decommissioned via the
# ungated `(ON_DUTY, SwimDeparted)` cell which emits `reason=swim-departed` —
# not in S01's accepted reason set. The 60s budget assertion above remains the
# meaningful contract in that branch.
RACE_TO_ON_DUTY_FILE="/tmp/s01-race-to-on-duty.$$"

# Snapshot of `aether.node-id` labels on TARGET_HOST scoped to this cluster.
# Returns one node-id per line, sorted. Empty string on transport failure.
snapshot_node_id_labels() {
    local cluster_filter=""
    if [ -n "${CLUSTER_ID:-}" ]; then
        cluster_filter="--filter label=aether.cluster=${CLUSTER_ID} --filter network=aether-${CLUSTER_ID}-network"
    fi
    # `docker ps --format '{{.Label "aether.node-id"}}'` prints the label value
    # per running container. Sort and dedupe so set-diff (`comm -13`) works.
    remote_exec "docker ps ${cluster_filter} --format '{{.Label \"aether.node-id\"}}' 2>/dev/null | sort -u" \
        2>/dev/null || true
}

# Wait until R's KV-backed lifecycle atom reflects state in $1 (JOINING or
# ON_DUTY — either is an acceptable pre-kill state, with JOINING preferred
# because it exercises the (JOINING, TransportUnreachable) cell that S01
# specifically targets).
#
# Returns 0 + prints the observed state on stdout; 1 on timeout.
#
# Poll cadence: 200ms — JOINING is a transient state that may exist for
# <1s before transitioning to ON_DUTY. A 1s poll cadence will frequently
# miss JOINING entirely, leaving us only able to kill at ON_DUTY (which
# exercises the gated S02 path rather than the ungated S01 path). 200ms
# gives us a strong chance of catching the JOINING window.
wait_for_replacement_in_kv() {
    local target="$1" timeout="${2:-90}"
    local deadline_ns
    deadline_ns=$(( $(date +%s) * 1000 + timeout * 1000 ))
    while [ "$(( $(date +%s) * 1000 ))" -lt "$deadline_ns" ]; do
        local state
        state=$(kv_lifecycle_state "$target")
        case "$state" in
            JOINING|ON_DUTY)
                printf '%s' "$state"
                return 0
                ;;
        esac
        # 200ms cadence to maximize chance of catching the JOINING window
        # before the FSM commits the ON_DUTY transition.
        sleep 0.2
    done
    return 1
}


# Wait until a new aether.node-id label appears on TARGET_HOST that was NOT
# present in $baseline_file. Returns 0 + prints the new node-id on stdout;
# returns 1 on timeout.
#
# We poll the label set rather than /api/cluster/topology because the label is
# set by DockerComputeProvider before R has any SWIM/topology presence — the
# label-based discovery wins us 2-5 seconds, which is the difference between
# "kill R in JOINING window" and "kill R after it transitioned to ON_DUTY".
wait_for_new_node_id_label() {
    local baseline_file="$1" timeout="${2:-60}"
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        local current new
        current=$(snapshot_node_id_labels)
        if [ -n "$current" ]; then
            new=$(comm -13 "$baseline_file" <(printf '%s\n' "$current") | grep -v '^$' || true)
            if [ -n "$new" ]; then
                # If multiple new ids appeared (unlikely but defend), take first.
                printf '%s' "$new" | head -n1
                return 0
            fi
        fi
        sleep 1
    done
    return 1
}

# Kill a container by its aether.node-id label. Stderr captured (silent stderr
# is a trap per project memory). The kill must be authoritative — cluster B
# uses `restart: "no"` so docker kill is final.
kill_by_node_id_label() {
    local node_id="$1"
    local cluster_filter=""
    if [ -n "${CLUSTER_ID:-}" ]; then
        cluster_filter="--filter label=aether.cluster=${CLUSTER_ID} --filter network=aether-${CLUSTER_ID}-network"
    fi
    local name kill_out kill_rc
    name=$(remote_exec "docker ps --filter 'label=aether.node-id=${node_id}' ${cluster_filter} --format '{{.Names}}' | head -1" 2>/dev/null || true)
    if [ -z "$name" ]; then
        log_fail "kill_by_node_id_label: no container found with label aether.node-id=${node_id}"
        return 1
    fi
    kill_out=$(remote_exec "docker kill ${name}" 2>&1)
    kill_rc=$?
    if [ $kill_rc -ne 0 ]; then
        log_fail "kill_by_node_id_label: docker kill ${name} failed (rc=${kill_rc}): ${kill_out}"
        return $kill_rc
    fi
    log_info "kill_by_node_id_label: killed ${name} (node-id=${node_id})"
    return 0
}

# (removed) transport-unreachable smoking-gun helper — v2 has no decommission-reason
# event; node departure is observed via membership-absence (see wait_for_node_removed).

# ---------------------------------------------------------------------------
# Test cases
# ---------------------------------------------------------------------------

test_initial_state() {
    wait_for_cluster_ready 60
    # NORMAL phase gates SWIM cold-boot suppression. Without NORMAL the FSM may
    # also suppress the TransportUnreachable cell during the cold-start fallback
    # (spec §17), so the test premise (TransportUnreachable wins over SWIM)
    # only holds once NORMAL is reached.
    wait_for_phase "NORMAL" 180 || log_warn "Cluster phase still COLD_BOOT; the JOINING-window assertion may absorb a SWIM-path success and not exercise S01"
    wait_for_leader 60
    local count
    count=$(cluster_member_count)
    assert_ge "$count" "5" "Initial: at least 5 nodes (${count})"
    # Capture the pre-priming label snapshot so the replacement-discovery loop
    # below can compute a clean set-diff. We snapshot AFTER NORMAL+leader so
    # the cluster is quiet — no in-flight CTM replacements polluting the diff.
    snapshot_node_id_labels > "$PRELABEL_SNAPSHOT_FILE"
    local pre_count
    pre_count=$(wc -l < "$PRELABEL_SNAPSHOT_FILE" | tr -d ' ')
    assert_ge "$pre_count" "5" "Pre-priming label snapshot has at least 5 entries (${pre_count})"
}

test_prime_replacement_via_kill() {
    # Priming kill: terminate one compose-baseline non-leader to make CTM
    # provision a replacement R. The compose victim itself takes the SWIM path
    # (it's ON_DUTY HEALTHY when killed); we don't care about its timing — we
    # only need it to vacate a slot so CTM provisions R, which IS our S01 target.
    local leader victim
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader identified for priming kill: ${leader}"
    victim=$(pick_non_leader "$leader")
    assert_ne "$victim" "" "Priming victim identified: ${victim}"
    printf '%s' "$victim" > "$PRIMING_VICTIM_FILE"
    log_info "Priming kill (to trigger CTM auto-heal): ${victim}"
    kill_node "$victim"
}

test_catch_replacement_in_joining_window() {
    # Discover R via label set-diff against the pre-priming snapshot.
    log_info "Waiting for CTM to provision replacement R (label set-diff)..."
    local replacement
    replacement=$(wait_for_new_node_id_label "$PRELABEL_SNAPSHOT_FILE" "$REPLACEMENT_DISCOVERY_TIMEOUT_S")
    if [ -z "$replacement" ]; then
        log_fail "No new aether.node-id label appeared within ${REPLACEMENT_DISCOVERY_TIMEOUT_S}s — CTM did not auto-heal (circuit breaker tripped? leader churn?)"
        return 1
    fi
    log_info "Replacement R discovered: ${replacement} (acting on it before SWIM HEALTHY)"
    printf '%s' "$replacement" > "$REPLACEMENT_NODE_ID_FILE"

    # Sanity check: R must not be the priming victim (CTM never reuses a
    # decommissioned NodeId — single-writer rule).
    local priming_victim
    priming_victim=$(cat "$PRIMING_VICTIM_FILE")
    assert_ne "$replacement" "$priming_victim" "Replacement NodeId ${replacement} is fresh (not the priming victim ${priming_victim})"

    # CRITICAL — wait for R's NodeLifecycleKey atom to land in KV (state =
    # JOINING or ON_DUTY). Without this gate, the post-kill DECOMMISSIONED
    # check is ambiguous: R might never have had a KV atom at all, in which
    # case "not DECOMMISSIONED" reads as either "FSM is broken" OR "FSM never
    # got a chance to write JOINING because R died too fast".
    #
    # The KV-backed endpoint `/api/nodes/lifecycle/<R>` reads NodeLifecycleKey
    # directly — bypassing MembershipView's SWIM filter. This is critical for
    # S01 because R is killed before SWIM HEALTHY, so R may never surface in
    # /api/nodes/status cluster.nodes[] regardless of whether the FSM wrote JOINING.
    # The KV atom is the authoritative consensus-replicated state we want to
    # assert against.
    local pre_kill_state
    if ! pre_kill_state=$(wait_for_replacement_in_kv "$replacement" 90); then
        log_fail "Replacement ${replacement} never reached JOINING/ON_DUTY in KV-backed /api/nodes/lifecycle/${replacement} within 90s — CTM provisioned the container but the FSM never wrote a NodeLifecycleKey atom (consensus stuck? leader churn? FSM not consuming SlotClaimed?). Cannot exercise S01."
        return 1
    fi
    case "$pre_kill_state" in
        JOINING)
            log_info "Replacement ${replacement} pre-kill KV state: JOINING — S01 (JOINING, TransportUnreachable) cell will be exercised"
            ;;
        ON_DUTY)
            # If R raced to ON_DUTY before the kill, the (ON_DUTY, SwimDeparted)
            # cell at line 187 of ClusterMembershipReducer.java fires and writes
            # `reason=swim-departed` — accepted by the smoking-gun regex below.
            # No skip needed; record the race for log context only.
            log_warn "Replacement ${replacement} raced past JOINING into ON_DUTY before kill — decommission proceeds via (ON_DUTY, SwimDeparted) instead of (JOINING, *); smoking-gun assertion below accepts both paths."
            ;;
    esac

    # The actual S01 kill. Record kill timestamp BEFORE the kill returns so the
    # 60s budget includes any SSH round-trip latency (worst-case for the test;
    # if anything, we under-count the wall-clock available, which makes the
    # assertion strictly stronger).
    date +%s > "$KILL_TIMESTAMP_FILE"
    kill_by_node_id_label "$replacement"
}

test_decommission_within_budget() {
    local replacement kill_ts now elapsed
    # Pre-req: test_catch_replacement_in_joining_window must have landed the kill
    # and written the kill timestamp. If that test failed before its kill (e.g.
    # the FSM never wrote a NodeLifecycleKey atom within 90s), the timestamp file
    # is absent and there is nothing to budget against. Without this guard,
    # `kill_ts=""` makes `elapsed=$((now - kill_ts))` evaluate to `now` (a Unix
    # timestamp ≈1.7e9) which produces the nonsense assertion "expected >= 1.7e9,
    # got '25'" — masking the real failure mode (prior test failed).
    if [ ! -s "$KILL_TIMESTAMP_FILE" ]; then
        log_fail "S01 kill timestamp missing — test_catch_replacement_in_joining_window did not land the kill (no replacement reached JOINING/ON_DUTY in the catch window)"
        return 1
    fi
    if [ ! -s "$REPLACEMENT_NODE_ID_FILE" ]; then
        log_fail "Replacement NodeId missing — test_catch_replacement_in_joining_window did not capture R"
        return 1
    fi
    replacement=$(cat "$REPLACEMENT_NODE_ID_FILE")
    kill_ts=$(cat "$KILL_TIMESTAMP_FILE")

    # Authoritative v2 assertion: poll until R is REMOVED from membership,
    # capped at the spec budget. v2 has no DECOMMISSIONED lifecycle state — a
    # killed node simply leaves the SWIM-fed membership. Removal is observed
    # via /api/nodes/lifecycle/<R> returning HTTP 404 (LIFECYCLE_NOT_FOUND) OR
    # R being absent from /api/nodes/status cluster.nodes[]. See
    # wait_for_node_removed for the dual signal.
    if ! wait_for_node_removed "$replacement" "$DECOMMISSION_BUDGET_S"; then
        # Diagnostic: show R's current lifecycle state so the failure log
        # explains WHY the budget was missed (still JOINING? still ON_DUTY?).
        local stuck_state
        stuck_state=$(kv_lifecycle_state "$replacement")
        now=$(date +%s)
        elapsed=$((now - kill_ts))
        log_fail "S01 budget violated: ${replacement} not removed from membership within ${DECOMMISSION_BUDGET_S}s (elapsed=${elapsed}s, lifecycle_state='${stuck_state:-<404/absent>}'; still present in /api/nodes/status). The SWIM failure detector did not drop the killed JOINING peer in time — likely a regression in SWIM departure detection or the MembershipView projection (R lingers in cluster.nodes[] past the budget)."
        return 1
    fi

    now=$(date +%s)
    elapsed=$((now - kill_ts))
    assert_ge "$DECOMMISSION_BUDGET_S" "$elapsed" "Replacement ${replacement} removed from membership within ${DECOMMISSION_BUDGET_S}s budget (actual=${elapsed}s)"
    log_pass "S01 timing budget met: ${replacement} left the cluster (404 from /api/nodes/lifecycle OR absent from /api/nodes/status) in ${elapsed}s (within ${DECOMMISSION_BUDGET_S}s budget)"
}

# (removed) transport-unreachable smoking-gun test — v2 has no decommission-reason event; node departure is observed via membership-absence (see test_decommission_within_budget).

test_pick_non_leader_excludes_decommissioned() {
    # Hygiene: after R is decommissioned, pick_non_leader() must not return
    # it as a candidate. Verifies the operator-visible projection has fully
    # caught up with the FSM transition.
    #
    # Pre-check: the S01 kill removed a JOINING peer (not the leader), but
    # cluster_leader can still transiently return empty immediately after the
    # smoking-gun docker-logs sweep — MGMT_ENTRY_POINT round-robin can hit a
    # backend whose MembershipView is mid-projection. wait_for_leader on
    # cluster B has a 120s floor; that's the right tolerance window here.
    local replacement leader candidates
    replacement=$(cat "$REPLACEMENT_NODE_ID_FILE")
    if ! wait_for_leader; then
        log_fail "No leader elected after S01 kill within wait_for_leader budget"
        return 1
    fi
    leader=$(cluster_leader)
    assert_ne "$leader" "" "Leader still elected after S01 kill: ${leader}"
    # Best-effort: pick_non_leader may return rc=1 if the cluster is too small
    # to satisfy the request (the priming kill + S01 kill removed 2 nodes; the
    # surviving set is 3-4 nodes depending on CTM provisioning state). We
    # tolerate that; the only assertion is that R is not returned IF any
    # candidate is returned.
    candidates=$(pick_non_leader "$leader" 3 2>/dev/null || true)
    if [ -n "$candidates" ]; then
        # grep -Fx: exact-line literal match.
        if printf '%s\n' "$candidates" | grep -Fxq -- "$replacement"; then
            log_fail "pick_non_leader returned the decommissioned replacement ${replacement} as a candidate — MembershipView projection regression"
            return 1
        fi
        log_pass "pick_non_leader candidates exclude decommissioned ${replacement}"
    else
        log_warn "pick_non_leader returned no candidates (cluster shrunk by 2 kills; CTM may still be re-provisioning). Skipping exclusion assertion."
    fi
}

cleanup() {
    # Tidy ferry files (best-effort; /tmp survives shell exit anyway, but
    # parallel test runs on the same host benefit from per-PID cleanup).
    rm -f "$PRELABEL_SNAPSHOT_FILE" "$REPLACEMENT_NODE_ID_FILE" \
          "$KILL_TIMESTAMP_FILE" "$PRIMING_VICTIM_FILE" "$RACE_TO_ON_DUTY_FILE"

    # Semantic baseline restore — re-enables auto-heal (we never disabled it,
    # but restore_cluster_baseline is idempotent), resets the CTM circuit
    # breaker if tripped, scales back to NODE_COUNT, waits for ON_DUTY healthy
    # parity + generation quiescence + phase=NORMAL. Subsequent suites inherit
    # a clean cluster.
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}

# Run cleanup on ANY exit path — including a `return 1` from inside a test
# function that propagates up through `set -e` and aborts the script. Without
# this trap, a failed timing assertion left the cluster in a degraded state
# (priming victim stopped, R stopped, CTM unrecovered), which then broke
# test-kill-leader.sh in the next test file. The trap fires once; we guard
# the inner restore_cluster_baseline against its own non-zero exit so the
# trap can't loop.
trap 'cleanup' EXIT

run_test "Initial 5 nodes + label snapshot" test_initial_state
run_test "Prime replacement via priming kill" test_prime_replacement_via_kill
run_test "Catch replacement in JOINING window and kill it" test_catch_replacement_in_joining_window
run_test "Replacement removed from membership within 90s (S01 budget)" test_decommission_within_budget
run_test "pick_non_leader excludes removed replacement" test_pick_non_leader_excludes_decommissioned
# cleanup runs via EXIT trap — guarantees baseline restore even if a run_test
# above triggers `set -e` abort via `return 1` from inside a test function.
print_summary
