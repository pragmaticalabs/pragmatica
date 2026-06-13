# Session Handover — 2026-05-25 (FSM-sovereign writer: re-projection FIXED, leader-gate REGRESSION found on Docker)

**Branch:** `fix/membership-sovereign-writer` (off `release-1.0.0-rc1` @ `fbad56c3a`) — **pushed, NOT landed.**
**HEAD:** `e97130ac8` (FSM-sovereign writer) on top of `adb5ba1cf` (SWIM bare-join, rebased held work).
**DO NOT LAND** — Docker cluster-B shows a regression (below).

## 0. TL;DR
Implemented the approved **full-sovereign B**: route all `LifecycleCommand`s through `MembershipFsm.applyLifecycleCommand` → reducer, so a `STOPPED+FORCED` peer can't be re-promoted (the S01 re-projection). **Re-projection mechanism is fixed — unit-proven** (Step-0 reproduces the bug; 4/4 `MembershipFsmCommandIngressTest`; `aether-deployment` fully green; in-process chaos spike green). **BUT Docker cluster-B suite 02 regressed**: cluster accumulates ghost ON_DUTY entries (`expected 5, got 10`), auto-heal stalls (360s timeout), generation never quiesces, 100% error under kill-load. **Prime suspect: the leader-gate I added drops `ForceDecommission`/`RecordJoining` during leader-churn** (DirectLifecycleWriter wrote unconditionally via consensus; my ingress returns `accepted=false` when `!isLeader`) → dead nodes never reach terminal STOPPED → ghosts pile up → no convergence.

## 1. What was committed (validated at unit/in-process level)
- `adb5ba1cf` fix(swim): bare-join HEALTHY only from real PeerConnected (held work, φ excluded).
- `e97130ac8` fix(membership): FSM-sovereign lifecycle writer. Files:
  - `LifecycleCommand.java` — added `peer()`/`at()` accessors to the sealed interface.
  - `MembershipFsm.java` — `applyLifecycleCommand(LifecycleCommand): Promise<Boolean>` (leader-gate → resolveState → `reducer.apply(state, command, ALWAYS_CONFIRMED)` → nop short-circuit / propose+mutate); `resolveState(peer)` (fsmStates → KV-`forEachLifecycle` fallback so retained `STOPPED+FORCED` → `Stopped`, not `Untracked`); replaced the two `getOrDefault(untracked)` sites (:825/:866).
  - `LifecycleWriter.java` + new `FsmRoutedLifecycleWriter.java` — delegates all commands + legacy `request*` (HLC-stamped) to the FSM ingress, preserves `CommandReceived/Applied` audit.
  - `AetherNode.java` — `membershipFsmRef` forward-ref (FSM built after drainCoordinator which is built after the writer); wired `fsmRoutedLifecycleWriter` instead of `directLifecycleWriter`.
  - Drain-trigger move (full-sovereign): `NodeLifecycleRoutes.initiateDrain` dropped explicit `prepareDrain`; `ClusterTopologyManagerRecord.terminateSingleNode` reduced to `awaitDrainAck` only — `InvokeDrain` is now the sole drain trigger.
  - Tests: `DirectLifecycleWriterForcedTombstoneTest` (Step-0, bug reproduces), `MembershipFsmCommandIngressTest` (4/4 fix), `ClusterTopologyManagerScaleDownDrainTest` (updated: CTM no longer calls prepareDrain).

**Validation that PASSED:** Step-0 (DirectLifecycleWriter re-promotes STOPPED+FORCED → confirmed); `MembershipFsmCommandIngressTest` 4/4 (nop in-fsmStates, nop via resolve-from-KV, legal JOINING→ON_DUTY promotes, non-leader gated); `aether-deployment` whole module green; `node` compiles; in-process `MembershipChaosSpikeTest` PASS (clean-kill decommission 3.7s, auto-heal 15s); JBCT lint clean on all my files (pre-existing RET-01/EX-01 in RecentCommandsBuffer/PhiAccrualDetector + the formatter's multi-top-level-type parse limit on LifecycleWriter.java are unrelated, pre-existing build.sh blockers).

## 2. The Docker regression (cluster-B suite 02, remote)
Log: `/tmp/docker-s01.log`. Sub-suite tallies: chaos 5P/1F, kill-leader 5P/0F, kill-multiple 4P/1F (harness double-kill flake), kill-node 2P/2F, self-drain cascade-corrupted.
- **S01 budget** FAIL: killed JOINING node `<absent>` at 92s — BUT decommission *did* fire (`smoking-gun NODE_FAILED swim-departed` PASS) and the node did NOT flap to ON_DUTY (`pick_non_leader excludes it` PASS). So re-projection is NOT the failure here — it's slow detection (transport-fast path doesn't fire for a not-yet-connected JOINING node → slow swim-departed → STOPPED late) + GC-vs-poll race (Pillar-3: the *event* assertion passes, the *KV-state* poll is racy).
- **`expected 5 ON_DUTY, got 10`** + auto-heal 360s timeout + recurring `restore_baseline: generation did not quiesce` + `Kill_node_during_active_load error rate 100%` → **cluster does not converge after churn; ghost ON_DUTY accumulate.**

## 3. ROOT-CAUSE HYPOTHESIS (strong) — the leader-gate regression
`MembershipFsm.applyLifecycleCommand` does `if (!isLeader.getAsBoolean()) return Promise.success(false);`. `DirectLifecycleWriter` (old) wrote via `commandApplier` (consensus) **unconditionally from any node**. During a chaos suite's constant leader-churn, a `ForceDecommission`/`RecordJoining` issued in a leader-transition window (isLeader transiently false) is now **silently dropped** instead of written → dead nodes never written STOPPED → linger ON_DUTY → over-count to 10 → auto-heal keeps adding → never converges.

**The design tension:** the leader-gate is needed for *correct reduction* (a follower with stale `fsmStates`=Untracked would `untrackedDirectToOnDuty` → re-open re-projection), BUT it breaks *churn-time liveness* (drops decommissions).

## 4. NEXT (in order)
1. **Pre-fix baseline (ATTRIBUTION):** run suite 02 on clean `rc1` (no branch) — `cd aether/tests/integration && ./run-tests.sh --env remote --suites 02 --skip-build` (after `git checkout release-1.0.0-rc1` + rebuild node JAR). Diff failures. If over-count/auto-heal/quiesce fail on rc1 too → pre-existing (cluster-B already unstable per handover-24c) and my fix is a clean re-projection improvement. If NEW → confirmed regression. **NOTE: the remote cluster-B may be left corrupted (10 ghosts) by this session's run — reset it first** (the run's own teardown should have cleaned up; verify `docker ps` on `$TARGET_HOST`).
2. **Rework the gate** (likely fix, lowest-risk): **leader-gate ONLY the promotion commands** (`ForceOnDuty`, `RecordJoining`) — they're the only ones where stale-follower reduction can re-project; let `ForceDecommission`/`ForceDrain`/`RequestReJoin` propose unconditionally (they go toward terminal/draining, can't re-project, and gating them is what drops decommissions during churn). Alternative: remove the gate entirely (promotion commands are leader-originated in practice — `ClusterSyncPongSignalFan.fanIfLeader` for ForceOnDuty; CTM/reconciler on leader) and rely on the reducer-nop; or make `resolveState` treat a retained `STOPPED+FORCED` KV value as authoritative over stale `fsmStates`.
3. Re-validate: in-process spike + Docker suite 02. Only then land.
4. Separately (deferred, #231): the **slow-detection** axis for JOINING-window kills (transport-fast path not firing) — S01-green needs this OR the Pillar-3 test-robustness (assert on the NODE_FAILED event, already passing, not the GC-racy KV poll).

## 5. Honest status
- Re-projection fix: **correct + unit-proven**, but NOT landable — the leader-gate regressed convergence under churn.
- §10 (handover-24c) "re-projection is THE bug" was **incomplete**: re-projection was one symptom (fixed); S01-green also needs detection-speed (the SENSE/φ axis deferred to #231), and the leader-gate must be reworked.
- The full-sovereign drain-trigger change (InvokeDrain sole trigger) is untested in isolation — fold into the re-validation.
- Decision log + this analysis should go into `membership-failure-detection-unification.md` §8 on the next pass.
