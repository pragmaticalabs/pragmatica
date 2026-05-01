package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.JitterUtil;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Topology observer for cluster networks. Tracks connections, health states,
/// SWIM events, and reconnection. This is the read-only topology tracking component;
/// cluster size management is handled by ClusterTopologyManager in the deployment layer.
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

    @MessageReceiver
    void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList);

    /// Idempotently add a peer to the topology. Network adapters call this directly when an
    /// unknown peer completes the Hello handshake (QUIC/Netty Hello handlers → `registerPeer`).
    @Contract void registerPeer(NodeInfo peerInfo);

    /// Idempotently remove a peer from the topology and tombstone it so static-config
    /// reconciliation will not resurrect it. Network adapters call this directly on confirmed
    /// peer departures.
    @Contract void unregisterPeer(NodeId peerId);

    @MessageReceiver
    void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes);

    @MessageReceiver
    void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes);

    @MessageReceiver
    void handleConnectionFailed(NetworkServiceMessage.ConnectionFailed connectionFailed);

    @MessageReceiver
    void handleConnectionEstablished(NetworkServiceMessage.ConnectionEstablished connectionEstablished);

    @MessageReceiver
    void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message);

    /// Which membership source (`SNAPSHOT` vs `LEGACY`) the observer would serve reads
    /// from right now, plus the resulting core-id set. Lets tests and diagnostics verify
    /// that the snapshot-backed path is actually engaged.
    default EffectiveMembership effectiveMembership() {
        return new EffectiveMembership(coreNodes(), EffectiveMembership.Source.LEGACY);
    }

    /// Default predicate used when no KV-backed lifecycle reader is wired (tests and
    /// legacy call sites). Preserves pre-rc1 behaviour: nothing is treated as
    /// DECOMMISSIONED, so `initReconcile` reseeds every `config.coreNodes()` entry.
    Predicate<NodeId> NEVER_DECOMMISSIONED = _ -> false;

    static Result<TopologyObserver> topologyObserver(TopologyConfig config, MessageRouter router) {
        return topologyObserver(config, router, TimeSource.system(), GenerationSnapshotSource.noop(), NEVER_DECOMMISSIONED);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource) {
        return topologyObserver(config, router, timeSource, GenerationSnapshotSource.noop(), NEVER_DECOMMISSIONED);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     GenerationSnapshotSource snapshotSource) {
        return topologyObserver(config, router, TimeSource.system(), snapshotSource, NEVER_DECOMMISSIONED);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource,
                                                     GenerationSnapshotSource snapshotSource) {
        return topologyObserver(config, router, timeSource, snapshotSource, NEVER_DECOMMISSIONED);
    }

    /// Production overload: accepts a `isDecommissioned` predicate driven by the local
    /// KV-Store's `NodeLifecycleValue` atoms. `initReconcile` consults it alongside the
    /// in-memory `tombstonedNodes` set so a DECOMMISSIONED ghost peer that survived a
    /// process restart (consensus log replay) is not silently re-added from
    /// `config.coreNodes()`. The in-memory set still covers the just-removed-this-session
    /// window; this predicate covers the persisted-across-restart window.
    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     Predicate<NodeId> isDecommissioned) {
        return topologyObserver(config, router, TimeSource.system(), GenerationSnapshotSource.noop(), isDecommissioned);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     GenerationSnapshotSource snapshotSource,
                                                     Predicate<NodeId> isDecommissioned) {
        return topologyObserver(config, router, TimeSource.system(), snapshotSource, isDecommissioned);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource,
                                                     GenerationSnapshotSource snapshotSource,
                                                     Predicate<NodeId> isDecommissioned) {
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
                       TopologyConfig config,
                       TimeSource timeSource,
                       AtomicBoolean active,
                       AtomicInteger effectiveClusterSize,
                       java.util.Set<NodeId> readyNodes,
                       Set<NodeId> coreNodeIds,
                       Set<NodeId> tombstonedNodes,
                       GenerationSnapshotSource snapshotSource,
                       Predicate<NodeId> isDecommissioned,
                       AtomicBoolean quorumEstablished,
                       AtomicBoolean started) implements TopologyObserver {
            private static final Logger log = LoggerFactory.getLogger(TopologyObserver.class);

            Manager(Map<NodeId, NodeState> nodeStatesById,
                    Map<NodeAddress, NodeId> nodeIdsByAddress,
                    MessageRouter router,
                    TopologyConfig config,
                    TimeSource timeSource,
                    AtomicBoolean active,
                    AtomicInteger effectiveClusterSize,
                    java.util.Set<NodeId> readyNodes,
                    Set<NodeId> coreNodeIds,
                    Set<NodeId> tombstonedNodes,
                    GenerationSnapshotSource snapshotSource,
                    Predicate<NodeId> isDecommissioned,
                    AtomicBoolean quorumEstablished,
                    AtomicBoolean started) {
                this.config = config;
                this.router = router;
                this.nodeStatesById = nodeStatesById;
                this.nodeIdsByAddress = nodeIdsByAddress;
                this.timeSource = timeSource;
                this.active = active;
                this.effectiveClusterSize = effectiveClusterSize;
                this.readyNodes = readyNodes;
                this.coreNodeIds = coreNodeIds;
                this.tombstonedNodes = tombstonedNodes;
                this.snapshotSource = snapshotSource;
                this.isDecommissioned = isDecommissioned;
                this.quorumEstablished = quorumEstablished;
                this.started = started;
                this.effectiveClusterSize.set(config.clusterSize());
                // Mirror the `initReconcile` filter: a peer the cluster has durably retired
                // (KV NodeLifecycleValue.DECOMMISSIONED) must not be reconstructed from
                // static config on a fresh process restart. Self is always added — it must
                // be present in nodeStatesById for `self()` to work.
                config().coreNodes()
                      .stream()
                      .filter(node -> node.id().equals(config.self()) || !isDecommissioned.test(node.id()))
                      .forEach(this::addNode);
                // Self node validation is done in the factory method before construction
                log.trace("Topology observer {} initialized with {} nodes, cluster size {}",
                          config.self(),
                          config.coreNodes(),
                          config.clusterSize());
                SharedScheduler.scheduleAtFixedRate(this::initReconcile, config.reconciliationInterval());
            }

            private Instant now() {
                return Instant.ofEpochSecond(0, timeSource.nanoTime());
            }

            private void initReconcile() {
                if (active.get()) {
                    // Re-add any configured core nodes that were removed due to disconnection.
                    // Without this, nodes removed from nodeStatesById are never reconnected
                    // because reconcile() only requests connections for nodes IN the map.
                    // Skip tombstoned nodes — these were explicitly removed (node killed/replaced)
                    // and must not be resurrected from config, otherwise they linger as phantoms
                    // alongside any CTM-provisioned replacement and inflate cluster count.
                    //
                    // Also skip nodes whose KV-Store `NodeLifecycleValue` atom is DECOMMISSIONED:
                    // the in-memory `tombstonedNodes` set is cleared on every JVM restart, so
                    // without consulting the KV atom a process restart re-seeds a DECOMMISSIONED
                    // ghost peer from static config. The two filters compose: in-memory covers
                    // the just-removed-this-session window; KV covers the across-restart window.
                    //
                    // Peer eviction on sustained SUSPECTED state is intentionally NOT handled here:
                    // HealthReconciler on the leader consumes accumulated PingTimeout + SwimHint
                    // signals and writes NodeLifecycleKey = LEFT via fenced atom updates, which
                    // replaces the former idle-timer-based evictLongSuspectedPeers path.
                    config().coreNodes().stream()
                          .filter(node -> !nodeStatesById.containsKey(node.id()))
                          .filter(node -> !tombstonedNodes.contains(node.id()))
                          .filter(node -> !isDecommissioned.test(node.id()))
                          .forEach(this::addNode);
                    router().route(new NetworkServiceMessage.ListConnectedNodes());
                } else if (nodeStatesById().size() <= 1) {
                    log.info("Topology drained to self-only — re-seeding from config ({} core nodes)", config().coreNodes().size());
                    tombstonedNodes.clear();
                    // The drained-to-self re-seed still respects KV DECOMMISSIONED — clearing the
                    // local tombstone set does not authorise resurrection of a node that the
                    // cluster has durably retired.
                    config().coreNodes().stream()
                          .filter(node -> !isDecommissioned.test(node.id()))
                          .forEach(this::addNode);
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

            private void requestConnectionIfEligible(NodeId id) {
                Option.option(nodeStatesById.get(id))
                      .filter(state -> state.canAttemptConnection(now()))
                      .onPresent(_ -> requestConnection(id));
            }

            @Override
            public void registerPeer(NodeInfo peerInfo) {
                // Clear tombstone so the node can rejoin on explicit re-add (e.g. restarted container
                // handshakes via QUIC Hello and gets routed as an unknown-node registration).
                tombstonedNodes.remove(peerInfo.id());
                addNode(peerInfo);
            }

            @Override
            public void unregisterPeer(NodeId peerId) {
                // Tombstone prevents initReconcile from resurrecting this node from the static
                // config.coreNodes() list. Cleared on explicit re-add via registerPeer or when the
                // observer drains to self-only.
                tombstonedNodes.add(peerId);
                removeNode(peerId);
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

            @Override
            public void handleConnectionFailed(NetworkServiceMessage.ConnectionFailed connectionFailed) {
                var nodeId = connectionFailed.nodeId();
                nodeStatesById.computeIfPresent(nodeId, (_, state) ->
                    processConnectionFailure(state, connectionFailed.cause()));
                evaluateQuorumState();
            }

            private NodeState processConnectionFailure(NodeState state, Cause cause) {
                var nodeId = state.info()
                                  .id();
                var newAttempts = state.failedAttempts() + 1;
                var backoff = config.backoff();
                var now = now();
                var delay = backoff.backoffStrategy()
                                   .nextTimeout(newAttempts);
                var jitteredDelayMs = JitterUtil.applyJitter(delay.millis(),
                                                             JitterUtil.MIN_FACTOR_DEFAULT,
                                                             JitterUtil.MAX_FACTOR_DEFAULT);
                var jitteredDelay = TimeSpan.timeSpan(jitteredDelayMs).millis();
                var nextAttempt = now.plusNanos(jitteredDelay.nanos());
                log.debug("Node {} connection failed (attempt {}), next attempt after {} (jittered from {}): {}",
                          nodeId,
                          newAttempts,
                          jitteredDelay,
                          delay,
                          cause.message());
                return NodeState.suspected(state.info(), newAttempts, now, nextAttempt);
            }

            @Override
            public void handleConnectionEstablished(NetworkServiceMessage.ConnectionEstablished connectionEstablished) {
                var nodeId = connectionEstablished.nodeId();
                nodeStatesById.computeIfPresent(nodeId, (_, state) ->
                    processConnectionEstablished(state));
                evaluateQuorumState();
            }

            private NodeState processConnectionEstablished(NodeState state) {
                if (state.health() == NodeHealth.SUSPECTED) {
                    log.debug("Node {} recovered from suspected state", state.info().id());
                }
                return NodeState.healthy(state.info(), now());
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
                // Remove from ready set and core node set — node is no longer operational
                readyNodes.remove(nodeId);
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
            public void markReady(NodeId nodeId) {
                readyNodes.add(nodeId);
                log.debug("Node {} marked ready (ON_DUTY). Ready nodes: {}", nodeId, readyNodes.size());
            }

            @Override
            public void markReady(NodeId nodeId, NodeAddress address) {
                readyNodes.add(nodeId);
                // If this node is not in our topology, it was dynamically provisioned
                // by another leader. Add it and request connection.
                if (nodeStatesById.get(nodeId) == null && !nodeId.equals(config.self())) {
                    var nodeInfo = NodeInfo.nodeInfo(nodeId, address);
                    addNode(nodeInfo);
                    log.info("Node {} added to topology via ON_DUTY notification at {}", nodeId, address.asString());
                }
                log.debug("Node {} marked ready (ON_DUTY) with address. Ready nodes: {}", nodeId, readyNodes.size());
            }

            @Override
            public void markDeparted(NodeId nodeId) {
                readyNodes.remove(nodeId);
                log.debug("Node {} marked departed. Ready nodes: {}", nodeId, readyNodes.size());
            }

            @Override
            public int readyNodeCount() {
                // Prefer snapshot-projected ON_DUTY set: dynamically provisioned nodes may be
                // ON_DUTY in the leader-projected view before appearing in local nodeStatesById
                // (and therefore before the local readyNodes membership tracker gets populated
                // via markReady from consensus). Fall back to legacy readyNodes when the
                // snapshot source is absent or no snapshot has arrived yet.
                return snapshotSource.currentMembershipView()
                                     .map(view -> view.onDutyMemberIds().size())
                                     .or(() -> (int) readyNodes.stream()
                                                               .filter(id -> !isPassive(id))
                                                               .count());
            }

            @Override
            public int healthyActiveNodeCount() {
                // Snapshot is the authoritative view when present — the leader has already
                // cross-referenced SWIM/health-hint signals across the whole cluster, whereas
                // local nodeStatesById only sees QUIC connection outcomes observed by self.
                return snapshotSource.currentMembershipView()
                                     .map(MembershipView::healthyOnDutyCount)
                                     .or(() -> (int) nodeStatesById.values()
                                                                   .stream()
                                                                   .filter(state -> state.info().role() != NodeRole.PASSIVE)
                                                                   .filter(state -> state.health() == NodeHealth.HEALTHY)
                                                                   .count());
            }

            private int activeTopologySize() {
                return (int) nodeStatesById.values()
                                          .stream()
                                          .filter(state -> state.health() == NodeHealth.HEALTHY)
                                          .count();
            }

            /// Canonical edge-transition publisher for `QuorumStateNotification`.
            ///
            /// Phase A (commit `5c29a104f`) made SWIM the canonical source for membership
            /// observations: SWIM observations flow through HealthReconciler → topology KV
            /// atoms → `TopologyObserver`. This method completes the symmetric counterpart
            /// for the quorum-loss path: QUIC/Netty transports no longer publish quorum
            /// state — they only manage peer-link state. The observer owns the quorum view
            /// because it already has all the bookkeeping (`quorumSize`, `nodeStatesById`,
            /// health states).
            ///
            /// Idempotent: only fires on `false → true` (established) or `true → false`
            /// (disappeared) edge transitions. SWIM filters transient flap upstream via
            /// its suspect-window so the observer sees only post-filtered membership
            /// decisions — no debounce needed here.
            ///
            /// The peer count excludes self; the `+ 1` adds self as the implicitly-healthy
            /// observer. This matches the formula previously used by `QuicClusterNetwork`
            /// and `NettyClusterNetwork`.
            ///
            /// Startup gate: this method short-circuits while `started` is false. The
            /// constructor seeds `coreNodes` (including self) via `addNode`, which would
            /// otherwise route through this publisher before the production
            /// `MessageRouter.DelegateRouter` has had its delegate field populated by the
            /// node bootstrap wiring — causing an NPE on `delegate.route(...)`. `start()`
            /// flips `started=true` and immediately invokes this method once so the initial
            /// edge state (established or disappeared) is published exactly once after the
            /// router is fully wired. Subsequent mutations (registerPeer, connection
            /// events, cluster-size changes) follow the normal idempotent edge-transition
            /// rules.
            private void evaluateQuorumState() {
                if (!started.get()) {
                    return;
                }
                var haveQuorum = (healthyActivePeerCount() + 1) >= quorumSize();
                if (haveQuorum) {
                    if (quorumEstablished.compareAndSet(false, true)) {
                        log.info("Quorum established (healthy active peers + 1 >= {})", quorumSize());
                        router.route(QuorumStateNotification.established());
                    }
                } else {
                    if (quorumEstablished.compareAndSet(true, false)) {
                        log.warn("Quorum lost (healthy active peers + 1 < {})", quorumSize());
                        router.route(QuorumStateNotification.disappeared());
                    }
                }
            }

            /// Healthy active peers, excluding self. Used by the canonical quorum-state
            /// publisher: `+ 1` is added back to include self, matching the formula
            /// formerly used by the QUIC/Netty transports.
            private int healthyActivePeerCount() {
                return snapshotSource.currentMembershipView()
                                     .map(this::peerHealthyOnDutyCount)
                                     .or(this::legacyHealthyActivePeerCount);
            }

            /// Snapshot's `healthyOnDutyCount` may include self; subtract it deterministically
            /// so the `+ 1` in `evaluateQuorumState` does not double-count.
            private int peerHealthyOnDutyCount(MembershipView view) {
                var selfHealthy = view.onDutyMemberIds().contains(config.self()) ? 1 : 0;
                return Math.max(0, view.healthyOnDutyCount() - selfHealthy);
            }

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
                    log.warn("Rejecting cluster size change to {}: minimum is 3 for Byzantine fault tolerance", newSize);
                    return;
                }
                if (newSize > currentSize) {
                    int newQuorum = newSize / 2 + 1;
                    int activeNodes = activeTopologySize();
                    if (activeNodes < newQuorum) {
                        log.warn("Rejecting cluster size increase from {} to {}: only {} active nodes, need {} for new quorum",
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
                if (active().compareAndSet(false, true)) {
                    log.trace("Starting topology observer at {}", config.self());
                    // Flip the startup gate before publishing so the first
                    // `evaluateQuorumState` call below — and any racing mutation
                    // that lands while `start()` is in flight — observes a fully
                    // wired router. Constructor-time `addNode` fires for self and
                    // any non-decommissioned core nodes ran with `started=false`
                    // and were no-ops; this single explicit call publishes the
                    // initial edge (established or disappeared) exactly once.
                    started.set(true);
                    evaluateQuorumState();
                    initReconcile();
                }
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<Unit> stop() {
                active().set(false);
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
                                          config,
                                          timeSource,
                                          new AtomicBoolean(false),
                                          new AtomicInteger(config.clusterSize()),
                                          ConcurrentHashMap.newKeySet(),
                                          ConcurrentHashMap.newKeySet(),
                                          ConcurrentHashMap.newKeySet(),
                                          snapshotSource,
                                          isDecommissioned,
                                          new AtomicBoolean(false),
                                          new AtomicBoolean(false)));
    }
}
