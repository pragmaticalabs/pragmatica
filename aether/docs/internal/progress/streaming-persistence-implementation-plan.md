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

## PHASE A-WAL — crash-durability (write-ahead log)

**User directive (2026-06-27): every ACKED event must survive `kill -9`.** Sealed segments cover *evicted* events; the un-sealed ring tail is volatile. A per-partition WAL makes the tail crash-durable: append+fsync BEFORE ack, replay on recovery, truncate as events seal. Green-field (no existing WAL). Integration points mapped (see investigation) — all in `aether/aether-stream` + `AetherNode`.

**Durability contract:** publish resolves (acks) only after the event is in the partition WAL and fsync'd. EVENTUAL = WAL-fsync then local-ring ack; sync-replica = WAL-fsync + replica acks. Crash + restart ⇒ WAL replay restores the ring tail; sealed segments restore the rest; no acked event lost. **Perf: group-commit** — batch concurrent appends into one fsync (per-event fsync would gate throughput; the user wants decent perf).

### Increments (gated; aether-stream unit test green each)
- **W1 — ring seed-offset primitive.** `OffHeapRingBuffer.append` only assigns `head+1` (`:329-330`); there is no write-at-offset. Add an offset-preserving seed primitive (e.g. `seedAppend(offset,payload,ts)` / `seedHead`) used by replay ONLY, so a ring resuming at `lastSealedOffset+1` keeps WAL/segment/cursor offsets aligned. **The load-bearing primitive.** Test: seed at K → `headOffset()==K`, `read(K)` returns it.
- **W2 — `PartitionWal` durable log.** New `…/stream/wal/PartitionWal.java`. Append-only file per `(stream,partition)` under `<streamDataDir>/wal/<stream>/<partition>.wal`. Record `[u32 len][u64 offset][u64 ts][u32 crc32][payload]`. `append(offset,payload,ts)` (buffered + **group-commit fsync**), `replayFrom(minOffset)` (skip ≤minOffset; torn final record dropped via len+crc), `truncate(uptoOffset)`, `close()`. Tests: append→replay roundtrip, torn-write recovery, truncate.
- **W3 — append before ack.** `StreamPartitionManager.publishLocal:586-598` — after `appendToPartition` yields the offset (`:592`), before replication: WAL append + fsync completes inside the synchronous Result (pre-ack for BOTH modes — EVENTUAL `PartitionedStreamAccess:508`, sync `:513`). Thread the `PartitionWal` handle via `StreamEntry`.
- **W4 — recovery on partition create.** Partitions materialize lazily (`StreamEntry.buildPartitions:980-988`), so replay is per-partition there, not a global boot step. Open-or-recover the WAL; replay records with `offset > lastSealedOffset` (from `SegmentIndex`) into the ring via the W1 seed primitive, before the ring is published. Anchor after `AetherNode.java:2571` rebuild.
- **W5 — truncate on seal.** `StorageSegmentSink.seal:64-67` success continuation (segment durably on disk): truncate the partition WAL up to `segment.endOffset()`.
- **W6 — WAL lifecycle.** Open/recover in `buildPartitions`, close in `StreamEntry.close:1062`, delete on `destroyStream`.
- **W7 — cursor crash-durability.** Ensure `CursorStore` commits fsync (crash-durable), not just buffered.
- **Harness — EmberCluster.** Writable per-node data dir (`storageConfig` `artifacts.diskPath` → test temp dir; currently `Map.of()` → default `/data` read-only, `EmberCluster.java:654`), preserved across `stop()`→`start()`; an ungraceful (`kill -9`-equivalent) stop for the crash test.
- **A6 (crash variant) — the gate.** `StreamCrashDurabilityTest`: 5 nodes, writable dirs; produce N + commit cursor → UNGRACEFUL kill → restart preserving IDs+dirs → assert ALL N readable + cursor survived (zero loss). Plus a graceful-restart variant.

### Load-bearing risks
1. **W1 seed primitive** — offset alignment of replayed tail vs sealed segments vs cursors; the whole story desyncs if replay assigns sequential offsets (the existing `appendRecovered`/`buffer.append` bug, `StreamPartitionManager:608` / `PartitionBackfill:292`).
2. **W2 torn-write recovery** — a crash mid-append must leave the WAL replayable (last partial record dropped by len+crc, not corrupting earlier records).
3. **W3 group-commit correctness** — fsync must cover the appending event before its ack resolves, while still batching across concurrent publishers.



Each gets a feature-catalog note (status → Partial/Planned) with why + the covering phase:
1. **Failover-to-different-node durability** — segments are node-local on disk in Phase A → **Phase B / #265** (replicated segments + placement-aware hydration).
2. **Cold full-cluster restart** — DHT engine is `MemoryStorageEngine` → **Phase B** disk-backed DHT engine increment, or **Phase C** PG.
3. **Exactly-once** — Phase A is at-least-once (`CursorStore.replaceRef` non-atomic, `segment/CursorStore.java:51-54`) → **Phase C** `PgTransactionalCursorCommit`.
4. **Full-cluster KV state** (consumer-group assignment `ConsumerGroupCoordinator:217`, stream config/registry) survives single-node restart via Rabia but needs `[backup]` for full-cluster (default `RabiaPersistence::inMemory`, `AetherNode.java:547`) → document; consider default-on for non-Forge.
5. **AHSE GC + Demotion stay OFF** (`DelegatedStorageAdapter.noOp()`, `AetherNode.java:1857`) — GC deletes on non-durable refcounts → data-loss risk; cross-node demotion needs DHT-tier capacity reporting fix → **separate track**.
6. **Dead-letter state** in-memory (`InMemoryDeadLetterHandler`) — lost on restart → low priority, documented.
7. **Durable-entity** is independent (its own #345/#349 fence substrate) — **not** on this path.

---

## A6 — RESOLVED (2026-06-28)

`StreamCrashDurabilityTest.fullClusterRestart_recoversAllAckedEvents_viaWalReplay` is GREEN (10+ consecutive in-JVM runs, ~68s each).

**Corrected root cause** (NOT read-routing alone, NOT a `PartitionBackfill` race). On a simultaneous full-cluster cold restart the cluster reaches quorum (3/5) at ~14s and flips `COLD_BOOT→NORMAL`, but SWIM's first probe-acks lag the QUIC attach — so the `QuorumLossDetector`'s SWIM-alive effective count momentarily decays below threshold and HEALTHY nodes **self-drain** before convergence. Terminal-removal makes them unrecoverable → cluster wedges at 3/5 → with RF=1 the data-holding HRW owner is stranded / reassigned to an empty-WAL survivor → reads return 0. The prior "owner replays WAL @49" evidence was the PRE-restart promotion, misread as post-restart recovery. Ownership is HRW-from-membership (not KV) so no KV persistence is needed — the fix is to stop the premature self-fence.

**Fix (4 parts, one shared bounded cold-boot window ≈75s):**
1. **`QuorumLossDetector` cold-boot gate (root).** `emitIntent` suppressed while `swimIsBootingSupplier` is active; a genuine minority still self-fences once the window elapses. (`QuorumLossDetector` + `AetherNode` injection.)
2. **SWIM cold-boot convergence window.** `AetherNode.swimIsBootingSupplier` stays true for `COLD_BOOT_CONVERGENCE_WINDOW_MS` (75s) past boot — covering the transport 60s force-dial — so never-HEALTHY seeds stay UNKNOWN (not evicted) until they connect (reuses the existing tested COLD_BOOT FAULTY-suppression branch).
3. **`NEAREST` reads made LOCAL-FIRST** (`ForwardingReadRouter`). Read local; forward to the HRW owner ONLY on a local miss. Closes the original `GOVERNOR`-never-forwards gap (a post-restart non-owner read reaches the WAL-recovered owner) WITHOUT forwarding away from a node that holds the data (the `StreamFanoutConsumerTest` regression).
4. **Test full-membership gate.** `StreamCrashDurabilityTest` waits for leader `/api/health` `nodeCount`=N before publish/read.

**Regression net (all green):** aether-stream 528, aether-deployment 744 (+ `QuorumLossDetectorTest.ColdBootSuppression`), integrations/swim 170, `AetherNodeColdBootWindowTest`, forge `StreamCrashDurabilityTest` (10+ runs) + `StreamFanoutConsumerTest`.

**Deferred (noted, not blocking A6):** NEAREST forwards on every empty tail-poll for a NON-replica node that holds no data (perf follow-up; a registered replica reads local). A genuine stable cold-boot minority self-fences after the 75s window rather than immediately.

---

## Validation discipline
- Per-increment: focused build (`build-runner`), existing `aether/aether-stream` + `integrations/storage` unit tests green.
- End-to-end: the Forge restart test (A6) is the gate; in-JVM first, never a cloud primary surface.
- `mvn install` fires `HetznerCloudIT` with `HCLOUD_TOKEN` set → **always** `env -u HCLOUD_TOKEN` + `-DskipTests`; forge via `integration-test -Dit.test=…`, never `verify`.
- aether/** = BSL-1.1 headers. Single-line commits, no trailers.
- Envelope bump only if `slice-processor` codegen output changes (this work doesn't).
