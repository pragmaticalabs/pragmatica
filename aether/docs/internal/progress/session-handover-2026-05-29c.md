<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-29c — P2-b wired & Docker-validated; P3 DELETIONS DONE (LocalQuorumWatcher + ReachabilityAggregator); only ClusterPhaseView repoint remains

**Branch:** `release-1.0.0-rc1`. **HEAD:** `56253736f` (+ handover commit). **6 commits LOCAL/unpushed** atop pushed `bf9507cbf` (push at checkpoint).

## ⭐ START HERE
Continuation of the membership-unification big-bang (design of record: `aether/docs/specs/membership-unification-spec.md`; prior: `session-handover-2026-05-29b.md`). This session:
1. **P2-b DONE + Docker-validated** — SWIM-fed `MembershipTracker` is the live consensus `MembershipView`.
2. **FQCN sweep** of `AetherNode.java` (44 removed).
3. **P3 DELETIONS DONE** — `LocalQuorumWatcher` → tracker-fed `QuorumLossDetector` (arm-after-first-quorum guard); `ReachabilityAggregator` deleted (SWIM is single liveness signal). Snapshot + reconciler membership read the tracker.
4. **REMAINING P3:** only `ClusterPhaseView` repoint (a repoint, not a deletion). Then P4 (Bug B) → P5 → P6.

## Commits this session (atop pushed `bf9507cbf`)
```
56253736f refactor(membership): P3 — delete ReachabilityAggregator; MembershipView strict 2-arg; ClusterSyncPing/CommunityReport reachability emptied (no wire change); AetherNode unwired
107491b1c refactor(membership): P3 — delete LocalQuorumWatcher; quorum-loss via QuorumLossDetector (arm-after-first-quorum; ACTIVATES previously-dormant drain — needs chaos validation post-Bug-B)
232e52fe4 refactor(membership): P3 (part) — LeaderReconciler + generation-snapshot membership read the unified MembershipView/tracker
2aecf81b6 feat(membership): P2-b — wire MembershipTracker as live MembershipView via TrackerBackedGenerationSnapshotSource; FQCN sweep
bd60edbb5 docs: handover 2026-05-29c (this file, earlier revision)
```
All unit-green (aether/node -am clean install + aether-deployment/aether-metrics module tests).

## ⚠️ SAFETY NOTE — quorum-loss self-drain was DORMANT, now ACTIVATED
The old `LocalQuorumWatcher` never fired in production (`onConfiguredCoreCountChanged` had ZERO prod callers → threshold 0 → never below). `QuorumLossDetector` (new, `aether-deployment/.../ntt/`) is fed the REAL core count + the tracker's stable member count, so it is LIVE. **Guarded** by an arm-after-first-quorum latch (`armed` set once `memberCount ≥ quorum`; fires only `armed && sameWindow && stillBelow`). Grace = `quorumLossDrainThreshold` (8s). 30 unit tests green. **The real-quorum-loss→self-drain path is NEW behavior, UNVALIDATED in chaos** — suite 02 must exercise it once Bug B (P4) unblocks the gate.

## NEXT — ClusterPhaseView repoint (last P3 item)
Investigation verdict: ClusterPhaseView carries leader-awareness the tracker lacks (NORMAL requires a leader), so it CANNOT be deleted — make it a thin adapter:
- **ClusterPhaseView** (aether-deployment): change factory to `clusterPhaseView(Supplier<MembershipPhase> trackerPhaseSupplier, BooleanSupplier haveLeaderReader)`. `compute()` = map `trackerPhase` (swim `MembershipPhase{COLD_BOOT,NORMAL,RECOVERING}`) 1:1 to `AetherValue.ClusterPhase`, then **downgrade NORMAL→RECOVERING if `!haveLeader`**. Drop the old `MembershipViewReader`/`priorPhaseReader`/`stableWindow`/coreSize/timeout params + `stableWindowSatisfied()` dead helper. Update its test. (Delegate to jbct-coder.)
- **AetherNode wiring** (`~:1135–1172`): replace `phaseMembershipReader` + the `clusterPhaseView(...)` construction with `ClusterPhaseView.clusterPhaseView(trackerPhaseSupplier, () -> healthLeaderSupplier.get().isPresent())`. **Forward-ref trap:** `membershipTracker` is declared ~`:1606`, AFTER ClusterPhaseView (~`:1165`) — so use the existing `membershipTrackerRef` param: `Supplier<MembershipPhase> trackerPhaseSupplier = () -> Option.option(membershipTrackerRef.get()).map(MembershipTracker::phase).or(MembershipPhase.COLD_BOOT);`. **Re-add** `import org.pragmatica.swim.membership.MembershipPhase;`. **Delete now-dead vars:** `swimDetectorRefForPhase` (decl ~:1147 + `.set()` ~:1491), `clusterPhaseReader` (~:1135), `phaseInQuorum` (~:1152), `phaseMembershipReader` (~:1153). Confirm `effectivePhaseSupplier` (~:1172) still consumes `clusterPhaseView` (keep it).

## What's wired now
- **`TrackerBackedGenerationSnapshotSource`** (new file, `aether/node`) — adapter exposing the tracker as `MembershipView` once `phase() != COLD_BOOT`, else delegates to the KV-projected view (cold-start runs on the proven QUIC-count path, then hands off). Term/epoch delegate to KV source.
- **`AetherNode.assembleNode`**: constructs `membershipTracker` right after `ntt` (config = `MembershipTrackerConfig.fromDepartureTimeout(nttDepartureTimeout, 500ms)`), feeds it the SWIM observation stream + QUIC connect/disconnect taps (`nttConnectTap`/`nttDisconnectTap` now also call `membershipTracker::onQuicReconnect/onQuicDisconnect`), stops it on shutdown (record component). The `GenerationSnapshotSource` handed to consensus is the tracker-backed adapter (forward-ref `membershipTrackerRef`).
- **GenerationSnapshotPublisher** `memberSupplier`: `ntt::currentMembers` → `membershipTracker::members`.
- **LeaderReconciler**: factory param 2 `NodeTopologyTracker ntt` → `MembershipView membershipView` (consensus interface; tracker implements it). Internals use `coreMemberIds()` / `.size()`. `LocalQuorumWatcher` param RETAINED. 18/18 tests green.
- The tracker's `MembershipListener` is still `NOOP` (consensus reads via the pull adapter; no push consumer yet).

## Docker validation (suite 00 smoke, remote)
**Formation path GREEN with tracker live** — 5 nodes + leader (`aether-a-node-1`) in 0s, generations quiesced (1:9, 1:19), quorum(5), liveness 200, all nodes visible, status/events OK, blueprint deploy accepted, app route wired. **P2-b proven non-regressive.**
**Only 2 failures, both = Bug B (known, P4 scope):** `Slices_provisioned` 0 instances (240s timeout) and `App_request_succeeds` 404. Root cause unchanged: readiness route appended to `aetherEntries` AFTER the `allEntries.addAll(...)` merge → never installed → CDM `allocatable` empty. Gate aborts later suites (correct).

## REMAINING P3 (do in a focused pass — pong-path deletion is wide)
1. **`LocalQuorumWatcher` — ✅ DONE** (commit `107491b1c`). Replaced by tracker-fed `QuorumLossDetector`; see SAFETY NOTE above.
2. **Delete `ReachabilityAggregator`** — NOT STARTED. WIDE blast radius: `ClusterSyncCollector`, `metricsCollector` local-snapshot supplier (`AetherNode` ~`:1083`), `ClusterSyncScheduler` tick (~`:1126`), `attachQuicConnectivityReporter` (~`:1749`), `SpokesmanPingLoop` (~`:1809`), `QuicClusterNetwork`, + 4 test files. Under the unified model SWIM (fed by QUIC hints) is the single liveness signal, so its reachability view is redundant — but each consumer needs removal or repoint to the tracker. **First investigate each consumer** (does it need a replacement signal from the tracker, or just deletion?) before cutting. Treat as its own sub-step; delegate the `aether-deployment`/pong-path Java to `jbct-coder`, do AetherNode wiring directly.
3. **`ClusterPhaseView` phase ← tracker** — NOT STARTED. Currently builds a deployment `MembershipView.strict(swimSnapshotSupplier, inQuorum)` lambda (`AetherNode` ~`:1215`). Target: derive phase from `tracker.phase()` (swim `MembershipPhase{COLD_BOOT,NORMAL,RECOVERING}` maps 1:1 to `AetherValue.ClusterPhase`). Keep it thin.

## THEN P4 / P5 / P6 (per spec §7)
- **P4** (gets suite 00 fully green): per-member state → `Map<NodeId,NodeState>` keyed by tracker membership; `allocatable = members.filter(READY)`; **wire all routes into the live router (fixes Bug B's `aetherEntries`-after-merge defect)**; QUIC-evict cleans state.
- **P5**: reconciler identity-aware (never replace a configured core peer not-yet-joined) + count-smoothing (fixes Bug C death-spiral).
- **P6**: delete NTT + remaining dead code; full build; Docker `--suites 00,02` green end-to-end.

## Traps / notes
- **AetherNode = direct edits only** (truncation magnet; ~3260 lines, single `assembleNode`). `aetherNode` is a **record** (decl ~`:551`); shutdown-time deps must be record components (that's why `membershipTracker.stop()` works — it's a component).
- Build: `mvn -pl aether/node -am clean install -Dmaven.test.skip=true` (NEVER `mvn verify` — HCLOUD failsafe). Integration: `cd aether/tests/integration && ./run-tests.sh --env remote --suites 00 --skip-build`.
- Delegate `aether-deployment` Java to `jbct-coder`; AetherNode wiring stays in main thread.
- Remote `$TARGET_HOST` cluster-a is cleaned by the harness's `deploy_docker` zombie sweep each run.
- 2 unpushed commits (`2aecf81b6`, `232e52fe4`).

## References
- Spec: `aether/docs/specs/membership-unification-spec.md`
- Prior: `aether/docs/internal/progress/session-handover-2026-05-29b.md`
- Memory: `[[project_membership_v2_redesign]]`
