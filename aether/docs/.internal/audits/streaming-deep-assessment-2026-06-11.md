# Aether Streaming — Deep Design Assessment

**Date:** 2026-06-11 · **Against:** `analysis/cluster-topology-audit` (rc1 `dd5a2187f` + audit commits) · **Method:** 3 agents (architecture/completeness, gaps/failure-semantics, scalability/performance), synthesized. Companion to `design-completeness-assessment-2026-06-10.md` (gap #1, tickets #248–#254) and the prior `streaming-performance-analysis.md` (2026-04-10, partially obsolete — §6).

## Verdict

The **architecture is sound and the architectural big call is right**: the data plane is consensus-free (Rabia carries only stream metadata — one `StreamConfigKey` commit per stream with a committed-latch; events flow point-to-point via HRW-owner routing on the dedicated FORWARD QUIC lane). Stream throughput is structurally decoupled from the ~1K commits/s consensus ceiling. The off-heap ring + lazy-growth budget model (the offheap-budget spec **is** landed — the earlier "7 streams exhaust 128MB" claim is obsolete), namespaces, system streams, and read-forwarding are real and wired.

The problem is one layer down: **replication is the only thing standing between volatile rings (#248) and data loss, and its three load-bearing guarantees are each independently broken** — offset fidelity, catch-up, and sync-ack semantics. Meanwhile nearly every durability/semantics component *exists and is unit-tested but is not wired into the node*: replication batcher, cursor stores (in-storage and PG), transactional cursor commit, the entire push-consumer runtime (retry/SKIP/STALL/DLQ), watermark/assignment stores, segment sealer, tiered reader, PG segment store. The streaming subsystem is the repo's seam-disease in its purest form: a working in-memory happy path, validated by a happy-path-only suite (04's own charter flags TAUTOLOGY tests), surrounded by dead machinery that reads as features.

**Honest delivery semantics today:** at-most-once, with silent loss windows (acked-then-killed, ring wrap under slow consumers, replica divergence) and silent duplication windows (cursor loss on restart). The streaming-spec promises more (STRONG mode, `backpressure=block|reject`, durable consumers) than the wiring delivers.

## 1. Architecture as implemented (condensed)

- **Write:** slice `publish` → serialize → HRW partition owner (`ReplicaPlacement.stableHash64`; same placement authority as `ReplicaSetController`) → local off-heap ring append (or one QUIC forward hop, FORWARD lane) → async fire-and-forget replication to HRW replica set → ack to publisher **on local append** (app path never awaits replication; `minSyncReplicas` honored only on the system-stream path).
- **Read:** pull. Local read if owner; else forward to a `CAUGHT_UP` replica with one alternate retry. Push listeners only for co-located consumers; remote = adaptive 1–50ms polling, batch 100.
- **Replication:** per-event, unbatched (`ReplicationBatcher` exists, unwired at `AetherNode.java:2036`); receiver appends at *local* ring head ignoring `fromOffset`; acks report owner-frame offsets; any ack promotes SYNCING→CAUGHT_UP.
- **Retention:** ring DROP_OLDEST (EVENTUAL) with `EvictionListener.NOOP` in production — evicted events are gone (#248); `RetentionEnforcer`/`TieredStreamReader` vacuous; PG backends orphaned.
- **Control plane:** KV-backed registry, consumer-group coordinator (registry-only — does not drive delivery), leader-gated `SystemStreamRegistrar` (level-triggered, self-healing) for cluster-events + namespaces. PR #239 fully merged (2026-06-06); 04-streaming passed in the 2026-06-08 full run.

## 2. Findings, ranked

### Critical — replication correctness (the data-safety chain)

| # | Finding | Evidence |
|---|---|---|
| S1 | **Silent replica divergence**: receiver ignores `message.fromOffset()`, appends at local head; transport fire-and-forget, no retry; one dropped/reordered `ReplicateEvents` permanently shifts all subsequent local offsets while acks report owner-frame offsets — divergence undetectable; forwarded reads then serve wrong events for requested offsets | `ReplicationReceiveHandler.java:95-121`, `StreamPartitionManager.java:402-408`, `DefaultReplicationManager.java:84-114` |
| S2 | **Backfill never fires for the common case**: the trigger gate checks the *local* buffer's `eventCount()` — a fresh replica's ring is empty so `onBecameReplica` is skipped (inverted vs documented intent); any live ack then promotes it CAUGHT_UP, making a suffix-only replica a legitimate read target *and backfill source* | `StreamPartitionManager.java:461-464`, `ReplicaSetController.java:265-268`, `ReplicaRegistry.java:69-79` |
| S3 | **App publish ack = local ring write, nothing more** — combined with #248 volatility: acked events lost on node kill, undetected | `PartitionedStreamAccess.java:421-424` vs `DefaultStreamPublisher.java:192-205` |
| S4 | **STRONG mode dead end-to-end** (no consensus-path injection, no apply handler, creation rejected `AHSE_REQUIRED_FOR_STRONG` under production NOOP listener) while spec/`StreamConfig` advertise it; if naively wired it would be one Rabia proposal **per event** (≤~500 events/s cluster-wide) — unviable as designed | `StreamPublisherFactory.java:78`, `StreamPartitionManager.java:207`, `DefaultStreamPublisher.java:165-167` |

### High

| # | Finding | Evidence |
|---|---|---|
| S5 | `minSyncReplicas` accounting hollow: acks carry no replica identity (two acks from one replica satisfy minAcks=2); replica set includes self (inflates precheck); ack-before-register race → spurious 5s `REPLICATION_TIMEOUT`; unverified self-send double-append hazard | `DefaultReplicationManager.java:96-148`, `ReplicaSetController.java:262-269`, `StreamPartitionManager.java:386-390` |
| S6 | **Consumer cursors memory-only in production** (`StreamAccessFactory` omits `CursorStore`; commit → in-heap map + NOOP writer) → restart/redeploy = duplicate-everything or loss; the durable pieces (CursorStore, PgCursorStore, PgTransactionalCursorCommit) are dead code; `CursorStore.replaceRef` itself non-atomic delete-then-create + leaks one block per commit | `StreamAccessFactory.java:109-132`, `PartitionedStreamAccess.java:447-451`, `CursorStore.java:51-54` |
| S7 | **Push-consumer runtime entirely unwired** (retry/backoff/SKIP/STALL/DLQ; `StreamConsumerAdapter` zero production usages); latent bugs if wired as-is: 60s idle reaper kills quiet push consumers; mid-batch DLQ exhaustion stops delivery until next append; DLQ is in-memory | `ConsumerRuntimeState.java:148-157, 300-349` |
| S8 | **Slow consumer + ring wrap = silent loss then wedged reader**: DROP_OLDEST + NOOP listener; `CursorExpired` only DEBUG-logged in the poll loop, no jump-to-tail/reset policy | `OffHeapRingBuffer.java:540-541`, `ConsumerRuntimeState.java:391-397` |
| S9 | **Ghost-node window**: replica state changes only on membership reconcile — a dead-but-connected node stays CAUGHT_UP: minSync publishes eat 5s timeouts; reads forward to the corpse with one retry | `PartitionedStreamAccess.java:574-596` |
| S10 | **Cluster-wide floor hydration**: every node materializes every stream's all partitions (`hydrateEntry` on KV put), followers over-subscribe past budget unconditionally (WARN only) → memory O(streams×partitions×nodes); creation gates at ~12 default streams/node; 100 streams ≈ ~1.06GB native per node (8× budget) | `StreamPartitionManager.java:317-371`; floor ≈ 2.66MB/partition (`OffHeapRingBuffer.java:36-43,159`, `RetentionPolicy.java:19-20`) |

### Medium

- **S11** Batch publish misroutes keyed events — whole batch to the *first* event's partition (breaks key→partition affinity); also skips minSync and fails `PARTITION_NOT_LOCAL` instead of forwarding (`DefaultStreamPublisher.java:175-181`).
- **S12** Stream lifecycle KV drift: `reapIdleStreams` removes local-only (resurrects on restart); `destroyStream` removal commit fire-and-forget (followers allocated forever on failure) (`StreamPartitionManager.java:287-313, 468-500`).
- **S13** Replication durability stores all NOOP in production (watermarks/assignments amnesia; `rebuildFromWatermarks` no caller; failover recovery operates blind) (`AetherNode.java:2034`).
- **S14** No ownership handoff protocol — instant HRW flip on membership edge; in-flight publishes to the old owner land in an unowned ring. (HRW does minimize reshuffle volume — but what moves gets no working backfill, S2.)
- **S15** No partition-count cap in `StreamConfig` — one committed huge-partition config forces unconditional native allocation on every node (cluster-wide OOM vector, follower-side WARN only).
- **S16** `/api/events` became leader-bound in #239 (503 during churn) — availability regression noted in the 2026-06-06 handover, never ticketed.

### Performance (ranked levers)

1. **Wire `ReplicationBatcher`** (exists, tested, 100 events/1ms) — per-event replication caps owners at ~25–50K events/s vs multi-million ring capability; the single biggest lever and nearly a one-line change (`AetherNode.java:2036`).
2. **Placement-aware hydration** — only replica-set nodes materialize rings (fixes S10, the binding memory constraint).
3. **Forward-path per-event overhead** — secure-random UUID + 5s timer + CHM churn per event (`StreamForwardClient.java:155-169`) bounds remote publish ~20–50K/s/node and floods SharedScheduler.
4. **FORWARD-lane convergence** — replication, publish-forward, read-forward share one QUIC lane; 100 remote consumers on a partition ≈ up to 100K poll requests/s at the owner with no shared fan-out, HOL-blocking replication acks that gate minSync publishes.
5. **Nothing load-tests the stream data plane** — k6 targets app HTTP at 200 rps; suite 04 publishes a few hundred events via curl. All throughput numbers above are code-derived estimates; no benchmark exists.

## 3. What breaks first

| Scenario | First failure |
|---|---|
| 100 streams (defaults) | Native memory: creation gate ~12 streams; follower over-subscription → ~1.06GB/node, OOM risk |
| 1K partitions | Floor allocation (2.66GB at default retention; ~460 minimal partitions max under 128MB) |
| 10K events/s | Nothing breaks, but per-event replication burns 20K msgs+acks/s at RF=2; wall ≈ 25–50K events/s/owner |
| 100 consumers / 1 partition | Owner read fan-out (per-consumer polling, no sharing); catch-up past ring head returns nothing (#248) |
| Node kill | S1/S2/S3 compound: acked-data loss + suffix-only replica promoted CAUGHT_UP + reads served from it |

## 4. Untested failure scenarios (none covered by unit or integration today)

Kill mid-publish / acked-then-killed; replica failover read correctness; dropped/reordered replication → offset skew; fresh-replica backfill end-to-end; slow consumer racing wrap / CursorExpired recovery; cursor position across restart/redeploy; duplicate-ack minSync; ack-before-await race; budget exhaustion + follower over-subscription; ghost-node replica targeting; STRONG creation (fails instantly today).

## 5. Prior-analysis delta (streaming-performance-analysis.md, 2026-04-10)

Still hold: QUIC forward latency, push notification, sync-rep ack API, adaptive polling, consumer-group registry, partial zero-copy read. **No longer hold:** "batch replication complete" (unwired); "STRONG complete" (dead, all its latency/throughput rows describe nonexistent behavior); "tiered read complete" (no segments ever sealed); §7 memory model (HEAD reality is index-dominated eager floor + lazy growth); "configurable budget" (env-var only). **Missed entirely:** the O(streams×partitions×nodes) follower hydration — the actual binding memory constraint.

## 6. Recommended priorities

1. **Replication correctness trio (S1+S2+S5)** — offset-verified apply (carry/check `fromOffset`, reject+repair on mismatch), backfill gate fixed to fire on assignment (no-op if source empty), identity-tracked acks with self excluded. Without these, replication is not a durability mechanism; it is a rumor.
2. **Wire the batcher** (perf lever #1, trivial).
3. **Cursor durability decision** — cursors are small authored facts; KV-checkpointing them (per the durability split principle) avoids waiting on #248/#249; or wire `CursorStore` once a durable tier exists. Fix `replaceRef` atomicity either way.
4. **Placement-aware hydration** (S10) — also the natural seam for #241 community-scoped placement later.
5. **Honest semantics**: state at-most-once/at-least-once per path in streaming-spec; **decide STRONG** — kill the dead consensus path + spec claims, or redesign as batched consensus publish post-#248 (per-event proposals can never work).
6. **Suite 04 hardening + deterministic failure tests** for the §4 list (same pattern as suite-14 ticket #254).
7. Ticket S16 (`/api/events` leader-bound 503) — known, unticketed.

## 7. Tickets filed (2026-06-11, all rc1)

- **#260** (bug) — S1: offset-verified replication apply (divergence detection/repair)
- **#261** (bug) — S2: fresh-replica backfill gate inverted + premature CAUGHT_UP promotion
- **#262** (bug) — S3+S5: minSyncReplicas accounting (identity acks, self-exclusion, ack race, app-path wiring)
- **#263** — wire ReplicationBatcher (perf lever #1) + first data-plane benchmark
- **#264** — S6: durable consumer cursors (KV-checkpointing direction)
- **#265** — S10+S15: placement-aware hydration + partition-count cap
- **#266** (bug) — S11: batch publish misroutes keyed events
- **#267** (bug) — S16: /api/events leader-bound 503 regression

Documented, deliberately NOT ticketed yet (decide after the trio lands): S7 consumer-runtime wiring (depends on cursor + DLQ durability decisions), S8 CursorExpired recovery policy, S9 ghost-node replica selection (largely addressed by overhaul Waves 4–8 reachability work), S12 lifecycle KV drift (relates #215), S13 NOOP watermark stores, S14 ownership handoff, STRONG kill-or-redesign decision, suite-04 hardening (#254 pattern).

## 8. Relation to existing tickets

#248/#249 (seal path + durable tier) are prerequisites for: catch-up reads, STRONG, DLQ durability, cursor-store-in-storage. #165 (production-readiness epic) covers emission gaps + replay scope but **not** the replication-correctness trio — those are new findings. #212 (SSE/WS tail), #205 (stream RBAC — reads currently ungated), #215 (metadata replication remainder; relates to S12) unchanged.
