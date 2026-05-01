# Membership Architecture Specification

**Status:** Draft v1
**Date:** 2026-05-01
**Branch target:** `release-1.0.0-rc1`
**Scope:** Full redesign of cross-layer signal flow between QUIC, SWIM, HealthReconciler, TopologyObserver, Rabia, leader election, auto-heal (CTM), and node lifecycle. Backwards-compatibility is **not** a constraint.

---

## 1. Motivation

The rc1 codebase exhibits a chronic class of bugs traceable to **bidirectional, cyclic, cross-layer signal flow**. Investigation throughout the 2026-04-30 / 2026-05-01 sessions traced specific symptoms (cold-boot 240+s consensus stall, phantom CTM provisioning under load, anchor-storm flap, Rabia engine resets to Phase 0, 6-minute leader-election cascades) to the same root cause: transport-level events drive membership decisions; membership decisions feed transport decisions; both feed leader election, auto-heal, and consensus reset. There is no clear hierarchy.

Layered fixes (Phase A demoting QUIC HEALTHY, Fix-1 KV-sync grace, Fix A peer-observation timer, Option C TopologyObserver canonical publisher) have moved individual signal sources between layers but have not eliminated the cycles. Each fix surfaces a downstream variant of the same noise pattern.

This specification proposes a **strict one-way layering** with explicit signal contracts, a first-class **cold-boot mode**, and decoupled state machines that each handle one concern cleanly. Implementation requires breaking changes to wire formats, FSM event types, and several existing public APIs.

## 2. Design Principles

### P1. One-way signal flow
Each layer reads from layers below; emits signals upward (or laterally to peer layers); never reads or writes layers above. **No layer reacts to layers above it.** No layer skips levels.

### P2. Layered separation of concerns
Each layer owns exactly one concern. Layers are not allowed to fuse responsibilities (e.g. transport must not make membership decisions; membership must not perform consensus).

### P3. Explicit phases over implicit state
Cold-boot, normal operation, and recovery are *first-class phases* with explicit transitions, not implicit consequences of timing. Code should query "what phase are we in?" not "are we sufficiently warmed up?"

### P4. Single-writer, append-only authoritative state
`NodeLifecycleKey` (and all membership-affecting KV atoms) have exactly one writer (HealthReconciler), driven through Rabia consensus. Notifications fire on consensus commit, not on optimistic local writes.

### P5. Idempotent edge-transition semantics
Every consumer of state-change notifications must be idempotent against duplicate notifications. Producers must guarantee one notification per actual edge transition.

### P6. Durable consensus state
Rabia state is durable across transient signal interruptions. Quorum-loss is a *paused* condition, not a *reset* condition. Reset is a separate, explicit reconfiguration command.

### P7. Test contracts match operator contracts
Tests must consume the exact same observable signals operators use (`/health/ready`, `/api/cluster/topology`). No derived/internal predicates.

---

## 3. Layer Architecture

```
   ┌─────────────────────────────────────────────────────────────────────┐
   │ Layer 7: Node Lifecycle FSM (Booting → Joining → Active → ...)      │
   │   - Per-node state machine                                          │
   │   - Owns /health/live and /health/ready response                    │
   └─────────────────────────────────────────────────────────────────────┘
                                ▲
   ┌─────────────────────────────────────────────────────────────────────┐
   │ Layer 6: Auto-Heal (CTM)                                            │
   │   - Reads MembershipView (KV-derived). Provisions/decommissions.    │
   │   - Suspended in cold-boot mode.                                    │
   └─────────────────────────────────────────────────────────────────────┘
                                ▲
   ┌─────────────────────────────────────────────────────────────────────┐
   │ Layer 5: Leader Election                                            │
   │   - Rank-staircase proposals. Adopts peer leader on observation.    │
   └─────────────────────────────────────────────────────────────────────┘
                                ▲
   ┌─────────────────────────────────────────────────────────────────────┐
   │ Layer 4: Consensus (Rabia)                                          │
   │   - Durable across transient quorum loss. Reset only on explicit    │
   │     reconfiguration command.                                        │
   └─────────────────────────────────────────────────────────────────────┘
                                ▲
   ┌─────────────────────────────────────────────────────────────────────┐
   │ Layer 3: Topology View (TopologyObserver)                           │
   │   - Pure projection of KV NodeLifecycleKey + ClusterConfigKey.      │
   │   - Sole publisher of TopologyChangeNotification & QuorumState.     │
   └─────────────────────────────────────────────────────────────────────┘
                                ▲
   ┌─────────────────────────────────────────────────────────────────────┐
   │ Layer 2: Authoritative Membership (HealthReconciler)                │
   │   - Single writer of NodeLifecycleKey via Rabia consensus.          │
   │   - Quorum-of-observations rule + cooldown + cold-boot suppression. │
   └─────────────────────────────────────────────────────────────────────┘
                                ▲
   ┌─────────────────────────────────────────────────────────────────────┐
   │ Layer 1: Health Observation (SWIM)                                  │
   │   - Gossip-aggregated. HEALTHY / SUSPECTED / FAULTY.                │
   │   - Suspect window 15s (shortenable by Layer 0 hint).               │
   └─────────────────────────────────────────────────────────────────────┘
                                ▲
   ┌─────────────────────────────────────────────────────────────────────┐
   │ Layer 0: Transport (QUIC)                                           │
   │   - Byte movement. Per-peer connection state.                       │
   │   - Emits TransportObservation as informational hint only.          │
   └─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Layer Specifications

### 4.1 Layer 0 — Transport (QUIC)

**Responsibility:** Byte movement between known peers. Connection establishment, reconnect logic, backpressure, stream multiplexing.

**Public surface:**
```java
interface ClusterTransport {
    Promise<Unit> send(NodeId target, byte[] payload, StreamType stream);
    Promise<Unit> broadcast(byte[] payload, StreamType stream);
    Option<TransportConnectionState> connectionState(NodeId peer);  // queryable, not signal-driving
    Promise<Unit> start(int port);
    Promise<Unit> stop();
}
```

**Internal state:** per-peer `PeerState` (CONNECTING/CONNECTED/REMOVED), reconnect backoff, peerLinks table.

**Signals emitted upward (one only):**
```java
sealed interface TransportObservation {
    record PeerReachable(NodeId peer) implements TransportObservation {}
    record PeerUnreachable(NodeId peer, Cause cause) implements TransportObservation {}
}
```

`TransportObservation` is an **informational hint**. Layer 1 (SWIM) MAY use it to shorten its own suspect window for a peer (e.g., upon `PeerUnreachable` from local transport, SWIM may downgrade suspect timeout for that specific peer from 15s to 3s). Layer 1 MUST NOT translate transport observations directly into authoritative HEALTHY/FAULTY signals — those still go through SWIM gossip aggregation.

**What Layer 0 must NOT do:**
- Emit `TopologyChangeNotification` (any variant)
- Emit `QuorumStateNotification` (any variant)
- Call `topologyManager.registerPeer` or `unregisterPeer`
- Call `peerObservationStore.pushHealth`
- Mutate the auto-heal stability anchor
- Trigger `RabiaEngine.clusterDisconnected` / `clusterConnected`
- Emit `ClusterFsmEvent.QuorumEstablished` / `QuorumDisappeared`
- Write to any KV atom

### 4.2 Layer 1 — Health Observation (SWIM)

**Responsibility:** Gossip-based failure detection. Per-peer health signal with intentional noise filtering.

**Public surface:**
```java
interface SwimMembership {
    Promise<Unit> start(List<NodeAddress> seeds);
    Promise<Unit> stop();
    Stream<SwimObservation> observations();  // hot stream of peer state changes
    Option<SwimHealth> currentHealth(NodeId peer);
    Option<List<NodeId>> currentMembers();
    void recordTransportHint(TransportObservation hint);  // optional faster suspect path
}
```

**State:** per-peer `SwimHealth` (HEALTHY / SUSPECTED / FAULTY), gossip log, incarnation numbers.

**Signals emitted:**
```java
sealed interface SwimObservation {
    record HealthyObserved(NodeId peer, long incarnation) implements SwimObservation {}
    record SuspectObserved(NodeId peer, long incarnation) implements SwimObservation {}
    record FaultyObserved(NodeId peer, long incarnation) implements SwimObservation {}
    record DeparturedObserved(NodeId peer, long incarnation) implements SwimObservation {}
}
```

**Behavior contract:**
- SUSPECT window: 15s default. Configurable. May be shortened to ≥3s for a specific peer if Layer 0 reports `PeerUnreachable` for that peer (acknowledging the local channel as concurring evidence).
- FAULTY transition: requires either suspect-window expiration OR k-of-n peers reporting suspect (k = quorum-size, n = members).
- Recovery from SUSPECT → HEALTHY: any indirect ping success.
- Cold-boot mode (Layer 2 broadcasts cold-boot phase): SWIM does NOT emit `FaultyObserved` for nodes that have never been observed HEALTHY. (Reason: a never-observed peer during cold boot is "not yet here," not "failed.")

**What SWIM must NOT do:**
- Make membership decisions (i.e., write to NodeLifecycleKey)
- Bypass its own debounce on transport hints
- Suppress its observations after Stopped/Starting (Q6 semantics removed)

### 4.3 Layer 2 — Authoritative Membership (HealthReconciler)

**Responsibility:** Decide and persist authoritative cluster membership. Sole writer of `NodeLifecycleKey` via consensus.

**Public surface:**
```java
interface HealthReconciler {
    Promise<Unit> start();
    Promise<Unit> stop();
    void onSwimObservation(SwimObservation obs);
    Promise<Unit> requestDrain(NodeId peer);          // explicit operator command
    Promise<Unit> requestDecommission(NodeId peer);   // explicit operator command
    ColdBootPhase phase();                            // BOOTING | NORMAL | RECOVERING
}
```

**Decision rules:**
1. **Quorum-of-observations**: A FAULTY observation requires ⌈N/2⌉+1 nodes (where N = currently-ON_DUTY count) reporting FAULTY for the target peer for ≥ `decision_window` (default 10s) before HealthReconciler writes `NodeLifecycleKey = DECOMMISSIONED`.
2. **Cooldown**: After any state transition for a peer, no further transitions for that peer for `transition_cooldown` (default 30s).
3. **Cold-boot suppression**: While in BOOTING phase, no LEFT/DECOMMISSIONED transitions are written. Only JOINING → ON_DUTY transitions are allowed.
4. **Single-writer enforcement**: All writes go through `Rabia.propose(KVCommand.Put<NodeLifecycleKey,_>)`. Only the leader is allowed to propose; followers route their decisions through `ClusterSyncPong` to the leader.

**State:**
```java
record ReconcilerState(
    ColdBootPhase phase,
    Map<NodeId, ObservationAggregator> observations,   // per-peer, k-of-n tracking
    Map<NodeId, Instant> lastTransitionAt,             // per-peer cooldown
    Instant clusterStartAt
) {}
```

**Cold-boot phase transitions (Layer 2 owns this):**

```
[ Cluster cold-start: leader undefined, KV empty ]
                  │
                  ▼
            ┌───────────┐
            │  BOOTING  │  ← All FAULTY suppression. Auto-heal suspended.
            └─────┬─────┘     Only ON_DUTY writes allowed.
                  │
       (configured cluster_size of nodes ON_DUTY for ≥ stable_window)
                  │
                  ▼
            ┌───────────┐
            │  NORMAL   │  ← Full signal processing.
            └─────┬─────┘
                  │
       (HealthReconciler writes < quorum ON_DUTY)
                  │
                  ▼
            ┌───────────┐
            │ RECOVERING│  ← Auto-heal active. SWIM running.
            └─────┬─────┘     But cluster is below quorum.
                  │
       (quorum re-established for ≥ recovery_stable_window)
                  │
                  ▼
            (back to NORMAL)
```

**Phase broadcasts:** HealthReconciler broadcasts phase transitions on a topic-bus message `ClusterPhaseChanged(phase)`. Layers 1 (SWIM), 6 (CTM) subscribe.

### 4.4 Layer 3 — Topology View (TopologyObserver)

**Responsibility:** Pure read-only projection of KV atoms. Sole publisher of `TopologyChangeNotification` and `QuorumStateNotification`.

**Public surface:**
```java
interface TopologyView {
    NodeInfo self();
    List<NodeId> currentMembers();
    int clusterSize();
    int quorumSize();
    int healthyOnDutyCount();
    Option<NodeId> currentLeader();
    MembershipView snapshot();
}
```

**Inputs:** subscribes to `KVStoreNotification.ValuePut/Remove<NodeLifecycleKey>` and `KVStoreNotification.ValuePut<ClusterConfigKey>`. **NO other input channels.**

**Signals emitted:**
```java
sealed interface TopologyChangeNotification {
    record NodeJoined(NodeId nodeId, MembershipView view) {}
    record NodeOnDuty(NodeId nodeId, MembershipView view) {}
    record NodeDraining(NodeId nodeId, MembershipView view) {}
    record NodeRemoved(NodeId nodeId, RemovalReason reason, MembershipView view) {}
}

sealed interface QuorumStateNotification {
    record Established(int sequence) {}
    record Disappeared(int sequence) {}
    record Reconfigured(int oldSize, int newSize, int sequence) {}
}
```

**Edge-transition semantics:**
- `NodeOnDuty` fires on first transition of a peer's `NodeLifecycleKey` value into `ON_DUTY`.
- `NodeRemoved` fires on first transition into `DECOMMISSIONED` or `SHUTTING_DOWN`, OR on KV REMOVE.
- `NodeDraining` fires on first transition into `DRAINING`.
- `NodeJoined` fires on first transition into `JOINING`.

**Quorum latch:** atomic `quorumEstablished` boolean. `evaluateQuorumState` runs after each KV-atom-driven mutation. Edge transitions emit `Established(seq++)` or `Disappeared(seq++)` exactly once per edge.

**What TopologyObserver must NOT do:**
- Have any `registerPeer/unregisterPeer/markReady/markDeparted/handleConnectionFailed/handleConnectionEstablished` API. These are removed entirely.
- Be writeable from any layer. The internal state is computed from KV atom subscriptions only.
- React to `TransportObservation`, `SwimObservation`, or any non-KV signal.

### 4.5 Layer 4 — Consensus (Rabia)

**Responsibility:** Total-ordered command commits. Durable state across transient interruptions.

**Public surface:** unchanged from current `RabiaNode`/`RabiaEngine`. Behavior changes:

**Lifecycle states:**
```
   ┌──────────┐
   │ Stopped  │
   └────┬─────┘
        │ start()
        ▼
   ┌──────────┐
   │ Syncing  │  ← initial sync from peers
   └────┬─────┘
        │ sync complete
        ▼
   ┌──────────┐
   │ Active   │  ← accepts proposals, commits decisions
   └─┬──────┬─┘
     │      │ pause(): quorum lost
     │      ▼
     │   ┌──────────┐
     │   │ Paused   │  ← keeps state, refuses proposals,
     │   └────┬─────┘    drops nothing, accepts inbound votes
     │        │ resume(): quorum regained
     │        ▼
     │   (back to Active, same Phase as before)
     │
     │ explicit reconfigure command
     ▼
   (transition through Stopped → Syncing → Active with NEW config)
```

**Critical change:** `Paused` state is new. Previous `clusterDisconnected()` path **deleted**. There is no path that resets `currentPhase` to ZERO except explicit reconfiguration via Rabia command.

**Quorum-loss handling:**
- HealthReconciler observes loss → emits `Disappeared` → Rabia transitions Active → Paused
- Paused state retains: `currentPhase`, `phases` map, `pendingBatches`, `bufferedDecisions`, `correlationMap`
- Paused state refuses new `propose()` calls (returns `Result.failure(NotInQuorum)`)
- Paused state ACCEPTS inbound `Decision` messages and applies them (recovers transparently when quorum returns)
- HealthReconciler observes quorum re-established → emits `Established` → Rabia transitions Paused → Active

**Reconfigure command:** `RabiaCommand.Reconfigure(newClusterConfig)` is a special Rabia-level command (not a regular `KVCommand`) that, when committed, atomically:
1. Writes new `ClusterConfigKey` value
2. Resets per-peer phase counters
3. Drains in-flight proposals (failing them)
4. Transitions all engines to `Syncing` to re-sync against new config

**No `currentPhase = Phase.ZERO` write outside `Reconfigure` command.**

### 4.6 Layer 5 — Leader Election

**Responsibility:** Maintain a current leader committed to KV `LeaderKey`. Robust against cold-boot election storms.

**Public surface:** unchanged from current `LeaderManager`/`LeaderElectionFsm`.

**Behavior changes:**

**Rank-staircase proposal scheduling:** when entering `Electing`, the FSM does NOT propose immediately. Instead:
- `proposalDelay = baseElectionDelay + rank * perRankDelay + jitter`
- where `rank` is the position of this node's NodeId in the lexicographically-sorted member list (lowest = rank 0)
- `baseElectionDelay = 2s`, `perRankDelay = leader_election_round_time + safety_margin` (default 5s)

This means at cold-boot in a 5-node cluster:
- node-1 (rank 0): proposes at T+2s+jitter
- node-2 (rank 1): proposes at T+7s+jitter (only if no leader committed by then)
- node-3 (rank 2): proposes at T+12s+jitter
- node-4 (rank 3): proposes at T+17s+jitter
- node-5 (rank 4): proposes at T+22s+jitter

In practice, only node-1 ever proposes; the others observe `LeaderKey=node-1` in KV and short-circuit before their proposal timer fires. Election storm eliminated structurally.

**Observation preempts proposal (always):** in `Electing`, the FSM listens for `KVStoreNotification.ValuePut<LeaderKey>` continuously, on a dedicated 500ms timer that is independent of the proposal lifecycle (`proposalInFlight` does NOT gate this listener). On observation of any `LeaderKey` value pointing to a member of the current topology, the FSM transitions to `Led(observedLeader)` immediately, cancelling its own pending proposal.

**KV-sync grace:** on first entry to `Electing` after `Joining` (cold-boot path), the FSM enters `AwaitingKvSync` with a 3s grace window. If `LeaderKey` arrives in KV during the grace, transition to `Led`. Otherwise transition to `Electing` and start the rank-staircase timer.

**State machine:**
```
   ┌──────────┐
   │ Dormant  │ (pre-quorum)
   └────┬─────┘
        │ QuorumEstablished
        ▼
   ┌──────────────────┐
   │ AwaitingKvSync   │ ← 3s grace for peer KV-sync
   └────┬──────┬──────┘
        │      │ LeaderKey observed
        │      ▼
        │   ┌──────┐
        │   │ Led  │
        │   └──┬───┘
        │      │
        │      │ leader unhealthy / leader different in KV / quorum lost
        │      │
        │      ▼
        │   (back to AwaitingKvSync or Dormant)
        │
        │ grace elapsed without observation
        ▼
   ┌──────────┐
   │ Electing │ ← rank-staircase proposal + 500ms KV poll
   └────┬─────┘
        │ proposal succeeds OR LeaderKey observed
        ▼
      (Led)
```

### 4.7 Layer 6 — Auto-Heal (CTM)

**Responsibility:** Provision replacement containers when on-duty count drops below desired. Decommission excess.

**Public surface:** unchanged from current `ClusterTopologyManager`.

**Behavior changes:**

**Sole input source:** subscribes to `TopologyChangeNotification` from Layer 3. **No other input.**

**Phase awareness:** subscribes to `ClusterPhaseChanged` from Layer 2. CTM only operates in `NORMAL` phase. In `BOOTING` and `RECOVERING`, CTM is suspended (no provisioning, no decommissioning).

**Stability anchor:** bumped only on edge transitions of `MembershipView.healthyOnDutyCount()`. NOT on `TransportObservation`, NOT on `SwimObservation`, NOT on any per-peer event that doesn't change the on-duty count.

**Decision loop:**
```
every 5s, only if phase == NORMAL:
  desired = currentClusterConfig.desiredSize
  actual  = view.healthyOnDutyCount()
  
  if actual < desired AND time_since_last_anchor_bump > stability_window (30s):
      provision(desired - actual)
  
  if actual > desired:
      decommission(actual - desired)  // graceful drain via NodeLifecycleKey = DRAINING
```

**HIGH-18 (phantom replacement) protection:** the stability window itself, gated only on authoritative KV transitions, eliminates the phantom-replacement-during-flap class of bugs without requiring per-event anchor bumps. SWIM's intentional debounce upstream, plus HealthReconciler's quorum-of-observations rule, plus the cooldown, plus the 30s stability window — collectively make transient flap a non-event for CTM.

### 4.8 Layer 7 — Node Lifecycle FSM

**Responsibility:** Per-node lifecycle state. Owns response semantics for `/health/live`, `/health/ready`, `/api/health`.

**State machine:**
```
   ┌────────────┐
   │  Booting   │ ← JVM up, configuration loaded
   └────┬───────┘
        │ start cluster transports + persistence + etc.
        ▼
   ┌────────────┐
   │  Joining   │ ← QUIC mesh forming, KV-syncing from peers
   └────┬───────┘
        │ KV sync complete + own NodeLifecycleKey == ON_DUTY
        ▼
   ┌────────────┐
   │  Active    │ ← consensus Active, routes synced, ready for work
   └─┬────────┬─┘
     │        │ admin: drain
     │        ▼
     │   ┌────────────┐
     │   │  Draining  │ ← NodeLifecycleKey = DRAINING. Refuses new work.
     │   └────┬───────┘
     │        │ in-flight work complete + drain timeout
     │        ▼
     │   ┌────────────┐
     │   │  Stopped   │ (terminal)
     │   └────────────┘
     │
     │ admin: hard-stop / SIGTERM / process exit
     ▼
   (Stopped — no graceful drain)
```

**Health endpoint contract:**
- `/health/live` returns 200 in `Booting` onward. 503 only if process is unable to respond (i.e., never).
- `/health/ready` returns 200 ONLY in `Active`. Returns 503 with explanatory body in all other states.
- `/api/health` returns aggregate cluster health (different concern; reads `MembershipView`).

**Implementation:** the existing `buildReadinessResponse` collapses to a single check: `lifecycle.phase() == Active`. The components (consensus/routes/quorum) become INTERNAL to the lifecycle FSM's `Joining → Active` transition gates, not exposed as separate UP/DOWN signals.

---

## 5. Cold-Boot Mode

### 5.1 Detection
A node enters BOOTING phase when:
1. Local `NodeLifecycleKey` for self is unset (first boot or post-`down -v`)
2. Cluster KV has no committed `LeaderKey` OR fewer than quorum-size `ON_DUTY` peers visible

The phase is determined cluster-wide by HealthReconciler via consensus on a `ClusterPhaseKey` atom. All nodes observe the same phase via KV subscription.

### 5.2 Behavior in BOOTING

| Layer | Behavior |
|---|---|
| Transport | Normal (continues handshakes, reconnects) |
| SWIM | Does NOT emit `FaultyObserved` for nodes that have never been HEALTHY |
| HealthReconciler | Only writes JOINING → ON_DUTY transitions. No LEFT/DECOMMISSIONED/SHUTTING_DOWN writes (other than admin commands). |
| TopologyObserver | Normal (publishes notifications based on KV state) |
| Rabia | Normal but tolerant: paused-resume cycles common, no resets |
| Leader Election | Rank-staircase + KV-sync grace as described |
| Auto-heal | **Suspended.** No provisioning, no decommissioning. |
| Node Lifecycle | Joining (per-node) |

### 5.3 Exit conditions (BOOTING → NORMAL)
ALL of:
- `MembershipView.healthyOnDutyCount() == clusterSize()` (all configured nodes ON_DUTY)
- `currentLeader().isPresent()` (leader committed in KV)
- Cluster has been stable in this state for ≥ `stable_window` (default 5s)

When all conditions met, HealthReconciler proposes `ClusterPhaseKey = NORMAL`. On consensus commit, all nodes observe and apply the phase change.

### 5.4 RECOVERING phase
Symmetric to BOOTING but entered when, in NORMAL phase, healthyOnDutyCount drops below quorum-size. Behaviors:
- Auto-heal active (provisioning replacements)
- HealthReconciler may decommission unrecoverable peers
- Leader election allowed (in case current leader is one of the lost peers)
- Exit: quorum regained for ≥ `recovery_stable_window` (default 30s) → NORMAL.

---

## 6. Signal Catalog

Complete list of cross-layer signals after this redesign. Anything not on this list does not exist.

| From → To | Signal | Type | Notes |
|---|---|---|---|
| Layer 0 → Layer 1 | `TransportObservation.PeerReachable/Unreachable` | informational | SWIM may use as suspect-shortener hint |
| Layer 1 → Layer 2 | `SwimObservation.HealthyObserved/Suspect/Faulty/Departed` | observation | input to reconciler decision |
| Layer 2 → Layer 4 | `RabiaCommand.Put<NodeLifecycleKey, NodeLifecycleValue>` | command | proposed via consensus |
| Layer 2 → all | `ClusterPhaseChanged(phase)` | broadcast | published via Rabia commit on `ClusterPhaseKey` |
| Layer 4 → Layer 3 | `KVStoreNotification.ValuePut/Remove<NodeLifecycleKey, ClusterConfigKey, ClusterPhaseKey, LeaderKey>` | notification | derived from consensus commit |
| Layer 3 → Layer 4 | (nothing — Layer 3 is read-only) | — | — |
| Layer 3 → Layer 5 | `KVStoreNotification.ValuePut<LeaderKey>` (relayed) | notification | leader election listener |
| Layer 3 → Layer 6 | `TopologyChangeNotification.*`, `QuorumStateNotification.*` | notification | sole CTM input |
| Layer 4 → Layer 5 | `RabiaCommand.Put<LeaderKey, _>` commit | command-result | proposes leader |
| Layer 5 → Layer 7 | `LeaderChange(currentLeader)` | notification | optional — for routing |
| Layer 6 → Layer 0 (provisioning) | command (provision/decommission VM) | RPC | external SPI calls |
| Layer 6 → Layer 4 | `RabiaCommand.Put<ProvisioningSlotKey, _>` | command | tracks in-flight provisioning |
| Layer 7 → all | `LifecyclePhaseChanged(self, phase)` | broadcast | per-node |

**Removed signals (must not exist after redesign):**
- QUIC → anything except `TransportObservation`
- Any `TopologyChangeNotification` emitter other than TopologyObserver
- Any `QuorumStateNotification` emitter other than TopologyObserver
- Direct `topologyManager.registerPeer/unregisterPeer/markReady/markDeparted` callable from outside Layer 2/3
- `RabiaEngine.clusterConnected/clusterDisconnected` (replaced by Active/Paused state transitions)

---

## 7. State Transition Tables

### 7.1 NodeLifecycleValue states

Authoritative state in KV. Transitions only through HealthReconciler via consensus.

```
                  ┌─────────────┐
                  │   JOINING   │  (initial, set by HealthReconciler on node observation)
                  └──────┬──────┘
                         │ SwimObservation: HealthyObserved AND
                         │ post-cold-boot OR explicit promote
                         ▼
                  ┌─────────────┐
                  │   ON_DUTY   │
                  └──┬───────┬──┘
                     │       │ admin: drain
                     │       ▼
                     │  ┌─────────────┐
                     │  │  DRAINING   │
                     │  └──────┬──────┘
                     │         │ drain complete
                     │         ▼
                     │  ┌────────────────┐
                     │  │ SHUTTING_DOWN  │
                     │  └────────┬───────┘
                     │           │ self-confirms shutdown
                     │           ▼
                     │     (KV REMOVE)
                     │
                     │ quorum-of-observations report FAULTY for ≥ decision_window
                     │ AND post-cold-boot AND cooldown elapsed
                     ▼
              ┌─────────────────┐
              │  DECOMMISSIONED │
              └────────┬────────┘
                       │ TTL expiry (e.g., 1 hour)
                       ▼
                 (KV REMOVE)
```

### 7.2 ColdBootPhase states

Stored in `ClusterPhaseKey`. Transitions via HealthReconciler consensus proposal.

```
                  ┌─────────────┐
                  │   BOOTING   │  (cluster start — empty KV)
                  └──────┬──────┘
                         │ healthyOnDuty == clusterSize AND leader committed
                         │ AND stable_window elapsed
                         ▼
                  ┌─────────────┐
                  │   NORMAL    │
                  └──┬───────┬──┘
                     │       │ healthyOnDuty < quorumSize
                     │       ▼
                     │  ┌──────────────┐
                     │  │  RECOVERING  │
                     │  └──────┬───────┘
                     │         │ healthyOnDuty == clusterSize
                     │         │ AND recovery_stable_window elapsed
                     │         ▼
                     │     (back to NORMAL)
                     │
                     │ admin: explicit reconfigure
                     ▼
                 (transitions through BOOTING with new config)
```

### 7.3 RabiaEngine states

```
                  ┌────────────┐
                  │  Stopped   │
                  └─────┬──────┘
                        │ start()
                        ▼
                  ┌────────────┐
                  │  Syncing   │
                  └─────┬──────┘
                        │ sync round complete
                        ▼
                  ┌────────────┐
                  │   Active   │  ← currentPhase ≥ 1, accepts proposals, applies decisions
                  └─┬────────┬─┘
                    │        │ Layer 2 emits Disappeared
                    │        ▼
                    │   ┌────────────┐
                    │   │   Paused   │  ← state retained; refuses new proposals
                    │   └─────┬──────┘
                    │         │ Layer 2 emits Established
                    │         ▼
                    │     (Active, same currentPhase)
                    │
                    │ explicit RabiaCommand.Reconfigure
                    ▼
              (transition through Stopped → Syncing → Active with new config)
```

### 7.4 Leader Election FSM states

```
                  ┌──────────┐
                  │ Dormant  │ (pre-quorum)
                  └────┬─────┘
                       │ QuorumEstablished
                       ▼
                  ┌────────────────┐
                  │ AwaitingKvSync │ ← 3s grace window
                  └─┬────────┬─────┘
                    │        │ LeaderKey observed in KV
                    │        ▼
                    │   ┌────────┐
                    │   │  Led   │ ← steady state
                    │   └────┬───┘
                    │        │ leader removed from topology / quorum lost
                    │        │
                    │        ▼
                    │   (Dormant or AwaitingKvSync)
                    │
                    │ grace elapsed without observation
                    ▼
                  ┌──────────┐
                  │ Electing │ ← rank-staircase proposal + 500ms KV poll
                  └────┬─────┘
                       │ proposal succeeds OR LeaderKey observed
                       ▼
                     (Led)
```

### 7.5 Node Lifecycle FSM states

```
                  ┌────────────┐
                  │  Booting   │ (JVM starting)
                  └─────┬──────┘
                        │ all subsystems started
                        ▼
                  ┌────────────┐
                  │  Joining   │ ← KV-syncing, awaiting own ON_DUTY KV write
                  └─────┬──────┘
                        │ self in NodeLifecycleValue.ON_DUTY
                        │ AND consensus Active
                        │ AND routes synced
                        ▼
                  ┌────────────┐
                  │   Active   │ ← /health/ready returns UP
                  └─┬────────┬─┘
                    │        │ admin: drain
                    │        ▼
                    │   ┌────────────┐
                    │   │  Draining  │
                    │   └─────┬──────┘
                    │         │ drain complete
                    │         ▼
                    │     (Stopped)
                    │
                    │ SIGTERM / process exit
                    ▼
                  (Stopped)
```

---

## 8. API Contract Changes

### 8.1 Removed APIs (compile errors expected on migration)

```java
// All removed from TopologyObserver — gone:
void registerPeer(NodeId, NodeInfo);
void unregisterPeer(NodeId);
void markReady(NodeId);
void markDeparted(NodeId);
void handleConnectionEstablished(...);
void handleConnectionFailed(...);

// Removed from RabiaEngine:
void clusterConnected();
void clusterDisconnected();

// Removed from QuicClusterNetwork / NettyClusterNetwork:
private void processViewChange(ViewChangeOperation, NodeId);
// (replaced by internal-only PeerStateChange dispatch that emits TransportObservation)
```

### 8.2 New APIs

```java
// Layer 0
interface ClusterTransport {
    Promise<Unit> send(NodeId, byte[], StreamType);
    Promise<Unit> broadcast(byte[], StreamType);
    Option<TransportConnectionState> connectionState(NodeId);
    Stream<TransportObservation> observations();
    Promise<Unit> start(int port);
    Promise<Unit> stop();
}

// Layer 2
interface HealthReconciler {
    Promise<Unit> start();
    Promise<Unit> stop();
    void onSwimObservation(SwimObservation);
    Promise<Unit> requestDrain(NodeId);
    Promise<Unit> requestDecommission(NodeId);
    ColdBootPhase phase();
    Stream<ClusterPhaseChanged> phaseChanges();
}

// Layer 4 (Rabia changes)
interface RabiaEngine {
    Promise<Result<Unit>> apply(List<KVCommand>);
    boolean isActive();
    boolean isPaused();          // NEW
    Promise<Unit> reconfigure(ClusterConfig);   // NEW — explicit
    // clusterConnected/clusterDisconnected REMOVED
}

// Layer 7
interface NodeLifecycleFsm {
    NodeLifecyclePhase phase();
    Stream<LifecyclePhaseChanged> phaseChanges();
    Promise<Unit> drain();
}
```

### 8.3 Health endpoint contract

`/health/live` — returns 200 `{"status": "UP", "nodeId": "..."}` always once JVM responds.

`/health/ready`:
- 200 `{"status": "UP", "nodeId": "...", "phase": "Active"}` only in `Active` phase
- 503 `{"status": "DOWN", "nodeId": "...", "phase": "<current>", "reason": "..."}` otherwise

`/api/health` — cluster-wide aggregate; reads `MembershipView` from TopologyObserver.

---

## 9. Implementation Plan (Phased Rollout)

The full redesign is a multi-week effort. Phased to keep the cluster bootable at each step.

### Phase R1: Layer 4 (Rabia) — Add `Paused` state
**Scope:** Rename `clusterDisconnected/clusterConnected` to `pause/resume`. State retained across pause. Reset only on `Reconfigure` command.

**Files:** `RabiaEngine.java`, `RabiaNode.java`, all consumers.

**Risk:** moderate. Existing consumers expect Phase 0 reset on disappear; need careful migration.

**Validation:** unit tests covering pause/resume cycles preserving phase. 02-chaos: cluster survives a 30s quorum loss without re-syncing from scratch.

### Phase R2: Layer 1 (SWIM) — Restore non-Stopped/Starting peer event handling + transport hint
**Scope:** SWIM observations canonical. Transport hint reduces suspect window from 15s to 3s for that peer only. Cold-boot suppression of FAULTY for never-healthy peers.

**Files:** `SwimHealthState.java`, `SwimHealthContext.java`, `QuicClusterNetwork.java` (emit TransportObservation, no other signals).

### Phase R3: Layer 2 (HealthReconciler) — Quorum-of-observations + cooldown + phase
**Scope:** Single-writer of NodeLifecycleKey. Aggregates observations across N nodes. Cooldown. Cold-boot phase suppresses LEFT/DECOMMISSIONED.

**Files:** `HealthReconciler.java`, new `ClusterPhaseKey/Value`, AetherNode wiring.

### Phase R4: Layer 3 (TopologyObserver) — Pure projection
**Scope:** Strip all writeable APIs. Pure subscriber to NodeLifecycleKey/ClusterConfigKey/LeaderKey/ClusterPhaseKey. Sole publisher of `TopologyChangeNotification` and `QuorumStateNotification`.

**Files:** `TopologyObserver.java`, `TopologyMembershipPublisher.java` (folds into observer), all callers of removed APIs.

### Phase R5: Layer 0 (Transport) — Transport-only
**Scope:** Remove all non-`TransportObservation` upward signaling. ClusterTransport public surface narrowed.

**Files:** `QuicClusterNetwork.java`, `NettyClusterNetwork.java`, all callers.

### Phase R6: Layer 5 (Leader Election) — Rank staircase + always-listen for KV
**Scope:** Rank-staircase delays on Electing entry. Independent observation timer (Fix A made permanent).

**Files:** `LeaderElectionFsm.java`, `LeaderElectionState.java`, `LeaderElectionContext.java`.

### Phase R7: Layer 6 (Auto-Heal) — Phase-aware + KV-only input
**Scope:** Subscribe to ClusterPhaseChanged. Suspend in BOOTING. Sole input is TopologyChangeNotification.

**Files:** `ClusterTopologyManagerRecord.java`.

### Phase R8: Layer 7 (Node Lifecycle FSM) — Explicit per-node phases
**Scope:** New FSM. Health endpoint contract refactor.

**Files:** `AetherNode.java`, `StatusRoutes.java`.

### Phase R9: Test contract alignment
**Scope:** Test harness `is_cluster_ready`, `wait_for_leader`, `wait_for_node_count` redefined to consume `/health/ready` and `/api/cluster/topology` directly via curl. No `aether status` Java cold-starts in poll loops.

**Files:** `aether/tests/integration/lib/common.sh`, `lib/cluster.sh`.

### Phase R10: Cleanup
**Scope:** Remove dead code paths, unused imports, redundant fields. Format pass.

---

## 10. Test Strategy

### 10.1 Unit tests (per-layer)
Each layer has its own test suite that exercises the layer's contract with mocked-out neighbours. No test should require multi-layer integration to verify single-layer behavior.

### 10.2 Cross-layer integration tests
A small suite of tests that exercises specific flows:
- Cold-boot cluster formation (5 nodes from empty KV)
- Single-node failure (kill leader, observe re-election + auto-heal)
- Quorum loss + recovery (kill 2/5, observe Paused, restore, observe Resume same phase)
- Reconfigure (cluster size change from 5 to 7)

### 10.3 Property tests
Cold-boot mode invariants:
- During BOOTING, FAULTY observations do not result in DECOMMISSIONED writes
- During BOOTING, auto-heal does not provision

### 10.4 Test harness changes (Phase R9)
Defined above. Harness must use operator-visible signals only.

---

## 11. Open Questions / Decisions Needed

1. **Reconfigure semantics:** when cluster size changes via admin (5 → 7), what happens to in-flight Rabia proposals? Drain or carry over? Spec proposes drain; alternative is to carry over with a phase-fence.

2. **Per-peer suspect-window shortening via transport hint:** is 3s minimum sufficient? Could overshoot in WAN deployments. Consider making the floor configurable per-cluster.

3. **Cold-boot detection robustness:** spec relies on `LeaderKey unset OR < quorum ON_DUTY`. What if a malicious or corrupted KV has `LeaderKey` set but no ON_DUTY peers? Need explicit recovery from this. Possibly a `ClusterPhaseKey = BOOTING` initial value before any node starts.

4. **Migration from existing cluster state:** how do we upgrade an in-flight rc1 cluster running with current architecture to the new design? Big-bang requires cluster-wide stop+restart. Rolling upgrade is hard because of the changed signal contracts. Explicit: this redesign assumes greenfield (no live-cluster upgrade path).

5. **Observability:** every layer emits a metrics-friendly event stream. New dashboards needed for layer-by-layer signal flow visualization.

6. **Backwards compatibility window:** current rc1 users will need to redeploy clusters. Document the breaking changes prominently.

---

## 12. Estimated Effort

| Phase | LoC | Effort |
|---|---|---|
| R1 — Rabia Paused | ~400 | 2-3 days |
| R2 — SWIM canonical + transport hint | ~250 | 1-2 days |
| R3 — HealthReconciler quorum + phase | ~600 | 3-4 days |
| R4 — TopologyObserver pure projection | ~300 | 1-2 days |
| R5 — Transport narrowed | ~200 | 1 day |
| R6 — Leader Election rank staircase | ~150 | 1 day |
| R7 — Auto-Heal phase-aware | ~150 | 1 day |
| R8 — Node Lifecycle FSM | ~400 | 2 days |
| R9 — Test harness | ~300 | 1-2 days |
| R10 — Cleanup | ~200 | 1 day |
| **Total** | **~2950** | **15-20 days** |

Plus ~3-5 days of integration testing on remote infrastructure.

---

## 13. Migration Note

This spec assumes a **greenfield deployment**. There is no rolling-upgrade path from current rc1 to the redesigned architecture. Existing clusters will need cluster-wide stop, deployment of new binaries, and bootstrap from scratch. Document this prominently in the rc1 → new-design release notes.

The architectural cleanup commits already on `release-1.0.0-rc1` (`5c29a104f` Phase A through `d53b0021e` Phase A completion) lay the groundwork: SWIM-canonical pipeline is partially established, TopologyObserver is the canonical quorum publisher, leader-election FSM has KV-sync grace and peer-observation timer. These commits are forward-compatible with the redesign and should be preserved as the starting point.

---

## 14. Acceptance Criteria

The redesign is complete when:

1. ✅ Cold-boot of a 5-node cluster from empty KV reaches `Active` on all nodes within **< 15s** in 95th-percentile case
2. ✅ Killing a leader and observing re-election completes within **< 10s**
3. ✅ Cluster B chaos suite (02-chaos) passes 4/4 in CI
4. ✅ Full integration suite (15 suites, ~150 tests) passes 15/15 with no flakes across 10 consecutive runs
5. ✅ A 4-hour soak test (01-stability) shows zero spurious leader elections, zero spurious quorum-disappeared events, zero unexpected RabiaEngine resets
6. ✅ No code path exists where a transport-level event (QUIC connection state change) causes a state mutation in any layer above Layer 1
7. ✅ Static analysis (grep audit) confirms one-way signal flow per the layer model

---

**END OF SPECIFICATION**
