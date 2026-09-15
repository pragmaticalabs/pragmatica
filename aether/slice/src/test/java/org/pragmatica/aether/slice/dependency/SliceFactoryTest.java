// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// Tests for SliceFactory with single-parameter factories.
///
/// Factories take only (SliceCreationContext) and resolve
/// dependencies dynamically via SliceCreationContext at runtime.
class SliceFactoryTest {
    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact,
                                                              String method,
                                                              TypeToken<T> requestType,
                                                              TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    private static final ResourceProviderFacade STUB_RESOURCES = new ResourceProviderFacade() {
        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return Causes.cause("Stub resource provider").promise();
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
            return Causes.cause("Stub resource provider").promise();
        }

        /// Explicit no-op (#892): this stub refuses every provisioning, so nothing can need closing.
        @Override
        public Promise<Unit> releaseAll(String sliceId) {
            return Promise.unitPromise();
        }
    };

    private static final SliceCreationContext STUB_CONTEXT = SliceCreationContext.sliceCreationContext(STUB_INVOKER,
                                                                                                       STUB_RESOURCES);

    // Test factory with no dependencies (matches generated factory pattern)
    public static class SimpleSliceFactory {
        public static Promise<SimpleSlice> simpleSlice(SliceCreationContext ctx) {
            return Promise.success(new SimpleSlice());
        }

        public static Promise<Slice> simpleSliceSlice(SliceCreationContext ctx) {
            return simpleSlice(ctx).map(s -> s);
        }
    }

    public static class SimpleSlice implements Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    // Test factory that simulates dependency resolution via SliceCreationContext
    public static class OrderServiceFactory {
        public static Promise<OrderService> orderService(SliceCreationContext ctx) {
            // In real generated code, dependencies are resolved via ctx.invoker().methodHandle()
            return Promise.success(new OrderService());
        }

        public static Promise<Slice> orderServiceSlice(SliceCreationContext ctx) {
            return orderService(ctx).map(s -> s);
        }
    }

    public static class OrderService implements Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    @Test
    void creates_slice_with_no_dependencies() {
        SliceFactory.createSlice(SimpleSliceFactory.class, STUB_CONTEXT, List.of(), List.of()).await().onFailureRun(Assertions::fail).onSuccess(slice -> {
            assertThat(slice).isInstanceOf(SimpleSlice.class);
        });
    }

    @Test
    void creates_slice_with_dynamic_dependencies() {
        // Dependencies are passed but not used in factory call
        // (they're resolved via SliceCreationContext at runtime)
        SliceFactory.createSlice(OrderServiceFactory.class, STUB_CONTEXT, List.of(), List.of()).await().onFailureRun(Assertions::fail).onSuccess(slice -> {
            assertThat(slice).isInstanceOf(OrderService.class);
        });
    }

    @Test
    void fails_when_factory_method_not_found() {
        // NoMethodFactory doesn't have the required noMethodSlice() method
        class NoMethodFactory {}
        SliceFactory.createSlice(NoMethodFactory.class, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
            assertThat(cause.message()).contains("Factory method not found");
            assertThat(cause.message()).contains("noMethodSlice");
        });
    }

    @Test
    void fails_when_factory_has_wrong_parameter_count() {
        // Factory with wrong number of parameters (expected exactly 1: SliceCreationContext)
        class WrongParamCountFactory {
            public static Promise<Slice> wrongParamCountSlice(SliceCreationContext ctx, String extra) {
                return Promise.success(new SimpleSlice());
            }
        }
        SliceFactory.createSlice(WrongParamCountFactory.class, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
            assertThat(cause.message()).contains("Parameter mismatch");
            assertThat(cause.message()).contains("expected 1");
        });
    }

    @Test
    void fails_when_first_parameter_is_not_creation_context() {
        // Factory with a single parameter of the wrong type
        class WrongFirstParamFactory {
            public static Promise<Slice> wrongFirstParamSlice(String notCtx) {
                return Promise.success(new SimpleSlice());
            }
        }
        SliceFactory.createSlice(WrongFirstParamFactory.class, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
            assertThat(cause.message()).contains("factory parameter 0 must be SliceCreationContext");
        });
    }

    @Test
    void fails_namingBothCauses_when_factory_parameter_type_missing() throws ClassNotFoundException {
        // Load GhostParamFactory through a classloader that hides its parameter type GhostAspect,
        // so reflective parameter inspection throws (as an rc1 slice referencing removed Aspect does).
        var hiddenType = GhostAspect.class.getName();
        var loader = new HidingClassLoader(hiddenType);
        var factoryClass = loader.loadClass(GhostParamFactory.class.getName());

        SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
            assertThat(cause.message()).contains("Parameter mismatch");
            assertThat(cause.message()).contains("rebuild against this runtime version");
            assertThat(cause.message()).contains("DIFFERENT VERSION of the artifact");
            assertThat(cause.message()).contains("GhostAspect");
        });
    }

    /// #758, runtime direction for an ARRAY-typed parameter: the JVM reports the descriptor
    /// `[Lorg/pragmatica/…/GhostAspect;`, which must be unwrapped to the element class before the
    /// owning loader is asked — else a removed runtime class reads as an unserved dependency.
    @Test
    void fails_namingBothCauses_when_factory_array_parameter_type_missing() throws ClassNotFoundException {
        var loader = new HidingClassLoader(GhostAspect.class.getName(), GhostArrayParamFactory.class.getName());
        var factoryClass = loader.loadClass(GhostArrayParamFactory.class.getName());

        SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
            assertThat(cause.message()).contains("rebuild against this runtime version")
                                       .contains("slice references class " + GhostAspect.class.getName())
                                       .doesNotContain("[L");
        });
    }

    /// #758: the same reflective failure for an APPLICATION type — another slice's class that never
    /// reached this slice's loader chain — must not be diagnosed as a removed runtime class. The
    /// cause must name the missing class, say no loader above the slice's serves its package, list
    /// the chain, point at every dependency section, and assert no cause.
    @Test
    void fails_namingTheUnservedPackage_whenAnApplicationTypeIsMissing() throws Exception {
        assertMissingTypeDiagnosedAsUnserved(GHOST_PROVIDER_TYPE, GHOST_CONSUMER_FACTORY, List.of());
    }

    /// #758, the ticket's own shape: the application type lives UNDER the vendor namespace
    /// (`org.pragmatica.example.…`, as every `ticketing/` slice does). A name-prefix discriminator
    /// calls this a removed runtime class; the owning-loader discriminator must not.
    @Test
    void fails_namingTheUnservedPackage_whenAnApplicationTypeUnderTheVendorPrefixIsMissing() throws Exception {
        assertMissingTypeDiagnosedAsUnserved(SEAT_SELLABILITY_PROBE, BUY_TICKET_PROBE_FACTORY, List.of());
    }

    /// #758, application direction for an ARRAY-typed parameter: the message must name the element
    /// class, never the descriptor.
    @Test
    void fails_namingTheUnservedPackage_whenAnApplicationArrayTypeIsMissing() throws Exception {
        assertMissingTypeDiagnosedAsUnserved(GHOST_PROVIDER_TYPE, GHOST_ARRAY_CONSUMER_FACTORY, List.of());
    }

    /// #758, the production loader shape: a `[slices]` jar appended AFTER construction as
    /// `DependencyResolver` does must appear in the listed chain with the slice's own jar — the
    /// operator sees what was consulted.
    @Test
    void fails_listingTheLiveLoaderChain_whenAJarWasAppendedAfterConstruction() throws Exception {
        var addedLater = jar("added-later", GHOST_PROVIDER_TYPE);

        assertMissingTypeDiagnosedAsUnserved(SEAT_SELLABILITY_PROBE, BUY_TICKET_PROBE_FACTORY, List.of(addedLater));
    }

    /// #758, the case the lazy probe alone gets wrong: the missing class's package IS served by a jar
    /// in the shared loader (an `[infra]`/`[shared]` artifact of the wrong version) from which NOTHING
    /// has been loaded yet — the default state at factory-inspection time. `getDefinedPackage` reads
    /// null there; the directory probe must still find the package and the verdict must be the
    /// rebuild one, naming the serving loader and its jar.
    @Test
    void fails_namingBothCauses_whenTheMissingClassPackageIsServedByAJarNothingHasLoadedFrom() throws Exception {
        var providerJar = jar("provider", OTHER_PROBE_TYPE);
        var sharedLoader = sharedLoaderWith(providerJar);

        // Control, inside the run: the lazy probe alone cannot see the package yet.
        assertThat(sharedLoader.getDefinedPackage(PROBE_PACKAGE)).isNull();

        assertServedPackageDiagnosedWithoutChoosingACause(sharedLoader, providerJar, "org.example:provider:1.0.0");
    }

    /// #758: and the same verdict once a class HAS been loaded from that jar — the diagnosis must not
    /// flip with load order.
    @Test
    void fails_namingBothCauses_whenTheMissingClassPackageIsServedByAJarAClassWasLoadedFrom() throws Exception {
        var providerJar = jar("provider", OTHER_PROBE_TYPE);
        var sharedLoader = sharedLoaderWith(providerJar);

        sharedLoader.loadClass(OTHER_PROBE_TYPE);
        assertThat(sharedLoader.getDefinedPackage(PROBE_PACKAGE)).isNotNull();

        assertServedPackageDiagnosedWithoutChoosingACause(sharedLoader, providerJar, "org.example:provider:1.0.0");
    }

    /// #758 BLOCKING, round 3: "served but lacking" does NOT distinguish a class REMOVED by an
    /// upgrade from a serving loader holding a DIFFERENT VERSION than the slice was built against.
    /// `addArtifact` keeps the first version for a `groupId:artifactId` and ignores every later one,
    /// so the second shape is reachable by design; the slice here was built against 2.0.0 and is
    /// served 1.0.0, where a rebuild is exactly the wrong remedy — it would not compile. The message
    /// must name the LOADED version as evidence and must not tell the operator to rebuild alone.
    @Test
    void fails_namingTheLoadedVersion_whenTheSharedLoaderKeptTheFirstVersionOfTheArtifact() throws Exception {
        var servedJar = jar("provider-1.0.0", OTHER_PROBE_TYPE);
        var requestedJar = jar("provider-2.0.0", OTHER_PROBE_TYPE, SEAT_SELLABILITY_PROBE);
        var sharedLoader = new SharedLibraryClassLoader(SliceFactoryTest.class.getClassLoader());

        sharedLoader.addArtifact("org.example", "provider", Version.version("1.0.0").unwrap(), servedJar);
        sharedLoader.addArtifact("org.example", "provider", Version.version("2.0.0").unwrap(), requestedJar);

        // Controls, inside the run: the newer jar was refused, so the class is genuinely unreachable
        // and the evidence the message must quote is the version actually loaded.
        assertThat(sharedLoader.getURLs()).containsExactly(servedJar);
        assertThat(sharedLoader.getLoadedVersion("org.example", "provider").unwrap().withQualifier()).isEqualTo("1.0.0");

        assertServedPackageDiagnosedWithoutChoosingACause(sharedLoader, servedJar, "org.example:provider:1.0.0");
    }

    /// #758: the loaded artifact versions are the evidence that lets an operator tell the two causes
    /// apart — they are what the slice's own declaration is compared against. Asserted alone here, so
    /// dropping the evidence reddens a test that the wording of the two causes does not.
    @Test
    void names_theLoadedArtifactVersions_whenTheServingLoaderTracksThem() throws Exception {
        var providerJar = jar("provider", OTHER_PROBE_TYPE);
        var sharedLoader = sharedLoaderWith(providerJar);
        var consumerJar = jar("consumer", BUY_TICKET_PROBE_FACTORY);

        try (var sliceLoader = new SliceClassLoader(new URL[]{consumerJar}, sharedLoader)) {
            var factoryClass = sliceLoader.loadClass(BUY_TICKET_PROBE_FACTORY);

            SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
                assertThat(cause.message()).contains("(that loader has loaded [org.example:provider:1.0.0])");
            });
        }
    }

    /// #758 SHOULD-FIX-1: the package is served ONLY by the application loader and the shared loader
    /// holds NO urls. `findResource` asks a loader about its own contents; a parent-first
    /// `getResource` would credit the empty shared loader with its parent's package and name a loader
    /// with nothing in it as the owner.
    @Test
    void fails_namingTheOwningLoader_whenTheSharedLoaderAboveIsEmpty() throws Exception {
        var consumerJar = jar("consumer", RUNTIME_GHOST_CONSUMER_FACTORY);
        var appLoader = SliceFactoryTest.class.getClassLoader();
        var sharedLoader = new SharedLibraryClassLoader(appLoader);

        // Controls, inside the run: the shared loader can serve nothing, and the loader that CAN is
        // the application loader — so naming the shared loader would be wrong on the evidence.
        assertThat(sharedLoader.getURLs()).isEmpty();
        assertThat(appLoader.getResource(RUNTIME_PACKAGE.replace('.', '/'))).isNotNull();

        try (var sliceLoader = new SliceClassLoader(new URL[]{consumerJar}, sharedLoader)) {
            var factoryClass = sliceLoader.loadClass(RUNTIME_GHOST_CONSUMER_FACTORY);

            SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
                assertThat(cause.message()).contains("slice references class " + RUNTIME_GHOST_TYPE)
                                           .contains("whose package " + RUNTIME_PACKAGE + " is served by ")
                                           .doesNotContain("is served by SharedLibraryClassLoader")
                                           .doesNotContain("that loader has loaded");
            });
        }
    }

    /// #758, arm 3 — "the class is present": a class reachable from the slice's own loader RESOLVES,
    /// so no resolution Throwable is raised and `classLoadFailure` is never entered. Enabled
    /// tripwire: if that ever stops holding, a present class would be diagnosed as an absent one.
    @Test
    void succeeds_whenTheReferencedClassIsInTheSliceOwnJar() throws Exception {
        var consumerJar = jar("consumer", PRESENT_CONSUMER_FACTORY, PRESENT_PROBE_TYPE);
        var sharedLoader = new SharedLibraryClassLoader(SliceFactoryTest.class.getClassLoader());

        try (var sliceLoader = new SliceClassLoader(new URL[]{consumerJar}, sharedLoader)) {
            var factoryClass = sliceLoader.loadClass(PRESENT_CONSUMER_FACTORY);

            SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await()
                        .onFailure(cause -> Assertions.fail(cause.message()))
                        .onSuccess(slice -> assertThat(slice).isNotNull());
        }
    }

    /// #758, arm 3's non-vacuity control: the SAME factory with the referenced class omitted from the
    /// jar does fail — so the test above is passing because the class resolves, not because the
    /// reference is never inspected.
    @Test
    void fails_namingTheUnservedPackage_whenTheSameReferencedClassIsOmittedFromTheSliceOwnJar() throws Exception {
        assertFixtureOffTheTestClasspath(PRESENT_PROBE_TYPE);
        var consumerJar = jar("consumer", PRESENT_CONSUMER_FACTORY);
        var sharedLoader = new SharedLibraryClassLoader(SliceFactoryTest.class.getClassLoader());

        try (var sliceLoader = new SliceClassLoader(new URL[]{consumerJar}, sharedLoader)) {
            var factoryClass = sliceLoader.loadClass(PRESENT_CONSUMER_FACTORY);

            SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
                assertThat(cause.message()).contains("Class " + PRESENT_PROBE_TYPE + " referenced by " + PRESENT_CONSUMER_FACTORY)
                                           .contains("no loader above it serves package " + PROBE_PACKAGE);
            });
        }
    }

    /// #758, arm 4 — "the class is present but its static initialiser fails": resolution succeeds, so
    /// this never reaches `classLoadFailure`; it surfaces from `invokeFactory` carrying the real
    /// Throwable. Enabled tripwire: widening `isMissingClass` would route the second attempt's
    /// `NoClassDefFoundError: Could not initialize class X` into the diagnosis, which would then
    /// report the JVM's message text as a missing class name.
    @Test
    void fails_carryingTheRealCause_whenTheFactoryStaticInitialiserThrows() throws Exception {
        var consumerJar = jar("consumer", CLINIT_BOOM_FACTORY);
        var sharedLoader = new SharedLibraryClassLoader(SliceFactoryTest.class.getClassLoader());

        try (var sliceLoader = new SliceClassLoader(new URL[]{consumerJar}, sharedLoader)) {
            var factoryClass = sliceLoader.loadClass(CLINIT_BOOM_FACTORY);

            // The JVM reports the first attempt and every later one differently; both must carry the
            // real Throwable and neither may reach the class-resolution diagnosis.
            for (var expected : List.of("java.lang.ExceptionInInitializerError",
                                        "Could not initialize class " + CLINIT_BOOM_FACTORY)) {
                SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
                    assertThat(cause.message()).as("attempt reporting %s", expected)
                                               .contains("Unexpected slice loading error")
                                               .contains(expected)
                                               .doesNotContain("rebuild against this runtime version")
                                               .doesNotContain("slice references class")
                                               .doesNotContain("no loader above it serves package");
                });
            }
        }
    }

    // ---- #758 fixtures: compiled at test time into OUT-OF-TREE jars. They must not sit on the test
    // classpath: the application loader would then serve their packages (as a resource, or as a
    // defined package once a class literal touched them) and the fixture would read as a runtime
    // class — the very thing the discriminator exists to tell apart.

    private static final String PROBE_PACKAGE = "org.pragmatica.example.probe758";
    private static final String SEAT_SELLABILITY_PROBE = PROBE_PACKAGE + ".SeatSellabilityProbe";
    private static final String BUY_TICKET_PROBE_FACTORY = PROBE_PACKAGE + ".BuyTicketProbeFactory";
    private static final String OTHER_PROBE_TYPE = PROBE_PACKAGE + ".OtherProbeType";
    private static final String GHOST_PROVIDER_TYPE = "com.example.ghost.GhostProviderType";
    private static final String GHOST_CONSUMER_FACTORY = "com.example.ghost.GhostConsumerFactory";
    private static final String GHOST_ARRAY_CONSUMER_FACTORY = "com.example.ghost.GhostArrayConsumerFactory";
    private static final String PRESENT_PROBE_TYPE = PROBE_PACKAGE + ".PresentProbeType";
    private static final String PRESENT_CONSUMER_FACTORY = PROBE_PACKAGE + ".PresentConsumerFactory";
    private static final String CLINIT_BOOM_FACTORY = PROBE_PACKAGE + ".ClinitBoomFactory";

    /// A package the APPLICATION loader serves, holding a class that does not exist in it — what a
    /// runtime class removed by an upgrade looks like from a slice built before the removal. The stub
    /// is compiled so the fixture references it, and never packaged into any jar.
    private static final String RUNTIME_PACKAGE = "org.pragmatica.aether.slice";
    private static final String RUNTIME_GHOST_TYPE = RUNTIME_PACKAGE + ".GhostRemovedRuntimeType";
    private static final String RUNTIME_GHOST_CONSUMER_FACTORY = PROBE_PACKAGE + ".RuntimeGhostConsumerFactory";

    private static final String SLICE_BODY = """
            {
                return org.pragmatica.lang.Promise.success(new org.pragmatica.aether.slice.Slice() {
                    @Override
                    public java.util.List<org.pragmatica.aether.slice.SliceMethod<?, ?>> methods() {
                        return java.util.List.of();
                    }
                });
            }
            """;

    private static final List<String> FIXTURE_SOURCES = List.of(
        "package " + PROBE_PACKAGE + "; public class SeatSellabilityProbe {}",
        "package " + PROBE_PACKAGE + "; public class OtherProbeType {}",
        "package " + PROBE_PACKAGE + "; public class BuyTicketProbeFactory {"
        + " public static org.pragmatica.lang.Promise<org.pragmatica.aether.slice.Slice> buyTicketProbeSlice(SeatSellabilityProbe ignored)"
        + SLICE_BODY + "}",
        "package com.example.ghost; public class GhostProviderType {}",
        "package com.example.ghost; public class GhostConsumerFactory {"
        + " public static org.pragmatica.lang.Promise<org.pragmatica.aether.slice.Slice> ghostConsumerSlice(GhostProviderType ignored)"
        + SLICE_BODY + "}",
        "package com.example.ghost; public class GhostArrayConsumerFactory {"
        + " public static org.pragmatica.lang.Promise<org.pragmatica.aether.slice.Slice> ghostArrayConsumerSlice(GhostProviderType[] ignored)"
        + SLICE_BODY + "}",
        "package " + RUNTIME_PACKAGE + "; public class GhostRemovedRuntimeType {}",
        "package " + PROBE_PACKAGE + "; public class RuntimeGhostConsumerFactory {"
        + " public static org.pragmatica.lang.Promise<org.pragmatica.aether.slice.Slice>"
        + " runtimeGhostConsumerSlice(" + RUNTIME_GHOST_TYPE + " ignored)"
        + SLICE_BODY + "}",
        "package " + PROBE_PACKAGE + "; public class PresentProbeType {}",
        "package " + PROBE_PACKAGE + "; public class PresentConsumerFactory {"
        + " public static void uses(PresentProbeType ignored) {}"
        + " public static org.pragmatica.lang.Promise<org.pragmatica.aether.slice.Slice>"
        + " presentConsumerSlice(org.pragmatica.aether.slice.SliceCreationContext ignored)"
        + SLICE_BODY + "}",
        "package " + PROBE_PACKAGE + "; public class ClinitBoomFactory {"
        + " static { if (Boolean.TRUE) { throw new RuntimeException(\"clinit boom\"); } }"
        + " public static org.pragmatica.lang.Promise<org.pragmatica.aether.slice.Slice>"
        + " clinitBoomSlice(org.pragmatica.aether.slice.SliceCreationContext ignored)"
        + SLICE_BODY + "}");

    @TempDir
    static Path fixtureDir;
    static Path fixtureClasses;

    @BeforeAll
    static void compileFixtures() throws Exception {
        var sources = fixtureDir.resolve("src");
        var files = new java.util.ArrayList<String>();

        for (var source : FIXTURE_SOURCES) {
            var pkg = source.substring("package ".length(), source.indexOf(';'));
            var name = source.replaceAll("(?s).*public class (\\w+)\\b.*", "$1");
            var file = sources.resolve(pkg.replace('.', '/')).resolve(name + ".java");

            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            files.add(file.toString());
        }

        fixtureClasses = Files.createDirectories(fixtureDir.resolve("classes"));
        var classpath = Stream.of(Slice.class, SliceMethod.class, Promise.class)
                              .map(c -> c.getProtectionDomain().getCodeSource().getLocation().getPath())
                              .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        var args = new java.util.ArrayList<>(List.of("-d", fixtureClasses.toString(), "-cp", classpath, "-proc:none"));

        args.addAll(files);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).as("fixture javac").isZero();
    }

    /// A jar holding the named classes (plus their nested classes) WITH directory entries, as Maven
    /// writes them. Only the named classes go in — a consumer jar deliberately omits the type it
    /// references. A fresh file per call: a loader holds its jar open.
    private static URL jar(String prefix, String... classNames) throws IOException {
        var path = Files.createTempFile(fixtureDir, prefix, ".jar");
        var directories = new java.util.HashSet<String>();

        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            for (var className : classNames) {
                var directory = className.substring(0, className.lastIndexOf('.')).replace('.', '/') + "/";

                if (directories.add(directory)) {
                    out.putNextEntry(new JarEntry(directory));
                    out.closeEntry();
                }
                var simpleName = className.substring(className.lastIndexOf('.') + 1);

                try (var siblings = Files.list(fixtureClasses.resolve(directory))) {
                    for (var classFile : siblings.filter(f -> f.getFileName().toString().matches(simpleName + "(\\$.*)?\\.class")).toList()) {
                        out.putNextEntry(new JarEntry(directory + classFile.getFileName()));
                        out.write(Files.readAllBytes(classFile));
                        out.closeEntry();
                    }
                }
            }
        }

        return path.toUri().toURL();
    }

    private static SharedLibraryClassLoader sharedLoaderWith(URL providerJar) {
        var sharedLoader = new SharedLibraryClassLoader(SliceFactoryTest.class.getClassLoader());

        sharedLoader.addArtifact("org.example", "provider", Version.version("1.0.0").unwrap(), providerJar);

        return sharedLoader;
    }

    private static void assertFixtureOffTheTestClasspath(String className) {
        var packageName = className.substring(0, className.lastIndexOf('.'));
        var appLoader = SliceFactoryTest.class.getClassLoader();

        assertThat(appLoader.getResource(packageName.replace('.', '/'))).as("fixture package %s is on the test classpath", packageName).isNull();
        assertThat(appLoader.getDefinedPackage(packageName)).as("fixture package %s defined in the application loader", packageName).isNull();
    }

    private static void assertMissingTypeDiagnosedAsUnserved(String missingType, String factoryName, List<URL> appendedJars) throws Exception {
        assertFixtureOffTheTestClasspath(missingType);
        var packageName = missingType.substring(0, missingType.lastIndexOf('.'));
        var consumerJar = jar("consumer", factoryName);
        var sharedLoader = new SharedLibraryClassLoader(SliceFactoryTest.class.getClassLoader());

        try (var sliceLoader = new SliceClassLoader(new URL[]{consumerJar}, sharedLoader)) {
            appendedJars.forEach(sliceLoader::addSliceDependencyUrl);
            var factoryClass = sliceLoader.loadClass(factoryName);
            var expectedUrls = Stream.concat(Stream.of(consumerJar), appendedJars.stream()).map(URL::toString).toList();

            assertThat(factoryClass.getClassLoader()).as("child-first: the jar defines the factory").isSameAs(sliceLoader);

            SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
                assertThat(cause.message()).contains("Class " + missingType + " referenced by " + factoryName)
                                           .contains("not on this slice's classloader")
                                           .contains("no loader above it serves package " + packageName)
                                           .contains("Loader chain: [SliceClassLoader" + expectedUrls + ", SharedLibraryClassLoader[], ")
                                           .contains("[slices]")
                                           .contains("[shared]")
                                           .contains("[infra]")
                                           .doesNotContain("[L")
                                           .doesNotContain("rebuild")
                                           .doesNotContain("removed class");
            });
        }
    }

    /// The served-but-lacking verdict: the evidence (which loader, which jar, which artifact
    /// version), BOTH causes that produce it, and NEITHER chosen. `doesNotContain("slice was compiled
    /// against an older runtime")` is the #758 round-3 BLOCKING pin — that clause asserted the upgrade
    /// cause on a state a version skew reaches just as readily.
    private static void assertServedPackageDiagnosedWithoutChoosingACause(SharedLibraryClassLoader sharedLoader,
                                                                          URL providerJar,
                                                                          String loadedArtifact) throws Exception {
        assertFixtureOffTheTestClasspath(SEAT_SELLABILITY_PROBE);
        var consumerJar = jar("consumer", BUY_TICKET_PROBE_FACTORY);

        try (var sliceLoader = new SliceClassLoader(new URL[]{consumerJar}, sharedLoader)) {
            var factoryClass = sliceLoader.loadClass(BUY_TICKET_PROBE_FACTORY);

            SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
                assertThat(cause.message()).contains("slice references class " + SEAT_SELLABILITY_PROBE + ", whose package " + PROBE_PACKAGE
                                                    + " is served by SharedLibraryClassLoader[" + providerJar + "] but the class is not")
                                           .contains("(that loader has loaded [" + loadedArtifact + "])")
                                           .contains("REMOVED by a runtime or artifact upgrade")
                                           .contains("rebuild against this runtime version")
                                           .contains("DIFFERENT VERSION of the artifact")
                                           .contains("reconcile the version and do NOT rebuild")
                                           .doesNotContain("slice was compiled against an older runtime")
                                           .doesNotContain("not on this slice's classloader");
            });
        }
    }

    /// Test classloader implementing the JDK {@link ClassLoader} SPI: it defines the ghost factory
    /// from parent bytes (so the factory's defining loader is this one) while refusing to load the
    /// hidden parameter type, forcing a {@link ClassNotFoundException} during reflective inspection.
    /// try/catch/throw here satisfy the {@code loadClass}/{@code findClass} contract, mirroring the
    /// production SliceClassLoader boundary.
    private static final class HidingClassLoader extends ClassLoader {
        private final String hiddenType;
        private final String definedHere;

        private HidingClassLoader(String hiddenType) {
            this(hiddenType, GhostParamFactory.class.getName());
        }

        private HidingClassLoader(String hiddenType, String definedHere) {
            super(HidingClassLoader.class.getClassLoader());
            this.hiddenType = hiddenType;
            this.definedHere = definedHere;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(hiddenType)) {
                throw new ClassNotFoundException(name);
            }

            if (name.equals(definedHere)) {
                return defineFromParentBytes(name, resolve);
            }

            return super.loadClass(name, resolve);
        }

        private Class<?> defineFromParentBytes(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                var existing = findLoadedClass(name);

                if (existing != null) {
                    return existing;
                }

                var resourcePath = name.replace('.', '/') + ".class";

                try (var in = getParent().getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new ClassNotFoundException(name);
                    }

                    var bytes = in.readAllBytes();
                    var defined = defineClass(name, bytes, 0, bytes.length);

                    if (resolve) {
                        resolveClass(defined);
                    }

                    return defined;
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }
}

/// Stand-in for a runtime class that existed when the slice was compiled but has since been
/// removed (the real case: {@code org.pragmatica.aether.slice.Aspect}). Declared top-level so a
/// cross-classloader reflective inspection reproduces the removed-type failure without tripping a
/// nested-class declaring-class access check first.
class GhostAspect {}

class GhostSlice implements Slice {
    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of();
    }
}

/// Factory whose method signature references {@link GhostAspect}. Loaded through a classloader that
/// hides {@code GhostAspect}, its reflective parameter inspection fails — reproducing an rc1-built
/// slice loaded against a runtime where the factory parameter type was removed.
class GhostParamFactory {
    public static Promise<Slice> ghostParamSlice(GhostAspect ignored) {
        return Promise.success(new GhostSlice());
    }
}

/// Same as {@link GhostParamFactory} with the removed type as an ARRAY parameter (#758).
class GhostArrayParamFactory {
    public static Promise<Slice> ghostArrayParamSlice(GhostAspect[] ignored) {
        return Promise.success(new GhostSlice());
    }
}
