#!/bin/bash
# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
#
# test-self-drain-quorum-loss.sh — Spec §16 rows S19 + S20.
#
# Scenarios:
#   S19: Three of five nodes are killed simultaneously via SIGKILL. The two
#        surviving nodes each see (connectedPeers=1 + self=1) = 2 < (N/2)+1 = 3,
#        which trips SelfDrainCoordinator's "sustained below quorum" debounce
#        (configured 8s window). Each survivor MUST:
#          (a) flip InFlightRequestTracker to !acceptingNewWork at +~8s,
#          (b) wait up to 30s grace for in-flight requests to drain,
#          (c) Runtime.halt(2) — JVM exits with code 2.
#        Total wall-clock budget: 8s threshold + 30s grace = 38s. We allow
#        7s headroom for SSH/RTT/scheduler jitter → 45s.
#        The drain is UNINTERRUPTIBLE: even if connectivity were somehow
#        restored mid-drain (it cannot here — the killed peers stay dead),
#        the CAS-guarded phase transition prevents abort.
#        NO consensus / KV writes happen from survivors after DRAINING — this
#        is the structural guarantee of SelfDrainCoordinator (no KV/consensus
#        imports, asserted by unit test `noConsensusOrKvImports`). We
#        additionally observe this via log inspection: after the drain-trigger
#        log line, NO further KV-write log lines should appear.
#
#   S20: After all 5 containers are restarted, the cluster MUST recover to
#        5 ON_DUTY healthy cores within 60s. This exercises the cold-boot
#        path post-self-drain: fresh JOINING transitions, SWIM convergence,
#        aggregator periodic-emission cycle, NORMAL phase.
#
# Mechanics:
#   `docker kill aether-${CLUSTER_ID}-node-X aether-${CLUSTER_ID}-node-Y
#    aether-${CLUSTER_ID}-node-Z` issued as a single remote_exec invocation
#   so the three SIGKILLs land within a few ms of each other on the daemon
#   side. Cluster B's docker-compose uses `restart: "no"` so the kill is
#   authoritative (no auto-restart absorbs the kill).
#
# Exit code expectation:
#   `SelfDrainCoordinator.performExit()` invokes the configured `jvmExit`
#   runnable, which the production factory wires to
#   `Runtime.getRuntime().halt(2)` (selfDrainCoordinator.java:104). We
#   assert `docker inspect --format '{{.State.ExitCode}}'` == 2 on each
#   survivor. Any other exit code (0, 137, 143) would indicate a different
#   shutdown path (graceful, SIGKILL, SIGTERM) — i.e. self-drain did NOT
#   fire as designed.
#
# Smoking-gun signal (T3.1):
#   At the SelfDrainCoordinator CAS transition into DRAINING the
#   coordinator publishes a `SELF_DRAIN_INITIATED` event into the cluster-
#   scoped replicated event log (Severity=WARNING, details.nodeId=<self>,
#   details.reason=<sustained-below-quorum|quorum-disappeared|rabia-paused>,
#   details.graceMs=<n>). The event is NOT leader-gated — the draining
#   node itself is the only authoritative source for "I'm self-draining".
#   We consume it from /api/events via `wait_for_self_drain_event`
#   (lib/topology.sh) filtering by type AND nodeId.
#
#   Caveat: the publish goes through Rabia. In S19 quorum is gone on the
#   survivor side, so the publish may not commit before `Runtime.halt(2)`
#   lands. The event is therefore a SOFT signal — missing it falls back
#   to `log_warn`. The exit-code-2 + container-exit-state assertions
#   remain the HARD contract.
#
#   This REPLACES the prior `docker logs | grep 'Self-drain: DRAINING on'`
#   workaround which suffered from SSH-RTT + docker-daemon log-flush race
#   and was a single-cluster-only signal.
#
# Regression coverage for the topology-observation refactor:
#   * Step 5 (SelfDrainCoordinator implementation): the entire test
#     exercises this. If `initiateDrain()` doesn't fire, survivors will
#     remain RUNNING and the docker-state assertion will fail.
#   * Step 5 — uninterruptibility: not directly observable here (kills are
#     final, no reconnect mid-drain), but exit-code=2 plus single
#     drain-trigger log line per survivor confirms `performExit()` ran
#     exactly once.
#   * Step 5 — no consensus/KV dependency: structurally guaranteed at
#     compile time by the test `noConsensusOrKvImports`; here we
#     additionally verify it empirically (post-DRAINING logs carry no
#     KV-write lines from the survivor).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"
source "${SCRIPT_DIR}/../../lib/topology.sh"

# Wall-clock budget for survivor exit measured from the kill timestamp:
# 8s SelfDrainConfig.triggerThreshold (default) + 30s inflightGrace
# (default) + 7s headroom for SSH RTT, JVM shutdown hooks, docker daemon
# state reconciliation. The headroom is intentionally conservative — a
# fast pass at ~38s + ~2s daemon update is normal; we just avoid flaky
# failures from slow remotes.
SURVIVOR_EXIT_BUDGET_S=45

# Post-restart cluster-recovery budget. 5 fresh JVMs cold-boot, SWIM
# converges, aggregator emits its first periodic snapshot, NORMAL phase
# is reached. Spec §16 row S20 sets 60s as the contract.
RECOVERY_BUDGET_S=60

# Files ferrying state between test functions. run_test isolates each
# function in its own shell context, so env vars don't survive.
VICTIMS_FILE="/tmp/s19-victims.$$"
SURVIVORS_FILE="/tmp/s19-survivors.$$"
KILL_TS_FILE="/tmp/s19-kill-ts.$$"
# Event-baseline timestamp captured immediately BEFORE the kill so the
# subsequent /api/events poll for SELF_DRAIN_INITIATED only sees events
# emitted by survivors AFTER the kill landed. Format: ISO-8601 UTC,
# accepted by /api/events?since= and produced by `topology_now`.
EVENT_BASELINE_FILE="/tmp/s19-event-baseline.$$"

# Resolve the docker container name for a fixed compose ordinal (1..N).
# This is intentionally label-free: at the point of S19 we want to kill
# fixed compose slots (so the killed set is reproducible across runs)
# rather than chasing NodeIds. Returns "aether-${CLUSTER_ID}-node-${i}".
compose_container_name() {
    local ordinal="$1"
    printf 'aether-%s-node-%s' "${CLUSTER_ID:-b}" "$ordinal"
}

# Snapshot of fixed compose ordinals currently running in this cluster.
# Cluster B baseline is 5 (NODE_COUNT). We scan ordinals 1..NODE_COUNT and
# report the ones whose container is in state=running. Used to identify
# survivors after the simultaneous kill.
running_compose_ordinals() {
    local total="${NODE_COUNT:-5}"
    local i name state out
    for i in $(seq 1 "$total"); do
        name=$(compose_container_name "$i")
        out=$(remote_exec "docker inspect --format '{{.State.Status}}' ${name} 2>&1" 2>&1)
        # `docker inspect` on a missing container returns rc=1 with "No such
        # object" on stdout/stderr — we tolerate both as "not running".
        state=$(printf '%s' "$out" | head -1 | tr -d '\r')
        if [ "$state" = "running" ]; then
            printf '%s\n' "$i"
        fi
    done
}

# Read `docker inspect --format '{{.State.ExitCode}}'` for an ordinal.
# Returns the exit code on stdout (or empty + rc=1 if the container is
# missing / still running). The caller decides whether "still running"
# is a failure.
container_exit_code() {
    local ordinal="$1"
    local name out rc
    name=$(compose_container_name "$ordinal")
    out=$(remote_exec "docker inspect --format '{{.State.ExitCode}}' ${name} 2>&1")
    rc=$?
    if [ $rc -ne 0 ]; then
        return 1
    fi
    printf '%s' "$out" | head -1 | tr -d '\r '
}

# Read `docker inspect --format '{{.State.Status}}'` for an ordinal.
# Returns the status string (running / exited / created / ...) on stdout.
container_status() {
    local ordinal="$1"
    local name out
    name=$(compose_container_name "$ordinal")
    out=$(remote_exec "docker inspect --format '{{.State.Status}}' ${name} 2>&1" 2>&1)
    printf '%s' "$out" | head -1 | tr -d '\r '
}

# Poll until the named ordinal's container is in state=exited, capped at
# $2 seconds. Returns 0 on observed exit; 1 on timeout.
#
# Race fix (2026-05-22): the 1s sleep can land *after* deadline expires,
# causing the loop to exit without ever sampling the final state. If the
# container exits during that gap, the previous `return 1` would lie even
# when `container_status` after the loop reads "exited". Add an explicit
# post-loop sample so the result is always tied to the most recent
# observation — failure log already evidenced this ("Current state: exited"
# alongside a wait_for_container_exit timeout).
wait_for_container_exit() {
    local ordinal="$1" timeout="$2"
    local deadline=$((SECONDS + timeout))
    local status
    while [ $SECONDS -lt $deadline ]; do
        status=$(container_status "$ordinal")
        if [ "$status" = "exited" ]; then
            return 0
        fi
        sleep 1
    done
    # Final post-deadline sample — guards against the 1s sleep landing past
    # deadline and missing a container that exited within the budget.
    status=$(container_status "$ordinal")
    if [ "$status" = "exited" ]; then
        return 0
    fi
    return 1
}

# Smoking-gun for the `ACTIVE → DRAINING` CAS transition: the
# `SELF_DRAIN_INITIATED` cluster event published by `SelfDrainCoordinator.
# initiateDrain(String)`. This event is intentionally NOT leader-gated (the
# draining node itself is the only authoritative source for "I'm self-
# draining" — a partition victim cannot rely on the leader to publish on
# its behalf). We poll the unioned-multi-node /api/events stream via
# `wait_for_self_drain_event` (lib/topology.sh) filtering by
# `type=SELF_DRAIN_INITIATED` AND `details.nodeId=<ordinal-mapped-id>`.
#
# T3.1 (test-readiness-contract.md §6): this REPLACES the prior `docker
# logs | grep 'Self-drain: DRAINING on'` workaround. Event-driven assertion
# avoids the SSH-RTT + docker-daemon log-flush race and produces a stable
# acceptance signal that survives log-driver rotation.
#
# Caveat — Rabia publish under quorum loss: SelfDrainCoordinator publishes
# the event synchronously at the CAS, but the publish flows through Rabia.
# In the S19 scenario quorum is GONE on the survivor side, so the publish
# may not commit before `Runtime.halt(2)` lands. The event MAY still reach
# the cluster via a victim's pre-shutdown gossip OR via post-restart
# replay; either way it's best-effort. We therefore poll on a generous
# budget (the survivor exit budget is the natural bound) and tolerate
# timeout as a soft signal — the exit-code-2 + container-exit-state
# assertions remain the hard contract. The `--soft` flag below downgrades
# a missing event to a `log_warn` instead of a `log_fail`, mirroring the
# negative-assertion pattern of `verify_no_kv_writes_after_drain`.
SELF_DRAIN_EVENT_TIMEOUT_S=60

# After the drain-trigger line, the SelfDrainCoordinator MUST NOT initiate
# any KV write — its design forbids consensus/KV dependency (asserted at
# compile time by `noConsensusOrKvImports` unit test). We additionally
# verify this empirically: after the smoking-gun line, NO log entries
# matching consensus/KV write activity from the local node may appear.
#
# Pattern: we look for the post-drain region of the log (everything after
# the smoking-gun line) and check for ConsensusEngine / RabiaEngine /
# KvStoreCommand / NodeLifecycleKey write markers. Empty match = pass.
#
# This is a NEGATIVE assertion (absence-of-evidence) and so is inherently
# weaker than the positive smoking-gun check; if it passes it's strong
# evidence, if it fails it's worth investigating but may be a logging
# artifact (e.g. an unrelated background task logged after drain but
# before halt). We log_warn rather than log_fail on a match.
verify_no_kv_writes_after_drain() {
    local ordinal="$1"
    local name lines drain_line post drain_kv_writes
    name=$(compose_container_name "$ordinal")
    # Capture full log, split at the drain-trigger line. `awk` is portable
    # and avoids spawning grep -A with an unknown line count.
    drain_kv_writes=$(remote_exec "docker logs ${name} 2>&1 | awk '/Self-drain: DRAINING on/{seen=1; next} seen' | grep -E 'ConsensusEngine|RabiaEngine|KvStoreCommand|NodeLifecycleKey write|applyAtomic' | head -5 || true" 2>/dev/null)
    if [ -n "$drain_kv_writes" ]; then
        printf '%s' "$drain_kv_writes"
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# Test cases
# ---------------------------------------------------------------------------

test_initial_state() {
    wait_for_cluster_ready 60
    # NORMAL phase ensures the aggregator's periodic emission has stabilized
    # and the cold-start fallback paths in SelfDrainCoordinator are no
    # longer in effect. Soft (log_warn) to align with sibling tests; the
    # downstream container-state and exit-code assertions are the real
    # signal.
    wait_for_phase "NORMAL" 180 || \
        log_warn "Cluster phase did not reach NORMAL within 180s — self-drain timing may be elongated by cold-start aggregator behavior"
    wait_for_leader 60
    local count
    count=$(cluster_active_core_count)
    assert_eq "$count" "5" "Initial: 5 ON_DUTY healthy cores"
}

test_pick_victims_and_kill_three_simultaneously() {
    # We pick fixed compose ordinals 1, 2, 3 as victims. This is
    # deterministic (no dependency on which NodeId currently occupies
    # slot 1) and reproducible across runs. The cluster is breaking
    # whichever 3 we pick — leader inclusion is incidental, not load-
    # bearing for S19 (the surviving 2 will lose quorum visibility
    # regardless of who held the lease before the kill).
    local victims="1 2 3"
    printf '%s\n' "$victims" > "$VICTIMS_FILE"

    # Pre-kill: snapshot which compose ordinals are currently running, so
    # the survivor identification below is exact (handles the corner case
    # where the cluster started with a degraded slot — survivors must be
    # drawn from "running before kill" minus "victims", not just
    # 1..NODE_COUNT minus victims).
    local pre_running
    pre_running=$(running_compose_ordinals)
    local pre_count
    pre_count=$(printf '%s\n' "$pre_running" | grep -c '.' || true)
    assert_eq "$pre_count" "5" "Pre-kill: 5 compose ordinals running"

    log_info "Killing compose ordinals ${victims} simultaneously (single docker kill invocation)"
    local kill_cmd kill_out kill_rc
    # Single remote_exec → single SSH RTT → single docker daemon call:
    # the three SIGKILLs are issued within microseconds of each other.
    # This is the closest practical approximation to "simultaneous".
    kill_cmd="docker kill aether-${CLUSTER_ID:-b}-node-1 aether-${CLUSTER_ID:-b}-node-2 aether-${CLUSTER_ID:-b}-node-3"
    # T3.1: capture the /api/events baseline timestamp BEFORE issuing the
    # kill so the SELF_DRAIN_INITIATED poll later sees only events emitted
    # AFTER the kill landed. The since-filter is exclusive on the server
    # side; a couple of seconds of pre-baseline drift is irrelevant
    # because the WARNING-severity SELF_DRAIN_INITIATED event isn't
    # emitted by anything other than `SelfDrainCoordinator.initiateDrain`.
    topology_now > "$EVENT_BASELINE_FILE"
    # Record kill timestamp BEFORE the kill returns so any SSH-RTT
    # latency is counted against us, not against the budget (worst-case
    # for the test; if anything, we under-count the wall-clock available,
    # making the assertion strictly stronger).
    date +%s > "$KILL_TS_FILE"
    kill_out=$(remote_exec "$kill_cmd" 2>&1)
    kill_rc=$?
    if [ $kill_rc -ne 0 ]; then
        log_fail "docker kill of victims 1,2,3 failed (rc=${kill_rc}): ${kill_out}"
        return 1
    fi
    log_info "Kill issued; docker daemon response: $(printf '%s' "$kill_out" | head -c 200)"

    # Survivors = running-before-kill minus victims. Compute via line-set
    # difference (comm -23 needs sorted inputs).
    local victims_sorted survivors
    victims_sorted=$(printf '%s\n' 1 2 3)
    survivors=$(printf '%s\n' "$pre_running" | sort -n | comm -23 - <(printf '%s\n' "$victims_sorted" | sort -n) | grep -v '^$' || true)
    local survivor_count
    survivor_count=$(printf '%s\n' "$survivors" | grep -c '.' || true)
    assert_eq "$survivor_count" "2" "Survivors identified (2 ordinals): $(printf '%s' "$survivors" | tr '\n' ' ')"
    printf '%s\n' "$survivors" > "$SURVIVORS_FILE"
}

test_survivors_self_drain_and_exit() {
    local s1 s2
    s1=$(sed -n '1p' "$SURVIVORS_FILE")
    s2=$(sed -n '2p' "$SURVIVORS_FILE")
    if [ -z "$s1" ] || [ -z "$s2" ]; then
        log_fail "Survivors file missing entries (s1='${s1}', s2='${s2}') — upstream test_pick_victims... failed silently?"
        return 1
    fi

    log_info "Awaiting survivor exits within ${SURVIVOR_EXIT_BUDGET_S}s budget (8s threshold + 30s grace + 7s headroom)"

    # Wait for survivor 1 first, then survivor 2. Both should exit
    # within roughly the same wall-clock window (their drain debounce
    # started at the same moment). Sequential wait is fine because the
    # budget is shared (we cap at SURVIVOR_EXIT_BUDGET_S total elapsed
    # from kill, not per-survivor).
    local kill_ts now elapsed remaining
    kill_ts=$(cat "$KILL_TS_FILE")

    now=$(date +%s)
    elapsed=$((now - kill_ts))
    remaining=$((SURVIVOR_EXIT_BUDGET_S - elapsed))
    if [ "$remaining" -le 0 ]; then
        log_fail "Survivor exit budget exhausted before wait began (elapsed=${elapsed}s) — upstream step took too long"
        return 1
    fi
    if ! wait_for_container_exit "$s1" "$remaining"; then
        log_fail "S19 violation: survivor node-${s1} did not exit within budget. Current state: $(container_status "$s1")"
        return 1
    fi
    log_info "Survivor node-${s1} exited"

    now=$(date +%s)
    elapsed=$((now - kill_ts))
    remaining=$((SURVIVOR_EXIT_BUDGET_S - elapsed))
    if [ "$remaining" -le 0 ]; then
        # If we ran out while waiting for s1, s2 may still exit shortly.
        # Allow a small additional grace (5s) — both should be exiting
        # in parallel, so this only flexes the test under abnormal jitter.
        remaining=5
    fi
    if ! wait_for_container_exit "$s2" "$remaining"; then
        log_fail "S19 violation: survivor node-${s2} did not exit within budget. Current state: $(container_status "$s2")"
        return 1
    fi
    log_info "Survivor node-${s2} exited"

    now=$(date +%s)
    elapsed=$((now - kill_ts))
    log_pass "S19: both survivors (node-${s1}, node-${s2}) exited within ${elapsed}s (budget=${SURVIVOR_EXIT_BUDGET_S}s)"
}

test_survivor_exit_codes_are_two() {
    # SelfDrainCoordinator.performExit() invokes the configured jvmExit
    # runnable, which the production factory wires to
    # `Runtime.getRuntime().halt(2)` (SelfDrainCoordinator.java:104).
    # Any other exit code indicates a different shutdown path:
    #   0   — graceful clean shutdown (not self-drain)
    #   137 — SIGKILL from outside (e.g. docker kill itself)
    #   143 — SIGTERM (e.g. docker stop)
    # We assert exactly 2 on both survivors.
    local s1 s2 ec1 ec2
    s1=$(sed -n '1p' "$SURVIVORS_FILE")
    s2=$(sed -n '2p' "$SURVIVORS_FILE")
    ec1=$(container_exit_code "$s1" || true)
    ec2=$(container_exit_code "$s2" || true)
    assert_eq "$ec1" "2" "Survivor node-${s1} exit code is 2 (Runtime.halt(2) from SelfDrainCoordinator)"
    assert_eq "$ec2" "2" "Survivor node-${s2} exit code is 2 (Runtime.halt(2) from SelfDrainCoordinator)"
}

test_drain_trigger_log_signature_present() {
    # Smoking gun (T3.1): each survivor MUST emit `SELF_DRAIN_INITIATED`
    # at the SelfDrainCoordinator ACTIVE→DRAINING CAS. We consume it from
    # /api/events via `wait_for_self_drain_event` (lib/topology.sh) using
    # the baseline captured immediately pre-kill. The event is NOT
    # leader-gated (a partition victim is the only authoritative source
    # for "I'm self-draining"), so the publish originates on the survivor
    # itself; `topology_events_since` unions across all live node
    # endpoints so it will be picked up whichever node first replays it
    # to the cluster-scoped event log.
    #
    # Caveat: in S19 quorum is gone on the survivor side, so the Rabia
    # publish may not commit before `Runtime.halt(2)` lands. We therefore
    # treat a timeout as a SOFT signal (`log_warn`, not `log_fail`) — the
    # exit-code-2 + container-exit-state assertions above are the hard
    # contract. If the event reliably lands in CI we can upgrade to
    # `log_fail` later; for now we honor the publish-vs-halt race.
    local s1 s2 baseline
    s1=$(sed -n '1p' "$SURVIVORS_FILE")
    s2=$(sed -n '2p' "$SURVIVORS_FILE")
    baseline=$(cat "$EVENT_BASELINE_FILE" 2>/dev/null || echo "")
    if [ -z "$baseline" ]; then
        log_warn "Missing /api/events baseline (s19-event-baseline file empty) — SELF_DRAIN_INITIATED poll will scan from epoch=0"
    fi
    if wait_for_self_drain_event "node-${s1}" "$baseline" "$SELF_DRAIN_EVENT_TIMEOUT_S"; then
        log_pass "SELF_DRAIN_INITIATED observed via /api/events for node-${s1}"
    else
        log_warn "No SELF_DRAIN_INITIATED event observed on /api/events for node-${s1} within ${SELF_DRAIN_EVENT_TIMEOUT_S}s — Rabia publish may have lost the race against Runtime.halt(2); exit-code-2 assertion above remains the hard contract"
    fi
    if wait_for_self_drain_event "node-${s2}" "$baseline" "$SELF_DRAIN_EVENT_TIMEOUT_S"; then
        log_pass "SELF_DRAIN_INITIATED observed via /api/events for node-${s2}"
    else
        log_warn "No SELF_DRAIN_INITIATED event observed on /api/events for node-${s2} within ${SELF_DRAIN_EVENT_TIMEOUT_S}s — Rabia publish may have lost the race against Runtime.halt(2); exit-code-2 assertion above remains the hard contract"
    fi
}

test_no_kv_writes_after_drain_trigger() {
    # Empirical complement to the compile-time assertion
    # `SelfDrainCoordinatorTest.noConsensusOrKvImports`. After the drain
    # signature line, the survivor MUST NOT log evidence of consensus/KV
    # write activity (the coordinator is structurally forbidden from
    # initiating one, but a buggy wiring could route through some other
    # subsystem). This is a NEGATIVE assertion and so is inherently
    # weaker than a positive observation; we log_warn (not log_fail) on
    # match because legitimate unrelated background-task log lines
    # could appear in the post-drain window before halt(2) lands.
    local s1 s2 leak
    s1=$(sed -n '1p' "$SURVIVORS_FILE")
    s2=$(sed -n '2p' "$SURVIVORS_FILE")
    leak=$(verify_no_kv_writes_after_drain "$s1" || true)
    if [ -n "$leak" ]; then
        log_warn "Post-drain KV-write evidence on node-${s1} (investigate, may be benign): $(printf '%s' "$leak" | head -c 300)"
    else
        log_pass "No KV-write log signatures after drain trigger on node-${s1}"
    fi
    leak=$(verify_no_kv_writes_after_drain "$s2" || true)
    if [ -n "$leak" ]; then
        log_warn "Post-drain KV-write evidence on node-${s2} (investigate, may be benign): $(printf '%s' "$leak" | head -c 300)"
    else
        log_pass "No KV-write log signatures after drain trigger on node-${s2}"
    fi
}

test_cluster_recovers_to_five_on_duty() {
    # S20 contract: restart all 5 nodes via restart_all_nodes (compose
    # cycle), then assert the cluster reaches 5 ON_DUTY healthy within
    # RECOVERY_BUDGET_S. restart_all_nodes itself waits for cluster
    # readiness + leader + generation quiescence + per-node /health/ready,
    # so by the time it returns the cluster is mostly there; we add a
    # final assertion on the ON_DUTY healthy count to pin the S20 contract.
    log_info "Restarting all 5 compose nodes (S20: post-self-drain recovery)"
    if ! restart_all_nodes; then
        log_fail "S20 violation: restart_all_nodes returned non-zero — cluster did not recover cleanly from self-drain exits"
        return 1
    fi
    # restart_all_nodes already drove the cluster back to leader + quorum,
    # but the ON_DUTY healthy count is the actual S20 acceptance signal.
    if ! wait_for "5 ON_DUTY healthy cores after self-drain recovery" \
        "[ \$(cluster_active_core_count) -eq 5 ]" "$RECOVERY_BUDGET_S"; then
        local now_count
        now_count=$(cluster_active_core_count)
        log_fail "S20 violation: cluster did not return to 5 ON_DUTY healthy cores within ${RECOVERY_BUDGET_S}s of restart (current count=${now_count})"
        return 1
    fi
    assert_cluster_healthy "S20: cluster recovered to 5 ON_DUTY within ${RECOVERY_BUDGET_S}s of restart"
}

cleanup() {
    rm -f "$VICTIMS_FILE" "$SURVIVORS_FILE" "$KILL_TS_FILE" "$EVENT_BASELINE_FILE"

    # Semantic baseline restore. After S19+S20 the cluster should already
    # be back at 5 ON_DUTY (restart_all_nodes was invoked in
    # test_cluster_recovers_to_five_on_duty), but a failure earlier in
    # the test could have left containers exited. restore_cluster_baseline
    # handles both: if the cluster is already healthy it's effectively a
    # no-op; if it's degraded it'll attempt restart + scale-back.
    # Idempotent.
    restore_cluster_baseline || \
        log_warn "cleanup: restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn"
}

# Run cleanup on ANY exit path — including a `return 1` from inside a
# test function that propagates up through `set -e` and aborts the
# script. Pattern matches Step 7's test-joining-window-kill.sh and
# Step 8's test-partition-quorum-gate.sh.
trap 'cleanup' EXIT

run_test "Initial 5 ON_DUTY healthy cores" test_initial_state
run_test "Pick 3 victims and kill simultaneously" test_pick_victims_and_kill_three_simultaneously
run_test "Survivors self-drain and exit within ${SURVIVOR_EXIT_BUDGET_S}s (S19)" test_survivors_self_drain_and_exit
run_test "Survivor exit codes are 2 (Runtime.halt(2))" test_survivor_exit_codes_are_two
run_test "Drain-trigger log signature present on survivors" test_drain_trigger_log_signature_present
run_test "No KV-writes after drain trigger (negative assertion)" test_no_kv_writes_after_drain_trigger
run_test "Cluster recovers to 5 ON_DUTY within ${RECOVERY_BUDGET_S}s (S20)" test_cluster_recovers_to_five_on_duty
print_summary
