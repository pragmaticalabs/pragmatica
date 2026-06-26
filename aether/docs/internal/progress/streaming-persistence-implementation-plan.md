# Streaming Persistence — Implementation Plan (A → B → C)

**Status:** scoped + ready. Created 2026-06-27 (session `aether-main`). Goal (user-set): **working end-to-end streaming persistence** — "at least working, ideally decent perf" — with the standard *no unsupported claims*: anything we ship as a gap is documented with a why + a plan to cover it.

Owner: `aether-main` (this session) drives + integrates. Coordinates with **#265** (Phase B *is* #265; see [`issue-265-implementation-plan.md`](issue-265-implementation-plan.md)) and #261 (landed). Touches the hot streaming path in `AetherNode` — **sequential, gated increments, no big-bang.**

---

## The problem (verified against HEAD `c3bebcf11`, 2026-06-27)

Streaming storage is **100% RAM today**, by construction:
- `dhtStorage = MemoryStorageEngine.memoryStorageEngine(...)` (`AetherNode.java:408`) — DHT engine is all-RAM.
- `createStreamStorage` wires `MemoryTier + DhtStorageTier` only — **no disk tier** (`AetherNode.java:692-699`), and builds a **raw `StorageInstance` with `InMemoryMetadataStore`, no SnapshotManager/restore** (`StorageInstance.java:67`).
- `EvictionListener.NOOP` (`AetherNode.java:2531`) — evicted events vanish; nothing is ever sealed.
- `SegmentIndex` is `new SegmentIndex()` in-memory, **never rebuilt** (`AetherNode.java:2606`); `SegmentIndex.rebuildFromRefs()` has **zero callers**.
- `maybeSnapshot()` has **zero callers** (`DefaultSnapshotManager.java:53`) — refs never snapshotted.
- `CursorStore` passed as `none()` in prod → consumer offsets are RAM-only (`ConsumerRuntimeState` guards every commit with `onPresent`).

Net: catalog entries #146/#179/#180/#181/#185/#190/#191/#144 advertise durable streaming that **does not survive any restart**. (See companion doc-truth-pass.)

The end-to-end durable path breaks at four wiring points (sink, metadata snapshot, index rebuild, cursor) **and** a substrate choice (where the bytes live). Wiring is common to every target; substrate decides the guarantee.

---

## Staged durability targets (user-approved: A → B → C)

| Phase | Guarantee | Substrate | Scope |
|-------|-----------|-----------|-------|
| **A** | Node crashes & restarts, **reclaims its own partitions** → streams + cursors survive | LocalDisk tier on `streamStorage` + metadata snapshot | THIS doc. Self-contained, fully Forge-provable in single JVM. |
| **B** = #265 | Ownership **fails over to another live node** → segments follow | Replicated segments + placement-aware hydration (#265) + disk-backed DHT engine | [`issue-265-implementation-plan.md`](issue-265-implementation-plan.md). Builds on A. |
| **C** | Cold **full-cluster** restart + **exactly-once** | Postgres-backed segments + `PgTransactionalCursorCommit` | Largest. New `SqlConnector` wiring + PG dep on streaming path. `Pg{Stream,Segment,Cursor}Store` already exist (test-only). |

The **seal + cursor + snapshot + rebuild** wiring is shared; A delivers it. B and C swap/extend the substrate.

---

## PHASE A — local-disk, same-node-restart durability

Incremental + gated. After **each** increment: focused build green + existing tests pass. The Forge restart test (A6) is the end-to-end gate.

### A0 — Streaming baseline Forge test (regression net) — LAND FIRST
Deliver **#265 STEP 0** `StreamFanoutConsumerTest` in `aether/forge/forge-tests` (it is shared groundwork; see #265 plan §STEP 0). Scenarios 1–5: fan-out completeness, re-read-from-earliest, late-joining consumer, slow-consumer-no-loss, ordering/monotonic offsets. Harness: `EmberCluster.emberCluster(5, …)`, port band **7000/7100/7200**, `test-persistence` blueprint (set **partitions=1** + count-based retention ≥ N), drive via slice app-HTTP `publish`/`read`. Gate: `env -u HCLOUD_TOKEN mvn -q -Pwith-e2e -pl aether/forge/forge-tests integration-test -Dit.test=StreamFanoutConsumerTest` (NEVER `verify`). **Locks current semantics before we touch the path.**

### A1+A3 — promote `streamStorage` to a disk-backed, snapshot-capable `StorageSetup`
Replace the raw `createStreamStorage` (`AetherNode.java:692-699`) with a `StorageFactory` **"streams" StorageSetup** mirroring `defaultContentStorage`/`defaultArtifactStorage` (`AetherNode.java:909`, `:1800`): tiers **memory → LocalDisk → DHT**, a snapshot-capable `MetadataStore`, and a **stable per-node data dir** (config-driven, e.g. `${data.dir}/stream-segments`). This single change gives: the durable tier (A1), a snapshot-capable metadata store + restore-at-boot (A3), and folds streams into the unified `storageSetups()` so the snapshot scheduler (A3b) covers it for free. `LocalDiskTier.localDiskTier(Path, long) → Result<LocalDiskTier>`.

### A2 — wire the sink (keystone)
Replace `EvictionListener.NOOP` (`AetherNode.java:2531`) with `SegmentSealer.segmentSealer(StorageSegmentSink.storageSegmentSink(streamStorage, streamSegmentIndex))`. **Construction-order:** `streamStorage`/`streamSegmentIndex` are built at `:2606-2608`, after the SPM at `:2530`; both depend only on `dhtClientOption` / nothing, so **hoist their creation above `:2530`** (clean, no SPM dependency). On eviction → `buildSegment → SealedSegment → sink.seal` → `storage.put + storage.createRef + index.addSegment`.

### A3b — snapshot driver + index rebuild
- **Schedule** `maybeSnapshot()`: one AetherNode-level virtual-thread scheduled executor, ~10–15 s poll, iterate `storageSetups().values()` → `setup.snapshotManager().maybeSnapshot()` (self-gates on the dual trigger; cheap when not due). Dies with the node.
- **Restore-at-boot** already runs in the StorageSetup build path; **add** a boot call to `streamSegmentIndex.rebuildFromRefs(metadataStore)` *after* restore so the offset→segment index is reconstructed from restored `streams/…` refs.

### A4 — durable consumer cursors
Register `CursorStore.cursorStore(streamStorage)` as the stream `ProvisioningContext`/SPI extension (near `AetherNode.java:4493`). In `StreamAccessFactory` (`:45/:73/:109`), pull `context.extension(CursorStore.class)` and switch `ownerRoutedAccess`/`plainAccess` to the **cursor-bearing** `PartitionedStreamAccess.streamAccess(…)` overload (`:262` / owner-routed `:359`), passing `Option.some(cursorStore)` and `cursorWriter = (s,g,p,o) -> cursorStore.commit(g,s,p,o)`. `ConsumerRuntimeState` already fetches on attach (`:164`) and commits on progress (`:188,:213`) once `some`.

### A5 — tiered reads after restart
`StreamingCoordinator` holds a bare `SegmentReader` (`StreamingCoordinator.java:53`); pass a `TieredStreamReader.tieredStreamReader(streamSegmentIndex, streamStorage)` into the cursor-bearing access overload (param at `PartitionedStreamAccess.java:235`) so a post-restart read (empty hot ring) is served from sealed segments. (TieredStreamReader reads cold tier only — correct post-restart; hot ring serves live.)

### A6 — end-to-end restart proof (the gate)
Add `StreamPersistenceRestartTest` (sibling of A0; model on `StreamOwnershipDriverFenceTest` / EmberCluster `killNode`/`stop`/`start`). **Same-node restart preserving the data dir:** produce N → force seal (exceed ring capacity or seal trigger) → commit consumer cursor at offset K → restart the owning node **reusing its stream-segments dir** → assert the consumer **resumes at K** and reads **all N** (sealed segments via A5). Also add a **full-cluster `stop()`→`start()` variant asserting it FAILS** under memory-DHT — documenting the cold-cluster gap that motivates B/C. **Requires:** EmberCluster must give each node a stable data dir that survives restart (harness work in A0/A1).

---

## Deferred + DOCUMENTED gaps (the "ship with a plan" set)

Each gets a feature-catalog note (status → Partial/Planned) with why + the covering phase:
1. **Failover-to-different-node durability** — segments are node-local on disk in Phase A → **Phase B / #265** (replicated segments + placement-aware hydration).
2. **Cold full-cluster restart** — DHT engine is `MemoryStorageEngine` → **Phase B** disk-backed DHT engine increment, or **Phase C** PG.
3. **Exactly-once** — Phase A is at-least-once (`CursorStore.replaceRef` non-atomic, `segment/CursorStore.java:51-54`) → **Phase C** `PgTransactionalCursorCommit`.
4. **Full-cluster KV state** (consumer-group assignment `ConsumerGroupCoordinator:217`, stream config/registry) survives single-node restart via Rabia but needs `[backup]` for full-cluster (default `RabiaPersistence::inMemory`, `AetherNode.java:547`) → document; consider default-on for non-Forge.
5. **AHSE GC + Demotion stay OFF** (`DelegatedStorageAdapter.noOp()`, `AetherNode.java:1857`) — GC deletes on non-durable refcounts → data-loss risk; cross-node demotion needs DHT-tier capacity reporting fix → **separate track**.
6. **Dead-letter state** in-memory (`InMemoryDeadLetterHandler`) — lost on restart → low priority, documented.
7. **Durable-entity** is independent (its own #345/#349 fence substrate) — **not** on this path.

---

## Validation discipline
- Per-increment: focused build (`build-runner`), existing `aether/aether-stream` + `integrations/storage` unit tests green.
- End-to-end: the Forge restart test (A6) is the gate; in-JVM first, never a cloud primary surface.
- `mvn install` fires `HetznerCloudIT` with `HCLOUD_TOKEN` set → **always** `env -u HCLOUD_TOKEN` + `-DskipTests`; forge via `integration-test -Dit.test=…`, never `verify`.
- aether/** = BSL-1.1 headers. Single-line commits, no trailers.
- Envelope bump only if `slice-processor` codegen output changes (this work doesn't).
