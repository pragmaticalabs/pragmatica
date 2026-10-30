// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ConfigChangeFeed;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingContext;
import org.pragmatica.aether.slice.Watched;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #774: a slice whose factory subscribed and then FAILED was never registered, so no unload will ever release
/// it: the failed `createSliceFromClass` promise is the release seam, at both creation sites (no `[slices]`
/// dependencies, and with them). Every slice - the one asked for and each dependency - subscribes under its OWN
/// artifact.
class DependencyResolverConfigWatchTest {
    private static final String FAILING = "org.example:failing:1.0.0";
    private static final String DEP = "org.example:dep:1.0.0";
    private static final String HEALTHY = "org.example:healthy:1.0.0";
    private static final String FAILING_CLASS = "org.pragmatica.aether.slice.dependency.WatchingFailingFactory";
    private static final String DEP_CLASS = "org.pragmatica.aether.slice.dependency.WatchingDepFactory";
    private static final SliceInvokerFacade NO_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact, String method, TypeToken<T> requestType, TypeToken<R> responseType) {
            return Causes.cause("no invoker in this test").result();
        }
    };

    @TempDir
    Path tempDir;

    private final SharedLibraryClassLoader sharedLoader = new SharedLibraryClassLoader(getClass().getClassLoader());
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final SliceRegistry registry = SliceRegistry.sliceRegistry();

    @AfterEach
    void closeLoader() throws Exception {
        sharedLoader.close();
    }

    @Test
    void creationFailure_withNoSliceDependencies_releasesThatSlicesSubscriptions() throws Exception {
        var repository = repository(Map.of(FAILING, jar(FAILING, FAILING_CLASS, "")));

        var result = resolve(FAILING, repository).await();

        assertThat(result.isFailure()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> log.contains("release " + FAILING));
        assertThat(log).containsExactly("subscribe " + FAILING, "release " + FAILING);
        assertThat(registry.lookup(artifact(FAILING)).isPresent()).as("the failed slice was never registered").isFalse();
    }

    @Test
    void creationFailure_withSliceDependencies_releasesOnlyTheFailedSlice_notItsRegisteredDependency() throws Exception {
        var repository = repository(Map.of(FAILING, jar(FAILING, FAILING_CLASS, "[slices]\n" + DEP + "\n"),
                                           DEP, jar(DEP, DEP_CLASS, "")));

        var result = resolve(FAILING, repository).await();

        assertThat(result.isFailure()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> log.contains("release " + FAILING));
        assertThat(log).as("each slice subscribed under its own artifact; only the failed one was released")
                       .containsExactly("subscribe " + DEP, "subscribe " + FAILING, "release " + FAILING);
        assertThat(registry.lookup(artifact(DEP)).isPresent()).as("the dependency is registered and live").isTrue();
        assertThat(registry.lookup(artifact(FAILING)).isPresent()).isFalse();
    }

    @Test
    void successfulCreation_releasesNothing() throws Exception {
        var repository = repository(Map.of(HEALTHY, jar(HEALTHY, DEP_CLASS, "")));

        var result = resolve(HEALTHY, repository).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(log).containsExactly("subscribe " + HEALTHY);
    }

    // ---------------------------------------------------------------- scaffolding

    private Promise<DependencyResolver.ResolvedSlice> resolve(String coordinates, Repository repository) {
        return DependencyResolver.resolveWithContext(artifact(coordinates),
                                                     repository,
                                                     registry,
                                                     sharedLoader,
                                                     NO_INVOKER,
                                                     noResources(),
                                                     Option.some((art, classLoader) -> sliceComposite(art)),
                                                     Option.empty(),
                                                     SliceLoadingContext.noResourceOverlay(),
                                                     Option.some(new LoggingFeed(log)));
    }

    private static Option<ConfigurationProvider> sliceComposite(Artifact art) {
        return Option.some(IntrinsicConfigProvider.intrinsicConfigProvider(art.asString(), Map.of("watched.key", "v")));
    }

    private static Artifact artifact(String coordinates) {
        return Artifact.artifact(coordinates).unwrap();
    }

    private static Repository repository(Map<String, URL> jars) {
        return art -> Promise.success(new Location(art, jars.get(art.asString())));
    }

    private URL jar(String sliceArtifact, String sliceClass, String dependencies) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Slice-Artifact", sliceArtifact);
        manifest.getMainAttributes().putValue("Slice-Class", sliceClass);
        var path = tempDir.resolve(sliceArtifact.replace(":", "-") + ".jar");

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

    private record LoggingFeed(List<String> log) implements ConfigChangeFeed {
        @Override
        public <T> Result<Watched.Subscription> subscribe(String owner, String section, Fn0<Result<T>> bind, Fn1<Promise<Unit>, T> callback) {
            log.add("subscribe " + owner);
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
