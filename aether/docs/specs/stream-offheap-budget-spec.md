# Stream Off-Heap Budget — Floor Reservation + Lazy Elastic Growth

**Status:** Draft (implementation-ready)
**Scope:** `aether/aether-stream`, `aether/node`
**Author:** spec-writer (unsupervised; assumptions documented inline as `[ASSUMPTION]` / `[DECISION]`)
**Version target:** `1.0.0-rc1`
**License:** BUSL-1.1 (all touched files under `aether/**`; carry the standard SPDX header)

---

## 1. Problem statement (root cause — settled, not re-litigated)

Each node has a fixed off-heap stream budget:
`StreamPartitionManager.DEFAULT_MAX_TOTAL_BYTES = 128 * 1024 * 1024L` (128 MB)
— `StreamPartitionManager.java:43`.

Today, `createStream` reserves the stream's **full retention capacity up front**:

- `createFreshStream` (`StreamPartitionManager.java:151-159`) computes
  `calculateStreamBytes(config)` (`:410-414`):
  `perPartition = 64 + (24 * retention.maxCount()) + retention.maxBytes()`, times `partitions`,
  and admits the stream only if `totalAllocatedBytes.get() + requiredBytes <= maxTotalBytes`
  (`:154`). On admission, `reserveAndPublish` (`:163-166`) adds the **full** `requiredBytes` to
  `totalAllocatedBytes` (`:164`).
- The ring is built eagerly in `StreamEntry.fromConfig` (`:454-466`), and
  `OffHeapRingBuffer.offHeapRingBuffer` (`:98-115`) **pre-allocates one contiguous `MemorySegment`**
  of `HEADER_SIZE + INDEX_ENTRY_SIZE*capacity + dataRegionSize` via `arena.allocate(totalSize, 64)`
  (`OffHeapRingBuffer.java:104-107`). There is **no growth path** — the buffer is sized once and
  never resized.

Consequence: a 4-partition stream with the management-API default retention
(`MANAGEMENT_API_RETENTION = retentionPolicy(10_000, 4MiB, 1h)`, `StreamRoutes.java:228-230`) grabs
`(64 + 24*10_000 + 4*1024*1024) * 4 ≈ 17.74 MB` even with a single event. ~7 such streams exhaust
128 MB. When exhausted, `createFreshStream` returns
`StreamError.General.STREAM_MEMORY_EXCEEDED` (`:154`, "Total off-heap memory limit exceeded",
`StreamError.java:20`).

On the management publish path, `StreamRoutes.publishToPartition` (`:195-203`) calls
`ensureStreamExists` (`:232-238`), whose `recoverWhenAlreadyExists` (`:240-244`) tolerates **only**
`STREAM_ALREADY_EXISTS`. Any other cause (including `STREAM_MEMORY_EXCEEDED`) is returned as-is, but
because `createStream` never put the entry into `streams`, the subsequent `publishLocal` would also
fail `StreamNotFound` — the publish fails opaquely.

A just-shipped fix right-sized `system:cluster-events` 64 MB → 16 MB
(`AetherNode.DEFAULT_STREAM_MEMORY_BYTES = 16 * 1024 * 1024L`, `AetherNode.java:524`; retention
resolved in `AetherNode.java:1948-1958`). That unblocked the immediate tests but the **structural
eager-full-reservation** remains and recurs as stream count grows. This spec is the structural fix.

---

## 2. Goals & invariants

- **G1 — Floor-on-create.** `createStream` reserves only a small per-partition **floor**. If the
  floor cannot be admitted, fail `STREAM_MEMORY_EXCEEDED` immediately (loud).
- **G2 — Created ⇒ usable (core invariant).** A successfully-created stream is guaranteed at least
  the floor of usable capacity. No "created-but-unusable / silent runtime write failure."
- **G3 — Lazy growth floor→cap.** The ring grows incrementally toward the retention `maxBytes` cap
  as data is appended; each increment is accounted against the budget. A stream never grows past its
  `maxBytes` cap — it evicts (existing retention semantics preserved).
- **G4 — Shared elastic pool.** `maxTotalBytes − Σ floors` is the pool all streams draw from as they
  grow.
- **G5 — Loud, attributed failure.** Exhaustion at create (floor) OR growth (pool) returns a clear
  `STREAM_MEMORY_EXCEEDED`-class error, propagates to the triggering operation (HTTP 500 / deployment
  FAILED), and emits a cluster event into `system:cluster-events`.
- **G6 — Honest propagation.** App/resource deployment stream-create failures must propagate (fail
  the deployment), not be swallowed. System-stream bootstrap may fail-soft + retry (existing
  `SystemStreamRegistrar` behaviour).
- **G7 — JBCT.** Errors as values (`Result`/`Option`); no exceptions for control flow; `@Contract` on
  void side-effects; do not block the consensus apply thread on slow allocation.

---

## 3. Current state (file:line reference map)

| Concern | Location |
|---|---|
| Budget constant 128 MB | `StreamPartitionManager.java:43` |
| `totalAllocatedBytes` (AtomicLong) | `StreamPartitionManager.java:49`, accessor `:105-107` |
| `createStream` entry | `:137-140` |
| `createFreshStream` (admission + reserve) | `:151-159` |
| `reserveAndPublish` (eager full add) | `:163-166` |
| `hydrateEntry` (follower path, full add) | `:240-248` |
| `calculateStreamBytes` (full capacity formula) | `:410-414` |
| `closeAndRelease` (release full capacity) | `:404-408` |
| `appendToPartition` → `buffer.append` | `:280-288` |
| `StreamEntry.fromConfig` (eager ring build) | `:454-466` |
| `OffHeapRingBuffer` single-segment pre-alloc | `OffHeapRingBuffer.java:98-115` |
| `OffHeapRingBuffer.append` | `:117-131` |
| `OffHeapRingBuffer.allocatedBytes()` (= `segment.byteSize()`) | `:188-190` |
| `OffHeapRingBuffer.close` (`arena.close()`) | `:212-217` |
| Eviction (`evictForSpace`, `evictOldest`, `applyRetention`) | `:192-261, 393-457` |
| `StreamError` enum (incl. `STREAM_MEMORY_EXCEEDED`) | `StreamError.java:11-33` |
| `StreamRoutes.ensureStreamExists` / `recoverWhenAlreadyExists` | `StreamRoutes.java:232-244` |
| `StreamRoutes.publishToPartition` | `:195-203` |
| `SystemStreamFactories.ensureLocalPartition` (swallow) | `SystemStreamFactories.java:170-191` |
| `StreamPublisherFactory.ensureStreamExists` (swallow) | `StreamPublisherFactory.java:74-76` |
| `StreamAccessFactory.ensureStreamExists` (swallow) | `StreamAccessFactory.java:112-114` |
| `SystemStreamRegistrar` (retry, terminal = `STREAM_MEMORY_EXCEEDED`) | `SystemStreamRegistrar.java:145, 197-201` |
| `ClusterEvent` sealed hierarchy + `permits` | `ClusterEvent.java:28-160` |
| `ClusterEventAggregator.emit` | `ClusterEventAggregator.java:253-267` |
| Deployment FSM `Active` slice-state transitions | `ClusterDeploymentState.java:1002-1089` |
| `handleSliceFailure` / `DeploymentFailed` route | `:1023-1051, 1084-1089` |
| HTTP error mapping (non-`HttpError` Cause → 500) | `aether/http-routing-adapter/.../ErrorMapper.java:16-18` |

### 3.1 OffHeapRingBuffer allocation model (key finding)

`OffHeapRingBuffer` is a **fixed-size, single-`MemorySegment`** ring. Layout (offsets in
`OffHeapRingBuffer.java:27-47`): a 64-byte header, an index region of `INDEX_ENTRY_SIZE(24) *
capacity` bytes, and a data region of `dataRegionSize` bytes — all in one contiguous segment
allocated once at `:107`. `capacity` = `retention.maxCount()`; `dataRegionSize` = `retention.maxBytes()`
(passed through `StreamEntry.fromConfig:458-463`). Append writes into the pre-allocated data region
with wrap-around (`writeDataBytes:331-340`); retention/eviction move `tail`/`head` pointers but never
free or grow memory. `allocatedBytes()` returns `segment.byteSize()` — i.e. **the full pre-allocated
size, not the live byte usage**. This is why a 1-event stream still reports ~17 MB.

**Implication:** lazy growth requires a structural change — either a segmented ring or a
grow-by-reallocate ring. See §4.2.

### 3.2 Apply-thread context

`onStreamConfigPut` (`StreamPartitionManager.java:222-228`) is `@MessageReceiver` — it runs on the KV
notification/apply path when a committed `StreamConfigKey` `Put` is observed; it calls `hydrateEntry`
(`:240-248`) which today does the eager full allocation on that thread. `appendToPartition`
(`:280-288`) → `OffHeapRingBuffer.append` is on the publish path (HTTP route thread / replication
thread), **not** the Rabia consensus apply thread. So growth-on-append does not run on the consensus
apply thread. Hydration on the apply/notification thread must keep allocation small (floor only) — see
§5.2.

---

## 4. Proposed design

### 4.0 Constants & data types (new)

In `StreamPartitionManager`:

```
// [DECISION] Floor = one growth segment. See §4.2 for segment sizing.
static final long DEFAULT_SEGMENT_BYTES = 256 * 1024L;   // 256 KiB data per segment
static final long PER_PARTITION_FLOOR_BYTES = ... ;       // = OffHeapRingBuffer.floorBytes(config) per partition
```

`OffHeapRingBuffer` gains:
- `static long floorBytes(long capacity, long maxBytes)` — bytes for the header + index + the **first**
  data segment (`min(DEFAULT_SEGMENT_BYTES, maxBytes)`), see §4.2.
- `static long capBytes(long capacity, long maxBytes)` — total bytes if grown to the `maxBytes` cap
  (equals today's `calculateStreamBytes`-per-partition). Used only for assertions/telemetry.
- An injected **growth-admission callback** so the buffer asks the manager for budget before
  allocating a new segment (§4.3).

[ASSUMPTION] The 64-byte header and the `24*maxCount` index region are allocated **at the floor**
(up front), not grown. The index is fixed-size by `capacity` and is small relative to the data region
for the streams that blow the budget (the 4 MiB data region dominates the 240 KB index at
`maxCount=10_000`). Growing only the **data region** keeps the index addressing model unchanged and
avoids re-hashing slot indices. The floor therefore = `HEADER_SIZE + INDEX_ENTRY_SIZE*capacity +
firstSegmentDataBytes`. [DECISION] This is acceptable: for the management-API default the floor is
`64 + 240_000 + 262_144 ≈ 502 KB` per partition vs. ~4.43 MB today — an ~8.8× create-time reduction,
and the index is a true minimum the stream needs to be usable (G2).

> [OPEN QUESTION resolved] *Should the index also be lazily grown?* No. Growing the index means
> re-mapping every live slot on resize (the index is addressed by `floorMod(offset, capacity)`),
> which is complex and error-prone. The index is `O(maxCount)` and bounded; data is the dominant and
> variable term. Grow data only.

### 4.1 Floor reservation at creation (G1, G2)

Replace the eager full reservation with a floor reservation.

**`createFreshStream` (`StreamPartitionManager.java:151-159`):**
- Compute `floorBytes = perPartitionFloor(config) * config.partitions()` instead of
  `calculateStreamBytes(config)`.
- Admit against the budget with an **atomic CAS-style reserve** (§4.3) rather than the current
  read-then-add (`:154`/`:164`), which has a check-then-act race when two creates run concurrently.
- On admission failure → `STREAM_MEMORY_EXCEEDED.result()` **and** emit the cluster event (§4.5) and
  log WARN (§4.5). No ring is built, nothing published.

**`reserveAndPublish` (`:163-166`):** the floor bytes are already reserved by the atomic admission in
`createFreshStream`; this method now only publishes the config and latches committed. (The current
`totalAllocatedBytes.addAndGet(requiredBytes)` at `:164` moves into the atomic admission step.)

**`StreamEntry.fromConfig` / `OffHeapRingBuffer.offHeapRingBuffer`:** construct each ring at its
**floor** (header + index + first data segment), not at `dataRegionSize`. The ring records its
logical `dataRegionSize` **cap** (`= retention.maxBytes()`) separately from its currently-allocated
data bytes.

Invariant G2 holds: a created stream owns its floor; the floor is non-zero usable data capacity.

### 4.2 Lazy growth floor→cap (G3) — segmented ring

[DECISION] Use a **segmented data region** rather than grow-by-reallocate. Reallocate-and-copy of a
multi-MB off-heap segment on the hot append path is a latency cliff and doubles peak memory during the
copy; a segment list grows in fixed increments with no copy.

**New `OffHeapRingBuffer` data model:**
- The header + index stay in a primary `MemorySegment` (`controlSegment`), allocated at floor.
- The data region becomes a list of fixed-size data segments
  (`List<MemorySegment> dataSegments`, each `DEFAULT_SEGMENT_BYTES`, except the last logical segment
  capped so total never exceeds `maxBytes`). `dataRegionSize` (the cap) is unchanged in meaning; the
  **allocated** data size = `dataSegments.size() * DEFAULT_SEGMENT_BYTES` (clamped to cap).
- Address translation: a logical data offset `pos ∈ [0, dataRegionSize)` maps to
  `segmentIndex = pos / DEFAULT_SEGMENT_BYTES`, `segmentOffset = pos % DEFAULT_SEGMENT_BYTES`. The
  existing wrap-around math in `writeDataBytes`/`readDataBytes`/`copyWrappedToContiguous`
  (`:319-346, 371-386`) is rewritten against this translation. A single logical write that spans a
  segment boundary is split across two `MemorySegment.copy` calls (the buffer already handles
  ring-wrap splitting, so this is the same shape of code).

**Grow trigger:** in `append` (`:117-131`), before `writeDataBytes`, compute whether the write needs a
data position beyond the currently-allocated data bytes. If yes and the cap is not yet reached, request
one more segment via the growth-admission callback (§4.3):
- **Admitted** → allocate the new `MemorySegment`, append to `dataSegments`, write proceeds.
- **Rejected (pool exhausted)** → do **not** silently drop. Behaviour by eviction policy:
  - `DROP_OLDEST` (EVENTUAL streams): fall back to the existing `evictForSpace` path — evict oldest to
    make room within **already-allocated** segments, then write. Growth was an optimization; eviction is
    the correctness fallback, so an EVENTUAL stream that can't grow simply behaves like a fixed ring at
    its current allocated size. **No error to the caller** (writes still succeed by evicting), but emit
    a **rate-limited** `StreamMemoryExceeded` cluster event (§4.5) tagged `phase=growth` so operators
    see the pool is saturated. [DECISION] EVENTUAL publish must not start failing just because the pool
    is full — that would regress the very publishes this spec exists to protect; the floor already
    guarantees usable capacity (G2).
  - `REJECT_WHEN_FULL` (STRONG streams): return `STREAM_MEMORY_EXCEEDED` from `append` (the STRONG
    contract already returns `BUFFER_FULL` when it can't evict; an inability to grow is the same class
    of "cannot accept write"). This propagates to the publish caller as a failure (loud).

> [OPEN QUESTION resolved] *Does growth interact badly with eviction shrinking?* We do **not**
> shrink/free data segments on eviction. Eviction only advances `tail`; freeing a segment would
> require compaction. [DECISION] Segments are released only on stream `close()`/`destroy`. This keeps
> the high-water-mark reserved for the stream's lifetime — acceptable because the cap bounds it and
> the floor model already drastically lowers the *initial* footprint, which is the bug. Reclaiming
> idle-stream high-water is a follow-up (RC2), tracked in §10.

**Capacity (index) full vs. data full:** `capacity` (= `maxCount`) eviction is unchanged
(`countEvictionsForSpace:398-413` first loop). Only the data-region sizing changes.

### 4.3 Accounting & concurrency (G4)

Single source of truth remains `AtomicLong totalAllocatedBytes` (`:49`). All reservation goes through
one atomic admission primitive:

```
// returns true iff the reservation was admitted (atomically reserved)
private boolean tryReserve(long bytes) {
    for (;;) {
        var current = totalAllocatedBytes.get();
        if (current + bytes > maxTotalBytes) { return false; }
        if (totalAllocatedBytes.compareAndSet(current, current + bytes)) { return true; }
    }
}

@Contract private void release(long bytes) { totalAllocatedBytes.addAndGet(-bytes); }
```

This replaces the read-then-add at `:154`+`:164` (a TOCTOU race today) with a CAS loop — correct under
concurrent `createStream` and concurrent growth.

**Reserve-then-allocate ordering & failure handling (the "reserve succeeds but allocate fails"
case):** reserve first (`tryReserve`), then `arena.allocate`. `Arena.allocate` throwing
(`OutOfMemoryError` / native alloc failure) is an unrecoverable VM-level condition; per JBCT we do
**not** use exceptions for control flow, but a native OOM is genuinely exceptional. [DECISION] Wrap the
single `arena.allocate` call for a growth segment in a guarded helper that, on `OutOfMemoryError`,
**releases the just-reserved bytes** (`release(segmentBytes)`) and returns a failure
`Result<MemorySegment>` (`STREAM_MEMORY_EXCEEDED`), so the accounting never leaks a reservation. This
is the one place a `try/catch` is justified (interop with `java.lang.foreign`), isolated to a private
method and immediately converted to a `Result`. The floor allocation in `offHeapRingBuffer` gets the
same guard: floor reserve → allocate → on failure release + return failure (so `createFreshStream`
reports `STREAM_MEMORY_EXCEEDED`).

**Growth admission callback wiring:** `StreamEntry.fromConfig` passes the manager's `tryReserve`/
`release` to each `OffHeapRingBuffer` (a small functional seam, e.g.
`LongPredicate reserve` + `LongConsumer release`), so the buffer accounts every segment against the
shared pool without a back-reference to the manager (keeps `OffHeapRingBuffer` decoupled and unit
-testable with a fake budget). Default seam = always-admit + no-op (preserves standalone buffer tests
like `OffHeapRingBufferTest`/`EvictionPolicyTest`).

**Release lifecycle:**
- `closeAndRelease` (`:404-408`): release **the bytes actually allocated** by this stream
  (`Σ over partitions of buffer.allocatedBytes()`), not `calculateStreamBytes(config)`. Add a method
  `StreamEntry.allocatedBytes()` summing each ring's live allocation. (Today `closeAndRelease` releases
  the full formula amount; under lazy growth the formula no longer matches what was reserved.)
- `destroyStream` (`:186-190`) and `reapIfIdle`/`removeIfStillIdle` (`:340-361`) already funnel through
  `closeAndRelease` — they inherit the corrected release automatically.
- `close()` (`:363-367`) sets `totalAllocatedBytes` to 0 after closing all entries — unchanged
  (whole-manager teardown).

### 4.4 Shared elastic pool (G4)

The pool is implicit: `available = maxTotalBytes − totalAllocatedBytes.get()`. `Σ floors` is just the
sum of admitted floors; the remainder is what growth draws from via `tryReserve`. No separate
data structure is needed — the single atomic is the pool. A read-only accessor
`availableBytes()` is added for telemetry/tests.

### 4.5 Loud, attributed failure + observability (G5)

**(a) Error value.** Reuse `StreamError.General.STREAM_MEMORY_EXCEEDED` (`StreamError.java:20`) for the
create-floor failure (unchanged identity, so `SystemStreamRegistrar.isTerminal` at
`SystemStreamRegistrar.java:197-201` keeps treating it as terminal — see §7). For STRONG growth-failure
in `append`, return the same enum constant.

[DECISION] Keep a single enum constant (don't split create vs growth) so existing terminal-classifier
logic and tests don't churn; the **cluster event** (below) carries the `phase` discriminator and the
numeric detail. The opaque message stays the user-facing detail string.

**(b) Propagation.**
- *Management publish:* `StreamRoutes.recoverWhenAlreadyExists` (`:240-244`) already returns any
  non-`ALREADY_EXISTS` cause unchanged → `ensureStreamExists` fails → `publishToPartition` fails →
  the route's `Result` is failed → `ErrorMapper` maps the non-`HttpError` cause to **HTTP 500** with
  `detail = "Total off-heap memory limit exceeded"` (`ErrorMapper.java:16-18`). **No route change
  needed** for publish; the create-floor failure now reaches here instead of being masked.
- *App/resource stream provisioning:* change `StreamPublisherFactory.ensureStreamExists`
  (`StreamPublisherFactory.java:74-76`) and `StreamAccessFactory.ensureStreamExists`
  (`StreamAccessFactory.java:112-114`) to **propagate** `createStream` failure into the
  `Promise<StreamPublisher>` / `Promise<StreamAccess>` (return the failed `Result` instead of
  discarding it). `buildPublisher`/`buildAccess` become `Result`-returning and the `.map(...)` in
  `provision` becomes `.flatMap(...)`. A failed provision fails the resource provisioning, which fails
  the slice load/activate, which transitions the slice to `FAILED` with the cause message as
  `failureReason` — surfacing through the existing deployment FSM path
  (`ClusterDeploymentState.handleSliceFailure:1023-1051` → `DeploymentFailed` route at `:1044-1048`
  and `:1084-1088`). The deployment thus FAILS VISIBLY (G6) rather than "deployed but dead."

  > [ASSUMPTION] Resource provisioning failure already propagates to slice `FAILED`. The slice
  > runtime provisions declared `StreamPublisher`/`StreamAccess` resources during load; a failed
  > `Promise` from `provision(...)` is the standard resource-provisioning failure that marks the slice
  > FAILED (the same mechanism any resource factory uses). The `fatal` flag on the resulting
  > `NodeArtifactValue` should be **false** for `STREAM_MEMORY_EXCEEDED` (it is a capacity condition
  > that may clear as other streams are destroyed), so `handleTransientFailure`
  > (`:1053-1075`) retries with backoff rather than `handleDeterministicFailure` permanently failing
  > it (`:1034-1051`). [DECISION] Map `STREAM_MEMORY_EXCEEDED` to a non-fatal/transient deployment
  > failure.

- *System-stream bootstrap:* keep fail-soft (`SystemStreamFactories.ensureLocalPartition`,
  `SystemStreamFactories.java:170-191`) **but** still emit the cluster event + WARN log so the soft
  failure is visible (see §6). The `SystemStreamRegistrar` retry loop continues to own re-attempts and
  correctly treats `STREAM_MEMORY_EXCEEDED` as terminal (won't thrash consensus — `:197-201`).

**(c) Cluster event.** Add one new closed variant to the sealed `ClusterEvent` hierarchy.

New permit + record in `ClusterEvent.java` (add to the `permits` list at `:28-59` and a record near
`:159`):

```java
/// Off-heap stream budget exhausted at stream creation (floor) or growth (elastic pool).
/// `details` carries: streamName, partitions, phase (one of "create-floor" | "growth"),
/// requestedBytes, availableBytes, maxTotalBytes, nodeId, consistencyMode.
record StreamMemoryExceeded(HlcTimestamp at, Severity severity, String summary,
                            Map<String, String> details) implements ClusterEvent {}
```

[DECISION] Reuse the existing `details: Map<String,String>` shape (every variant uses it) rather than
adding typed fields, matching `SliceFailure`/`DeploymentFailed` precedent. Severity = `WARNING` (it is
recoverable — destroying/right-sizing other streams frees the pool; it is not a quorum-class
`CRITICAL`).

**Envelope/codec note:** `ClusterEvent` is `@Codec`-annotated (`:27`); adding a permitted record
generates a new `ClusterEvent_StreamMemoryExceededCodec` (same pattern as the existing per-variant
codecs under `aether/node/target/generated-sources/.../ClusterEvent_*Codec.java`). This is a stream
**payload** type, not the slice-processor envelope, so `ENVELOPE_FORMAT_VERSION` does **not** change
(per project invariant #3 — that gate is only for `slice-processor` codegen output structure). The
events stream is a retention-bounded ring (not a persisted log), so older nodes simply won't recognize
the new variant; the consumer's `switch` must keep an `ExtendedEvent`/`default` arm — it already does
(`ClusterEvent.java:26` notes compiler-enforced exhaustiveness incl. an `ExtendedEvent` arm).

**Emit path.** `ClusterEventAggregator.emit(ClusterEvent)` (`ClusterEventAggregator.java:253-267`) is
owner-gated + replay-gated. Budget exhaustion is a **per-node, local** fact (each node has its own
budget), so it must NOT be owner-gated the way consensus-derived facts are — otherwise a non-owner
node that exhausts its budget would suppress the event. [DECISION] Add a dedicated emit entry point
that bypasses the owner gate but keeps the replay gate:

```java
@Contract public void onStreamMemoryExceeded(StreamMemoryBudget.Exhaustion e) {
    emitLocal(new StreamMemoryExceeded(hlcClock.now(), Severity.WARNING, e.summary(), e.details()));
}
```

where `emitLocal` is `emit` minus the `ownerCheck` gate (still honors `replayingCheck` and
publisher-bound check). This mirrors `SelfDrainInitiated` which the class doc (`ClusterEvent.java:154-159`)
explicitly notes is "NOT leader-gated" — a per-node truth. The aggregator must therefore expose a
**sink** the `StreamPartitionManager` can call without depending on `aether/node` (layering: aggregator
is in `aether/node`, manager in `aether/aether-stream`). Wire it as a `Consumer<Exhaustion>` callback
injected into `StreamPartitionManager` (alongside the existing `EvictionListener` seam), defaulting to
no-op. `AetherNode` binds it to `clusterEventAggregator::onStreamMemoryExceeded` during construction
(near the stream stack wiring, `AetherNode.java:1925-1964`).

**Rate-limiting.** Growth-phase exhaustion can fire on every append once the pool is saturated. The
emit sink must rate-limit per `(streamName, phase)` — [DECISION] at most one event per stream per
60s (a per-stream `AtomicLong lastEmittedAtMs` in the buffer or manager). Create-phase exhaustion is
naturally infrequent (once per failed create) and is not rate-limited.

---

## 5. Concrete change list (by file)

### 5.1 `OffHeapRingBuffer.java`
1. Replace single `segment` with `controlSegment` (header+index) + `List<MemorySegment> dataSegments`.
2. Add `static long floorBytes(long capacity, long maxBytes)` and
   `static long capBytes(long capacity, long maxBytes)`.
3. Constructor/factory: allocate control + first data segment at floor; store `maxBytes` as the cap.
4. Inject growth-admission seam (`LongPredicate reserve`, `LongConsumer release`) — default
   always-admit/no-op.
5. Rewrite data address translation (segment index + offset) in `writeDataBytes`, `readDataBytes`,
   `copyWrappedToContiguous`, `readWrappedData`, `readSliceAtOffset` to span the segment list.
6. `append`/`appendBatch`: on need-to-grow, `reserve(DEFAULT_SEGMENT_BYTES)` → guarded `arena.allocate`
   → add segment; on reject, EVENTUAL=evict-fallback (no error), STRONG=`STREAM_MEMORY_EXCEEDED`.
7. `allocatedBytes()` returns control + allocated data segments (already "what's allocated"; now
   reflects the lazy high-water).
8. `close()` (`:212-217`): release accounted bytes via the seam, then `arena.close()`.
9. Guarded-allocate helper (single `try/catch OutOfMemoryError` → release + `Result` failure).

### 5.2 `StreamPartitionManager.java`
1. Add `DEFAULT_SEGMENT_BYTES`, `perPartitionFloor(StreamConfig)`, `tryReserve`, `release`,
   `availableBytes()`.
2. `createFreshStream` (`:151-159`): reserve **floor** via `tryReserve`; on reject → emit event +
   WARN + `STREAM_MEMORY_EXCEEDED.result()`.
3. `reserveAndPublish` (`:163-166`): drop the `addAndGet` (now done in admission); keep publish+latch.
4. `hydrateEntry` (`:240-248`): reserve **floor** (not full) via `tryReserve`. [DECISION] If a
   follower cannot admit even the floor, log WARN + emit event but **still create the entry** with a
   degraded (already-reserved-what-it-could) ring — a follower must not diverge from the committed
   cluster config. Practically the floor is tiny; this is an extreme edge. Mark this in §8.
5. `closeAndRelease` (`:404-408`): release `entry.allocatedBytes()` (live), not
   `calculateStreamBytes`.
6. Keep `calculateStreamBytes` for telemetry/`capBytes` parity only (or delete if unused after the
   change — verify call sites; it is `private static`).
7. Add constructor seam: `Consumer<Exhaustion>` budget-event sink (default no-op) + thread it into
   each `OffHeapRingBuffer` for growth-phase events.
8. New nested record `Exhaustion(streamName, partitions, phase, requestedBytes, availableBytes,
   maxTotalBytes, nodeId, consistencyMode)` + `summary()`/`details()` builders (keeps event
   construction out of `aether/node` types).

### 5.3 `StreamPublisherFactory.java` / `StreamAccessFactory.java`
- `ensureStreamExists` → return `Result<Unit>`; treat `STREAM_ALREADY_EXISTS` as success
  (reuse the same `recoverWhenAlreadyExists` predicate — extract a shared helper, e.g.
  `StreamCreateOutcome.tolerateAlreadyExists(Result<Unit>)` in `aether-stream`, so `StreamRoutes`,
  both factories, and `SystemStreamFactories` share one definition rather than three copies).
- `buildPublisher`/`buildAccess` → `Result<...>`; `provision` uses `.flatMap` so a budget failure fails
  the provision `Promise`.

### 5.4 `SystemStreamFactories.java`
- `ensureLocalPartition` (both overloads, `:170-191`): keep fail-soft for the system bootstrap callers,
  but on a `STREAM_MEMORY_EXCEEDED` (vs benign `STREAM_ALREADY_EXISTS`) emit the budget event + WARN
  (not just DEBUG). [DECISION] System streams stay fail-soft because `SystemStreamRegistrar` owns
  retry and a node must still boot; but a genuine memory failure must be visible, not DEBUG-buried.

### 5.5 `ClusterEvent.java`
- Add `ClusterEvent.StreamMemoryExceeded` to `permits` (`:28-59`) + the record (`:159`).

### 5.6 `ClusterEventAggregator.java`
- Add `emitLocal` (owner-gate-bypassing variant of `emit`) and
  `onStreamMemoryExceeded(StreamPartitionManager.Exhaustion)` (translates to the event + `emitLocal`).

### 5.7 `AetherNode.java`
- Bind the manager's budget-event sink to `clusterEventAggregator::onStreamMemoryExceeded` where the
  stream stack is constructed (`:1925-1964`). The `StreamPartitionManager` is created just above this
  (the `streamPartitionManager(... clusterNode)` call referenced at `:1925-1927`); add the sink to that
  factory call (new overload).

---

## 6. Failure-propagation matrix

| Caller | Failure point | Cause | Surfaced as |
|---|---|---|---|
| `StreamRoutes.publishToPartition` → `ensureStreamExists` | create-floor (pool can't fit floor) | `STREAM_MEMORY_EXCEEDED` | HTTP **500**, detail "Total off-heap memory limit exceeded" (via `ErrorMapper` 500-default) + `StreamMemoryExceeded` event (phase=`create-floor`) + WARN log |
| `StreamRoutes.createStream` (explicit `POST /streams`) | create-floor | `STREAM_MEMORY_EXCEEDED` | HTTP **500** same detail + event + WARN |
| App slice resource `StreamPublisher`/`StreamAccess` (`*Factory.provision`) | create-floor | `STREAM_MEMORY_EXCEEDED` | Provision `Promise` fails → slice → `FAILED` (non-fatal/transient) → `DeploymentFailed` cluster event + retry w/ backoff (`ClusterDeploymentState:1053-1075`) + `StreamMemoryExceeded` event + WARN. **Deployment visibly fails**, not silent. |
| System bootstrap (`SystemStreamFactories.ensureLocalPartition`) | create-floor | `STREAM_MEMORY_EXCEEDED` | Fail-soft (node boots) BUT WARN + `StreamMemoryExceeded` event; `SystemStreamRegistrar` treats terminal (no retry thrash, `:197-201`) |
| EVENTUAL stream append (`OffHeapRingBuffer.append`) | growth (pool exhausted) | — (no error) | Evict-fallback within allocated segments; write **succeeds**; rate-limited `StreamMemoryExceeded` event (phase=`growth`) + WARN. Preserves publish liveness (G2/§4.2). |
| STRONG stream append | growth (pool exhausted, can't evict) | `STREAM_MEMORY_EXCEEDED` | `publishLocal` fails → publish caller fails (HTTP 500 / app error) + event (phase=`growth`) + WARN |
| Follower `hydrateEntry` | create-floor (extreme) | logged | Entry still created (no cluster divergence) + WARN + event; §8 |

---

## 7. Cluster-event schema (`StreamMemoryExceeded`)

| Field | Source | Example |
|---|---|---|
| `at` | `hlcClock.now()` | HLC ts |
| `severity` | constant | `WARNING` |
| `summary` | built | `"Off-heap budget exhausted creating stream 'orders' (4 parts): need 2.0 MB floor, 1.1 MB available of 128 MB"` |
| `details.streamName` | config | `orders` |
| `details.partitions` | config | `4` |
| `details.phase` | call site | `create-floor` \| `growth` |
| `details.requestedBytes` | reservation | `2097152` |
| `details.availableBytes` | `availableBytes()` | `1153433` |
| `details.maxTotalBytes` | `maxTotalBytes` | `134217728` |
| `details.nodeId` | `selfNode` | `node-3` |
| `details.consistencyMode` | config | `EVENTUAL` \| `STRONG` |

Visible at `GET /api/events` (the events stream the aggregator publishes to). Severity `WARNING`.

---

## 8. Backward compatibility

- **Existing app streams.** Same `createStream`/publish API; only the *timing* of allocation changes
  (floor-then-grow). External behaviour identical until the pool is genuinely exhausted, where it is
  now loud instead of silent. No API/signature change visible to slices.
- **Hydrated (follower) streams.** `hydrateEntry` (`:240-248`) reserves floor not full; followers
  always create the entry to stay consistent with the committed `StreamConfigKey` (§5.2 item 4). This
  is the one place we accept best-effort reservation rather than hard-fail, because a follower diverging
  from committed cluster config is worse than a transient over-subscription of a tiny floor.
- **`committed`-flag work (f3360cbea).** Untouched. `markCommitted`/`isCommitted`
  (`:470-476`) and the create/republish split (`:137-184`) are orthogonal to allocation sizing; the
  floor change lives entirely inside `createFreshStream`/`reserveAndPublish`/`hydrateEntry`.
- **`SystemStreamRegistrar` (Fix #1).** `STREAM_MEMORY_EXCEEDED` enum identity unchanged →
  `isTerminal` (`SystemStreamRegistrar.java:197-201`) still classifies it terminal; retry loop semantics
  unchanged.
- **`system:cluster-events` 16 MB right-size.** Stays as-is (`AetherNode.java:524, 1948`); under the
  floor model it now reserves only its floor at create and grows lazily — so the 16 MB is the *cap*, and
  the structural fix means even a mis-sized cap no longer eats the budget eagerly. The 16 MB right-size
  remains correct and complementary.
- **Standalone `OffHeapRingBuffer` tests.** The growth seam defaults to always-admit/no-op, so existing
  `EvictionPolicyTest`, `SegmentFallbackTest`, etc. that build buffers directly keep passing; they now
  exercise the segmented path with an unlimited budget.
- **`StreamPartitionManagerTest`.** `setUp` uses `streamPartitionManager(Long.MAX_VALUE)`
  (`StreamPartitionManagerTest.java:24`) — unaffected; floors always admit.

---

## 9. JBCT constraints

- **Errors as values.** All new failure paths return `Result`/`Option`; the only `try/catch` is the
  isolated native-allocation guard (`Arena.allocate` interop), immediately converted to a `Result`
  (§4.3). Documented as the justified exception.
- **`@Contract`.** New void side-effect methods (`release`, the budget-event sink, `emitLocal`,
  `onStreamMemoryExceeded`) carry `@Contract`.
- **Apply-thread.** Growth-on-append runs on publish/replication threads, not the Rabia consensus apply
  thread (§3.2). Hydration on the KV-notification thread allocates only the **floor** (small, fast),
  not the full retention buffer — strictly less work than today's eager full allocation. No new
  blocking is introduced on the apply path; segment growth (the only larger allocation) happens lazily
  off the apply path.
- **No `Promise<Result>` antipattern.** Factory `provision` returns `Promise<StreamPublisher>` (async
  Result), achieved via `Result.async()` / `flatMap` — never `Promise<Result<...>>`.
- **No FQCN in new code bodies** — import the new types (existing file already uses some FQCN in
  `AetherNode`; new lines should use imports per project rule).

---

## 10. Risks & open questions (with chosen resolutions)

1. **No segment reclamation on idle/eviction.** High-water reserved for stream lifetime.
   *Resolution:* accept for RC1 (cap-bounded; floor already fixes the eager-reservation bug). Track
   idle-stream high-water reclamation as RC2 follow-up. [DECISION]
2. **Segmented address translation correctness.** Wrap-around already exists; segment-boundary splits
   add a second seam. *Resolution:* property test (§11) replays random append/read/evict sequences and
   asserts byte-for-byte equality vs the current single-segment buffer behaviour for a fixed
   `maxBytes` (regression oracle). [DECISION]
3. **Segment size choice (256 KiB).** Too small ⇒ many segments + per-segment overhead; too large ⇒
   coarse floor. *Resolution:* `DEFAULT_SEGMENT_BYTES = 256 KiB`, clamp last segment to `maxBytes`.
   For tiny streams (`maxBytes < 256 KiB`) the single segment = the cap (degenerate, fine). [DECISION]
4. **EVENTUAL growth-reject = evict, not fail.** Could mask sustained over-subscription as silent data
   loss (oldest evicted). *Resolution:* rate-limited `StreamMemoryExceeded` event makes it observable;
   eviction is already EVENTUAL semantics under retention pressure. [DECISION]
5. **Aggregator layering.** Manager (`aether-stream`) must not depend on aggregator (`aether/node`).
   *Resolution:* inject a `Consumer<Exhaustion>` sink; `AetherNode` binds it. `Exhaustion` is an
   `aether-stream` type. [DECISION]
6. **Follower can't admit floor.** *Resolution:* create entry anyway, log+event (§8). [DECISION]
7. **`fatal` flag for deployment failure.** Mapping budget-exceed to non-fatal/transient assumes the
   condition may clear. If a slice's stream genuinely can never fit, it retries `MAX_RETRIES` (5) then
   `logMaxRetriesExceeded` + `DeploymentFailed` (`:1077-1089`) — still loud, eventually gives up.
   *Resolution:* non-fatal/transient is correct; permanent failure still surfaces after retries.
   [DECISION]

---

## 11. Test plan

### 11.1 Unit — `StreamPartitionManager` accounting
- `createStream_reservesFloorNotFullCapacity` — create a 4-part 4 MiB-retention stream; assert
  `totalAllocatedBytes()` ≈ `4 * perPartitionFloor` (≈ 2 MB), not ~17.7 MB.
- `createStream_floorAdmission_succeedsPastOldSevenStreamWall` — with the production 128 MB budget,
  create ≥ 20 management-API-default streams; assert all succeed (old wall was ~7).
- `createStream_exhaustionAtFloor_returnsMemoryExceeded_andEmitsEvent` — tiny `maxTotalBytes`; assert
  `STREAM_MEMORY_EXCEEDED` + the injected sink received one `Exhaustion(phase=create-floor)`.
- `append_growsTowardCap_accountsEachSegment` — append until growth; assert `totalAllocatedBytes`
  increases by `DEFAULT_SEGMENT_BYTES` per growth and `allocatedBytes()` tracks it.
- `append_growthExhaustion_eventual_evictsAndSucceeds_emitsRateLimitedEvent`.
- `append_growthExhaustion_strong_returnsMemoryExceeded`.
- `destroyStream_releasesLiveAllocatedBytes_notFormula` — create+grow+destroy; assert
  `totalAllocatedBytes()` returns to 0 (not negative, not the formula amount).
- `reserve_isAtomicUnderConcurrentCreate` — N threads create distinct streams sized so only K fit;
  assert exactly K succeed, `totalAllocatedBytes <= maxTotalBytes` always.
- `reserveThenAllocateFails_releasesReservation` — fault-inject allocation failure via the guarded
  helper seam; assert reservation released (no leak).
- `hydrateEntry_reservesFloor` and `hydrateEntry_cannotAdmitFloor_stillCreatesEntry`.

### 11.2 Unit — `OffHeapRingBuffer` grow
- `floorBytes_lessThanCapBytes_forLargeRetention`.
- `append_spansSegmentBoundary_readBackIdentical` (boundary correctness).
- `grow_property_matchesSingleSegmentOracle` (risk #2 — random op sequence equivalence).
- `growthSeamReject_eventual_fallsBackToEvict`; `growthSeamReject_strong_returnsFull`.
- `close_releasesAccountedBytesViaSeam`.

### 11.3 Unit — propagation & events
- `StreamPublisherFactory_provision_propagatesMemoryExceeded` (failed `Promise`).
- `StreamAccessFactory_provision_propagatesMemoryExceeded`.
- `StreamRoutes_publish_memoryExceeded_maps500WithDetail` (assert detail string).
- `ClusterEventAggregator_onStreamMemoryExceeded_emitsLocal_notOwnerGated` (non-owner still emits).
- `SystemStreamFactories_ensureLocalPartition_memoryExceeded_emitsWarnEvent_butFailsSoft`.
- `ClusterEvent_StreamMemoryExceeded_codecRoundTrip` (generated codec).

### 11.4 Integration — budget-stress suite
New suite (e.g. `aether/tests/integration/NN-stream-budget`): on a 5-node cluster, create streams past
the old ~7-stream wall (e.g. 30 management-API streams) via the CLI/management API; assert:
- All creates that fit the floor succeed (the wall is gone).
- When the pool is finally exhausted, the failing create returns **HTTP 500** with the exact detail and
  a `StreamMemoryExceeded` event appears in `GET /api/events` (loud, not silent).
- A slice whose declared stream can't be provisioned shows the deployment **FAILED** (not "deployed but
  dead") in deployment status + a `DeploymentFailed` event.
Use the project CLI for cluster management (not curl), per project conventions.

---

## 12. Reconciliation checklist (spec section → planned change → tag)

| # | Spec | Change | Tag |
|---|---|---|---|
| 1 | §4.1 floor reserve | `StreamPartitionManager.createFreshStream` floor + `tryReserve` | TODO |
| 2 | §4.1 | `reserveAndPublish` drop eager add | TODO |
| 3 | §4.2 segmented ring | `OffHeapRingBuffer` data-segment list + address translation | TODO |
| 4 | §4.2 grow trigger | `append`/`appendBatch` grow-or-evict/fail | TODO |
| 5 | §4.3 atomic admission | `tryReserve`/`release` CAS | TODO |
| 6 | §4.3 alloc-fail guard | guarded `arena.allocate` → release + `Result` | TODO |
| 7 | §4.3 growth seam | inject `reserve`/`release` into buffer | TODO |
| 8 | §4.3 release-on-destroy | `closeAndRelease` uses live `allocatedBytes()` | TODO |
| 9 | §4.5b publish propagation | (verify ErrorMapper 500 — no code change) | TODO |
| 10 | §4.5b app propagation | `StreamPublisherFactory`/`StreamAccessFactory` propagate | TODO |
| 11 | §4.5b deployment surface | non-fatal/transient mapping; verify slice FAILED path | TODO |
| 12 | §4.5c event variant | `ClusterEvent.StreamMemoryExceeded` + permits | TODO |
| 13 | §4.5c emit | `ClusterEventAggregator.emitLocal` + `onStreamMemoryExceeded` | TODO |
| 14 | §4.5c sink wiring | `StreamPartitionManager` sink + `AetherNode` bind | TODO |
| 15 | §4.5c rate-limit | per-`(stream,phase)` 60s throttle | TODO |
| 16 | §5.2.4 hydrate floor | `hydrateEntry` floor reserve | TODO |
| 17 | §5.4 system fail-soft+loud | `SystemStreamFactories.ensureLocalPartition` WARN+event | TODO |
| 18 | §5.3 shared tolerate helper | extract `tolerateAlreadyExists` | TODO |
| 19 | §11.1-11.4 tests | unit + integration suite | TODO |
| 20 | §7 changelog/catalog | `CHANGELOG.md` + `feature-catalog.md` entry | TODO |

Commit only when MISSING = STUB = SHORTCUT = OMISSION = SIMPLIFICATION = 0 across this table.

---

## 13. References

### Internal — primary code
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamPartitionManager.java`
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/OffHeapRingBuffer.java`
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamError.java`
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/SystemStreamFactories.java`
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamPublisherFactory.java`
- `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamAccessFactory.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/StreamRoutes.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/ClusterEvent.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/ClusterEventAggregator.java`
- `aether/node/src/main/java/org/pragmatica/aether/node/SystemStreamRegistrar.java`
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` (stream wiring ~1925-2035)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/fsm/ClusterDeploymentState.java`
- `aether/http-routing-adapter/src/main/java/org/pragmatica/aether/http/adapter/ErrorMapper.java`
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/StreamConfig.java`
- `aether/slice-api/src/main/java/org/pragmatica/aether/slice/RetentionPolicy.java`

### Internal — tests / docs to update
- `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerTest.java`
- `aether/docs/reference/feature-catalog.md`
- `CHANGELOG.md`
