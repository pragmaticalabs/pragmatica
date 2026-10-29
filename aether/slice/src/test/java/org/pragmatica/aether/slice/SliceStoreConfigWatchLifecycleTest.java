// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceStore.sliceStore;
import org.pragmatica.aether.slice.dependency.DependencyResolver;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.dependency.WatchingSlowFactory;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #774, the subscription lifecycle through a REAL `SliceStore` loading real slice jars: a subscription belongs to
/// the slice ARTIFACT that declares it, lives from the factory to that artifact's own unload, and is released by
/// nothing else. The feed here counts subscriptions per owner and releases them on `releaseAll`, as the node's does.
class SliceStoreConfigWatchLifecycleTest {
    private static final String DEP_CLASS = "org.pragmatica.aether.slice.dependency.WatchingDepFactory";
    private static final String CODEC_THROWS_CLASS = "org.pragmatica.aether.slice.dependency.WatchingCodecThrowsFactory";
    private static final String SLOW_CLASS = "org.pragmatica.aether.slice.dependency.WatchingSlowFactory";
    private static final Artifact SLOW = artifact("org.example:slow:1.0.0");
    private static final Artifact X = artifact("org.example:x:1.0.0");
    private static final Artifact Y = artifact("org.example:y:1.0.0");
    private static final Artifact D = artifact("org.example:d:1.0.0");
    private static final Artifact CODEC_THROWS = artifact("org.example:codec-throws:1.0.0");
    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact, String method, TypeToken<T> requestType, TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    @TempDir
    Path tempDir;

    private final SharedLibraryClassLoader sharedLoader = new SharedLibraryClassLoader(getClass().getClassLoader());
    private final SliceRegistry registry = SliceRegistry.sliceRegistry();
    private final CountingFeed feed = new CountingFeed();
    private sliceStore store;
    private Repository repository;

    @BeforeEach
    void setUp() throws IOException {
        var jars = Map.of(SLOW.asString(), jar(SLOW, SLOW_CLASS, ""),
                          CODEC_THROWS.asString(), jar(CODEC_THROWS, CODEC_THROWS_CLASS, ""),
                          D.asString(), jar(D, DEP_CLASS, ""),
                          X.asString(), jar(X, DEP_CLASS, "[slices]\n" + D.asString() + "\n"),
                          Y.asString(), jar(Y, DEP_CLASS, "[slices]\n" + D.asString() + "\n"));
        repository = art -> Promise.success(new Location(art, jars.get(art.asString())));

        store = (sliceStore) SliceStore.sliceStore(registry,
                                                   List.of(repository),
                                                   sharedLoader,
                                                   STUB_INVOKER,
                                                   noResources(),
                                                   SliceActionConfig.sliceActionConfig(),
                                                   Option.some(IntrinsicConfigProvider.intrinsicConfigProvider("node", Map.of("watched.key", "v"))),
                                                   Option.empty(),
                                                   Option.empty(),
                                                   SliceLoadingContext.noResourceOverlay(),
                                                   Option.some(feed));
    }

    @AfterEach
    void closeLoader() throws Exception {
        WatchingSlowFactory.gate.countDown();
        sharedLoader.close();
    }

    // ---------------------------------------------------------------- (vi) a load that outlives its waiter

    @Test
    void loadTimeoutAbandon_theLateCreateSubscribesUnderTheSameArtifact_andALaterUnloadReleasesIt() {
        WatchingSlowFactory.gate = new CountDownLatch(1);

        var load = store.loadSlice(SLOW);

        assertThat(feed.count(SLOW)).as("creation is still running: nothing subscribed yet").isZero();
        assertThat(store.entries()).as("the FSM giving up does not remove the entry: it is the only handle for the later unload")
                                   .containsKey(SLOW);

        WatchingSlowFactory.gate.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> feed.count(SLOW) == 1);
        load.await();
        store.unloadSlice(SLOW).await();

        assertThat(feed.count(SLOW)).as("the late subscription is released by the unload").isZero();
    }

    // ---------------------------------------------------------------- (vii) dependency ownership

    @Test
    void dependency_isNotReleasedByItsCreatorsUnload_norByAReusingSlice_butByItsOwnUnload() {
        store.loadSlice(X).await();

        assertThat(feed.count(X)).isEqualTo(1);
        assertThat(feed.count(D)).as("the dependency subscribed under ITS artifact, not its creator's").isEqualTo(1);

        store.unloadSlice(X).await();

        assertThat(feed.count(X)).isZero();
        assertThat(feed.count(D)).as("D outlives X: it is registered and live").isEqualTo(1);
        assertThat(registry.lookup(D).isPresent()).isTrue();

        store.loadSlice(Y).await();

        assertThat(feed.count(D)).as("Y reuses the registered D: no second subscription, and D's is still the live one").isEqualTo(1);
        assertThat(feed.count(Y)).isEqualTo(1);

        store.loadSlice(D).await();
        store.unloadSlice(D).await();

        assertThat(feed.count(D)).as("unloading D as its own entry is what releases it").isZero();
        assertThat(feed.count(Y)).isEqualTo(1);
    }

    @Test
    void tripwire_aDependencyNeverLoadedAsItsOwnEntry_staysSubscribedUntilNodeStop() {
        store.loadSlice(X).await();
        store.unloadSlice(X).await();

        assertThat(feed.count(D)).as("STATED LIMIT: the registry only unregisters through SliceStore.cleanup, so a dependency "
                                     + "that is never its own entry lives (and stays subscribed) until node stop. If this "
                                     + "fails the registry gained a dependency-unload hook (ACCEPTED rc5 limit; revisit trigger): unregister "
                                     + "and feed.releaseAll(D) together, and replace this tripwire").isEqualTo(1);

        feed.shutdown().await();

        assertThat(feed.count(D)).isZero();
    }

    // ---------------------------------------------------------------- residual edge R8

    @Test
    void aFailedLoadAfterRegistration_leavesNeitherARegistrationNorASubscription() {
        var withCodec = storeWithNodeCodec();

        var loaded = withCodec.loadSlice(CODEC_THROWS).await();

        assertThat(loaded.isFailure()).as("the load fails AFTER the slice was created, subscribed and registered").isTrue();
        assertThat(withCodec.loaded()).as("no SliceStore entry was ever created").isEmpty();
        // The unwind is a failure callback of the load's Promise and may run just after `await()` returns.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(registry.lookup(CODEC_THROWS).isPresent()).as("a slice whose load failed must not stay registered").isFalse();
            assertThat(feed.count(CODEC_THROWS)).as("nor keep its subscription").isZero();
        });
    }

    @Test
    void unwindingAFailedLoad_neverTakesAwayAnInstanceAnEarlierLoadRegistered() {
        store.loadSlice(X).await();
        store.unloadSlice(X).await();
        assertThat(registry.lookup(D).isPresent() && feed.count(D) == 1).as("precondition: D was registered and subscribed by X's load").isTrue();

        store.unwindFailedLoad(D, true).await();

        assertThat(registry.lookup(D).isPresent()).as("this load did not register D: it must not unregister it").isTrue();
        assertThat(feed.count(D)).as("nor release the live instance's subscription").isEqualTo(1);

        store.unwindFailedLoad(D, false).await();

        assertThat(registry.lookup(D).isPresent()).as("a load that registered the slice unregisters it again").isFalse();
        assertThat(feed.count(D)).as("and releases what it subscribed").isZero();
    }

    @Test
    void aThrowingCodecOfAnAlreadyRegisteredSlice_isAFailedLoad_notAThrowOutOfComputeIfAbsent_andALaterLoadWorks() {
        // Registered and subscribed by an EARLIER resolution, as a dependency would be.
        DependencyResolver.resolveWithContext(CODEC_THROWS,
                                              repository,
                                              registry,
                                              sharedLoader,
                                              STUB_INVOKER,
                                              noResources(),
                                              Option.some((art, classLoader) -> Option.some(IntrinsicConfigProvider.intrinsicConfigProvider("node", Map.of("watched.key", "v")))),
                                              Option.some(SliceCodec.sliceCodec(List.of())),
                                              SliceLoadingContext.noResourceOverlay(),
                                              Option.some(feed))
                          .await();
        assertThat(registry.lookup(CODEC_THROWS).isPresent() && feed.count(CODEC_THROWS) == 1).as("precondition: registered and subscribed by the earlier resolution").isTrue();
        var withCodec = storeWithNodeCodec();

        var loaded = withCodec.loadSlice(CODEC_THROWS).await();

        assertThat(loaded.isFailure()).as("the throw is a failed load").isTrue();
        loaded.onSuccess(_ -> Assertions.fail("the load of a slice whose codec throws succeeded"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(SliceLoadingFailure.Fatal.CodecBindFailed.class));
        assertThat(withCodec.entries()).as("no half entry is left in the map").doesNotContainKey(CODEC_THROWS);
        assertThat(registry.lookup(CODEC_THROWS).isPresent()).as("the earlier load still owns the registered instance").isTrue();
        assertThat(feed.count(CODEC_THROWS)).isEqualTo(1);

        var later = store.loadSlice(CODEC_THROWS).await();

        assertThat(later.isSuccess()).as("a store with no codec scoping loads the same artifact: nothing was poisoned").isTrue();
        assertThat(store.entries()).containsKey(CODEC_THROWS);
    }

    private sliceStore storeWithNodeCodec() {
        return (sliceStore) SliceStore.sliceStore(registry,
                                                  List.of(repository),
                                                  sharedLoader,
                                                  STUB_INVOKER,
                                                  noResources(),
                                                  SliceActionConfig.sliceActionConfig(),
                                                  Option.some(IntrinsicConfigProvider.intrinsicConfigProvider("node", Map.of("watched.key", "v"))),
                                                  Option.some(SliceCodec.sliceCodec(List.of())),
                                                  Option.empty(),
                                                  SliceLoadingContext.noResourceOverlay(),
                                                  Option.some(feed));
    }

    // ---------------------------------------------------------------- scaffolding

    private static Artifact artifact(String coordinates) {
        return Artifact.artifact(coordinates).unwrap();
    }

    private URL jar(Artifact sliceArtifact, String sliceClass, String dependencies) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Slice-Artifact", sliceArtifact.asString());
        manifest.getMainAttributes().putValue("Slice-Class", sliceClass);
        var path = tempDir.resolve(sliceArtifact.asString().replace(":", "-") + ".jar");

        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            out.putNextEntry(new JarEntry("META-INF/dependencies/" + sliceClass));
            out.write(dependencies.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path.toUri().toURL();
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

    /// Counts live subscriptions per owner; `releaseAll` drops an owner's, `shutdown` drops all.
    private static final class CountingFeed implements ConfigChangeFeed {
        private final Map<String, AtomicInteger> live = new ConcurrentHashMap<>();

        int count(Artifact artifact) {
            return subscriberCount(artifact.asString());
        }

        @Override
        public <T> Result<Watched.Subscription> subscribe(String owner, String section, Fn0<Result<T>> bind, Fn1<Promise<Unit>, T> callback) {
            live.computeIfAbsent(owner, _ -> new AtomicInteger()).incrementAndGet();
            return Result.success(Watched.Subscription.noop());
        }

        @Override
        public Unit publish(String key) {
            return Unit.unit();
        }

        @Override
        public Promise<Unit> releaseAll(String owner) {
            live.remove(owner);
            return Promise.unitPromise();
        }

        @Override
        public int subscriberCount(String owner) {
            var counter = live.get(owner);

            return counter == null
                   ? 0
                   : counter.get();
        }

        @Override
        public Promise<Unit> shutdown() {
            live.clear();
            return Promise.unitPromise();
        }
    }
}
