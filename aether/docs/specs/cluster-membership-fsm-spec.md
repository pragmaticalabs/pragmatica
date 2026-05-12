---
title: Per-Peer Cluster-Membership FSM Specification
date: 2026-05-12
author: spec-writer agent, reviewed by Sergiy Yevtushenko
status: approved (2026-05-12)
branch: release-1.0.0-rc1
supersedes: ad-hoc gate-stack in HealthReconcilerImpl + ObservationAggregator (D.2, D.3 gates)
related:
  - aether/docs/specs/membership-architecture-spec.md (v2, typed-stream split; this spec implements its Layer 2 cleanly)
  - aether/docs/internal/progress/session-handover-2026-05-11b.md (D.3 + D.5 landed)
---

# Per-Peer Cluster-Membership FSM Specification

> **Status note.** This spec is implementation-ready but intentionally larger than a single PR. It is decomposed into ordered layers E.2 → E.8 (§9). Each layer is a self-contained commit with its own regression tests. The final commit in the sequence is the one that deletes `ObservationAggregator` and the four gates in `handleAggregatedEdge`.

---

## 1. Problem statement

The current authoritative-membership layer (`HealthReconcilerImpl`) routes a single conceptual decision — *"is peer N dead?"* — through **seven independent gates** stacked in series. Each gate has its own suppress / threshold / cooldown / escape-hatch logic, was added to fix a specific symptom, and exposes the next symptom downstream.

```
SWIM detects faulty
  G1  SwimProtocol.emitFaultyOrUnknown          phase suppression (D.3)
  G2  ObservationAggregator.tally               threshold quorum (D.2, broken — see §1.1)
  G3  emitIfChanged                             lastAggregated cache (sets state on skip!)
  G4  handleAggregatedEdge: leader gate         3 escape hatches (target=leader, leader=∅, self-leader)
  G5  cooldownActive(target, nowMs)             per-target cooldown
  G6  suppressedByPhase                         phase suppression AGAIN (lifecycle-layer copy of G1)
  G7  consensus.apply()                         NodeInactive rejection
```

### 1.1 Concrete failure mode (the smoking gun)

`aether/tests/integration/suites/02-chaos/test-kill-node.sh` (the non-leader victim case). Pre-conditions are clean: cluster is in `phase=NORMAL`, leader is known and stable, victim is *not* the leader, `everSeenHealthy[victim] = true`.

`docker kill` the victim. Observed sequence on the leader, traced post-mortem:

| t (s) | Event | Gate outcome |
|---|---|---|
| 0.0 | container killed | — |
| ~1.5 | SWIM `SuspectObserved` emitted | G2: `translate(Suspect) = none` → window unchanged, no edge |
| ~16.5 | SWIM `FaultyObserved` emitted (suspect timeout ~15s) | G1 pass (phase=NORMAL), arrives at G2 |
| ~16.5 | `aggregateEdge` called with `observer=self` | G2: tally sees one entry `{observer=self, state=DECOMMISSIONED}`; `effectiveThreshold(DECOMMISSIONED)=1`; passes; G3 sets `lastAggregated[victim]=DECOMMISSIONED` and emits |
| ~16.5 | `handleAggregatedEdge` | G4 pass (leader-self); G5 pass (no prior write); G6 pass (phase=NORMAL); G7 pass; KV write proposed |
| **expected:** `NodeLifecycleKey[victim] = DECOMMISSIONED`, `NODE_FAILED` event downstream |

This path **should** work. Inspecting `lastAggregated` state on the leader at the moment of the test timeout reveals it still contains a stale `ON_DUTY` entry. That entry was placed during a **prior** observation cycle, specifically by `emitIfChanged` at G3 reacting to a `HealthyObserved` after a transient flap. The very next `FaultyObserved` should have moved it to `DECOMMISSIONED`. It did not — because `recordWrite` (called when the write succeeds) calls `aggregator.resetEdgeState(target, newState)`, but `recordWrite` only runs *after* `commandApplier.apply(...)` succeeds. In the failing run, `commandApplier` rejected with `ConsensusError.NodeInactive` on a *different* lifecycle write earlier in the same window, and the aggregator's `lastAggregated` is now permanently desynced from KV. The next `FaultyObserved` produces the same edge `DECOMMISSIONED` → G3 sees `lastAggregated[victim] == DECOMMISSIONED` already (stale) → emits `none()` → no write proposed → `NODE_FAILED` never fires → test times out.

**This is the structural failure.** G3 *caches* a decision that is supposed to be reconstructible from KV. The cache and KV can drift apart whenever a downstream stage rejects, retries, or races with itself. There is no mechanism to detect drift because there is no single source of truth — there are **two** sources (the in-memory `lastAggregated` map and the consensus-replicated `NodeLifecycleKey` atom), neither dominating.

The D.2 (asymmetric threshold), Option-B (DECOMMISSIONED threshold=1), M1 (periodic phase tick), and leader-unknown escape-hatch fixes each unblocked one symptom and exposed the next. The integration-test pass rate has been pinned at 8/15 for three sessions; the failures are all variants of the same drift bug above.

### 1.2 Why a structural rewrite, not another patch

Per `feedback_structural_over_tactical.md`: when a problem recurs as multiple symptoms, escalate to a structural fix. Each gate addresses a *symptom* of a missing primary structure:

| Symptom-fix | Underlying missing structure |
|---|---|
| `lastAggregated` cache (G3) | "Have we already committed this decision?" — should query KV, not a parallel map |
| Leader gate + escape hatches (G4) | "Who owns this write?" — should be a single authoritative writer, not a sliding leader-or-escape rule |
| Cooldown (G5) | "Have we written this transition recently?" — should be the FSM state itself (idempotent transitions) |
| Phase suppression at lifecycle layer (G6) | "Is the cluster in cold-boot?" — should be a precondition on the *event*, not a filter on the *output* |
| `everSeenHealthy` set, `aggregatorLock` | Bootstrap correctness — should be encoded in the FSM's initial state (`UNTRACKED + Faulty = no-op`) |

The replacement is a **per-peer state machine** whose state is fully reconstructible from KV. Caches disappear because they are replaced by KV reads. Cooldowns disappear because idempotent transitions cannot fire twice with different outcomes. Phase suppression disappears at this layer because the FSM bootstrap state already encodes the "we have not yet seen this peer healthy" condition.

**Note on self-write elimination (Q1 decision, 2026-05-12).** The legacy `HealthReconcilerImpl` admitted a *self-write* path: a joining node would call `attemptSelfOnDutyWrite` to flip its own `NodeLifecycleKey` from `JOINING` to `ON_DUTY`. This was a workaround for the consensus-admission vs lifecycle-write race that exists when the joining node writes its own state before being fully admitted: the node knows it has started but the cluster's consensus active-set has not yet caught up, so the self-write can be rejected with `NodeInactive` and must retry on backoff. Under the **leader-initiated** design adopted here, that race is structurally eliminated: the leader's SWIM cannot observe a peer that is not in the consensus active set, so by the time the leader writes `ON_DUTY` the peer is fully admitted by definition. The joining node is purely passive once it has joined consensus — it does not call any self-promotion API.

---

## 2. Design principles

The FSM must satisfy seven invariants. Each is testable.

### I1. KV-reconstructible

**FSM state for peer `N` is a pure function of:** `NodeLifecycleKey[N]`, the `ProvisioningSlotValue` whose `assignedNodeId == N` (if any), and the currently-active `ClusterPhase` (which is itself derived from `NodeLifecycleKey` listing per §7). No hidden in-memory state on the leader. New-leader cold-start reads KV → derives state → resumes. This subsumes the membership-architecture-spec P4 ("Single-writer, append-only authoritative state") and extends it: state is not just *written* through KV, it is *recoverable* from KV.

### I2. Single-writer

Only the **current leader** fires transitions. Followers may emit events into the FSM input queue (via SWIM observation gossip) but never write `NodeLifecycleKey` directly. The "self-leader-eviction escape hatch" (current G4) is replaced by an explicit `LeaderEvicted` event handled by the leader-election layer (§6.3), not by an in-band lifecycle write from a non-leader. The "leader-unknown escape hatch" disappears: during a leader-handoff window no transitions fire on this layer; the new leader replays state from KV (§6) and resumes. Latency cost is bounded by leader-election latency (typically <1s on Rabia per memory `project_leader_election_debug.md`).

### I3. Idempotent transitions

For every `(state, event)` pair, applying the event N times yields the same `(state', KV-writes', side-effects')` as applying it once, modulo idempotent `Put` writes. The transition table is closed: every cell is either a transition record or an explicit no-op record. There is no implicit "do nothing on unrecognized event" — every cell must be listed.

### I4. Totality

The transition table (§5) has **7 states × 8 events = 56 cells**, every one explicitly populated. The implementation has a `default` branch on `(state, event)` that throws `IllegalStateException`. The FSM cannot enter a state where an event is ambiguous.

### I5. Bootstrap-safe (subsumes cold-boot suppression at FSM level)

`(UNTRACKED, SwimFaulty) → no-op`. `(UNTRACKED, SwimHealthy)` is the *only* path into `JOINING`. This encodes the "never-seen-healthy = ignore failure" rule structurally — there is no `everSeenHealthy` set anywhere. The SWIM-side suppression of `FaultyObserved` during `COLD_BOOT` (current D.3 gate G1) **stays as defense-in-depth at the gossip layer** to reduce wire chatter, but is no longer correctness-load-bearing for this FSM. Removing it would not break correctness; only efficiency.

### I6. Event-driven, not poll-driven

The FSM is a pure function `transition: (State, Event) → (State', Effects)`. There is **no `Tick` event**. All deadlines are enforced by **one-shot timers** scheduled on transition entry and cancelled on transition exit. When a timer fires, it enqueues a discrete event (e.g., `JoinDeadlineExpired(peer)`) onto the same input queue as every other event. There is no periodic poll, no cron, no `SharedScheduler.schedule(... evaluatePhase, interval)`. Every event source has explicit delivery guarantees: if a future bug drops events, the fix is to repair the event source, not to add polling on top.

### I7. Effects are KV writes only (no in-memory side channels)

Every observable effect of a transition is a `KVCommand` against the consensus-replicated KV-Store. There is no in-memory pub/sub, no `Consumer<...>` callback list, no `AtomicReference` mirroring KV. Downstream subsystems (routing, CTM, status routes, NODE_FAILED metric) subscribe to KV change notifications, not to the FSM directly. This eliminates the entire `phaseListeners` / `currentPhase` AtomicReference / `lastWriteAt` map class of drift bugs.

---

## 3. States

Seven states per peer. Each state is a record (no fields beyond `peer: NodeId` plus per-state metadata derived from KV). Sealed-interface style following the `NodeDeploymentState` precedent.

| State | Lifecycle KV | Provisioning slot | Semantics | Consumed by |
|---|---|---|---|---|
| `UNTRACKED` | absent | absent | The leader has no record of this peer. Bootstrap state for every NodeId; also the terminal post-GC state for fully-decommissioned peers after `DecommissionedAtomGc` cleanup. | nothing routes here; absent NodeLifecycleKey hides from all KV-derived views |
| `PROVISIONING` | absent | present, `assignedNodeId=∅` | CTM has spawned a slot but no node has claimed it. | `ClusterTopologyManager` (counts as in-flight); `MembershipView` excludes from routing |
| `JOINING` | `state=JOINING` | present, `assignedNodeId=N` | **Reachable only via the slot-provisioning path** (`(UNTRACKED\|PROVISIONING, SlotClaimed) → JOINING`). A node has been assigned a CTM-spawned slot; the leader awaits SWIM confirmation before promoting it to `ON_DUTY` (will do so on first `SwimHealthy(peer)` observation). SWIM-discovered peers without a slot transition directly to `ON_DUTY` per §5.1 note 8 (Bootstrap-correction 2026-05-12). | routing skips; status reports "joining" |
| `ON_DUTY` | `state=ON_DUTY` | none (slot deleted by `DecommissionedAtomGc.completeSlot`) | Full member. Receives traffic, participates in consensus voting. | routing, streams, CTM as "current size", status |
| `DRAINING` | `state=DRAINING` | none | `DrainCoordinator` has begun the drain protocol. Owners filter this node out of new work; in-flight requests continue. | `ConsensusDrainCoordinator` polls quiescence; `LifecycleAwareRouter` excludes from new assignments |
| `DECOMMISSIONED` | `state=DECOMMISSIONED` | none | Permanently removed from the cluster. CTM may provision a replacement slot. | `NodeReconciler` (treats as missing); `NODE_FAILED` metric fires on entry; `DecommissionedAtomGc` will eventually GC the atom → state returns to `UNTRACKED` |
| `FAILED_DRAIN` | `state=FAILED_DRAIN` | none | Drain protocol timed out. Operator review required. Node is still addressable for emergency ops; routing already moved on. | `/api/node/lifecycle` exposes status=`failed_drain` to operator; CTM does NOT provision replacement (operator-driven recovery) |

**Mapping to current `NodeLifecycleState` enum** (`aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java:591`):

The existing enum has six values: `JOINING, ON_DUTY, DRAINING, DECOMMISSIONED, SHUTTING_DOWN, FAILED_DRAIN`. The FSM adds **no new KV state**; it adds two FSM-only states (`UNTRACKED`, `PROVISIONING`) that are encoded as **absence-or-slot-only** in KV, not as new enum values. The `SHUTTING_DOWN` enum value is retained for backward compatibility but is no longer emitted by this FSM — it is folded into `DRAINING` (operator-initiated graceful drain) and `DECOMMISSIONED` (post-drain). Existing readers of `SHUTTING_DOWN` continue to function; new writes only produce the seven canonical states above.

---

## 4. Events

Eight event types. Single sealed interface following the `NodeDeploymentEvents` precedent.

```java
// aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/
//   MembershipFsmEvent.java
public sealed interface MembershipFsmEvent {
    NodeId peer();
    long nowMs();

    record SwimHealthy(NodeId peer, long incarnation, long nowMs) implements MembershipFsmEvent {}
    record SwimFaulty(NodeId peer, long incarnation, long nowMs) implements MembershipFsmEvent {}
    record SwimDeparted(NodeId peer, long incarnation, long nowMs) implements MembershipFsmEvent {}
    // SuspectObserved is intentionally absent: suspect is a SWIM-internal transient state
    // and never produces an FSM event. UnknownObserved is also absent; it folds into Faulty
    // via SwimProtocol's existing suspect-timeout machinery.

    record SlotSpawned(NodeId peer /* slot-id mapped to peer post-claim */, long nowMs) implements MembershipFsmEvent {}
    record SlotClaimed(NodeId peer, long nowMs) implements MembershipFsmEvent {}
    // SlotSpawned fires when ProvisioningSlotKey appears with assignedNodeId=∅;
    // peer() returns a synthetic "slot-pending" NodeId — see §4.2 ordering rules.

    record OperatorDrain(NodeId peer, DrainReason reason, long nowMs) implements MembershipFsmEvent {}
    record OperatorDecommission(NodeId peer, boolean force, long nowMs) implements MembershipFsmEvent {}

    record DrainOutcome(NodeId peer, boolean success, long nowMs) implements MembershipFsmEvent {}

    record JoinDeadlineExpired(NodeId peer, long nowMs) implements MembershipFsmEvent {}
    // JoinDeadlineExpired is enqueued by a one-shot timer scheduled when the FSM enters
    // JOINING for `peer`. The timer is cancelled when the FSM leaves JOINING (whether to
    // ON_DUTY, DECOMMISSIONED, or DRAINING). On fire, the timer enqueues this event onto
    // the FSM input queue exactly once. There is no periodic Tick.
}
```

### 4.1 Event sources and ordering

| Event | Source | Wire path | Ordering guarantee |
|---|---|---|---|
| `SwimHealthy`, `SwimFaulty`, `SwimDeparted` | `SwimProtocol` → `HealthReconciler.onSwimObservation` (renamed `MembershipFsm.onSwimObservation`) | local in-process; SWIM gossip is multi-source but the FSM sees only the local node's interpretation | Per-peer FIFO **on the local node**. Not globally ordered. The FSM handles re-ordering by mapping `state→event→state` deterministically; replay of out-of-order events converges (idempotence I3). |
| `SlotSpawned`, `SlotClaimed` | KV notification on `ProvisioningSlotKey` Put | `KVStoreNotification.ValuePut` listener registered in `MembershipFsm.start()` | Consensus-replicated → totally ordered across all leaders. Guaranteed delivery on commit. |
| `OperatorDrain`, `OperatorDecommission` | `/api/node/drain`, `/api/node/decommission` REST routes → `LifecycleWriter` (interface preserved) → FSM input queue | local in-process on the leader; non-leader routes forward via the existing route-forwarding layer | Single operator-call-at-a-time semantics; not concurrent with other operator calls for the same peer (REST handler holds the FSM input lock — §10.2). |
| `DrainOutcome` | `ConsensusDrainCoordinator` poll loop → callback to FSM | local in-process | At-most-once per `DRAINING → ...` transition: `ConsensusDrainCoordinator` is invoked exactly once on `(ON_DUTY|JOINING, OperatorDrain) → DRAINING` entry; it resolves exactly once. `DrainCoordinator` owns its own hard-deadline timeout (`drainHardDeadlineMs`); on timeout it resolves with `success=false`. The FSM does NOT schedule a separate drain timer. |
| `JoinDeadlineExpired` | Per-peer **one-shot** timer scheduled via `SharedScheduler.schedule(TimeSpan)` on `JOINING` entry; cancelled on `JOINING` exit | local in-process on the leader | At-most-once per `JOINING` entry. If the FSM exits `JOINING` before the timer fires, the cancellation prevents the event from being enqueued. If the event is already enqueued but the FSM has already left `JOINING`, the receiving cell is a `nop` (the timer carries no state). |

### 4.2 Slot-to-peer mapping

`SlotSpawned` is the awkward event: the slot exists before any peer has claimed it, so there is no `NodeId` to key the FSM on. Resolution: the FSM tracks slots in a per-leader `Map<String, NodeId>` from `ProvisioningSlotKey.slotId() → assignedNodeId`. **This map is itself reconstructible from KV** (it is exactly the inversion of `ProvisioningSlotValue.assignedNodeId`); rebuilt on leader-takeover (§6.2). `SlotSpawned` does not enter the FSM as a state-changing event — it merely adds a slot row to this mapping. The FSM enters `PROVISIONING` only when the slot is claimed (`SlotClaimed`), at which point we know the `NodeId`. Until that moment, the slot is tracked in CTM accounting (the existing `aliveSlots` list in `ClusterTopologyManagerRecord`), which is fine because CTM cares about *slot count*, not *peer identity*, prior to claim.

---

## 5. Transition table (exhaustive)

7 states × 8 events = 56 cells. Each cell records `(new state, KV writes, side-effects)`. Cells marked **N/A** are unreachable by construction (e.g., `SlotClaimed` for a peer already in `ON_DUTY` cannot happen because the slot is deleted on claim) — these are still total: implementation throws `IllegalStateException` to surface the bug rather than silently swallow.

Notation:
- `Put(L=X)` = `KVCommand.Put(NodeLifecycleKey(peer), NodeLifecycleValue.copy(...).withState(X))`
- `Del(S)` = `KVCommand.Remove(ProvisioningSlotKey(slotId))`
- `→S` = transition to state `S`
- `nop` = no transition, no writes, no effects
- `err` = illegal — throws `IllegalStateException`

| From \ Event | SwimHealthy | SwimFaulty | SwimDeparted | SlotClaimed | OperatorDrain | OperatorDecommission | DrainOutcome | JoinDeadlineExpired |
|---|---|---|---|---|---|---|---|---|
| **UNTRACKED** | →ON_DUTY; `Put(L=ON_DUTY)`; emit `NODE_ON_DUTY` *(Bootstrap-correction 2026-05-12; see §5.1 note 8)* | nop (I5 bootstrap-safe) | nop | →JOINING; `Put(L=JOINING)`; emit `NODE_JOINING` | nop (no-op for unknown peer) | nop unless `force=true` → →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; emit `NODE_FAILED` | err | nop (timer for a peer that left JOINING; harmless) |
| **PROVISIONING** | err (no NodeId yet — handled via §4.2 mapping, not FSM) | err | err | →JOINING; `Put(L=JOINING)`; emit `NODE_JOINING` | err | err (no NodeId yet) | err | nop |
| **JOINING** | →ON_DUTY; `Put(L=ON_DUTY)`; `Del(slot)`; emit `NODE_ON_DUTY` *(leader-initiated, see §5.1 note 1)* | nop *(while JOINING, transient SWIM faulty observed during boot is ignored until the join one-shot timer fires; see JoinDeadlineExpired column)* | →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; `Del(slot)`; emit `NODE_FAILED` | nop (idempotent re-delivery) | →DRAINING; `Put(L=DRAINING)`; invoke `DrainCoordinator.prepareDrain(peer, reason)` | →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; `Del(slot)`; emit `NODE_FAILED` | err (no drain in progress) | →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; `Del(slot)`; emit `NODE_FAILED` (reason=join-timeout) |
| **ON_DUTY** | nop (re-confirmation; no write) | →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; emit `NODE_FAILED`. *This is the smoking-gun path. No threshold gate, no cooldown gate, no lastAggregated cache. The single SWIM observation on the leader is sufficient because the leader is single-writer (I2) and the write is consensus-replicated (I7)* | →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; emit `NODE_FAILED` | err (slot already deleted on JOINING→ON_DUTY) | →DRAINING; `Put(L=DRAINING)`; invoke `DrainCoordinator.prepareDrain(peer, reason)` | if `force=true`: →DECOMMISSIONED directly; `Put(L=DECOMMISSIONED)`; emit `NODE_FAILED` (reason=operator-forced) *(see §5.1 note 3)*; else →DRAINING (graceful); `Put(L=DRAINING)`; invoke `DrainCoordinator.prepareDrain(peer, OperatorRequested)` | err | nop (timer for a peer that left JOINING; harmless) |
| **DRAINING** | nop (operator drain in progress; ignore peer's gossip — drain protocol owns this) | nop (drain in progress; SWIM faulty during drain is expected if node is shutting itself down — drain outcome will resolve the state) | →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; emit `NODE_FAILED`. *Hard departure overrides drain.* | err | nop (already draining) | if `force=true`: cancel drain; →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; emit `NODE_FAILED` (forced); else nop | if `success`: →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; emit `NODE_DRAINED`. If `!success`: →FAILED_DRAIN; `Put(L=FAILED_DRAIN)`; emit `NODE_DRAIN_FAILED` (reason=hard-deadline-from-DrainCoordinator) | nop (timer for a peer that left JOINING; harmless) |
| **DECOMMISSIONED** | nop *(zombie; ignore. Re-join requires GC to fire first → state returns to UNTRACKED → SwimHealthy can then transition to JOINING)* | nop | nop | err (slot deleted) | nop | nop (idempotent) | err | nop (waiting for `DecommissionedAtomGc` to reset to UNTRACKED) |
| **FAILED_DRAIN** | nop | nop | →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; emit `NODE_FAILED` *(operator resolved by node dying)* | err | nop (drain already failed; operator must explicitly recover) | →DECOMMISSIONED; `Put(L=DECOMMISSIONED)`; emit `NODE_FAILED` *(operator override to clear the failed-drain marker)* | err | nop |

### 5.1 Notes on the table

1. **`(JOINING, SwimHealthy) → ON_DUTY` is leader-initiated (Q1 decision, 2026-05-12).** The transition fires on the **leader's** FSM when the leader's local SWIM detector first reports the joining peer as Healthy. There is no self-write path: the joining node does not call `attemptSelfOnDutyWrite` or any equivalent API; it is purely passive once it has joined consensus. The race that the legacy self-write path was working around — consensus-admission lagging behind the joiner's self-registration — is structurally eliminated here, because the leader's SWIM cannot observe a peer that is not in the consensus active set. By the time the leader writes `ON_DUTY`, the peer is fully admitted by definition.

2. **`(ON_DUTY, SwimFaulty) → DECOMMISSIONED` directly.** This is the central correctness change. No threshold, no cooldown, no cache. Correctness rests on:
   - **I2** — only the leader fires this transition. Multiple followers seeing the same peer as Faulty does *not* result in N concurrent writes; they enqueue events on followers' FSMs which then *no-op* because the follower's FSM is not the leader's FSM (§6.1).
   - **Consensus deduplication** — the leader's `KVCommand.Put(L=DECOMMISSIONED)` is consensus-replicated. A second `Put` with the same state is a no-op at the consumer layer (KV-store notification fires once per distinct `(key, value)` pair via existing `ValuePut` dedup).
   - **SWIM gossip convergence** — the leader's local SWIM detector is itself a quorum-based aggregator across `k=4` random probes per round (existing SWIM behaviour). The leader does NOT rely on cross-node SWIM observation gossip for correctness; SWIM gossip is the *transport* of observations, the leader's own SWIM instance is the *aggregator*. This eliminates the `ObservationAggregator` class entirely (§12.1).

3. **`(JOINING, SwimFaulty) → nop`, deadline-bounded by a one-shot timer.** During the join window (60s default) we tolerate transient SWIM faulty observations because the node is mid-boot and may not have completed SWIM handshake. A hard `SwimDeparted` (TCP RST / explicit shutdown) still terminates immediately. Join deadline is enforced by a **one-shot timer** scheduled when entering `JOINING`. On fire, the timer enqueues `JoinDeadlineExpired(peer)`, which fires `(JOINING, JoinDeadlineExpired) → DECOMMISSIONED`. There is no periodic poll. The timer is cancelled if the FSM leaves `JOINING` via any other path.

4. **`(DRAINING, SwimFaulty) → nop`.** Once an operator-initiated drain starts, SWIM-driven failure detection is muted — the `DrainCoordinator` owns the outcome. This prevents the race where the drain protocol succeeds at t=10s but a SWIM faulty observation at t=11s (because the node has stopped responding by then) would otherwise re-trigger a redundant `DECOMMISSIONED` write. The hard-deadline for draining is owned by `DrainCoordinator` itself (`drainHardDeadlineMs`, default 90s); on timeout it resolves the awaiting `Promise` with `success=false`, which the FSM consumes as a `DrainOutcome(success=false)` event → `(DRAINING, DrainOutcome) → FAILED_DRAIN`. The FSM does not schedule its own drain timer.

5. **`(DECOMMISSIONED, SwimHealthy) → nop`.** A peer cannot un-decommission by sending us healthy gossip. Re-joining requires the leader's `DecommissionedAtomGc` to clean up the atom first (FSM moves to `UNTRACKED`), at which point a fresh `SwimHealthy` can drive `UNTRACKED → JOINING`. This is the correct semantics for "zombie node returns with the same NodeId after being decommissioned": it gets re-admitted after GC, not before.

6. **`OperatorDecommission(force=true)` is a direct transition, distinct from drain (Q2 decision, 2026-05-12).** Force is its own FSM transition path: `(ON_DUTY, OperatorDecommission(force=true)) → DECOMMISSIONED` writes `Put(L=DECOMMISSIONED)` directly as a single atomic KV write; it does **not** invoke `DrainCoordinator`. By contrast, `(ON_DUTY, OperatorDecommission(force=false)) → DRAINING` invokes `DrainCoordinator.prepareDrain` and arrives at `DECOMMISSIONED` only via the `DrainOutcome` event. Force and graceful drain share an end state but are semantically distinct operations; the audit trail (operator-forced vs drain-then-decommissioned) is preserved by the emitted event reasons (`reason=operator-forced` vs `NODE_DRAINED`).

7. **No phase suppression in the table.** `COLD_BOOT` correctness is achieved structurally: at cold-boot, every peer's FSM is in `UNTRACKED`, and `(UNTRACKED, SwimFaulty) = nop` (I5). The only paths that fire writes for an UNTRACKED peer are `UNTRACKED → ON_DUTY` (SWIM-discovered) and `UNTRACKED → JOINING → ON_DUTY` (slot-provisioned), none of which produce `DECOMMISSIONED`. The lifecycle layer cannot emit a spurious failure during cold-boot. The SWIM-layer suppression (G1) is now redundant for correctness but kept for wire efficiency.

8. **`(UNTRACKED, SwimHealthy) → ON_DUTY` direct (Bootstrap-correction 2026-05-12).** SWIM emits an observation only when a peer's state *changes* (e.g., Unknown→Healthy, Healthy→Suspect) — it does **not** periodically re-emit `Healthy`. Routing UNTRACKED through JOINING would require a *second* `SwimHealthy` observation to fire `(JOINING, SwimHealthy) → ON_DUTY`; in production that second observation never arrives, leaving SWIM-discovered peers stranded in JOINING until the 60s `JoinDeadline` timer fires `(JOINING, JoinDeadlineExpired) → DECOMMISSIONED`. Collapsing the intermediate JOINING makes the SWIM-discovered transition self-sufficient: a single observation per peer-state-change is enough. JOINING remains reachable via `(UNTRACKED|PROVISIONING, SlotClaimed) → JOINING` (the slot-provisioning path) and is then completed via `(JOINING, SwimHealthy) → ON_DUTY` — that pair is correct because `SlotClaimed` is a KV-replicated event whose delivery is guaranteed by consensus and the subsequent SWIM observation is a genuine state change. Narrows §3's JOINING semantics: JOINING is **only** reachable via the slot-provisioning path; SWIM-discovered peers transition directly to ON_DUTY.

---

## 6. Leadership transfer

### 6.1 Follower role

Followers maintain a **read-only shadow** of the FSM derived from KV notifications. They observe `NodeLifecycleKey` and `ProvisioningSlotKey` puts/removes and update an in-memory `Map<NodeId, MembershipFsmState>` that mirrors what the leader's FSM would say. This map is consulted by local subsystems (status routes, routing) but **not** mutated by event emission. SWIM observations on followers are dropped (not enqueued anywhere) — they are gossiped to the leader via SWIM's normal multi-source mechanism, where the leader's local SWIM instance ingests them. There is no separate "observation forwarding" wire format.

### 6.2 Leader takeover protocol

When a node becomes leader (via `LeaderElectionFsm.becomeLeader`), it performs synchronous KV replay:

```java
// Pseudocode for MembershipFsm.onLeaderElected(self)
void onLeaderElected(NodeId self) {
    fsmStates.clear();
    slotIdToPeer.clear();

    // 1. Rebuild slot-to-peer mapping
    kvStore.forEach(ProvisioningSlotKey.class, ProvisioningSlotValue.class, (k, v) ->
        v.assignedNodeId().onPresent(nodeId -> slotIdToPeer.put(k.slotId(), nodeId))
    );

    // 2. Reconstruct per-peer FSM state from NodeLifecycleKey
    kvStore.forEach(NodeLifecycleKey.class, NodeLifecycleValue.class, (k, v) ->
        fsmStates.put(k.nodeId(), deriveState(k.nodeId(), v))
    );

    // 3. Cross-reference: peers in slots but not in lifecycle = PROVISIONING (slot-pending)
    //    peers in lifecycle but no slot = ON_DUTY/DRAINING/etc per their atom

    // 4. For any peer in {DRAINING}, re-invoke DrainCoordinator.awaitDrainAck to resume
    //    the drain protocol. Polling resumes from current state; no double-write issues
    //    because Put(L=DRAINING) on already-DRAINING is a no-op at the KV layer.

    // 5. For any peer in JOINING, schedule a fresh one-shot JoinDeadline timer using the
    //    remaining time = max(0, joinDeadlineMs - (nowMs - v.updatedAt())). If already
    //    elapsed, enqueue JoinDeadlineExpired immediately.

    // 6. (Reserved — see §6.4 quorum-loss handling.)

    // 7. Self-bootstrap (Bootstrap-correction 2026-05-12). If the new leader has no
    //    NodeLifecycleKey(self) entry in KV, the leader must write its own ON_DUTY. SWIM
    //    cannot observe self, so the wiring layer (AetherNode) registers a NodeLifecycle
    //    state-change listener that, on transition to NodeState.ACTIVE, calls:
    //        membershipFsm.onSwimObservation(new SwimObservation.HealthyObserved(self, 0L))
    //    The synthetic observation routes through the same SWIM entry point. On the leader,
    //    the reducer cell (UNTRACKED, SwimHealthy) → ON_DUTY writes Put(L=ON_DUTY) for self.
    //    On followers, the leader-write gate inside MembershipFsm drops the synthetic
    //    observation (single-writer invariant) — the leader writes the follower's own entry.
}

private MembershipFsmState deriveState(NodeId peer, NodeLifecycleValue v) {
    return switch (v.state()) {
        case JOINING -> new MembershipFsmState.Joining(peer, v.updatedAt());
        case ON_DUTY -> new MembershipFsmState.OnDuty(peer, v.updatedAt());
        case DRAINING -> new MembershipFsmState.Draining(peer, v.updatedAt());
        case DECOMMISSIONED -> new MembershipFsmState.Decommissioned(peer, v.updatedAt());
        case FAILED_DRAIN -> new MembershipFsmState.FailedDrain(peer, v.updatedAt());
        case SHUTTING_DOWN -> new MembershipFsmState.Draining(peer, v.updatedAt()); // legacy → DRAINING
    };
}
```

### 6.3 Self-leader failure

When the leader itself becomes faulty, a follower will be elected leader. The new leader runs §6.2, sees `NodeLifecycleKey[oldLeader] = ON_DUTY`, observes `SwimFaulty(oldLeader)` from its own SWIM, and fires `(ON_DUTY, SwimFaulty) → DECOMMISSIONED` for the old leader as a normal transition. **There is no special "self-leader-eviction escape hatch"** (the current G4 escape) — the new leader is simply a leader writing about a peer; the old leader, by virtue of having lost the election, is now a peer.

The transition latency budget is bounded by `(leader-election-time) + (one SWIM observation cycle on the new leader)`. Per `project_leader_election_debug.md`, leader-election on Rabia is virtually instantaneous (<200ms). The SWIM observation is in the new leader's local detector and fires within `suspectTimeout` (default 10s; configurable). Total budget: ≤ 11s. This matches or improves the current behaviour, which depends on the escape hatch path being hit before various timeouts elapse.

### 6.4 Quorum loss

If quorum is lost, the leader cannot write KV. The FSM input queue continues to accept events but transitions are deferred — events are queued and the `commandApplier` (consensus apply) fails. On quorum restoration, queued events replay; if the leader is unchanged, transitions resume from the queue; if leadership changed during the outage, §6.2 runs and the queue is discarded (events that were in flight are re-derived from KV state and fresh SWIM observations).

---

## 7. ClusterPhase as a derived view

`ClusterPhase` becomes a **derived projection**, not a separate FSM-replicated atom.

### 7.1 Computation

```java
// MembershipView.computeClusterPhase(stableWindowMs, recoveryStableWindowMs, nowMs)
ClusterPhase computeClusterPhase(long nowMs) {
    int quorum = quorumThreshold(expectedClusterSize); // (N+1)/2, floor 1
    int onDuty = fsmStates.values().stream()
                    .filter(s -> s instanceof OnDuty)
                    .count();
    boolean haveLeader = currentLeader.isPresent();

    if (!everReachedQuorum.get()) {
        if (onDuty >= quorum && haveLeader) {
            return promoteAfterStable(NORMAL, COLD_BOOT, nowMs, stableWindowMs);
        }
        return COLD_BOOT;
    }

    // we have reached NORMAL at least once
    if (onDuty < quorum || !haveLeader) {
        return RECOVERING;
    }
    return promoteAfterStable(NORMAL, RECOVERING, nowMs, recoveryStableWindowMs);
}
```

### 7.2 Storage

**E.6 implementation (KV-as-cache).** `ClusterPhaseKey.SINGLETON` is no longer authoritative. The source of truth is `ClusterPhaseView.compute(nowMs)` — a stateless derivation over per-peer `NodeLifecycleKey` entries (see `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/phase/ClusterPhaseView.java`). The view consults `ClusterPhaseKey` as an **optional cache hint** to track the "ever reached NORMAL" bit across leader takeovers (if the cache holds `NORMAL` or `RECOVERING`, NORMAL was reached at some prior moment; otherwise the cluster is treated as never having reached NORMAL).

Until E.7 the legacy write path is preserved behind the `aether.membership.fsm.shadowEnabled` flag:
1. **Flag off (default).** `HealthReconcilerImpl.proposeClusterPhase` writes `ClusterPhaseKey` exactly as before; the view's cache lookup observes those writes and the derived path matches legacy behaviour. Zero behaviour change.
2. **Flag on.** `HealthReconcilerImpl.proposeClusterPhase` short-circuits (no consensus write). `ClusterPhaseView` is queried directly by:
   - `StatusRoutes.readClusterPhase` (via `ManageableNode.clusterPhaseSupplier()`) — dashboard / CLI.
   - `ClusterTopologyManagerRecord` auto-heal suspension predicate (`phaseSupplier`).
   - `SwimProtocol`'s `isBootingSupplier` (cold-boot suppression gate G1).

   With the flag on the KV atom is stale (no writer); the view's prior-phase cache lookup also returns stale data. This is acceptable for E.6 because the only state that survives leader takeovers via the cache is the one-bit "ever reached NORMAL" flag, and a fresh leader reconstructs it conservatively (defaults to "never"). Operators running with the flag on for the first time will see `COLD_BOOT → NORMAL` on the first leader's stable window even if the prior incarnation had already reached NORMAL; this matches §7.3's "conservative choice" and is a soft signal.

E.7 deletes the legacy writer entirely, after which `ClusterPhaseKey` may be removed from the KV schema (or kept as an FSM-derived cache, written atomically with the lifecycle transitions that cause phase changes — TBD by E.7).

### 7.3 Stability window semantics

A stability window is "the duration after which a satisfied promotion condition becomes effective". Encoded as a per-FSM `stableSinceMs: Option<Long>` field (one per leader, NOT per peer). Reset to `none()` on any peer-FSM transition that violates the quorum condition. Set to `some(nowMs)` on the first transition that satisfies it. Promotion fires on the next event-driven re-evaluation when `nowMs - stableSinceMs >= window`. If no event arrives within the stability window (a degenerate quiet-cluster case), the leader schedules a **one-shot timer** for `stableSinceMs + window` to re-evaluate; this timer is cancelled and re-scheduled on every relevant transition. There is no periodic tick.

`stableSinceMs` is **not** stored in KV — it is recomputed on leader takeover by inspecting `NodeLifecycleKey[*].updatedAt()` for the latest write that brought the cluster to quorum, and using that timestamp as the start of the stability window. If no such write exists (i.e., the cluster has been at quorum since before this leader took over), `stableSinceMs = leaderTakeoverMs`. This is a conservative choice — it may delay a `RECOVERING → NORMAL` promotion by up to `recoveryStableWindowMs` after a leader change, which is acceptable because that promotion is a soft signal (lifts CTM auto-heal suspension, etc.), not a correctness gate.

---

## 8. DrainCoordinator integration

`ConsensusDrainCoordinator` (§D.5 implementation, `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/drain/ConsensusDrainCoordinator.java`) is reused **unchanged in its core logic** — it remains the right abstraction for "wait for inflight=0 and lifecycle=DRAINING". The integration point shifts:

### 8.1 Before (current)

`NodeLifecycleRoutes.handleDrain` calls `coordinator.prepareDrain → awaitDrainAck → markDrainComplete`, with each step writing KV directly via the `LifecycleWriter` interface. The route handler owns the protocol.

### 8.2 After (FSM-owned)

The FSM owns the transitions. The drain protocol becomes:

```java
// On (ON_DUTY|JOINING, OperatorDrain) → DRAINING transition entry:
void onEnterDraining(NodeId peer, DrainReason reason, long nowMs) {
    // KV write is already done by the transition (Put(L=DRAINING) in the batch).
    drainCoordinator.awaitDrainAck(peer, drainTimeout)
        .onSuccess(_ -> fsm.enqueue(new DrainOutcome(peer, true, nowMs())))
        .onFailure(_ -> fsm.enqueue(new DrainOutcome(peer, false, nowMs())));
}
```

`DrainOutcome(success=true)` fires `(DRAINING, DrainOutcome) → DECOMMISSIONED`. `DrainOutcome(success=false)` fires `(DRAINING, DrainOutcome) → FAILED_DRAIN`. `DrainCoordinator` owns the hard-deadline timeout internally (`drainHardDeadlineMs`); on timeout it resolves the `Promise` with `success=false`, producing the `DrainOutcome(false)` event. **The FSM does not schedule any drain timer of its own** — there is exactly one timer for the `DRAINING` state and it lives in `DrainCoordinator`.

### 8.3 `prepareDrain` becomes the transition's KV write itself

The current `coordinator.prepareDrain(peer)` writes `Put(L=DRAINING)` via `LifecycleWriter.requestDrain`. With the FSM owning lifecycle writes, this becomes redundant — the transition into `DRAINING` already does the write. `prepareDrain` is therefore **removed from the public DrainCoordinator interface** and inlined as a no-op (or removed entirely). The interface shrinks to `awaitDrainAck` and `markDrainComplete` (the latter is also removed because `DECOMMISSIONED` is the FSM transition's own write).

Net result: `DrainCoordinator` becomes a one-method interface — `Promise<Boolean> awaitDrainAck(NodeId, TimeSpan)` — returning success/failure rather than `Promise<Unit>` that the FSM threads into a `DrainOutcome` event.

### 8.4 Backward compatibility

`/api/node/drain/{nodeId}` REST behaviour is preserved: returns 200 on success, 503 on failure, same response schema. The route handler enqueues `OperatorDrain` into the FSM and `await`s the resulting state change (subscribing to the FSM's state-change stream via `KVStoreNotification` on `NodeLifecycleKey[peer]`). This is consistent with I7 (effects flow through KV).

---

## 9. Migration plan

Ordered layers. Each layer is one PR / commit, has its own regression tests, and leaves the system in a deployable state. The system continues to pass integration tests after each layer; the only layer that may temporarily regress test counts is E.7 (the deletion of the old gates), because some tests assert against gate behaviour and must be rewritten.

### E.2 — Scaffold the FSM types and event queue (no behaviour change)

**Adds.**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsmState.java` (sealed interface, 7 records).
- `MembershipFsmEvent.java` (sealed interface, 8 events).
- `MembershipFsm.java` (skeleton: empty event queue, `transition()` returns input state, no KV writes).
- Unit tests for transition table totality (every cell exercises a single transition).

**Removes.** Nothing.

**Test impact.** No integration test changes. Module tests: +1 transition-table test, +1 enum-mapping test.

### E.3 — Wire the FSM to KV reads (read-only shadow on followers)

**Adds.**
- `MembershipFsm.onLeaderElected(self)` → KV replay (§6.2 steps 1-3).
- `MembershipFsm.onKvNotification(notification)` → maintains `fsmStates` map on followers (read-only).
- `MembershipView` public read API: `Option<MembershipFsmState> get(NodeId)`, `Map<NodeId, MembershipFsmState> snapshot()`.

**Removes.** Nothing.

**Test impact.** New module test: KV replay yields correct states for fixture KV snapshots. Integration tests unaffected (the FSM is parallel-read; doesn't write yet).

### E.4 — Make the FSM the writer for `OperatorDrain` and `OperatorDecommission`

**Adds.**
- `LifecycleWriter` implementation in `MembershipFsm` (`requestDrain`, `requestDecommission`, `requestActivate`, `requestFailedDrain` enqueue events instead of writing KV directly).
- `MembershipFsm` event queue actually applies transitions and writes KV on the leader.
- One-shot join-deadline timer scheduling on `JOINING` entry; cancellation on `JOINING` exit.

**Removes.** Nothing yet — the old `HealthReconcilerImpl.requestDrain` etc. are still wired but bypass-able. Feature-flag at module-boot: `membership.fsm.enabled=true` (default `false` for this layer).

**Test impact.** With feature-flag on, `/api/node/drain` exercises the FSM path. New module test: drain protocol fires `DrainOutcome` correctly; join-deadline timer fires `JoinDeadlineExpired` and transitions to `DECOMMISSIONED`. Existing tests still green with feature-flag off.

### E.5 — Move SWIM observation routing through the FSM

**Adds.**
- `MembershipFsm.onSwimObservation(observation)` — translates to `SwimHealthy`/`SwimFaulty`/`SwimDeparted` events and enqueues.
- The FSM handles the `(ON_DUTY, SwimFaulty)` transition that emits `DECOMMISSIONED` and `NODE_FAILED`.
- The FSM handles the `(JOINING, SwimHealthy) → ON_DUTY` transition — **leader-initiated**, replacing the legacy self-write path entirely.

**Removes.** **Nothing yet.** Both paths run in parallel with the feature flag controlling which one writes KV. The old `HealthReconcilerImpl.handleAggregatedEdge` is still active but its writes are observed-only (no-op if FSM already wrote the same state — relies on KV `Put` idempotence).

**Test impact.** Enable feature flag on docker-remote suite 02-chaos. Run `test-kill-node.sh` end-to-end. **Acceptance gate: the smoking-gun test from §1.1 passes deterministically.** If it does not, abort migration here and diagnose.

### E.6 — Cut over `ClusterPhase` to derived computation

**Adds.**
- `MembershipView.computeClusterPhase(nowMs)` per §7.1.
- FSM batches `Put(ClusterPhaseKey)` into the same `commandApplier.apply(...)` call as the lifecycle write that triggered the phase change.
- One-shot timer for stability-window re-evaluation (§7.3) when no transitions arrive within the window.

**Removes.**
- `HealthReconcilerImpl.evaluatePhaseTransition`, `coldBootTarget`, `recoveringTarget`, `promoteAfterStable`, `resetStableMarker`, `schedulePhaseEvaluationTick`, `onPhaseEvaluationTick`.
- `HealthReconcilerImpl.currentPhase` AtomicReference (read it from KV via `phaseReader`).
- `HealthReconcilerImpl.stableSinceMs` AtomicLong (moved into FSM as per-leader transient field).
- `phaseEvaluationInterval` config (no longer needed; phase updates fire atomically with the underlying lifecycle write).

**Test impact.** `HealthReconcilerTest` and `ClusterPhaseSmokeTest` are partially rewritten — assertions on AtomicReference state become assertions on `MembershipView.computeClusterPhase` output. `phaseEvaluationInterval` config removal requires removing the test wiring at `HealthReconcilerImpl#L147` (test-only `immediateRetryScheduler` path becomes the production path).

### E.7 — Delete the gate stack

**Removes.**
- `ObservationAggregator.java` (entire file).
- `HealthReconcilerImpl.handleAggregatedEdge` (entire method).
- `HealthReconcilerImpl.suppressedByPhase` (G6 — redundant with §5 transition table).
- `HealthReconcilerImpl.cooldownActive` and `lastWriteAt` map (G5 — redundant with idempotent transitions, I3).
- `HealthReconcilerImpl.aggregateEdge` and `aggregatorLock`.
- The **entire legacy self-promotion path** in `HealthReconcilerImpl`: `attemptSelfOnDutyWrite`, `proposeSelfOnDutyWrite`, `evaluateSelfPromotion`, `promoteSelfToOnDuty`, `signalSelfReady`, `handleSelfOnDutyFailure`, `isTransientInactiveRejection`, `computeBackoffDelay`, `selfOnDutyAtomFactory` field, `selfReady` `AtomicBoolean`, `selfPromoted` `AtomicBoolean`, `MAX_SELF_ONDUTY_RETRIES`, `INITIAL_SELF_ONDUTY_RETRY_DELAY_MS`, `MAX_SELF_ONDUTY_RETRY_DELAY_MS` constants. Replaced by the leader-initiated `(JOINING, SwimHealthy) → ON_DUTY` transition.
- The `SelfOnDutyAtomFactory` interface and its `defaultSelfOnDutyAtomFactory()` factory method in `HealthReconciler.java` (no consumers remain).
- The `HealthReconcilerSelfOnDutyAtomTest.java` test file (~200 LOC) — entire self-promotion test surface is obsolete.
- The leader-unknown escape hatch (current G4 second branch).
- The self-leader-eviction escape hatch (current G4 first branch).
- D.2 `effectiveThreshold` asymmetric quorum (no longer needed).
- The Option-B "DECOMMISSIONED threshold=1" workaround.

**Reverts.**
- The D.2 commit on `ObservationAggregator` (asymmetric threshold): identify with `git log --oneline -- aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/health/ObservationAggregator.java` and revert the asymmetric-threshold commit; the file is deleted anyway, but the revert simplifies merge history.
- The leader-unknown escape hatch commit in `HealthReconcilerImpl` (identifier: search `git log -S "leader unknown"`).

**Adds.** Nothing (this layer is pure deletion).

**Test impact.** `ObservationAggregatorTest` is deleted. `HealthReconcilerSelfOnDutyAtomTest` is deleted. `HealthReconcilerTest` shrinks substantially. Net LOC: −1000, +0.

### E.8 — Switch feature flag to `true` by default and remove the flag

**Adds.**
- Default value of `membership.fsm.enabled` changes to `true`.

**Removes.**
- The feature flag itself (and all `if (membershipFsmEnabled) { ... } else { ... }` branches).

**Test impact.** Full integration suite run on docker-remote. Acceptance gate: ≥ 14/15 suites green (allowing one transient flake), with **all 4 chaos suites (`02-chaos/test-kill-*`) deterministically green**.

### E.9 (optional, post-RC1) — Cross-node SWIM observation gossip

The current FSM design treats the leader's local SWIM detector as authoritative. This is correct but throws away information: peers also have SWIM views, and on WAN-jittery links a non-leader peer may detect a failure faster than the leader. Post-RC1, we can add `SwimObservation` gossip over the consensus-membership channel: followers forward their SWIM observations to the leader, which feeds them as additional `SwimFaulty(peer, observerNode)` events into the FSM. Threshold quorum can be reintroduced **as an explicit FSM precondition on the transition rule** (not as a gate on top): `(ON_DUTY, SwimFaulty) → DECOMMISSIONED` fires only if `distinctObservers(peer, Faulty) >= quorum`. This is the design that the current `ObservationAggregator` was reaching for — but as a clean FSM precondition, not a stacked gate.

E.9 is **out of scope for RC1.** Capturing it here so the design is forward-compatible.

---

## 10. Invariants and tests

### 10.1 Implementation invariants

| Invariant | Enforcement | Test |
|---|---|---|
| I1 (KV-reconstructible) | `MembershipFsm.onLeaderElected` is the only way state appears; no setter on `fsmStates` outside the event queue | `LeaderTakeoverReplayTest`: write 50 random KV snapshots, force leader-election, assert FSM state matches `deriveState` for every peer |
| I2 (single-writer) | `transition()` is `private`; followers' FSM is a `ReadOnlyMembershipFsm` subclass that throws on `enqueue()` | `FollowerWriteRejectedTest`: assert NPE/IllegalState when a non-leader's FSM is asked to fire a transition |
| I3 (idempotent) | `commandApplier.apply` deduplicates `Put(K, V)` where current value already equals V | `IdempotentTransitionTest`: feed same event 100 times, assert exactly one KV write commits |
| I4 (totality) | The `switch` over `(State, Event)` is `default → throw new IllegalStateException(...)` | `TransitionTableTotalityTest`: parametric over all 56 cells, exercises every cell with a randomized prior-state setup |
| I5 (bootstrap-safe) | `(UNTRACKED, SwimFaulty) → nop` is explicit in the transition table | `ColdBootIgnoreFaultyTest`: bootstrap a cluster with `expectedClusterSize=5`, fire `SwimFaulty(peer-2)` before any `SwimHealthy`, assert no KV write |
| I6 (event-driven) | All deadlines route through one-shot timers that enqueue discrete events; no periodic schedulers in the FSM module | `OneShotTimerIsolationTest`: instantiate FSM with a `ManualScheduler` test double; assert that no transitions fire until either an external event is enqueued or a scheduled one-shot timer is advanced to its fire time |
| I7 (effects = KV) | `MembershipFsmEvent` has no `Consumer` callback field; effects are returned as `List<KVCommand>` | `NoSideChannelTest`: build FSM with `commandApplier` that throws on every call; assert no observable behaviour change in downstream subsystems (they observe nothing because nothing was written) |

### 10.2 Concurrency model

The FSM is **strictly single-threaded** for transitions on the leader. Events from multiple sources (REST handlers, SWIM observation thread, KV-notification thread, fired one-shot timers) are serialized into a single `BlockingQueue<MembershipFsmEvent>`. One `dequeue → transition → applyKV → emit` worker thread processes the queue. This eliminates the need for `aggregatorLock`, `phaseListenerLock`, and the various `AtomicReference` fields in the current code.

**Timers.** All FSM timers are **one-shot**, scheduled via `SharedScheduler.schedule(TimeSpan)` on transition *entry* and cancelled on transition *exit*. The FSM module does not own any periodic scheduler. Categories:

- `JoinDeadline(peer)` — scheduled on `JOINING` entry; on fire, enqueues `JoinDeadlineExpired(peer)`. Cancelled on any exit from `JOINING`.
- `StabilityWindow` — scheduled in §7.3 when a stability window has been entered and no further transitions are expected within the window. Cancelled / re-scheduled whenever a transition would change the result of the stability check.

Drain hard-deadline is **not** an FSM timer — it lives inside `DrainCoordinator` (§8.2) and surfaces to the FSM as a `DrainOutcome(success=false)` event.

REST handlers `await` the resulting state change by subscribing to `KVStoreNotification[NodeLifecycleKey(peer)]` before enqueueing the event, using a single-shot `Promise<Unit>` that resolves on the first matching notification. Timeout on the await is the operator's request timeout (default 60s) — independent of any FSM-internal deadlines.

### 10.3 Test patterns (paste-ready)

```java
// AbstractFsmTransitionTest provides:
//   void given(MembershipFsmState initial);
//   void when(MembershipFsmEvent event);
//   void thenState(Class<? extends MembershipFsmState> expected);
//   void thenWritten(KVCommand<AetherKey>... commands);
//   void thenNoOp();

@Test void onDuty_swimFaulty_writesDecommissionedAndEmitsNodeFailed() {
    given(new OnDuty(peer1, 1000L));
    when(new SwimFaulty(peer1, 7L, 2000L));
    thenState(Decommissioned.class);
    thenWritten(Put(NodeLifecycleKey(peer1), L=DECOMMISSIONED));
    thenEmitted(NODE_FAILED);
}

@Test void untracked_swimFaulty_isNoOp() {
    given(new Untracked(peer1));
    when(new SwimFaulty(peer1, 7L, 2000L));
    thenNoOp();
}

@Test void draining_drainOutcomeSuccess_writesDecommissioned() {
    given(new Draining(peer1, 1000L));
    when(new DrainOutcome(peer1, true, 2000L));
    thenState(Decommissioned.class);
    thenWritten(Put(NodeLifecycleKey(peer1), L=DECOMMISSIONED));
    thenEmitted(NODE_DRAINED);
}

@Test void joining_joinDeadlineExpired_writesDecommissioned() {
    given(new Joining(peer1, 1000L));
    when(new JoinDeadlineExpired(peer1, 62_000L));
    thenState(Decommissioned.class);
    thenWritten(Put(NodeLifecycleKey(peer1), L=DECOMMISSIONED));
    thenEmitted(NODE_FAILED);
}

// Property test
@Property void anyEventOnUntrackedNeverWritesDecommissioned(
        @ForAll MembershipFsmEvent event) {
    assumeThat(event, instanceOf(SwimHealthy.class).or(SwimFaulty.class).or(SwimDeparted.class)
                            .or(OperatorDrain.class).or(JoinDeadlineExpired.class));
    given(new Untracked(event.peer()));
    when(event);
    assertThat(writes, not(contains(commandWith(L=DECOMMISSIONED))));
}
```

---

## 11. Risks and unknowns

### R1. SWIM gossip convergence latency on WAN

**Concern.** §5 note 2 says "the leader's local SWIM detector is authoritative". On a 5-node cluster where the leader's link to the victim is the *slowest* link, the leader may detect Faulty 5-10s later than a follower. With cross-node observation gossip absent (E.9 is post-RC1), the FSM's failure-detection latency is bounded by the leader's local SWIM latency.

**Mitigation.** SWIM's default `suspectTimeout=10s` combined with the leader's 4-way k-random probe ring means worst-case detection is ~15s on the leader. This matches the existing observed behaviour (§1.1 timeline) — the FSM is not slower than the current code; it just removes the spurious gates that prevented the write *after* detection. If WAN latency proves problematic in operator testing, E.9 (cross-node SWIM gossip) is the structural fix.

**Tangential consideration.** The current code's `quorumThreshold` was supposed to address this by demanding multiple observers agree — but as §1 showed, it never actually had multiple observers, so the quorum was a fiction. The FSM is *more honest* about being a single-observer system, which is itself an improvement.

### R2. Leadership-transfer race during chaos

**Concern.** Consider: leader L0 detects Faulty(peer-3), starts `Put(L=DECOMMISSIONED)`, the consensus round is in-progress, L0 itself dies before commit. New leader L1 elected; runs §6.2 replay; sees `NodeLifecycleKey[peer-3] = ON_DUTY` (because L0's write never committed). L1's local SWIM has not yet detected peer-3 as Faulty (L0 was the first to notice). FSM is in `(ON_DUTY, no-event)` for peer-3 for up to `suspectTimeout` seconds on L1. During that window, routing still considers peer-3 alive — wrong, but transient.

**Mitigation.** Acceptable for RC1. The window is bounded by `suspectTimeout` (10s default). Routing failures during the window manifest as request timeouts on traffic to peer-3, which the routing layer's own retry logic handles. We do not need to engineer cross-leader observation handoff for RC1 (would require persisting SWIM observation state into KV, which has its own correctness risks).

**Tangential consideration.** A more robust design would persist *pending observations* into KV — but this adds load and complexity. The pragmatic call is: leadership-transfer windows are short and rare; the cost of perfect handoff is not worth paying yet.

### R3. ProvisioningSlot KV listener ordering vs NodeLifecycleKey writes

**Concern.** Suppose node N starts up and writes `Put(L=JOINING)` (its self-registration); the leader was simultaneously about to `Put(L=DECOMMISSIONED)` for the slot's previous occupant. KV-store delivers notifications in commit order, but if the leader's FSM consumes both notifications in the same dispatch tick, the order matters: `SlotClaimed(N) → JOINING` then `(JOINING, SwimFaulty)` may incorrectly transition N to `DECOMMISSIONED` instead of the previous occupant.

**Mitigation.** KV notifications carry the **keyed nodeId** (`NodeLifecycleKey.nodeId()`), and `SwimFaulty(peer)` carries `peer`. The FSM dispatches events to per-peer state machines keyed by `NodeId`. There is no cross-peer state coupling. The scenario above splits into two independent transitions: peer-N is in `UNTRACKED → JOINING`, peer-prev is in `ON_DUTY → DECOMMISSIONED`. No race.

**Tangential consideration.** If a slot is **re-used** (same `slotId`, different `NodeId` over time), the slot-to-peer mapping must update on `Remove(ProvisioningSlotKey)` to clear the old entry before `Put` of the same key with a new `assignedNodeId`. Verify `slotIdToPeer.remove(slotId)` is wired on `Remove` notifications.

### R4. One-shot timer cancellation correctness

**Concern.** Per I6 and §10.2, every join-deadline timer must be cancelled on `JOINING` exit. If cancellation is missed (e.g., due to a race between the scheduler firing and the cancellation request), a stale `JoinDeadlineExpired(peer)` may be enqueued for a peer that is already in `ON_DUTY` or `DECOMMISSIONED`.

**Mitigation.** Stale-fire is harmless by construction: the transition table maps `JoinDeadlineExpired` to `nop` in every state other than `JOINING`. The FSM does not need to track which timers are "live" — the event itself is idempotent against all non-`JOINING` states. Tests: `StaleJoinDeadlineTest` exercises the fire-after-promotion path explicitly.

**Tangential consideration.** A common implementation bug in one-shot timer code is *re-arming on idempotent re-entry* — e.g., receiving `SwimHealthy` on an already-`JOINING` peer and re-scheduling the join timer. The transition table makes this explicit: the `JOINING, SlotClaimed` cell is `nop`, and so is the `JOINING, SwimHealthy` re-entry idempotence (a leader observing already-`ON_DUTY` peer); the timer is scheduled only on the `entry edge` into `JOINING`, not on every event handled while in `JOINING`. Implementation: gate timer scheduling on the predicate `prevState != JOINING && newState == JOINING`.

### R5. `DecommissionedAtomGc` interaction

**Concern.** The FSM transitions `DECOMMISSIONED → UNTRACKED` is not in the table because it's not an FSM-driven transition — it's a side effect of `DecommissionedAtomGc.run()` deleting the KV atom. If the GC fires between the leader's `(ON_DUTY, SwimFaulty) → DECOMMISSIONED` write and the follower's KV notification arriving, a follower could observe a `Remove(NodeLifecycleKey)` for a peer it has never seen as `DECOMMISSIONED`. Followers' shadow FSMs would jump directly from `ON_DUTY` to `UNTRACKED`.

**Mitigation.** Followers' shadow FSMs are read-only and do not enforce transition rules — they accept any state implied by KV. The leader's FSM is the rule-enforcer, and the leader is the one that wrote `DECOMMISSIONED` in the first place, so its FSM is consistent. Add a test: `DecommissionedGcRaceTest` exercises this scenario explicitly.

### R6. `SHUTTING_DOWN` legacy enum value

**Concern.** Existing KV rows in production / persistent dev environments may contain `SHUTTING_DOWN`. The FSM `deriveState` maps it to `Draining`. If the FSM then writes a fresh value, that value will be `DRAINING`, not `SHUTTING_DOWN` — a one-way migration. Downstream code that special-cases `SHUTTING_DOWN` (search via `grep -r SHUTTING_DOWN aether/`) needs review.

**Mitigation.** Audit all `NodeLifecycleState.SHUTTING_DOWN` references before E.7. Any branch that treats `SHUTTING_DOWN` differently from `DRAINING` is a bug to fix (or a feature to preserve by introducing an explicit FSM state — but we are choosing to fold them).

---

## 12. What this eliminates

Explicit deletion inventory. Numbers are LOC estimates.

| Component | Path | LOC | Reason |
|---|---|---|---|
| `ObservationAggregator` entire class | `aether/aether-deployment/.../health/ObservationAggregator.java` | 211 | Replaced by FSM transition table; the cross-node threshold becomes a precondition in E.9, not a gate |
| `HealthReconcilerImpl.handleAggregatedEdge` | `health/HealthReconcilerImpl.java:264-297` | ~35 | Replaced by FSM `transition()` |
| `HealthReconcilerImpl.suppressedByPhase` (G6) | `health/HealthReconcilerImpl.java:304-307` | 4 | Redundant with I5 (UNTRACKED + Faulty = nop) |
| `HealthReconcilerImpl.cooldownActive` and `lastWriteAt` (G5) | `health/HealthReconcilerImpl.java:309-312, 69` | ~5 | Redundant with I3 (idempotent transitions) |
| `HealthReconcilerImpl.aggregateEdge` and `aggregatorLock` | `health/HealthReconcilerImpl.java:257-262, 65` | ~7 | No aggregator anymore |
| Leader-unknown escape hatch (G4) | `health/HealthReconcilerImpl.java:266-281` | ~16 | Replaced by §6.2 leader takeover protocol |
| Self-leader-eviction escape hatch (G4) | same region | (counted above) | Replaced by §6.3 — new leader writes about old leader as a normal peer |
| `effectiveThreshold` D.2 asymmetric quorum | `health/ObservationAggregator.java:180-182` | 3 | File deleted |
| **Legacy self-promotion path** — `attemptSelfOnDutyWrite`, `proposeSelfOnDutyWrite`, `evaluateSelfPromotion`, `promoteSelfToOnDuty`, `signalSelfReady`, `handleSelfOnDutyFailure`, `isTransientInactiveRejection`, `computeBackoffDelay` methods; `selfOnDutyAtomFactory` field; `selfReady` and `selfPromoted` `AtomicBoolean` fields; `MAX_SELF_ONDUTY_RETRIES`, `INITIAL_SELF_ONDUTY_RETRY_DELAY_MS`, `MAX_SELF_ONDUTY_RETRY_DELAY_MS` constants | `health/HealthReconcilerImpl.java` (Q1 decision, 2026-05-12) | ~120 | Folded into leader-initiated `(JOINING, SwimHealthy) → ON_DUTY` transition — no self-write |
| `SelfOnDutyAtomFactory` interface + `defaultSelfOnDutyAtomFactory()` factory | `health/HealthReconciler.java` (Q1 decision, 2026-05-12) | ~20 | No callers; deleted with self-promotion path |
| `HealthReconcilerSelfOnDutyAtomTest` test file | `health/HealthReconcilerSelfOnDutyAtomTest.java` (test, Q1 decision, 2026-05-12) | ~200 | Entire self-promotion test surface obsolete |
| `evaluatePhaseTransition` family | `health/HealthReconcilerImpl.java:397-460` | ~65 | Replaced by §7 derived view |
| `phaseListeners`, `addPhaseListener`, `notifyListener` | `health/HealthReconcilerImpl.java:81, 391-395, 485-490` | ~12 | Replaced by KV notifications on `ClusterPhaseKey` (existing consumer code already supports both paths) |
| `currentPhase`, `stableSinceMs` AtomicRef/Long | `health/HealthReconcilerImpl.java:73, 75` | 2 | I7 (no in-memory mirroring of KV) |
| `phaseEvaluationInterval` config + scheduling | `HealthReconcilerConfig.java`, `health/HealthReconcilerImpl.java:147-158` | ~12 | Phase updates fire atomically with lifecycle writes |
| ~~`DrainCoordinator.prepareDrain` and `markDrainComplete`~~ — **RETAINED post-E.8** | `drain/DrainCoordinator.java` | n/a | The interface methods remain: they are direct `LifecycleWriter` calls invoked by `MembershipFsm.invokeDrain` (which still uses the coordinator's protocol orchestration — `prepareDrain → awaitDrainAck → markDrainComplete` per D.5). Spec §8.3's "removal" intent is superseded: the FSM owns *which transitions* trigger drain writes, but the coordinator remains the protocol orchestrator. Three live consumers: `MembershipFsm`, `ConsensusDrainCoordinator` impl, `NodeLifecycleRoutes`. |
| ~~`LegacyLifecycleWriterFixture` test fixture~~ — **RETAINED post-E.8** | `cluster/LegacyLifecycleWriterFixture.java` (test) | n/a | Used by 8 CTM tests as a direct-write mock; not actually "legacy" post-E.8 — it mimics `LifecycleWriter.directLifecycleWriter`'s production shape. Rename pending (future cleanup): drop "Legacy" prefix. No functional removal needed. |

**Estimated net LOC change**: −1000 production, +600 production (FSM + state records + event types) = **−400 net LOC**. Test code: −600 (deleting aggregator/health/self-onduty tests), +700 (FSM transition tests + property tests + KV replay tests) = +100 net LOC. **Total: −400 production, +100 tests = net −300 LOC, with the gate-stack class of bugs and the self-write race eliminated.**

---

## Decided

The three previously-open design questions have been resolved on **2026-05-12**:

- **Q1 → A (2026-05-12)** — *Leader-initiated `JOINING → ON_DUTY`*. The leader's FSM observes `SwimHealthy(peer)` and writes `Put(L=ON_DUTY)`. There is no self-write path on the joining node. The entire self-promotion machinery in `HealthReconcilerImpl` (`attemptSelfOnDutyWrite` and its support cast — see §12 deletion inventory) is deleted in E.7, along with the `SelfOnDutyAtomFactory` interface and `HealthReconcilerSelfOnDutyAtomTest` test file. Rationale: the consensus-admission vs lifecycle-write race that self-write was working around is structurally eliminated because the leader's SWIM cannot observe a peer outside the consensus active set — by the time the leader writes `ON_DUTY`, the peer is fully admitted by definition.
- **Q2 → A (2026-05-12)** — *Direct `ON_DUTY → DECOMMISSIONED` on `force=true`*. `OperatorDecommission(force=true)` from `ON_DUTY` is its own FSM transition: a single atomic KV write of `Put(L=DECOMMISSIONED)`, no `DrainCoordinator` invocation. By contrast, `OperatorDecommission(force=false)` from `ON_DUTY` routes through `DRAINING` and invokes `DrainCoordinator` as usual. Force and graceful drain share an end state but are semantically distinct operations; the audit trail distinguishes operator-forced (`reason=operator-forced`) from drain-then-decommissioned (`NODE_DRAINED`).
- **Q3 → C (2026-05-12)** — *Tick eliminated; FSM is purely event-driven (I6)*. The `Tick` event is removed from the event vocabulary. All deadlines are enforced by **one-shot timers** scheduled on transition entry and cancelled on transition exit. Specifically: the join deadline becomes the `JoinDeadlineExpired(peer)` event, scheduled on `JOINING` entry; the drain hard-deadline is owned by `DrainCoordinator` and surfaces as `DrainOutcome(success=false)`. The FSM owns no periodic scheduler. Rationale: `Tick` was originally a defensive polling layer to mask event-delivery bugs. The I6 invariant (event-driven, not poll-driven) makes that defense structurally untenable. Every event source is now required to have explicit delivery guarantees. If a future bug drops events, the fix is to repair the event source, not to add polling on top.
- **Bootstrap-correction 2026-05-12** — *SWIM emission is one-shot; `(UNTRACKED, SwimHealthy) → ON_DUTY` direct; self-bootstrap via NodeLifecycle ACTIVE listener*. Surfaced by E.8 integration validation on TARGET_HOST: followers were stranded in JOINING for the full 60s `JoinDeadline` window then transitioned to DECOMMISSIONED, because the FSM expected *two* `SwimHealthy` events per peer (UNTRACKED→JOINING then JOINING→ON_DUTY) while SWIM only emits an observation per peer-state-change. Two changes: (1) `(UNTRACKED, SwimHealthy) → ON_DUTY` direct — JOINING remains reachable only via the slot-provisioning path (`(UNTRACKED\|PROVISIONING, SlotClaimed) → JOINING`); (2) self-bootstrap — the leader's `NodeLifecycleKey(self)` is now written by a synthetic `SwimHealthy(self)` observation injected when this node's `NodeLifecycle` transitions to `NodeState.ACTIVE`. The synthetic observation routes through the existing leader-write gate, so it has no effect on followers (the leader writes the follower's own ON_DUTY entry). No new self-write path: the leader still owns every `NodeLifecycleKey` write, including the leader's own entry.

**Deferred (post-RC1):**
- **[TBD]** E.9 cross-node SWIM gossip — full design deferred to a separate spec post-RC1.

---

## References

### Internal documents
- `aether/docs/specs/membership-architecture-spec.md` (v2) — parent architecture; this spec implements its Layer 2 cleanly
- `aether/docs/internal/progress/session-handover-2026-05-11b.md` — D.3 + D.5 commit landing context
- `aether/docs/internal/progress/session-handover-2026-05-10b.md` — green-sticker remediation campaign that produced the gate-stack symptoms
- `aether/docs/contributors/evolutionary-implementation.md` — methodology for the E.2–E.8 layered rollout

### Codebase references (style precedent)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/node/fsm/NodeDeploymentState.java` — sealed-interface FSM style; per-state records with `onEntry`/`handle`/`TransitionRequest` precedent
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/NodeReconcilerState.java` — minimal sealed-state record style
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/drain/ConsensusDrainCoordinator.java` — D.5 drain protocol; integrates via §8
- `integrations/swim/src/main/java/org/pragmatica/swim/SwimObservation.java` — event-source sealed hierarchy

### Codebase references (target of replacement)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/health/HealthReconcilerImpl.java` — primary deletion target
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/health/ObservationAggregator.java` — full deletion in E.7
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/health/HealthReconciler.java` — `SelfOnDutyAtomFactory` interface + factory deleted in E.7 (Q1)
- `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/health/HealthReconcilerSelfOnDutyAtomTest.java` — deleted in E.7 (Q1)
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java#L591` — `NodeLifecycleState` enum (preserved unchanged; FSM does not modify KV schema)

### External / standards
- "Lifeguard: Local Health Awareness for More Accurate Failure Detection" (Dadgar et al., 2018) — SWIM improvements; relevant to R1 SWIM convergence concerns
- "In Search of an Understandable Consensus Algorithm" (Ongaro & Ousterhout, 2014) — Raft single-writer-via-leader pattern; this spec applies the same principle to membership writes

### User memory
- `feedback_structural_over_tactical.md` — justification for the rewrite
- `feedback_no_quick_hacks_for_complex_issues.md` — escalation policy
- `project_single_writer_rule_scope.md` — single-writer for KV atoms (this spec's I2)
- `feedback_architectural_guidelines.md` — state reconstructible from KV-Store (this spec's I1)
