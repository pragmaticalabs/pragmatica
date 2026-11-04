// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
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

    /// v-2653 F3. `assembleNode` hands the node store's replay bit to the leader manager, so a `LeaderChange` raised in a
    /// replay burst carries `replayed` across the react hand-off. The source itself reads false off the delivery thread,
    /// so the install is pinned by presence. Red with the `installReplaySource(kvStore::isReplaying)` line removed.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void assembleNode_installsTheStoreReplaySourceIntoTheLeaderManager() {
        node = boot();

        assertThat(node.leaderReplaySourceInstalled()).as("the leader manager reads the node store's replay bit").isTrue();
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
        for (var executor : executors) {
            drain(executor);
        }

        assertThat(ran.get()).as("no side-effect reaction runs once stop() started").isZero();
        assertThat(executors.stream().mapToLong(ReactExecutor::droppedEngineStopped).sum() - droppedBefore)
            .as("each executor dropped its probe").isGreaterThanOrEqualTo(executors.size());

        assertThat(stopped.await(timeSpan(60).seconds()).isSuccess()).as("the stop completed").isTrue();
        node = null;
        var caller = Thread.currentThread();
        var undoThreads = new CopyOnWriteArrayList<Thread>();

        executors.forEach(executor -> executor.undo("v2653-after-stop", () -> undoThreads.add(Thread.currentThread())));
        assertThat(undoThreads).as("every executor is closed after the stop: a refused undo runs on the caller")
                               .hasSize(executors.size())
                               .allMatch(thread -> thread == caller);
    }

    /// v-2653 F5, the node-stopping half in isolation. The engine half of the gate is asked to stop only at the tail of
    /// the stop chain (`clusterNode.stop()`), so a reaction that runs in the first step of `stop()` after the flag is
    /// set is gated by `markStopping()` alone. The probe runs from inside `stop()`: the `ClusterStateNotification.passive()`
    /// routed right after the periodic tasks are cancelled releases the held executors and drains them.
    /// Red with `reactors.markStopping()` removed from `stop()`: the probes run (the engine is still alive).
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
        var hook = MessageRouter.mutable();

        for (var executor : executors) {
            hold(executor, release);
            executor.react("v2653-early-probe", ran::incrementAndGet);
        }
        var droppedBefore = executors.stream().mapToLong(ReactExecutor::droppedEngineStopped).sum();

        hook.addRoute(ClusterStateNotification.class, (ClusterStateNotification _) -> {
            release.countDown();
            executors.forEach(executor -> drainQuietly(executor));
            dropped.set((int) (executors.stream().mapToLong(ReactExecutor::droppedEngineStopped).sum() - droppedBefore));
            reached.countDown();
        });
        router.replaceDelegate(hook);
        var stopped = node.stop();

        assertThat(reached.await(30, SECONDS)).as("stop() reached its first routed step").isTrue();
        assertThat(ran.get()).as("no side-effect reaction runs once stop() started, engine still alive").isZero();
        assertThat(dropped.get()).as("each executor dropped its probe").isGreaterThanOrEqualTo(executors.size());
        assertThat(stopped.await(timeSpan(60).seconds()).isSuccess()).as("the stop completed").isTrue();
        node = null;
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

    private static void drain(ReactExecutor executor) throws InterruptedException {
        var done = new CountDownLatch(1);

        executor.undo("v2653-drain", done::countDown);
        assertThat(done.await(10, SECONDS)).as("%s drained", executor.component()).isTrue();
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
