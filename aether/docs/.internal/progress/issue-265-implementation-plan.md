# #265 Implementation Plan — Placement-Aware Stream Hydration (rc2)

**Status:** scoped + ready to implement. Moved rc3→**rc2** (2026-06-24). Design spec: [`../../specs/placement-aware-stream-hydration-spec.md`](../../specs/placement-aware-stream-hydration-spec.md) (merged #341 — READ IT; §5 reshuffle, §13 reconciliation). This doc = the implementation plan + the **STEP 0 streaming baseline test** to land first.

## Why / the bug
Every node materializes a ~2.66 MB ring for every partition of every stream (no placement check) → memory is O(streams × partitions × nodes); followers over-subscribe the 128 MB budget unconditionally; no partition cap → cluster OOM vector. Fix: materialize a partition's ring only on its HRW replica set (owner+replicas); non-replicas hold metadata only and forward (reusing owner-forwarding).

## Corrected scope (spec §13 is partly STALE)
The #261 backfill + catch-up-gate work (spec "Phase 3") is **already landed** — commit `e0303164a` + #333 follow-ups (`d2dd76021`,`f912a7903`,`13e9eb9f6`). The spec marks it MISSING; the code disagrees. So W3 below is DONE. Net ~6 work items.

## Work items
| # | Change | State | Cplx | Risk |
|---|---|---|---|---|
| W0 | Inject `roleFor(stream,partition)→Role` placement supplier into `StreamPartitionManager` (build-order via the existing `AtomicReference` seam) | MISSING | M | HIGH |
| W1 | Gate `hydrateEntry`/`buildPartitions`: materialize ring iff OWNER/REPLICA; non-replica = metadata-only catalog entry | MISSING | M | HIGH |
| W2 | Budget reframe: budget **rejects/paces** over-budget materialize (remove unconditional over-subscribe) | WRONG (opposite today) | M | HIGH |
| W3 | #261 onBecameReplica trigger + coverage-gated SYNCING→CAUGHT_UP | **DONE** | — | — |
| W4 | **Reshuffle ring lifecycle: ISR-gated materialize-before-release, never-below-RF caught-up, flap debounce** | MISSING | **L** | **HIGH** |
| W5 | Derived partition-count cap: parser + `createFreshStream` pre-commit reject + follower-ceiling event | MISSING | M | MED |
| W6 | Cap read REST→CLI→Docs triad (invariant #1) | MISSING | S | LOW |
| W7 | Forge tests (placement / budget / reshuffle-history / cap) | MISSING | M | LOW |

## Risk hotspots
1. **W4 (hardest)** — injects a per-partition ring-lifecycle state machine + catch-up gating into `ReplicaSetController.reconcilePartition` (`:250-275`), today a registry-only mutation but a hot, under-load, consensus-adjacent path. Concurrency + transient over-budget (§6) + "never below RF caught-up". Needs heavy Forge + eventually a **live-cluster** pass.
2. **W2** — reverses the deliberately-documented must-not-diverge invariant (`StreamPartitionManager.hydrateEntry:465-474`); a reserve/release asymmetry leaks budget; correctness now *couples to* membership-FSM convergence.
3. **W0/W1 build-order seam** — `streamPartitionManagerRef` AtomicReference at `AetherNode:2457` ALREADY exists but is consumed for the #261 earliest-retained wiring (`:2461-2464`), NOT placement. Thread `ReplicaSetController.roleFor` (built `:2635`, after the manager `:2465`) back in + the pre-membership-boot deferral rule (§5.4).

## Reusable (verified — spec "reuse" claims HOLD)
- HRW placement: `ReplicaPlacement.place()/rank()/score()` (FNV-64 of `stream|partition|nodeId`) + `ReplicaSetController.roleFor/roleFrom/ownerFor:281-334`.
- Ring alloc site: `StreamPartitionManager.buildPartitions():852` ← `onStreamConfigPut:443-450 → hydrateEntry:475-499`; `OffHeapRingBuffer.floorBytes():159` (~2.66 MB at capacity=100k).
- Budget: `DEFAULT_MAX_TOTAL_BYTES` 128MB, enforced (currently defeated) `hydrateEntry:478-486`.
- Reshuffle: `ReplicaSetController.reconcile:208→reconcileStream:237→reconcilePartition:250-275` (registry-only today). Owner-forwarding: `AetherNode:2709` (read) / `:2740` (write).

## Phasing (gated, Forge-first, observability-first)
- **STEP 0 — streaming end-to-end baseline test FIRST** (the regression net; see below). Land + green BEFORE touching hydration.
- **P1 — the memory win**: W0+W1+W2 + placement/budget Forge tests. Delivers O(...)→O(replica-set) + fixes unbounded over-subscription. Moderate risk; most of the value.
- **P2 — reshuffle correctness**: W4 + reshuffle-history test. The hard part; careful design + heavy Forge + a live-cluster pass.
- **P3**: W5 + W6.
- Consider an **observability surface first** (per-node ring-count / materialized-bytes / placement telemetry) so P1/P2 are diagnosable.

---

# STEP 0 — Streaming end-to-end baseline integration test (do this first)

Goal: lock current log/Kafka-style streaming behavior so #265's refactor has a regression net. Forge in-JVM (single-JVM multi-node), the `SliceInvocationTest`/`SliceMediaTypeTest` pattern.

## Confirmed semantics (from code)
- **Log/Kafka fan-out**: every consumer keeps its OWN offset (server keeps no per-consumer state); every consumer reads ALL messages. `StreamAccess.fetch(fromOffset,maxEvents)` / `StreamPartitionManager.readLocal(...fromOffset...):572`.
- **Replay from 0 = YES** if offset ≥ tail (earliest retained); below tail → clean `CursorExpired(requested, tail)` (`OffHeapRingBuffer.readChecked:532-551`), NOT silent loss.
- **Ordering is per-partition only**. HTTP publish hardcodes **partition 0** (`StreamRoutes:232`, `StreamApiRoutes:395`) → publishing over HTTP gives one clean ordered log.

## Harness anchors
- Slice template: **`aether/tests/blueprints/test-persistence`** already injects `StreamAccess<String>` (`@ResourceQualifier(type=StreamAccess.class, config="streams.test-events")`, `EventStreamReader.java:17`), declares `[streams.test-events]` (`resources.toml:13`, partitions=4, retention time 5m), and exposes app-HTTP `publish`/`read` routes (`PersistenceSlice.java:134`). **Caveats to fix for a controlled test:** set **partitions=1** (clean global order) and **count-based retention ≥ N** (so a slow consumer is never evicted → no spurious `CursorExpired`). Either author a dedicated minimal stream slice (cleanest, avoids perturbing test-persistence's own tests) or a test-only blueprint variant.
- Cluster: `EmberCluster.emberCluster(5, base, baseMgmt, baseApp, prefix)` → `start().await()` → await `currentLeader().isPresent()` → deploy blueprint TOML POST `/api/blueprints` on leader mgmt port → drive slice app routes via `JdkHttpOperations`. (5 nodes per project minimum.) **Port band**: pick unused trio e.g. **7000/7100/7200** (existing bands 5050–6999, 12500+); see `forge-tests/.../TEST_PORT_ALLOCATION.md`.
- Publish: simplest = the slice's own app-HTTP `publish` route (plain JSON, no namespace plumbing). Read: slice `read` route (`fetch(fromOffset,max)`, partition 0). (Or the management `StreamApiRoutes` GET `/api/streams/events/{ns}/{stream}/{version}?fromOffset=&maxEvents=` → `{events:[{offset,payload}],nextOffset,hasMore}`.)

## Scenarios (the ask + extras)
1. **Fan-out completeness** — publish N; run M parallel consumers at DIFFERENT paces (vary batch size + inter-poll sleep), each independently reading from offset 0 forward; assert each gets ALL N, in order, no dups/gaps.
2. **Re-read from earliest** — a consumer that reached tail resets offset→0 and re-reads; assert it gets all N again (replay).
3. **Late-joining consumer** — start a consumer AFTER all N published; assert it reads the full history from 0.
4. **Slow consumer, no loss** — one consumer reads very slowly while publishing continues; assert it eventually gets all N, no gaps (generous count retention → no eviction).
5. **Ordering / monotonic offsets** — partitions=1: offsets strictly monotonic, payloads in publish order.
6. *(stretch)* **CursorExpired surfaced** — with small retention, read below tail → assert a clean `CursorExpired`, not silent loss.
7. *(stretch)* **Concurrent publishers + consumers** — interleave; consumers eventually drain all published.

Core = 1–5 (cover the request). Put it in `aether/forge/forge-tests` as e.g. `StreamFanoutConsumerTest`. Gate: `env -u HCLOUD_TOKEN mvn -q -Pwith-e2e -pl aether/forge/forge-tests integration-test -Dit.test=StreamFanoutConsumerTest` (NEVER `verify`).

## Build safety (whole ticket)
`mvn install` fires HetznerCloudIT with HCLOUD_TOKEN set → ALWAYS `env -u HCLOUD_TOKEN` + `-DskipTests`; forge tests via `integration-test -Dit.test=…`, never `verify`. aether/** = BSL-1.1.
