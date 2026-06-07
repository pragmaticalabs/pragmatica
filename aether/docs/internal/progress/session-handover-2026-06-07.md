# Session Handover — 2026-06-07 (stream off-heap budget: root-cause + structural fix)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `9635a6582` · pushed up to `7b0c6acf6`; **commits `f27f56f22`→`9635a6582` are LOCAL-ONLY** (per user instruction: "changes committed but left local"). Untracked: `aether/tests/integration/suites/02z-killonly/` (local scaffolding).

## TL;DR
The 04/08 app-stream-publish regression was **off-heap budget exhaustion** (`STREAM_MEMORY_EXCEEDED`), NOT consensus contention. Two wrong theories (re-publish storm, slow-apply backpressure) were chased from log inference; **one authenticated `curl` against the live endpoint gave the real answer** (`500 "Total off-heap memory limit exceeded"`). Fixed in two parts: **(#1, pushed)** right-size `system:cluster-events` off-heap 64MB→16MB (it ate half the 128MB per-node budget); **(#2, local)** the structural rework — `OffHeapRingBuffer` now floor-reserves + lazily grows (segmented), with CAS budget accounting, loud `STREAM_MEMORY_EXCEEDED` + a `ClusterEvent.StreamMemoryExceeded`, and failure propagation to publish (HTTP 500) and deployment (transient→retry→`DeploymentFailed`). All unit-tested + Docker-validated (04/08 100%-fail → green).

## Commits this session (local unless noted)
| Commit | What |
|--------|------|
| `7f659732e` (PUSHED) | fix(stream): right-size `CLUSTER_EVENTS_MAX_BYTES` 64→16MB (#1) |
| `7b0c6acf6` (PUSHED) | chore(observability): SLOW-APPLY (Rabia apply executor) + SLOW-HANDLER (MessageRouter) probes |
| `f27f56f22` (local) | #96 Wave 1 — segmented `OffHeapRingBuffer` + lazy floor→cap growth + budget seam (+spec) |
| `0028a8a1b` (local) | #96 Wave 2 — floor-reservation + CAS budget accounting + exhaustion sink in `StreamPartitionManager` |
| `ae529a61b` (local) | #96 Wave 3 — `StreamMemoryExceeded` event + propagate exhaustion to publish/deployment |
| `9635a6582` (local) | docs: changelog + feature-catalog for #96 |

Also merged earlier this session (PUSHED): **PR #240** (peglib 0.6.2 + FlowPrinter idempotency) at `0397c8574` — reviewed, hardened (alignment snapshot), green.

## The 04/08 root cause (settled, empirical)
- Streams reserve their FULL retention capacity up front (`calculateStreamBytes = (64 + 24*maxCount + maxBytes) * partitions`). A 4-part 4MB-retention stream grabbed ~17.7MB with 1 event; `system:cluster-events` grabbed **64MB**. The per-node budget `DEFAULT_MAX_TOTAL_BYTES = 128MB` fit ~7 streams.
- In a multi-suite batch, cumulative reservations (cluster-events 64 + ~3.5 app streams) hit 128MB → the next `createStream` returns `STREAM_MEMORY_EXCEEDED`; the management publish path (`StreamRoutes.ensureStreamExists` → `recoverWhenAlreadyExists`, tolerated ONLY `STREAM_ALREADY_EXISTS`) failed the publish 100% → "Stream not found".
- Symptoms all explained: **04 passed isolated** (budget free), **failed in a batch** (cumulative); **11 always passed** (cluster-events created first); a notification-decouple experiment **falsified** the consensus theory (08 still failed after decoupling).
- **Proof:** `curl -X POST .../api/streams/publish/notifications` on the live cluster → `500 "Total off-heap memory limit exceeded"` = `StreamError.General.STREAM_MEMORY_EXCEEDED`. The stream-list showed ~125MB already reserved.

## #2 structural fix — spec + 3 waves (all unit-green, Docker-validated)
Spec: `aether/docs/specs/stream-offheap-budget-spec.md` (662 lines, 20-item reconciliation checklist).
- **Wave 1** — `OffHeapRingBuffer`: single contiguous segment → control segment (header+index, at floor) + `List<MemorySegment>` data segments (256KiB each), grow-on-append toward cap; `floorBytes`/`capBytes`; injected `LongPredicate reserve`/`LongConsumer release` admission seam (default always-admit, so existing tests pass); guarded `arena.allocate` (release-on-OOM). Address translation rewritten (ring-wrap ∘ segment-boundary split). **Safety net: `grow_property_matchesSingleSegmentOracle` (6000 random ops, byte-for-byte vs reference) passes.** `growthFrozen` invariant: a ring that starts wrapping below cap never grows again (stable modulus).
- **Wave 2** — `StreamPartitionManager`: CAS `tryReserve`/`release` (fixes read-then-add TOCTOU); `createFreshStream`/`hydrateEntry` reserve FLOOR not full capacity; `closeAndRelease` releases LIVE bytes; `Exhaustion` record + `exhaustionSink` plumbing. **Accounting composition (no double-count/leak):** manager reserves `control+firstSegment` at create, buffer seam reserves growth segments; destroy releases both halves → returns to 0. Leak detector `destroyStream_releasesLiveAllocatedBytes_returnsToZero` + `reserve_isAtomicUnderConcurrentCreate` pass.
- **Wave 3** — `ClusterEvent.StreamMemoryExceeded` (WARNING, auto-generated per-variant codec — NO envelope bump); `ClusterEventAggregator.emitLocal` + `onStreamMemoryExceeded` (owner-gate-bypassed, per-node fact) + per-(stream,phase) 60s rate-limit; `AetherNode` binds the sink; factories (`StreamPublisherFactory`/`StreamAccessFactory`/`SystemStreamFactories`) swallow→propagate via shared `tolerateAlreadyExists`; **#11 deployment surface fixed structurally** — `SpiResourceProvider` was unconditionally `Fatal`; new slice-api `ResourceCapacityExhausted` marker reclassifies `STREAM_MEMORY_EXCEEDED` as `Intermittent` → retry → `DeploymentFailed` after MAX_RETRIES.
- **EVENTUAL streams** evict-and-succeed-with-event on growth saturation (floor guarantees usability, event gives visibility); **STRONG** reject loudly. Decision documented in spec §10.

## Validation
- **Unit (all green):** aether-stream 416/0, aether/node 522/0, slice-api 256/0, resource/api 75/0. Codec round-trip confirms codegen wired.
- **Docker runtime:** `11,04,08` batch on the Wave-3 image → **11 6/0, 04 4/0, 08 5/0** (was 04 3/1 / 08 4/1, both 100% publish failure). Segmented buffer round-trips under sustained load + cross-node replication.
- **Full suite (in progress at handover):** **cluster A 10/10 GREEN** (00,04,06,07,08,09,10,11,14,15 — incl. the fixed 04/08; NO #96 regression). **Cluster B collapsed** on the pre-existing #68/#94 multi-kill instability (test-kill-multiple restore failed → cluster B unrecoverable → cascade to 0 cores → 03/05/12/13 inherit a dead cluster). This is the documented shared-cluster-B contamination cascade, NOT #96 (which touches stream allocation, nothing in the membership/leader/scale path). A clean cluster-B signal needs a cluster-B-only re-run; expect the same #68/#94 flakiness.

## Reconciliation (spec 20-item checklist): 19/20 DONE
All of #1–#18 + #20 (docs) done across waves 1-3. **#19 (integration budget-stress suite) DEFERRED → task #97** — the budget behavior + loud-fail path are unit-validated (`succeedsPastOldSevenStreamWall` ≥20 streams, `StreamFactoryPropagationTest`, `StreamRoutesPublishMemoryExceededTest`, rate-limit test) and the happy path is runtime-validated (04/08 + cluster A); a dedicated E2E stress suite asserting HTTP-500+event+deployment-FAILED at exhaustion is the one remaining coverage item.

## Separate finding kept for #94: apply-thread coupling
The decoupling experiment proved (via the `SLOW-APPLY`/`SLOW-HANDLER` probes, kept in `7b0c6acf6`): the single Rabia apply thread runs heavy control-plane reactions synchronously (`ClusterDeploymentState`/`ControlLoop`/`HttpRoutePublisher` on `pool-2-thread-1`, 12-37ms/task, queue→126), causing CONSENSUS-lane backpressure under load. **This is real and likely behind #94 recovery-latency**, but is NOT the 04/08 cause. The KVStore notification-decouple experiment was REVERTED (falsified for 04/08); revisit it for #94 on its own merits. Probes left in as observability.

## Open tag-gate / follow-ups
- **#97** — #96 integration budget-stress suite (the one deferred spec item).
- **#94** — recovery latency / NODE_FAILED-within-60s + the apply-thread coupling above (consider decoupling notification dispatch off the apply thread, with the reset/resync caveat).
- **#68** — 02-chaos generation-quiesce-180s after multi-kill (cluster-B-collapse trigger).
- **#93** — A3 drain-budget 500-vs-409 (local: `resolveLifecycleState` before enqueue).
- **#95** — 05-security needs secure-mode cluster-B variant.
- **#91** — physical-node-drain DHT durability (RC2; barrier patch preserved).

## Adversarial off-heap review — 3 bugs found + fixed (`cb00fb574`)
A post-implementation `jbct-reviewer` adversarial pass on the committed off-heap code found what the 6000-op property oracle missed (it always grew to full cap):
- **CRITICAL (data corruption/OOB):** a frozen-below-cap DROP_OLDEST ring receiving an event larger than its *allocated* (not cap) size evicted to empty, then wrote `L` bytes into an `A`-byte ring → self-overlap corruption OR `dataSegments.get(idx)` IndexOutOfBounds. Fixed: guard rejects/drops events `> allocatedDataBytes` after the grow attempt (STRONG→loud fail, EVENTUAL→drop, never write past allocation).
- **WARNING (native-OOM leak):** multi-partition construction OOM on partition k leaked Arenas + budget + escaped `Result`. Fixed: `OffHeapRingBuffer.fromConfig`→`Result<StreamEntry>`, partial-build closes siblings + releases floor budget.
- **WARNING (close race):** shared-Arena `close()` vs in-flight read/append → uncaught `IllegalStateException`. Fixed: `guardedAccess` converts it to a `BUFFER_CLOSED` Result.
Re-validated: aether-stream **422/0** incl. 6 new tests (frozen-ring oversized drop/reject, frozen-regime property oracle, concurrent-close, partial-construction budget-return). **Docker-validated on the `cb00fb574` image: 11 6/0, 04 4/0, 08 5/0** — the hardened create path (`fromConfig`→`Result`) works at runtime. The off-heap structural fix is now fully implemented, reviewed, hardened, and validated at unit + runtime.

## Learnings (saved to memory: `feedback_capture_actual_error_first`)
- **For an API/endpoint failure, capture the actual error RESPONSE BODY first (curl the live endpoint) before theorizing from logs.** Two wrong root-cause theories + two built-and-validated fixes were avoided by one curl. The `"detail"` field named the exact error enum.
- **Off-heap correctness via a property oracle**: the 6000-op random-sequence equivalence test was the right safety net for the segmented-ring rewrite — stronger than re-deriving offset math by hand.
- **Backpressure is `rate_in` vs `rate_consumed`** — a slow consumer (single-thread apply doing heavy sync work) causes lane backpressure at modest volume; instrument dispatch handling-time to find the slow path (MessageRouter + apply-executor probes).
