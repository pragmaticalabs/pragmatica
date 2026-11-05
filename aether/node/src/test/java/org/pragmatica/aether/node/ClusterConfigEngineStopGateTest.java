// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.node.assembly.ClusterBootstrapAssembly;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/// Exit (ab), cloud-call kind (KVStore dispatcher design §5, §3.5.1 row 68; D1-react): a ClusterConfig put absorbed
/// before an engine-only stop, whose reaction runs after it, never reaches worker-topology reconcile (cloud listing and
/// provisioning) or the leader reconciler. The config-changed state step stays in absorb and runs at once.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ClusterConfigEngineStopGateTest {
    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor("ClusterTopologyManager",
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());
    private final ClusterTopologyManager topology = mock(ClusterTopologyManager.class);
    private final LeaderReconciler reconciler = mock(LeaderReconciler.class);

    @AfterEach
    void tearDown() {
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
    void clusterConfigPut_reactionQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var release = hold();

        ClusterBootstrapAssembly.onClusterConfigPut(put(), topology, reconciler, executor);
        verify(topology).onClusterConfigChanged();
        assertThat(executor.queueDepth()).as("the reaction is queued, so the probe reaches the gate").isEqualTo(1);
        engineStopped.set(true);
        release.countDown();
        drain();

        verify(topology, never()).reconcileWorkerTopology();
        verify(reconciler, never()).onConfigChange();
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
    }

    @Test
    void clusterConfigPut_engineAlive_reconcilesOnce_dropsNothing() throws InterruptedException {
        var release = hold();

        ClusterBootstrapAssembly.onClusterConfigPut(put(), topology, reconciler, executor);
        assertThat(executor.queueDepth()).isEqualTo(1);
        verify(topology, never()).reconcileWorkerTopology();
        release.countDown();
        drain();

        verify(topology).onClusterConfigChanged();
        verify(topology).reconcileWorkerTopology();
        verify(reconciler).onConfigChange();
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    private static ValuePut<ClusterConfigKey, ClusterConfigValue> put() {
        return new ValuePut<>(new KVCommand.Put<>(ClusterConfigKey.CURRENT, mock(ClusterConfigValue.class)),
                              Option.none());
    }
}
