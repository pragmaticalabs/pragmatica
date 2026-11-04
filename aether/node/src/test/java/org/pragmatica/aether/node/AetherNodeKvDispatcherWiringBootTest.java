// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// D1 production opt-in pin (KVStore dispatcher design §3.1): every `KVStore` constructor defaults to inline delivery,
/// so a node delivers on its own dispatcher thread only because `AetherNode` passes `NotificationDelivery.dispatched`
/// at its one store construction site. Every boot entry is asserted: the production entry `Main` uses, the in-JVM
/// `jvmExit` entry and the in-JVM exit-code entry (Ember). Mutation: build the node's store with inline delivery
/// (all three arms red). The stop arm pins the teardown `close()` (exit (x) at the node): after `stop()` the store is
/// closed and its dispatcher thread is gone; mutation, drop the close step (red).
class AetherNodeKvDispatcherWiringBootTest {
    @TempDir
    Path tempDir;

    private final List<AetherNode> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        nodes.forEach(node -> node.stop()
                                  .await(timeSpan(10).seconds()));
        ConfigService.clear();
        ResourceProvider.clear();
    }

    private AetherNodeConfig config() {
        return AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                 Option.none(),
                                                                 ConfigurationProvider.builder().build(),
                                                                 tempDir);
    }

    private AetherNode booted(Result<AetherNode> boot) {
        var node = boot.onFailure(cause -> fail("boot must succeed: " + cause.message()))
                       .unwrap();
        nodes.add(node);

        return node;
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void productionEntry_buildsADispatchedStore() {
        var node = booted(AetherNode.aetherNode(config(), DispatcherFailureSink.loggingOnly()));

        assertThat(node.kvStore().deliversOnDispatcherThread()).as("the production node store must be dispatched")
                                                               .isTrue();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void inJvmEntry_buildsADispatchedStore() {
        var node = booted(AetherNode.aetherNode(config(), () -> {}));

        assertThat(node.kvStore().deliversOnDispatcherThread()).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void exitCodeEntry_buildsADispatchedStore() {
        var node = booted(AetherNode.aetherNode(config(), (int code) -> {}));

        assertThat(node.kvStore().deliversOnDispatcherThread()).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void stop_closesTheStoreAndEndsItsDispatcherThread() {
        var node = booted(AetherNode.aetherNode(config(), () -> {}));
        var store = node.kvStore();

        store.awaitDelivered(store.enqueuedSeq())
             .await(timeSpan(10).seconds());
        nodes.remove(node);
        node.stop()
            .await(timeSpan(30).seconds());

        assertThat(store.deliveryMetrics().closed()).as("the stop sequence closes the store").isTrue();
        assertThat(liveThreadNamed(store.dispatcherThreadName())).as("no dispatcher thread outlives the stop").isFalse();
    }

    /// The in-JVM sink is called on the failing dispatcher thread, and the node stop it triggers closes the store,
    /// which joins that thread: the stop must run on another thread. Mutation: run the stop inline (red).
    @Test
    @Timeout(value = 30, unit = SECONDS)
    void inJvmSink_stopsTheNodeOffTheCallingThread() throws Exception {
        var stoppedOn = new AtomicReference<Thread>();
        var stopped = new CountDownLatch(1);

        AetherNode.inJvmDispatcherSink(() -> recordStop(stoppedOn, stopped))
                  .stopNode("kv-dispatcher-failed", new StackOverflowError("handler"));

        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stoppedOn.get()).isNotSameAs(Thread.currentThread());
    }

    private static void recordStop(AtomicReference<Thread> stoppedOn, CountDownLatch stopped) {
        stoppedOn.set(Thread.currentThread());
        stopped.countDown();
    }

    private static boolean liveThreadNamed(String name) {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }
}
