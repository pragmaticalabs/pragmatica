# Placement-Aware Stream Hydration — Design Specification

**Version:** 0.2
**Status:** Draft — **decision-complete** (all §14 open questions resolved 2026-07-04; three by the
landed #262/#410 two-knob + failover-convergence work, two by config-knobbed defaults)
**Date:** 2026-06-24 (updated 2026-07-04)
**Author:** design-stream
**Issue:** #265 (folds in #261 backfill fix)
**Related:** #165 (event-stream production readiness epic), #241 (community placement), #261 (backfill)
**Amends:** `stream-offheap-budget-spec.md`, `in-memory-streams-spec.md` (DD-1), `streaming-spec.md` (§7 ownership)

---

## Table of Contents

1. [Overview & Goals](#1-overview--goals)
2. [Current State (verified)](#2-current-state-verified)
3. [Architecture Overview](#3-architecture-overview)
4. [Placement-Gated Hydration](#4-placement-gated-hydration)
5. [The Reshuffle Lifecycle — materialize / backfill / release (folds in #261)](#5-the-reshuffle-lifecycle)
6. [Budget Accounting & the must-not-diverge reframe](#6-budget-accounting--the-must-not-diverge-reframe)
7. [Partition-Count Cap (derived, create-time)](#7-partition-count-cap-derived-create-time)
8. [Non-Replica Read/Write Path (reuse forwarding)](#8-non-replica-readwrite-path)
9. [Reconciliation with Existing Specs](#9-reconciliation-with-existing-specs)
10. [Configuration Model](#10-configuration-model)
11. [Error Model](#11-error-model)
12. [Implementation Plan](#12-implementation-plan)
13. [Reconciliation to Existing Code](#13-reconciliation-to-existing-code)
14. [Open Questions](#14-open-questions)
15. [References](#15-references)

---

## 1. Overview & Goals

### 1.1 Purpose

Today every node materializes an in-memory ring (buffer + offset index + first data segment) for
**every partition of every stream** — an `O(streams × partitions × nodes)` memory blow-up that gates
stream creation at ~12 streams/node and is a cluster-wide OOM vector. This spec makes ring
materialization **placement-aware**: only nodes in a partition's HRW replica set hold its ring;
non-replica nodes keep metadata only and forward; a replica-set reshuffle materializes/releases rings
with history preserved; and a derived partition-count cap is enforced at stream-creation time.

### 1.2 The problem (verified — see §2 for anchors)

- **Eager all-node hydration.** `onStreamConfigPut` fires on every node from the replicated
  `StreamConfigKey` commit and builds rings for all partitions, with **zero placement consultation**.
  Per-partition floor ≈ **2.66 MB**; against the **128 MB** per-node budget that caps a node at ~48
  default-partition partitions / ~12 default streams, **regardless of where replicas actually live**.
- **Unconditional over-subscription.** The follower hydration path adds the floor **unconditionally**
  (WARN + event only, no rejection) to satisfy a "must-not-diverge" accounting invariant — so the
  budget does not actually bound follower memory.
- **No partition-count cap.** `StreamConfig.partitions` is an unvalidated `int`; one committed
  huge-partition config forces unconditional native allocation on every node.
- **Broken backfill (#261).** History is never copied to a freshly-assigned replica, and any
  live-replication ack falsely promotes it to `CAUGHT_UP` — so "caught up" is a fiction and owner death
  after a reshuffle loses history. Because placement-aware materialization *depends on* correct
  backfill (a newly-materialized replica must recover history before serving), **#261's fix is folded
  into this spec.**

### 1.3 Goals

- **G-1 — Placement-gated materialization.** A node materializes a partition's ring **iff** it is in
  that partition's HRW replica set (owner + replicas). Non-replicas hold metadata only.
- **G-2 — Budget bounds real usage.** After G-1, the 128 MB budget bounds *actual* per-node ring
  memory, because each partition is materialized on exactly `RF` nodes, not `N`.
- **G-3 — Correct reshuffle.** A replica-set change materializes rings on newly-assigned nodes
  (backfilling history to caught-up **before** they serve) and releases rings on de-assigned nodes,
  **never dropping a partition below `RF` live, caught-up holders** mid-move.
- **G-4 — History-correct backfill (#261).** Backfill fires on *becoming* a replica (no-op only if the
  best source is genuinely empty); `CAUGHT_UP` is reached only after backfill confirms coverage from
  the partition's earliest retained offset.
- **G-5 — Derived partition cap, create-time.** A partition-count cap derived from the RAM budget is
  enforced **before commit** at stream creation; followers alarm (never silently diverge) on a
  committed config that exceeds bounds.
- **G-6 — Reuse existing transport.** Non-replica read/write reuses the existing owner-forwarding
  paths; no net-new transport.

### 1.4 Non-Goals

- **#241 community-scoped placement.** This spec uses the existing HRW replica set as the placement
  function; community/source-aware placement is a later refinement (§9) that swaps the placement
  function, not this lifecycle.
- **Client-side redirect protocol.** Non-replicas *forward* (reuse existing paths). A Kafka-style
  redirect-on-stale optimization is noted as future work (§8), not built here.
- **Changing the HRW algorithm or the replication factor model** — both exist and are reused as-is.
- **Re-partitioning (changing partition count of a live stream).** Out of scope; partitions remain
  immutable post-create (per `in-memory-streams-spec.md` §16.3).

### 1.5 Design principles

- **Converge, then place.** Placement is a deterministic function of converged membership; correctness
  rests on membership convergence, not on every node holding everything.
- **Materialize-before-release, gated on catch-up** (Kafka ISR-gated reassignment): never drop below
  `RF` caught-up holders during a reshuffle.
- **Derive limits from the budget**, don't hard-code them.
- **Reuse the wired seams** (HRW placement, owner-forwarding, the `onBecameReplica` hook) — this is a
  gating + lifecycle change, not new infrastructure.

---

## 2. Current State (verified)

Verified against source on the release tip. **Ticket line numbers were stale; corrected below.**

### 2.1 What EXISTS and is reused (the good news)

| Capability | State | Anchor |
|---|---|---|
| HRW replica-set computation | **wired** — owner = rank 0, replicas = rest, else NONE | `ReplicaSetController.reconcile():208-235`, `ReplicaPlacement.place():237-248`, `roleFrom():306-315`, `roleFor/ownerFor/isOwner:281-334` |
| Reshuffle trigger | **wired** — every `MembershipDecision` → `reconcile`; PASSIVE→ACTIVE edge too | `:184-189`, `:193-202` |
| `onBecameReplica` hook | **wired** to `PartitionBackfill.backfill` | `ReplicaSetController.java:265-271`; `AetherNode.java:2636` |
| Read forwarding to owner | **wired**, falls back to owner when local buffer absent | `ForwardingReadRouter`; `AetherNode.java:2704` |
| Write forwarding to owner | **wired** — `publishRemote` when `partitionBuffer(...).isEmpty()` | `DefaultStreamPublisher.publishRemote`; `AetherNode.java:2735` |
| Create-path budget rejection | **wired** — owner rejects with `STREAM_MEMORY_EXCEEDED` | `createFreshStream:232-249` → `reportFloorExhaustion:265-275` |

### 2.2 What is WRONG / MISSING (the work)

| Gap | State | Anchor |
|---|---|---|
| Eager all-node hydration | **every node materializes every partition** — no placement check | `onStreamConfigPut:443-450` → `hydrateEntry:475-499` → `buildPartitions:852-872`; wired `AetherNode.java:2472-2474` |
| Per-partition floor | ≈ 2.66 MB (`64 + 24×100_000 + min(256KB, maxBytes)`) | `OffHeapRingBuffer.java:159-160,274-275,43`; `RetentionPolicy.java:19-20` |
| Budget not enforced on followers | floor added **unconditionally**, WARN+event only | `DEFAULT_MAX_TOTAL_BYTES:47`; over-subscribe `:478-486`; must-not-diverge rule `:465-474` |
| Placement not visible at hydration site | `StreamPartitionManager` (built `:2460`) holds no replica-set ref; `ReplicaSetController` built later (`:2630`) | `AetherNode.java:2452-2474`, `:2630-2650` |
| Reshuffle doesn't touch buffers | `onBecameReplica`/unregister update the registry only, not rings | `ReplicaSetController.java:250-275` |
| ~~Backfill never fires for fresh replica (#261)~~ **DONE** | now fires unconditionally on becoming a replica (owner-first with probed-survivor fallback, §14.3); #261 closed | `ReplicaSetController.reconcilePartition:337-347`; `PartitionBackfill.probeThenPromoteOwner:665` |
| ~~False CAUGHT_UP (#261)~~ **DONE** | now coverage-gated — `CAUGHT_UP` only after coverage from the earliest retained offset; #261 closed | `ReplicaRegistry.updateWatermark:116-122` |
| No partition-count cap | `partitions` unvalidated; parser has no bound | `StreamConfig.java:15,23`; `StreamConfigParser.parseStreamSection:285-304` |

### 2.3 The two hard seams

1. **Placement injection + construction-order inversion.** `hydrateEntry` cannot consult placement
   today. Fix: inject a placement supplier (a `roleFor(stream, partition) → Role` view over
   `ReplicaRegistry`/`ReplicaSetController`) into `StreamPartitionManager`, resolving the build-order
   inversion with the **same `AtomicReference` seam already used** at `AetherNode.java:2452`
   (`streamPartitionManagerRef`).
2. **The must-not-diverge invariant must be re-derived** (§6): a non-replica holding *nothing* is
   correct, not divergence — but only because HRW placement is deterministic given converged membership.

---

## 3. Architecture Overview

### 3.1 The model

A partition's ring is materialized on a node **iff** the node is in that partition's HRW replica set.
Per partition there are exactly three roles (already computed): **OWNER** (HRW rank 0), **REPLICA**
(ranks 1..RF-1), **NON-REPLICA** (everyone else). Owner and replicas materialize the ring;
non-replicas hold catalog metadata only and forward.

```
StreamConfigKey commit ──(replicated to ALL nodes)──> onStreamConfigPut on each node
                                                          │
                                         roleFor(stream, partition)  ← placement supplier (NEW)
                                          ┌───────────────┴────────────────┐
                                   OWNER or REPLICA                     NON-REPLICA
                                   materialize ring                  metadata only;
                                   (+ backfill if REPLICA)           reads/writes forward to owner
```

### 3.2 Per-partition ring lifecycle on a node

```
ABSENT ──(self enters replica set)──> MATERIALIZING ──> BACKFILLING ──(coverage confirmed)──> SERVING
   ▲                                                                                              │
   └──────────────────── RELEASING <──(self leaves replica set, after a new replica is SERVING)──┘
```

- **OWNER** of a fresh partition (no history) goes `ABSENT → MATERIALIZING → SERVING` (no backfill).
- **REPLICA** goes through `BACKFILLING` and reaches `SERVING`/`CAUGHT_UP` **only after** backfill
  confirms coverage from the earliest retained offset (§5, fixes #261).
- A node only serves reads / acts as a backfill source for a partition while `SERVING`.

### 3.3 End-to-end flows

- **Config commit:** each node computes `roleFor` and materializes or skips (§4).
- **Membership change → reshuffle:** `reconcile` diffs desired vs current replica sets; newly-assigned
  nodes materialize+backfill, de-assigned nodes release **after** the replacement is caught up (§5).
- **Non-replica I/O:** reads/writes forward to the owner via the existing routers (§8).

---

## 4. Placement-Gated Hydration

> **Decision.** `hydrateEntry` consults a **placement supplier** and materializes a partition's ring
> **iff** `roleFor(stream, partition) ∈ {OWNER, REPLICA}`. Non-replica partitions are recorded in the
> catalog as metadata only (no `OffHeapRingBuffer` allocation).
>
> **Why.** This is the whole fix for the O(N) blow-up: each partition is materialized on exactly `RF`
> nodes instead of all `N`. The placement function (HRW) already exists and is deterministic across
> nodes given the same membership view, so each node independently reaches the same allocate/skip
> decision without coordination. The existing owner-forwarding paths (§8) already handle I/O for
> partitions a node doesn't hold, so a metadata-only entry is fully functional.
>
> **Rejected alternative.** *Keep all-node hydration and only shrink the per-ring footprint* — bounded
> by retention, not placement; still `O(streams × partitions × nodes)` and still OOMs at scale.
> *Governor-only materialization* (`in-memory-streams-spec.md` DD-1 Option A) — concentrates all
> partitions on the governor, recreating the blow-up on one node and coupling stream capacity to a
> single node's RAM.

**Placement supplier + construction order.** Introduce `PartitionRole roleFor(String stream, int
partition)` backed by `ReplicaRegistry`/`ReplicaSetController`, injected into `StreamPartitionManager`.
Resolve the build-order inversion (`StreamPartitionManager` at `AetherNode.java:2460` vs
`ReplicaSetController` at `:2630`) with the existing `AtomicReference` seam (`streamPartitionManagerRef`
pattern, `:2452`): the manager reads placement through a ref that is populated once the controller
exists. Until the ref is populated (early boot, pre-membership), see the bootstrap rule in §5.4.

---

## 5. The Reshuffle Lifecycle

*materialize / backfill / release — folds in the #261 backfill fix*

> **Decision.** On a replica-set change, follow the **Kafka ISR-gated sequence**: **materialize the
> ring on each newly-assigned node and backfill it to caught-up, and only then release the ring on
> de-assigned nodes** — never reducing a partition below `RF` live, caught-up holders mid-move.
>
> **Why.** This is the only sequence that preserves both availability and history during a reshuffle.
> Materialize-then-release (vs release-then-materialize) guarantees a partition is never momentarily
> held by fewer than `RF` caught-up nodes; gating release on the replacement reaching `SERVING`
> prevents the "owner dies after reshuffle → history lost" failure (#261). Pulsar's transfer-before-
> close and Kafka's "expand to OAR∪RAR, wait for ISR, then shrink" are the same idea.
>
> **Rejected alternative.** *Release-then-materialize* — a window with `< RF` holders; an owner death
> in that window loses data. *Materialize without a catch-up gate* — the new replica serves a partial
> ring as if complete (the current #261 bug). *Eager reshuffle of a node's entire partition set at
> once* — a thundering-herd of simultaneous backfills; instead reshuffle the per-partition replica
> *delta* (HRW's minimal-disruption property already bounds this to ~`K/N` partitions per membership
> change, KIP-429 cooperative style).

### 5.1 Materialize (newly-assigned replica)

1. `reconcile` detects `self` newly in a partition's replica set → register as **SYNCING** and
   allocate the ring (`MATERIALIZING`).
2. Fire `onBecameReplica(stream, partition)` **unconditionally** — *not* gated on local buffer
   non-emptiness. **(#261 fix — `StreamPartitionManager.java:461-464` trigger inverted today.)**
3. Backfill from the best `SERVING` source (owner preferred) starting at the partition's **earliest
   retained offset**, draining tiered storage as needed (`hierarchical-storage-spec.md §9` prefetch).

### 5.2 Catch-up gate (the #261 correctness fix)

> **Decision.** A replica transitions `SYNCING → CAUGHT_UP` **only after backfill confirms coverage
> from the earliest retained offset through the source's head**, *not* on the first live-replication
> ack.
>
> **Why.** Today any live ack flips `CAUGHT_UP` (`ReplicaRegistry.java:69-79`), so a replica holding
> only the post-join suffix becomes a read target and backfill source serving incomplete history
> (`PartitionBackfill.java:205-211`). Coverage-confirmed promotion is what makes `CAUGHT_UP` mean what
> it says and makes the replica a safe read/backfill source.
>
> **Status (HEAD): DONE (#261 closed).** Implemented — `CAUGHT_UP` is now coverage-gated via
> `ReplicaRegistry.updateWatermark:116-122`, and backfill fires unconditionally on becoming a replica
> with owner-first + probed-survivor fallback (`PartitionBackfill.probeThenPromoteOwner:665`; §14.3).
> The "Today any live ack flips `CAUGHT_UP`" text above is the pre-fix rationale, retained for context.
>
> **Rejected alternative.** *Promote on first ack* (status quo) — the #261 fiction. *Promote on
> offset-count parity* — fails with compaction/retention gaps; coverage-from-earliest-retained is the
> correct predicate.

A replica serves reads and acts as a backfill source **only while `SERVING`** (= `CAUGHT_UP` +
ring materialized). Live replication continues to append during and after backfill; backfill and live
tail are reconciled at the offset where backfill catches the source head.

### 5.3 Release (de-assigned node)

> **Decision.** A node releases a partition's ring (frees the `Arena`, returns the floor to the budget
> pool) **only after** the reconcile confirms the partition has `RF` other `SERVING` holders — i.e.
> the replacement replica has reached `CAUGHT_UP`.
>
> **Why.** Releasing before the replacement is caught up reduces caught-up holders below `RF`.
> Gating release on confirmed replacement coverage keeps the invariant. (Symmetric with §5.1.)
>
> **Rejected alternative.** *Release immediately on de-assignment* — the availability/history window
> again.

> **Status (increment 5, implemented).** `StreamPartitionManager.reconcileReshuffle` (scheduled every 5s)
> runs the release state machine: a materialized partition whose `roleFor` is NONE becomes a candidate,
> debounces (§5.4), then releases IFF (a) the local `ReplicaRegistry` view shows ≥ the **effective, clamped**
> RF (`ReplicaPlacement.replicationFactor`) OTHER replicas CAUGHT_UP — a cluster-shrink reshuffle uses the
> clamped RF so it never demands copies the shrunk cluster cannot host — AND (b) the **owner rule**: a node
> that lost the HRW OWNER role but is still named by the committed `StreamPartitionOwnershipValue` holds until
> the 1d-iii driver commits the ownership change elsewhere. Release frees the ring `Arena` + its budget
> reservation (which lets the same-tick queue drain admit a deferred partition); **WAL files stay on disk**
> (cheap re-hydration on flap-back + crash-recovery value — reaper cleanup is future work). The atomic
> `materialized`-map remove mirrors the `reapIfIdle` discipline (in-flight reads/appends finish against the
> resolved ring; a new access forwards).

### 5.4 Edge cases

- **Membership flapping.** Debounce release: a node de-assigned then re-assigned before its replacement
  is caught up keeps its ring (cancel a pending release). Reconcile is idempotent. *(Increment 5,
  implemented: a role-loss to NONE starts candidacy; release fires only after the partition survives
  `RELEASE_DEBOUNCE_TICKS = 2` reconcile ticks — ≈10s at the 5s reconcile cadence — as a candidate; a role
  regained within the window cancels candidacy at zero cost, no re-materialize.)*
- **Owner death mid-backfill.** The backfill source dies → re-target the next-best `SERVING` source;
  if none, the partition is degraded (surface a cluster event; see §11).
- **Pre-membership boot.** Before the placement ref is populated, a node must not eagerly materialize
  everything (the old behavior) nor wrongly skip. Rule: **defer hydration of a stream until placement
  is available**, then run the normal gated path. Config commits that arrive pre-membership are queued
  by the existing catalog and replayed once `roleFor` is live.
- **Zero-ref partitions** (`event-stream-namespaces-spec.md §8`): a partition with zero references is
  not materialized anywhere; refcount→0 triggers release on all holders.

---

## 6. Budget Accounting & the must-not-diverge reframe

> **Decision.** Remove the unconditional follower over-subscription. A non-replica reserves **nothing**.
> The per-node budget (`DEFAULT_MAX_TOTAL_BYTES`, 128 MB) bounds **real** usage: `≈ (materialized
> partitions on this node) × floor`, where materialized partitions = those for which the node is OWNER
> or REPLICA. Reserve/release stays symmetric because materialize (§5.1) reserves and release (§5.3)
> frees — there is no longer a "reserve-the-floor-but-don't-allocate" case.
>
> **Why.** The old invariant — *"a follower must not diverge from committed config, so add the floor
> unconditionally even past budget"* (`StreamPartitionManager.java:465-474`) — existed because every
> follower was forced to mirror every partition, and dropping a floor would desync the reserve/release
> accounting into a negative leak. Placement-gating removes the premise: a non-replica legitimately
> holds nothing, which is **not** divergence. The new correctness condition is weaker and already
> guaranteed elsewhere: **all nodes converge on the same HRW replica set given a converged membership
> view** (the membership FSM's job). So "must-not-diverge on memory" becomes "must-converge on
> membership, then placement is deterministic," and the budget can once again *reject* over-budget
> materialization on any node.
>
> **Rejected alternative.** *Keep unconditional over-subscription* — defeats G-2 (budget bounds
> nothing). *Make followers reject over-budget hydration but keep all-node hydration* — followers would
> diverge on a committed config (some reject, some over-subscribe), the exact problem the old invariant
> avoided; only placement-gating + create-time capping (§7) resolves it without divergence.

**Transient over-budget during reshuffle.** Materialize-before-release (§5) means a node briefly holds
both an old and a new ring's worth for migrating partitions. Budget headroom must tolerate this:
reserve the new ring against budget; if it would exceed, the reshuffle for that partition is **paced**
(serialized) rather than rejected — releasing some old ring first — never silently over-subscribing.

**System-stream budget exemption (owner decision 2026-07-05, amends this section for `system:*` only).**
The reject-when-over-budget rule above applies to **app streams only**. A `system:*` stream (cluster-events
and any future cluster-critical audit stream) **bypasses the budget reject**: it always materializes,
oversubscribing past the cap when necessary, and emits a distinct named `SYSTEM_OVERSUBSCRIBE` event
(separate from the app-stream `CREATE_FLOOR` deferral) so the oversubscription is operator-visible. In the
reshuffle materialization queue, system streams are ordered **first**. *Rationale:* cluster-critical streams
must not defer behind app-stream budget pressure, and their footprint is bounded — few streams, full-cluster
placement is deliberate (§3, `ReplicaPlacement.systemReplicationFactor`). This restores, scoped to system
streams, the old unconditional over-subscribe behavior §6 removed for app streams; app streams still defer.

---

## 7. Partition-Count Cap (derived, create-time)

> **Decision.** Derive a per-stream partition cap from the RAM budget and enforce it **at stream
> creation, before the config is committed**. Followers **alarm but do not reject** a committed config
> that exceeds bounds.
>
> **Why.** The binding constraint for in-memory rings is RAM, not file handles, so a fixed number is
> wrong across node sizes; derive it:
>
> ```
> max_partitions_per_stream  ≈  (per_node_ring_budget × node_count)  /  (RF × ring_footprint)
> ```
>
> Enforcement must be **pre-commit**: once a `StreamConfigKey` is committed, *rejecting* it on a
> follower would diverge from the committed cluster state (the §6 hazard). So the create-time gate
> (parser + `createFreshStream`, before commit) is the real control; the follower-side check is
> defense-in-depth that **emits a cluster event** and refuses to *materialize* beyond a hard ceiling,
> but never silently drops committed config. This mirrors Pulsar's `maxNumPartitionsPerPartitionedTopic`
> server-side create guard.
>
> **Rejected alternative.** *Fixed default cap* (e.g. 64) — wrong on both small and large nodes;
> offered only as an absolute upper guard (the Kafka `100 × nodes × RF` latency heuristic) layered over
> the derived cap. *Follower-side hard rejection of committed config* — divergence. *No cap* (status
> quo) — one config OOMs the cluster.

**Enforcement points:** `StreamConfigParser.parseStreamSection` (`:285-304`) validates against the
derived cap at parse/create; `createFreshStream` (`StreamPartitionManager.java:232`) re-checks
pre-commit; `hydrateEntry` (`:475`) enforces only the absolute hard ceiling with a cluster event.
Surface the computed cap via a management/CLI read so operators see the limit (REST→CLI→Docs triad,
CLAUDE.md invariant #1).

---

## 8. Non-Replica Read/Write Path

> **Decision.** Non-replica nodes serve reads/writes by **forwarding to a `SERVING` replica (owner
> preferred)** using the **existing** routers — `ForwardingReadRouter` (read) and
> `DefaultStreamPublisher.publishRemote` (write) — which already fall back to owner-forward when the
> local buffer is absent.
>
> **Why.** The expensive transport already exists and is wired (`AetherNode.java:2704`, `:2735`); a
> metadata-only node is exactly the "local buffer absent" case those paths already handle. No new wire
> protocol is needed — this is the single biggest reason #265 is a gating change, not an
> infrastructure project.
>
> **Rejected alternative.** *Client-side redirect (Kafka `NotLeaderForPartition`)* — avoids a forward
> hop but requires a client metadata-refresh protocol and changes the slice-facing API; deferred as a
> future optimization (cache the replica set client-side, redirect-on-stale). *Stateless proxy tier
> (Pulsar proxy)* — unneeded; nodes already inter-connect. **Security note:** validate forward targets
> against the known member set (cf. Pulsar proxy CVE-2022-24280) — reuse the membership roster, never
> a caller-supplied target.

---

## 9. Reconciliation with Existing Specs

- **`stream-offheap-budget-spec.md` (combined admission story).** That spec owns the 128 MB constant,
  the eager-hydrate OOM vector (§3.2/§5.2), and `tryReserve`/floor accounting. This spec is the
  **placement axis** of the same admission problem: it reduces *who* holds a partition; the budget spec
  bounds *how much* each holder costs. §6 here **supersedes** the budget spec's unconditional
  over-subscribe rule. Cross-reference both; they form one admission model.
- **`in-memory-streams-spec.md` DD-1.** DD-1 enumerates ring-location options A (governor-only,
  "recommended Phase 1"), B (governor-primary + worker replicas), C (worker-sharded) — none of which is
  "metadata-only non-replica + forward." This spec **adds a fourth, placement-scoped option and adopts
  it**; update DD-1 to point here. Note DD-1 uses *consistent-hash* terminology; the implementation
  uses **HRW/rendezvous** (`ReplicaPlacement`) — align the spec's wording to the code.
- **`streaming-spec.md` §7 (ownership).** §7 is single-governor-owner (`PartitionOwnershipTable`). No
  spec ever endorsed "all partitions on every node" — that is a runtime artifact of `hydrateEntry`.
  This spec makes the materialization set = the HRW replica set; reconcile §7's ownership table with
  `ReplicaSetController`'s owner=rank-0 model.
- **`hierarchical-storage-spec.md` §9.** Cross-node prefetch / `HOT_ONLY` is the **warmup ally** for
  §5.1 materialize (drain tiered storage into the new replica's ring). Note: the `128MB` `memory.max-bytes`
  in §12.2 is a **storage-tier** budget, a *different* number from the stream-ring budget — do not
  conflate.
- **`event-stream-namespaces-spec.md` §8.** Refcount lifecycle: a zero-ref partition is materialized
  **nowhere**; placement keys are addressed by the 3-part `namespace:stream:version` (§4).
- **`cluster-topology-overhaul-spec.md` A8.** The replica-set computation must filter members by
  **role-correct counting** (`coreCountedMembers`, CORE/WORKER/SPOT in `MemberDescriptor`).
- **#241 (`worker-membership-spec.md`).** Community/source-aware placement and DHT re-replication on
  death are the structural analog of this reshuffle. **Composition:** #241 later swaps the *placement
  function* (which nodes are eligible / community-scoped) while this spec's materialize/backfill/release
  *lifecycle* is unchanged — a clean seam, not a rewrite.
- **#261.** Folded in here (§5.2). The standalone #261 fix becomes this spec's catch-up-gate section;
  close #261 against this spec or keep it as the unit-test tracker for the gate.

---

## 10. Configuration Model

```toml
[streams]
ring_budget_bytes      = "128MiB"   # per-node materialized-ring budget (DEFAULT_MAX_TOTAL_BYTES)
# NOTE (v0.2): RF is NOT a global config key. Each stream declares its own `replicas`
# (two-knob model, #262/#410, StreamConfig field); the cap formula (§7) and budget
# accounting (§6) use the PER-STREAM `replicas` at create-time validation.

[streams.limits]
# derived cap is computed at runtime; these are the ABSOLUTE upper guards (Kafka-style)
max_partitions_per_stream_ceiling = 1024
max_partitions_cluster_total      = "100x-nodes-x-maxrf"   # derived: 100 × N × max declared replicas; guards aggregate ring memory
```

Per-stream `partitions` stays in the stream section; create-time validation (§7) rejects configs whose
`partitions` exceed `min(derived_cap, ceiling)` **before commit** with a clear error. The derived cap
is exposed read-only via the management API + CLI.

---

## 11. Error Model

JBCT `Cause` taxonomy; parse-don't-validate at the create boundary.

| Surface | `Cause` variants |
|---|---|
| Create (pre-commit) | `PartitionCapExceeded(requested, derivedCap, ceiling)`, `StreamMemoryExceeded` (existing) |
| Materialize / reshuffle | `BackfillSourceUnavailable(stream, partition)`, `ReshufflePacedByBudget`, `MaterializeBudgetExceeded` |
| Catch-up | `CoverageIncomplete(fromOffset, head)` (internal; blocks `CAUGHT_UP`) |
| Follower defense-in-depth | `CommittedConfigOverCeiling` → **cluster event**, never a silent drop |

Backpressure/degradation events (`BackfillSourceUnavailable`, `CommittedConfigOverCeiling`,
`ReshufflePacedByBudget`) are operator-visible via the existing exhaustion/event sink
(`StreamPartitionManager` `exhaustionSink`) — observability-first per CLAUDE.md.

---

## 12. Implementation Plan

Risk-first; foundational seams before the lifecycle.

| Phase | Scope | Anchors |
|---|---|---|
| 0 — placement seam | inject `roleFor` supplier into `StreamPartitionManager`; resolve build-order via the `AtomicReference` seam | `AetherNode.java:2452,2460,2630` |
| 1 — gate hydration | `hydrateEntry` materializes iff OWNER/REPLICA; non-replica = metadata-only catalog entry | `StreamPartitionManager.java:475-499` |
| 2 — budget reframe | remove unconditional over-subscribe; budget rejects over-budget materialize; reshuffle pacing | `:465-486` |
| 3 — backfill fix (#261) | unconditional `onBecameReplica` trigger; coverage-gated `CAUGHT_UP` | `:461-464`; `ReplicaRegistry.java:69-79`; `PartitionBackfill.java:205-211` |
| 4 — reshuffle lifecycle | **DONE (increment 5)** — materialize-before-release with catch-up + owner gate; flap debounce (2 ticks); release frees ring `Arena` + budget (WAL kept); `reshuffle_concurrency = 2` slot pacing; `system:*` budget exemption + system-first drain | `StreamPartitionManager.reconcileReshuffle`; `ReplicaSetController.java:250-275` |
| 5 — partition cap | derived cap + create-time enforcement + follower ceiling event; management/CLI read | `StreamConfigParser.java:285-304`; `StreamPartitionManager.java:232` |
| 6 — verification | memory test: 100 streams × default partitions within budget on a 5-node cluster; reshuffle history-preservation test (owner kill → replica serves complete history) | `aether/tests/integration` |

**Acceptance (from #265 + #261):** (a) non-replica nodes allocate no ring; HRW reshuffle
materialize/release with history preserved; (b) partition cap enforced at create, follower ceiling
emits a cluster event; (c) memory test passes; (d) backfill fires on becoming a replica and
`CAUGHT_UP` follows coverage from earliest retained offset; owner kill after reshuffle → replica
serves complete history.

---

## 13. Reconciliation to Existing Code

| Capability | Current | Target | Tag | Anchor |
|---|---|---|---|---|
| Hydration placement-gating | every node materializes every partition | materialize iff OWNER/REPLICA | **MISSING** | `StreamPartitionManager.java:443-450,475-499,852-872` |
| Placement at hydration site | not injected; build-order inverted | `roleFor` supplier via `AtomicReference` seam | **MISSING** | `AetherNode.java:2452,2460,2630` |
| Budget enforcement (follower) | unconditional over-subscribe, WARN-only | reject over-budget materialize; pace reshuffle | **WRONG** | `StreamPartitionManager.java:465-486` |
| HRW replica set + reshuffle trigger | wired | reuse as the placement function | **DONE (reuse)** | `ReplicaSetController.java:208-275` |
| `onBecameReplica` → backfill | fires unconditionally on becoming replica; owner-first + probed-survivor fallback | as targeted | **DONE (#261, §14.3)** | `ReplicaSetController.reconcilePartition:337-347`; `PartitionBackfill.probeThenPromoteOwner:665` |
| `CAUGHT_UP` promotion | coverage-from-earliest-retained gate | as targeted | **DONE (#261)** | `ReplicaRegistry.updateWatermark:116-122` |
| Reshuffle ring materialize/release | registry only; buffers untouched | add/drop rings, catch-up-gated | **DONE (increment 5)** | `StreamPartitionManager.reconcileReshuffle`, `buildAndInstall` |
| Read/write forwarding | wired, owner-fallback on absent buffer | reuse for non-replicas | **DONE (reuse)** | `AetherNode.java:2704,2735` |
| Partition cap | none | derived + create-time + follower ceiling | **MISSING** | `StreamConfig.java:15`; `StreamConfigParser.java:285-304` |
| Create-path budget rejection | rejects with `STREAM_MEMORY_EXCEEDED` | keep; extend to cap | **DONE** | `createFreshStream:232-249` |

---

## 14. Open Questions

**All five resolved (v0.2, 2026-07-04)** — three by the #262/#410 two-knob + failover-convergence work
that landed after this spec was drafted, two by default-setting (config-knobbed, tunable):

1. **RF source. RESOLVED: per-stream.** The two-knob model (#262/#410) made `replicas` a first-class
   per-stream `StreamConfig` field (default 1; durable pub-sub topics constrain it per
   `durable-pubsub-spec.md` §3). The cap formula (§7) and budget accounting (§6) use each stream's
   declared `replicas` at create-time validation. The global `[streams] replication_factor` key is
   removed from §10 — a cluster default, if ever wanted, is a default *for the per-stream field*,
   not a parallel source of truth.
2. **Reshuffle pacing. RESOLVED: bounded concurrency window, default 2 partitions per node,**
   config `[streams] reshuffle_concurrency = 2`. One-at-a-time starves large reshuffles; unbounded
   floods backfill. A small window is the standard middle; tune against backfill throughput once
   §12's implementation exposes the reshuffle-lag metric. *(Increment 5, implemented:
   `RESHUFFLE_CONCURRENCY = 2` slots gate REPLICA materialize+backfill at the `buildAndInstall` seam —
   an OWNER ring, which has no backfill, is never paced. Excess queues **system-first, then FIFO**; a
   queued app partition proceeds only when a slot AND budget headroom both exist (budget-AND; system
   streams: slot only). A completed backfill (self CAUGHT_UP) or a release frees a slot; `materializeQueueDepth`
   is surfaced in the hydration snapshot.)*
3. **Backfill source selection. RESOLVED: owner-first with probed-survivor fallback — the mechanism
   that now exists.** Post-#410, `PartitionBackfill` pulls from the owner (`backfillFromOwner`), and
   an owner with a blind local registry probes peers' real tails and catches up from the best
   reachable survivor (`probeThenPromoteOwner`; epoch-adopting recovery appends). Hydration reuses
   exactly this path — no new selection logic. Nearest/least-loaded selection is deferred to #241
   zone-awareness, as a pure optimization behind the same seam.
4. **Absolute ceiling default. RESOLVED: 1024 per stream, PLUS a cluster-wide total-partitions
   guard** (`100 × nodes × max declared replicas`, §10) — the per-stream ceiling alone doesn't
   bound aggregate ring memory across many streams; the cluster guard does (Kafka-style).
5. **Pre-membership config replay. RESOLVED: no replay buffer needed — consensus re-apply is the
   replay.** Committed `StreamConfigKey` entries re-apply from the log/snapshot on boot, and each
   apply fires the Put handlers (catalog hydration + the reconcile edge added by #410). Reconcile
   is suppressed while quorum-PASSIVE and re-fired on the PASSIVE→ACTIVE edge, so placement
   catches up regardless of arrival order. Verified against the #410 wiring (AetherNode
   `onStreamConfigPut` + reconcile-edge registration order).

---

## 15. References

- **Lazy materialization / load-shedding:** Pulsar load balancing — https://pulsar.apache.org/docs/next/administration-load-balance/ ·
  extensible load balancer (bundle state machine, transfer-before-close) — https://streamnative.io/blog/extensible-load-balancer-pulsar-3-0
- **Reassignment without availability loss:** Kafka detailed replication design — https://cwiki.apache.org/confluence/display/kafka/kafka+detailed+replication+design+v3 ·
  KIP-435 (reassignment batching, never below `min.insync`) — https://cwiki.apache.org/confluence/display/KAFKA/KIP-435:+Internal+Partition+Reassignment+Batching ·
  KIP-429 (cooperative/incremental rebalance) — https://cwiki.apache.org/confluence/display/KAFKA/KIP-429:+Kafka+Consumer+Incremental+Rebalance+Protocol
- **Rendezvous (HRW) hashing:** https://en.wikipedia.org/wiki/Rendezvous_hashing · weighted HRW — https://datatracker.ietf.org/doc/html/draft-mohanty-bess-weighted-hrw-02
- **Forward vs redirect:** Kafka protocol / `NotLeaderForPartition` — https://kafka.apache.org/42/design/protocol/ ·
  Pulsar proxy (PIP-1) — https://github.com/apache/pulsar/wiki/PIP-1:-Pulsar-Proxy
- **Partition limits:** Confluent — how to choose partitions — https://www.confluent.io/blog/how-choose-number-topics-partitions-kafka-cluster/ ·
  KIP-578 (partition-count limits) — https://cwiki.apache.org/confluence/display/KAFKA/KIP-578:+Add+configuration+to+limit+number+of+partitions ·
  KRaft max partitions — https://www.instaclustr.com/blog/apache-kafka-kraft-abandons-the-zookeeper-part-3-maximum-partitions-and-conclusions/ ·
  Pulsar `maxNumPartitionsPerPartitionedTopic` — https://github.com/apache/pulsar/issues/6793

---

*Companion to issue #265. Folds in #261 (§5.2 catch-up gate). Amends `stream-offheap-budget-spec.md`
(§6), `in-memory-streams-spec.md` DD-1 (§9), `streaming-spec.md` §7 (§9).*
