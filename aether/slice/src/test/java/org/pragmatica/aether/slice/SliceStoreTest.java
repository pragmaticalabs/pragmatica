// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceStore.EntryState;
import org.pragmatica.aether.slice.SliceStore.LoadedSliceEntry;
import org.pragmatica.aether.slice.SliceStore.sliceStore;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.config.ConfigError;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SliceStoreTest {

    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact, String method, TypeToken<T> requestType, TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    private SliceRegistry registry;
    private SharedLibraryClassLoader sharedLoader;
    private Artifact artifact;

    @BeforeEach
    void setUp() {
        registry = SliceRegistry.sliceRegistry();
        sharedLoader = new SharedLibraryClassLoader(getClass().getClassLoader());
        artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    // === Slice config override precedence ===

    @Test
    void node_deployment_override_wins_over_slice_intrinsic_for_shared_keys() {
        // The slice ships LOCAL defaults in resources.toml; each deployment overrides the
        // same section/value via the node-composite. Regression guard for the previously
        // inverted LayeredConfigProvider order (slice intrinsic was wrongly winning).
        var intrinsic = IntrinsicConfigProvider.intrinsicConfigProvider("slice.toml",
                                                                        Map.of("database.async_url", "postgresql://forge-postgres:5432/forge",
                                                                               "streams.test-events.partitions", "4"));
        var nodeComposite = IntrinsicConfigProvider.intrinsicConfigProvider("node",
                                                                            Map.of("database.async_url", "postgresql://pg-vm:5432/aether_forge"));

        var composite = SliceStore.sliceStore.assembleSliceComposite(artifact, intrinsic, nodeComposite);

        // Deployment override wins for the shared key...
        assertThat(composite.getString("database.async_url").unwrap()).isEqualTo("postgresql://pg-vm:5432/aether_forge");
        // ...and slice-only keys fall through to the slice intrinsic.
        assertThat(composite.getString("streams.test-events.partitions").unwrap()).isEqualTo("4");
    }

    // === LoadedSliceEntry Tests ===

    @Test
    void loaded_slice_entry_returns_artifact() {
        var slice = createTestSlice();
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var loadingContext = SliceLoadingContext.sliceLoadingContext(STUB_INVOKER);
        var entry = new LoadedSliceEntry(artifact, slice, classLoader, loadingContext, Option.empty(), EntryState.LOADED);

        assertThat(entry.artifact()).isEqualTo(artifact);
    }

    @Test
    void loaded_slice_entry_returns_slice() {
        var slice = createTestSlice();
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var loadingContext = SliceLoadingContext.sliceLoadingContext(STUB_INVOKER);
        var entry = new LoadedSliceEntry(artifact, slice, classLoader, loadingContext, Option.empty(), EntryState.LOADED);

        assertThat(entry.slice()).isSameAs(slice);
    }

    @Test
    void loaded_slice_entry_with_state_returns_new_entry() {
        var slice = createTestSlice();
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var loadingContext = SliceLoadingContext.sliceLoadingContext(STUB_INVOKER);
        var entry = new LoadedSliceEntry(artifact, slice, classLoader, loadingContext, Option.empty(), EntryState.LOADED);

        var activeEntry = entry.withState(EntryState.ACTIVE);

        assertThat(activeEntry.state()).isEqualTo(EntryState.ACTIVE);
        assertThat(activeEntry.artifact()).isEqualTo(artifact);
        assertThat(activeEntry.sliceInstance()).isSameAs(slice);
        assertThat(activeEntry.classLoader()).isSameAs(classLoader);
    }

    // === Factory Method Tests ===

    @Test
    void slice_store_factory_creates_instance() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader, STUB_INVOKER, SliceActionConfig.sliceActionConfig());

        assertThat(store).isNotNull();
        assertThat(store.loaded()).isEmpty();
    }

    // === Activation Tests ===

    @Test
    void activate_calls_start_on_slice() {
        var startCalled = new AtomicBoolean(false);
        var slice = createTestSlice(() -> {
            startCalled.set(true);
            return Promise.success(Unit.unit());
        }, () -> Promise.success(Unit.unit()));

        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        store.activateSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(startCalled.get()).isTrue();
    }

    @Test
    void activate_transitions_to_active_state() {
        var slice = createTestSlice();
        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        store.activateSlice(artifact)
             .await()
             .onSuccess(loaded -> {
                 var entry = (LoadedSliceEntry) loaded;
                 assertThat(entry.state()).isEqualTo(EntryState.ACTIVE);
             })
             .onFailureRun(Assertions::fail);
    }

    @Test
    void activate_already_active_returns_success() {
        var startCount = new AtomicInteger(0);
        var slice = createTestSlice(() -> {
            startCount.incrementAndGet();
            return Promise.success(Unit.unit());
        }, () -> Promise.success(Unit.unit()));

        var store = createStoreWithPreloadedSlice(slice, EntryState.ACTIVE);

        store.activateSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        // Should not call start again
        assertThat(startCount.get()).isEqualTo(0);
    }

    @Test
    void activate_not_loaded_fails() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader, STUB_INVOKER, SliceActionConfig.sliceActionConfig());

        store.activateSlice(artifact)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> assertThat(cause.message()).contains("not loaded"));
    }

    // === Deactivation Tests ===

    @Test
    void deactivate_calls_stop_on_slice() {
        var stopCalled = new AtomicBoolean(false);
        var slice = createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> {
                    stopCalled.set(true);
                    return Promise.success(Unit.unit());
                });

        var store = createStoreWithPreloadedSlice(slice, EntryState.ACTIVE);

        store.deactivateSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(stopCalled.get()).isTrue();
    }

    @Test
    void deactivate_transitions_to_loaded_state() {
        var slice = createTestSlice();
        var store = createStoreWithPreloadedSlice(slice, EntryState.ACTIVE);

        store.deactivateSlice(artifact)
             .await()
             .onSuccess(loaded -> {
                 var entry = (LoadedSliceEntry) loaded;
                 assertThat(entry.state()).isEqualTo(EntryState.LOADED);
             })
             .onFailureRun(Assertions::fail);
    }

    @Test
    void deactivate_already_loaded_returns_success() {
        var stopCount = new AtomicInteger(0);
        var slice = createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> {
                    stopCount.incrementAndGet();
                    return Promise.success(Unit.unit());
                });

        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        store.deactivateSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        // Should not call stop
        assertThat(stopCount.get()).isEqualTo(0);
    }

    @Test
    void deactivate_not_loaded_fails() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader, STUB_INVOKER, SliceActionConfig.sliceActionConfig());

        store.deactivateSlice(artifact)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> assertThat(cause.message()).contains("not loaded"));
    }

    // === Unload Tests ===

    @Test
    void unload_removes_from_loaded_list() {
        var slice = createTestSlice();
        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        assertThat(store.loaded()).hasSize(1);

        store.unloadSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(store.loaded()).isEmpty();
    }

    @Test
    void unload_active_calls_stop_first() {
        var stopCalled = new AtomicBoolean(false);
        var slice = createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> {
                    stopCalled.set(true);
                    return Promise.success(Unit.unit());
                });

        var store = createStoreWithPreloadedSlice(slice, EntryState.ACTIVE);

        store.unloadSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(stopCalled.get()).isTrue();
    }

    @Test
    void unload_loaded_does_not_call_stop() {
        var stopCount = new AtomicInteger(0);
        var slice = createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> {
                    stopCount.incrementAndGet();
                    return Promise.success(Unit.unit());
                });

        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        store.unloadSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(stopCount.get()).isEqualTo(0);
    }

    @Test
    void unload_nonexistent_succeeds() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader, STUB_INVOKER, SliceActionConfig.sliceActionConfig());

        store.unloadSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);
    }

    // === Loaded List Tests ===

    @Test
    void loaded_returns_all_entries() {
        var slice1 = createTestSlice();
        var slice2 = createTestSlice();
        var artifact1 = Artifact.artifact("org.example:slice1:1.0.0").unwrap();
        var artifact2 = Artifact.artifact("org.example:slice2:1.0.0").unwrap();

        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader, STUB_INVOKER, SliceActionConfig.sliceActionConfig());
        addPreloadedSlice(store, artifact1, slice1, EntryState.LOADED);
        addPreloadedSlice(store, artifact2, slice2, EntryState.ACTIVE);

        var loaded = store.loaded();

        assertThat(loaded).hasSize(2);
        assertThat(loaded.stream().map(SliceStore.LoadedSlice::artifact))
                .containsExactlyInAnyOrder(artifact1, artifact2);
    }

    // === sliceComposite() Tests ===

    @Test
    void sliceComposite_returns_attached_provider_when_present() {
        var slice = createTestSlice();
        var provider = org.pragmatica.config.IntrinsicConfigProvider.intrinsicConfigProvider(
                "test", java.util.Map.of("topics.events.topic_name", "events"));
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader, STUB_INVOKER, SliceActionConfig.sliceActionConfig());
        addPreloadedSliceWithConfig(store, artifact, slice, EntryState.LOADED, Option.some(provider));

        var composite = store.sliceComposite(artifact);

        assertThat(composite.isPresent()).isTrue();
        assertThat(composite.unwrap().getString("topics.events.topic_name").unwrap()).isEqualTo("events");
    }

    @Test
    void sliceComposite_returns_none_when_slice_has_no_config() {
        var slice = createTestSlice();
        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        var composite = store.sliceComposite(artifact);

        assertThat(composite.isEmpty()).isTrue();
    }

    @Test
    void sliceComposite_returns_none_when_slice_not_loaded() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader, STUB_INVOKER, SliceActionConfig.sliceActionConfig());

        var composite = store.sliceComposite(artifact);

        assertThat(composite.isEmpty()).isTrue();
    }

    // === Slice-intrinsic secret resolution (#269) ===
    //
    // No log-scraping here — this codebase's log backend (log4j2) doesn't support capturing
    // appender output in unit tests (see ProvisioningRecoveryAfterFailureBurstProbeTest's own
    // note on the same limitation). `resolveIntrinsicSecrets` and `intrinsicSecretsDroppedMessage`
    // are package-private precisely so the behavior and the consequence-naming wording are each a
    // first-class, directly assertable return value instead.

    @Test
    void resolveIntrinsicSecrets_resolvesPlaceholder_whenResolverSucceeds() {
        var intrinsic = IntrinsicConfigProvider.intrinsicConfigProvider("slice.toml",
                                                                        Map.of("database.password", "${secrets:db/password}",
                                                                               "database.async_url", "postgresql://forge-postgres:5432/forge"));
        Fn1<Promise<String>, String> resolver = path -> Promise.success("resolved-" + path);

        var resolved = sliceStore.resolveIntrinsicSecrets(artifact, intrinsic, Option.some(resolver));

        assertThat(resolved.isPresent()).isTrue();
        assertThat(resolved.unwrap().getString("database.password").unwrap()).isEqualTo("resolved-db/password");
        // Non-secret keys pass through unchanged.
        assertThat(resolved.unwrap().getString("database.async_url").unwrap()).isEqualTo("postgresql://forge-postgres:5432/forge");
    }

    @Test
    void resolveIntrinsicSecrets_passesThroughUnchanged_whenNoResolverConfigured() {
        var intrinsic = IntrinsicConfigProvider.intrinsicConfigProvider("slice.toml",
                                                                        Map.of("database.password", "${secrets:db/password}"));

        var resolved = sliceStore.resolveIntrinsicSecrets(artifact, intrinsic, Option.empty());

        assertThat(resolved.isPresent()).isTrue();
        // Pre-#269 behavior when a slice runs with no secrets integration configured at all:
        // the literal placeholder reaches the composite rather than failing the slice load.
        assertThat(resolved.unwrap().getString("database.password").unwrap()).isEqualTo("${secrets:db/password}");
    }

    @Test
    void resolveIntrinsicSecrets_dropsEntireLayer_whenResolverFails() {
        // Two keys: only one references a secret. All-or-nothing means BOTH are gone from the
        // result, not just the failed key — the composite's return type (Option.none()) makes
        // that the only possible outcome, so no literal placeholder can leak through it either.
        var intrinsic = IntrinsicConfigProvider.intrinsicConfigProvider("slice.toml",
                                                                        Map.of("database.password", "${secrets:db/password}",
                                                                               "database.async_url", "postgresql://forge-postgres:5432/forge"));
        Fn1<Promise<String>, String> resolver = path -> Causes.cause("secret store unreachable").promise();

        var resolved = sliceStore.resolveIntrinsicSecrets(artifact, intrinsic, Option.some(resolver));

        assertThat(resolved.isEmpty()).isTrue();
    }

    @Test
    void intrinsicSecretsDroppedMessage_namesSliceFailedKeyAndConsequence() {
        var cause = ConfigError.secretResolutionFailed("database.password",
                                                       "db/password",
                                                       Causes.cause("secret store unreachable"));

        var message = sliceStore.intrinsicSecretsDroppedMessage(artifact, cause);

        assertThat(message).contains(artifact.asString());
        assertThat(message).contains("database.password");
        assertThat(message).contains("db/password");
        assertThat(message).contains("dropping the ENTIRE");
        assertThat(message).contains("not-configured at provision time");
    }

    // === logShadowedKeys redaction (R5) ===

    @Test
    void shadowedKeys_returnsKeyNamesOnly_neverTheValues() {
        var secretLookingValue = "hunter2-super-secret-password";
        var intrinsic = IntrinsicConfigProvider.intrinsicConfigProvider("slice.toml",
                                                                        Map.of("database.password", secretLookingValue,
                                                                               "database.async_url", "postgresql://forge-postgres:5432/forge"));
        var nodeComposite = IntrinsicConfigProvider.intrinsicConfigProvider("node",
                                                                            Map.of("database.password", "override-value",
                                                                                   "database.async_url", "postgresql://forge-postgres:5432/forge"));

        var shadowed = sliceStore.shadowedKeys(intrinsic, nodeComposite);

        // Only the key whose value actually differs is reported...
        assertThat(shadowed).containsExactly("database.password");
        // ...and the returned list cannot possibly contain either value, for any input: the
        // method's return type is List<String> of key names, so this is a structural guarantee,
        // not a per-call coincidence. Asserted explicitly anyway, for the reader who won't trust
        // the type alone.
        assertThat(shadowed).doesNotContain(secretLookingValue, "override-value");
    }

    @Test
    void shadowedKeys_returnsEmpty_whenValuesMatch() {
        var intrinsic = IntrinsicConfigProvider.intrinsicConfigProvider("slice.toml",
                                                                        Map.of("database.async_url", "postgresql://forge-postgres:5432/forge"));
        var nodeComposite = IntrinsicConfigProvider.intrinsicConfigProvider("node",
                                                                            Map.of("database.async_url", "postgresql://forge-postgres:5432/forge"));

        var shadowed = sliceStore.shadowedKeys(intrinsic, nodeComposite);

        assertThat(shadowed).isEmpty();
    }

    // === Helper Methods ===

    private Slice createTestSlice() {
        return createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> Promise.success(Unit.unit())
                              );
    }

    private Slice createTestSlice(
            java.util.function.Supplier<Promise<Unit>> startSupplier,
            java.util.function.Supplier<Promise<Unit>> stopSupplier
                                 ) {
        return new Slice() {
            @Override
            public Promise<Unit> start() {
                return startSupplier.get();
            }

            @Override
            public Promise<Unit> stop() {
                return stopSupplier.get();
            }

            @Override
            public List<SliceMethod<?, ?>> methods() {
                return List.of();
            }
        };
    }

    private SliceStore createStoreWithPreloadedSlice(Slice slice, EntryState state) {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader, STUB_INVOKER, SliceActionConfig.sliceActionConfig());
        addPreloadedSlice(store, artifact, slice, state);
        return store;
    }

    private void addPreloadedSlice(SliceStore store, Artifact artifact, Slice slice, EntryState state) {
        addPreloadedSliceWithConfig(store, artifact, slice, state, Option.empty());
    }

    private void addPreloadedSliceWithConfig(SliceStore store,
                                             Artifact artifact,
                                             Slice slice,
                                             EntryState state,
                                             Option<org.pragmatica.config.ConfigurationProvider> sliceConfig) {
        // Access internal map via the record
        var impl = (sliceStore) store;
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var loadingContext = SliceLoadingContext.sliceLoadingContext(STUB_INVOKER);
        var entry = new LoadedSliceEntry(artifact, slice, classLoader, loadingContext, sliceConfig, state);
        impl.entries().put(artifact, Promise.success(entry));
    }
}
