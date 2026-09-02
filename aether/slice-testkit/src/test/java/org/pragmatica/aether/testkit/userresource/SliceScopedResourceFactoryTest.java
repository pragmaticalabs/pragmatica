// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.userresource;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.SliceScopedResourceProvider;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingContext;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A user-defined resource TYPE must be provisionable by a user-defined [ResourceFactory] that
/// ships inside the slice jar (#773).
///
/// This exercises the REAL [SpiResourceProvider] rather than the kit's `withResource` map, because
/// the whole defect lives in the SPI lookup: the node's factory registry is built once at node boot
/// from the thread-context classloader, and a resource class defined by a `SliceClassLoader`
/// minutes later can never be a key in it. The fixture reproduces exactly that geometry — the
/// factory's `META-INF/services` descriptor exists ONLY inside the jar, and the resource `Class`
/// used at the call site is the slice loader's own definition.
///
/// No ports, no cluster: the premise is falsifiable with a classloader and a jar.
class SliceScopedResourceFactoryTest {
    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();
    private static final String CONFIG_SECTION = "user-resource";
    private static final String CONFIG_VALUE = "user-resource-config";
    private static final String SLICE_ID = "org.example:user-slice:1.0.0";

    @Test
    void provide_resolvesSliceSuppliedFactory_forResourceTypeDefinedBySliceLoader(@TempDir Path tempDir) throws Exception {
        var jar = packageSliceJar(tempDir);

        try (var sliceLoader = new SliceClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var sliceResourceType = sliceLoader.loadClass(UserResource.class.getName());
            var nodeProvider = spiResourceProvider(_ -> Result.success(CONFIG_VALUE));

            // The two premises the ticket rests on. If either of these breaks, the test below is
            // measuring something other than the reported defect.
            assertThat(sliceResourceType).describedAs("slice loader must define its OWN copy of the resource type")
                                         .isNotSameAs(UserResource.class);
            assertThat(nodeProvider.hasFactory(sliceResourceType)).describedAs("node-boot registry must not know the slice's type")
                                                                  .isFalse();

            var context = loadingContextFor(sliceLoader, nodeProvider);
            var result = context.resources()
                                .provide(sliceResourceType, CONFIG_SECTION)
                                .await(TIMEOUT);
            var resource = result.fold(cause -> fail("provisioning failed: " + cause.getClass().getName() + ": " + cause.message()),
                                       value -> value);

            assertThat(resource.getClass()).describedAs("the instance must come from the SLICE's factory, not a node-loader twin")
                                           .isSameAs(sliceResourceType);
            assertThat(resource.getClass().getClassLoader()).isSameAs(sliceLoader);
        }
    }

    /// The generated slice factory emits the no-context overload for a plain resource dependency
    /// (`FactoryClassGenerator:1811`), but interceptors and context-carrying resources use the
    /// three-argument one. Both have to reach the slice-scoped factory.
    @Test
    void provideWithContext_resolvesSliceSuppliedFactory(@TempDir Path tempDir) throws Exception {
        var jar = packageSliceJar(tempDir);

        try (var sliceLoader = new SliceClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var sliceResourceType = sliceLoader.loadClass(UserResource.class.getName());
            var context = loadingContextFor(sliceLoader, spiResourceProvider(_ -> Result.success(CONFIG_VALUE)));
            var result = context.resources()
                                .provide(sliceResourceType,
                                         CONFIG_SECTION,
                                         ProvisioningContext.provisioningContext())
                                .await(TIMEOUT);
            var resource = result.fold(cause -> fail("provisioning failed: " + cause.getClass().getName() + ": " + cause.message()),
                                       value -> value);

            assertThat(resource.getClass()).isSameAs(sliceResourceType);
        }
    }

    /// The overlay must not swallow genuinely unknown types: a type no factory anywhere claims still
    /// has to surface the node provider's named failure rather than a silent success or an NPE.
    @Test
    void provide_stillFailsWithFactoryNotFound_forTypeNoFactoryClaims(@TempDir Path tempDir) throws Exception {
        var jar = packageSliceJar(tempDir);

        try (var sliceLoader = new SliceClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var context = loadingContextFor(sliceLoader, spiResourceProvider(_ -> Result.success(CONFIG_VALUE)));
            var result = context.resources()
                                .provide(Integer.class, CONFIG_SECTION)
                                .await(TIMEOUT);
            var cause = result.fold(failure -> failure, value -> fail("expected failure, got " + value));

            assertThat(cause).isInstanceOf(SliceLoadingFailure.Fatal.ResourceFactoryNotFound.class);
        }
    }

    /// The overlay must contain ONLY what the slice loader itself defined.
    ///
    /// `ServiceLoader` walks the whole delegation chain, so an unfiltered scan through a slice loader
    /// also returns every platform factory the node registered at boot — and an overlay holding those
    /// would hand each slice its own connection pool, stream publisher and cache, because the overlay
    /// carries its own promise cache. A slice jar with no factory of its own must therefore produce no
    /// overlay at all, even though the scan can plainly see the built-ins.
    @Test
    void sliceScopedProvider_isAbsent_whenSliceShipsNoFactoryOfItsOwn(@TempDir Path tempDir) throws Exception {
        var jar = packageJarWithoutServiceDescriptor(tempDir);

        // Control: if the parent classpath carried no factories, this test would pass for the wrong
        // reason — there would be nothing for the filter to exclude.
        assertThat(ServiceLoader.load(ResourceFactory.class).stream().count()).describedAs("built-in factories must be visible through the parent, or the filter is untested")
                                                                              .isGreaterThan(0L);

        try (var sliceLoader = new SliceClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var overlay = SliceScopedResourceProvider.sliceScopedResourceProvider(sliceLoader,
                                                                                  facadeOf(spiResourceProvider(_ -> Result.success(CONFIG_VALUE))),
                                                                                  (_, _) -> Result.success(CONFIG_VALUE));

            assertThat(overlay.isPresent()).describedAs("parent-loaded built-in factories must not enter the slice overlay")
                                           .isFalse();
        }
    }

    /// Build the loading context the way `AetherNode` wires one: node provider underneath, the
    /// slice-scoped overlay builder injected through the same seam, materialized with the slice's
    /// classloader.
    private static SliceLoadingContext loadingContextFor(ClassLoader sliceClassLoader, SpiResourceProvider nodeProvider) {
        var nodeFacade = facadeOf(nodeProvider);
        var context = SliceLoadingContext.sliceLoadingContext(NoOpInvoker.INSTANCE, nodeFacade, SLICE_ID);

        context.setResourceOverlayBuilder(loader -> SliceScopedResourceProvider.sliceScopedResourceProvider(loader,
                                                                                                            nodeFacade,
                                                                                                            (_, _) -> Result.success(CONFIG_VALUE)));
        context.materializeComposite(sliceClassLoader);

        return context;
    }

    /// A slice jar carrying classes but NO `META-INF/services` entry — a slice that defines no
    /// resource factory of its own, which is the overwhelmingly common case.
    private static Path packageJarWithoutServiceDescriptor(Path tempDir) throws IOException {
        var jar = tempDir.resolve("plain-slice.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            var name = classEntryName(UserResource.class);

            writeEntry(out, name, classBytes(name));
        }

        return jar;
    }

    /// Build the throw-away slice jar: the user's resource type, the user's factory, and the
    /// `META-INF/services` descriptor that `PackageSlicesMojo` already preserves in a real slice jar.
    private static Path packageSliceJar(Path tempDir) throws IOException {
        var jar = tempDir.resolve("user-slice.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var name : List.of(classEntryName(UserResource.class), classEntryName(UserResourceFactory.class))) {
                writeEntry(out, name, classBytes(name));
            }

            writeEntry(out,
                       "META-INF/services/" + ResourceFactory.class.getName(),
                       UserResourceFactory.class.getName().getBytes(StandardCharsets.UTF_8));
        }

        return jar;
    }

    private static String classEntryName(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    private static byte[] classBytes(String entryName) throws IOException {
        try (var stream = SliceScopedResourceFactoryTest.class.getClassLoader().getResourceAsStream(entryName)) {
            if (stream == null) {
                throw new IOException("fixture class not on the test classpath: " + entryName);
            }

            return stream.readAllBytes();
        }
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static ResourceProviderFacade facadeOf(SpiResourceProvider provider) {
        return new ResourceProviderFacade() {
            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return provider.provide(resourceType, configSection);
            }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                return provider.provide(resourceType, configSection, context);
            }

            @Override
            public Promise<Unit> releaseAll(String sliceId) {
                return provider.releaseAll(sliceId);
            }
        };
    }

    private enum NoOpInvoker implements SliceInvokerFacade {
        INSTANCE;
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                              String methodName,
                                                              TypeToken<T> requestType,
                                                              TypeToken<R> responseType) {
            return org.pragmatica.lang.utils.Causes.cause("slice-to-slice invocation is not used by this test")
                                                   .result();
        }
    }
}
