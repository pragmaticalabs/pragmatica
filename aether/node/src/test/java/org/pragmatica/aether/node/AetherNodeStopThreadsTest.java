// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #932: nine `replica-set-controller` and nine `stream-partition-backfill` threads survived a nine-node
/// stop, one of each per node. Neither executor was closed on the node's stop path. The pin is the
/// THREADS, counted by name before boot, while the node is up, and after `stop()`; a promise that
/// resolves proves nothing about a thread.
class AetherNodeStopThreadsTest {
    private static final String CONTROLLER = "replica-set-controller";
    private static final String BACKFILL = "stream-partition-backfill";

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop failed in teardown: " + cause.message()));
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void stop_endsTheReplicaSetControllerAndBackfillThreads() throws InterruptedException {
        var controllerBaseline = threadsNamed(CONTROLLER);
        var backfillBaseline = threadsNamed(BACKFILL);

        node = AetherNode.aetherNode(AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                                        Option.none(),
                                                                                        ConfigurationProvider.builder().build(),
                                                                                        tempDir),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        assertThat(eventually(() -> threadsNamed(CONTROLLER) > controllerBaseline)).as("control: a booted node owns a %s thread",
                                                                                      CONTROLLER).isTrue();
        assertThat(eventually(() -> threadsNamed(BACKFILL) > backfillBaseline)).as("control: a booted node owns a %s thread",
                                                                                  BACKFILL).isTrue();

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;

        assertThat(eventually(() -> threadsNamed(CONTROLLER) <= controllerBaseline)).as("%s threads after stop; baseline %d, now %d",
                                                                                       CONTROLLER,
                                                                                       controllerBaseline,
                                                                                       threadsNamed(CONTROLLER)).isTrue();
        assertThat(eventually(() -> threadsNamed(BACKFILL) <= backfillBaseline)).as("%s threads after stop; baseline %d, now %d",
                                                                                   BACKFILL,
                                                                                   backfillBaseline,
                                                                                   threadsNamed(BACKFILL)).isTrue();
    }

    private static long threadsNamed(String name) {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(thread -> thread.isAlive() && thread.getName().equals(name))
                     .count();
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + 10_000_000_000L;

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
