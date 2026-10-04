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
| `GET /api/events` | The `ClusterEvent` stream — `NODE_FAILED` (CRITICAL), `NODE_LEFT` (WARNING), `LEADER_LOST` / `LEADER_ELECTED`, `QUORUM_LOST` (CRITICAL) / `QUORUM_ESTABLISHED`, `SELF_DRAIN_INITIATED` (WARNING), `DEPARTURE_PUSH_INCOMPLETE`, `SCALE_CAPPED`, `STREAM_MEMORY_EXCEEDED`, `OPERATOR_WARNING` (identified by `details.code`) (`ClusterEvent.java`, 35 sealed variants) | `aether events` |
| `GET /api/v1/health` | `status` (healthy / degraded / unhealthy), `quorum` (true/false), `nodeCount`, `sliceCount` | `aether health`, `aether nodes health` |
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
| `BEST_EFFORT` slice failure (durable `PARTIAL`) | n/a — durable until redeployed | `BlueprintStatusAggregationTest` |
| Stream owner failover (`confirmation_factor ≥ 2`) | not measured since #1555 fixed ownership moving (#1550); ≤180 s / ≤120 s measured at RF=2 before #1547 | Forge `StreamOwnerFailoverTest`, `StreamDefaultRfOwnerReplacementTest`; 02-chaos C17–C20 (RF=2) |
| Stream owner loss (RF = 3, default `confirmation_factor` 2) | none today — ownership stays on the dead owner; replicated history held on survivors | `StreamDefaultRfOwnerReplacementTest` |
| Core network partition | eviction ~3 s; heal to N ≤30 s | 12-network C9/C10 |
| QUIC connection churn | missing-peer reconcile 5–60 s | 12-network connectedPeerCount |
| Full-cluster restart | derived state rebuilds; snapshot-only KV | guarantees.md §1–§2 · **partial (#349)** |
| DHT / artifact loss under churn | mitigation only | **pending full fix (#420 / #349)** |
| Pub/sub message loss | none (at-most-once) | guarantees.md §5 |

## Cluster membership and leader election

### Non-leader node failure

- **Symptom:** a node stops responding; `nodeCount` on `/api/v1/health` drops by one; slices it hosted re-route to peers.
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

- **Symptom:** nodes on the minority side reject writes and then exit; `/api/v1/health` reports `quorum:false` on that side.
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
- **Detection surface:** `/api/v1/health` `nodeCount`; `aether status`.
- **Automatic response:** a periodic reconcile re-evaluation (armed at the quorum threshold) retries provisioning; the historical permanent-paused wedge is closed.
- **Budget:** **not yet pinned** — this is a cloud-gate-class scenario.
- **Operator action:** manually reprovision if the cluster stays under target well beyond the ~180 s auto-heal budget.
- **Proof anchor:** the fix landed ([#336](https://github.com/pragmaticalabs/pragmatica/issues/336), **closed** — OBSERVED birth-state + missed-pong). The recovery budget is **pending remote/cloud validation** ([#362](https://github.com/pragmaticalabs/pragmatica/issues/362), open).

## Deployment

### Per-node deployment failure under `ALL_OR_NOTHING` rollback

- **Symptom:** `POST /api/blueprints` returned `"status": "applied"`, but the blueprint never reaches `DEPLOYED`. `GET /api/slices/status` shows **nothing** for the failing artifact — not a FAILED entry — because the deploy was rolled back, not partially applied; `GET /api/v1/blueprints/status/{id}` instead reports the durable terminal outcome (`overallStatus: FAILED` or `ROLLED_BACK`, `cause`, `failingSlices`) rather than agreeing with that empty live snapshot (#759).
- **Detection surface:** `GET /api/events` — one `DEPLOYMENT_FAILED` event per node that attempted and failed the slice load (`details.nodeId`, `details.reason`); a blueprint targeting N nodes that fails deterministically on all of them produces N events, not one. `GET /api/v1/blueprints/status/{id}` now carries this too — `cause` and `failingSlices` (full artifact coordinates) on the same request that used to 404 (#759) — but `GET /api/slices/status` still shows nothing for the rolled-back artifact, so the event feed remains the only surface with per-node detail.
- **Automatic response:** under the default `ALL_OR_NOTHING` atomicity (`02-deployment.md` §Deployment Atomicity), a deterministic slice-load failure on any allocated node rolls back the entire blueprint and removes the deployment-map entry for that artifact — the same map `GET /api/slices/status` reads from, so it goes back to empty/PENDING rather than ever showing FAILED. `GET /api/v1/blueprints/status/{id}` instead answers from the durable outcome key written at that same rollback, so it does not revert to empty (#759). The cluster-event stream is append-only and is not retracted by the rollback, so the `DEPLOYMENT_FAILED` record survives.
- **Degraded / at risk:** the blueprint's slices are not running anywhere; no partial deployment is left behind (that is the point of `ALL_OR_NOTHING`). No data at risk.
- **Operator action:** if a deploy stays PENDING past its expected time, poll `GET /api/v1/blueprints/status/{id}` for the terminal outcome (`cause`, `failingSlices`) before falling back to `GET /api/events` for the per-node `DEPLOYMENT_FAILED` detail (#759); `GET /api/slices/status` will not show a failure, only an empty/PENDING map. Fix the cause named in `details.reason` (or the status endpoint's `cause`) and redeploy.
- **Proof anchor:** `DurableEntityForgeTest` (forge-tests) — `failIfSliceFailed` fails fast on the `DEPLOYMENT_FAILED` event for a deliberately un-bundled `DurableEntity` resource provider, reproducing the "no resource provider registered for resource type" case end to end; fast-red in ~35 s (vs. the 240 s Awaitility timeout it replaced) when the provider is absent, unchanged green when present.

### `BEST_EFFORT` slice failure (durable `PARTIAL`)

- **Symptom:** under `BEST_EFFORT` atomicity, one slice's instances fail to reach `ACTIVE` while sibling slices keep serving; `GET /api/v1/blueprints/status/{id}` reports `overallStatus: PARTIAL` — not `FAILED`, not `DEPLOYED` — and this can be the durable state the blueprint settles into rather than a transient step toward `ROLLED_BACK` (that only applies under `ALL_OR_NOTHING`, above).
- **Detection surface:** `GET /api/v1/blueprints/status/{id}` — `overallStatus: PARTIAL`, `slices[]` carrying the real per-slice `target`/`active`/`failed` counts and a `"FAILED"` per-slice status on the failing artifact; once `ClusterDeploymentState.recordBestEffortFailureOutcome` writes its terminal outcome record, `cause` and `failingSlices` populate too. `overallStatus` reports `PARTIAL` for this same slice mix in both the pre-outcome live-aggregation window and the post-outcome window, so a poller sees no status change across that transition (#759 review round 4).
- **Automatic response:** none — `BEST_EFFORT` deliberately does not roll back siblings for one slice's failure; the failed slice's instances stay `FAILED` until redeployed.
- **Degraded / at risk:** the failed slice is unavailable; every sibling slice keeps serving normally. No data at risk.
- **Operator action:** redeploy the failed slice (a scoped republish of just that artifact, or a full blueprint republish) once the underlying cause (`cause` / `failingSlices` on the status response, or the per-node `DEPLOYMENT_FAILED` event) is fixed.
- **Proof anchor:** `BlueprintStatusAggregationTest#statusRoute_blueprintLiveWithTerminalFailure_bestEffort_reportsPartialWithSliceCounts` (post-outcome window) and `#statusRoute_reportsPartial_whenOneSliceFailedAndSiblingFullyDeployed` (pre-outcome live-aggregation window, same `PARTIAL` value) — both `aether/node`.

## Streams

### Stream owner failover — `confirmation_factor ≥ 2`

- **Symptom (intended):** a partition's owner dies; a brief read unavailability, then a caught-up replica serves the complete history. Until #1555 ownership did not leave a killed owner (#1550); since #1555 stream placement reads the live members, so it moves to a surviving replica `[verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamOwnerFailoverTest.java — RF 3 / CF 2, graceful kill]`. The budget below is the RF=2 behaviour 02-chaos measured before #1547; none has been measured since #1555.
- **Detection surface:** `GET /api/v1/streams/{namespace}/{stream}/{version}/replicas/{partition}` — `hrwOwner` changes, `servedByOwner` returns true on the new owner, `replicas[].state` shows a CAUGHT_UP replica.
- **Automatic response (intended, `[design intent — unverified]` at RF=3):** HRW ownership reseats to a CAUGHT_UP replica; the epoch fence rejects the deposed owner's late appends; the new owner serves **every** pre-kill event in order.
- **Budget:** measured at RF=2 before #1547 — new owner-authoritative view ≤180 s; complete history settled ≤120 s (02-chaos C18/C19/C20). Not re-measured since #1555.
- **Degraded / at risk:** brief read unavailability during reseat. **No acked data at risk** at `confirmation_factor = replication_factor` (the #445 fix closed the live-vs-reconciled divergence that previously dropped acked events). At `2 ≤ CF < RF` an acked event is on the owner and `CF − 1` peers; #1555's promotion gate catches the new owner up from the highest-head live member before it serves, so every acked event held by a live replica is recovered `[design intent — unverified]`. The 02-chaos proof below ran at RF=2 with the write-ack floor equal to RF (then named `min-sync-replicas`, renamed `confirmation_factor` by #1564); its fixture is RF=3 with CF 2 since #1547, and a blueprint's declared factor reaches the runtime since #1549.
- **Operator action:** none for the ownership move (automatic since #1555). A lost core's replica slot is refilled by a replacement joining under a fresh identity once the voter install adds it (#1732) `[verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamConfirmationEqualsFactorAvailabilityTest.java — Ember, 3 nodes, graceful stop of a NON-owner core; green at 44367f625, RED at its base b55a359e1; not run on the cloud or under SIGKILL]` `[unverified: when the lost core was the partition's OWNER — StreamDefaultRfOwnerReplacementTest kills the owner and joins a replacement but does not assert the replacement's placement]`; until then the partition runs one replica short.
- **Proof anchor:** `02-chaos/test-stream-replica-failover.sh` (C17–C20); `PartitionBackfillTest`.

### Stream owner loss — RF = 3, default `confirmation_factor` 2 (the default stream)

- **Symptom (before #1555; #1550):** a partition's owner is terminally removed and the partition stops being served: every survivor keeps resolving the dead node as owner, and forwarded reads fail with "Stream partition is not owned by this node". Fixed by #1555: ownership moves to a surviving replica.
- **Detection surface:** `GET /api/v1/streams/{namespace}/{stream}/{version}/replicas/{partition}` — `hrwOwner` changes and the new owner's view shows the replicas' `confirmedOffset`.
- **Automatic response:** HRW hands ownership to the next-ranked survivor, which is already a replica, and it serves every replicated event `[verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamDefaultRfOwnerReplacementTest.java — 2/2 on cloudbb-d1 at 53b168eb0, 2026-09-30, 4 <testcase> 0 red; logs and XML archived in oss s29/s6-1735-recite-forge-53b168eb0.tgz]`. The replacement core is placed into the partition's replica set once the voter install adds it (#1732) `[mechanism: placement members are the installed voters narrowed to the FSM's counted core, and the voter install triggers the reconcile, whichever core was lost]` `[unverified: when the lost core was the partition's OWNER — StreamDefaultRfOwnerReplacementTest kills the owner and joins a replacement but does not assert the replacement's placement]`; the non-owner case is `[verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamConfirmationEqualsFactorAvailabilityTest.java — Ember, 3 nodes, graceful stop of a NON-owner core; green at 44367f625, RED at its base b55a359e1; not run on the cloud or under SIGKILL]`.
- **Budget:** not measured.
- **Degraded / at risk:** since #1564 the default `confirmation_factor` is 2, so an acked event is on the owner and one peer before the ack and the owner's death alone does not lose it `[design intent — unverified]`. Before #1564 the default acked on the owner's WAL fsync alone; that is still the case for a stream declaring `confirmation_factor = 1` (warned at declaration), and because a terminally removed owner's WAL is never read again, events acked but not yet replicated when such an owner dies are **lost**, not merely unavailable. Everything that reached the replicas is held on the survivors and served by the new owner `[verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamDefaultRfOwnerReplacementTest.java`]`.
- **Operator action:** none for the ownership move. Replacing the lost core refills the partition's replica set once the voter install adds it (#1732) `[unverified: when the lost core was the partition's OWNER — StreamDefaultRfOwnerReplacementTest kills the owner and joins a replacement but does not assert the replacement's placement]`. To make every acked event reach every replica before the ack, declare `confirmation_factor = replication_factor` — at the cost that losing any one replica refuses writes (see [known-limitations.md](known-limitations.md)).
- **Proof anchor:** Forge `StreamDefaultRfOwnerReplacementTest` (owner killed, replacement joins under a fresh id: two survivors hold all events, RF=1 control holds none; the new owner serves them — enabled by #1555).

### Stream partition below its confirmation factor — acknowledged publishes refused (#1883)

- **Symptom:** publishes to one stream partition fail with `NOT_ENOUGH_REPLICAS` before anything is appended, while reads of acknowledged data still work: the partition's committed in-sync set (ISR) holds fewer members than the stream's `confirmation_factor` (min-ISR = CF).
- **Detection surface:** the cluster event **`STREAM_ISR_BELOW_MINIMUM`** (severity WARNING; `details`: `stream`, `partition`, `owner`, `isr`, `fenced`, `confirmationFactor`) on `GET /api/events` / `aether events`, raised ONCE when the ISR crosses below the factor (every node derives it from the committed ownership record; the cluster-events owner publishes it, at most once if that partition has no owner at the moment); **`STREAM_ISR_RESTORED`** (INFO) once when it reaches the factor again. A commit that leaves the ISR on the same side of the factor announces nothing, so ISR churn is not an event stream.
- **Automatic response:** a member the leader's liveness view does not list leaves the ISR and is recorded as `fenced`; it is never expanded back while fenced, and the leader unfences it when it lists it live again; the owner then expands it once it has caught up to the high-water mark. A member that only lags is proposed out by the owner after `[streaming] isr_lag_max` and back in when caught up.
- **Budget:** bounded by when a replica rejoins and catches up.
- **Degraded / at risk:** no acknowledged publish is accepted (CF >= 2); nothing acknowledged is lost, and reads and the owner are unaffected. A rolling restart of the replicas of an RF = CF stream passes through this state by design (the event pair brackets it).
- **Operator action:** bring the missing replica back (`details.fenced` lists members the leader keeps out for liveness; `details.isr` the members still in sync). To keep writes available through the loss of one replica, declare `confirmation_factor` below `replication_factor` (see the stream replication policy).
- **Proof anchor:** `StreamIsrAnnouncerTest` (one event per crossing; ordinary commits, CF 1 and a fence change announce nothing), `ClusterEventAggregatorTest.streamIsrBelowMinimumAndRestored_reachTheEventStream_withTheirDetails`, `StreamFailoverAnnouncerWiringTest`, `IsrWriterMonitorFixedPointTest`.

### Stream consumer re-reads after the partition's ring was rebuilt (#1873)

- **Symptom:** a consumer group of a stream partition delivers records it already delivered (at-least-once), at offsets that now hold DIFFERENT records. The partition's owner rebuilt its ring and began a new epoch: a restart of a stream without a WAL (only sealed segments survive), a failover to a replica that held less than the old owner, or a re-created stream. Before #1873 such a consumer skipped the new records at the re-assigned offsets without any signal.
- **Detection surface:** the cluster event **`STREAM_LINEAGE_RESTARTED`** (severity INFO; `details`: `stream`, `partition`, `owner`, `oldEpoch`, `newEpoch`, `startOffset`) when an owner begins a new epoch under an unchanged owner; the node-local `OPERATOR_WARNING` **`stream-consumer-rewound`** (WARNING; `details.code`) on the node whose consumer re-seeked with a proven loss, naming the group, the partition and the proven offsets. A change of owner is `STREAM_FAILOVER_*`.
- **Automatic response:** every consumer read is checked on the node that serves it against the partition's committed epoch starts; a cursor past the start of the epoch that followed its own is refused with a typed divergence naming where the new epoch began, and the consumer re-reads from there, adopting the new epoch. A resume from a checkpoint is checked the same way (the checkpoint records the owner epoch it was read under). A consumer of an earlier life of a re-created stream is refused from the new life's first offset.
- **Budget:** one extra read round trip per divergence.
- **Degraded / at risk:** records the group processed in the replaced lineage are gone from the log at `confirmation_factor` 1 or with no WAL; the new records at those offsets are delivered, never skipped. A consumer older than the 16 epoch starts the record keeps (the record folds the dropped starts into the oldest it keeps) is admitted only at or below that offset and otherwise resumes AT it: it may re-read records, never skips one, and that rewind is an INFO log line, not the `stream-consumer-rewound` warning, when no exact start of a later epoch below its cursor survives in the record (a folded start proves nothing for a consumer newer than the epoch it folded from). A no-WAL restart that began at offset 0, or any restart that superseded the consumer's own epoch start, IS a proven loss and raises the warning. A checkpoint written by a build before #1873 records no epoch and is not checked; an unfenced consumer (no committed assignment) carries no claim.
- **Operator action:** none for the re-read. If `stream-consumer-rewound` appears for a `confirmation_factor` >= 2 stream, treat it as a defect report: the design argument is that an acknowledged record is held by the new lineage, so no processed record can be missing there `[unverified: argued from the code, not exercised on a multi-node run]`.
- **Proof anchor:** `NoWalRestartConsumerRedeliveryTest` (a real no-WAL owner restart, a running consumer re-reads the new records), `EpochValidationTest`, `StreamPartitionManagerEpochReadTest`, `StreamReadRouterEpochTest` (forwarded read), `ConsumerEpochRewindTest` (resume validated like a read, no head query), `StreamLineageAnnouncerTest`.

### Stream partition with no in-sync owner — failover refused (#1730)

- **Symptom:** a partition stops being served: its owner is dead and no member of its in-sync replica set (ISR) is live, so failover elects nobody — unclean failover is off, because a live replica outside the ISR may lack acknowledged records.
- **Detection surface:** the cluster event **`STREAM_FAILOVER_REFUSED`** (severity CRITICAL; `details`: `stream`, `partition`, `owner`, `isr`, `live`, `reason`) on `GET /api/events` / `aether events`, raised ONCE when the refusal commits (every node derives it from the committed ownership record; the cluster-events owner publishes it — at most once if that partition has no owner at the moment, when the `NoInSyncReplica` status is the record); **`STREAM_FAILOVER_RESOLVED`** (INFO) once when an owner is elected or the owner returns. The partition status also carries the `NoInSyncReplica` block.
- **Automatic response:** none until an ISR member is live again; then the leader elects it (or the returning owner keeps ownership) and the partition serves again.
- **Budget:** n/a — bounded by when an ISR member returns.
- **Degraded / at risk:** the partition is unavailable (no reads, no writes); nothing acknowledged is lost while an ISR member's disk survives.
- **Operator action:** bring an ISR member (listed in `details.isr`) back. There is no operator override to promote a non-ISR replica yet (#1569, open). If every ISR member was terminally removed, the partition's acknowledged data is gone: destroy and recreate the stream.
- **Proof anchor:** `StreamFailoverAnnouncementTest` (one event per committed transition; repeated reconciles announce nothing; recovery announces resolved), `ClusterEventAggregatorTest.streamFailoverRefused_derivedOnEveryNode_publishedOnceByTheEventsOwner_notTheLeader` (leader not the events owner: still exactly one copy), `StreamFailoverAnnouncerWiringTest`, `IsrOwnershipWriterTest$Failover`, `ClusterEventAggregatorTest.streamFailoverRefusedAndResolved_reachTheEventStream_withTheirDetails`.

### Fresh-stream first-publish race

- **Symptom:** a concurrent first publish to a brand-new stream returns a transient 503/500.
- **Automatic response:** owner-side lazy materialization (`ensureStreamMaterialized`) + a bounded 3×150 ms retry (`PublishForwardResponse.retryable`).
- **Budget:** retries resolve within ~450 ms.
- **Operator action:** application-level retry on publish (already the recommended pattern for pub/sub-class calls).
- **Proof anchor:** fixed; incident ledger 2026-07-08.

## Storage durability

### Full-cluster restart (in-memory state)

- **Symptom:** after a **simultaneous** full-cluster restart, KV + DHT + un-sealed stream state since the last snapshot is gone.
- **Automatic response:** a whole-cluster restart is a regular start of fresh cores; with `[backup]` enabled the leader restores the cluster state from the change-triggered KV backup before any cluster-state write is admitted (#1533), and placement is rebuilt on the fresh nodes. DHT system maps rebuild as nodes re-register their slices/routes/endpoints on activation.
- **Budget:** n/a (bounded by restart + re-registration).
- **Degraded / at risk:** cluster-state changes after the last backup push (KV); entity state (checkpoints are not restored); stream records held only by the old nodes; all DHT system-map state (self-heals by rebuild). Without `[backup]`, all cluster state. A **rolling** restart is safe — this applies only to losing the whole cluster at once.
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
