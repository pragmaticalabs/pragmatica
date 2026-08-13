# Durable Single-Writer Entity — Design Specification

*The primitive for durable workflows & sagas.*

**Version:** 0.4.0
**Status:** Draft — **decision-complete** (author-facing API pinned; all §14 sign-off items resolved). v0.4.0 reconciles §5.1/§5.3 with the shipped surface (#432): the error hierarchy is `DurableEntityError`, not `EntityCause`, and the pinned six-case set was incomplete — see §5.3. v0.3.0 closed S1/S2/S4/S5 (signals in v1 §6.6, retention defaults, C hidden, per-call read consistency §8.1). See changelog.
**Date:** 2026-06-27 (updated 2026-08-13)
**Author:** design-stream
**Epic:** #345
**Supersedes:** #190 (the Persistent-Workflow draft is carried forward here as the *workflow specialization*, §6)
**Depends on:** the per-key ownership fence (epic #345, piece 1) + persistent storage backing (epic #349)
**Related:** #265/#261 (streaming substrate option), #268 (resource lifecycle)

---

## Table of Contents

1. [Overview & Goals](#1-overview--goals)
2. [The layering & dependency chain](#2-the-layering--dependency-chain)
3. [Current Substrate (verified)](#3-current-substrate-verified)
4. [Architecture — the durable entity](#4-architecture--the-durable-entity)
5. [The `DurableEntity` API](#5-the-durableentity-api)
6. [Workflow specialization (supersedes #190)](#6-workflow-specialization)
7. [Saga specialization](#7-saga-specialization)
8. [Execution Semantics](#8-execution-semantics)
9. [Failure Model](#9-failure-model)
10. [Side Effects](#10-side-effects)
11. [Substrate dependencies (epic pieces)](#11-substrate-dependencies)
12. [Reconciliation to Existing Code](#12-reconciliation-to-existing-code)
13. [Implementation Phases](#13-implementation-phases)
14. [Owner Decisions Still Needed](#14-owner-decisions-still-needed)
15. [References](#15-references)

---

## 1. Overview & Goals

### 1.1 Purpose

Provide **one** foundational primitive — a **durable, single-writer, scalable entity** — and express
durable **workflows** and **sagas** as thin specializations of it. An entity is a keyed object with
durable state, mutated by exactly one fenced writer at a time, placed across the cluster by partition.
The slice author writes business logic; the runtime owns placement, fencing, durability, and
serialization.

### 1.2 The reframe — from "workflow engine" to "durable entity primitive"

Issue #190 proposed a Persistent-Workflow resource. Analysis showed its correctness rests entirely on a
**per-key single-writer fence** that does not exist yet (governor/stream/partition ownership is
advisory HRW, unchecked — epic #345). That fence is **substrate-independent** and needed by *any*
durable single-writer feature. Once it exists, a workflow is just *"an entity whose update is an FSM
transition,"* and a saga is *"an entity that orchestrates steps with compensation."* So the right unit
is the **entity**, with workflow and saga as specializations — not two separate engines.

> **Decision.** Build a `DurableEntity` primitive; make `PersistentWorkflow` (#190) and `Saga`
> specializations of it.
>
> **Why.** They share 90% of their machinery — durable keyed state, fenced single-writer, per-key
> serialization, durable timers, recovery on failover. Building one primitive and two thin facades
> costs barely more than #190 alone and avoids two divergent engines. It also matches the industry
> convergence point (virtual actors / durable entities: Orleans grains, Azure Durable Entities, Restate
> virtual objects, Dapr actors, Akka persistent actors).
>
> **Rejected alternative.** *A standalone workflow engine* (#190 as-is) — solves only the FSM case;
> a saga then either rebuilds the same substrate or is bolted awkwardly onto the workflow FSM.
> *Adopt Temporal/Restate* — a second failure domain + second partition model beside Aether's own; see
> §1.3 of the #190 analysis.

### 1.3 Easy + durable + scalable — and how the design delivers all three

The hard tension is durable-and-single-writer-correct (linearizable per key, C-favoring under partition) **vs** scalable (partitioned). The industry
resolution is **per-partition fenced leader**: partition the keyspace (scale), one fenced owner per
partition (single-writer correctness), replicate within the partition (durability). Spanner (Paxos
groups), CockroachDB (range leases), Restate (partition processors + epoch fencing), Temporal (history
shards) are all this pattern. **Aether is one fence away from it** — partitioning and owners exist;
the epoch check (#345) is the missing piece.

- **Easy** — a keyed, single-threaded, durable object exposed as a typed resource handle; no SDK, no
  determinism contract (state-as-truth ⇒ no replay).
- **Durable** — state replicated + fenced (epic #345). *Restart*-durability requires the persistence
  layer to be wired (epic #349); until then the entity is **HA, not restart-durable** — see §4.4.
- **Scalable** — entities are partition-placed (HRW); millions spread across the cluster, processed in
  parallel; only same-entity operations serialize. The sole inherent bottleneck is a single hot entity,
  which cannot be parallelized without abandoning single-writer.

### 1.4 Goals / Non-Goals

**Goals:** G-1 durable keyed entity; G-2 fenced single-writer correctness (on #345); G-3 horizontal
scale by partition placement; G-4 per-key serialization with cross-key parallelism; G-5 durable
per-entity timers; G-6 workflow + saga as specializations; G-7 no SDK, no determinism contract,
state-as-truth (no replay); G-8 JBCT-native ergonomics (`Promise`/`Result`/sealed types).

**Non-Goals:** the fence itself (epic #345, separate); **the persistence wiring itself (epic #349,
separate)**; cross-cluster/multi-region entities; replay/event-sourcing as a *user-visible* model (the
fenced log in §4.4 is internal and no-replay); a managed-activity model (side-effects stay in slice
code, §10).

### 1.5 Design principles

- **One primitive, thin specializations** — workflow and saga are facades over `DurableEntity`.
- **Fence is the foundation** — correctness rests on #345; without it, "single writer" is convention.
- **State-as-truth, no replay** — durable current state; nondeterminism allowed because nothing reruns.
- **Side effects belong to the slice** — the runtime owns state correctness, not external effects.
- **Substrate-independent** — the entity sits on the fenced KV first; a fenced log is a drop-in
  evolution (§4.4).

---

## 2. The layering & dependency chain

```
  epic #345 — per-key ownership FENCE  (rc2; substrate-independent correctness)
        │   reject a stale owner's write (governor / DHT-key / stream-append)
        ▼
  DurableEntity<K,S>  — fenced, keyed, single-writer, durable, partition-placed
        │
        ├──►  PersistentWorkflow<S,E>   = entity whose update() applies an FSM transition   (was #190)
        └──►  Saga<C>                    = entity that orchestrates steps + compensation
```

Each layer is independently useful: the fence fixes a latent split-brain bug today (#345); the entity
serves any durable-single-writer need; workflow and saga are convenience facades.

---

## 3. Current Substrate (verified)

| Capability | State | Anchor |
|---|---|---|
| Custom-resource SPI (`@ResourceQualifier` + `ResourceFactory<T,C>` via `ServiceLoader`) | ✅ **mechanical** — a new entity resource is annotation + factory + services entry, no framework edits | `ResourceQualifier.java:13-18`; `SpiResourceProvider.java:45-47` |
| `StateMachineDefinition<S,E,C>` (builder, `transition(S,E,S)`, `onEntry`/`onExit`, `finalState(S)`, `build() → Result<...>`) | ✅ exists, **unused at runtime, in-memory only** | `StateMachineDefinition.java:24,94,104-127`; `InMemoryStateMachine.java:17-19` |
| Partitioned placement + per-partition owner (HRW) | ✅ exists (DHT ring, governor/owner) | `ReplicaPlacement.java:16-34`; `GovernorElection.java:40-46` |
| **Per-key write fence (single-writer enforcement)** | ✅ **IMPLEMENTED** — `staleEpochWrite` + `EpochBearing<E>` in `KVStore` Rabia applier; rejects any `Put` whose incoming epoch is strictly older than the committed one; deterministic (pure function of replicated state); covers governor + DHT ownership writes. Stream-path epoch-CAS is #345 piece 1b (remaining gap). | `KVStore.java:87-127`; `EpochBearing.java:1-38`; `AetherValue.java: DhtPartitionOwnershipValue`, `StreamPartitionOwnershipValue`; `BootstrapModule.java:367-402` |
| Per-key serialization queue (serialize same-key, parallel across keys) | ❌ **MISSING** | — |
| Durable per-instance timers (one-shot, fire-and-delete, survive handover) | ❌ **MISSING** (scheduler is per-slice-method cron) | `ScheduledTaskManager.java:178,333` |
| Runtime→slice invocation (timer fire, dispatch) | ✅ exists | `SliceInvoker.java:95-96` |
| Durable KV store (replicated, quorum) | ✅ exists, **in-memory — not restart-durable** (→ #349). DHT fence (KV path) now live; stream-path fence (#345 piece 1b) still open. | `DHTClient.java:39-76`; `MemoryStorageEngine.java:71-75` |

**Reading:** the *correctness* fence for the KV path landed in the Rabia applier (`staleEpochWrite`);
the *convenience* substrate (resource SPI, FSM library, placement) exists. Remaining net-new: stream-path
epoch fence (#345 1b), per-key serialization queue, durable per-instance timers, and the entity core itself.

---

## 4. Architecture — the durable entity

### 4.1 The model

An entity instance is `(key, state, ownerEpoch, pendingTimers)`. `state` is an application-defined
immutable value (record / sealed interface). The entity is **placed** by hashing `key` to a partition
(HRW), whose **owner is the single fenced writer**. All writes go through the owner and are
**epoch-fenced** (#345): a write tagged with a stale owner epoch is rejected by every Rabia replica
identically (deterministic pure function of committed state — see `EpochBearing.java`), so a deposed
owner cannot commit after handover.

### 4.2 Fenced single-writer (the correctness core)

> **Decision.** Every entity write is `update(key, mutator)` executed **only on the partition owner**
> and committed via a **fenced write** (#345): `write(key, newState, ownerEpoch)` succeeds iff
> `ownerEpoch` is current. The fence mechanism (`EpochBearing` + `staleEpochWrite`) is already live
> in the KV Rabia applier; it must be extended to the stream-path append (#345 piece 1b).
>
> **Why.** This is the per-partition-fenced-leader pattern. The owner serializes writes (per-key queue,
> §4.3); the epoch fence makes single-writer a *guarantee* across handover, not a convention. A reader
> on any node sees the last committed state because writes are RF-replicated under the fence. The
> fence is deterministic: every replica accepts or rejects identically (reads only committed state +
> the command, no wall-clock, no randomness — `EpochBearing.java:23-27`).
>
> **Rejected alternative.** *Unfenced owner* (today for stream path) — split-brain double-writes
> during handover (the #345 bug). *Per-key Paxos/Rabia group* — correct but a consensus group per
> key doesn't scale to millions of entities; the fenced-owner-over-replicated-partition is the
> scalable form.

### 4.3 Per-key serialization

> **Decision.** The owner runs a **per-key serialization queue**: operations on the same `key` are
> applied in total order; different keys proceed in parallel.
>
> **Why.** Total per-entity order is required for state correctness; cross-key parallelism is required
> for scale. A `ConcurrentHashMap<Key, Queue>` with a per-key worker gives both. (Net-new — §3.)
>
> **Rejected alternative.** *Single global queue per owner* — serializes unrelated entities, destroying
> scale. *No serialization* — concurrent same-key updates race even under the fence.

### 4.4 State representation — fenced KV snapshot vs fenced log (resolved)

> **Decision.** The entity API hides the representation. For the **restart-durable** path, **prefer a
> fenced log on the stream substrate**; a fenced KV snapshot on the DHT remains the simplest
> **in-memory / HA-only** form for an initial functional cut.
>
> **Why (persistence reality, epic #349).** Both forms are state-as-truth / no-replay; the deciding
> factor is *what is actually durable*. The DHT is `MemoryStorageEngine` — in-memory, lost on a
> full-cluster restart — so "fenced KV snapshot on the DHT" is HA but **not restart-durable** until
> epic #349 option (c) (a persistent DHT engine, the single largest storage build). The **stream
> substrate, by contrast, has a built, spec-aligned durable path one wire away** (seal →
> `LocalDiskTier`/S3; #349 path a) — so a **fenced log on a stream partition rides that same wiring**,
> gets restart-durability cheaply, and yields ordering + free audit/event-sourcing, overlapping the
> streaming-hardening roadmap (#265/#261). The log stays **no-replay**: the entity folds to a snapshot
> and tails; the governor owns the fold, so there is no determinism or migration burden.
>
> **Rejected alternative.** *KV-snapshot-on-DHT as the durable default* — quietly assumes a durable
> DHT that does not exist; making it restart-durable is the biggest build in the storage stack.
> *Replay/event-sourcing as the user contract* — rejected; the log is an internal durability
> mechanism, not a determinism contract exposed to authors.
>
> **Sequencing.** KV-snapshot (in-memory, HA-only) is acceptable for a first functional cut on the
> #345 fence; the **restart-durable** entity is the fenced log on the durable stream substrate (#349).
> Both behind one API, so the move costs no author churn.

### 4.5 Timers

Each owner keeps an in-memory timer wheel for its entities; entries are **persisted (fenced) under a
parallel key prefix** so they survive handover (the new owner rebuilds the wheel by scanning its
arc). On expiry the owner applies the scheduled operation via the same path as an external update.
One-shot, fire-and-delete; auto-cancelled on terminal state. (Distinct from per-slice cron — §11.)

### 4.6 Hot-entity bottleneck (acknowledged)

A single high-traffic entity (e.g. a global counter) is an inherent single-writer bottleneck — no
design can parallelize same-entity mutations without abandoning the single-writer guarantee. Authors
must shard such entities by key if throughput demands it. This is not a design gap; it is the
correct trade-off stated explicitly: single-writer = serialized = bounded throughput per entity.

---

## 5. The `DurableEntity` API

### 5.1 Interface

```java
/**
 * A keyed, fenced, single-writer, durable entity.
 * <p>
 * K — entity key type (must be serializable + equality-comparable)
 * S — state type (immutable value: record or sealed interface)
 */
public interface DurableEntity<K, S> {
    /** Create a new entity; fails with KeyAlreadyExists if the key is taken. */
    Promise<S>           create(K key, S initial);

    /** Owner-routed read of committed state at the default consistency. Returns Option.none() if absent. */
    Promise<Option<S>>   get(K key);

    /**
     * Per-call read consistency (§8.1). LINEARIZABLE fails with LinearizableUnavailable rather than
     * silently degrading; BOUNDED_STALE is served from the replica set and is NOT forwarded (#596).
     */
    default Promise<Option<S>> get(K key, ReadConsistency consistency) { ... }

    /**
     * Fenced single-writer mutation. The mutator is a PURE S→S function (no IO).
     * Runs on the partition owner, inside the per-key serialization queue, committed under the fence.
     * Returns the post-update state.
     */
    Promise<S>           update(K key, Fn1<S, S> mutator);

    /** Schedule a one-shot timer; on expiry applies onFire as an update on the owner. */
    Promise<TimerToken>  scheduleTimer(K key, Duration delay, Fn1<S, S> onFire);

    /** Cancel a previously scheduled timer (no-op if already fired or cancelled). */
    Promise<Unit>        cancelTimer(K key, TimerToken token);

    /**
     * Delete the entity. Succeeds only when the current state satisfies the terminal predicate
     * registered at provisioning time; the runtime auto-cancels pending timers.
     */
    Promise<Unit>        delete(K key);

    record TimerToken(String value) {}
}
```

`Fn1` is `org.pragmatica.lang.functions.Fn1` (return-type-first: `Fn1<R, T1>` is `R apply(T1)`).
Operations surface failures as typed `Cause` subtypes on the `Promise` error channel — see §5.3.

### 5.2 Provisioning — annotation, config, manifest

Follow the existing `@Sql`/`@Publisher` pattern: a custom annotation meta-annotated with
`@ResourceQualifier`, a `ResourceFactory<DurableEntity<K,S>, EntityConfig>` registered via
`ServiceLoader`, and a config section + manifest entry.

**Step 1 — custom qualifier annotation** (one per entity type, owned by the slice):

```java
@ResourceQualifier(type = DurableEntity.class, config = "orders-entity")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrdersEntity {}
```

**Step 2 — config section** (`aether-config.toml` or per-slice config):

```toml
[orders-entity]
# Partition count for this entity type; each partition gets one fenced owner.
partitions        = 64
# RF for KV replication (HA path). Ignored once restart-durable log path is wired.
replication-factor = 3
# Retention after terminal state before GC.
terminal-ttl      = "7d"
# Optional: enable per-transition audit stream.
audit-stream      = "orders-audit"
```

**Step 3 — manifest** (`slice-manifest.toml` reactive/resource section):

```toml
[[resource]]
type   = "DurableEntity"
config = "orders-entity"
```

**Step 4 — inject into slice method**:

```java
@Route("/orders/{id}/cancel")
public Promise<OrderState> cancel(
        String id,
        @OrdersEntity DurableEntity<String, OrderState> orders) {

    return orders.update(id, state -> state.withStatus(CANCELLED));
}
```

### 5.3 Error types

**Reconciled with the shipped surface 2026-08-13 (#432).** This section previously pinned a
`EntityCause` hierarchy of six cases that never shipped under those names. The shipped type is
authoritative and is reproduced here; anything pattern-matching the old names does not compile.

The divergence was not the implementation drifting from a correct design — the pinned set was
*incomplete*. Ownership, fencing and read-consistency each produce failures an author must be able to
distinguish, and those only became apparent while building I0–I3. Renaming the shipped cases to the
old names would still have left those cases unpinned, so the spec yields to the code here.

```java
/** Sealed Cause hierarchy for DurableEntity operations. */
public sealed interface DurableEntityError extends Cause {
    /** create() called for a key that already exists. */
    record KeyAlreadyExists(String key) implements DurableEntityError { ... }
    /** get()/update()/delete() called for a key that does not exist. */
    record KeyNotFound(String key) implements DurableEntityError { ... }
    /** Timer not found (cancelled, fired, or wrong token). */
    record TimerNotFound(String key) implements DurableEntityError { ... }
    /** Durable timers are not implemented yet (#351) — hard failure, never a silent no-op. */
    record TimerNotSupported(String key) implements DurableEntityError { ... }
    /** Fenced write rejected: the presented owner epoch is stale. Transient — re-resolve and retry. */
    record StaleOwner(String key, String presentedEpoch) implements DurableEntityError { ... }
    /** The durable substrate failed the operation; carries the underlying cause. */
    record StorageFailed(String key, Cause cause) implements DurableEntityError { ... }
    /** This node is not the committed owner of the key's partition. STABLE — ask the named owner. */
    record NotCurrentOwner(String key, String committedOwner) implements DurableEntityError { ... }
    /** Ownership for the key's partition has not been committed yet. TRANSIENT — retry here. */
    record OwnershipNotYetCommitted(String key, String keyspace, int partition) implements DurableEntityError { ... }
    /** A LINEARIZABLE read presented an epoch below the high water mark. */
    record StaleEpochRead(String key, String presentedEpoch, String highWaterEpoch) implements DurableEntityError { ... }
    /** LINEARIZABLE was requested but the barrier is unavailable — never silently degraded to a weaker read. */
    record LinearizableUnavailable(String key) implements DurableEntityError { ... }
}
```

`NotCurrentOwner` vs `OwnershipNotYetCommitted` is the load-bearing distinction: the first is stable
and means *go elsewhere*, the second is transient and means *retry here*. Collapsing them into one
"retry" cause produces a message that never clears — that exact defect shipped once and was fixed in
I3 (`FoldInProgress` returned where `PartitionNotHeld` was meant).

**Not implemented, deliberately unpinned:** the entity-lifecycle cases `EntityTerminated` and
`EntityNotTerminal` from the old listing have no shipped equivalent, because the terminal-state
predicate that `delete()` describes in §5.1 is not built yet. They are listed here as intended, NOT
as available; they belong with the lifecycle work, and the case names should be settled when that
lands rather than pre-pinned a second time.

**Reachability caveat (#596):** every one of these is only observable on the partition's owner. There
is no owner-forwarding for writes or bounded-stale reads, so a caller that reaches a non-owner gets
`NotCurrentOwner` and must re-resolve itself — and behind a pinning load balancer it cannot.


---

## 6. Workflow specialization

*Supersedes #190 — the Persistent-Workflow design, carried forward as a specialization.*

### 6.1 Decision — keep `PersistentWorkflow` as a distinct public facade

> **Decision.** `PersistentWorkflow<S,E>` remains a **distinct public facade** over
> `DurableEntity<String, S>`. It is NOT replaced by `DurableEntity` + a raw `StateMachineDefinition`
> adapter exposed to the author.
>
> **Why.** The façade provides: (1) event-validated `dispatch` that rejects invalid events *before*
> any write (domain-meaningful error vs a generic update failure); (2) a vocabulary (`start`,
> `dispatch`, `current`) that maps directly to FSM mental models authors already have;
> (3) automatic final-state detection and timer/audit integration; (4) encapsulation of the
> `StateMachineDefinition<S,E,Unit>` wiring so the author never touches entity internals.
> Ergonomics wins over surface-area minimalism here — the facade saves authors from wiring a
> non-trivial adapter every time they need a workflow. The facade is thin (~50 lines of delegation);
> it does not add a second engine.
>
> **Rejected alternative.** *Expose `DurableEntity` + `StateMachineDefinition` adapter directly* —
> forces every author to write the transition-validation/dispatch glue; error messages become
> generic entity errors instead of domain FSM errors; no natural home for `isFinalState` auto-cancel.

### 6.2 Interface

```java
/**
 * A workflow is a DurableEntity<String, S> whose update() is an FSM transition.
 * <p>
 * S — state type (sealed interface of state cases, one per FSM node)
 * E — event type (sealed interface of event cases)
 */
public interface PersistentWorkflow<S, E> {
    /** Create a new workflow instance at its initial FSM state. */
    Promise<S>          start(String id, S initial);

    /**
     * Dispatch an event. Validates the event against the FSM BEFORE any write (rejects with
     * InvalidEvent if no matching transition exists). Applies the pure transition on the owner
     * under the fence. Returns post-transition state.
     * <p>
     * Also the substrate for EXTERNAL signal injection (§6.6): the management signal surface
     * routes to this method on the owner — a signal is a dispatch, fenced like any write.
     */
    Promise<S>          dispatch(String id, E event);

    /** Read of committed current state at the default read level (BOUNDED_STALE — see §8.1). */
    Promise<Option<S>>  current(String id);

    /** Read at an explicit per-call consistency level (§8.1). */
    Promise<Option<S>>  current(String id, ReadConsistency consistency);

    /** Schedule a timer that fires the given event when it expires. */
    Promise<TimerToken> scheduleTimer(String id, Duration delay, E event);

    /** Cancel a previously scheduled timer. */
    Promise<Unit>       cancelTimer(String id, TimerToken token);

    /** Delete a completed (final-state) workflow instance. */
    Promise<Unit>       delete(String id);
}
```

### 6.3 Provisioning

Same pattern as `DurableEntity`. The slice registers the `StateMachineDefinition` alongside the
qualifier:

```java
@ResourceQualifier(type = PersistentWorkflow.class, config = "order-workflow")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrdersWorkflow {}
```

```toml
[order-workflow]
partitions        = 64
replication-factor = 3
terminal-ttl      = "30d"
audit-stream      = "order-workflow-audit"
```

### 6.4 Worked example — `OrderProcess` FSM

**State and event types:**

```java
public sealed interface OrderState permits OrderState.Pending, OrderState.Confirmed,
                                           OrderState.Shipped, OrderState.Cancelled {}
public record Pending()   implements OrderState {}
public record Confirmed() implements OrderState {}
public record Shipped()   implements OrderState {}
public record Cancelled() implements OrderState {}

public sealed interface OrderEvent permits OrderEvent.Confirm, OrderEvent.Ship, OrderEvent.Cancel {}
public record Confirm() implements OrderEvent {}
public record Ship()    implements OrderEvent {}
public record Cancel()  implements OrderEvent {}
```

**FSM definition** (note: `C` = `Unit` for workflows with no side-effect context):

```java
// StateMachineDefinition<S, E, C>.builder(String name) — verified API
Result<StateMachineDefinition<OrderState, OrderEvent, Unit>> ORDER_FSM =
    StateMachineDefinition.<OrderState, OrderEvent, Unit>builder("order-process")
        .initialState(new Pending())
        .transition(new Pending(),   new Confirm(), new Confirmed())
        .transition(new Pending(),   new Cancel(),  new Cancelled())
        .transition(new Confirmed(), new Ship(),    new Shipped())
        .transition(new Confirmed(), new Cancel(),  new Cancelled())
        .finalState(new Shipped())
        .finalState(new Cancelled())
        .build();                                    // → Result<StateMachineDefinition<...>>
```

**Slice usage:**

```java
@Route("/orders/{id}/confirm")
public Promise<OrderState> confirm(
        String id,
        @OrdersWorkflow PersistentWorkflow<OrderState, OrderEvent> workflow) {

    return workflow.dispatch(id, new Confirm());
    // → Result.success(new Confirmed())  — or WorkflowCause.InvalidEvent if Confirm not valid here
}
```

### 6.5 Workflow error types

```java
public sealed interface WorkflowCause extends Cause {
    record WorkflowNotFound(String id)               implements WorkflowCause { ... }
    record WorkflowAlreadyExists(String id)          implements WorkflowCause { ... }
    record InvalidEvent(String id, Object event,
                        Object currentState)         implements WorkflowCause { ... }
    record WorkflowTerminated(String id,
                              Object finalState)     implements WorkflowCause { ... }
    record StaleOwnerEpoch(String id)                implements WorkflowCause { ... }
}
```

---

### 6.6 External signal injection (v1 — resolves S1)

A **signal** is an FSM event injected into a running workflow from outside the owning slice
(operator, another service, the book's observability demo). Mechanically it is nothing new:
the signal surface routes to `dispatch(id, event)` on the owner — validated against the FSM,
applied under the epoch fence, subject to the same per-key total order as any other event.
No second write path exists.

Surface (management triad, per project invariant — REST → CLI → docs):

- `POST /api/workflows/{workflowType}/{id}/signal` — body: the event (JSON, validated against
  the workflow's event type); response: post-transition state or the typed rejection
  (`InvalidEvent`, `WorkflowNotFound`, `StaleOwnerEpoch` → retried by the gateway).
- CLI: `aether workflows signal <type> <id> --event <json>`.
- Docs: `management-api.md` + `cli.md` sections.

Authorization rides the existing management-API auth; a signal is a state mutation and is
audited like one (§ observability piece, #355).

**Saga signals are v2.** A saga advances by step completion; an external signal would need a
`WAIT_SIGNAL` step kind (a step that parks the saga until a matching signal arrives) — a new
step-kind design with its own timeout/compensation semantics, deliberately out of v1 scope.
Workflows cover the v1/book requirement; the FSM *is* the signal consumer.

## 7. Saga specialization

### 7.1 Model

A saga is `DurableEntity<String, SagaState<C>>` whose state is a **step ledger** tracking forward
progress and, on failure, reverse compensation. The author declares steps and compensations; the
runtime drives the ledger and records each step's completion durably under the fence.

### 7.2 Author-facing step declaration

Each step is a pair of `(forward, compensation)` functions. Both receive the saga context `C` (an
immutable record carrying the saga's business inputs):

```java
/**
 * A single saga step: a forward action and its paired compensation.
 * <p>
 * C — saga context (immutable; business inputs visible to all steps)
 * R — step result type (stored in the ledger; compensation receives it to undo precisely)
 */
public enum RerunPolicy { RUN_ONCE, IDEMPOTENT }

public record SagaStep<C, R>(
    String name,
    Fn1<Promise<R>, C>          forward,       // executes the step's side effect
    Fn2<Promise<Unit>, C, R>    compensation,  // undoes the step given its result
    RerunPolicy                 rerun          // required: RUN_ONCE journals; IDEMPOTENT re-runs freely
) {
    public static <C, R> SagaStep<C, R> step(
            String name,
            Fn1<Promise<R>, C> forward,
            Fn2<Promise<Unit>, C, R> compensation,
            RerunPolicy rerun) {
        return new SagaStep<>(name, forward, compensation, rerun);
    }
}
```

### 7.3 Saga definition

```java
/**
 * Declares a saga: an ordered list of steps with paired compensations.
 * Build once at class-init; the runtime drives it.
 */
public final class SagaDefinition<C> {
    public static <C> Builder<C> builder(String name) { ... }

    public static final class Builder<C> {
        public <R> Builder<C> step(SagaStep<C, R> step) { ... }
        public SagaDefinition<C> build() { ... }
    }
}
```

### 7.4 The per-step re-run policy (resolved — S3)

Every `SagaStep` carries a **required** `RerunPolicy`; there is no default, so a step cannot be
declared without stating whether repeating its `forward` on recovery is safe.

- **`RUN_ONCE`** — the runtime writes a `StepAttempt(sagaId, stepIndex)` marker under the fence
  *before* invoking `forward`; on recovery, a marker present means the runtime does **not** invoke
  `forward` again. This bounds the runtime to **at-most-once invocation** of `forward`. It is not, by
  itself, effectively-once at the effect: the marker cannot distinguish "crashed before the effect
  ran" from "crashed after," so end-to-end once-only for a non-idempotent downstream requires that
  downstream to **dedup on `(sagaId, stepIndex)`** (the idempotency key the runtime supplies). Use it
  for non-idempotent effects (charge, ship, send) whose downstream honors that key.
- **`IDEMPOTENT`** — no marker; the author asserts `forward` is safe to run again, so recovery
  re-runs it. Use it for reads and for writes keyed by a natural idempotency key.

The key `(sagaId, stepIndex)` is the idempotency anchor (also handed downstream — §8). Because the
policy is mandatory, the dangerous case — a non-idempotent effect left re-runnable by omission —
cannot arise: it is a compile error, not a production incident.

### 7.5 Compensation semantics

> **Decision.** Compensation is **best-effort reverse**: compensations run in reverse step order
> (highest committed step index down to 0); a compensation failure is recorded in `SagaState` and
> does not stop the remaining compensations. A saga that exits compensation with one or more failed
> compensations lands in `PartiallyCompensated` (not `Compensated`). No automatic retry of
> compensation; retrying is the author's responsibility (e.g., via a monitoring slice that observes
> `PartiallyCompensated` sagas).
>
> **Why.** Guaranteed compensation requires an unbounded retry loop, which hides errors and can loop
> forever on a permanently broken downstream. Best-effort-with-explicit-partial-state gives the
> author visibility and control. The `PartiallyCompensated` case is queryable and actionable (operator
> can inspect, the monitoring slice can retry, the author can add domain-specific recovery). This
> matches how production saga systems actually behave (Temporal compensations are also best-effort;
> Restate's saga guide documents the same pattern).
>
> **Rejected alternative.** *Guaranteed compensation (infinite retry)* — hides permanent failures
> behind an opaque retry loop; gives the author no signal. *Stop on first compensation failure* —
> leaves later compensations permanently un-run, worsening the leak.

### 7.6 Sealed state types

```java
/**
 * The durable state of a running saga (stored in the entity ledger).
 * <p>
 * C — saga context type
 */
public sealed interface SagaState<C> permits
    SagaState.Running, SagaState.Compensating, SagaState.Completed,
    SagaState.Compensated, SagaState.PartiallyCompensated, SagaState.Failed {

    /** Saga is executing forward steps. */
    record Running<C>(C context, int currentStep, List<StepRecord> completed)
        implements SagaState<C> {}

    /** Saga is running compensations in reverse after a forward step failed. */
    record Compensating<C>(C context, int failedStep, List<StepRecord> completed,
                           List<CompensationFailure> compensationFailures)
        implements SagaState<C> {}

    /** All forward steps succeeded. Terminal. */
    record Completed<C>(C context, List<StepRecord> completed)
        implements SagaState<C> {}

    /** All compensations ran successfully. Terminal. */
    record Compensated<C>(C context, List<StepRecord> completed)
        implements SagaState<C> {}

    /**
     * Saga reached end of compensation with one or more compensation failures. Terminal.
     * Requires operator/author intervention.
     */
    record PartiallyCompensated<C>(C context, List<StepRecord> completed,
                                   List<CompensationFailure> compensationFailures)
        implements SagaState<C> {}

    /** Saga failed in a way that prevented starting compensation (e.g. saga state corrupted). Terminal. */
    record Failed<C>(C context, Cause reason)
        implements SagaState<C> {}
}

record StepRecord(int index, String name, Object result, Instant completedAt) {}
record CompensationFailure(int index, String name, Cause reason) {}
```

### 7.7 The `Saga` facade interface

```java
/**
 * A saga orchestrates a sequence of steps with paired compensations over a shared context C.
 * Backed by DurableEntity<String, SagaState<C>>.
 */
public interface Saga<C> {
    /**
     * Start and drive a new saga to completion (or compensation).
     * Idempotent if a saga with the given id already exists and is Running/Compensating
     * (returns its current status); fails with SagaAlreadyTerminated if already in a terminal state.
     */
    Promise<SagaResult<C>> run(String id, C context);

    /** Read of committed saga state, for monitoring, at the default read level (BOUNDED_STALE — §8.1). */
    Promise<Option<SagaState<C>>> status(String id);

    /** Read at an explicit per-call consistency level (§8.1). */
    Promise<Option<SagaState<C>>> status(String id, ReadConsistency consistency);

    /** Delete a terminal saga instance (respects terminal-ttl if configured). */
    Promise<Unit> delete(String id);
}

/** The outcome of a completed saga run. */
public sealed interface SagaResult<C> permits SagaResult.Succeeded, SagaResult.Compensated,
                                              SagaResult.PartiallyCompensated, SagaResult.Failed {
    record Succeeded<C>(C context, List<StepRecord> steps)     implements SagaResult<C> {}
    record Compensated<C>(C context, List<StepRecord> steps)   implements SagaResult<C> {}
    record PartiallyCompensated<C>(C context,
                                   List<StepRecord> steps,
                                   List<CompensationFailure> failures) implements SagaResult<C> {}
    record Failed<C>(C context, Cause reason)                  implements SagaResult<C> {}
}
```

### 7.8 Saga error types

```java
public sealed interface SagaCause extends Cause {
    record SagaNotFound(String id)                   implements SagaCause { ... }
    record SagaAlreadyExists(String id)              implements SagaCause { ... }
    record SagaAlreadyTerminated(String id,
                                 SagaState<?> state) implements SagaCause { ... }
    record StepFailed(String id, int stepIndex,
                      String stepName, Cause cause)  implements SagaCause { ... }
    record StaleOwnerEpoch(String id)                implements SagaCause { ... }
}
```

### 7.9 Provisioning

```java
@ResourceQualifier(type = Saga.class, config = "order-saga")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrderSaga {}
```

```toml
[order-saga]
partitions        = 32
replication-factor = 3
terminal-ttl      = "90d"
audit-stream      = "order-saga-audit"
```

### 7.10 Worked example — order saga

**Context (immutable input record):**

```java
public record OrderContext(
    String orderId,
    String customerId,
    List<LineItem> items,
    BigDecimal total
) {}
```

**Saga definition:**

```java
// Declared once (e.g. in a static final field or @Provides method)
SagaDefinition<OrderContext> ORDER_SAGA =
    SagaDefinition.<OrderContext>builder("order-saga")
        .step(SagaStep.<OrderContext, ReservationId>step(
            "reserve-inventory",
            ctx -> inventorySlice.reserve(ctx.orderId(), ctx.items()),          // forward
            (ctx, reservationId) -> inventorySlice.release(reservationId),      // compensation
            IDEMPOTENT))                                                        // keyed by order id; repeat is a no-op
        .step(SagaStep.<OrderContext, ChargeId>step(
            "charge-payment",
            ctx -> paymentSlice.charge(ctx.customerId(), ctx.total()),          // forward
            (ctx, chargeId) -> paymentSlice.refund(chargeId),                   // compensation
            RUN_ONCE))                                                          // a second charge moves real money
        .step(SagaStep.<OrderContext, Unit>step(
            "confirm-order",
            ctx -> orderSlice.confirm(ctx.orderId()),                           // forward
            (ctx, _) -> orderSlice.cancel(ctx.orderId()),                       // compensation
            IDEMPOTENT))                                                        // setting status to confirmed is idempotent
        .build();
```

**Slice usage:**

```java
@Route("/orders/{orderId}/place")
public Promise<SagaResult<OrderContext>> placeOrder(
        String orderId,
        OrderRequest req,
        @OrderSaga Saga<OrderContext> saga) {

    var context = new OrderContext(orderId, req.customerId(), req.items(), req.total());
    return saga.run(orderId, context);
    // On success  → SagaResult.Succeeded  (all 3 steps committed)
    // On failure  → SagaResult.Compensated (all compensations ran)
    //             → SagaResult.PartiallyCompensated (compensation failed; needs intervention)
}
```

**Crash-window behaviour** (step 2, `charge-payment`, a `RUN_ONCE` step):
1. Payment service charges successfully.
2. Process crashes before ledger commit.
3. New owner recovers, finds `StepAttempt(orderId, 1)` marker in entity state.
4. Promotes to `StepCompleted` without re-calling `paymentSlice.charge`.
5. Proceeds to step 3 (`confirm-order`).

---

## 8. Execution Semantics

- **Single-writer total order per entity** (owner + per-key queue + fence). Across entities: no order.
- **Writes: linearizable per key** — a committed `update` is ordered and durable across RF replicas
  under the epoch write-fence (KV path live; stream path is #345 piece 1b).
- **Reads: per-call consistency (resolves S5)** — see §8.1. The default is committed-but-bounded-stale;
  callers state what they need per call. The write fence orders writes, not default reads: a
  BOUNDED_STALE read during handover can be served by a deposed owner that has not yet learned it
  lost ownership, and a read from a lagging replica trails the latest commit.
- **No replay** — recovery resumes from current durable state; transitions never rerun; nondeterminism
  permitted.
- **Idempotency** — each update carries a stable per-entity monotonic counter `(key, n)`; slice
  side-effect code uses it as an idempotency key. The saga run-once primitive uses `(id, stepIndex)`.
- **Owner-handover unavailability** — entities on a partition are unavailable while the new owner
  is elected (seconds, SWIM). In-flight operations see a retriable `StaleOwnerEpoch` or timeout and
  are transparently retried by the runtime. The fence guarantees the *old* owner cannot commit after
  handover.

### 8.1 Read consistency (v1 — resolves S5)

Every read (`get`/`current`/`status`) takes an optional `ReadConsistency`:

```java
public enum ReadConsistency {
    /** Default. Local committed-prefix read: ~µs, no coordination, never torn; staleness =
        consensus apply lag (ms healthy; grows on a partitioned minority). Monotonic per node.
        Keeps serving through owner failover and leader churn. */
    BOUNDED_STALE,
    /** Linearizable: the read reflects every write acknowledged before it began. Costs a
        coordination round (mechanism below); blocks (retriable) during owner/leader churn. */
    LINEARIZABLE
}
```

The enum names **semantics**; the LINEARIZABLE **mechanism** is cluster config (ops knob, not
caller-visible — swapping it never changes what callers may assume):

```toml
[durable-entity]
read-linearization = "no-op-round"   # "no-op-round" (default) | "lease"
```

- **`no-op-round`** (default) — **IMPLEMENTED (#345 item 1e-a).** The read is ordered through a
  no-op consensus round (the owner submits a `KVCommand.Noop` and awaits its OWN local apply of it)
  and served after the local apply reaches it, re-checking the epoch fence AFTER the round. No clock
  assumptions; correct on Rabia by construction. Cost ~0.5–2 ms per batch (concurrent LINEARIZABLE
  reads on the same arc share a round via the content-derived batch id). Wired on the stream read
  path (`ForwardingReadRouter` / `LinearizableOwnerServe` / `LinearizableBarrier`); the forwarded
  read path re-runs the same owner-side pipeline. The entity surface exposes it per-call via
  `DurableEntity.get(key, ReadConsistency)` — **IMPLEMENTED (#345 item 1e-b).** The entity-native
  `LinearizableEntityServe` re-runs the same owner-side pipeline (committed-owner routing → no-op
  round → post-round epoch fence) over the SHARED `StreamPartitionOwnershipValue` ownership substrate
  (`CommittedPartitionOwnerSource` / `OwnershipEpochHighWater` / `EntityPartitionArc`), entity-shaped
  and rejecting with typed `DurableEntityError` causes. A read reaching a non-owner is rejected
  `DurableEntityError.NotCurrentOwner` (owner-forwarding an entity read cross-node needs transport
  that does not exist yet — a follow-up); a deposed owner is rejected
  `DurableEntityError.StaleEpochRead`; and the un-wired in-memory cut (production cluster wiring is
  #277) degrades a LINEARIZABLE read to the local read, which on a single owner already reflects every
  acknowledged write.
- **`lease`** — **FUTURE (rejected at config parse until validated).** The owner serves LINEARIZABLE
  reads locally while holding a time-bounded ownership lease. Near-BOUNDED_STALE cost when healthy;
  **correctness depends on bounded clock skew** — the lease TTL must dominate `max_clock_skew`, the
  runtime monitors skew and fails the mechanism closed (falls back to `no-op-round`) when the bound
  is violated, and reads block up to lease-TTL after owner death. **Validation gate:** the lease
  mechanism ships only with a dedicated clock-skew chaos suite (skewed-clock + partition scenarios);
  until that suite is green on real infra it is **rejected at config parse** (`ConfigLoader` fails a
  `read-linearization = "lease"` load with a named error) and deployments run `no-op-round`.

The `no-op-round` mechanism ships in v1 (implemented #345 item 1e-a); `lease` is deferred and
rejected at config parse until its clock-skew chaos validation gate is green (owner decision,
2026-07-04 — accepting the validation surface). Per-call semantics + configurable mechanism means: callers can mix fast polling
(BOUNDED_STALE) with decision-point reads (LINEARIZABLE) in one application, and ops can
trade the lease's performance for the no-op round's assumption-freedom without touching code.

---

## 9. Failure Model

- **Technical vs business failure** (JBCT): transport/peer/handover failures travel the error channel
  and are retried by the runtime; business outcomes travel the success channel and drive transitions.
- **Bounded per-entity unavailability** — on owner departure, the partition's entities are unavailable
  until the new owner is elected (seconds, SWIM); dispatches retry transparently. The fence (#345)
  guarantees the *old* owner cannot commit after handover.
- **Permanent loss** — if all replicas of a partition are lost, its entities are lost (inherited DHT
  durability; governed by RF + ops). No extra guarantee invented.
- **Partial saga compensation** — tracked in `PartiallyCompensated` state; never silently discarded.
  The author/operator must resolve; the runtime provides visibility, not automated recovery.

---

## 10. Side Effects

The mutator is pure. After `update`/`dispatch` returns, the slice has the new state and performs
whatever side effect it implies (call a slice, HTTP, notify, DB) using its own resources — the runtime
does not run side effects on the slice's behalf (avoids re-creating Temporal's Activity model). The
**`RUN_ONCE`** step (§7.4) is the single managed affordance for narrowing the
crash-after-effect-before-commit window without an SDK; end-to-end once-only still requires a
downstream that dedups on the `(sagaId, stepIndex)` key.

---

## 11. Substrate dependencies (epic pieces)

| Piece | Status | Note |
|---|---|---|
| **0 — Persistent backing** (epic #349, sibling) | **MISSING — durability foundation** | prod storage is memory-only (no restart durability); the entity is HA until #349 wires it. Log-on-stream (§4.4) rides #349 path (a) |
| **1a — KV-path ownership fence** (#345) | **IMPLEMENTED** | `staleEpochWrite` + `EpochBearing` in `KVStore` Rabia applier; covers DHT + governor writes |
| **1b — Stream-path epoch fence** (#345) | **MISSING** | stream-append has no epoch-CAS / sequencer-epoch check; deposed owner's append still accepted |
| 2 — Per-key serialization queue | MISSING | serialize same-key, parallel across keys |
| 3 — Durable per-instance timers | MISSING | one-shot, fenced-persisted, handover-recovered |
| 4 — `DurableEntity` core | new | fenced KV snapshot state; owner routing |
| 5 — Workflow facade (#190) | new (design done) | entity + `StateMachineDefinition` |
| 6 — Saga facade + run-once step | new | step ledger + journaled step |
| 7 — Observability / audit stream | new | metrics + opt-in transition/step audit to a stream |

Per-slice cron stays on `ScheduledTaskManager` (independent). **Two foundations gate this stack:** the
**#345 fence** (KV path done; stream path pending) and **#349 persistent backing** (durability).

---

## 12. Reconciliation to Existing Code

| Capability | Current | Target | Tag | Anchor |
|---|---|---|---|---|
| KV-path per-key fence | `staleEpochWrite` + `EpochBearing` **live in Rabia applier** | extend entity write to carry `ownerEpoch` as `EpochBearing` value | **REUSE** | `KVStore.java:87-127`; `EpochBearing.java` |
| Stream-path epoch fence | no epoch-CAS on stream append | stream-path epoch check (#345 piece 1b) | **MISSING** | `OffHeapRingBuffer.java:330`; `ReplicationReceiveHandler.java:133-157` |
| `StateMachineDefinition` | exists, unused, in-memory | consume in the workflow facade (C=Unit for pure FSMs) | **REUSE** | `StateMachineDefinition.java:24` |
| Resource SPI | exists, mechanical | register `DurableEntity`/`PersistentWorkflow`/`Saga` types | **REUSE** | `SpiResourceProvider.java:45` |
| Per-key serialization | none | owner-side per-key queue | **MISSING** | — |
| Per-instance timers | per-slice cron only | durable one-shot per-entity timers | **MISSING** | `ScheduledTaskManager.java:178` |
| Durable KV | LWW, HLC-versioned, eventually consistent (FULL/q=1: single-node ack, stale cross-node reads); KV-path fence adds single-writer write ordering | entity state uses fenced KV (HA) → fenced log (restart-durable) | **EXTEND** | `DHTClient.java:39` |

---

## 13. Implementation Phases

| Phase | Scope | Epic piece |
|---|---|---|
| 0 | **Stream-path epoch fence** — complete the #345 fence for stream appends (#345 piece 1b) | #345 / piece 1b |
| 1 | `DurableEntity` core — fenced KV-snapshot state, owner routing, per-key serialization queue | pieces 2, 4 |
| 2 | Durable per-entity timers (fenced-persisted, handover recovery) | piece 3 |
| 3 | **Workflow facade** — `PersistentWorkflow` over the entity + `StateMachineDefinition` (C=Unit) | piece 5 |
| 4 | **Saga facade** — step ledger + journaled run-once step + compensation | piece 6 |
| 5 | Observability + audit stream + operator API | piece 7 |
| 6 | Hardening — docs, sample slices, chaos/soak under governor handover | — |

**Acceptance:** a sample workflow *and* a sample saga run to completion across `kill-9` of the owning
node with no slice-author-visible errors; the fence rejects a stale-owner write in a split-brain test;
100k entities within memory/throughput budgets on a 5-node cluster.

---

## 14. Owner Decisions Still Needed

**All resolved as of 2026-07-04** (S3 on 2026-07-01; S1/S2/S4/S5 on 2026-07-04). Recorded here
with rationale; the normative content lives in the referenced sections.

**S1 — signals scope for v1. RESOLVED (2026-07-04): signal injection IS v1** (book requirement).
Scoped precisely: **workflow** signal injection ships in v1 as a thin external exposure of
`dispatch` (management triad, §6.6) — a signal is a dispatch, fenced like any write, no second
write path. **Saga** signals (a `WAIT_SIGNAL` step kind with park/timeout/compensation
semantics) are explicitly v2 — new step-kind design, not a thin exposure. The general
query API (list-by-state) remains v2.

**S2 — GC / terminal retention defaults. RESOLVED (2026-07-04): as proposed** —
`terminal-ttl = "7d"` (entities) / `"30d"` (workflows) as config defaults, plus the explicit
`delete` API. Rationale: retain-forever-by-default is unbounded storage growth by surprise;
finite-defaults-loudly-documented matches the product's retention-as-floor stance
(cf. durable-pubsub-spec §7). Ops raise the TTLs where the domain needs it.

**S3 — re-run policy default. RESOLVED (2026-07-01).** Neither default. Every `SagaStep` carries a
**required** `RerunPolicy` (`RUN_ONCE` | `IDEMPOTENT`); a step cannot be constructed without stating
its re-run safety (§7.2, §7.4). This removes both silent failure modes — a forgotten opt-in that
double-executes a non-idempotent effect, and a forgotten opt-out that journals needlessly — at the
cost of one enum per step, visible and reviewable on the line. The asymmetry decided it: a missed
declaration is a compile error, not a production incident.

**S4 — `StateMachineDefinition` `C` type parameter. RESOLVED (2026-07-04): keep hidden,
`C = Unit`.** Owner's rationale, verbatim: *"non-durable context in durable workflow sounds odd
at best."* Context flowing through `TransitionContext` is not persisted — anything load-bearing
in it silently vanishes on owner failover, violating state-as-truth. Context that matters belongs
in state; workflows stay resumable by construction. Widening to `PersistentWorkflow<S,E,C>`
later is possible (pre-GA, no compat debt) if a non-load-bearing-context use case materializes.

**S5 — read-side linearization. RESOLVED (2026-07-04): per-call semantics, both mechanisms in
v1.** `ReadConsistency` (BOUNDED_STALE default | LINEARIZABLE) as a per-call parameter — callers
state what they need, no cluster-wide semantic switch. The LINEARIZABLE mechanism is an ops
config: `no-op-round` (default; no clock assumptions, correct on Rabia) or `lease`
(near-local cost; gated on a dedicated clock-skew chaos suite). Normative: §8.1. The owner chose
to ship both mechanisms in v1 accepting the added validation surface; the lease's validation
gate is the guard-rail. Fixes #382 (javadoc overclaim) via the honest per-level documentation.

---

## 15. References

- **Durable entities / virtual actors:** Microsoft Orleans grains — https://learn.microsoft.com/en-us/dotnet/orleans/grains/ · Azure Durable Entities — https://learn.microsoft.com/en-us/azure/azure-functions/durable/durable-functions-entities · Restate virtual objects — https://docs.restate.dev/concepts/durable_building_blocks · Dapr actors — https://docs.dapr.io/developing-applications/building-blocks/actors/actors-overview/
- **Per-partition fenced leader:** Restate first-principles (Bifrost, epoch fencing) — https://www.restate.dev/blog/building-a-modern-durable-execution-engine-from-first-principles · CockroachDB range leases — https://www.cockroachlabs.com/docs/stable/architecture/replication-layer · Spanner — https://cloud.google.com/spanner/docs/whitepapers
- **Durable-execution model (no-replay vs replay):** Vanlightly, demystifying determinism — https://jack-vanlightly.com/blog/2025/11/24/demystifying-determinism-in-durable-execution · DBOS architecture — https://docs.dbos.dev/architecture
- **Internal:** #345 (fence epic), #349 (durability epic), #190 (superseded workflow draft), #265/#261 (streaming substrate), `StateMachineDefinition`, `EpochBearing`, `KVStore`.

---

## Changelog — v0.4.0 (2026-08-13)

**§5.1/§5.3 reconciled with the shipped surface (#432).** The spec claimed authority over an error
hierarchy that never shipped under those names, and the book copied it verbatim — teaching authors a
surface that does not compile.

| What | Why |
|---|---|
| `EntityCause` → `DurableEntityError`, all ten shipped cases listed (§5.3) | The pinned six-case set was not merely renamed, it was INCOMPLETE: ownership (`NotCurrentOwner`, `OwnershipNotYetCommitted`), fencing (`StaleOwner`) and read consistency (`StaleEpochRead`, `LinearizableUnavailable`) each produce failures an author must distinguish, and they only emerged while building I0–I3. Renaming the six would have left the other four unpinned, so the spec yields to proven code rather than the reverse. |
| `EntityTerminated` / `EntityNotTerminal` marked intended-but-unbuilt | The terminal-state predicate `delete()` relies on is not implemented. Listing them as available would repeat the original defect; they get pinned when the lifecycle work lands. |
| §5.1 gains the `get(K, ReadConsistency)` overload | It exists in §8.1 and in shipped `DurableEntity.java:119`; only the §5.1 snippet omitted it — an internal inconsistency independent of the naming question. |
| Reachability caveat added to §5.3 | Every case is observable only on the partition owner; there is no owner-forwarding (#596). |

**Decision record:** the issue offered (a) rename shipped code to the pinned names, or (b) amend the
spec, and assigned the call to the design stream. This is (b), chosen on the completeness argument
above plus lower risk — the shipped cases are integration-proven, and renaming a public error surface
mid-rc3 breaks author code for a naming preference. An owner ruling settles it if (a) is still wanted;
reverting is a spec edit, no code churn.

---

## Changelog — v0.3.0 (2026-07-04)

**All §14 owner decisions closed — spec is decision-complete for implementation.**

- **S1 resolved (signals in v1):** new §6.6 — workflow signal injection as a thin external
  exposure of `dispatch` (management triad: REST `POST /api/workflows/{type}/{id}/signal`,
  CLI `aether workflows signal`, docs). Saga `WAIT_SIGNAL` step kind explicitly v2.
- **S2 resolved:** terminal-ttl defaults confirmed (`7d` entities / `30d` workflows) + explicit delete.
- **S4 resolved:** `C` stays hidden (`Unit`) — non-durable context in a durable workflow violates
  state-as-truth; context belongs in state.
- **S5 resolved:** new §8.1 — per-call `ReadConsistency` (BOUNDED_STALE default | LINEARIZABLE);
  both linearizable mechanisms (`no-op-round` default, `lease` behind a clock-skew chaos-suite
  validation gate) ship in v1, mechanism selected by ops config, semantics never config-dependent.
  `current`/`status` gain the per-call overload (§6.2, §7.7). Fixes #382 by honest documentation.
- §8 reads bullet rewritten to point at §8.1.

## Changelog — v0.2.3 (2026-07-01)

Consistency-lens pass (Kleppmann; `guarantees.md` discipline): guarantee claims corrected to name the
precise per-operation model + the mechanism that earns it. No API change; wording + one new §14 item.

| What | Why |
|---|---|
| **Reads: "linearizable" → "committed, bounded-stale"** (§5.1, §6.2, §7.7, §8) | The epoch write-fence orders writes, not reads. An owner-routed read during handover can be served by a deposed-unaware owner; a lagging replica trails the latest commit. Linearizable reads need a read-side lease/ReadIndex or quorum read — now tracked as **S5**. Extends C5/#382 (shipped javadoc) and matches the `guarantees.md` D1 rewrite. |
| **§1.3 "(CP)" → "linearizable per key, C-favoring under partition"** | One-bit CAP label replaced with the per-operation model. |
| **§12 "LWW/AP quorum" → LWW/eventual (FULL/q=1)** | The DHT default is FULL/q=1 (single-node ack, stale cross-node reads), not a quorum; the KV-path fence adds write ordering on top. Matches C3/D2. |
| **§7.4 `RUN_ONCE` = at-most-once invocation** (not effectively-once by itself) | The marker cannot distinguish crash-before-effect from crash-after; end-to-end once-only for a non-idempotent downstream requires that downstream to dedup on `(sagaId, stepIndex)`. House term: effectively-once (D16), never exactly-once. |
| **New §14 S5** — read-side linearization mechanism (lease/ReadIndex vs quorum read) | The genuine design decision the lens surfaced; relates to #382. |

---

## Changelog — v0.2.2 (2026-07-01)

| What | Why |
|---|---|
| **S3 resolved: mandatory per-step `RerunPolicy`** (§7.2, §7.4, §7.10, §14) | The journaled run-once step is no longer opt-in (or opt-out). `SagaStep` gains a required `RerunPolicy` (`RUN_ONCE` \| `IDEMPOTENT`); a step cannot be constructed without declaring its re-run safety. Rationale: the failure asymmetry — a forgotten opt-in double-executes a non-idempotent effect (silent, in production); a forgotten opt-out costs one fenced write. No default makes the dangerous case a compile error. The marker write is cheap relative to the network side effect it guards, so the performance case for an opt-in default is weak. |

---

## Changelog — v0.2.1 (correction, 2026-06-29)

Two API-shape errors in v0.2, caught during the Aether book's fidelity pass, are corrected here.
No design change; signatures only.

| What | Why |
|---|---|
| **All async signatures: `Promise<Result<T>>` → `Promise<T>`** (§5.1, §5.2, §6.2, §6.4, §7.2, §7.7, §7.10) | `Promise<T>` is the async `Result`: it already carries a typed-`Cause` error channel. `Promise<Result<T>>` double-wraps, stacking two failure representations. v0.1's bare `Promise<T>` was correct; the v0.2 move to `Promise<Result<...>>` (and the claim that bare `Promise` "hides the error channel") was the misstep. Failures travel as `EntityCause`/`WorkflowCause`/`SagaCause` on the `Promise` channel; sync parses still return `Result`. |
| **`SagaStep` `Fn1`/`Fn2` order** (§7.2): `Fn1<C, Promise<Result<R>>>` → `Fn1<Promise<R>, C>`; `Fn2<C, R, Promise<Result<Unit>>>` → `Fn2<Promise<Unit>, C, R>` | Pragmatica `Fn1<R, T1>` / `Fn2<R, T1, T2>` are return-type-first (`R apply(T1)`). The v0.2 order declared `forward` as `C apply(Promise<...>)`, the reverse of intent; the worked lambda `ctx -> slice.call(...)` only typechecks under the corrected order. |

---

## Changelog — v0.2

| What | Why |
|---|---|
| **§3 substrate table**: KV-path fence status changed from ❌ MISSING → ✅ IMPLEMENTED | Verified: `staleEpochWrite` + `EpochBearing<E>` already live in `KVStore` Rabia applier, covering DHT + governor writes; #345 piece 1a is done. Stream-path fence (piece 1b) correctly remains MISSING. |
| **§3, §11, §12**: split "fence" into 1a (KV, done) and 1b (stream, missing) | Precision; the two paths have different status and different implementation sites. |
| **§5**: ~~`DurableEntity` API — all methods now return `Promise<Result<...>>`~~ | **Superseded (v0.2.1):** this was an error. `Promise<T>` already carries the typed-`Cause` error channel; `Promise<Result<T>>` double-wraps. v0.1's bare `Promise<T>` was correct. The provisioning walkthrough + `EntityCause` hierarchy added in v0.2 stand. |
| **§5.2–5.3**: full provisioning walkthrough (annotation → config → manifest → inject) + `EntityCause` sealed hierarchy | Book needs concrete, copy-paste-ready provisioning code; error types needed for pattern-matching examples. |
| **§6.1**: Q5 resolved — `PersistentWorkflow` kept as distinct facade | Ergonomics > surface-area; decision and rationale recorded inline. |
| **§6.3–6.5**: full provisioning + worked `OrderProcess` example using real `StateMachineDefinition` builder API | v0.1 deferred to "#190 draft"; v0.2 pins the API against verified source (builder methods at `StateMachineDefinition.java:89-135`). |
| **§7**: Saga section completely redesigned | v0.1 sketched the saga interface but left the author API unspecified ("slice-provided step/compensation functions" with no shape). v0.2 adds: `SagaStep<C,R>`, `SagaDefinition<C>`, `SagaState<C>` sealed hierarchy, `SagaResult<C>`, `SagaCause`, provisioning, and a full order-saga worked example. |
| **§7.5**: Q3 resolved — compensation is best-effort-reverse; `PartiallyCompensated` is a named terminal state | Explicit decision + rationale; replaces vague "best-effort vs guaranteed" open question. |
| **§7.4**: run-once step made opt-in (`runOnce` flag on `SagaStep`) | Per-step granularity avoids unnecessary journaling overhead on inherently idempotent steps. |
| **§4.6**: hot-entity bottleneck section added | Named explicitly as an acknowledged trade-off (not a bug), so the book can address it directly. |
| **§8**: owner-handover unavailability added to execution semantics | Material failure mode missing from v0.1 semantics section. |
| **§14**: §14 renamed from "Open Questions" to "Owner Decisions Still Needed"; Q1–Q6 resolved inline; four remaining items require Sergiy's call | Distinguishes resolved design decisions from items requiring human authority. |
| **§13**: Phase 0 changed from "per-key ownership fence" to "stream-path epoch fence" | KV-path fence is done; stream-path fence (piece 1b) is now the actual first unblocked implementation task. |

---

*Companion to epic #345. Supersedes #190 (carried forward as §6). Built on the per-key ownership fence
(#345, piece 1). Workflow and saga are facades over one `DurableEntity` primitive.*
