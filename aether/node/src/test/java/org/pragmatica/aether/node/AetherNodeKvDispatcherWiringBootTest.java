// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;

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

    /// v-2648 F5: the node's store raises its dispatcher operator events through THIS node's operator-warning sink. A handler
    /// held past the slow-handler threshold raises `kv-dispatch-slow-handler`, read at the node's event aggregator, its
    /// subject the notification type. The node's router is wrapped so one marker put is held instead of routed (no node
    /// handler sees it). Mutation: build the node's store with `OperatorEvents.none()`, or drop the sink binding (red).
    @Test
    @Timeout(value = 60, unit = SECONDS)
    void slowHandler_raisesKvDispatchSlowHandler_throughTheNodesSink() throws Exception {
        var delegateRouter = MessageRouter.DelegateRouter.delegate();
        var node = booted(AetherNode.aetherNode(config(),
                                                delegateRouter,
                                                NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                                DispatcherFailureSink.loggingOnly()));
        var marker = AetherKey.LogLevelKey.forLogger("d1-slow-handler-wiring");
        var observed = new CountDownLatch(1);

        delegateRouter.replaceDelegate(holdingMarker(currentDelegate(delegateRouter), marker, () -> awaitQuietly(observed)));
        var store = node.kvStore();

        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(marker,
                                                                                           AetherValue.LogLevelValue.logLevelValue("d1-slow-handler-wiring",
                                                                                                                                   "INFO")))));
        try {
            assertThat(eventually(() -> slowHandlerRaised(node, "ValuePut LogLevelKey"))).as("kv-dispatch-slow-handler reached the node's sink")
                                                                                        .isTrue();
        } finally {
            observed.countDown();
        }
    }

    private static boolean slowHandlerRaised(AetherNode node, String subject) {
        return node.eventAggregator()
                   .lastRaisedOperatorWarning()
                   .filter(ClusterEvent.OperatorWarning.class::isInstance)
                   .map(ClusterEvent.OperatorWarning.class::cast)
                   .filter(event -> "kv-dispatch-slow-handler".equals(event.details().get("code")))
                   .filter(event -> subject.equals(event.details().get("subject")))
                   .isPresent();
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }

        return condition.getAsBoolean();
    }

    /// The router the node installed behind its delegate (RabiaNode builds it at construction).
    private static MessageRouter currentDelegate(MessageRouter.DelegateRouter delegateRouter) throws ReflectiveOperationException {
        var field = delegateRouter.getClass()
                                  .getDeclaredField("delegate");

        field.setAccessible(true);

        return (MessageRouter) field.get(delegateRouter);
    }

    /// Routes everything to `original`, except the put of `marker`, which it hands to `hold` instead (on the delivering thread).
    private static MessageRouter holdingMarker(MessageRouter original, AetherKey marker, Runnable hold) {
        return new MessageRouter.ImmutableRouter<Message>() {
            @Override
            public Map<Class<Message>, List<Consumer<Message>>> routingTable() {
                return Map.of();
            }

            @Override
            public <R extends Message> void route(R message) {
                if (message instanceof KVStoreNotification.ValuePut<?, ?> put && marker.equals(put.cause().key())) {
                    hold.run();

                    return;
                }

                original.route(message);
            }
        };
    }

    /// v-2648 F2, deterministic (vd1A4: the stop arm above went red only 1 in 14 with the fix reverted). The store's close is
    /// held: a handler holds the dispatcher thread through the close's interrupt, so the close's bounded join (5 s) waits
    /// for it. While the close is held (the store already reads closed), the promise `stop()` returned must not be resolved;
    /// once the handler is released, it resolves. Mutation: the F2 revert, `closeKvStore` as an independent
    /// `onResultRun` (red: the promise is resolved before the close runs).
    @Test
    @Timeout(value = 90, unit = SECONDS)
    void stop_resolvesOnlyAfterTheStoreCloseReturns() throws Exception {
        var delegateRouter = MessageRouter.DelegateRouter.delegate();
        var node = booted(AetherNode.aetherNode(config(),
                                                delegateRouter,
                                                NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                                DispatcherFailureSink.loggingOnly()));
        var marker = AetherKey.LogLevelKey.forLogger("d1-stop-holds-close");
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        delegateRouter.replaceDelegate(holdingMarker(currentDelegate(delegateRouter),
                                                     marker,
                                                     () -> holdThroughInterrupts(held, release)));
        var store = node.kvStore();

        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(marker,
                                                                                           AetherValue.LogLevelValue.logLevelValue("d1-stop-holds-close",
                                                                                                                                   "INFO")))));
        assertThat(held.await(20, TimeUnit.SECONDS)).as("the dispatcher is held in the marker's handler").isTrue();
        nodes.remove(node);
        Promise<Unit> stopped = node.stop();

        try {
            assertThat(eventually(() -> store.deliveryMetrics().closed())).as("the stop reached the store's close").isTrue();
            assertThat(stopped.isResolved()).as("stop() resolved while the store's close is still held").isFalse();
            Thread.sleep(300);
            assertThat(stopped.isResolved()).as("stop() resolved while the store's close is still held (300 ms later)")
                                            .isFalse();
        } finally {
            release.countDown();
        }
        assertThat(stopped.await(timeSpan(30).seconds())
                          .isSuccess()).as("stop() resolves once the close returns").isTrue();
        assertThat(liveThreadNamed(store.dispatcherThreadName())).as("no dispatcher thread outlives the stop").isFalse();
    }

    /// Holds the delivering thread until `release`, through the close's interrupt (re-asserted on return), bounded at 30 s.
    private static void holdThroughInterrupts(CountDownLatch held, CountDownLatch release) {
        held.countDown();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        var interrupted = false;

        while (release.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                release.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
