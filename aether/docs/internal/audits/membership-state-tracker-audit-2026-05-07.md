# Membership State Tracker Audit — 2026-05-07

**Branch:** `release-1.0.0-rc1`
**Trigger:** 12-network suite flake on cloud cluster (50–150 ms RTT). Single SWIM `FaultyObserved` for peer-X amplifies into 3–5 redundant `processViewChange(REMOVE)` routings, racing with KV replication and resetting CTM stability anchors. Localhost (~0 ms RTT) hides the race; cloud surfaces it.
**Compared against:** `aether/docs/specs/membership-architecture-spec.md` (the R1–R10 redesign, drafted 2026-05-01).

---

## 1. Per-tracker map

The phrase "what is the cluster size and which peers are healthy?" is currently answered by **four parallel data structures** plus **two debounce sidecars**, each with its own update path.

### Primary trackers

| # | Tracker | Source-of-data | Authoritative for | Mutation triggers | Consumers | Debounce / staleness | Cloud failure mode |
|---|---|---|---|---|---|---|---|
| **T1** | `MembershipView` (KV-projected snapshot) | `NodeLifecycleKey` + `ClusterConfigKey` + `NodeArtifactKey` atoms via `GenerationSnapshotPublisher` | `coreMemberIds`, `onDutyMemberIds`, `healthyOnDutyCount`, `desiredCoreSize`, `ctmProvisionedNodeIds`, `nodesWithoutSlices` | Leader-only writes via Rabia consensus, observed by every node through `KVStoreNotification.ValuePut/Remove` | `TopologyObserver.healthyActiveNodeCount` / `readyNodeCount`, `ClusterTopologyManagerRecord.snapshotHealthyOnDutyCount`, CTM provisioning loop, `HealthReconciler.onDutyCountSupplier`. | None at this layer — projection is eventual-consistent over Rabia commits. | Lags the **local** SWIM observation by one Rabia round-trip + projection latency. On cloud this can be 200–500 ms; under quorum loss it can be unbounded. Listeners that wait on it after a SWIM FAULTY will see the count flap as the projection catches up. |
| **T2** | `TopologyObserver.nodeStatesById` (per-node `Map<NodeId,NodeState>`) | `addNode` / `removeNode` calls + `NetworkServiceMessage.ConnectedNodesList` reconcile + `DiscoveredNodes` gossip | `topology()` list, transport-level legacy `healthyActiveNodeCount` fallback, periodic `initReconcile` re-seed/connection requests | `addNode` (constructor seed of `coreNodes`, gossip discovery, `initReconcile` re-seed), `removeNode` (only via `unregisterPeer` — no longer wired). `NodeState.health` is **never updated by SWIM FAULTY**. | `TopologyObserver.evaluateQuorumState` (legacy fallback path), `requestConnectionIfEligible` reconnect dispatcher, `coreNodes()` when snapshot is empty. | `tombstonedNodes` set, `isDecommissioned` predicate (KV-backed across restarts). No per-state debounce. | **`NodeHealth` field is dead state.** It defaults to HEALTHY on `addNode` and never transitions because SWIM FAULTY now flows to `HealthReconciler` (which writes KV, which feeds T1) rather than to T2. Result: `legacyHealthyActivePeerCount` would lie if the snapshot path were ever absent — and during cold-boot that path **is** absent (snapshot empty for first ~2 s). |
| **T3** | `SwimProtocol.members` (`ConcurrentHashMap<NodeId, SwimMember>`) | Local probe round (`tick` → `Ping` / `PingReq` / `Ack`), gossip piggyback (`MembershipUpdate`), `addSeedMember` | `MemberState` enum (ALIVE / SUSPECT / FAULTY) per peer, incarnation numbers | Periodic tick: `markSuspect`, `applySuspect`, `transitionToFaulty`, `applyAliveFromAck`, `applyAliveRevival`, `cleanupFaultyMembers` (FAULTY → removal after 3× suspect window) | `SwimObservation` listeners (T3 → consumers): `HealthReconciler.onSwimObservation`, `ClusterEventAggregator.onSwimObservation`, `AetherNode` local QUIC eviction lambda. `currentHealth()` snapshot (admin endpoints). | `everSeenHealthy` set (cold-boot gate: never-healthy peer emits `UnknownObserved`, not `FaultyObserved`). `lastEmittedHealth` map enforces edge-triggered emission. `transportHints` shortens suspect window to 3 s floor on `PeerUnreachable`. `revivalTimestamps` (5 s grace after external markAlive). | **The single SWIM FAULTY edge fans out to three listeners; each listener triggers its own downstream cascade.** Cold-boot suppression depends on `everSeenHealthy` being populated **before** the first FAULTY observation. On cloud, the seed peer set may not have completed a successful Ping round before another peer's container restart fires SUSPECT → FAULTY, dropping the FAULTY into UNKNOWN — silent on intent, noisy in logs. |
| **T4** | `QuicClusterNetwork.peers` (`ConcurrentHashMap<NodeId, PeerState>`) | `connectPeer` (outbound dial), `attach` on inbound Hello, `disconnect(DisconnectNode)`, `expireEvicted` GC, `closePeerConnections` shutdown | Per-peer connection lifecycle: INIT / CONNECTING / CONNECTED / EVICTED / REMOVED + offline buffer | `beginConnecting`, `attach`, `evict`, `expireEvicted`, `authoritativeRemove`. `disconnect` now uses `evict` (recoverable) per commit `a4695786b`. | `connectedPeers()`, `connectedNodeCount()`, `activeConnectedCount()` (CONNECTED ∪ EVICTED), `processViewChange` advisory routing, `dispatchPayload` outbound gating. | `phaseChangedAtNanos` per peer (3× `helloTimeout` protection window in `disconnect`), `reconcileNextAttemptMs` + `reconcileCurrentDelayMs` per-peer reconciler backoff. | **Per-peer protection-window check guards against fresh-connection races but does not coordinate across multiple `disconnect()` callers.** When the local SWIM listener AND a leader-routed `DisconnectNode` AND a missing-peer reconciler all fire on the same peer within the cloud RTT window, all three pass the protection window check (it's keyed on phase age, not on caller identity). Multiple `processViewChange(REMOVE)` routings result. |

### Secondary trackers (debounce sidecars)

| # | Sidecar | Source | Owner | What it gates |
|---|---|---|---|---|
| **S1** | `ClusterTopologyManagerRecord.lastObservedHealthyOnDutyCount` (`AtomicInteger`) | `snapshotHealthyOnDutyCount()` (read of T1) | CTM | Stability anchor reset decision in `maybeBumpAnchorOnHealthyOnDutyEdge`. After commit `70f8da499`, only **upward** count edges reset the 30 s window; downward edges preserve it. |
| **S2** | `ClusterTopologyManagerRecord.lastObservedRealActual` + `realActualStableSinceMs` | `snapshotHealthyOnDutyCount()` | CTM | The actual provisioning gate — `actual < desired AND now - anchor > stabilityWindow` triggers `provision`. |
| **S3** | `HealthReconcilerImpl.aggregator` (per-peer `ObservationAggregator`) | `SwimObservation` stream (T3) | HealthReconciler (leader-only writes) | k-of-n + aggregation window (`config.aggregationWindowMs()`) + cooldown (`config.cooldownMs()`) before writing `NodeLifecycleKey`. |
| **S4** | `HealthReconcilerImpl.lastWriteAt` (`Map<NodeId, Long>`) | KV-write success | HealthReconciler | Per-peer write cooldown to throttle Rabia churn. |
| **S5** | `HealthReconcilerImpl.currentPhase` + `stableSinceMs` | `ClusterPhaseKey` KV atom + `onDutyCountSupplier` | HealthReconciler (leader proposes phase transitions; everyone observes) | Suppresses `DECOMMISSIONED` / `SHUTTING_DOWN` / `DRAINING` writes while in BOOTING. |
| **S6** | `SwimProtocol.lastEmittedHealth` + `everSeenHealthy` | T3 transitions | SWIM | Edge-trigger gate; cold-boot suppression. |

---

## 2. Where the spec's intent diverges from implementation

The spec defines a strict 8-layer one-way pipeline (Layer 0 Transport → Layer 1 SWIM → Layer 2 HealthReconciler → Layer 3 TopologyObserver → Layer 4 Rabia → Layer 5 Leader Election → Layer 6 CTM → Layer 7 Node Lifecycle FSM, §3 of the spec). The implementation diverges as follows:

### D1. TopologyObserver is *not* a pure projection

**Spec §4.4:** "Pure read-only projection of KV atoms. Sole publisher of `TopologyChangeNotification` and `QuorumStateNotification`. No `registerPeer/unregisterPeer/markReady/markDeparted` API. Internal state is computed from KV atom subscriptions only."

**Reality** (`TopologyObserver.java:54-98, 132-302`): the observer keeps a write-behind in-memory `nodeStatesById` map seeded from `config.coreNodes()` at construction, mutated by `addNode`/`removeNode` driven by gossip messages (`DiscoveredNodes`, `ConnectedNodesList`), and re-seeded on every periodic `initReconcile` tick. It is the `MembershipView` snapshot adapter (`snapshotSource.currentMembershipView()`) that delivers the KV-projected view, but the legacy in-memory map is still the fallback when the snapshot is empty (`readyNodeCount` line 370–373, `healthyActiveNodeCount` line 380–387, `legacyHealthyActivePeerCount` line 461–468). Result: T2 is a parallel cache that mostly defers to T1 but overrides it whenever T1 is empty (cold-boot, post-stop).

### D2. QUIC still emits `TopologyChangeNotification`

**Spec §4.1:** Layer 0 must NOT emit `TopologyChangeNotification` (any variant) or call `topologyManager.registerPeer/unregisterPeer`.

**Reality** (`QuicClusterNetwork.java:1058-1113`): `processViewChange` still routes `TopologyChangeNotification.NodeAdded`, `NodeRemoved`, `NodeDown` directly to the message router on every ADD/REMOVE/SHUTDOWN op. The doc-comment claims these are "informational" and the observer "does not derive membership from them," but **15 receivers across 8 modules** subscribe to these notifications (CDM, CTM via record, LoadBalancerManager, TaskAssignmentCoordinator, ClusterSyncScheduler, ClusterSyncCollector, DeploymentMetricsCollector/Scheduler, SliceInvoker, HttpForwarder, AppHttpServer, ControlLoop). They drop endpoint registries, prune routing tables, retract slice deployments, evict per-node metric series. None of these call sites checks the spec-defined "informational" caveat — they treat the notification as authoritative.

### D3. Rabia still has no `Paused` state

**Spec §4.5:** "`Paused` state is new. Previous `clusterDisconnected()` path **deleted**. There is no path that resets `currentPhase` to ZERO except explicit reconfiguration via Rabia command."

**Reality:** the Rabia engine still has `clusterConnected` / `clusterDisconnected` callable from outside, and quorum-loss flips to `Phase.ZERO` is still triggered on `QuorumStateNotification.Disappeared`. The R1 phase of the spec is unstarted.

### D4. Leader election is monolithic, not rank-staircased

**Spec §4.6:** rank-staircase delays + KV-sync grace + always-listen for KV during `Electing`.

**Reality:** the implementation has the KV-sync grace and always-listen pieces (visible in commit history), but rank-staircase delays are not applied. Cold-boot election storms remain possible.

### D5. `HealthReconciler.onSwimObservation` is leader-gated for writes but the side cascade is not

**Spec §4.3 (decision rule 4):** "All writes go through `Rabia.propose`. Only the leader is allowed to propose; followers route their decisions through `ClusterSyncPong` to the leader."

**Reality** (`HealthReconcilerImpl.java:242-264`): the leader-gate is correct for KV writes. But the **same SWIM observation** is **also** delivered to (a) `ClusterEventAggregator.onSwimObservation` which buffers a `NODE_FAILED` ring-buffer event on every node (intentional, per `f7a6f6f2a`), and (b) the AetherNode local lambda that calls `localNetwork.disconnect(DisconnectNode)` on every node (intentional, per `a4695786b`). Both side branches are correct **in isolation** but each one independently triggers its own `processViewChange(REMOVE)` cascade through `QuicClusterNetwork.disconnect → processViewChange`, so a single cluster-wide FAULTY produces N (one per surviving node) `TopologyChangeNotification.NodeRemoved` routings on the message bus. With N=5 nodes and 4 surviving, that is 4 redundant routings. Add the leader's own `routeDisconnect` (still wired in `SwimHealthContext.routeFaulty` line 178–186) → 5 routings. Add the missing-peer reconciler tick → 6.

### D6. CTM still listens to `TopologyChangeNotification` directly, not via `MembershipView`

**Spec §4.7:** CTM's "sole input source" is `TopologyChangeNotification` from the observer (Layer 3). But spec §4.4 also says the observer must derive everything from KV. The intent is: T1 (snapshot) drives `TopologyChangeNotification`, CTM consumes those.

**Reality** (`ClusterTopologyManagerRecord.java:241-280`): CTM consumes `TopologyChangeNotification` from the message router, but the publisher chain is **QUIC's `processViewChange` for transport edges and `TopologyObserver` for snapshot/cluster-size edges**. Both publishers fire concurrently for the same logical event. CTM sees the same removal twice — once from T4 (`processViewChange(REMOVE)`) and once from T1 via T3 (`HealthReconciler` writes `DECOMMISSIONED` → snapshot re-projection → `TopologyObserver` emits `NodeRemoved`). Each invocation calls `maybeBumpAnchorOnHealthyOnDutyEdge`. Pre-`70f8da499` this reset the anchor twice; post-fix the second one preserves it on downward edge — but the underlying duplication is unaddressed.

### D7. `everSeenHealthy` cold-boot gate races with `addObservationListener`

**Reality** (`CoreSwimHealthDetector.java:67, 247-250, 296`): listeners registered via `addObservationListener` before SWIM enters `Running` are stored in `pendingObservationListeners` (commit `d0dcee8bc`) and re-attached in `seedAndWrap`. Correct in principle. But the spec §4.2 cold-boot rule ("SWIM does NOT emit `FaultyObserved` for nodes that have never been observed HEALTHY") is implemented per-peer in `everSeenHealthy`, and that flag is populated **only** when an `Ack` round-trip succeeds (`recordHealthyAndEmit` called from `processAckProbe` and `applyAliveFromAck`). On cloud cold-boot, the first tick is delayed by a jittered startup (~5 s). If a peer's container is killed at T+0 and another peer doesn't complete a successful Ping until T+6 s, the SUSPECT → FAULTY transition for the killed peer fires at T+15 s but `everSeenHealthy` was never set → emitted as `UnknownObserved` instead of `FaultyObserved` → `HealthReconciler` aggregator does not count it (only `FaultyObserved` is aggregated in the `ObservationAggregator`) → no `DECOMMISSIONED` write → CTM never sees a downward count edge → no provisioning. **The cold-boot suppression is too aggressive on cloud.**

---

## 3. Actual cascade for SWIM `FaultyObserved(peer-X)`

Below is the cascade observed on a 5-node cloud cluster when `peer-X` (a non-leader follower) is killed. T0 = local SWIM `applySuspect → expireSuspectIfOverdue → transitionToFaulty → emitFaultyOrUnknown` on a witness node `peer-W` (the one whose probe timed out twice).

Key:
- `*` = redundant routing of the same logical "peer-X is gone" fact
- `Δ` = mutation of a tracker
- `→` = message routing or method call

### Phase 1 — Witness node observes FAULTY (T0)

```
T0  SwimProtocol.transitionToFaulty(peer-X)                          (Δ T3: ALIVE→FAULTY)
    └─ emitFaultyOrUnknown(peer-X) → emitObservationOnEdge → 3 listeners in fan-out:

       (a) HealthReconciler.onSwimObservation
           ├─ aggregateEdge → ObservationAggregator.onObservation(peer-X, FAULTY)
           │     - on 5-node cluster needs ⌈5/2⌉+1 = 3 nodes reporting FAULTY for the
           │       aggregation window, but `aggregator.onObservation` returns a
           │       `StateChanged` edge as soon as the per-peer LOCAL aggregator
           │       crosses its own threshold — each node aggregates its OWN sliding
           │       window without cross-node consensus. This is NOT the spec's
           │       "quorum-of-observations" rule. (D5 confirms.)
           └─ if leader → handleAggregatedEdge → proposeLifecycleWrite
                 → KVCommand.Put<NodeLifecycleKey, DECOMMISSIONED>          (Δ T1 indirect, via Rabia)

       (b) ClusterEventAggregator.onSwimObservation                  (intentional, f7a6f6f2a)
           └─ bufferNodeFailedEvent → NODE_FAILED ring-buffer entry

       (c) AetherNode local lambda                                   (intentional, a4695786b)
           └─ localNetwork.disconnect(DisconnectNode(peer-X))
                 → QuicClusterNetwork.disconnect (line 362)
                    ├─ if peer in CONNECTED past 3*helloTimeout:
                    │    PeerState.evict (CONNECTED→EVICTED)         (Δ T4)
                    ├─ cleanupPeerQueues(peer-X)
                    ├─ resetReconnectBackoff(peer-X)
                    └─ * processViewChange(REMOVE, peer-X) (line 400)
                         ├─ reportPeerRemoval → if leader: disconnectListener.onDisconnect
                         │                       else: connectivityReporter.onPeerDisconnected
                         ├─ peerStateListener.onPeerLeft(peer-X)
                         │     → swimDetector.recordTransportHint(PeerUnreachable)
                         │       → SwimProtocol.applyUnreachableHint
                         │         → biases peer-X suspect window to 3 s floor (no-op:
                         │           peer-X is ALREADY FAULTY, not SUSPECT)
                         └─ * router.route(TopologyChangeNotification.NodeRemoved(peer-X))
                              ┌───────────── 15 fan-out subscribers ────────────────────┐
                              │ • CDM.onTopologyChange                                  │
                              │ • CTM.onTopologyChange → maybeBumpAnchorOnHealthyOnDutyEdge
                              │     (now safe per 70f8da499; preserves anchor on down)  │
                              │ • LoadBalancerManager.onTopologyChange (drops endpoints)│
                              │ • TaskAssignmentCoordinator (re-balances tasks)         │
                              │ • ClusterSyncScheduler / ClusterSyncCollector (purges)  │
                              │ • DeploymentMetricsCollector/Scheduler (drops series)   │
                              │ • SliceInvoker.onNodeRemoved (drops endpoints)          │
                              │ • HttpForwarder.onNodeRemoved (drops routes)            │
                              │ • AppHttpServer.onNodeRemoved (drops virtual-host)      │
                              │ • ControlLoop.onTopologyChange (autoscale recompute)    │
                              │ • ClusterDeploymentState FSM event                      │
                              └─────────────────────────────────────────────────────────┘
```

### Phase 2 — Leader processes the local SWIM observation (T0+ε if leader=peer-W; otherwise T0+RTT after gossip)

If `peer-W` is the leader, T0 already wrote `DECOMMISSIONED`. If `peer-W` is a follower, the leader's own SWIM tick discovers FAULTY independently within its own ~suspect window (15 s default; transport-hint-shortened to 3 s if leader's QUIC saw the disconnect first — and it did, because the local lambda above ran on the leader too). Cascade on the leader:

```
T1  Leader's SwimProtocol.transitionToFaulty(peer-X)                 (Δ T3 on leader)
    └─ emitFaultyOrUnknown → 3-listener fan-out (same as Phase 1)

       (a) HealthReconciler.onSwimObservation on LEADER
           └─ this time leader-gated: proposeLifecycleWrite
                 → 2nd KVCommand.Put<NodeLifecycleKey, DECOMMISSIONED>
                   (idempotent — same value — but burns a Rabia round)
                 → cooldown lastWriteAt[peer-X] now blocks further writes for 30 s

       (b) eventAggregator (leader): 2nd NODE_FAILED entry on leader's ring buffer
           (different node — union across nodes is fine, but logs duplicate)

       (c) AetherNode lambda: ANOTHER localNetwork.disconnect(peer-X)
           → QuicClusterNetwork.disconnect on LEADER
              ├─ peer-X is already EVICTED on leader (from leader's own earlier tick
              │  if it went first; or unchanged if leader observes after follower).
              │  evict() returns Option.empty (already EVICTED) → no-op ✓
              │  BUT cleanupPeerQueues / resetReconnectBackoff still run.
              └─ * processViewChange(REMOVE, peer-X) AGAIN
                  → 2nd identical TopologyChangeNotification.NodeRemoved on leader's bus
                  → all 15 leader-side subscribers re-process the same removal
```

### Phase 3 — KV replication catches up, snapshot re-projects (T0 + Rabia round + ε)

```
T2  Rabia commit of NodeLifecycleKey[peer-X] = DECOMMISSIONED
    └─ KVStoreNotification.ValuePut to all 4 surviving nodes
        ├─ ClusterEventAggregator.onNodeLifecyclePut
        │     └─ bufferNodeFailedEvent  (3rd NODE_FAILED entry; source=lifecycle-kv)
        ├─ GenerationSnapshotPublisher recomputes MembershipView           (Δ T1)
        │     └─ healthyOnDutyCount: 5 → 4
        │     └─ adapter publishes new snapshot
        └─ TopologyObserver re-evaluates (no current code path actually
           subscribes to NodeLifecycleKey directly — observer only owns
           QuorumStateNotification edges, not NodeRemoved emission per
           snapshot delta. NodeRemoved ALREADY fired in Phase 1 from QUIC.)
```

### Phase 4 — Missing-peer reconciler tick (T0 + ~10 s on default schedule)

```
T3  QuicClusterNetwork.reconcileMissingPeersTick
    └─ for peers not in CONNECTED set: PeerState.reconcileBackoffAllows
        ├─ peer-X is EVICTED locally → not eligible for re-dial  (Δ T4 unchanged)
        └─ if peer-X is REMOVED via expireEvicted (after EVICTED TTL) →
           skipped (good); else → re-dial attempted, fails, schedules backoff
```

### Phase 5 — SWIM final cleanup (T0 + 3*suspectTimeout = ~45 s default)

```
T4  SwimProtocol.cleanupFaultyMembers (in tick)
    └─ remove peer-X from members map                                 (Δ T3)
    └─ emitDeparted(peer-X) → 3-listener fan-out
        (a) HealthReconciler.onSwimObservation: DepartedObserved is NOT
            aggregated (only FaultyObserved is in ObservationAggregator)
            → ignored ✓
        (b) ClusterEventAggregator.onSwimObservation:
            DepartedObserved → bufferNodeLeftEvent (NODE_LEFT)
            (4th event for the same departure across the cluster)
        (c) AetherNode lambda: only matches FaultyObserved → ignored ✓
```

### Cascade summary

| Event | Routed N times | Duplicate work | Race target |
|---|---|---|---|
| `processViewChange(REMOVE, peer-X)` | **N (number of surviving nodes)** | All 15 subscribers fire N times each | T1 snapshot still resolving |
| `TopologyChangeNotification.NodeRemoved(peer-X)` | **N+1** if leader's own SWIM tick fires after lambda | CTM anchor logic; pre-70f8da499 reset N times | S1/S2 |
| `KVCommand.Put<NodeLifecycleKey, DECOMMISSIONED>` | 1 (leader) but cooldown saves the rest | Wasted Rabia round if leader's own SWIM fires after lambda | Rabia round-trip latency |
| `NODE_FAILED` ring-buffer entry per node | 2–3 per node (SWIM-witness + leader-witness + lifecycle-KV-observed) | Test harness must dedupe across sources | union semantics OK |
| `QuicClusterNetwork.disconnect(peer-X)` | **N** (every node's own lambda) + 1 (leader's `routeDisconnect` in `SwimHealthContext.routeFaulty`) | Per-peer protection-window check passes for all callers | none (each call is local) |

---

## 4. Recommended consolidation design

### 4.1 Principle: one writer, one reader, one notification

Pick **`MembershipView` (T1)** as the single source of truth for "which peers are healthy ON_DUTY right now," and define its writer/reader contracts unambiguously.

**Writer contract (single):**
- `HealthReconciler` (leader-only) is the **sole** writer of `NodeLifecycleKey` atoms.
- The Generation snapshot publisher is the **sole** writer of `MembershipView` derivations on every node, fed by Rabia commit notifications.
- No other module mutates membership. Period.

**Reader contract (single, lazy):**
- All "is peer-X healthy?" reads go through `MembershipView` queries via a thin façade. No fallback path that consults `T2.nodeStatesById` or `T4.peers`.
- During pre-snapshot cold-boot, callers either (a) wait on a snapshot-arrival promise or (b) read the configured `coreNodes()` size with no health overlay (treat all as JOINING).

**Notification contract (single):**
- `TopologyObserver` becomes a **pure adapter** from `MembershipView` deltas to `TopologyChangeNotification` and `QuorumStateNotification`. It diffs successive snapshots and emits one notification per actual edge.
- `QuicClusterNetwork.processViewChange` is **deleted**. QUIC emits only `TransportObservation` (informational hint to SWIM).
- The AetherNode local-lambda `localNetwork.disconnect(faulty.peer())` is replaced by a `MembershipView` subscriber (or by `TopologyObserver`'s `NodeRemoved` emission) that the QUIC layer consumes to issue its own `evict`.

### 4.2 Tracker fates

| Tracker | Fate | Rationale |
|---|---|---|
| **T1 `MembershipView`** | **KEEP — promote to single source of truth.** | Already KV-derived, leader-coordinated, Rabia-durable. Spec §4.4 alignment. |
| **T2 `TopologyObserver.nodeStatesById`** | **RETIRE the in-memory map.** Keep `coreNodes()` and `addNode`/`removeNode` only as a transitional shim that delegates to `MembershipView`. Strip `NodeHealth` field entirely. | The map is dead state; `NodeHealth` never updates. The `legacyHealthyActivePeerCount` fallback hides bugs during snapshot warmup. |
| **T3 `SwimProtocol.members`** | **KEEP — but narrow its public surface.** SWIM owns transient "is this peer answering Pings?" detection. Its observations feed only `HealthReconciler` (the aggregator). Remove direct subscribers (`ClusterEventAggregator`, AetherNode lambda) — they should subscribe to `TopologyChangeNotification` from the observer instead. | SWIM is a Layer 1 concern. Removing the direct fan-out collapses the cascade to one routing per logical edge. |
| **T4 `QuicClusterNetwork.peers`** | **KEEP — narrow to transport-only.** Delete `processViewChange` upward emission. PeerState lifecycle stays for connection management. `disconnect(DisconnectNode)` becomes a transport-internal command driven by a **`MembershipView` subscriber** (added in QUIC), not by a SWIM-observation lambda in AetherNode. | T4 is a Layer 0 concern. Spec §4.1 alignment. |
| **S1 / S2 (CTM anchors)** | **KEEP — but feed only from `MembershipView` deltas.** Remove the `TopologyChangeNotification` listener path; subscribe directly to `MembershipView` epoch transitions. | Eliminates double-anchor-bump from QUIC + KV path duplication. |
| **S3 / S4 (HealthReconciler aggregator + cooldown)** | **KEEP — but rename/reframe.** S3 should genuinely implement the spec's quorum-of-observations rule (k-of-n across nodes via `PeerObservationStore.subscribeHealth`), not the current per-node sliding window. | Spec §4.3 decision rule 1. |
| **S5 (cluster phase)** | **KEEP — already correct.** | Spec §5 alignment. |
| **S6 (`everSeenHealthy`)** | **KEEP but loosen.** Lift cold-boot suppression as soon as `ClusterPhaseKey == NORMAL`, not only on per-peer ALIVE evidence. Otherwise a peer that goes FAULTY before any successful Ping never produces a `FaultyObserved`. | D7 above. |

### 4.3 Resulting flow for the "peer-X dies" scenario

```
T0  peer-W's SwimProtocol.transitionToFaulty(peer-X)                        (Δ T3)
    └─ emitFaulty → SINGLE listener: HealthReconciler.onSwimObservation
        └─ aggregator: per-peer cross-node observation count via PeerObservationStore
        └─ if leader AND k-of-n satisfied AND post-cooldown:
            propose Put<NodeLifecycleKey, DECOMMISSIONED>
T1  Rabia commit
    └─ MembershipView re-projection                                          (Δ T1)
    └─ TopologyObserver diffs MembershipView_old vs MembershipView_new
        └─ emits SINGLE TopologyChangeNotification.NodeRemoved(peer-X)
            ├─ CTM.onTopologyChange → anchor logic (single bump path)
            ├─ LoadBalancerManager / TaskAssignmentCoordinator / SliceInvoker
            │   / HttpForwarder / AppHttpServer (each fires once)
            ├─ MembershipView subscriber inside QuicClusterNetwork:
            │   └─ if peer no longer in onDutyMemberIds: peer.evict
            │   (T4 transition driven by membership, not by SWIM directly)
            └─ ClusterEventAggregator.onTopologyChange (NOT onSwimObservation)
                 → SINGLE NODE_FAILED entry per node (still local-witness,
                   but driven by the *snapshot* delta, not SWIM directly —
                   same UX, different driver)
```

**Cascade reduction:** N (surviving nodes) × duplicate-routing → 1 routing per logical edge. Anchor-storm structurally impossible.

---

## 5. Implementation plan (ordered, surgical)

### Pre-condition

All steps assume **release-1.0.0-rc1** branch state at HEAD `4dae32be2` (or later). `./build.sh` green before starting; integration test 12-network passing on localhost (it does).

### Step 1 — Add `MembershipView` delta-diff publisher inside `TopologyObserver`

**Files:**
- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/TopologyObserver.java` (~30 lines added)
- New: `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/MembershipDelta.java` (record holding `added`, `removed`, `phaseChanged` sets)

**Touchpoints:**
- `TopologyObserver.Manager.evaluateQuorumState` (line 424) — extend to also diff prev-vs-current `coreMemberIds` / `onDutyMemberIds` and emit `TopologyChangeNotification.NodeAdded` / `NodeRemoved` if not already covered by the existing publisher.
- Subscribe internally to `GenerationSnapshotSource` snapshot updates so any KV-driven snapshot change triggers a diff.

**Risk:** **MEDIUM.** The observer's `started` lifecycle gate must be respected; an early diff emission before AetherNode's router delegate is wired causes NPE (the current code carefully serializes this in `start()` lines 524–544). Mirror that pattern.

**Validation:** Unit test diffing two `MembershipView` instances and asserting one notification per edge.

### Step 2 — Make `MembershipView` deltas the SOLE driver of `TopologyChangeNotification.NodeRemoved` / `NodeDown`

**Files:**
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java` (delete `processViewChange` REMOVE/SHUTDOWN routing on lines 1079–1095; keep `peerStateListener` calls, kill `router.route(viewChange)` for those ops; line 1112)
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java:400` (`disconnect` method) — remove `processViewChange(REMOVE, nodeId)` call. The membership delta from `MembershipView` will fire the notification instead.

**Risk:** **HIGH.** This is the load-bearing change. 15 receivers across 8 modules consume `TopologyChangeNotification.NodeRemoved`. Before this step, run a grep audit confirming every receiver is also subscribed to `MembershipView` deltas (or to the topology notification published by step 1's diff). Specifically:
- `SliceInvoker`, `HttpForwarder`, `AppHttpServer` listen for endpoint/route registry teardown — these MUST fire on the `MembershipView` delta path, not only on QUIC view-change.
- `LoadBalancerManager`, `TaskAssignmentCoordinator` rebalance work — same.
- `CDM.onTopologyChange` reconciles slice deployments — same.
- `ClusterSyncScheduler/Collector`, `DeploymentMetricsCollector/Scheduler` retire metrics — same.
- `ControlLoop.onTopologyChange` recomputes scaling decisions — same.

**Validation:** Run integration suites 02-chaos, 03-scaling, 12-network on localhost and on remote Hetzner cluster A. The membership-delta-driven path must produce exactly one routing per departure.

### Step 3 — Drop the AetherNode local SWIM-FAULTY-to-`disconnect` lambda

**Files:**
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java:1181-1186`

Replace the lambda with a `MembershipView` subscriber inside `QuicClusterNetwork` that, when a peer leaves the `onDutyMemberIds` set, calls `peer.evict` directly without going through `disconnect()`.

**Touchpoints:**
- New method `QuicClusterNetwork.onMembershipDelta(MembershipDelta)` — for each removed peer, evict the local PeerState and tear down the per-peer queue.
- AetherNode lambda → deleted.
- `SwimHealthContext.routeFaulty` (line 177–187) — leader's `routeDisconnect` is no longer needed because membership-delta drives eviction. Delete the leader-only `routeDisconnect(peer)` call; keep the `emitLeaderHint` + `bufferHealthObservation` for the leader-side aggregation path.

**Risk:** **MEDIUM.** Test harness's "kill -9 then expect cluster to converge" flow now has one less belt-and-braces path. Verify membership-delta latency is bounded (Rabia round) and that PeerState eviction within the protection window is still safe.

**Validation:** 12-network kill-leader test on cloud — must pass within the existing timeout budget.

### Step 4 — Re-source `ClusterEventAggregator.onSwimObservation` to `onTopologyChange`

**Files:**
- `aether/node/src/main/java/org/pragmatica/aether/api/ClusterEventAggregator.java:99-107` — keep the method but stop registering it as a SWIM observation listener.
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java:1170` — remove `swimHealthDetector.addObservationListener(eventAggregator::onSwimObservation)`.
- Add a new path: `eventAggregator` subscribes to `TopologyChangeNotification.NodeRemoved` / `NodeDown` (already does — see `onNodeRemoved`, `onNodeDown` if they exist; otherwise add).
- Or, alternatively, subscribe to `MembershipView` deltas directly.

**Risk:** **LOW.** Source identity in events shifts from `swim-observation` to `topology-delta`. The `f7a6f6f2a` commit message explains why local-witness emit was added — that justification is preserved because every node observes the membership delta locally too.

**Validation:** Test harnesses that union `NODE_FAILED` events across nodes still see them; the source field changes value.

### Step 5 — Strip `TopologyObserver.nodeStatesById` of `NodeHealth` and the legacy fallback paths

**Files:**
- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/TopologyObserver.java`
  - Remove `NodeHealth` field from `NodeState`.
  - Remove `legacyHealthyActivePeerCount` (line 461–468), `activeTopologySize` (line 389–394).
  - `healthyActiveNodeCount` (line 376–387) and `readyNodeCount` (line 363–373) become snapshot-only with explicit "snapshot not yet available → return Option.empty()" semantics.
- All callers of `healthyActiveNodeCount` / `readyNodeCount` updated to handle the empty case explicitly.

**Risk:** **MEDIUM.** Cold-boot windows where the snapshot is empty currently silently fall back to the legacy in-memory count. After this change, callers must wait for the snapshot or accept a "0 healthy nodes" reading. Grep audit + targeted unit tests required.

**Validation:** Cold-boot integration test (5-node cluster from scratch) — `TopologyObserver.healthyActiveNodeCount` must return the snapshot-derived value the moment the snapshot is published, not before.

### Step 6 — Loosen SWIM cold-boot suppression to phase-aware, not per-peer-ever-healthy

**Files:**
- `integrations/swim/src/main/java/org/pragmatica/swim/SwimProtocol.java:703-711` (`emitFaultyOrUnknown`)
- `aether/node/src/main/java/org/pragmatica/aether/node/health/CoreSwimHealthDetector.java` — add a `Supplier<ClusterPhase>` injected via the existing context, so SWIM can query "are we in BOOTING phase?".

In `emitFaultyOrUnknown`, suppress only if `phase == BOOTING`. Once `NORMAL` is reached, `FaultyObserved` always fires regardless of `everSeenHealthy`.

**Risk:** **MEDIUM.** Behavior change during cold boot. Test the 5-node cold-start scenario to ensure no false-positive DECOMMISSIONED writes during initial seed-peer ALIVE convergence.

**Validation:** Cold-boot test plus container-restart-during-cold-boot test on remote.

### Step 7 — Replace per-node `ObservationAggregator` with cross-node `PeerObservationStore`-backed quorum

**Files:**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/health/HealthReconcilerImpl.java` — strip `ObservationAggregator`. On every `SwimObservation.FaultyObserved`, push a `PeerHealthObservation(peer, FAULTY, epoch)` via the existing `PeerObservationStore`. The leader periodically reads the store, computes ⌈N/2⌉+1 across nodes, and writes `DECOMMISSIONED`.

**Risk:** **HIGH.** Aggregation semantics change. ClusterSync infrastructure (`PeerObservationStore`, `ClusterSyncCollector`, `ClusterSyncScheduler`) already exists and is wired — but the leader-side reduction to a quorum decision is currently absent. Need a dedicated reducer.

**Validation:** Add unit test for `HealthReconcilerImpl.handleQuorumOfObservations` covering 5-node case with 3/5 reporting FAULTY.

### Step 8 — Cleanup pass

- Delete `TopologyObserver.handleConnectionEstablished/Failed` if any remnants.
- Remove `routeDisconnect` from `SwimHealthContext` if step 3 retired its callers.
- Update `aether/docs/specs/membership-architecture-spec.md` §13 ("Migration Note") with the implementation status (R1–R3 still TBD; D1, D6 closed).
- Add a small architecture diagram to `aether/docs/internal/` showing the consolidated flow.

**Risk:** **LOW.**

---

## 6. At-risk areas summary

| Area | Risk | Why |
|---|---|---|
| **Step 2 (delete `processViewChange` upward emit)** | **HIGH** | 15 subscribers must already be receiving the same notification through the membership-delta path; any miss = silent endpoint registry / route table stale. |
| **Step 7 (cross-node quorum aggregation)** | **HIGH** | Requires functional `PeerObservationStore` reducer. Currently absent. |
| **Step 3 (drop AetherNode lambda)** | **MEDIUM** | Removes a defensive belt; relies on `MembershipView` delta latency. |
| **Step 5 (strip `NodeHealth` from TopologyObserver)** | **MEDIUM** | Cold-boot windows where snapshot is empty change behavior. |
| **Step 6 (phase-aware cold-boot suppression)** | **MEDIUM** | Could surface FAULTY events during cold-boot that the current suppression silently dropped. |
| **Step 1 (`MembershipView` delta publisher)** | **MEDIUM** | Lifecycle-gate ordering against `started` must mirror existing `evaluateQuorumState` discipline. |
| **Step 4 (re-source ClusterEventAggregator)** | **LOW** | Event source field changes; UX unchanged. |
| **Step 8 (cleanup)** | **LOW** | Pure deletions. |

**Estimated total effort:** 5–7 days of focused work, plus 2–3 days of integration test stabilization on remote (Hetzner cluster A + B) before merge.

---

## 7. Open questions

1. **Step 7 timing on remote:** does the `PeerObservationStore`-backed quorum aggregation respect the cluster B chaos-test deadlines (kill-then-converge < 30 s)? Membership-delta latency = (one Rabia round to write `DECOMMISSIONED`) + (snapshot re-projection ~50 ms) + (notification dispatch). Worst case ~ 500 ms on cloud. Should be fine but needs measurement.
2. **Reconfigure under in-flight membership delta:** if cluster size changes (5 → 7) WHILE a peer is in the middle of the FAULTY → DECOMMISSIONED flow, the `desiredCoreSize` shift could reset the aggregator window. Need explicit handling.
3. **`SwimHealthContext.routeFaulty`'s leader-only `routeDisconnect`:** the comment notes followers may also `routeDisconnect` if the faulty peer is the **current leader** (line 184). After step 3, this disappears entirely. Confirm via experimentation that membership-delta-driven eviction handles the dead-leader case fast enough for re-election to succeed within budget.
4. **Backwards compat:** spec §13 explicitly disclaims a rolling-upgrade path. This audit's plan inherits that constraint.

---

**END OF AUDIT**
