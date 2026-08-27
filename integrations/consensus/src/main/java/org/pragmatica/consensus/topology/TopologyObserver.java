package org.pragmatica.consensus.topology;

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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Topology observer for cluster networks. Tracks connections, health states,
/// SWIM events, and reconnection. This is the read-only topology tracking component;
/// cluster size management is handled by ClusterTopologyManager in the deployment layer.
///
/// **Membership-delta emission moved out (cluster-topology-overhaul Wave 4):** the observer
/// no longer emits `MembershipDecision` — the sole emitter is the aether
/// `MembershipDeltaProjector`, a pure projection of the `MembershipFsm` delta edge
/// (JOINED / REMOVED), quorum-gated through {@link #inQuorum()} and pruning departed peers
/// through {@link #pruneDeparted(NodeId)}. This observer keeps ONLY quorum-presence
/// evaluation (`evaluateQuorumState` → the `ClusterStateNotification` CAS edge to the
/// dedicated quorum-presence channel) plus the dial-set bookkeeping. Subscribers that need
/// fast local reactions during cluster bootstrap consume `TransportObservation` (emitted by
/// `QuicClusterNetwork` / `SwimProtocol`); cluster-canonical
/// membership reactions consume `MembershipDecision` — conflating the two streams is a
/// subtle dual-reaction bug class, see the javadoc on each type for the epistemic
/// distinction.
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
    /// The two fallbacks are NOT the same read (#557). `healthyActiveNodeCount` /
    /// `readyNodeCount` still count the raw discovery-derived map. The quorum-eval
    /// fallback `healthyActivePeerCount` counts only the map entries that the transport
    /// has ALSO reported as connected — see `connectedPeerCount`. Quorum is a
    /// reachability claim and must not be satisfiable by discovery alone; a readiness
    /// count is not.
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
        BOOTING,
        NORMAL
    }

    /// Current topology mode. Default `NORMAL` for the abstract interface so legacy /
    /// test-only implementations (notably `TopologyManager` minimal stubs) retain the
    /// snapshot-only contract by default.
    default TopologyMode topologyMode() {
        return TopologyMode.NORMAL;
    }

    @Contract
    @MessageReceiver
    void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList);

    @Contract
    @MessageReceiver
    void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes);

    @Contract
    @MessageReceiver
    void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes);

    @Contract
    @MessageReceiver
    void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message);

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

    /// Prune a cluster-canonically departed peer from the observer's bookkeeping
    /// (cluster-topology-overhaul Wave 4). Sole production caller: the aether
    /// `MembershipDeltaProjector`, on the FSM REMOVED delta edge, AFTER routing the
    /// corresponding `MembershipDecision.NodeRemoved` — exactly where the deleted
    /// `publishCoreMembershipDelta` removal arm called `removeNode` from. The production
    /// override delegates to `removeNode` (drop from dial set + `coreNodeIds`, route
    /// `DisconnectNode`, re-evaluate quorum presence via the evaluator's PRE-EXISTING
    /// `removeNode` trigger — §3.1: no NEW path into `evaluateQuorumState`). Default no-op
    /// for legacy / test-only stubs, mirroring the `topologyMode()` / `inQuorum()` defaults.
    @Contract
    default void pruneDeparted(NodeId departed) {}

    /// Default predicate used when no KV-backed lifecycle reader is wired (tests and
    /// legacy call sites). Preserves pre-rc1 behaviour: nothing is treated as
    /// DECOMMISSIONED, so `initReconcile` reseeds every `config.coreNodes()` entry.
    Predicate<NodeId> NEVER_DECOMMISSIONED = _ -> false;
    /// RC1 Step 2 default HLC supplier used by legacy factory overloads / tests that
    /// haven't wired `HlcClock`. Always returns `HlcTimestamp.ZERO` — subscribers that
    /// dedup on `stampedAt` must treat ZERO as a cold-replay sentinel.
    Supplier<HlcTimestamp> ZERO_HLC_SUPPLIER = () -> HlcTimestamp.ZERO;

    static Result<TopologyObserver> topologyObserver(TopologyConfig config, MessageRouter router) {
        return topologyObserver(config,
                                router,
                                TimeSource.system(),
                                GenerationSnapshotSource.noop(),
                                NEVER_DECOMMISSIONED,
                                ZERO_HLC_SUPPLIER);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource) {
        return topologyObserver(config,
                                router,
                                timeSource,
                                GenerationSnapshotSource.noop(),
                                NEVER_DECOMMISSIONED,
                                ZERO_HLC_SUPPLIER);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     GenerationSnapshotSource snapshotSource) {
        return topologyObserver(config,
                                router,
                                TimeSource.system(),
                                snapshotSource,
                                NEVER_DECOMMISSIONED,
                                ZERO_HLC_SUPPLIER);
    }

    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     TimeSource timeSource,
                                                     GenerationSnapshotSource snapshotSource) {
        return topologyObserver(config, router, timeSource, snapshotSource, NEVER_DECOMMISSIONED, ZERO_HLC_SUPPLIER);
    }

    /// Production overload: accepts a `isDecommissioned` predicate driven by the local
    /// KV-Store tombstone atoms, so a DECOMMISSIONED ghost peer that survived a
    /// process restart (consensus log replay) is not silently re-added from
    /// `config.coreNodes()`. RETAINED in Wave 7 (cluster-topology-overhaul): this is the
    /// cross-restart persisted-tombstone hook the in-memory membership FSM cannot cover (the
    /// FSM's incarnation fence does not survive a process restart). The former in-memory
    /// `tombstonedNodes` set — which covered the just-removed-this-session window — is RETIRED:
    /// nothing ever added to it since the Wave-4 delta-projector cutover (it was inert), and the
    /// aether `MembershipFsm` incarnation fence now owns same-session resurrect protection (a
    /// DEAD identity is retained and only a strictly higher incarnation reopens it; the
    /// projector prunes via {@link #pruneDeparted}).
    static Result<TopologyObserver> topologyObserver(TopologyConfig config,
                                                     MessageRouter router,
                                                     Predicate<NodeId> isDecommissioned) {
        return topologyObserver(config,
                                router,
                                TimeSource.system(),
                                GenerationSnapshotSource.noop(),
                                isDecommissioned,
                                ZERO_HLC_SUPPLIER);
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
        return topologyObserver(config,
                                router,
                                TimeSource.system(),
                                snapshotSource,
                                isDecommissioned,
                                hlcSupplier,
                                router);
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
        return topologyObserver(config,
                                router,
                                TimeSource.system(),
                                snapshotSource,
                                isDecommissioned,
                                hlcSupplier,
                                quorumPresenceRouter);
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
        var selfInCoreNodes = config.coreNodes().stream().anyMatch(info -> info.id()
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
                       GenerationSnapshotSource snapshotSource,
                       Predicate<NodeId> isDecommissioned,
                       AtomicBoolean quorumEstablished,
                       AtomicBoolean started,
                       Object lifecycleLock,
                       AtomicReference<Option<ScheduledFuture<?>>> reconcileFuture,
                       AtomicReference<Set<NodeId>> observedConnections,
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
                    GenerationSnapshotSource snapshotSource,
                    Predicate<NodeId> isDecommissioned,
                    AtomicBoolean quorumEstablished,
                    AtomicBoolean started,
                    Object lifecycleLock,
                    AtomicReference<Option<ScheduledFuture<?>>> reconcileFuture,
                    AtomicReference<Set<NodeId>> observedConnections,
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
                this.snapshotSource = snapshotSource;
                this.isDecommissioned = isDecommissioned;
                this.quorumEstablished = quorumEstablished;
                this.started = started;
                this.lifecycleLock = lifecycleLock;
                this.reconcileFuture = reconcileFuture;
                this.observedConnections = observedConnections;
                this.mode = mode;
                this.hlcSupplier = hlcSupplier;
                this.effectiveClusterSize.set(config.clusterSize());
                // Configured-core IDENTITY (v2-architecture §5.4): `coreNodeIds` is the static
                // quorum/role denominator and is populated directly from `config.coreNodes()`
                // (non-passive entries), independent of the QUIC dial set. This is the set
                // `coreNodes()` / `effectiveMembership()` fall back to before a `MembershipView`
                // snapshot exists. It is NOT the dial set — discovery (`addNode`) still adds
                // freshly-discovered non-passive peers (e.g. CTM replacements) to it as well.
                // Wave 7 disposition (cluster-topology-overhaul, parallel-view collapse): RETAINED
                // as the minimal CONSENSUS-BOOTSTRAP fallback ONLY. The FSM-backed snapshot view
                // (`snapshotSource.currentMembershipView()`, latched once quorum-many members are
                // seen) is the authority for every post-bootstrap read; this set is consulted
                // solely while that view is `none()` (cold-start formation, before the FSM latch
                // flips — load-bearing, must not be removed: integrations/consensus cannot import
                // the aether MembershipFsm, and quorum must bootstrap before the view exists).
                config().coreNodes().stream().map(NodeInfo::id).forEach(coreNodeIds::add);
                // QUIC DIAL SET (v2-architecture §5.4): `nodeStatesById` is populated by SWIM
                // discovery (`handleDiscoveredNodes` → `addNode`) + self ONLY. Static
                // `config.coreNodes()` is NOT seeded here — a configured peer becomes dialable
                // only after SWIM discovers it (seeds → ANNOUNCE → DiscoveredNodes → addNode).
                // Self must be present so `self()` works and so the local node counts itself.
                // PEERS/`coreNodes` continue to feed SWIM's seed/ANNOUNCE path (NettySwimTransport),
                // which is outside this observer; SWIM then feeds QUIC via discovery.
                config().coreNodes().stream().filter(node -> node.id()
                                                                 .equals(config.self())).forEach(this::addNode);
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
                    // ORDER IS LOAD-BEARING (#557): refresh the transport observation FIRST,
                    // evaluate quorum SECOND. Since `MessageRouter` dispatch is synchronous
                    // (`SimpleMutableRouter.dispatchOne` calls the handler inline), routing
                    // `ListConnectedNodes` runs `QuicClusterNetwork.listNodes` →
                    // `ConnectedNodesList` → this observer's `reconcile` before returning, so
                    // `observedConnections` is CURRENT by the time `evaluateQuorumState` reads
                    // it through `connectedPeerCount`. With the previous
                    // evaluate-then-refresh order the boot-time quorum decision would always be
                    // taken on a one-interval-stale observation, which both doubles the
                    // worst-case time to establish quorum and delays detecting its loss by the
                    // same amount — defeating the point of sourcing the count from observed
                    // connectivity. This is a REORDER of two pre-existing statements, NOT a new
                    // path into `evaluateQuorumState` (§3.1 hard constraint): the set of
                    // triggers is unchanged.
                    //
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
                    // Periodic quorum-presence re-evaluation (missed-edge safety net). The
                    // structural triggers of `evaluateQuorumState` (addNode / removeNode /
                    // handleSetClusterSize / start) fire only on MEMBERSHIP-SET changes, but the
                    // production quorum decision reads `healthyOnDutyCount()` off the PULL-BASED
                    // `MembershipView` snapshot, which receives no push on change. A transient
                    // QUIC/SWIM flap that drops then RESTORES the snapshot healthy count WITHOUT a
                    // structural membership event therefore leaves no trigger to re-emit the
                    // `QuorumEstablished` edge — Rabia stays paused after `doPauseForQuorumLoss`
                    // and the cluster wedges leaderless permanently (Hetzner gate 2026-06-13). The
                    // re-check here closes that whole class: it is CAS-edge-guarded, so it is a
                    // no-op on a settled cluster (the latch already matches `haveQuorum()`) and
                    // routes a notification ONLY on a genuine false->true / true->false edge —
                    // recovery is bounded to one reconcile interval. This is distinct from the
                    // retired per-tick NodeRemoved DELTA emission (which churned membership): a
                    // level re-check of a latched edge cannot churn. It is ALSO the sole trigger
                    // that can flip the #557 connectivity-sourced boot quorum true, because
                    // `reconcile` deliberately does not call the evaluator.
                    evaluateQuorumState();
                }
            }

            @Contract
            @Override
            public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {
                // #557: latch the transport's post-handshake CONNECTED set as the boot-time
                // quorum evidence. This is the ONLY writer of `observedConnections`; an empty
                // list is recorded verbatim so quorum can DROP when connectivity is lost.
                // Deliberately does NOT call `evaluateQuorumState` — adding a trigger here
                // would violate the §3.1 constraint documented on that method; the periodic
                // `initReconcile` tick consumes the freshly latched set instead.
                observedConnections.set(Set.copyOf(connectedNodesList.connected()));

                var snapshot = new HashSet<>(nodeStatesById.keySet());
                // Self node is never in peerLinks (no self-connection), so always exclude it
                // to avoid routing a ConnectNode(self) message every reconciliation interval.
                snapshot.remove(config.self());
                connectedNodesList.connected().forEach(snapshot::remove);
                snapshot.forEach(this::requestConnectionIfEligible);
            }

            /// Wave-4 prune path: drop a cluster-canonically departed peer via the existing
            /// `removeNode` machinery (dial-set + `coreNodeIds` removal, `DisconnectNode`,
            /// quorum-presence re-evaluation through the evaluator's PRE-EXISTING `removeNode`
            /// trigger). Called by the aether `MembershipDeltaProjector` on the FSM REMOVED
            /// delta edge — the same call the deleted `publishCoreMembershipDelta` removal arm
            /// made. Self-removal is rejected inside `removeNode`; pruning an already-absent
            /// peer is a no-op.
            @Contract
            @Override
            public void pruneDeparted(NodeId departed) {
                log.debug("Pruning departed member {} (membership-delta REMOVED edge)", departed);
                removeNode(departed);
            }

            private void requestConnectionIfEligible(NodeId id) {
                Option.option(nodeStatesById.get(id))
                      .onPresent(_ -> requestConnection(id));
            }

            @Contract
            @Override
            public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {
                var nodeInfos = nodeStatesById.values().stream().map(NodeState::info).toList();

                router().route(new NetworkServiceMessage.Send(discoverNodes.self(),
                                                              new NetworkMessage.DiscoveredNodes(discoverNodes.self(),
                                                                                                 nodeInfos)));
            }

            @Contract
            @Override
            public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {
                // Resurrect protection (Wave 7, cluster-topology-overhaul): the in-memory
                // tombstone filter that used to sit here is RETIRED — the set was inert (nothing
                // added to it since the Wave-4 cutover) and the aether MembershipFsm incarnation
                // fence owns same-session resurrect protection (a cluster-canonically departed
                // peer is pruned via pruneDeparted and its DEAD identity reopens only on a
                // strictly higher incarnation). Cross-restart protection stays with the
                // `isDecommissioned` KV predicate at the factory seam.
                discoveredNodes.nodes().forEach(this::addNode);
            }

            private void addNode(NodeInfo nodeInfo) {
                var now = now();
                var initialState = NodeState.discovered(nodeInfo, now);
                // To avoid reliance on the networking layer behavior, adding is done
                // atomically and the command to establish the connection is sent only once.
                Option.option(nodeStatesById().putIfAbsent(nodeInfo.id(), initialState)).onEmpty(() -> {
                    nodeIdsByAddress().putIfAbsent(nodeInfo.address(), nodeInfo.id());
                    coreNodeIds.add(nodeInfo.id());
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
                Option.option(nodeStatesById().remove(nodeId)).onPresent(state -> {
                    nodeIdsByAddress.remove(state.info().address());
                    router().route(new NetworkServiceMessage.DisconnectNode(nodeId));
                    evaluateQuorumState();
                });
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                return Option.option(nodeStatesById.get(id)).map(NodeState::info);
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

                    return view.unwrap()
                               .onDutyMemberIds()
                               .size();
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

                    return view.unwrap()
                               .healthyOnDutyCount();
                }

                if (mode.get() == TopologyMode.BOOTING) {
                    return discoveredNodeCount();
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

                if (view.coreMemberIds().size() >= quorum && mode.compareAndSet(TopologyMode.BOOTING,
                                                                                TopologyMode.NORMAL)) {
                    log.info("Topology mode transitioned BOOTING -> NORMAL (snapshot coreMemberIds={} >= quorum={})",
                             view.coreMemberIds().size(),
                             quorum);
                }
            }

            /// Legacy active-node count for `readyNodeCount` BOOTING fallback. Counts
            /// non-passive entries in `nodeStatesById` — discovery is all that map records, because
            /// the early-bootstrap path needs to count provisionally-joined peers before
            /// any successful Ack establishes their HEALTHY edge.
            private int legacyActiveNodeCount() {
                return (int) nodeStatesById.values()
                                           .stream()
                                           .count();
            }

            /// Legacy healthy-active-node count for `healthyActiveNodeCount` BOOTING
            /// fallback. INCLUDES self (matching the snapshot-derived
            /// `healthyOnDutyCount` shape used by `NORMAL`-mode callers).
            private int discoveredNodeCount() {
                return (int) nodeStatesById.values()
                                           .stream()
                                           .count();
            }

            /// Updates the `quorumEstablished` atomic so that `inQuorum()` reads the latest
            /// local view, AND routes the cold-start/resume `ClusterStateNotification`
            /// originator on each quorum edge.
            ///
            /// **NARROWED (cluster-topology-overhaul Wave 4, §3.1 hard constraint):** this
            /// method now does quorum-presence ONLY — the `publishMembershipDeltas()` call and
            /// the `previousCoreMembers` baseline diff are GONE; membership-delta emission
            /// lives entirely in the aether `MembershipDeltaProjector`, driven by the
            /// `MembershipFsm` delta edge. This evaluator is load-bearing and timing-sensitive
            /// and is invoked ONLY by its pre-existing triggers (`addNode`, `removeNode`,
            /// `handleSetClusterSize`, `start`) — never broaden it, and never route a new path
            /// into it (the promotion-edge invocation variant caused the bisect-proven 600s
            /// restore-READY flap).
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
            }

            /// Quorum decision sourced from the injected `MembershipView` (membership-unification
            /// P2). When the view is present it is the SOLE authority: `healthyOnDutyCount`
            /// (the count of `ON_DUTY` core members the SWIM-fed tracker reports HEALTHY) is
            /// compared against `quorumSize()` directly — no `+ 1`/self adjustment, because the
            /// tracker's `healthyOnDutyCount` already includes self when self is `ON_DUTY`.
            ///
            /// When the view is absent (no tracker injected yet / very early boot) this falls
            /// back to the legacy `nodeStatesById` peer count (`connectedPeerCount()
            /// + 1 >= quorumSize()`) so construction and view-less tests still cold-start.
            /// Since #557 that fallback is gated on OBSERVED transport connectivity, not on
            /// discovery — see `connectedPeerCount`. It stays satisfiable without any
            /// consensus commit, so the bootstrap catch-22 the fallback exists to break is
            /// preserved.
            private boolean haveQuorum() {
                var view = snapshotSource.currentMembershipView();

                if (view.isPresent()) {
                    maybeTransitionToNormal(view.unwrap());
                    var healthyOnDuty = view.unwrap().healthyOnDutyCount();
                    var quorum = quorumSize();

                    log.debug("Quorum evaluation (membership view): healthyOnDuty={} threshold={}",
                              healthyOnDuty,
                              quorum);

                    return healthyOnDuty >= quorum;
                }

                var peers = connectedPeerCount();
                var quorum = quorumSize();

                log.debug("Quorum evaluation (legacy fallback): healthyActivePeers+1={} threshold={}", peers + 1, quorum);

                return (peers + 1) >= quorum;
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

                    return knownCorePeerCount(view.unwrap().coreMemberIds());
                }

                if (mode.get() == TopologyMode.BOOTING) {
                    return connectedPeerCount();
                }

                return 0;
            }

            /// Count peers that are both in the authoritative core-member set (from the snapshot)
            /// and KNOWN to this observer, excluding self. The NORMAL-mode quorum count that
            /// bridges the JOINING lag window while respecting minority-partition safety: a node
            /// absent from the snapshot's `coreMemberIds` is not counted, which is what preserves
            /// the minority-partition invariant.
            ///
            /// #558 — this docstring previously said "HEALTHY in the live SWIM `nodeStatesById`
            /// map". That was never true: `nodeStatesById` carried no SWIM-driven health, the
            /// `health == HEALTHY` filter it referred to was constant-true, and the name said
            /// `swimHealthyCorePeerCount`. Membership of `coreMemberIds` is doing all the work
            /// here; the observed-reachability guarantee for the NORMAL path lives upstream in
            /// `MembershipFsm.coreObservedMembers`, which is what feeds that snapshot (#557).
            private int knownCorePeerCount(Set<NodeId> coreMembers) {
                return (int) nodeStatesById.values()
                                           .stream()
                                           .filter(state -> !state.info()
                                                                  .id()
                                                                  .equals(config.self()))
                                           .filter(state -> coreMembers.contains(state.info().id()))
                                           .count();
            }

            /// Pre-snapshot fallback — the BOOT-TIME quorum count, sourced from OBSERVED
            /// TRANSPORT CONNECTIVITY rather than from discovery (#557).
            ///
            /// Counts peers that are simultaneously (a) in the discovery-derived dial set
            /// `nodeStatesById`, excluding self, (b) HEALTHY there, and (c) named by the most
            /// recent `NetworkServiceMessage.ConnectedNodesList` (`observedConnections`).
            ///
            /// (c) is the #557 fix. Without it this method degenerated to
            /// `nodeStatesById.size() - 1` — a pure DISCOVERY count: `addNode` inserts every
            /// freshly discovered peer as `NodeState.healthy(...)` BEFORE any connection exists,
            /// and the map has no health-mutating write path at all (only `putIfAbsent` and
            /// `remove`; `NodeState.suspected(...)` has no caller in the repo), so filter (b) was
            /// vacuous. Cold start therefore routed `ClusterStateNotification.ACTIVE` while zero
            /// QUIC lanes were up and `RabiaEngine.doClusterConnected` broadcast its one-shot
            /// `SyncRequest` into an empty network, leaving the cluster unformed.
            ///
            /// `observedConnections` carries `QuicClusterNetwork.connectedPeers()` — peers whose
            /// `PeerState.Phase` is `CONNECTED`, set only by `onPeerConnected` once the QUIC
            /// handshake, Hello exchange and identity verification have completed. It is NOT the
            /// sticky, non-liveness `isActive()` flag.
            ///
            /// **The documented catch-22 is preserved.** The connect path is consensus-free:
            /// `ConnectNode` → `QuicClusterNetwork.connect` → `topologyManager.get` (reads the
            /// in-memory dial set only, never the snapshot) → dial → `onPeerConnected`. No step
            /// reads Rabia state, a committed KV value, the `MembershipView` or `inQuorum()`.
            /// This count is therefore still satisfiable from transport connectivity alone with
            /// no consensus commit, which is what lets bootstrap escape the deadlock described
            /// on `haveQuorum`.
            ///
            /// Callers: `haveQuorum` and `healthyActivePeerCount` (the bootstrap quorum publish)
            /// ONLY. The public `healthyActiveNodeCount` / `readyNodeCount` reads go through
            /// `discoveredNodeCount` / `legacyActiveNodeCount` and are unaffected.
            private int connectedPeerCount() {
                // Read the observation once — a concurrent `reconcile` must not split the count.
                var connected = observedConnections.get();

                return (int) nodeStatesById.values()
                                           .stream()
                                           .filter(state -> !state.info()
                                                                  .id()
                                                                  .equals(config.self()))
                                           .filter(state -> connected.contains(state.info()
                                                                                    .id()))
                                           .count();
            }

            /// Updates the quorum denominator from a `SetClusterSize` message.
            ///
            /// **One quorum denominator (cluster-topology-overhaul Wave 9 item 1).**
            /// `ClusterConfigKey.coreCount` is the single SOURCE of truth for the cluster's
            /// core-count denominator; this `effectiveClusterSize` atomic is a DERIVED cell. In
            /// production the aether-side `ClusterConfigKey` subscription (`AetherNode.onClusterConfigPut`)
            /// drives THIS method with the committed `coreCount` on every config commit — the
            /// observer is consensus-side and cannot read aether KV directly, so this is the
            /// single bridge from KV → the atomic. §3.1: this remains exactly one of
            /// `evaluateQuorumState`'s pre-existing triggers; only the origin of the value
            /// changed (KV commit, not a separately-routed operator path). Boot ordering: the
            /// atomic is seeded from `TopologyConfig.clusterSize()` at construction and holds that
            /// (config-derived, equal) value until the first `ClusterConfigKey` commit supersedes it.
            @Contract
            @Override
            public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {
                int newSize = message.clusterSize();
                int currentSize = effectiveClusterSize.get();

                if (newSize < 3) {
                    log.info("rejected: cluster size change to {} below minimum 3 for Byzantine fault tolerance",
                             newSize);

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
                    reconcileFuture.getAndSet(Option.none()).onPresent(f -> f.cancel(false));
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
                                          snapshotSource,
                                          isDecommissioned,
                                          new AtomicBoolean(false),
                                          new AtomicBoolean(false),
                                          new Object(),
                                          new AtomicReference<>(Option.none()),
                                          new AtomicReference<>(Set.of()),
                                          new AtomicReference<>(TopologyMode.BOOTING),
                                          hlcSupplier));
    }
}
