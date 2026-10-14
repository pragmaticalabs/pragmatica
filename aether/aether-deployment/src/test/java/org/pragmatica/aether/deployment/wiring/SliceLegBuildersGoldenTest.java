// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentContext;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentState;
import org.pragmatica.aether.deployment.node.machine.LegCommand;
import org.pragmatica.aether.deployment.node.machine.LegCommand.LegKind;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.FakeSliceStore;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// W-30, the builder-drift golden (phase-3 design 4.3, L3): for the same slice, configuration and committed state, the commands the adapter's
/// builders produce equal the commands the LEGACY `NodeDeploymentState` builders submit. The legacy side is the real private builder methods of
/// `NodeDeploymentState.Active`, driven by reflection (the same bridge the sibling suites use), with a recording cluster that captures what the
/// legacy chain would have sent. When the rc5 L-03 fix lands in the legacy builders only, this test goes red.
class SliceLegBuildersGoldenTest {
    private static final Artifact ARTIFACT = ORDERS;
    private static final Artifact BLUEPRINT = Artifact.artifact("org.example:orders-app:1.0.0").unwrap();
    private static final long CLOCK = 1_760_000_000_000L;

    private static final String TOML = """
        [topics.audit]
        topic_name = "audit-events"

        [streams.orders]
        partitions = 4

        [scheduling.sweep]
        interval = "60s"
        cron = ""
        execution_mode = "SINGLE"

        [scheduling.nightly]
        cron = "0 0 * * *"
        execution_mode = "single"
        """;

    private final KVStore<AetherKey, AetherValue> kv = WiringFixtures.store();
    private final FakeSliceStore sliceStore = new FakeSliceStore();
    private final StubCluster cluster = new StubCluster();
    private final List<OperatorWarning> legacyWarnings = new CopyOnWriteArrayList<>();
    private final List<OperatorWarning> adapterWarnings = new CopyOnWriteArrayList<>();
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;
    private SliceLegBuilders builders;

    @BeforeEach
    void setUp() {
        ConfigService.setInstance(realBinderOver(TOML));
        builders = newBuilders();
        var holder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory = fsm -> context(fsm, holder);

        harness = FsmTestHarness.harness("golden-" + System.nanoTime(), factory);
        holder.get().setOperatorWarningSink(OperatorWarningSink.handingOffTo(legacyWarnings::add));
        harness.dispatch(new QuorumEstablished());
    }

    @AfterEach
    void tearDown() {
        ConfigService.clear();
    }

    private SliceLegBuilders newBuilders() {
        return new SliceLegBuilders(new SliceLegBuilders.Sources(SELF, sliceStore, kv, () -> cluster, () -> CLOCK, () -> OperatorWarningSink.handingOffTo(adapterWarnings::add)));
    }

    private NodeDeploymentState context(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm, AtomicReference<NodeDeploymentContext> holder) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                sliceStore,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                kv,
                                                new RuntimeFixtures.RecordingInvocationHandler(),
                                                MessageRouter.mutable(),
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis(),
                                                () -> CLOCK,
                                                Option::none);

        holder.set(context);

        return context.dormant();
    }

    private static ConfigService realBinderOver(String toml) {
        return ProviderBasedConfigService.providerBasedConfigService(ConfigurationProvider.builder().withSource(TomlConfigSource.tomlConfigSource(toml).unwrap()).build());
    }

    // -- the legacy side ---------------------------------------------------------------------------------------------------
    private NodeDeploymentState.Active legacyState() {
        return (NodeDeploymentState.Active) harness.state();
    }

    /// What the legacy builder `method` submits for `slice`: the commands of every `apply` it made, in order.
    private List<KVCommand<AetherKey>> legacyCommands(String method, Slice slice) throws Exception {
        cluster.applied.clear();
        Method builder = NodeDeploymentState.Active.class.getDeclaredMethod(method, Artifact.class, Slice.class);

        builder.setAccessible(true);
        ((Promise<?>) builder.invoke(legacyState(), ARTIFACT, slice)).await();

        return cluster.applied.stream().flatMap(List::stream).toList();
    }

    private Promise<?> legacyPublish(String method, Slice slice) throws Exception {
        Method builder = NodeDeploymentState.Active.class.getDeclaredMethod(method, Artifact.class, Slice.class);

        builder.setAccessible(true);
        cluster.applied.clear();

        return ((Promise<?>) builder.invoke(legacyState(), ARTIFACT, slice));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<KVCommand<AetherKey>> legacyStreamRefs(Slice slice) throws Exception {
        sliceStore.withLoaded(ARTIFACT, slice);
        var action = Class.forName(NodeDeploymentState.Active.class.getName() + "$RefcountAction");
        Method builder = NodeDeploymentState.Active.class.getDeclaredMethod("buildStreamRefCommands", SliceNodeKey.class, action);

        builder.setAccessible(true);

        return (List<KVCommand<AetherKey>>) builder.invoke(legacyState(), new SliceNodeKey(ARTIFACT, SELF), Enum.valueOf((Class<Enum>) action, "ACQUIRE"));
    }

    // -- the adapter side --------------------------------------------------------------------------------------------------
    private List<KVCommand<AetherKey>> legs(Slice slice, LegKind kind) {
        return builders.legs(ARTIFACT, slice).unwrap().stream().filter(leg -> leg.kind() == kind).map(LegCommand::command).toList();
    }

    /// The operator-warning sink hands warnings to a drain thread, so they arrive after the call that raised them.
    private void awaitWarnings(int count) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> adapterWarnings.size() >= count && legacyWarnings.size() >= count);
    }

    private static List<String> warningLines(List<OperatorWarning> warnings) {
        return warnings.stream().map(w -> w.code() + "|" + w.subject() + "|" + w.message()).toList();
    }

    private void owned(BlueprintId blueprint) {
        kv.process(kv.createBatch(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
                                                              SliceTargetValue.sliceTargetValue(ARTIFACT.version(), 1, Option.some(blueprint))))));
    }

    // -- the golden comparisons ---------------------------------------------------------------------------------------------
    @Test
    void topicSubscriptions_equalTheLegacyBuilders_underTheOwningBlueprintsNamespace() throws Exception {
        owned(BlueprintId.blueprintId(BLUEPRINT));
        var slice = new RuntimeFixtures.TopicSlice();

        var legacy = legacyCommands("doPublishTopicSubscriptions", slice);
        var adapter = legs(slice, LegKind.TOPIC_SUBSCRIPTION);

        assertThat(legacy).as("the legacy builders resolved both subscriptions, so the comparison is not vacuous").hasSize(2);
        assertThat(adapter).isEqualTo(legacy);
        assertThat(((AetherKey.TopicSubscriptionKey) ((KVCommand.Put<?, ?>) adapter.getFirst()).key()).nodeId()).as("#1448: scoped to this node").isEqualTo(SELF);
    }

    @Test
    void streamRegistrations_equalTheLegacyBuilders_andRaiseTheSameWarnings() throws Exception {
        var slice = new RuntimeFixtures.StreamSlice();

        var legacy = legacyCommands("doPublishStreamSubscriptions", slice);
        var adapter = legs(slice, LegKind.STREAM_REGISTRATION);

        assertThat(legacy).as("the declared consumer registered, the ghost one did not").hasSize(1);
        assertThat(adapter).isEqualTo(legacy);
        awaitWarnings(1);
        assertThat(warningLines(adapterWarnings)).isNotEmpty().isEqualTo(warningLines(legacyWarnings));
        assertThat(adapterWarnings.getFirst().message()).contains("could NOT be registered").contains("streams.ghost");
    }

    @Test
    void aStreamConsumerThatRegistersAfterBeingRaised_raisesTheResolvedWarning_likeLegacy() throws Exception {
        var slice = new RuntimeFixtures.StreamSlice();

        legacyCommands("doPublishStreamSubscriptions", slice);
        legs(slice, LegKind.STREAM_REGISTRATION);
        ConfigService.setInstance(realBinderOver(TOML + "\n[streams.ghost]\npartitions = 2\n"));
        legacyCommands("doPublishStreamSubscriptions", slice);
        legs(slice, LegKind.STREAM_REGISTRATION);

        awaitWarnings(2);
        assertThat(warningLines(adapterWarnings)).hasSize(2).isEqualTo(warningLines(legacyWarnings));
    }

    @Test
    void scheduledTasks_equalTheLegacyBuilders() throws Exception {
        var slice = new RuntimeFixtures.ScheduledSlice();

        var legacy = legacyCommands("doPublishScheduledTasks", slice);
        var adapter = legs(slice, LegKind.SCHEDULED_TASK);

        assertThat(legacy).hasSize(2);
        assertThat(adapter).isEqualTo(legacy);
    }

    @Test
    void anInvalidSchedule_failsWithTheLegacyMessage() throws Exception {
        ConfigService.setInstance(realBinderOver("""
                                                 [scheduling.sweep]
                                                 interval = "every now and then"
                                                 [scheduling.nightly]
                                                 cron = "nonsense"
                                                 """));
        var slice = new RuntimeFixtures.ScheduledSlice();

        var legacy = legacyPublish("doPublishScheduledTasks", slice);
        var legacyFailure = legacy.await().fold(cause -> cause.message(), _ -> "no failure");
        var adapterFailure = builders.legs(ARTIFACT, slice).fold(cause -> cause.message(), _ -> "no failure");

        assertThat(legacyFailure).contains("every now and then").contains("nonsense");
        assertThat(adapterFailure).isEqualTo(legacyFailure);
    }

    /// The legs ride in one ACTIVE batch; their order is the legacy chain's (topics, then streams, then scheduled tasks), not the manifest's.
    @Test
    void theLegsOfAMixedSlice_areInTheLegacyChainsOrder() throws Exception {
        var slice = new RuntimeFixtures.MixedSlice();
        var expected = new ArrayList<KVCommand<AetherKey>>();

        expected.addAll(legacyCommands("doPublishTopicSubscriptions", slice));
        expected.addAll(legacyCommands("doPublishStreamSubscriptions", slice));
        expected.addAll(legacyCommands("doPublishScheduledTasks", slice));

        var adapter = builders.legs(ARTIFACT, slice).unwrap().stream().map(LegCommand::command).toList();

        assertThat(expected).hasSize(3);
        assertThat(adapter).isEqualTo(expected);
        assertThat(builders.legs(ARTIFACT, slice).unwrap()).extracting(LegCommand::kind)
                                                          .containsExactly(LegKind.TOPIC_SUBSCRIPTION, LegKind.STREAM_REGISTRATION, LegKind.SCHEDULED_TASK);
    }

    @Test
    void streamReferences_equalTheLegacyAcquireCommands() throws Exception {
        var blueprint = BlueprintId.blueprintId(BLUEPRINT);
        var events = ResourceAddress.resourceAddress("ticketing", "events", "1.0.0").unwrap();
        var audit = ResourceAddress.resourceAddress("ticketing", "audit", "1.0.0").unwrap();

        owned(blueprint);
        kv.process(kv.createBatch(List.of(new KVCommand.Put<>(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(blueprint),
                                                              BlueprintStreamBindingsValue.blueprintStreamBindingsValue(List.of(BlueprintStreamBindingsValue.NamedAddress.namedAddress("events", events),
                                                                                                                                BlueprintStreamBindingsValue.NamedAddress.namedAddress("audit", audit)))))));
        var slice = new RuntimeFixtures.StreamRoleSlice();

        var legacy = legacyStreamRefs(slice);
        var adapter = builders.streamReferences(ARTIFACT, slice);

        assertThat(legacy).as("two declared roles, two ACQUIRE commands").hasSize(2);
        assertThat(adapter).extracting(LegCommand::kind).containsOnly(LegKind.STREAM_REF);
        assertThat(adapter.stream().map(LegCommand::command).toList()).isEqualTo(legacy);
    }

    @Test
    void streamReferences_areEmpty_whenTheSliceHasNoOwningBlueprint_likeLegacy() throws Exception {
        var slice = new RuntimeFixtures.StreamRoleSlice();

        assertThat(legacyStreamRefs(slice)).isEmpty();
        assertThat(builders.streamReferences(ARTIFACT, slice)).isEmpty();
    }

    @Test
    void aSliceWithoutManifests_hasNoLegs_andNoReferences() throws Exception {
        var slice = new RuntimeFixtures.BareSlice();

        assertThat(builders.legs(ARTIFACT, slice).unwrap()).isEmpty();
        assertThat(builders.streamReferences(ARTIFACT, slice)).isEmpty();
        assertThat(legacyCommands("doPublishTopicSubscriptions", slice)).isEmpty();
    }
}
