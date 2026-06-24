# #345 Implementation Plan — Durable Single-Writer Entity (ownership fence + entity primitive)

**Status:** planned, risk-first. Owner: main clone (me); merges driven by me. Runs parallel to #277 (aether-clone) — file-disjoint for the fence phases. Specs: [`durable-entity-primitive-spec.md`](../../specs/durable-entity-primitive-spec.md) (epic, supersedes #190), [`ownership-fence-spec.md`](../../specs/ownership-fence-spec.md) (piece 1).

## Why the fence comes first (composition)
Today partitioned-key ownership is decided by HRW and is **advisory** — the data-plane write path never checks the owner's epoch, so a **stale owner** (post reshuffle / partition / governor handover) can still commit → two owners double-write → split-brain. The **ownership fence** makes single-writer a *guarantee*: propagate the owner `Epoch` (an existing CP value, already committed to the Rabia-backed KV — **no new consensus**) to data-plane writers, and enforce a per-ownership-domain monotonic high-water at each replica's commit point. The **durable entity** then layers placement + per-key serialization + durability + timers on top of that correctness substrate. So piece 1 (fence) is a hard prerequisite for piece 2 (entity).

**Second foundation gate — #349 (persistent DHT backing):** the DHT is `MemoryStorageEngine` (in-memory), so the entity is **HA, not restart-durable** until #349 lands. The fence needs nothing from #349; only the entity's restart-durability claim does. (See Open Questions.)

## Risk-first phasing
Ranked by interaction-risk × blast-radius. Phase 1 is consensus-adjacent and **cloud-gated**; Phases 2–3 are additive and Forge-sufficient.

### STEP-0 — split-brain baseline (do FIRST)
Forge test: two owners for a partition across a forced governor handover; assert the **old owner's write is currently ACCEPTED** (documents the bug the fence must flip) + a within-epoch throughput baseline. Mirror existing Forge consensus tests. This is the regression gate.

### Phase 1 — the fence (foundational, consensus-adjacent, Forge→cloud gated)
| # | Item | Anchor | Cx | Risk |
|---|---|---|---|---|
| 1a | Generalize `staleLeaderWrite` → add an `EpochBearing` accessor across the `AetherValue` sealed types (governor `communityEpoch` + ownership `ownerEpoch`); gate any epoch-bearing key in the Rabia applier | `KVStore.java:93-98` / `handlePut:76-83`; `AetherValue.java:36,460,1274` | M | **HIGH** — runs inside the Rabia applier on every replica; must stay deterministic |
| 1b | Per-ownership-domain high-water table (CP-seeded, monotonic, **per-domain not per-key** so it fences new-key inserts too) | new in-memory table | M | **HIGH** — seeding/advance under governor churn (reconciler-under-load class) |
| 1c | DHT data-plane fence — epoch on `VersionedEntry`; thread through put | `MemoryStorageEngine.java:36,59-76`, `DistributedDHTClient.java:108`, `DHTNode.java:179` | L | **HIGH** — every DHT write; ⚠ replication-payload compat |
| 1d | Stream append fence — `ownerEpoch` on append, gate before `buffer.append`; `StreamConsensusCommand` + epoch | `StreamPartitionManager.java:558-566` | M | MED |
| 1e | Owner-routed linearizable reads + takeover catch-up + typed causes (`StaleEpoch`/`NotCurrentOwner`) | `ReplicaSetController.ownerFor:329` | M | MED |
| 1f | **Ownership/epoch observability triad** — `GET /api/ownership/{domain}` → owner `NodeId` + current `Epoch` per partition/domain (REST→CLI→Docs, invariant #1). Per **observability-first** doctrine; also unblocks the Phase-1 *cloud* handover test (no public owner/epoch accessor exists today — STEP-0 had to reconstruct ownership from pure HRW) | M | LOW |

> **STEP-0 flip (P1d):** the baseline's `publishLocal` call gains the stale `ownerEpoch` argument and the assertion flips from accepted→rejected (`StaleEpochAppend`). This needs the real owner-epoch wiring (STEP-0 uses fabricated `Epoch` constants as modeling stand-ins) — so the flip rides 1b (high-water) + 1d (epoch on the append) and benefits from 1f (real owner/epoch query).

**Gate:** Forge first (STEP-0 flips to *rejected*; read-your-writes across handover; throughput unchanged within a stable epoch) → **then cloud** (deposed-owner write rejected on *every* replica under a real governor handover; linearizable read under load). Do not stack Phase 2 until Phase 1 clears the cloud gate.

### Phase 2 — entity substrate, HA-first (additive, Forge-sufficient)
- **2a** Per-key serialization queue (owner-side `ConcurrentHashMap<Key,Queue>` + worker).
- **2b** `DurableEntity<K,S>` core + resource SPI — **new module `aether/resource/durable-entity/`** mirroring `aether/resource/http/` (annotation + `ResourceFactory` + `META-INF/services` entry; no framework edits). **Fenced KV-snapshot state (in-memory, HA-only)** — the spec's "first functional cut on the fence."
- **2c** Durable per-instance timers (fenced-persisted, handover-rebuilt).

### Phase 3 — restart-durability (#349 path (a), foundational, gated)
Move the entity's durable state from KV-snapshot to a **fenced log on a stream partition sealed to the existing `LocalDiskTier`/S3 tier** (`integrations/storage/` AHSE — already built). No-replay: the entity folds to a snapshot and tails; the governor owns the fold (no determinism/migration burden). Same `DurableEntity` API → **no author churn** (spec §4.4). **Option (c) — a persistent DHT engine — is explicitly OUT of scope (its own epic, "the single largest storage build").**
> ⚠ The stream substrate is **success-critical** (see memory `[[project_streaming_is_essential]]`) — build P3 to a high persistence + performance + correctness bar; gate Forge→cloud; **no shortcuts**. Coordinate with the #265/#261 streaming roadmap (shared stream subsystem).

### Phase 4 — facades + ops (additive)
- **4a** Workflow facade (`PersistentWorkflow` over `StateMachineDefinition`) — supersedes #190.
- **4b** Saga facade + run-once journaled step + audit stream + operator API (full REST→CLI→Docs **triad**, invariant #1).

## Risk hotspots
1. **1a in the Rabia applier** — determinism across replicas; a non-deterministic guard diverges state.
2. **High-water seeding/advance under governor churn** — the reconciler-under-load class (see memory). Cloud-validate.
3. **`VersionedEntry` epoch field = DHT replication-payload compat** (distinct from envelope versioning) — needs a compat story: readers tolerate a missing/old epoch, or the payload is versioned. Treat as a wire-compat decision, not a silent change.
4. **Owner-handover catch-up correctness** — new owner must catch up before serving linearizable reads.

## Reusable vs net-new
- **Reuse:** the `Epoch` CP token; governor/ownership consensus commit (Rabia `KVCommand.Put`); the `staleLeaderWrite` monotonic-CAS pattern; HRW placement + `ReplicaSetController` owner resolution; governor-change epoch propagation; `StateMachineDefinition`; the resource SPI; `SliceInvoker`; DHT quorum put/get; core JBCT types.
- **Net-new:** per-domain high-water table; epoch in `VersionedEntry` + DHT/stream commit-point gate; owner-routed reads + takeover catch-up; the `EpochBearing` interface; per-key serialization queue; durable per-instance timers; `DurableEntity`/`PersistentWorkflow`/`Saga` resources + facades; audit stream.

## Coordination with #277 (parallel)
- **Parallel-safe for Phase 1.** The fence touches `KVStore`, `MemoryStorageEngine`, `DistributedDHTClient`, `DHTNode`, `StreamPartitionManager`, `ReplicaSetController`, `AetherValue` — **disjoint** from #277's set (`SliceFactory`, `FactoryClassGenerator`, `InvocationHandler`, `SliceInvoker`, `AetherNode`, `AetherKey`, `ObservabilityRoutes`).
- `AetherKey` (#277) vs `AetherValue` (#345) = same `kvstore` dir, **different files** — package-adjacency only.
- **Only watch:** the late entity-facade phase (2b/3) may add resource wiring near `AetherNode` (which #277 touches) → sequence after #277's `AetherNode` work or worktree-isolate then.

## Verification
- **Unit:** `KVStore` stale-epoch reject per epoch-bearing type; `computeVersionedEntry` epoch gate; high-water monotonicity/re-seed.
- **Forge (primary gate, single-JVM):** STEP-0 repro flips to rejected; read-your-writes across handover; within-epoch throughput unchanged.
- **Cloud (REQUIRED, Phase 1 only):** deposed-owner rejected on every replica under real governor handover; linearizable read across handover under load. Forge first, cloud as final gate per item.
- Acceptance: fence §10 + entity §13 (sample workflow+saga survive `kill -9` of owner; 100k entities within budget on a 5-node cluster).

## Commit / merge model
I own merges → commit directly on `release-1.0.0-rc2` in risk-first **gated batches** (STEP-0 → 1a → 1b → … each behind its Forge gate; the whole of Phase 1 behind the cloud gate before Phase 2 stacks). No feature branch for my own work per project convention; each batch single-line-committed and verified.

## Resolved decisions (2026-06-24)
1. **Durability sequencing** — entity is **HA-first** (KV-snapshot, P2), then **restart-durable** (fenced log on durable stream, P3) — both behind one API, no author churn (spec §4.4).
2. **Cloud gating** — Phase 1 (1a–1e) runs a Hetzner cloud pass as the *terminal* gate (reconciler-under-load class). Forge first, cloud last.
3. **Wire compat — collapsed.** No backward-compat constraint → add `ownerEpoch` directly to the wire `DHTMessage` put-request and the in-memory `VersionedEntry`; no tolerant-reader / versioned-message / two-phase rollout. The entry carries two orthogonal numbers: HLC `version` (last-write-wins) + `ownerEpoch` (fence).
4. **#349 scope** — include **path (a)** (fenced log on the existing `LocalDiskTier`/S3 stream substrate) as **P3** → the entity ships restart-durable. **Defer option (c)** (persistent DHT engine) to its own epic.
