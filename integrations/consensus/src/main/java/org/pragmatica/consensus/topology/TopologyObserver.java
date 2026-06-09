package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Topology observer for cluster networks. Tracks connections, health states,
/// SWIM events, and reconnection. This is the read-only topology tracking component;
/// cluster size management is handled by ClusterTopologyManager in the deployment layer.
///
/// **Canonical emitter of `MembershipDecision`**: `publishMembershipDeltas` is the
/// single source of truth for cluster-wide membership decisions. Subscribers that
/// need authoritative membership (workload reassignment, capacity planning, routing
/// updates) consume `MembershipDecision`. Subscribers that need fast local reactions
/// during cluster bootstrap consume `TransportObservation` (emitted by
/// `QuicClusterNetwork` / `NettyClusterNetwork` / `SwimProtocol`). Conflating the
/// two streams is a subtle dual-reaction bug class — see the javadoc on each type
/// for the epistemic distinction.
public interface TopologyObserver extends TopologyManager {
    /// Errors that can occur during topology observer creation.
    sealed interface TopologyError extends Cause {
        record SelfNodeNotInCoreNodes(NodeId self) implements TopologyError {
            @Override
            public String message() {
                return "Self node " + self + " must be in coreNodes";
            }
        }
    }

    /// Explicit mode that drives `BOOTING`-vs-`NORMAL` semantics for the legacy
    /// `nodeStatesById` fallback paths.
    ///
    /// `BOOTING` — the cluster has never observed a `MembershipView` whose projected
    /// `coreMemberIds` reaches quorum (`clusterSize/2 + 1`). During this window
    /// `healthyActiveNodeCount`, `readyNodeCount`, and the private quorum-eval
    /// `healthyActivePeerCount` fall back to the in-memory `nodeStatesById` map so
    /// `/health/ready` does not stall waiting on a snapshot that itself requires
    /// quorum to be published.
    ///
    /// `NORMAL` — quorum has been observed at least once via the snapshot. From this
    /// point all reads are snapshot-only; if the snapshot ever becomes empty again
    /// the observer reports `0` rather than leaking a transport-derived count that
    /// disagrees with the leader's view.
    ///
    /// Transition is one-way: `BOOTING -> NORMAL` triggers on the FIRST snapshot whose
    /// `coreMemberIds.size() >= clusterSize/2 + 1`. Subsequent smaller snapshots do
    /// not flip back to `BOOTING`.
    enum TopologyMode {
        BOOTING, NORMAL
    }

    /// Current topology mode. Default `NORMAL` for the abstract interface so legacy /
    /// test-only implementations (notably `TopologyManager` minimal stubs) retain the
    /// snapshot-only contract by default.
    default TopologyMode topologyMode() {
        return TopologyMode.NORMAL;
    }

    @MessageReceiver
    void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList);

    @MessageReceiver
    void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes);

    @MessageReceiver
    void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes);

    @MessageReceiver
    void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message);

    /// Edge-triggered membership re-evaluation. Routed once per confirmed FSM departure
    /// (`AetherNode.onConfirmedDeparture`) so the core-membership delta recomputes and
    /// emits `NodeRemoved` for a departed peer that has no following `addNode` to re-run
    /// the diff (a CTM replacement dying at steady core size). Once-per-edge cadence —
    /// per reconcile-tick re-evaluation regressed READY-convergence.
    ///
    /// Default no-op so legacy / test-only stubs need not implement it (mirrors the
    /// `topologyMode()` / `effectiveMembership()` defaults); the production observer
    /// overrides this with the real `evaluateQuorumState()` re-poke.
    @MessageReceiver
    default void handleReevaluateMembership(NetworkServiceMessage.ReevaluateMembership message) {}

    /// Which membership source (`SNAPSHOT` vs `LEGACY`) the observer would serve reads
    /// from right now, plus the resulting core-id set. Lets tests and diagnostics verify
    /// that the snapshot-backed path is actually engaged.
    default EffectiveMembership effectiveMembership() {
        return new EffectiveMembership(coreNodes(), EffectiveMembership.Source.LEGACY);
    }

    /// Accessor for the observer's quorum-established bit (RC1 Step 5).
    ///
    /// `MembershipView.strict(...)` consults this supplier on every read to decide whether
    /// the local node may legitimately advertise `ON_DUTY` peers. The base interface
    /// returns a permanently-true supplier so legacy / test-only `TopologyManager` stubs
    /// remain trivially quorate; the production observer overrides to expose the same
    /// `AtomicBoolean` mutated by `evaluateQuorumState`. Duplicating the source elsewhere
    /// is exactly the drift bug `MembershipView` Step 5 exists to eliminate — always wire
    /// through this accessor.
    default BooleanSupplier inQuorum() {
        return () -> true;
    }

    /// Default predicate used when no KV-backed lifecycle reader is wired (tests and
    /// legacy call sites). Preserves pre-rc1 behaviour: nothing is treated as
    /// DECOMMISSIONED, so `initReconcile` reseeds every `config.coreNodes()` entry.
    Predicate<NodeId> NEVER_DECOMMISSIONED = _ -> false;

    /// RC1 Step 2 default HLC supplier used by legacy factory overloads / tests that
    /// haven't wired `HlcClock`. Always returns `HlcTimestamp.ZERO` — subscribers that
    /// dedup on `stampedAt` must treat ZERO as a cold-replay sentinel.
    Supplier<HlcTimestamp> ZERO_HLC_SUPPLIER = () -> HlcTimestamp.ZERO;

    static Result<TopologyObserver> topologyObserver(TopologyConfig config, MessageRouter router) {
        return topologyObserver(config, router, TimeSource.system(), GenerationSnapshotSource.noop(), NEVER_DECOMMISSIONED, ZERO_HLC_SUPPLIER);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource) {
        return topologyObserver(config, router, timeSource, GenerationSnapshotSource.noop(), NEVER_DECOMMISSIONED, ZERO_HLC_SUPPLIER);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     GenerationSnapshotSource snapshotSource) {
        return topologyObserver(config, router, TimeSource.system(), snapshotSource, NEVER_DECOMMISSIONED, ZERO_HLC_SUPPLIER);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource,
                                                     GenerationSnapshotSource snapshotSource) {
        return topologyObserver(config, router, timeSource, snapshotSource, NEVER_DECOMMISSIONED, ZERO_HLC_SUPPLIER);
    }

    /// Production overload: accepts a `isDecommissioned` predicate driven by the local
    /// KV-Store tombstone atoms. `initReconcile` consults it alongside the
    /// in-memory `tombstonedNodes` set so a DECOMMISSIONED ghost peer that survived a
    /// process restart (consensus log replay) is not silently re-added from
    /// `config.coreNodes()`. The in-memory set still covers the just-removed-this-session
    /// window; this predicate covers the persisted-across-restart window.
    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     Predicate<NodeId> isDecommissioned) {
        return topologyObserver(config, router, TimeSource.system(), GenerationSnapshotSource.noop(), isDecommissioned, ZERO_HLC_SUPPLIER);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     GenerationSnapshotSource snapshotSource,
                                                     Predicate<NodeId> isDecommissioned) {
        return topologyObserver(config, router, TimeSource.system(), snapshotSource, isDecommissioned, ZERO_HLC_SUPPLIER);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource,
                                                     GenerationSnapshotSource snapshotSource,
                                                     Predicate<NodeId> isDecommissioned) {
        return topologyObserver(config, router, timeSource, snapshotSource, isDecommissioned, ZERO_HLC_SUPPLIER);
    }

    /// RC1 Step 2 production overload: accepts an `hlcSupplier` that stamps every
    /// `MembershipDecision` emission with the current local HLC timestamp. Subscribers
    /// rely on `stampedAt` for cross-node causal ordering.
    ///
    /// Backward-compatible quorum-presence default: the established/lost
    /// `ClusterStateNotification` signal is routed through `router` (the shared bus),
    /// preserving pre-rewiring behaviour for every caller that does not supply a
    /// dedicated `quorumPresenceRouter`.
    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     GenerationSnapshotSource snapshotSource,
                                                     Predicate<NodeId> isDecommissioned,
                                                     Supplier<HlcTimestamp> hlcSupplier) {
        return topologyObserver(config, router, TimeSource.system(), snapshotSource, isDecommissioned, hlcSupplier, router);
    }

    /// Quorum-presence rewiring overload: accepts an explicit `quorumPresenceRouter` that
    /// carries the quorum established/lost `ClusterStateNotification` signal to a dedicated
    /// single-subscriber channel (RabiaEngine ONLY), instead of the cluster-wide shared bus
    /// `router`. Production (`RabiaNode`) calls this so the established/lost edge feeds
    /// consensus privately while `ConsensusBridge` remains the sole shared-bus emitter of
    /// Rabia's active status. All OTHER emissions (`MembershipDecision`, transport service
    /// messages) continue to use `router`.
    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     GenerationSnapshotSource snapshotSource,
                                                     Predicate<NodeId> isDecommissioned,
                                                     Supplier<HlcTimestamp> hlcSupplier,
                                                     MessageRouter quorumPresenceRouter) {
        return topologyObserver(config, router, TimeSource.system(), snapshotSource, isDecommissioned, hlcSupplier, quorumPresenceRouter);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource,
                                                     GenerationSnapshotSource snapshotSource,
                                                     Predicate<NodeId> isDecommissioned,
                                                     Supplier<HlcTimestamp> hlcSupplier) {
        return topologyObserver(config, router, timeSource, snapshotSource, isDecommissioned, hlcSupplier, router);
    }

    /// Deepest builder: constructs the `Manager` record. `quorumPresenceRouter` carries the
    /// quorum established/lost `ClusterStateNotification` edge; callers default it to `router`
    /// for shared-bus behaviour or pass a dedicated single-subscriber channel.
    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource,
                                                     GenerationSnapshotSource snapshotSource,
                                                     Predicate<NodeId> isDecommissioned,
                                                     Supplier<HlcTimestamp> hlcSupplier,
                                                     MessageRouter quorumPresenceRouter) {
        // Validate that self node is in coreNodes - required for self() to work
        var selfInCoreNodes = config.coreNodes()
                                    .stream()
                                    .anyMatch(info -> info.id()
                                                          .equals(config.self()));
        if (!selfInCoreNodes) {
            return new TopologyError.SelfNodeNotInCoreNodes(config.self()).result();
        }
        record Manager(Map<NodeId, NodeState> nodeStatesById,
                       Map<NodeAddress, NodeId> nodeIdsByAddress,
                       MessageRouter router,
                       MessageRouter quorumPresenceRouter,
                       TopologyConfig config,
                       TimeSource timeSource,
                       AtomicBoolean active,
                       AtomicInteger effectiveClusterSize,
                       Set<NodeId> coreNodeIds,
                       Set<NodeId> tombstonedNodes,
                       GenerationSnapshotSource snapshotSource,
                       Predicate<NodeId> isDecommissioned,
                       AtomicBoolean quorumEstablished,
                       AtomicBoolean started,
                       Object lifecycleLock,
                       AtomicReference<Option<ScheduledFuture<?>>> reconcileFuture,
                       AtomicReference<Set<NodeId>> previousCoreMembers,
                       AtomicReference<TopologyMode> mode,
                       Supplier<HlcTimestamp> hlcSupplier) implements TopologyObserver {
            private static final Logger log = LoggerFactory.getLogger(TopologyObserver.class);

            Manager(Map<NodeId, NodeState> nodeStatesById,
                    Map<NodeAddress, NodeId> nodeIdsByAddress,
                    MessageRouter router,
                    MessageRouter quorumPresenceRouter,
                    TopologyConfig config,
                    TimeSource timeSource,
                    AtomicBoolean active,
                    AtomicInteger effectiveClusterSize,
                    Set<NodeId> coreNodeIds,
                    Set<NodeId> tombstonedNodes,
                    GenerationSnapshotSource snapshotSource,
                    Predicate<NodeId> isDecommissioned,
                    AtomicBoolean quorumEstablished,
                    AtomicBoolean started,
                    Object lifecycleLock,
                    AtomicReference<Option<ScheduledFuture<?>>> reconcileFuture,
                    AtomicReference<Set<NodeId>> previousCoreMembers,
                    AtomicReference<TopologyMode> mode,
                    Supplier<HlcTimestamp> hlcSupplier) {
                this.config = config;
                this.router = router;
                this.quorumPresenceRouter = quorumPresenceRouter;
                this.nodeStatesById = nodeStatesById;
                this.nodeIdsByAddress = nodeIdsByAddress;
                this.timeSource = timeSource;
                this.active = active;
                this.effectiveClusterSize = effectiveClusterSize;
                this.coreNodeIds = coreNodeIds;
                this.tombstonedNodes = tombstonedNodes;
                this.snapshotSource = snapshotSource;
                this.isDecommissioned = isDecommissioned;
                this.quorumEstablished = quorumEstablished;
                this.started = started;
                this.lifecycleLock = lifecycleLock;
                this.reconcileFuture = reconcileFuture;
                this.previousCoreMembers = previousCoreMembers;
                this.mode = mode;
                this.hlcSupplier = hlcSupplier;
                this.effectiveClusterSize.set(config.clusterSize());
                // Configured-core IDENTITY (v2-architecture §5.4): `coreNodeIds` is the static
                // quorum/role denominator and is populated directly from `config.coreNodes()`
                // (non-passive entries), independent of the QUIC dial set. This is the set
                // `coreNodes()` / `effectiveMembership()` fall back to before a `MembershipView`
                // snapshot exists. It is NOT the dial set — discovery (`addNode`) still adds
                // freshly-discovered non-passive peers (e.g. CTM replacements) to it as well.
                config().coreNodes()
                      .stream()
                      .filter(node -> node.role() != NodeRole.PASSIVE)
                      .map(NodeInfo::id)
                      .forEach(coreNodeIds::add);
                // QUIC DIAL SET (v2-architecture §5.4): `nodeStatesById` is populated by SWIM
                // discovery (`handleDiscoveredNodes` → `addNode`) + self ONLY. Static
                // `config.coreNodes()` is NOT seeded here — a configured peer becomes dialable
                // only after SWIM discovers it (seeds → ANNOUNCE → DiscoveredNodes → addNode).
                // Self must be present so `self()` works and so the local node counts itself.
                // PEERS/`coreNodes` continue to feed SWIM's seed/ANNOUNCE path (NettySwimTransport),
                // which is outside this observer; SWIM then feeds QUIC via discovery.
                config().coreNodes()
                      .stream()
                      .filter(node -> node.id().equals(config.self()))
                      .forEach(this::addNode);
                // Self node validation is done in the factory method before construction
                log.trace("Topology observer {} initialized (dial set = self only; coreNodeIds from config), cluster size {}",
                          config.self(),
                          config.clusterSize());
                // The periodic reconciliation timer is scheduled by `start()`, NOT the
                // constructor. Constructor-time scheduling would (a) run before `started`
                // is flipped, so the first ticks observed `started=false` and silently
                // no-op'd, and (b) was never cancelled by `stop()`, so the timer kept
                // firing for the lifetime of the JVM after the observer was stopped.
            }

            private Instant now() {
                return Instant.ofEpochSecond(0, timeSource.nanoTime());
            }

            private void initReconcile() {
                if (active.get()) {
                    // v2-architecture §5.4: the QUIC dial set (`nodeStatesById`) is SWIM-discovery-
                    // derived, so there is NO static re-seed from `config.coreNodes()` here. The
                    // periodic tick only drives reconnection of peers ALREADY in the discovery-
                    // derived set: `reconcile()` (the `ConnectedNodesList` handler) requests a
                    // reconnect for every known-but-not-connected peer. A configured peer that
                    // SWIM has not (yet) discovered is simply absent from the dial set and is
                    // (re)admitted only via `handleDiscoveredNodes` when SWIM gossip carries it.
                    // A departed peer leaves the set when it is removed and is NOT resurrected
                    // from config — it is left for the leader's auto-heal to provision a
                    // replacement, which SWIM then discovers.
                    router().route(new NetworkServiceMessage.ListConnectedNodes());
                }
            }

            @Override
            public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {
                var snapshot = new HashSet<>(nodeStatesById.keySet());
                // Self node is never in peerLinks (no self-connection), so always exclude it
                // to avoid routing a ConnectNode(self) message every reconciliation interval.
                snapshot.remove(config.self());
                connectedNodesList.connected()
                                  .forEach(snapshot::remove);
                snapshot.forEach(this::requestConnectionIfEligible);
            }

            @Override
            public void handleReevaluateMembership(NetworkServiceMessage.ReevaluateMembership message) {
                // Confirmed-departure edge: re-run the quorum/delta evaluation so the dead peer
                // (already dropped from the membership view) is diffed out and routed as
                // NodeRemoved + pruned via removeNode. evaluateQuorumState is safe once-on-death
                // (quorum notif is compareAndSet-gated, delta is previousCoreMembers.getAndSet-
                // gated → idempotent); only PER-TICK frequency regresses convergence.
                log.debug("Membership re-evaluation requested on departure of {}", message.departed());
                evaluateQuorumState();
            }

            private void requestConnectionIfEligible(NodeId id) {
                Option.option(nodeStatesById.get(id))
                      .filter(state -> state.canAttemptConnection(now()))
                      .onPresent(_ -> requestConnection(id));
            }

            @Override
            public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {
                var nodeInfos = nodeStatesById.values()
                                              .stream()
                                              .map(NodeState::info)
                                              .toList();
                router().route(new NetworkServiceMessage.Send(discoverNodes.self(),
                                                              new NetworkMessage.DiscoveredNodes(discoverNodes.self(),
                                                                                                 nodeInfos)));
            }

            @Override
            public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {
                // Tombstone filter: don't resurrect a locally-removed peer just because a gossip
                // partner still knows about it (their REMOVE may not have propagated yet).
                discoveredNodes.nodes()
                               .stream()
                               .filter(info -> !tombstonedNodes.contains(info.id()))
                               .forEach(this::addNode);
            }

            private void addNode(NodeInfo nodeInfo) {
                var now = now();
                var initialState = NodeState.healthy(nodeInfo, now);
                // To avoid reliance on the networking layer behavior, adding is done
                // atomically and the command to establish the connection is sent only once.
                Option.option(nodeStatesById().putIfAbsent(nodeInfo.id(),
                                                           initialState))
                      .onEmpty(() -> {
                                   nodeIdsByAddress().putIfAbsent(nodeInfo.address(),
                                                                  nodeInfo.id());
                                   if (nodeInfo.role() != NodeRole.PASSIVE) {
                                       coreNodeIds.add(nodeInfo.id());
                                   }
                                   // Only request connection if topology observer is active (router is ready)
                if (active().get()) {
                                       requestConnection(nodeInfo.id());
                                   }
                                   evaluateQuorumState();
                               });
            }

            private void requestConnection(NodeId id) {
                router().route(new NetworkServiceMessage.ConnectNode(id));
            }

            private void removeNode(NodeId nodeId) {
                // Never remove self node - would cause NPE in self() method
                if (nodeId.equals(config.self())) {
                    log.warn("Ignoring removal of self node {}", nodeId);
                    return;
                }
                // Remove from core node set — node is no longer operational
                coreNodeIds.remove(nodeId);
                // To avoid reliance on the networking layer behavior, removing is done
                // atomically and command to drop the connection is sent only once.
                Option.option(nodeStatesById().remove(nodeId))
                      .onPresent(state -> {
                                     nodeIdsByAddress.remove(state.info()
                                                                  .address());
                                     router().route(new NetworkServiceMessage.DisconnectNode(nodeId));
                                     evaluateQuorumState();
                                 });
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                return Option.option(nodeStatesById.get(id))
                             .map(NodeState::info);
            }

            @Override
            public Option<NodeState> getState(NodeId id) {
                return Option.option(nodeStatesById.get(id));
            }

            @Override
            public Set<NodeId> coreNodes() {
                return snapshotSource.currentMembershipView()
                                     .map(MembershipView::coreMemberIds)
                                     .or(() -> Collections.unmodifiableSet(coreNodeIds));
            }

            @Override
            public EffectiveMembership effectiveMembership() {
                return snapshotSource.currentMembershipView()
                                     .map(view -> new EffectiveMembership(view.coreMemberIds(),
                                                                           EffectiveMembership.Source.SNAPSHOT))
                                     .or(() -> new EffectiveMembership(Collections.unmodifiableSet(coreNodeIds),
                                                                        EffectiveMembership.Source.LEGACY));
            }

            /// RC1 Step 5: expose the same `AtomicBoolean` mutated by `evaluateQuorumState`.
            /// External readers thread this through `MembershipView.strict(...)` so a
            /// minority-side node stops advertising `ON_DUTY` peers it cannot serve.
            @Override
            public BooleanSupplier inQuorum() {
                return quorumEstablished::get;
            }

            @Override
            public List<NodeId> topology() {
                return nodeStatesById.keySet()
                                     .stream()
                                     .sorted()
                                     .toList();
            }

            @Override
            public int clusterSize() {
                return effectiveClusterSize.get();
            }

            @Override
            public TopologyMode topologyMode() {
                return mode.get();
            }

            @Override
            public int readyNodeCount() {
                // BOOTING vs NORMAL semantics (audit Step 5, R1):
                //   BOOTING — snapshot may be absent because quorum has never been
                //     observed; fall back to legacy in-memory `nodeStatesById` size of
                //     non-passive peers + self so `/health/ready` does not stall waiting
                //     on a snapshot that itself requires quorum.
                //   NORMAL — snapshot is the SOLE source. If absent (publisher hiccup
                //     during steady state), report 0 rather than leak a transport-derived
                //     count that disagrees with the leader's view.
                var view = snapshotSource.currentMembershipView();
                if (view.isPresent()) {
                    maybeTransitionToNormal(view.unwrap());
                    return view.unwrap().onDutyMemberIds().size();
                }
                if (mode.get() == TopologyMode.BOOTING) {
                    return legacyActiveNodeCount();
                }
                return 0;
            }

            @Override
            public int healthyActiveNodeCount() {
                // BOOTING vs NORMAL semantics (audit Step 5, R1):
                //   BOOTING — fall back to legacy in-memory map (defaults to HEALTHY on
                //     add) so the bootstrap path can advance before the first snapshot.
                //   NORMAL — snapshot is the SOLE source. If absent, report 0.
                var view = snapshotSource.currentMembershipView();
                if (view.isPresent()) {
                    maybeTransitionToNormal(view.unwrap());
                    return view.unwrap().healthyOnDutyCount();
                }
                if (mode.get() == TopologyMode.BOOTING) {
                    return legacyHealthyActiveNodeCount();
                }
                return 0;
            }

            /// One-way transition `BOOTING -> NORMAL` triggered by the FIRST observation
            /// of a `MembershipView` whose projected `coreMemberIds` reaches quorum
            /// (`clusterSize/2 + 1`). After flip, all `*Count` reads stop consulting the
            /// legacy `nodeStatesById` fallback and become snapshot-only.
            ///
            /// Idempotent: subsequent snapshots (smaller or larger) do not flip back.
            private void maybeTransitionToNormal(MembershipView view) {
                if (mode.get() == TopologyMode.NORMAL) {
                    return;
                }
                var quorum = quorumSize();
                if (view.coreMemberIds().size() >= quorum
                    && mode.compareAndSet(TopologyMode.BOOTING, TopologyMode.NORMAL)) {
                    log.info("Topology mode transitioned BOOTING -> NORMAL (snapshot coreMemberIds={} >= quorum={})",
                             view.coreMemberIds().size(), quorum);
                }
            }

            /// Legacy active-node count for `readyNodeCount` BOOTING fallback. Counts
            /// non-passive entries in `nodeStatesById` regardless of `NodeHealth` because
            /// the early-bootstrap path needs to count provisionally-joined peers before
            /// any successful Ack establishes their HEALTHY edge.
            private int legacyActiveNodeCount() {
                return (int) nodeStatesById.values()
                                           .stream()
                                           .filter(state -> state.info().role() != NodeRole.PASSIVE)
                                           .count();
            }

            /// Legacy healthy-active-node count for `healthyActiveNodeCount` BOOTING
            /// fallback. INCLUDES self (matching the snapshot-derived
            /// `healthyOnDutyCount` shape used by `NORMAL`-mode callers).
            private int legacyHealthyActiveNodeCount() {
                return (int) nodeStatesById.values()
                                           .stream()
                                           .filter(state -> state.info().role() != NodeRole.PASSIVE)
                                           .filter(state -> state.health() == NodeHealth.HEALTHY)
                                           .count();
            }

            /// Updates the `quorumEstablished` atomic so that `inQuorum()` and the
            /// `publishMembershipDeltas` gate read the latest local view, AND routes the
            /// cold-start/resume `ClusterStateNotification` originator on each quorum edge.
            ///
            /// Bootstrap originator (restored after E2 Phase 2c.0): on the `false → true`
            /// quorum edge this routes `ClusterStateNotification.active()`; on `true → false`,
            /// `.passive()`. This is the ONLY producer of the *first* ACTIVE — `RabiaEngine`
            /// leaves `Stopped` only on receiving ACTIVE, and `ConsensusBridge` merely echoes
            /// an already-active engine, so it cannot self-originate the cold-start signal.
            /// 2c.0 suppressed this emission (intending the bridge to own it) and thereby
            /// deadlocked cold-start; restoring it here re-establishes both initial formation
            /// and quorum-return resume. `RabiaEngine` ignores a redundant ACTIVE while already
            /// active (the bridge echo), so the bridge can keep owning steady-state transitions
            /// for downstream `ClusterStateNotification` consumers without conflict.
            ///
            /// Quorum-presence rewiring: the established/lost edge is now delivered ONLY to
            /// `RabiaEngine`, via the dedicated single-subscriber `quorumPresenceRouter`
            /// channel — NOT the cluster-wide shared bus. This makes the quorum signal flow
            /// strictly unidirectional (`TopologyObserver → RabiaEngine → ConsensusBridge →
            /// everyone else`): consensus receives its activation trigger privately, while
            /// `ConsensusBridge` remains the SOLE shared-bus emitter of Rabia's active status
            /// to all public `ClusterStateNotification` consumers and `LeaderManager`. Only the
            /// two lines below use `quorumPresenceRouter`; every other `router.route(...)` in
            /// this class (`MembershipDecision`, transport service messages) stays on the
            /// shared `router`.
            ///
            /// Idempotent: only fires on `false → true` (active) or `true → false`
            /// (passive) edge transitions via CAS. SWIM filters transient flap upstream via
            /// its suspect-window so the observer sees only post-filtered membership
            /// decisions.
            ///
            /// Quorum source (membership-unification P2): when an injected `MembershipView`
            /// is present, quorum is read directly off that authoritative view
            /// (`healthyOnDutyCount >= quorumSize`) rather than counting the local QUIC/SWIM
            /// `nodeStatesById` connectivity. Consensus thereby derives quorum from the single
            /// SWIM-fed membership tracker (injected through `GenerationSnapshotSource`) and
            /// stays decoupled from the SWIM impl. The legacy `nodeStatesById` peer count is
            /// retained ONLY as the pre-view BOOTING fallback (see `haveQuorum`).
            ///
            /// Startup gate: this method short-circuits while `started` is false. The
            /// constructor seeds `coreNodes` (including self) via `addNode`, which would
            /// otherwise route through this evaluator before the production
            /// `MessageRouter.DelegateRouter` has had its delegate field populated by the
            /// node bootstrap wiring.
            private void evaluateQuorumState() {
                if (!started.get()) {
                    return;
                }
                var haveQuorum = haveQuorum();
                if (haveQuorum) {
                    if (quorumEstablished.compareAndSet(false, true)) {
                        log.info("Quorum established (local view) — routing ClusterStateNotification.ACTIVE to RabiaEngine via private quorum-presence channel");
                        quorumPresenceRouter.route(ClusterStateNotification.active());
                    }
                } else {
                    if (quorumEstablished.compareAndSet(true, false)) {
                        log.warn("Quorum lost (local view) — routing ClusterStateNotification.PASSIVE to RabiaEngine via private quorum-presence channel");
                        quorumPresenceRouter.route(ClusterStateNotification.passive());
                    }
                }
                publishMembershipDeltas();
            }

            /// Quorum decision sourced from the injected `MembershipView` (membership-unification
            /// P2). When the view is present it is the SOLE authority: `healthyOnDutyCount`
            /// (the count of `ON_DUTY` core members the SWIM-fed tracker reports HEALTHY) is
            /// compared against `quorumSize()` directly — no `+ 1`/self adjustment, because the
            /// tracker's `healthyOnDutyCount` already includes self when self is `ON_DUTY`.
            ///
            /// When the view is absent (no tracker injected yet / very early boot) this falls
            /// back to the legacy `nodeStatesById` peer count (`legacyHealthyActivePeerCount()
            /// + 1 >= quorumSize()`) so construction and view-less tests still cold-start.
            private boolean haveQuorum() {
                var view = snapshotSource.currentMembershipView();
                if (view.isPresent()) {
                    maybeTransitionToNormal(view.unwrap());
                    var healthyOnDuty = view.unwrap().healthyOnDutyCount();
                    var quorum = quorumSize();
                    log.debug("Quorum evaluation (membership view): healthyOnDuty={} threshold={}", healthyOnDuty, quorum);
                    return healthyOnDuty >= quorum;
                }
                var peers = legacyHealthyActivePeerCount();
                var quorum = quorumSize();
                log.debug("Quorum evaluation (legacy fallback): healthyActivePeers+1={} threshold={}", peers + 1, quorum);
                return (peers + 1) >= quorum;
            }

            /// Diff the latest `MembershipView` against the previously observed core member
            /// set and route one `MembershipDecision.NodeJoined` / `NodeRemoved` per edge.
            /// This method is the **exclusive emitter of `MembershipDecision`** — single-
            /// source-of-truth for cluster-canonical membership decisions. Downstream
            /// subscribers drive off this canonical edge stream rather than the parallel
            /// SWIM / QUIC / KV-lifecycle paths that previously amplified a single peer
            /// departure into N+ redundant routings.
            ///
            /// Membership-v2 finale: the synthetic per-node lifecycle-projection walker was
            /// removed. Membership is presence-derived (`coreMemberIds()` = `presenceSampler.currentMembers()`),
            /// so only the core-membership delta (`NodeJoined` / `NodeRemoved`) is emitted here.
            /// Every emission is
            /// stamped with `(logIndex = snapshotSource.observedRabiaTerm(), stampedAt =
            /// hlcSupplier.get())`. The `observedRabiaTerm()` is the closest committed-index
            /// proxy available at this layer; subscribers that need a strict per-event
            /// commit index should use the consensus log directly. When the observer cannot
            /// reconstruct a committed term (early bootstrap / unwired tests) the term is
            /// `0L`; subscribers dedup with the documented `-1L` sentinel guard.
            ///
            /// RC1 Step 5 quorum gate: minority-side observers MUST NOT emit
            /// `MembershipDecision` — they cannot make cluster-canonical statements about
            /// membership. The `inQuorum()` check suppresses emission in this window so a
            /// non-quorate leader does not advertise membership decisions the majority has
            /// not committed.
            ///
            /// No-op when the snapshot source is empty (legacy / cold-boot windows). The
            /// `started` gate is enforced upstream by `evaluateQuorumState` so the router
            /// is fully wired by the time this method is invoked.
            private void publishMembershipDeltas() {
                var view = snapshotSource.currentMembershipView();
                if (view.isEmpty()) {
                    return;
                }
                // RC1 Step 5: minority-side observers cannot make cluster-canonical
                // statements. Suppress emission when non-quorate so a partitioned leader
                // does not advertise decisions the majority has not committed.
                if (!quorumEstablished.get()) {
                    return;
                }
                // Hook the BOOTING -> NORMAL transition into the snapshot-arrival path so
                // it is checked on every snapshot publish, not only when external readers
                // happen to query a count.
                maybeTransitionToNormal(view.unwrap());
                var snapshot = view.unwrap();
                var logIndex = snapshotSource.observedRabiaTerm();
                var stampedAt = hlcSupplier.get();
                publishCoreMembershipDelta(snapshot, logIndex, stampedAt);
            }

            private void publishCoreMembershipDelta(MembershipView snapshot, long logIndex, HlcTimestamp stampedAt) {
                var current = snapshot.coreMemberIds();
                var previous = previousCoreMembers.getAndSet(Set.copyOf(current));
                var delta = MembershipDelta.diff(previous, current);
                if (delta.isEmpty()) {
                    return;
                }
                var topology = List.copyOf(current);
                for (var added : delta.added()) {
                    log.debug("Membership delta: NodeJoined {} (logIndex={}, stampedAt={})", added, logIndex, stampedAt);
                    router.route(MembershipDecision.nodeJoined(added, topology, logIndex, stampedAt));
                }
                for (var removed : delta.removed()) {
                    log.debug("Membership delta: NodeRemoved {} (logIndex={}, stampedAt={})", removed, logIndex, stampedAt);
                    router.route(MembershipDecision.nodeRemoved(removed, topology, logIndex, stampedAt));
                    // Prune the departed core node from nodeStatesById so topology()/clusterSize()
                    // readers (StatusRoutes, CTM, dashboard, topology-route) stop reporting it. This
                    // is the sole production caller of removeNode — without it departed nodes are
                    // never pruned (over-provision: nodeCount stays 6 after a kill). Safe: the delta
                    // fires only post-quorum-commit, so it cannot affect the pre-quorum BOOTING
                    // fallback that also reads nodeStatesById; nodeStatesById is keyed per-node, so
                    // pruning a removed node cannot drop a still-present one.
                    removeNode(removed);
                }
            }


            /// Healthy active peers, excluding self. Used by the canonical quorum-state
            /// publisher: `+ 1` is added back to include self, matching the formula
            /// formerly used by the QUIC/Netty transports.
            /// BOOTING vs NORMAL semantics:
            ///   BOOTING — snapshot is published only after Rabia commits, which itself
            ///     requires quorum to be established. Falling back to the legacy
            ///     `nodeStatesById` count avoids the catch-22 where `/health/ready` never
            ///     flips and bootstrap times out.
            ///   NORMAL — count peers that are (a) in the snapshot's `coreMemberIds` AND
            ///     (b) HEALTHY in the live SWIM `nodeStatesById` map. Using `coreMemberIds`
            ///     (not `onDutyMemberIds`) bridges the auto-heal lag window: replacement
            ///     nodes start as JOINING in the snapshot (not yet ON_DUTY) but SWIM has
            ///     already verified their connectivity. Peers outside `coreMemberIds` are
            ///     NOT counted even if SWIM-healthy, preserving the minority-partition
            ///     safety invariant — a truly-partitioned node is absent from the
            ///     authoritative snapshot's coreMemberIds.
            private int healthyActivePeerCount() {
                var view = snapshotSource.currentMembershipView();
                if (view.isPresent()) {
                    maybeTransitionToNormal(view.unwrap());
                    return swimHealthyCorePeerCount(view.unwrap().coreMemberIds());
                }
                if (mode.get() == TopologyMode.BOOTING) {
                    return legacyHealthyActivePeerCount();
                }
                return 0;
            }

            /// Count peers that are both in the authoritative core-member set (from the
            /// snapshot) and HEALTHY in the live SWIM `nodeStatesById` map, excluding self.
            /// This is the NORMAL-mode quorum count that bridges the JOINING lag window
            /// while respecting minority-partition safety.
            private int swimHealthyCorePeerCount(Set<NodeId> coreMembers) {
                return (int) nodeStatesById.values()
                                           .stream()
                                           .filter(state -> !state.info().id().equals(config.self()))
                                           .filter(state -> state.info().role() != NodeRole.PASSIVE)
                                           .filter(state -> state.health() == NodeHealth.HEALTHY)
                                           .filter(state -> coreMembers.contains(state.info().id()))
                                           .count();
            }

            /// Snapshot's `healthyOnDutyCount` may include self; subtract it deterministically
            /// so the `+ 1` in `evaluateQuorumState` does not double-count.
            private int peerHealthyOnDutyCount(MembershipView view) {
                var selfHealthy = view.onDutyMemberIds().contains(config.self()) ? 1 : 0;
                return Math.max(0, view.healthyOnDutyCount() - selfHealthy);
            }

            /// Pre-snapshot fallback — counts peers in `nodeStatesById` whose initial
            /// `NodeHealth` is HEALTHY (this defaults to true on `addNode`). This
            /// path is used ONLY by `healthyActivePeerCount` for the bootstrap quorum
            /// publish; the public `healthyActiveNodeCount` (used by CTM and operator
            /// status) remains snapshot-only.
            private int legacyHealthyActivePeerCount() {
                return (int) nodeStatesById.values()
                                          .stream()
                                          .filter(state -> !state.info().id().equals(config.self()))
                                          .filter(state -> state.info().role() != NodeRole.PASSIVE)
                                          .filter(state -> state.health() == NodeHealth.HEALTHY)
                                          .count();
            }

            @Override
            public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {
                int newSize = message.clusterSize();
                int currentSize = effectiveClusterSize.get();
                if (newSize < 3) {
                    log.info("rejected: cluster size change to {} below minimum 3 for Byzantine fault tolerance", newSize);
                    return;
                }
                if (newSize > currentSize) {
                    int newQuorum = newSize / 2 + 1;
                    // RC1-9 audit Step 5: handleSetClusterSize uses the same snapshot-derived
                    // healthy count as `healthyActivePeerCount`; the legacy
                    // `activeTopologySize()` (transport-only count) is gone.
                    int activeNodes = healthyActivePeerCount() + 1;
                    if (activeNodes < newQuorum) {
                        log.info("rejected: insufficient healthy nodes for cluster size {} -> {} (active={}, required quorum={})",
                                 currentSize,
                                 newSize,
                                 activeNodes,
                                 newQuorum);
                        return;
                    }
                }
                int oldQuorum = currentSize / 2 + 1;
                int newQuorum = newSize / 2 + 1;
                effectiveClusterSize.set(newSize);
                log.info("Cluster size changed from {} to {} (quorum: {} -> {})",
                         currentSize,
                         newSize,
                         oldQuorum,
                         newQuorum);
                // Cluster-size changes shift the quorum threshold without touching
                // `nodeStatesById`. Route through the canonical edge-transition publisher
                // so the same latch state is used everywhere — preserving the original
                // size-shrink "quorum re-established" fire while also covering the
                // size-grow "quorum lost because threshold rose above current healthy"
                // case that the legacy single-direction check missed.
                evaluateQuorumState();
            }

            @Override
            public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return (socketAddress instanceof InetSocketAddress inet)
                       ? NodeAddress.nodeAddress(inet)
                                    .option()
                                    .flatMap(addr -> Option.option(nodeIdsByAddress.get(addr)))
                       : Option.empty();
            }

            @Override
            public Promise<Unit> start() {
                // Hold `lifecycleLock` only for the state-mutation phase (CAS on
                // `active`, flip `started`, schedule + store the reconcile future).
                // The synchronous edge-publication side-effects (`evaluateQuorumState`
                // → `router.route(...)`) and the initial `initReconcile()` invocation
                // run AFTER releasing the lock so a re-entrant `MessageRouter`
                // recipient cannot deadlock with the lifecycle lock.
                var shouldPublish = false;
                synchronized (lifecycleLock) {
                    if (active().compareAndSet(false, true)) {
                        log.trace("Starting topology observer at {}", config.self());
                        // Flip the startup gate before publishing so racing mutations
                        // observe a fully wired router. Constructor-time `addNode`
                        // fires for self and any non-decommissioned core nodes ran
                        // with `started=false` and were no-ops; the explicit call
                        // below publishes the initial edge exactly once.
                        started.set(true);
                        var future = SharedScheduler.scheduleAtFixedRate(this::initReconcile,
                                                                          config.reconciliationInterval());
                        reconcileFuture.set(Option.some(future));
                        shouldPublish = true;
                    }
                }
                if (shouldPublish) {
                    evaluateQuorumState();
                    initReconcile();
                }
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<Unit> stop() {
                synchronized (lifecycleLock) {
                    // Reset `started` so a subsequent `start()` re-publishes the initial
                    // edge cleanly (otherwise `evaluateQuorumState` short-circuits).
                    started.set(false);
                    active().set(false);
                    // Cancel the periodic reconcile timer scheduled by `start()`.
                    // Without this, `initReconcile` would continue firing forever after the
                    // observer is stopped (keeps routing `ListConnectedNodes` reconnect
                    // requests for the discovery-derived dial set).
                    reconcileFuture.getAndSet(Option.none())
                                   .onPresent(f -> f.cancel(false));
                }
                return Promise.success(Unit.unit());
            }

            @Override
            public NodeInfo self() {
                // Self node is guaranteed to be in topology after constructor completes
                // (added via config.coreNodes().forEach(this::addNode))
                return nodeStatesById().get(config.self())
                                     .info();
            }

            @Override
            public TimeSpan pingInterval() {
                return config().pingInterval();
            }

            @Override
            public TimeSpan helloTimeout() {
                return config().helloTimeout();
            }

            @Override
            public Option<TlsConfig> tls() {
                return config().tls();
            }
        }
        return Result.success(new Manager(new ConcurrentHashMap<>(),
                                          new ConcurrentHashMap<>(),
                                          router,
                                          quorumPresenceRouter,
                                          config,
                                          timeSource,
                                          new AtomicBoolean(false),
                                          new AtomicInteger(config.clusterSize()),
                                          ConcurrentHashMap.newKeySet(),
                                          ConcurrentHashMap.newKeySet(),
                                          snapshotSource,
                                          isDecommissioned,
                                          new AtomicBoolean(false),
                                          new AtomicBoolean(false),
                                          new Object(),
                                          new AtomicReference<>(Option.none()),
                                          new AtomicReference<>(Set.<NodeId>of()),
                                          new AtomicReference<>(TopologyMode.BOOTING),
                                          hlcSupplier));
    }
}
