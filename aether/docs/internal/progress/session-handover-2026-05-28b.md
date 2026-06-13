<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-28b — Membership v2 Implementation Mid-Cutover

**Branch:** `release-1.0.0-rc1`. **HEAD:** `8ee6da68a`. **All work LOCAL/unpushed.**

**Candidate tag move recommended:** advance `v1.0.0-rc1-candidate` to `8ee6da68a` at start of next session (currently at `810f4c5d4`).

## 0. TL;DR

This session **implemented E1 of the membership v2 migration in full**, then **collapsed the original E2 + E3 + E4 staging into a single "direct cutover"** per user direction ("no persisted topology in KV; old code is broken anyway, no rollback value"). E2 is now ~30% through its consolidated form. **NTT + LocalQuorumWatcher + LeaderReconciler + DrainProcedure + CTM v2 methods are in place and tested (44/44 NTT-tier tests green).** The old FSM/slot/lifecycle machinery is partly deleted but the core (FSM + reducer + NodeLifecycleKey + ProvisioningSlot) remains and must be removed in the next session.

## 1. Commit chain on `release-1.0.0-rc1`

```
8ee6da68a refactor(membership): delete audit-of-lifecycle-commands infra — E2 phase 2c-α.1a of v2 migration
a9d6229b0 refactor(consensus): RabiaEngine emits ConsensusActive/Passive; rename QuorumStateNotification→ClusterStateNotification — E2 phase 2c.0 of v2 migration
a7c806339 refactor(membership): extract DrainProcedure, delete SelfDrainCoordinator + friends — E2 phase 2b of v2 migration
c0c4e6444 refactor(membership): delete divergence-logger + flag + φ-accrual machinery — E2 phase 2a of v2 migration
2175ea564 feat(membership): NTT tracks SWIM member set, authoritative source for cluster size — E2 phase 1.6 of v2 migration
52dcfffcc refactor(membership): state-derived reconcile + drop periodic tick — E2 phase 1.5 of v2 migration
68f1844b7 feat(membership): v2 action paths — E2 phase 1 of v2 migration
9490c8804 feat(membership): AetherNode wiring — E1 stage 6b of v2 migration
9200d2c2d feat(membership): TOML resolver + FSM/NTT listener hooks — E1 stage 6a of v2 migration
7f133bd74 feat(membership): DivergenceLogger — E1 stage 5 of v2 migration  [LATER DELETED in 2a]
c5859deb8 feat(membership): LeaderReconciler — E1 stage 4 of v2 migration
becdd0e5b feat(membership): LocalQuorumWatcher — E1 stage 3 of v2 migration
c50b60ca1 feat(membership): NodeTopologyTracker — E1 stage 2 of v2 migration
33edd8ea0 feat(membership): MembershipConfig + NttObservationFlag — E1 stage 1 of v2 migration  [flag DELETED in 2a]
28594279b docs(spec): amend membership v2 for RC1 scope + locked implementation decisions
810f4c5d4 test(forge): remove archived membership spike tests (preserved in history)
b7c81a1ae test(forge): archive membership multi-kill + quorum-mask spike tests for history
```

Tag `v1.0.0-rc1-candidate` still at `810f4c5d4` — move forward at session start.

## 2. Locked design decisions (from this session)

| ID | Decision | Status |
|---|---|---|
| Q-E2.1 | Single 3-step flag `OFF / OBSERVATION / PRIMARY` | **Deleted in 2a** — user said no persisted-state rollback; direct cutover |
| Q-E2.2 | REPLACEMENT (NTT sole trigger, FSM disconnected) | Locked |
| Q-E2.3 | "node count" naming in new code only | Locked |
| Q-E2.4 | DELETE SelfDrainCoordinator entirely → DrainProcedure | **Done in 2b** |
| Q-E2.5 | DELETE CTM slot machinery entirely | Pending Phase 2c |
| User-A2 | **NO topology persisted in KV — delete NodeLifecycleKey entirely** | Pending Phase 2c |
| D1 | `MemberStatus` → `{MEMBER, ABSENT}` | Pending 2c-α.2 |
| D2 | `ClusterPhase` stability via NTT `quorateSinceNanos` | Pending 2c-α.2 |
| D3 | `MembershipView` thin wrapper over NTT | Pending 2c-α.2 |
| D4 | CDM event-driven via `ConsensusActive` + KV-write observation | Pending 2c-α.3 |
| User refinement | Add `ConsensusPassive` from RabiaEngine; rename `QuorumStateNotification` → `ClusterStateNotification` (was `QuorumEstablished/QuorumLost` → `ClusterActive/ClusterPassive`) | **Done in 2c.0** |
| User refinement | NTT does NOT store events (per-peer map dropped); atomic boolean + per-peer timers; reconcile state-derived | **Done in 1.5** |
| User refinement | Drop periodic reconciliation tick — event-driven only; leader-activation reconcile delayed by `nttDepartureTimeout × 1.5` | **Done in 1.5** |
| User refinement | NTT tracks SWIM member set as authoritative source for `clusterMembershipCount` | **Done in 1.6** |
| User refinement | LocalQuorumWatcher should ALSO subscribe to ClusterPassive (more accurate than QUIC count) — deferred to 2c-α.3 | Pending |

## 3. v2 component map (what's working)

All in `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/ntt/`:

- **NodeTopologyTracker** — subscribes to SWIM `DepartedObserved` + `HealthyObserved`; per-peer `Map<NodeId, ScheduledFuture<?>>` for departure timers; `Set<NodeId> currentMembers` (authoritative). `Runnable onReconcileNeeded` injected; fires on timer expiry. QUIC reconnect cancels timer + HealthyObserved re-arms member set.
- **LocalQuorumWatcher** — observes QUIC connect/disconnect + `coreCount` changes; emits `QuorumLossIntent` when below `N/2+1` for `quorumLossDrainThreshold`.
- **LeaderReconciler** — leader-pinned. Subscribes to NTT (via `Runnable` trigger), LocalQuorumWatcher, SWIM HealthyObserved (`onSwimMemberHealthy`), `onConfigChange`. CAS-debounce pattern (≥1 in-flight, re-schedule if events arrive during reconcile). `activate()` schedules ONE delayed reconcile at `nttDepartureTimeout × 1.5`. `reconcile()` queries `ntt.currentMembers()` + `configuredCoreCountSupplier` + CTM v2 methods.
- **DrainProcedure** — §8 unified drain (CAS guard + `InFlightRequestTracker` + halt(2)). LEAVE emitter is no-op for now (Phase 6/E6).
- **CTM v2 methods** — `provisionReplacement(Option<NodeId>, Set<NodeId>)` / `drainNode(NodeId, DrainReason)` / `reconcile()` — currently delegate to slot machinery (Phase 2c deletes that and rewires direct to `NodeLifecycleManager`).

Tests: 44 NTT-tier tests pass (NodeTopologyTracker 12, LocalQuorumWatcher 10, LeaderReconciler 17, DrainProcedure 5). Broader aether-deployment: 609 pass. aether-node: 499 pass. aether-metrics: 204. aether-invoke: 183. aether-control: 89.

## 3.5 — Architecture rework deep-dive (load-bearing context for next session)

Two intertwined reworks landed this session that are not described in the original v2 spec foundation. They are spec-amended and code-implemented; next session must understand both to continue correctly.

### 3.5.1 — NTT evolution (E1 → Phase 1.5 → Phase 1.6)

The NTT component was rewritten THREE times this session as design clarified. The current shape is materially simpler than what E1 originally landed. **The original event-buffering / claim-and-process model has been dropped.**

#### Original E1 NTT (commit `c50b60ca1`, since rewritten)
- Per-peer `Map<NodeId, NttPendingEntry>` where `NttPendingEntry` carried a `ScheduledFuture<?>` AND an `Option<TopologyUnhealthyEvent>` (fired event).
- API: `claim(NodeId)`, `drainAllFiredEvents()`, `firedEventCount()`, `setOnTimerFireListener(Consumer<TopologyUnhealthyEvent>)`.
- Rationale at the time: leader could claim per-peer events; leader-vacuum windows were handled by the map persisting events for the next leader to drain on activation.

#### Phase 1.5 simplification (commit `52dcfffcc`) — drop event records, drop periodic tick

User observation: "since we do full reconciliation (not just reacting on events), we don't actually need to store events in NTT, plain atomic boolean (there were events) is enough."

Changes:
- **Deleted `TopologyUnhealthyEvent` record** — no per-peer event data to carry.
- **NTT state shrank** to `Map<NodeId, ScheduledFuture<?>>` (per-peer departure timers only).
- **NTT output** changed from `Consumer<TopologyUnhealthyEvent>` listener to a simple injected `Runnable onReconcileNeeded` callback, fired on each timer expiry.
- **Dropped `claim` / `drainAllFiredEvents` / `firedEventCount`** APIs entirely.
- **LeaderReconciler dropped periodic tick** (`tickPeriod()` deleted; no `provisioning_timeout × 1.5` periodic ticking).
- **LeaderReconciler.activate()** now schedules ONE delayed reconcile at `nttDepartureTimeout × 1.5` (was: immediate first tick + drain NTT map). User reasoning: "leader churn is invasive and triggers some traffic anyway — things should settle before we start reacting."
- **CAS-debounce pattern** added: first event sets `reconcileInFlight` + schedules reconcile; subsequent events set `rescheduleRequested`; on completion, if rescheduleRequested, recurse. At most one reconcile in flight; no events lost.
- **Added event ingresses** to LeaderReconciler: `onSwimMemberHealthy(NodeId)` (catches the "surplus appeared" case symmetrically), `onConfigChange()` (KV-driven configured-size change).
- **`ReconcileTrigger` enum** updated: dropped `PERIODIC_TICK`; renamed `NTT_DRAIN`→`NTT_FIRE`; added `MEMBER_APPEARED`, `CONFIG_CHANGE`.
- **`ReconcileIntent`** simplified: dropped `Set<NodeId> peersToProvision/peersToDrain`; replaced with `int provisionCount / drainCount`. Reconcile owns peer selection internally.

#### Phase 1.6 (commit `2175ea564`) — NTT becomes authoritative source for `clusterMembershipCount`

User observation: "NTT can maintain set of nodes. it can serve as a seed source for provisioning. what we see now through SWIM is more fresh/recent than QUIC/Rabia."

Changes:
- **NTT constructor gains `NodeId self`** parameter — included unconditionally in member set.
- **New internal state:** `Set<NodeId> currentMembers = ConcurrentHashMap.newKeySet()`. Seeded with `self`.
- **NTT subscribes to BOTH `DepartedObserved` AND `HealthyObserved`:**
  - `HealthyObserved(peer)` → `currentMembers.add(peer)`; cancels any pending NTT timer + removes from `pendingTimers` map (analog of QUIC reconnect — peer reappeared via SWIM).
  - `DepartedObserved(peer)` → `currentMembers.remove(peer)`; schedules NTT timer.
- **New accessors:** `Set<NodeId> currentMembers()` (unmodifiable snapshot via `Set.copyOf`), `int currentMemberCount()`.
- **LeaderReconciler dropped** its `IntSupplier clusterMembershipCountSupplier` + `Supplier<Set<NodeId>> currentClusterMembersSupplier` constructor params — now reads `ntt.currentMemberCount()` and `ntt.currentMembers()` directly via the already-injected NTT collaborator.
- **AetherNode wiring** dropped the QUIC-based `currentClusterMembersSupplier` lambda; NTT is now the single source for SWIM-converged member set.
- This honors spec §4 literally: `clusterMembershipCount` IS "SWIM's converged member set" via NTT, not approximated via QUIC.

#### Current NTT contract (after Phase 1.6)
```
Constructor: nodeTopologyTracker(MembershipConfig, NodeId self, Runnable onReconcileNeeded)
                                  + test overloads taking NttTimerScheduler

State (internal):
  - Map<NodeId, ScheduledFuture<?>> pendingTimers
  - Set<NodeId> currentMembers (ConcurrentHashMap.newKeySet, seeded with self)

Inputs:
  - onSwimObservation(SwimObservation)
      DepartedObserved(peer) → remove from currentMembers; arm timer (computeIfAbsent — idempotent)
      HealthyObserved(peer)  → add to currentMembers; cancel pending timer + drop from pendingTimers
  - onQuicReconnect(NodeId) → cancel timer + drop from pendingTimers
  
Output:
  - Timer expiry → remove from pendingTimers; onReconcileNeeded.run()
  
Accessors:
  - Set<NodeId> currentMembers()      (unmodifiable snapshot)
  - int currentMemberCount()
  - int pendingTimerCount()           (observability)
```

#### Open work for next session: NTT.quorateSinceNanos
Pending 2c-α.2 — NTT will gain `AtomicLong quorateSinceNanos`:
- Updated on `currentMembers.size()` transition across the quorum threshold (`N/2+1`).
- Set to `TimeSource.nanoTime()` when crossing into quorate; reset to `Long.MIN_VALUE` when crossing out.
- Accessor: `Option<Long> quorateSinceNanos()` (`Long.MIN_VALUE` → `Option.none()`).
- **Purpose:** replaces the deleted `NodeLifecycleValue.updatedAt()`-based `oldestOnDutyAt` formula in `ClusterPhaseView.stableWindowSatisfied`. v2 stability window is measured from "when did we cross into quorum" — pure SWIM-derived.

### 3.5.2 — Message rework: RabiaEngine consensus events + rename + bridge

User observation (collapsed): "currently we have ClusterEvent.QuorumEstablished/QuorumLost. We need to find the source that emits these events and remove emission. Instead add two tiny handlers that receive Rabia emitted ConsensusActive/ConsensusPassive and re-emit as QuorumEstablished/QuorumLost. Then we can rename QuorumEstablished/QuorumLost into ClusterActive/ClusterPassive." Plus: "Add ConsensusPassive that is also emitted by RabiaEngine when it deactivates due to quorum loss."

#### The problem this fixes (edge cases)

Pre-2c.0, `QuorumStateNotification.ESTABLISHED/DISAPPEARED` was emitted when LOCAL connected-peer count crossed `N/2+1` threshold. That's necessary but **not sufficient** for "cluster is operational." Three real cases where subscribers acted stale:
1. **Boot:** quorum-many QUIC peers connected but Rabia is still `Syncing`. ClusterSync / ScheduledTasks / AppHttpServer / metrics subscribers acted as if cluster were up; KV writes failed silently.
2. **Recovery after partition:** local QUIC count restored but Rabia needs re-sync. Same issue.
3. **Brief leader churn / Rabia `Paused`:** QUIC count unchanged but Rabia non-operational. Subscribers acted stale.

Under the new design, the event fires **only when consensus is genuinely Active** (Rabia phase = `Active`).

#### What changed in commit `a9d6229b0` (Phase 2c.0)

**1. RabiaEngine emits new consensus events** (in `integrations/consensus/`):
- `ConsensusEvent` sealed interface (new file at `integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/ConsensusEvent.java`)
- `ConsensusActive(NodeId self)` — emitted on transition INTO Active phase
- `ConsensusPassive(NodeId self)` — emitted on transition AWAY from Active (Paused, Stopped, Syncing)
- Emission is idempotent per transition (CAS-guarded internally; not every tick within a phase)
- RabiaEngine constructor gains a consensus-event listener (`Consumer<ConsensusEvent>`); the dispatch wiring is via the existing event-listener mechanism RabiaEngine already used

**2. Bridge handler** (new file at `integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/ConsensusBridge.java`):
- Subscribes to `ConsensusActive` / `ConsensusPassive`
- Translates to `ClusterStateNotification.active()` / `ClusterStateNotification.passive()` and routes to the cluster-wide MessageRouter
- **This is the ONLY emitter of `ClusterStateNotification` in v2.** All other emission paths were deleted.

**3. Renamed `QuorumStateNotification` → `ClusterStateNotification`** (via `git mv`):
- Class: `QuorumStateNotification` → `ClusterStateNotification` at `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/ClusterStateNotification.java`
- Enum values: `ESTABLISHED` → `ACTIVE`; `DISAPPEARED` → `PASSIVE`
- Factory methods: `established()` → `active()`; `disappeared()` → `passive()`
- Existing `advanceSequence(AtomicLong)` semantics preserved (the record is `Message.Local`-shaped with `sequence: long` for monotonicity)
- Inner `enum State { ACTIVE, PASSIVE }` — minimal cardinality
- **Javadoc preserves the historical context note** referring to the old `QuorumStateNotification` name and its bug — that's intentional and stays.

**4. `ClusterFsmEvent` subclass rename:**
- `ClusterFsmEvent.QuorumEstablished` → `ClusterFsmEvent.ClusterActive`
- `ClusterFsmEvent.QuorumDisappeared` → `ClusterFsmEvent.ClusterPassive`
- Switch arms across all consumers updated.

**5. Old emission source deleted:**
- The QUIC-count-based emitter (locally observed peer-count threshold crossing) was removed.
- `RabiaEngine` now has accessor `clusterState()` (was `quorumState()`).
- `LeaderManager.watchClusterState` (was `watchQuorumState`) — interface contract rename.

**6. All 41 subscribers updated** across `aether-metrics`, `aether-invoke`, `aether-control`, `aether-deployment`, `aether/node`, `aether/slice`. Specifically:
- `ClusterSyncScheduler`, `DeploymentMetricsScheduler` (metrics gossip)
- `ScheduledTaskManager` (task scheduling)
- `AppHttpServer` (HTTP traffic acceptance)
- `NodeDeploymentManager`, `NodeDeploymentState` (deployment)
- `ControlLoop`, `ControlLoopState` (control plane)
- `RollbackManager` (via test references)
- `AetherValue.java` (KV value type containing the slice serialization)
- `ClusterEventAggregator` (audit stream)
- Various test doubles (`TestLeaderManager`)

#### Subscribers' new behavior

| Subscriber | Old behavior | New behavior |
|---|---|---|
| `ClusterSyncScheduler` | gossiped metrics when local QUIC count crossed quorum | gossips only when Rabia is `Active` (genuinely operational) |
| `ScheduledTaskManager` | scheduled tasks on QUIC-count event | schedules tasks on `Active` (KV writes can actually succeed) |
| `AppHttpServer` | started accepting HTTP on QUIC-count event | accepts HTTP only when consensus active |
| Metrics schedulers | started ticks on QUIC-count event | tick only when Active |

In every case the new behavior is strictly more accurate. Subscribers may fire slightly LATER (waiting for Rabia confirmation in addition to local quorum) but no longer fire while consensus is non-operational.

#### Open work for next session: LocalQuorumWatcher refinement
**Pending 2c-α.3** per user refinement: "LocalQuorumWatcher should also subscribe to ClusterPassive — more accurate than QUIC count for self-drain trigger."

Currently LocalQuorumWatcher uses its own per-event tracking (`onPeerConnected/onPeerDisconnected/onConfiguredCoreCountChanged`) for the self-drain trigger. The refinement: ALSO subscribe to `ClusterStateNotification` and treat `PASSIVE` as a self-drain trigger condition. This makes the safety mechanism Rabia-confirmed rather than QUIC-count-derived. Subtle behavior change (slightly later trigger; no false positives during transient QUIC blips).

Implementation note: keep the QUIC-count path as primary (faster detection); add `ClusterPassive` as secondary trigger. Or fully replace QUIC-count path with Rabia path. Decide during 2c-α.3 — likely the latter for consistency with rest of the design.

### 3.5.3 — Why these reworks matter together

NTT's simplification + message rework are independent improvements but compose well:

- **NTT-as-truth-source** (Phase 1.6) + **Rabia-as-cluster-active-truth-source** (2c.0) means every level of the v2 stack has ONE authoritative source per concern:
  - "Who's in the cluster?" → NTT (SWIM-derived `currentMembers`)
  - "Can the cluster make consensus decisions?" → RabiaEngine (`ConsensusActive`)
  - "Is local quorum healthy?" → LocalQuorumWatcher (QUIC-derived `localQuorumCount`)
  - All three are SEPARATELY testable, separately observable, and don't drift because each has one writer.

- **CAS-debounce + state-derived reconcile** (Phase 1.5) means the LeaderReconciler is robust to bursty events. The whole point of E1 was to eliminate the parallel-state bug class; the simplification doubles down on this — no event buffering, no per-peer event records, no race-prone leader-claim semantics.

- **The semantics of "ready"** (CDM rewire planned in 2c-α.3) becomes cleanly event-driven:
  - Self-readiness: subscribe to local `ConsensusActive` (RabiaEngine emission)
  - Peer-readiness: observe peer's KV writes (already a primitive CDM has)
  - No more `LifecycleCommand.ForceOnDuty` hack-bridge from CDM into the membership layer.

These together mean **every component listens to the correct signal**. Pre-v2, components listened to proxies (QUIC count for "cluster ready", `NodeLifecycleValue.ON_DUTY` for "node ready") which were necessary-but-not-sufficient and produced edge-case bugs. Post-v2, each consumer subscribes to the authoritative source for what it actually needs.

## 4. Remaining E2 work — sub-stage breakdown

### 2c-α.1a-2: Delete `LifecycleCommand` class hierarchy
**Scope (per jbct-coder's investigation): ~25 files modified, ~10 deleted.**
- `LifecycleCommand.java` + impl classes (ForceOnDuty, ForceDecommission, ForceDrain, ForceActivate, RequestReJoin, etc.)
- `MembershipFsmInput` sealed permits — collapse to `MembershipFsmEvent` only (or delete the sealed interface)
- `MembershipFsm.applyLifecycleCommand` ingress (~80 lines, public API)
- `ClusterMembershipReducer` `case LifecycleCommand` branch + 5 dedicated handlers (`applyForceDecommission`, `applyForceOnDuty`, `applyRecordJoining`, `applyRequestReJoin`, `applyForceDrain`) — ~120 lines
- `LifecycleWriter.applyCommand(LifecycleCommand, ...)` API + impls (DirectLifecycleWriter, FsmRoutedLifecycleWriter)
- `LifecycleReconciler` rule outputs — all 7 rules emit `LifecycleCommand` (OnDutyFaulty, JoiningTimeout, DrainTimeout, GenerationLifecycleGap, StoppedZombie, SwimLifecycleGap, JoiningStuckAlert) → rewire output type or delete rules
- `LifecycleCommandRequest` / `LifecycleCommandResponse` records
- `NodeLifecycleRoutes` HTTP handlers that emit LifecycleCommand → 410 Gone with TODO marker for 2c-β
- CLI `AetherCli` `LifecycleCommand` subcommand at `cli/AetherCli.java:599` (PicoCLI; distinct from FSM LifecycleCommand, may not collide)
- ~14 test files: reducer command-branch tests, FSM ingress tests, writer command tests, rule tests

**Risk: HIGH truncation.** Recommend splitting further:
- 2c-α.1a-2-i: Reducer command-branch deletion + FSM ingress
- 2c-α.1a-2-ii: LifecycleWriter API + impls + reconciler rules

### 2c-α.1b: Delete `ProvisioningSlotKey/Value` + CTM slot machinery
**Scope: ~10-15 files.**
- `ProvisioningSlotKey` + `ProvisioningSlotValue` in `AetherKey`/`AetherValue`
- `KVStoreSerializer` cases for the above
- `ClusterTopologyManagerRecord` slot methods: `classifyOccupied`, `classifyOccupancy`, `freeStaleFillingSlots`, `freeDeadSlots`, `freeSlot`, `SlotOccupancy` enum, `slotReader` field
- `/api/cluster/slots` endpoint — return 410 (2c-β does full triad cleanup)
- ~17 CTM tests that exercise slot classification — bulk delete
- `ClusterTopologyRoutes` slot path

### 2c-α.1c: Delete `NodeLifecycleKey/Value` + serializer cases + stub consumers
**Scope: ~15-20 files. Includes heavy AetherNode involvement.**
- `NodeLifecycleKey` + `NodeLifecycleValue` + 5 lifecycle enum states
- `KVStoreSerializer` cases
- Stub callers with TODO for 2c-α.2 rebuild:
  - `MembershipFsm` (FSM module persists in this stage, just doesn't write lifecycle)
  - `ClusterTopologyManagerRecord` (drop `NodeLifecycleValue` reads in classifyOccupied — but that method gone in 2c-α.1b)
  - `NodeLifecycleRoutes` → 410
  - `ClusterEventAggregator.onNodeLifecyclePut` → no-op stub
  - `ClusterGenerationProjector` → no-op stub
  - `NodeReadinessTracker` → no-op stub
  - `ClusterSyncPongSignalFan` → drop ForceOnDuty emission
  - `ClusterSyncScheduler` → drop lifecycle read
  - `SwimHealthContext` (aether/node/.../health/fsm/) → drop NodeLifecycleValue import
  - `ClusterDeploymentState` (CDM) → no-op stub for now; 2c-α.3 does event-driven rewire
  - `LifecycleReconcilerRecord` rule readers — handled in 2c-α.1a-2

### 2c-α.2: Rebuild derivation primitives over NTT
**Scope: rewrite ~3 large interfaces + add NTT quorate tracking.**
- `MembershipView` interface: shrink `MemberView` record to `(NodeId, ConnectionState)` — no `Option<NodeLifecycleValue>`. Replace 5-value `MemberStatus` enum with `{MEMBER, ABSENT}` per D1.
- `ClusterPhaseView`: replace `stableWindowSatisfied` derivation; new source = NTT `quorateSinceNanos`.
- `NodeTopologyTracker`: add `AtomicLong quorateSinceNanos` field; updated on `currentMembers` transition across `quorum`. Accessor: `Option<Long> quorateSinceNanos()` (Long.MIN_VALUE → None).
- Rewire production consumers of `MembershipView`: 9 call sites (routes, dashboard, CTM, etc.) — drop `Option<NodeLifecycleValue>` consumption.
- Tests for the 3 interfaces above.

### 2c-α.3: Orchestration + CDM event-driven rewire + dead-test deletion
**Scope: BIG — heavy AetherNode touch + CDM rewire + ~30 test files deleted.**
- `AetherNode.java`: drop construction of `MembershipFsm` + `MembershipFsmConfig` + `LifecycleReconciler` + `LifecycleWriter` + `FsmRoutedLifecycleWriter` + `DirectLifecycleWriter` + `JoinDeadlineExpired` timer + listener registrations + the `aetherNode` record's `lifecycleReconciler` field + collectRouteEntries adjustments
- Delete the FSM module entirely (`MembershipFsm.java` + `ClusterMembershipReducer.java` + `MembershipFsmState.java` + 6 lifecycle-state classes)
- Delete `LifecycleReconciler` + 7 rule classes + their config (`ReconcilerConfig`, `ReconcilerRulesConfig`, `RuleSpec`)
- Delete `LifecycleWriter` + impls
- Delete `JoinDeadlineExpired` event
- Delete `DecommissionedAtomGc` + test
- Delete `DrainCoordinator` interface + `NoOpDrainCoordinator` (FSM was sole consumer)
- **CDM rewire (load-bearing)**: replace `LifecycleCommand.ForceOnDuty` emission in `ClusterDeploymentState` with: subscribe to local `ConsensusActive` (from RabiaEngine via bridge) for self-readiness; observe peer KV writes for peer-readiness. CDM remains as deployment FSM.
- Wire `LocalQuorumWatcher` to ALSO subscribe to `ClusterPassive` (more accurate than QUIC count for self-drain — pending refinement).
- Wire `LeaderReconciler.onConfigChange()` to actual KV subscription on `ClusterConfigValue.coreCount`.
- Bulk delete ~30+ test files dependent on deleted machinery.
- Stub-clean `NodeLifecycleRoutes` (or leave 410 stubs for 2c-β).

**Risk: HIGHEST truncation potential. Plan: do CDM rewire SEPARATELY from AetherNode deletion. 2c-α.3-i = CDM event-driven + LocalQuorumWatcher ClusterPassive subscription. 2c-α.3-ii = AetherNode + FSM module deletion + dead-test deletion.**

### 2c-β: REST + CLI + docs triad cleanup
**Scope: REST routes + CLI subcommands + management-api.md + cli.md.**
- Delete `NodeLifecycleRoutes` entirely (or strip its 410 stubs)
- Delete `/api/cluster/slots`, `/api/cluster/reconciler/status`, `/api/node/lifecycle/...`
- Rebuild `/api/cluster/membership` reading from NTT (or delete and document NTT-based replacement)
- CLI subcommands consuming defunct endpoints → delete
- Docs: `aether/docs/reference/management-api.md`, `aether/docs/reference/cli.md` — remove deleted endpoint docs
- ManagementRoute enum entries — remove

### Phase 3: chaos tests + suite run
**Per Phase 3 task #21:**
- Rewrite `test-joining-window-kill.sh` (S01) — Class B significant
- Rewrite `test-self-drain-quorum-loss.sh` (S19/S20) — Class B moderate
- Class A renames in 4 kill-tests
- Run full chaos suite: `cd aether/tests/integration && ./run-tests.sh --env remote`
- First end-to-end v2 validation
- Tune `nttDepartureTimeout` / `quorumLossDrainThreshold` per chaos observations

## 5. Truncation pattern — observed + mitigation

**Observed:** every sub-stage with `AetherNode.java` surgery hit jbct-coder truncation at ~50-100 tool calls. AetherNode.java is ~3300 lines; touching it consumes massive Read context per turn.

**Mitigation for next session:**
1. **For AetherNode.java edits: do them directly with focused Read+Edit, not delegations.** Reading specific line ranges + targeted Edit calls works reliably.
2. **For non-AetherNode work: delegate to jbct-coder with TIGHT scope** (one component per delegation, explicit file:line targets, ≤15 files per delegation).
3. **If delegation truncates: verify build + recover by inspecting filesystem state.** The previous agent's partial work is often preserved on disk; build-runner verification reveals what's left.
4. **Per-stage commits even if next stage's prep work bleeds in** — atomic-per-concern beats atomic-per-mega-commit when the mega-commit's agent truncates anyway.

## 6. Critical files / known traps

| File | Trap |
|---|---|
| `AetherNode.java` | ~3300 lines. Truncation magnet. **Do directly via Read+Edit.** |
| `ClusterTopologyManagerRecord.java` | ~1700 lines mostly slot machinery — most goes in 2c-α.1b. |
| `MembershipFsm.java` | Body still present at HEAD. Deletion in 2c-α.3. |
| `MembershipFsmInput` sealed permits | Currently permits `MembershipFsmEvent, LifecycleCommand`. After 2c-α.1a-2, either collapse to single permit or delete sealed interface. |
| `ConsensusBridge.java` (new in 2c.0) | Translates RabiaEngine `ConsensusActive/Passive` to `ClusterStateNotification`. CDM should listen to `ConsensusActive` for self-readiness (D4). |
| `KVStoreSerializer` cases | Removing arms is wire-format change. RC1 has no live customers; hard cutover. |
| `LegacyLifecycleWriterFixture` (test fixture) | Used by ~17 CTM tests. Delete during 2c-α.1b alongside slot-machinery tests. |

## 7. Outstanding sub-stage dependencies

```
Phase 2c.0 ✓ (done)
   ↓
2c-α.1a   ✓ (done)
   ↓
2c-α.1a-2 (LifecycleCommand class) — NEXT
   ↓
2c-α.1b (ProvisioningSlot + CTM slot machinery)
   ↓
2c-α.1c (NodeLifecycleKey + serializer + stub consumers)
   ↓
2c-α.2 (MembershipView/ClusterPhaseView rebuild + NTT quorateSinceNanos)
   ↓
2c-α.3 (orchestration + CDM event-driven + AetherNode + dead-test deletion)
   ↓
2c-β (REST + CLI + docs triad)
   ↓
Phase 3 (chaos test rewrites + suite run)
```

## 8. References

- **Spec:** `aether/docs/specs/membership-architecture-v2-spec.md` (HEAD has all amendments through 2c.0)
- **Prior handovers:**
  - `aether/docs/internal/progress/session-handover-2026-05-28.md` (rc1-track layered-stack diagnosis)
  - `aether/docs/internal/progress/session-handover-2026-05-28-experimental.md` (v2 spec finalization)
- **Memory pointers:**
  - `[[project_membership_v2_redesign]]` (the redesign principles)
  - `[[project_cluster_b_wedge_layered_stack]]` (rc1 layered-stack motivating v2)
  - `[[feedback_structural_over_tactical]]` (the principle governing the cutover)

## 9. First moves for next session

1. **Move candidate tag:** `git tag -f v1.0.0-rc1-candidate 8ee6da68a` (no push).
2. **Verify resume state:** `git log -5 --oneline release-1.0.0-rc1` should show `8ee6da68a` at HEAD. `git status --short` should be empty.
3. **Read this handover + the v2 spec §10 deletion list.**
4. **Pick up at 2c-α.1a-2** — delete `LifecycleCommand` class hierarchy. Recommend splitting into 2 sub-deliveries (reducer/FSM-ingress, then writer-API/reconciler-rules) to fit context.
5. **AetherNode.java edits: do directly, not delegated.**

The cutover momentum is real; v2 components are validated; the remaining work is mechanical-but-substantial. Next session can pick up cleanly.
