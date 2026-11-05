// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.node.assembly.WorkerHierarchyAssembly;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.worker.governor.GovernorAnnouncer;
import org.pragmatica.aether.worker.governor.GovernorAuthorityClient;
import org.pragmatica.aether.worker.health.CommunityMemberDirectory;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Exit (ab), worker-mode kind (KVStore dispatcher design §5, §3.5.1 row 92; D1-react): a WORKER activation directive
/// absorbed before an engine-only stop, whose reaction runs after it, neither switches the node to forwarding mode nor
/// starts (and publishes) a governor announcer. `markAssigned` is the absorb-side state step and runs at once.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WorkerActivationEngineStopGateTest {
    private static final NodeId SELF = new NodeId("worker-1");

    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor("WorkerActivation",
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());
    private final CoreSwimHealthDetector detector = Mockito.mock(CoreSwimHealthDetector.class);
    private final AtomicReference<GovernorAnnouncer> announcer = new AtomicReference<>();
    private final SwitchableClusterNode<KVCommand<org.pragmatica.aether.slice.kvstore.AetherKey>> switchable = switchableMock();

    @AfterEach
    void tearDown() {
        Option.option(announcer.get()).onPresent(GovernorAnnouncer::stop);
        executor.close();
    }

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

    @Test
    void workerDirective_reactionQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var release = hold();

        handle();
        verify(detector).markAssigned();
        assertThat(executor.queueDepth()).as("the reaction is queued, so the probe reaches the gate").isEqualTo(1);
        engineStopped.set(true);
        release.countDown();
        drain();

        verify(switchable, never()).switchTo(Mockito.any());
        assertThat(announcer.get()).as("no announcer started or published after the engine stop").isNull();
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
    }

    @Test
    void workerDirective_engineAlive_activatesOnce_dropsNothing() throws InterruptedException {
        var release = hold();

        handle();
        assertThat(executor.queueDepth()).isEqualTo(1);
        verify(switchable, never()).switchTo(Mockito.any());
        release.countDown();
        drain();

        verify(switchable).switchTo(Mockito.any());
        assertThat(announcer.get()).as("control arm: the announcer was published").isNotNull();
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SwitchableClusterNode switchableMock() {
        return Mockito.mock(SwitchableClusterNode.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handle() {
        var config = Mockito.mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF);
        when(config.workerConfig()).thenReturn(Option.some(Mockito.mock(WorkerConfig.class)));
        when(config.topology()).thenReturn(new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of()));
        var put = new ValuePut<ActivationDirectiveKey, ActivationDirectiveValue>(new KVCommand.Put<>(new ActivationDirectiveKey(SELF),
                                                                                                      ActivationDirectiveValue.worker("c-0", "")),
                                                                                  Option.none());

        WorkerHierarchyAssembly.handleActivationDirective(put,
                                                          SELF,
                                                          Mockito.mock(RabiaNode.class),
                                                          switchable,
                                                          Mockito.mock(ForwardingClusterNode.class),
                                                          config,
                                                          MessageRouter.DelegateRouter.delegate(),
                                                          Mockito.mock(KVStore.class),
                                                          null,
                                                          null,
                                                          () -> detector,
                                                          CommunityMemberDirectory.communityMemberDirectory(),
                                                          announcer,
                                                          Mockito.mock(GovernorAuthorityClient.class),
                                                          () -> false,
                                                          LoggerFactory.getLogger(WorkerActivationEngineStopGateTest.class),
                                                          executor);
    }
}
