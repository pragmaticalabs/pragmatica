<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Reachability Aggregator — Cluster-Canonical Transport View via Metrics Ping-Pong
status: approved
target: RC1
related: aether/docs/specs/membership-architecture-spec.md, aether/docs/specs/cluster-membership-fsm-spec.md, aether/docs/specs/dht-resilience-spec.md
---

# Reachability Aggregator: Cluster-Canonical Transport View

## Problem statement

`/api/status` and other consumers compute "currently-reachable peers" as `MembershipView.onDutyPeers() ∩ network.connectedPeers()`. The right-hand operand is **per-reader local QUIC state** (`PeerState.Phase.CONNECTED` on this node's QuicClusterNetwork). The left-hand operand is KV-replicated and cluster-canonical, but the intersection introduces per-reader variance: two readers querying the same cluster at the same instant can return different `coreCount` values depending on which transport-layer handshakes have completed locally.

Failure observed:

- **02-chaos/Kill_2_nodes**: `pick_non_leader: only 1/2 candidates available (leader=node-2, cluster=b)`. The cluster has 5 healthy peers; the entry-point's local QUIC has 2 of them in CONNECTED phase at the moment of the read; the test fails to pick 2 non-leader victims.
- **03-scaling, 05-security, 13-edge-cases cascade**: subsequent destructive suites can't initialize because `restore_cluster_baseline` polls the same variant view.

Root cause is **per-reader variance**, not "dead peers showing alive". KV says all 5 are ON_DUTY; the transport-honest filter trims to a smaller, reader-specific set.

## How the membership architecture already handles this

`MembershipView.onDutyPeers()` is cluster-canonical (KV-replicated, single-writer via `HealthReconciler`). The variance is introduced by the **consumer-side** transport intersection, which was added (RC1 Wave 4 follow-up) to handle the *opposite* failure: peer killed but KV-ON_DUTY for several seconds until SWIM detects + `HealthReconciler` writes. That filter is correct in concept but the per-reader QUIC-connection set is not the right input — it should be a **cluster-canonical reachability view**, derived from observations aggregated across observers.

The membership architecture spec (§11) describes this as `PeerObservationStore`, deferred to RC2. With the user direction to ship 15/15 for RC1, the layer must land now.

## Design principle

**Two views of "reachable", used for different purposes:**

| View | Source | Latency | Consumers |
|---|---|---|---|
| **Local QUIC reachability** | `network.connectedPeers()` (`PeerState.Phase.CONNECTED`) | <1s, sub-tick | Hot-path internal: DHT live-replica filter, request routing, retry logic |
| **Cluster-canonical reachability** | leader-broadcast aggregated view (this spec) | ~1-2s convergence | Operator surface: `/api/status`, chaos test helpers, slice placement |

These views serve different purposes. Routing must react in <1s to a dropped peer. Operator-visible state must be reader-invariant. We stop conflating them.

## Mechanism: extend the metrics ping-pong

Aether already has a leader-driven cluster-wide ping-pong cycle:

- **Tier 1**: leader ↔ core followers, 1s cadence. `ClusterSyncPing` (leader→follower, carries `allMetrics`) and `ClusterSyncPong` (follower→leader, carries `metrics` + drained `peerHealth` + `peerConnectivity` observations).
- **Tier 2**: spokesman ↔ governors. Same wire types, KV-driven activation (Spokesman assignment), currently **carries no peer observations** — only metrics + lifecycle state.

Both tiers gain a third observation channel: `peerTransport`. The leader builds an aggregated reachability snapshot and broadcasts it back in the next ping. Followers cache the snapshot for warm-takeover.

## Layered design (three layers)

### Layer 1 — Symmetric connectivity reporting (the gap that was hiding)

`PeerConnectivityObservation` (existing wire type, `integrations/cluster/.../PeerConnectivityObservation.java`) already carries QUIC transport events with `ConnectivityState` {CONNECTED, DISCONNECTED, STALE}. It's already pushed via `PeerConnectivityReporter`, drained into `ClusterSyncPong.peerConnectivity`, and fanned into `HealthSignal.RemoteConnectivity` on the leader.

The gap: `PeerConnectivityReporter` (in `integrations/consensus/.../PeerConnectivityReporter.java`) has only `onPeerDisconnected`. Reconnections are silent. So followers report when they lose a peer but not when they regain one — the leader has half the information needed to track current reachability.

Fix: extend the reporter:

```java
@Contract public interface PeerConnectivityReporter {
    void onPeerDisconnected(NodeId peerId, long observedTerm, long observedCounter);
    void onPeerConnected(NodeId peerId, long observedTerm, long observedCounter);  // NEW
    static PeerConnectivityReporter noop() { ... }
}
```

`QuicClusterNetwork` fires `onPeerConnected` when a peer transitions into `Phase.CONNECTED` (mirroring the existing `onPeerDisconnected` at line 1195). `AetherNode.attachQuicFollowerWiring` adapter emits `PeerConnectivityObservation(peerId, ConnectivityState.CONNECTED, ...)` for these.

No new wire type. No new buffer method. No pong-format change.

### Layer 2 — Wire-format extension (one field)

`ClusterSyncPing` gains `Option<AggregatedReachabilitySnapshot> aggregatedReachability`:

```java
record AggregatedReachabilitySnapshot(long generatedAtMs,
                                      Map<NodeId, ReachabilityState> states) {
    record ReachabilityState(NodeId target,
                             ReachabilityKind kind,
                             int observerCount,
                             long lastObservedAtMs) {
        enum ReachabilityKind { REACHABLE, UNREACHABLE, UNKNOWN }
    }
}
```

`Option<>` (not bare reference): the leader has no snapshot during the cold-start window (first 1-2 ticks). Followers treat `Option.none()` as "fall back to KV-only view".

Constructor canonicalizes null to `Option.none()` (backward compatible — pre-extension followers receive the new field, pre-extension nodes treat absence as `none()`).

### Layer 3 — Leader-side ReachabilityAggregator

New class: `aether/aether-deployment/.../membership/ReachabilityAggregator`.

```java
public interface ReachabilityAggregator {
    // Called by ClusterSyncCollector on each incoming pong (leader-only via fanIfLeader gate).
    @Contract void ingest(NodeId observer, List<PeerConnectivityObservation> connectivity,
                          List<PeerHealthObservation> health);

    // Called by ClusterSyncContext when building the outbound ping.
    Option<AggregatedReachabilitySnapshot> snapshot();

    // Called on leader-loss; clears all state.
    @Contract void reset();

    // Called on leader-gained; seed from the most-recent cached snapshot.
    @Contract void seedFromCache(AggregatedReachabilitySnapshot cached);
}
```

Aggregation contract:

- Per-target state: `Map<NodeId, Map<NodeId, ObservationEntry>>` where outer key is target, inner key is observer.
- `ObservationEntry(kind, observedAtMs)`. Latest-wins per (target, observer) pair, where `kind` derives from `ConnectivityState` (CONNECTED → REACHABLE, DISCONNECTED → UNREACHABLE, STALE → UNREACHABLE for aggregation purposes). Health observations contribute via `HealthHintWire` (HEALTHY → REACHABLE, SUSPECT/FAULTY → UNREACHABLE).
- TTL eviction (passive, on-snapshot-build): observations older than `TTL` (default 30s, configurable) are dropped from the quorum count.
- **Asymmetric quorum:**
  - `REACHABLE` upgrades on a single positive observer. Aligns with how local SWIM HEALTHY works — any node saying "I see this peer" is sufficient positive evidence. Required because transition-only observations (PeerJoined/PeerDisconnected/PeerReconnected fire from `processViewChange` only) mean follower buffers go empty in steady-state; without this asymmetry, the snapshot decays past TTL to just the leader's self-fold and never reaches multi-observer quorum on stable clusters.
  - `UNREACHABLE` requires `⌈healthyOnDutyCount / 2⌉ + 1` observers reporting UNREACHABLE within TTL. Guards against single-witness false positives (a local transient disconnect on one observer doesn't immediately mark the peer dead cluster-wide).
- Source for `healthyOnDutyCount`: `MembershipView.onDutyPeers().size()` (KV-canonical).
- Single-writer rule: aggregator does NOT write KV directly. When sustained quorum crosses a threshold corresponding to a meaningful `NodeLifecycleKey` transition, the aggregator emits a request to `HealthReconciler`, which proposes the KV write via Rabia (existing single-writer path).

The aggregator emits the snapshot for broadcast in every tick. The snapshot is read-only for followers — they cache it for warm-takeover.

### Layer 4 — Follower-side cache

Followers store the most-recent received `AggregatedReachabilitySnapshot` in a local field (`ReachabilitySnapshotCache`). No reactions, no decisions. Pure cache.

When a follower becomes leader (LeaderManager transition), the aggregator seeds itself from this cache: each `ReachabilityState` becomes a synthetic observation `(target, [leader's own NodeId], kind, lastObservedAtMs)` with a single observer. Real observations from pongs arriving over the next 1-2 ticks refine the state. Bounded warmup.

### Layer 5 — Consumer-side `/api/status` rewrite

`ClusterTopologyRoutes.transportConnectedOnDutyCount` and `StatusRoutes.toNodeInfo` change input from `network.connectedPeers()` to the aggregated snapshot:

```java
private static int reachableOnDutyCount(MembershipView view,
                                        Option<AggregatedReachabilitySnapshot> aggregate,
                                        NodeId selfId) {
    return aggregate.fold(
        () -> view.onDutyPeers().size(),  // cold-start fallback: KV-only
        snapshot -> {
            int count = 0;
            for (NodeId peer : view.onDutyPeers()) {
                if (peer.equals(selfId) || snapshot.isReachable(peer)) {
                    count++;
                }
            }
            return count;
        }
    );
}
```

Cold-start fallback uses KV-only count (no intersection), accepting the brief window where a recently-killed peer might still count until KV catches up. This matches the pre-Wave-4 behavior and is bounded by SWIM detection + HealthReconciler write latency (~10-30s worst case during cluster formation; in steady-state, snapshot is always present).

### Layer 6 — Tier-2 symmetry

Tier-2 (`SpokesmanPingLoop` ↔ governors) currently carries **zero** peer observations — neither health nor connectivity flow up the community hierarchy. This is a pre-existing gap; the RC1 work brings Tier-2 to symmetry with Tier-1.

- `SpokesmanPingLoop` gains injection of `PeerObservationBuffer`.
- Outbound ping construction drains `peerHealth` + `peerConnectivity` into each governor-bound pong.
- Spokesman-side aggregator mirror: `GovernorReachabilityAggregator` running on the spokesman, with the same shape as `ReachabilityAggregator` but scoped to its assigned communities.
- `CommunityReport` gains `Option<AggregatedReachabilitySnapshot> communityReachability` for spokesman→cluster-leader aggregate propagation.

Tier-2 single-writer rule: only the spokesman aggregates for its assigned communities. KV writes still funnel through the cluster leader's `HealthReconciler` via existing community-state escalation paths.

## Failure modes and bounded windows

| Scenario | Behavior | Bound |
|---|---|---|
| Cold start (first ticks post-formation) | `/api/status` falls back to KV-only view | 1-2 ticks (1-2s) |
| Leader change (orderly) | New leader seeds from cached snapshot, refines from pongs | 1-2 ticks (1-2s) |
| Leader change (partition recovery) | New leader's seed may be stale; first refinement cycle overrides | 2-3 ticks (2-3s) |
| Flap storm exceeding buffer | Latest observations win (ring drop-oldest) | Quorum convergence on end-state, not trajectory |
| Pong loss | Whole batch lost (no replay) | TODO: re-enqueue on `WriteOutcome` refusal if measured loss observed |
| Aggregator state lost on leader loss | New leader rebuilds; potential flap in derived KV transitions | Bounded by TTL + quorum threshold |

## Single-writer rule preservation

- `NodeLifecycleKey` writer remains `HealthReconciler` (KV authoritative).
- `ReachabilityAggregator` does NOT write KV. It only emits in-memory snapshots and routes lifecycle-transition requests through `HealthReconciler`.
- Snapshot in ping payload is broadcast cache, not authoritative state.
- Followers consume snapshots read-only.

## Implementation order

1. `AggregatedReachabilitySnapshot` + `ReachabilityState` + `ReachabilityKind` records (`integrations/cluster/.../metrics/`).
2. `PeerConnectivityReporter` extension: add `onPeerConnected(NodeId, term, counter)`.
3. `QuicClusterNetwork` push transport CONNECTED transitions through reporter.
4. `AetherNode.attachQuicFollowerWiring` adapter: handle both onConnected/onDisconnected.
5. `ClusterSyncPing.aggregatedReachability` field (`Option<AggregatedReachabilitySnapshot>`), defensive null-handling.
6. `ReachabilityAggregator` class (interface + record impl) in `aether/aether-deployment/.../membership/`.
7. Wire aggregator into `ClusterSyncCollector.onClusterSyncPong` (leader-only via existing `fanIfLeader` gate).
8. Wire aggregator output into `ClusterSyncContext.sendOnePing` ping construction.
9. `ReachabilitySnapshotCache` follower-side; leader-seeding hook on leader-gained.
10. `ClusterTopologyRoutes` + `StatusRoutes` rewrite to consume snapshot.
11. Tier-2: `SpokesmanPingLoop` observation buffer injection + drain.
12. Tier-2: `GovernorReachabilityAggregator` + `CommunityReport.communityReachability`.
13. Unit tests: aggregator state, TTL eviction, quorum threshold, snapshot construction.
14. Integration validation: 02-chaos / 03-scaling / 05-security on cluster B; 13-edge-cases (App_routes_reachable + disable_auto_heal); cluster A regression check.

## Verification

1. `mvn -pl integrations/cluster,aether/aether-metrics,aether/aether-deployment test` — all green.
2. `mvn -pl aether/node install -am -DskipTests` — full rebuild.
3. Integration suite on `--env remote`:
   - `02-chaos/Kill_2_nodes`: passes (pick_non_leader returns 2 candidates from a 5-node cluster).
   - `03-scaling`, `05-security`: cluster B no longer cascade-degrades after `02-chaos`.
   - `13-edge-cases/Cluster_ready_5_nodes`: `disable_auto_heal` succeeds (independent fix already landed).
   - All previously-green suites remain green.
4. Hetzner environment full pass.

## Out of scope (RC1)

- Hinted handoff for derived KV transitions when leader changes mid-decision.
- Per-peer flap circuit breaker on the aggregator.
- Buffer cap auto-scaling with cluster size.
- Persistent aggregator state across leader transitions (KV-replicated TTL state).
- Pong-loss replay (TODO marker added in code; defer until measured).

## Risks

| Risk | Mitigation |
|---|---|
| Aggregator state lost on leader change creates derived-KV flaps | Self-corrects on next quorum cycle; bounded by TTL |
| Stale snapshot in cold-start window mis-counts ON_DUTY | KV-only fallback; window is bounded (1-2s) |
| Tier-2 untested in RC1 deployments | Documented as preemptive correctness; will validate when governors are exercised post-RC1 |
| Wire-format additions break older nodes during rolling upgrade | Defensive null-handling in constructors; aggregatedReachability Option-wrapped; pre-extension nodes treat as `none()` |
