// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionOwnership;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// Partition-ownership gating for declarative `[streams.X]` consumers (#488).
///
/// The decision table under test: this node consumes partition `P` of stream `S` iff it is the HRW
/// OWNER of `(S, P)` AND the declaring slice is deployed locally. Both halves matter — owner-gating
/// alone would try to consume partitions whose slice is absent, and deployment-gating alone would
/// deliver every event once per node that has the slice.
///
/// The node codec here is the REAL [FrameworkCodecs], so the `eventTypePublishable` assertions
/// exercise the actual #526 condition rather than a mocked stand-in: `java.lang.String` is registered,
/// an app-defined record is not.
class StreamConsumerManagerTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("onOrderEvent").unwrap();
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final String STREAM = "orders";
    private static final String CONFIG_SECTION = "streams.orders";
    private static final String GROUP = "orders-onOrderEvent";
    private static final int PARTITION_COUNT = 4;

    private StreamConsumerRegistry registry;
    private RecordingRuntime runtime;
    private InvocationHandler invocationHandler;
    private SliceInvoker invoker;
    private MutableOwnership ownership;

    @BeforeEach
    void setUp() {
        registry = StreamConsumerRegistry.streamConsumerRegistry();
        runtime = new RecordingRuntime();
        invocationHandler = mock(InvocationHandler.class);
        invoker = mock(SliceInvoker.class);
        ownership = new MutableOwnership();
        when(invocationHandler.localSlice(any())).thenReturn(Option.none());
    }

    private StreamConsumerManager manager() {
        return StreamConsumerManager.streamConsumerManager(registry,
                                                           runtime,
                                                           invoker,
                                                           invocationHandler,
                                                           FrameworkCodecs.frameworkCodecs(),
                                                           ownership);
    }

    private void declare(String eventType, boolean batchMode) {
        var key = StreamRegistrationKey.streamRegistrationKey(STREAM, CONFIG_SECTION, ARTIFACT, METHOD);
        var value = StreamRegistrationValue.streamRegistrationValue(SELF, GROUP, batchMode, eventType);

        registry.onStreamRegistrationPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
    }

    private void declareStringConsumer() {
        declare("java.lang.String", false);
    }

    private void undeclare() {
        var key = StreamRegistrationKey.streamRegistrationKey(STREAM, CONFIG_SECTION, ARTIFACT, METHOD);

        registry.onStreamRegistrationRemove(new ValueRemove<>(new KVCommand.Remove<>(key), Option.none()));
    }

    private void deploySliceLocally() {
        when(invocationHandler.localSlice(ARTIFACT)).thenReturn(Option.some(new StubBridge()));
    }

    @Nested
    class Gating {

        @Test
        void reconcile_subscribesOwnedPartitions_whenSliceDeployedLocally() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.own(0, 2);

            manager().reconcile();

            assertThat(runtime.subscribedPartitions())
                    .describedAs("consumes exactly the partitions this node owns")
                    .containsExactlyInAnyOrder(0, 2);
        }

        @Test
        void reconcile_subscribesNothing_whenNodeOwnsNoPartition() {
            declareStringConsumer();
            deploySliceLocally();

            manager().reconcile();

            assertThat(runtime.subscribedPartitions())
                    .describedAs("a node holding the slice but owning no partition consumes nothing — another node owns them")
                    .isEmpty();
        }

        @Test
        void reconcile_subscribesNothing_whenSliceNotDeployedLocally() {
            declareStringConsumer();
            ownership.own(0, 1, 2, 3);

            manager().reconcile();

            assertThat(runtime.subscribedPartitions())
                    .describedAs("owning a partition is not enough — the slice must be here to invoke")
                    .isEmpty();
        }

        @Test
        void reconcile_subscribesNothing_whenStreamUnknownLocally() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.forgetStream();

            manager().reconcile();

            assertThat(runtime.subscribedPartitions())
                    .describedAs("a stream whose config has not reached this node yet is a transient state, not an error")
                    .isEmpty();
        }

        @Test
        void reconcile_unsubscribes_whenOwnershipIsLost() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.own(0, 1);
            var manager = manager();

            manager.reconcile();
            assertThat(runtime.subscribedPartitions()).containsExactlyInAnyOrder(0, 1);

            ownership.own(1);
            manager.reconcile();

            assertThat(runtime.subscribedPartitions())
                    .describedAs("losing ownership of partition 0 detaches only that partition")
                    .containsExactly(1);
        }

        @Test
        void reconcile_unsubscribesEverything_whenDeclarationIsRemoved() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.own(0, 1);
            var manager = manager();

            manager.reconcile();
            undeclare();
            manager.reconcile();

            assertThat(runtime.subscribedPartitions()).isEmpty();
        }

        @Test
        void reconcile_addsNoDuplicateSubscription_whenCalledRepeatedly() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.own(0, 1);
            var manager = manager();

            manager.reconcile();
            manager.reconcile();
            manager.reconcile();

            assertThat(runtime.subscribeCalls)
                    .describedAs("reconcile is idempotent — a re-tick must not re-subscribe")
                    .isEqualTo(2);
            assertThat(manager.activeSubscriptionCount()).isEqualTo(2);
        }
    }

    @Nested
    class LoudFailures {

        @Test
        void statuses_reportUnconsumedOwnedPartitions_whenOwnerLacksSlice() {
            declareStringConsumer();
            ownership.own(1, 3);
            var manager = manager();

            manager.reconcile();

            assertThat(manager.statuses()).singleElement()
                                          .satisfies(status -> {
                                              assertThat(status.sliceDeployedLocally()).isFalse();
                                              assertThat(status.unconsumedOwnedPartitions())
                                                      .describedAs("the gap must be named, not silent: nobody consumes these")
                                                      .containsExactlyInAnyOrder(1, 3);
                                              assertThat(status.diagnostic().or(""))
                                                      .contains("not deployed here")
                                                      .contains("NOT being consumed");
                                          });
        }

        @Test
        void statuses_reportNoGap_whenNodeOwnsNothingAndLacksSlice() {
            declareStringConsumer();
            var manager = manager();

            manager.reconcile();

            assertThat(manager.statuses()).singleElement()
                                          .satisfies(status -> {
                                              assertThat(status.unconsumedOwnedPartitions())
                                                      .describedAs("not owning the partitions is not a gap — it is another node's job")
                                                      .isEmpty();
                                              assertThat(status.diagnostic()).isEqualTo(Option.none());
                                          });
        }

        @Test
        void statuses_reportEventTypeUnpublishable_forAppDefinedType() {
            declare("com.example.OrderPlaced", false);
            deploySliceLocally();
            ownership.own(0);
            var manager = manager();

            manager.reconcile();

            assertThat(manager.statuses()).singleElement()
                                          .satisfies(status -> {
                                              assertThat(status.eventTypePublishable())
                                                      .describedAs("an app-defined type is absent from the node codec, so it cannot be published at all")
                                                      .isFalse();
                                              assertThat(status.diagnostic().or(""))
                                                      .describedAs("the operator must learn the real reason, and #526 by name")
                                                      .contains("#526");
                                          });
        }

        @Test
        void statuses_reportEventTypePublishable_forFrameworkType() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.own(0);
            var manager = manager();

            manager.reconcile();

            assertThat(manager.statuses()).singleElement()
                                          .satisfies(status -> {
                                              assertThat(status.eventTypePublishable()).isTrue();
                                              assertThat(status.diagnostic()).isEqualTo(Option.none());
                                          });
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void stop_unsubscribesEverySubscription() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.own(0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.activeSubscriptionCount()).isEqualTo(4);

            manager.stop();

            assertThat(runtime.subscribedPartitions())
                    .describedAs("a stopped node must leave nothing attached — #499 zombie lesson")
                    .isEmpty();
            assertThat(manager.activeSubscriptionCount()).isZero();
        }

        @Test
        void statuses_areEmpty_whenNothingDeclared() {
            assertThat(manager().statuses()).isEmpty();
        }
    }

    /// Ownership stub. Defaults to owning nothing, which is the correct default for a node that has
    /// not been told otherwise.
    private static final class MutableOwnership implements PartitionOwnership {
        private final Set<Integer> owned = ConcurrentHashMap.newKeySet();
        private volatile boolean streamKnown = true;

        void own(int... partitions) {
            owned.clear();
            for (var partition : partitions) {
                owned.add(partition);
            }
        }

        void forgetStream() {
            streamKnown = false;
        }

        @Override
        public Option<Integer> partitionCount(String streamName) {
            return streamKnown
                   ? Option.some(PARTITION_COUNT)
                   : Option.none();
        }

        @Override
        public boolean ownedLocally(String streamName, int partition) {
            return owned.contains(partition);
        }
    }

    /// Records subscribe/unsubscribe instead of running a delivery loop, so the gating decision is
    /// observable without a partition manager.
    private static final class RecordingRuntime implements StreamConsumerRuntime {
        private final Map<Integer, String> subscriptions = new ConcurrentHashMap<>();
        private int subscribeCalls;

        List<Integer> subscribedPartitions() {
            return List.copyOf(subscriptions.keySet());
        }

        @Override
        public Result<Unit> subscribe(String streamName, int partition, ConsumerConfig config, ConsumerCallback callback) {
            return subscribe(streamName, partition, config, callback, IdlePolicy.REAP_WHEN_IDLE);
        }

        @Override
        public Result<Unit> subscribe(String streamName,
                                      int partition,
                                      ConsumerConfig config,
                                      ConsumerCallback callback,
                                      IdlePolicy idlePolicy) {
            subscribeCalls++;
            subscriptions.put(partition, config.groupId());

            return Result.unitResult();
        }

        @Override
        public Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup) {
            subscriptions.remove(partition);

            return Result.unitResult();
        }

        @Override
        public Option<Long> cursorPosition(String streamName, int partition, String consumerGroup) {
            return Option.none();
        }

        @Override
        public Option<TransactionalCursorCommit> transactionalCursorCommit() {
            return Option.none();
        }

        @Override
        public DeadLetterHandler deadLetterHandler() {
            return DeadLetterHandler.deadLetterHandler();
        }

        @Override
        public List<SubscriptionSnapshot> subscriptions() {
            return subscriptions.entrySet()
                                .stream()
                                .map(entry -> new SubscriptionSnapshot(STREAM,
                                                                      entry.getKey(),
                                                                      entry.getValue(),
                                                                      0L,
                                                                      false,
                                                                      IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED))
                                .toList();
        }

        @Override
        public void close() {}
    }

    /// Bridge stub whose classLoader resolves the declared event type, so the #526 publishability
    /// probe runs against a real class lookup.
    private static final class StubBridge implements SliceBridge {
        @Override
        public Promise<byte[]> invoke(String methodName, byte[] input) {
            return Promise.success(input);
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public ClassLoader classLoader() {
            return StreamConsumerManagerTest.class.getClassLoader();
        }

        @Override
        public List<String> methodNames() {
            return List.of(METHOD.name());
        }

        @Override
        public Option<ObservabilityStrategyCell> observabilityCell(String methodName) {
            return Option.none();
        }
    }
}
