# The Failure Almanac

> **The operator's catalog of every known Aether failure mode.** For each: what you see, where you see it, what the runtime does automatically, how long that should take, what is degraded or at risk meanwhile, and when to intervene. Every recovery budget is anchored to the chaos/integration test that asserts it — or explicitly marked pending where the number is unmeasured.

This page is the **operator view**. The *guarantees* behind these behaviors live in [`guarantees.md`](guarantees.md) and [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md); the *scope boundaries* live in [`known-limitations.md`](known-limitations.md); the *step-by-step procedures* live in the [Incident Response runbook](../operators/runbooks/incident-response.md). The Almanac does not restate those — it assembles the failure modes across features and tells you how to act. It complements `resilience-operability-principles.md` P2 (per-failure-mode budgets, not aggregate MTTR) and P6 (failure behavior is documentation).

**Maintenance rule** (same triad discipline as REST→CLI→docs): a new chaos scenario or a new operator-facing near-miss event **without an Almanac row is incomplete**.

## How to read an entry

| Field | Meaning |
|-------|---------|
| **Symptom** | What an operator observes when this happens |
| **Detection surface** | The event, API endpoint, CLI command, metric, or status field that shows it |
| **Automatic response** | What the runtime does on its own to recover |
| **Budget** | How long the automatic response should take (asserted by a test, or marked pending) |
| **Degraded / at risk** | What is unavailable or degraded meanwhile, and what data (if any) is at risk |
| **Operator action** | When and how to intervene — often "none, if within budget" |
| **Proof anchor** | The executable test that proves the behavior, or a pending-validation marker |

## Operator surfaces — where to look

Failure modes surface through a small, fixed set of observables. Learn these once.

| Surface | Exposes | CLI |
|---------|---------|-----|
| `GET /api/events` | The `ClusterEvent` stream — `NODE_FAILED` (CRITICAL), `NODE_LEFT` (WARNING), `LEADER_LOST` / `LEADER_ELECTED`, `QUORUM_LOST` (CRITICAL) / `QUORUM_ESTABLISHED`, `SELF_DRAIN_INITIATED` (WARNING), `DEPARTURE_PUSH_INCOMPLETE`, `SCALE_CAPPED`, `STREAM_MEMORY_EXCEEDED` (`ClusterEvent.java`, 33 variants) | `aether events` |
| `GET /api/health` | `status` (healthy / degraded / unhealthy), `quorum` (true/false), `nodeCount`, `sliceCount` | `aether health`, `aether nodes health` |
| `GET /api/nodes/lifecycle/<id>` | Per-node lifecycle state (ON_DUTY, DRAINING, DECOMMISSIONED, …) | `aether nodes lifecycle` |
| `aether cluster membership` | Per-peer SWIM FSM state + the quorum-loss self-drain signal | (CLI) |
| `GET /api/v1/streams/{namespace}/{stream}/{version}/replicas/{partition}` | `hrwOwner`, `servedByOwner`, `replicas[].state`, `confirmedOffset` | (stream failover diagnosis) |
| Metrics (Micrometer) | `aether.streams.memory.used.bytes` / `.used.ratio` — the only dedicated failure-adjacent gauges today | scrape endpoint |

> **There is no aggregate MTTR gauge, and there are no dedicated failure-rate metrics** — this is deliberate (`resilience-operability-principles.md` P2). Budgets, event counts, and the surfaces above are the honest signals.

## Summary index

| Failure mode | Recovery budget | Proof |
|--------------|-----------------|-------|
| Non-leader node failure | detect ~8 s quiescent / ≤60 s under load; auto-heal to N ≤180 s | 02-chaos C3/C6 |
| Leader failure | re-election ≤150 s (transient zero-leader ≈19 s) | 02-chaos C4 |
| Quorum loss / minority partition | minority self-drain ≤45 s; recovery ≤60 s | 02-chaos C12–C16 |
| Slow detection under sustained load | ~11 s nominal → up to ~80 s under load | guarantees.md §3 · **pending tighter bound (#94)** |
| Provisioning stall under churn | fix landed; budget cloud-pending | **pending validation (#362)** |
| Per-node deployment failure (`ALL_OR_NOTHING` rollback) | n/a — permanent until cause is fixed | `DurableEntityForgeTest` |
| Stream owner failover (RF ≥ 2) | owner view ≤180 s; complete history ≤120 s | 02-chaos C17–C20 |
| Stream owner failover (RF = 1, default) | none until owner returns | guarantees.md §4 |
| Core network partition | eviction ~3 s; heal to N ≤30 s | 12-network C9/C10 |
| QUIC connection churn | missing-peer reconcile 5–60 s | 12-network connectedPeerCount |
| Full-cluster restart | derived state rebuilds; snapshot-only KV | guarantees.md §1–§2 · **partial (#349)** |
| DHT / artifact loss under churn | mitigation only | **pending full fix (#420 / #349)** |
| Pub/sub message loss | none (at-most-once) | guarantees.md §5 |

## Cluster membership and leader election

### Non-leader node failure

- **Symptom:** a node stops responding; `nodeCount` on `/api/health` drops by one; slices it hosted re-route to peers.
- **Detection surface:** `/api/events` emits `NODE_FAILED` (CRITICAL, from the SWIM FSM DEAD edge) or `NODE_LEFT` (WARNING, graceful); `/api/nodes/lifecycle/<id>` transitions the node to DECOMMISSIONED; `aether cluster membership` shows the peer FAULTY.
- **Automatic response:** SWIM detects the death → the leader writes DECOMMISSIONED to the KV → CTM auto-heal provisions a replacement to restore the configured member count N.
- **Budget:** detection ~8 s when the cluster is quiescent, ≤60 s under sustained load (02-chaos C3); auto-heal back to exactly N ≤180 s (C6).
- **Degraded / at risk:** reduced capacity until the replacement is ACTIVE. **No data loss** — KV state is quorum-durable on the surviving majority.
- **Operator action:** none if auto-heal is enabled and within budget. If no replacement appears, check cloud credentials / provisioning quota.
- **Proof anchor:** `02-chaos/test-kill-node.sh` (C3, C6).

### Leader failure

- **Symptom:** a brief control-plane pause — deploys, scaling, and auto-heal stall for a few seconds; application traffic on the majority is unaffected.
- **Detection surface:** `/api/events` emits `LEADER_LOST` then `LEADER_ELECTED`; `aether status` shows the new leader id.
- **Automatic response:** deterministic re-election (leader = first node in sorted topology, `viewSequence`-fenced so two committing leaders are structurally impossible). Leaderless Rabia consensus keeps committing; only leader-pinned coordination pauses.
- **Budget:** new leader elected ≤150 s worst case (02-chaos C4); a transient zero-leader gap self-heals in ≈19 s (guarantees.md §3). **The 150 s figure is a `[CONTRACT-GAP]`** — asserted by the test, not pinned by a canonical election spec.
- **Degraded / at risk:** deploy/scale/auto-heal paused during re-election; no data at risk.
- **Operator action:** none. If no leader after the budget, treat it as a quorum problem (below).
- **Proof anchor:** `02-chaos/test-kill-leader.sh` (C4); `02z-killonly`.

### Quorum loss / minority partition (self-drain)

- **Symptom:** nodes on the minority side reject writes and then exit; `/api/health` reports `quorum:false` on that side.
- **Detection surface:** `/api/events` emits `QUORUM_LOST` (CRITICAL) and `SELF_DRAIN_INITIATED` (WARNING, `reason` ∈ `sustained-below-quorum` | `quorum-disappeared` | `rabia-paused`); the minority JVMs exit with **code 2** (distinguishes self-drain from clean=0 / SIGKILL=137); `aether cluster membership` carries the self-drain signal.
- **Automatic response:** a node that cannot reach `core/2 + 1` peers self-terminates via `Runtime.halt(2)` after the split timeout; the majority continues serving. Drained nodes require external restart / CTM reprovision.
- **Budget:** self-drain exit ≤45 s (8 s threshold + 30 s grace + 7 s headroom; wall-clock ~38 s cloud-proven) (C12); post-restart recovery to N healthy cores ≤60 s (C16). The self-drain state machine is a `[CONTRACT-GAP]` (code-only; guarded by `SelfDrainCoordinatorTest`).
- **Degraded / at risk:** the minority is unavailable **by design** (consistency over availability). Acked data on the majority is safe; the minority's in-flight uncommitted writes are rejected, not lost-then-served.
- **Operator action:** restart or reprovision the drained minority nodes once the partition cause is fixed. See the partition contract in [14-consistency-and-partitions.md](../architecture/14-consistency-and-partitions.md).
- **Proof anchor:** `02-chaos/test-self-drain-quorum-loss.sh` (C12–C16); `SelfDrainCoordinatorTest`.

### Slow failure detection under sustained load

- **Symptom:** a genuinely dead node's `NODE_FAILED` event lags the death — up to ~80 s under sustained local trouble, versus ~11 s nominal.
- **Detection surface:** `/api/events` `NODE_FAILED` (delayed).
- **Automatic response:** SWIM's local-health multiplier stretches the suspect timeout under load (×8) to avoid false positives; a 15 s co-confirmation backstop bounds full eviction.
- **Budget:** ~11 s nominal (probe 0.8 s + suspect 10 s), up to ~80 s under load. **This is detection *latency*, not a correctness gap** — routing intersects targets with the live set so traffic is not forwarded to an undetected-dead node indefinitely.
- **Operator action:** none. Persistent long detection under load is a known timing sensitivity.
- **Proof anchor:** guarantees.md §3. **Pending** a tighter asserted bound under load ([#94](https://github.com/pragmaticalabs/pragmatica/issues/94), open).

## Consensus and provisioning

### Provisioning stall under heavy reconciler load

- **Symptom:** after churn under load, auto-heal does not restore the member count promptly; `nodeCount` stays below target.
- **Detection surface:** `/api/health` `nodeCount`; `aether status`.
- **Automatic response:** a periodic reconcile re-evaluation (armed at the quorum threshold) retries provisioning; the historical permanent-paused wedge is closed.
- **Budget:** **not yet pinned** — this is a cloud-gate-class scenario.
- **Operator action:** manually reprovision if the cluster stays under target well beyond the ~180 s auto-heal budget.
- **Proof anchor:** the fix landed ([#336](https://github.com/pragmaticalabs/pragmatica/issues/336), **closed** — OBSERVED birth-state + missed-pong). The recovery budget is **pending remote/cloud validation** ([#362](https://github.com/pragmaticalabs/pragmatica/issues/362), open).

## Deployment

### Per-node deployment failure under `ALL_OR_NOTHING` rollback

- **Symptom:** `POST /api/blueprints` returned `"status": "applied"`, but the blueprint never reaches `DEPLOYED`. `GET /api/slices/status` shows **nothing** for the failing artifact — not a FAILED entry — because the deploy was rolled back, not partially applied; `GET /api/blueprints/status/{id}` instead reports the durable terminal outcome (`overallStatus: FAILED` or `ROLLED_BACK`, `cause`, `failingSlices`) rather than agreeing with that empty live snapshot (#759).
- **Detection surface:** `GET /api/events` — one `DEPLOYMENT_FAILED` event per node that attempted and failed the slice load (`details.nodeId`, `details.reason`); a blueprint targeting N nodes that fails deterministically on all of them produces N events, not one. `GET /api/blueprints/status/{id}` now carries this too — `cause` and `failingSlices` (full artifact coordinates) on the same request that used to 404 (#759) — but `GET /api/slices/status` still shows nothing for the rolled-back artifact, so the event feed remains the only surface with per-node detail.
- **Automatic response:** under the default `ALL_OR_NOTHING` atomicity (`02-deployment.md` §Deployment Atomicity), a deterministic slice-load failure on any allocated node rolls back the entire blueprint and removes the deployment-map entry for that artifact — the same map `GET /api/slices/status` reads from, so it goes back to empty/PENDING rather than ever showing FAILED. `GET /api/blueprints/status/{id}` instead answers from the durable outcome key written at that same rollback, so it does not revert to empty (#759). The cluster-event stream is append-only and is not retracted by the rollback, so the `DEPLOYMENT_FAILED` record survives.
- **Degraded / at risk:** the blueprint's slices are not running anywhere; no partial deployment is left behind (that is the point of `ALL_OR_NOTHING`). No data at risk.
- **Operator action:** if a deploy stays PENDING past its expected time, poll `GET /api/blueprints/status/{id}` for the terminal outcome (`cause`, `failingSlices`) before falling back to `GET /api/events` for the per-node `DEPLOYMENT_FAILED` detail (#759); `GET /api/slices/status` will not show a failure, only an empty/PENDING map. Fix the cause named in `details.reason` (or the status endpoint's `cause`) and redeploy.
- **Proof anchor:** `DurableEntityForgeTest` (forge-tests) — `failIfSliceFailed` fails fast on the `DEPLOYMENT_FAILED` event for a deliberately un-bundled `DurableEntity` resource provider, reproducing the "no resource provider registered for resource type" case end to end; fast-red in ~35 s (vs. the 240 s Awaitility timeout it replaced) when the provider is absent, unchanged green when present.

## Streams

### Stream owner failover — RF ≥ 2 (`min-sync-replicas ≥ 2`)

- **Symptom:** a partition's owner dies; a brief read unavailability, then a caught-up replica serves the complete history.
- **Detection surface:** `GET /api/v1/streams/{namespace}/{stream}/{version}/replicas/{partition}` — `hrwOwner` changes, `servedByOwner` returns true on the new owner, `replicas[].state` shows a CAUGHT_UP replica.
- **Automatic response:** HRW ownership reseats to a CAUGHT_UP replica; the epoch fence rejects the deposed owner's late appends; the new owner serves **every** pre-kill event in order.
- **Budget:** new owner-authoritative view ≤180 s; complete history (all N events) settled ≤120 s (02-chaos C18/C19/C20).
- **Degraded / at risk:** brief read unavailability during reseat. **No acked data at risk** at `min-sync-replicas ≥ 2` (the #445 fix closed the live-vs-reconciled divergence that previously dropped acked events).
- **Operator action:** none.
- **Proof anchor:** `02-chaos/test-stream-replica-failover.sh` (C17–C20); `PartitionBackfillTest`.

### Stream owner failover — RF = 1 (default)

- **Symptom:** after the owner dies, consumers read **empty** until the original owner restarts.
- **Detection surface:** `/api/streams/replicas/...` shows no CAUGHT_UP non-owner replica.
- **Automatic response:** none — no other replica holds the data; HRW may move ownership to a peer with no copy.
- **Budget:** n/a — data is unavailable until the owner returns.
- **Degraded / at risk:** un-replicated appends are **crash-durable on the dead owner's disk** (WAL fsync) but **not served** during the outage. Not lost, but unavailable.
- **Operator action:** configure `min-sync-replicas ≥ 2` for failover safety; restart the owner to recover its partitions. See [known-limitations.md](known-limitations.md) (RF=1 one-disk-deep).
- **Proof anchor:** guarantees.md §4 (RF=1 empty-after-failover); Forge `StreamCrashDurabilityTest` (per-owner WAL survives restart).

### Fresh-stream first-publish race

- **Symptom:** a concurrent first publish to a brand-new stream returns a transient 503/500.
- **Automatic response:** owner-side lazy materialization (`ensureStreamMaterialized`) + a bounded 3×150 ms retry (`PublishForwardResponse.retryable`).
- **Budget:** retries resolve within ~450 ms.
- **Operator action:** application-level retry on publish (already the recommended pattern for pub/sub-class calls).
- **Proof anchor:** fixed; incident ledger 2026-07-08.

## Storage durability

### Full-cluster restart (in-memory state)

- **Symptom:** after a **simultaneous** full-cluster restart, KV + DHT + un-sealed stream state since the last snapshot is gone.
- **Automatic response:** KV restores from its most recent lifecycle snapshot (`GitBackedPersistence`, default in-memory); DHT system maps rebuild as nodes re-register their slices/routes/endpoints on activation.
- **Budget:** n/a (bounded by restart + re-registration).
- **Degraded / at risk:** everything since the last lifecycle snapshot (KV); all DHT system-map state (self-heals by rebuild); un-sealed stream data. A **rolling** restart is safe — this applies only to losing the whole cluster at once.
- **Operator action:** treat the rc-series as non-durable across a full-cluster crash; durable tiers are tracked under [#349](https://github.com/pragmaticalabs/pragmatica/issues/349) / #383.
- **Proof anchor:** guarantees.md §1–§2; [known-limitations.md](known-limitations.md). Forge `StreamCrashDurabilityTest` proves a **single owner's** WAL survives restart. **Partial** — full durable persistence pending #349.

### DHT / artifact loss under churn

- **Symptom:** an artifact or content marker 404s across **all** nodes after rapid membership churn (e.g. 5→7→5).
- **Detection surface:** GET returns 404; **no operator event fires** for this today.
- **Automatic response:** departing-node push (rc2 mitigation) + rebalance on departure. This is a mitigation, not a full fix — there is no join-migration, no read-repair, and no hinted-handoff, so churn can drop below the replica floor faster than repair restores it.
- **Budget:** n/a — mitigation reduces but does not eliminate the window.
- **Degraded / at risk:** DHT-hosted artifacts, content-blocks, and stream segments can be **lost** under churn (cloud-proven). A single lost 64 KB chunk invalidates the whole artifact.
- **Operator action:** avoid rapid successive membership changes during deploys; re-upload artifacts if a 404 appears. Full fix targeted rc3/GA.
- **Proof anchor:** **Pending full fix** — [#420](https://github.com/pragmaticalabs/pragmatica/issues/420) (real loss cloud-proven), durable tiers #349.

### DHT system-map staleness

- **Symptom:** routing/endpoint reads briefly disagree across nodes.
- **Automatic response:** eventual convergence; the maps are derived, self-healing state re-registered on activation.
- **Budget:** eventual.
- **Degraded / at risk:** stale routing reads for a short window; nothing durable at risk (derived state).
- **Operator action:** none. Background in [09-storage.md](../architecture/09-storage.md) and [#384](https://github.com/pragmaticalabs/pragmatica/issues/384).
- **Proof anchor:** guarantees.md §2.

## Network and transport

### Core network partition

- **Symptom:** the minority side loses quorum and self-drains; the majority continues.
- **Detection surface:** `/api/events` `QUORUM_LOST`; `aether cluster membership`.
- **Automatic response:** identical to quorum-loss self-drain; on heal, CTM re-provisions to N.
- **Budget:** prompt eviction ~3 s on a dual-signal partition; heal to N ON_DUTY ≤30 s (12-network C10); post-event convergence window 180 s. **SWIM's 15 s faulty-detection budget is a `[CONTRACT-GAP]`** — demoted to a warning, accepted in the [16 s, 60 s] band.
- **Degraded / at risk:** minority unavailable (by design); majority serves.
- **Operator action:** repair the partition; the drained side restarts and rejoins.
- **Proof anchor:** `12-network/test-partition-quorum-gate.sh` (C9/C10).

### QUIC connection churn

- **Symptom:** `connectedPeerCount` sits below expected; a peer is stuck SUSPECTED; a replacement never reaches READY.
- **Detection surface:** `connectedPeerCount` (cluster topology), `aether cluster membership` (SUSPECTED peer).
- **Automatic response:** acceptor adopt-newer + dialer close-future sweep drop zombie connections; a periodic missing-peer reconciler (5 s tick, 5–60 s jittered backoff) redials; a 60 s `swimHints` TTL lets sticky-SUSPECTED self-heal.
- **Budget:** reconcile within 5–60 s.
- **Degraded / at risk:** transient mesh under-connectivity; no data at risk.
- **Operator action:** usually self-heals; if `connectedPeerCount` stays low past ~60 s, restart the isolated node.
- **Proof anchor:** 12-network `connectedPeerCount` contracts; incident ledger #131.

## Pub/sub, scaling, and resource pressure

### Pub/sub message loss

- **Symptom:** a subscriber that is down at publish time misses the message permanently; the publisher sees success.
- **Detection surface:** **none** — `topic.publish` is at-most-once and returns success even when nothing is delivered.
- **Automatic response:** none (best-effort, no retry, no persistence, no dedup).
- **Degraded / at risk:** any message to a momentarily-absent subscriber.
- **Operator action:** use **durable streams**, not pub/sub, for delivery-critical paths; add application-level acknowledgment where needed.
- **Proof anchor:** guarantees.md §5.

### Stream memory pressure

- **Symptom:** a stream approaches or exceeds its memory budget.
- **Detection surface:** `/api/events` `STREAM_MEMORY_EXCEEDED`; metric `aether.streams.memory.used.ratio`.
- **Automatic response:** the off-heap budget accounting applies back-pressure per policy.
- **Operator action:** raise `STREAM_MAX_MEMORY_BYTES` (default 128 MB) or scale out.
- **Proof anchor:** feature-catalog row 181; `ManagementServer` memory gauges.

### Scale capped

- **Symptom:** a slice wants more instances but is held at its configured maximum.
- **Detection surface:** `/api/events` `SCALE_CAPPED`; the per-slice scaling snapshot (#425).
- **Automatic response:** none — the `maxInstances` bound is respected by design.
- **Operator action:** raise the slice's `max` if capacity allows (see [08-scaling.md](../architecture/08-scaling.md)).
- **Proof anchor:** `ScalingEvent` (#425).

## Honest gaps — failure modes not yet anchored to an executable proof

Per `resilience-operability-principles.md` P6, the gaps are documentation too. These failure modes are real or designed, but their operator observability and/or recovery budget is **not yet proven by a test** — do not rely on them until the marker clears.

- **Near-miss telemetry — `PROMOTION_GAP`, `CURSOR_GAP`, `DLQ_STALL`.** These "degraded-but-recovered" signals are **spec-only** (durable-pubsub-spec, streaming-spec, principles P4). **No code emits them today**, and the proposed surfaces (`GET /api/topics/{topic}/groups`, `.../dlq`) are unimplemented. Until wired, a rising cursor gap or a DLQ stall is **not operator-visible**. *Scheduled, not orphaned:* [#436](https://github.com/pragmaticalabs/pragmatica/issues/436) (consumer-cursor lag metric + backlog triggers) delivers the `CURSOR_GAP`/DLQ-adjacent signals, and [#416](https://github.com/pragmaticalabs/pragmatica/issues/416) (SLI catalog + black-box probe) productizes the operator surface.
- **Multi-community / hierarchical failure modes** — a worker community partitioned from the core dissolves and drains (the contract in [14-consistency-and-partitions.md](../architecture/14-consistency-and-partitions.md)). Proven today only at the single-tier core. *Pending validation ([#367](https://github.com/pragmaticalabs/pragmatica/issues/367)).*
- **Provisioning stall under load** — the fix landed (**#336 closed**: OBSERVED birth-state + missed-pong). The recovery budget is not yet asserted; remote/cloud validation is tracked by [#362](https://github.com/pragmaticalabs/pragmatica/issues/362) (open). *Pending cloud validation.*
- **Failure-detection latency under sustained load (#94)** — no tight bound is asserted; the ~80 s figure is observed, not a contract. *Pending tighter bound.*
- **DHT durability under churn (#420)** — rc2 mitigation only; real loss is cloud-proven. *Pending full fix (#349, rc3/GA).*
- **Leader re-election (150 s) and SWIM faulty-detection (15 s)** — asserted in tests but `[CONTRACT-GAP]`: no canonical spec pins these numbers, and the 15 s SWIM budget is demoted to a warning in 12-network. *Pending spec pin.*

## Related Documents

- [../operators/runbooks/incident-response.md](../operators/runbooks/incident-response.md) - Step-by-step incident procedures (the how; this page is the what)
- [guarantees.md](guarantees.md) - Authoritative per-operation guarantees behind these behaviors
- [../architecture/14-consistency-and-partitions.md](../architecture/14-consistency-and-partitions.md) - The partition contract the membership/quorum modes rest on
- [known-limitations.md](known-limitations.md) - Deliberate scope boundaries (single source for scope)
- [../architecture/resilience-operability-principles.md](../architecture/resilience-operability-principles.md) - P2 (per-mode budgets) and P6 (failure behavior is documentation)
- [feature-catalog.md](feature-catalog.md) - Feature inventory with Partial/Planned gaps
