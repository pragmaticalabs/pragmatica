// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmTimings;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.FakeSliceStore;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingInvocationHandler;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingRoutePublisher;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskPauseKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// The teardown chains end to end (phase-3 design 4.3, spec N13, N16, N17, NI-5; W-09 "step map wrong" for the steps PR C left unwired): the real
/// `NodeFsm`, interpreter, `LocalOpExecutor` and `SliceRuntimeAdapter` over a scripted slice store, the real `KVStore` applier and a recording
/// invocation handler and route publisher. Nothing in this class is a double of anything the FSM2 engine itself is made of.
class TeardownChainTest {
    private static final String TOML = """
        [topics.orders]
        topic_name = "orders"

        [streams.orders]
        partitions = 4

        [scheduling.sweep]
        interval = "60s"
        cron = ""
        execution_mode = "SINGLE"
        """;

    private final FakeSliceStore sliceStore = new FakeSliceStore();
    private final RecordingInvocationHandler bridge = new RecordingInvocationHandler();
    private final RecordingRoutePublisher publisher = new RecordingRoutePublisher();
    private final ConfigNotificationManager configNotifications = ConfigNotificationManager.configNotificationManager();
    private final List<String> counted = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        SliceRuntimeAdapterTest.ConfigProbe.calls.clear();
        ConfigService.setInstance(ProviderBasedConfigService.providerBasedConfigService(ConfigurationProvider.builder()
                                                                                                         .withSource(TomlConfigSource.tomlConfigSource(TOML).unwrap())
                                                                                                         .build()));
    }

    @AfterEach
    void tearDown() {
        ConfigService.clear();
        configNotifications.shutdown();
    }

    /// The rig over the real executor and adapter. The adapter reads the rig's store and applies through the rig's cluster, so it is built after
    /// the rig and bound late.
    private NodeRig rigWith(Slice slice) {
        var late = new AtomicReference<LocalOps>();
        var rig = new NodeRig(NodeFsmTimings.nodeFsmTimings(), true, new LocalOps() {
            @Override
            public void run(NodeFsmEffect.LocalOp op, LongSupplier currentIncarnation, Consumer<NodeFsmInput.LocalOpSettled> settled) {
                late.get().run(op, currentIncarnation, settled);
            }

            @Override
            public void close() {
                late.get().close();
            }
        });
        var adapter = SliceRuntimeAdapter.sliceRuntimeAdapter(new SliceRuntimeAdapter.Collaborators(SELF,
                                                                                                   sliceStore,
                                                                                                   bridge,
                                                                                                   SliceCodec.sliceCodec(List.of()),
                                                                                                   Option.some(publisher),
                                                                                                   Option.some(RuntimeFixtures.FACADE),
                                                                                                   configNotifications,
                                                                                                   rig.store,
                                                                                                   () -> rig.cluster,
                                                                                                   () -> BOOT_INCARNATION,
                                                                                                   () -> OperatorWarningSink.logOnly()));

        late.set(LocalOpExecutor.localOpExecutor(adapter,
                                                 new ManualTimerService(),
                                                 NodeFsmTimings.nodeFsmTimings(),
                                                 (step, result) -> counted.add(step + ":" + result)));
        sliceStore.withLoaded(ORDERS, slice);

        return rig;
    }

    private long keys(NodeRig rig, Class<? extends AetherKey> type) {
        Map<?, ?> committed = rig.store.snapshot();

        return committed.keySet().stream().filter(type::isInstance).count();
    }

    private void awaitActiveWithLegs(NodeRig rig) {
        rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
        rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
        rig.awaitThat(() -> keys(rig, ScheduledTaskKey.class) == 1);
    }

    /// The observed Put of `state` and the commands that rode with it, as the node submitted them.
    private static List<KVCommand<AetherKey>> batchOf(NodeRig rig, SliceObservedState state) {
        return rig.cluster.applied.stream()
                                  .filter(commands -> commands.getFirst() instanceof KVCommand.Put<AetherKey, ?> put
                                                      && put.value() instanceof SliceObservedValue row
                                                      && row.state() == state)
                                  .findFirst()
                                  .orElseThrow(() -> new AssertionError("no batch wrote " + state + "; wrote " + rig.cluster.applied.size() + " batches"));
    }

    private static boolean removes(List<KVCommand<AetherKey>> batch, Class<? extends AetherKey> type) {
        return batch.stream().anyMatch(command -> command instanceof KVCommand.Remove<AetherKey> remove && type.isInstance(remove.key()));
    }

    /// The leader's removal of the goal: a `LeaderTransaction` whose only mutation takes the committed desired value to nothing.
    private static KVCommand.LeaderTransaction<AetherKey, AetherValue> removalOfTheGoal(NodeRig rig) {
        var key = desiredKey(ORDERS);

        return new KVCommand.LeaderTransaction<>(key,
                                                 "remove-goal-" + rig.applier.slot(),
                                                 LEADER,
                                                 List.of(),
                                                 List.of(new KVCommand.Mutation<>(key, rig.store.get(key), Option.<AetherValue> none())));
    }

    // -- N13 ---------------------------------------------------------------------------------------------------------------------------
    @Test
    void deactivation_writesTheRemovesWithDeactivating_thenSeversAndDeactivates_thenWritesLoaded() {
        try (var rig = rigWith(new RuntimeFixtures.MixedSlice())) {
            awaitActiveWithLegs(rig);
            assertThat(keys(rig, TopicSubscriptionKey.class)).as("the ACTIVE batch carried the legs").isEqualTo(1);
            assertThat(keys(rig, StreamRegistrationKey.class)).isEqualTo(1);

            rig.goal(ORDERS, SliceGoal.LOADED);
            rig.awaitState(ORDERS, SliceObservedState.LOADED);

            var deactivating = batchOf(rig, SliceObservedState.DEACTIVATING);

            assertThat(removes(deactivating, TopicSubscriptionKey.class)).as("N13: removes ride with W DEACTIVATING").isTrue();
            assertThat(removes(deactivating, StreamRegistrationKey.class)).isTrue();
            assertThat(removes(deactivating, ScheduledTaskKey.class)).isTrue();
            assertThat(removes(deactivating, ScheduledTaskPauseKey.class)).isTrue();
            assertThat(keys(rig, TopicSubscriptionKey.class) + keys(rig, StreamRegistrationKey.class) + keys(rig, ScheduledTaskKey.class)).as("no leg left behind").isZero();
            assertThat(sliceStore.calls).contains("deactivate " + ORDERS).doesNotContain("unload " + ORDERS);
            assertThat(bridge.calls).contains("unregister " + ORDERS);
            assertThat(publisher.calls).contains("unpublish " + ORDERS);
            assertThat(counted).contains("PREPARE_TEARDOWN:done", "DEACTIVATE:done", "UNPUBLISH:done").doesNotContain("PREPARE_TEARDOWN:failed", "DEACTIVATE:failed");
        }
    }

    @Test
    void deactivation_ofASliceTheStoreCannotDeactivate_writesFailed_notLoaded() {
        try (var rig = rigWith(new RuntimeFixtures.MixedSlice())) {
            awaitActiveWithLegs(rig);
            sliceStore.deactivateFailure = org.pragmatica.lang.utils.Causes.cause("stop failed");

            rig.goal(ORDERS, SliceGoal.LOADED);
            rig.awaitState(ORDERS, SliceObservedState.FAILED);

            assertThat(counted).contains("DEACTIVATE:failed");
        }
    }

    // -- N16 ---------------------------------------------------------------------------------------------------------------------------
    @Test
    void unload_writesTheRemovesWithUnloading_thenUnloads_thenWritesUnloaded() {
        try (var rig = rigWith(new RuntimeFixtures.MixedSlice())) {
            awaitActiveWithLegs(rig);

            rig.goal(ORDERS, SliceGoal.UNLOADED);
            rig.awaitState(ORDERS, SliceObservedState.UNLOADED);

            var unloading = batchOf(rig, SliceObservedState.UNLOADING);

            assertThat(removes(unloading, TopicSubscriptionKey.class)).as("N16: removes ride with W UNLOADING").isTrue();
            assertThat(removes(unloading, ScheduledTaskKey.class)).isTrue();
            assertThat(keys(rig, TopicSubscriptionKey.class) + keys(rig, StreamRegistrationKey.class) + keys(rig, ScheduledTaskKey.class)).as("no leg left behind").isZero();
            assertThat(sliceStore.calls).contains("unload " + ORDERS);
            assertThat(sliceStore.loaded()).as("the store no longer holds the slice").isEmpty();
            assertThat(counted).contains("PREPARE_TEARDOWN:done", "UNLOAD:done");
        }
    }

    /// B-147: a failed unload still writes UNLOADED. The store takes the entry out before it can fail (`SliceStoreTest`), so the FSM's "not loaded"
    /// is true of the store too.
    @Test
    void anUnloadThatFails_stillWritesUnloaded_andTheStoreNoLongerHoldsTheSlice() {
        try (var rig = rigWith(new RuntimeFixtures.MixedSlice())) {
            awaitActiveWithLegs(rig);
            sliceStore.unloadFailure = org.pragmatica.lang.utils.Causes.cause("class loader would not close");

            rig.goal(ORDERS, SliceGoal.UNLOADED);
            rig.awaitState(ORDERS, SliceObservedState.UNLOADED);

            assertThat(counted).contains("UNLOAD:failed");
            assertThat(sliceStore.loaded()).isEmpty();
        }
    }

    // -- N17 ---------------------------------------------------------------------------------------------------------------------------
    @Test
    void aRemovedGoal_ofALoadedSlice_forcesItsCleanup_nodeScopedLegsReleased_clusterScopedKept() {
        try (var rig = rigWith(new RuntimeFixtures.MixedSlice())) {
            awaitActiveWithLegs(rig);

            rig.applier.commit(List.of(removalOfTheGoal(rig)));

            rig.awaitThat(() -> sliceStore.calls.contains("unload " + ORDERS));
            rig.awaitThat(() -> keys(rig, TopicSubscriptionKey.class) == 0);
            assertThat(sliceStore.calls).containsSubsequence("deactivate " + ORDERS, "unload " + ORDERS);
            assertThat(bridge.calls).contains("unregister " + ORDERS);
            assertThat(publisher.calls).contains("unpublish " + ORDERS);
            assertThat(keys(rig, StreamRegistrationKey.class) + keys(rig, ScheduledTaskKey.class)).as("last-replica knowledge lives in the FSM: not guessed here").isEqualTo(2);
            assertThat(rig.stateOf(ORDERS).isPresent()).as("forced cleanup writes no row (R-1.6.4): the leader owns it").isTrue();
            rig.awaitThat(() -> counted.contains("FORCED_CLEANUP:done"));
        }
    }

    // -- NI-5 --------------------------------------------------------------------------------------------------------------------------
    @Test
    void aConfigChange_reachesTheRegisteredSlice_throughNotifyConfig() {
        try (var rig = rigWith(new RuntimeFixtures.ConfigSlice())) {
            rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
            rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
            await().atMost(Duration.ofSeconds(5)).until(() -> SliceRuntimeAdapterTest.ConfigProbe.calls.contains("database"));
            SliceRuntimeAdapterTest.ConfigProbe.calls.clear();

            rig.interpreter.deliver(new NodeFsmInput.ConfigChanged("database.pool"));

            await().atMost(Duration.ofSeconds(5)).until(() -> SliceRuntimeAdapterTest.ConfigProbe.calls.contains("database"));
            rig.awaitThat(() -> counted.contains("NOTIFY_CONFIG:done"));
        }
    }

    @Test
    void aConfigChange_afterTheSliceWasDeactivated_reachesNobody() {
        try (var rig = rigWith(new RuntimeFixtures.ConfigSlice())) {
            rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
            rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
            await().atMost(Duration.ofSeconds(5)).until(() -> SliceRuntimeAdapterTest.ConfigProbe.calls.contains("database"));
            rig.goal(ORDERS, SliceGoal.LOADED);
            rig.awaitState(ORDERS, SliceObservedState.LOADED);
            SliceRuntimeAdapterTest.ConfigProbe.calls.clear();

            rig.interpreter.deliver(new NodeFsmInput.ConfigChanged("database.pool"));
            rig.awaitThat(() -> counted.contains("NOTIFY_CONFIG:done"));

            await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(SliceRuntimeAdapterTest.ConfigProbe.calls::isEmpty);
        }
    }
}
