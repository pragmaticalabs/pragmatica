// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.node.machine.LegCommand;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.FakeSliceStore;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingInvocationHandler;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingRoutePublisher;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// The node FSM's local steps against the slice runtime's collaborators (phase-3 design 4.3 step map, W-09's "step map wrong"): each step does
/// its effect, reports `Done` or `Failed(cause)` with the collaborator's own cause, and the steps outside the tracer's path fail loudly. A
/// mutation probe per step (the effect deleted) is the evidence that every case here can go red.
class SliceRuntimeAdapterTest {
    /// The factory a `config-update` manifest names; it records the sections it was told about.
    public static final class ConfigProbe {
        static final List<String> calls = new CopyOnWriteArrayList<>();

        public static void notifyConfigUpdate(Object sliceInstance, String section, ConfigFacade config) {
            calls.add(section);
        }
    }

    private static final String TOML = """
        [scheduling.sweep]
        interval = "60s"
        """;

    private final FakeSliceStore store = new FakeSliceStore();
    private final RecordingInvocationHandler bridge = new RecordingInvocationHandler();
    private final RecordingRoutePublisher publisher = new RecordingRoutePublisher();
    private final ConfigNotificationManager configNotifications = ConfigNotificationManager.configNotificationManager();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final KVStore<AetherKey, AetherValue> kv = WiringFixtures.store();

    @BeforeEach
    void setUp() {
        ConfigProbe.calls.clear();
        ConfigService.setInstance(ProviderBasedConfigService.providerBasedConfigService(ConfigurationProvider.builder()
                                                                                                         .withSource(TomlConfigSource.tomlConfigSource(TOML).unwrap())
                                                                                                         .build()));
    }

    @AfterEach
    void tearDown() {
        ConfigService.clear();
        configNotifications.shutdown();
    }

    private SliceRuntimeAdapter adapter(boolean withPublisher) {
        return SliceRuntimeAdapter.sliceRuntimeAdapter(new SliceRuntimeAdapter.Collaborators(SELF,
                                                                                            store,
                                                                                            bridge,
                                                                                            SliceCodec.sliceCodec(List.of()),
                                                                                            withPublisher
                                                                                            ? Option.some(publisher)
                                                                                            : Option.none(),
                                                                                            withPublisher
                                                                                            ? Option.some(RuntimeFixtures.FACADE)
                                                                                            : Option.none(),
                                                                                            configNotifications,
                                                                                            kv,
                                                                                            () -> new StubCluster(),
                                                                                            () -> BOOT_INCARNATION,
                                                                                            () -> OperatorWarningSink.handingOffTo(warnings::add)));
    }

    private LocalOutcome run(LocalStep step) {
        return adapter(true).run(new NodeFsmEffect.LocalOp(1, 7, Option.some(ORDERS), step, 0, Option.none())).await().unwrap();
    }

    private LocalOutcome runWithoutPublisher(LocalStep step) {
        return adapter(false).run(new NodeFsmEffect.LocalOp(1, 7, Option.some(ORDERS), step, 0, Option.none())).await().unwrap();
    }

    private static String messageOf(LocalOutcome outcome) {
        return ((LocalOutcome.Failed) outcome).cause().message();
    }

    // -- LOAD, ACTIVATE ------------------------------------------------------------------------------------------------
    @Test
    void load_loadsTheSlice_andIsDone() {
        assertThat(run(LocalStep.LOAD)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(store.calls).containsExactly("load " + ORDERS);
    }

    @Test
    void load_failsWithTheStoresOwnCause() {
        var cause = Causes.cause("artifact not found");

        store.loadFailure = cause;

        var outcome = run(LocalStep.LOAD);

        assertThat(outcome).isEqualTo(LocalOutcome.failed(cause));
    }

    @Test
    void activate_activatesTheSlice_andIsDone() {
        assertThat(run(LocalStep.ACTIVATE)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(store.calls).containsExactly("activate " + ORDERS);
    }

    @Test
    void activate_failsWithTheStoresOwnCause() {
        var cause = Causes.cause("start failed");

        store.activateFailure = cause;

        assertThat(run(LocalStep.ACTIVATE)).isEqualTo(LocalOutcome.failed(cause));
    }

    // -- REGISTER_BRIDGE ----------------------------------------------------------------------------------------------
    @Test
    void registerBridge_registersTheLoadedSliceWithTheInvocationHandler() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.REGISTER_BRIDGE)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(bridge.calls).containsExactly("register " + ORDERS);
    }

    @Test
    void registerBridge_failsTyped_whenTheSliceIsNotInTheStore() {
        var outcome = (LocalOutcome.Failed) run(LocalStep.REGISTER_BRIDGE);

        assertThat(outcome.cause()).as("typed Intermittent, so the leader retries instead of condemning the artifact (#916)").isInstanceOf(SliceLoadingFailure.Intermittent.SliceNotInStore.class);
        assertThat(bridge.calls).isEmpty();
    }

    // -- BUILD_LEGS ---------------------------------------------------------------------------------------------------
    @Test
    void buildLegs_returnsTheLegsOfTheSlice() {
        store.withLoaded(ORDERS, new RuntimeFixtures.TopicSlice());

        var done = (LocalOutcome.Done) run(LocalStep.BUILD_LEGS);

        assertThat(done.commands()).extracting(LegCommand::kind).contains(LegCommand.LegKind.TOPIC_SUBSCRIPTION);
    }

    @Test
    void buildLegs_isDoneWithNoLegs_forASliceThatDeclaresNone() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(((LocalOutcome.Done) run(LocalStep.BUILD_LEGS)).commands()).isEmpty();
    }

    @Test
    void buildLegs_isDoneWithNoLegs_whenTheSliceIsNotInTheStore() {
        assertThat(((LocalOutcome.Done) run(LocalStep.BUILD_LEGS)).commands()).isEmpty();
    }

    @Test
    void buildLegs_failsTheStep_forAnInvalidScheduleString() {
        ConfigService.setInstance(ProviderBasedConfigService.providerBasedConfigService(ConfigurationProvider.builder()
                                                                                                         .withSource(TomlConfigSource.tomlConfigSource("""
                                                                                                                                                       [scheduling.sweep]
                                                                                                                                                       interval = "every now and then"
                                                                                                                                                       [scheduling.nightly]
                                                                                                                                                       cron = "0 0 * * *"
                                                                                                                                                       """).unwrap())
                                                                                                         .build()));
        store.withLoaded(ORDERS, new RuntimeFixtures.ScheduledSlice());

        var outcome = run(LocalStep.BUILD_LEGS);

        assertThat(messageOf(outcome)).contains("sweep").contains("every now and then");
    }

    // -- REGISTER_CONFIG ----------------------------------------------------------------------------------------------
    @Test
    void registerConfig_registersTheSliceForItsDeclaredSections_andNotifiesInitially() {
        store.withLoaded(ORDERS, new RuntimeFixtures.ConfigSlice());

        assertThat(run(LocalStep.REGISTER_CONFIG)).isInstanceOf(LocalOutcome.Done.class);

        await().atMost(Duration.ofSeconds(5)).until(() -> ConfigProbe.calls.contains("database"));
    }

    @Test
    void registerConfig_registersNothing_forASliceWithoutConfigUpdateSections() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.REGISTER_CONFIG)).isInstanceOf(LocalOutcome.Done.class);
        configNotifications.notifyChange("database.pool", artifact -> RuntimeFixtures.noConfig());

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(ConfigProbe.calls::isEmpty);
    }

    @Test
    void registerConfig_isDone_whenTheSliceIsNotInTheStore() {
        assertThat(run(LocalStep.REGISTER_CONFIG)).isInstanceOf(LocalOutcome.Done.class);
    }

    // -- PUBLISH_ROUTES -----------------------------------------------------------------------------------------------
    @Test
    void publishRoutes_publishesTheRoutesOfASliceThatServesHttp() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.PUBLISH_ROUTES)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(publisher.calls).containsExactly("publish " + ORDERS);
    }

    @Test
    void publishRoutes_publishesNothing_forASliceWithoutRoutes() {
        publisher.hasRoutes = false;
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.PUBLISH_ROUTES)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(publisher.calls).isEmpty();
    }

    @Test
    void publishRoutes_isDone_whenTheNodeHasNoRoutePublisher() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(runWithoutPublisher(LocalStep.PUBLISH_ROUTES)).isInstanceOf(LocalOutcome.Done.class);
    }

    @Test
    void publishRoutes_failsTheChain_withThePublishersCause() {
        var cause = Causes.cause("route collision");

        publisher.publishFailure = cause;
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.PUBLISH_ROUTES)).isEqualTo(LocalOutcome.failed(cause));
    }

    // -- DESCRIBE_ACTIVE ----------------------------------------------------------------------------------------------
    @Test
    void describeActive_carriesTheEndpointMethodNames() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        var done = (LocalOutcome.Done) run(LocalStep.DESCRIBE_ACTIVE);

        assertThat(done.methods()).containsExactly("execute", "handle");
        assertThat(done.commands()).as("no stream roles, no references").isEmpty();
    }

    @Test
    void describeActive_isPlainDone_whenTheSliceIsNotInTheStore() {
        var done = (LocalOutcome.Done) run(LocalStep.DESCRIBE_ACTIVE);

        assertThat(done.methods()).isEmpty();
    }

    // -- UNPUBLISH, SUSPEND, EVICT -----------------------------------------------------------------------------------------
    @Test
    void unpublish_removesRoutesBridgeAndConfigRegistration() {
        store.withLoaded(ORDERS, new RuntimeFixtures.ConfigSlice());
        run(LocalStep.REGISTER_CONFIG);
        await().atMost(Duration.ofSeconds(5)).until(() -> ConfigProbe.calls.contains("database"));
        ConfigProbe.calls.clear();

        assertThat(run(LocalStep.UNPUBLISH)).isInstanceOf(LocalOutcome.Done.class);

        assertThat(publisher.calls).containsExactly("unpublish " + ORDERS);
        assertThat(bridge.calls).containsExactly("unregister " + ORDERS);
        configNotifications.notifyChange("database.pool", artifact -> RuntimeFixtures.noConfig());
        // the registration is gone: a change to its section reaches nobody
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(ConfigProbe.calls::isEmpty);
    }

    @Test
    void unpublish_isDone_evenWhenThePublisherFails() {
        publisher.unpublishFailure = Causes.cause("registry unavailable");

        assertThat(run(LocalStep.UNPUBLISH)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(bridge.calls).as("the bridge is unregistered regardless").containsExactly("unregister " + ORDERS);
    }

    @Test
    void suspend_removesRoutesAndBridge_butKeepsTheConfigRegistration() {
        store.withLoaded(ORDERS, new RuntimeFixtures.ConfigSlice());
        run(LocalStep.REGISTER_CONFIG);
        await().atMost(Duration.ofSeconds(5)).until(() -> ConfigProbe.calls.contains("database"));
        ConfigProbe.calls.clear();

        assertThat(run(LocalStep.SUSPEND)).isInstanceOf(LocalOutcome.Done.class);

        assertThat(publisher.calls).containsExactly("unpublish " + ORDERS);
        assertThat(bridge.calls).containsExactly("unregister " + ORDERS);
        configNotifications.notifyChange("database.pool", artifact -> RuntimeFixtures.noConfig());
        await().atMost(Duration.ofSeconds(5)).until(() -> ConfigProbe.calls.contains("database"));
    }

    @Test
    void suspend_withoutAPublisher_stillUnregistersTheBridge() {
        assertThat(runWithoutPublisher(LocalStep.SUSPEND)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(bridge.calls).containsExactly("unregister " + ORDERS);
    }

    @Test
    void evict_dropsTheEntryAFailedLoadLeftBehind() {
        assertThat(run(LocalStep.EVICT)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(store.calls).containsExactly("unload " + ORDERS);
    }

    @Test
    void evict_leavesALoadedSliceAlone() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.EVICT)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(store.calls).as("a healthy slice is not unloaded by an eviction").isEmpty();
    }

    // -- PREPARE_TEARDOWN, DEACTIVATE, UNLOAD (N13, N16) ---------------------------------------------------------------------------
    @Test
    void prepareTeardown_returnsTheRemovesOfTheSlice_andTouchesNothing() {
        store.withLoaded(ORDERS, new RuntimeFixtures.MixedSlice());

        var done = (LocalOutcome.Done) run(LocalStep.PREPARE_TEARDOWN);

        assertThat(done.commands()).as("this node's TOML declares no [streams.orders], so the stream consumer never registered and has nothing to remove")
                                   .extracting(LegCommand::kind)
                                   .containsExactly(LegCommand.LegKind.TOPIC_SUBSCRIPTION, LegCommand.LegKind.SCHEDULED_TASK, LegCommand.LegKind.SCHEDULED_TASK);
        assertThat(done.commands()).extracting(leg -> leg.command()).allMatch(command -> command instanceof org.pragmatica.cluster.state.kvstore.KVCommand.Remove<?>);
        assertThat(store.calls).as("the step builds, it does not sever anything").isEmpty();
        assertThat(bridge.calls).isEmpty();
        assertThat(publisher.calls).isEmpty();
    }

    @Test
    void prepareTeardown_isDoneWithNoCommands_whenTheSliceIsNotInTheStore() {
        assertThat(((LocalOutcome.Done) run(LocalStep.PREPARE_TEARDOWN)).commands()).isEmpty();
    }

    @Test
    void deactivate_deactivatesTheSlice_andIsDone() {
        assertThat(run(LocalStep.DEACTIVATE)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(store.calls).containsExactly("deactivate " + ORDERS);
    }

    @Test
    void deactivate_failsWithTheStoresOwnCause() {
        var cause = Causes.cause("stop failed");

        store.deactivateFailure = cause;

        assertThat(run(LocalStep.DEACTIVATE)).isEqualTo(LocalOutcome.failed(cause));
    }

    @Test
    void unload_unloadsTheSlice_andIsDone() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.UNLOAD)).isInstanceOf(LocalOutcome.Done.class);
        assertThat(store.calls).containsExactly("unload " + ORDERS);
        assertThat(store.loaded()).isEmpty();
    }

    @Test
    void unload_failsWithTheStoresOwnCause() {
        var cause = Causes.cause("class loader would not close");

        store.unloadFailure = cause;

        assertThat(run(LocalStep.UNLOAD)).isEqualTo(LocalOutcome.failed(cause));
    }

    // -- FORCED_CLEANUP (N17, B-148, NT-73) ------------------------------------------------------------------------------------------
    private void journaled() {
        var journal = new CopyOnWriteArrayList<String>();

        store.journal = journal;
        bridge.journal = journal;
        publisher.journal = journal;
        this.journal = journal;
    }

    private List<String> journal = new CopyOnWriteArrayList<>();

    @Test
    void forcedCleanup_severesThenDeactivatesThenUnloads_inThatOrder() {
        journaled();
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.FORCED_CLEANUP)).isInstanceOf(LocalOutcome.Done.class);

        assertThat(journal).containsExactly("bridge: unregister " + ORDERS,
                                            "routes: unpublish " + ORDERS,
                                            "store: deactivate " + ORDERS,
                                            "store: unload " + ORDERS);
    }

    /// B-166 CHANGE, NT-73: the config registration does not survive a forced cleanup (legacy left it behind).
    @Test
    void forcedCleanup_unregistersTheConfigRegistration() {
        store.withLoaded(ORDERS, new RuntimeFixtures.ConfigSlice());
        run(LocalStep.REGISTER_CONFIG);
        await().atMost(Duration.ofSeconds(5)).until(() -> ConfigProbe.calls.contains("database"));
        ConfigProbe.calls.clear();

        assertThat(run(LocalStep.FORCED_CLEANUP)).isInstanceOf(LocalOutcome.Done.class);

        configNotifications.notifyChange("database.pool", artifact -> RuntimeFixtures.noConfig());
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(ConfigProbe.calls::isEmpty);
    }

    /// The node-scoped legs go with the instance: this node's topic subscription row (R-3.4.5: always removed) and its stream reference counts. The
    /// cluster-scoped ones (stream registration, scheduled task and its pause key) stay: whether this was the last replica is the FSM's knowledge
    /// (`Rows.hostedElsewhere`), and a forced cleanup carries no batch the FSM could filter.
    @Test
    void forcedCleanup_releasesTheNodeScopedLegs_andLeavesTheClusterScopedOnes() {
        store.withLoaded(ORDERS, new RuntimeFixtures.MixedSlice());
        var cluster = new StubCluster();

        adapterOver(cluster).run(new NodeFsmEffect.LocalOp(1, 0, Option.some(ORDERS), LocalStep.FORCED_CLEANUP, 0, Option.none())).await();

        assertThat(cluster.applied).hasSize(1);
        assertThat(cluster.applied.getFirst()).hasSize(1).allMatch(command -> command instanceof org.pragmatica.cluster.state.kvstore.KVCommand.Remove<?> remove
                                                                              && remove.key() instanceof AetherKey.TopicSubscriptionKey);
    }

    @Test
    void forcedCleanup_submitsNothing_forASliceWithoutNodeScopedLegs() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());
        var cluster = new StubCluster();

        adapterOver(cluster).run(new NodeFsmEffect.LocalOp(1, 0, Option.some(ORDERS), LocalStep.FORCED_CLEANUP, 0, Option.none())).await();

        assertThat(cluster.applied).as("an empty batch would be refused by the engine").isEmpty();
    }

    /// FER: a release the cluster refuses is logged; the slice is still taken down. The step is Done.
    @Test
    void forcedCleanup_stillTakesTheSliceDown_whenTheReleaseIsRefused() {
        store.withLoaded(ORDERS, new RuntimeFixtures.MixedSlice());
        var cluster = new StubCluster();

        cluster.script = _ -> Causes.cause("quorum paused").promise();

        try (var logs = LogCapture.capture(SliceRuntimeAdapter.class, Level.ALL, false)) {
            assertThat(adapterOver(cluster).run(new NodeFsmEffect.LocalOp(1, 0, Option.some(ORDERS), LocalStep.FORCED_CLEANUP, 0, Option.none())).await().unwrap()).isInstanceOf(LocalOutcome.Done.class);
            assertThat(store.calls).containsExactly("deactivate " + ORDERS, "unload " + ORDERS);
            assertThat(logs.entries()).as("the refusal is logged, not swallowed silently")
                                    .anySatisfy(line -> assertThat(line.message()).contains("Could not release the legs of " + ORDERS).contains("quorum paused"));
        }
    }

    /// Both steps of the removal failed: the step reports the deactivation's failure, the first one, and the unload still ran.
    @Test
    void forcedCleanup_whenBothTheDeactivationAndTheUnloadFail_reportsTheDeactivation() {
        var deactivation = Causes.cause("stop failed");

        store.deactivateFailure = deactivation;
        store.unloadFailure = Causes.cause("class loader would not close");
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.FORCED_CLEANUP)).isEqualTo(LocalOutcome.failed(deactivation));
        assertThat(store.calls).containsExactly("deactivate " + ORDERS, "unload " + ORDERS);
    }

    /// Which legs a forced cleanup may remove: the node-scoped ones (this node's topic subscription row, its stream reference counts), and none of
    /// the cluster-scoped ones (stream registration, scheduled task).
    @Test
    void nodeScoped_keepsTheTopicRowsAndTheReferenceCounts_andDropsTheClusterScopedLegs() {
        var topic = removalOf("topic");
        var reference = removalOf("reference");
        var registration = removalOf("registration");
        var task = removalOf("task");

        var kept = SliceRuntimeAdapter.nodeScoped(List.of(LegCommand.legCommand(LegCommand.LegKind.TOPIC_SUBSCRIPTION, topic),
                                                          LegCommand.legCommand(LegCommand.LegKind.STREAM_REGISTRATION, registration),
                                                          LegCommand.legCommand(LegCommand.LegKind.STREAM_REF, reference),
                                                          LegCommand.legCommand(LegCommand.LegKind.SCHEDULED_TASK, task)));

        assertThat(kept).containsExactly(topic, reference);
    }

    /// A deactivation that fails must not leave the slice in the store (the FSM has already recorded it as not loaded): the unload still runs, and the
    /// step reports the failure so `localops_total{step=FORCED_CLEANUP,result=failed}` counts it.
    @Test
    void forcedCleanup_stillUnloads_whenTheDeactivationFails_andReportsIt() {
        var cause = Causes.cause("stop failed");

        store.deactivateFailure = cause;
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        assertThat(run(LocalStep.FORCED_CLEANUP)).isEqualTo(LocalOutcome.failed(cause));
        assertThat(store.calls).containsExactly("deactivate " + ORDERS, "unload " + ORDERS);
    }

    @Test
    void forcedCleanup_reportsTheUnloadFailure() {
        var cause = Causes.cause("class loader would not close");

        store.unloadFailure = cause;

        assertThat(run(LocalStep.FORCED_CLEANUP)).isEqualTo(LocalOutcome.failed(cause));
    }

    private static KVCommand<AetherKey> removalOf(String name) {
        return new KVCommand.Remove<>(new AetherKey.SliceTargetKey(Artifact.artifact("org.example:" + name + ":1.0.0").unwrap().base()));
    }

    private SliceRuntimeAdapter adapterOver(StubCluster cluster) {
        return SliceRuntimeAdapter.sliceRuntimeAdapter(new SliceRuntimeAdapter.Collaborators(SELF,
                                                                                            store,
                                                                                            bridge,
                                                                                            SliceCodec.sliceCodec(List.of()),
                                                                                            Option.some(publisher),
                                                                                            Option.some(RuntimeFixtures.FACADE),
                                                                                            configNotifications,
                                                                                            kv,
                                                                                            () -> cluster,
                                                                                            () -> BOOT_INCARNATION,
                                                                                            () -> OperatorWarningSink.handingOffTo(warnings::add)));
    }

    // -- NOTIFY_CONFIG (NI-5, B5) -----------------------------------------------------------------------------------------------------
    private LocalOutcome notifyConfig(Option<String> key) {
        return adapter(true).run(new NodeFsmEffect.LocalOp(1, 0, Option.none(), LocalStep.NOTIFY_CONFIG, 0, key)).await().unwrap();
    }

    @Test
    void notifyConfig_pushesAChangedKeyToTheSlicesRegisteredForItsSection() {
        store.withLoaded(ORDERS, new RuntimeFixtures.ConfigSlice());
        run(LocalStep.REGISTER_CONFIG);
        await().atMost(Duration.ofSeconds(5)).until(() -> ConfigProbe.calls.contains("database"));
        ConfigProbe.calls.clear();

        assertThat(notifyConfig(Option.some("database.pool"))).isInstanceOf(LocalOutcome.Done.class);

        await().atMost(Duration.ofSeconds(5)).until(() -> ConfigProbe.calls.contains("database"));
    }

    @Test
    void notifyConfig_reachesNobody_forAKeyOutsideEverySection() {
        store.withLoaded(ORDERS, new RuntimeFixtures.ConfigSlice());
        run(LocalStep.REGISTER_CONFIG);
        await().atMost(Duration.ofSeconds(5)).until(() -> ConfigProbe.calls.contains("database"));
        ConfigProbe.calls.clear();

        assertThat(notifyConfig(Option.some("unrelated.key"))).isInstanceOf(LocalOutcome.Done.class);

        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(ConfigProbe.calls::isEmpty);
    }

    @Test
    void notifyConfig_failsLoudly_whenTheOpCarriesNoKey() {
        var outcome = notifyConfig(Option.none());

        assertThat(messageOf(outcome)).isEqualTo("local step NOTIFY_CONFIG carries no config key");
    }

    // -- every step -------------------------------------------------------------------------------------------------------------------
    /// A step that names an artifact and is handed none fails loudly instead of being skipped.
    @Test
    void aStepThatNeedsAnArtifact_failsLoudly_withoutOne() {
        for (var step : LocalStep.values()) {
            if (step != LocalStep.NOTIFY_CONFIG) {
                var outcome = adapter(true).run(new NodeFsmEffect.LocalOp(1, 7, Option.none(), step, 0, Option.none())).await().unwrap();

                assertThat(outcome).as(step.name()).isInstanceOf(LocalOutcome.Failed.class);
                assertThat(messageOf(outcome)).as(step.name()).isEqualTo("local step " + step + " needs an artifact");
            }
        }

        assertThat(store.calls).as("none of them touched the store").isEmpty();
        assertThat(bridge.calls).isEmpty();
        assertThat(publisher.calls).isEmpty();
    }

    /// No step is left to fail as unwired: running any of them, with the artifact (or the key) it needs, reaches its collaborator and ends `Done`
    /// on a healthy runtime. A step added to `LocalStep` is a compile error in the adapter's switch, and a case here.
    @Test
    void everyStep_runsAgainstAHealthyRuntime_andIsDone() {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());

        for (var step : LocalStep.values()) {
            var op = step == LocalStep.NOTIFY_CONFIG
                     ? new NodeFsmEffect.LocalOp(1, 0, Option.none(), step, 0, Option.some("database.pool"))
                     : new NodeFsmEffect.LocalOp(1, 7, Option.some(ORDERS), step, 0, Option.none());
            var outcome = adapter(true).run(op).await().unwrap();

            assertThat(outcome).as(step.name()).isInstanceOf(LocalOutcome.Done.class);
            store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());
        }
    }
}
