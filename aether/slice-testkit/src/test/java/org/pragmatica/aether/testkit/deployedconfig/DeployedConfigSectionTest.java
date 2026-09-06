// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.deployedconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingContext;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A deployed slice declaring `@ResourceQualifier(type = ConfigurationSection.class)` must receive
/// its parsed config record, populated from the real configuration (#889).
///
/// This drives [SliceStore#loadSlice] — the actual deployment entry point, the one
/// `NodeDeploymentState` calls — against a real slice jar on disk: manifest, child-first
/// `SliceClassLoader`, `DependencyResolver`, the deferred slice-composite builder, and the
/// processor-generated `EndpointProbeFactory`. Nothing about the context is hand-built, which is
/// the whole point: a test that constructs its own `SliceCreationContext` through the
/// config-carrying overload bypasses precisely the code that was broken and would have passed
/// against the defect.
///
/// No ports and no cluster. The premise is falsifiable with a jar and a config provider.
class DeployedConfigSectionTest {
    private static final TimeSpan TIMEOUT = timeSpan(10).seconds();
    private static final String ARTIFACT_COORDS = "org.example:endpoint-probe:1.0.0";
    private static final String PACKAGE_PATH = "org/pragmatica/aether/testkit/deployedconfig";
    private static final String FACTORY_CLASS = "org.pragmatica.aether.testkit.deployedconfig.EndpointProbeFactory";
    private static final String ENVELOPE_VERSION = "1007";
    private static final String CONFIG_SECTION = "deployed.endpoint";

    /// The values the deployed slice must actually observe. Distinctive on purpose — a facade that
    /// returned defaults, empties or zeroes could not produce this string.
    private static final Map<String, String> DEPLOYED_CONFIG = Map.of(CONFIG_SECTION + ".host", "endpoint.internal",
                                                                       CONFIG_SECTION + ".port", "8443",
                                                                       CONFIG_SECTION + ".secure", "true",
                                                                       CONFIG_SECTION + ".tags", "alpha, beta",
                                                                       CONFIG_SECTION + ".weight", "7");

    private static final String EXPECTED_RENDER = "endpoint.internal|8443|true|alpha+beta|7";

    @Test
    void deployedSlice_receivesParsedConfigRecord_withRealValues(@TempDir Path tempDir) throws Exception {
        var slice = loadDeployedSlice(tempDir, Option.some(nodeComposite()));

        assertThat(describe(slice)).describedAs("the deployed slice must observe the configured values, not a no-op facade's failures")
                                   .isEqualTo("probe=" + EXPECTED_RENDER);
    }

    /// The premise check, and the reason the assertion above means what it says.
    ///
    /// With no node-composite there is no slice-composite to serve, `SliceLoadingContext#config`
    /// falls back to the delegate's `NoOpConfigFacade`, and creation must FAIL. If this passed,
    /// the test above would be satisfied by something other than the config path — and the
    /// pre-#889 runtime is exactly this state on every deployment.
    @Test
    void deployedSlice_failsToCreate_whenNoConfigurationIsAvailable(@TempDir Path tempDir) throws Exception {
        var jar = packageSliceJar(tempDir);
        var result = storeFor(jar, Option.none()).loadSlice(artifact())
                                                 .await(TIMEOUT);

        assertThat(result.isFailure()).describedAs("a config-section slice cannot be created against a facade that answers nothing")
                                      .isTrue();
    }

    /// Cross-loader sanity: the slice really was defined by the deployment's own child-first loader,
    /// so the value asserted above travelled the same path a deployed slice's would. Without this,
    /// a regression that quietly loaded the test's own classes would look identical.
    @Test
    void deployedSlice_isDefinedBySliceClassLoader_notTheTestClasspath(@TempDir Path tempDir) throws Exception {
        var slice = loadDeployedSlice(tempDir, Option.some(nodeComposite()));
        var probeInterface = probeInterfaceOf(slice);

        assertThat(probeInterface).describedAs("the deployed slice must use the slice loader's own copy of the interface")
                                  .isNotSameAs(EndpointProbe.class);
        assertThat(slice.getClass().getClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    private Slice loadDeployedSlice(Path tempDir, Option<ConfigurationProvider> nodeComposite) throws IOException {
        var jar = packageSliceJar(tempDir);

        return storeFor(jar, nodeComposite).loadSlice(artifact())
                                           .await(TIMEOUT)
                                           .fold(cause -> fail("slice load failed: " + cause.message()),
                                                 SliceStore.LoadedSlice::slice);
    }

    /// Invoke the slice's own method across the classloader boundary.
    ///
    /// The `Method` is taken from the SLICE LOADER's copy of the public interface rather than from
    /// the implementing record, which is a local class and therefore not publicly accessible. The
    /// return travels as a `String`, a JDK type both loaders share.
    private static String describe(Slice slice) throws Exception {
        var method = probeInterfaceOf(slice).getMethod("describe", String.class);
        var promise = (Promise<?>) method.invoke(slice, "probe");

        return promise.await(TIMEOUT)
                      .fold(cause -> fail("describe failed: " + cause.message()), String::valueOf);
    }

    private static Class<?> probeInterfaceOf(Slice slice) throws ClassNotFoundException {
        return slice.getClass()
                    .getClassLoader()
                    .loadClass(EndpointProbe.class.getName());
    }

    private static ConfigurationProvider nodeComposite() {
        return IntrinsicConfigProvider.intrinsicConfigProvider("node.toml", DEPLOYED_CONFIG);
    }

    private static Artifact artifact() {
        return Artifact.artifact(ARTIFACT_COORDS).unwrap();
    }

    /// Wire the store the way `AetherNode` does, minus the parts this slice does not use: a
    /// repository that serves the jar, a fresh registry, and the node-composite under test. The
    /// resource facade refuses everything on purpose — [EndpointProbe] declares no resources, so a
    /// working one could only mask a failure.
    private static SliceStore storeFor(Path jar, Option<ConfigurationProvider> nodeComposite) {
        return SliceStore.sliceStore(SliceRegistry.sliceRegistry(),
                                     List.of(repositoryServing(jar)),
                                     new SharedLibraryClassLoader(DeployedConfigSectionTest.class.getClassLoader()),
                                     REFUSING_INVOKER,
                                     REFUSING_RESOURCES,
                                     SliceActionConfig.sliceActionConfig(),
                                     nodeComposite,
                                     Option.none(),
                                     Option.none(),
                                     SliceLoadingContext.noResourceOverlay());
    }

    private static Repository repositoryServing(Path jar) {
        return artifact -> Location.location(artifact, toUrl(jar))
                                   .async();
    }

    private static java.net.URL toUrl(Path jar) {
        return Result.lift(Causes::fromThrowable, () -> jar.toUri().toURL())
                     .expect("temp jar path must be a valid URL");
    }

    /// Package the compiled fixture — including the processor-generated `EndpointProbeFactory` and
    /// every synthetic local-record class both it and [EndpointProbe] produce — into a slice jar
    /// shaped the way `PackageSlicesMojo` shapes a real one.
    ///
    /// The whole package directory is swept rather than a hand-listed set of classes: the local
    /// records compile to synthetic names (`EndpointProbeFactory$1endpointProbeSlice`) that a list
    /// would silently miss, and a missing class surfaces as a confusing load failure rather than as
    /// a packaging error.
    private static Path packageSliceJar(Path tempDir) throws IOException {
        var jar = tempDir.resolve("endpoint-probe.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(jar), sliceManifest())) {
            for (var classFile : sliceClassFiles()) {
                writeEntry(out, PACKAGE_PATH + "/" + classFile.getFileName(), Files.readAllBytes(classFile));
            }
        }

        return jar;
    }

    private static Manifest sliceManifest() {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();

        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, ARTIFACT_COORDS);
        attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, FACTORY_CLASS);
        attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, ENVELOPE_VERSION);

        return manifest;
    }

    /// Every compiled class of the fixture package EXCEPT this test's own — the test never runs
    /// inside the slice loader, and shipping it would only add a class nobody loads.
    private static List<Path> sliceClassFiles() throws IOException {
        try (var files = Files.list(packageDirectory())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".class"))
                        .filter(path -> !path.getFileName().toString().startsWith(DeployedConfigSectionTest.class.getSimpleName()))
                        .sorted()
                        .toList();
        }
    }

    private static Path packageDirectory() {
        var url = DeployedConfigSectionTest.class.getClassLoader()
                                                  .getResource(PACKAGE_PATH);

        return Result.lift(Causes::fromThrowable, () -> Path.of(url.toURI()))
                     .expect("fixture package must be on the test classpath as a directory");
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static final SliceInvokerFacade REFUSING_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact,
                                                              String method,
                                                              TypeToken<T> requestType,
                                                              TypeToken<R> responseType) {
            return Causes.cause("Slice invocation not configured for this fixture").result();
        }
    };

    private static final ResourceProviderFacade REFUSING_RESOURCES = new ResourceProviderFacade() {
        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return Causes.cause("EndpointProbe must declare no resources").promise();
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
            return Causes.cause("EndpointProbe must declare no resources").promise();
        }

        @Override
        public Promise<Unit> releaseAll(String sliceId) {
            return Promise.unitPromise();
        }
    };
}
