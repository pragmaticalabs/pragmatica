// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.loadbalancer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManagerTest.RecordingLoadBalancerProvider;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManagerTest.RecordingTopologyManager;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Exit (ab), cloud-call kind (KVStore dispatcher design §5, §3.5.1 rows 53/54; D1-react): a NodeRoutes put absorbed
/// before an engine-only stop, whose reaction runs after it, never reaches the cloud load balancer. Replaces the C9
/// tripwire LoadBalancerEngineStopTripwireTest, which pinned the inline call this gate removes.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LoadBalancerEngineStopGateTest {
    private static final String COMPONENT = "LoadBalancerManager";

    /// Exit (ab) log line (design R5: logged once per component per stop): the drop warns once; closed in tearDown.
    private final LogCapture dropLog = LogCapture.capture(ReactExecutor.class, Level.WARN, false);
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    /// The react executor under test, its engine flag (the gate's engine half) and the hold seam (§5 (ab): "hold the
    /// component's react executor, a latch on its next task"). The hold is an UNDO task, so the gate never drops it.
    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor(COMPONENT,
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());

    private CountDownLatch hold() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        executor.undo("test-hold", () -> awaitQuietly(started, release));
        assertThat(started.await(10, TimeUnit.SECONDS)).as("hold task started").isTrue();

        return release;
    }

    private void drain() throws InterruptedException {
        var done = new CountDownLatch(1);

        executor.undo("test-drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).as("executor drained").isTrue();
    }

    private static void awaitQuietly(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final NodeId node = NodeId.randomNodeId();
    private final RecordingLoadBalancerProvider provider = new RecordingLoadBalancerProvider();
    private LoadBalancerManager manager;

    @BeforeEach
    void setUp() throws InterruptedException {
        var topology = new RecordingTopologyManager();
        topology.register(node, "10.0.0.1", 8080);
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        manager = LoadBalancerManager.loadBalancerManager(NodeId.randomNodeId(),
                                                          new KVStore<>(router, LoadBalancerManagerTest.noopSerializer(), null),
                                                          topology,
                                                          provider,
                                                          8080,
                                                          executor);
        manager.activate().await();
        drain();
        provider.clear();
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dropLog.close();
    }

    @Test
    void nodeRoutesPut_reactionQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var release = hold();

        manager.onNodeRoutesPut(put(node));
        assertThat(executor.queueDepth()).as("the reaction is queued, so the probe reaches the gate").isEqualTo(1);
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(provider.routeChanges).as("no cloud call after the engine stop").isEmpty();
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
        assertThat(dropLog.messages()).as("one warn for the first drop, naming the component")
                                      .hasSize(1)
                                      .allMatch(message -> message.contains("Engine stopped: react executor " + COMPONENT
                                                                            + " drops side-effect reactions"));
    }

    @Test
    void nodeRoutesPut_engineAlive_callsProviderOnce_dropsNothing() throws InterruptedException {
        var release = hold();

        manager.onNodeRoutesPut(put(node));
        assertThat(executor.queueDepth()).isEqualTo(1);
        release.countDown();
        drain();

        assertThat(provider.routeChanges).as("control arm: exactly one cloud call").hasSize(1);
        assertThat(provider.routeChanges.getFirst().nodeIps()).containsExactly("10.0.0.1");
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    /// v-2653 (verifier): row 54, the route removal's cloud call is a side effect behind the gate too.
    @Test
    void nodeRoutesRemove_reactionQueuedBeforeEngineStop_isDropped() throws InterruptedException {
        manager.onNodeRoutesPut(put(node));
        drain();
        provider.clear();
        var release = hold();

        manager.onNodeRoutesRemove(new ValueRemove<>(new KVCommand.Remove<>(NodeRoutesKey.nodeRoutesKey(node, ARTIFACT)),
                                                     Option.none()));
        assertThat(executor.queueDepth()).as("the removal is queued").isEqualTo(1);
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(provider.routeChanges).as("no cloud call after the engine stop").isEmpty();
        assertThat(executor.droppedEngineStopped()).isEqualTo(1);
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node) {
        var value = NodeRoutesValue.nodeRoutesValue(List.of(RouteEntry.activeRoute("POST", "/api/orders/", "create")));

        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, ARTIFACT), value), Option.none());
    }
}
