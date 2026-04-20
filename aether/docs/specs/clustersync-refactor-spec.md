# ClusterSync Refactor — Plan

**Status:** Approved • **Scope:** Tier 1 • **Branch:** `release-1.0.0-rc1` • **RC2 Tier-2 follow-up:** issue #178

## Purpose

Rebuild the cluster-state control loop so that data flow is strictly unidirectional:

```
detect (sensor)
   │
   ▼  (batched on follower, direct on leader)
inform-leader-via-pong
   │
   ▼  (leader single-writer, epoch-fenced)
decide
   │
   ▼  (atom writes + snapshot bump)
distribute-via-ping
   │
   ▼  (followers read as single source of truth)
consume
```

Eliminates the event-driven shadow-state pattern that produced the bugs chased across the 2026-04-19 and 2026-04-20 sessions.

## Architectural principles

1. **One writer.** The Rabia leader is the only node that writes cluster-membership atoms or mutates `ClusterGenerationSnapshot`.
2. **One reader interface.** Every consumer of cluster state reads via `ManageableNode.currentGenerationSnapshot()` or `NodeSnapshotCache`. No shadow maps derived from KV notifications.
3. **Sensors are pure.** Followers' SWIM and QUIC detectors observe peers and report upstream via `ClusterSyncPong`. They do not close connections, write atoms, evict peers, or run `processViewChange`.
4. **Transport hygiene follows the snapshot.** Followers' QUIC peer table and forwarding decisions are gated on `snapshot.coreMembers()`. Removing a peer from the snapshot triggers the follower's teardown as a clean-up step.
5. **Epoch-fenced decisions.** Every signal the leader's reconciler accepts carries an `observedEpoch`. Signals outside a two-counter window of the current snapshot are dropped. Old-leader writes are rejected by Rabia term ordering, not by the application layer.
6. **In-flight infrastructure converges via reconcile.** A mid-flight `docker create` (or any infrastructure operation) completes across leader-change; the new leader's next reconcile absorbs any transient surplus or deficit.
7. **Every reconciler has an explicit lifecycle.** `start(leaderEpoch)` / `stop(reason)`. Signals arriving before `start` or after `stop` are fenced.

## Commit sequence (revised order)

The order differs from the numeric labels because commit 5 (lifecycle fencing) lands before commits 3 and 4 so that the subsequent refactors sit on a race-proof base.

| # | Commit title | Depends on |
|---|--------------|------------|
| 0 | `refactor: rename MetricsPing/Pong chain to ClusterSync*` | base |
| 1 | `feat: ClusterSyncPong carries peer observations (SWIM + QUIC)` | 0 |
| 2 | `refactor: followers stop acting on local detections; observations go upstream only` | 1 |
| 5 | `feat: HealthReconciler lifecycle — start(leaderEpoch)/stop, signals epoch-fenced with window=2` | 2 |
| 3 | `refactor: CTM reads configured/actual size from ClusterGenerationSnapshot` | 5 |
| 4 | `refactor: CDM reads membership/governors from snapshot; shadow maps deleted` | 3 |
| 6 | `chore: delete remaining follower-side shadow caches of snapshot-carried data` | 4 |
| 7 | `fix: snapshotCoreCount strict ON_DUTY + HEALTHY (workaround removed)` | 6 |

---

## Commit 0 — rename `Metrics{Ping,Pong}` to `ClusterSync{Ping,Pong}`

### Rationale
The transport carries snapshot, lifecycle, metrics, and (after commit 1) peer observations. "Metrics" is a misnomer that breeds naming confusion at call sites. A pure mechanical rename lands first so all subsequent diffs reference the correct type.

### Files touched
- `integrations/cluster/src/main/java/org/pragmatica/cluster/metrics/MetricsMessage.java` → `ClusterSyncMessage.java`, rename sealed types.
- `integrations/cluster/src/**/metrics/**` package → `cluster/src/**/sync/**` package (or keep package, rename types only — decide at implementation).
- `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/MetricsCollector.java` → `ClusterSyncCollector.java` (class rename) OR keep class, rename methods (`onMetricsPong` → `onClusterSyncPong`). Decide by reviewing whether the class aggregates metrics (keep metrics part of its name) or routes sync (rename fully).
- `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/MetricsScheduler.java` → `ClusterSyncScheduler.java`.
- `aether/aether-metrics/src/main/java/org/pragmatica/aether/worker/metrics/SpokesmanPingLoop.java` — **untouched** in commit 0 (Tier 2 stays `SpokesmanPing*` per Q7 = deferred).
- Generated codec stubs under `target/generated-sources/annotations/` regenerate automatically.
- All test files referencing renamed types.
- `aether/docs/specs/cluster-generation-spec.md` — update §7 language.

### Acceptance
- `mvn install` green.
- Every existing unit and integration test passes unchanged — no behavior change.
- Git `log --follow` traces through the rename cleanly.

### Race analysis
None — pure rename. Mechanical.

### Edge cases
- Persisted envelopes / wire-format compatibility: the codec's `deterministicTag` uses the fully-qualified class name. Renaming the class changes the tag. **Action:** bump `ENVELOPE_FORMAT_VERSION` in `ManifestGenerator.java` per the project's envelope-versioning policy (CLAUDE.md §10).
- External scripts or operators that grep logs for "MetricsPing" — acceptable to break, document in CHANGELOG.

---

## Commit 1 — `ClusterSyncPong` carries peer observations

### Rationale
Today, only the leader's own sensor contributes to `HealthReconciler` input. Followers observe peers (SWIM, QUIC) and drop the signals on the floor. Adding two lists to `ClusterSyncPong` gives the leader the multi-observer view SWIM was designed for, without creating a new message type.

### Wire-type changes

In `integrations/cluster/.../ClusterSyncMessage.java`:

```java
public sealed interface ClusterSyncMessage extends ProtocolMessage {

    @Codec record PeerHealthObservation(NodeId peerId,
                                        HealthHintWire hint,
                                        long observedEpochTerm,
                                        long observedEpochCounter) {}

    @Codec record PeerConnectivityObservation(NodeId peerId,
                                              ConnectivityState state,
                                              long observedEpochTerm,
                                              long observedEpochCounter) {}

    @Codec enum HealthHintWire { HEALTHY, SUSPECTED, FAULTY }
    @Codec enum ConnectivityState { CONNECTED, DISCONNECTED, STALE }

    record ClusterSyncPong(NodeId sender,
                           Map<String, Double> metrics,
                           long observedRabiaTerm,
                           long observedEpochTerm,
                           long observedEpochCounter,
                           String lifecycleState,
                           List<CommunityReport> communityReports,
                           List<PeerHealthObservation> peerHealth,
                           List<PeerConnectivityObservation> peerConnectivity)
            implements ClusterSyncMessage { … }
}
```

`HealthHintWire` and `ConnectivityState` live in `integrations/cluster/` (`ProtocolMessage`-compatible) and are mapped to the `aether/slice` `HealthHint` and SWIM-side enums on the receiving side.

### Leader-side plumbing
- `HealthReconciler.onSignal` gains two new signal variants:
  - `HealthSignal.RemoteSwimHint(NodeId observer, NodeId peer, HealthHint hint, Epoch observedAtEpoch)`
  - `HealthSignal.RemoteConnectivity(NodeId observer, NodeId peer, ConnectivityState state, Epoch observedAtEpoch)`
- New wiring class `ClusterSyncPongSignalFan` lives in `aether-metrics` (or a new `aether-sync` module if packaging cleanly warrants it). On each incoming pong, it unpacks the two lists and calls `healthSignalSink.emit(...)` for each entry. One signal per observation. `HealthReconciler` aggregates remote hints across observers (implementation detail; see reducer rules below).
- `ClusterSyncCollector.onClusterSyncPong` calls `ClusterSyncPongSignalFan.fan(pong)` in addition to its existing metrics aggregation.

### Reducer rules on the leader
A single peer X may be reported HEALTHY by some observers and SUSPECTED by others in the same window. The leader needs a deterministic fold:

- **FAULTY** if at least `⌈N/2⌉ + 1` distinct observers report FAULTY within one sync interval. (Matches existing SWIM quorum.)
- **SUSPECTED** if any observer reports SUSPECTED AND fewer observers report HEALTHY than the FAULTY threshold.
- **HEALTHY** otherwise.

Implementation lives in a new class `PeerObservationReducer`, invoked from `HealthReconciler` before it compares against the current snapshot. Observation tuples `(observer, peer, hint)` are stored in a window keyed by `(peer, observationEpoch)`.

### Acceptance
- Wire-level test: send a crafted `ClusterSyncPong` with mixed health hints; assert the reducer output on the leader side.
- `NodeSnapshotCacheTest` regression check: pong enrichment does not change follower-side cache behavior (commit 1 is additive — followers still act on local detections until commit 2).
- Build green.

### Race analysis
- Two followers send concurrent pongs reporting X differently. Leader processes them in arrival order. Final state is whatever the reducer computes after both are ingested. Deterministic given a total order on arrivals at the leader (delegated to the router).
- Follower's observation buffer fills between sync cycles. **Bound:** cap at (cluster size - 1) per pong; oldest dropped. Missed observation is re-reported in the next cycle.

### Edge cases
- Pong arrives from a now-removed peer (kicked after the sensor ran). Leader's `onSignal` epoch fence (commit 5) drops it. Pre-commit-5 behavior: leader accepts the signal harmlessly (sender is gone, so the observation about itself doesn't propagate).
- Observer's `observedEpoch` is ahead of leader's current snapshot epoch (possible under re-election). Treated as HEALTHY — observer saw a state the leader hasn't caught up to. Not yet actionable; will be re-observed next cycle.

---

## Commit 2 — followers become sensor-only

### Rationale
After commit 1 the leader has enough information to decide. Now disable follower-side *actions*: no `processViewChange(REMOVE)`, no `HealthSignalSink.emit` into the local (follower) reconciler, no QUIC teardown driven by SWIM, no KV atom writes from detector code paths.

### Files touched
- `aether/node/src/main/java/org/pragmatica/aether/node/health/CoreSwimHealthDetector.java`
  - Add an `isLeader` gate (supplied via constructor as `BooleanSupplier leaderSupplier`).
  - `onMemberFaulty` / `onMemberSuspect`:
    - If leader: existing path (emit to local reconciler).
    - If follower: push a `PeerHealthObservation` into the outbound-pong buffer. No eviction, no DisconnectNode, no processViewChange.
  - `isLocalDisconnect` circuit breaker stays leader-only.
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java`
  - Remove the `disconnectListener.onDisconnect(...)` call on `processViewChange(REMOVE)` *on follower nodes*. On the leader, keep it (feeds local reconciler).
  - `disconnect(DisconnectNode)` — leader path keeps existing logic; follower path is idempotent no-op at the decision layer (the REMOVE was already published via the leader's snapshot; follower's QUIC peer table cleanup triggers on snapshot diff, not on DisconnectNode).
- `aether/aether-metrics/.../ClusterSyncScheduler.java`
  - New outbound-pong-buffer API: `bufferObservation(PeerHealthObservation)` / `bufferObservation(PeerConnectivityObservation)`. Buffer drained into the next scheduled pong.
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java`
  - Wire `CoreSwimHealthDetector` with `isLeaderGate::get` as its leader supplier.
  - Follower-side QUIC connectivity listener feeds the buffer instead of `processViewChange(REMOVE)`.

### Acceptance
- Isolated repro of the `02-chaos/test-kill-leader` no longer produces duplicate SWIM FAULTY logs on the follower side. (Grep remote node logs; count should match "one event per actual failure" per observer.)
- 02-chaos integration suite green — was green in the best-case run of the prior session; this commit must preserve that.
- 12-network suite green — the duplicate-FAULTY cascade that previously broke quorum-during-window should be gone because follower QUIC teardown no longer feeds SWIM's own certainty loop.
- Build + existing unit tests green.

### Race analysis
- **Follower's observation buffer vs. pong departure.** Buffer is drained into the next outbound pong in one atomic step (drain-and-flush). A concurrent detector callback lands in the buffer for the *next* pong. No observation lost.
- **Leader-change mid-observation.** Observer is a follower, leader changes. Observer's next pong goes to the new leader (QUIC routing follows topology). Observations the previous leader would have received are re-observed and re-sent in the next cycle. No state loss.
- **Observer receives CoreSwim callback on a detached cluster (partition).** Follower's SWIM marks peers FAULTY; buffer fills; pong can't reach leader because leader is partitioned away. Buffer is bounded; oldest observations drop. When partition heals, observer re-observes and re-sends fresh state. Stale observations in the buffer are dropped by the epoch fence (commit 5). Safe.

### Edge cases
- **Follower's local forwarding to a peer not in snapshot.** After commit 2 the follower never removes peers from its own forwarding table except by reading `snapshot.coreMembers()`. If the snapshot still lists X as a member, follower keeps trying to reach X. QUIC transport's own retransmit handles packet loss. Slice requests that happen to route through X during the window from "X died" to "leader publishes snapshot without X" time out at the application level and retry — consistent with general distributed-systems expectations.
- **Follower kills its own SWIM detector (shutdown).** Detector stops emitting, buffer drains once more if any observations are pending, then scheduler stops. Clean.

---

## Commit 5 — `HealthReconciler` lifecycle and epoch fence

*(Ordered before 3 and 4 so the next two refactors sit on a race-proof base.)*

### Rationale
`HealthReconciler` today is gated by an `AtomicBoolean isLeader`. On leader-change, stale signals in the queue can be processed before the gate flips. The fence gives a clean "no signals from before I became leader" boundary.

### Changes
- `HealthReconciler.start(Epoch leaderEpoch)` replaces the current no-arg `start()`. Stores the start epoch.
- `HealthReconciler.stop(StopReason reason)` replaces the current no-arg `stop()`. `StopReason` is `LEADER_LOST | SHUTDOWN`.
- Every `HealthSignal` variant gains an `Epoch observedAt` field. `onSignal(signal)`:
  ```
  if (signal.observedAt.rabiaTerm < startEpoch.rabiaTerm) drop;
  if (signal.observedAt.rabiaTerm == startEpoch.rabiaTerm
      && signal.observedAt.localCounter < currentSnapshot.epoch.localCounter - 2) drop;
  ```
- `HealthReconcilerActivator.onLeaderChange` calls `reconciler.stop(LEADER_LOST)` then `reconciler.start(newLeaderEpoch)`.
- `HealthReconciler.currentSnapshot()` is only defined on an active reconciler. On a stopped reconciler it returns an empty `ClusterGenerationSnapshot.empty(rabiaTerm)` placeholder. Avoids nulls.

### Acceptance
- New unit tests in `HealthReconcilerLifecycleTest`:
  - Signal emitted at `(term=5, counter=100)` while reconciler started at `(term=6, counter=0)` → dropped.
  - Signal at `(term=6, counter=5)` with current snapshot `(term=6, counter=8)` → dropped (outside window).
  - Signal at `(term=6, counter=6)` with current `(term=6, counter=8)` → accepted (within window=2).
- Leader-change chaos test: repeated `docker kill` of the leader every 10s produces no consensus-apply errors and no stale-signal-induced spurious decisions.

### Race analysis
- **Signal arrives between `stop()` and `start(newEpoch)`.** Reconciler is stopped; all signals no-op. No decision made on stale input. After `start(newEpoch)` the first reconcile is seeded from `projectFromCommittedAtoms()` — authoritative KV truth — not from the in-flight signal queue.
- **Two `start()` calls without an intervening `stop()`.** Treat as bug; assert and log. `HealthReconcilerActivator.onLeaderChange` is the only caller; it serializes start/stop pairs.

### Edge cases
- **Empty cluster (cold start).** No prior term; `startEpoch = (0, 0)`. Fence admits all signals until the first generation bump.
- **Term wraparound.** `long` rabiaTerm; practically never wraps. If it does in testing, the `<` comparison wraps; add an assertion that `startEpoch.rabiaTerm >= 0`.

---

## Commit 3 — CTM reads snapshot for membership counts

### Rationale
The single most load-bearing replacement of shadow state with snapshot reads. This commit makes the scale-up and scale-down failure-modes impossible to reproduce: CTM's view of "desired" and "actual" is identical to the snapshot the leader is publishing.

### Files touched
`aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java`

- Delete fields: `configuredSizeRef`, `desiredSizeRef`, `lastObservedRabiaTerm`.
- `setDesiredSize(int size)` becomes a thin write-through: commit a `ClusterConfigValue` atom with the new `coreCount`. Does not mutate any local state. The next snapshot bump (driven by the atom commit) carries `desiredCoreSize` to everyone, including this leader's own snapshot view.
- `reconcileActive()`:
  ```java
  var snapshot = snapshotSource.currentMembershipView();
  if (snapshot.isEmpty()) { return; }  // not yet projected
  var configured = snapshot.get().desiredCoreSize();
  var actual = snapshot.get().healthyOnDutyCount();
  ```
- `activateWithCurrentTopology()`: same — reads `configured` from snapshot, not from a constructor-supplied size. Constructor no longer takes a `configuredSize` parameter.
- `onTopologyChange` callbacks (`handleNodeAdded`, `handleNodeRemoved`, `handleNodeDown`): unchanged in intent, but reconciliation is now entirely snapshot-driven. Callbacks just trigger `reconcile()`.
- `nodeJoinTimes` stays (heuristic for eviction sort; not in snapshot).

### Acceptance
- `03-scaling/test-02-scale-up`: 5 → 7 completes within 15s, counts verified on snapshot.
- `03-scaling/test-03-scale-down`: 7 → 5 completes within 30s, CTM's `handleSurplus` fires and terminates 2 nodes.
- Scale-up-scale-down cycle on a CTM-provisioned leader (leader-change during scale-up): scaling completes correctly after the leader-change, no hung state.

### Race analysis
- **Scale command commits but snapshot hasn't propagated to the leader's own snapshot view yet.** CTM reads snapshot; sees old `desiredCoreSize`. `reconcile()` takes no action. On the next snapshot bump (within one ping interval), `desiredCoreSize` updates; `reconcile()` sees the new value; acts. Acceptable — bounded delay, deterministic convergence.
- **Leader-change during scale.** Old leader wrote `ClusterConfigValue`; atom committed via Rabia. New leader reads the atom on `projectFromCommittedAtoms()`; snapshot reflects new `desiredCoreSize`. New leader's CTM reconciles. Safe.
- **`setDesiredSize` called twice concurrently.** Both writes go through Rabia; second overwrites first. Both snapshot bumps propagate. Final state is the last committed write. Expected behavior.

### Edge cases
- **`ClusterConfigValue` atom doesn't exist yet.** Snapshot's `desiredCoreSize = 0`. CTM's `reconcile()` short-circuits (no work to do). Before the first scale command, cluster operates at the compose-baked size via `ClusterFormationConfig.coreMax` as the activation-time default; `CDM.effectiveCoreMax()` (already in place from commit `47e31092a`) consults `ClusterConfigValue` if present, falls back to the static bound otherwise. CTM relies on the snapshot's `desiredCoreSize` being seeded by the first `ClusterConfigValue` write — which must happen during bootstrap.
  - **Action in this commit:** confirm bootstrap commits an initial `ClusterConfigValue` (seed_cluster_config path) before the first CTM activation. If not, add it to the bootstrap sequence.

---

## Commit 4 — CDM reads snapshot for membership views

### Rationale
`ClusterDeploymentManager.Active` carries `activeNodes`, `drainingNodes`, `communityGovernors` — each a shadow of information the snapshot already has. Deletes them; consumers read the snapshot.

### Changes
In `ClusterDeploymentManager.java` Active record:
- Delete `activeNodes` (AtomicReference). Replace reads with a helper method:
  ```java
  private List<NodeId> activeNodes() {
      return snapshotSource.currentMembershipView()
          .map(v -> v.onDutyMemberIds().stream().toList())
          .or(List::of);
  }
  ```
- Delete `drainingNodes`. Replace reads with:
  ```java
  private Set<NodeId> drainingNodes() {
      return snapshotSource.current()
          .map(s -> s.coreMembers().values().stream()
              .filter(m -> m.lifecycle() == NodeLifecycleState.DRAINING)
              .map(CoreMember::nodeId)
              .collect(toUnmodifiableSet()))
          .or(Set.of());
  }
  ```
- Delete `communityGovernors`. Replace reads with `snapshot.communities().get(communityId).governorNodeId()`.
- Keep leader-only decision state: `inFlightBlueprints`, `retryCounters`, `restoringBlueprints`, `permanentlyFailed`, `transitionalStateTimestamps`. These are not replicated; they track in-progress leader-side work.
- `workerNodes` stays — out of scope of the core-membership snapshot (per Q4 audit).

### Acceptance
- Existing unit tests (`ClusterDeploymentManagerTest`) pass with shadow-fields removed.
- New unit test: stub `snapshotSource` returning a snapshot with one DRAINING member; assert `drainingNodes()` returns that member; switch to a snapshot without the DRAINING member; assert it's gone. No shadow state to desync.

### Race analysis
- **Leader-change mid-decision.** Old CDM's in-flight `inFlightBlueprints` is leader-only state; discarded on deactivate. New leader re-reads atoms via `rebuildStateFromKVStore()` on activation. No shared state; no race.
- **Concurrent snapshot update during iteration.** Each helper method reads `snapshotSource.current()` once and operates on the returned immutable snapshot. Subsequent reads get fresh snapshots. No torn reads.

---

## Commit 6 — delete remaining follower-side shadow caches

### Rationale
Catch-all for any follower-side state that was missed in 3 and 4. Final audit pass.

### Targets to audit (grep + review)
- `TopologyObserver.nodeStatesById` — narrow-use (QUIC-level state); OK as-is, but verify all *snapshot-covered* fields (role, observed faulty count) either defer to the snapshot or are explicitly documented as local-only.
- `NodeDeploymentManager` — look for any `Set<NodeId>` / `Map<NodeId, …>` that mirror KV atoms. Self-write path (writing own `NodeLifecycleKey=ON_DUTY`) is fine; mirroring the ON_DUTY state of *other* nodes is not.
- `LoadBalancerManager` — if it tracks member state, must read from snapshot.
- `TaskAssignmentCoordinator`, `ConsumerGroupCoordinator`, `ScheduledTaskManager` — their "who's leader" state is fine (local boolean). Their "who's in the cluster" state must come from snapshot.

### Acceptance
- Grep audit: no follower-path `ConcurrentHashMap<NodeId, …>` that tracks membership or lifecycle.
- Build + 15-suite integration green.

---

## Commit 7 — snapshot `coreCount` strict again

### Rationale
Commit `48e3342f0` relaxed `snapshotCoreCount` to count `ON_DUTY | JOINING` because transient SWIM SUSPECTED hints were pulling the count below the real membership during chaos recovery. With commits 1–6 in place, SWIM hints are now multi-observer-aggregated at the leader, the leader decides, and the snapshot publishes coherent state. Transient SUSPECTED on a single follower no longer reaches the snapshot. The relaxed count is no longer needed and hides legitimate degraded-state reporting.

### Change
`ClusterTopologyRoutes.snapshotCoreCount` reverts to:
```java
return (int) snapshot.coreMembers().values().stream()
    .filter(m -> m.lifecycle() == NodeLifecycleState.ON_DUTY)
    .filter(m -> m.healthHint() == HealthHint.HEALTHY)
    .count();
```

### Acceptance — the correctness gate for the whole refactor
All 15 integration suites green on 5 consecutive remote runs. Any suite that regresses here reveals a hole in commits 1–6; do not merge 7 until those are identified and fixed.

---

## Cross-cutting: component lifecycle matrix

| Component | On node startup | On leader-gain | On leader-loss | On shutdown |
|-----------|-----------------|----------------|----------------|-------------|
| `CoreSwimHealthDetector` | running (sensor mode) | also feeds local reconciler directly | reverts to buffer-for-pong | stops |
| `QuicClusterNetwork` | running | unchanged (transport, not role-dependent) | unchanged | close all connections |
| `ClusterSyncScheduler` | running (pongs outbound) | also sends pings with snapshot | stops sending pings, still sends pongs | stops |
| `HealthReconciler` | stopped | `start(newLeaderEpoch)` → seed from KV → reconcile | `stop(LEADER_LOST)` → clear decision state | `stop(SHUTDOWN)` |
| `ClusterTopologyManager` | Inactive | `activate()` → reconcile from snapshot | `deactivate()` (in-flight provisions finish; new leader converges) | stops |
| `ClusterDeploymentManager` | Dormant | `activate()` → `rebuildStateFromKVStore()` → reconcile | `deactivate()` (in-flight blueprints finish) | stops |
| `NodeSnapshotCache` | empty | unchanged (reads leader's ping) | unchanged | — |

## Cross-cutting: edge-case matrix

| Scenario | Behavior |
|----------|----------|
| Leader-change mid-reconcile | Old leader's in-flight operations complete; state changes commit via Rabia and are visible to new leader through KV. New leader's next reconcile absorbs the result. No cancellation. |
| Network partition: leader on minority side | Leader loses quorum; `QuorumStateNotification.disappeared()` fires; `HealthReconciler.stop(LEADER_LOST)`. Majority-side elects new leader; new leader starts clean. |
| Slow follower | Follower's snapshot lags; its observations arrive at leader with older epoch. Epoch-fence window=2 catches those slightly-stale; older than two counters dropped. Observations re-sent in next cycle with fresh epoch. |
| Snapshot delivery lag | Leader just decided; atom committed; snapshot counter not yet bumped or not yet distributed. Consumers read the old snapshot for one ping interval. Bounded delay; acceptable. |
| Provisioned node becomes leader | CTM on the provisioned node starts, reads snapshot (now propagated to this leader via its own `HealthReconciler.currentSnapshot()` because we're on the leader path), reconciles based on authoritative data. Pre-refactor bug where the provisioned leader's CTM used stale `configuredSize` is gone because CTM reads only from snapshot. |
| Simultaneous scale-up and node-kill | Scale writes `ClusterConfigValue` with `coreCount=7`; kill triggers SWIM + QUIC observation; leader receives both. Next reconcile sees desired=7, actual=4 (5 – 1 dead), provisions 3 replacements. Correct. |
| Two operators scale concurrently (7 then 5) | Both commits go through Rabia; second overwrites first. Last-write-wins at the atom level; snapshot bump reflects the latest. CTM reconciles to whatever the latest value is. |
| Rabia term bumps without real change (phantom leader-election) | `HealthReconciler.stop(LEADER_LOST)` then `.start(newTerm)`. Seeded from KV again. Signals fenced by `startEpoch`. No decision drift. |
| All followers report peer X faulty during GC pause on X | Leader's reducer counts majority FAULTY; fires eviction. If pause was real (30s GC), eviction is correct. If transient (2s blip), next cycle's observations report HEALTHY; leader either re-admits X (if X hasn't been removed yet) or CTM provisions a replacement (if it was). |
| `ClusterSyncPong` drops on the network | Follower's observations don't reach the leader this cycle. Observations re-sent next cycle. Window-of-2 fence accepts the re-send. |

## Testing strategy

### Per-commit
- **Unit tests** for every new public method or changed invariant (state-machine transitions, reducer rules, fence logic).
- **Integration smoke** (one targeted suite per commit) before merging.

### Refactor end-to-end
- **5 consecutive 15/15 green runs on remote Docker** is the gate for commit 7.
- **Chaos soak**: run `02-chaos`, `03-scaling`, `12-network`, `13-edge-cases` in a loop for 30 minutes continuously. No failures, no growing log warnings.
- **Hetzner verification** (matches production profile): single full run on Hetzner environment before claiming RC1 green.

### Regression guards (write and keep)
- "Follower cannot write `NodeLifecycleKey` for a peer other than itself" — asserted at the write boundary.
- "`HealthReconciler` on a stopped instance ignores all signals" — unit test.
- "`ClusterTopologyRoutes.snapshotCoreCount` never exceeds `coreMembers.size()`" — property test.
- "A snapshot's `coreMembers.size() <= snapshot.desiredCoreSize() + surplusAllowance`" where `surplusAllowance` accounts for in-flight provisioning slack — property test.

## Open decisions deferred

1. **Package rename**: `cluster/metrics` → `cluster/sync`? Decide at commit 0 implementation time; leaning toward renaming types only (keep package path `cluster/metrics` for commit 0 minimality; can rename in a follow-up).
2. **Separate `aether-sync` module**: worth extracting the sync-loop code from `aether-metrics` into a sibling module? Also a follow-up; not part of this plan.

## Deliverables checklist

- [ ] Commit 0 landed, build green.
- [ ] Commit 1 landed, leader receives peer observations; tests added.
- [ ] Commit 2 landed, followers sensor-only; 02-chaos clean.
- [ ] Commit 5 landed, lifecycle + fence; leader-change test added.
- [ ] Commit 3 landed, CTM snapshot-driven; 03-scaling green.
- [ ] Commit 4 landed, CDM shadow-free; unit tests pass.
- [ ] Commit 6 landed, follower audit clean.
- [ ] Commit 7 landed, strict count; 5× 15/15 green on remote.
- [ ] Hetzner 15/15 green.
- [ ] Chaos soak 30m clean.
- [ ] Retrospective written; append to issue #178.
- [ ] Bump `ENVELOPE_FORMAT_VERSION` in `ManifestGenerator.java` (commit 0 side effect).
- [ ] CHANGELOG entry.

## References

- Spec: `aether/docs/specs/cluster-generation-spec.md`
- Prior handover: `aether/docs/internal/progress/session-handover-2026-04-20-generation-membership.md`
- Tier-2 follow-up: https://github.com/pragmaticalabs/pragmatica/issues/178
