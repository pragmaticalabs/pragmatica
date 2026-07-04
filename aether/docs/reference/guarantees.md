# Aether — Guarantees (Authoritative)

> **This is the single source of truth for what Aether actually guarantees, per operation.**
> Where any other document (feature-catalog, READMEs, javadoc, specs) states a guarantee that conflicts with this one, **this document wins** — and the other should be corrected (see the companion [`guarantees-corrections-needed.md`](./guarantees-corrections-needed.md)).
>
> **Method:** the *consistency lens* — every guarantee is named with a precise model and traced to a mechanism in source (`file:line`). A claim with no mechanism behind it is marked **unearned**. No CP/AP labels; no unqualified "strongly consistent / highly available / exactly-once / durable".
> **Grounding:** code-read at branch `release-1.0.0-rc2`, HEAD `e320881f0`, 2026-06-29. Durable-entity is **PLANNED** and status-tagged against what is actually wired today.

---

## How to read this

**Status tags**

| Tag | Meaning |
|-----|---------|
| **LIVE** | Wired in the node bootstrap and earned by a mechanism in code. |
| **PARTIAL** | Works, but with a named limitation or gap. |
| **PLANNED** | Spec/intent exists; mechanism **not wired** into the live path. Not a current guarantee. |
| **⚠ DEFECT** | A shipped claim that the code does **not** deliver (bug / unsatisfiable / wrong docstring). |

**Consistency vocabulary** (strongest → weakest): *linearizable* (real-time, single up-to-date copy) · *sequential* (global order, not real-time) · *causal + session* (read-your-writes, monotonic reads/writes) · *snapshot isolation* · *read-committed / bounded-staleness / eventual*. ACID-"C" is unrelated to linearizability.
**Durability:** *process-durable* (survives process restart; in-mem replicated) · *crash-durable* (fsync-before-ack) · *quorum-durable* (committed on N replicas).
**Delivery:** *at-most-once* · *at-least-once* · *effectively-once* (at-least-once + dedup/idempotency key). True exactly-once over a network does not exist.

**One-line orientation:** Aether is **CP on the write/metadata path** (leaderless Rabia consensus; the minority of a partition self-fences) with **eventual, local reads**, a **separate eventual DHT plane** for routing/endpoint state, **crash-durable single-owner streams** (default RF=1), and **best-effort at-most-once** pub/sub. Durable-entity's fenced/durable guarantees are **planned, not yet wired**.

---

## Summary matrix

| # | Subsystem | Operation | Consistency | Durability | Delivery | Availability under partition | Status |
|---|-----------|-----------|-------------|------------|----------|------------------------------|--------|
| 1 | KV (Store-A) | `consensus.commit` | sequential (total order + agreement) | quorum-durable, in-mem | — | majority side only | LIVE |
| 2 | KV (Store-A) | `kv.write` (Put) | linearizable **write order** | quorum-durable in-mem; **snapshot-only** persistence | — | majority serves; minority pauses + self-halts | LIVE |
| 3 | KV (Store-A) | `kv.read` | **NOT linearizable** — sequential, local/stale | — | — | both sides serve (stale) | LIVE *(as local/eventual)* |
| 4 | KV (Store-A) | `kv.delete` (Remove) | total-ordered, **unfenced** | quorum-durable in-mem | — | majority only | ⚠ DEFECT (fence gap) |
| 5 | KV (Store-A) | `epoch.fence` (Put) | monotonic single-writer (reject stale epoch/leader) | deterministic, reconstructible from KV | — | enforced when writes resume | LIVE |
| 6 | DHT (Store-B) | `dht.write` (system maps) | **eventual**, W=1 single-node ack | **not crash-durable** (in-mem) | — | side with ≥W replicas (≈local) | LIVE *(eventual)* |
| 7 | DHT (Store-B) | `dht.read` (system maps) | **eventual**, first-non-empty (no read-repair) | — | — | any live replica | LIVE *(eventual)* |
| 8 | DHT (Store-B) | `dht.write` (artifact repo) | quorum **LWW by HLC** (W=R=majority) | quorum in-mem | — | majority | LIVE *(eventual/LWW)* |
| 9 | DHT (Store-B) | `dht.epoch-gate` | per-partition single-writer fence | — | — | — | LIVE |
| 10 | Availability | `cluster.accept-writes` | — | — | — | **majority only**; minority `QuorumPaused` | LIVE |
| 11 | Availability | `minority.self-fence` | — | — | — | minority **halts** ~15 s after quorum loss (**C-over-A**) | LIVE |
| 12 | Availability | `leader.election` | exactly-one (viewSequence-fenced) | — | — | transient zero-leader self-heals (≈19 s worst) | LIVE |
| 13 | Availability | `node.failure-detect` | — | — | — | ~11 s nominal (×8 ≈80 s under sustained load) | LIVE |
| 14 | Streams | `stream.append` | per-partition **total order** | **crash-durable** (WAL fsync-before-ack), **RF=1** | — | HRW owner side | LIVE |
| 15 | Streams | `stream.append` (sync-replicated) | quorum-durable *intended* | — | — | — | ✅ RESOLVED #262 two-knob (RF = `replicas` knob; barrier awaits `min-sync-replicas − 1` distinct peer acks; supersedes the interim #378 `RF = minSyncReplicas+1` derivation) |
| 16 | Streams | `stream.read` (GOVERNOR, default) | eventual / local | — | — | local node | LIVE |
| 17 | Streams | `stream.read` (NEAREST, app default) | read-your-writes via owner-on-empty; else eventual | — | — | local, forward-to-owner on empty | LIVE |
| 18 | Streams | `stream.read` (LINEARIZABLE) | linearizable (fenced owner + catch-up) | — | — | owner | PARTIAL (degrades to ANY_REPLICA if owner-source unwired) |
| 19 | Streams | `stream.consume` | per-partition order | cursor RAM→periodic checkpoint | **at-least-once** | owner side; **RF=1: empty after failover** | LIVE |
| 20 | Streams | `cursor.commit` (PG-tx) | — | — | **effectively-once** (dedup `(group,stream,partition)`) | — | PLANNED (not wired in bootstrap) |
| 21 | Streams | `publish` (STRONG / consensus) | total order across nodes | — | — | — | PLANNED (unwired) |
| 22 | Pub-sub | `topic.publish` / `deliver` | **unordered** | **none** (never persisted) | **at-most-once** (no retry) | best-effort, no consensus on hot path | LIVE |
| 23 | Pub-sub | `subscription.register` | — | **crash-durable** (Rabia KV) | — | majority for the registration write | LIVE |
| 24 | Pub-sub | `config.notifyInitial` | — | process-local | fire-once on activate | — | LIVE |
| 25 | Pub-sub | `config.notifyChange` (runtime push) | — | — | — | — | ⚠ DEFECT (machinery present, **no caller**) |
| 26 | Pub-sub | `pg.notify` (LISTEN/NOTIFY) | per-channel FIFO while connected | none | **at-most-once** (no replay) | — | LIVE |
| 27 | Pub-sub | `@Notify` (email/HTTP) | — | — | **at-least-once** (retries, may dup) | — | LIVE *(separate outbound surface)* |
| 28 | Durable-entity | `entity.create/update/delete` (wired) | per-key serial **(single-JVM only)**; no fence, no owner-route | **none** (in-process map) | — | per-node divergent (no owner concept) | PARTIAL — CRUD only; **not reachable from a deployed slice** |
| 29 | Durable-entity | `entity.get` (wired) | local map read (**NOT** linearizable) | none | — | divergent across nodes | ⚠ DEFECT (javadoc says "Linearizable get") |
| 30 | Durable-entity | `entity.get` / `update` (fenced+durable) | linearizable owner-routed / epoch-fenced single-writer | restart-durable | — | owner | PLANNED (variants unwired; gated on owner-routing 1e + persistence #349) |
| 31 | Durable-entity | `entity.timer`, `workflow.*`, `saga.*` | — | — | run-once / effectively-once | — | PLANNED (timer declined today; workflow/saga **zero code**) |

---

## 1. Cluster — write/metadata plane (Rabia KV, "Store-A")

Source of truth for leader, blueprint, target, generation, governor, and ownership atoms. `KVStore` is a `StateMachine<KVCommand>` driven by leaderless Rabia.

- **`kv.write` (Put)** — *linearizable write order*. Every Put goes through the single total Rabia log and is applied deterministically on every replica; the ack returns only after quorum-commit **and** local apply (`KVStore.handlePut` `KVStore.java:76`; `RabiaEngine` apply `:1536-1538`). Durability is **quorum-replicated in memory** — **not** fsync-durable. Persistence is **snapshot-only** at lifecycle events via `GitBackedPersistence` (default in-memory), so a simultaneous **full-cluster crash loses everything since the last snapshot**.
- **`kv.read`** — **not linearizable.** Reads are served from the local applied `ConcurrentHashMap` (`KVStore.get` `:240`), which can trail the committed frontier (`isPendingCatchUp`). This is the ZooKeeper-default-read shape: fast, sequential per node, possibly stale, with **no `sync()` / no linearizable read path** in production.
- **`epoch.fence`** — the #345 correctness fence. The applier rejects a `Put` carrying a strictly-older owner epoch or a stale leader token (`staleEpochWrite` `:116`, `staleLeaderWrite` `:101`), identically on every replica → genuine **monotonic single-writer per ownership key**. The rejection is **silent** (returns the stored value, no notification).
- **`kv.delete` (Remove)** — ⚠ total-ordered but **NOT fenced** (`handleRemove` `:131` has no `staleWrite` guard). A deposed owner's Remove **is applied**. See Known Gaps.
- **`kv.cas`** — there is **no general compare-and-set**; the only conditional write is the epoch fence above.

**Failure behavior:** writes serve on the **majority side only**; the minority returns `QuorumPaused` and the node self-halts (see §3). Reads keep serving **stale local state on both sides** of a partition. Fence decisions are deterministic and survive handover (the token rides the committed value).

---

## 2. Cluster — eventual plane (DHT `ReplicatedMap`, "Store-B")

Holds slice-node state, HTTP routes, and RPC endpoints — **migrated off consensus** onto a consistent-hash `ReplicatedMap` (catalog rows 94/95/152/281) for O(3)-vs-O(N) scaling. The migration was a real **guarantee downgrade** that the catalog frames only as a perf win.

- **`dht.write` (aether system maps)** — **eventual, single-node ack.** `DHTConfig.FULL` is **W=1, R=1** (`DHTConfig.java:111`): a write resolves after one local in-memory put, with best-effort async replication. **Ack-then-crash before replication loses the write.** Storage is in-memory (`MemoryStorageEngine`) → **restart loses everything**.
- **`dht.read` (aether system maps)** — **eventual, first-non-empty wins.** `QuorumCollector.selectBest` returns the first non-empty response, **not** the max-version, so reads can be stale even when R+W>N.
- **`dht.write` (artifact repository)** — stronger: **quorum LWW by HLC version** (W=R=majority, `Main.java:323`). Still eventual (HLC is a logical clock; concurrent writes are LWW-dropped), not linearizable.
- **`dht.epoch-gate`** — `PartitionOwnerEpochGate` `:52` enforces per-(keyspace,partition) single-writer at the replica using a CP-seeded monotonic high-water (covers entity-keyed puts).

> ✅ **Fixed #380 (`0f34a084c`).** `DHTConfig` previously claimed "Full replication is always strongly consistent. R + W > N ensures any read will see the most recent write." `FULL` is W=R=1 (R+W=2 ≤ N) and the read path never reconciles versions, so that was false. The `FULL` docstring now states *eventually consistent, not linearizable*, and the dead, misnamed `isStronglyConsistent()` (zero callers) was renamed `hasQuorumOverlap()` with an honest "necessary-but-not-sufficient" contract. Adding real read-repair/reconciliation remains future work (not this fix).

---

## 3. Cluster — availability & liveness

Aether prefers **consistency over availability** and makes it explicit per node.

- **Quorum** = simple majority of **core** members, `core/2 + 1` (`QuorumLossDetector.java:429`; workers excluded). Consensus commits only while a node sees quorum; on loss the engine pauses and **rejects writes** with `QuorumPaused` (`RabiaEngine.java:325,682`).
- **Minority self-fence (C-over-A)** — a node that has lost quorum self-terminates via `Runtime.halt(2)` ~15 s after loss (`DrainProcedure` + `QuorumLossDetector`), **gated** so it never fires before it was ever quorate (armed-latch), during the 75 s cold-boot convergence window (`COLD_BOOT_CONVERGENCE_WINDOW_MS`, the A6 fix), or when a false-FAULTY storm is co-confirmation-refuted. Recovery of a fenced node requires external restart / CTM reprovision.
- **Leader election** — exactly one leader per tenure; two committing leaders are **structurally impossible** (single-commit Rabia + viewSequence fence, `LeaderElectionState.java:498-535`). A transient zero-leader gap during re-election self-heals via the departure edge + a follower lease (≈19 s worst case).
- **Failure detection** — SWIM detects a dead node in **~11 s nominal** (probe 0.8 s + suspect 10 s), stretchable to **~80 s** under sustained local trouble (LHM ×8); full eviction adds a 15 s co-confirmation backstop.
- **Recovery** — quorum return re-emits `ACTIVE` and auto-resumes the majority; a periodic reconcile re-evaluation bounds recovery (closes the historical permanent-paused wedge).

> Note: the leaderless **consensus** has no SPOF, but **control-plane** operations (deploy, scale, auto-heal) are **leader-pinned** and briefly pause during re-election. "No single point of failure" needs that qualification.

---

## 4. Streams

A default app stream is **partitioned, per-partition totally ordered, and crash-durable on a single owner**.

- **`stream.append`** — the partition's HRW owner assigns a monotonic offset (`OffHeapRingBuffer` under lock) and **fsyncs to a per-partition WAL before acking** (`durablyLog`→`wal.append().await()` `StreamPartitionManager.java:684`; `PartitionWal` group-commit `force(false)`; WAL **on by default**). An acked event survives `kill -9` / OS crash + restart. **Default RF=1** ⇒ durability is **one-disk-deep**: fsync protects against crashes, **not disk loss, and not owner failover** (HRW can move ownership to a peer holding no copy → consumer reads **empty** until the original owner returns).
- **Reads** — **eventual / local-first by default.** GOVERNOR reads the local node only; NEAREST (the app default) forwards to the HRW owner **only on an empty local read** (a non-empty stale local read is *not* forwarded). True **linearizable** fenced-owner reads exist only under `ReadPreference.LINEARIZABLE`, and only when the committed-owner source is wired (else degrades to ANY_REPLICA).
- **`stream.consume`** — **at-least-once.** The cursor advances in RAM per event but checkpoints only every ~1000 events / 30 s (default cursor RAM-only), so a crash redelivers the un-checkpointed window. **Effectively-once** (dedup on `(group,stream,partition)` UPSERT inside the business transaction) exists only via `PgTransactionalCursorCommit`, which is **not wired into the node**.
- **Append epoch fence** — `rejectIfStale` rejects a deposed owner's late appends; the high-water advances by observing committed ownership. **LIVE** — the owner-change driver (1d-iii, `9842e1ea2`) auto-commits an ownership record with a monotonically advanced `ownershipTerm` on every membership-driven owner reseat (leader-gated, idempotent when the owner is unchanged), Forge-proven end-to-end (`StreamOwnershipDriverFenceTest`: driver auto-commit + deposed-but-alive owner's stale-epoch append rejected). Residual: the composed real-handover proof (kill → HRW reseat → epoch n→n+1 → fence) is a single-JVM limitation deferred to the Phase-1 cloud gate; ownership-write fan-out under mass reshuffle is un-batched until #265.

> ✅ **Synchronous replicated durability is now satisfiable (resolved by the #262 two-knob model, which supersedes the interim #378 `1f581e530` derivation).** The two knobs are now INDEPENDENT: `replicas` sets the replication factor — the APP path places `RF = clamp(replicas,1,N)` copies (owner + `replicas−1` peers) — while `min-sync-replicas` (which COUNTS the owner) sets the write-ack floor, so a synchronous publish awaits `min-sync-replicas − 1` DISTINCT NON-SELF acks. With `replicas ≥ min-sync-replicas` the required peers exist, so any `min-sync-replicas ≥ 1` publish is satisfiable; a too-small cluster clamps RF down and the await fails CLEARLY with `NOT_ENOUGH_REPLICAS`. This replaces the earlier `RF = clamp(minSyncReplicas+1,1,N)` derivation, which conflated the two knobs. **Still open:** the STRONG/consensus cross-node publish path is **unwired**, and "zero-copy" applies only to the **consumer** read, not the producer path. See Known Gaps.

---

## 5. Pub-sub & notifications

- **Topic `publish` / `deliver`** — **at-most-once, unordered, best-effort.** `publish()` resolves currently-registered subscribers and fires one RPC per subscriber slice (round-robin across that slice's live instances via the endpoint registry), **returning success even when nothing is delivered**. Messages are **never persisted or queued**; a subscriber down at publish time misses the message permanently, and a mid-flight failure is **not retried** (the path uses `invoke`, not `invokeWithRetry`). No dedup key anywhere.
- **`subscription.register`** — **crash-durable**: the `TopicSubscriptionKey` is a Rabia-replicated KV write, so subscriptions and endpoint membership survive node restart and leader change (the topology self-heals even though in-flight messages do not).
- **`config.notifyInitial`** — fires once, process-local, on activation. ⚠ **`config.notifyChange` (runtime push) has no caller** — runtime config changes do **not** push to slice callbacks via this path.
- **`pg.notify`** (PG LISTEN/NOTIFY) — **at-most-once**, per-channel FIFO while connected; **no replay** across a disconnect.
- **`@Notify`** (email/HTTP) — a **separate outbound surface**, not pub-sub: **at-least-once** with retries (may duplicate), no DLQ/persistence; the slice owns policy.

---

## 6. Durable-entity (PLANNED)

**The wired path today is `InMemoryDurableEntity` — a single-JVM `ConcurrentHashMap` with no fence, no replication, no durability, no owner-routing — and it is not a dependency of `aether/node`, so no deployed slice can inject it.** The fenced variants exist and are unit-tested but are **unwired**.

**What is actually earned today:** correct **per-key serialization within one JVM** (same-key total order, cross-key parallelism via `PerKeySerialExecutor`) and typed errors. Nothing more.

**What is PLANNED (spec §5–§7, not earned):**
- `entity.get` → "linearizable owner-routed read" — **gated on owner-routing (1e)**, unwired in every variant. ⚠ The shipped `DurableEntity.java:27,53` javadoc already claims "Linearizable get" — an overclaim against the wired local-map impl.
- `entity.update` (fenced) → epoch-fenced single-writer — logic exists in `FencedDurableEntity`/`PartitionFencedDurableEntity` but is **proven only against fakes** and is unwired; single-replica, not HA.
- Restart-durability → **none** today (in-mem); needs persistence (#349).
- `entity.timer` is declined today; `workflow.*` and `saga.*` have **zero code**.

**Substrate status:** the KV epoch fence (#345 1a) **is live** in the Rabia applier — but it guards **ownership-metadata** records, **not entity-state** writes. Earning the spec requires owner-routing (1e), the owner-change driver (1d-iii), quorum replication, and persistence (#349).

> For the durable-entity §14 sign-off: treat every consistency/durability line in the spec as **INTENDED**, and require the present-tense javadoc to be corrected to match the wired reality.

---

## 7. Known gaps & defects surfaced by this audit

**Code/behaviour** issues (beyond doc wording), surfaced for triage. *Tracking* reflects a GitHub-issue search (open + closed) on 2026-06-29; resolution statuses updated 2026-07-01 as fixes land.

1. **✅ RESOLVED (via #262 two-knob model) — Stream synchronous replication was unsatisfiable** — historically the APP replication factor was derived from `minSyncReplicas` alone (`RF = clamp(minSyncReplicas,1,N)`, owner-inclusive) while both publish paths await distinct non-self acks (`PartitionedStreamAccess.java`, `DefaultStreamPublisher.java`; precheck `DefaultReplicationManager.java`), leaving peers one short so every `minSyncReplicas≥1` publish failed `NOT_ENOUGH_REPLICAS` (verified at HEAD, 2026-06-29). An interim fix `1f581e530` (#378) set `RF = clamp(minSyncReplicas+1,1,N)`. **The #262 two-knob model supersedes it:** `replicas` is now an independent placement knob (`ReplicaPlacement.replicationFactor` ⇒ `RF = clamp(replicas,1,N)`) and `min-sync-replicas` (owner-counted) sets the barrier to `minAcks = min-sync-replicas − 1` distinct non-self acks. With `replicas ≥ min-sync-replicas` the peers exist ⇒ satisfiable; a too-small cluster fails CLEARLY. `ReplicaSetControllerTest.replicationFactorDerivesFromReplicasNotMinSyncReplicas` guards the replicas-derived RF. *Tracking:* **#378** (CLOSED, superseded) · **#262** (two-knob, CLOSED).
2. **✅ RESOLVED — KV `Remove` was unfenced** (`KVStore.java`) — a deposed owner/leader could delete a fenced key while `Put` was protected. **Fixed in `79e44fef6`:** `handleRemove` now applies the epoch/leader fence symmetrically with `Put`. `KVCommand.Remove` carries an optional authority `witness`; a `Remove` of a key whose committed value is fenced (any `EpochBearing` value, or the `LeaderKey`'s `LeaderValue`) is rejected — no `ValueRemove` emitted — unless the witness is present, matching-kind, and current, so a witnessless/wrong-typed/stale delete of a fenced key cannot pass. Decision reads only committed storage + the command → deterministic in the applier. *Tracking:* **#379** (CLOSED; sub-issue of epic **#345**).
3. **✅ RESOLVED — `DHTConfig` FULL docstring claimed strong consistency it did not provide** (`DHTConfig.java`) — claimed R+W>N strong consistency; FULL is W=R=1 and the read path never reconciles versions (no read-repair). **Fixed in `0f34a084c`:** FULL documented as eventually-consistent (async replicate, no read-repair, anti-entropy off under FULL); dead `isStronglyConsistent()` (0 callers) renamed `hasQuorumOverlap()` with an honest necessary-but-not-sufficient contract. Real read-repair is separate future work. *Tracking:* **#380** (CLOSED).
4. **⚠ `config.notifyChange` has no caller** — runtime config-change push is dead machinery; catalog row 176 implies it works. *Tracking:* **#381**.
5. **⚠ `DurableEntity` javadoc claims "Linearizable get"** on a non-linearizable local-map impl. *Tracking:* **#382** (under durable-entity epic **#345 / #352**).
6. **Default persistence is in-memory / snapshot-only** — a simultaneous full-cluster crash loses KV + DHT state since the last lifecycle snapshot. Intentional, but should be stated as a guarantee, not buried. *Tracking:* **#383** (under storage-durability epic **#349**).
7. **DHT guarantee downgrade undocumented** — slice-node/route/endpoint keys moved CP→eventual + quorum-durable→single-node-in-mem (catalog rows 94/95/152), framed only as a perf win. *Tracking:* **#384**.

---

## 8. Method, scope & references

- **Scope:** cluster data-plane (Rabia KV + DHT), cluster availability, streams, pub-sub/notifications, durable-entity (planned). Not covered: `@PgSql`/`@Sql` persistence semantics, AHSE storage, HTTP routing — candidates for a follow-up pass.
- **Confidence:** each row traces to `file:line`. Items the investigators could not run live (SWIM under-load latency, the off-by-one on a real cluster, STRONG-publish wiring, DHT staleness bound) are flagged UNVERIFIED in the backing notes and should be confirmed before being quoted as hard numbers.
- **Lens:** [`/consistency-lens`](../../../.claude/skills/consistency-lens/SKILL.md) · Kleppmann, *"Please stop calling databases CP or AP"*.
- **Durable-entity spec:** [`../specs/durable-entity-primitive-spec.md`](../specs/durable-entity-primitive-spec.md).
- **Corrections worklist:** [`./guarantees-corrections-needed.md`](./guarantees-corrections-needed.md).
