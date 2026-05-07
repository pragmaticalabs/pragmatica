package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
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

    @MessageReceiver
    void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes);

    @MessageReceiver
    void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes);

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
                       Set<NodeId> coreNodeIds,
                       Set<NodeId> tombstonedNodes,
                       GenerationSnapshotSource snapshotSource,
                       Predicate<NodeId> isDecommissioned,
                       AtomicBoolean quorumEstablished,
                       AtomicBoolean started,
                       Object lifecycleLock,
                       AtomicReference<Option<ScheduledFuture<?>>> reconcileFuture,
                       AtomicReference<Set<NodeId>> previousCoreMembers) implements TopologyObserver {
            private static final Logger log = LoggerFactory.getLogger(TopologyObserver.class);

            Manager(Map<NodeId, NodeState> nodeStatesById,
                    Map<NodeAddress, NodeId> nodeIdsByAddress,
                    MessageRouter router,
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
                    AtomicReference<Set<NodeId>> previousCoreMembers) {
                this.config = config;
                this.router = router;
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
            public int readyNodeCount() {
                // Snapshot-projected ON_DUTY set is the sole source of truth: dynamically
                // provisioned nodes may be ON_DUTY in the leader-projected view before
                // appearing in local nodeStatesById. KV NodeLifecycleKey writes
                // (HealthReconciler) are authoritative; if the snapshot is absent (pre-sync),
                // we report 0 ready nodes rather than fall back to a transport-derived view.
                return snapshotSource.currentMembershipView()
                                     .map(view -> view.onDutyMemberIds().size())
                                     .or(0);
            }

            @Override
            public int healthyActiveNodeCount() {
                // RC1-9 audit Step 5: snapshot is the SOLE source of truth. The legacy
                // `nodeStatesById`-derived fallback is gone — `NodeState.health` is dead
                // state w.r.t. cluster-wide health (defaults to HEALTHY on add, never
                // updates on SWIM FAULTY because audit Step 3 moved that flow through
                // KV-Store). Cold-boot windows where the snapshot is empty now report
                // `0` instead of leaking a transport-derived count that disagrees with
                // the leader's view.
                return snapshotSource.currentMembershipView()
                                     .map(MembershipView::healthyOnDutyCount)
                                     .or(0);
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
            /// router is fully wired. Subsequent KV-driven mutations (snapshot updates,
            /// cluster-size changes) follow the normal idempotent edge-transition rules.
            private void evaluateQuorumState() {
                if (!started.get()) {
                    return;
                }
                var peers = healthyActivePeerCount();
                var quorum = quorumSize();
                var haveQuorum = (peers + 1) >= quorum;
                log.debug("Quorum evaluation: healthyActivePeers+1={} threshold={}", peers + 1, quorum);
                if (haveQuorum) {
                    if (quorumEstablished.compareAndSet(false, true)) {
                        log.info("Quorum established");
                        router.route(QuorumStateNotification.established());
                    }
                } else {
                    if (quorumEstablished.compareAndSet(true, false)) {
                        log.warn("Quorum lost");
                        router.route(QuorumStateNotification.disappeared());
                    }
                }
                publishMembershipDeltas();
            }

            /// Diff the latest `MembershipView` against the previously observed core member
            /// set and route one `TopologyChangeNotification.NodeAdded` / `NodeRemoved` per
            /// edge. Foundation for the membership-state-tracker consolidation
            /// (`aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md`):
            /// downstream subscribers can drive off this canonical edge stream rather than
            /// the parallel SWIM / QUIC / KV-lifecycle paths that today amplify a single
            /// peer departure into N+ redundant routings. Step 1 of the consolidation only
            /// publishes; later steps make this the SOLE driver and retire the alternate
            /// emit paths in QUIC / AetherNode / SwimHealthContext.
            ///
            /// No-op when the snapshot source is empty (legacy / cold-boot windows). The
            /// `started` gate is enforced upstream by `evaluateQuorumState` so the router
            /// is fully wired by the time this method is invoked.
            private void publishMembershipDeltas() {
                var view = snapshotSource.currentMembershipView();
                if (view.isEmpty()) {
                    return;
                }
                var current = view.unwrap().coreMemberIds();
                var previous = previousCoreMembers.getAndSet(Set.copyOf(current));
                var delta = MembershipDelta.diff(previous, current);
                if (delta.isEmpty()) {
                    return;
                }
                var topology = List.copyOf(current);
                for (var added : delta.added()) {
                    log.debug("Membership delta: NodeAdded {}", added);
                    router.route(TopologyChangeNotification.nodeAdded(added, topology));
                }
                for (var removed : delta.removed()) {
                    log.debug("Membership delta: NodeRemoved {}", removed);
                    router.route(TopologyChangeNotification.nodeRemoved(removed, topology));
                }
            }

            /// Healthy active peers, excluding self. Used by the canonical quorum-state
            /// publisher: `+ 1` is added back to include self, matching the formula
            /// formerly used by the QUIC/Netty transports.
            /// RC1-9 audit Step 5: snapshot-only — legacy nodeStatesById fallback is gone.
            /// During cold-boot windows where the snapshot has not yet been published, this
            /// returns 0, which conservatively fails the quorum check and routes
            /// `QuorumStateNotification.disappeared()`. The previous fallback could announce
            /// quorum based on transport-only signals that disagreed with the eventual
            /// snapshot.
            private int healthyActivePeerCount() {
                return snapshotSource.currentMembershipView()
                                     .map(this::peerHealthyOnDutyCount)
                                     .or(0);
            }

            /// Snapshot's `healthyOnDutyCount` may include self; subtract it deterministically
            /// so the `+ 1` in `evaluateQuorumState` does not double-count.
            private int peerHealthyOnDutyCount(MembershipView view) {
                var selfHealthy = view.onDutyMemberIds().contains(config.self()) ? 1 : 0;
                return Math.max(0, view.healthyOnDutyCount() - selfHealthy);
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
                    // edge cleanly (otherwise `evaluateQuorumState` short-circuits while
                    // `initReconcile` could re-seed tombstoned nodes — see review item #10).
                    started.set(false);
                    active().set(false);
                    // Cancel the periodic reconcile timer scheduled by `start()`.
                    // Without this, `initReconcile` would continue firing forever
                    // after the observer is stopped (keeps re-seeding nodes from
                    // config and routing `ListConnectedNodes`).
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
                                          new AtomicReference<>(Set.<NodeId>of())));
    }
}
