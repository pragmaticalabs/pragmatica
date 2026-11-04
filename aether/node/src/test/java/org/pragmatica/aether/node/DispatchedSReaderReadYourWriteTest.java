// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.UpgradeRunIndex;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskPauseKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.UpgradeRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskPauseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.HeldDelivery;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// D1 exit (b), KVStore dispatcher design §5 and the S-reader map (kvstore-d0-classification, "(S) reader to row map"):
/// every hard S-reader reads state its handler writes, and its read-your-write rests on "the apply promise resolves
/// after the commit's own notification was delivered". Over a dispatched store whose dispatcher is held, each arm
/// commits the reader's own command the way the engine does (apply through the store, park the promise with
/// `completeAfterDelivery`), routes the notification to the reader's real handler, and records what the reader returns
/// at the moment the promise resolves: it must be the new value. S2/S3 go through the reader's own write path
/// (`DynamicConfigManager.setConfig`); the others commit the command their writer commits.
///
/// Covered: S2/S3 (config overlay), S4/S6 (task state), S5 (paused flag), S8/S9 (ownership high-water), S11
/// (node-replacement index), S12 (upgrade-run index), S13 (incarnation mirror). Not covered here: S1, S7, S10 (their
/// fixtures need an HTTP route publisher, the stream partition manager, and an assignment-token-guarded checkpoint).
/// Mutation: `NotificationDispatcher.completeAfterDelivery` completes at apply (`now = true`): every arm reads the old
/// value, red.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DispatchedSReaderReadYourWriteTest {
    private static final NodeId SELF = new NodeId("ryw-self");
    private static final NodeId ORIGINAL = new NodeId("ryw-original");
    private static final LeaderValue LEADER = new LeaderValue(SELF, 1);
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:ryw-slice:1.0.0")
                                                     .unwrap();
    private static final MethodName METHOD = MethodName.methodName("cleanup")
                                                       .unwrap();

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
                                                                        NotificationDelivery.dispatched("ryw-s",
                                                                                                        DispatcherFailureSink.loggingOnly(),
                                                                                                        hold));

    @AfterEach
    void closeStore() {
        hold.release();
        store.close();
    }

    /// S2, S3: ConfigRoutes reads the overlay right after `setConfig` resolves.
    @Test
    void dynamicConfigManager_readsItsOverrideWhenTheWriteResolves() {
        var provider = DynamicConfigurationProvider.dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                      .build());
        var manager = DynamicConfigManager.dynamicConfigManager(committingNode(), store, provider, SELF);
        routeOn(ConfigKey.class, manager::onConfigPut);

        var seen = readAtResolution(() -> manager.setConfig("ryw.key", "new"),
                                    () -> Option.option(manager.overrides()
                                                               .get("ryw.key")));

        assertThat(seen).isEqualTo(Option.some("new"));
    }

    /// S4, S6: the prior state a SINGLE-mode fire injects is read from the state registry after its commit.
    @Test
    void scheduledTaskStateRegistry_readsTheStateWhenTheWriteResolves() {
        var registry = ScheduledTaskStateRegistry.scheduledTaskStateRegistry();
        routeOn(ScheduledTaskStateKey.class, registry::onStatePut);
        var key = ScheduledTaskStateKey.scheduledTaskStateKey("ryw", ARTIFACT, METHOD, SELF);
        var state = ScheduledTaskStateValue.unknownOutcomeState(Option.none(), 7L, 5L);

        var seen = readAtResolution(() -> commit(new KVCommand.Put<>(key, state)),
                                    () -> registry.stateFor(key));

        assertThat(seen).isEqualTo(Option.some(state));
    }

    /// S5: the paused flag a pause route reports is the registry's, set by the pause handler.
    @Test
    void scheduledTaskRegistry_readsThePausedFlagWhenThePauseResolves() {
        var registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        routeOn(ScheduledTaskKey.class, registry::onScheduledTaskPut);
        routeOn(ScheduledTaskPauseKey.class, registry::onScheduledTaskPausePut);
        var task = ScheduledTaskKey.scheduledTaskKey("ryw", ARTIFACT, METHOD);
        settle(commit(new KVCommand.Put<>(task, ScheduledTaskValue.intervalTask(SELF, "1s", ExecutionMode.SINGLE))));

        var seen = readAtResolution(() -> commit(new KVCommand.Put<>(ScheduledTaskPauseKey.scheduledTaskPauseKey(task),
                                                                     ScheduledTaskPauseValue.scheduledTaskPauseValue(1L))),
                                    () -> registry.allTasks()
                                                  .getFirst()
                                                  .paused());

        assertThat(seen).isEqualTo(true);
    }

    /// S8, S9 (SAFETY): a linearizable serve fences on the ownership high-water its handler advances.
    @Test
    void ownershipEpochHighWater_readsTheNewEpochWhenTheWriteResolves() {
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(store);
        routeOn(StreamPartitionOwnershipKey.class, highWater::onStreamPartitionOwnershipPut);
        var epoch = Epoch.epoch(0L, 3L, 0);
        var ownership = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, epoch, 3L, HlcTimestamp.ZERO);

        var seen = readAtResolution(() -> commit(new KVCommand.Put<>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey("ryw",
                                                                                                                             0),
                                                                     ownership)),
                                    () -> highWater.highWater(OwnershipDomain.streamPartition("ryw", 0)));

        assertThat(seen).isEqualTo(Option.some(epoch));
    }

    /// S11: NodeReplacementWiring reads the replacement index its handler fills (the WorkerHierarchyAssembly route).
    @Test
    void nodeReplacementIndex_readsTheRecordWhenTheTransactionResolves() {
        var index = NodeReplacementIndex.nodeReplacementIndex();
        routeOn(NodeReplacementKey.class,
                (ValuePut<NodeReplacementKey, NodeReplacementValue> put) -> index.put(put.cause()
                                                                                         .key(),
                                                                                      put.cause()
                                                                                         .value()));
        commitLeader();
        var key = new NodeReplacementKey(ORIGINAL);
        var record = new NodeReplacementValue(new NodeId("ryw-replacement"), "core", NodeReplacementPhase.JOINING, 0L);

        var seen = readAtResolution(() -> commit(leaderTransaction(key, record)), () -> index.recordFor(ORIGINAL));

        assertThat(seen).isEqualTo(Option.some(record));
    }

    /// S12: UpgradeRunWiring reads the upgrade-run index its handler fills (the WorkerHierarchyAssembly route).
    @Test
    void upgradeRunIndex_readsTheRunWhenTheTransactionResolves() {
        var index = UpgradeRunIndex.upgradeRunIndex();
        routeOn(UpgradeRunKey.class,
                (ValuePut<UpgradeRunKey, UpgradeRunValue> put) -> index.put(put.cause()
                                                                               .value()));
        commitLeader();
        var run = new UpgradeRunValue("2.0.0", List.of(SELF), 0, "", UpgradeRunState.RUNNING, UpgradeStop.NONE, "", 1L, 1L, 1L);

        var seen = readAtResolution(() -> commit(leaderTransaction(UpgradeRunKey.INSTANCE, run)), index::run);

        assertThat(seen).isEqualTo(Option.some(run));
    }

    /// S13: the incarnation stamp is read from the mirror its route entries keep current.
    @Test
    void observedIncarnation_readsTheNewIncarnationWhenTheWriteResolves() {
        var mirror = ObservedIncarnation.observedIncarnation(0L);
        mirror.routeEntries()
              .forEach(this::register);

        var seen = readAtResolution(() -> commit(new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                                     ClusterIncarnationValue.clusterIncarnationValue("L2",
                                                                                                                     9,
                                                                                                                     "id-L2-9"))),
                                    mirror::current);

        assertThat(seen).isEqualTo(9L);
    }

    /// Holds the dispatcher before the write's batch, starts the write, records what `read` returns when the write's
    /// promise resolves, releases the hold after the batch reached it, and returns the recorded value.
    private <T> Object readAtResolution(Supplier<Promise<?>> write, Supplier<T> read) {
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

    private static void settle(Promise<?> committed) {
        assertThat(committed.await(timeSpan(10).seconds())
                            .isSuccess()).isTrue();
    }

    private void commitLeader() {
        settle(commit(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KVCommand leaderTransaction(AetherKey key, AetherValue value) {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                       "ryw-" + key,
                                                                       LEADER,
                                                                       List.of(),
                                                                       List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                                                              Option.none(),
                                                                                                                              Option.some(value))));
    }

    /// Routes every `ValuePut` whose key is a `keyType` to `handler`, as the node's KV notification router does.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <K> void routeOn(Class<K> keyType, Consumer<? extends ValuePut> handler) {
        router.addRoute(ValuePut.class, (ValuePut put) -> routeIf(keyType, (Consumer) handler, put));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void routeIf(Class<?> keyType, Consumer handler, ValuePut put) {
        if (keyType.isInstance(put.cause().key())) {
            handler.accept(put);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(MessageRouter.Entry<?> entry) {
        entry.entries()
             .forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last()));
    }

    /// A node whose `apply` is the engine's commit path: apply the batch through the store (which enqueues its
    /// notifications), then park the promise until the batch is delivered.
    @SuppressWarnings("unchecked")
    private RabiaNode<KVCommand<AetherKey>> committingNode() {
        RabiaNode<KVCommand<AetherKey>> node = Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList()))
               .thenAnswer(invocation -> commitAll(invocation.getArgument(0)));

        return node;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Promise<?> commit(KVCommand command) {
        return commitAll(List.of(command));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Promise<List<Object>> commitAll(List commands) {
        var promise = Promise.<List<Object>> promise();
        List<Object> results = store.process(store.createBatch(commands));

        store.completeAfterDelivery(promise, results);

        return promise;
    }
}
