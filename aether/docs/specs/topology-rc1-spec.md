<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
See LICENSE in the repository root for full terms.
-->
---
title: Topology / Membership RC1 Consolidation Spec
date: 2026-05-13
author: spec-writer agent, reviewed pending
status: draft
branch: release-1.0.0-rc1
related:
  - aether/docs/specs/membership-architecture-spec.md (parent, v2 typed-stream split; this spec lives at Layers 2-3 and extends them with HLC + cluster-event log)
  - aether/docs/specs/cluster-membership-fsm-spec.md (per-peer FSM; this spec adds incarnation gating + HLC stamping to its event surface)
  - aether/docs/internal/progress/session-handover-2026-05-13.md (H-series context; defines the starting state for RC1 increment)
---

# Topology / Membership RC1 Consolidation Spec

## 1. Status & scope

**Release target:** `1.0.0-rc1`.
**Branch:** `release-1.0.0-rc1` · HEAD at spec authoring: `84726a848`.
**Extends:** `membership-architecture-spec.md` v2 (parent) — Layers 2 (HealthReconciler authoritative membership) and 3 (TopologyObserver projection) gain new invariants and event-channel rules. Layers 0/1/4/5/6/7 unchanged. The current FSM spec (`cluster-membership-fsm-spec.md`) is unmodified at the cell level; this spec adds two cross-cutting concerns to its event surface (HLC timestamps replace `nowMs`; reducer consults `SwimObservation.incarnation`).

**In scope (RC1):**

- Six convergent design moves identified by the cross-design synthesis (§3, §7).
- Two currently-failing integration suites closed: `02-chaos` (OB2 — NODE_FAILED skew) and `11-observability` (OB1 — per-node alert/trace race).
- Rolling-upgrade wire-format gate (versioned `SwimObservation` + `NodeLifecycleValue`).
- Monotone incarnation gating in the reducer.
- HLC throughout the FSM event surface and `MembershipDecision` stamping.
- Quorum-aware `MembershipView` (minority side returns empty `onDutyPeers`).
- KV-put subscriber migration (5 subscribers) to `MembershipDecision`.
- Cluster-scoped replicated `ClusterEventLog`.

**Out of scope (postponed — see §6):**

- Reciprocal-witness gate (Agent B's M5; >10 s witness window, not chaos-validated).
- Stateless fold rewrite (Agent A's M5-M10; large blast radius, RC2+).
- Lease channel (Agent C's `NodeLeaseKey`; overlaps the convergent event log).
- Tangent T2 — fold model as cluster-decision substrate beyond membership.
- Cosmetic cleanup of `Decommissioned.swimDriven` dormant field.
- Lifeguard suspicion-timer extension (SWIM module change; not on chaos hot path).

**Backwards compatibility:** RC1 is the version floor. Pre-RC1 persisted KV state is not migrated; clean rolling upgrade *within* RC1 (v1 → vN of the wire format) IS supported via the version byte (§3.3).

---

## 2. Architecture summary

The convergent insight from three independent design analyses (Appendix B):

> **Every drift bug surviving the H-series traces to per-node materialised state where cluster-scoped state was needed.** Two failures embody it: the chaos suite polls `/api/events` on node X while the relevant `NODE_FAILED` observation was buffered into `ClusterEventAggregator` on node Y; the observability suite POSTs an alert to node A and the dashboard GETs it from node B. Both are "the answer exists, but on the wrong node". The fix is not "make every node smarter about routing"; it is "stop materialising cluster-scoped facts in per-node maps".

**Current state after H-series (recap):**

- `MembershipView` is the canonical query: SWIM ∪ KV-overrides; SWIM-FAULTY peers are no longer reported `ON_DUTY` even if KV says so. Cure: chaos-revival storm structurally eliminated by making the `(DECOMMISSIONED, SwimHealthy) → ON_DUTY` cell a permanent `nop`. The reducer remains a total function; the FSM cells are unchanged at the lattice level.
- `MembershipDecision` exists as the canonical event stream out of `TopologyObserver` (parent spec v2), but 5 of its potential subscribers still consume KV-put notifications directly. The dual channel is the source of the conflation noted in handover §6.
- `ClusterEventAggregator` is per-node (`ClusterEventAggregator.java` line 41: `private final RingBuffer<ClusterEvent> buffer;`). Per-node alert and trace maps in `/api/alerts` and `/api/traces` are likewise per-node.
- FSM events carry `long nowMs` (wall-clock from `System.currentTimeMillis()`). NTP step-back is corruption-load-bearing for refractory/age comparisons.
- `SwimObservation.incarnation` field is present on the wire; the reducer does not consult it. Stale-arrival sequences invert ordering (SM2).
- `MembershipView` reports `ON_DUTY` on a non-quorate node based on local SWIM alone; minority-side claims survive partition.

**The RC1 increment** is six concrete moves that turn three of those weaknesses into structural impossibilities, and migrate the two cluster-scoped read paths off per-node materialisation. The increment is end-loaded toward correctness — none of the six destabilises the lattice or the H-series cure.

---

## 3. The six steps

Steps are presented in **dependency order** (ship order), not numbering order. Numbering follows the synthesis index. PR1-PR4 (Steps 4, 3, 6, 5) are independent and parallelisable; PR5 (Step 2) depends on 4+5; PR6 (Step 1) depends on 4+2.

| # | Step | Closes | LOC (prod/test) | Deps |
|---|------|--------|-----------------|------|
| 4 | HLC in FSM event timestamps | T1 (NTP step-back), SM2 (cross-node ordering) | 250/300 | none |
| 3 | Reducer consults `SwimObservation.incarnation` | N10, SM2, same-NodeId restart class | 60/120 | none |
| 6 | Versioned wire-format byte | MV1/MV2 (rolling-upgrade safety) | 200/250 | none |
| 5 | Quorum-aware `MembershipView` | minority-side `ON_DUTY` false claim | 80/180 | none (prereq for 2) |
| 2 | KV-put subscriber migration to `MembershipDecision` | H.5 dual-channel conflation | 700/600 | 4, 5 |
| 1 | Cluster-scoped replicated `ClusterEventLog` | OB1, OB2 (both currently failing tests) | 600/450 | 4, 2 |

Totals: ~1890 prod / ~1900 test LOC. Six PRs. ~5-7 engineering days.

---

### 3.1 Step 4 — HLC in FSM event timestamps  (FOUNDATION, ship first)

**Closes:** T1 (NTP step backward corrupts refractory/age comparisons), SM2 (cross-node event-ordering inversion).

**Mechanism.** Replace `long nowMs` in all 8 `MembershipFsmEvent` variants with `HlcTimestamp at`. Wire the existing `HlcClock` (`integrations/hlc/src/main/java/org/pragmatica/hlc/HlcClock.java`; factory `hlcClock(String nodeId) → Result<HlcClock>`) into the FSM construction graph. Every local event-creation site calls `hlcClock.now()`; every cross-node event passes through `hlcClock.update(remote.at)` so the local clock absorbs the remote's logical advance.

**Why HLC, not raw monotonic nanos.** Monotonic nanos solve T1 locally but cannot be compared across nodes. HLC packs `(wallMs, counter, nodeId)` into a single comparable token that orders correctly within a node (monotonic) AND across nodes (causally consistent with `update`). This is the cheaper of the two correctness fixes T1+SM2 require; doing only nanos leaves SM2 open.

**Why now, not RC2.** Step 2 (`MembershipDecision.stampedAt`) and Step 1 (`ClusterEventValue.at`) both consume HLC. Pulling HLC last would require touching the FSM event surface twice. RC1 has the budget for one pass.

**Files (prod).**

- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsmEvent.java` — 8 variants, replace `long nowMs` → `HlcTimestamp at`.
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsm.java` — constructor accepts `HlcClock`; every event construction site calls `hlcClock.now()`; cross-node event sites call `hlcClock.update(event.at)` BEFORE delegating to the reducer.
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/ClusterMembershipReducer.java` — propagate HLC through pure-function decisions; reducer does not call `now()`, the caller stamps.
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` — wire `HlcClock` per node into the FSM construction graph.
- ~10 event-construction sites (greppable: `MembershipFsmEvent\.[A-Z]`).

**Files (test).** All FSM tests need constructor updates (an `HlcClock` injection point). New: `MembershipFsmHlcMonotonicityTest` driving the FSM with adversarial physical-clock steps (forward jump, backward jump, NTP-step-back during refractory window).

**Hidden risks.**

- `HlcClock.update` returns `Result<HlcTimestamp>` and FAILS on remote-vs-local drift > 500 ms. Explicit policy required: WARN-and-drop the event. Do NOT silently absorb (would re-open T1 from the other direction). Do NOT throw (would crash on first asymmetric clock skew in production).
- `CounterOverflowException` if the FSM emits >65 535 events in a single microsecond. Steady-state cannot trigger this, but a worst-case mass-startup burst could. Wrap event creation with `Result.failure` propagation rather than letting the exception surface.
- HLC needs persisted state across node restarts (otherwise restart loses logical time). `HlcClock` already persists; verify the persisted path is on the same volume as KV (a separate restart-and-disk-loss test will catch this).

**Validation.** Module-level property test (1 000 random clock steps including backward jumps); integration `test_ntp_step_back.sh` (date-set on container, observe no spurious FSM transitions).

---

### 3.2 Step 3 — Reducer consults `SwimObservation.incarnation`  (independent)

**Closes:** N10 (stale SWIM observation arrival), SM2 (event-ordering inversion at reducer input), same-NodeId restart class.

**Mechanism.** Maintain `Map<NodeId, Long> latestObservedIncarnation` inside `MembershipFsm`. Gate every `SwimHealthy | SwimFaulty | SwimDeparted` event acceptance on `event.incarnation() >= map.get(peer)`. Update the map only on acceptance. Reject (drop with TRACE log) stale events.

**Restart-reset semantics.** Legitimate peer restart resets incarnation toward 0/1. Treat `event.incarnation() == 0 || event.incarnation() <= 1 && stored > threshold` as a restart-reset (NOT a stale event) and reset the map entry. Threshold defaults to 8 (anything below incarnation 8 against a stored value above N+10 is unambiguously a restart, not a stale arrival).

**Why this, not the existing `lastSeenForPeer` map at gate G3.** The H-series eliminated that gate by replacing it with KV-reconstructibility (per `cluster-membership-fsm-spec.md` I1). The incarnation map here is a *different* concern: not "have we already decided?" but "is this observation fresher than the last one I accepted?". It does not violate KV-reconstructibility because incarnation is a property of the *event stream*, not the FSM state; cold-start reads KV → reconstructs FSM state → incarnation map starts empty and reseeds on the first accepted event. A stale event during the cold window cannot do damage (the FSM has not yet started accepting work).

**Why not gate at the SWIM module instead.** SWIM's wire-level incarnation deduplication is per-observer; reducer-side gating catches the case where two observers each emit valid observations whose *contents* invert (peer X seen healthy by node A at incarnation N, then seen faulty by node B at incarnation N-1 due to a race in B's local cache). The gate at the reducer is the cluster-wide consolidation point.

**Files (prod).**

- `aether/aether-deployment/.../membership/fsm/MembershipFsm.java` — add the map; gate on the three SWIM event variants.
- `aether/aether-deployment/.../membership/fsm/MembershipFsmState.java` — if the snapshot/restore path needs to carry the map for leader takeover. Decide during implementation; preferred is "map is leader-local and rebuilds on takeover" (avoids a snapshot schema change).

**Files (test).** `MembershipFsmIncarnationTest` — replay out-of-order sequences; assert correct final state.

**Hidden risks.**

- Unbounded map growth if peers churn IDs. Prune on `NodeDecommissioned` decision emission (terminal cell observer-visible).
- Restart-reset misclassification. A long-running peer at incarnation 10 000 that suddenly emits incarnation 0 is unambiguous restart; but at incarnation 9 999 it is ambiguous. Verify SwimProtocol restart semantics before sealing thresholds.

**Validation.** Module test (replay sequences); integration extension to `13-edge-cases/test_chaos_flap.sh` (flap, kill, restart same NodeId; verify no stale-arrival re-flap).

---

### 3.3 Step 6 — Versioned wire-format byte  (independent)

**Closes:** MV1/MV2 (rolling-upgrade safety on the membership wire path).

**Mechanism.** Add a `byte version` field to `SwimObservation` variants and to `NodeLifecycleValue` (the two records that cross the wire AND get persisted to KV). Define `static final byte CURRENT_VERSION = 1` per type. Decode returns `Result.failure(WireFormatError.UnsupportedVersion)` for unknown versions.

**CRITICAL CHOICE — version byte goes LAST in the record, not FIRST.** This is counter to the "version-first purity" convention common in network protocols. Rationale:

- `@Codec` auto-generation is positional. Inserting a field at position 0 changes the byte offset of every subsequent field. Pre-RC1 persisted KV state cannot be migrated (RC1 is the version floor), but in-flight rolling-upgrade compatibility within RC1 (v1 → vN) is preserved IFF positions are append-only.
- Trade-off accepted: marginally harder protocol introspection (must read all fields to find the version) in exchange for safe rolling upgrades from v1 forward. Network monitoring tools that need version at byte 0 are not a project constraint.

**Per-channel decode policy.**

- `SwimObservation v→v+1`: WARN + drop. SWIM is a fast-path probe stream; one observation lost is invisible at the next probe round. Forward-compatibility preferred.
- `NodeLifecycleValue v→v+1`: fail-closed. Membership truth; silent drop would create undetected divergence. Decode failure propagates as `Result.failure`; the calling KV-listener stops consuming that key family until operator clears.

**Files (prod).**

- `integrations/swim/src/main/java/org/pragmatica/swim/SwimObservation.java` — add `byte version` last; default `CURRENT_VERSION`.
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` — `NodeLifecycleValue` (record at line 640); add `byte version` last; all 7 factory overloads default `version = 1`.
- NEW `integrations/serialization/.../WireFormatError.java` (sealed; variants `UnsupportedVersion`, `TruncatedFrame`).
- Codec regeneration; verify generated decoders return `Result.failure` rather than throw on truncation.

**Files (test).** `SwimObservationVersionTest`, `NodeLifecycleValueVersionTest`, integration `test_rolling_upgrade_wire_compat.sh` (5-min soak: mixed v1/v2 cluster, no crashes, no decode-failure log entries).

**Hidden risks.**

- Generated codec behaviour on truncated frames. Verify Result propagation BEFORE sealing the design. If the generator emits `throw`, fix the generator or shim a try-catch at the codec boundary (preferred: fix the generator).
- Version vs incarnation confusion. Document the distinction: incarnation = SWIM-protocol generation (monotonic per process restart); version = wire-format schema (monotonic per code release).

**Validation.** Module round-trip per type; integration mixed v1/v2 cluster soak.

---

### 3.4 Step 5 — Quorum-aware `MembershipView`  (independent, prereq for Step 2)

**Closes:** minority-side `ON_DUTY` false claim during partition; prerequisite for safe `MembershipDecision` emission on a partitioned leader (Step 2).

**Mechanism.** Add `BooleanSupplier inQuorum` to the `MembershipView` factory. On a non-quorate node: `onDutyPeers()` returns `List.of()`; `snapshot()` forces every `ON_DUTY` status to `UNTRACKED`; `statusOf(peer)` returns `UNTRACKED`. The local SWIM view is not consulted for `ON_DUTY` reasoning while non-quorate.

**Quorum source.** `quorumEstablished: AtomicBoolean` inside `TopologyObserver` (declared at line ~181, mutated at lines 527/532, gated `publishMembershipDeltas()` call at line 537). This MUST be the same `AtomicBoolean` `ClusterPhaseView.compute()` reads — having two truths for quorum is exactly the bug pattern we are eliminating. Expose as `BooleanSupplier inQuorum()` accessor on `TopologyObserver`.

**Critical risk — bootstrap dead-lock.** During initial cluster boot `quorumEstablished` is false. If `TopologyObserver` consults a strict `MembershipView` internally to decide whether to emit deltas, the view returns empty, no deltas flow, quorum never establishes. Mitigation: two factory variants.

- `MembershipView.strict()` — DEFAULT for all external readers: routes, dashboard, CTM, `/api/cluster/onduty`. Returns empty when non-quorate.
- `MembershipView.bootstrapAware()` — INTERNAL to `TopologyObserver` and friends. Returns content even when non-quorate, so the observer can drive the cluster TOWARD quorum without circular dependency.

**Why two factories, not a flag.** A flag on the read site is easy to forget. Two factories with distinct names force the caller to declare intent; reviewers can grep `bootstrapAware()` to find every site that bypasses quorum gating.

**Files (prod).**

- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/view/MembershipView.java` — factory split, implementation gated on `inQuorum.getAsBoolean()`.
- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/TopologyObserver.java` — expose `inQuorum()` accessor; wire to its existing `quorumEstablished` field.
- 3-5 caller wiring sites (greppable: `MembershipView.membershipView(`). External callers move to `strict()`; the `TopologyObserver`-internal call site moves to `bootstrapAware()`.

**Files (test).** `MembershipViewQuorumTest` (stub `inQuorum=false`, verify empty `onDutyPeers`); integration extension of the minority-partition scenario in `07-cluster-mgmt` asserting `/api/cluster/onduty` returns empty on the minority side.

**Hidden risks.**

- Bootstrap dead-lock (mitigated by two-factory split above).
- Read-write skew during a quorum-loss transition. A reader that fetches `onDutyPeers` at quorum-loss moment T sees the pre-loss list; the next call at T+ε sees empty. Document as "bounded stale read"; do not attempt to atomically snapshot quorum + view (the snapshot would itself be subject to the same race).

**Validation.** Module test with stubbed `inQuorum`; integration minority-partition assertion.

---

### 3.5 Step 2 — KV-put subscriber migration to `MembershipDecision`  (depends on 4 + 5)

**Closes:** H.5 dual-channel conflation (handover §6 item 4) — completes the H-series direction.

**Mechanism.** Extend `MembershipDecision` sealed interface with four new variants — `NodeJoining`, `NodeDraining`, `NodeFailedDrain`, `NodeShuttingDown` — alongside the existing `NodeJoined`, `NodeRemoved`, `NodeDecommissioned`. All seven variants carry new fields:

- `long logIndex` — Rabia commit index of the underlying KV snapshot. Sentinel `-1` indicates cold-replay (no committed index yet; subscriber must not use this for dedup).
- `HlcTimestamp stampedAt` — from Step 4's `HlcClock`.

**Why extend `MembershipDecision`, not introduce a new `ClusterEventLogKey` event family.** `ClusterEventLogKey` is for free-form audit events (Step 1); `MembershipDecision` is for the typed membership-state transitions. They are different domains:

- `MembershipDecision` has a closed set of variants known at compile time. Sealed-exhaustive switches give compile-time non-confusion (parent-spec v2's structural fix).
- `ClusterEventLogKey` is for arbitrary diagnostic events (alerts, traces, operator actions). Open-ended payload.

Folding both into a single event family would re-conflate the two; the H-series cure that introduced `MembershipDecision` would be undone.

**Why `logIndex = -1` as a sentinel rather than `Option<Long>`.** Subscribers must guard against `-1` (cold replay). `Option<Long>` looks cleaner at the type level but forces every subscriber to handle two cases at every read site. The sentinel pattern matches how `HlcTimestamp.ZERO` is used in the same codebase. Code review verifies the guard pattern at every dedup site.

**Why a fourth variant `NodeShuttingDown`.** Initial spec listed three (`NodeJoining`, `NodeDraining`, `NodeFailedDrain`) on the assumption that `SHUTTING_DOWN` could be expressed through `NodeDraining` + a reason field. Implementation surfaced that `NodeDeploymentManager`'s pre-RC1 `onNodeLifecyclePut` listener fired self-shutdown specifically on KV `SHUTTING_DOWN` writes; collapsing it into `NodeDraining` would force every consumer to discriminate by reason at every callsite. A distinct variant keeps sealed-exhaustive switches honest at compile time, matching the spec's structural-non-confusion principle (parent v2 §3.1).

**`TopologyObserver.publishMembershipDeltas`** (line ~555) becomes the sole emitter. It gains a parallel lifecycle-projection walker that diffs the previous KV lifecycle snapshot against the current and emits the four new variants. Existing emission of `NodeJoined`/`NodeRemoved`/`NodeDecommissioned` is preserved.

**Subscriber migration table.**

| Subscriber | Pre-RC1 | Post-RC1 |
|---|---|---|
| `ClusterDeploymentManager` | `onMembershipDecision` (line 87) + `onNodeLifecyclePut` (line 89) | `onMembershipDecision` only; switch expanded for 4 new variants |
| `NodeDeploymentManager` | `onNodeLifecyclePut` (line 75) | `onMembershipDecision`; drop KV-put |
| `ClusterDeploymentState` | FSM input `NodeLifecyclePutReceived` | folds into `MembershipDecisionReceived` |
| `GenerationSnapshotPublisher` | KV snapshot supplier | `MembershipDecision` subscription (snapshot-then-tail pattern) |
| `BootstrapModule` | KV snapshot supplier | same pattern as GSP |

**Files (prod).**

- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/MembershipDecision.java` — 4 new variants + `logIndex` + `stampedAt`.
- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/TopologyObserver.java` — extend `publishMembershipDeltas`.
- 5 subscriber files in the migration table above.

**Files (test).** `TopologyObserverTest` extension (lifecycle-projection walker emits exactly one variant per transition); per-subscriber unit tests; integration `test_membership_decision_ordering.sh` (single event per transition, no duplicates).

**Hidden risks.**

- `logIndex = -1` during cold replay. Subscribers reading `logIndex` for dedup MUST guard. Code-review checklist item.
- Out-of-order delivery across worker threads. `MembershipDecision` is emitted from a single observer but consumed on subscriber-owned threads. Test that 5/7/6 commit-order delivery still produces consistent final state at each subscriber (the consensus log index gives the canonical order; subscribers can re-sort or be tolerant of out-of-order — verify per-subscriber).
- GSP startup race. GSP needs the current snapshot before deltas flow. Mitigate: snapshot-then-subscribe pattern (initialise from snapshot at construction time, then attach subscription). Verify no events between snapshot and subscription are missed.

**Validation.** Module test + integration ordering test.

---

### 3.6 Step 1 — Cluster-scoped replicated `ClusterEventLog`  (depends on 4 + 2)

**Closes:** OB1 (alert/trace race — `/api/alerts`/`/api/traces`), OB2 (NODE_FAILED skew — `/api/events`). Both are currently-failing integration tests. Highest user-visible value of the six steps.

**Mechanism.** New KV key family `ClusterEventLogKey(long epoch, long seq)` replicated via Rabia (reuses existing `AetherKey` infrastructure; no second consensus atom). New value type:

```
record ClusterEventValue(
    HlcTimestamp at,           // Step 4 HLC
    EventType type,
    Severity severity,
    String nodeId,             // originator
    String message,
    Map<String,String> metadata,
    byte version)              // Step 6
```

**Why `(epoch, seq)`, not raw `(HlcTimestamp)`.** Total cluster ordering is established by Rabia commit, not HLC. HLC gives causal ordering but can tie at the µs level. `(epoch, seq)` gives:

- Strict total order without ties (epoch + seq is a Rabia-assigned unique pair).
- Efficient sweep-by-epoch (delete `epoch < currentEpoch - retainedEpochs`).
- Sidesteps OB1's receive-time race: two events POSTed to two nodes within the same wall-clock instant get distinct seqs at commit.

HLC is still present in the value for human-readable timeline reconstruction and cross-cluster diagnostics, but ordering uses the key.

**Why one KV family, not separate alert/trace/event families.** Three families would triple the sweeper bookkeeping and would not actually separate the data (alerts ARE events, traces ARE events). One family with `EventType` discriminator keeps the storage shape uniform and lets dashboard filter by type at read time.

**Writer pattern.** Each node writes its own events (key carries originator nodeId). Single-writer-per-node, no cluster-wide coordinator (the coordinator would be a SPOF). Transport-derived events (`PeerJoined`, `PeerConnected`) are the exception — emitted by every node and would produce N-fold duplication. **Decision: only the leader publishes transport-derived events.** Node-derived events (e.g., `OperationalEvent.AccessDenied`) publish from originator and carry nodeId.

**Reader pattern.** `/api/events`, `/api/alerts`, `/api/traces` read via a `KVStoreNotification.ValuePut<ClusterEventLogKey, ClusterEventValue>` subscriber feeding a windowed materialised view (`RingBuffer` cap 1000, same as today — but populated identically on every node from Rabia replication). Cold start scans the KV range up to `cap` entries.

**Sweeper.** New `ClusterEventLogSweeper`, leader-only, gated on `inQuorum` from Step 5. Deletes keys with `epoch < currentEpoch - retainedEpochs` (default 4 epochs ≈ several minutes at typical churn). MUST check `inQuorum()` — a minority-side leader could otherwise delete events the majority retains.

**Why epoch-bound sweep, not TTL.** TTL requires per-key timer; Rabia does not expose per-key TTL natively. Epoch-bound sweep batches deletions cleanly: one sweep per epoch tick, O(1) admin overhead.

**Files (prod).**

- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java` — add `ClusterEventLogKey`.
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` — add `ClusterEventValue`.
- NEW `aether/node/src/main/java/org/pragmatica/aether/api/ClusterEventLogPublisher.java`.
- `aether/node/src/main/java/org/pragmatica/aether/api/ClusterEventAggregator.java` — gut the 9 producer methods to delegate to the publisher; keep `events()`/`eventsSince()` reading the materialised view. The class becomes a thin projection over the KV-backed log.
- NEW `aether/node/src/main/java/org/pragmatica/aether/api/ClusterEventLogSweeper.java` — leader-only, quorum-gated.
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` — wire publisher + sweeper into the lifecycle.

**Files (test).** Rewrite `ClusterEventAggregatorTest` for the KV-backed path; new tests for publisher + sweeper; NEW integration `11-observability/test_events_cluster_ordering.sh` asserting identical ordered subsequence across all nodes.

**Hidden risks.**

- Snapshot blow-up under high event rate. Token-bucket rate-cap in the publisher (per-node, e.g., 100 events/s); excess events get dropped with a metric increment, NOT a `Result.failure` (would back-pressure the FSM).
- Cold-boot read divergence. The materialised view needs an `isReplay` flag so that replayed events on cold boot are NOT re-fan-out to downstream sinks (would cause every webhook to fire twice on every node restart). The downstream-sink subscription attaches AFTER the replay drains.
- `eventsSince(Instant)` contract drift. Today's API filters by wall-clock instant; under the new model, ordering is `(epoch, seq)`. **Recommended: retire the `Instant` filter, accept only `(epoch, seq)` cursor.** This is a breaking API change for the dashboard; coordinate with `dashboard-ui-spec.md` if landed; otherwise gate behind a flag and accept dual signatures for one release.
- Transport-derived events N-fold duplication. Mitigation captured above: leader-only emission for transport-derived; per-node for node-derived. Test that leader handoff does not drop a `PeerJoined` event mid-emission (leader-takeover replay catches this — already implemented for `MembershipDecision`).
- Sweeper on minority-side leader. Mitigation: `inQuorum()` gate from Step 5. WITHOUT Step 5 this is a data-loss hazard; this is why Step 5 is a hard prereq for Step 1's sweeper.

**Validation.**

- Module ordering test: two writers interleave events; a third reader observes them in `(epoch, seq)` order.
- Integration test on `TARGET_HOST`: POST 100 alerts across all nodes round-robin; GET from every node; assert identical content.
- **OB1 + OB2 currently-failing tests turn green at M6** (see §5).

---

## 4. Implementation order + dependency graph

```
            ┌─ Step 4 (HLC) ─────────┐
            │                        │
   parallel ├─ Step 3 (incarnation)  │
   PR1-PR4  ├─ Step 6 (wire fmt)     │
            └─ Step 5 (quorum view) ─┤
                                     │
                              Step 2 (subscriber migration)
                                     │
                                     ▼
                              Step 1 (event log) ◄─── OB1/OB2 turn green here
```

**Why this order.**

- **Step 4 first.** HLC is consumed by Steps 1 and 2 (`stampedAt`, `ClusterEventValue.at`). Pulling it last would force re-touching the FSM event surface; pulling it first lets Steps 1 and 2 consume the final shape.
- **Steps 3, 5, 6 parallel.** No dependencies between them; each is independently shippable.
- **Step 2 before Step 1.** Step 1's `ClusterEventLogPublisher` benefits from a cleaner subscription model on the aggregator, which Step 2 establishes (no more dual KV-put + `MembershipDecision` paths to coordinate).
- **Step 5 before Step 2.** Step 2 emits `MembershipDecision` from a possibly-partitioned leader; Step 5's `inQuorum()` gate ensures minority-side emission is structurally suppressed.

PRs 1-4 ship in parallel; PR5 (Step 2) sequenced after; PR6 (Step 1) closes the increment.

---

## 5. Verification milestones (TARGET_HOST)

Pre-flight: reset `aether/tests/integration/test-results.json` to known baseline before M1.

| Milestone | After PR | Run | Pass criterion |
|-----------|----------|-----|----------------|
| M1 HLC baseline | Step 4 | Full integration on `TARGET_HOST` | No regression vs pre-RC1 baseline (12/15 → 12/15) |
| M2 Incarnation | Step 3 | `13-edge-cases/test_chaos_flap.sh` | Flapping peer no longer reverts on stale-arrival; module test green |
| M3 Wire-fmt gate | Step 6 | `test_rolling_upgrade_wire_compat.sh` (NEW) | Mixed v1/v2 cluster, 5-min soak, no crashes, no decode-failure log lines |
| M4 Quorum safety | Step 5 | `test_minority_partition.sh` (NEW or extend `07-cluster-mgmt`) | Minority `/api/cluster/onduty` returns `[]`; majority unchanged |
| M5 Subscriber migration | Step 2 | `test_membership_decision_ordering.sh` (NEW) + deployment suite | Exactly one decision per FSM transition; no duplicate triggers in subscribers; deployment suite green |
| M6 Observability fix | Step 1 | `11-observability/*` full + `02-chaos/*` full | **OB1 + OB2 turn green; cluster B 02-chaos passes** |
| M7 RC1 acceptance | All 6 | Full integration on 3-, 5-, 7-node clusters + 30-min rolling-upgrade soak with mixed v1/v2 nodes | All 15 suites green; no crashes |

`TARGET_HOST`, `AETHER_SSH_KEY`, `AETHER_SSH_USER` already exported in the integration test environment; reference by name. Use `cd aether/tests/integration && ./run-tests.sh --env remote` per project convention.

---

## 6. Postponed (NOT in RC1)

| Item | Rationale for postponement |
|---|---|
| **Witness-gate** (Agent B's M5 reciprocal-observation gate) | Adds ≥10 s witness window on top of SWIM detection latency (10-15 s). The trade-off (asymmetric-partition false-positive elimination vs higher steady-state decommission latency) is not chaos-validated. Aether's slices are short-lived and CTM auto-heals fast — the current single-witness path is acceptable until chaos data forces the trade. |
| **Fold rewrite** (Agent A's M5-M10 — `MembershipFsm` becomes stateless reducer over `MembershipLog`) | Cleanest end-state by far, but large blast radius: FSM-as-folder, KV-as-derived-cache, snapshot-and-tail subscription model. Touches every membership reader. Holds at RC2+; pre-condition for production multi-cluster federation. |
| **Lease channel** (Agent C's `NodeLeaseKey`) | Most overlap with the convergent `ClusterEventLog`. Adds a third channel where two suffice. Defer until a specific need (e.g., sub-second cross-node liveness for routing) surfaces. |
| **Tangent T2** — fold model as broader cluster-decision substrate beyond membership (config, scheduling, sliceassignments) | Architectural ambition outside the RC1 stabilisation window. Re-evaluate post-1.0.0 GA. |
| **Cosmetic cleanup of `Decommissioned.swimDriven` dormant field** | Field is no longer read after H.4. Removal is mechanical; batch with H.6 sweep in a maintenance PR. |
| **Lifeguard suspicion-timer extension** (Agent B M6/M8) | SWIM module change, not on the chaos hot path. Better failure-detector adaptivity is desirable but not RC1-blocking. |

Each postponed item is captured here so RC2 planning has a documented starting point.

---

## 7. Appendix A — Synthesis (convergent cross-design analysis)

This appendix consolidates the synthesis derived from three independent design analyses run in the topology rethink session (`a-first-principles.md`, `b-failure-mode-driven.md`, `c-comparative.md` — all under `aether/docs/internal/design/topology-analysis-2026-05-13/`, linked in Appendix B as non-actionable historical source).

### A.1 Convergent core — six moves all three designs land on

Despite three radically different lenses — first-principles redesign, failure-mode-driven minimum-mechanism derivation, and comparative pattern-mining across seven distributed systems — six concrete moves appear in all three independently. This is the strongest possible signal that the problem space genuinely demands them.

| Move | Agent A reference | Agent B reference | Agent C reference |
|---|---|---|---|
| **HLC throughout FSM/event surface** | I-7 ("Time-step backward is never load-bearing") | M10 (rank 2, load-bearing eight) | V9 ("Monotonic time"); T1 row |
| **Monotone incarnation gating at reducer** | I-6 ("Identity = (NodeId, Incarnation)") | M1 (rank 1, load-bearing eight) | P-1 (pattern distillation: "monotonic refute key") |
| **Wire-format version byte** | I-8 ("Mixed-version readers tolerate forward unknown records") | M26 (rolling-upgrade gate) | V8 ("Mixed-version safety") |
| **Quorum-aware view** | I-9 ("Quorum loss is fold-visible") | (implicit in Rabia gating, made explicit via P-8 — Akka SBR) | V7 ("Quorum-aware view") |
| **Single canonical event channel (`MembershipDecision`)** | I-1 ("Single producer, single channel") | M31 ("Decision carries (logIndex, hlc)") | M1 of synthesised migration (subscriber migration) |
| **Cluster-scoped replicated event log** | I-10 ("Integration tests poll offsets, not events") | M28 ("Cluster-scoped Rabia-replicated event log") | M3 of synthesised migration (replicated `ClusterEventLog`) |

The convergence is not accidental. Each move corresponds to a class of bug visible in the current codebase:

- HLC closes T1 (NTP step-back affecting refractory/age comparisons in the reducer).
- Incarnation gating closes N10/SM2 (stale-arrival event-ordering inversion).
- Wire-format version closes MV1/MV2 (rolling-upgrade safety).
- Quorum-aware view closes the minority-side false-`ON_DUTY` claim during partition.
- Single canonical event channel closes the H.5 dual-channel conflation noted in the 2026-05-13 handover §6.
- Cluster-scoped event log closes OB1/OB2 (the two currently-failing integration suites).

### A.2 Divergent question — what each design proposes BEYOND the convergent core

After the convergent six, each design proposes a *different* additional move. This is where the design space genuinely forks:

| Design | Proposal beyond convergent core | Cost | Benefit |
|---|---|---|---|
| **Agent A** (first-principles fold) | Rewrite `MembershipFsm` as a stateless fold over an append-only `MembershipLog`; `MembershipView` becomes `fold(log up to local commit offset)`; KV `NodeLifecycleKey` retires to derived cache | Large blast radius (every reader touched); snapshot/tail subscription model required; "slow follower" failure mode (delayed correct answers under high decision rate) | Cleanest model; every drift bug eliminated by construction; integration tests poll offsets instead of events; rolling upgrades are a schema problem on one channel |
| **Agent B** (failure-mode-driven) | Reciprocal-witness gate (M5) — `(ON_DUTY, SwimFaulty) → DECOMMISSIONED` requires ≥⌈f+1⌉ distinct observers within `witnessWindow` (default 10 s) | Adds ≥10 s decommission latency on top of SWIM detection (~10-15 s); during partial-network-failure the witness window extends to 20-25 s, during which the leader routes work to a corpse | Asymmetric-partition false-positive decommissions eliminated by construction; single-node mis-observation cannot decommission a healthy peer |
| **Agent C** (comparative) | `NodeLeaseKey` — per-peer consensus-stamped liveness lease, leader-only writer, 3 s renewal period; view becomes `SWIM-HEALTHY AND lease-fresh` | Reintroduces centralised throughput dependency on the leader (~17 writes/s on a 50-node cluster); leader-handoff stall causes whole-cluster view flicker for one renewal cycle | Cluster-wide consistent "node is dead" signal even when local SWIM is jittery; closes OB2 and OB5 in one channel |

**The three forks are not strict alternatives.** Agent A's fold subsumes Agent B's witness gate (the gate becomes a fold-time predicate) AND subsumes Agent C's lease (the lease becomes a decision-record type on the log). Agent A is therefore the long-range end-state; B and C are intermediate destinations.

### A.3 Self-acknowledged weaknesses

Each design's author explicitly named its weakest point:

- **Agent A:** "Handles slow followers poorly under high decision-rate churn." A folder lagging by GC pause or chaos-rate decisions appears in tests as flake — delayed correct answers, not wrong answers. Honest trade: wrong-answer bugs are unbounded; delay bugs are bounded by Rabia commit latency plus fold lag.
- **Agent B:** "Witness window adds ≥10 s decommission latency." Common case (one node hard-fails, every peer observes FAULTY within 1-2 probe rounds) is harmless; partial-network-failure (one peer's SWIM jittery while underlying node is genuinely dead) extends decommission to 20-25 s. Pragmatic compromise (2-witness for followers, 1-witness for leader-own) not chaos-validated.
- **Agent C:** "Lease-renewal channel reintroduces centralised throughput dependency on the Rabia leader." 17 writes/s on a 50-node cluster of pure liveness traffic; under load or during leader-handoff this can starve operator writes. Mitigation (Cockroach-style batching in one range) requires KV multi-put atom — adds consensus surface area.

The self-acknowledged weaknesses are NOT symmetric. Agent A's weakness (latency) is bounded and observable; Agent B's weakness (witness latency) is bounded but bigger; Agent C's weakness (throughput) is structural and grows with cluster size.

### A.4 Tangents surfaced during synthesis

Four tangential considerations emerged across the designs that are not part of the RC1 plan but deserve future-work tagging:

- **T1** (Agent B): NTP step-back as load-bearing — addressed by HLC adoption (Step 4); the tangent is "audit every wall-clock comparison in the codebase, not just FSM events". Out of RC1 scope; track as a maintenance pass.
- **T2** (Agent A): Fold model as a broader cluster-decision substrate — applies beyond membership (config, scheduling, slice assignments). Architectural ambition; RC2+.
- **T3** (Agent C): Lifeguard suspicion-timer extension and Phi-accrual failure-detector adaptivity — improves SWIM detection latency under high-RTT links. SWIM-module work; not on chaos hot path.
- **T4** (Agent B): Per-state deadline timer (extend `JOIN_DEADLINE` to `PROVISIONING` and `DRAINING`) — closes stuck-state class structurally. Captured as an issue; not RC1-blocking.

### A.5 Recommended RC1 plan (the synthesis output)

The synthesis recommends the convergent six moves AND DEFERS the three divergent proposals (A's fold, B's witness gate, C's lease channel) until after RC1. The rationale is twofold:

1. **The convergent six close every currently-observed failure.** OB1 and OB2 (the two failing integration suites) fall out of Step 1. T1 is closed by Step 4. N10/SM2 by Step 3. MV1/MV2 by Step 6. Minority-side false claim by Step 5. H.5 conflation by Step 2. There is no observed bug in the current codebase that the divergent proposals close *additionally* — they close *future* bug classes (asymmetric partition for B; high decision-rate slow-follower drift for A; cluster-wide consistent liveness for C). RC1 prioritises observed-bug closure over future-class prevention.

2. **The three divergent proposals overlap.** Agent A's fold subsumes both B and C; doing all three simultaneously would either be redundant or impose coordination cost. Picking one (and which one) is a post-RC1 strategic choice that benefits from RC1 production data.

The RC1 plan is therefore: ship the convergent six in dependency order (§4); track A/B/C as candidate RC2 directions; revisit after the first month of RC1 production exposure.

**Total effort estimate.** ~5-7 engineering days for the six steps; six PRs; sequenceable. Risk-bounded: each PR independently revertible.

### A.6 The single load-bearing sentence

If the entire spec collapses to one sentence, it is this:

> **Every drift bug surviving the H-series traces to per-node materialised state where cluster-scoped state was needed.**

OB1 (per-node alert/trace map), OB2 (per-node `ClusterEventAggregator` buffer), H.5 dual-channel (per-node KV-put listener + per-cluster `MembershipDecision` stream), minority-side false `ON_DUTY` (per-node SWIM view trusted without cluster-quorum check) — all four are instances of the same pattern. The RC1 increment is six moves that eliminate four instances and gate the remaining surface against the same class of bug.

---

## 8. Appendix B — Historical source documents

The following three design analyses informed the synthesis but are **non-actionable historical source**. They are preserved for traceability of the design rationale. Implementation MUST NOT reference them as design specs; this consolidated spec is the authoritative artefact.

| Path | Description | Status |
|---|---|---|
| `aether/docs/internal/design/topology-analysis-2026-05-13/a-first-principles.md` | "Membership is a folded log" — first-principles redesign by Agent A. Proposes `MembershipFsm` rewrite as a stateless fold over an append-only `MembershipLog`. Source for the convergent insights I-1, I-6, I-7, I-8, I-9, I-10. | Non-actionable historical source |
| `aether/docs/internal/design/topology-analysis-2026-05-13/b-failure-mode-driven.md` | 32 mechanisms derived bottom-up from 47 edge cases by Agent B. Source for the load-bearing eight (M1, M2, M5, M10, M11, M12, M28, M31) and the reciprocal-witness gate proposal (M5, postponed in RC1 per §6). | Non-actionable historical source |
| `aether/docs/internal/design/topology-analysis-2026-05-13/c-comparative.md` | Comparative analysis across seven distributed systems (Consul/Serf, etcd/K8s, ZooKeeper, Akka, Cassandra, CockroachDB, Nomad) by Agent C. Source for pattern distillation P-1..P-8 and the lease-with-epoch (`NodeLeaseKey`) proposal (postponed in RC1 per §6). | Non-actionable historical source |

### Internal cross-references

- Parent: [`aether/docs/specs/membership-architecture-spec.md`](membership-architecture-spec.md) (v2; this spec extends Layers 2-3).
- Current FSM spec: [`aether/docs/specs/cluster-membership-fsm-spec.md`](cluster-membership-fsm-spec.md) (per-peer FSM; this spec adds HLC + incarnation to its event surface).
- Session handover: [`aether/docs/internal/progress/session-handover-2026-05-13.md`](../internal/progress/session-handover-2026-05-13.md) (H-series context).
- Existing infrastructure to reuse: `integrations/hlc/.../HlcClock.java`, `integrations/hlc/.../HlcTimestamp.java`, `aether/aether-deployment/.../phase/ClusterPhaseView.java`, the 11 existing `MembershipDecision` subscribers.

### External references (regulatory & standards)

None applicable — Aether's membership layer is a self-contained distributed-systems concern. The comparative analysis (Appendix B, Agent C) cites the academic and industry primary sources (SWIM paper, Lifeguard paper, Cockroach RFCs, Akka Cluster docs) inline at `aether/docs/internal/design/topology-analysis-2026-05-13/c-comparative.md` §7.
