<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-31 — Membership-v2 lifecycle collapse SHIPPED; one root blocker remains

## ⚡ START HERE / TL;DR

The **synthetic-lifecycle layer is gone** and the change is **validated correct** on live Docker. Three commits landed. Every remaining destructive-suite failure traces to **one root bug (#34): provider-minted replacement nodes never reach `READY` in the leader's pong readiness view.** Fix that and the multi-kill suites should largely fall into place.

- **Branch:** `release-1.0.0-rc1`, **HEAD `61861bf39`**, working tree clean.
- **18 commits ahead of origin — DO NOT push** (RC1 not green).
- This session's 3 commits:
  - `25efb809f` refactor(membership): collapse synthetic lifecycle → presence + NodeReportedState; NTT-evict failure-removal + NODE_FAILED event; leader-forward control-plane routes; rm LocalDisconnect
  - `9aa0a182c` fix(docker): propagate DOCKER_GID to provisioned replacements + guard unresolved env placeholder
  - `61861bf39` test(integration): harness vocabulary migration + READY barrier + endpoint-resolution hardening

## 1. What shipped

### `25efb809f` — the core refactor (60 files, +923/−1338)
- **Deleted** the FSM-era synthetic lifecycle: `NodeLifecycleState`, consensus `LifecycleState` (file), `MembershipView.MemberStatus`, the `synthesizeLifecycle`/`deriveLifecyclesFromMembership` shim, `ON_DUTY`/`STOPPED`. Zero `ON_DUTY` in production.
- **Membership/quorum are presence-derived** from `ntt.currentMembers()`; node work-state is the real `NodeReportedState` (SYNCING/READY/DRAINING) reported via pong. CDM DRAINING re-sourced from the real pong set.
- **NTT-evict failure-removal:** `NodeTopologyTracker.evict()` + leader-pinned confirmed-dead trigger (SWIM-FAULTY ∩ liveness-gone) in `LeaderReconciler`.
- **`NodeRemoved → NODE_FAILED` /api/events wiring** — `ClusterEventAggregator.onMembershipDecision` was a no-op stub ("re-sourcing is future work"); this was the *actual* missing piece of #23. Now: `NodeRemoved→NODE_FAILED/CRITICAL`, `NodeDecommissioned→NODE_LEFT/WARNING`, with the missing router subscriptions.
- **Leader-forward audit (control-plane-collapse residue):** 40 `ANY` management routes → `LEADER`, 32 → `LOCAL`, **zero `ANY` remain**. `ANY` (any-core-node, no leader-forward) was a vestige of the distributed control plane; control plane is leader-only now, so control-plane routes forward to leader and only genuinely node-local diagnostics stay local. `ManagementRoute.java`. Fixes the leader-local readiness/`/api/events` views being queried off-leader.
- **Deleted `LocalDisconnect`** + faulty-counter (obsolete once removal is leader-pinned + quorum-gated).

### `9aa0a182c` — DOCKER_GID provisioning fix
`DockerComputeProvider` ran `docker run --group-add ${env:DOCKER_GID}` with the placeholder **un-interpolated** → exit 125 → CTM circuit-breaker → chaos/scaling wedge. Root: syntax + resolution were fine (`[cloud.compute]` IS run through `resolveEnvVars` at `ConfigLoader:327`), but **`DOCKER_GID` was never propagated to provider-minted replacements** (seeds get it from compose env; replacements didn't). Fix: `propagateEnvVar(command,"DOCKER_GID")` + a defensive `!startsWith("${env:")` guard. **Validated: priorFailureCount=0, no exit-125.**

### `61861bf39` — harness migration
Harness spoke the old FSM vocabulary in ~200 sites. Migrated functional sites: `JOINING→SYNCING`, `ON_DUTY→READY`, `DECOMMISSIONED→absence-from-membership` assertion. Added a **READY readiness barrier** to `restore_cluster_baseline` (waits for N cores reporting `READY`, soft-warn). Hardened `_resolve_live_endpoint` (label-discovery port-parse dropped IPv6/`127.0.0.1` binds → `rc=7`). Lint-baseline line numbers updated.

## 2. Validation (live Docker, remote cluster-B)

**The refactor is correct** — proven by live-cluster diagnosis, not code review:
- Consensus healthy (phase advancing), quorum holds, readiness fine on the live wedged cluster.
- **Passing test files:** `test-kill-leader` 5/0 (re-election + auto-heal-to-5), `test-gossip-encryption` 6/0, `test-swim-detection` 3/0 (NODE_FAILED in 9s on a clean single kill).
- #32 cleared the provisioning wedge.

**NOT green** — destructive multi-kill paths fail, all tracing to #34 (below). No clean full 02,12 tally exists because the runs dragged on the 600s READY-barrier + recurring `rc=7` once the cluster relied on non-surfacing replacements.

## 3. THE NEXT BLOCKER — #34 (dominant root of all remaining failures)

**Provider-minted replacement nodes never reach `READY` in the leader's pong-based readiness view.** Only **3/5 cores report READY, persistent over 600s** — confirmed on a **pristine** cluster (not timing, not pollution).

- `READY = consensusActive && subsystemsReady`, reported via pong, collected **leader-only** by `ClusterSyncPongSignalFan` (leader-gated `if (!isLeader) return`).
- Compose **seed** nodes reach READY; **replacements don't surface** in the leader's view.
- **This one root explains:** catch-replacement "never reported SYNCING/READY"; `restore_cluster_baseline` READY-barrier 3/5 timeout; `pick_non_leader: only 1/2`; multi-kill `NODE_FAILED` misses (leader-gated production during churn on non-integrated replacements); recurring `rc=7` control-plane wedge after multi-kill (leader becomes/relies on a non-surfacing replacement).
- **Echoes the DOCKER_GID lesson:** replacements miss something seeds get.
- **Investigate (live-cluster, like the wedge):** does the leader add provider-minted replacements to its ClusterSync **ping set**? Do replacements emit `ConsensusActive` + `onSubsystemsReady` + a pong that **reaches the leader**? Files: `ClusterSyncPongSignalFan`, the leader ping-dispatch (`ClusterSyncContext`), `NodeReportedStateHolder`, AetherNode consensus-edge wiring (`:2033-2044`).
- **Also:** tune the READY-barrier 600s timeout (wastes time until #34 is fixed).

## 4. Other open items
- **#30** sub-quorum self-drain exit 0≠2 — re-check after #34 (likely downstream).
- **#33** endpoint-resolution: port-parse fixed, but `rc=7` recurs post-multi-kill because the *leader itself* is a non-surfacing replacement (→ #34, not a harness bug).
- **#18** P4 minor comment/doc tidy.
- **Pre-existing / separate (not this session):** 05-security auth (`whoami` anonymous/dev-mode), 08-resources `Pause_task`, 12-network partition gossip-TLS handshake; phantom KSUID/case-DNS reconnect noise; slice-artifact-distribution loop.
- **Not re-validated this session:** the 9 non-destructive cluster-A suites — compile/unit green, low risk, but unverified against the leader-forward audit.

## 5. Process notes
- **Every diagnosis was settled by live-cluster evidence**, not code review — code-review conclusions were wrong twice (cold-start last session; "resolveEnvVar leaks literal" this session — it was propagation). Wedge evidence saved at `/tmp/wedge-diag.txt`.
- Java → jbct-coder; maven → build-runner with focused `mvn -pl <m> install -DskipTests -am` (NEVER verify/format/`./build.sh`; NEVER with HCLOUD_TOKEN). Shell harness → general-purpose/code-reviewer.
- Mid-flight `run-tests.sh` kills leave KSUID-named provisioned-replacement zombies the harness sweep misses → purge with `docker rm -f $(docker ps -aq --filter name=aether-b)` + `compose down -v` before re-running. Let runs finish (teardown cleans up).
- Memory: `project_membership_v2_lifecycle_collapse.md`.
