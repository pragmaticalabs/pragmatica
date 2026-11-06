// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.HeldDelivery;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// D1 exit (b′), KVStore dispatcher design §5: the #2616 rows' read-your-write rests only on "the apply promise resolves
/// after the commit's own notification was delivered". For AlertManager, ObservabilityConfigRegistry and LogLevelRegistry,
/// over a dispatched store whose dispatcher is held, the value read at the moment the write's promise resolves is the new
/// one. The committing node applies through the store and completes through `completeAfterDelivery`, as the engine does.
/// Mutation: `NotificationDispatcher.completeAfterDelivery` completes at apply (`now = true`): every arm reads the old
/// value, red.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DispatchedReadYourWriteTest {
    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final HeldDelivery hold = HeldDelivery.heldDelivery();
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(router,
                                                                        new Serializer() {
                                                                            @Override
                                                                            public <T> void write(ByteBuf buffer, T value) {}
                                                                        },
                                                                        new Deserializer() {
                                                                            @Override
                                                                            public <T> T read(ByteBuf buffer) {
                                                                                return null;
                                                                            }
                                                                        },
                                                                        NotificationDelivery.dispatched("ryw",
                                                                                                        DispatcherFailureSink.loggingOnly(),
                                                                                                        hold));

    @AfterEach
    void closeStore() {
        hold.release();
        store.close();
    }

    @Test
    void alertManager_readsItsThresholdWhenTheWriteResolves() {
        var manager = AlertManager.alertManager(committingNode(), store);
        router.addRoute(ValuePut.class, put -> routeThreshold(manager, put));

        var seen = readAtResolution(() -> manager.setThreshold("ryw.metric", 0.25, 0.75),
                                    () -> threshold(manager));

        assertThat(seen).isEqualTo(0.25);
    }

    @Test
    void observabilityConfigRegistry_readsItsConfigWhenTheWriteResolves() {
        var registry = ObservabilityConfigRegistry.observabilityConfigRegistry(committingNode(), store);
        router.addRoute(ValuePut.class, put -> routeObservability(registry, put));

        var seen = readAtResolution(() -> registry.setConfig("org.example:ryw", "call", true, false, false, false, 3),
                                    () -> registry.getConfig("org.example:ryw", "call").depth());

        assertThat(seen).isEqualTo(3);
    }

    @Test
    void logLevelRegistry_readsItsLevelWhenTheWriteResolves() {
        var registry = LogLevelRegistry.logLevelRegistry(committingNode(), store);
        router.addRoute(ValuePut.class, put -> routeLogLevel(registry, put));

        var seen = readAtResolution(() -> registry.setLevel("org.example.ryw", "DEBUG"),
                                    () -> registry.allLevels().get("org.example.ryw"));

        assertThat(seen).isEqualTo("DEBUG");
    }

    /// Holds the dispatcher before the write's batch, starts the write, records what `read` returns when the write's
    /// promise resolves, releases the hold after the batch reached it, and returns the recorded value.
    private <T> Object readAtResolution(Supplier<Promise<Unit>> write, Supplier<T> read) {
        hold.holdFrom(store.enqueuedSeq() + 1);
        var seen = new AtomicReference<Object>("unresolved");
        var written = write.get()
                           .onSuccess(_ -> seen.set(read.get()));

        assertThat(hold.awaitHeld(10)).as("the dispatcher is held before the write's batch").isTrue();
        sleep();
        hold.release();
        assertThat(written.await(timeSpan(10).seconds())
                          .isSuccess()).isTrue();

        return awaitSeen(seen);
    }

    private static Object awaitSeen(AtomicReference<Object> seen) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while ("unresolved".equals(seen.get()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        return seen.get();
    }

    private static void sleep() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Object threshold(AlertManager manager) {
        var values = manager.getAllThresholds().get("ryw.metric");

        return values == null
               ? "absent"
               : values[0];
    }

    /// A node whose `apply` is the engine's commit path: apply the batch through the store (which enqueues its
    /// notifications), then park the promise until the batch is delivered.
    @SuppressWarnings("unchecked")
    private RabiaNode<KVCommand<AetherKey>> committingNode() {
        RabiaNode<KVCommand<AetherKey>> node = Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList()))
               .thenAnswer(invocation -> commit(invocation.getArgument(0)));

        return node;
    }

    private Promise<List<Object>> commit(List<KVCommand<AetherKey>> commands) {
        var promise = Promise.<List<Object>> promise();
        List<Object> results = store.process(store.createBatch(commands));

        store.completeAfterDelivery(promise, results);

        return promise;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void routeThreshold(AlertManager manager, ValuePut put) {
        if (put.cause().key() instanceof AetherKey.AlertThresholdKey) {
            manager.onAlertThresholdPut(put);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void routeObservability(ObservabilityConfigRegistry registry, ValuePut put) {
        if (put.cause().key() instanceof AetherKey.ObservabilityConfigKey) {
            registry.onObservabilityConfigPut(put);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void routeLogLevel(LogLevelRegistry registry, ValuePut put) {
        if (put.cause().key() instanceof AetherKey.LogLevelKey) {
            registry.onLogLevelPut(put);
        }
    }
}
