# Membership Architecture Specification

**Status:** Draft v2
**Date:** 2026-05-10 (v2: typed-stream split landed)
**Branch target:** `release-1.0.0-rc1`
**Scope:** Full redesign of cross-layer signal flow between QUIC, SWIM, HealthReconciler, TopologyObserver, Rabia, leader election, auto-heal (CTM), and node lifecycle. Backwards-compatibility is **not** a constraint.

**v2 update — typed observation/decision streams.** The unified `TopologyChangeNotification` of v1 has been split into two type-distinct streams (`TransportObservation` and `MembershipDecision`) that live in `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/`. This is a **structural** fix to the dual-reaction class of bugs: subscribers' Java type signatures now declare which stream they consume, and the compiler's sealed-exhaustive checking enforces non-confusion. See §3.1 (typed streams) and §6 (signal catalog).

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
   │   - Sole publisher of MembershipDecision & QuorumStateNotification. │
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
   │ Layer 0: Transport (QUIC, Netty)                                    │
   │   - Byte movement. Per-peer connection state.                       │
   │   - Emits TransportObservation (local, fast, partial-view).         │
   └─────────────────────────────────────────────────────────────────────┘
```

---

## 3.1 Typed observation/decision streams (v2)

The previous v1 design routed every membership-relevant event through a single `TopologyChangeNotification` type. That conflated two epistemically different facts:

- **"I observed peer X disconnect"** — local fact, fast, may flap, partial-view.
- **"The cluster has agreed peer X is no longer a member"** — global fact, slow, authoritative, idempotent.

Subscribers to the unified type received duplicate emissions for the same conceptual event from two different paths (transport-level QUIC eviction + consensus-driven snapshot delta). Some had subtle interleaving bugs where state changed between the two emissions and produced wrong reactions. The audit at `aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md` traced this to D2 (QUIC still emits `TopologyChangeNotification`) and D6 (CTM listens to the unified type, not snapshot deltas).

The v2 architecture replaces `TopologyChangeNotification` with **two type-distinct sealed interfaces** in `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/`. Subscribers' method signatures now declare which stream they consume; the Java compiler enforces non-confusion via sealed-exhaustive pattern checks.

### 3.1.1 `TransportObservation` — local, fast, may-flap

`integrations/consensus/.../TransportObservation.java`. Sealed `Message.Local` interface with five variants:

| Variant | Meaning | Fires from |
|---|---|---|
| `PeerJoined(nodeId, topology, source)` | Local handshake completed | QUIC ADD, Netty ADD |
| `PeerDisconnected(nodeId, topology, source)` | Local channel evicted | QUIC REMOVE, Netty REMOVE, SWIM-FAULTY |
| `PeerReconnected(nodeId, topology, source)` | Local channel re-established after a previous disconnect | QUIC RECONNECT |
| `PeerObservedFaulty(nodeId, topology, source)` | SWIM protocol declared peer FAULTY (suspect timeout + indirect-ping failure) | SWIM protocol layer |
| `SelfShutdown(nodeId, topology, source)` | THIS node is shutting down (self-emit) | QUIC SHUTDOWN with `self.id()` |

Each variant carries an `ObservationSource` enum (`QUIC | NETTY | SWIM`) for diagnostics. `topology()` is the **local** view of connected peers as known to THIS node — not a cluster-canonical snapshot.

**Properties:** local, fast (synchronous with transport events), may flap, partial-view.

**Producers:** `QuicClusterNetwork.processViewChange`, `NettyClusterNetwork.processViewChange`, `SwimProtocol.emitFaultyOrUnknown`.

**Consumers:** code paths that need fast local reactions and tolerate partial-view semantics, notably:
- `LeaderManager` — bootstrap fast-path before consensus exists.
- `ClusterFsmRouter` — same bootstrap fast-path.
- `RabiaNode` — bootstrap fast-path: consensus engine itself needs to learn about peers before it can commit anything.
- `SelfShutdown` may also be consumed by DECISION-stream subscribers that need a self-cleanup hook (see §3.1.2 below).

### 3.1.2 `MembershipDecision` — global, authoritative, idempotent

`integrations/consensus/.../MembershipDecision.java`. Sealed `Message.Local` interface with three variants:

| Variant | Meaning |
|---|---|
| `NodeJoined(nodeId, topology)` | The cluster has agreed (via consensus snapshot) that this node is a core member. |
| `NodeRemoved(nodeId, topology)` | The cluster has agreed (via consensus snapshot) that this node is no longer a core member. View-level transition. |
| `NodeDecommissioned(nodeId, topology)` | The cluster has agreed (via consensus on the lifecycle KV entry) that this node is permanently decommissioned. Lifecycle-level decision distinct from `NodeRemoved`. |

`topology()` is the **cluster-canonical** view of `coreMemberIds` after the decision committed.

**Properties:** global, authoritative (subscribers may rely on it for canonical reactions), eventually-consistent (consensus must commit before projection updates), idempotent (the diff is computed from prior committed state, so duplicate emissions for the same decision do not occur).

**Producer (sole emitter):** `TopologyObserver.publishMembershipDeltas`. Single-source-of-truth is part of the contract — no other code path in the system emits `MembershipDecision`.

**Consumers** (cluster-canonical reactions): `ClusterDeploymentManager` (workload reassignment), `ClusterTopologyManager` (capacity anchoring), `LoadBalancerManager` (target table), `HttpForwarder` (routing cleanup), `SliceInvoker`, `TaskAssignmentCoordinator`, `ClusterSyncCollector`, `ClusterSyncScheduler`, `DeploymentMetricsCollector`, `DeploymentMetricsScheduler`, `ControlLoop`, `AppHttpServer`, `DHTTopologyListener`.

### 3.1.3 Why the split is structural, not informational

In v1 the doc-comment on `TopologyChangeNotification` claimed transport-level emissions were "informational" while snapshot-level emissions were "authoritative." The audit (D2 in `membership-state-tracker-audit-2026-05-07.md`) showed that 15 receivers across 8 modules treated all emissions as authoritative regardless of source — the convention was unenforceable.

The v2 split fixes this in the type system: a subscriber that wants canonical truth declares `@MessageReceiver onMembershipDecision(MembershipDecision d)` and is **physically incapable** of receiving a `TransportObservation`. Likewise, bootstrap fast-path consumers declare `@MessageReceiver onTransportObservation(TransportObservation o)` and never see canonical decisions. Compiler-enforced non-confusion replaces convention-enforced non-confusion.

### 3.1.4 Bootstrap chicken-egg, resolved

A previous v1 concern: leader-election needs a topology view, but the canonical membership snapshot only exists after consensus commits, which requires a leader. v1 papered this over with a "load-bearing" rationale for retaining synchronous transport-level emissions on the unified channel.

In v2 the answer is explicit and type-safe. During bootstrap, `LeaderManager`, `ClusterFsmRouter`, and `RabiaNode` consume `TransportObservation` — the partial-view, fast-path stream is exactly what's available before consensus exists. Once consensus is up and `TopologyObserver.publishMembershipDeltas` starts emitting `MembershipDecision`, every other consumer receives canonical truth. The transition is implicit in which stream each component subscribes to; no "reverted audit step" or carve-out is needed.

### 3.1.5 Single-writer rule, refined

Principle P4 (single-writer for `NodeLifecycleKey`) applies to `MembershipDecision` — only `TopologyObserver.publishMembershipDeltas` emits decisions, and `HealthReconciler` is still the sole writer of the underlying KV atom. `TransportObservation` deliberately does NOT obey single-writer: every node emits its own observations independently — that is the entire point of an observation stream.

The `HealthReconciler.handleAggregatedEdge` self-leader-eviction escape hatch (when the eviction target IS the current leader, any surviving node may attempt the lifecycle write) is reframed under v2 as: "single-writer for *decisions*; the leader-self-decommission case is an explicit exception because the leader cannot decommission itself." Project memory `feedback_single_writer_rule_scope.md` captures the same distinction (atoms LEFT/ACTIVE/ON_DUTY are decision atoms; transport hygiene like local `DisconnectNode` routing is not).

### 3.1.6 CQRS-style transducer pattern

Architecturally the split is a CQRS shape:

- **Observation streams** (`TransportObservation`) are per-node, fast, and may be wrong/transient.
- **Decision streams** (`MembershipDecision`) are cluster-canonical, slower, and authoritative.
- **Transducers** aggregate observations into decisions. Currently:
  - `HealthReconciler` aggregates SWIM observations (cross-node, quorum-of-observations rule, cooldown) and writes `NodeLifecycleKey`. `TopologyObserver` then projects committed lifecycle state to `MembershipDecision.NodeRemoved` (and, when wired, `NodeDecommissioned`).
  - There is **no transducer yet** for raw QUIC/Netty `PeerDisconnected` observations — see §11 (`PeerObservationStore`, RC2).

### 3.1.7 `NodeDecommissioned` wiring status

`MembershipDecision.NodeDecommissioned` is present in the type system and pattern-matchable by subscribers, but `TopologyObserver.publishMembershipDeltas` does not yet emit it. Today `HealthReconciler` writes `NodeLifecycleKey = DECOMMISSIONED` and `TopologyObserver` projects that as `MembershipDecision.NodeRemoved` (view-level transition). Wiring `TopologyObserver` to additionally project the lifecycle DECOMMISSIONED edge as `NodeDecommissioned` is a follow-up item — kept distinct from `NodeRemoved` because durable decommission and transient view removal are different decisions to react to.

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

**Signals emitted upward (one only):** `TransportObservation` (defined in `integrations/consensus/.../TransportObservation.java`). See §3.1.1 for the full variant list and properties. QUIC and Netty both publish to this stream; the `ObservationSource` enum disambiguates the producer.

The stream is consumed only by bootstrap fast-path components (`LeaderManager`, `ClusterFsmRouter`, `RabiaNode`) and by `HealthReconciler`'s SWIM aggregator when the source is `SWIM`. Components that need cluster-canonical truth subscribe to `MembershipDecision` instead, and the type system prevents cross-stream confusion.

Layer 1 (SWIM) MAY use a `PeerDisconnected` observation as an informational hint to shorten its own suspect window for that peer (e.g., from 15s to 3s). Layer 1 MUST NOT translate transport observations directly into authoritative HEALTHY/FAULTY signals — those still go through SWIM gossip aggregation. Layer 1's `PeerObservedFaulty` emission is the SWIM protocol layer's *own* observation, distinct from a raw transport-level `PeerDisconnected`.

**What Layer 0 must NOT do:**
- Emit `MembershipDecision` (any variant) — this is `TopologyObserver`'s exclusive domain
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

**Responsibility:** Pure read-only projection of KV atoms. **Sole publisher** of `MembershipDecision` and `QuorumStateNotification`. There is no other emitter for either stream.

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
// integrations/consensus/.../MembershipDecision.java
sealed interface MembershipDecision extends Message.Local {
    NodeId nodeId();
    List<NodeId> topology();   // cluster-canonical core member list AFTER this decision

    record NodeJoined(NodeId nodeId, List<NodeId> topology)         implements MembershipDecision {}
    record NodeRemoved(NodeId nodeId, List<NodeId> topology)        implements MembershipDecision {}
    record NodeDecommissioned(NodeId nodeId, List<NodeId> topology) implements MembershipDecision {}
}

sealed interface QuorumStateNotification {
    record Established(int sequence) {}
    record Disappeared(int sequence) {}
    record Reconfigured(int oldSize, int newSize, int sequence) {}
}
```

**Emission point:** `TopologyObserver.publishMembershipDeltas` runs after each `evaluateQuorumState` mutation, diffs the new committed `MembershipView.coreMemberIds()` against the prior snapshot, and emits one `MembershipDecision.NodeJoined` per added member and one `NodeRemoved` per removed member. The diff is computed from canonical state, so duplicate emissions for the same edge do not occur (idempotent at projection — see §3.1.2).

**Edge-transition semantics:**
- `NodeJoined` fires on first transition into the cluster's `coreMemberIds` set.
- `NodeRemoved` fires on first transition out of the cluster's `coreMemberIds` set (whether by `DECOMMISSIONED`/`SHUTTING_DOWN` lifecycle write or by KV REMOVE).
- `NodeDecommissioned` (planned wiring): fires on first lifecycle KV transition into `DECOMMISSIONED`. Distinct from `NodeRemoved` because durable lifecycle decommission and transient view removal are different decisions to react to. See §3.1.7 — variant exists in the type system; emission wiring is a follow-up.

**Quorum latch:** atomic `quorumEstablished` boolean. `evaluateQuorumState` runs after each KV-atom-driven mutation. Edge transitions emit `Established(seq++)` or `Disappeared(seq++)` exactly once per edge.

**What TopologyObserver must NOT do:**
- Have any `registerPeer/unregisterPeer/markReady/markDeparted/handleConnectionFailed/handleConnectionEstablished` API. These are removed entirely.
- Be writeable from any layer. The internal state is computed from KV atom subscriptions only.
- React to `TransportObservation`, `SwimObservation`, or any non-KV signal.
- Emit any other variant of `MembershipDecision` or `QuorumStateNotification` from any path other than `publishMembershipDeltas` / `evaluateQuorumState`.

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

**Sole input source:** subscribes to `MembershipDecision` from Layer 3 (and `QuorumStateNotification` for quorum-loss suspension). **No other input.** CTM does **not** subscribe to `TransportObservation` — by design, transient transport flaps must not drive provisioning. The compiler enforces this: `@MessageReceiver` on `MembershipDecision` cannot accidentally receive a `TransportObservation`.

**Phase awareness:** subscribes to `ClusterPhaseChanged` from Layer 2. CTM only operates in `NORMAL` phase. In `BOOTING` and `RECOVERING`, CTM is suspended (no provisioning, no decommissioning).

**Stability anchor:** bumped only on edge transitions of `MembershipView.healthyOnDutyCount()` as projected by `MembershipDecision`. NOT on `TransportObservation`, NOT on `SwimObservation`, NOT on any per-peer event that doesn't change the on-duty count.

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

Complete list of cross-layer signals after this redesign. Anything not on this list does not exist. The two streams introduced in §3.1 are typeset as **OBSERVATION** (local, fast, may-flap) and **DECISION** (global, authoritative) for clarity.

| From → To | Signal | Stream / Type | Notes |
|---|---|---|---|
| Layer 0 → Layer 1, bootstrap fast-path | `TransportObservation.{PeerJoined,PeerDisconnected,PeerReconnected,PeerObservedFaulty,SelfShutdown}` | OBSERVATION | local fact; `ObservationSource ∈ {QUIC, NETTY, SWIM}` |
| Layer 1 → Layer 2 | `SwimObservation.HealthyObserved/Suspect/Faulty/Departed` | OBSERVATION | input to reconciler decision aggregation |
| Layer 2 → Layer 4 | `RabiaCommand.Put<NodeLifecycleKey, NodeLifecycleValue>` | command | proposed via consensus |
| Layer 2 → all | `ClusterPhaseChanged(phase)` | broadcast | published via Rabia commit on `ClusterPhaseKey` |
| Layer 4 → Layer 3 | `KVStoreNotification.ValuePut/Remove<NodeLifecycleKey, ClusterConfigKey, ClusterPhaseKey, LeaderKey>` | notification | derived from consensus commit |
| Layer 3 → Layer 4 | (nothing — Layer 3 is read-only) | — | — |
| Layer 3 → Layer 5 | `KVStoreNotification.ValuePut<LeaderKey>` (relayed) | notification | leader election listener |
| Layer 3 → Layer 6 (and other DECISION subscribers) | `MembershipDecision.{NodeJoined,NodeRemoved,NodeDecommissioned}`, `QuorumStateNotification.*` | DECISION | sole DECISION-stream emitter is `TopologyObserver.publishMembershipDeltas` |
| Layer 4 → Layer 5 | `RabiaCommand.Put<LeaderKey, _>` commit | command-result | proposes leader |
| Layer 5 → Layer 7 | `LeaderChange(currentLeader)` | notification | optional — for routing |
| Layer 6 → Layer 0 (provisioning) | command (provision/decommission VM) | RPC | external SPI calls |
| Layer 6 → Layer 4 | `RabiaCommand.Put<ProvisioningSlotKey, _>` | command | tracks in-flight provisioning |
| Layer 7 → all | `LifecyclePhaseChanged(self, phase)` | broadcast | per-node |

**Stream-subscriber matrix** (which components consume which stream):

| Component | Subscribes to | Why |
|---|---|---|
| `LeaderManager` | OBSERVATION | bootstrap fast-path; needs to learn peers before consensus exists |
| `ClusterFsmRouter` | OBSERVATION | same bootstrap fast-path |
| `RabiaNode` | OBSERVATION | engine learns peer set before it can commit anything |
| `HealthReconciler` SWIM aggregator | OBSERVATION (`PeerObservedFaulty` only, source=SWIM) | aggregates into KV writes; emits no observations of its own |
| `ClusterDeploymentManager`, `ClusterTopologyManager`, `LoadBalancerManager`, `HttpForwarder`, `SliceInvoker`, `TaskAssignmentCoordinator`, `ClusterSyncCollector`/`Scheduler`, `DeploymentMetricsCollector`/`Scheduler`, `ControlLoop`, `AppHttpServer`, `DHTTopologyListener` | DECISION | canonical reactions to cluster-agreed membership |
| Self-cleanup hooks on local shutdown | OBSERVATION (`SelfShutdown` only) | local fact; the cluster does not need to agree the local node is shutting down |

**Removed signals (must not exist after redesign):**
- The unified `TopologyChangeNotification` type — split into `TransportObservation` and `MembershipDecision` (v2). The class is **deleted**, not renamed.
- QUIC / Netty → anything except `TransportObservation`
- Any `MembershipDecision` emitter other than `TopologyObserver.publishMembershipDeltas`
- Any `QuorumStateNotification` emitter other than `TopologyObserver`
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
//   The old upward emission of TopologyChangeNotification.NodeAdded/NodeRemoved/NodeDown.
//   processViewChange now publishes only TransportObservation variants (PeerJoined,
//   PeerDisconnected, PeerReconnected, SelfShutdown) with ObservationSource = QUIC or NETTY.

// Deleted entirely (v2):
sealed interface TopologyChangeNotification { ... }   // gone — replaced by typed split below
```

### 8.2 New APIs

```java
// Streams (v2 — typed split, replacing TopologyChangeNotification)
//   integrations/consensus/.../TransportObservation.java  (OBSERVATION stream)
//   integrations/consensus/.../MembershipDecision.java    (DECISION stream)
// Both are Message.Local sealed interfaces; see §3.1 for variants and properties.

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
**Scope:** Strip all writeable APIs. Pure subscriber to NodeLifecycleKey/ClusterConfigKey/LeaderKey/ClusterPhaseKey. Sole publisher of `MembershipDecision` and `QuorumStateNotification` (v2 — see §3.1 for the typed-stream split that replaced the unified `TopologyChangeNotification`).

**Files:** `TopologyObserver.java`, `TopologyMembershipPublisher.java` (folds into observer), all callers of removed APIs.

### Phase R5: Layer 0 (Transport) — Transport-only
**Scope:** Remove all non-`TransportObservation` upward signaling. ClusterTransport public surface narrowed.

**Files:** `QuicClusterNetwork.java`, `NettyClusterNetwork.java`, all callers.

### Phase R6: Layer 5 (Leader Election) — Rank staircase + always-listen for KV
**Scope:** Rank-staircase delays on Electing entry. Independent observation timer (Fix A made permanent).

**Files:** `LeaderElectionFsm.java`, `LeaderElectionState.java`, `LeaderElectionContext.java`.

### Phase R7: Layer 6 (Auto-Heal) — Phase-aware + KV-only input
**Scope:** Subscribe to ClusterPhaseChanged. Suspend in BOOTING. Sole input is `MembershipDecision` (v2 — DECISION stream); CTM does not subscribe to `TransportObservation`.

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

## 11. RC2 follow-up: `PeerObservationStore` (Step 7 from the audit)

The v2 split closes D2/D6 from `membership-state-tracker-audit-2026-05-07.md` structurally — components are now bound by Java types to the correct stream — but it does not by itself add cross-node aggregation for raw QUIC/Netty `TransportObservation.PeerDisconnected`. Today only SWIM observations get cross-node aggregation (via `HealthReconciler`'s quorum-of-observations rule). A single witness reporting a QUIC eviction has no quorum check before downstream reactions fire.

The natural follow-up architectural layer is a typed transducer between `TransportObservation` and `MembershipDecision`:

**`PeerObservationStore`** — RC2 component.

| Property | Value |
|---|---|
| Purpose | Cross-node aggregation of `TransportObservation.PeerDisconnected` / `PeerObservedFaulty` / `PeerJoined` with TTL |
| Input | `TransportObservation` events from N nodes (broadcast or pushed via consensus) |
| Output | `MembershipDecision` proposed for write when ⌈N/2⌉+1 distinct observers report the same target |
| Semantics | TTL-decayed observation set; quorum threshold; cooldown post-decision |
| Lands as | The typed transducer between OBSERVATION and DECISION streams (CQRS shape per §3.1.6) |
| Eliminates | Single-witness false-positive surface for non-SWIM transport observations |
| Estimated effort | 2-3 days (consensus integration + TTL state machine + tests) |

The component lands cleanly in v2 because the type system already separates the input stream (OBSERVATION) from the output stream (DECISION), and the new contract that `TopologyObserver.publishMembershipDeltas` is the **sole** DECISION emitter means `PeerObservationStore` will route its decisions through `HealthReconciler`'s KV write path rather than minting `MembershipDecision` directly. Nothing in v2 reserves the right to bypass this.

---

## 12. Open Questions / Decisions Needed

1. **Reconfigure semantics:** when cluster size changes via admin (5 → 7), what happens to in-flight Rabia proposals? Drain or carry over? Spec proposes drain; alternative is to carry over with a phase-fence.

2. **Per-peer suspect-window shortening via transport hint:** is 3s minimum sufficient? Could overshoot in WAN deployments. Consider making the floor configurable per-cluster.

3. **Cold-boot detection robustness:** spec relies on `LeaderKey unset OR < quorum ON_DUTY`. What if a malicious or corrupted KV has `LeaderKey` set but no ON_DUTY peers? Need explicit recovery from this. Possibly a `ClusterPhaseKey = BOOTING` initial value before any node starts.

4. **Migration from existing cluster state:** how do we upgrade an in-flight rc1 cluster running with current architecture to the new design? Big-bang requires cluster-wide stop+restart. Rolling upgrade is hard because of the changed signal contracts. Explicit: this redesign assumes greenfield (no live-cluster upgrade path).

5. **Observability:** every layer emits a metrics-friendly event stream. New dashboards needed for layer-by-layer signal flow visualization.

6. **Backwards compatibility window:** current rc1 users will need to redeploy clusters. Document the breaking changes prominently.

---

## 13. Estimated Effort

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

## 14. Migration Note

This spec assumes a **greenfield deployment**. There is no rolling-upgrade path from current rc1 to the redesigned architecture. Existing clusters will need cluster-wide stop, deployment of new binaries, and bootstrap from scratch. Document this prominently in the rc1 → new-design release notes.

The architectural cleanup commits already on `release-1.0.0-rc1` (`5c29a104f` Phase A through `d53b0021e` Phase A completion) lay the groundwork: SWIM-canonical pipeline is partially established, TopologyObserver is the canonical quorum publisher, leader-election FSM has KV-sync grace and peer-observation timer. These commits are forward-compatible with the redesign and should be preserved as the starting point.

---

## 15. Acceptance Criteria

The redesign is complete when:

1. ✅ Cold-boot of a 5-node cluster from empty KV reaches `Active` on all nodes within **< 15s** in 95th-percentile case
2. ✅ Killing a leader and observing re-election completes within **< 10s**
3. ✅ Cluster B chaos suite (02-chaos) passes 4/4 in CI
4. ✅ Full integration suite (15 suites, ~150 tests) passes 15/15 with no flakes across 10 consecutive runs
5. ✅ A 4-hour soak test (01-stability) shows zero spurious leader elections, zero spurious quorum-disappeared events, zero unexpected RabiaEngine resets
6. ✅ No code path exists where a transport-level event (QUIC connection state change) causes a state mutation in any layer above Layer 1
7. ✅ Static analysis (grep audit) confirms one-way signal flow per the layer model

---

## 16. Realistic Scenarios

This section enumerates the canonical scenarios the FSM + aggregator + self-drain protocol must handle. Each row maps to an acceptance test (unit, integration, or manual). The table is the **contract** subsequent implementation must satisfy.

**Legend:**
- **SWIM**: H = HEALTHY, S = SUSPECTED, F = FAULTY, U = UNKNOWN
- **QUIC**: CONN = CONNECTED, EVI = EVICTED, RC = RECONNECTED
- **Aggregator**: REACH = REACHABLE quorum, UNREACH = UNREACHABLE quorum, UNK = UNKNOWN/insufficient data
- **`*`**: any value; not relevant for this scenario

| S-ID | Scenario | Trigger | SWIM | QUIC | Aggregator | Expected FSM transition | Expected KV writes | Operator-surface result | Acceptance test |
|---|---|---|---|---|---|---|---|---|---|
| S01 | JOINING-window kill | Node killed during JOINING (before SWIM HEALTHY) | U | CONN→EVI | UNK→UNREACH within 5-7s | JOINING → DECOMMISSIONED (via TransportUnreachable, ungated) | Put(DECOMMISSIONED) within ≤25s (empirical floor ~17s; QUIC drop-detection for SIGKILL'd peers dominates) | `/api/status` excludes peer within ≤25s | `aether/tests/integration/suites/02-chaos/test-joining-window-kill.sh` |
| S02 | ON_DUTY single non-leader kill | Steady-state non-leader killed | H→F | CONN→EVI | REACH→UNREACH | ON_DUTY → DECOMMISSIONED (via Transport, gated by aggregator UNREACH) | Put(DECOMMISSIONED) within ≤15s | `/api/status` excludes within ≤15s | covered by `test-kill-node.sh` (existing, retimed) |
| S03 | ON_DUTY two simultaneous non-leader kills | Two non-leaders killed in <1s | H→F (both) | CONN→EVI (both) | REACH→UNREACH (both) | Both ON_DUTY → DECOMMISSIONED in parallel | Put(DECOMMISSIONED) × 2 within ≤15s | `/api/status` excludes both within ≤15s; `pick_non_leader(count=2)` succeeds | covered by `test-kill-multiple.sh` (existing, retimed) |
| S04 | Brief transport flap < 5s | Transient network blip, peer reconnects within 5s | H (unchanged) | CONN→EVI→RC | REACH→UNK→REACH (within period) | nop (gate blocks transient) | none | no change | covered by FSM unit test `gate_blocks_transient_unreachable` |
| S05 | 2-vs-3 partition (majority side) | Network partition, this side has 3 nodes | H→F for 2 minority peers | CONN→EVI for 2 | REACH for 3 in-side, UNK for 2 partitioned (no quorum to mark UNREACH from majority's perspective) | nop for partitioned peers (gate blocks) | none | minority peers stay ON_DUTY in `/api/status` until heal | `aether/tests/integration/suites/12-network/test-partition-quorum-gate.sh` |
| S06 | Partition heal | Connection restored after S05 | F→H | EVI→RC | UNK→REACH | nop (no state change) | none (peers were never decommissioned) | all 5 ON_DUTY within ≤15s of heal | same test as S05 (heal phase) |
| S07 | Graceful operator drain | `aether cluster drain <node>` | * | * | * | ON_DUTY → DRAINING → DECOMMISSIONED | Put(DRAINING), Put(DECOMMISSIONED) on drain success | `/api/status` reflects DRAINING then DECOMMISSIONED | covered by existing `test-drain-success.sh` |
| S08 | Drain timeout | Drain doesn't complete within hard deadline | * | * | * | DRAINING → FAILED_DRAIN | Put(FAILED_DRAIN) | `/api/status` shows FAILED_DRAIN | covered by FSM unit test `drain_timeout_fails` |
| S09 | Drain during partition | Operator drains while node is partitioned | * | * | UNREACH | DRAINING but drain protocol may not complete; FAILED_DRAIN on timeout | Put(DRAINING), Put(FAILED_DRAIN) on timeout | DRAINING then FAILED_DRAIN | covered by FSM unit test `drain_during_partition` |
| S10 | Operator force-decommission | `aether cluster decommission --force <node>` | * | * | * | * → DECOMMISSIONED (force) | Put(DECOMMISSIONED) | `/api/status` excludes within seconds | covered by existing `test-decommission-force.sh` |
| S11 | Restart inside revival TTL | Killed node restarts with same NodeId, new incarnation, within TTL window | F→H (new incarnation) | EVI→CONN | UNREACH→REACH | DECOMMISSIONED stays (chaos-revival defense); new incarnation joins as fresh JOINING via CTM | none (DECOMMISSIONED is terminal); new slot via CTM | new KSUID identity in `/api/status`; old DECOMMISSIONED entry persists | covered by FSM unit test `decommissioned_terminal_no_revival` |
| S12 | Restart outside revival TTL | Same as S11 but after DecommissionedAtomGc removed the entry | * | * | * | UNTRACKED → JOINING → ON_DUTY (fresh path) | Put(JOINING), Put(ON_DUTY) | clean rejoin | covered by FSM unit test `untracked_rejoin_after_gc` |
| S13 | SWIM-only failure (transport OK) | SWIM marks peer FAULTY but QUIC connection OK | H→F | CONN (unchanged) | REACH (aggregator from QUIC observations) | nop (gate blocks: aggregator says REACH) | none | no change | covered by FSM unit test `gate_blocks_swim_only_failure` |
| S14 | Transport-only failure | QUIC connection drops but SWIM still HEALTHY (rare, e.g., specific protocol issue) | H (unchanged) | CONN→EVI | REACH→UNREACH (within period) | ON_DUTY → DECOMMISSIONED (Transport cell, gated by aggregator UNREACH) | Put(DECOMMISSIONED) within ≤25s | excluded within ≤25s | covered by S01 family of tests |
| S15 | Cold-start formation | 5 nodes start simultaneously | U→H | (none)→CONN×4 | UNK→REACH within 2 periods | UNTRACKED → JOINING → ON_DUTY for each peer (driven by SwimHealthy) | Put(JOINING), Put(ON_DUTY) per peer | all 5 ON_DUTY within ≤30s | covered by existing `test-cluster-formation.sh` |
| S16 | Cold-start + simultaneous kill | One node killed during cold-start (before its SWIM HEALTHY) | U (stays U for killed) | CONN→EVI (killed) | UNK→UNREACH (no SWIM context yet) | UNTRACKED stays UNTRACKED (no JOINING was ever written if SlotClaimed didn't fire); OR JOINING → DECOMMISSIONED if SlotClaimed fired | depends on race; either no writes or Put(DECOMMISSIONED) | killed peer absent from `/api/status` | covered by FSM unit test `coldstart_kill_during_provisioning` |
| S17 | Aggregator quorum lost | All followers go silent (no pongs reach leader); leader self-fold only | * | * | UNK (insufficient observers) | nop for all transport cells (gate cold-start fallback: no snapshot → trust upstream); SWIM cells unchanged | none | unchanged | covered by FSM unit test `gate_cold_start_fallback` |
| S18 | Leader kill + re-election | Current leader killed | H→F (from new leader's view) | CONN→EVI (from new leader's view) | UNREACH (eventually, after new leader's aggregator catches up) | New leader writes (OnDuty, SwimFaulty/Transport) → DECOMMISSIONED for old leader | Put(DECOMMISSIONED) for old leader within ≤15s post-election | new leader visible in `/api/status`; old leader excluded | covered by existing `test-kill-leader.sh` |
| S19 | Quorum-loss → self-drain | Node loses contact with ≥⌈N/2⌉+1 peers for ≥8s | * | EVI for majority | UNK or UNREACH | none from this node's FSM (it self-drains, doesn't write) | none (self-drain bypasses KV) | this node's HTTP returns 503/refused within ~9s; process exits within ~38s | `aether/tests/integration/suites/02-chaos/test-self-drain-quorum-loss.sh` |
| S20 | Self-drain → restart → rejoin | After S19, orchestrator restarts the node | U→H | (none)→CONN×N | UNK→REACH | UNTRACKED → JOINING → ON_DUTY (fresh) | Put(JOINING), Put(ON_DUTY) | `/api/status` shows node ON_DUTY within ≤60s | same test as S19 (rejoin phase) |

---

### 16.1 Self-Drain Protocol

A node self-drains when it cannot reach ⌈N/2⌉+1 peers (counting itself) for ≥ `triggerThreshold` seconds. Self-drain is **uninterruptible once started** — quorum restoration mid-drain does not abort.

**Triggers** (any one):
- `QuorumStateNotification.DISAPPEARED` from local `TopologyObserver`
- Rabia `Paused` state from local consensus engine
- Periodic (1Hz) check: `connectedPeers().size() + 1 < (topologySize / 2) + 1` for `triggerThreshold` consecutive seconds

**Drain procedure** (state machine: `ACTIVE → DRAINING → EXITED`):
1. CAS transition `ACTIVE → DRAINING` (idempotent; double-trigger no-ops)
2. `InFlightRequestTracker.setAcceptingNewWork(false)` — HTTP server returns 503 for new requests
3. Schedule `inflightGrace` timeout (default 30s) — if reached, exit anyway
4. When tracker reaches zero in-flight, OR timeout fires: `Runtime.halt(2)`
5. Orchestrator restart policy decides: cluster A `restart: unless-stopped` (auto-restart); cluster B `restart: "no"` (stay exited)

**Key invariants:**
- No KV writes during self-drain (KV is unreachable anyway in a partition; depending on it would deadlock)
- No consensus calls during self-drain
- Restart after drain begins from a clean process state — node rediscovers peers via bootstrap

**Why self-drain instead of decommissioning the partitioned node from the other side?**
A partitioned node is not "dead" — it's potentially alive on the other side of a partition. If the majority side decommissions it, then partition heals, the node would re-join with a stale DECOMMISSIONED entry in its local KV view, causing inconsistency. Self-drain lets the partitioned node take itself out: the cluster's "membership" view becomes append-only (no false decommission), and the partitioned node returns as a fresh JOINING after restart. The cluster does not need to distinguish "dead" from "partitioned" — both look the same (transport-disconnected, never returns), and that's the correct semantic.

### 16.2 Scenario coverage status

Each scenario row above lists its acceptance test. Steps 7, 8, 9 of the implementation plan introduce new integration tests for S01, S05, S06, S19, S20. Other scenarios are covered by existing tests or FSM unit tests (Step 2, Step 4, Step 6). Step 10 cross-checks that every row maps to a passing test before the refactor is considered complete.

---

**END OF SPECIFICATION**
