// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.aether.deployment.wiring.FsmTimers;
import org.pragmatica.aether.deployment.wiring.NodeFsmInterpreter;
import org.pragmatica.aether.deployment.wiring.SliceRuntimeAdapter;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.awaitility.Awaitility.await;

/// The production assembly of the deployment wiring over a real `KVStore` applier, minus the slice runtime: the same `DeploymentWiring`,
/// `Fsm2Routes`, `Fsm2System`, route table and boot-token registry that `AetherNode` builds, with a real notification router and a store that
/// commits batches at increasing slots (so notifications carry revisions). Slice execution needs a slice store and is covered where the tests
/// provide one; the routes, gates and drivers need none.
final class Fsm2Harness implements AutoCloseable {
    static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    static final NodeId PEER = NodeId.nodeId("core-2").unwrap();
    static final LeaderValue LEADER = new LeaderValue(SELF, 3L);
    static final long SELF_TOKEN = 0xABCDL;

    final MessageRouter.MutableRouter router = MessageRouter.mutable();
    final KVStore<AetherKey, AetherValue> store = new KVStore<>(router, new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override
        public <T> T read(ByteBuf buffer) {
            return null;
        }
    });
    final AtomicLong slot = new AtomicLong();
    final BootTokens bootTokens = BootTokens.bootTokens(SELF_TOKEN);
    final ClusterNodeStub cluster = new ClusterNodeStub(SELF);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final List<Message> events = Collections.synchronizedList(new ArrayList<>());
    final List<OperatorWarning> warnings = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger selfReady = new AtomicInteger();
    /// Run on the node consumer after each `SelfReady` effect: a test that needs the consumer held (a backlog, a stalled stop) blocks in it.
    volatile Runnable selfReadyHook = () -> {};
    /// Run wherever the seams are sampled, which includes the cluster consumer at `Rebuild`: a test that needs that consumer held blocks in it.
    volatile Runnable countedCoresHook = () -> {};
    /// The bound a stop gives each interpreter (design 7, 2b); the design's 10 s unless a test shortens it, before `assembled()`.
    private long stopWaitMs = NodeFsmInterpreter.STOP_WAIT_MS;
    private boolean defaultStopWait;
    final List<String> exits = Collections.synchronizedList(new ArrayList<>());
    final EngineMarkerGuard guard;
    final DeploymentWiring wiring;
    final List<MessageRouter.Entry<?>> entries = new ArrayList<>();
    Option<Fsm2System> system = Option.none();
    private final DeploymentEngine engine;
    private final boolean worker;

    /// How many times the seam poller has sampled the cluster's counted cores (one per sample).
    final AtomicInteger samples = new AtomicInteger();
    /// The engine the committed marker names; the configured engine unless a test commits a different one.
    volatile DeploymentEngine markerEngine;
    /// An incarnation is committed while the marker is not (a pre-phase-3 cluster), when the marker is otherwise withheld.
    volatile boolean incarnationOnly;
    /// The timer service the interpreters and the seam cadence use; the shared scheduler unless a test substitutes a manual one.
    private FsmTimers.TimerService clock = FsmTimers.sharedScheduler();
    /// Whether the cluster's engine marker is committed; false holds the guard closed (WAITING) until a test commits it.
    final AtomicBoolean markerCommitted = new AtomicBoolean(true);

    Fsm2Harness(DeploymentEngine engine, boolean worker) {
        this(engine, worker, true);
    }

    Fsm2Harness(DeploymentEngine engine, boolean worker, boolean markerCommitted) {
        this(engine, worker, markerCommitted, false);
    }

    /// The wiring built the way `AetherNode` builds it: through the production factory, which binds the store's own stamp accessors and builds the
    /// guard over the store's committed mirror. The guard then opens only when the engine marker and the incarnation are really committed.
    static Fsm2Harness production(DeploymentEngine engine) {
        return new Fsm2Harness(engine, false, true, true);
    }

    private Fsm2Harness(DeploymentEngine engine, boolean worker, boolean markerCommitted, boolean production) {
        this.markerCommitted.set(markerCommitted);
        this.markerEngine = engine;
        this.engine = engine;
        this.worker = worker;
        cluster.script = commands -> Promise.success(commitAll(commands));
        if (production) {
            this.wiring = DeploymentWiring.deploymentWiring(configFor(engine),
                                                            worker,
                                                            store,
                                                            AdmissionSeam.admissionSeam(bootTokens::tokenOf, _ -> 0L),
                                                            registry,
                                                            OperatorWarningSink.handingOffTo(warnings::add),
                                                            () -> exits.add("exit"));
            this.guard = wiring.guard();
        } else {
            this.guard = openGuard(engine);
            this.wiring = DeploymentWiring.deploymentWiring(engine,
                                                            worker,
                                                            registry,
                                                            guard,
                                                            new DeploymentWiring.Node(SELF, store::notificationRevision, store::isReplaying, AdmissionSeam.admissionSeam(bootTokens::tokenOf, _ -> 0L)));
        }
    }

    /// A guard whose marker matches the engine, so it is open (the gate's own rows are `EngineMarkerGuardTest`'s).
    private EngineMarkerGuard openGuard(DeploymentEngine configured) {
        var guard = EngineMarkerGuard.engineMarkerGuard(configured, SELF, 1_000L, new EngineMarkerGuard.Reader() {
            @Override
            public Option<DeploymentEngineValue> marker() {
                return markerCommitted.get()
                       ? Option.some(new DeploymentEngineValue(markerEngine, SELF, 1L))
                       : Option.none();
            }

            @Override
            public boolean incarnationPresent() {
                return markerCommitted.get() || incarnationOnly;
            }

            @Override
            public Option<LeaderValue> leader() {
                return Option.some(LEADER);
            }
        }, FsmTimers.sharedScheduler(), () -> 1L, OperatorWarningSink.handingOffTo(warnings::add), exits::add);

        guard.evaluate();

        return guard;
    }

    /// Shortens the stop bound; before `assembled()`.
    Fsm2Harness withStopWait(long ms) {
        stopWaitMs = ms;

        return this;
    }

    /// Builds the system through the one-argument factory, the one production calls, so its default bound is the one under test.
    Fsm2Harness withDefaultStopWait() {
        defaultStopWait = true;

        return this;
    }

    private void selfReadied() {
        selfReady.incrementAndGet();
        selfReadyHook.run();
    }

    /// Substitutes the timer service; before `assembled()`.
    Fsm2Harness withClock(FsmTimers.TimerService timers) {
        clock = timers;

        return this;
    }

    private Set<NodeId> countedCores() {
        samples.incrementAndGet();
        countedCoresHook.run();

        return Set.of(SELF, PEER);
    }

    /// Builds the route table and the system the way `assembleNode` does, then starts the consumers.
    Fsm2Harness assembled() {
        var builder = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class);

        wiring.registerKv(builder);
        entries.addAll(builder.build().asRouteEntries());
        wiring.registerEntries(entries);
        entries.addAll(wiring.guardEntries());
        entries.forEach(this::register);
        if (wiring.isFsm2() && !worker) {
            wiring.attach(defaultStopWait
                          ? Fsm2System.fsm2System(collaborators())
                          : Fsm2System.fsm2System(collaborators(), stopWaitMs));
        }
        wiring.start();
        system = wiring.system();

        return this;
    }

    private static AetherNodeConfig configFor(DeploymentEngine engine) {
        return AetherNodeConfig.builder()
                               .self(SELF)
                               .coreNodes(List.of())
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(3)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(Map.of())
                               .build()
                               .withDeploymentEngine(engine);
    }

    private Fsm2System.Collaborators collaborators() {
        var config = configFor(engine);

        return new Fsm2System.Collaborators(config,
                                            new ProcessIncarnation(1L, SELF_TOKEN),
                                            SwimConfig.DEFAULT,
                                            TimeSpan.timeSpan(15).seconds(),
                                            store,
                                            () -> cluster,
                                            new SliceRuntimeAdapter.Collaborators(SELF, failingStore(), null, null, Option.none(), Option.none(), null, store, () -> cluster, System::currentTimeMillis, () -> OperatorWarningSink.logOnly()),
                                            Set::of,
                                            this::selfReadied,
                                            new EffectTranslator.Targets(SELF, events::add, () -> OperatorWarningSink.handingOffTo(warnings::add), registry, _ -> {}),
                                            new SeamPoller.Sources(this::countedCores,
                                                                   () -> Set.of(SELF, PEER),
                                                                   Set::of,
                                                                   () -> Option.some(Set.of(SELF, PEER)),
                                                                   () -> Option.some(Set.of(SELF, PEER)),
                                                                   Set::of,
                                                                   Map::of,
                                                                   Map::of,
                                                                   () -> Set.of(SELF, PEER),
                                                                   bootTokens::tokenOf),
                                            AdmissionSeam.admissionSeam(bootTokens::tokenOf, _ -> 0L),
                                            guard,
                                            clock);
    }

    /// A slice store whose loads fail: enough for a local op to run and be counted as failed.
    private static SliceStore failingStore() {
        return (SliceStore) Proxy.newProxyInstance(Fsm2Harness.class.getClassLoader(),
                                                                                               new Class<?>[]{SliceStore.class},
                                                                                               (_, method, _) -> method.getName().equals("loadSlice")
                                                                                                                 ? Causes.cause("no slice store in this rig").promise()
                                                                                                                 : Option.none());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(MessageRouter.Entry<?> entry) {
        entry.entries().forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last()));
    }

    // -- committing ----------------------------------------------------------------------------------------------------------
    @SuppressWarnings({"unchecked", "rawtypes"})
    synchronized List<Object> commitAll(List<? extends KVCommand> commands) {
        var raw = (KVStore) store;

        return (List<Object>) raw.processCommitted(raw.createBatch((List) List.copyOf(commands)), slot.incrementAndGet());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void put(AetherKey key, AetherValue value) {
        commitAll(List.of(new KVCommand.Put(key, value)));
    }

    /// A leader-authorized write: one `LeaderTransaction` under the committed leader, as the cluster FSM writes.
    @SuppressWarnings({"unchecked", "rawtypes"})
    void leaderPut(AetherKey key, AetherValue value) {
        var raw = (KVStore) store;
        LeaderValue leader = (LeaderValue) raw.getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();

        commitAll(List.of(new KVCommand.LeaderTransaction(key, "tx-" + slot.get(), leader, List.of(), List.of(new KVCommand.Mutation(key, raw.get(key), Option.some(value))))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void remove(AetherKey key) {
        commitAll(List.of(new KVCommand.Remove(key)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void commitLeader() {
        commitAll(List.of(new KVCommand.Put(LeaderKey.INSTANCE, LEADER)));
    }

    // -- observing ------------------------------------------------------------------------------------------------------------
    double count(String input, String result, String fsm) {
        return registry.find("aether_deployment_fsm_inputs_total").tags("fsm", fsm, "input", input, "result", result).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    void awaitThat(BooleanSupplier condition) {
        await().atMost(Duration.ofSeconds(10)).until(condition::getAsBoolean);
    }

    @Override
    public void close() {
        wiring.stop();
    }
}
