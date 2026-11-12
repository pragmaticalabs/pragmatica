// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.ObservedSliceFeed;
import org.pragmatica.aether.slice.kvstore.ObservedSliceListener;
import org.pragmatica.aether.slice.kvstore.SliceRowSource;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.aether.deployment.wiring.FsmTimers;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.aether.deployment.cluster.TerminationRetirement;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import static org.pragmatica.lang.Promise.promise;


/// **The only place an engine-dependent hook is bound** (phase-3 design 8.3, D2, W-15, W-32). Every deployment hook of the node that differs
/// between the legacy and the FSM2 engine goes through here, and each binding is recorded as `<branch point> -> <target>`; the record is compared
/// with the checked-in baseline of the engine (`wiring-legacy.txt`, `wiring-fsm2.txt`), so a hook bound to the wrong engine, or a branch point
/// nobody bound, is a red test and not a silent behaviour.
///
/// | Engine | A legacy hook (`legacy(...)`) | A FSM2 hook |
/// |---|---|---|
/// | LEGACY | the legacy target, unchanged (and unwrapped, so the LEGACY hot path pays nothing) | not bound |
/// | FSM2 | an explicit **counted drop** (`aether_deployment_fsm2_dropped_total{point}`): CDM and NDM are constructed and never reach an input | bound through [Fsm2System] and [Fsm2Routes] |
///
/// The activation hooks (B2 the quorum edge, B3 the level-heal, B7 the leader toggle) are additionally gated by the [EngineMarkerGuard], under
/// both engines: a node whose engine disagrees with the cluster's activates neither engine's FSMs during its window (design 8.2).
///
/// **N-2, discharged.** `ConfigChanged` (B5) reaches the node interpreter on a FSM2 core: the adapter executes every `LocalStep`, `NOTIFY_CONFIG`
/// included (its switch is exhaustive, so a step it could not run is a compile error), and the hook is bound without a condition, on a core
/// and on a worker (#2757).
///
/// **The worker arm (F3, P3-D; replaces N-3).** A FSM2 worker gets the node half of a [Fsm2System]: the node FSM's KV routes (B1.node, over the
/// replica feed, know `23ca0fd65f` D.2), the quorum edge (B2), the level-heal (B3), the passive-client switch as its entry edge (B4), the config
/// hook (B5), the self-ready signal (B6) and the stop drain (B13). Every cluster-side hook (B1.cluster, B7, B8, B9, B12, B14, B16) stays
/// unbound or a counted drop on a worker: a worker never leads.
public final class DeploymentWiring {
    private static final String LEGACY_TARGET = "legacy";
    private static final String DROPPED_TARGET = "dropped";
    /// The drop point of a config change applied before the FSM2 system is attached (#2757).
    static final String B5_PRE_ATTACH = "B5.ConfigApplied.preAttach";

    /// What the wiring reads from the node: its identity, the store's thread-scoped stamp accessors and the admission seam (the one reader
    /// of tokens and incarnations, delta 7).
    public record Node(NodeId self, LongSupplier revision, BooleanSupplier replaying, AdmissionSeam admissions) {}

    private final DeploymentEngine engine;
    private final boolean worker;
    private final MeterRegistry registry;
    private final EngineMarkerGuard guard;
    private final AdmissionSeam admissions;
    private final Fsm2Routes routes;
    private final Map<String, String> bindings = new ConcurrentSkipListMap<>();
    private volatile Option<Fsm2System> system = Option.none();

    private DeploymentWiring(DeploymentEngine engine,
                             boolean worker,
                             MeterRegistry registry,
                             EngineMarkerGuard guard,
                             Node node) {
        this.engine = engine;
        this.worker = worker;
        this.registry = registry;
        this.guard = guard;
        this.admissions = node.admissions();
        bindings.put("B13.Stop", engineTarget("fsm2:Fsm2System.deactivateCluster"));
        bindings.put("B13.StopDrain", nodeTarget("fsm2:Fsm2System.drainNode"));
        bindings.put("B16.Seams", engineTarget("fsm2:SeamPoller"));
        bindings.put("B20.LineageMint", "marker=" + engine);
        bindings.put("B21.SliceRows", nodeTarget("fsm2:SliceRowSource.observed"));
        this.routes = Fsm2Routes.fsm2Routes(routeTargets(node, this::deliverToNode, this::deliverToCluster));
    }

    /// The FSM2 routes' targets: the node's stamp accessors and, for the writer's boot token, its admission seam (delta 7).
    static Fsm2Routes.Targets routeTargets(Node node,
                                           Fsm2Routes.Sink<NodeFsmInput> toNode,
                                           Fsm2Routes.Sink<ClusterInput> toCluster) {
        return new Fsm2Routes.Targets(node.self(),
                                      node.revision(),
                                      node.replaying(),
                                      node.admissions()::tokenOf,
                                      toNode,
                                      toCluster);
    }

    /// The node's wiring: builds the engine marker guard over the node's own committed mirror (a refused node exits through `exit`), and records
    /// every binding. The guard's window is `engineMismatchWindowMs`.
    public static DeploymentWiring deploymentWiring(AetherNodeConfig config,
                                                    boolean worker,
                                                    KVStore<AetherKey, AetherValue> store,
                                                    AdmissionSeam admissions,
                                                    MeterRegistry registry,
                                                    OperatorWarningSink warnings,
                                                    Runnable exit) {
        var guard = EngineMarkerGuard.engineMarkerGuard(config.deploymentEngine(),
                                                        config.self(),
                                                        engineMismatchWindowMs(config),
                                                        EngineMarkerGuard.readerOver(store),
                                                        FsmTimers.sharedScheduler(),
                                                        System::currentTimeMillis,
                                                        warnings,
                                                        _ -> Thread.ofVirtual()
                                                                   .name("engine-mismatch-exit")
                                                                   .start(exit));
        // The stamp is read only by the Fsm2Routes handlers, inside the dispatch, where it is present. Empty (a read off the dispatching
        // thread, a wiring bug) maps to 0, which FsmRuntime drops and counts as dropped_revision (watermark >= 0): the fail-safe Fsm2Routes
        // documents. NodeFsmInterpreterTest (aRevisionZeroKvInput_againstAFreshWatermark_isDropped_notStepped) pins the drop; the binding itself is
        // pinned by DeploymentWiringProductionBindingTest; Fsm2RoutesTest pins only that the stamp reads 0 outside the dispatch.
        var wiring = deploymentWiring(config.deploymentEngine(),
                                      worker,
                                      registry,
                                      guard,
                                      new Node(config.self(),
                                               () -> store.notificationRevision()
                                                          .or(0L),
                                               store::isReplaying,
                                               admissions));

        return listeningForInstalls(wiring, store);
    }

    /// D8: on an FSM2 worker every replay item of the store, the replica feed's and the metadata channel's alike, tells the node FSM which snapshot it
    /// installs. A core registers nothing: its interpreter drops replay-flagged inputs.
    static DeploymentWiring listeningForInstalls(DeploymentWiring wiring, KVStore<AetherKey, AetherValue> store) {
        if (wiring.isFsm2() && wiring.worker) {
            store.onReplayStart(wiring::snapshotInstalled);
        }

        return wiring;
    }

    /// A replica snapshot was installed in the worker's store at `revision`; the node FSM resets its replay watermark to it (ruling D8).
    @Contract
    void snapshotInstalled(long revision) {
        system.onPresent(fsm2 -> fsm2.nodeInterpreter()
                                     .installedAt(revision));
    }

    /// The routes resolve the interpreters when an input arrives, not when the routes are built: the system is assembled after the route table
    /// is, and a notification can only arrive once the table is wired.
    public static DeploymentWiring deploymentWiring(DeploymentEngine engine,
                                                    boolean worker,
                                                    MeterRegistry registry,
                                                    EngineMarkerGuard guard,
                                                    Node node) {
        return new DeploymentWiring(engine, worker, registry, guard, node);
    }

    private boolean deliverToNode(NodeFsmInput input, long revision, boolean replay) {
        return system.map(fsm2 -> fsm2.nodeInterpreter()
                                      .deliverKv(input, revision, replay))
                     .or(false);
    }

    private boolean deliverToCluster(ClusterInput input, long revision, boolean replay) {
        return system.map(fsm2 -> fsm2.clusterInterpreter()
                                      .deliverKv(input, revision, replay))
                     .or(false);
    }

    /// The target of a hook whose FSM2 side exists only on a core: the FSM2 target there, a drop on a FSM2 worker (a worker never leads), the legacy one otherwise.
    private String engineTarget(String fsm2Target) {
        return isFsm2()
               ? fsm2TargetOrDrop(fsm2Target)
               : LEGACY_TARGET;
    }

    /// The target of a hook the node FSM serves, on a core and on a worker alike: the FSM2 target under FSM2, the legacy one otherwise.
    private String nodeTarget(String fsm2Target) {
        return isFsm2()
               ? fsm2Target
               : LEGACY_TARGET;
    }

    private String fsm2TargetOrDrop(String fsm2Target) {
        return worker
               ? DROPPED_TARGET
               : fsm2Target;
    }

    /// The registry the FSM2 metrics are published to (the sensor arms T reads).
    public MeterRegistry meters() {
        return registry;
    }

    public DeploymentEngine engine() {
        return engine;
    }

    public boolean isFsm2() {
        return engine == DeploymentEngine.FSM2;
    }

    public EngineMarkerGuard guard() {
        return guard;
    }

    /// The admission seam the routes, the seam poller and the FSM2 system read tokens and incarnations through (delta 7).
    public AdmissionSeam admissions() {
        return admissions;
    }

    /// B21 (deployment state machine spec 1.6.1): where a reader that scans the store reads the per-node slice rows. The `NodeArtifactKey`
    /// rows under LEGACY; under FSM2 the current `SliceObservedKey` rows, stale only when the seam's token for the node is known and differs
    /// from the row's (C8: an unknown token reads current).
    public SliceRowSource sliceRows() {
        return isFsm2()
               ? SliceRowSource.observed(admissions::tokenOf)
               : SliceRowSource.legacy();
    }

    // -- the FSM2 engine -------------------------------------------------------------------------------------------
    /// Attaches the assembled FSM2 system: a core's whole system, a worker's node half. Refused under LEGACY, and refused when the system's role
    /// is not the node's (a core's system handed to a worker, or the reverse).
    @Contract
    public void attach(Fsm2System assembled) {
        if (isFsm2() && assembled.worker() == worker) {
            system = Option.some(assembled);
            bindings.put("B15.construction", "fsm2:Fsm2System");
            bindings.put("B6.SelfReady", "fsm2:SelfReady effect");
        }
    }

    public Option<Fsm2System> system() {
        return system;
    }

    // -- legacy hooks ------------------------------------------------------------------------------------------------
    /// A legacy hook taking one event. Under LEGACY the target itself; under FSM2 a counted drop.
    public <T> Consumer<T> legacy(String point, Consumer<T> target) {
        return isFsm2()
               ? recorded(point, DROPPED_TARGET, dropped(point))
               : recorded(point, LEGACY_TARGET, target);
    }

    /// A legacy hook without an event.
    public Runnable legacy(String point, Runnable target) {
        return isFsm2()
               ? recorded(point, DROPPED_TARGET, () -> dropCounter(point).increment())
               : recorded(point, LEGACY_TARGET, target);
    }

    /// A legacy activation hook (B2, B3, B7): the legacy target, run only while the engine marker guard is open. The guard is inert under LEGACY
    /// unless a mismatching marker is committed.
    public <T> Consumer<T> legacyGated(String point, Consumer<T> target) {
        return isFsm2()
               ? recorded(point, DROPPED_TARGET, dropped(point))
               : recorded(point,
                          LEGACY_TARGET + "+gate",
                          event -> guard.whenOpen(point, () -> target.accept(event)));
    }

    /// A legacy activation hook without an event, gated like [#legacyGated(String, Consumer)].
    public Runnable legacyGated(String point, Runnable target) {
        return isFsm2()
               ? recorded(point, DROPPED_TARGET, () -> dropCounter(point).increment())
               : recorded(point, LEGACY_TARGET + "+gate", () -> guard.whenOpen(point, target));
    }

    private <T> Consumer<T> dropped(String point) {
        return _ -> dropCounter(point).increment();
    }

    private Counter dropCounter(String point) {
        return registry.counter("aether_deployment_fsm2_dropped_total", "point", point);
    }

    private <T> T recorded(String point, String target, T hook) {
        bindings.put(point, target);

        return hook;
    }

    // -- FSM2 hooks --------------------------------------------------------------------------------------------------
    /// B1, B8: the KV routes of both FSMs. Under LEGACY nothing is registered; for a worker the node FSM's routes only.
    @Contract
    public void registerKv(KVNotificationRouter.Builder<AetherKey, AetherValue> builder) {
        if (isFsm2() && worker) {
            registerNodeKv(builder);
        } else if (isFsm2()) {
            registerFsm2Kv(builder);
        }
    }

    private void registerFsm2Kv(KVNotificationRouter.Builder<AetherKey, AetherValue> builder) {
        routes.register(builder);
        Fsm2Routes.CLUSTER_PUT_FAMILIES.forEach(type -> bindings.put("B1.cluster." + type.getSimpleName() + ".put",
                                                                     "fsm2:Fsm2Routes"));
        Fsm2Routes.CLUSTER_REMOVE_FAMILIES.forEach(type -> bindings.put("B1.cluster." + type.getSimpleName() + ".remove",
                                                                        "fsm2:Fsm2Routes"));
        recordNodeRoutes();
    }

    private void registerNodeKv(KVNotificationRouter.Builder<AetherKey, AetherValue> builder) {
        routes.registerNode(builder);
        recordNodeRoutes();
    }

    private void recordNodeRoutes() {
        List.of("SliceDesiredKey", "SliceObservedKey", "SliceTargetKey", "VersionRoutingKey").forEach(family -> bindings.put("B1.node." + family,
                                                                                                                             "fsm2:Fsm2Routes"));
    }

    /// B21 (deployment state machine spec 1.6.1, R-1.6.6): the routes of the readers outside the two FSMs that follow the observed rows. Under
    /// FSM2, on a core and on a worker alike, the `SliceObservedKey` puts and removes feed the `state` listeners through a state feed and the
    /// `traffic` listeners through a traffic feed, which also follows the `NodeDepartureKey` records; both read tokens through the admission
    /// seam. Under LEGACY nothing is registered here: those readers keep their `NodeArtifactKey` routes, each bound through [#legacy].
    @Contract
    public void registerReaders(KVNotificationRouter.Builder<AetherKey, AetherValue> builder,
                                List<ObservedSliceListener> state,
                                List<ObservedSliceListener> traffic) {
        if (isFsm2()) {
            registerObservedFeeds(builder,
                                  ObservedSliceFeed.stateFeed(admissions::tokenOf, state),
                                  ObservedSliceFeed.trafficFeed(admissions::tokenOf, traffic),
                                  readerNames(state),
                                  readerNames(traffic));
        }
    }

    private void registerObservedFeeds(KVNotificationRouter.Builder<AetherKey, AetherValue> builder,
                                       ObservedSliceFeed stateFeed,
                                       ObservedSliceFeed trafficFeed,
                                       String stateReaders,
                                       String trafficReaders) {
        builder.onPut(SliceObservedKey.class, stateFeed::onObservedPut)
               .onRemove(SliceObservedKey.class, stateFeed::onObservedRemove)
               .onPut(SliceObservedKey.class, trafficFeed::onObservedPut)
               .onRemove(SliceObservedKey.class, trafficFeed::onObservedRemove)
               .onPut(NodeDepartureKey.class, trafficFeed::onDeparturePut)
               .onRemove(NodeDepartureKey.class, trafficFeed::onDepartureRemove);
        bindings.put("B21.readers.state.SliceObservedKey", "fsm2:ObservedSliceFeed.stateFeed" + stateReaders);
        bindings.put("B21.readers.traffic.SliceObservedKey", "fsm2:ObservedSliceFeed.trafficFeed" + trafficReaders);
        bindings.put("B21.readers.traffic.NodeDepartureKey", "fsm2:ObservedSliceFeed.trafficFeed" + trafficReaders);
    }

    /// The readers a feed hands rows to, by type, so the baseline pins WHICH readers `AetherNode` wired and a dropped one is a changed row.
    private static String readerNames(List<ObservedSliceListener> readers) {
        return readers.stream()
                      .map(DeploymentWiring::typeName)
                      .collect(Collectors.joining(",", "[", "]"));
    }

    /// The class name without its package; unlike `getSimpleName` it is not empty for an anonymous class.
    private static String typeName(Object reader) {
        var name = reader.getClass().getName();

        return name.substring(name.lastIndexOf('.') + 1);
    }

    /// B2, B7, B8, B9: the message routes the FSM2 engine consumes. Under LEGACY none; for a worker the quorum edge (B2) only.
    @Contract
    public void registerEntries(List<MessageRouter.Entry<?>> entries) {
        if (isFsm2() && worker) {
            registerQuorumEdge(entries);
        } else if (isFsm2()) {
            registerFsm2Entries(entries);
        }
    }

    private void registerQuorumEdge(List<MessageRouter.Entry<?>> entries) {
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                              notification -> system.onPresent(fsm2 -> fsm2.onClusterStateNotification(notification))));
        bindings.put("B2.ClusterStateNotification", "fsm2:Fsm2System.onClusterStateNotification");
    }

    private void registerFsm2Entries(List<MessageRouter.Entry<?>> entries) {
        registerQuorumEdge(entries);
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> system.onPresent(fsm2 -> fsm2.onLeaderChange(change))));
        entries.addAll(routes.leaderEntries());
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              decision -> system.onPresent(fsm2 -> fsm2.membership()
                                                                                       .onNodeJoined(decision))));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              decision -> system.onPresent(fsm2 -> fsm2.membership()
                                                                                       .onNodeRemoved(decision))));
        bindings.put("B7.LeaderChange", "fsm2:Fsm2System.onLeaderChange");
        bindings.put("B8.LeaderKey", "fsm2:Fsm2Routes.leaderEntries");
        bindings.put("B9.NodeJoined", "fsm2:MembershipInputs");
        bindings.put("B9.NodeRemoved", "fsm2:MembershipInputs");
    }

    /// B3: the 5 s activation level-heal. Under LEGACY the legacy driver; under FSM2 the interpreter's entry request.
    public Runnable activationDriver(BooleanSupplier consensusActive, Runnable legacyDriver) {
        return isFsm2() && system.isPresent()
               ? recorded("B3.ActivationDriver", "fsm2:Fsm2System.reconcileActivation", fsm2Driver(consensusActive))
               : legacyGated("B3.ActivationDriver", legacyDriver);
    }

    private Runnable fsm2Driver(BooleanSupplier consensusActive) {
        return () -> system.onPresent(fsm2 -> fsm2.reconcileActivation(consensusActive));
    }

    /// B6: the self-ready signal is driven by the NDM under LEGACY and by the `SelfReady` effect under FSM2.
    public Unit selfReady(String target) {
        bindings.put("B6.SelfReady", target);

        return Unit.unit();
    }

    /// B4: the passive-client switch. Under FSM2 on a worker, the worker's entry edge ([Fsm2System#onPassiveClientReady], resolved when it fires:
    /// the hook is built before the system is attached); on a FSM2 core a counted drop; under LEGACY the legacy reconcile, gated.
    public Runnable passiveClientSwitch(Runnable legacy) {
        return isFsm2() && worker
               ? recorded("B4.PassiveClientSwitch", "fsm2:Fsm2System.onPassiveClientReady", this::passiveClientReady)
               : legacyGated("B4.PassiveClientSwitch", legacy);
    }

    private void passiveClientReady() {
        system.onPresent(Fsm2System::onPassiveClientReady);
    }

    /// B5: a config applied. Under FSM2, on a core and on a worker (#2757), the node interpreter's `ConfigChanged` (NI-5).
    public Consumer<String> configApplied(Consumer<String> legacy) {
        return isFsm2()
               ? recorded("B5.ConfigApplied", "fsm2:NodeFsmInput.ConfigChanged", this::configChanged)
               : legacy("B5.ConfigApplied", legacy);
    }

    /// #2757: a change applied before [#attach] is dropped and counted (`aether_deployment_fsm2_dropped_total{point=B5.ConfigApplied.preAttach}`).
    /// Design-out, not loss: no FSM2 chain has run before attach, so no slice is registered for config, and REGISTER_CONFIG reads the current
    /// value at registration (`SliceRuntimeAdapter` `notifyInitial`). The count makes a reachable drop visible.
    private void configChanged(String key) {
        system.onPresent(fsm2 -> fsm2.nodeInterpreter()
                                     .deliver(new NodeFsmInput.ConfigChanged(key)))
              .onEmpty(() -> dropCounter(B5_PRE_ATTACH).increment());
    }

    /// B12: a node reported itself DRAINING. Under FSM2 on a core, the cluster interpreter's `NodeDraining` (CI-10); on a worker a counted drop (a worker runs no cluster FSM);
    /// under LEGACY the legacy eviction target, unchanged. The membership FSM's acknowledgement is not part of this hook and runs under both engines.
    public Consumer<NodeId> drainReport(Consumer<NodeId> legacy) {
        return isFsm2() && !worker
               ? recorded("B12.DrainReport", "fsm2:Fsm2System.onNodeDraining", this::nodeDraining)
               : legacy("B12.DrainReport", legacy);
    }

    private void nodeDraining(NodeId node) {
        system.onPresent(fsm2 -> fsm2.onNodeDraining(node));
    }

    /// B10: membership admitted a worker (the direct admission call and the `WorkerJoinDecision` route). Under FSM2 on a core, the cluster
    /// interpreter's `WorkerJoin` (CI-7) with the admission membership attaches; on a worker a counted drop (a worker runs no cluster FSM); under
    /// LEGACY the legacy target, unchanged.
    public Consumer<WorkerJoinDecision> workerJoin(String point, Consumer<WorkerJoinDecision> legacy) {
        return isFsm2() && !worker
               ? recorded(point, "fsm2:MembershipInputs.onWorkerJoin", this::workerJoined)
               : legacy(point, legacy);
    }

    private void workerJoined(WorkerJoinDecision decision) {
        system.onPresent(fsm2 -> fsm2.membership()
                                     .onWorkerJoin(decision));
    }

    /// B11: a worker's placement record was written. Under FSM2 on a core, translated to the cluster interpreter's `WorkerJoin` (design 8.3), as
    /// the legacy re-drive translates it to `onWorkerJoin`; on a worker a counted drop; under LEGACY the legacy target, unchanged.
    public Consumer<ValuePut<AetherKey.NodePlacementKey, AetherValue.NodePlacementValue>> placementRedrive(Consumer<ValuePut<AetherKey.NodePlacementKey, AetherValue.NodePlacementValue>> legacy) {
        return isFsm2() && !worker
               ? recorded("B11.PlacementRedrive", "fsm2:MembershipInputs.onWorkerJoin", this::placementPut)
               : legacy("B11.PlacementRedrive", legacy);
    }

    private void placementPut(ValuePut<AetherKey.NodePlacementKey, AetherValue.NodePlacementValue> put) {
        workerJoined(WorkerJoinDecision.workerJoinDecision(put.cause().key().nodeId(),
                                                           "worker",
                                                           HlcTimestamp.ZERO));
    }

    /// B9 (worker arm): a worker left. Under FSM2 on a core, the cluster interpreter's `WorkerLeave` (CI-9); on a worker a counted drop; under
    /// LEGACY the legacy target, unchanged.
    public Consumer<WorkerLeaveDecision> workerLeave(Consumer<WorkerLeaveDecision> legacy) {
        return isFsm2() && !worker
               ? recorded("B9.cdm.WorkerLeave", "fsm2:MembershipInputs.onWorkerLeave", this::workerLeft)
               : legacy("B9.cdm.WorkerLeave", legacy);
    }

    private void workerLeft(WorkerLeaveDecision decision) {
        system.onPresent(fsm2 -> fsm2.membership()
                                     .onWorkerLeave(decision));
    }

    /// B14: the committed snapshot was restored (`onStateRestored`). Under FSM2 on a core, the leader FSM's re-incarnation rule; on a worker a counted
    /// drop (the node FSM needs nothing, design 4.4); under LEGACY nothing is added to what the node registers itself (`refreshCommittedLeader` and the rest), recorded as legacy.
    public Runnable stateRestored() {
        return isFsm2() && !worker
               ? recorded("B14.StateRestored", "fsm2:Fsm2System.onStateRestored", this::restored)
               : legacy("B14.StateRestored", DeploymentWiring::nothing);
    }

    private static Unit nothing() {
        return Unit.unit();
    }

    private void restored() {
        system.onPresent(Fsm2System::onStateRestored);
    }

    /// C3 (#3028): the registrar through which the legacy node manager subscribes to the KV store's replay-end signal: bound under LEGACY, a recorded
    /// drop under FSM2.
    public Consumer<LongConsumer> replayEnd(KVStore<AetherKey, AetherValue> kvStore) {
        return target -> legacyBinding("C3.ndm.ReplayEnd", () -> kvStore.onReplayEnd(target));
    }

    /// A one-time legacy registration (B6, the callbacks the NDM is handed): run now under LEGACY, not run under FSM2. Recorded either way; not
    /// an event, so nothing is counted as dropped.
    @Contract
    public void legacyBinding(String point, Runnable registration) {
        if (isFsm2()) {
            bindings.put(point, DROPPED_TARGET);
        } else {
            bindings.put(point, LEGACY_TARGET);
            registration.run();
        }
    }

    /// B17 (Q8): whether the blueprint publish batch writes the apply record: on under FSM2, off under LEGACY.
    public boolean applyRecords() {
        bindings.put("B17.ApplyRecord",
                     isFsm2()
                     ? "on"
                     : "off");

        return isFsm2();
    }

    /// B18 (Q1b, 12 item 4b): what the CTM's confirmed reap retires. Under FSM2 the node's directive and departure record, in one transaction;
    /// under LEGACY nothing (the legacy cluster FSM deletes the directive at `NodeRemoved`, and a second delete would race it).
    public TerminationRetirement reaperRetirement(TerminationRetirement retirement) {
        return gatedRetirement("B18.CtmReaperDirectiveDelete", retirement);
    }

    /// B19 (C3, 12 item 4b): what the operator `--force` clear retires, gated like [#reaperRetirement].
    public TerminationRetirement forceClearRetirement(TerminationRetirement retirement) {
        return gatedRetirement("B19.ForceClearDirectiveDelete", retirement);
    }

    private TerminationRetirement gatedRetirement(String point, TerminationRetirement retirement) {
        return isFsm2()
               ? recorded(point, "on", retirement)
               : recorded(point, "off", TerminationRetirement.NONE);
    }

    /// B20: the engine marker guard's own triggers: the three keys it reads, and the leader edge. Bound under both engines.
    public List<MessageRouter.Entry<?>> guardEntries() {
        bindings.put("B20.EngineMarkerGuard", "guard");

        return List.of(MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, this::guardPut),
                       MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class, this::guardRemove),
                       MessageRouter.Entry.route(LeaderNotification.LeaderChange.class, _ -> guard.evaluate()));
    }

    @SuppressWarnings("rawtypes")
    private void guardPut(KVStoreNotification.ValuePut put) {
        if (readByGuard(put.cause().key())) {
            guard.evaluate();
        }
    }

    @SuppressWarnings("rawtypes")
    private void guardRemove(KVStoreNotification.ValueRemove remove) {
        if (readByGuard(remove.cause().key())) {
            guard.evaluate();
        }
    }

    private static boolean readByGuard(Object key) {
        return key instanceof AetherKey.DeploymentEngineKey || key instanceof AetherKey.ClusterIncarnationKey || key instanceof LeaderKey;
    }

    /// `B_engine` = 2 x the leader-election timeout `[guess, ruled to stay a guess; settled by E-14]`: the longest a node waits by rank before it
    /// proposes itself when the others fail (base + one rank step per other core + the retry delay), doubled.
    public static long engineMismatchWindowMs(AetherNodeConfig config) {
        var election = config.timeouts().election();
        var ranks = Math.max(0,
                             config.topology().coreNodes().size() - 1);

        return 2 * (election.baseDelay()
                            .millis() + ranks * election.perRankDelay()
                                                        .millis() + election.retryDelay()
                                                                            .millis());
    }

    // -- lifecycle -----------------------------------------------------------------------------------------------------
    /// Starts the FSM2 consumers and the seam cadence, and reads the marker once (a boot read). Under LEGACY only the boot read.
    @Contract
    public void start() {
        guard.evaluate();
        system.onPresent(Fsm2System::start);
    }

    /// The node is stopping: the FSM2 interpreters close (`Close` only, design 7). Idempotent.
    @Contract
    public void stop() {
        system.onPresent(Fsm2System::stop);
    }

    /// B13, the first half of the node's stop (design 7, 2a and 2b): the PASSIVE edge is routed, the FSM2 node interpreter drains (the `SUSPEND`s of the
    /// quorum loss run, within a bound), and only then the router is quiesced (design 7's order, W-16 (a): the quiesce never precedes the drain).
    /// Under LEGACY the drain is a no-op and the order is the one the node always had.
    ///
    /// #2688: `delivered` is taken right after PASSIVE is routed and resolves once the reactions that edge queued (the `LeaderChange` the FSM routes onto
    /// a react executor) have run; the quiesce, which would drop them, waits for it. The returned promise resolves after the quiesce.
    ///
    /// Threads, per step (#2806, #3002):
    /// - The quiesce is a pooled task started once `delivered` has resolved, so it runs on a pooled virtual thread in every ordering (`delivered`
    ///   resolved before this call or after it), never on the thread that resolved `delivered` and never on the caller.
    /// - The returned promise is resolved ONLY by a task on the async executor ([Promise#continueOn()]), after the quiesce. A continuation the caller
    ///   chains on it therefore runs on that executor's thread, or on the caller's own thread if the hop has already finished when it is attached,
    ///   and never on the thread that resolved `delivered`: that is the react executor whose reactions it waited for, or the shared timeout
    ///   scheduler when the caller bounded the wait, and the node's teardown chained here blocks (the final cursor commits are awaited, bounded).
    ///   Without the hop, the `flatMap` into the pooled quiesce resolved the returned promise inline on the thread that resolved `delivered`
    ///   whenever the quiesce finished before `flatMap` looked at it (#3002).
    public Promise<Unit> leaveQuorum(Runnable routePassive, Supplier<Promise<Unit>> delivered, Runnable quiesce) {
        return leftQuorum(routePassive, delivered, quiesce).continueOn();
    }

    /// Test seam (#3002): `hop` runs the task that resolves the returned promise ([Promise#continueOn(Executor)]); production uses the async
    /// executor. A test executor that finishes the task before `execute` returns forces the "already resolved when attached" ordering.
    Promise<Unit> leaveQuorum(Runnable routePassive,
                              Supplier<Promise<Unit>> delivered,
                              Runnable quiesce,
                              Executor hop) {
        return leftQuorum(routePassive, delivered, quiesce).continueOn(hop);
    }

    private Promise<Unit> leftQuorum(Runnable routePassive, Supplier<Promise<Unit>> delivered, Runnable quiesce) {
        routePassive.run();
        var reactionsRun = delivered.get();

        system.onPresent(Fsm2System::drainNode);

        return reactionsRun.flatMap(unit -> quiescedOnPool(quiesce));
    }

    private static Promise<Unit> quiescedOnPool(Runnable quiesce) {
        return promise(() -> quiesced(quiesce));
    }

    private static Result<Unit> quiesced(Runnable quiesce) {
        quiesce.run();

        return Result.unitResult();
    }

    /// The first step of the stop sequence (B13): under LEGACY the legacy deactivation; under FSM2 the cluster interpreter deactivates and closes.
    public Promise<Unit> deactivateCluster(Supplier<Promise<Unit>> legacy) {
        return isFsm2() && system.isPresent()
               ? fsm2Deactivation()
               : legacy.get();
    }

    private Promise<Unit> fsm2Deactivation() {
        system.onPresent(Fsm2System::deactivateCluster);

        return Promise.unitPromise();
    }

    // -- the record --------------------------------------------------------------------------------------------------
    /// The recorded bindings, sorted: one `<branch point> -> <target>` line each.
    public List<String> recorded() {
        return bindings.entrySet()
                       .stream()
                       .map(DeploymentWiring::row)
                       .toList();
    }

    private static String row(Map.Entry<String, String> binding) {
        return binding.getKey() + " -> " + binding.getValue();
    }
}
