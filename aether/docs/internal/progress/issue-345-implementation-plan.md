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
| 1d | **Stream append fence — per-stream-partition reshuffle epoch (#265-entangled).** `ownerEpoch` on `appendToPartition`, gate before `buffer.append` against the **stream-partition** domain high-water. **Requires building the per-stream-partition ownership epoch primitive** — a persisted, consensus-written stream-ownership record whose epoch advances on owner change (reshuffle). No such record exists today (HRW computed on the fly), so this is the start of #265's reshuffle-ring work. Flips STEP-0. | `StreamPartitionManager.java:558-566` | **L** | **HIGH** |
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


---

# OWNER RULING 2026-08-10 — Option A: full epic, entity + durability + workflow + saga

Scope decision after a costed four-way assessment (A full epic / B durability-only / C wire the
already-built fence / D ship as-is): **Option A. Production readiness matters.**

Consistent with the GA north star (2026-07-20: no time pressure, quality primary, four axes).
This epic stays on **rc3** — it is feature work, and rc4 is explicitly "no new features", so rc3
does not close until this lands.

## Re-grounding — what changed since the June plan

`issue-345-implementation-plan.md` was written 2026-06-24. Four things are different now:

1. **Phase 3's prerequisite is DONE.** The plan's Phase 3 targets "a fenced log on a stream
   partition sealed to the existing `LocalDiskTier`/S3 tier". In June that seal was
   `EvictionListener.NOOP`. It is now wired: `StorageFactory` composes memory + DHT + `LocalDiskTier`
   for streams, `SnapshotManager.restoreFromLatest()` runs at boot, and `PartitionWal` is on the
   production write path. Observed initializing on live cloud nodes 2026-08-09.
2. **Phase 2a is DONE** — `PerKeySerialExecutor` (lock-free tail-chaining) is real and shared by all
   three entity impls. Spec §11's table still marks piece 2 MISSING; the table is stale.
3. **The envelope freeze applies and is NOT threatened.** `@DurableEntity` involves zero
   slice-processor code (verified: no hits in `jbct/slice-processor/src/main`), so this arc needs no
   `ENVELOPE_FORMAT_VERSION` bump. The freeze at 1000 holds.
4. **The #277 coordination section is obsolete** — #277 is closed.

## Verified starting state

| Piece | State |
|---|---|
| 1a KV fence | code-present (`staleEpochWrite`/`EpochBearing`) |
| 1b stream-path fence | MISSING |
| 2a per-key serialization | **DONE** |
| 2b entity core + SPI | code-present, **structurally unreachable** — see below |
| 2c durable timers (#351) | zero code — every impl hard-fails `TimerNotSupported` |
| 3 restart-durability | zero code; prerequisite now met |
| 4a PersistentWorkflow (#353) | zero code |
| 4b Saga + audit (#354/#355) | zero code |

**The unreachability is the load-bearing fact.** `DurableEntityFactory.provision()` unconditionally
returns the *no-arg* `InMemoryDurableEntity` — a bare `ConcurrentHashMap`, `linearizableServe =
none()`. The wired constructor, `FencedDurableEntity` and `PartitionFencedDurableEntity` are fully
coded and tested, and their only non-test callers are three test files. `grep -rl "DurableEntity"
aether/node` returns nothing. Every ownership input those variants need already exists
(`EntityPartitionArc`, `CommittedPartitionOwnerSource`, `PartitionOwnerEpochGate`,
`OwnershipEpochHighWater`) and the node already constructs the epoch gate at `AetherNode:428`.

**No slice anywhere declares a DurableEntity resource** — no TOML, no example, no fixture. Nothing
would run this code even if it were wired. That governs increment 0 below.

## Increment ladder (risk-first, one gate each, no big-bang)

**I0 — a fixture that RUNS.** An example slice declaring and exercising a DurableEntity, driven in
Forge. Without it every later increment is unfalsifiable — this project has shipped silently-inert
features precisely because `build.sh` stayed green with no consumer running.
*Gate: entity ops observable in a Forge run.*

**I1 — wire the fenced entity. Do these IN ORDER; the order is load-bearing.**

**(a) Make the deploy failure observable to the operator — but NOT as originally written.**

> **Correction 2026-08-10, from live diagnosis.** The first version of this step said "activation
> failure never reaches the cluster slice-status surface" and called it an observability hole. That
> premise was an inference from I0 and it is WRONG. Verified mechanism, with live 5-node evidence:
>
> 1. `NodeDeploymentState.handleLoadingFailure` (`:394`) → `transitionToFailed` → FAILED **is** written to KV.
> 2. `ClusterDeploymentState.Active.handleDeterministicFailure` (`:1300`) routes a `DeploymentFailed`
>    event via `ctx.router()`, and then — because atomicity is `ALL_OR_NOTHING` — calls
>    `rollbackBlueprintForArtifact` (`:2004`).
> 3. Rollback issues `KVCommand.Remove` per instance (`removeNodeArtifactKey`, `:1518`);
>    `IndexedDeploymentMap.onNodeArtifactRemove` (`DeploymentMap.java:58`) does `index.remove(...)`.
> 4. `allDeployments()` therefore has nothing to group, so the `.reduce(FAILED, higherState)`
>    aggregation — which is correct code — never runs on a non-empty list.
>
> Observed live: `slicesStatus()` reports `size=1, state=UNLOADING` for ~5s, then `size=0` for the
> rest of the run. The artifact string matches `ENTITY_SLICE` byte-for-byte, so the predicate was
> never the problem.
>
> **`slicesStatus()` emptying is arguably CORRECT** — under `ALL_OR_NOTHING` the deployment did not
> happen, and that surface reports current state, not history. So there is no "make slicesStatus show
> FAILED" fix to make, and doing it would fight the atomicity contract.

What the step actually is, therefore:

- **Fix the fixture to poll the right surface.** `failIfSliceFailed` polls `slicesStatus()`, a
  current-state view that a rolled-back deploy correctly empties. It must instead watch the failure
  record — the `DeploymentFailed` cluster event. This is what converts I0's 4-minute timeout red into
  a fast red, and it is a fixture fix, not a product fix.
- **Then settle the one open product question** (see below) and fix only what it exposes.

**RESOLVED 2026-08-10 — the operator-facing surface works; there is no product defect here.**
Same reproduction, polling `/api/events` on all five management ports instead of `slicesStatus()`:

- A `DEPLOYMENT_FAILED` event appears for the artifact on **every** node and is identical across all
  five, so it is not a single-node artifact.
- **The diagnostic detail survives verbatim** — `No resource provider registered for resource type:
  …DurableEntity. Bundle the module supplying a ResourceFactory for this type on the runtime
  classpath.` appears in both `summary` and `details.reason`, not flattened to a bare state change.
  Corroborated by construction: `ClusterEventAggregator.handleDeploymentFailed` (`:639`) reads
  `value.failureReason()` into the summary and `buildFailedMetadata`.
- **Path B delivered it** (the `ClusterEventAggregator` KV-watch), definitively — Path A's
  `DeploymentEvent.DeploymentFailed` is a structurally different class that cannot produce this
  shape. The owner-gate suppression seen for other event types did not affect it.
- **Rollback does NOT retract it.** This is an append to the replicated `system:cluster-events`
  stream, not a mutable status entry — which is precisely why it behaves differently from the
  `slicesStatus()` case above. Polled every 3s for 227s: identical content, byte-identical length,
  on all five ports, zero flicker.
- Not tested: survival across a node restart / stream replay. Distinct question from the one H3
  raised; note it rather than claim it.

**So step (a) is a FIXTURE fix plus a docs note, and no product code:**

1. Repoint `failIfSliceFailed` from `slicesStatus()` (a current-state view that a rolled-back deploy
   correctly empties) to the `DEPLOYMENT_FAILED` event for the artifact. That is what converts I0's
   4-minute timeout red into a fast red.
2. Record the operator-facing reality in the docs: `POST /api/blueprints` answers `"applied"` on
   acceptance, and a per-node deployment failure surfaces afterwards in the cluster-event feed, NOT
   in slice status — which under `ALL_OR_NOTHING` correctly shows nothing, because the deployment did
   not happen. Anyone diagnosing "deploy said applied, nothing serves" needs to be told where to look.

**(b)** Add `resource-durable-entity` to `aether/node/pom.xml`, mirroring `resource-http`. It is
today depended on by no pom but its own, which is why nothing ships.

**(c)** Add a first-class `@Entity`-style qualifier to the resource module — there is no counterpart
to `@Http`/`@Notify`, so every author must hand-roll one (I0's fixture does exactly that).

**(d)** Wire the factory to a fenced variant via the resource SPI's context channel.

> **Correction 2026-08-10.** An earlier draft of this step said "constructs `PartitionFencedDurableEntity`
> from `EntityPartitionArc`, `CommittedPartitionOwnerSource`, `OwnershipEpochHighWater`". Those are
> **the wrong collaborators** — they belong to `InMemoryDurableEntity`'s wired constructor, not to
> `PartitionFencedDurableEntity`. Two distinct wired variants exist and they are not interchangeable:
>
> | | `InMemoryDurableEntity` (wired ctor) | `PartitionFencedDurableEntity` |
> |---|---|---|
> | Needs | `NodeId`, `EntityPartitionArc`, `CommittedPartitionOwnerSource`, `Option<OwnershipEpochHighWater>`, `Option<EntityLinearizableBarrier>` | `StorageEngine`, `PartitionOwnerEpochSource`, `EntityPartitionArc`, `Serializer`, `Deserializer` |
> | Fence | coarse — the single `"core"` governor arc; MISSES a same-generation owner reshuffle | per-`(keyspace, partition)` owner epoch; CATCHES that reshuffle |
> | Reads | `LinearizableEntityServe` — owner-routed, barrier round, epoch guard | **no override of `get(K, ReadConsistency)`** — inherits `DurableEntity:86`'s default and serves a bare local `storage.get` even when LINEARIZABLE is requested |
> | Storage | in-process map | `StorageEngine` — today only `MemoryStorageEngine`, so still not restart-durable |
>
> Neither is complete. The stronger fence and the linearizable read pipeline live in *different*
> classes, and no variant has both.

**Mechanism (verified, no new seam):** `ResourceFactory:17` already exposes
`default Promise<T> provision(C config, ProvisioningContext context)`; `SpiResourceProvider.registerExtension`
fills a node-wide map merged into every context. `StreamAccessFactory` is the working template and is
wired in `AetherNode.registerForwardExtensionsOnSpi` under a comment naming **"#345 item 1e-c"** — this
epic's own stream side already landed the pattern. `Serializer` and `Deserializer` are already
registered there; `NodeId` and `OwnershipEpochHighWater` too.

**Decision required before coding:** which variant does I1 wire? The I1 gate is about the FENCE
(`create_succeedsOnEveryNode_forTheSameKey` flipping), which argues for `PartitionFencedDurableEntity`
and its stronger per-partition epoch. The cost of that choice is that a caller requesting
`ReadConsistency.LINEARIZABLE` silently gets a local read — a claim-vs-reality gap that must then be
either closed (port `LinearizableEntityServe` onto it) or documented as a known limitation. Do not
paper over it.

> **OWNER RULING 2026-08-10: `PartitionFencedDurableEntity` AND port `LinearizableEntityServe` onto
> it.** One class ends up with both properties rather than a gate that passes without earning its
> guarantee, and no shipped API is left silently ignoring a `ReadConsistency` argument.
>
> Consequence — the collaborator set is the UNION of the two variants:
> `StorageEngine`, `PartitionOwnerEpochSource`, `EntityPartitionArc`, `Serializer`, `Deserializer`
> (already required) plus `NodeId`, `CommittedPartitionOwnerSource`, `Option<OwnershipEpochHighWater>`,
> `Option<EntityLinearizableBarrier>` (needed by `LinearizableEntityServe`).
>
> Already registered as SPI extensions: `NodeId`, `OwnershipEpochHighWater`, `Serializer`,
> `Deserializer`. To construct and register: `KvCommittedPartitionOwnerSource` (needs only the built
> `kvStore`; zero call sites today) and `EntityLinearizableBarrier` — which has **no factory at all**,
> only a bare `@FunctionalInterface` whose javadoc says the production binding arrives "with the
> durable-entity node wiring". It needs a ~10-line factory mirroring
> `LinearizableBarrier.noOpRound(applier, timeout)`, reusing the node-wide `clusterCommandApplier`
> (`AetherNode:1951`, `KVCommand`-generic so it is directly reusable) plus a timeout —
> `DurableEntityConfig` carries no timeout knob today, so either add one or borrow an existing value.
> `EntityPartitionArc` stays per-config, built inside `provision` from `keyspace`/`partitionCount`.

**(e)** Either honor `DurableEntityConfig.replicationFactor` or refuse it loudly — today it is
accepted and silently ignored.

*Gate: `DurableEntityForgeTest` test 10 `create_succeedsOnEveryNode_forTheSameKey` MUST flip to
failing — five nodes each accepting a create for the same key must become one accepted and four
rejected. A deposed partition owner is REJECTED on a same-generation reshuffle (the STEP-0 repro
flips). Then the cloud gate the plan requires for fence work.*

**I2 — piece 1b, the stream-path fence.** Required before entity state may live on a log.
*Gate: unit + Forge.*

**I3 — Phase 3, fenced log on a stream partition → disk tier.** Entity state moves from
KV-snapshot to a fenced log; fold-to-snapshot and tail; governor owns the fold; same `DurableEntity`
API, no author churn (spec §4.4). This is #349 path (a) for entity state — coordinate there.
*Gate: Forge kill-9 of the owner, state survives; then cloud.* **This also supplies the
crash-durability evidence currently missing for `PartitionWal` — today only unit-tested.**

**I4 — durable per-entity timers (#351).** *Gate: timer survives owner handover AND restart.*

**I5 — PersistentWorkflow facade (#353).**

**I6 — Saga + journaled run-once step + audit stream + operator API (#354, #355).** The operator API
is subject to the QUAD invariant — REST + CLI + docs + dashboard surface (or a recorded dormant-slot
decision).
*Gate: the plan's acceptance bar — a sample workflow and saga survive `kill -9` of the owner, and
100k entities stay within budget on a 5-node cluster.*

## Standing constraints for this arc

- **Claim discipline per increment**: catalog row 217 and `guarantees.md` get an evidence tag as each
  gate passes — never ahead of it. Row 217 stays "Partial" until I3 is cloud-green.
- **The spec's rejected alternative stays rejected**: a durable default on fenced-KV-on-DHT "quietly
  assumes a durable DHT that does not exist". Option (c), a persistent DHT `StorageEngine`, remains
  out of scope as its own epic.
- Separately and independently: `StorageBackedPersistence` (AHSE-backed Rabia snapshots) is built,
  unit-tested and orphaned. Wiring it bounds consensus-KV loss on full-cluster restart. It is NOT a
  substitute for anything above — snapshot granularity, not per-write durability.
