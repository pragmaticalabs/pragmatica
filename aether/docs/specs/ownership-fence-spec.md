# Per-Key Ownership Fence — Design Specification

*Reject a stale owner's write. The fencing-token substrate for single-writer-per-key.*

**Version:** 0.1
**Status:** Draft
**Date:** 2026-06-24
**Author:** design-stream
**Epic:** #345 (this is **piece 1**, the foundation)
**Consumed by:** the durable-entity primitive (`durable-entity-primitive-spec.md`) and everything on it (workflow, saga)
**Related:** #265/#261 (stream ownership & durability), #336 (reconciler-under-load)

---

## Table of Contents

1. [Overview & Goals](#1-overview--goals)
2. [Background — two stores, one Epoch (verified)](#2-background)
3. [Architecture — the fencing-token model](#3-architecture)
4. [Change 1a — generalize `staleLeaderWrite` (Store A)](#4-change-1a)
5. [Change 1b — data-plane fence (DHT + stream)](#5-change-1b)
6. [Owner-routed linearizable reads](#6-owner-routed-linearizable-reads)
7. [Correctness model](#7-correctness-model)
8. [Error Model](#8-error-model)
9. [Reconciliation to Existing Code](#9-reconciliation-to-existing-code)
10. [Implementation Phases](#10-implementation-phases)
11. [Open Questions](#11-open-questions)
12. [References](#12-references)

---

## 1. Overview & Goals

### 1.1 Purpose

Make single-writer-per-key a **guarantee**, not a convention. Ownership of partitioned keys is decided
by HRW and is advisory: the write path does not check the owner's epoch, so a **stale owner** (after a
reshuffle / partition / governor handover) can still commit, and two owners can double-write a key →
split-brain. This spec adds an **epoch fence** — a fencing token enforced at the point each replica
commits — so a deposed owner's write is rejected everywhere, and pairs it with **owner-routed reads** so
the resulting state is linearizable.

### 1.2 The key insight

The fence needs **no new consensus.** The owner `Epoch` is *already* a CP, monotonic value: governor and
ownership records commit to the Rabia-backed KV (Store A), which is already leader-fenced. #345 is
therefore narrow: **propagate the epoch that already exists to the data-plane writers, and enforce its
monotonicity at the per-replica commit point.** This is the classic fencing-token pattern (a monotonic
token; the resource rejects a stale one).

### 1.3 Scope — two coordinated changes, separable in time

| Change | What | Value | Milestone |
|---|---|---|---|
| **1a** | Generalize `staleLeaderWrite` from `LeaderKey` to *any* epoch-bearing Store-A key (governor, ownership) | **Fixes the latent split-brain bug today**; can ship alone | **rc2** |
| **1b** | Add the epoch fence to the data-plane write paths (DHT `putVersioned`, stream append) + owner-routed linearizable reads | The **substrate the durable entity needs** | rc3 |

### 1.4 Goals / Non-Goals

**Goals:** reject any write carrying a stale owner epoch, at the point of durability, on every replica;
per-ownership-domain granularity (protects new keys); owner-routed linearizable reads; reuse the
existing CP `Epoch` as the source of truth; no new consensus group.

**Non-Goals:** general DHT linearizability for *non*-owner-routed reads (owner-routing is the path);
re-architecting the DHT to a consensus log; the entity/workflow/saga features (epic pieces 2–7).

---

## 2. Background

*(All verified against source.)*

### 2.1 Two distinct stores

- **Store A — Rabia KV** (`KVStore` as a `StateMachine<KVCommand>`, written via `ClusterNode.apply(List<KVCommand>)`). **CP, consensus-ordered, already leader-fenced.** Governor and ownership records commit here:
  - `GovernorAnnouncer.applyAnnouncement:199-211` → `KVCommand.Put(GovernorAnnouncementKey, …)`.
  - `BootstrapModule.java:402-408` → `KVCommand.Put(DhtPartitionOwnershipKey, …)`.
- **Store B — the DHT** (`DistributedDHTClient`, byte[] keys, HLC-LWW quorum). **AP, not consensus-ordered.** Entity data lives here. Plus the **stream** ring (`StreamPartitionManager`/`OffHeapRingBuffer`), likewise unfenced.

### 2.2 The `Epoch` (source of truth, already CP)

`Epoch(long rabiaTerm, long localCounter)` — `Comparable`, lexicographic `(rabiaTerm, localCounter)`
(`Epoch.java:19`), with `isAtLeast`/`isStrictlyAfter`/`nextCounter`. Minted/bumped on handover:
`withGovernorChange` does `nextTerm = communityTerm + 1; Epoch.epoch(nextTerm, 0)`
(`AetherValue.java:592-609`) — term strictly increases across handovers (monotonic). The same `Epoch`
is in `DhtPartitionOwnershipValue` (`:1272-1302`). A node already observes its current epoch via the
governor-change event.

### 2.3 The existing fence (the pattern)

`staleLeaderWrite` (`KVStore.java:93-98`) is a monotonic CAS *inside the Rabia applier*:
```java
return put.key() instanceof LeaderKey
       && put.value() instanceof LeaderValue incoming
       && storage.get(put.key()) instanceof LeaderValue stored
       && incoming.viewSequence() <= stored.viewSequence();   // stale-or-equal ⇒ reject
```
Called from `handlePut:77-79`; on rejection it writes nothing and emits no `ValuePut`. It runs
deterministically on every replica inside `process(Batch):54`, and gates **only `LeaderKey`**.

### 2.4 The data-plane commit points (where 1b hooks)

- **DHT:** `DistributedDHTClient.put:98-119` stamps `version = hlc.now().packed():108`, fans the same
  versioned put to RF replicas; each commits at `MemoryStorageEngine.putVersioned:59-65` →
  `computeVersionedEntry:67-76` (`existing.version() >= version ⇒ drop`). Stored value =
  `VersionedEntry(byte[] value, long version):36` — **HLC only, no epoch**.
- **Stream:** `StreamPartitionManager.appendToPartition:558-566` → `buffer.append(payload, timestamp):564`
  — append carries **payload+timestamp+offset only, no epoch**.
- **Reads:** `DistributedDHTClient.get:75-95` is a quorum read with **no read-repair, no R+W>N** — a
  stale-but-reachable replica can serve an old value.

---

## 3. Architecture

### 3.1 Fencing tokens

> **Decision.** Every fenceable write carries the writer's believed-current owner `Epoch` (a fencing
> token). Each replica maintains a **monotonic high-water epoch per ownership domain** and **rejects any
> write whose epoch is below the high-water**, advancing the high-water on accept. HLC version is the
> within-epoch tiebreak (preserving today's LWW behavior inside an epoch).
>
> **Why.** A monotonic token enforced at the resource is the standard, minimal way to make leases/locks
> safe under handover (Chubby sequencers, ZooKeeper `zxid`, Raft term, Kleppmann's fencing tokens). Once
> any replica has seen the new owner's epoch, the old owner can never commit there again — so even a
> single honest replica breaks the split-brain. It needs only a `long`-pair of metadata and a comparison
> on the existing commit path.
>
> **Rejected alternative.** *Lease/timeout-based single-writer* (old owner stops on lease expiry) — relies
> on clocks and a stop-the-world assumption; a paused-then-resumed old owner still double-writes (the
> exact failure fencing tokens were invented to fix). *Per-key Paxos/Rabia* — correct but a consensus
> group per key/entity doesn't scale.

### 3.2 Per-ownership-domain granularity

> **Decision.** The high-water is tracked **per ownership domain** (community / DHT-partition /
> stream-partition), not per key.
>
> **Why.** It matches the ownership model (an owner owns the whole arc, not individual keys), it's cheaper
> (one epoch per domain vs per value), and it **rejects stale writes to *new* keys** — a per-key
> high-water can't, because a never-before-seen key has no prior epoch to compare against, leaving a
> split-brain hole for fresh inserts (exactly what entity `create` does).
>
> **Rejected alternative.** *Per-key epoch only* — leaves new-key inserts unfenced. (We still persist the
> epoch *in* each `VersionedEntry` for recovery and within-key ordering, but the **domain** high-water is
> the authoritative fence.)

### 3.3 Source of truth & propagation

The epoch lives in Store A (CP) and is observed by every node on governor/ownership change. A writer
stamps each data-plane write with its current epoch for the key's domain. A replica seeds its per-domain
high-water from Store A's current `ownerEpoch` (the authoritative value) and advances it on accepted
writes — so enforcement needs **no Store-A read per write** (the high-water is a local cache, correct
because it only ever moves forward and is anchored to the CP epoch).

### 3.4 Enforcement at the commit point

The check runs where each replica *commits durably* — `putVersioned` (DHT) and `appendToPartition`
(stream) — not at the client/owner. This is essential: a stale owner must be rejected by the replicas it
talks to, regardless of what it believes.

---

## 4. Change 1a — generalize `staleLeaderWrite` (Store A)

> **Decision.** Broaden `staleLeaderWrite` from `LeaderKey`-only to **any epoch-bearing Store-A key**
> (`GovernorAnnouncementKey`, `DhtPartitionOwnershipKey`, …): compare the incoming value's `Epoch`
> against the stored value's `Epoch`, reject `incoming ≤ stored`.
>
> **Why.** This is the same monotonic-CAS already proven for `LeaderKey`, in the same deterministic Rabia
> applier — so it's a small, low-risk change that **immediately closes the live split-brain bug** for
> governor and ownership records (the ones that decide who may write data). It can land in rc2 alone,
> ahead of 1b.
>
> **Rejected alternative.** *Leave Store-A ownership unfenced and only fence the data plane (1b)* — but
> ownership *records themselves* are the root: a stale governor that can still write its own
> `GovernorAnnouncement` re-asserts ownership. Fence the ownership records first.

Mechanics: extend the guard in `KVStore.staleLeaderWrite` to a small set of epoch-bearing key/value
types exposing an `Epoch` accessor; otherwise unchanged (same `handlePut` rejection path, no
notification on reject).

---

## 5. Change 1b — data-plane fence (DHT + stream)

> **Decision.** Add an `Epoch` to the data-plane write and enforce per-domain monotonicity at each
> replica's commit point.
>
> **(a) DHT:** extend `VersionedEntry` to `(value, hlcVersion, epoch)`; thread the writer's epoch through
> `DistributedDHTClient.put` (`:108`) → `DHTNode.handlePutRequest` (`:179`) → `putVersioned`. In
> `computeVersionedEntry` (`:67-76`), reject when `incomingEpoch < domainHighWater`; within the same
> epoch keep the HLC `version` LWW check. Advance the per-domain high-water on accept.
>
> **(b) Stream:** add a writer-supplied `ownerEpoch` to `publishLocal`/`appendToPartition`
> (`:558-566`); reject the append (before `buffer.append`, `:564`) when the epoch is below the partition
> high-water. The owner-local offset increment handles within-epoch ordering; the epoch fence prevents
> two owners.
>
> **Why.** These are the single durable-commit points per replica (§2.4) — the only places that
> guarantee *every* replica enforces the fence. Reusing the existing HLC `version` as the within-epoch
> tiebreak means no behavior change inside a stable epoch; the epoch only bites across handovers.
>
> **Rejected alternative.** *Fence at the client/owner before sending* — a buggy/stale owner bypasses it.
> *Offset-CAS only for the stream* — catches concurrent appends but not a stale owner replaying a
> contiguous batch (`ReplicationReceiveHandler` accepts contiguous offsets today); the epoch is the
> primary fence, offset-CAS at most a within-epoch belt-and-suspenders.

---

## 6. Owner-routed linearizable reads

> **Decision.** Reads of fenced data are **routed to the current owner**, which serves from its local
> replica; on taking ownership at a new epoch, an owner **catches up** (reads the domain's latest
> committed state from a write-quorum) before serving.
>
> **Why.** Under single-writer + the fence, the **current owner holds the latest committed state** — so
> an owner-local read is linearizable, with no read-repair or R+W>N machinery on the general DHT path
> (which has neither today, §2.4). The takeover catch-up closes the one gap: a brand-new owner must not
> serve before it has the latest committed write from the prior epoch. Routing resolves the owner from
> the CP ownership record (Store A), so a deposed owner is never selected.
>
> **Rejected alternative.** *Quorum read with epoch-max reconciliation* — works without owner-routing but
> adds read-repair-like reconciliation to every read; heavier, and unnecessary once reads go to the
> single writer. Kept as the fallback for any unavoidable non-owner read path. *Read from any replica*
> (today) — non-linearizable; a stale replica serves old state.

---

## 7. Correctness model

- **No split-brain double-write.** A new owner at epoch `N` writes to a quorum; any replica that has seen
  `N` rejects the old owner's epoch `< N` write. Since reads require the owner (resolved from the CP
  record at `N`) and writes require ≥ the domain high-water, the old owner can neither commit nor be
  read from. The window where both believe they own ends as soon as `N` is observed on the path.
- **Monotonicity & recovery.** The per-domain high-water only advances; on restart a replica re-seeds it
  from Store A's `ownerEpoch` (CP) and from the max epoch in its stored values — never regressing.
- **Within-epoch semantics unchanged.** Inside a stable epoch the HLC `version` LWW check is exactly
  today's behavior; the fence is inert until a handover bumps the epoch.
- **Liveness.** A genuinely-current owner is never spuriously fenced (its epoch equals the high-water).
  Bounded unavailability is the handover interval (seconds), as today.

---

## 8. Error Model

| Surface | `Cause` | Caller action |
|---|---|---|
| Fenced KV put | `StaleEpoch(key, presented, current)` | re-resolve owner; retry against current owner/epoch |
| Stream append | `StaleEpochAppend(stream, partition, presented, current)` | same |
| Owner read (deposed) | `NotCurrentOwner(redirectTo)` | route to the resolved current owner |

Rejections are **silent at the data layer** (no value mutation, no notification — mirroring
`staleLeaderWrite`); the typed `Cause` surfaces to the *caller* (entity/owner), which retries against the
new owner — invisible to slice authors, as with all platform-level retries.

---

## 9. Reconciliation to Existing Code

| Capability | Current | Target | Tag | Anchor |
|---|---|---|---|---|
| Store-A epoch fence | `LeaderKey` only (`staleLeaderWrite`) | any epoch-bearing key (governor/ownership) | **EXTEND (1a)** | `KVStore.java:93-98,77-79` |
| `VersionedEntry` | `(value, hlcVersion)` | `(value, hlcVersion, epoch)` | **EXTEND (1b)** | `MemoryStorageEngine.java:36,67-76` |
| DHT put fence | none (LWW only) | per-domain epoch gate at commit | **NEW (1b)** | `DistributedDHTClient.java:108`; `DHTNode.java:179`; `MemoryStorageEngine.java:59-76` |
| Stream append fence | none (offset increment only) | epoch gate before `buffer.append` | **NEW (1b)** | `StreamPartitionManager.java:558-566` |
| Per-domain high-water | none | in-memory table, CP-seeded, monotonic | **NEW (1b)** | — |
| Reads | quorum, no repair | owner-routed + takeover catch-up | **NEW (1b)** | `DistributedDHTClient.java:75-95` |
| `Epoch` source | CP, computed, **unchecked on data plane** | the fencing token | **REUSE** | `Epoch.java:19`; `AetherValue.java:592-609,1272-1302` |

---

## 10. Implementation Phases

| Phase | Scope | Milestone |
|---|---|---|
| **1a** | Generalize `staleLeaderWrite` to governor/ownership keys; chaos test: stale governor's `GovernorAnnouncement`/ownership write rejected post-handover | **rc2** |
| **1b-i** | `Epoch` in `VersionedEntry` + per-domain high-water + gate `putVersioned`; thread epoch through `put`/`handlePutRequest` | rc3 |
| **1b-ii** | Stream append epoch gate (`appendToPartition`); thread epoch through publish/forward | rc3 |
| **1b-iii** | Owner-routed reads + takeover catch-up; typed `StaleEpoch`/`NotCurrentOwner` causes | rc3 |
| **verify** | Split-brain test (two owners, old rejected on every replica); linearizable read-your-writes across handover; perf: fence adds ≤1 comparison + 1 `long`-pair per write | rc3 |

**Acceptance:** a deposed owner's write is rejected on every replica; a read after a committed write
returns that write or later across a governor handover; within a stable epoch, throughput is unchanged.

---

## 11. Open Questions

1. **High-water domain key.** Is "ownership domain" the community, the DHT partition, or the
   stream-partition — and is there one unified domain id, or per-subsystem? (Affects the high-water table
   key.) Recommend a single `OwnershipDomain` abstraction keyed uniformly.
2. **High-water persistence.** Pure in-memory cache re-seeded from Store A on restart, or persisted
   alongside data? Recommend in-memory + CP re-seed (simplest, correct).
3. **Takeover catch-up source.** Read-quorum vs the prior owner's replica set (ties to #261 backfill).
4. **1a scope of keys.** Exactly which Store-A key types become epoch-fenced in rc2 (governor, DHT
   ownership — others?).
5. **Stream STRONG path.** `StreamConsensusCommand` also carries no epoch; fence it in 1b-ii or note it.

---

## 12. References

- **Fencing tokens:** Kleppmann, *How to do distributed locking* — https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- **Sequencers / epoch fencing:** Google Chubby (§2.4 sequencers) — https://research.google/pubs/pub27897/ · ZooKeeper `zxid`/epoch — https://zookeeper.apache.org/doc/current/zookeeperInternals.html
- **Epoch-fenced single-writer at scale:** Restate first-principles (partition-leader epochs) — https://www.restate.dev/blog/building-a-modern-durable-execution-engine-from-first-principles
- **Internal:** epic #345, `durable-entity-primitive-spec.md` (consumer), `KVStore.staleLeaderWrite`, `Epoch`.

---

*Epic #345, piece 1. 1a (generalize `staleLeaderWrite`) is the rc2 correctness fix; 1b (data-plane fence
+ owner-routed reads) is the entity substrate. The fence reuses the already-CP `Epoch`; it adds no
consensus.*
