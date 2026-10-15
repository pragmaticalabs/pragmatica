// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.wiring.SliceRuntimeAdapter;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.aether.deployment.wiring.FsmTimers;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;

import io.micrometer.core.instrument.MeterRegistry;


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
/// **N-2.** `ConfigChanged` (B5) is bound to the node interpreter only when the adapter executes the step it triggers (`NOTIFY_CONFIG`); in this
/// tracer it does not, so the hook is a counted drop under FSM2.
///
/// **N-3.** A worker never gets a [Fsm2System]: [#attach] refuses one, and the worker's FSM2-side bindings are counted drops (workers are unwired
/// in phase 3).
public final class DeploymentWiring {
    private static final String LEGACY_TARGET = "legacy";
    private static final String DROPPED_TARGET = "dropped";

    /// What the wiring reads from the node: its identity, the store's thread-scoped stamp accessors and the boot-token registry.
    public record Node(NodeId self, LongSupplier revision, BooleanSupplier replaying, ToLongFunction<NodeId> tokenOf) {}

    private final DeploymentEngine engine;
    private final boolean worker;
    private final MeterRegistry registry;
    private final EngineMarkerGuard guard;
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
        bindings.put("B13.Stop", engineTarget("fsm2:Fsm2System.deactivateCluster"));
        bindings.put("B16.Seams", engineTarget("fsm2:SeamPoller"));
        bindings.put("B20.LineageMint", "marker=" + engine);
        this.routes = Fsm2Routes.fsm2Routes(new Fsm2Routes.Targets(node.self(),
                                                                   node.revision(),
                                                                   node.replaying(),
                                                                   node.tokenOf(),
                                                                   this::deliverToNode,
                                                                   this::deliverToCluster));
    }

    /// The node's wiring: builds the engine marker guard over the node's own committed mirror (a refused node exits through `exit`), and records
    /// every binding. The guard's window is `engineMismatchWindowMs`.
    public static DeploymentWiring deploymentWiring(AetherNodeConfig config,
                                                    boolean worker,
                                                    KVStore<AetherKey, AetherValue> store,
                                                    BootTokens bootTokens,
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

        return deploymentWiring(config.deploymentEngine(),
                                worker,
                                registry,
                                guard,
                                new Node(config.self(),
                                         store::notificationRevision,
                                         store::isReplaying,
                                         bootTokens::tokenOf));
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

    /// The target of a hook whose FSM2 side exists only on a core: the FSM2 target there, a drop on a FSM2 worker (N-3), the legacy one otherwise.
    private String engineTarget(String fsm2Target) {
        return isFsm2()
               ? (worker
                  ? DROPPED_TARGET
                  : fsm2Target)
               : LEGACY_TARGET;
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

    // -- the FSM2 engine -------------------------------------------------------------------------------------------
    /// Attaches the assembled FSM2 system. Refused for a worker (N-3) and under LEGACY.
    @Contract
    public void attach(Fsm2System assembled) {
        if (isFsm2() && !worker) {
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

    private io.micrometer.core.instrument.Counter dropCounter(String point) {
        return registry.counter("aether_deployment_fsm2_dropped_total", "point", point);
    }

    private <T> T recorded(String point, String target, T hook) {
        bindings.put(point, target);

        return hook;
    }

    // -- FSM2 hooks --------------------------------------------------------------------------------------------------
    /// B1, B8: the KV routes of both FSMs. Under LEGACY nothing is registered; for a worker nothing either (N-3).
    @Contract
    public void registerKv(KVNotificationRouter.Builder<AetherKey, AetherValue> builder) {
        if (isFsm2() && !worker) {
            registerFsm2Kv(builder);
        }
    }

    private void registerFsm2Kv(KVNotificationRouter.Builder<AetherKey, AetherValue> builder) {
        routes.register(builder);
        Fsm2Routes.CLUSTER_PUT_FAMILIES.forEach(type -> bindings.put("B1.cluster." + type.getSimpleName() + ".put",
                                                                     "fsm2:Fsm2Routes"));
        Fsm2Routes.CLUSTER_REMOVE_FAMILIES.forEach(type -> bindings.put("B1.cluster." + type.getSimpleName() + ".remove",
                                                                        "fsm2:Fsm2Routes"));
        List.of("SliceDesiredKey", "SliceObservedKey", "SliceTargetKey", "VersionRoutingKey").forEach(family -> bindings.put("B1.node." + family,
                                                                                                                             "fsm2:Fsm2Routes"));
    }

    /// B2, B7, B8, B9: the message routes the FSM2 engine consumes. Under LEGACY none.
    @Contract
    public void registerEntries(List<MessageRouter.Entry<?>> entries) {
        if (isFsm2() && !worker) {
            registerFsm2Entries(entries);
        }
    }

    private void registerFsm2Entries(List<MessageRouter.Entry<?>> entries) {
        entries.add(MessageRouter.Entry.route(ClusterStateNotification.class,
                                              notification -> system.onPresent(fsm2 -> fsm2.onClusterStateNotification(notification))));
        entries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                              change -> system.onPresent(fsm2 -> fsm2.onLeaderChange(change))));
        entries.addAll(routes.leaderEntries());
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeJoined.class,
                                              decision -> system.onPresent(fsm2 -> fsm2.membership()
                                                                                       .onNodeJoined(decision))));
        entries.add(MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,
                                              decision -> system.onPresent(fsm2 -> fsm2.membership()
                                                                                       .onNodeRemoved(decision))));
        bindings.put("B2.ClusterStateNotification", "fsm2:Fsm2System.onClusterStateNotification");
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

    /// B5: a config applied. Bound to the node interpreter only once the adapter executes `NOTIFY_CONFIG` (N-2); until then a counted drop.
    public Consumer<String> configApplied(Consumer<String> legacy) {
        return isFsm2() && !worker && SliceRuntimeAdapter.wired(LocalStep.NOTIFY_CONFIG)
               ? recorded("B5.ConfigApplied", "fsm2:NodeFsmInput.ConfigChanged", this::configChanged)
               : legacy("B5.ConfigApplied", legacy);
    }

    private void configChanged(String key) {
        system.onPresent(fsm2 -> fsm2.nodeInterpreter()
                                     .deliver(new NodeFsmInput.ConfigChanged(key)));
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
                       .map(entry -> entry.getKey() + " -> " + entry.getValue())
                       .toList();
    }
}
