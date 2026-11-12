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
import java.util.concurrent.atomic.AtomicReference;
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
import org.pragmatica.lang.Cause;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #774, the subscription lifecycle through a REAL `SliceStore` loading real slice jars: a subscription belongs to
/// the slice ARTIFACT that declares it, lives from the factory to that artifact's own unload, and is released by
/// nothing else. The feed here counts subscriptions per owner and releases them on `releaseAll`, as the node's does.
@Timeout(120)
class SliceStoreConfigWatchLifecycleTest {
    private static final String DEP_CLASS = "org.pragmatica.aether.slice.dependency.WatchingDepFactory";
    private static final String CODEC_THROWS_CLASS = "org.pragmatica.aether.slice.dependency.WatchingCodecThrowsFactory";
    private static final String SLOW_CLASS = "org.pragmatica.aether.slice.dependency.WatchingSlowFactory";
    private static final Artifact SLOW = artifact("org.example:slow:1.0.0");
    private static final Artifact X = artifact("org.example:x:1.0.0");
    private static final Artifact Y = artifact("org.example:y:1.0.0");
    private static final Artifact D = artifact("org.example:d:1.0.0");
    private static final Artifact CODEC_THROWS = artifact("org.example:codec-throws:1.0.0");
    private static final Artifact CODEC_THROWS_DEP = artifact("org.example:codec-throws-dep:1.0.0");
    private static final int FAILED_LOAD_ATTEMPTS = 2_000;
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
                          CODEC_THROWS_DEP.asString(), jar(CODEC_THROWS_DEP, CODEC_THROWS_CLASS, "[slices]\n" + D.asString() + "\n"),
                          D.asString(), jar(D, DEP_CLASS, ""),
                          X.asString(), jar(X, DEP_CLASS, "[slices]\n" + D.asString() + "\n"),
                          Y.asString(), jar(Y, DEP_CLASS, "[slices]\n" + D.asString() + "\n"));
        repository = art -> Promise.success(new Location(art, jars.get(art.asString())));

        store = (sliceStore) SliceStore.sliceStore(registry,
                                                   List.of(repository),
                                                   sharedLoader,
                                                   STUB_INVOKER,
                                                   resources,
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
        assertCodecBindFailure(loaded, CODEC_THROWS);
        assertThat(withCodec.loaded()).as("no SliceStore entry was ever created").isEmpty();
        // The unwind is a failure callback of the load's Promise and may run just after `await()` returns.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(registry.lookup(CODEC_THROWS).isPresent()).as("a slice whose load failed must not stay registered").isFalse();
            assertThat(feed.count(CODEC_THROWS)).as("nor keep its subscription").isZero();
        });
    }

    @Test
    void aFailedLoadWithSliceDependencies_isTheSameTypedFailureNamingTheTopLevelSlice_andKeepsItsRegisteredDependency() {
        var withCodec = storeWithNodeCodec();

        var loaded = withCodec.loadSlice(CODEC_THROWS_DEP).await();

        assertCodecBindFailure(loaded, CODEC_THROWS_DEP);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(registry.lookup(CODEC_THROWS_DEP).isPresent()).as("the failed top-level slice is unwound").isFalse();
            assertThat(feed.count(CODEC_THROWS_DEP)).isZero();
        });
        assertThat(registry.lookup(D).isPresent() && feed.count(D) == 1).as("its dependency was created by this load but stays: it is registered and live").isTrue();
    }

    /// The failure is the typed `CodecBindFailed`, names the artifact whose load failed, and carries the slice's own
    /// exception as its source.
    private static void assertCodecBindFailure(Result<SliceStore.LoadedSlice> loaded, Artifact artifact) {
        loaded.onSuccess(_ -> Assertions.fail("the load of a slice whose codec throws succeeded"))
              .onFailure(cause -> assertCodecBindCause(cause, artifact));
    }

    private static void assertCodecBindCause(Cause cause, Artifact artifact) {
        assertThat(cause).isInstanceOfSatisfying(SliceLoadingFailure.Fatal.CodecBindFailed.class,
                                                 failure -> assertThat(failure.artifact()).isEqualTo(artifact.asString()));
        assertThat(cause.message()).contains(artifact.asString());
        assertThat(cause.source().isPresent()).as("the cause keeps the slice's own failure as its source").isTrue();
        cause.source().onPresent(source -> assertThat(source.message()).contains("codec(parent) blew up"));
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
        assertCodecBindFailure(loaded, CODEC_THROWS);
        assertThat(withCodec.entries()).as("no half entry is left in the map").doesNotContainKey(CODEC_THROWS);
        assertThat(registry.lookup(CODEC_THROWS).isPresent()).as("the earlier load still owns the registered instance").isTrue();
        assertThat(feed.count(CODEC_THROWS)).isEqualTo(1);

        var later = store.loadSlice(CODEC_THROWS).await();

        assertThat(later.isSuccess()).as("a store with no codec scoping loads the same artifact: nothing was poisoned").isTrue();
        assertThat(store.entries()).containsKey(CODEC_THROWS);
    }

    /// #2571: a failed load has left the map by the time its failure is visible, so a retry right after it starts
    /// a new load. The eviction used to be an independent action that could run after the caller had resumed:
    /// [#aThrowingCodecOfAnAlreadyRegisteredSlice_isAFailedLoad_notAThrowOutOfComputeIfAbsent_andALaterLoadWorks]
    /// failed in CI on exactly this check, with the key present when checked and gone when printed. Repeated
    /// because the old order lost only some of the races. The location fails on another thread, as a real load does:
    /// an already-failed promise runs even an independent action inline, which hides the race.
    @Test
    void aFailedLoad_hasLeftTheMap_byTheTimeItsFailureIsSeen() {
        var failing = storeOver(art -> Promise.<Location>promise(pending -> Thread.ofVirtual()
                                                                                 .start(() -> pending.fail(Causes.cause("no location for "
                                                                                                                       + art.asString())))));

        for (int attempt = 0; attempt < FAILED_LOAD_ATTEMPTS; attempt++) {
            var loaded = failing.loadSlice(X).await();

            assertThat(loaded.isFailure()).as("control: attempt %d fails", attempt).isTrue();
            assertThat(failing.entries()).as("attempt %d: no failed entry once the failure is seen", attempt)
                                         .doesNotContainKey(X);
        }
    }

    /// #2571: a failed load's eviction removes that load only. Two callers wait on one load; the first one's own
    /// dependent step retries at once, which starts a NEW load under the same key before the second caller's eviction
    /// runs. Evicting by key alone took the retry's in-flight load out of the map. Single-threaded and deterministic:
    /// the waiters' dependent steps run in attach order on the thread that fails the location.
    @Test
    void aSecondWaitersEviction_leavesTheRetrysNewLoadInTheMap() {
        var firstLocation = Promise.<Location>promise();
        var lookups = new AtomicInteger();
        var shared = storeOver(_ -> lookups.getAndIncrement() == 0
                                    ? firstLocation
                                    : Promise.<Location>promise());
        var retry = new AtomicReference<Promise<SliceStore.LoadedSlice>>();
        var first = shared.loadSlice(X)
                          .fold(result -> {
                                    retry.set(shared.loadSlice(X));

                                    return result.async();
                                });
        var second = shared.loadSlice(X);

        await().atMost(Duration.ofSeconds(10)).until(() -> lookups.get() == 1);
        firstLocation.fail(Causes.cause("no location for " + X.asString()));

        assertThat(first.await().isFailure()).as("control: the shared load failed").isTrue();
        assertThat(second.await().isFailure()).as("control: both waiters saw the failure").isTrue();
        assertThat(retry.get()).as("control: the retry ran").isNotNull();
        assertThat(retry.get().isResolved()).as("the retry started a new load instead of getting the failed one back")
                                            .isFalse();
        assertThat(shared.entries()).as("the second waiter's eviction left the retry's new load in the map")
                                    .containsKey(X);
    }

    private sliceStore storeOver(Repository source) {
        return (sliceStore) SliceStore.sliceStore(registry,
                                                  List.of(source),
                                                  sharedLoader,
                                                  STUB_INVOKER,
                                                  noResources(),
                                                  SliceActionConfig.sliceActionConfig(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  SliceLoadingContext.noResourceOverlay(),
                                                  Option.some(feed));
    }

    private sliceStore storeWithNodeCodec() {
        return (sliceStore) SliceStore.sliceStore(registry,
                                                  List.of(repository),
                                                  sharedLoader,
                                                  STUB_INVOKER,
                                                  resources,
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

    /// #2975: records every scope the store releases resources for.
    private final RecordingResources resources = new RecordingResources();

    private static final class RecordingResources implements ResourceProviderFacade {
        private final List<String> released = new java.util.concurrent.CopyOnWriteArrayList<>();

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
            released.add(sliceId);

            return Promise.unitPromise();
        }
    }

    /// T18 (#2975): a load that registered the slice and then failed releases the resources it provisioned, under the
    /// slice's own scope, through the same facade call an unload's generated `stop()` makes. A load that did not
    /// register the slice releases nothing: the live instance owns those resources.
    @Test
    void unwindingAFailedLoad_releasesTheResourcesItProvisioned_onlyWhenThisLoadRegisteredTheSlice() {
        store.unwindFailedLoad(D, true).await();

        assertThat(resources.released).as("an earlier load owns the instance and its resources").isEmpty();

        store.unwindFailedLoad(D, false).await();

        assertThat(resources.released).containsExactly(D.asString());
    }

    /// T18 through a real failed load: the codec of the slice throws after the slice was created and registered.
    @Test
    void aLoadFailingAfterCreation_releasesTheSlicesResources() {
        assertThat(storeWithNodeCodec().loadSlice(CODEC_THROWS).await().isFailure()).as("precondition: the load fails").isTrue();

        // The unwind is attached as an event on the failed load, so it may complete just after the caller sees the failure.
        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(resources.released).containsExactly(CODEC_THROWS.asString()));
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
