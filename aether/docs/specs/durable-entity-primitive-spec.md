# Durable Single-Writer Entity — Design Specification

*The primitive for durable workflows & sagas.*

**Version:** 0.1
**Status:** Draft
**Date:** 2026-06-24
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
14. [Open Questions](#14-open-questions)
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

The hard tension is durable-and-single-writer-correct (CP) **vs** scalable (partitioned). The industry
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
        └──►  Saga                       = entity that orchestrates steps + compensation
```

Each layer is independently useful: the fence fixes a latent split-brain bug today (#345); the entity
serves any durable-single-writer need; workflow and saga are convenience facades.

---

## 3. Current Substrate (verified)

| Capability | State | Anchor |
|---|---|---|
| Custom-resource SPI (`@ResourceQualifier` + `ResourceFactory` via `ServiceLoader`) | ✅ **mechanical** — a new entity resource is annotation + factory + services entry, no framework edits | `ResourceQualifier.java:13-18`; `SpiResourceProvider.java:45-47` |
| `StateMachineDefinition<S,E,C>` (builder, transitions, onEntry/onExit, finalState) | ✅ exists, **unused at runtime, in-memory only** | `StateMachineDefinition.java:24,94,104-127`; `InMemoryStateMachine.java:17-19` |
| Partitioned placement + per-partition owner (HRW) | ✅ exists (DHT ring, governor/owner) | `ReplicaPlacement.java:16-34`; `GovernorElection.java:40-46` |
| **Per-key write fence (single-writer enforcement)** | ❌ **MISSING — epic #345.** `ownerEpoch`/`communityEpoch` computed but unchecked; only `staleLeaderWrite` (LeaderKey) fences | `AetherValue.java:592-609,1272-1302`; `KVStore.java:93-94`; pattern exists `BootstrapModule.rewriteIfOwnerStale:367-402` |
| Per-key serialization queue (serialize same-key, parallel across keys) | ❌ **MISSING** | — |
| Durable per-instance timers (one-shot, fire-and-delete, survive handover) | ❌ **MISSING** (scheduler is per-slice-method cron) | `ScheduledTaskManager.java:178,333` |
| Runtime→slice invocation (timer fire, dispatch) | ✅ exists | `SliceInvoker.java:95-96` |
| Durable KV store (replicated, quorum) | ✅ exists, but **LWW/AP, not fenced** (→ #345) **and in-memory — not restart-durable** (→ #349) | `DHTClient.java:39-76`; `MemoryStorageEngine.java:71-75` |

**Reading:** the *convenience* substrate (resource SPI, FSM library, placement) exists; the *correctness*
substrate (fence, per-key serialization, durable per-instance timers) is the real net-new work, gated
on epic #345.

---

## 4. Architecture — the durable entity

### 4.1 The model

An entity instance is `(key, state, ownerEpoch, pendingTimers)`. `state` is an application-defined
immutable value (record / sealed interface). The entity is **placed** by hashing `key` to a partition
(HRW), whose **owner is the single fenced writer**. All writes go through the owner and are
**epoch-fenced** (#345): a write tagged with a stale owner epoch is rejected, so a deposed owner cannot
commit after handover.

### 4.2 Fenced single-writer (the correctness core)

> **Decision.** Every entity write is `update(key, mutator)` executed **only on the partition owner**
> and committed via a **fenced write** (#345): `write(key, newState, ownerEpoch)` succeeds iff
> `ownerEpoch` is current.
>
> **Why.** This is the per-partition-fenced-leader pattern. The owner serializes writes (per-key queue,
> §4.3); the epoch fence makes single-writer a *guarantee* across handover, not a convention. A reader
> on any node sees the last committed state because writes are RF-replicated under the fence.
>
> **Rejected alternative.** *Unfenced owner* (today) — split-brain double-writes during handover (the
> #345 bug). *Per-key Paxos/Rabia group* — correct but a consensus group per key doesn't scale to
> millions of entities; the fenced-owner-over-replicated-partition is the scalable form.

### 4.3 Per-key serialization

> **Decision.** The owner runs a **per-key serialization queue**: operations on the same `key` are
> applied in total order; different keys proceed in parallel.
>
> **Why.** Total per-entity order is required for state correctness; cross-key parallelism is required
> for scale. A `ConcurrentHashMap<Key, Queue>` with a per-key worker gives both. (Net-new — §3.)
>
> **Rejected alternative.** *Single global queue per owner* — serializes unrelated entities, destroying
> scale. *No serialization* — concurrent same-key updates race even under the fence.

### 4.4 State representation — fenced KV snapshot vs fenced log (re-weighted for durability)

> **Decision.** The entity API hides the representation. For the **restart-durable** path, **prefer a
> fenced log on the stream substrate**; a fenced KV snapshot on the DHT remains the simplest
> **in-memory / HA-only** form for an initial functional cut.
>
> **Why (updated — persistence reality, epic #349).** Both forms are state-as-truth / no-replay; the
> deciding factor is *what is actually durable*. The DHT is `MemoryStorageEngine` — **in-memory, lost on
> a full-cluster restart** — so "fenced KV snapshot on the DHT" is HA but **not restart-durable** until
> someone builds a *persistent DHT engine* (the single largest storage build, option (c) of epic #349).
> The **stream substrate, by contrast, has a built, spec-aligned durable path one wire away** (seal →
> `LocalDiskTier`/S3; #349 path (a)) — so a **fenced log on a stream partition rides that same wiring**,
> gets restart-durability cheaply, and yields ordering + free audit/event-sourcing, overlapping the
> streaming-hardening roadmap (#265/#261). The log stays **no-replay**: the entity folds to a snapshot
> and tails; the governor owns the fold, so there is no determinism or migration burden.
>
> **Rejected alternative.** *KV-snapshot-on-DHT as the durable default* — quietly assumes a durable DHT
> that does not exist; making it restart-durable is the biggest build in the storage stack.
> *Replay/event-sourcing as the user contract* — rejected; the log is an internal durability mechanism,
> not a determinism contract exposed to authors.
>
> **Sequencing.** KV-snapshot (in-memory, HA-only) is acceptable for a first functional cut on the #345
> fence; the **restart-durable** entity is the fenced log on the durable stream substrate (#349). Both
> behind one API, so the move costs no author churn.

### 4.5 Timers

Each owner keeps an in-memory timer wheel for its entities; entries are **persisted (fenced) under a
parallel key prefix** so they survive handover (the new owner rebuilds the wheel by scanning its
arc). On expiry the owner applies the scheduled operation via the same path as an external update.
One-shot, fire-and-delete; auto-cancelled on terminal state. (Distinct from per-slice cron — §11.)

---

## 5. The `DurableEntity` API

```java
public interface DurableEntity<K, S> {
    Promise<S>          create(K key, S initial);              // fails if key exists
    Promise<Option<S>>  get(K key);                            // linearizable read
    Promise<S>          update(K key, Fn1<S, S> mutator);      // fenced single-writer mutation
    Promise<TimerToken> scheduleTimer(K key, Duration delay, Fn1<S, S> onFire);
    Promise<Unit>       cancelTimer(K key, TimerToken token);
    Promise<Unit>       delete(K key);                          // only if mutator marks terminal

    record TimerToken(String value) {}
}
```

`update` runs the mutator **on the owner**, inside the per-key queue, and commits the result under the
fence. The mutator is a **pure** `S → S` (no IO); side effects live in slice code consuming the result
(§10). `mutator` may return a value marked terminal (sealed-type final case) to enable `delete` and
timer auto-cancel.

---

## 6. Workflow specialization

*Supersedes #190 — the Persistent-Workflow design, carried forward as a specialization.*

A workflow is `DurableEntity` where `update` is an **FSM transition** driven by the existing
`StateMachineDefinition`:

```java
public interface PersistentWorkflow<S, E> {           // facade over DurableEntity<String, S>
    Promise<S>           start(String id, S initial);
    Promise<S>           dispatch(String id, E event); // update(id, s -> fsm.apply(s, event))
    Promise<Option<S>>   current(String id);
    Promise<TimerToken>  scheduleTimer(String id, Duration delay, E event);
    Promise<Unit>        cancelTimer(String id, TimerToken token);
    Promise<Unit>        delete(String id);
}
```

`dispatch` validates the event against the FSM (reject before any write), applies the pure transition
on the owner under the fence, returns the post-transition state. **No replay, no determinism contract**
(state-as-truth). Provisioned via a qualifier annotation (`@OrdersWorkflow`), owned by the slice that
defines the FSM. The full #190 API, usage example, encapsulation rules, audit-stream, and observability
metrics carry forward unchanged — they are the workflow facade over the entity. (See #190's draft for
the worked `OrderProcess` example; it stands as-is on top of this primitive.)

---

## 7. Saga specialization

A saga is `DurableEntity` whose state is a **step ledger** `(stepIndex, completed[], compensating?)`,
orchestrating a forward sequence of steps and, on failure, running compensations in reverse.

```java
public interface Saga<C> {                              // facade over DurableEntity<String, SagaState>
    Promise<SagaResult> run(String id, C context);      // drives forward; compensates on failure
    Promise<Option<SagaState>> status(String id);
}
```

> **Decision.** Steps and compensations are **slice-provided functions**; the entity records each step's
> completion durably (fenced) so a recovered saga resumes after the last committed step. Offer **one
> optional managed primitive — a journaled "run-once" step** keyed by `(id, stepIndex)`: the runtime
> records the step result under the fence so a crash *after* the side effect but *before* the state
> commit does not re-run it.
>
> **Why.** Sagas are the canonical multi-step-with-compensation pattern; the durable step ledger is
> exactly entity state. The run-once primitive closes the at-least-once failure window (the one thing
> the pure side-effects-in-app-code model lacks vs Restate's `ctx.run`) **without** an SDK or replay —
> the result is journaled in entity state, not reconstructed.
>
> **Rejected alternative.** *Temporal-style managed activities* — re-introduces an activity model +
> retry framework + its complexity. *No run-once* — every step's side effect must be idempotent by the
> author with no affordance; acceptable but strictly weaker.

Cross-entity orchestration (parent/child, fan-out/fan-in) is composed by slice code via `SliceInvoker`,
not a runtime primitive (v1).

---

## 8. Execution Semantics

- **Single-writer total order per entity** (owner + per-key queue + fence). Across entities: no order.
- **Linearizability** — a committed `update` is durable across RF replicas under the fence; a later
  `get` from any node sees that state or a later one. (Requires #345; the KV is LWW/AP until then.)
- **No replay** — recovery resumes from current durable state; transitions never rerun; nondeterminism
  permitted.
- **Idempotency** — each update carries a stable per-entity monotonic counter `(key, n)`; slice
  side-effect code uses it as an idempotency key. The saga run-once primitive uses `(id, stepIndex)`.

---

## 9. Failure Model

- **Technical vs business failure** (JBCT): transport/peer/handover failures travel the error channel
  and are retried by the runtime; business outcomes travel the success channel and drive transitions.
- **Bounded per-entity unavailability** — on owner departure, the partition's entities are unavailable
  until the new owner is elected (seconds, SWIM); dispatches retry transparently. The fence (#345)
  guarantees the *old* owner cannot commit after handover.
- **Permanent loss** — if all replicas of a partition are lost, its entities are lost (inherited DHT
  durability; governed by RF + ops). No extra guarantee invented.

---

## 10. Side Effects

The mutator is pure. After `update`/`dispatch` returns, the slice has the new state and performs
whatever side effect it implies (call a slice, HTTP, notify, DB) using its own resources — the runtime
does not run side effects on the slice's behalf (avoids re-creating Temporal's Activity model). The
**optional saga run-once step** (§7) is the single managed affordance, for closing the crash-after-
effect-before-commit window without an SDK.

---

## 11. Substrate dependencies (epic pieces)

| Piece | Status | Note |
|---|---|---|
| **0 — Persistent backing** (epic #349, sibling) | **MISSING — durability foundation** | prod storage is memory-only (no restart durability); the entity is HA until #349 wires it. Log-on-stream (§4.4) rides #349 path (a) |
| **1 — Per-key ownership fence** (#345) | **MISSING — foundation, rc2** | reject stale-owner write; generalize `rewriteIfOwnerStale` / add `putFenced` |
| 2 — Per-key serialization queue | MISSING | serialize same-key, parallel across keys |
| 3 — Durable per-instance timers | MISSING | one-shot, fenced-persisted, handover-recovered |
| 4 — `DurableEntity` core | new | fenced KV snapshot state; owner routing |
| 5 — Workflow facade (#190) | new (design done) | entity + `StateMachineDefinition` |
| 6 — Saga facade + run-once step | new | step ledger + journaled step |
| 7 — Observability / audit stream | new | metrics + opt-in transition/step audit to a stream |

Per-slice cron stays on `ScheduledTaskManager` (independent). **Two foundations gate this stack:** the
**#345 fence** (correctness) and **#349 persistent backing** (durability). The fenced **log** state
option (§4.4) is the cheaper durable path — it rides #349 path (a) and overlaps the streaming roadmap
(#265/#261).

---

## 12. Reconciliation to Existing Code

| Capability | Current | Target | Tag | Anchor |
|---|---|---|---|---|
| Per-key fence | `ownerEpoch` computed, **unchecked**; LeaderKey-only fence | generalize to per-key write fence (#345) | **MISSING** | `KVStore.java:93`; `BootstrapModule.java:367-402` |
| `StateMachineDefinition` | exists, unused, in-memory | consume in the workflow facade | **REUSE** | `StateMachineDefinition.java:24` |
| Resource SPI | exists, mechanical | register `DurableEntity`/`PersistentWorkflow`/`Saga` types | **REUSE** | `SpiResourceProvider.java:45` |
| Per-key serialization | none | owner-side per-key queue | **MISSING** | — |
| Per-instance timers | per-slice cron only | durable one-shot per-entity timers | **MISSING** | `ScheduledTaskManager.java:178` |
| Durable KV | LWW/AP quorum | fenced (via #345) | **EXTEND** | `DHTClient.java:39` |

---

## 13. Implementation Phases

| Phase | Scope | Epic piece |
|---|---|---|
| 0 | **Per-key ownership fence** — the substrate-independent correctness fix | #345 / piece 1 (rc2) |
| 1 | `DurableEntity` core — fenced KV-snapshot state, owner routing, per-key serialization queue | pieces 2, 4 |
| 2 | Durable per-entity timers (fenced-persisted, handover recovery) | piece 3 |
| 3 | **Workflow facade** — `PersistentWorkflow` over the entity + `StateMachineDefinition` (the #190 design) | piece 5 |
| 4 | **Saga facade** — step ledger + journaled run-once step | piece 6 |
| 5 | Observability + audit stream + operator API | piece 7 |
| 6 | Hardening — docs, sample slices, chaos/soak under governor handover | — |

**Acceptance:** a sample workflow *and* a sample saga run to completion across `kill-9` of the owning
node with no slice-author-visible errors; the fence rejects a stale-owner write in a split-brain test;
100k entities within memory/throughput budgets on a 5-node cluster.

---

## 14. Open Questions

1. **State store: KV-snapshot vs fenced-log — re-weighted (§4.4).** Given the persistence reality (DHT
   is in-memory; epic #349), the **restart-durable** path is a fenced log on the durable stream
   substrate (rides #349 path a); KV-snapshot is the in-memory/HA-only first cut. Confirm the
   re-weighting.
2. **Fence mechanism (in #345):** extend `staleLeaderWrite` to governor/ownership keys vs a general
   `putFenced` on the DHT vs offset-CAS for the log path. Decided in the #345 design.
3. **Saga compensation semantics:** best-effort reverse vs guaranteed; partial-compensation handling.
4. **Entity GC / retention:** terminal entities — retain (history) vs TTL-GC; per-facade default.
5. **Should `PersistentWorkflow` remain a distinct public facade, or just `DurableEntity` + a
   `StateMachineDefinition` adapter?** (Ergonomics vs surface area.)
6. **Visibility/query + signals** — the underestimated long tail of durable execution; scope for v1?

---

## 15. References

- **Durable entities / virtual actors:** Microsoft Orleans grains — https://learn.microsoft.com/en-us/dotnet/orleans/grains/ · Azure Durable Entities — https://learn.microsoft.com/en-us/azure/azure-functions/durable/durable-functions-entities · Restate virtual objects — https://docs.restate.dev/concepts/durable_building_blocks · Dapr actors — https://docs.dapr.io/developing-applications/building-blocks/actors/actors-overview/
- **Per-partition fenced leader:** Restate first-principles (Bifrost, epoch fencing) — https://www.restate.dev/blog/building-a-modern-durable-execution-engine-from-first-principles · CockroachDB range leases — https://www.cockroachlabs.com/docs/stable/architecture/replication-layer · Spanner — https://cloud.google.com/spanner/docs/whitepapers
- **Durable-execution model (no-replay vs replay):** Vanlightly, demystifying determinism — https://jack-vanlightly.com/blog/2025/11/24/demystifying-determinism-in-durable-execution · DBOS architecture — https://docs.dbos.dev/architecture
- **Internal:** #345 (fence epic), #190 (superseded workflow draft), #265/#261 (streaming substrate), `StateMachineDefinition`.

---

*Companion to epic #345. Supersedes #190 (carried forward as §6). Built on the per-key ownership fence
(#345, piece 1). Workflow and saga are facades over one `DurableEntity` primitive.*
