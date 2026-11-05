// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceStore.EntryState;
import org.pragmatica.aether.slice.SliceStore.LoadedSliceEntry;
import org.pragmatica.aether.slice.SliceStore.sliceStore;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #774: a slice's `Watched` subscriptions are released when the slice is UNLOADED - before `stop()`, whatever
/// `stop()` does - and never by deactivation.
class SliceStoreConfigWatchReleaseTest {
    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact, String method, TypeToken<T> requestType, TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final ConfigChangeFeed feed = new LoggingFeed(log);
    private Artifact artifact;
    private Artifact other;

    @BeforeEach
    void setUp() {
        artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        other = Artifact.artifact("org.example:other-slice:1.0.0").unwrap();
    }

    @Test
    void unload_releasesTheSlicesSubscriptions_beforeStop() {
        var store = storeWith(slice(() -> logged("stop", Promise.unitPromise())), EntryState.ACTIVE, TimeSpan.timeSpan(5).seconds());

        store.unloadSlice(artifact).await();

        assertThat(log).as("release first, so a stop() that fails or hangs cannot skip it").containsExactly("release " + artifact.asString(), "stop");
    }

    @Test
    void unload_ofALoadedSlice_releases_withoutStop() {
        var store = storeWith(slice(() -> logged("stop", Promise.unitPromise())), EntryState.LOADED, TimeSpan.timeSpan(5).seconds());

        store.unloadSlice(artifact).await();

        assertThat(log).containsExactly("release " + artifact.asString());
    }

    @Test
    void unload_releases_evenWhenStopFails() {
        var store = storeWith(slice(() -> logged("stop", Causes.cause("stop blew up").promise())), EntryState.ACTIVE, TimeSpan.timeSpan(5).seconds());

        var result = store.unloadSlice(artifact).await();

        assertThat(result.isFailure()).isTrue();
        assertThat(log).contains("release " + artifact.asString());
    }

    @Test
    void unload_releases_evenWhenStopTimesOut() {
        var store = storeWith(slice(() -> logged("stop", Promise.<Unit> promise())), EntryState.ACTIVE, TimeSpan.timeSpan(150).millis());

        var result = store.unloadSlice(artifact).await();

        assertThat(result.isFailure()).isTrue();
        assertThat(log).contains("release " + artifact.asString());
    }

    @Test
    void deactivate_doesNotRelease_theSubscriptionSurvivesTheInstanceBeingLoaded() {
        var store = storeWith(slice(() -> logged("stop", Promise.unitPromise())), EntryState.ACTIVE, TimeSpan.timeSpan(5).seconds());

        store.deactivateSlice(artifact).await();

        assertThat(log).containsExactly("stop");
    }

    @Test
    void unload_releasesOnlyTheUnloadedArtifact() {
        var store = storeWith(slice(() -> Promise.unitPromise()), EntryState.LOADED, TimeSpan.timeSpan(5).seconds());
        addEntry(store, other, slice(() -> Promise.unitPromise()), EntryState.LOADED);

        store.unloadSlice(artifact).await();

        assertThat(log).containsExactly("release " + artifact.asString());
    }

    @Test
    void unload_ofAnUnknownSlice_releasesNothing() {
        var store = storeWith(slice(() -> Promise.unitPromise()), EntryState.LOADED, TimeSpan.timeSpan(5).seconds());

        store.unloadSlice(other).await();

        assertThat(log).isEmpty();
    }

    // ---------------------------------------------------------------- scaffolding

    private Promise<Unit> logged(String what, Promise<Unit> result) {
        log.add(what);
        return result;
    }

    private SliceStore storeWith(Slice slice, EntryState state, TimeSpan startStopTimeout) {
        var base = SliceActionConfig.sliceActionConfig();
        var config = new SliceActionConfig(base.loadingTimeout(),
                                           base.activatingTimeout(),
                                           base.deactivatingTimeout(),
                                           base.unloadingTimeout(),
                                           startStopTimeout,
                                           base.repositories(),
                                           base.frameworkJarsPath());
        var store = SliceStore.sliceStore(SliceRegistry.sliceRegistry(),
                                          List.of(),
                                          new SharedLibraryClassLoader(getClass().getClassLoader()),
                                          STUB_INVOKER,
                                          noResources(),
                                          config,
                                          Option.empty(),
                                          Option.empty(),
                                          Option.empty(),
                                          SliceLoadingContext.noResourceOverlay(),
                                          Option.some(feed));

        addEntry(store, artifact, slice, state);
        return store;
    }

    private static void addEntry(SliceStore store, Artifact artifact, Slice slice, EntryState state) {
        var classLoader = new SliceClassLoader(new URL[0], SliceStoreConfigWatchReleaseTest.class.getClassLoader());
        var entry = new LoadedSliceEntry(artifact,
                                         slice,
                                         classLoader,
                                         SliceLoadingContext.sliceLoadingContext(STUB_INVOKER),
                                         Option.empty(),
                                         state);

        ((sliceStore) store).entries().put(artifact, Promise.success(entry));
    }

    private static Slice slice(Fn0<Promise<Unit>> stop) {
        return new Slice() {
            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return stop.apply();
            }

            @Override
            public List<SliceMethod<?, ?>> methods() {
                return List.of();
            }
        };
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

    private record LoggingFeed(List<String> log) implements ConfigChangeFeed {
        @Override
        public <T> Result<Watched.Subscription> subscribe(String owner, String section, Fn0<Result<T>> bind, Fn1<Promise<Unit>, T> callback) {
            return Result.success(Watched.Subscription.noop());
        }

        @Override
        public Unit publish(String key) {
            return Unit.unit();
        }

        @Override
        public Promise<Unit> releaseAll(String owner) {
            log.add("release " + owner);
            return Promise.unitPromise();
        }

        @Override
        public int subscriberCount(String owner) {
            return 0;
        }

        @Override
        public Promise<Unit> shutdown() {
            return Promise.unitPromise();
        }
    }
}
