// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.generation.BootstrapModuleTest.RecordingClusterNode;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Exit (ab), cluster-write kind (KVStore dispatcher design §5, §3.5.1 rows 66/67; D1-react): a bootstrap retry
/// absorbed before an engine-only stop, whose reaction runs after it, neither writes the core partition nor burns a
/// bootstrap attempt. Row 67 (the restore-decision hook) reaches the same seam through `retryIfNeeded`.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BootstrapEngineStopGateTest {
    private static final NodeId SELF = new NodeId("node-self");

    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor("BootstrapModule",
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());
    private final RecordingClusterNode cluster = new RecordingClusterNode();
    private final BootstrapModule module = newModule(cluster);

    private BootstrapModule newModule(RecordingClusterNode node) {
        var created = BootstrapModule.bootstrapModule(() -> true,
                                                      // #2654: a committed incarnation; at Epoch.NO_INCARNATION the module plans no bootstrap write.
                                                      () -> Epoch.epoch(1L, 1L, 0L),
                                                      () -> Option.<Long>none(),
                                                      HlcClock.hlcClock(new NodeId("test-self")),
                                                      () -> Set.<NodeId>of(),
                                                      () -> Map.of(),
                                                      () -> SELF,
                                                      () -> new BootstrapModule.ClusterConfigBaseline(1, 1, 0),
                                                      node);

        created.installReactExecutor(executor);

        return created;
    }

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
    void retryIfNeeded_reactionQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var release = hold();

        module.retryIfNeeded();
        assertThat(executor.queueDepth()).as("the reaction is queued, so the probe reaches the gate").isEqualTo(1);
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(cluster.batches).as("no cluster write after the engine stop").isEmpty();
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
    }

    @Test
    void retryIfNeeded_engineAlive_attemptsOnce_dropsNothing() throws InterruptedException {
        var release = hold();

        module.retryIfNeeded();
        assertThat(executor.queueDepth()).isEqualTo(1);
        release.countDown();
        drain();

        assertThat(cluster.batches).as("control arm: the attempt wrote the core partition once").hasSize(1);
        assertThat(executor.droppedEngineStopped()).isZero();
    }
}
