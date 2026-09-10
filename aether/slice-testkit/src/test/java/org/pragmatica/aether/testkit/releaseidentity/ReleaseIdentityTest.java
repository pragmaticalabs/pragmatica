// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.releaseidentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.pragmatica.aether.resource.SliceScopedResourceProvider;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingContext;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.testkit.trackedresource.TrackedResourceFactory;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Functions.Fn2;
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

/// Unloading a deployed slice must CLOSE the resources it provisioned (#892).
///
/// This drives `SliceStore.loadSlice` / `activateSlice` / `unloadSlice` — the deployment entry
/// points `NodeDeploymentState` calls — against a real slice jar on disk: manifest, child-first
/// `SliceClassLoader`, `DependencyResolver`, the real `SpiResourceProvider`, the node's own
/// wiring of `SliceScopedResourceProvider`, and the processor-generated `ReleaseProbeFactory`
/// whose `stop()` carries the literal THE GENERATOR emitted. Nothing about the release identity is
/// hand-written here, which is the whole point: the defect was two independently computed strings
/// disagreeing, so a test that supplies either of them itself cannot see it.
///
/// Three independent silent successes stood between a slice unload and a closed resource, each
/// sufficient on its own and each hiding the next:
///
///   1. the node's `ResourceProviderFacade` never overrode `releaseAll`, so the release stopped at
///      the interface's `Promise.unitPromise()` default and no provider was ever reached (#892);
///   2. the release identity could not match a provisioning scope even when it arrived (#892);
///   3. the close dispatch missed the project's own `AsyncCloseable` convention (#891) — which is
///      why [org.pragmatica.aether.testkit.trackedresource.TrackedResource] implements exactly
///      that one and no other.
///
/// Every assertion is on whether the RESOURCE was closed, never on whether a promise succeeded:
/// all three defects reported success while doing nothing.
///
/// SCOPE, stated so it is not read as more than it is: this is the node's slice-lifecycle path
/// in-process, not a running cluster. No ports, no consensus, no HTTP. What it does establish is
/// that the close happens through the deployment code path rather than through a hand-built
/// context.
class ReleaseIdentityTest {
    private static final TimeSpan TIMEOUT = timeSpan(10).seconds();

    /// The deployed coordinate, in the shape `PackageSlicesMojo` produces for this very slice:
    /// `groupId:artifactId-kebab(SliceName):version`. Its first two segments are therefore exactly
    /// what the generator emits into `stop()`, so the two strings are a NEAR miss — the hardest
    /// case for the fix, not the easiest.
    private static final String ARTIFACT_COORDS = "org.pragmatica-lite.aether:slice-testkit-release-probe:1.0.0";

    private static final String PACKAGE_PATH = "org/pragmatica/aether/testkit/releaseidentity";
    private static final String FACTORY_CLASS = "org.pragmatica.aether.testkit.releaseidentity.ReleaseProbeFactory";
    private static final String ENVELOPE_VERSION = "1007";

    /// The slice's own configuration layer, shipped inside the jar as `META-INF/resources.toml`
    /// the way `PackageSlicesMojo` ships it. One section, for the one resource the slice declares.
    private static final String SLICE_RESOURCES_TOML = """
            [tracked.resource]
            name = "release-probe"
            """;

    /// The provider's constructor-supplied loader, which a DEPLOYED slice must never reach:
    /// `SpiResourceProvider.resolveConfigLoader` prefers a loader derived from the
    /// `ConfigurationProvider` the provisioning context carries, and that context is the slice
    /// composite. Refusing here rather than returning a plausible value is deliberate — a stub
    /// would let the test keep passing if the composite ever stopped reaching the provider, which
    /// is exactly the wiring under measurement.
    private static final Fn2<Result<?>, String, Class<?>> REFUSING_FALLBACK_LOADER =
        (section, _) -> Causes.cause("resource config must resolve through the slice composite, not the fallback loader: " + section)
                              .result();

    /// The premise, MEASURED rather than asserted from the ticket.
    ///
    /// The generated `stop()` is run against a context that knows no slice id, which is the one
    /// arrangement where its argument reaches a provider unchanged — so what the recorder captures
    /// IS the literal `FactoryClassGenerator.computeSliceArtifactCoordinate` emitted, taken from
    /// the current processor's output rather than typed here.
    ///
    /// The sharp form of the mismatch: that literal does not even PARSE as an `Artifact`. A
    /// registered provisioning scope is always some `artifact.asString()`, so no scope can equal a
    /// string `Artifact.artifact(...)` rejects — the defect is structural, not a version skew a
    /// tolerant comparison could bridge.
    @Test
    void generatedStop_emitsACoordinateNoDeployedArtifactCanEqual() {
        var recorder = new ReleaseRecorder();
        var slice = ReleaseProbeFactory.releaseProbeSlice(SliceLoadingContext.sliceLoadingContext(RefusingInvoker.INSTANCE,
                                                                                                  recorder))
                                       .await(TIMEOUT)
                                       .fold(cause -> fail("slice creation failed: " + cause.message()), value -> value);

        slice.stop().await(TIMEOUT);

        var emitted = recorder.releasedId();

        assertThat(emitted).describedAs("premise: the generated stop() must reach releaseAll at all")
                           .isNotNull();
        assertThat(Artifact.artifact(emitted).isFailure()).describedAs("the generator's literal is not a parseable artifact coordinate: " + emitted)
                                                          .isTrue();
        assertThat(emitted).describedAs("and it is therefore not the coordinate this slice deploys under")
                           .isNotEqualTo(ARTIFACT_COORDS);
        assertThat(ARTIFACT_COORDS).describedAs("control: the deployed coordinate IS parseable, so the check above discriminates")
                                   .satisfies(coords -> assertThat(Artifact.artifact(coords).isSuccess()).isTrue());
    }

    /// The observable close, through the deployment path.
    @Test
    void unloadSlice_closesTheResourceTheSliceProvisioned(@TempDir Path tempDir) throws Exception {
        var factory = TrackedResourceFactory.trackedResourceFactory();
        var store = storeFor(packageSliceJar(tempDir), factory);

        awaitUnit(store.loadSlice(artifact()).mapToUnit(), "load");
        awaitUnit(store.activateSlice(artifact()).mapToUnit(), "activate");

        assertThat(factory.provisioned()).describedAs("premise: the deployed slice must have provisioned exactly its one resource")
                                         .hasSize(1);
        assertThat(factory.closed()).describedAs("control: nothing is closed before the unload, so a pass below is attributable to it")
                                    .isEmpty();

        awaitUnit(store.unloadSlice(artifact()), "unload");

        assertThat(factory.closed()).describedAs("the slice's own resource must be closed by its unload")
                                    .containsExactlyElementsOf(factory.provisioned());
    }

    /// Deactivation runs the same `stop()` and must close just as an unload does — `SliceStore`
    /// reaches it by a different branch (`deactivateEntry`), and a fix that only satisfied the
    /// unload branch would leave a slice stopped-but-holding.
    @Test
    void deactivateSlice_closesTheResourceTheSliceProvisioned(@TempDir Path tempDir) throws Exception {
        var factory = TrackedResourceFactory.trackedResourceFactory();
        var store = storeFor(packageSliceJar(tempDir), factory);

        awaitUnit(store.loadSlice(artifact()).mapToUnit(), "load");
        awaitUnit(store.activateSlice(artifact()).mapToUnit(), "activate");

        // `closed()` is a FILTERED SUBSET of `provisioned()`, so the final assertion passes
        // vacuously if nothing was ever provisioned. Both halves are guarded: non-empty before, and
        // empty-closed before, so a pass below is attributable to the deactivation.
        assertThat(factory.provisioned()).describedAs("premise: the deployed slice must have provisioned exactly its one resource")
                                         .hasSize(1);
        assertThat(factory.closed()).describedAs("control: nothing is closed before the deactivation")
                                    .isEmpty();

        awaitUnit(store.deactivateSlice(artifact()).mapToUnit(), "deactivate");

        assertThat(factory.closed()).describedAs("the slice's own resource must be closed by its deactivation")
                                    .containsExactlyElementsOf(factory.provisioned());
    }

    private static void awaitUnit(Promise<Unit> promise, String step) {
        promise.await(TIMEOUT)
               .onFailure(cause -> fail(step + " failed: " + cause.message()));
    }

    private static Artifact artifact() {
        return Artifact.artifact(ARTIFACT_COORDS).unwrap();
    }

    /// Wire the store the way `AetherNode` does: the provider's OWN facade (never a hand-rolled
    /// partial one — see `ResourceProvider.facade`), a node composite so a slice composite exists,
    /// and the node's slice-scoped overlay builder. The overlay resolves to `Option.none()` here
    /// because this slice ships no factories of its own, which is the common deployed case.
    private static SliceStore storeFor(Path jar, TrackedResourceFactory factory) {
        var facade = SpiResourceProvider.spiResourceProvider(List.of(factory), REFUSING_FALLBACK_LOADER)
                                        .facade();

        return SliceStore.sliceStore(SliceRegistry.sliceRegistry(),
                                     List.of(repositoryServing(jar)),
                                     new SharedLibraryClassLoader(ReleaseIdentityTest.class.getClassLoader()),
                                     RefusingInvoker.INSTANCE,
                                     facade,
                                     SliceActionConfig.sliceActionConfig(),
                                     Option.some(emptyNodeComposite()),
                                     Option.none(),
                                     Option.none(),
                                     sliceClassLoader -> SliceScopedResourceProvider.sliceScopedResourceProvider(sliceClassLoader, facade));
    }

    /// A node composite that is PRESENT but overrides nothing. Presence is load-bearing rather than
    /// decorative: the generator emits the no-context `provide(type, section)` overload for a plain
    /// resource dependency, and it is `SliceLoadingContext.CompositeAwareResourceProvider` that
    /// upgrades such a call to the context overload — which is what carries the slice id to the
    /// provider at all. A store built without a composite provisions unattributed, and unattributed
    /// resources are released by nobody, by design (#268 R2).
    private static ConfigurationProvider emptyNodeComposite() {
        return IntrinsicConfigProvider.intrinsicConfigProvider("node.toml", Map.of());
    }

    /// Serves exactly the jar the test packaged. An artifact nobody packaged is a failure, never a
    /// fallback to some other jar.
    private static Repository repositoryServing(Path jar) {
        var jars = Map.of(ARTIFACT_COORDS, jar);

        return artifact -> Option.option(jars.get(artifact.asString()))
                                 .toResult(Causes.cause("no jar packaged for " + artifact.asString()))
                                 .flatMap(path -> Location.location(artifact, toUrl(path)))
                                 .async();
    }

    private static java.net.URL toUrl(Path jar) {
        return Result.lift(Causes::fromThrowable, () -> jar.toUri().toURL())
                     .expect("temp jar path must be a valid URL");
    }

    /// Package the compiled fixture — [ReleaseProbe], the processor-generated `ReleaseProbeFactory`
    /// and every synthetic local-record class both produce — into a slice jar shaped the way
    /// `PackageSlicesMojo` shapes a real one.
    ///
    /// The whole package directory is swept rather than a hand-listed set: the local records compile
    /// to synthetic names a list would silently miss, and a missing class surfaces as a confusing
    /// load failure rather than as a packaging error. The resource TYPE is deliberately not in this
    /// package and so not in the jar — see `TrackedResource`'s header.
    private static Path packageSliceJar(Path tempDir) throws IOException {
        var jar = tempDir.resolve("release-probe.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(jar), sliceManifest())) {
            for (var classFile : sliceClassFiles()) {
                writeEntry(out, PACKAGE_PATH + "/" + classFile.getFileName(), Files.readAllBytes(classFile));
            }

            writeEntry(out, "META-INF/resources.toml", SLICE_RESOURCES_TOML.getBytes(StandardCharsets.UTF_8));
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
                        .filter(path -> !path.getFileName().toString().startsWith(ReleaseIdentityTest.class.getSimpleName()))
                        .sorted()
                        .toList();
        }
    }

    private static Path packageDirectory() {
        var url = ReleaseIdentityTest.class.getClassLoader()
                                           .getResource(PACKAGE_PATH);

        return Result.lift(Causes::fromThrowable, () -> Path.of(url.toURI()))
                     .expect("fixture package must be on the test classpath as a directory");
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }

    /// Captures the id a `releaseAll` call carries, and provisions nothing worth holding — the
    /// premise test is about the ARGUMENT, so a resource would only add a way for it to fail for an
    /// unrelated reason.
    private static final class ReleaseRecorder implements ResourceProviderFacade {
        private volatile String releasedId;

        String releasedId() {
            return releasedId;
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return Promise.success(null);
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
            return Promise.success(null);
        }

        @Override
        public Promise<Unit> releaseAll(String sliceId) {
            releasedId = sliceId;

            return Promise.unitPromise();
        }
    }

    private enum RefusingInvoker implements SliceInvokerFacade {
        INSTANCE;

        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                              String methodName,
                                                              TypeToken<T> requestType,
                                                              TypeToken<R> responseType) {
            return Causes.cause("slice-to-slice invocation is not used by this fixture").result();
        }
    }
}
