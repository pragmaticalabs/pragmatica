// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
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

    // -- steps outside the path ---------------------------------------------------------------------------------------------
    @Test
    void theStepsOutsideTheTracersPath_failLoudly() {
        for (var step : List.of(LocalStep.PREPARE_TEARDOWN, LocalStep.DEACTIVATE, LocalStep.UNLOAD, LocalStep.FORCED_CLEANUP, LocalStep.NOTIFY_CONFIG)) {
            var outcome = run(step);

            assertThat(outcome).as(step.name()).isInstanceOf(LocalOutcome.Failed.class);
            assertThat(messageOf(outcome)).isEqualTo("unwired in phase 3 tracer: " + step);
        }

        assertThat(store.calls).as("none of them touched the store").isEmpty();
        assertThat(bridge.calls).isEmpty();
        assertThat(publisher.calls).isEmpty();
    }

    @Test
    void aStepWithoutAnArtifact_failsLoudly() {
        var outcome = adapter(true).run(new NodeFsmEffect.LocalOp(1, 0, Option.none(), LocalStep.NOTIFY_CONFIG, 0, Option.some("database.pool"))).await().unwrap();

        assertThat(messageOf(outcome)).isEqualTo("unwired in phase 3 tracer: NOTIFY_CONFIG");
    }

    @Test
    void everyStep_hasADeclaredOutcomeClass() {
        var onPath = List.of(LocalStep.LOAD, LocalStep.ACTIVATE, LocalStep.REGISTER_BRIDGE, LocalStep.BUILD_LEGS, LocalStep.REGISTER_CONFIG, LocalStep.PUBLISH_ROUTES,
                             LocalStep.DESCRIBE_ACTIVE, LocalStep.UNPUBLISH, LocalStep.SUSPEND, LocalStep.EVICT);
        var unwired = List.of(LocalStep.PREPARE_TEARDOWN, LocalStep.DEACTIVATE, LocalStep.UNLOAD, LocalStep.FORCED_CLEANUP, LocalStep.NOTIFY_CONFIG);

        assertThat(List.of(LocalStep.values())).as("a new step must be classified here and in the adapter's switch").containsExactlyInAnyOrderElementsOf(concat(onPath, unwired));
    }

    private static List<LocalStep> concat(List<LocalStep> first, List<LocalStep> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
