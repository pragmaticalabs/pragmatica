# Durable Single-Writer Entity — Design Specification

*The primitive for durable workflows & sagas.*

**Version:** 0.10.3
**Status:** Draft. **§5 (`DurableEntity`) is reconciled with the shipped named-command API**
(`DurableEntity<K, S, C extends Mutator<S>>`). **§6 (workflow) and §7 (saga) are PLANNED façades with no
production code** (#353, #354; milestone v1.0.0-rc5). **§7 was reopened by #1827 and its five contract
items are RULED** (owner, 2026-10-02; §14 S6–S10): three-way recovery by declared downstream capability
(§7.4), typed `StepContext` identity (§7.2), accumulating saga state (§7.3), version pinning at creation
with drain-then-retire (§7.9), and `WAIT_SIGNAL` specified now (§7.8). Implementation stays with #354;
§7.11 is its acceptance matrix. Earlier history: v0.5.0 entity-centric error names (#432); v0.3.0 closed
S1/S2/S4/S5. See changelog.
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
        └──►  Saga<I,S,O,D>              = entity that orchestrates steps + compensation     (PLANNED, #354)
```

Each layer is independently useful: the fence fixes a latent split-brain bug today (#345); the entity
serves any durable-single-writer need; workflow and saga are convenience facades.

---

## 3. Current Substrate (verified)

| Capability | State | Anchor |
|---|---|---|
| Custom-resource SPI (`@ResourceQualifier` + `ResourceFactory<T,C>` via `ServiceLoader`) | ✅ **mechanical** — a new entity resource is annotation + factory + services entry, no framework edits | `ResourceQualifier.java:13-18`; `SpiResourceProvider.java:33` (class), `:82` (`ServiceLoader` scan) |
| `StateMachineDefinition<S,E,C>` (builder, `transition(S,E,S)`, `onEntry`/`onExit`, `finalState(S)`, `build() → Result<...>`) | ✅ exists, **unused at runtime, in-memory only** | `StateMachineDefinition.java:25` (record), `:32` (`builder`), `:96-100` (`finalState`), `:114-116` (`transition(S,E,S)`), `:120-124` (`onEntry`), `:128-132` (`onExit`), `:134` (`build`); `InMemoryStateMachine.java:16-20` |
| Partitioned placement + per-partition owner (HRW) | ✅ exists (DHT ring, governor/owner) | `ReplicaPlacement.java:16-37`; `GovernorElection.java:36-45` |
| **Per-key write fence (single-writer enforcement)** | ✅ **IMPLEMENTED** — `staleEpochWrite` + `EpochBearing<E>` in `KVStore` Rabia applier; rejects any `Put` whose incoming epoch is strictly older than the committed one; deterministic (pure function of replicated state); covers governor + DHT ownership writes. The stream-path epoch fence (#345 piece 1b) has since shipped too — row below and §11. | `KVStore.java:275-282` (applied in `staleWrite`), `:314-331` (fence contract), `:356-360` (`staleEpochWrite`); `EpochBearing.java:35-47`; `AetherValue.java: DhtPartitionOwnershipValue`, `StreamPartitionOwnershipValue`; `BootstrapModule.java:383-409` |
| Per-key serialization queue (serialize same-key, parallel across keys) | ✅ **SHIPPED** (v0.6.0 status update) | `PerKeySerialExecutor.java` |
| Durable per-instance timers (one-shot, fire-and-delete, survive handover) | ✅ **SHIPPED** (#351, #345 I4): a timer is a record in the entity's own fenced log | `EntityTimerDriver.java`; `DurableEntity.java:144-222`; CHANGELOG "#351 / #345 I4" |
| Runtime→slice invocation (dispatch) | ✅ exists | `SliceInvoker.java:80-87` |
| Timer fire on the owner | ✅ **SHIPPED** (#351): applied in-process by the entity, not through `SliceInvoker` | `EntityTimerDriver.java:17-28` |
| Durable KV store (replicated, quorum) | ✅ exists, **in-memory — not restart-durable** (→ #349). No longer the entity's state store: since #345 I3 entity state lives on a fenced, fsync-durable, replicated stream log (§4.4). | `DHTClient.java:39-75`; `MemoryStorageEngine.java:69-73` |
| Stream-path epoch fence on the entity log append | ✅ **SHIPPED** (v0.6.0 status update): a deposed owner's append is refused `StaleEpochAppend` → `EntityLogError.StaleOwnerAppend` | `StreamEntityLogSubstrate.java:267-286` |

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
> in the KV Rabia applier and, since v0.6.0's status update, on the entity log's stream-path append too
> (#345 piece 1b, §3).
>
> **Why.** This is the per-partition-fenced-leader pattern. The owner serializes writes (per-key queue,
> §4.3); the epoch fence makes single-writer a *guarantee* across handover, not a convention. A reader
> on any node sees the last committed state because writes are RF-replicated under the fence. The
> fence is deterministic: every replica accepts or rejects identically (reads only committed state +
> the command, no wall-clock, no randomness — `EpochBearing.java:23-27`).
>
> **Rejected alternative.** *Unfenced owner* (the stream path before #345 piece 1b) — split-brain double-writes
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

*Rewritten v0.7.0 to the shipped mechanism (#351, #345 I4); the v0.2 design here — an in-memory wheel
per owner over a parallel key prefix, auto-cancelled on terminal state — was not what shipped.*

A schedule is a record appended to the entity's **own fenced log**, admitted and epoch-fenced like an
update; the pending set is folded from that log, so it survives handover and restart by the same
mechanism state does, and there is **no timer wheel** — a second, process-local copy could disagree with
the log after a handover. `EntityTimerDriver` ticks once per second and asks each registered keyspace's
entity what is due on partitions it owns; a due timer's `onFire` command is applied on the owner through
the same per-key path as an external update (`EntityTimerDriver.java:17-28`). One-shot; leaves the pending
set when it fires. `delete` auto-cancels the key's pending timers; there is no terminal-state predicate
(§5.1). A fire that cannot be applied is consumed and logged, not retried (`TimerFireFailed`, §5.3).
(Distinct from per-slice cron — §11.)

### 4.6 Hot-entity bottleneck (acknowledged)

A single high-traffic entity (e.g. a global counter) is an inherent single-writer bottleneck — no
design can parallelize same-entity mutations without abandoning the single-writer guarantee. Authors
must shard such entities by key if throughput demands it. This is not a design gap; it is the
correct trade-off stated explicitly: single-writer = serialized = bounded throughput per entity.

---

## 5. The `DurableEntity` API

### 5.1 Interface

**Reconciled with the shipped surface (v0.6.0, #1827).** This is the interface as it ships in
`aether/resource/durable-entity/.../DurableEntity.java:86-263`; the shipped javadoc is authoritative for
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

    /** Retry-safe entry: a re-send carrying a token ALREADY PENDING for this key is that same schedule. */
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
`EntitySlice.java:132-148`), which follows the same pattern with its own variants (`SetAmount`,
`Expire`); `Cancel` above is illustrative.

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
> exists in production Java. Everything in §6 is design intent. **Owner decision D4 (2026-10-02):** §7.9's
> definition registration and version-binding rules apply to workflows too — registration through the
> slice factory, pin at creation (definition + artifact + dependency versions), all bound work and
> dependent calls routed by the binding, loud failure on missing bound code, per-binding decoding with a
> drift guard and contract fingerprints (D8 refined), drain-then-retire with reconcile-only retention (D3), rollback of new starts only.
> **This is added scope for #353**: the workflow façade carries the same rollout-side work as #354, and its
> acceptance needs the A4–A9 rows of §7.11 restated for workflow instances.

### 6.1 Decision — keep `PersistentWorkflow` as a distinct public facade

> **Decision.** `PersistentWorkflow<S,E>` remains a **distinct public facade** over a
> `DurableEntity<String, S, C>` (its command type `C` and definition binding follow §7.9). It is NOT replaced by `DurableEntity` + a raw `StateMachineDefinition`
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

**Saga signals — superseded 2026-10-02 (#1827 R5):** the saga `WAIT_SIGNAL` step kind is now specified
in rc4 (§7.8: identity and dedup, buffering, deadline outcomes, version pin, recovery); implementation
stays with #354. The historical text follows. An external signal would need a
`WAIT_SIGNAL` step kind (a step that parks the saga until a matching signal arrives) — a new
step-kind design with its own timeout/compensation semantics, deliberately out of v1 scope.
Workflows cover the v1/book requirement; the FSM *is* the signal consumer.

## 7. Saga specialization

> **Status: PLANNED — no production code** (#354, milestone v1.0.0-rc5; verified at
> `release-1.0.0-rc4` `564d2d3df`: no `Saga`, `SagaStep`, `SagaDefinition`, `SagaState`, `SagaCause` or `SagaError`
> type exists in any Java source, main or test). **The contracts below are RULED** (owner, 2026-10-02,
> #1827 items 1–5 → §14 S6–S10) and normative for the #354 implementation; the implementation itself
> stays with #354. The §7.11 matrix is its acceptance.
>
> Constraints carried unchanged (#1827): state-based recovery without replaying the author's program;
> JBCT-native types and `Promise`-returning behaviour; existing slice and rollout mechanisms, specialized
> rather than replaced (§7.9); best-effort reverse compensation with explicit `PartiallyCompensated` and no
> automatic retry of a FAILED compensation (§7.5); no consensus journal/WAL — attempt, result,
> compensation and signal records are application state of the saga entity, held in its fenced log like
> any entity state (§4.4), not consensus persistence.
>
> Spec-author choices that the ruling text does not itself fix are tagged **[author choice]** so a
> reviewer can contest each one separately.

### 7.1 Model

A saga is a durable entity keyed by saga id whose state is a `SagaInstance<S>` (§7.6): the
version binding fixed at creation (§7.9), an **accumulating saga state `S`** declared by the author (a
JBCT value type, §7.3), and a **step ledger** recording forward progress, in-flight attempts,
unresolved outcomes, waits and — on failure — reverse compensation. The runtime drives the ledger on
the partition owner, under the epoch fence; recovery reads the persisted `SagaInstance` and continues
from it. Nothing the author wrote is re-executed to rebuild state (no replay): only an **in-flight**
step may be re-invoked, and only as its declared recovery capability allows (§7.4).

### 7.2 Step identity — `StepContext` (R2)

Every forward, outcome lookup and compensation receives an explicit, typed `StepContext`. It is the only
identity a step needs to hand a downstream, and the author never derives one by hand.

```java
/** Identity of one logical saga instance: stable for its whole life, never reused. */
public record SagaInstanceId(String keyspace, String sagaId, long incarnation) {}

/**
 * Identity handed to step code. operationId and compensationId are deterministic functions of
 * (instance, stepIndex), so they are stable across retries, recovery, owner changes and redeploys.
 */
public record StepContext(SagaInstanceId instance,
                          int stepIndex,
                          String stepName,
                          OperationId operationId,       // the forward's idempotency / lookup key
                          OperationId compensationId,    // the SAME step's compensation key — always distinct
                          int attempt) {}                // 1, 2, …  DIAGNOSTIC ONLY — never part of any key

public record OperationId(String value) {}
```

**Derivation — a fixed-length digest (owner decision D6).**

```
operationId    = base32( SHA-256( enc("aether.saga.fwd.v1") ‖ enc(keyspace) ‖ enc(incarnation) ‖ enc(sagaId) ‖ enc(stepIndex) ) )
compensationId = base32( SHA-256( enc("aether.saga.cmp.v1") ‖ enc(keyspace) ‖ enc(incarnation) ‖ enc(sagaId) ‖ enc(stepIndex) ) )

enc(string) = 4-byte big-endian length ‖ UTF-8 bytes      enc(long) = 8 bytes big-endian      enc(int) = 4 bytes big-endian
base32      = RFC 4648 alphabet, lower-case, no padding
```

- **Fixed length: 52 characters.** 256 bits at 5 bits per character is 51.2, so 52 characters carry the
  whole digest: nothing is truncated, and the collision bound is the full SHA-256 one. For `n` distinct
  identities the probability that any two collide is at most `n² / 2²⁵⁷`; even `n = 2⁶⁴` identities gives
  at most `2⁻¹²⁹`. A shorter length, should one ever be needed, keeps `b = 5 × length` bits and the bound
  becomes `n² / 2^(b+1)` — state it at the same time as the change.
- **Unambiguous.** Every field is length-prefixed, so no choice of `keyspace` or `sagaId` text, however
  it uses `/` or any other character, can make two different tuples encode to the same bytes (v1828's
  objection to the v0.7.0 `/` join).
- **Within downstream key limits.** 52 ASCII characters fit common idempotency-key limits — Stripe accepts
  keys of up to 255 characters (Stripe API reference, "Idempotent requests"); an author whose downstream is
  stricter must declare that step `Neither` or wrap the key.
- **Reveals nothing.** The key carries no business id (`sagaId` is often an order or customer id); a
  downstream sees an opaque token.
- **Distinct forward vs compensation** by the domain tag, not by a suffix the downstream might strip.
- **The readable tuple is kept alongside.** `StepContext` and every operator view, log line and
  `Reconcile` reason carry `(keyspace, incarnation, sagaId, stepIndex, fwd|cmp)` next to the digest, so an
  operator matching a downstream record to a saga never has to invert a hash.

Both are computed from persisted fields of the instance, never from a clock, node, attempt or random
source, so every owner that ever holds the instance computes the same values.

- **Stable across retries and owner changes** — by construction: none of the inputs changes when the
  owner, node, attempt or code version changes.
- **Distinct for forward vs compensation** — the `fwd` vs `cmp` domain tags, so a downstream that dedups
  on the key can never mistake a refund for a repeated charge.
- **`attempt`** counts this step's attempts recorded in the ledger — invocations, re-invocations and
  lookups (§7.4, D7). It exists for logs, operator views and the retry budget; a downstream MUST NOT key on
  it, and the runtime never uses it in a key.

**What a "genuinely new logical operation" is.** Exactly one thing mints new identities: **creating a new
saga instance**. Each step index of an instance is one logical operation for its whole life. Re-invocation
after a crash, a timeout, an owner change, a redeploy, an operator `resolve`, or a version drain is the
*same* operation and carries the *same* `operationId`. A caller that wants the effect again (a second
charge) must start a new saga instance; there is no API to re-mint a step's identity inside an instance.

**`keyspace` and `incarnation` are part of the key (confirmed by D6).** Both are needed for "distinct for a
genuinely new logical operation":
`keyspace`, because two saga types may reuse a business id (`order-saga/42` and `refund-saga/42`) against
one shared downstream; and `incarnation`, minted once when the instance is created and persisted with it,
because `delete(id)` followed by `run(id, …)` creates a NEW instance under the same `sagaId` — without the
incarnation its steps would present the deleted instance's keys, and a REPLAYABLE downstream would answer
with the OLD receipt for a NEW order. The incarnation is a value the owner mints at create, e.g. the
create record's log offset `[mechanism: unique per (keyspace, partition) log, persisted in SagaInstance]`.

### 7.3 Steps, accumulating state and definitions (R3)

The saga declares a state record `S` (immutable, JBCT value type). Each step's forward reads `S`, returns
a result `R`, and a **pure fold** `(S, R) → S` folds the result into the state. The runtime persists the
new `S` together with the `StepRecord` in one fenced write, so a later step reads earlier results from
`S` — typed, and present after any recovery without replay. A step's compensation still receives that
step's own `R`.

```java
/** How a step's in-flight outcome is recovered (R1). Mandatory — there is no default (S3 carried over). */
public sealed interface StepRecovery<S, R> {
    /**
     * Re-invoking with the same operationId returns the ORIGINAL result (downstream dedups on the key).
     * keyRetention: how long the DOWNSTREAM remembers a key (e.g. a provider's idempotency-key window).
     */
    record Replayable<S, R>(Duration keyRetention) implements StepRecovery<S, R> {}
    /**
     * A declared outcome query. some(R) = the operation happened, with this result;
     * none() = it did not happen AND no earlier attempt carrying this key can still apply — the query is
     * ORDERED against the operation (§7.4). keyRetention: how long the downstream can answer for a key.
     */
    record Lookup<S, R>(Duration keyRetention,
                        Fn2<Promise<Option<R>>, StepContext, S> outcome) implements StepRecovery<S, R> {}
    /** Neither: an unresolved outcome parks the saga for the operator. */
    record Neither<S, R>() implements StepRecovery<S, R> {}
}

/** A forward step. Fn2/Fn3 are org.pragmatica.lang.Functions.FnN (return type first). */
public record SagaStep<S, R extends SagaData>(
    String                               name,
    Fn2<Promise<R>, StepContext, S>      forward,           // performs the effect; hands ctx.operationId() downstream
    Fn2<S, S, R>                         fold,              // PURE (S, R) -> S; no IO
    StepRecovery<S, R>                   recovery,          // forward's in-flight recovery (§7.4)
    Fn3<Promise<Unit>, StepContext, S, R> compensation,     // undoes the step given ITS OWN R; ctx.compensationId()
    StepRecovery<S, Unit>                compensationRecovery, // [author choice] same three-way rule for compensation (§7.5)
    RetryBudget                          retry,             // ONE budget for retries, re-invokes and lookups (§7.4, D7b, E2, E4)
    ReplyClassifier                      replies            // reply -> evidence mapping; ReplyClassifier.DEFAULT (§7.4, E5)
) implements SagaStepKind<S> {}

/** A wait step (R5, §7.8): parks until a named signal or its deadline. */
public record WaitStep<S, P extends SagaData>(
    String               name,          // the SIGNAL NAME senders address; unique within the definition
    Class<P>             payload,
    Fn2<S, S, P>         fold,          // PURE (S, P) -> S: the payload folds into S like a step result
    Duration             deadline,      // measured from entering the wait
    OnDeadline<S>        onDeadline,    // declared by the author: continue with a default, or compensate
    int                  maxFireAttempts // failed DeadlineFired fires before NeedsReconciliation (default 5; E6)
) implements SagaStepKind<S> {}

public sealed interface OnDeadline<S> {
    record ContinueWith<S>(Fn1<S, S> defaultFold) implements OnDeadline<S> {}   // pure
    record Compensate<S>()                       implements OnDeadline<S> {}
}

public sealed interface SagaStepKind<S> permits SagaStep, WaitStep {}

/**
 * Built once per slice load inside the slice factory (§7.9), never in a static field.
 * I — the start input (contract);  S — the accumulating state (PRIVATE);  O — the output (contract);
 * D — the data root: step results and signal payloads (contract, §7.9).        (owner decision D9)
 */
public final class SagaDefinition<I, S, O, D extends SagaData> {
    /**
     * clockSafetyMargin: required, no default — see the key-retention rule in §7.4 (v1828 B1).
     * init:   PURE I -> S, applied once at creation, on the owner, under the binding.
     * finish: PURE S -> O, applied when the saga completes; its result is the Succeeded outcome.
     */
    public static <I, S, O, D extends SagaData> Builder<I, S, O, D> builder(String name, int definitionVersion,
                                                                            Duration clockSafetyMargin,
                                                                            Fn1<S, I> init,
                                                                            Fn1<O, S> finish) { ... }

    public static final class Builder<I, S, O, D extends SagaData> {
        public <R extends D> Builder<I, S, O, D> step(SagaStep<S, R> step) { ... }
        public <P extends D> Builder<I, S, O, D> await(WaitStep<S, P> wait) { ... }
        /** Fails (typed) on duplicate step/wait names, an empty definition, or a wait as the last step. */
        public Result<SagaDefinition<I, S, O, D>> build() { ... }
    }
}
```

**Input, state and output are separate (owner decision D9).** `run` takes an `I`; the pure `init` turns it
into the first `S`; the steps fold into `S` as ruled (R3); the pure `finish` turns the final `S` into an `O`.
`I`, `O`, the signal payloads and the receipts form the **contract** — they cross the instance boundary to
and from callers that are not bound to its version. `S` never crosses it, so it is **private** and evolves
freely under pin-at-creation (§7.9).

`SagaData` is a marker the saga's **data root** extends: the author declares one sealed interface
`D extends SagaData` that permits every step result `R` and every signal payload `P` (§7.9 explains why:
it is how they get codecs through the shipped sealed-root recursion). `fold`, `onDeadline` and the
`defaultFold` are pure, like a `Mutator` (§5.1), and run on the owner inside the per-key serialization.

**Why folding into `S` rather than handing `R`s forward [author note on R3].** State-as-truth: what a
later step can see is exactly what is persisted, so recovery cannot produce a view that a crash-free run
would not have produced. Two steps that both need the same reservation read it from `S`; nothing walks
the ledger by index. Arbitrary typed sequential dataflow is therefore available — but only through `S`,
whose shape the author owns and versions with the definition (§7.9).

### 7.4 Forward-step recovery (R1)

Before invoking a forward the runtime commits `StepAttempt(stepIndex, attempt, at)` under the fence, where
`at` is the committing owner's clock reading (§7.6). A
recovering owner that finds an attempt and no `StepRecord` for that step is in one of two situations it
cannot tell apart from the marker:

| Window | Sequence before the crash | External effect | Result `R` (e.g. `ChargeId`) |
|---|---|---|---|
| **W1 — crash before invocation** | marker committed → crash before `forward` reached the downstream | did **not** happen | does not exist |
| **W2 — crash after the effect, before result commit** | marker committed → downstream applied the effect → crash before `R` was committed | **did** happen | exists downstream, absent from the ledger |

**A marker NEVER becomes success.** It proves an invocation may have started — nothing about the effect
and no value of `R`. A `StepRecord` is committed only with an `R` that came from the forward, a `Lookup`,
or an operator (`resolve`, §7.7).

The same unresolved state arises **without a crash** when a forward's promise fails after its request may
have reached the downstream. **Owner decision D1 (v1828 B2): failures are classified by whether the
request was SENT, not by retryability** — `Cause.Transient` is the wrong axis (a never-sent request to a
missing endpoint is `Transient`; an unclassified failure after a write is not).

The invoker tags every failure it produces (implementation with #354):

```java
/** Carried by every SliceInvoker failure (and by any resource client a step uses that adopts it). */
public sealed interface DeliveryEvidence {
    /** Refused before the request left this node: no endpoints, circuit open, serialization error. */
    record NotSent()  implements DeliveryEvidence {}
    /** The request may have reached the downstream: timeout, connection reset after write, or anything unclassified. */
    record Sent()     implements DeliveryEvidence {}
    /** The downstream received the request and replied with a refusal (decoded from its own reply). Definite. */
    record Answered() implements DeliveryEvidence {}
}
```

- **`NotSent` is definite:** the step took no effect. It is **retried with backoff inside the step's
  retry budget** (below, D7a) — no endpoints, an open circuit and a serialization fault included — and only
  an exhausted budget parks the saga. A `NotSent` gap shorter than the remaining backoff schedule therefore
  never parks; with the default budget and no earlier attempts that is at least 381 s (the bound is
  derived under "Retry budget" below).
- **`Sent` is ambiguous:** the step takes the R1 recovery path below (re-invoke, look up, or park), within
  the key-retention window.
- **No tag means `Sent`.** A failure from a resource that does not carry the evidence — author code, a
  third-party client — is treated as possibly delivered. Being unclassified never makes a failure definite.
- **An ANSWERED refusal is definite (CTO-confirmed 2026-10-02 as consistent with D1).** A failure decoded
  from the downstream's own reply that is a **definitive business refusal** (a declined card, a typed
  refusal returned by the remote slice) proves the request arrived and was refused. It carries the third
  tag, `Answered`, definite like `NotSent` but never retried: the runtime treats it as the step's failure
  and compensates.
- **Not every reply is an answer (v1828 N5, CTO ruling E5).** A reply that settles nothing is not
  `Answered`. Each step declares a `ReplyClassifier` that maps a failure decoded from a reply to evidence;
  the transport's tag (a reply arrived, so the request was sent) is the input, and the classifier refines
  it:

  ```java
  @FunctionalInterface
  public interface ReplyClassifier {
      DeliveryEvidence classify(Cause replyFailure);

      /**
       * DEFAULT:
       *  - "in progress", "conflict on key", "concurrent request" (e.g. Stripe's 409 for an idempotency
       *    key whose first request is still running; HTTP 409/425)          -> Sent      (ambiguous)
       *  - an explicit "not processed" / "rate limited" (e.g. HTTP 429)    -> NotSent   (definite, retried)
       *  - a cause implementing StepRefusal (a definitive business refusal) -> Answered  (definite, not retried)
       *  - anything else decoded from a reply                              -> Sent      (ambiguous)
       */
      ReplyClassifier DEFAULT = ...;
  }

  /** Marker a slice's typed business-refusal causes implement so DEFAULT can recognise them. */
  public interface StepRefusal extends Cause {}
  ```

  The default is conservative by construction: only a cause that says it is a refusal becomes `Answered`;
  an unrecognised reply is `Sent`. The `StepRefusal` marker as the recognition mechanism is
  **CTO-confirmed (2026-10-02)**; the HTTP-status rules apply only to failures that carry a status.
- **Evidence is persisted (v1828 N7, CTO ruling E7).** Each `StepAttempt` records the evidence its
  attempt ended with (§7.6), so an owner that takes over can tell `NotSent`-only histories from ones that
  may have reached the downstream. **An attempt whose evidence was never recorded counts as `Sent`** — a
  crash between sending and recording leaves exactly that gap.
- **Every attempt is bounded (v1828 N2, CTO ruling E2).** The runtime bounds each invocation, re-invocation
  and lookup by the step's declared `attemptTimeout` (part of `RetryBudget`, default 30 s). Expiry counts
  as **`Sent`** — the request may still be in flight — and counts against the budget, and the next action
  follows the R1 path. A reply that arrives after expiry is never applied automatically. For a `Replayable`
  or `Lookup` step it is dropped (the re-invoke or lookup recovers the receipt). For a **`Neither`** step it
  is recorded on the instance as a **late receipt** (`LateReceipt(stepIndex, attempt, encoded R)`), visible
  in the operator listing and `status`, as evidence the operator can use for `SucceededWithReceipt`
  (v1828 nit). **No `Running` step can be stuck
  forever:** every attempt ends within `attemptTimeout`, and attempts end at `maxAttempts`, so a step either
  completes, fails definitely, or parks.

| Declared capability | Recovery action for an in-flight step | What it earns, and the condition it rests on |
|---|---|---|
| `Replayable` | within the key-retention window (below): re-invoke `forward` with the **same** `operationId` (`attempt` + 1, same persisted `S`); commit the returned `R`, fold, continue. Past the window: as `Neither` | the effect is applied at most once **and** the receipt is recovered — **iff** the downstream dedups on `operationId` and answers a repeat with the original result. The runtime cannot check that; the declaration is the author's assertion about the downstream. |
| `Lookup` | within the key-retention window (below): call `outcome(ctx, S)`. `some(R)` → commit `R` as the step's result. `none()` → invoke `forward` (same `operationId`). Past the window: as `Neither` | the receipt is recovered when the operation happened; a `none()` is "safe to invoke" **iff** the query is ordered against the operation — no earlier attempt carrying this `operationId` can still be applied after the query answers `none()` (e.g. the downstream records the key durably before acting, or rejects late arrivals). A query that can race an in-flight earlier attempt makes `Lookup` unsound; such a downstream must be declared `Neither`. A lookup that itself fails is retried inside the step's retry budget and is never treated as `none()`; an exhausted budget parks the step `OutcomeUnknown`. |
| `Neither` | no invocation. The step enters **`OutcomeUnknown`** and the saga parks in **`NeedsReconciliation`** (§7.6) | nothing is guessed. The operator resolves it (§7.7) as **succeeded-with-receipt** (supplies `R`), **failed** (no effect), **compensate**, or — as last resort — **abandon** (§7.7, D3). The parked instance keeps its version pin (§7.9) and is counted by the retirement gate. |

**Key-retention window (v1828 B1, CTO ruling T1).** A downstream remembers an operation key for a
bounded time (a payment provider's idempotency-key window, a search index's retention). Re-invoking or
querying after the downstream has forgotten the key is not `Replayable`/`Lookup` any more: a re-invoke
would apply the effect a second time, and a lookup would answer `none()` for an operation that happened.
So `Replayable` and `Lookup` each declare the downstream's `keyRetention`, and the definition declares a
`clockSafetyMargin` (required, §7.3). Recovery — after a crash, after an ambiguous failure, after a park
for any reason, after a drain — re-invokes or looks up **only while**

    now − origin  <  keyRetention − clockSafetyMargin
    origin = at of the EARLIEST StepAttempt of this step whose evidence is Sent or unrecorded

and otherwise treats the step as `Neither`: `OutcomeUnknown` → `NeedsReconciliation`, no invocation. The
same rule applies to an in-flight compensation, against `compensationRecovery`'s window.

**The origin is the earliest possibly-sent attempt, never the latest (v1828 N3, CTO ruling E3).** A
downstream's key window runs from the first time it saw the key; re-invoking does not restart it. Measuring
from the latest attempt would let every re-invoke push the limit out and reopen B1. Attempts whose evidence
is `NotSent` are excluded — the downstream never saw the key on those — and if every attempt so far was
`NotSent`, no window constrains the step yet.

*Clock assumption, stated:* `attempt.at` is stamped by the owner that committed the marker and `now` is
read on the owner performing recovery, possibly another node. The comparison is correct **iff** the
declared margin exceeds the skew between any two owners' clocks plus the skew between the cluster and the
downstream's clock for its window. The marker is committed before the request is sent, so the
downstream's own window starts no earlier than `attempt.at`; that delay errs on the safe side and needs no
margin. Nothing in the runtime measures the skew; the margin is the author's declaration, like the
capability itself.

**Retry budget (owner decision D7b, closes v1828 B3).** Every forward step declares ONE `RetryBudget` —
a maximum number of attempts and a maximum total time, with defaults — and that single budget is shared
by everything the runtime does for the step without a fresh decision: retries of `NotSent` failures,
`Replayable` re-invocations and `Lookup` queries. It is never infinite. Each such action commits a new
`StepAttempt` (`attempt + 1`) first, so the count survives an owner change.

**Episodes (v1828 N4, CTO ruling E4).** The budget has two limits measured differently:
- **`maxAttempts` is cumulative and persisted** — counted from the step's `StepAttempt` records across
  every owner, so no handover resets it.
- **`maxElapsed` bounds one retry EPISODE.** An episode is the run of attempts made by one owner (one
  owner epoch, recorded on each `StepAttempt`); it starts at that owner's first attempt for the step. An
  ownerless interval ends the episode — no attempts are made while nobody owns the partition — and the new
  owner starts a new episode, with backoff starting again at `initialBackoff`. An ownerless interval
  therefore never consumes `maxElapsed`, and a step resumed after a long handover is bounded by its
  remaining attempts and by the key window, not by time it spent unowned.

Backoff is exponential, capped, with upward-only jitter (each wait is the computed delay × a uniform factor
in [1.0, 1.25]), so the computed schedule is a lower bound on the time attempts take. **CTO-confirmed
(2026-10-02):** the 381 s arithmetic below and the upward-only jitter.

```java
public record RetryBudget(int maxAttempts, Duration maxElapsed, Duration attemptTimeout,
                          Duration initialBackoff, Duration maxBackoff) {
    /** Defaults per CTO ruling E4 (attemptTimeout per E2): 20 attempts, 10 min/episode, 30 s, 200 ms→30 s. */
    public static final RetryBudget DEFAULT = new RetryBudget(20, Duration.ofMinutes(10), Duration.ofSeconds(30),
                                                              Duration.ofMillis(200), Duration.ofSeconds(30));
}
```

**What the defaults add up to.** 20 attempts have 19 waits between them: 0.2, 0.4, 0.8, 1.6, 3.2, 6.4,
12.8, 25.6 s (51.0 s), then 11 waits at the 30 s cap (330 s) — **381 s ≈ 6 min 21 s** of backoff before
jitter, up to 476 s with it. The two limits bind together: attempts that fail fast (`NotSent`) exhaust
`maxAttempts` after 381–476 s, inside the 10-minute episode; attempts that run to `attemptTimeout` (20 ×
30 s plus the backoff) reach `maxElapsed` first.

**The real "a restart never parks" bound.** A `NotSent` gap — no endpoint, an open circuit — parks the
step only if it outlasts the backoff schedule of the step's **remaining** attempts. For a step with no
earlier attempts that is at least 381 s under the defaults, so a restart or rolling replacement shorter than
that does not park. Attempts already spent (cumulative) shorten it; a gap longer than it parks
`RetriesExhausted`/`BoundVersionUnavailable`, resolved by `Resume` (§7.7). An author whose dependency can be
absent longer declares a larger budget.

**Whichever expires first wins:** the budget (attempts, or `maxElapsed` within an episode) or the T1
key-retention limit `keyRetention − clockSafetyMargin` from the origin above. On exhaustion the step parks in `NeedsReconciliation` with
`OutcomeUnknown` if any attempt carried `Sent` evidence (the effect may have happened), or with
`RetriesExhausted` if every attempt was `NotSent` (it definitely did not) — decidable after a handover
because the evidence is persisted (E7) **[author choice: the split; D7 says "exhaustion →
NeedsReconciliation"; v1828 accepted it]**. `RetriesExhausted` caused by no live endpoint for a bound
version is reported as `BoundVersionUnavailable`. **[author choice, v1828 accepted]** A step's compensation is governed by
the same declared budget, counted separately from its forward; its exhaustion parks
`CompensationOutcomeUnknown` if any compensation attempt may have been sent, or
`RetriesExhausted(compensation = true)` if all were `NotSent` (E7).

`IDEMPOTENT`/`RUN_ONCE` (v0.5.0) are superseded: `Replayable` covers what `IDEMPOTENT` meant *and* says
how the receipt comes back; `RUN_ONCE`'s at-most-once invocation is what `Neither` gives, now with a
defined outcome instead of a silent promotion. The S3 property is kept — the capability is a required
component, so a step cannot be declared without stating it.

### 7.5 Compensation semantics

> **Decision (unchanged, v0.2).** Compensation is **best-effort reverse**: compensations run in reverse
> step order (highest completed step index down to 0); a compensation that **definitely** fails is recorded
> in the ledger and does not stop the remaining compensations. A saga that exits compensation with one or
> more failed compensations lands in `PartiallyCompensated` (not `Compensated`). A FAILED compensation is
> not retried automatically; retrying is the author's or operator's action (e.g. a monitoring slice that
> observes `PartiallyCompensated` sagas).
>
> **Why.** Guaranteed compensation requires an unbounded retry loop, which hides errors and can loop
> forever on a permanently broken downstream. Best-effort-with-explicit-partial-state gives visibility
> and control. **Rejected:** guaranteed compensation (infinite retry); stop on first compensation failure.

**Crash or ambiguous failure DURING a compensation (v0.7.0, from R1 + R2) [author choice].** An in-flight
compensation is not a failed one, so the no-retry rule above does not decide it. Each step declares a
`compensationRecovery` with the same three values, keyed by `compensationId`: `Replayable` re-invokes the
compensation with the same `compensationId`; `Lookup` queries and invokes only on a sound `none()`;
`Neither` records `CompensationOutcomeUnknown` and parks the saga in `NeedsReconciliation` (operator:
compensated, or failed → counts toward `PartiallyCompensated`). Wait steps have no compensation — they
have no external effect; their folded payload is part of the snapshot `S_j` of every later step `j`, and so reaches that step's compensation (D2).

**Compensation input — owner decision D2: a snapshot as of the step.** Compensation of step `j` receives
`(ctx, S_j, R_j)`: `R_j` is the step's own committed result, and `S_j` is the state **right after step
`j`'s fold** — not the current state, which may include later steps that were already undone. The runtime
persists `S_j` with each `StepRecord` (it is written in the same append as the fold, §7.3) and keeps it
until the instance is terminal; at the terminal transition the snapshots are dropped and only the final
`S` and the results remain. A step without a `StepRecord` is never compensated, except through the
operator's **compensate** resolution, which supplies `R_j`; the runtime then computes
`S_j = fold(S_{j−1}, R_j)` before compensating.

*Storage bound:* a non-terminal instance holds at most one encoded `S` per completed forward step, i.e.
`≤ (forward steps in the definition) × (max encoded size of S)` in addition to its results and current
state. A definition with many steps and a large `S` should keep `S` small — it is the cost of D2's
exactness, paid only while the instance is live.

### 7.6 Persisted shape — `SagaInstance` and the ledger

Everything below is persisted in the saga entity's fenced log (state-as-truth); the owner folds it, any
replica-holder can rebuild it, and no author code runs to rebuild it.

```java
/** The durable state of one saga instance (the entity's S). */
public record SagaInstance<S>(SagaInstanceId id,
                              VersionBinding binding,          // fixed at creation, never rewritten (§7.9)
                              S state,                          // accumulating state after the last fold
                              List<StepRecord> completed,       // in step order
                              SagaPhase phase) {}

/** stateAfter: S_j, the encoded state right after this step's fold (D2); dropped at the terminal state. */
public record StepRecord(int index, String name, byte[] encodedResult, String resultType,
                         Option<byte[]> stateAfter, Instant at) {}

public sealed interface SagaPhase {
    /** Executing; inFlight is the attempt marker of the step being invoked, if any. */
    record Running(int nextStep, Option<StepAttempt> inFlight)                    implements SagaPhase {}
    /** Parked on a WaitStep (§7.8). */
    record Waiting(int waitStep, Instant deadlineAt, String deadlineTimerToken)   implements SagaPhase {}
    /**
     * Parked for the operator. Non-terminal. `suspended` is the phase it parked FROM — Running, Waiting
     * or Compensating, with every field intact — so a resolution resumes exactly there (v1828 B7, T3).
     */
    record NeedsReconciliation(Reconcile reason, SagaPhase suspended)            implements SagaPhase {}
    /** Running compensations in reverse; inFlight as for Running; one outcome per compensated step. */
    record Compensating(int nextToCompensate, Option<StepAttempt> inFlight,
                        List<CompensationOutcome> outcomes)                      implements SagaPhase {}
    record Completed()                                                            implements SagaPhase {}  // terminal
    record Compensated()                                                          implements SagaPhase {}  // terminal
    record PartiallyCompensated(List<CompensationFailure> failures)               implements SagaPhase {}  // terminal
    record Failed(String causeType, String message)                               implements SagaPhase {}  // terminal
}

/**
 * at: the committing owner's clock (key-window origin, §7.4). ownerEpoch: delimits retry episodes (E4).
 * evidence: how the attempt ended, recorded when it fails; none() = unrecorded, which counts as Sent (E7).
 */
public record StepAttempt(int stepIndex, int attempt, boolean compensation, Instant at,
                          String ownerEpoch, Option<DeliveryEvidence> evidence) {}

/** Per-step compensation progress, persisted so that neither a park nor a handover loses it. */
public sealed interface CompensationOutcome {
    int stepIndex();
    record Compensated(int stepIndex)                         implements CompensationOutcome {}
    record CompensationFailed(int stepIndex, String causeType, String message) implements CompensationOutcome {}
    record Unresolved(int stepIndex, OperationId compensationId) implements CompensationOutcome {}
}

public sealed interface Reconcile {
    record OutcomeUnknown(int stepIndex, OperationId operationId, int attempts)             implements Reconcile {}
    record CompensationOutcomeUnknown(int stepIndex, OperationId compensationId, int attempts) implements Reconcile {}
    record RetriesExhausted(int stepIndex, OperationId operationId, int attempts,
                            boolean compensation)                                           implements Reconcile {}  // all attempts NotSent
    record DeadlineTransitionFailed(int waitStep, int fireAttempts, String lastCauseType)  implements Reconcile {}  // E6
    record BoundVersionUnavailable(String artifact, String detail)                          implements Reconcile {}
    record ForceRetired(String version, String byOperator)                                  implements Reconcile {}
}

/** Signals (§7.8): at most one per wait step, so the buffer is bounded by the definition. */
public record SignalRecord(String waitName, String signalId, byte[] encodedPayload, String payloadType,
                           boolean consumed, Instant receivedAt) {}
```

`SagaInstance` also carries `List<SignalRecord> signals`; it is elided above for width. A
`NeedsReconciliation` never nests (`suspended` is never itself `NeedsReconciliation` or terminal), and it
carries the suspended phase's resume point (`nextStep` / `waitStep` + `deadlineAt` + token /
`nextToCompensate` + per-step `outcomes`), so the information a resolution needs to continue is in the
persisted state, not in a node's memory; §7.7's resolution table (D3) says what each resolution does with
it. Results and payloads are stored **encoded with their type
tag** (the codec of the bound version, §7.9) so the ledger is decodable by exactly the code the instance is
bound to.

### 7.7 The `Saga` façade interface

```java
/**
 * PLANNED (#354). A saga taking I, accumulating PRIVATE state S, producing O; D is the data root
 * (§7.3, §7.9). Nothing on this interface exposes S: callers see only contract types (D9).
 */
public interface Saga<I, S, O, D extends SagaData> {
    /**
     * Create an instance (binding the version per §7.9; init: I -> S on the owner) and drive it until it
     * completes, compensates, parks (Waiting / NeedsReconciliation) or the caller's deadline expires.
     * Calling run() for an instance that already exists is not a restart: it returns its current outcome.
     */
    Promise<SagaOutcome<O>> run(String sagaId, I input);

    /** Committed status, BOUNDED_STALE by default (§8.1): phase and reason, the output once completed — never S. */
    Promise<Option<SagaStatus<O>>> status(String sagaId);
    Promise<Option<SagaStatus<O>>> status(String sagaId, ReadConsistency consistency);

    /**
     * Deliver a signal to a wait step of ONE incarnation (§7.8). The incarnation comes from run()/status().
     * Idempotent on (waitName, signalId) within a live incarnation.
     */
    <P extends D> Promise<SignalAccepted> signal(SagaInstanceId instance, String waitName, String signalId,
                                                 P payload);

    /** Operator resolution of a parked instance (NeedsReconciliation or Waiting; table below). Fenced. */
    Promise<SagaOutcome<O>> resolve(SagaInstanceId instance, Resolution<D> resolution);

    /** Delete a TERMINAL instance of ONE incarnation; refused (typed) otherwise. */
    Promise<Unit> delete(SagaInstanceId instance);
}

public sealed interface Resolution<D extends SagaData> {
    /** The effect happened; here is its receipt. The runtime folds it and continues forward. */
    record SucceededWithReceipt<D extends SagaData>(D result)  implements Resolution<D> {}
    /** The effect did not happen. The step counts as definitely failed: compensate steps below it. */
    record Failed<D extends SagaData>(String reason)            implements Resolution<D> {}
    /**
     * Undo. For OutcomeUnknown: the effect happened — record the operator-supplied receipt, then compensate
     * this step AND every step below it, in reverse ([author choice] on how it differs from Failed).
     * For ForceRetired: compensate every completed step from the suspended point.
     */
    record Compensate<D extends SagaData>(Option<D> result)     implements Resolution<D> {}
    /** For a Waiting instance: stop waiting and compensate from the current point (D3 "cancel"). */
    record Cancel<D extends SagaData>(String reason)            implements Resolution<D> {}
    /** For BoundVersionUnavailable: the bound code is reachable again — resume from the suspended phase. */
    record Resume<D extends SagaData>()                         implements Resolution<D> {}
    /** For CompensationOutcomeUnknown: the compensation did / did not take effect. */
    record CompensationResolved<D extends SagaData>(boolean effective) implements Resolution<D> {}
    /** Last resort, always accepted on a parked instance: mark Failed(OperatorAbandoned), compensate nothing. */
    record Abandon<D extends SagaData>(String reason)           implements Resolution<D> {}
}

/**
 * Every outcome names the incarnation it is about (E9): it is the address for signal/resolve/delete.
 * Only Succeeded carries a payload, the O that finish produced (D9). The others carry no state: S is
 * private, and an unfinished or unwound saga has no O (accepted default, CTO-confirmed 2026-10-02).
 */
public sealed interface SagaOutcome<O> {
    SagaInstanceId instance();
    record Succeeded<O>(SagaInstanceId instance, O output)         implements SagaOutcome<O> {}
    record Compensated<O>(SagaInstanceId instance)                 implements SagaOutcome<O> {}
    record PartiallyCompensated<O>(SagaInstanceId instance,
                                   List<CompensationFailure> failures) implements SagaOutcome<O> {}
    record Failed<O>(SagaInstanceId instance, String causeType)   implements SagaOutcome<O> {}
    /** Not terminal: the instance is parked and its run continues later (signal, deadline or operator). */
    record Waiting<O>(SagaInstanceId instance, String waitName, Instant deadlineAt) implements SagaOutcome<O> {}
    record NeedsReconciliation<O>(SagaInstanceId instance, Reconcile reason) implements SagaOutcome<O> {}
}

/** What status() returns to callers: the contract-safe view of an instance (D9; shape CTO-confirmed as default). */
public record SagaStatus<O>(SagaInstanceId instance, String phase, Option<Reconcile> reason,
                            Option<String> waitName, Option<Instant> deadlineAt, Option<O> output) {}
```

`S` and the full `SagaInstance` are visible to operators through the management API, whose JSON surface
decodes them through the bound version (§7.9, decode-site table) — never to callers through `Saga`.

**Where a sender gets the incarnation (E9).** From the `SagaOutcome` that `run` returned
(`outcome.instance()`), from `status(sagaId)` (`SagaStatus.instance()`), or from the management listing, which
shows it for every instance. A sender that knows only a business `sagaId` reads `status` first; a signal
addressed to an incarnation that has since been replaced is refused `IncarnationReplaced` (§7.8), never
redirected.

The operator surface follows the §6.6 management-triad pattern (PLANNED with #354):
`GET /api/sagas/{type}?phase=NeedsReconciliation`, `POST /api/sagas/{type}/{id}/resolve`,
`POST /api/sagas/{type}/{id}/signal`, with CLI and docs counterparts.

**Resolution table — owner decision D3 (v1828 B5, B7).** `resolve` is accepted on a **parked** instance —
`NeedsReconciliation` or `Waiting` — and only with the kinds its row lists; anything else is refused with
a typed `SagaError.ResolutionNotApplicable`. Every resolution resumes from the persisted suspended phase
(§7.6), so nothing a resolution needs lives only in a node's memory. The bound code is still loaded for
every row, because a version with bound work is never unloaded (§7.9, "reconcile-only").

| Parked as | Accepted resolutions | Effect |
|---|---|---|
| `NeedsReconciliation(OutcomeUnknown(i))` | `SucceededWithReceipt(R)` · `Failed` · `Compensate(some(R))` · `Abandon` | fold `R` and continue · compensate steps `< i` · record `R`, compensate steps `≤ i` · terminal `Failed(OperatorAbandoned)` |
| `NeedsReconciliation(CompensationOutcomeUnknown(j))` | `CompensationResolved(effective)` · `Abandon` | record step `j`'s compensation as done or failed and continue the reverse sequence · `Failed(OperatorAbandoned)` |
| `NeedsReconciliation(ForceRetired)` | `Compensate(none())` · `Abandon` | compensate every completed step from the suspended point, on the retained code · `Failed(OperatorAbandoned)` |
| `NeedsReconciliation(RetriesExhausted(i, compensation = false))` | `Resume` · `Failed` · `Abandon` | retry step `i`'s forward with a fresh budget (no attempt was sent, so this is safe) · compensate steps `< i` · `Failed(OperatorAbandoned)` **[author choice: row added with D7's split]** |
| `NeedsReconciliation(RetriesExhausted(j, compensation = true))` | `Resume` · `Abandon` | retry step `j`'s compensation with a fresh budget and continue the reverse sequence · `Failed(OperatorAbandoned)` (E7) |
| `NeedsReconciliation(DeadlineTransitionFailed(w))` | `Cancel` · `Abandon` | compensate every completed step from the current point · `Failed(OperatorAbandoned)` (E6) |
| `NeedsReconciliation(BoundVersionUnavailable)` | `Resume` · `Abandon` | continue the suspended phase once the bound code answers again (refused while it does not) · `Failed(OperatorAbandoned)` |
| `Waiting(w)` | `Cancel` · `Abandon` | cancel the deadline timer, then compensate every completed step from the current point · `Failed(OperatorAbandoned)` |

`Abandon` marks the instance terminal `Failed` with cause `OperatorAbandoned` and compensates nothing; it
is explicit, recorded with the operator and reason, and is the last resort on every parked instance
**(CTO-confirmed 2026-10-02: "always available" means on every PARKED instance; abandoning a Running or
Compensating instance mid-invocation would race the in-flight call)**. This table replaces the earlier
"`resolve` is accepted only in `NeedsReconciliation`", which contradicted §7.8's operator action on a
waiting instance.

**Error surface.** `SagaError` (sealed, extends `Cause`), named per #432's entity-centric convention:
`SagaNotFound`, `SagaAlreadyTerminated(phase)`, `ResolutionNotApplicable(phase, resolution)`,
`SignalRejected(reason)` (§7.8), `BoundVersionUnavailable(artifact)` (§7.9),
`DefinitionNotRegistered(definitionId)` (§7.9), `BoundVersionFingerprintMismatch(binding, expected, found)`
(§7.9), `ContractFingerprintMismatch(instance, expected, presented)` (§7.9),
`ContractChangeRequiresNewDefinition(definition, differingTypes)` (§7.9, D10),
`UnsupportedEnvelopeVersion(version)` (§7.9, S2),
plus the entity's own `EntityError` cases passed through unchanged (fence, ownership, storage).

### 7.8 `WAIT_SIGNAL` — waits, signals and deadlines (R5)

Specified in rc4; implemented with #354. A `WaitStep` (§7.3) parks the saga until a signal named by the
step arrives or its deadline passes. A wait has no external effect, so it has no compensation and no
recovery capability. Every rule below is enforced on the owner, inside the per-key serialization, so
signals, deadline fires, operator actions and step completions on one instance are totally ordered.

**Signal identity and dedup.** A signal is addressed `(SagaInstanceId, waitName, signalId)` — the
instance id **includes the incarnation** (§7.2), so a signal meant for a deleted instance can never fold
into a new instance created under the same `sagaId` (v1828 B8, CTO ruling T4). `signalId` is
**caller-minted** (the `TimerToken` precedent, §5.1): a sender that re-sends after a lost
acknowledgement presents the same id.

**Precedence (T4), evaluated in this order on the owner:**
1. **Incarnation.** No instance under `sagaId` → `SagaNotFound`. The addressed incarnation is not the
   current one → `SignalRejected(IncarnationReplaced)`. The current incarnation is terminal →
   `SignalRejected(Terminated(phase))`. This check comes **first**, so even an exact duplicate of a signal
   the instance consumed before it terminated is answered with the rejection, not with `SignalAccepted`.
2. **Dedup within the live incarnation** (below): an exact duplicate is a no-op.
3. **Phase and wait rules** (the paragraphs that follow): `Compensating`, `WaitExpired`,
   `WaitAlreadySignalled`, `UnknownWait`.

**Dedup.** The instance keeps at most **one** `SignalRecord` per wait step,
so dedup is exact and needs no window: a signal whose `(waitName, signalId)` matches the stored record
is a **no-op** answered with the original `SignalAccepted`, whether the record is still buffered or
already consumed. A signal with a **different** `signalId` for a wait step that already holds one is
refused (`SignalRejected(WaitAlreadySignalled)`).

**Signal before the wait (buffered, bounded).** If the instance has not yet reached the named wait, the
signal is persisted as a buffered `SignalRecord` and acknowledged. When the saga enters that wait it
consumes the buffered record at once — folds the payload, schedules no deadline, continues. Bound: one
record per wait step, so a saga's buffer is at most the number of wait steps in its definition, and a
payload larger than the keyspace's maximum record size is refused at the boundary. A signal naming no
wait step of the bound definition is refused (`SignalRejected(UnknownWait)`).

**Signals the saga can no longer use.** Terminal and replaced incarnations are rejected at step 1 of the
precedence. Otherwise refused with a typed `SignalRejected(reason)`: `Compensating` (an in-flight or
pending compensation is never interrupted by a signal), and `WaitExpired` (the wait's deadline already
won). A `NeedsReconciliation` instance is judged by its **suspended** phase (§7.6): parked from
`Running` or `Waiting`, a signal for a wait that lies ahead is buffered as above — parking for the
operator does not discard future input; parked from `Compensating`, it is refused `Compensating`.

**Deadline — mechanism (v1828 B6, CTO ruling T2).** The shipped primitives give no atomic "change state
and arm a timer": `update` and `scheduleTimer` are separate appends (`DurableEntity.java:142,222`). The
deadline is therefore built so that every interleaving of those two appends with a crash is safe:

1. **Arm first.** Entering a wait first calls `scheduleTimer` with a **deterministic token**
   `base32(SHA-256(enc("aether.saga.deadline.v1") ‖ enc(keyspace) ‖ enc(incarnation) ‖ enc(sagaId) ‖
   enc(waitStepIndex)))` — the §7.2 construction under its own domain tag (one wait step = one token) — and the command
   `DeadlineFired(waitStep, token)`.
2. **Then persist `Waiting(waitStep, deadlineAt, token)`.**
3. **A fire is a guarded no-op unless it matches.** `DeadlineFired` is a pure transition: if the phase is
   `Waiting` on the same `waitStep` with the same token, it applies the author's `OnDeadline` —
   `ContinueWith(defaultFold)` folds the default into `S` and continues, `Compensate()` enters
   `Compensating` for every completed step. In any other phase, or for a different wait, it leaves the
   state unchanged. A crash between steps 1 and 2 therefore leaves a timer whose fire does nothing; on
   recovery the runtime re-enters the wait and arms it again (the earlier token is no longer pending once it
   fired, so this schedules a new timer; while it is still pending, the same token dedupes, §5.1). If it
   has already fired, the re-entry's deadline starts at re-entry. If it is still pending, the timer armed at
   the first entry governs, so the fire may come earlier than the re-entry's persisted `deadlineAt` by up to
   the crash-to-recovery interval. It never comes later than the first arming's instant plus the timer
   contract's lateness.
4. **Recovery re-arms idempotently.** An owner taking over a partition calls `scheduleTimer` with the
   persisted token and delay `max(0, deadlineAt − now)` for each `Waiting` instance. If the timer is still
   pending, the shipped already-pending check appends nothing (`PartitionFencedDurableEntity.java:299-309`);
   if it was lost, it is re-created. Either way exactly one live timer guards the wait.
5. **A fire that fails is not consumed.** Shipped timers consume a fire whose preparation fails
   deterministically — undecodable command, absent key, a throwing mutator, an unencodable result — and
   then never retry it (`PartitionFencedDurableEntity.java:709-711` chooses consume-by-cancel;
   `:794-801` settles it). For a saga deadline that loses the deadline for good. **Required for #354:** a
   `DeadlineFired` whose transition does not commit leaves the timer pending and is re-fired on later
   ticks. Each failed fire is recorded by a separate `DeadlineFireFailed(waitStep, count)` append — a
   runtime transition that runs no author code, so it cannot fail the way the fold did — and logged at
   ERROR, so the count survives a handover. **After the wait step's bounded number of fires
   (`maxFireAttempts`, default 5, CTO-confirmed 2026-10-02) the instance moves to
   `NeedsReconciliation(DeadlineTransitionFailed)`** (suspended phase `Waiting`), the timer is cancelled,
   and the instance appears in the `phase=NeedsReconciliation` listing (v1828 N6, CTO ruling E6). The
   operator resolves it with `Cancel` or `Abandon` (§7.7). There is no "deploy a fixed version" exit: the
   binding never changes (R4a), and the shipped code consumes such fires precisely because they "recur
   identically on every future tick" (`PartitionFencedDurableEntity.java:692-697`).

If a signal and the deadline race, the first one the owner applies wins and the other is a no-op: a late
fire fails the guard in step 3; a late signal is refused `WaitExpired`. A signal that wins then cancels the
timer (idempotent cancel); if that cancel is lost, the guard still makes the eventual fire a no-op.

**Interaction with compensation.** A wait is never entered while `Compensating`. A deadline `Compensate()`
moves the instance to `Compensating` in the fire's own transition; any later fire for that wait fails the
guard. An operator may `Cancel` or `Abandon` a waiting instance (§7.7, D3); `Cancel` cancels the
deadline timer and moves the instance to `Compensating` in one transition, and the guard makes any fire
that still arrives a no-op. Signals are never applied to a compensating or terminal instance.

**Composition with the state fold (R3).** The consumed payload `P` folds into `S` through the wait step's
pure `fold`, exactly like a step result; later steps and compensations see it only through `S`.

**Wire and persistence shape.** A waiting instance is `SagaInstance` with phase
`Waiting(waitStep, deadlineAt, deadlineTimerToken)` plus its `SignalRecord`s (§7.6). Signals travel to
the owner as an ordinary entity command (`DeliverSignal(incarnation, waitName, signalId,
encodedPayload, payloadType)`), forwarded from any node like any entity write (#596), and are encoded with the bound
version's codec for `P` (§7.9).

**Recovery after owner failure.** State-based: the new owner folds the log to the same
`SagaInstance` (still `Waiting`, buffered signals intact). The deadline timer is a record in the same log
and survives the handover; step 4 above re-arms it idempotently in case it was never armed or was lost. Nothing replays; no author code runs until a signal,
deadline or operator action arrives.

**Version pin.** A waiting instance is bound like any other (§7.9) and counts toward its version's
live total, so a wait blocks retirement of the code that will run the rest of the saga.

### 7.9 Definition registration, version binding, retirement and rollback (R4a, R4b, R5)

#### Registration through the slice factory

The saga is declared like any resource (§5.2): an author qualifier and a config section. What binds a
*definition* to it is the slice factory, which already runs on every node that hosts the slice, at load —
so the definition exists wherever an owner might need it, and its dependencies are the slice's own
injected ones:

```java
@ResourceQualifier(type = SagaRegistry.class, config = "sagas.order")
@Retention(RUNTIME) @Target(PARAMETER)
public @interface OrderSaga {}

static Result<OrderPlacement> orderPlacement(@OrderSaga SagaRegistry<OrderSagaInput, OrderSagaState,
                                                                       OrderSagaResult, OrderSagaData> sagas,
                                             InventorySlice inventory,
                                             PaymentSlice payment,
                                             ShippingSlice shipping) {
    return OrderSagaDefinition.orderSaga(inventory, payment, shipping)   // Result<SagaDefinition<…>>
                              .flatMap(sagas::register)                  // Result<Saga<…>>
                              .map(orderPlacement::new);
}
```

A slice factory may already return `Result<Slice>` (`SliceModel.detectReturnKind`), so a definition that
fails to build or register fails the slice load with its typed cause — loudly, on every hosting node.

```java
/** PLANNED (#354). The resource a saga qualifier provisions. */
public interface SagaRegistry<I, S, O, D extends SagaData> {
    /** Bind a definition for THIS slice version. Fails typed on a name/version conflict or codec gap. */
    Result<Saga<I, S, O, D>> register(SagaDefinition<I, S, O, D> definition);
}
```

- **Codecs.** `S` and the data root `D` are type arguments of the resource-qualified parameter, so the
  slice processor collects them; `D` is a sealed root, and codec generation already recurses into a
  sealed root's permitted records (CHANGELOG, "Slice codec generation now recurses into a sealed root's
  permitted subclasses"), so every step result `R` and payload `P` gets a codec and tag with no extra
  mechanism. A result or payload type outside `D` does not typecheck (`R extends D`, `P extends D`).
- **Dependencies.** Ordinary factory parameters, resolved by the existing `DependencyResolver`; their
  resolved artifact versions become part of the binding (below).
- **Registration key.** `(keyspace, definition name, definitionVersion, slice artifact incl. version)`.
  The node keeps every registered version side by side, which replaces — for saga keyspaces — the
  keyspace-only registries of §7.11.1 (first-wins timer/checkpoint drivers, last-wins forward target).
  Unloading one version unregisters only that version's key.

#### Version binding at creation (R4a)

`run` creates the instance on the version the **existing** rollout/split policy selects for that request
(rolling, A/B split, canary stage — whatever `activeRouting` would pick for a new start) among the versions
of the addressed definition — which all share one contract (D10, (3) below) — and persists, in the create
record, a `VersionBinding`:

```java
public record VersionBinding(String definitionName, int definitionVersion,
                             String sliceArtifact,                    // groupId:artifactId:version
                             Map<String, String> dependencyVersions,  // artifactBase -> version: the FULL resolved closure (D7c)
                             String stateFingerprint,                 // private types: S + runtime records (D8 refined)
                             String stateShapeHash,                   // named SHAPE lines behind it, for the swap guard
                             String contractFingerprint,              // boundary types: I, O, D's closure (D8 refined, D9)
                             String contractShapeHash) {}             // named SHAPE lines behind it, for the swap guard
```

**The pin is transitive (owner decision D7c, closes v1828 B4).** `dependencyVersions` records the full
resolved dependency **closure** at creation — every slice the saga's slice depends on, directly or through
another dependency, with the version `DependencyResolver` resolved — not only the direct dependencies. A
dependent call made anywhere in that chain on behalf of the instance is routed to the closure's version.

From then on **everything the instance does runs on its binding, not on live weights**: forward,
lookup and compensation invocations, signal and deadline handling, recovery, and **every dependent slice
call made from step code**. Mechanism: the owner executes the instance through the registry entry whose
key matches the binding, and runs step code inside an invocation scope that carries the binding, which
`SliceInvoker` consults **before** `activeRouting` — a dependency named in the binding is selected by
exact artifact version, bypassing the weighted pick (today's code ignores the requested version during a
rollout, §7.11.1, so this is new work for #354). The scope is captured on the caller side and re-bound
across the per-key executor hop, as `submitWithDeadline` already does for the request deadline
(`PartitionFencedDurableEntity.java:235-238`).

**Missing bound code fails loudly.** If the bound slice version, definition, or a bound dependency
version has no live endpoint, nothing falls back to the latest version: the invocation is not made
(`NotSent`, cause `SagaError.BoundVersionUnavailable`). It is retried with backoff inside the step's
budget (D7a), so a restart or a rolling replacement of the bound version shorter than the remaining backoff
schedule (≥ 381 s for a fresh step under the defaults, §7.4) does not park the saga. Only an
exhausted budget parks it, in `NeedsReconciliation(BoundVersionUnavailable)`; because no invocation was
made, this is never an `OutcomeUnknown`. Under D3 and D7c a version with bound work is never unloaded, so
the park signals lost capacity, not retirement, and is resolved by `Resume` or `Abandon` (§7.7).

**Coexistence in one keyspace — per-binding decoding (owner decision D8, refined 2026-10-02; supersedes
D5 and the earlier fingerprint-only D8 text).** Pin-at-creation means an instance's private data is only
ever written and read by its bound version. That removes the reason to restrict coexistence on it, and puts
the whole burden on two things: every decode goes through the binding, and anything encoded by someone
**outside** the binding is checked before it is decoded.

*Why the codec forces this.* The shipped record codec is **positional**: a generated `readBody` reads
exactly its own component list in declaration order, with no field count, no lengths and no field ids
(`CodecClassGenerator.java:422-484`). A reader whose component list differs from the writer's does not skip
what it does not know — it desynchronises. So a record must be decoded by the code that encoded it, or by
code with an identical layout; no "additive optional field" rule can hold on this wire (v0.8.0's D5 text,
which required one, is withdrawn).

**(1) Private data: no coexistence restriction.** `S`, step records, `S_j` snapshots, the ledger and every
runtime record about an instance are written only by its bound version. v1-bound and v2-bound instances may
share a keyspace **even when their `S` differs**. The hard requirement is that **every decode site
dispatches on the instance's `VersionBinding`, never on "current" code.** Each saga-keyspace log record
is therefore wrapped in a runtime-owned **envelope** with a fixed codec that no slice owns, and its payload
bytes are decoded only through the registry entry that matches that binding (§7.9 registration).

```java
/** Runtime-owned, never slice-encoded. Written first in every saga-keyspace log record (S1, S2). */
record LogEnvelope(byte formatVersion,          // S2: the log outlives a full-cluster upgrade
                   SagaInstanceId instance,
                   String bindingRef,           // the instance's VersionBinding (by content hash)
                   RecordKind kind,
                   PhaseKind phaseAfter,        // Running / Waiting / NeedsReconciliation / Compensating / terminal…
                   boolean pendingTimer,        // a deadline timer is armed after this record
                   boolean pendingCompensation, // compensation work remains after this record
                   Instant appendedAt) {}       // the owner's clock at append
```

**Format version (v1828 S2).** Both envelopes — this log envelope and the request envelope of (3) — begin
with a format-version byte. The log outlives a runtime upgrade (an Aether upgrade stops the whole cluster
and restarts it on the new build), so a new runtime must read the envelopes an old one wrote; a reader
accepts every format version it knows. A log envelope with an unknown format version is the one case that
still holds the partition's watermark (#701): it means the runtime is older than the data, and refusing is
the honest answer. A request envelope with an unknown version is refused with
`SagaError.UnsupportedEnvelopeVersion`.

**The fold carries payloads opaquely (v1828 S1a).** Only owners must host every bound version (§7.9
takeover eligibility); replicas need not. So the saga-keyspace fold never decodes a payload while
applying a record: every transition the owner commits appends the instance's full encoded state
(state-as-truth, as the shipped entity log already stores post-update state), so folding is "keep the
latest envelope and the latest payload bytes per instance", decoding only the envelope. A payload is decoded
lazily, at a decode site below, through the instance's binding. **A record a node cannot decode never holds
the partition watermark** — the #701 refusal (`EntityFold.java:253-272`) applies to the envelope alone,
so a replica that does not host v2 keeps folding v1 and v2 records alike.

**Encode sites (v1828 S1b).** Payloads are encoded in exactly one place — the owner, by the bound version's
codec, when it commits a transition; the envelope is encoded by the runtime. A **checkpoint write**
(`EntityCheckpointDriver`) persists envelopes and payload bytes as they are, never decoding and re-encoding
a payload, so any holder of the partition can write a checkpoint whatever versions it hosts.

The decode sites, all of which must decode payloads through the binding and nowhere else (#354):

| Decode site | What it decodes |
|---|---|
| Owner fold on takeover, restart and catch-up | every record of every instance in the partition, to rebuild `SagaInstance` |
| Replica-held reads (`status` at `BOUNDED_STALE` on a non-owner, §8.1) | the instance's state and ledger |
| Checkpoint restore (`EntityCheckpointDriver` snapshot → fold) | the snapshotted instance state |
| Operator listing and `status` (management API, CLI) | phase, `Reconcile` reason, state and results, for display |
| Reconciliation (`resolve`) | the suspended phase, `R_j`, `S_j`, and the operator-supplied receipt |
| Step execution and compensation | `S`, `S_j`, `R_j` handed to forward, lookup and compensation |
| Timer fires (`DeadlineFired`, `DeadlineFireFailed`) | the timer command and the waiting state it guards |
| Signal consumption | a buffered `SignalRecord` payload, folded into `S` |
| Retirement-gate counting | the latest **envelope** per instance only — `bindingRef`, `phaseAfter`, `pendingTimer`, `pendingCompensation` — never a payload (v1828 S1c) |
| Terminal-state GC (the terminal-TTL of S2, when it is built) | the latest envelope only: a terminal `phaseAfter` and its `appendedAt` give the terminal time (v1828 S1d) |
| Audit stream (§11 piece 7, planned) | envelopes always; payloads only on a node hosting the binding, otherwise the record is emitted envelope-only and marked so (v1828 S1d) |

A node that does not host an instance's bound version decodes none of its payloads; under §7.9's
takeover-eligibility rule it is never that instance's owner, and for display it reports the envelope
(id, binding, phase) and that the payload is unreadable here — never a misdecode.

**(2) Drift guard.** The binding records, at creation, the fingerprints of the bound version's types
(below). A version id is supposed to name one build; if the code later registered under that same
`sliceArtifact` version has a **different** fingerprint — a rebuild with changed types under an unchanged
version — nothing is decoded with it: registration of that definition fails loudly at slice load with
`SagaError.BoundVersionFingerprintMismatch(binding, expected, found)` while any live binding names the
version, and every decode site re-checks the registry entry's fingerprint against the binding as a
backstop, failing with the same typed error instead of decoding.
*Consequence for development, stated (v1828 nit):* a SNAPSHOT-style rebuild that changes a fingerprinted type
under an unchanged version id is refused while any saga bound to that version is live. Use a new version id
for each such dev build, or let the bound instances finish first.

**(3) Inbound contract.** Some bytes reach an instance from callers that are **not** bound to its version,
or go back to them: the `run` input `I`, signal payloads (§7.8), operator receipts in a `Resolution`, entity
commands forwarded from other nodes (#596), and the output `O` in `SagaOutcome`/`SagaStatus` (D9).
These form the definition's **contract**, and its fingerprint is recorded in the binding separately from
the private-state fingerprint. A caller may address an instance only if its contract fingerprint **equals**
the bound version's:

- **A contract change is a new definition (owner decision D10).** Every version of one saga definition name
  — one keyspace — has the **same** contract fingerprint (with the swap guard). A change to `I`, `O` or `D`'s
  signal/receipt closure must ship under a **new definition name**, i.e. a new keyspace. A deploy that
  registers a definition name whose contract differs from that name's registered or live-bound versions is
  **refused at deploy** with `SagaError.ContractChangeRequiresNewDefinition(definition, differingTypes)`,
  naming the differing TAG/ENUM/SHAPE lines (the same pre-flight path that refuses a missing config
  section, #1067). The old definition stays deployed per R4b — draining: no new starts, still executing
  its bound work — **together with the code paths that call it**, until its instances finish. In practice
  the new slice version registers both definitions, the old one unchanged and the new one under its new
  name; new starts go to the new name, and signals and resolutions for old instances keep using the old
  definition's handle. Because the old definition's contract never changes, **nothing on the application path
  can strand**: every old instance stays reachable by callers holding the old contract, which are still
  deployed.
- **Private-only changes stay ordinary rollouts.** A change to `S` alone, with `I`, `O` and `D` unchanged,
  keeps the definition name and rolls out normally (D9).
- **How a caller obtains the fingerprint.** A caller that does not host the saga gets it **from the definition
  registry entry it was compiled against**: the contract types live in the defining slice's API artifact,
  and the slice processor generates into that artifact a `SagaContract` descriptor — definition name,
  `contractFingerprint`, contract named-shape hash. The caller's build depends on that artifact, so the
  fingerprint it presents is the one of the contract its code was compiled against. A hosting caller uses
  its own registry entry, which is the same descriptor.
- **Detection (backstop).** Every binary-encoded inbound request carries, in a runtime-owned request
  envelope that begins with its own format-version byte (S2), the definition name, the caller's
  `contractFingerprint` and its named-shape hash; the owner compares them with the instance's binding —
  fingerprint equality plus the swap guard below — **before decoding the payload**. Under D10 a mismatch
  means a caller compiled against something no deployed version of that definition has (a stale or
  mis-built caller), and is refused with `SagaError.ContractFingerprintMismatch(instance, expected,
  presented)` — never a positional misdecode. The management API's JSON surface is not positional: it decodes
  operator input through the bound version's types (decode-site table) and needs no fingerprint.
- **Cross-slice deploy order.** Because a contract change is a new definition, the defining slice and its
  callers need no lock-step deploy: the defining slice ships first with both definitions registered;
  callers move to the new name in their own rollout; old-name callers keep working until the old
  instances finish. There is no window in which `run` is refused in both orders.
- **Additive contract evolution is deferred** until a tolerant, framed codec exists (post-GA, not promised).
- **`S` is not in the contract (owner decision D9).** `run` takes `I`, outcomes carry `O`, and `status`
  exposes no `S`, so a change to `S` alone — with `I`, `O`, `D` unchanged — is an ordinary rollout: new
  instances bind to the new version, old ones finish on theirs, and every caller keeps working.

**The two fingerprints.** `stateFingerprint` covers `S` and every runtime record type of the instance;
`contractFingerprint` covers the boundary types above: `I`, `O`, and the data root `D`'s sealed closure
(signal payloads `P`, and the result types `R` a `Resolution` can carry as a receipt). Step results are
therefore contract types too — an operator or caller may supply one — while `S` is not.

**How a fingerprint is computed.** A fingerprint covers a SET of types and every type reachable through
their components. It is the SHA-256 of the sorted lines that describe those types in the wire baseline's
format (`aether/node/src/test/resources/wire-assignment-baseline.txt:1-14` defines it;
`WireAssignmentTripwireTest` derives it from the generator), **with component names removed**:

- `TAG <type> <wire tag>` — as in the baseline;
- `ENUM <type> <NAME=ordinal,…>` — as in the baseline;
- `SHAPE <type> (<type>,<type>,…)` — each fingerprinted type's identity followed by its component TYPES in
  declaration order. The position of each type in the list is its position on the wire.

**Renames are exempt (CTO ruling, 2026-10-02).** The codec is positional: a component is written and read
by its position and type, never by its name, and the baseline itself records that a same-type,
same-position rename is byte-neutral (`wire-assignment-baseline.txt:13-14`). Refusing such a rename would
force a migration for a change that alters nothing on disk, so the fingerprint omits component names.
What it keeps — the type's identity, each component's type and position, the TAG and ENUM content — is
exactly what changes the bytes or the meaning of the bytes.

**Swap guard (CTO ruling, 2026-10-02).** The rename exemption alone would also pass a **same-typed name
swap** — two components of the same type exchanging names (`String from, String to` → `String to,
String from`): identical bytes, but each side reads the other's value under the other's meaning. So two
type sets with equal fingerprints are compatible **only if no component name present in both appears at a
different position within the same type**. A pure rename (a name that disappears, replaced at the same
position by a new one) passes; a name that moves is refused. This is decidable from the two sets' NAMED
SHAPE lines, which the hash deliberately omits, so the runtime keeps them (mechanism CTO-confirmed
2026-10-02):

- every definition registration publishes its named SHAPE lines (state set and contract set) to a
  cluster-wide, content-addressed **shape registry** in the KV store, keyed by the SHA-256 of the named
  lines;
- the `VersionBinding` records, for each fingerprint, the named-shape hash it was computed from, and every
  inbound request's envelope carries the caller's contract fingerprint **and** its named-shape hash;
- a comparison whose fingerprints are equal but whose named-shape hashes differ resolves both sets from the
  registry and applies the rule (the verdict is cached per pair of hashes). A swap is refused with the same
  typed error as a fingerprint mismatch — `BoundVersionFingerprintMismatch` for drift (2),
  `ContractFingerprintMismatch` for the contract (3) — naming the moved components. Equal fingerprints
  with equal named-shape hashes need no lookup.
- **Lifecycle (v1828 S3).** An entry is kept while any registered definition or any live binding references
  its hash, and collected only after both are gone. Every slice load **republishes** its entries (an
  idempotent put by hash), so a loaded version's shapes are present after any restart. **A lookup miss is
  refused** with the same typed mismatch error, marked "shape unavailable" — never assumed compatible.
  *Durability, stated:* whether the consensus KV keeps the registry across a full-cluster restart is
  `[unverified]`; the design relies on republish-on-load instead, which covers every version that is
  loaded — and a version that is not loaded has no code to decode with anyway.

**Scope.** The fingerprint and swap guard apply to **(2) and (3) only**; they are not a coexistence gate
on private state, which (1) needs none of. Within that scope the swap guard protects every pair that reads
the same bytes across builds: on the **contract types**, a caller and the instances it addresses (3); and,
for a rebuild under an unchanged version id, the rebuild and the private records its predecessor wrote
(2) — there a moved name would make the rebuild read existing records under the wrong meaning (drift
scope CTO-confirmed 2026-10-02). It is never applied between two different versions' private types: under pin-at-creation they never read each
other's bytes.

Today the TAG/ENUM/SHAPE derivation covers the node's `@Codec` types; producing the same lines for a
slice's generated codecs is #354 implementation work.

**What it costs.** Changing private types costs nothing at deploy: new instances bind to the new version and
old ones finish on theirs. Changing contract types costs a new definition name — a new keyspace — and
keeping the old definition and its callers deployed until its instances finish (D10). Rebuilding a version
id with different types is refused.

*Out of scope, not promised:* a framed envelope around each payload (a field count and per-field lengths)
would let a reader skip what it does not know and could allow additive contract changes in place, relaxing
D10. It is a wire-format change of its own (post-GA at the earliest), and nothing in this spec depends on
it.

This applies to keyspaces backing sagas and workflows; whether plain `DurableEntity` keyspaces take the same
envelope and checks is a follow-up (§7.11.1 shows they have the same exposure).

#### Retirement — drain, then retire (R4b)

A version that would be removed (rollout completion, rollback, blueprint change) and still has bound work
enters **DRAINING** instead of being deallocated:

- **No new starts** are bound to it; the rollout policy routes new starts elsewhere.
- **Bound work still executes** — steps, lookups, compensations, signals, deadlines, recovery.
- **A live count is reported**: instances bound to the version that are not terminal, plus pending
  deadline timers and pending compensations, per keyspace. Each partition owner derives its share from
  its fold and publishes it; the retirement gate sums them. Visible through the management triad
  (`GET /api/sagas/{type}/versions`, CLI, docs).
- **Retired only at zero.** The deallocation paths of §7.11.1 (`removeNonTargetVersions` and the
  others) consult the gate before unloading **any version that appears in any live binding** — the saga's
  own slice version and every version in a binding's dependency closure (D7c). A dependency version
  referenced only through a live binding is therefore DRAINING, not removable, and its live count names
  the bindings that hold it. *Capacity consequence, stated:* the number of coexisting versions of a slice —
  and of every dependency in any closure — is bounded only by the live sagas bound to them; a long wait keeps
  its whole closure deployed.
- **Takeover capacity.** A node is eligible to own a saga keyspace partition only if it hosts every
  version still bound by live work in that keyspace (ACTIVE or DRAINING), and the deployment keeps a
  draining version allocated on at least `replication_factor` of the keyspace's hosting nodes, so an
  owner failure always has an eligible successor.
- **Operator force-retire — retain code until resolved (owner decision D3).** Force-retire stops new
  starts and normal execution: every bound instance moves to `NeedsReconciliation(ForceRetired)` in fenced
  writes — never a silent drop. The version is **not** unloaded: it stays loaded in a **reconcile-only**
  state, in which it runs only what a resolution asks for (compensations after `Compensate`), and it is
  counted like a draining version for ownership eligibility. Retirement completes when the count of
  bound, unresolved instances reaches 0. So no resolution ever needs code that is gone.

#### Rollback (R4b)

Rollback **redirects new starts only**. Instances already started on the rejected version keep their
binding and run to completion on it; that version goes DRAINING, not deallocated, until its count is zero
or an operator force-retires it. Rollback reverses no persisted state and no external effect. This changes
today's rollback, which deallocates the new version in the same batch as the routing removal
(`DeploymentManagerImpl.java:374-398`, §7.11.1).

### 7.10 Worked example — order saga (R1–R5)

State, data root and a definition whose step B consumes step A's folded result:

```java
/** Contract: what callers send and get back (D9). */
public record OrderSagaInput(String orderId, String customerId, List<LineItem> items, BigDecimal total) {}
public record OrderSagaResult(String orderId, ChargeId charge, Shipment shipment) {}

/** Private: free to change between versions (D9). */
public record OrderSagaState(String orderId, String customerId, List<LineItem> items, BigDecimal total,
                             Option<ReservationId> reservation, Option<ChargeId> charge,
                             Option<Approval> approval, Option<Shipment> shipment) {
    static OrderSagaState start(OrderSagaInput in) {                   // init: I -> S, pure
        return new OrderSagaState(in.orderId(), in.customerId(), in.items(), in.total(),
                                  Option.none(), Option.none(), Option.none(), Option.none());
    }
    OrderSagaResult result() {                                          // finish: S -> O, pure
        return new OrderSagaResult(orderId, charge.unwrap(), shipment.unwrap());  // both present on success
    }
    OrderSagaState withReservation(ReservationId r) { ... }   // pure "with" copies
    OrderSagaState withCharge(ChargeId c)           { ... }
    OrderSagaState withApproval(Approval a)         { ... }
    OrderSagaState withShipment(Shipment sh)        { ... }
}

public sealed interface OrderSagaData extends SagaData {
    record ReservationId(String value)           implements OrderSagaData {}
    record ChargeId(String value)                implements OrderSagaData {}
    record Approval(String approver, boolean ok) implements OrderSagaData {}
    record Shipment(String trackingId)           implements OrderSagaData {}
}

static Result<SagaDefinition<OrderSagaInput, OrderSagaState, OrderSagaResult, OrderSagaData>>
        orderSaga(InventorySlice inventory, PaymentSlice payment, ShippingSlice shipping) {
    return SagaDefinition.<OrderSagaInput, OrderSagaState, OrderSagaResult, OrderSagaData>builder(
               "order-saga", 1, Duration.ofMinutes(5),
               OrderSagaState::start,                       // init
               OrderSagaState::result)                      // finish
        // A: reserve. Inventory dedups on the key and answers a repeat with the original reservation.
        .step(new SagaStep<OrderSagaState, ReservationId>(
            "reserve-inventory",
            (ctx, s) -> inventory.reserve(ctx.operationId(), s.orderId(), s.items()),
            OrderSagaState::withReservation,
            new StepRecovery.Replayable<>(Duration.ofDays(7)),      // inventory keeps keys 7 days
            (ctx, s, reservation) -> inventory.release(ctx.compensationId(), reservation),  // A's OWN R
            new StepRecovery.Replayable<>(Duration.ofDays(7)),
            RetryBudget.DEFAULT,
            ReplyClassifier.DEFAULT))
        // B: charge, CONSUMING A's folded result from S. The provider offers a lookup by our key that is
        //    ORDERED against the charge: it records the key before charging, so not-found means no attempt
        //    carrying the key can still land. A search API that is only eventually consistent does NOT
        //    qualify — such a provider must be declared Neither (§7.4).
        .step(new SagaStep<OrderSagaState, ChargeId>(
            "charge-payment",
            (ctx, s) -> payment.charge(ctx.operationId(), s.customerId(), s.total(),
                                       s.reservation().unwrap()),      // present: A's fold ran before B
            OrderSagaState::withCharge,
            new StepRecovery.Lookup<>(Duration.ofHours(24),          // the provider's key window
                                      (ctx, s) -> payment.findCharge(ctx.operationId())),
            (ctx, s, chargeId) -> payment.refund(ctx.compensationId(), chargeId),
            new StepRecovery.Neither<>(),       // a refund with no lookup: unknown outcome goes to the operator
            RetryBudget.DEFAULT,
            ReplyClassifier.DEFAULT))           // the provider's 409 "key in use" stays ambiguous (E5)
        // W: manual approval for large orders, 24h; on expiry compensate.
        .await(new WaitStep<OrderSagaState, Approval>(
            "approval", Approval.class, OrderSagaState::withApproval,
            Duration.ofHours(24), new OnDeadline.Compensate<>(), 5))
        // C: ship. A courier API with no dedup and no lookup.
        .step(new SagaStep<OrderSagaState, Shipment>(
            "ship",
            (ctx, s) -> shipping.dispatch(s.orderId(), s.reservation().unwrap()),
            OrderSagaState::withShipment,
            new StepRecovery.Neither<>(),
            (ctx, s, shipment) -> shipping.recall(shipment),
            new StepRecovery.Neither<>(),
            new RetryBudget(3, Duration.ofMinutes(2), Duration.ofSeconds(15),
                            Duration.ofSeconds(1), Duration.ofSeconds(20)),
            ReplyClassifier.DEFAULT))
        .build();
}
```

(`s.reservation().unwrap()` is safe by construction — the state after A's fold always holds it — but a
production definition would make that a typed state transition rather than an `Option`; kept short
here.)

**Crash windows on `charge-payment` (Lookup):**
- W1 (crash after the marker, before the provider saw the request): the new owner calls
  `findCharge(operationId)` → `none()` → invokes `charge` with the **same** `operationId` → commits the
  `ChargeId`, folds, continues. One charge — because the lookup is ordered against the charge; with an
  eventually consistent lookup a late first attempt could still land after `none()`, which is why such a
  provider is `Neither`.
- Recovery more than 24h − 5min after the marker (a long park or drain): past the key window, so no
  lookup and no re-charge — the instance parks `NeedsReconciliation(OutcomeUnknown)` (§7.4).
- W2 (provider charged, crash before `ChargeId` committed): `findCharge` → `some(chargeId)` → commits it,
  folds, continues. One charge, and the receipt is recovered — so if a later step fails, `refund` gets the
  real `ChargeId`.
- v0.5.0 promoted the marker to completion here and confirmed the order; in W1 that confirmed an unpaid
  order. That is now impossible: no path commits a `StepRecord` without an `R`.

**Crash on `ship` (Neither):** the instance parks in `NeedsReconciliation(OutcomeUnknown(3, …))`. The
operator checks the courier and resolves `SucceededWithReceipt(new Shipment("…"))`,
`Failed("never dispatched")` (refund B, release A), or `Compensate(some(new Shipment("…")))` (recall, refund,
release).

**Step B after recovery reads A's result from `S`, and A's compensation receives A's own `R`:** if
`charge-payment` definitely fails after a recovery, compensation runs `inventory.release(compensationId,
reservation)` with the `ReservationId` committed in A's `StepRecord` — the same value B read from `S`.

### 7.11 Acceptance matrix for the saga implementation (#1827)

Acceptance for #354. Each row names the fault, the outcome required by the rulings, and the
**must-be-zero** count the test asserts — the instrument counts the failure, never the success. A row is
satisfied only by a run that shows, **in the same run**, that the fault landed (the marker committed, the
downstream was or was not reached, the signal arrived before the wait, …) before it asserts the outcome.

| ID | Scenario | Setup and fault | Expected outcome (ruling) | Must-be-zero count |
|---|---|---|---|---|
| A1 | **Crash before invocation** (W1) | kill the owner after `StepAttempt(i)` commits, before the downstream sees the request; run once per capability | `Replayable`: re-invoked with the same `operationId`, one effect, `R` committed, saga continues. `Lookup`: `none()` → invoked with the same `operationId`, continues. `Neither`: `NeedsReconciliation(OutcomeUnknown(i))`, no invocation (R1) | `StepRecord(i)` without an `R` from forward/lookup/operator; downstream effects for `operationId(i)` > 1; for `Neither`, invocations after the crash |
| A2 | **Effect succeeds before result commit** (W2) | downstream applies the effect and returns `R`; kill before `R` commits; run once per capability | `Replayable`: the repeat returns the ORIGINAL `R`; `Lookup`: `some(R)` committed without invoking; `Neither`: parked `OutcomeUnknown`; operator `SucceededWithReceipt(R)` → saga continues with that `R` (R1) | downstream effects > 1; a later compensation of step `i` invoked with an `R` other than the original |
| A3 | **Crash during compensation** | step `k` definitely fails; kill the owner while compensation of step `j < k` is in flight | per `compensationRecovery`: `Replayable` re-invoked with the same `compensationId`; `Lookup` resolved by query; `Neither` → `NeedsReconciliation(CompensationOutcomeUnknown(j))`. Remaining compensations still run in reverse; any definite compensation failure ends in `PartiallyCompensated` (R1, R2, §7.5) | a compensation skipped in the reverse order; a compensation keyed by `operationId` instead of `compensationId`; terminal `Compensated` while any compensation failed or is unresolved |
| A4 | **v2 promotion with active v1 work** | instances created under v1 with pending steps, timers and waits; roll out v2 through a canary/split and complete | v1 instances keep running on v1 — their steps, timers, compensations **and dependent slice calls** — while new starts bind to v2; on completion v1 goes DRAINING with a live count, not deallocated (R4a, R4b) | a v1-bound invocation or dependent call served by v2 code; v1 deallocated while its count is > 0 |
| A5 | **v1 owner failure** | as A4 with v1 DRAINING; kill the owner of a partition holding v1-bound instances (steps with no earlier attempts) | ownership moves to a node hosting every live bound version; v1 instances resume there from persisted state, no replay; the ownerless interval consumes neither attempts nor `maxElapsed`, and the new owner starts a new episode; calls refused `NotSent` for less than 381 s are retried and succeed (R4b, D7a, E4) | v1-bound work stranded with no eligible owner; a park caused by a `NotSent` gap shorter than the remaining backoff schedule |
| A6 | **Rollback with live v2 work** | instances created under v2 hold pending steps/compensations/waits; roll back to v1 | new starts bind to v1; v2 instances run to completion on v2, which stays DRAINING until its count is zero; no persisted state or external effect is reversed by the rollback (R4b) | a v2-bound instance executed by v1 code; v2 deallocated with a non-zero count; a v2 instance dropped without a `NeedsReconciliation` record |
| A7 | **Retirement while old code is referenced** | attempt to retire v1 while v1-bound deadline timers or compensations are pending; then operator force-retire; then resolve every instance | plain retirement is BLOCKED with the live count reported; force-retire moves every v1-bound instance to `NeedsReconciliation(ForceRetired)` and keeps v1 loaded reconcile-only; v1 retires only when the unresolved count reaches 0 (R4b, D3) | v1 unloaded while any bound instance is unresolved; a bound instance with no `NeedsReconciliation` record after force-retire |
| A8 | **Missing bound code** | make the bound dependency version unavailable (no live endpoint), on a step with no earlier attempts and the default budget, (a) for 300 s, then restore it; (b) for 600 s | the call is never made against another version; (a) retried with backoff and succeeds, no park (300 s < 381 s); (b) parks `NeedsReconciliation(BoundVersionUnavailable)` when the 20th attempt fails, at 381–476 s (R4a, D7a, E4) | a dependent call served by a non-bound version; a park in (a); no park in (b) |
| A9 | **Coexistence by binding** (D8 refined, D9; rename ruling; swap guard) | (a) v2 changes only `S` (`I`, `O`, `D` unchanged): roll it out while v1 instances are live, then exercise every decode site on both (owner takeover, replica `status`, checkpoint restore, listing, `resolve`, a deadline fire, a signal) and call `run`/`signal`/`status` from v1 and v2 callers; (b) rebuild v1's version id with a changed component type and deploy it while v1 instances are live; (c) a stale or mis-built caller whose contract fingerprint differs from every deployed version of the definition signals, resolves and `run`s; (d) a caller or rebuild differing only by a pure rename of a component; (e) a same-typed name swap in a contract type (caller) and in a private type (rebuild) | (a) a normal rollout: both versions coexist, every decode uses the instance's binding, and no request sees a contract mismatch — callers of either version reach instances of both; (b) registration fails loudly with `BoundVersionFingerprintMismatch`, nothing decoded with the drifted build; (c) each request refused with `ContractFingerprintMismatch` before its payload is decoded; (d) accepted; (e) refused — `ContractFingerprintMismatch` for the caller, `BoundVersionFingerprintMismatch` for the rebuild — naming the moved components | a payload decoded by a version other than the instance's binding; a contract mismatch reported for an `S`-only change; a decode with a drifted build; a request decoded despite a contract mismatch; a pure rename refused; a same-typed swap accepted |
| A10 | **Recovery past the key window** (v1828 B1, N3) | for a `Replayable` and a `Lookup` step: a first attempt ends `Sent`, later re-invokes keep failing `Sent`, then resume once `now − origin ≥ keyRetention − clockSafetyMargin`, where origin is the FIRST attempt's `at`; repeat with the first attempt's evidence unrecorded (crash) | no re-invocation and no lookup after that point, although the LATEST attempt is still inside the window; both steps park `NeedsReconciliation(OutcomeUnknown)` (T1, E3, E7) | an invocation or lookup issued once the earliest possibly-sent attempt is past the window |
| A11 | **Sent vs not sent** (D1) | fail a forward (a) with the circuit open, (b) by timing out after the request was written, (c) with an unclassified error, (d) with a refusal decoded from the downstream's reply | (a) definite, no effect assumed; (b), (c) ambiguous → R1 path; (d) definite, `Answered` (CTO-confirmed), not retried | an ambiguous failure treated as definite; a re-invocation after (a) or (d) that is not a fresh decision of the retry policy |
| A12 | **Compensation sees `S_j`** (D2) | steps A, B, C complete; C's successor fails definitely | compensation of B receives `S_B` (state right after B's fold), not the final `S`; the snapshots are absent after the terminal transition | a compensation invoked with a state other than its step's snapshot; snapshots retained on a terminal instance |
| A13 | **Resolution table** (D3) | park one instance per row of §7.7's table and apply each accepted resolution, then each unlisted one | each accepted resolution has the row's effect, resumed from the persisted suspended phase; each unlisted one is refused `ResolutionNotApplicable`; `Abandon` works on every row | a resolution applied outside its row; a resolution needing unloaded code |
| A14 | **Retry budget and episodes** (D7a, D7b, E2, E4, E7; late receipt) | (a) a `NotSent` failure that clears after two attempts; (b) persistent `NotSent` failures; (c) persistent `Sent` failures on a `Replayable` step; (d) a key window shorter than the budget; (e) a forward whose promise never settles; (f) a compensation whose attempts all fail `NotSent`; kill the owner mid-budget in (b) and (c) | (a) succeeds with no park; (b) parks `RetriesExhausted`; (c) parks `OutcomeUnknown`; (d) stops at the key window; (e) each attempt ends at `attemptTimeout` as `Sent`, then (c)'s path; (f) parks `RetriesExhausted(compensation = true)`; across the owner change the attempt count continues, a new episode starts, and the persisted evidence decides the split | an attempt beyond `maxAttempts` (cumulative) or beyond `maxElapsed` within one episode; an attempt past the key window; an attempt outliving `attemptTimeout`; an attempt count reset by an owner change; a `RetriesExhausted` park for a step with an unrecorded or `Sent` attempt |
| A15 | **Transitive pin** (D7c) | create an instance whose slice depends on X, which depends on Y; roll out new versions of Y; try to retire the old Y | the binding lists X and Y at creation; calls from X on the instance's behalf go to the bound Y; retiring the old Y is blocked with a live count naming the binding | a call reaching a Y version outside the binding; the old Y unloaded while a live binding references it |
| A16 | **Reply classification** (E5) | fail a forward with (a) a 409 "idempotency key in use", (b) a 429, (c) a `StepRefusal` cause, (d) an unrecognised error decoded from a reply | (a) `Sent` → R1 path; (b) `NotSent` → retried in budget; (c) `Answered` → step failed, compensate, no retry; (d) `Sent` | a 409 or unrecognised reply treated as definite; compensation started while the original request may still apply |
| A17 | **Replica without the bound version** (v1828 S1a, S1b, S2) | a partition replica hosts only v1 while the owner hosts v1 and v2 and commits v2-bound transitions; let the replica write a checkpoint; restart the cluster on a newer runtime build | the replica keeps folding v1 and v2 records (payloads opaque, envelopes decoded), its watermark advances past v2 records, and its checkpoint carries v2 payloads byte-identical; `status` on the replica shows v2 instances envelope-only; the newer runtime reads the old envelopes (known format version) | the replica's watermark held at a v2 record; a v2 payload decoded or re-encoded on the replica; an old-format envelope refused by the newer runtime |
| A18 | **In-place contract change refused** (D10) | deploy a new version of `order-saga` whose `I`, `O` or a `D` signal/receipt type differs (including a same-typed swap) under the same definition name | refused at deploy with `ContractChangeRequiresNewDefinition`, naming the differing lines; the running versions are untouched | a deploy accepted with a contract change under an unchanged definition name |
| A19 | **New definition with old instances live** (D10) | the new slice version registers `order-saga` (old contract, unchanged) and `order-saga-2` (new contract); old instances are mid-wait; callers move to `order-saga-2` in their own rollout; then send approvals to old instances from the application path | new starts go to `order-saga-2`; old instances stay reachable through `order-saga` by the deployed callers that hold its contract, and their signals are accepted; `order-saga` drains and retires once its instances finish | a signal to an old instance refused on the application path (stranded); a `run` refused in either deploy order |
| W1 | **Signal before the wait** | deliver the `approval` signal while the saga is still on step B | buffered and acknowledged; consumed on entering the wait, payload folded into `S`, no deadline scheduled (R5) | the signal lost; a deadline timer armed despite a buffered signal |
| W2 | **Duplicate signal** | deliver the same `(waitName, signalId)` twice, once buffered and once after consumption; then a different `signalId` | both duplicates are no-ops answered with the original `SignalAccepted`; the different id is refused `WaitAlreadySignalled` (R5) | payload folded more than once |
| W3 | **Deadline expiry** | let the wait's deadline pass with no signal, once per `OnDeadline`; then send a late signal | `ContinueWith`: default folded, saga continues; `Compensate`: compensation of completed steps in reverse; the late signal refused `WaitExpired` (R5) | both the deadline and a signal applied to one wait; a fire earlier than `deadlineAt` by the firing owner's clock |
| W4 | **Owner failure while parked** | kill the owner of a `Waiting` instance, then signal it, and separately let a deadline fire after the handover | the new owner holds the same `Waiting` state and re-armed timer; the signal or deadline is applied once; no author code ran during takeover (R5, state-based recovery) | the deadline lost across handover; any step re-invoked during takeover |
| W5 | **Retirement attempted while parked** | retire the version a `Waiting` instance is bound to | blocked, the waiting instance counted; force-retire parks it `NeedsReconciliation(ForceRetired)` with `Waiting` as its suspended phase, and the version stays loaded reconcile-only until it is resolved (R4b, R5, D3) | the bound version unloaded while the instance is unresolved |
| W6 | **Signal during compensation / after terminal** | signal an instance that is `Compensating`, one parked from `Compensating`, and one that is `Completed` — including an exact duplicate of a signal the `Completed` instance consumed | refused `SignalRejected(Compensating)` twice and `SignalRejected(Terminated(Completed))`, the duplicate included (incarnation check first, T4); compensation is not interrupted (R5) | a signal folded into a compensating or terminal instance; `SignalAccepted` returned for a terminal incarnation |
| W7 | **Signal to a replaced incarnation** (v1828 B8) | `delete` a terminal instance, `run` the same `sagaId` again, then retry a signal addressed to the old incarnation | refused `SignalRejected(IncarnationReplaced)`; the new instance's state is unchanged (T4) | an old-incarnation signal folded into, or buffered on, the new instance |
| W8 | **Crash between arming and `Waiting`; failing deadline** (v1828 B6, N6) | kill the owner after the deadline `scheduleTimer` lands and before `Waiting` commits; separately, make a `DeadlineFired` transition fail every time, with an owner change after the second failure | the stray fire is a no-op; recovery re-enters the wait with one live timer. The failing fire is re-fired, each failure recorded (`DeadlineFireFailed`), the count continuing across the handover; after `maxFireAttempts` the instance parks `NeedsReconciliation(DeadlineTransitionFailed)`, visible in the listing, with the timer cancelled; `Cancel` and `Abandon` both resolve it (T2, E6) | a wait with no live deadline timer; a failed deadline fire consumed; more than `maxFireAttempts` failed fires; a failing deadline absent from the listing |
| D1 | **Typed dataflow after recovery** | kill the owner between A's commit and B's invocation | B reads A's `ReservationId` from the recovered `S`; if B then definitely fails, A's compensation receives the same `ReservationId` (R3) | B invoked with no reservation in `S`; A's compensation invoked with a value differing from A's `StepRecord` |
| I1 | **Identity stability** | force re-invocations across owner change and a redeploy of the same version; delete a terminal instance and `run` the same `sagaId` again; use a `sagaId` containing `/` | the same `operationId`/`compensationId` on every re-invocation of one instance; different values for the new incarnation; every id is 52 lower-case base32 characters and contains no business id; `attempt` changes and appears in no key (R2, D6) | two different `operationId`s for one step of one instance; one `operationId` shared by two incarnations or by two distinct tuples; a key that is not 52 characters |

#### 7.11.1 What the current rollout code does to durable work (source read, `564d2d3df`, not run)

Evidence that informed R4; it records today's code, which the R4 contract (§7.9) changes.

- **No durable version binding exists.** No entity record carries an artifact version, and `DurableEntity`
  has no version parameter (`DurableEntity.java:86`).
- **During an active rollout the requested version is not honoured.** `SliceInvoker.selectEndpoint` takes
  the base's `activeRouting` and picks old/new by weight (`SliceInvoker.java:997-1011, 1083-1101`), a
  per-call weighted round-robin over every version of the base (`EndpointRegistry.java:218-270`). Pinning
  the entry artifact therefore does not pin the dependent calls a step makes. Without an active rollout the
  pick is exact-artifact (`SliceInvoker.java:1013-1023`).
- **Completion and rollback deallocate code without consulting durable work.** `handleRoutingRemoval` →
  `removeNonTargetVersions` drops every non-target version and issues unloads
  (`ClusterDeploymentState.java:1777-1804`); rollback restores the target to the old version and removes
  the routing key in one batch, so the new version is deallocated by the same path
  (`DeploymentManagerImpl.java:374-398`). A slice-target change when no rolling update is active for
  the base (`ClusterDeploymentState.java:1553-1570`, guarded by `!activeRoutings.contains`), slice-target
  removal (`ClusterDeploymentState.java:396-401`) and blueprint removal
  (`ClusterDeploymentState.java:1047-1060`) deallocate the same way. None reads entity state or pending timers.
- **Two versions on one node share a keyspace without isolation.** Resources are cached per slice scope
  `groupId:artifactId:version` (`SpiResourceProvider.java:184-186, 502`; `SliceLoadingContext.java:439-441`),
  so each version provisions its own entity instance for the same keyspace, while the node registries are
  keyed by keyspace alone: timer and checkpoint drivers keep the FIRST registrant (`putIfAbsent`,
  `EntityTimerDriver.java:59-74`, `EntityCheckpointDriver.java:244`), the owner-forward registry keeps the
  LAST (`EntityForwardService.java:94-104`), and either version's unload unregisters all of them by
  keyspace (`DurableEntityFactory.java:268, 313-320`), including the surviving version's.
  `[unverified: read from source; no two-version run was performed]`
- **Retirement leaves state and timers in the log for whichever version still hosts the keyspace.** A
  record that build cannot decode or apply holds the partition's applied watermark, and the partition then
  refuses reads until a build that can apply it is deployed (#701, `EntityFold.java:253-272`;
  `guarantees.md` §6) — an availability event, not silent loss.

---

## 8. Execution Semantics

- **Single-writer total order per entity** (owner + per-key queue + fence). Across entities: no order.
- **Writes: linearizable per key** — a committed `update` is ordered and durable across RF replicas
  under the epoch write-fence (KV path and the entity log's stream path both live, §3).
- **Reads: per-call consistency (resolves S5)** — see §8.1. The default is committed-but-bounded-stale;
  callers state what they need per call. The write fence orders writes, not default reads: a
  BOUNDED_STALE read during handover can be served by a deposed owner that has not yet learned it
  lost ownership, and a read from a lagging replica trails the latest commit.
- **No replay** — recovery resumes from current durable state; transitions never rerun; nondeterminism
  permitted.
- **Idempotency** — *design intent, not on the shipped surface:* a stable per-entity monotonic counter
  `(key, n)` usable by slice side-effect code as an idempotency key. The shipped `update` returns the
  post-update `S` only (§5.1) and exposes no such counter (v0.6.0 correction). Saga steps receive their
  identity explicitly as `StepContext.operationId` / `compensationId` (§7.2).
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
saga step (§7, PLANNED) is the single managed affordance: the runtime hands the step a stable
`operationId` (§7.2) and, after an ambiguous outcome, re-invokes, queries or parks according to the
downstream capability the author declared (§7.4). Applying an effect at most once is earned by the
**downstream** deduping on that `operationId` (`Replayable`) or answering an ordered outcome query
(`Lookup`); for a downstream that offers neither, the runtime does not guess — the saga parks for the
operator (`Neither`).

---

## 11. Substrate dependencies (epic pieces)

Status column updated in v0.6.0 for the pieces whose shipped state was verified at `564d2d3df`;
`guarantees.md` §6 is authoritative for wired entity behaviour.

| Piece | Status | Note |
|---|---|---|
| **0 — Persistent backing** (epic #349, sibling) | **PARTIAL** (v0.6.0) | the entity log is fsync'd to a per-partition WAL before ack and replicated at the keyspace's factors (`guarantees.md` §6, "crash-durable, not none"); #349's broader persistence work remains open |
| **1a — KV-path ownership fence** (#345) | **IMPLEMENTED** | `staleEpochWrite` + `EpochBearing` in `KVStore` Rabia applier; covers DHT + governor writes |
| **1b — Stream-path epoch fence** (#345) | **SHIPPED** (v0.6.0) | a deposed owner's entity-log append is refused: the stream raises `StaleEpochAppend`, which the entity layer sees as `EntityLogError.StaleOwnerAppend` (`StreamEntityLogSubstrate.java:267-286`) |
| 2 — Per-key serialization queue | **SHIPPED** (v0.6.0) | `PerKeySerialExecutor`, used by `PartitionFencedDurableEntity` |
| 3 — Durable per-instance timers | **SHIPPED** (#351, closed) | a timer is a record in the entity's fenced log; owner-stamped instant |
| 4 — `DurableEntity` core | **SHIPPED** (#345 I1–I4) | fenced-log `PartitionFencedDurableEntity`, named-command API (§5.1), owner forwarding (#596) |
| 5 — Workflow facade (#353, was #190) | **PLANNED** — no code (rc5) | entity + `StateMachineDefinition`; registration and version binding per §7.9 |
| 6 — Saga facade (#354) | **PLANNED** — no code (rc5) | contracts ruled by #1827 (§7); acceptance §7.11 |
| 7 — Observability / audit stream | new | metrics + opt-in transition/step audit to a stream |

Per-slice cron stays on `ScheduledTaskManager` (independent). **Two foundations gated this stack:** the
**#345 fence** (KV and stream paths both shipped, v0.6.0) and **#349 persistent backing** (partial, above).

---

## 12. Reconciliation to Existing Code

| Capability | Current | Target | Tag | Anchor |
|---|---|---|---|---|
| KV-path per-key fence | `staleEpochWrite` + `EpochBearing` **live in Rabia applier** | extend entity write to carry `ownerEpoch` as `EpochBearing` value | **REUSE** | `KVStore.java:356-360`; `EpochBearing.java` |
| Stream-path epoch fence | v0.2: no epoch-CAS on stream append | stream-path epoch check (#345 piece 1b) | **DONE** (v0.6.0) | `StreamEntityLogSubstrate.java:267-286` |
| `StateMachineDefinition` | exists, unused, in-memory | consume in the workflow facade (C=Unit for pure FSMs) | **REUSE** | `StateMachineDefinition.java:25` |
| Resource SPI | exists, mechanical | register `DurableEntity`/`PersistentWorkflow`/`Saga` types | **REUSE** | `SpiResourceProvider.java:33` |
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
| 4 | **Saga facade** — step ledger, three-way step recovery, waits and compensation (§7) | piece 6 |
| 5 | Observability + audit stream + operator API | piece 7 |
| 6 | Hardening — docs, sample slices, chaos/soak under governor handover | — |

**Status (v0.7.0):** phases 0–2 have shipped (§11 pieces 1b, 2, 3, 4). Phases 3–4 are open (#353, #354;
rc5); phase 4's contracts are ruled (§7, §14 S6–S10). Phase 4 also carries the rollout-side changes R4
requires (binding-honouring endpoint selection, the retirement gate, rollback that drains) — §7.9.

**Acceptance:** a sample workflow *and* a sample saga run to completion across `kill-9` of the owning
node with no slice-author-visible errors; the fence rejects a stale-owner write in a split-brain test;
100k entities within memory/throughput budgets on a 5-node cluster. **For the saga, the §7.11 matrix is
part of this acceptance**, and where a ruling makes an outcome operator-visible (e.g. a `Neither` step parked
in `NeedsReconciliation`), the matrix row governs over "no slice-author-visible errors".

---

## 14. Owner Decisions Still Needed

**S1–S5 resolved** (S3 on 2026-07-01; S1/S2/S4/S5 on 2026-07-04). **S6–S10 reopened by #1827 and
resolved by owner ruling 2026-10-02.** Rationale recorded below; normative content in the referenced
sections.

**S6 — saga step recovery (#1827 item 1). RESOLVED (R1): three-way, by declared downstream
capability** — `Replayable` (re-invoke with the same operation id; the downstream returns the original
result), `Lookup` (declared outcome query; found → record, not-found → invoke — sound only when the
query is ordered against the operation, so that no earlier attempt can still apply after a not-found) or
`Neither` (`OutcomeUnknown`;
the saga parks `NeedsReconciliation`; the operator resolves succeeded-with-receipt, failed or compensate).
A marker never becomes success. `Replayable` and `Lookup` apply only within the downstream's declared
key-retention window, less a declared clock-safety margin; past it the step is `Neither` (CTO ruling on
v1828 B1). Normative: §7.4, §7.7. Supersedes S3's `RUN_ONCE`/`IDEMPOTENT` values
while keeping S3's rule that the declaration is mandatory. #354's scope/acceptance wording to be
reconciled with it (tracker edit, owner/CTO).

**S7 — operation identity (#1827 item 2). RESOLVED (R2): explicit typed `StepContext`** to forward,
lookup and compensation, carrying `operationId` (stable across retries and owner changes), a distinct
`compensationId`, and a diagnostic-only `attempt`. A genuinely new logical operation = a new saga
instance. Normative: §7.2 (with the author's keyspace/incarnation refinement, tagged there).

**S8 — typed forward dataflow (#1827 item 3). RESOLVED (R3): accumulating saga state** `S`; each step
`forward(ctx, S) → R` plus a pure fold `(S, R) → S`, persisted per step, recovery from persisted `S`
without replay; compensation still receives its own step's `R`. Normative: §7.3, §7.10, §7.11 D1.

**S9 — durable versions (#1827 item 4). RESOLVED (R4a, R4b): pin at creation; drain then retire.** The
rollout policy picks the version at creation; the binding (definition + artifact + dependency versions)
is persisted and routes all of the instance's work, including dependent slice calls; missing bound code
fails loudly; coexistence in one keyspace only with declared-compatible codecs, else the rollout is
refused. Retirement is blocked while bound work exists (DRAINING, live count); force-retire parks bound
work in `NeedsReconciliation`; rollback redirects new starts only; the bound version stays deployed for
ownership takeover. Normative: §7.9.

**S10 — waits and definition registration (#1827 item 5). RESOLVED (R5): specify saga `WAIT_SIGNAL` in
rc4** (implementation with #354) — signal identity/dedup, buffering before the wait, rejection after
completion/compensation, author-declared deadline outcome, version pin, compensation interaction,
persisted shape, state-based recovery, fold of the payload into `S` — **and definition registration**
through the slice factory carrying codecs, dependencies and the R4 binding. Normative: §7.8, §7.9.
Supersedes S1's "saga signals are v2".

**S6–S10 follow-up — owner decisions on the #1828 review (2026-10-02).** D1: ambiguity is classified by
whether the request was sent (`NotSent` definite, `Sent` ambiguous; §7.4). D2: compensation of step `j`
receives the snapshot `S_j` (§7.5). D3: code is retained until every bound instance is resolved
(reconcile-only), with the per-reason resolution table and `Abandon` as last resort (§7.7, §7.9). D4: §7.9
applies to workflows (§6; added #353 scope). D5 (bidirectional compatibility) is SUPERSEDED by D8 as
refined: private state needs no coexistence restriction because every decode dispatches on the binding;
a drift guard refuses a rebuilt version id with changed types; callers must present the bound version's
contract fingerprint (§7.9). D6: `operationId`/`compensationId` are 52-character base32 SHA-256
digests of a length-prefixed, domain-tagged tuple, with the readable tuple kept alongside (§7.2). D7:
`NotSent` failures retry inside a budget, so an endpoint gap shorter than the remaining backoff schedule never parks; ONE declared per-step retry
budget, bounded by the key window as well; the pin is transitive and the retirement gate covers every
version in any live binding (§7.4, §7.9). Round 2 of the #1828 review is fully decided. Round 3 (CTO rulings E2–E9 on v1828 r2): per-attempt
`attemptTimeout`; key-window origin = earliest possibly-sent attempt; retry episodes with cumulative
attempts and defaults 20 / 10 min / 200 ms→30 s; a reply-classification mapping per step; failing
deadline fires park `DeadlineTransitionFailed`; persisted attempt evidence; `SagaOutcome` and `delete`
carry the incarnation. D8 (owner, on v1828 N1, refined in round 4): per-binding decoding, drift guard,
contract fingerprints; renames exempt from fingerprints, with a swap guard; A9 rewritten. D9 (owner):
separate input and output — `Saga<I, S, O, D>`, `run(I)`, pure `init`/`finish`; the contract is `I`, `O`
and `D`'s closure, and `S` is private (§7.3, §7.7, §7.9). D10 (owner): a contract change is a new definition (new keyspace); an
in-place contract change is refused at deploy; the old definition drains with its callers (§7.9).

**S1 — signals scope for v1. RESOLVED (2026-07-04); saga half SUPERSEDED by S10 (2026-10-02)** — saga
`WAIT_SIGNAL` is specified in rc4 (§7.8); the workflow half stands. Original text: **signal injection IS
v1** (book requirement).
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

**S3 — re-run policy default. RESOLVED (2026-07-01); values superseded by S6 (2026-10-02)** — the
mandatory-declaration rule stands, now over `StepRecovery` (`Replayable` | `Lookup` | `Neither`, §7.4).
Original text: neither default. Every `SagaStep` carries a
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

## Changelog — v0.10.3 (2026-10-02)

**Owner decision D10: a contract change is a new definition.** A change to `I`, `O` or `D`'s signal/receipt
closure ships under a new definition name (new keyspace); an in-place change is refused at deploy with
`ContractChangeRequiresNewDefinition`; the old definition and its callers stay deployed (draining) until its
instances finish; private-only changes stay ordinary rollouts; additive contract evolution is deferred until
a framed codec exists. A non-hosting caller's fingerprint comes from the generated `SagaContract` descriptor
in the defining slice's API artifact. The "callers keep using the old contract" text and the cross-slice
deploy-order gap are replaced; `run` version choice is the policy's again; matrix A18, A19. (§7.9, §7.11)

---

## Changelog — v0.10.2 (2026-10-02)

**#1828 final round (v1828 r4: MERGE WITH NITS), folded in before merge.**

| What | v1828 |
|---|---|
| The saga-keyspace fold carries payloads opaquely and decodes lazily per binding; an undecodable payload never holds the watermark; matrix A17 | S1a |
| Encode sites: payloads only by the owner under the bound codec; checkpoint writes keep payloads opaque | S1b |
| `LogEnvelope` carries `phaseAfter`, `pendingTimer`, `pendingCompensation`, `appendedAt`; the retirement gate and terminal GC read envelopes only | S1c, S1d |
| Terminal-TTL GC and the audit stream added as decode sites | S1d |
| Format-version byte on the log envelope and the request envelope; `UnsupportedEnvelopeVersion` | S2 |
| Shape-registry lifecycle: kept while referenced, republished on every load, a miss refused | S3 |
| Duplicate `StepAttempt` javadoc removed; arch-examples `Saga<I, S, O, D>`; a late `Neither` reply recorded as an operator-visible late receipt; drift guard's dev-rebuild consequence stated | nits |

---

## Changelog — v0.10.1 (2026-10-02)

**#1828 review round 4 (v1828 r3), CTO items G1–G4.**

| What | Item |
|---|---|
| Fingerprint-only coexistence (D8) — already in v0.10.0; confirmed against r3 | G1 |
| Rotted base citations re-pinned: `KVStore` (`staleWrite` 275-282, fence contract 314-331, `staleEpochWrite` 356-360), `SpiResourceProvider` (class :33, `ServiceLoader` scan :82), `StateMachineDefinition` (record :25, `builder` :32, `finalState` 96-100, `transition` 114-116, `onEntry` 120-124, `onExit` 128-132, `build` :134); then EVERY citation in the live sections audited, base ones included — 62 ranges, ten more tightened (`GovernorElection`, `BootstrapModule`, `SliceInvoker:80-87`, `EpochBearing:35-47`, `DHTClient`, `EntityForwardService`, `EntityFold`, `DeploymentManagerImpl`, `InMemoryStateMachine`, `ReplicaPlacement`, `DurableEntityFactory`, `ClusterDeploymentState:1553-1570`) | G2 |
| §3 row no longer calls the stream-path fence a "remaining gap" | G3 |
| v0.8.0 changelog-row typo | G4 |
| CTO-confirmed: the 381 s arithmetic with upward-only jitter, the `StepRefusal` marker, `maxFireAttempts` = 5 | — |
| Renames exempt from the codec fingerprint (CTO ruling): it hashes type identity, component types and positions, TAG and ENUM content — not component names | — |
| **D9 (owner): separate input and output.** `Saga<I, S, O, D>`: `run(I)`, pure `init` I→S, pure `finish` S→O; contract = `I`, `O`, `D`'s closure (signal payloads, receipts); `S` private and absent from `SagaOutcome`/`SagaStatus`; the "S on the boundary" limitation removed; §7.10 example and A9(a) updated | — |
| **Swap guard (CTO):** equal fingerprints are compatible only if no shared component name moves position within a type; named SHAPE lines kept in a content-addressed shape registry (CTO-confirmed), binding and request envelope carry the named-shape hash; A9(e) | — |
| **D8 refined (owner):** private state coexists freely, every decode site dispatches on the binding (sites enumerated); drift guard `BoundVersionFingerprintMismatch`; inbound contract fingerprint carried in the envelope and checked before decode, `ContractFingerprintMismatch`; `VersionBinding` carries `stateFingerprint` + `contractFingerprint`; `IncompatibleDurableCodec` retired; A9 rewritten (different `S` coexists, drift refused, contract mismatch refused, pure rename accepted) | G1 |

---

## Changelog — v0.10.0 (2026-10-02)

**#1828 review round 3 (v1828 r2), CTO rulings E2–E9, and owner decision D8 (v1828 N1).**

| What | v1828 | Ruling | Where |
|---|---|---|---|
| Each attempt bounded by `attemptTimeout` (default 30 s); expiry = `Sent`; no `Running` step can hang | N2 | E2 | §7.4, §7.11 A14(e) |
| Key-window origin = earliest attempt that was or may have been sent | N3 | E3 | §7.4, §7.11 A10 |
| Episodes: `maxElapsed` per owner episode, `maxAttempts` cumulative; defaults 20 / 10 min / 30 s / 200 ms→30 s; 381 s backoff total; real no-park bound derived | N4 | E4 | §7.4, §7.9, §7.11 A5, A8, A14 |
| `ReplyClassifier` per step: in-progress/conflict → `Sent`, not-processed/rate-limited → `NotSent`, `StepRefusal` → `Answered`, else `Sent` | N5 | E5 | §7.3, §7.4, §7.11 A16 |
| Failing deadline fires counted (`DeadlineFireFailed`), park `DeadlineTransitionFailed` after `maxFireAttempts`; `Cancel`/`Abandon`; the "deploy a fixed version" exit removed | N6 | E6 | §7.3, §7.6, §7.7, §7.8, §7.11 W8 |
| `StepAttempt` persists `ownerEpoch` and evidence (unrecorded = `Sent`); compensation `RetriesExhausted` row | N7 | E7 | §7.4, §7.6, §7.7 |
| Stale "pending under B7" removed | N8 | E8 | §7.6 |
| `SagaOutcome` carries `SagaInstanceId`; `delete(SagaInstanceId)`; where a sender gets the incarnation | N9 | E9 | §7.7 |
| `MemoryStorageEngine.java:69-73`, `PartitionFencedDurableEntity.java:692-697` pinned; D7c capacity consequence stated | citations, attack 1 | — | §3, §7.8, §7.9 |
| Fingerprint-only coexistence: identical TAG/ENUM/SHAPE fingerprint or refused at deploy; any change = new keyspace or migration; D5's bidirectional text and the unverified codec requirement removed; framed envelope noted out of scope | N1 | D8 (owner) | §6, §7.9, §7.11 A9 |

---

## Changelog — v0.9.0 (2026-10-02)

**Owner decisions D6–D7 on the #1828 review; round 2 complete.**

| What | Decision | v1828 | Where |
|---|---|---|---|
| Operation ids are 52-char base32 SHA-256 digests of a length-prefixed, domain-tagged `(keyspace, incarnation, sagaId, stepIndex)`; collision bound `n²/2²⁵⁷`; readable tuple kept alongside; deadline token built the same way | D6 | author-choice item 6 | §7.2, §7.8, §7.11 I1 |
| `NotSent` failures retry with backoff inside the budget; a brief endpoint gap never parks | D7a | should-fix (restart blip) | §7.4, §7.9, §7.11 A5, A8, A14 |
| One declared per-step `RetryBudget` shared by retries, re-invokes and lookups; bounded by the key window too; exhaustion parks (`OutcomeUnknown` or [author choice] `RetriesExhausted`) | D7b | B3 | §7.3, §7.4, §7.6, §7.7, §7.11 A14 |
| Transitive pin: the binding records the full dependency closure; the retirement gate covers every version in any live binding | D7c | B4 | §7.9, §7.11 A15 |

---

## Changelog — v0.8.0 (2026-10-02)

**Owner decisions D1–D5 on the #1828 review.**

| What | Decision | v1828 | Where |
|---|---|---|---|
| Failures tagged `NotSent` (definite) / `Sent` (ambiguous, R1 path); untagged = `Sent`; `Answered` refusal is definite (CTO-confirmed) | D1 | B2 | §7.4, §7.11 A11 |
| Compensation of step `j` receives the snapshot `S_j`; snapshots kept until terminal; storage bound stated | D2 | author-choice item 2 | §7.5, §7.6, §7.11 A12 |
| Retain code until resolved: force-retire → reconcile-only; resolution table per reason incl. `Waiting` → `Cancel`, `Abandon` always on parked instances; `resolve` takes the incarnation | D3 | B5, B7 | §7.7, §7.8, §7.9, §7.11 A7, A13, W5 |
| §7.9 binding rules apply to workflows; added #353 scope | D4 | author-choice item 4 | §6 |
| Bidirectional codec compatibility (additive optional fields); else new keyspace or migration | D5 | author-choice item 5 | §7.9, §7.11 A9 |

---

## Changelog — v0.7.1 (2026-10-02)

**PR #1828 review round 2 (v1828 report), CTO technical rulings T1–T5.** Owner-decision items from the
same review (B2 classifier, B3 retry budget, B4 dependency pin scope, B5/B7 resolution table, codec
direction, operation-key encoding, endpoint-absence parking, §7.9 for workflows) are deliberately NOT
addressed here.

| What | v1828 | Ruling | Where |
|---|---|---|---|
| `Replayable`/`Lookup` declare the downstream's `keyRetention`; `StepAttempt.at`; recovery past `keyRetention − clockSafetyMargin` is `Neither`; clock assumption stated | B1 | T1 | §7.3, §7.4, §7.6, §7.11 A10 |
| Deadline: arm first with a deterministic token, then persist `Waiting`; guarded no-op fires; idempotent re-arm on takeover; a failed deadline fire is not consumed | B6 | T2 | §7.8, §7.11 W8 |
| `NeedsReconciliation` persists the suspended phase and per-step compensation outcomes | B7 (part) | T3 | §7.6 |
| Signals address the incarnation; terminal/replaced incarnation rejected before dedup; parked-from-`Compensating` refuses signals | B8 | T4 | §7.7, §7.8, §7.11 W6, W7 |
| Lookup ordering condition in S6 and §7.10; stream-fence leftovers; §13 phase 4; S1 superseded; `StaleOwnerAppend` naming | should-fix, leftovers | T5 | §4.2, §8, §7.10, §11, §13, §14 |
| `DurableEntity.java` citations re-pinned (+1 line after the v0.7.0 javadoc reflow) | — | — | §3, §5.1, §7.8, §7.11.1 |

---

## Changelog — v0.7.0 (2026-10-02)

**#1827, Phase B: owner rulings R1–R5 written into §7; §14 S6–S10 resolved.** §7 rewritten; the saga is
still PLANNED (#354) and §7.11 is its acceptance.

| What | Ruling | Where |
|---|---|---|
| Three-way recovery by declared capability (`Replayable` / `Lookup` / `Neither`); `OutcomeUnknown` → `NeedsReconciliation`; operator resolutions; a marker never becomes success; ambiguous forward failures take the same path | R1 | §7.4, §7.7 |
| `StepContext` with `operationId`, distinct `compensationId`, diagnostic `attempt`; "new logical operation" defined | R2 | §7.2 |
| Accumulating state `S`, `forward(ctx, S) → R`, pure fold, persisted per step; compensation gets its own `R` | R3 | §7.3, §7.10 |
| Version pinned at creation and routing all bound work incl. dependent calls; loud failure on missing code; codec-compatible coexistence or refused rollout; drain-then-retire; force-retire to reconciliation; rollback redirects new starts | R4a, R4b | §7.9 |
| `WAIT_SIGNAL` specified (identity/dedup, buffering, rejection, deadline outcome, pin, compensation, shape, recovery, fold); definition registration through the slice factory | R5 | §7.8, §7.9 |
| Acceptance matrix filled; WAIT, missing-code, codec, dataflow and identity rows added | — | §7.11 |
| `SagaCause` → `SagaError`; `SagaResult` → `SagaOutcome` with non-terminal `Waiting` / `NeedsReconciliation`; `RerunPolicy` → `StepRecovery` | — | §7.3, §7.7 |

Author choices the ruling text does not fix are tagged **[author choice]** in §6 and §7: keyspace and
incarnation in the operation key; ambiguous forward failure treated as unresolved; compensation's own
recovery capability; `compensation(ctx, S, R)`; the meaning of the operator's "compensate"; the form of the
codec-compatibility declaration; applying §7.9 to workflows.

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
