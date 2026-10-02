# Durable Single-Writer Entity — Design Specification

*The primitive for durable workflows & sagas.*

**Version:** 0.6.0
**Status:** Draft. **§5 (`DurableEntity`) is reconciled with the shipped named-command API**
(`DurableEntity<K, S, C extends Mutator<S>>`). **§6 (workflow) and §7 (saga) are PLANNED façades with no
production code** (#353, #354; milestone v1.0.0-rc5), and **§7 is reopened by #1827**: five contract
items — RUN_ONCE recovery and the unresolved outcome, operation identity, typed forward dataflow, durable
version binding/retirement/rollback, wait scope and definition registration — are **PENDING RULING** (§14
S6–S10). The spec is not decision-complete until those rulings land. Earlier history: v0.5.0 entity-centric
error names (#432); v0.3.0 closed S1/S2/S4/S5. See changelog.
**Date:** 2026-06-27 (updated 2026-10-02)
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
  DurableEntity<K,S,C extends Mutator<S>>  — fenced, keyed, single-writer, durable, partition-placed
        │                                    (SHIPPED; C = the keyspace's sealed command hierarchy)
        ├──►  PersistentWorkflow<S,E>   = entity whose update() applies an FSM transition   (PLANNED, #353; was #190)
        └──►  Saga<C>                    = entity that orchestrates steps + compensation     (PLANNED, #354)
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
| Per-key serialization queue (serialize same-key, parallel across keys) | ✅ **SHIPPED** (v0.6.0 status update) | `PerKeySerialExecutor.java` |
| Durable per-instance timers (one-shot, fire-and-delete, survive handover) | ✅ **SHIPPED** (#351, #345 I4): a timer is a record in the entity's own fenced log | `EntityTimerDriver.java`; `DurableEntity.java:143-221`; CHANGELOG "#351 / #345 I4" |
| Runtime→slice invocation (timer fire, dispatch) | ✅ exists | `SliceInvoker.java:95-96` |
| Durable KV store (replicated, quorum) | ✅ exists, **in-memory — not restart-durable** (→ #349). No longer the entity's state store: since #345 I3 entity state lives on a fenced, fsync-durable, replicated stream log (§4.4). | `DHTClient.java:39-76`; `MemoryStorageEngine.java:71-75` |
| Stream-path epoch fence on the entity log append | ✅ **SHIPPED** (v0.6.0 status update): a deposed owner's append is refused `StaleEpochAppend` → `EntityLogError.StaleOwnerAppend` | `StreamEntityLogSubstrate.java:268-288` |

**Reading (v0.2 snapshot, superseded):** at v0.2 the remaining net-new pieces were the stream-path epoch
fence, the per-key serialization queue, durable timers and the entity core. All four have since shipped
(rows above, v0.6.0); the workflow and saga façades (§6, §7) have not. For the current wired status of
every entity operation, `guarantees.md` §6 is authoritative over this table.

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

**Reconciled with the shipped surface (v0.6.0, #1827).** This is the interface as it ships in
`aether/resource/durable-entity/.../DurableEntity.java:85-262`; the shipped javadoc is authoritative for
per-method semantics and is summarized here, not restated. Earlier revisions of this section showed
`DurableEntity<K, S>` with lambda (`Fn1<S, S>`) mutators — that shape was replaced by the named-command
form (CHANGELOG, "A durable entity's transition is now a NAMED command", #596 prerequisite) and does not
compile against the shipped artifact.

```java
/**
 * A keyed, fenced, single-writer, durable entity.
 * <p>
 * K — entity key type (used only as a map key: equals/hashCode)
 * S — state type (immutable value: record or sealed interface)
 * C — the keyspace's transition type: a SEALED interface extending Mutator<S> whose variants are records
 */
public interface DurableEntity<K, S, C extends Mutator<S>> {
    /** Create; fails EntityAlreadyExists if the key holds state. Totally ordered per key. */
    Promise<S>           create(K key, S initial);

    /** BOUNDED_STALE read (§8.1). Option.none() when no state exists for the key. */
    Promise<Option<S>>   get(K key);

    /** Per-call read consistency (§8.1). The shipped fenced-log implementation overrides this default. */
    default Promise<Option<S>> get(K key, ReadConsistency consistency) { return get(key); }

    /**
     * Fenced single-writer mutation: applies the named command on the committed owner, inside the
     * per-key serialization, under the epoch fence. Returns the post-update state.
     */
    Promise<S>           update(K key, C mutator);

    /** One-shot durable timer; the owner mints the token. Delegates to the token-carrying entry. */
    default Promise<TimerToken> scheduleTimer(K key, Duration delay, C onFire) { ... }

    /** Retry-safe entry: a re-send carrying the same caller-minted token is the SAME schedule. */
    Promise<TimerToken>  scheduleTimer(K key, Duration delay, C onFire, TimerToken token);

    /** Idempotent: an unknown, fired, cancelled or never-landed token succeeds with nothing appended. */
    Promise<Unit>        cancelTimer(K key, TimerToken token);

    /** Delete; auto-cancels the key's pending timers. Fails EntityNotFound if the key holds no state. */
    Promise<Unit>        delete(K key);

    record TimerToken(String value) {
        public static TimerToken timerToken(String value) { ... }
    }
}
```

`Mutator<S>` (`aether/resource/api/.../Mutator.java`) is a single-method `S apply(S state)` that is
deliberately **not** `Fn1`: a transition must be NAMED so it can be persisted (a timer's `onFire`) and
forwarded to the committed owner (#596). The durable API accepts the implementor's own sealed `C`, and a
lambda cannot implement a sealed interface, so an unpersistable transition does not typecheck at the call
site. Because `C` is a type argument of the resource-qualified parameter, the slice processor collects it,
and every record variant gets its own generated codec and tag. A command's `apply` must be pure (no IO);
side effects belong to the caller consuming the returned state (§10).

```java
/** Entity state for the `orders` keyspace (an immutable record). */
public record OrderState(String status, int expiries) {}

/** The keyspace's command hierarchy — each variant is a named, serializable transition. */
public sealed interface OrderCommand extends Mutator<OrderState> {
    record Cancel() implements OrderCommand {
        @Override public OrderState apply(OrderState state) {
            return new OrderState("cancelled", state.expiries());
        }
    }
    record Expire() implements OrderCommand {
        @Override public OrderState apply(OrderState state) {
            return new OrderState("expired", state.expiries() + 1);
        }
    }
}
```

The shipped reference slice is `aether/tests/blueprints/test-entity` (`EntitySlice.OrderCommand`,
`EntitySlice.java:132-148`).

**What `delete` does NOT do (shipped):** there is no terminal-state predicate; `delete` removes any present
key. The predicate an earlier revision described here is unbuilt (see §5.3, `EntityTerminated` /
`EntityNotTerminal`).

Operations surface failures as typed `Cause` subtypes on the `Promise` error channel — see §5.3.

### 5.2 Provisioning — annotation, config, manifest

Follows the existing resource pattern: an author-declared annotation meta-annotated with
`@ResourceQualifier`, served by the shipped `DurableEntityFactory` (registered via `ServiceLoader`), and a
config section. There is no shipped `@Entity` qualifier: durable entities are per-keyspace, so the
pattern is **one author-declared qualifier per keyspace**, each naming its own section (rationale in the
`DurableEntity` javadoc, "Binding a keyspace into a slice"). Reconciled with the shipped binding path in
v0.6.0 (#1827); the reference is `aether/tests/blueprints/test-entity`.

**Step 1 — custom qualifier annotation** (one per keyspace, owned by the slice):

```java
@ResourceQualifier(type = DurableEntity.class, config = "entities.orders")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OrderEntity {}
```

**Step 2 — config section** in `resources.toml` — the blueprint's, or the slice jar's own
`META-INF/resources.toml`, which the loader layers beneath the node composite (#1067). The section name is
whatever the qualifier's `config` names; deploy is refused with `MissingConfigSection` if it is in no
layer:

```toml
[entities.orders]
# Logical name of the entity family; required.
keyspace            = "orders"
# Partition count for this entity type; each partition gets one fenced owner. Default 64.
partition_count     = 64
# Optional (#1564). Replication factor of the entity's backing stream partition — copies per
# partition INCLUDING the owner. Absent: the committed cluster [replication] default (built-in 3).
replication_factor  = 3
# Optional (#1564). Copies, the owner included, that hold a write before it is acknowledged.
# Absent: min(cluster default (built-in 2), replication_factor).
confirmation_factor = 2
```

The section binds to `DurableEntityConfig(keyspace, partitionCount, replicationFactor,
confirmationFactor)`; component names are looked up in snake_case, and the record is `@StrictKeys`, so
any other key (a dashed `replication-factor`, the pre-#1564 `min_sync_replicas`) fails the bind instead
of being ignored. An earlier revision of this example used dashed keys (`partitions`,
`replication-factor`, `terminal-ttl`, `audit-stream`) that never bound to anything; terminal-state GC
and a per-transition audit stream are not config keys at this head.

The replication policy is the one streams and durable topics use (`guarantees.md` §4a, #1564): valid iff
`1 ≤ confirmation_factor ≤ replication_factor`; refused, typed, at deploy (the slice-jar replication
pre-flight) and at activation when that bound fails, when an RF below 3 came from a default, when RF
exceeds the cluster's desired core count, or when a live keyspace is redeclared with different factors
(`ChangedOnLiveResource`); warned (`replication-factor-below-three`, LOUD; `confirmation-equals-replication-factor`;
`confirmation-factor-owner-only`) in the WARN log and the deploy response's `warnings`. The derived CF of
earlier revisions (`minSyncReplicas() = min(2, RF)`) is gone (superseded by #1564). The write path appends
on the owner (fsync) and then awaits `CF − 1` distinct non-self acks; an entity append has no pre-append
replica floor, so a write whose barrier is not met is still applied on the owner and reported
`ReplicationBarrierUnmet` — the record is in the owner's log and fold but was not acknowledged at CF, and is
lost if the owner dies before any peer holds it `[mechanism: StreamEntityLogSubstrate.awaitBarrier after publishLocal]`.

There is no separate manifest step. An earlier revision showed a `slice-manifest.toml` `[[resource]]`
entry; no such file exists in the product — the qualified factory parameter plus the section is the whole
declaration.

**Step 3 — inject at the slice factory parameter**, and apply named commands (HTTP exposure, if any, is
declared in the slice's `routes.toml`, not by an annotation on the method):

```java
@Slice
public interface OrderSlice {
    record CancelRequest(String orderId) {}

    Promise<OrderState> cancel(CancelRequest request);

    static OrderSlice orderSlice(@OrderEntity DurableEntity<String, OrderState, OrderCommand> orders) {
        return new orderSlice(orders);
    }

    record orderSlice(DurableEntity<String, OrderState, OrderCommand> orders) implements OrderSlice {
        @Override
        public Promise<OrderState> cancel(CancelRequest request) {
            return orders.update(request.orderId(), new OrderCommand.Cancel());
        }
    }
}
```

`K`, `S` and `C` (`String`, `OrderState`, `OrderCommand`) are type arguments of the resource-qualified
parameter, so the slice processor registers their codecs — including one per `OrderCommand` variant —
with no author annotation.

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
public sealed interface EntityError extends Cause {
    /** create() called for a key that already exists. */
    record EntityAlreadyExists(String key) implements EntityError { ... }
    /** get()/update()/delete() called for a key that does not exist. */
    record EntityNotFound(String key) implements EntityError { ... }
    /** Pinned for the author surface; NO code path constructs it (cancelTimer is idempotent). */
    record TimerNotFound(String key, TimerToken token) implements EntityError { ... }
    /** A forwarded schedule came back naming a different token than the caller minted — a defect, reported. */
    record TimerTokenMismatch(String key, TimerToken token, String appliedToken) implements EntityError { ... }
    /** A forwarded schedule arrived with a negative delay; refused, never clamped. */
    record TimerDelayInvalid(String key, long delayMillis) implements EntityError { ... }
    /** Answer of the test-only in-memory backings; a running node never returns it (#351 shipped). */
    record TimerNotSupported(String key) implements EntityError { ... }
    /** A due timer could not be applied; logged and the timer CONSUMED. Never reaches a caller. */
    record TimerFireFailed(String key, TimerToken token, Cause cause) implements EntityError { ... }
    /** Fenced write rejected: the presented owner epoch is stale. Transient — re-resolve and retry. */
    record StaleOwnerEpoch(String key, String presentedEpoch) implements EntityError { ... }
    /** The durable substrate failed the operation (permanent); carries the underlying cause. */
    record StorageFailed(String key, Cause cause) implements EntityError { ... }
    /** The backing is not READY (Cause.Transient, e.g. promotion after failover); nothing was appended (#1766). */
    record StorageUnavailable(String key, Cause cause) implements EntityError, Cause.Transient { ... }
    /** This node is not the committed owner of the key's partition. STABLE — ask the named owner. */
    record NotCurrentOwner(String key, String committedOwner) implements EntityError { ... }
    /** Ownership for the key's partition has not been committed yet. TRANSIENT — retry here. */
    record OwnershipNotYetCommitted(String key, String keyspace, int partition) implements EntityError, Cause.Transient { ... }
    /** A LINEARIZABLE read presented an epoch below the high water mark. */
    record StaleEpochRead(String key, String presentedEpoch, String highWaterEpoch) implements EntityError { ... }
    /** LINEARIZABLE was requested but the barrier is unavailable — never silently degraded to a weaker read. */
    record LinearizableUnavailable(String key) implements EntityError, Cause.Transient { ... }
}
```

`NotCurrentOwner` vs `OwnershipNotYetCommitted` is the load-bearing distinction: the first is stable
and means *go elsewhere*, the second is transient and means *retry here*. Collapsing them into one
"retry" cause produces a message that never clears — that exact defect shipped once and was fixed in
I3 (`FoldInProgress` returned where `PartitionNotHeld` was meant).

**Not implemented, deliberately unpinned:** the entity-lifecycle cases `EntityTerminated` and
`EntityNotTerminal` from the old listing have no shipped equivalent, because the terminal-state
predicate an earlier §5.1 described for `delete()` is not built. They are listed here as intended, NOT
as available; they belong with the lifecycle work, and the case names should be settled when that
lands rather than pre-pinned a second time.

**Reachability (#596, updated v0.6.0):** `create`/`update`/`delete` and `scheduleTimer`/`cancelTimer`
issued on a non-owner are forwarded to the committed owner, which re-runs admission and the epoch fence on
arrival; the owner's typed verdict is reconstructed across the wire. A `BOUNDED_STALE` `get` is served
locally by any node holding the partition's log and forwarded to the owner only from a node holding none
(`guarantees.md` summary matrix rows 28–29, §6). A `LINEARIZABLE` `get` is NOT forwarded: a non-owner refuses
`NotCurrentOwner` and the caller re-resolves (§8.1).


---

## 6. Workflow specialization

*Supersedes #190 — the Persistent-Workflow design, carried forward as a specialization.*

> **Status: PLANNED — no production code** (#353, milestone v1.0.0-rc5). Verified at
> `release-1.0.0-rc4` `564d2d3df`: no `PersistentWorkflow`, `WorkflowCause` or workflow signal route
> exists in production Java. Everything in §6 is design intent. How a workflow definition (the
> `StateMachineDefinition`, its dependencies and codecs) binds through slice provisioning and is recovered
> by version is **PENDING RULING 5** (§14 S10); version binding of long-lived instances is **PENDING
> RULING 4** (§14 S9).

### 6.1 Decision — keep `PersistentWorkflow` as a distinct public facade

> **Decision.** `PersistentWorkflow<S,E>` remains a **distinct public facade** over a
> `DurableEntity<String, S, C>` (the backing command type `C` is part of definition registration,
> PENDING RULING 5). It is NOT replaced by `DurableEntity` + a raw `StateMachineDefinition`
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
 * PLANNED (#353). A workflow is a DurableEntity<String, S, C> whose update() is an FSM transition.
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
keyspace            = "order-workflow"
partition_count     = 64
replication_factor  = 3          # optional (#1564); absent: the cluster [replication] default
confirmation_factor = 2          # optional (#1564); absent: min(cluster default, replication_factor)
```

Design sketch: the keys above follow `DurableEntityConfig`. Terminal-state retention (`30d`) and an audit
stream (`order-workflow-audit`) are intended features with no config key yet.

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

**Slice usage** (PLANNED façade; injected at the slice factory parameter as in §5.2 — there is no
`@Route` method annotation, HTTP exposure lives in `routes.toml`):

```java
static OrderFlow orderFlow(@OrdersWorkflow PersistentWorkflow<OrderState, OrderEvent> workflow) {
    return new orderFlow(workflow);
}

record orderFlow(PersistentWorkflow<OrderState, OrderEvent> workflow) implements OrderFlow {
    @Override
    public Promise<OrderState> confirm(ConfirmRequest request) {
        return workflow.dispatch(request.orderId(), new Confirm());
        // → resolves to Confirmed — or fails WorkflowCause.InvalidEvent if Confirm is not valid here
    }
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

*PLANNED with the façade (#353): neither the REST route nor the CLI verb below exists at
`564d2d3df`.*

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

**Saga signals are v2** (as decided 2026-07-04; the initial approval/deadline path for sagas, and whether
a workflow→saga handoff is supported, are reopened as **PENDING RULING 5**, §14 S10). A saga advances by
step completion; an external signal would need a
`WAIT_SIGNAL` step kind (a step that parks the saga until a matching signal arrives) — a new
step-kind design with its own timeout/compensation semantics, deliberately out of v1 scope.
Workflows cover the v1/book requirement; the FSM *is* the signal consumer.

## 7. Saga specialization

> **Status: PLANNED — no production code** (#354, milestone v1.0.0-rc5; verified at
> `release-1.0.0-rc4` `564d2d3df`: no `Saga`, `SagaStep`, `SagaDefinition`, `SagaState`, `SagaCause` or
> `RerunPolicy` type exists in any Java source, main or test). **Reopened by #1827.** The API below is the
> v0.5.0 design; five of its contracts are **PENDING RULING** and are marked where they bite:
>
> | # | Open contract | Where it bites | §14 |
> |---|---|---|---|
> | 1 | RUN_ONCE recovery and the unresolved outcome | §7.4, §7.6, §7.10 | S6 |
> | 2 | Operation-identity delivery to `forward` / `compensation` | §7.2, §7.4, §7.10 | S7 |
> | 3 | Typed forward-step dataflow (step B consuming step A's result) | §7.2, §7.3 | S8 |
> | 4 | Durable version binding, retirement, rollback | §7.9, §7.11 | S9 |
> | 5 | Initial wait/approval scope; definition registration | §6.6, §7.9 | S10 |
>
> Constraints that hold whatever the rulings (#1827, "Constraints to preserve"): state-based recovery
> without replaying the author's program; JBCT-native types and `Promise`-returning behaviour; existing
> slice and rollout mechanisms; best-effort reverse compensation with explicit `PartiallyCompensated` and
> no automatic compensation retry by default (§7.5); no consensus journal/WAL — attempt, result and
> compensation records are application state of the saga entity (§7.1), not consensus persistence.

### 7.1 Model

A saga is a durable entity (`DurableEntity<String, SagaState<C>, …>`; the backing command type is part of
definition registration, PENDING RULING 5) whose state is a **step ledger** tracking forward progress and,
on failure, reverse compensation. The author declares steps and compensations; the runtime drives the
ledger and records each step's completion durably under the fence.

### 7.2 Author-facing step declaration

Each step is a pair of `(forward, compensation)` functions. Both receive the saga context `C` (an
immutable record carrying the saga's business inputs).

**What this signature does NOT carry (v0.6.0, #1827).** `forward` receives only `C`: (a) no operation
identity, although §7.4 promises the runtime supplies `(sagaId, stepIndex)` downstream — the delivery
path, its lifetime, and compensation's identity are **PENDING RULING 2**; (b) no earlier step's result
`R`, so a step that needs a preceding reservation/authorization result cannot obtain it through this API —
supported typed dataflow (or an explicit restriction) is **PENDING RULING 3**. The record below is the
v0.5.0 shape and is expected to change under both rulings.

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

### 7.4 The per-step re-run policy (S3 resolved; RUN_ONCE recovery reopened — S6)

Every `SagaStep` carries a **required** `RerunPolicy`; there is no default, so a step cannot be
declared without stating whether repeating its `forward` on recovery is safe.

- **`RUN_ONCE`** — the runtime writes a `StepAttempt(sagaId, stepIndex)` marker under the fence
  *before* invoking `forward`; on recovery, a marker present means the runtime does **not** invoke
  `forward` again. This bounds the runtime to **at-most-once invocation** of `forward`. It is not, by
  itself, effectively-once at the effect: the marker cannot distinguish "crashed before the effect
  ran" from "crashed after," so end-to-end once-only for a non-idempotent downstream requires that
  downstream to **dedup on `(sagaId, stepIndex)`** (the key the runtime is meant to supply; how it
  reaches `forward` is PENDING RULING 2). Use it for non-idempotent effects (charge, ship, send) whose
  downstream honors that key.
- **`IDEMPOTENT`** — no marker; the author asserts `forward` is safe to run again, so recovery
  re-runs it. Use it for reads and for writes keyed by a natural idempotency key.

The key `(sagaId, stepIndex)` is the idempotency anchor (intended to be handed downstream — §8;
delivery PENDING RULING 2). Because the policy is mandatory, the dangerous case — a non-idempotent
effect left re-runnable by omission — cannot arise: it is a compile error, not a production incident.

#### 7.4.1 The two crash windows a `RUN_ONCE` marker cannot tell apart (#1827)

A recovering owner that finds `StepAttempt(sagaId, i)` and no committed result for step `i` is in one of
two durable-state-identical situations:

| Window | Sequence before the crash | External effect | Result `R` (e.g. `ChargeId`) |
|---|---|---|---|
| **W1 — crash before invocation** | marker committed → crash before `forward` reached the downstream | did **not** happen | does not exist |
| **W2 — crash after the effect, before result commit** | marker committed → downstream applied the effect → crash before `R` was committed | **did** happen | exists downstream, absent from the ledger |

What the marker establishes, by its mechanism (written under the fence *before* `forward` is invoked):
that an invocation **may** have started. It establishes neither that the effect occurred nor any value of
`R`. Two consequences follow from that alone and are not open:

- **A marker is never evidence of success.** Recording step `i` as completed (a `StepRecord`) on the
  strength of the marker would, in W1, advance the saga past an effect that never happened — and in both
  windows leave compensation without the `R` it is declared to receive (§7.2: `compensation(C, R)`).
  v0.5.0's §7.10 crash-window walkthrough did exactly this; it is withdrawn (§7.10).
- **Suppressing re-invocation is an at-most-once invocation policy, not an outcome.** It bounds how often
  `forward` runs; it does not decide what the saga does next.

**PENDING RULING 1 (§14 S6):** how the unresolved outcome is represented in `SagaState`, how it is
resolved (a downstream-idempotent re-invocation or an outcome lookup under the same logical operation
identity — which depends on RULING 2 — versus operator handling), what `run`/`status` report while it is
unresolved, and how a recovered `R` is obtained for compensation.

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

**Not yet represented (v0.6.0):** neither the §7.4 attempt marker nor a step whose outcome is unresolved
(§7.4.1) has a case in this hierarchy; adding one is part of **PENDING RULING 1**. `StepRecord.result` is
`Object`, so a later step has no typed access to it — **PENDING RULING 3**.

### 7.7 The `Saga` facade interface

```java
/**
 * A saga orchestrates a sequence of steps with paired compensations over a shared context C.
 * PLANNED (#354). Backed by a DurableEntity whose state is SagaState<C> (§7.1).
 * What run() returns while a RUN_ONCE step's outcome is unresolved is PENDING RULING 1 (§7.4.1).
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
keyspace            = "order-saga"
partition_count     = 32
replication_factor  = 3          # optional (#1564); absent: the cluster [replication] default
confirmation_factor = 2          # optional (#1564); absent: min(cluster default, replication_factor)
```

Design sketch: the keys above follow `DurableEntityConfig`. Terminal-state retention (`90d`) and an audit
stream (`order-saga-audit`) are intended features with no config key yet.

**Open (PENDING RULING 5, §14 S10):** the qualifier and section above bind *configuration* only. How a
specific `SagaDefinition` — its steps, the slice dependencies its steps call, and the codecs for `C`,
each `R` and the ledger — binds to this resource through the existing slice factory/provisioning path,
and how it is recovered by version after an owner change, is not specified. (A definition held in a
`static final` field, as §7.10 shows, cannot capture the injected `inventorySlice`/`paymentSlice`
dependencies its steps call.) Version binding of in-flight instances is **PENDING RULING 4** (§14 S9);
the current rollout code's behaviour is summarized in §7.11.

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

Note what the `charge-payment` forward does not pass: no operation identity reaches
`paymentSlice.charge`, so the downstream has nothing to dedup on (§7.4) — **PENDING RULING 2**.

**Slice usage** (PLANNED façade; factory-parameter injection as in §5.2, HTTP exposure in `routes.toml`):

```java
static OrderPlacement orderPlacement(@OrderSaga Saga<OrderContext> saga) {
    return new orderPlacement(saga);
}

record orderPlacement(Saga<OrderContext> saga) implements OrderPlacement {
    @Override
    public Promise<SagaResult<OrderContext>> placeOrder(PlaceOrderRequest req) {
        var context = new OrderContext(req.orderId(), req.customerId(), req.items(), req.total());
        return saga.run(req.orderId(), context);
        // On success  → SagaResult.Succeeded  (all 3 steps committed)
        // On failure  → SagaResult.Compensated (all compensations ran)
        //             → SagaResult.PartiallyCompensated (compensation failed; needs intervention)
        // Unresolved RUN_ONCE outcome → PENDING RULING 1
    }
}
```

**Crash-window behaviour** (step 2, `charge-payment`, a `RUN_ONCE` step). *Withdrawn in v0.6.0 (#1827).*
v0.5.0 recovered the `StepAttempt(orderId, 1)` marker, promoted it to completion without calling
`paymentSlice.charge`, and proceeded to `confirm-order`. In window W1 (§7.4.1) that confirms an **unpaid**
order; in both windows compensation would have no `ChargeId` to refund. The marker suppresses re-invocation
(at-most-once) and proves nothing else. The recovery behaviour for both windows is **PENDING RULING 1**;
the acceptance rows are §7.11 A1–A2.

### 7.11 Acceptance matrix for the saga/workflow implementation (#1827)

The scenarios #1827 requires the implementation (#354; workflow rows also #353) to pass. Each row names
the fault, what already holds by an existing decision or mechanism, the **must-be-zero** count the test
asserts (the instrument counts the failure, never the success), and the expected outcome. Expected
outcomes that depend on an open item read **PENDING RULING n** (§14 S6–S10) and are filled when the
ruling lands; nothing in this column is decided by this revision.

| ID | Scenario | Setup and fault | Holds regardless (decision · mechanism) | Must-be-zero count | Expected outcome |
|---|---|---|---|---|---|
| A1 | **Crash before invocation** (W1) | `RUN_ONCE` step `i`; kill the owner after `StepAttempt(id, i)` commits and before `forward` reaches the downstream | `forward` not re-invoked automatically (§7.4, at-most-once) · no `StepRecord` for `i` without its `R` (§7.4.1) · a deposed owner cannot commit (epoch fence, §4.2) | `StepRecord(i)` committed with no `R`; steps `> i` invoked | **PENDING RULING 1** (resolution path also **PENDING RULING 2**) |
| A2 | **Effect succeeds before result commit** (W2) | downstream applies the effect for step `i` and returns `R`; kill the owner before `R` is committed | as A1 · the downstream effect happened and is not reversed by any runtime action | downstream effects for `(id, i)` beyond one; compensation invoked without `R` | **PENDING RULING 1** (receipt recovery and identity: **PENDING RULING 2**) |
| A3 | **Crash during compensation** | step `k` fails; compensation of step `j < k` is invoked; kill the owner before its outcome commits | reverse order, best-effort, failures recorded, terminal `PartiallyCompensated`, no automatic compensation retry by default (§7.5) | a compensation skipped in the reverse sequence; a terminal `Compensated` while any compensation failed | **PENDING RULING 1** (re-invocation of an in-flight compensation) and **PENDING RULING 2** (compensation identity) |
| A4 | **v2 promotion with active v1 work** | instances started under v1 hold pending steps and timers; roll out v2 (rolling, A/B split or canary) and promote/complete | rollout uses the existing deployment mechanisms (#1827 constraint) · persisted state and external effects are not reversed by a routing change | a v1-started instance's step, timer fire or compensation executed by code it is not bound to | **PENDING RULING 4** |
| A5 | **v1 owner failure** | as A4 with v1 and v2 coexisting; kill the partition owner hosting v1-started instances | ownership fails over within the keyspace's hosting set (#345, feature-catalog #217) · the deposed owner cannot commit | v1-started work stranded with no node able to execute its bound code | **PENDING RULING 4** |
| A6 | **Rollback with live v2 work** | instances started under v2 hold pending steps, timers or compensations; roll back to v1 | rollback changes routing for new starts and does not reverse persisted state or external effects | a v2-started instance silently executed by v1 code; a partition refusing reads with no operator-visible cause | **PENDING RULING 4** |
| A7 | **Retirement while old code is referenced** | attempt to remove/deallocate v1 while v1-bound timers or compensations are pending | — (no retention check exists today, §7.11.1) | v1 code removed while v1-bound durable work is pending, unless the ruling defines that as permitted with a named outcome | **PENDING RULING 4** |

Rows A1–A3 are the façade's recovery contract; A4–A7 specialize the existing rollout processes for
long-lived durable instances. A row is not satisfied by a test whose fixture never reaches the fault
point: each must show, in the same run, that the fault landed (e.g. the marker committed and the
downstream was or was not reached) before asserting the outcome.

#### 7.11.1 What the current rollout code does to durable work (source read, `564d2d3df`, not run)

Evidence for RULING 4; describes today's code, decides nothing.

- **No durable version binding exists.** No entity record carries an artifact version, and `DurableEntity`
  has no version parameter (`DurableEntity.java:85`).
- **During an active rollout the requested version is not honoured.** `SliceInvoker.selectEndpoint` takes
  the base's `activeRouting` and picks old/new by weight (`SliceInvoker.java:997-1011, 1083-1101`), a
  per-call weighted round-robin over every version of the base (`EndpointRegistry.java:218-270`). Pinning
  the entry artifact therefore does not pin the dependent calls a step makes. Without an active rollout the
  pick is exact-artifact (`SliceInvoker.java:1013-1023`).
- **Completion and rollback deallocate code without consulting durable work.** `handleRoutingRemoval` →
  `removeNonTargetVersions` drops every non-target version and issues unloads
  (`ClusterDeploymentState.java:1777-1804`); rollback restores the target to the old version and removes
  the routing key in one batch, so the new version is deallocated by the same path
  (`DeploymentManagerImpl.java:374-396`). Target change (:1555-1570), target removal (:395-400) and
  blueprint removal (:1047-1061) deallocate the same way. None reads entity state or pending timers.
- **Two versions on one node share a keyspace without isolation.** Resources are cached per slice scope
  `groupId:artifactId:version` (`SpiResourceProvider.java:184-186, 502`; `SliceLoadingContext.java:439-441`),
  so each version provisions its own entity instance for the same keyspace, while the node registries are
  keyed by keyspace alone: timer and checkpoint drivers keep the FIRST registrant (`putIfAbsent`,
  `EntityTimerDriver.java:59-74`, `EntityCheckpointDriver.java:244`), the owner-forward registry keeps the
  LAST (`EntityForwardService.java:94-103`), and either version's unload unregisters all of them by
  keyspace (`DurableEntityFactory.java:268, 313-319`), including the surviving version's.
  `[unverified: read from source; no two-version run was performed]`
- **Retirement leaves state and timers in the log for whichever version still hosts the keyspace.** A
  record that build cannot decode or apply holds the partition's applied watermark, and the partition then
  refuses reads until a build that can apply it is deployed (#701, `EntityFold.java:253-271`;
  `guarantees.md` §6) — an availability event, not silent loss.

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
- **Idempotency** — *design intent, not on the shipped surface:* a stable per-entity monotonic counter
  `(key, n)` usable by slice side-effect code as an idempotency key. The shipped `update` returns the
  post-update `S` only (§5.1) and exposes no such counter (v0.6.0 correction). The saga's
  `(id, stepIndex)` anchor and its delivery to step code are **PENDING RULING 2** (§7.4).
- **Owner-handover unavailability** — entities on a partition are unavailable while the new owner
  is elected (seconds, SWIM). In-flight operations see a retriable `StaleOwnerEpoch` or timeout and
  are transparently retried by the runtime. The fence guarantees the *old* owner cannot commit after
  handover.

### 8.1 Read consistency (v1 — resolves S5)

Every read (`get`/`current`/`status`) takes an optional `ReadConsistency`:

```java
public enum ReadConsistency {
    /** Default. Local committed-prefix read: ~µs steady-state, no coordination, never torn.
        Staleness = REPLICATION lag of the entity's backing stream partition (ms healthy; grows
        while a replica is catching up or partitioned) — entity state lives on a replicated
        stream partition since I3, not in consensus-applied KV, so consensus apply lag is not
        the bound. Each access catches the local fold up to the log's current head before
        serving, which is what makes the lag the bound rather than a frozen rebuild-time
        snapshot. Monotonic per node. Keeps serving through owner failover and leader churn on
        nodes that HOLD the partition; a node outside the replica set refuses (PartitionNotHeld)
        rather than answering "absent" for keys it cannot see. */
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
  and rejecting with typed `EntityError` causes. A read reaching a non-owner is rejected
  `EntityError.NotCurrentOwner` (owner-forwarding an entity read cross-node needs transport
  that does not exist yet — a follow-up); a deposed owner is rejected
  `EntityError.StaleEpochRead`; and the un-wired in-memory cut (production cluster wiring is
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
- **Partial saga compensation** (PLANNED, §7.5) — tracked in `PartiallyCompensated` state; never silently discarded.
  The author/operator must resolve; the runtime provides visibility, not automated recovery.

---

## 10. Side Effects

The mutator is pure. After `update`/`dispatch` returns, the slice has the new state and performs
whatever side effect it implies (call a slice, HTTP, notify, DB) using its own resources — the runtime
does not run side effects on the slice's behalf (avoids re-creating Temporal's Activity model). The
**`RUN_ONCE`** step (§7.4, PLANNED) is the single managed affordance in this design: it bounds `forward`
to at-most-once invocation across recovery. It does not resolve the crash-after-effect-before-commit
window — the marker cannot tell that window from crash-before-invocation (§7.4.1), and the resolution is
**PENDING RULING 1**; end-to-end once-only still requires a downstream that dedups on an operation
identity the runtime delivers (**PENDING RULING 2**).

---

## 11. Substrate dependencies (epic pieces)

Status column updated in v0.6.0 for the pieces whose shipped state was verified at `564d2d3df`;
`guarantees.md` §6 is authoritative for wired entity behaviour.

| Piece | Status | Note |
|---|---|---|
| **0 — Persistent backing** (epic #349, sibling) | **PARTIAL** (v0.6.0) | the entity log is fsync'd to a per-partition WAL before ack and replicated at the keyspace's factors (`guarantees.md` §6, "crash-durable, not none"); #349's broader persistence work remains open |
| **1a — KV-path ownership fence** (#345) | **IMPLEMENTED** | `staleEpochWrite` + `EpochBearing` in `KVStore` Rabia applier; covers DHT + governor writes |
| **1b — Stream-path epoch fence** (#345) | **SHIPPED** (v0.6.0) | a deposed owner's entity-log append is refused `StaleEpochAppend` (`StreamEntityLogSubstrate.java:268-288`) |
| 2 — Per-key serialization queue | **SHIPPED** (v0.6.0) | `PerKeySerialExecutor`, used by `PartitionFencedDurableEntity` |
| 3 — Durable per-instance timers | **SHIPPED** (#351, closed) | a timer is a record in the entity's fenced log; owner-stamped instant |
| 4 — `DurableEntity` core | **SHIPPED** (#345 I1–I4) | fenced-log `PartitionFencedDurableEntity`, named-command API (§5.1), owner forwarding (#596) |
| 5 — Workflow facade (#353, was #190) | **PLANNED** — no code (rc5) | entity + `StateMachineDefinition`; definition registration PENDING RULING 5 |
| 6 — Saga facade + run-once step (#354) | **PLANNED** — no code (rc5) | step ledger + attempt marker; contracts reopened by #1827 (§7, PENDING RULINGS 1–5) |
| 7 — Observability / audit stream | new | metrics + opt-in transition/step audit to a stream |

Per-slice cron stays on `ScheduledTaskManager` (independent). **Two foundations gated this stack:** the
**#345 fence** (KV and stream paths both shipped, v0.6.0) and **#349 persistent backing** (partial, above).

---

## 12. Reconciliation to Existing Code

| Capability | Current | Target | Tag | Anchor |
|---|---|---|---|---|
| KV-path per-key fence | `staleEpochWrite` + `EpochBearing` **live in Rabia applier** | extend entity write to carry `ownerEpoch` as `EpochBearing` value | **REUSE** | `KVStore.java:87-127`; `EpochBearing.java` |
| Stream-path epoch fence | v0.2: no epoch-CAS on stream append | stream-path epoch check (#345 piece 1b) | **DONE** (v0.6.0) | `StreamEntityLogSubstrate.java:268-288` |
| `StateMachineDefinition` | exists, unused, in-memory | consume in the workflow facade (C=Unit for pure FSMs) | **REUSE** | `StateMachineDefinition.java:24` |
| Resource SPI | exists, mechanical | register `DurableEntity`/`PersistentWorkflow`/`Saga` types | **REUSE** | `SpiResourceProvider.java:45` |
| Per-key serialization | v0.2: none | owner-side per-key queue | **DONE** (v0.6.0) | `PerKeySerialExecutor.java` |
| Per-instance timers | v0.2: per-slice cron only | durable one-shot per-entity timers | **DONE** (#351) | `EntityTimerDriver.java` |
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

**Status (v0.6.0):** phases 0–2 have shipped (§11 pieces 1b, 2, 3, 4). Phases 3–4 are open (#353, #354;
rc5); phase 4 additionally waits on the #1827 rulings (§14 S6–S10).

**Acceptance:** a sample workflow *and* a sample saga run to completion across `kill-9` of the owning
node with no slice-author-visible errors; the fence rejects a stale-owner write in a split-brain test;
100k entities within memory/throughput budgets on a 5-node cluster. **For the saga, the §7.11 matrix is
part of this acceptance**, and where a ruling makes an outcome operator-visible (e.g. an unresolved
`RUN_ONCE` outcome), the matrix row governs over "no slice-author-visible errors".

---

## 14. Owner Decisions Still Needed

**S1–S5 resolved** (S3 on 2026-07-01; S1/S2/S4/S5 on 2026-07-04), recorded below with rationale.
**S6–S10 are OPEN** — reopened by #1827 (2026-10-02) and awaiting owner rulings; they are listed first.
The normative content lives in the referenced sections.

**S6 — RUN_ONCE recovery and the unresolved outcome (#1827 item 1). OPEN — PENDING RULING 1.** A
recovered attempt marker cannot distinguish W1 (no effect) from W2 (effect, receipt missing) (§7.4.1). To
decide: how an unresolved outcome is represented in `SagaState`; how it is resolved (downstream-idempotent
re-invocation or outcome lookup under the same logical operation identity, versus reconciliation/operator
handling); what `run`/`status` report meanwhile; how a recovered result reaches compensation; and the
corresponding rewording of #354's scope and acceptance. Fixed by #1827 regardless: success is never
recorded from an attempt marker alone.

**S7 — operation-identity delivery (#1827 item 2). OPEN — PENDING RULING 2.** §7.4 promises
`(sagaId, stepIndex)` downstream; `forward(C)`/`compensation(C, R)` carry no identity (§7.2). To decide:
the typed delivery mechanism and its lifetime — stable across retries and owner changes, distinct for a
genuinely new logical operation — compensation's identity, and recovery of a missing forward receipt; with
an executable author-facing example.

**S8 — typed forward-step dataflow (#1827 item 3). OPEN — PENDING RULING 3.** Each `forward` sees only `C`;
a step's `R` reaches only its own compensation. To decide: a supported typed composition or
persisted-state mechanism, or an explicit restriction of the initial façade. Acceptance example: step B
consumes step A's typed result after recovery, and A's compensation still receives that result.

**S9 — durable version binding, retirement and rollback (#1827 item 4). OPEN — PENDING RULING 4.** To
decide, as specializations of the existing rolling/A-B/canary/promote/rollback processes (§7.11.1 records
what they do today): whether a version is assigned at instance creation and persisted for signals,
timers, recovery and compensation (or an alternative with safe transitions); definition/artifact identity,
required dependency versions and state/command/result codec compatibility, with no silent fallback to the
latest code; retirement eligibility while durable work references old code, and execution availability
after owner failure; rollback for instances already started on the rejected version; and shared-keyspace
schema/driver lifecycle during coexistence, or an explicit initial restriction.

**S10 — initial wait/approval scope and definition registration (#1827 item 5). OPEN — PENDING RULING 5.**
To decide: how a specific workflow/saga definition, its injected dependencies, codecs and resources bind
through the existing slice factory/provisioning mechanism and are recovered by version (§7.9); and the
supported initial path for approval/deadline processes given saga `WAIT_SIGNAL` is deferred (S1, §6.6) —
including whether a durable workflow→saga handoff is supported — or a documented limitation.

**S1 — signals scope for v1. RESOLVED (2026-07-04): signal injection IS v1** (book requirement).
Scoped precisely: **workflow** signal injection ships in v1 as a thin external exposure of
`dispatch` (management triad, §6.6) — a signal is a dispatch, fenced like any write, no second
write path. **Saga** signals (a `WAIT_SIGNAL` step kind with park/timeout/compensation
semantics) are explicitly v2 — new step-kind design, not a thin exposure. The general
query API (list-by-state) remains v2.

**S2 — GC / terminal retention defaults. RESOLVED (2026-07-04): as proposed** (not implemented at
`564d2d3df`: there is no `terminal-ttl` config key, §5.2) —
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

## Changelog — v0.6.0 (2026-10-02)

**#1827, Phase A: reconcile with the shipped entity API, withdraw the RUN_ONCE contradiction, open the
saga/workflow contract items.** Decisions on S6–S10 are pending owner rulings; this revision records the
questions, the evidence and the acceptance matrix, and decides none of them.

| What | Why |
|---|---|
| §5.1 interface → shipped `DurableEntity<K, S, C extends Mutator<S>>`, both `scheduleTimer` entries, idempotent `cancelTimer`, no `delete` predicate | Every example used two type parameters and `Fn1` lambdas, which do not compile against the shipped artifact (CHANGELOG, named-command change). |
| §5.2 binding → per-keyspace qualifier (`entities.orders`), `resources.toml` section, factory-parameter injection with a named command; the `slice-manifest.toml` step removed | The manifest file and the `@Route` method annotation exist nowhere in the product; the shipped binding is the qualified factory parameter (`test-entity` blueprint). |
| §5.3 adds `TimerTokenMismatch`, `TimerDelayInvalid`, `TimerFireFailed`, `StorageUnavailable`; marks transient cases; reachability rewritten | The shipped `EntityError` has these cases; v0.4.0's "no owner-forwarding" caveat predates #596. |
| §2, §6, §7 labelled **PLANNED** (#353, #354; rc5) | No workflow or saga type exists in any Java source at `564d2d3df`. |
| §7.10 crash-window walkthrough **withdrawn**; §7.4.1 states both crash windows | It promoted an attempt marker to completion — in the crash-before-charge window that confirms an unpaid order, and compensation has no `ChargeId`. It contradicted §7.4's own at-most-once statement. |
| §7.2, §7.4, §7.6, §7.9, §8, §10 mark the gaps PENDING RULING 1–5 | The step API promised an identity it cannot deliver, carries no inter-step dataflow, and has no definition-registration path; §8's `(key, n)` counter is not on the shipped surface. |
| New §7.11 acceptance matrix (A1–A7) and §7.11.1 rollout-code evidence | #1827 acceptance; expected outcomes stay PENDING until the rulings. |
| §3, §11, §12, §13 status refreshed | Stream-path fence, per-key serialization, timers and the entity core have shipped; these tables still read MISSING. |
| §14 S6–S10 added as OPEN | The five #1827 items, phrased as the questions to rule on. |

---

## Changelog — v0.5.0 (2026-08-14)

**Error surface renamed to entity-centric names; spec and code now agree (#432 closed).**

v0.4.0 closed the divergence by amending the spec to the shipped names. The owner's call was to close
it the other way where the shipped names were the weaker ones, so the CODE moved instead:

| Was (shipped) | Now | Why |
|---|---|---|
| `DurableEntityError` | `EntityError` | symmetric with the sibling `StreamError`; `DurableEntityProvisioningError` → `EntityProvisioningError` with it |
| `KeyNotFound` | `EntityNotFound` | **three** distinct `KeyNotFound` types existed — JWKS keys (`SecurityError`), config keys (`ConfigError`), entity keys. Same name, three meanings |
| `KeyAlreadyExists` | `EntityAlreadyExists` | the thing that exists is an entity, not a key |
| `StaleOwner` | `StaleOwnerEpoch` | the epoch is what is stale, not the owner |
| `TimerNotFound(key)` | `TimerNotFound(key, TimerToken)` | a caller holding several timers cannot act on "a timer was not found" |

**Deliberately NOT renamed:** `NotCurrentOwner`, `StaleEpochRead`, `OwnershipNotYetCommitted`,
`LinearizableUnavailable`, `StorageFailed`, `TimerNotSupported`. The line drawn: the same name for the
same CONCEPT across subsystems is a feature — `StreamError.NotCurrentOwner` and
`EntityError.NotCurrentOwner` mean exactly the same thing about a partition owner. The same name for
DIFFERENT concepts is the defect, which is what `KeyNotFound` was.

Note the record's simple name is the wire value (`cause.getClass().getSimpleName()` in the fixture
slice), so the rename changed strings asserted by `DurableEntityForgeTest` and the `02w-entity-crash`
integration suite; both were updated with it.

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
