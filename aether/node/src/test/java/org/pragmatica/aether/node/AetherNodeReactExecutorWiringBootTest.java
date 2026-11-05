// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.utility.react.ReactExecutor;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// D1-react: pins the `AetherNode.assembleNode` lines that install `reactors.executor("<Component>")` into each gated
/// C9 component. A component whose install line is missing keeps its default inline executor, so an engine stop
/// would not gate its side effects (§3.5 R5); the per-component `*EngineStopGateTest`s run on a hand-installed
/// executor and cannot see that. The node's executor registry creates an executor on first use, so a booted node
/// lists exactly the components whose install line ran. The stop test pins the node's own use of the gate (v-2653 F5):
/// `stop()` closes it first and closes the executors last; the gate's composition is pinned by [NodeReactorsGateTest].
@Timeout(value = 120, unit = SECONDS)
class AetherNodeReactExecutorWiringBootTest {
    static final Set<String> GATED_COMPONENTS = Set.of("LeaderManager",
                                                       "LoadBalancerManager",
                                                       "ControlLoop",
                                                       "RollbackManager",
                                                       "ScheduledTaskRegistry",
                                                       "WorkerActivation",
                                                       "ClusterTopologyManager",
                                                       "SpokesmanPingLoop",
                                                       "BootstrapModule");

    @TempDir
    Path tempDir;

    private AetherNode node;
    private final MessageRouter.DelegateRouter router = MessageRouter.DelegateRouter.delegate();

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void assembleNode_installsAnExecutorForEveryGatedComponent() {
        node = boot();

        var installed = node.reactExecutors()
                            .stream()
                            .map(ReactExecutor::component)
                            .collect(Collectors.toSet());

        assertThat(installed).as("every gated component has its react executor installed by assembleNode")
                             .containsAll(GATED_COMPONENTS);
    }

    /// v-2653 F3 / V1. `assembleNode` hands the node store's replay bit to the leader manager, so a `LeaderChange` raised
    /// in a replay burst carries `replayed` across the react hand-off. Pinned by VALUE: on the store's delivering thread
    /// the leader manager's installed source reads exactly what the store reads, true for the delivery of a replay burst
    /// and false for a live one (the bit is per delivering thread, so only a read there can tell). Red with the install
    /// line removed, or with a constant `() -> false` / `() -> true` installed.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void assembleNode_leaderManagerReadsTheStoreReplayBitOnTheDeliveringThread() {
        node = boot();
        var store = node.kvStore();
        var key = ConfigKey.forKey("d1react.v1");
        var seen = new CopyOnWriteArrayList<String>();
        var hook = MessageRouter.mutable();

        hook.addRoute(ValuePut.class, (ValuePut<?, ?> _) -> seen.add(store.isReplaying() + "/" + node.leaderReplaying()));
        router.replaceDelegate(hook);

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(key, ConfigValue.configValue("d1react.v1", "one")))),
                               10);
        var snapshot = store.makeSnapshot().unwrap();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(key, ConfigValue.configValue("d1react.v1", "two")))),
                               20);
        awaitDelivered(store);
        store.restoreCommittedSnapshot(snapshot, 30);
        store.replayNotifications();
        awaitDelivered(store);

        assertThat(seen).as("two live deliveries, then the replay burst of the install").containsExactly("false/false",
                                                                                                         "false/false",
                                                                                                         "true/true");
        assertThat(node.leaderReplaying()).as("off the delivering thread the bit reads false").isFalse();
    }

    private static void awaitDelivered(KVStore<AetherKey, AetherValue> store) {
        assertThat(store.awaitDelivered(store.enqueuedSeq()).await(timeSpan(10).seconds()).isSuccess()).as("delivered").isTrue();
    }

    /// v-2653 F5. `stop()` sets the node-stopping half of the gate FIRST (#642): a side-effect reaction queued before
    /// `stop()` and run right after it returns (the engine stop is the async tail of the stop chain) is dropped on every
    /// gated executor. After the stop completed, every executor is closed: a refused undo runs on the caller's thread.
    /// Red with `reactors.markStopping()` removed from `stop()` (the probes run) or `reactors.close()` removed (the undos
    /// run on the executor threads).
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void stop_closesTheGateFirst_andTheExecutorsLast() throws InterruptedException {
        node = boot();
        var executors = node.reactExecutors()
                            .stream()
                            .filter(executor -> GATED_COMPONENTS.contains(executor.component()))
                            .toList();
        var ran = new AtomicInteger();
        var release = new CountDownLatch(1);

        assertThat(executors).as("precondition: every gated executor is present").hasSize(GATED_COMPONENTS.size());
        for (var executor : executors) {
            hold(executor, release);
            executor.react("v2653-stop-probe", ran::incrementAndGet);
        }
        var droppedBefore = executors.stream().mapToLong(ReactExecutor::droppedEngineStopped).sum();
        var stopped = node.stop();
        release.countDown();

        // The executors may already be closed here (the store close is a dependent step of stop(), v-2648 F2), and a closed
        // executor runs a submitted drain undo on the caller at once, ahead of its queue: wait for the drops instead.
        assertThat(awaitDropped(executors, droppedBefore + executors.size())).as("each executor dropped its probe")
                                                                             .isGreaterThanOrEqualTo(droppedBefore + executors.size());
        assertThat(ran.get()).as("no side-effect reaction runs once stop() started").isZero();

        assertThat(stopped.await(timeSpan(60).seconds()).isSuccess()).as("the stop completed").isTrue();
        node = null;
        var caller = Thread.currentThread();

        for (var executor : executors) {
            assertThat(refusesOnTheCaller(executor, caller)).as("%s is closed after the stop: a refused undo runs on the caller",
                                                                 executor.component()).isTrue();
        }
    }

    /// The stop promise does not order `reactors.close()` (it is attached to the promise with `onResultRun`), so the
    /// closure is awaited, not assumed: each attempt submits an undo and records the thread it ran on; an open executor
    /// runs it on its own thread, a closed one refuses it and the caller runs it. The deadline only bounds a hang.
    private static boolean refusesOnTheCaller(ReactExecutor executor, Thread caller) throws InterruptedException {
        var deadline = System.nanoTime() + SECONDS.toNanos(30);

        while (System.nanoTime() < deadline) {
            var ranOn = new CompletableFuture<Thread>();

            executor.undo("v2653-after-stop", () -> ranOn.complete(Thread.currentThread()));
            try {
                if (ranOn.get(10, SECONDS) == caller) {
                    return true;
                }
            } catch (ExecutionException | TimeoutException e) {
                return false;
            }
            Thread.sleep(5);
        }
        return false;
    }

    /// v-2653 F5, the node-stopping half in isolation. The engine half of the gate is asked to stop only at the tail of
    /// the stop chain (`clusterNode.stop()`), so a reaction that runs in the first step of `stop()` after the flag is
    /// set is gated by `markStopping()` alone. The probe runs from inside `stop()`: the `ClusterStateNotification.passive()`
    /// routed right after the periodic tasks are cancelled releases the held executors and drains them.
    /// Red with `reactors.markStopping()` removed from `stop()`: the probes run (the engine is still alive).
    /// Also red with the executors closed at the start of `stop()` instead (an undo submitted from the probe is refused
    /// and runs on the probing thread): the gate closes first, the executors last.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void stop_gatesReactionsBeforeTheEngineIsAskedToStop() throws InterruptedException {
        node = boot();
        var executors = node.reactExecutors()
                            .stream()
                            .filter(executor -> GATED_COMPONENTS.contains(executor.component()))
                            .toList();
        var ran = new AtomicInteger();
        var dropped = new AtomicInteger();
        var release = new CountDownLatch(1);
        var reached = new CountDownLatch(1);
        var openRanOn = new CopyOnWriteArrayList<Thread>();
        var hookThread = new AtomicReference<Thread>();
        var hook = MessageRouter.mutable();

        for (var executor : executors) {
            hold(executor, release);
            executor.react("v2653-early-probe", ran::incrementAndGet);
        }
        var droppedBefore = executors.stream().mapToLong(ReactExecutor::droppedEngineStopped).sum();

        hook.addRoute(ClusterStateNotification.class, (ClusterStateNotification _) -> {
            release.countDown();
            executors.forEach(executor -> drainQuietly(executor));
            executors.forEach(executor -> executor.undo("v2653-open-probe", () -> openRanOn.add(Thread.currentThread())));
            executors.forEach(executor -> drainQuietly(executor));
            hookThread.set(Thread.currentThread());
            dropped.set((int) (executors.stream().mapToLong(ReactExecutor::droppedEngineStopped).sum() - droppedBefore));
            reached.countDown();
        });
        router.replaceDelegate(hook);
        var stopped = node.stop();

        assertThat(reached.await(30, SECONDS)).as("stop() reached its first routed step").isTrue();
        assertThat(ran.get()).as("no side-effect reaction runs once stop() started, engine still alive").isZero();
        assertThat(dropped.get()).as("each executor dropped its probe").isGreaterThanOrEqualTo(executors.size());
        assertThat(openRanOn).as("the executors are still open at the first step of stop(): an undo runs on their own threads")
                             .hasSize(executors.size())
                             .noneMatch(thread -> thread == hookThread.get());
        assertThat(stopped.await(timeSpan(60).seconds()).isSuccess()).as("the stop completed").isTrue();
        node = null;
    }

    /// The engine half of the gate in isolation (an01). The engine is asked to stop while the node is NOT stopping
    /// (`markStopping()` never ran): a reaction on an installed executor must then be dropped and counted. Control first:
    /// with the engine alive the same reaction runs, so the probe reaches the executor. Red with
    /// `reactors.bindEngine(clusterNode::isEngineStopRequested)` removed from `assembleNode`: the gate keeps its default
    /// "engine alive" and the reaction runs.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void engineStopRequested_dropsReactionsWhileTheNodeIsNotStopping() throws Exception {
        node = boot();
        var engine = engineOf(node);
        var executor = node.reactExecutors()
                           .stream()
                           .filter(candidate -> "LeaderManager".equals(candidate.component()))
                           .findFirst()
                           .orElseThrow();
        var ran = new AtomicInteger();

        assertThat(engine.isEngineStopRequested()).as("precondition: the engine was not asked to stop").isFalse();
        executor.react("an01-control", ran::incrementAndGet);
        drainQuietly(executor);
        assertThat(ran.get()).as("control: with the engine alive the reaction runs").isEqualTo(1);

        var droppedBefore = executor.droppedEngineStopped();

        assertThat(engine.stop().await(timeSpan(60).seconds()).isSuccess()).as("the engine stopped").isTrue();
        assertThat(engine.isEngineStopRequested()).isTrue();
        executor.react("an01-probe", ran::incrementAndGet);
        drainQuietly(executor);

        assertThat(ran.get()).as("the engine-stopped gate drops the reaction").isEqualTo(1);
        assertThat(executor.droppedEngineStopped()).isEqualTo(droppedBefore + 1);
    }

    /// The node's cluster node, read from the field its anonymous implementation captured (the test seam the node
    /// interface does not offer; the same reflection the other wiring tests use).
    private static RabiaNode<?> engineOf(AetherNode node) throws IllegalAccessException {
        for (var field : node.getClass().getDeclaredFields()) {
            if (RabiaNode.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return (RabiaNode<?>) field.get(node);
            }
        }
        throw new AssertionError("no captured RabiaNode field on " + node.getClass());
    }

    private static void drainQuietly(ReactExecutor executor) {
        var done = new CountDownLatch(1);

        executor.undo("v2653-drain-quiet", done::countDown);
        try {
            done.await(10, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AetherNode boot() {
        return AetherNode.aetherNode(minimalConfig(tempDir),
                                     router,
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
    }

    private static void hold(ReactExecutor executor, CountDownLatch release) throws InterruptedException {
        var started = new CountDownLatch(1);

        executor.undo("v2653-hold", () -> awaitQuietly(started, release));
        assertThat(started.await(10, SECONDS)).as("%s held", executor.component()).isTrue();
    }

    /// The executors' total dropped count once it reaches `atLeast`, or what it is after 30 s.
    private static long awaitDropped(List<ReactExecutor> executors, long atLeast) throws InterruptedException {
        var deadline = System.nanoTime() + SECONDS.toNanos(30);
        var dropped = executors.stream().mapToLong(ReactExecutor::droppedEngineStopped).sum();

        while (dropped < atLeast && System.nanoTime() < deadline) {
            Thread.sleep(10);
            dropped = executors.stream().mapToLong(ReactExecutor::droppedEngineStopped).sum();
        }

        return dropped;
    }

    private static void awaitQuietly(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await(10, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Present only so the node builds its `LoadBalancerManager` (an environment with a load-balancer provider is
    /// the only way the node assembles one); nothing is ever routed through it.
    private record IdleLoadBalancer() implements LoadBalancerProvider {
        @Override
        public Promise<Unit> onRouteChanged(RouteChange routeChange) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> onNodeRemoved(String nodeIp) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> reconcile(LoadBalancerState state) {
            return Promise.success(Unit.unit());
        }
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("react-executor-wiring-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.DEFAULT)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.some(EnvironmentIntegration.environmentIntegration(Option.none(),
                                                                                         Option.none(),
                                                                                         Option.some(new IdleLoadBalancer()))))
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }
}
