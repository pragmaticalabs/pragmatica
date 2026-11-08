// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.config.ConfigReadProtocol;
import org.pragmatica.aether.deployment.config.DefaultConfigChangeFeed;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmTimings;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.FakeSliceStore;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingInvocationHandler;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingRoutePublisher;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingContext;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.SliceStore.EntryState;
import org.pragmatica.aether.slice.SliceStore.LoadedSliceEntry;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// FSM2 F2b, the node carry-forward of the legacy config fixes (spec §8.5): each nested class ports a legacy `NodeDeploymentState` test onto the
/// FSM2 node interpreter, driven end to end through the real `NodeFsm`, interpreter, `LocalOpExecutor` and `SliceRuntimeAdapter` (as
/// `TeardownChainTest`), with the real `ConfigNotificationManager`.
class ConfigCarryForwardChainTest {
    private static final String TOML = """
        [database]
        pool_size = 4
        """;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final RecordingInvocationHandler bridge = new RecordingInvocationHandler();
    private final RecordingRoutePublisher publisher = new RecordingRoutePublisher();
    private final ConfigNotificationManager configNotifications = ConfigNotificationManager.configNotificationManager();
    private final List<String> counted = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        SliceRuntimeAdapterTest.ConfigProbe.calls.clear();
        JsonProbe.calls.clear();
        ConfigService.setInstance(ProviderBasedConfigService.providerBasedConfigService(ConfigurationProvider.builder()
                                                                                                         .withSource(TomlConfigSource.tomlConfigSource(TOML).unwrap())
                                                                                                         .build()));
    }

    @AfterEach
    void tearDown() {
        ConfigService.clear();
        configNotifications.shutdown();
    }

    /// The rig over the real executor and adapter over `sliceStore`, bound late as in `TeardownChainTest`.
    private NodeRig rigOver(SliceStore sliceStore) {
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

        late.set(LocalOpExecutor.localOpExecutor(adapterOver(sliceStore, rig),
                                                 new ManualTimerService(),
                                                 NodeFsmTimings.nodeFsmTimings(),
                                                 (step, result) -> counted.add(step + ":" + result)));

        return rig;
    }

    private SliceRuntimeAdapter adapterOver(SliceStore sliceStore, NodeRig rig) {
        return SliceRuntimeAdapter.sliceRuntimeAdapter(new SliceRuntimeAdapter.Collaborators(SELF,
                                                                                            sliceStore,
                                                                                            bridge,
                                                                                            SliceCodec.sliceCodec(List.of()),
                                                                                            Option.some(publisher),
                                                                                            Option.some(RuntimeFixtures.FACADE),
                                                                                            configNotifications,
                                                                                            rig.store,
                                                                                            () -> rig.cluster,
                                                                                            () -> BOOT_INCARNATION,
                                                                                            OperatorWarningSink::logOnly));
    }

    private NodeRig rigWith(Slice slice) {
        return rigOver(new FakeSliceStore().withLoaded(ORDERS, slice));
    }

    private static void activate(NodeRig rig) {
        rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
        rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
    }

    private static void deactivate(NodeRig rig) {
        rig.goal(ORDERS, SliceGoal.LOADED);
        rig.awaitState(ORDERS, SliceObservedState.LOADED);
    }

    private static void reactivate(NodeRig rig) {
        rig.goal(ORDERS, SliceGoal.ACTIVE);
        rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
    }

    /// A quorum loss suspends the serving instance (SUSPEND), and the next entry runs its REACTIVATE chain over the slice still loaded.
    private void suspendAndReenter(NodeRig rig) {
        var before = counted.stream().filter("SUSPEND:done"::equals).count();

        rig.loaded = Set.of(ORDERS);
        rig.interpreter.deliver(new NodeFsmInput.QuorumDisappeared());
        rig.awaitThat(() -> counted.stream().filter("SUSPEND:done"::equals).count() > before);
        rig.interpreter.requestEntry(false);
        rig.settle();
    }

    /// The factory the F2b JSON manifests name; `ConfigNotificationManager` calls its `notifyConfigUpdate` reflectively.
    public static final class JsonProbe {
        static final List<String> calls = new CopyOnWriteArrayList<>();

        public static void notifyConfigUpdate(Object sliceInstance, String section, ConfigFacade config) {
            calls.add(section);
        }
    }

    /// `META-INF/slice/F2bJsonRoutedSlice.manifest`: a `config-update` binding on `database` and `json.settings.section=json`.
    interface F2bJsonRoutedSlice extends Slice {}

    /// `META-INF/slice/F2bUnroutedSlice.manifest`: the factory only, no section.
    interface F2bUnroutedSlice extends Slice {}

    static final class JsonRoutedSlice implements F2bJsonRoutedSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static final class UnroutedSlice implements F2bUnroutedSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    // -- R-CF.29 (#2394, NT-163): `[json]` settings registration ---------------------------------------------------------------------
    /// Ports of `NodeDeploymentStateJsonSettingsRegistrationTest`.
    @Nested
    class JsonSettingsRegistration {
        /// Red when: the FSM2 builders drop the manifest's `json.settings.section` (the slice is notified on `database` only).
        @Test
        void routedSlice_isNotifiedOnItsJsonSection_initiallyAndOnACommittedChange() {
            try (var rig = rigWith(new JsonRoutedSlice())) {
                activate(rig);
                await().atMost(WAIT).untilAsserted(() -> assertThat(JsonProbe.calls).containsExactlyInAnyOrder("database", "json"));
                JsonProbe.calls.clear();

                rig.interpreter.deliver(new NodeFsmInput.ConfigChanged("json.default"));
                rig.interpreter.deliver(new NodeFsmInput.ConfigChanged("unrelated.pool_size"));
                rig.awaitThat(() -> counted.stream().filter("NOTIFY_CONFIG:done"::equals).count() >= 2);

                await().atMost(WAIT).untilAsserted(() -> assertThat(JsonProbe.calls).containsExactly("json"));
            }
        }

        /// The control: a slice whose manifest names no section is never registered, so a `json.*` change reaches nothing.
        @Test
        void sliceWithoutTheJsonSection_isNotRegistered() {
            try (var rig = rigWith(new UnroutedSlice())) {
                activate(rig);
                rig.interpreter.deliver(new NodeFsmInput.ConfigChanged("json.default"));
                rig.awaitThat(() -> counted.contains("NOTIFY_CONFIG:done"));

                await().during(Duration.ofMillis(300)).atMost(WAIT).until(JsonProbe.calls::isEmpty);
            }
        }
    }

    // -- R-CF.21 (#2164, NT-154): config sections re-registered on reactivation ------------------------------------------------------
    @Nested
    class ConfigReregistration {
        /// Red when: the activation chain run on a LOADED instance registers no config (its UNPUBLISH unregistered it at the deactivation), so
        /// neither the initial notification nor a later change reaches the slice.
        @Test
        void deactivateThenReactivate_registersTheSectionsAgain_andDeliversTheInitialNotification() {
            try (var rig = rigWith(new RuntimeFixtures.ConfigSlice())) {
                activate(rig);
                await().atMost(WAIT).until(() -> SliceRuntimeAdapterTest.ConfigProbe.calls.contains("database"));
                deactivate(rig);
                SliceRuntimeAdapterTest.ConfigProbe.calls.clear();

                reactivate(rig);

                await().atMost(WAIT).until(() -> SliceRuntimeAdapterTest.ConfigProbe.calls.contains("database"));
                SliceRuntimeAdapterTest.ConfigProbe.calls.clear();
                rig.interpreter.deliver(new NodeFsmInput.ConfigChanged("database.pool_size"));
                await().atMost(WAIT).until(() -> SliceRuntimeAdapterTest.ConfigProbe.calls.contains("database"));
            }
        }

        /// #2164 itself: legacy's suspended-slice reactivation re-ran no config registration. Red when the REACTIVATE chain carries no
        /// REGISTER_CONFIG: the initial notification is not delivered again.
        @Test
        void suspendThenReactivate_deliversTheInitialNotificationAgain() {
            try (var rig = rigWith(new RuntimeFixtures.ConfigSlice())) {
                activate(rig);
                await().atMost(WAIT).until(() -> SliceRuntimeAdapterTest.ConfigProbe.calls.contains("database"));
                var registrations = counted.stream().filter("REGISTER_CONFIG:done"::equals).count();
                SliceRuntimeAdapterTest.ConfigProbe.calls.clear();

                suspendAndReenter(rig);

                rig.awaitThat(() -> counted.stream().filter("REGISTER_CONFIG:done"::equals).count() > registrations);
                await().atMost(WAIT).until(() -> SliceRuntimeAdapterTest.ConfigProbe.calls.contains("database"));
            }
        }
    }

    // -- R-CF.28 (#2493, NT-162): a Watched/Live subscription survives deactivate and suspend ----------------------------------------
    /// Ports of `NodeDeploymentStateConfigWatchSurvivalTest` over a REAL `SliceStore` with a real feed: the subscription is the slice
    /// artifact's, released only by `SliceStore.unloadSlice`, so it must survive every FSM2 path that does not unload.
    @Nested
    class ConfigWatchSurvival {
        private final AtomicReference<String> value = new AtomicReference<>("v0");
        private final List<String> delivered = new CopyOnWriteArrayList<>();
        private DefaultConfigChangeFeed feed;
        private SliceStore.sliceStore store;

        @BeforeEach
        void setUpStore() {
            feed = DefaultConfigChangeFeed.defaultConfigChangeFeed(() -> 0L, ConfigReadProtocol.unprotected(), TimeSpan.timeSpan(2).seconds());
            store = realStore(_ -> Causes.cause("the entry is seeded; no location is looked up").promise(), Option.some(feed));
        }

        /// The slice the store holds LOADED, as a node that loaded it has it, with a `Watched` subscription enrolled in the feed.
        private void seedLoadedSliceWithASubscription() {
            Slice slice = () -> List.of(SliceMethod.sliceMethod(MethodName.methodName("execute").unwrap(),
                                                                (Unit unit) -> Promise.unitPromise(),
                                                                TypeToken.typeToken(Unit.class),
                                                                TypeToken.typeToken(Unit.class))
                                                   .unwrap());
            var entry = new LoadedSliceEntry(ORDERS,
                                             slice,
                                             new SliceClassLoader(new URL[0], getClass().getClassLoader()),
                                             SliceLoadingContext.sliceLoadingContext(STUB_INVOKER),
                                             Option.empty(),
                                             EntryState.LOADED);

            store.entries().put(ORDERS, Promise.success(entry));
            assertThat(feed.subscribe(ORDERS.asString(), "watched", () -> Result.success(value.get()), this::record).isSuccess()).isTrue();
            delivered.clear();
        }

        private Promise<Unit> record(String seen) {
            delivered.add(seen);

            return Promise.unitPromise();
        }

        private void changeAndAwait(String next) {
            value.set(next);
            feed.publish("watched.key");
            await().atMost(WAIT).until(() -> delivered.contains(next));
        }

        /// Red when: the FSM2 deactivation path releases the artifact's subscriptions (an unload where legacy deactivated).
        @Test
        void deactivateAndActivate_keepTheSubscription_andDeliveriesContinueWhileLoaded() {
            seedLoadedSliceWithASubscription();

            try (var rig = rigOver(store)) {
                activate(rig);
                deactivate(rig);
                changeAndAwait("while-deactivated");
                assertThat(feed.subscriberCount(ORDERS.asString())).as("deactivation is not an unload").isEqualTo(1);

                reactivate(rig);
                changeAndAwait("after-reactivation");
                assertThat(feed.subscriberCount(ORDERS.asString())).isEqualTo(1);
            }
        }

        /// Red when: the FSM2 suspend or reactivation path releases the artifact's subscriptions.
        @Test
        void suspendAndReactivate_keepTheSubscription_andDeliveriesContinueMeanwhile() {
            seedLoadedSliceWithASubscription();

            try (var rig = rigOver(store)) {
                activate(rig);
                var reactivations = counted.stream().filter("REGISTER_BRIDGE:done"::equals).count();

                suspendAndReenter(rig);
                rig.awaitThat(() -> counted.stream().filter("REGISTER_BRIDGE:done"::equals).count() > reactivations);
                changeAndAwait("after-reactivation");
                assertThat(feed.subscriberCount(ORDERS.asString())).as("suspension is not an unload").isEqualTo(1);
            }
        }

        /// The positive control: the unload of the artifact does release the subscription, so a release on another path would be seen.
        @Test
        void unload_releasesTheSubscription() {
            seedLoadedSliceWithASubscription();

            try (var rig = rigOver(store)) {
                activate(rig);
                rig.goal(ORDERS, SliceGoal.UNLOADED);
                rig.awaitState(ORDERS, SliceObservedState.UNLOADED);

                await().atMost(WAIT).until(() -> feed.subscriberCount(ORDERS.asString()) == 0);
            }
        }
    }

    // -- R-CF.27 (#2589, NT-161): a failed LOAD leaves no stale entry for its retry --------------------------------------------------
    @Nested
    class FailedLoadRetry {
        /// Red when: the failed load is still cached when the FSM2 LOAD step reports it, so the retried LOAD gets the failed promise back and
        /// never looks the artifact up again.
        @Test
        void aLoadThatFails_isRetriedByTheNextLoad_againstTheRepository() {
            var lookups = new AtomicInteger();
            Repository failingOffThread = artifact -> {
                lookups.incrementAndGet();

                return Promise.<Location>promise(pending -> Thread.ofVirtual()
                                                                  .start(() -> pending.fail(Causes.cause("no location for " + artifact.asString()))));
            };
            var store = realStore(failingOffThread, Option.none());

            try (var rig = new NodeRig()) {
                var adapter = adapterOver(store, rig);

                for (int attempt = 1; attempt <= 50; attempt++) {
                    var outcome = adapter.run(loadOp()).await().unwrap();

                    assertThat(outcome).as("control: attempt %d fails", attempt).isInstanceOf(LocalOutcome.Failed.class);
                    assertThat(lookups.get()).as("attempt %d looked the artifact up again", attempt).isEqualTo(attempt);
                    assertThat(store.entries()).as("attempt %d: no failed entry once the LOAD step reports it", attempt).doesNotContainKey(ORDERS);
                }
            }
        }

        private static NodeFsmEffect.LocalOp loadOp() {
            return new NodeFsmEffect.LocalOp(BOOT_INCARNATION, 1L, Option.some(ORDERS), LocalStep.LOAD, 0L, Option.none());
        }
    }

    // -- the real store ------------------------------------------------------------------------------------------------------------
    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact, String method, TypeToken<T> requestType, TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    private static SliceStore.sliceStore realStore(Repository repository, Option<DefaultConfigChangeFeed> feed) {
        return (SliceStore.sliceStore) SliceStore.sliceStore(SliceRegistry.sliceRegistry(),
                                                             List.of(repository),
                                                             new SharedLibraryClassLoader(ConfigCarryForwardChainTest.class.getClassLoader()),
                                                             STUB_INVOKER,
                                                             noResources(),
                                                             SliceActionConfig.sliceActionConfig(),
                                                             Option.empty(),
                                                             Option.empty(),
                                                             Option.empty(),
                                                             SliceLoadingContext.noResourceOverlay(),
                                                             feed.map(f -> f));
    }

    private static ResourceProviderFacade noResources() {
        return new ResourceProviderFacade() {
            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return Causes.cause("no resources in this test").promise();
            }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                return Causes.cause("no resources in this test").promise();
            }

            @Override
            public Promise<Unit> releaseAll(String sliceId) {
                return Promise.unitPromise();
            }
        };
    }
}
