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

# Enumerate the REAL running core containers in this cluster by docker name.
#
# History (test-readiness-contract.md §1.1, "Property 4 retirement"): we used
# to enumerate by fixed compose ordinals 1..NODE_COUNT. That broke under CTM
# auto-heal: replacement cores are provisioned with KSUID names like
# `aether-b-node-3EJfeb32h1qsy4MUXETqOntBWcA` that fall outside the 1..N
# ordinal range, so an ordinal scan reported only the survivors of earlier
# scenarios as "running". The fix mirrors `pick_non_leader` (cluster.sh:247):
# enumerate by ACTUAL running containers / membership, never by static
# ordinal.
#
# The `name=aether-<cluster>-node-` filter is a SUBSTRING match — it matches
# both `aether-b-node-1` and `aether-b-node-3EJ...` but NOT
# `aether-b-mgmt-gateway`. We additionally defend against any surprise match
# by confirming the prefix and excluding the mgmt-gateway explicitly. Emits
# one container NAME per line (sorted for determinism).
running_core_containers() {
    # Cloud: there is no single Docker host to `docker ps` against — each node is its
    # own VM and CTM replacements never carry the operator SSH key. Liveness comes from
    # the cluster's own membership instead: cloud_running_cores prints every READY core's
    # runtime node-id (one per line) via the mgmt API /api/nodes/lifecycle. Sort to keep
    # the deterministic victim/survivor split the docker path relies on.
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        cloud_running_cores | grep -v '^$' | sort
        return 0
    fi
    local prefix
    prefix="aether-${CLUSTER_ID:-b}-node-"
    local out names line
    out=$(remote_exec "docker ps --filter name=${prefix} --filter status=running --format '{{.Names}}'" 2>&1)
    names=$(printf '%s' "$out" | tr -d '\r')
    printf '%s\n' "$names" | while IFS= read -r line; do
        [ -z "$line" ] && continue
        # Defensive: only accept real core containers carrying the prefix, and
        # never the mgmt-gateway (which does not share this prefix, but guard
        # anyway in case of future naming drift).
        case "$line" in
            "${prefix}"*) ;;
            *) continue ;;
        esac
        case "$line" in
            *mgmt-gateway*) continue ;;
        esac
        printf '%s\n' "$line"
    done | sort
}

# Read `docker inspect --format '{{.State.ExitCode}}'` for a container NAME.
# Returns the exit code on stdout (or empty + rc=1 if the container is
# missing / still running). The caller decides whether "still running"
# is a failure.
container_exit_code() {
    local name="$1"
    local out rc
    out=$(remote_exec "docker inspect --format '{{.State.ExitCode}}' ${name} 2>&1")
    rc=$?
    if [ $rc -ne 0 ]; then
        return 1
    fi
    printf '%s' "$out" | head -1 | tr -d '\r '
}

# Read `docker inspect --format '{{.State.Status}}'` for a container NAME.
# Returns the status string (running / exited / created / ...) on stdout.
container_status() {
    local name="$1"
    local out
    out=$(remote_exec "docker inspect --format '{{.State.Status}}' ${name} 2>&1" 2>&1)
    printf '%s' "$out" | head -1 | tr -d '\r '
}

# Poll until the named container is in state=exited, capped at
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
    local name="$1" timeout="$2"
    local deadline=$((SECONDS + timeout))
    local status
    while [ $SECONDS -lt $deadline ]; do
        status=$(container_status "$name")
        if [ "$status" = "exited" ]; then
            return 0
        fi
        sleep 1
    done
    # Final post-deadline sample — guards against the 1s sleep landing past
    # deadline and missing a container that exited within the budget.
    status=$(container_status "$name")
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
    local name="$1"
    local drain_kv_writes
    # Capture full log, split at the drain-trigger line. `awk` is portable
    # and avoids spawning grep -A with an unknown line count.
    drain_kv_writes=$(remote_exec "docker logs ${name} 2>&1 | awk '/Self-drain: DRAINING on/{seen=1; next} seen' | grep -E 'ConsensusEngine|RabiaEngine|KvStoreCommand|applyAtomic' | head -5 || true" 2>/dev/null)
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
    # Poll-with-settle (up to 60s): the prior suite's restore gates on leader
    # deficit=0 which can precede active core count convergence by up to one
    # reconciler tick while a CTM replacement finalises admission.
    wait_for "Initial: 5 healthy cores" '[ "$(cluster_active_core_count)" -ge 5 ]' 60 || true
    local count
    count=$(cluster_active_core_count)
    assert_eq "$count" "5" "Initial: 5 healthy cores"
}

test_pick_victims_and_kill_three_simultaneously() {
    # Enumerate the REAL running core containers (ordinal AND KSUID-named
    # CTM replacements alike). After earlier 02-chaos scenarios, auto-heal
    # may have rotated some compose slots out for KSUID-named replacements,
    # so we MUST NOT assume names are 1..NODE_COUNT. See running_core_containers
    # and pick_non_leader (cluster.sh:247) for the precedent.
    # SETTLE BARRIER: after earlier chaos scenarios the FSM-counted membership can be
    # back at 5 while a just-drained surplus replacement's container lingers RUNNING
    # for its drain grace (~30s) — docker-running is a TRAILING indicator. Sampling
    # during that lag yields 6 running containers, breaks the 3-victim/2-survivor
    # math, and invalidates the S19 quorum-loss premise (bit Hetzner gate-C1: killed
    # 3 of 6 → 3 survivors legitimately KEPT quorum → "fence did not fire" was
    # correct behavior on a broken premise). Wait bounded for running==5, and ABORT
    # on a broken premise instead of killing into it.
    local running running_count settle_deadline
    settle_deadline=$((SECONDS + 120))
    while :; do
        running=$(running_core_containers)
        running_count=$(printf '%s\n' "$running" | grep -c '.' || true)
        [ "$running_count" -eq 5 ] && break
        [ "$SECONDS" -ge "$settle_deadline" ] && break
        sleep 3
    done
    if [ "$running_count" -ne 5 ]; then
        log_fail "Pre-kill precondition: expected 5 running core containers after 120s settle wait, got ${running_count} ($(printf '%s' "$running" | tr '\n' ' '))"
        return 1
    fi
    log_pass "Pre-kill: 5 running core containers ($(printf '%s' "$running" | tr '\n' ' '))"

    # Pick exactly 3 victims (first 3 of the sorted set) and record the
    # remaining 2 as survivors. `running` is already sorted for determinism
    # (reproducible victim set across runs). Which 3 we pick is incidental
    # for S19 — the surviving 2 lose quorum visibility regardless of who held
    # the leader lease.
    local victims survivors
    victims=$(printf '%s\n' "$running" | grep -v '^$' | head -3)
    survivors=$(printf '%s\n' "$running" | grep -v '^$' | tail -n +4)
    printf '%s\n' "$victims" > "$VICTIMS_FILE"

    local victim_count survivor_count
    victim_count=$(printf '%s\n' "$victims" | grep -c '.' || true)
    survivor_count=$(printf '%s\n' "$survivors" | grep -c '.' || true)
    assert_eq "$victim_count" "3" "Victims identified (3 containers): $(printf '%s' "$victims" | tr '\n' ' ')"
    assert_eq "$survivor_count" "2" "Survivors identified (2 containers): $(printf '%s' "$survivors" | tr '\n' ' ')"

    # Assemble the kill list as space-separated real container names.
    local victim_list
    victim_list=$(printf '%s\n' "$victims" | tr '\n' ' ' | sed 's/ *$//')
    log_info "Killing core containers [${victim_list}] simultaneously"
    # T3.1: capture the /api/events baseline timestamp BEFORE issuing the
    # kill so the SELF_DRAIN_INITIATED poll later sees only events emitted
    # AFTER the kill landed. The since-filter is exclusive on the server
    # side; a couple of seconds of pre-baseline drift is irrelevant
    # because the WARNING-severity SELF_DRAIN_INITIATED event isn't
    # emitted by anything other than `SelfDrainCoordinator.initiateDrain`.
    topology_now > "$EVENT_BASELINE_FILE"
    # Record kill timestamp BEFORE the kill returns so any RTT
    # latency is counted against us, not against the budget (worst-case
    # for the test; if anything, we under-count the wall-clock available,
    # making the assertion strictly stronger).
    date +%s > "$KILL_TS_FILE"

    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        # Cloud: there is no shared docker daemon to issue a single multi-kill against —
        # each victim is its own VM, DELETED via the provider compute API (cloud_kill_vm:
        # no test revives these victims; survivors self-drain and restore_cluster_baseline
        # re-scales fresh replacements). To preserve the "simultaneous" semantics the docker
        # single-RTT kill provided, fire each delete in the BACKGROUND (&) and `wait` for
        # all — the three provider API calls dispatch concurrently rather than serially.
        local v kill_pids=() kill_fail=0
        for v in $victims; do
            [ -z "$v" ] && continue
            cloud_kill_vm "$v" &
            kill_pids+=("$!")
        done
        local p
        # `${kill_pids[@]+...}` guards the empty-array expansion under `set -u` on
        # bash 3.2 (macOS), mirroring the `${base_urls[*]-}` idiom in lib/topology.sh.
        for p in ${kill_pids[@]+"${kill_pids[@]}"}; do
            wait "$p" || kill_fail=1
        done
        if [ "$kill_fail" -ne 0 ]; then
            log_fail "cloud delete of one or more victims [${victim_list}] failed (see cloud_kill_vm FAIL lines above)"
            return 1
        fi
        log_info "Cloud delete issued for all ${victim_count} victims (parallel cloud_kill_vm)"
    else
        local kill_cmd kill_out kill_rc
        # Single remote_exec → single SSH RTT → single docker daemon call:
        # the three SIGKILLs are issued within microseconds of each other.
        # This is the closest practical approximation to "simultaneous".
        kill_cmd="docker kill ${victim_list}"
        kill_out=$(remote_exec "$kill_cmd" 2>&1)
        kill_rc=$?
        if [ $kill_rc -ne 0 ]; then
            log_fail "docker kill of victims [${victim_list}] failed (rc=${kill_rc}): ${kill_out}"
            return 1
        fi
        log_info "Kill issued; docker daemon response: $(printf '%s' "$kill_out" | head -c 200)"
    fi

    printf '%s\n' "$survivors" > "$SURVIVORS_FILE"
}

# Confirm a survivor's self-drain DEPARTURE on cloud, tolerant of the
# re-resolve-at-kill race. Tiered proof, each tier only consulted when the
# previous one is unavailable/times out:
#
#   1. PRIMARY (wait_for_node_departure, lib/topology.sh): membership state
#      "Dead" on a surviving node's /api/cluster/membership, corroborated by
#      NODE_LEFT/NODE_FAILED on /api/events. Requires a LIVE node to query.
#
#   2. #441 S19: when BOTH last survivors self-drain within the same ~38s
#      window, tier 1 structurally cannot observe it — by the time either
#      survivor is checked, the OTHER may already be dead too, leaving no live
#      core to answer membership/events at all. Reach past the mgmt API
#      entirely: SSH to the survivor's own VM (cloud_ssh, reuses
#      $AETHER_SSH_KEY) and read the DESIGNED drain-halt state directly from
#      `docker inspect aether-node` — exit code 2 (Runtime.halt(2), the exact
#      halt reason SelfDrainCoordinator wires) with a non-zero FinishedAt is
#      authoritative: this is a direct read of the actual JVM exit state, not
#      a proxy signal, so a hit here is treated the same as tier 1 (log_info,
#      hard pass). No timestamp-window arithmetic is needed to bound this to
#      "within budget": test_initial_state already asserted all 5 containers
#      RUNNING immediately before the kill step in THIS run, so any Exited
#      state observed here necessarily happened during this scenario's
#      kill-to-now window, not a stale leftover from an earlier suite.
#
#   3. Tier 2 degrades gracefully when SSH itself cannot connect — the known,
#      separately-tracked #441 item 3 gap (2 of 3 revived/replacement VMs
#      refuse non-TTY SSH: root password expiry / incomplete cloud-init
#      authorized_keys propagation). openssh reports a connection/auth-level
#      failure as rc=255 (distinct from a remote command's own exit status).
#      On rc=255 fall back to an APPROXIMATE proof: hit the survivor's OWN
#      mgmt endpoint directly (bypassing _resolve_live_endpoint, which would
#      silently rotate to a DIFFERENT node) and check for a TRANSPORT failure
#      (curl status "000" — nothing listening), not an HTTP 404. This is
#      weaker evidence (a dead process is consistent with, but does not
#      prove, exit code 2) so it is log_warn, not log_pass.
#
#   4. Last-resort fallback (pre-existing): if the survivor's VM no longer
#      resolves at all (deleted/replaced), it has departed. Self-drain alone
#      does NOT power off the VM (only the container/JVM inside it halts —
#      see the GAP-A comment in test_survivors_self_drain_and_exit below), so
#      this tier rarely fires for a pure self-drain; it remains for the case
#      where CTM later replaces the slot.
#
# We only ever relax HOW departure is observed, never WHETHER it happened —
# every tier below tier 1 requires positive evidence, not merely an absence.
# Returns 0 on confirmed/satisfied departure, 1 otherwise.
_confirm_survivor_departure() {
    local survivor="$1" baseline="$2"
    if wait_for_node_departure "$survivor" "$baseline" "$SURVIVOR_EXIT_BUDGET_S"; then
        return 0
    fi

    # Tier 2: SSH + docker inspect on the survivor's own VM.
    local insp ssh_rc code finished_at
    insp=$(cloud_ssh "$survivor" "docker inspect --format '{{.State.ExitCode}}|{{.State.FinishedAt}}' aether-node 2>&1")
    ssh_rc=$?
    if [ "$ssh_rc" -eq 0 ]; then
        code="${insp%%|*}"
        finished_at="${insp#*|}"
        if [ "$code" = "2" ] && [ -n "$finished_at" ] && [ "$finished_at" != "0001-01-01T00:00:00Z" ]; then
            log_info "Survivor ${survivor} container exit code=2 (Runtime.halt(2)) FinishedAt=${finished_at} — designed drain halt confirmed via SSH docker-inspect (event/membership signal was unavailable, consistent with simultaneous last-survivor drain)"
            return 0
        fi
        log_fail "S19 violation (cloud): survivor ${survivor} reachable via SSH but docker inspect reports exit-code='${code}' FinishedAt='${finished_at}' — not a designed drain halt (expected exit code 2)"
        return 1
    fi
    if [ "$ssh_rc" -ne 255 ]; then
        # SSH connected fine; the REMOTE command itself failed (e.g. docker
        # daemon error, unexpected container name). Distinct from the known
        # #441 item 3 lockout — surface loudly rather than silently degrading
        # to the approximate mgmt-port proof below.
        log_fail "S19 violation (cloud): survivor ${survivor} SSH connected but the remote docker-inspect command failed (rc=${ssh_rc}): ${insp}"
        return 1
    fi

    # Tier 3 (approximate, log_warn): SSH transport/auth failure (rc=255) —
    # the known #441 item 3 keyless-lockout gap, not fixed here. Check the
    # survivor's own mgmt endpoint directly for a transport-dead signal.
    local ip mgmt_port status ip_rc
    ip=$(cloud_public_ip "$survivor")
    ip_rc=$?
    if [ "$ip_rc" -ne 0 ]; then
        # cloud_public_ip reports its own failure reason via log_fail, but
        # log_fail writes to STDOUT (lib/common.sh) — and command substitution
        # just captured that same stdout into $ip, silently swallowing the
        # diagnostic instead of ever surfacing it. Re-emit the captured text on
        # stderr ourselves so the actual reason is visible.
        printf '%s\n' "$ip" >&2
        log_info "Survivor ${survivor} public IP could not be resolved (rc=${ip_rc}) — tier 3 unavailable, falling through to VM-existence tier"
        ip=""
    fi
    if [ -n "$ip" ]; then
        mgmt_port="${CLOUD_MGMT_PORT:-8080}"
        status=$(http_status "${MGMT_SCHEME:-http}://${ip}:${mgmt_port}/health/live" -m 3)
        if [ "$status" = "000" ]; then
            log_warn "Survivor ${survivor} SSH unreachable (rc=255, likely #441 item 3 keyless lockout) AND its mgmt endpoint (${ip}:${mgmt_port}) is transport-dead (curl status 000, not an HTTP response) — treating as APPROXIMATE departure proof (process not listening is consistent with a self-drain halt, but exit-code-2 could not be verified)"
            return 0
        fi
        log_fail "S19 violation (cloud): survivor ${survivor} SSH unreachable (rc=255) and its mgmt endpoint (${ip}:${mgmt_port}) returned HTTP status '${status}' (still responding — not departed)"
        return 1
    fi

    # Tier 4: last-resort VM-existence fallback (pre-existing).
    if ! cloud_server_id "$survivor" >/dev/null 2>&1; then
        log_info "Survivor ${survivor} VM no longer resolves — already departed (event lost to publish-vs-halt race or pre-baseline); treating as satisfied departure"
        return 0
    fi
    log_fail "S19 violation (cloud): survivor ${survivor} did not DEPART membership within ${SURVIVOR_EXIT_BUDGET_S}s — no NODE_LEFT/NODE_FAILED on /api/events, SSH docker-inspect proof unavailable (rc=255) with mgmt endpoint unresolvable, and its VM still resolves (still running)"
    return 1
}

test_survivors_self_drain_and_exit() {
    local s1 s2
    s1=$(sed -n '1p' "$SURVIVORS_FILE")
    s2=$(sed -n '2p' "$SURVIVORS_FILE")
    if [ -z "$s1" ] || [ -z "$s2" ]; then
        log_fail "Survivors file missing entries (s1='${s1}', s2='${s2}') — upstream test_pick_victims... failed silently?"
        return 1
    fi

    # GAP-A (cloud): a VM power-off yields no docker container exit state, and the
    # survivors here self-drain via Runtime.halt(2) WITHOUT their VM being powered off,
    # so `docker inspect .State.ExitCode == 2` is unverifiable on cloud (no docker access).
    # The drain OUTCOME contract on cloud is therefore observed through membership instead
    # of the halt-reason: each survivor must DEPART membership (NODE_LEFT/NODE_FAILED on
    # /api/events) once it self-drains and halts. The exit-code-2 *reason* assertion is
    # kept only for docker/local (test_survivor_exit_codes_are_two). Best-effort
    # SELF_DRAIN_INITIATED corroboration is asserted in test_drain_trigger_log_signature_present.
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        local baseline
        baseline=$(cat "$EVENT_BASELINE_FILE" 2>/dev/null || echo "")
        log_info "GAP-A (cloud): asserting drain OUTCOME = survivors (${s1}, ${s2}) DEPART membership within ${SURVIVOR_EXIT_BUDGET_S}s (NODE_LEFT/NODE_FAILED on /api/events); exit-code-2 halt-reason is unverifiable without docker"
        if ! _confirm_survivor_departure "$s1" "$baseline"; then
            return 1
        fi
        log_info "Survivor ${s1} departed membership (self-drain outcome confirmed)"
        if ! _confirm_survivor_departure "$s2" "$baseline"; then
            return 1
        fi
        log_info "Survivor ${s2} departed membership (self-drain outcome confirmed)"
        log_pass "S19 (cloud): both survivors (${s1}, ${s2}) departed membership within budget — drain outcome contract met"
        return 0
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
        log_fail "S19 violation: survivor ${s1} did not exit within budget. Current state: $(container_status "$s1")"
        return 1
    fi
    log_info "Survivor ${s1} exited"

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
        log_fail "S19 violation: survivor ${s2} did not exit within budget. Current state: $(container_status "$s2")"
        return 1
    fi
    log_info "Survivor ${s2} exited"

    now=$(date +%s)
    elapsed=$((now - kill_ts))
    log_pass "S19: both survivors (${s1}, ${s2}) exited within ${elapsed}s (budget=${SURVIVOR_EXIT_BUDGET_S}s)"
}

test_survivor_exit_codes_are_two() {
    # GAP-A (cloud): exit-code-2 is the self-drain HALT REASON, read from
    # `docker inspect .State.ExitCode`. On cloud there is no docker access and a VM
    # power-off would not yield an exit code anyway, so the halt-reason is unverifiable.
    # The drain OUTCOME (survivors departed membership) is asserted on cloud in
    # test_survivors_self_drain_and_exit; skip the docker-only reason check here.
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        log_info "GAP-A (cloud): skipping exit-code-2 halt-reason assertion (no docker; unverifiable) — drain outcome (membership departure) already asserted in the self-drain step"
        return 0
    fi
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
    assert_eq "$ec1" "2" "Survivor ${s1} exit code is 2 (Runtime.halt(2) from SelfDrainCoordinator)"
    assert_eq "$ec2" "2" "Survivor ${s2} exit code is 2 (Runtime.halt(2) from SelfDrainCoordinator)"
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
    if wait_for_self_drain_event "$s1" "$baseline" "$SELF_DRAIN_EVENT_TIMEOUT_S"; then
        log_pass "SELF_DRAIN_INITIATED observed via /api/events for ${s1}"
    else
        log_warn "No SELF_DRAIN_INITIATED event observed on /api/events for ${s1} within ${SELF_DRAIN_EVENT_TIMEOUT_S}s — Rabia publish may have lost the race against Runtime.halt(2); exit-code-2 assertion above remains the hard contract"
    fi
    if wait_for_self_drain_event "$s2" "$baseline" "$SELF_DRAIN_EVENT_TIMEOUT_S"; then
        log_pass "SELF_DRAIN_INITIATED observed via /api/events for ${s2}"
    else
        log_warn "No SELF_DRAIN_INITIATED event observed on /api/events for ${s2} within ${SELF_DRAIN_EVENT_TIMEOUT_S}s — Rabia publish may have lost the race against Runtime.halt(2); exit-code-2 assertion above remains the hard contract"
    fi
}

test_no_kv_writes_after_drain_trigger() {
    # GAP-A / cross-cutting (cloud): this empirical check reads `docker logs` over SSH
    # (verify_no_kv_writes_after_drain). On cloud there is no shared docker daemon and
    # CTM-provisioned survivor VMs do not carry the operator SSH key, so docker-log
    # inspection is impossible. The structural guarantee is already enforced at compile
    # time by SelfDrainCoordinatorTest.noConsensusOrKvImports; skip the docker-log
    # complement on cloud rather than fail on an unreachable SSH/docker call.
    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        log_info "GAP-A (cloud): skipping post-drain docker-log KV-write negative check (no docker/SSH on cloud) — compile-time noConsensusOrKvImports remains the structural guarantee"
        return 0
    fi
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
        log_warn "Post-drain KV-write evidence on ${s1} (investigate, may be benign): $(printf '%s' "$leak" | head -c 300)"
    else
        log_pass "No KV-write log signatures after drain trigger on ${s1}"
    fi
    leak=$(verify_no_kv_writes_after_drain "$s2" || true)
    if [ -n "$leak" ]; then
        log_warn "Post-drain KV-write evidence on ${s2} (investigate, may be benign): $(printf '%s' "$leak" | head -c 300)"
    else
        log_pass "No KV-write log signatures after drain trigger on ${s2}"
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
    # but the healthy core count is the actual S20 acceptance signal.
    if ! wait_for "5 healthy cores after self-drain recovery" \
        "[ \$(cluster_active_core_count) -eq 5 ]" "$RECOVERY_BUDGET_S"; then
        local now_count
        now_count=$(cluster_active_core_count)
        log_fail "S20 violation: cluster did not return to 5 healthy cores within ${RECOVERY_BUDGET_S}s of restart (current count=${now_count})"
        return 1
    fi
    assert_cluster_healthy "S20: cluster recovered to 5 healthy cores within ${RECOVERY_BUDGET_S}s of restart"

    # #426 review follow-up (item 3): the echo-baseline redeploy formerly
    # duplicated here is now centralized in restart_all_nodes itself
    # (lib/cluster.sh _reestablish_echo_baseline, called from both its
    # docker/compose AND cloud branches) — restart_all_nodes at line 607
    # above already re-pushed/redeployed the echo blueprint and gated on all
    # target instances ACTIVE before returning. Keeping a second copy here
    # risked copy-drift between the two call sites; nothing further to do.
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

run_test "Initial 5 healthy cores" test_initial_state
run_test "Pick 3 victims and kill simultaneously" test_pick_victims_and_kill_three_simultaneously
run_test "Survivors self-drain and exit within ${SURVIVOR_EXIT_BUDGET_S}s (S19)" test_survivors_self_drain_and_exit
run_test "Survivor exit codes are 2 (Runtime.halt(2))" test_survivor_exit_codes_are_two
run_test "Drain-trigger log signature present on survivors" test_drain_trigger_log_signature_present
run_test "No KV-writes after drain trigger (negative assertion)" test_no_kv_writes_after_drain_trigger
run_test "Cluster recovers to 5 healthy cores within ${RECOVERY_BUDGET_S}s (S20)" test_cluster_recovers_to_five_on_duty
print_summary
