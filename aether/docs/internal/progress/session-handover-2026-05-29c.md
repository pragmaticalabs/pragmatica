<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-29c — P2-b wired & Docker-validated; P3 IN PROGRESS (membership repoints + LocalQuorumWatcher deletion done; ReachabilityAggregator + ClusterPhaseView remain)

**Branch:** `release-1.0.0-rc1`. **HEAD:** `107491b1c`. **Working tree:** handover doc untracked. **3 commits LOCAL/unpushed** atop pushed `bf9507cbf`.

## ⭐ START HERE
Continuation of the membership-unification big-bang (design of record: `aether/docs/specs/membership-unification-spec.md`; prior handover: `session-handover-2026-05-29b.md`). This session:
1. **P2-b DONE + Docker-validated** — the SWIM-fed `MembershipTracker` is now the live consensus `MembershipView`.
2. **FQCN sweep** of `AetherNode.java` (44 redundant qualifications removed).
3. **P3 IN PROGRESS** — snapshot + reconciler membership read the tracker; **`LocalQuorumWatcher` DELETED** → quorum-loss self-drain now sourced from the tracker via new `QuorumLossDetector` (arm-after-first-quorum guard). **`ReachabilityAggregator` deletion + `ClusterPhaseView` repoint REMAIN.**

## Commits this session (atop pushed `bf9507cbf`)
```
107491b1c refactor(membership): P3 — delete LocalQuorumWatcher; quorum-loss self-drain via QuorumLossDetector (arm-after-first-quorum; ACTIVATES previously-dormant drain — needs chaos validation post-Bug-B)
232e52fe4 refactor(membership): P3 (part) — LeaderReconciler + generation-snapshot membership read the unified MembershipView/tracker
2aecf81b6 feat(membership): P2-b — wire SWIM-fed MembershipTracker as live MembershipView via TrackerBackedGenerationSnapshotSource (warm-up hand-off; NTT-parity config); FQCN sweep
```
All UNPUSHED (or push at this checkpoint). (`bf9507cbf` and earlier are pushed.)

## ⚠️ SAFETY NOTE — quorum-loss self-drain was DORMANT, now ACTIVATED
The old `LocalQuorumWatcher` never fired in production: its `configuredCoreCount` was never wired (`onConfiguredCoreCountChanged` has ZERO prod callers) → threshold 0 → `isBelowThreshold()` always false. The quorum-loss→`DrainProcedure` chain was dead.
`QuorumLossDetector` (new, `aether-deployment/.../ntt/`) is fed the REAL core count (via `configuredCoreCountSupplier`) and the tracker's stable member count, so it is now LIVE. **Guarded** by an arm-after-first-quorum latch (`armed` set true once `memberCount ≥ quorum`; `onFiringCheck` no-ops unless `armed && sameWindow && stillBelow`) so a forming/minority node never self-drains during cold-start. Grace = `quorumLossDrainThreshold` (8s default). 30 unit tests green. **The real-quorum-loss→self-drain path is NEW behavior and is UNVALIDATED in chaos** — suite 02 must exercise it once Bug B (P4) unblocks the gate.

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
