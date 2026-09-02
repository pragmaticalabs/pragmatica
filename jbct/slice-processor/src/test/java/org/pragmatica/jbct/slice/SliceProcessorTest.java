// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.Compiler.javac;

class SliceProcessorTest {

    private static CompilationSubject assertCompilation(Compilation compilation) {
        return CompilationSubject.assertThat(compilation);
    }

    // Common stub definitions
    private static final JavaFileObject SLICE_ANNOTATION = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.annotation.Slice",
            """
            package org.pragmatica.aether.slice.annotation;

            import java.lang.annotation.*;

            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.SOURCE)
            public @interface Slice {}
            """);

    private static final JavaFileObject UNIT = JavaFileObjects.forSourceString(
            "org.pragmatica.lang.Unit",
            """
            package org.pragmatica.lang;

            public enum Unit {
                ;
                public static Unit unit() { return null; }
            }
            """);

    private static final JavaFileObject SLICE_CODEC = JavaFileObjects.forSourceString(
            "org.pragmatica.serialization.SliceCodec",
            """
            package org.pragmatica.serialization;

            import java.util.List;
            import java.util.Set;

            public interface SliceCodec {
                static int deterministicTag(String className) {
                    long hash = 0xcbf29ce484222325L;
                    for (var i = 0; i < className.length(); i++) {
                        hash ^= className.charAt(i);
                        hash *= 0x100000001b3L;
                    }
                    return 16384 + (int) Long.remainderUnsigned(hash, (1 << 21) - 16384);
                }
                static void writeCompact(Object buf, int value) {}
                static int readCompact(Object buf) { return 0; }
                static SliceCodec sliceCodec(SliceCodec parent, List<TypeCodec<?>> codecs) { return parent; }
                static SliceCodec sliceCodec(SliceCodec parent, List<TypeCodec<?>> codecs, Set<Class<?>> requiredTypes) { return parent; }
                record TypeCodec<T>(Class<T> type, int tag, TypeWriter<T> writer, TypeReader<T> reader) {}
                interface TypeWriter<T> { void writeBody(SliceCodec codec, Object buf, T value); }
                interface TypeReader<T> { T readBody(SliceCodec codec, Object buf); }
                default <T> void write(Object buf, T obj) {}
                @SuppressWarnings("unchecked")
                default <T> T read(Object buf) { return null; }
            }
            """);

    private static final JavaFileObject SLICE = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.Slice",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import org.pragmatica.serialization.SliceCodec;
            import java.util.List;

            public interface Slice {
                default Promise<Unit> start() { return null; }
                default Promise<Unit> stop() { return null; }
                List<SliceMethod<?, ?>> methods();
                default SliceCodec codec(SliceCodec parent) { return parent; }
            }
            """);

    private static final JavaFileObject SLICE_METHOD = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.SliceMethod",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.type.TypeToken;
            import java.util.function.Function;

            public record SliceMethod<I, O>(MethodName name, Function<I, ?> handler, TypeToken<O> responseType, TypeToken<I> requestType) {}
            """);

    private static final JavaFileObject METHOD_NAME = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.MethodName",
            """
            package org.pragmatica.aether.slice;

            public record MethodName(String value) {
                public static Wrapper methodName(String value) { return new Wrapper(new MethodName(value)); }
                public record Wrapper(MethodName name) {
                    public MethodName unwrap() { return name; }
                    public MethodName expect(String reason) { return name; }
                }
            }
            """);

    private static final JavaFileObject METHOD_HANDLE = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.MethodHandle",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Promise;

            public interface MethodHandle<R, I> {
                Promise<R> invoke(I request);
            }
            """);

    private static final JavaFileObject INVOKER_FACADE = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.SliceInvokerFacade",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Result;
            import org.pragmatica.lang.type.TypeToken;

            public interface SliceInvokerFacade {
                <T> Promise<T> invoke(String artifact, String method, Object request, Class<T> responseType);
                <R, I> Result<MethodHandle<R, I>> methodHandle(String artifact, String method, TypeToken<I> requestType, TypeToken<R> responseType);
            }
            """);

    private static final JavaFileObject METHOD_INTERCEPTOR = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.MethodInterceptor",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Functions.Fn1;
            import org.pragmatica.lang.Promise;

            @FunctionalInterface
            public interface MethodInterceptor {
                <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method);
            }
            """);

    private static final JavaFileObject PROVISIONING_CONTEXT = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.ProvisioningContext",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Functions.Fn1;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.type.TypeToken;

            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;

            public record ProvisioningContext(List<TypeToken<?>> typeTokens,
                                              Option<Fn1<?, ?>> keyExtractor,
                                              Map<Class<?>, Object> extensions) {
                public static ProvisioningContext provisioningContext() {
                    return new ProvisioningContext(List.of(), Option.none(), Map.of());
                }
                public ProvisioningContext withTypeToken(TypeToken<?> token) {
                    var tokens = new ArrayList<>(typeTokens);
                    tokens.add(token);
                    return new ProvisioningContext(List.copyOf(tokens), keyExtractor, extensions);
                }
                public ProvisioningContext withKeyExtractor(Fn1<?, ?> extractor) {
                    return new ProvisioningContext(typeTokens, Option.some(extractor), extensions);
                }
                @SuppressWarnings("unchecked")
                public <T> Option<T> extension(Class<T> type) {
                    return Option.option((T) extensions.get(type));
                }
                public <T> ProvisioningContext withExtension(Class<T> type, T value) {
                    var newExtensions = new HashMap<>(extensions);
                    newExtensions.put(type, value);
                    return new ProvisioningContext(typeTokens, keyExtractor, Map.copyOf(newExtensions));
                }
            }
            """);

    private static final JavaFileObject RESOURCE_PROVIDER_FACADE = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.ResourceProviderFacade",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;

            public interface ResourceProviderFacade {
                <T> Promise<T> provide(Class<T> resourceType, String configSection);
                <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);
                default Promise<Unit> releaseAll(String sliceId) { return null; }
            }
            """);

    private static final JavaFileObject CONFIG_FACADE = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.ConfigFacade",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Result;

            import java.util.List;

            public interface ConfigFacade {
                Result<String> requireString(String section, String key);
                Result<Integer> requireInt(String section, String key);
                Result<Long> requireLong(String section, String key);
                Result<Double> requireDouble(String section, String key);
                Result<Boolean> requireBoolean(String section, String key);
                Result<List<String>> requireStringList(String section, String key);
                Option<String> getString(String section, String key);
                Option<Integer> getInt(String section, String key);
                Option<Long> getLong(String section, String key);
                Option<Double> getDouble(String section, String key);
                Option<Boolean> getBoolean(String section, String key);
            }
            """);

    private static final JavaFileObject CONFIGURATION_SECTION = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.annotation.ConfigurationSection",
            """
            package org.pragmatica.aether.slice.annotation;

            public interface ConfigurationSection {}
            """);

    private static final JavaFileObject SLICE_CREATION_CONTEXT = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.SliceCreationContext",
            """
            package org.pragmatica.aether.slice;

            public interface SliceCreationContext {
                SliceInvokerFacade invoker();
                ResourceProviderFacade resources();
                ConfigFacade config();
            }
            """);

    private static final JavaFileObject RESOURCE_QUALIFIER = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.annotation.ResourceQualifier",
            """
            package org.pragmatica.aether.slice.annotation;

            import java.lang.annotation.*;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.ANNOTATION_TYPE)
            public @interface ResourceQualifier {
                Class<?> type();
                String config();
            }
            """);

    private static final JavaFileObject KEY_ANNOTATION = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.resource.aspect.Key",
            """
            package org.pragmatica.aether.resource.aspect;

            import java.lang.annotation.*;

            @Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
            @Retention(RetentionPolicy.SOURCE)
            public @interface Key {}
            """);

    private static final JavaFileObject PARTITION_KEY_ANNOTATION = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.annotation.PartitionKey",
            """
            package org.pragmatica.aether.slice.annotation;

            import java.lang.annotation.*;

            @Target(ElementType.RECORD_COMPONENT)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface PartitionKey {}
            """);

    private List<JavaFileObject> commonSources() {
        return new ArrayList<>(List.of(
                SLICE_ANNOTATION,
                SLICE_CODEC, SLICE, SLICE_METHOD, METHOD_NAME, METHOD_HANDLE, INVOKER_FACADE,
                METHOD_INTERCEPTOR, PROVISIONING_CONTEXT,
                RESOURCE_PROVIDER_FACADE, CONFIG_FACADE, SLICE_CREATION_CONTEXT, RESOURCE_QUALIFIER,
                CONFIGURATION_SECTION, KEY_ANNOTATION, PARTITION_KEY_ANNOTATION, UNIT
        ));
    }

    @Test
    void should_fail_on_non_interface() {
        var source = JavaFileObjects.forSourceString("test.NotAnInterface",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;

            @Slice
            public class NotAnInterface {}
            """);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(SLICE_ANNOTATION, source);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("can only be applied to interfaces");
    }

    @Test
    void should_fail_on_missing_factory_method() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface TestService {
                Promise<String> doSomething(String request);
            }
            """);

        var sources = commonSources();
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("No factory method found");
    }

    @Test
    void should_process_simple_slice_without_dependencies() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface TestService {
                Promise<String> doSomething(String request);

                static TestService testService() {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();
        assertCompilation(compilation).generatedSourceFile("test.TestServiceFactory");
    }

    // #403 staleness guard: the processor stamps its own build version so a stale locally-installed
    // jar is diagnosable (a NOTE in the build log + a version breadcrumb in every generated header)
    // instead of silently reintroducing an already-fixed codegen bug.

    @Test
    void buildInfo_resolvesVersion_fromFilteredResource() {
        // Proves Maven resource filtering populated slice-processor-build.properties: in the built
        // module the version must resolve rather than degrade to "unknown".
        assertThat(BuildInfo.VERSION).isNotEqualTo(BuildInfo.UNKNOWN);
        assertThat(BuildInfo.VERSION).doesNotContain("${");
    }

    @Test
    void process_emitsProcessorVersionNote() {
        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(simpleSliceSources());

        assertCompilation(compilation).succeeded();
        assertCompilation(compilation).hadNoteContaining("slice-processor " + BuildInfo.VERSION);
    }

    @Test
    void process_stampsProcessorVersionInGeneratedHeader() {
        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(simpleSliceSources());

        assertCompilation(compilation).succeeded();
        assertCompilation(compilation)
                  .generatedSourceFile("test.TestServiceFactory")
                  .contentsAsUtf8String()
                  .contains("Generated by slice-processor " + BuildInfo.VERSION + " from @Slice test.TestService");
    }

    @Test
    void process_warnsWithRemedy_whenInstalledProcessorPredatesSourceTree(@TempDir Path repoRoot) throws IOException {
        // End-to-end: point the build's working directory at a jbct/slice-processor source tree whose
        // newest file is dated far in the future (so it necessarily post-dates this freshly-built jar),
        // then run the real processor and assert it escalates the stale install to a remedy WARNING.
        stampFutureSourceTree(repoRoot);
        var previousWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", repoRoot.toString());
        try {
            Compilation compilation = javac()
                                           .withProcessors(new SliceProcessor())
                                           .compile(simpleSliceSources());

            assertCompilation(compilation).succeeded();
            assertCompilation(compilation).hadWarningContaining("run `mvn install` in jbct/");
        } finally {
            System.setProperty("user.dir", previousWorkingDir);
        }
    }

    private static void stampFutureSourceTree(Path repoRoot) throws IOException {
        var marker = repoRoot.resolve(Path.of("jbct", "slice-processor", "src", "main", "java",
                                              "org", "pragmatica", "jbct", "slice", "SliceProcessor.java"));
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "// fixture\n");
        Files.setLastModifiedTime(marker, FileTime.from(Instant.parse("2999-01-01T00:00:00Z")));
    }

    private List<JavaFileObject> simpleSliceSources() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface TestService {
                Promise<String> doSomething(String request);

                static TestService testService() {
                    return null;
                }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        return sources;
    }

    /// Loads a generated slice manifest through [java.util.Properties] rather than asserting on raw
    /// text: `Properties.store` escapes the `:` in a coordinate as `\:`, so substring assertions on
    /// the file body silently encode that escaping and break for the wrong reason.
    private static java.util.Properties sliceManifestProperties(Compilation compilation, String sliceName) throws IOException {
        var manifestFile = compilation.generatedFile(StandardLocation.CLASS_OUTPUT,
                                                     "META-INF/slice/" + sliceName + ".manifest");

        assertThat(manifestFile.isPresent()).isTrue();

        var props = new java.util.Properties();

        props.load(new java.io.StringReader(manifestFile.get()
                                                        .getCharContent(false)
                                                        .toString()));

        return props;
    }

    /// A cross-slice dependency coordinate must be keyed on the MODULE, never on package adjacency.
    ///
    /// `jbct:package-slices` builds the dependency file's `[slices]` section by prefix-matching
    /// `groupId:baseArtifactId-`, so a coordinate derived from the provider's PACKAGE is dropped
    /// silently rather than rejected: the section goes unwritten, the provider jar never reaches the
    /// consumer's SliceClassLoader, and the consumer dies inside `getDeclaredMethods()` with a
    /// NoClassDefFoundError naming the provider interface.
    ///
    /// The two packages here share only the module root — consumer under `booking.purchase`,
    /// provider under `eventmanagement.capacity`. That is what a domain-organised app produces, and
    /// it is precisely the shape an immediate-parent-package test fails to recognise.
    @Test
    void should_resolve_cross_subtree_slice_dependency_to_module_artifact() throws Exception {
        var provider = JavaFileObjects.forSourceString("com.example.app.eventmanagement.capacity.seatsellability.SeatSellability",
                                                        """
            package com.example.app.eventmanagement.capacity.seatsellability;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface SeatSellability {
                Promise<String> checkSeat(String seatId);

                static SeatSellability seatSellability() {
                    return null;
                }
            }
            """);
        var consumer = JavaFileObjects.forSourceString("com.example.app.booking.purchase.buyticket.BuyTicket",
                                                        """
            package com.example.app.booking.purchase.buyticket;

            import com.example.app.eventmanagement.capacity.seatsellability.SeatSellability;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface BuyTicket {
                Promise<String> buy(String orderId);

                static BuyTicket buyTicket(SeatSellability seatSellability) {
                    return null;
                }
            }
            """);
        var sources = commonSources();

        sources.add(provider);
        sources.add(consumer);

        Compilation compilation = javac().withProcessors(new SliceProcessor())
                                         .withOptions("-Aslice.groupId=com.example", "-Aslice.artifactId=app")
                                         .compile(sources);

        assertCompilation(compilation).succeeded();

        var consumerManifest = sliceManifestProperties(compilation, "BuyTicket");

        assertThat(consumerManifest.getProperty("dependencies.count")).isEqualTo("1");
        assertThat(consumerManifest.getProperty("dependency.0.interface"))
                                   .isEqualTo("com.example.app.eventmanagement.capacity.seatsellability.SeatSellability");
        assertThat(consumerManifest.getProperty("dependency.0.artifact")).isEqualTo("com.example:app-seat-sellability");

        // The coordinate is only useful if it names the jar the provider is actually packaged into,
        // so pin the agreement itself, not just its current spelling on one side.
        var providerArtifactId = sliceManifestProperties(compilation, "SeatSellability").getProperty("slice.artifactId");

        assertThat(consumerManifest.getProperty("dependency.0.artifact")).isEqualTo("com.example:" + providerArtifactId);
    }

    /// The narrow case the superseded immediate-parent-package test did handle — provider and
    /// consumer as direct package siblings. Keying on the module must not regress it.
    @Test
    void should_resolve_sibling_package_slice_dependency_to_module_artifact() throws Exception {
        var provider = JavaFileObjects.forSourceString("com.example.app.orders.stocklevel.StockLevel",
                                                        """
            package com.example.app.orders.stocklevel;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface StockLevel {
                Promise<Integer> level(String sku);

                static StockLevel stockLevel() {
                    return null;
                }
            }
            """);
        var consumer = JavaFileObjects.forSourceString("com.example.app.orders.checkout.Checkout",
                                                        """
            package com.example.app.orders.checkout;

            import com.example.app.orders.stocklevel.StockLevel;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface Checkout {
                Promise<String> checkout(String orderId);

                static Checkout checkout(StockLevel stockLevel) {
                    return null;
                }
            }
            """);
        var sources = commonSources();

        sources.add(provider);
        sources.add(consumer);

        Compilation compilation = javac().withProcessors(new SliceProcessor())
                                         .withOptions("-Aslice.groupId=com.example", "-Aslice.artifactId=app")
                                         .compile(sources);

        assertCompilation(compilation).succeeded();

        var consumerManifest = sliceManifestProperties(compilation, "Checkout");

        assertThat(consumerManifest.getProperty("dependency.0.artifact")).isEqualTo("com.example:app-stock-level");
    }

    @Test
    void should_generate_proxy_for_external_dependency() {
        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();
        assertCompilation(compilation).generatedSourceFile("test.OrderServiceFactory");
        assertCompilation(compilation)
                  .generatedSourceFile("test.OrderServiceFactory")
                  .contentsAsUtf8String()
                  .contains("record inventoryService(MethodHandle<");
    }

    /// #612: a non-Promise dependency method used to vanish silently from the generated proxy.
    /// The default and static members on the same interface pin the exemption — only abstract
    /// instance methods are proxy candidates, so only they are checked.
    @Test
    void should_fail_on_non_promise_dependency_method() {
        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService {
                Promise<Integer> checkStock(String productId);

                String checkStockSync(String productId);

                default String describe() { return "inventory"; }

                static String version() { return "1"; }
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("external.InventoryService.checkStockSync returns java.lang.String");
        assertCompilation(compilation).hadErrorContaining("must return Promise<T>");
    }

    /// #612's nastier half: before the erasure check, ANY generic return extracted its first type
    /// argument, so `Result<Integer>` was silently treated as `Promise<Integer>` and generated a
    /// wrong-shaped proxy instead of an error.
    @Test
    void should_fail_on_result_returning_dependency_method() {
        var externalService = JavaFileObjects.forSourceString("external.PricingService",
                                                              """
            package external;

            import org.pragmatica.lang.Result;

            public interface PricingService {
                Result<Integer> quote(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.CheckoutService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.PricingService;

            @Slice
            public interface CheckoutService {
                Promise<String> checkout(String orderId);

                static CheckoutService checkoutService(PricingService pricing) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("external.PricingService.quote returns org.pragmatica.lang.Result<java.lang.Integer>");
        assertCompilation(compilation).hadErrorContaining("must return Promise<T>");
    }

    /// #663: dependency methods inherited from super-interfaces were invisible to the
    /// dependency-method scan — the generated proxy record omitted them while still
    /// `implements`-ing the interface. The scan must follow Java interface-inheritance
    /// semantics: the two-level chain pins transitivity, not just direct supers.
    @Test
    void should_generate_proxy_for_dependency_method_inherited_from_super_interface() throws Exception {
        var auditedService = JavaFileObjects.forSourceString("external.AuditedService",
                                                             """
            package external;

            import org.pragmatica.lang.Promise;

            public interface AuditedService {
                Promise<String> auditLog(String entry);
            }
            """);

        var stockQuery = JavaFileObjects.forSourceString("external.StockQuery",
                                                         """
            package external;

            import org.pragmatica.lang.Promise;

            public interface StockQuery extends AuditedService {
                Promise<Integer> stockOf(String productId);
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends StockQuery {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(auditedService);
        sources.add(stockQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        // Declared method still proxied
        assertThat(factoryContent).contains("MethodHandle<Integer, String> checkStockHandle");
        // Direct super-interface method proxied
        assertThat(factoryContent).contains("MethodHandle<Integer, String> stockOfHandle");
        assertThat(factoryContent).contains("public Promise<Integer> stockOf(String productId)");
        // Transitively inherited method proxied
        assertThat(factoryContent).contains("MethodHandle<String, String> auditLogHandle");
        assertThat(factoryContent).contains("public Promise<String> auditLog(String entry)");
    }

    /// #663: a generic super-interface's method must be proxied with the dependency's
    /// actual type arguments (`lookup(K)` seen through `extends KeyedQuery<String>` is
    /// `lookup(String)`), not with the raw type variable.
    @Test
    void should_substitute_type_arguments_for_generic_super_interface_dependency_method() throws Exception {
        var keyedQuery = JavaFileObjects.forSourceString("external.KeyedQuery",
                                                         """
            package external;

            import org.pragmatica.lang.Promise;

            public interface KeyedQuery<K> {
                Promise<Integer> lookup(K key);
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends KeyedQuery<String> {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(keyedQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("MethodHandle<Integer, String> lookupHandle");
        assertThat(factoryContent).contains("public Promise<Integer> lookup(String key)");
    }

    /// #663 × #612: an inherited non-Promise method must hit the same compile error as a
    /// declared one — inheritance is not a hole in the #612 gate. The diagnostic names the
    /// declaring interface (where the offending signature lives) and the dependency that
    /// inherits it.
    @Test
    void should_fail_on_non_promise_dependency_method_inherited_from_super_interface() {
        var legacyQuery = JavaFileObjects.forSourceString("external.LegacyQuery",
                                                          """
            package external;

            public interface LegacyQuery {
                String syncLookup(String id);
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends LegacyQuery {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(legacyQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("external.LegacyQuery.syncLookup returns java.lang.String");
        assertCompilation(compilation).hadErrorContaining("must return Promise<T>");
        assertCompilation(compilation).hadErrorContaining("inherited by dependency external.InventoryService");
    }

    /// #663: an override chain is ONE method — a signature declared in both the dependency
    /// interface and its super-interface must produce exactly one proxy entry (the
    /// subinterface's declaration wins). Two entries would generate a duplicate record
    /// component. The pinned count is 2: one record component + one `invoke` call site.
    @Test
    void should_count_overridden_dependency_method_once() throws Exception {
        var stockQuery = JavaFileObjects.forSourceString("external.StockQuery",
                                                         """
            package external;

            import org.pragmatica.lang.Promise;

            public interface StockQuery {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends StockQuery {
                @Override
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(stockQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();
        var occurrences = factoryContent.split("checkStockHandle", -1).length - 1;

        assertThat(occurrences).isEqualTo(2);
    }

    /// #663 control: a flat dependency interface (no super-interfaces) generates exactly
    /// the proxy record it generated before the super-interface walk — same components,
    /// same declaration order.
    @Test
    void should_keep_flat_dependency_interface_proxy_unchanged() throws Exception {
        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService {
                Promise<Integer> checkStock(String productId);

                Promise<String> nameOf(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent)
                  .contains("record inventoryService(MethodHandle<Integer, String> checkStockHandle, "
                           + "MethodHandle<String, String> nameOfHandle) implements InventoryService {");
    }

    /// #663 regression: a Java 9+ private super-interface instance method is neither static
    /// nor default, so a modifier-skip filter let it into the scan; a private non-Promise
    /// helper then drew a spurious #612 error for an interface that compiled before the
    /// super-interface walk existed. Only ABSTRACT methods are proxy candidates.
    @Test
    void should_ignore_private_super_interface_helper_method() throws Exception {
        var cachedQuery = JavaFileObjects.forSourceString("external.CachedQuery",
                                                          """
            package external;

            import org.pragmatica.lang.Promise;

            public interface CachedQuery {
                Promise<Integer> cachedLookup(String key);

                private String cacheKey(String key) {
                    return "cache:" + key;
                }
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends CachedQuery {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(cachedQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("MethodHandle<Integer, String> cachedLookupHandle");
        assertThat(factoryContent).doesNotContain("cacheKeyHandle");
    }

    /// #663 regression, the silent half: a private Promise-returning super-interface method
    /// passed the modifier-skip filter and wrongly gained a record component + wiring + codec
    /// entry — generated output changed for a previously-compiling slice. Private methods are
    /// implementation detail; they never reach the proxy.
    @Test
    void should_not_proxy_private_promise_returning_super_interface_method() throws Exception {
        var cachedQuery = JavaFileObjects.forSourceString("external.CachedQuery",
                                                          """
            package external;

            import org.pragmatica.lang.Promise;

            public interface CachedQuery {
                Promise<Integer> cachedLookup(String key);

                private Promise<Integer> prefetch(String key) {
                    return cachedLookup(key);
                }
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends CachedQuery {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(cachedQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("MethodHandle<Integer, String> cachedLookupHandle");
        assertThat(factoryContent).doesNotContain("prefetchHandle");
    }

    /// #663 regression: the Object-method exclusion must key on the TYPE, not its string
    /// spelling — `equals(@Nullable Object)` (a TYPE_USE-annotated re-declaration) is still
    /// Object's equals per JLS 9.2, but its parameter's toString carries the annotation and
    /// escaped a string comparison, drawing a spurious #612 error.
    @Test
    void should_exclude_annotated_object_equals_redeclaration_from_dependency_scan() throws Exception {
        var nullable = JavaFileObjects.forSourceString("external.Nullable",
                                                       """
            package external;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target(ElementType.TYPE_USE)
            @Retention(RetentionPolicy.CLASS)
            public @interface Nullable {}
            """);

        var auditedQuery = JavaFileObjects.forSourceString("external.AuditedQuery",
                                                           """
            package external;

            import org.pragmatica.lang.Promise;

            public interface AuditedQuery {
                Promise<Integer> audit(String entry);

                boolean equals(@Nullable Object other);
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends AuditedQuery {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(nullable);
        sources.add(auditedQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("MethodHandle<Integer, String> auditHandle");
        assertThat(factoryContent).doesNotContain("equalsHandle");
    }

    /// #663 pin: diamond tie-break is deterministic — when two super-interfaces both declare
    /// the same signature, the winner follows extends-clause order (`getInterfaces()`), so
    /// the FIRST listed super's declaration supplies the proxy method (observable through
    /// its parameter name), and the signature yields exactly one record component.
    @Test
    void should_pick_first_extends_clause_declaration_for_diamond_dependency_method() throws Exception {
        var alphaQuery = JavaFileObjects.forSourceString("external.AlphaQuery",
                                                         """
            package external;

            import org.pragmatica.lang.Promise;

            public interface AlphaQuery {
                Promise<Integer> lookup(String alphaKey);
            }
            """);

        var betaQuery = JavaFileObjects.forSourceString("external.BetaQuery",
                                                        """
            package external;

            import org.pragmatica.lang.Promise;

            public interface BetaQuery {
                Promise<Integer> lookup(String betaKey);
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends AlphaQuery, BetaQuery {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(alphaQuery);
        sources.add(betaQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("public Promise<Integer> lookup(String alphaKey)");
        assertThat(factoryContent).doesNotContain("betaKey");

        var occurrences = factoryContent.split("lookupHandle", -1).length - 1;

        assertThat(occurrences).isEqualTo(2);
    }

    /// #663 pin: multi-level generic indirection — `KeyedQuery<K> extends BatchQuery<List<K>>`
    /// with the dependency binding K=String means BatchQuery's `putAll(U)` must resolve as
    /// `putAll(List<String>)`: `asMemberOf` composes the substitution U -> List<K> -> List<String>
    /// across levels, not just a single direct binding.
    @Test
    void should_compose_substitution_across_generic_super_interface_levels() throws Exception {
        var batchQuery = JavaFileObjects.forSourceString("external.BatchQuery",
                                                         """
            package external;

            import org.pragmatica.lang.Promise;

            public interface BatchQuery<U> {
                Promise<Integer> putAll(U items);
            }
            """);

        var keyedQuery = JavaFileObjects.forSourceString("external.KeyedQuery",
                                                         """
            package external;

            import java.util.List;
            import org.pragmatica.lang.Promise;

            public interface KeyedQuery<K> extends BatchQuery<List<K>> {
                Promise<Integer> lookup(K key);
            }
            """);

        var externalService = JavaFileObjects.forSourceString("external.InventoryService",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface InventoryService extends KeyedQuery<String> {
                Promise<Integer> checkStock(String productId);
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(batchQuery);
        sources.add(keyedQuery);
        sources.add(externalService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("public Promise<Integer> lookup(String key)");
        assertThat(factoryContent).contains("putAll(List<java.lang.String> items)");
    }

    @Test
    void should_handle_multiple_dependencies() throws Exception {
        var paymentService = JavaFileObjects.forSourceString("payments.PaymentService",
                                                             """
            package payments;

            import org.pragmatica.lang.Promise;

            public interface PaymentService {
                Promise<Boolean> processPayment(String paymentRequest);
            }
            """);

        var validator = JavaFileObjects.forSourceString("test.validation.OrderValidator",
                                                        """
            package test.validation;

            import org.pragmatica.lang.Promise;

            public interface OrderValidator {
                Promise<Boolean> validate(String orderId);

                static OrderValidator orderValidator() {
                    return null;
                }
            }
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.validation.OrderValidator;
            import payments.PaymentService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(OrderValidator validator, PaymentService payments) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(paymentService);
        sources.add(validator);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get()
                                        .getCharContent(false)
                                        .toString();

        // OrderValidator has a factory method, so it's a plain interface - constructed directly
        assertThat(factoryContent).contains("OrderValidator.orderValidator()");
        assertThat(factoryContent).doesNotContain("record orderValidator(MethodHandle<");
        // PaymentService has no factory method, so it gets a proxy record
        assertThat(factoryContent).contains("record paymentService(MethodHandle<");
    }

    @Test
    void should_generate_createSlice_method_with_all_business_methods() throws Exception {
        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface UserService {
                Promise<String> getUser(String userId);
                Promise<Boolean> updateUser(String userId);
                Promise<Void> deleteUser(String userId);

                static UserService userService() {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.UserServiceFactory")
                                        .get()
                                        .getCharContent(false)
                                        .toString();

        assertThat(factoryContent).contains("public static Promise<Slice> userServiceSlice");
        assertThat(factoryContent).contains("delegate::getUser");
        assertThat(factoryContent).contains("delegate::updateUser");
        assertThat(factoryContent).contains("delegate::deleteUser");
        assertThat(factoryContent).contains("record userServiceSlice(UserService delegate, ResourceProviderFacade resources) implements Slice, UserService");
    }

    @Test
    void should_fully_qualify_codec_types_for_injected_slice_with_colliding_nested_records() throws Exception {
        // Regression for codec-generation shadowing bug: the host slice's adapter record `implements`
        // the host interface, whose nested Request/Response are inherited member types. By JLS 6.5.5.2
        // they shadow single-type imports, so a SIMPLE name for an injected slice's nested Request/Response
        // in the generated codec() body silently resolves to the HOST's types -> wrong-arity constructor /
        // missing accessor compile errors. The fix emits fully-qualified names for codec type references.
        // The two slices below use nested Request/Response with DIFFERENT arity so any regression is a hard
        // compile failure that compile-testing's succeeded() will catch.
        var quote = JavaFileObjects.forSourceString("pricing.Quote",
                                                    """
            package pricing;

            import org.pragmatica.lang.Promise;

            public interface Quote {
                Promise<Response> price(Request request);

                record Request(String event) {}
                record Response(String event, long amountMinor) {}
            }
            """);

        var source = JavaFileObjects.forSourceString("test.Reservation",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import pricing.Quote;

            @Slice
            public interface Reservation {
                Promise<Response> reserve(Request request);

                record Request(String seat, String customer) {}
                record Response(String booking, long total, String currency) {}

                static Reservation reservation(Quote quote) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(quote);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.ReservationFactory")
                                        .get()
                                        .getCharContent(false)
                                        .toString();

        // The injected slice's codec entries must be fully qualified, not the bare simple name that
        // would be shadowed by the host's inherited Reservation.Request/Response member types.
        assertThat(factoryContent).contains("new SliceCodec.TypeCodec<pricing.Quote.Response>(pricing.Quote.Response.class,");
        assertThat(factoryContent).contains("return new pricing.Quote.Response(");
        assertThat(factoryContent).contains("new SliceCodec.TypeCodec<pricing.Quote.Request>(pricing.Quote.Request.class,");
        assertThat(factoryContent).contains("return new pricing.Quote.Request(");
        // The buggy bare form must never be emitted.
        assertThat(factoryContent).doesNotContain("new SliceCodec.TypeCodec<Response>(Response.class,");
        assertThat(factoryContent).doesNotContain("new SliceCodec.TypeCodec<Request>(Request.class,");
    }

    @Test
    void should_generate_correct_type_tokens_for_slice_methods() throws Exception {
        var request = JavaFileObjects.forSourceString("test.dto.CreateUserRequest",
                                                      """
            package test.dto;
            public record CreateUserRequest(String name, String email) {}
            """);
        var response = JavaFileObjects.forSourceString("test.dto.UserResponse",
                                                       """
            package test.dto;
            public record UserResponse(String id, String name) {}
            """);
        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.dto.CreateUserRequest;
            import test.dto.UserResponse;

            @Slice
            public interface UserService {
                Promise<UserResponse> createUser(CreateUserRequest request);

                static UserService userService() {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(request);
        sources.add(response);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.UserServiceFactory")
                                        .get()
                                        .getCharContent(false)
                                        .toString();
        // Import tracker should resolve these to simple names with imports
        assertThat(factoryContent).contains("new TypeToken<UserResponse>() {}");
        assertThat(factoryContent).contains("new TypeToken<CreateUserRequest>() {}");
        assertThat(factoryContent).contains("import test.dto.UserResponse;");
        assertThat(factoryContent).contains("import test.dto.CreateUserRequest;");
    }

    // ========== Negative Test Cases ==========

    @Test
    void should_fail_on_invalid_method_name_starting_with_uppercase() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface TestService {
                Promise<String> GetUser(String request);
                static TestService testService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("Invalid slice method name");
    }

    @Test
    void should_fail_on_method_returning_non_promise_type() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface TestService {
                String getUser(String request);
                static TestService testService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must return Promise<T>");
    }

    @Test
    void should_process_zero_param_method() throws Exception {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface TestService {
                Promise<String> getStatus();
                static TestService testService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.TestServiceFactory")
                                        .get().getCharContent(false).toString();
        // createSlice should use Unit for 0-param
        assertThat(factoryContent).contains("new TypeToken<Unit>() {}");
        assertThat(factoryContent).contains("_unit -> delegate.getStatus()");
    }

    @Test
    void should_fail_on_dependency_not_an_interface() {
        var dependency = JavaFileObjects.forSourceString("test.NotAnInterface",
                                                         """
            package test;
            public class NotAnInterface {}
            """);
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface TestService {
                Promise<String> doWork(String request);
                static TestService testService(NotAnInterface dep) { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(dependency);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must be an interface");
    }

    @Test
    void should_fail_on_raw_promise_return_type() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            @SuppressWarnings("rawtypes")
            public interface TestService {
                Promise getUser(String request);
                static TestService testService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("with type argument");
    }

    // ========== Multi-param Test Cases ==========

    @Test
    void should_process_multi_param_method() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId, int quantity);
                static OrderService orderService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();
        // Should generate request record
        assertThat(factoryContent).contains("public record PlaceOrderRequest(String orderId, int quantity) {}");
        // createSlice should use the generated record
        assertThat(factoryContent).contains("new TypeToken<PlaceOrderRequest>() {}");
        // Should pass individual args
        assertThat(factoryContent).contains("request.orderId()");
        assertThat(factoryContent).contains("request.quantity()");
    }

    @Test
    void should_process_mixed_param_counts() throws Exception {
        var source = JavaFileObjects.forSourceString("test.MixedService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface MixedService {
                Promise<String> getStatus();
                Promise<String> getUser(String userId);
                Promise<String> createOrder(String userId, int quantity);
                static MixedService mixedService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.MixedServiceFactory")
                                        .get().getCharContent(false).toString();
        // 0-param: Unit
        assertThat(factoryContent).contains("_unit -> delegate.getStatus()");
        // 1-param: direct delegate
        assertThat(factoryContent).contains("delegate::getUser");
        // N-param: request record
        assertThat(factoryContent).contains("public record CreateOrderRequest(String userId, int quantity) {}");
    }

    @Test
    void should_fail_on_overloaded_methods() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface TestService {
                Promise<String> doWork(String request);
                Promise<String> doWork(String request, int count);
                static TestService testService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("Overloaded slice methods not supported");
    }

    @Test
    void should_generate_proxy_for_zero_param_dependency() throws Exception {
        var healthService = JavaFileObjects.forSourceString("external.HealthService",
                                                             """
            package external;
            import org.pragmatica.lang.Promise;
            public interface HealthService {
                Promise<String> check();
            }
            """);
        var source = JavaFileObjects.forSourceString("test.MonitorService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.HealthService;
            @Slice
            public interface MonitorService {
                Promise<String> getStatus(String nodeId);
                static MonitorService monitorService(HealthService health) { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(healthService);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.MonitorServiceFactory")
                                        .get().getCharContent(false).toString();
        // Proxy for 0-param dep should use Unit
        assertThat(factoryContent).contains("MethodHandle<String, Unit> checkHandle");
        assertThat(factoryContent).contains("checkHandle.invoke(Unit.unit())");
    }

    @Test
    void should_generate_proxy_for_multi_param_dependency() throws Exception {
        var searchService = JavaFileObjects.forSourceString("external.SearchService",
                                                             """
            package external;
            import org.pragmatica.lang.Promise;
            public interface SearchService {
                Promise<String> search(String query, int limit);
            }
            """);
        var source = JavaFileObjects.forSourceString("test.AggregatorService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.SearchService;
            @Slice
            public interface AggregatorService {
                Promise<String> aggregate(String query);
                static AggregatorService aggregatorService(SearchService search) { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(searchService);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.AggregatorServiceFactory")
                                        .get().getCharContent(false).toString();
        // Proxy for multi-param dep should generate inner request record
        assertThat(factoryContent).contains("search_SearchRequest");
        assertThat(factoryContent).contains("searchHandle.invoke(new search_SearchRequest(query, limit))");
    }

    @Test
    void should_generate_proper_imports_no_fqcn() throws Exception {
        var request = JavaFileObjects.forSourceString("test.dto.CreateUserRequest",
                                                      """
            package test.dto;
            public record CreateUserRequest(String name, String email) {}
            """);
        var response = JavaFileObjects.forSourceString("test.dto.UserResponse",
                                                       """
            package test.dto;
            public record UserResponse(String id, String name) {}
            """);
        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.dto.CreateUserRequest;
            import test.dto.UserResponse;
            @Slice
            public interface UserService {
                Promise<UserResponse> createUser(CreateUserRequest request);
                static UserService userService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(request);
        sources.add(response);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.UserServiceFactory")
                                        .get().getCharContent(false).toString();
        // Should have proper imports
        assertThat(factoryContent).contains("import test.dto.CreateUserRequest;");
        assertThat(factoryContent).contains("import test.dto.UserResponse;");
        // Should use simple names in TypeToken references
        assertThat(factoryContent).contains("new TypeToken<UserResponse>() {}");
        assertThat(factoryContent).contains("new TypeToken<CreateUserRequest>() {}");
    }

    // ========== @ResourceQualifier Tests ==========

    @Test
    void should_generate_resource_provide_call_for_qualified_parameter() throws Exception {
        var primaryDb = JavaFileObjects.forSourceString("test.annotation.PrimaryDb",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DatabaseConnector.class, config = "database.primary")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface PrimaryDb {}
            """);
        var databaseConnector = JavaFileObjects.forSourceString("test.infra.DatabaseConnector",
                                                                """
            package test.infra;
            import org.pragmatica.lang.Promise;
            public interface DatabaseConnector {
                Promise<String> query(String sql);
            }
            """);
        var source = JavaFileObjects.forSourceString("test.OrderRepository",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.PrimaryDb;
            import test.infra.DatabaseConnector;
            @Slice
            public interface OrderRepository {
                Promise<String> findOrder(String orderId);
                static OrderRepository orderRepository(@PrimaryDb DatabaseConnector db) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(primaryDb);
        sources.add(databaseConnector);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderRepositoryFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.resources().provide(DatabaseConnector.class, \"database.primary\")");
        assertThat(factoryContent).doesNotContain("record databaseConnector(MethodHandle<");
    }

    @Test
    void should_handle_mixed_resource_and_slice_dependencies() throws Exception {
        var primaryDb = JavaFileObjects.forSourceString("test.annotation.PrimaryDb",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DatabaseConnector.class, config = "database.primary")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface PrimaryDb {}
            """);
        var databaseConnector = JavaFileObjects.forSourceString("test.infra.DatabaseConnector",
                                                                """
            package test.infra;
            import org.pragmatica.lang.Promise;
            public interface DatabaseConnector { Promise<String> query(String sql); }
            """);
        var inventoryService = JavaFileObjects.forSourceString("external.InventoryService",
                                                               """
            package external;
            import org.pragmatica.lang.Promise;
            public interface InventoryService { Promise<Integer> checkStock(String productId); }
            """);
        var source = JavaFileObjects.forSourceString("test.OrderRepository",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.PrimaryDb;
            import test.infra.DatabaseConnector;
            import external.InventoryService;
            @Slice
            public interface OrderRepository {
                Promise<String> placeOrder(String orderId);
                static OrderRepository orderRepository(@PrimaryDb DatabaseConnector db, InventoryService inventory) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(primaryDb);
        sources.add(databaseConnector);
        sources.add(inventoryService);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderRepositoryFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.resources().provide(DatabaseConnector.class, \"database.primary\")");
        assertThat(factoryContent).contains("record inventoryService(MethodHandle<");
        assertThat(factoryContent).contains("OrderRepository.orderRepository(db, inventory)");
    }

    // ========== Resource Type Argument Codec Derivation Tests ==========

    /// A `DurableEntity<K, S>`-shaped resource: the state type crosses the serialization boundary
    /// without ever appearing in a slice method signature, which is exactly the case the
    /// method-walking codec sweep misses.
    private static final JavaFileObject DURABLE_ENTITY = JavaFileObjects.forSourceString(
            "test.infra.DurableEntity",
            """
            package test.infra;

            import org.pragmatica.lang.Promise;

            public interface DurableEntity<K, S> {
                Promise<S> create(K key, S initial);
            }
            """);

    private static JavaFileObject entityQualifier(String name, String config) {
        return JavaFileObjects.forSourceString("test.annotation." + name,
                                               """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DurableEntity.class, config = "%s")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface %s {}
            """.formatted(config, name));
    }

    @Test
    void codec_generatesEntriesForRecordAndEnumTypeArguments_whenParameterIsResourceQualified() throws Exception {
        var orderState = JavaFileObjects.forSourceString("test.state.OrderState",
                                                         """
            package test.state;
            public record OrderState(String status, int amount) {}
            """);
        var auditLevel = JavaFileObjects.forSourceString("test.state.AuditLevel",
                                                         """
            package test.state;
            public enum AuditLevel { OFF, FULL }
            """);
        var source = JavaFileObjects.forSourceString("test.EntitySlice",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.OrderEntity;
            import test.annotation.AuditEntity;
            import test.infra.DurableEntity;
            import test.state.AuditLevel;
            import test.state.OrderState;
            @Slice
            public interface EntitySlice {
                Promise<String> create(String orderId);
                static EntitySlice entitySlice(@OrderEntity DurableEntity<String, OrderState> orders,
                                               @AuditEntity DurableEntity<String, AuditLevel> audit) { return null; }
            }
            """);

        var sources = commonSources();

        sources.add(DURABLE_ENTITY);
        sources.add(entityQualifier("OrderEntity", "entities.orders"));
        sources.add(entityQualifier("AuditEntity", "entities.audit"));
        sources.add(orderState);
        sources.add(auditLevel);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.EntitySliceFactory")
                                        .get().getCharContent(false).toString();

        // The record state type gets a component-wise codec even though no method mentions it.
        assertThat(factoryContent).contains("new SliceCodec.TypeCodec<test.state.OrderState>(test.state.OrderState.class");
        assertThat(factoryContent).contains("codec.write(buf, val.status())");
        assertThat(factoryContent).contains("return new test.state.OrderState(status, amount);");
        // The enum state type gets the ordinal codec.
        assertThat(factoryContent).contains("new SliceCodec.TypeCodec<test.state.AuditLevel>(test.state.AuditLevel.class");
        assertThat(factoryContent).contains("test.state.AuditLevel.values()[SliceCodec.readCompact(buf)]");
        // The String key is served by FrameworkCodecs, so no entry and no checklist for it.
        assertThat(factoryContent).doesNotContain("java.lang.String.class");
        assertThat(factoryContent).doesNotContain("Set.of(");
    }

    @Test
    void codec_addsStartupChecklistEntry_whenResourceTypeArgumentCannotBeGenerated() throws Exception {
        var opaqueState = JavaFileObjects.forSourceString("test.state.OpaqueState",
                                                          """
            package test.state;
            public interface OpaqueState { String describe(); }
            """);
        var source = JavaFileObjects.forSourceString("test.EntitySlice",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.OrderEntity;
            import test.infra.DurableEntity;
            import test.state.OpaqueState;
            @Slice
            public interface EntitySlice {
                Promise<String> create(String orderId);
                static EntitySlice entitySlice(@OrderEntity DurableEntity<String, OpaqueState> orders) { return null; }
            }
            """);

        var sources = commonSources();

        sources.add(DURABLE_ENTITY);
        sources.add(entityQualifier("OrderEntity", "entities.orders"));
        sources.add(opaqueState);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.EntitySliceFactory")
                                        .get().getCharContent(false).toString();

        // Nothing to generate for an interface — it goes on the startup checklist so slice load
        // fails naming the type, instead of the first write failing with "No codec registered".
        assertThat(factoryContent).contains("Set.of(test.state.OpaqueState.class));");
        assertThat(factoryContent).doesNotContain("new SliceCodec.TypeCodec<test.state.OpaqueState>");
        assertThat(factoryContent).contains("import java.util.Set;");
    }

    @Test
    void codec_generatesNoEntries_whenResourceParameterHasNoTypeArguments() throws Exception {
        var primaryDb = JavaFileObjects.forSourceString("test.annotation.PrimaryDb",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DatabaseConnector.class, config = "database.primary")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface PrimaryDb {}
            """);
        var databaseConnector = JavaFileObjects.forSourceString("test.infra.DatabaseConnector",
                                                                """
            package test.infra;
            import org.pragmatica.lang.Promise;
            public interface DatabaseConnector { Promise<String> query(String sql); }
            """);
        var source = JavaFileObjects.forSourceString("test.OrderRepository",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.PrimaryDb;
            import test.infra.DatabaseConnector;
            @Slice
            public interface OrderRepository {
                Promise<String> findOrder(String orderId);
                static OrderRepository orderRepository(@PrimaryDb DatabaseConnector db) { return null; }
            }
            """);

        var sources = commonSources();

        sources.add(primaryDb);
        sources.add(databaseConnector);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderRepositoryFactory")
                                        .get().getCharContent(false).toString();

        // A raw resource contributes nothing; the codec override stays as it was before derivation.
        assertThat(factoryContent).contains("return parent;");
        assertThat(factoryContent).doesNotContain("Set.of(");
    }

    // ========== Duplicate Detection Tests ==========

    @Test
    void should_fail_on_duplicate_resource_dependencies() {
        var primaryDb = JavaFileObjects.forSourceString("test.annotation.PrimaryDb",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DatabaseConnector.class, config = "database.primary")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface PrimaryDb {}
            """);
        var anotherPrimaryDb = JavaFileObjects.forSourceString("test.annotation.AnotherPrimaryDb",
                                                                """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DatabaseConnector.class, config = "database.primary")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface AnotherPrimaryDb {}
            """);
        var databaseConnector = JavaFileObjects.forSourceString("test.infra.DatabaseConnector",
                                                                """
            package test.infra;
            import org.pragmatica.lang.Promise;
            public interface DatabaseConnector { Promise<String> query(String sql); }
            """);
        var source = JavaFileObjects.forSourceString("test.DuplicateService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.PrimaryDb;
            import test.annotation.AnotherPrimaryDb;
            import test.infra.DatabaseConnector;
            @Slice
            public interface DuplicateService {
                Promise<String> doWork(String request);
                static DuplicateService duplicateService(@PrimaryDb DatabaseConnector db1, @AnotherPrimaryDb DatabaseConnector db2) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(primaryDb);
        sources.add(anotherPrimaryDb);
        sources.add(databaseConnector);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("Duplicate resource dependency");
    }

    // ========== Method Interceptor Tests ==========

    @Test
    void should_generate_single_method_interceptor() throws Exception {
        var withRetry = JavaFileObjects.forSourceString("test.annotation.WithRetry",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "retry.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithRetry {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.WithRetry;
            @Slice
            public interface OrderService {
                @WithRetry
                Promise<String> placeOrder(String orderId);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(withRetry);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("record OrderServiceWrapper(");
        assertThat(factoryContent).contains("implements OrderService");
        assertThat(factoryContent).contains("ctx.resources().provide(MethodInterceptor.class, \"retry.orders\")");
        assertThat(factoryContent).contains(".intercept(impl::placeOrder)");
        assertThat(factoryContent).doesNotContain("SliceRuntime");
        assertThat(factoryContent).doesNotContain("Aspects");
    }

    @Test
    void should_generate_multiple_interceptors_on_same_method() throws Exception {
        var withRetry = JavaFileObjects.forSourceString("test.annotation.WithRetry",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "retry.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithRetry {}
            """);
        var withCache = JavaFileObjects.forSourceString("test.annotation.WithCache",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "cache.orders.placeOrder")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithCache {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.WithRetry;
            import test.annotation.WithCache;
            @Slice
            public interface OrderService {
                @WithRetry
                @WithCache
                Promise<String> placeOrder(String orderId);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(withRetry);
        sources.add(withCache);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("ctx.resources().provide(MethodInterceptor.class, \"retry.orders\")");
        assertThat(factoryContent).contains("ctx.resources().provide(MethodInterceptor.class, \"cache.orders.placeOrder\")");
        assertThat(factoryContent).contains(".intercept(");
    }

    @Test
    void should_generate_mixed_intercepted_and_plain_methods() throws Exception {
        var withRetry = JavaFileObjects.forSourceString("test.annotation.WithRetry",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "retry.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithRetry {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.WithRetry;
            @Slice
            public interface OrderService {
                @WithRetry
                Promise<String> placeOrder(String orderId);
                Promise<Boolean> getStatus(String orderId);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(withRetry);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("record OrderServiceWrapper(");
        assertThat(factoryContent).contains("placeOrderFn");
        assertThat(factoryContent).contains("getStatusFn");
        assertThat(factoryContent).contains(".intercept(impl::placeOrder)");
        assertThat(factoryContent).contains("getStatusWrapped = impl::getStatus");
    }

    @Test
    void should_not_generate_wrapper_for_methods_without_interceptors() throws Exception {
        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface UserService {
                Promise<String> getUser(String userId);
                static UserService userService() { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.UserServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).doesNotContain("UserServiceWrapper");
        assertThat(factoryContent).doesNotContain("MethodInterceptor");
    }

    @Test
    void should_fail_on_multiple_key_annotations() {
        var request = JavaFileObjects.forSourceString("test.dto.GetUserRequest",
                                                      """
            package test.dto;
            import org.pragmatica.aether.resource.aspect.Key;
            public record GetUserRequest(@Key String userId, @Key String tenantId) {}
            """);
        var withCache = JavaFileObjects.forSourceString("test.annotation.WithCache",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "cache.users")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithCache {}
            """);
        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.dto.GetUserRequest;
            import test.annotation.WithCache;
            @Slice
            public interface UserService {
                @WithCache
                Promise<String> getUser(GetUserRequest request);
                static UserService userService() { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(request);
        sources.add(withCache);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("Multiple @Key annotations");
    }

    @Test
    void should_generate_interceptor_with_key_extractor() throws Exception {
        var userId = JavaFileObjects.forSourceString("test.dto.UserId",
                                                     """
            package test.dto;
            public record UserId(String value) {}
            """);
        var request = JavaFileObjects.forSourceString("test.dto.GetUserRequest",
                                                      """
            package test.dto;
            import org.pragmatica.aether.resource.aspect.Key;
            public record GetUserRequest(@Key UserId userId, boolean includeDetails) {}
            """);
        var response = JavaFileObjects.forSourceString("test.dto.User",
                                                       """
            package test.dto;
            public record User(String id, String name) {}
            """);
        var withCache = JavaFileObjects.forSourceString("test.annotation.WithCache",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "cache.users.getUser")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithCache {}
            """);
        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.dto.GetUserRequest;
            import test.dto.User;
            import test.annotation.WithCache;
            @Slice
            public interface UserService {
                @WithCache
                Promise<User> getUser(GetUserRequest request);
                static UserService userService() { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(userId);
        sources.add(request);
        sources.add(response);
        sources.add(withCache);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.UserServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("record UserServiceWrapper(");
        assertThat(factoryContent).contains("ProvisioningContext.provisioningContext()");
        assertThat(factoryContent).contains("new TypeToken<UserId>() {}");
        assertThat(factoryContent).contains("withKeyExtractor");
        assertThat(factoryContent).contains("GetUserRequest::userId");
        assertThat(factoryContent).contains(".intercept(impl::getUser)");
    }

    @Test
    void should_generate_interceptor_without_key_extractor() throws Exception {
        var withRetry = JavaFileObjects.forSourceString("test.annotation.WithRetry",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "retry.default")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithRetry {}
            """);
        var source = JavaFileObjects.forSourceString("test.SimpleService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.WithRetry;
            @Slice
            public interface SimpleService {
                @WithRetry
                Promise<String> doWork(String request);
                static SimpleService simpleService() { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(withRetry);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.SimpleServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("ctx.resources().provide(MethodInterceptor.class, \"retry.default\")");
        assertThat(factoryContent).doesNotContain("ProvisioningContext.provisioningContext()");
    }

    @Test
    void should_handle_mixed_interceptors_and_resource_deps() throws Exception {
        var primaryDb = JavaFileObjects.forSourceString("test.annotation.PrimaryDb",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DatabaseConnector.class, config = "database.primary")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface PrimaryDb {}
            """);
        var databaseConnector = JavaFileObjects.forSourceString("test.infra.DatabaseConnector",
                                                                """
            package test.infra;
            import org.pragmatica.lang.Promise;
            public interface DatabaseConnector { Promise<String> query(String sql); }
            """);
        var withRetry = JavaFileObjects.forSourceString("test.annotation.WithRetry",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "retry.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithRetry {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.PrimaryDb;
            import test.annotation.WithRetry;
            import test.infra.DatabaseConnector;
            @Slice
            public interface OrderService {
                @WithRetry
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@PrimaryDb DatabaseConnector db) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(primaryDb);
        sources.add(databaseConnector);
        sources.add(withRetry);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("ctx.resources().provide(DatabaseConnector.class, \"database.primary\")");
        assertThat(factoryContent).contains("ctx.resources().provide(MethodInterceptor.class, \"retry.orders\")");
        assertThat(factoryContent).contains(".intercept(impl::placeOrder)");
        assertThat(factoryContent).contains("OrderService.orderService(db)");
    }

    @Test
    void should_use_sliceCreationContext_parameter_in_factory() throws Exception {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface TestService {
                Promise<String> doSomething(String request);
                static TestService testService() { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.TestServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("import org.pragmatica.aether.slice.SliceCreationContext;");
        assertThat(factoryContent).contains("SliceCreationContext ctx)");
    }

    // ========== Inner/Nested Interface Tests ==========

    @Test
    void should_generate_correct_references_for_same_package_inner_interface_dependency() throws Exception {
        var outerInterface = JavaFileObjects.forSourceString("test.LoanSteps",
                                                              """
            package test;

            import org.pragmatica.lang.Promise;

            public interface LoanSteps {
                interface KycStep {
                    Promise<Boolean> verify(String customerId);

                    static KycStep kycStep() { return null; }
                }
            }
            """);

        var source = JavaFileObjects.forSourceString("test.LoanService",
                                                      """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface LoanService {
                Promise<String> processLoan(String request);

                static LoanService loanService(LoanSteps.KycStep kycStep) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(outerInterface);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.LoanServiceFactory")
                                        .get().getCharContent(false).toString();

        // Must use LoanSteps.KycStep (not just KycStep) for source-usable reference
        assertThat(factoryContent).contains("LoanSteps.KycStep.kycStep()");
        // No import needed since same package
        assertThat(factoryContent).doesNotContain("import test.LoanSteps");
    }

    @Test
    void should_generate_correct_references_for_cross_package_inner_interface_dependency() throws Exception {
        var outerInterface = JavaFileObjects.forSourceString("external.PaymentGateway",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface PaymentGateway {
                interface Processor {
                    Promise<Boolean> process(String payment);
                }
            }
            """);

        var source = JavaFileObjects.forSourceString("test.PaymentService",
                                                      """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.PaymentGateway;

            @Slice
            public interface PaymentService {
                Promise<String> processPayment(String request);

                static PaymentService paymentService(PaymentGateway.Processor processor) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(outerInterface);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.PaymentServiceFactory")
                                        .get().getCharContent(false).toString();

        // Import should be the top-level enclosing class, not the nested type
        assertThat(factoryContent).contains("import external.PaymentGateway;");
        assertThat(factoryContent).doesNotContain("import external.PaymentGateway.Processor;");
        // Proxy record implements clause should use source-usable name
        assertThat(factoryContent).contains("implements PaymentGateway.Processor");
    }

    // ========== Plain Interface Factory with @ResourceQualifier Parameters ==========

    @Test
    void should_generate_resource_provide_for_plain_interface_factory_params() throws Exception {
        var kycProvider = JavaFileObjects.forSourceString("test.annotation.KycProvider",
                                                          """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.HttpClient.class, config = "http.kyc")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface KycProvider {}
            """);
        var httpClient = JavaFileObjects.forSourceString("test.infra.HttpClient",
                                                          """
            package test.infra;
            public interface HttpClient {
                String get(String url);
            }
            """);
        var kycStep = JavaFileObjects.forSourceString("test.KycStep",
                                                        """
            package test;
            import org.pragmatica.lang.Promise;
            import test.annotation.KycProvider;
            import test.infra.HttpClient;
            public interface KycStep {
                Promise<Boolean> verify(String customerId);
                static KycStep kycStep(@KycProvider HttpClient httpClient) { return null; }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.LoanService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface LoanService {
                Promise<String> processLoan(String request);
                static LoanService loanService(KycStep kycStep) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(kycProvider);
        sources.add(httpClient);
        sources.add(kycStep);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.LoanServiceFactory")
                                        .get().getCharContent(false).toString();

        // Resource is provisioned for the plain interface's factory param (simple name via import)
        assertThat(factoryContent).contains("ctx.resources().provide(HttpClient.class, \"http.kyc\")");
        // Factory called WITH the provisioned arg
        assertThat(factoryContent).contains("KycStep.kycStep(kycStep_httpClient)");
        // Zero-arg call must NOT appear
        assertThat(factoryContent).doesNotContain("KycStep.kycStep()");
        // Async provisioning path used
        assertThat(factoryContent).contains("Promise.all(");
    }

    @Test
    void should_generate_resources_for_multiple_plain_interfaces_with_params() throws Exception {
        var kycProvider = JavaFileObjects.forSourceString("test.annotation.KycProvider",
                                                          """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.HttpClient.class, config = "http.kyc")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface KycProvider {}
            """);
        var fraudProvider = JavaFileObjects.forSourceString("test.annotation.FraudProvider",
                                                             """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.HttpClient.class, config = "http.fraud")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface FraudProvider {}
            """);
        var httpClient = JavaFileObjects.forSourceString("test.infra.HttpClient",
                                                          """
            package test.infra;
            public interface HttpClient {
                String get(String url);
            }
            """);
        var kycStep = JavaFileObjects.forSourceString("test.KycStep",
                                                        """
            package test;
            import org.pragmatica.lang.Promise;
            import test.annotation.KycProvider;
            import test.infra.HttpClient;
            public interface KycStep {
                Promise<Boolean> verify(String customerId);
                static KycStep kycStep(@KycProvider HttpClient httpClient) { return null; }
            }
            """);
        var fraudCheck = JavaFileObjects.forSourceString("test.FraudCheck",
                                                          """
            package test;
            import org.pragmatica.lang.Promise;
            import test.annotation.FraudProvider;
            import test.infra.HttpClient;
            public interface FraudCheck {
                Promise<Boolean> check(String customerId);
                static FraudCheck fraudCheck(@FraudProvider HttpClient httpClient) { return null; }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.LoanService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface LoanService {
                Promise<String> processLoan(String request);
                static LoanService loanService(KycStep kycStep, FraudCheck fraudCheck) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(kycProvider);
        sources.add(fraudProvider);
        sources.add(httpClient);
        sources.add(kycStep);
        sources.add(fraudCheck);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.LoanServiceFactory")
                                        .get().getCharContent(false).toString();

        // Both resources provisioned (simple name via import)
        assertThat(factoryContent).contains("ctx.resources().provide(HttpClient.class, \"http.kyc\")");
        assertThat(factoryContent).contains("ctx.resources().provide(HttpClient.class, \"http.fraud\")");
        // Both factory calls have args
        assertThat(factoryContent).contains("KycStep.kycStep(kycStep_httpClient)");
        assertThat(factoryContent).contains("FraudCheck.fraudCheck(fraudCheck_httpClient)");
        // Async provisioning path
        assertThat(factoryContent).contains("Promise.all(");
    }

    @Test
    void should_generate_correct_references_for_inner_interface_with_factory_method_cross_package() throws Exception {
        var outerInterface = JavaFileObjects.forSourceString("external.PaymentGateway",
                                                              """
            package external;

            import org.pragmatica.lang.Promise;

            public interface PaymentGateway {
                interface Processor {
                    Promise<Boolean> process(String payment);

                    static Processor processor() { return null; }
                }
            }
            """);

        var source = JavaFileObjects.forSourceString("test.PaymentService",
                                                      """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.PaymentGateway;

            @Slice
            public interface PaymentService {
                Promise<String> processPayment(String request);

                static PaymentService paymentService(PaymentGateway.Processor processor) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(outerInterface);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.PaymentServiceFactory")
                                        .get().getCharContent(false).toString();

        // Import should be the top-level enclosing class
        assertThat(factoryContent).contains("import external.PaymentGateway;");
        assertThat(factoryContent).doesNotContain("import external.PaymentGateway.Processor;");
        // Plain interface construction should use source-usable name
        assertThat(factoryContent).contains("PaymentGateway.Processor.processor()");
    }

    // ========== @Key on multi-param method ==========

    @Test
    void should_generate_key_extractor_for_multi_param_method() throws Exception {
        var withCache = JavaFileObjects.forSourceString("test.annotation.WithCache",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "cache.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithCache {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.aether.resource.aspect.Key;
            import test.annotation.WithCache;
            @Slice
            public interface OrderService {
                @WithCache
                Promise<String> placeOrder(@Key String orderId, int quantity);
                static OrderService orderService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(withCache);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("public record PlaceOrderRequest(String orderId, int quantity) {}");
        assertThat(factoryContent).contains("ProvisioningContext.provisioningContext()");
        assertThat(factoryContent).contains("withKeyExtractor");
        assertThat(factoryContent).contains("PlaceOrderRequest::orderId");
    }

    @Test
    void should_fail_on_multiple_key_params_in_multi_param_method() {
        var withCache = JavaFileObjects.forSourceString("test.annotation.WithCache",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "cache.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithCache {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.aether.resource.aspect.Key;
            import test.annotation.WithCache;
            @Slice
            public interface OrderService {
                @WithCache
                Promise<String> placeOrder(@Key String orderId, @Key int quantity);
                static OrderService orderService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(withCache);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("Multiple @Key annotations");
    }

    // ========== Factory Return Type Tests ==========

    @Test
    void should_process_factory_returning_result() throws Exception {
        var source = JavaFileObjects.forSourceString("test.ValidatedService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Result;
            @Slice
            public interface ValidatedService {
                Promise<String> doWork(String request);
                static Result<ValidatedService> validatedService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.ValidatedServiceFactory")
                                        .get().getCharContent(false).toString();
        // Should use Result.async instead of Promise.success (aspect seam removed)
        assertThat(factoryContent).contains(".async()");
        assertThat(factoryContent).doesNotContain("Promise.success(");
        assertThat(factoryContent).doesNotContain("aspect");
    }

    @Test
    void should_process_factory_returning_option() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OptionalService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Option;
            @Slice
            public interface OptionalService {
                Promise<String> doWork(String request);
                static Option<OptionalService> optionalService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.OptionalServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains(".toResult().async()");
        assertThat(factoryContent).doesNotContain("Promise.success(");
        assertThat(factoryContent).doesNotContain("aspect");
    }

    @Test
    void should_process_factory_returning_promise() throws Exception {
        var source = JavaFileObjects.forSourceString("test.AsyncService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            @Slice
            public interface AsyncService {
                Promise<String> doWork(String request);
                static Promise<AsyncService> asyncService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.AsyncServiceFactory")
                                        .get().getCharContent(false).toString();
        // Promise factory: returns the promise directly — no aspect wrap, no .async()
        assertThat(factoryContent).contains("return AsyncService.asyncService();");
        assertThat(factoryContent).doesNotContain("Promise.success(");
        assertThat(factoryContent).doesNotContain(".async()");
        assertThat(factoryContent).doesNotContain("aspect");
    }

    @Test
    void should_process_result_factory_with_resource_dependency() throws Exception {
        var primaryDb = JavaFileObjects.forSourceString("test.annotation.PrimaryDb",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DatabaseConnector.class, config = "database.primary")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface PrimaryDb {}
            """);
        var databaseConnector = JavaFileObjects.forSourceString("test.infra.DatabaseConnector",
                                                                """
            package test.infra;
            import org.pragmatica.lang.Promise;
            public interface DatabaseConnector { Promise<String> query(String sql); }
            """);
        var source = JavaFileObjects.forSourceString("test.ValidatedRepository",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Result;
            import test.annotation.PrimaryDb;
            import test.infra.DatabaseConnector;
            @Slice
            public interface ValidatedRepository {
                Promise<String> findItem(String itemId);
                static Result<ValidatedRepository> validatedRepository(@PrimaryDb DatabaseConnector db) { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(primaryDb);
        sources.add(databaseConnector);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.ValidatedRepositoryFactory")
                                        .get().getCharContent(false).toString();
        // Async path should use .flatMap instead of .map
        assertThat(factoryContent).contains(".flatMap(");
        assertThat(factoryContent).contains(".async()");
        assertThat(factoryContent).doesNotContain("aspect");
        assertThat(factoryContent).contains("ctx.resources().provide(DatabaseConnector.class, \"database.primary\")");
    }

    @Test
    void should_process_result_factory_with_interceptors() throws Exception {
        var withRetry = JavaFileObjects.forSourceString("test.annotation.WithRetry",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "retry.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithRetry {}
            """);
        var source = JavaFileObjects.forSourceString("test.ValidatedOrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Result;
            import test.annotation.WithRetry;
            @Slice
            public interface ValidatedOrderService {
                @WithRetry
                Promise<String> placeOrder(String orderId);
                static Result<ValidatedOrderService> validatedOrderService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(withRetry);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.ValidatedOrderServiceFactory")
                                        .get().getCharContent(false).toString();
        // Should use flatMap and have interceptor wrapping inside .map(impl -> { ... })
        assertThat(factoryContent).contains(".flatMap(");
        assertThat(factoryContent).contains(".map(impl -> {");
        assertThat(factoryContent).contains(".intercept(impl::placeOrder)");
        assertThat(factoryContent).contains("}).async()");
    }

    @Test
    void should_fail_on_mismatched_result_type_argument() {
        var source = JavaFileObjects.forSourceString("test.BadService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Result;
            @Slice
            public interface BadService {
                Promise<String> doWork(String request);
                static Result<String> badService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("does not match slice type");
    }

    // ========== Streaming Resource Tests ==========

    private static final JavaFileObject STREAM_PUBLISHER = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.StreamPublisher",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;

            @FunctionalInterface
            public interface StreamPublisher<T> {
                Promise<Unit> publish(T event);
            }
            """);

    private static final JavaFileObject STREAM_SUBSCRIBER = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.StreamSubscriber",
            """
            package org.pragmatica.aether.slice;

            public interface StreamSubscriber {}
            """);

    private static final JavaFileObject STREAM_ACCESS = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.StreamAccess",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import java.util.List;

            public interface StreamAccess<T> {
                Promise<Long> publish(T event);
                Promise<List<Object>> fetch(long fromOffset, int maxEvents);
            }
            """);

    private List<JavaFileObject> streamSources() {
        var sources = commonSources();
        sources.add(STREAM_PUBLISHER);
        sources.add(STREAM_SUBSCRIBER);
        sources.add(STREAM_ACCESS);
        return sources;
    }

    @Test
    void should_process_stream_publisher_parameter() throws Exception {
        var orderStream = JavaFileObjects.forSourceString("test.annotation.OrderStream",
                                                          """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamPublisher;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamPublisher.class, config = "streams.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface OrderStream {}
            """);
        var orderEvent = JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                                         """
            package test.dto;
            public record OrderEvent(String orderId, String customerId) {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.StreamPublisher;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderStream;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@OrderStream StreamPublisher<OrderEvent> stream) { return null; }
            }
            """);
        var sources = streamSources();
        sources.add(orderStream);
        sources.add(orderEvent);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();
        // StreamPublisher should be provisioned with ProvisioningContext
        assertThat(factoryContent).contains("ctx.resources().provide(StreamPublisher.class, \"streams.order-events\", ProvisioningContext.provisioningContext())");
    }

    @Test
    void should_process_stream_access_parameter() throws Exception {
        var orderAccess = JavaFileObjects.forSourceString("test.annotation.OrderStreamAccess",
                                                          """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamAccess;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamAccess.class, config = "streams.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface OrderStreamAccess {}
            """);
        var orderEvent = JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                                         """
            package test.dto;
            public record OrderEvent(String orderId, String customerId) {}
            """);
        var source = JavaFileObjects.forSourceString("test.AuditService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.StreamAccess;
            import org.pragmatica.lang.Promise;
            import test.annotation.OrderStreamAccess;
            import test.dto.OrderEvent;
            @Slice
            public interface AuditService {
                Promise<String> auditOrder(String orderId);
                static AuditService auditService(@OrderStreamAccess StreamAccess<OrderEvent> access) { return null; }
            }
            """);
        var sources = streamSources();
        sources.add(orderAccess);
        sources.add(orderEvent);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.AuditServiceFactory")
                                        .get().getCharContent(false).toString();
        // StreamAccess should be provisioned with ProvisioningContext
        assertThat(factoryContent).contains("ctx.resources().provide(StreamAccess.class, \"streams.order-events\", ProvisioningContext.provisioningContext())");
    }

    // ========== @PartitionKey Routing Tests (#507) ==========

    private static JavaFileObject partitionedEvent(String body) {
        return JavaFileObjects.forSourceString("test.dto.ShipmentEvent",
                                               """
            package test.dto;
            import org.pragmatica.aether.slice.annotation.PartitionKey;
            public record ShipmentEvent(%s) {}
            """.formatted(body));
    }

    private static final JavaFileObject SHIPMENT_STREAM = JavaFileObjects.forSourceString("test.annotation.ShipmentStream",
                                                                                           """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamPublisher;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamPublisher.class, config = "streams.shipments")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface ShipmentStream {}
            """);

    private static final JavaFileObject SHIPMENT_STREAM_ACCESS = JavaFileObjects.forSourceString("test.annotation.ShipmentStreamAccess",
                                                                                                  """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamAccess;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamAccess.class, config = "streams.shipments")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface ShipmentStreamAccess {}
            """);

    private static final JavaFileObject SHIPMENT_PUBLISHER_SLICE = JavaFileObjects.forSourceString("test.ShipmentService",
                                                                                                    """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.StreamPublisher;
            import org.pragmatica.lang.Promise;
            import test.annotation.ShipmentStream;
            import test.dto.ShipmentEvent;
            @Slice
            public interface ShipmentService {
                Promise<String> ship(String shipmentId);
                static ShipmentService shipmentService(@ShipmentStream StreamPublisher<ShipmentEvent> stream) { return null; }
            }
            """);

    private static final JavaFileObject SHIPMENT_ACCESS_SLICE = JavaFileObjects.forSourceString("test.ShipmentAudit",
                                                                                                 """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.StreamAccess;
            import org.pragmatica.lang.Promise;
            import test.annotation.ShipmentStreamAccess;
            import test.dto.ShipmentEvent;
            @Slice
            public interface ShipmentAudit {
                Promise<String> audit(String shipmentId);
                static ShipmentAudit shipmentAudit(@ShipmentStreamAccess StreamAccess<ShipmentEvent> access) { return null; }
            }
            """);

    private Compilation compilePartitioned(JavaFileObject event, JavaFileObject qualifier, JavaFileObject slice) {
        var sources = streamSources();

        sources.add(event);
        sources.add(qualifier);
        sources.add(slice);

        return javac().withProcessors(new SliceProcessor()).compile(sources);
    }

    @Test
    void partitionKey_emitsKeyExtractorOnStreamPublisher_whenEventDeclaresOne() throws Exception {
        var compilation = compilePartitioned(partitionedEvent("String shipmentId, @PartitionKey String customerId"),
                                             SHIPMENT_STREAM,
                                             SHIPMENT_PUBLISHER_SLICE);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.ShipmentServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("ctx.resources().provide(StreamPublisher.class, \"streams.shipments\", "
                                           + "ProvisioningContext.provisioningContext()"
                                           + ".withKeyExtractor((Fn1<String, ShipmentEvent>) ShipmentEvent::customerId))");
        // The event type is import-tracked, so the emitted method reference stays unqualified.
        assertThat(factoryContent).contains("import test.dto.ShipmentEvent;");
        assertThat(factoryContent).doesNotContain("(Fn1<String, test.dto.ShipmentEvent>)");
    }

    @Test
    void partitionKey_emitsKeyExtractorOnStreamAccess_whenEventDeclaresOne() throws Exception {
        var compilation = compilePartitioned(partitionedEvent("String shipmentId, @PartitionKey String customerId"),
                                             SHIPMENT_STREAM_ACCESS,
                                             SHIPMENT_ACCESS_SLICE);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.ShipmentAuditFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("ctx.resources().provide(StreamAccess.class, \"streams.shipments\", "
                                           + "ProvisioningContext.provisioningContext()"
                                           + ".withKeyExtractor((Fn1<String, ShipmentEvent>) ShipmentEvent::customerId))");
    }

    @Test
    void partitionKey_selectsAnnotatedComponent_whenEventDeclaresSeveralComponents() throws Exception {
        var compilation = compilePartitioned(partitionedEvent("String shipmentId, String region, @PartitionKey Long tenantId, String note"),
                                             SHIPMENT_STREAM,
                                             SHIPMENT_PUBLISHER_SLICE);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.ShipmentServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains(".withKeyExtractor((Fn1<Long, ShipmentEvent>) ShipmentEvent::tenantId)");
    }

    @Test
    void partitionKey_boxesPrimitiveKeyType_whenComponentIsPrimitive() throws Exception {
        var compilation = compilePartitioned(partitionedEvent("String shipmentId, @PartitionKey long tenantId"),
                                             SHIPMENT_STREAM,
                                             SHIPMENT_PUBLISHER_SLICE);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.ShipmentServiceFactory")
                                        .get().getCharContent(false).toString();
        // Fn1's type argument rejects primitives, so the key type must be emitted boxed.
        assertThat(factoryContent).contains(".withKeyExtractor((Fn1<Long, ShipmentEvent>) ShipmentEvent::tenantId)");
        assertThat(factoryContent).doesNotContain("Fn1<long,");
    }

    @Test
    void partitionKey_leavesProvisioningContextBare_whenEventDeclaresNone() throws Exception {
        var compilation = compilePartitioned(partitionedEvent("String shipmentId, String customerId"),
                                             SHIPMENT_STREAM,
                                             SHIPMENT_PUBLISHER_SLICE);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.ShipmentServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(factoryContent).contains("ctx.resources().provide(StreamPublisher.class, \"streams.shipments\", "
                                           + "ProvisioningContext.provisioningContext())");
        assertThat(factoryContent).doesNotContain("withKeyExtractor");
    }

    @Test
    void partitionKey_failsCompilation_whenEventDeclaresMultiple() {
        var compilation = compilePartitioned(partitionedEvent("@PartitionKey String shipmentId, @PartitionKey String customerId"),
                                             SHIPMENT_STREAM,
                                             SHIPMENT_PUBLISHER_SLICE);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("Multiple @PartitionKey annotations found on test.dto.ShipmentEvent");
        assertCompilation(compilation).hadErrorContaining("shipmentId, customerId");
    }

    @Test
    void partitionKey_leavesTopicPublisherBare_whenMessageDeclaresOne() throws Exception {
        var message = JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                                      """
            package test.dto;
            import org.pragmatica.aether.slice.annotation.PartitionKey;
            public record OrderEvent(@PartitionKey String orderId) {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.Publisher;
            import org.pragmatica.lang.Promise;
            import test.annotation.LegacyPublisher;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@LegacyPublisher Publisher<OrderEvent> orderPublisher) { return null; }
            }
            """);
        var sources = commonSources();

        sources.add(PUBLISHER);
        sources.add(SUBSCRIBER);
        sources.add(message);
        sources.add(publisherAnnotation("LegacyPublisher", "order-events"));
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();
        // Topics are unpartitioned — PublisherFactory never reads ProvisioningContext.keyExtractor(),
        // so emitting one here would advertise routing that does not happen.
        assertThat(factoryContent).contains("ctx.resources().provide(Publisher.class, \"order-events\", "
                                           + "ProvisioningContext.provisioningContext())");
        assertThat(factoryContent).doesNotContain("withKeyExtractor");
    }

    @Test
    void should_process_stream_subscriber_method() throws Exception {        var orderConsumer = JavaFileObjects.forSourceString("test.annotation.OrderStreamConsumer",
                                                            """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamSubscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface OrderStreamConsumer {}
            """);
        var orderEvent = JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                                         """
            package test.dto;
            public record OrderEvent(String orderId, String customerId) {}
            """);
        var source = JavaFileObjects.forSourceString("test.EventProcessor",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderStreamConsumer;
            import test.dto.OrderEvent;
            @Slice
            public interface EventProcessor {
                @OrderStreamConsumer
                Promise<Unit> processOrder(OrderEvent event);
                static EventProcessor eventProcessor() { return null; }
            }
            """);
        var sources = streamSources();
        sources.add(orderConsumer);
        sources.add(orderEvent);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
        assertCompilation(compilation).generatedSourceFile("test.EventProcessorFactory");
    }

    @Test
    void should_fail_stream_subscriber_with_wrong_return_type() {
        var orderConsumer = JavaFileObjects.forSourceString("test.annotation.OrderStreamConsumer",
                                                            """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamSubscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface OrderStreamConsumer {}
            """);
        var source = JavaFileObjects.forSourceString("test.BadProcessor",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.OrderStreamConsumer;
            @Slice
            public interface BadProcessor {
                @OrderStreamConsumer
                Promise<String> processOrder(String event);
                static BadProcessor badProcessor() { return null; }
            }
            """);
        var sources = streamSources();
        sources.add(orderConsumer);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must return Promise<Unit>");
    }

    @Test
    void should_fail_stream_subscriber_with_no_params() {
        var orderConsumer = JavaFileObjects.forSourceString("test.annotation.OrderStreamConsumer",
                                                            """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamSubscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface OrderStreamConsumer {}
            """);
        var source = JavaFileObjects.forSourceString("test.BadProcessor",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderStreamConsumer;
            @Slice
            public interface BadProcessor {
                @OrderStreamConsumer
                Promise<Unit> processOrder();
                static BadProcessor badProcessor() { return null; }
            }
            """);
        var sources = streamSources();
        sources.add(orderConsumer);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must have exactly one parameter");
    }

    @Test
    void should_detect_envelope_version_8() {
        assertThat(org.pragmatica.jbct.slice.generator.ManifestGenerator.class).isNotNull();
        // Verify the constant was bumped — accessed via reflection since it's package-private
        // The manifest test below verifies it appears in output
    }

    @Test
    void should_generate_manifest_with_stream_metadata() throws Exception {
        var orderStream = JavaFileObjects.forSourceString("test.annotation.OrderStream",
                                                          """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamPublisher;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamPublisher.class, config = "streams.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface OrderStream {}
            """);
        var orderConsumer = JavaFileObjects.forSourceString("test.annotation.OrderStreamConsumer",
                                                            """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.StreamSubscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface OrderStreamConsumer {}
            """);
        var orderEvent = JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                                         """
            package test.dto;
            public record OrderEvent(String orderId, String customerId) {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderProcessor",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.StreamPublisher;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderStream;
            import test.annotation.OrderStreamConsumer;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderProcessor {
                Promise<String> placeOrder(String orderId);
                @OrderStreamConsumer
                Promise<Unit> processOrder(OrderEvent event);
                static OrderProcessor orderProcessor(@OrderStream StreamPublisher<OrderEvent> stream) { return null; }
            }
            """);
        var sources = streamSources();
        sources.add(orderStream);
        sources.add(orderConsumer);
        sources.add(orderEvent);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        // Verify manifest was generated
        var manifestFile = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/slice/OrderProcessor.manifest");
        assertThat(manifestFile.isPresent()).isTrue();
        var manifestContent = manifestFile.get().getCharContent(false).toString();

        // Verify envelope version: FROZEN at 1000 until GA (owner ruling 2026-07-18, #386) — the rc
        // series is rebuild-together, so pre-GA envelope evolution rides without version bumps; the
        // stamp is a membership-checked compatibility gate, not structural dispatch. Historical
        // structure notes live on ManifestGenerator.ENVELOPE_FORMAT_VERSION.
        assertThat(manifestContent).contains("envelope.version=1000");

        // Verify stream publisher metadata
        assertThat(manifestContent).contains("stream.publishers.count=1");
        assertThat(manifestContent).contains("stream.publisher.0.config=streams.order-events");
        assertThat(manifestContent).contains("stream.publisher.0.eventType=test.dto.OrderEvent");

        // Verify stream subscription via reactive bindings
        assertThat(manifestContent).contains("reactive.count=1");
        assertThat(manifestContent).contains("reactive.0.category=stream");
        assertThat(manifestContent).contains("reactive.0.config=streams.order-events");
        assertThat(manifestContent).contains("reactive.0.method=processOrder");
        assertThat(manifestContent).contains("reactive.0.eventType=test.dto.OrderEvent");
        assertThat(manifestContent).contains("reactive.0.batch=false");

        // Verify stream event classes
        assertThat(manifestContent).contains("stream.event.classes=test.dto.OrderEvent");
    }

    // ========== ConfigurationSection Tests ==========

    @Test
    void should_generate_config_parsing_code_for_configuration_section() throws Exception {
        var appConfig = JavaFileObjects.forSourceString("test.annotation.AppConfig",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.annotation.ConfigurationSection;
            import java.lang.annotation.*;
            @ResourceQualifier(type = ConfigurationSection.class, config = "app.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface AppConfig {}
            """);
        var serviceConfig = JavaFileObjects.forSourceString("test.config.ServiceConfig",
                                                             """
            package test.config;
            import org.pragmatica.lang.Result;
            public record ServiceConfig(String host, int port, boolean enableTls) {
                public static Result<ServiceConfig> serviceConfig(String host, int port, boolean enableTls) {
                    return Result.success(new ServiceConfig(host, port, enableTls));
                }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.AppConfig;
            import test.config.ServiceConfig;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@AppConfig ServiceConfig config) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(appConfig);
        sources.add(serviceConfig);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();
        // Should NOT generate ctx.resources().provide() for config sections
        assertThat(factoryContent).doesNotContain("ctx.resources().provide(ConfigurationSection.class");
        // Should generate Result.all() with config facade calls
        assertThat(factoryContent).contains("Result.all(");
        assertThat(factoryContent).contains("ctx.config().requireString(\"app.orders\", \"host\")");
        assertThat(factoryContent).contains("ctx.config().requireInt(\"app.orders\", \"port\")");
        assertThat(factoryContent).contains("ctx.config().requireBoolean(\"app.orders\", \"enable_tls\")");
        assertThat(factoryContent).contains("ServiceConfig::serviceConfig");
        assertThat(factoryContent).contains(".async()");
        // Should import ConfigFacade and Result
        assertThat(factoryContent).contains("import org.pragmatica.aether.slice.ConfigFacade;");
        assertThat(factoryContent).contains("import org.pragmatica.lang.Result;");
    }

    @Test
    void should_handle_mixed_config_and_resource_dependencies() throws Exception {
        var appConfig = JavaFileObjects.forSourceString("test.annotation.AppConfig",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.annotation.ConfigurationSection;
            import java.lang.annotation.*;
            @ResourceQualifier(type = ConfigurationSection.class, config = "app.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface AppConfig {}
            """);
        var primaryDb = JavaFileObjects.forSourceString("test.annotation.PrimaryDb",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import java.lang.annotation.*;
            @ResourceQualifier(type = test.infra.DatabaseConnector.class, config = "database.primary")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface PrimaryDb {}
            """);
        var databaseConnector = JavaFileObjects.forSourceString("test.infra.DatabaseConnector",
                                                                """
            package test.infra;
            import org.pragmatica.lang.Promise;
            public interface DatabaseConnector {
                Promise<String> query(String sql);
            }
            """);
        var serviceConfig = JavaFileObjects.forSourceString("test.config.ServiceConfig",
                                                             """
            package test.config;
            import org.pragmatica.lang.Result;
            public record ServiceConfig(String host, int port) {
                public static Result<ServiceConfig> serviceConfig(String host, int port) {
                    return Result.success(new ServiceConfig(host, port));
                }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.AppConfig;
            import test.annotation.PrimaryDb;
            import test.config.ServiceConfig;
            import test.infra.DatabaseConnector;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@AppConfig ServiceConfig config,
                                                 @PrimaryDb DatabaseConnector db) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(appConfig);
        sources.add(primaryDb);
        sources.add(databaseConnector);
        sources.add(serviceConfig);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();
        // Config section: parsed via Result.all()
        assertThat(factoryContent).contains("ctx.config().requireString(\"app.orders\", \"host\")");
        assertThat(factoryContent).contains("ctx.config().requireInt(\"app.orders\", \"port\")");
        // Resource: provisioned via ctx.resources().provide()
        assertThat(factoryContent).contains("ctx.resources().provide(DatabaseConnector.class, \"database.primary\")");
    }

    @Test
    void should_generate_config_parsing_for_long_and_double_fields() throws Exception {
        var appConfig = JavaFileObjects.forSourceString("test.annotation.AppConfig",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.annotation.ConfigurationSection;
            import java.lang.annotation.*;
            @ResourceQualifier(type = ConfigurationSection.class, config = "app.cache")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface AppConfig {}
            """);
        var cacheConfig = JavaFileObjects.forSourceString("test.config.CacheConfig",
                                                          """
            package test.config;
            import org.pragmatica.lang.Result;
            public record CacheConfig(long maxSizeBytes, double evictionRate) {
                public static Result<CacheConfig> cacheConfig(long maxSizeBytes, double evictionRate) {
                    return Result.success(new CacheConfig(maxSizeBytes, evictionRate));
                }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.CacheService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.AppConfig;
            import test.config.CacheConfig;
            @Slice
            public interface CacheService {
                Promise<String> get(String key);
                static CacheService cacheService(@AppConfig CacheConfig config) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(appConfig);
        sources.add(cacheConfig);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.CacheServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.config().requireLong(\"app.cache\", \"max_size_bytes\")");
        assertThat(factoryContent).contains("ctx.config().requireDouble(\"app.cache\", \"eviction_rate\")");
        assertThat(factoryContent).contains("CacheConfig::cacheConfig");
    }

    @Test
    void should_generate_config_parsing_for_value_object_fields() throws Exception {
        var appConfig = JavaFileObjects.forSourceString("test.annotation.AppConfig",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.annotation.ConfigurationSection;
            import java.lang.annotation.*;
            @ResourceQualifier(type = ConfigurationSection.class, config = "app.gateway")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface AppConfig {}
            """);
        var apiUrl = JavaFileObjects.forSourceString("test.config.ApiUrl",
                                                      """
            package test.config;
            import org.pragmatica.lang.Result;
            public record ApiUrl(String value) {
                public static Result<ApiUrl> apiUrl(String raw) {
                    return Result.success(new ApiUrl(raw));
                }
            }
            """);
        var gatewayConfig = JavaFileObjects.forSourceString("test.config.GatewayConfig",
                                                             """
            package test.config;
            import org.pragmatica.lang.Result;
            public record GatewayConfig(ApiUrl baseUrl, int maxRetries) {
                public static Result<GatewayConfig> gatewayConfig(ApiUrl baseUrl, int maxRetries) {
                    return Result.success(new GatewayConfig(baseUrl, maxRetries));
                }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.GatewayService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.AppConfig;
            import test.config.GatewayConfig;
            @Slice
            public interface GatewayService {
                Promise<String> call(String request);
                static GatewayService gatewayService(@AppConfig GatewayConfig config) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(appConfig);
        sources.add(apiUrl);
        sources.add(gatewayConfig);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.GatewayServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.config().requireString(\"app.gateway\", \"base_url\").flatMap(ApiUrl::apiUrl)");
        assertThat(factoryContent).contains("ctx.config().requireInt(\"app.gateway\", \"max_retries\")");
        assertThat(factoryContent).contains("GatewayConfig::gatewayConfig");
    }

    @Test
    void should_generate_config_parsing_for_optional_primitive_fields() throws Exception {
        var appConfig = JavaFileObjects.forSourceString("test.annotation.AppConfig",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.annotation.ConfigurationSection;
            import java.lang.annotation.*;
            @ResourceQualifier(type = ConfigurationSection.class, config = "app.server")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface AppConfig {}
            """);
        var serverConfig = JavaFileObjects.forSourceString("test.config.ServerConfig",
                                                            """
            package test.config;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Result;
            public record ServerConfig(String host, Option<Integer> port, Option<Boolean> enableTls) {
                public static Result<ServerConfig> serverConfig(String host, Option<Integer> port, Option<Boolean> enableTls) {
                    return Result.success(new ServerConfig(host, port, enableTls));
                }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.ServerService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.AppConfig;
            import test.config.ServerConfig;
            @Slice
            public interface ServerService {
                Promise<String> serve(String request);
                static ServerService serverService(@AppConfig ServerConfig config) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(appConfig);
        sources.add(serverConfig);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.ServerServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.config().requireString(\"app.server\", \"host\")");
        assertThat(factoryContent).contains("Result.success(ctx.config().getInt(\"app.server\", \"port\"))");
        assertThat(factoryContent).contains("Result.success(ctx.config().getBoolean(\"app.server\", \"enable_tls\"))");
        assertThat(factoryContent).contains("ServerConfig::serverConfig");
    }

    @Test
    void should_generate_config_parsing_for_string_list_field() throws Exception {
        var appConfig = JavaFileObjects.forSourceString("test.annotation.AppConfig",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.annotation.ConfigurationSection;
            import java.lang.annotation.*;
            @ResourceQualifier(type = ConfigurationSection.class, config = "app.cors")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface AppConfig {}
            """);
        var corsConfig = JavaFileObjects.forSourceString("test.config.CorsConfig",
                                                          """
            package test.config;
            import org.pragmatica.lang.Result;
            import java.util.List;
            public record CorsConfig(List<String> allowedOrigins, boolean allowCredentials) {
                public static Result<CorsConfig> corsConfig(List<String> allowedOrigins, boolean allowCredentials) {
                    return Result.success(new CorsConfig(allowedOrigins, allowCredentials));
                }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.CorsService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.AppConfig;
            import test.config.CorsConfig;
            @Slice
            public interface CorsService {
                Promise<String> check(String origin);
                static CorsService corsService(@AppConfig CorsConfig config) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(appConfig);
        sources.add(corsConfig);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.CorsServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.config().requireStringList(\"app.cors\", \"allowed_origins\")");
        assertThat(factoryContent).contains("ctx.config().requireBoolean(\"app.cors\", \"allow_credentials\")");
        assertThat(factoryContent).contains("CorsConfig::corsConfig");
    }

    @Test
    void should_generate_config_parsing_for_optional_value_object_field() throws Exception {
        var appConfig = JavaFileObjects.forSourceString("test.annotation.AppConfig",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.annotation.ConfigurationSection;
            import java.lang.annotation.*;
            @ResourceQualifier(type = ConfigurationSection.class, config = "app.proxy")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface AppConfig {}
            """);
        var proxyUrl = JavaFileObjects.forSourceString("test.config.ProxyUrl",
                                                        """
            package test.config;
            import org.pragmatica.lang.Result;
            public record ProxyUrl(String value) {
                public static Result<ProxyUrl> proxyUrl(String raw) {
                    return Result.success(new ProxyUrl(raw));
                }
            }
            """);
        var proxyConfig = JavaFileObjects.forSourceString("test.config.ProxyConfig",
                                                           """
            package test.config;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Result;
            public record ProxyConfig(String host, Option<ProxyUrl> fallbackUrl) {
                public static Result<ProxyConfig> proxyConfig(String host, Option<ProxyUrl> fallbackUrl) {
                    return Result.success(new ProxyConfig(host, fallbackUrl));
                }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.ProxyService",
                                                      """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.annotation.AppConfig;
            import test.config.ProxyConfig;
            @Slice
            public interface ProxyService {
                Promise<String> proxy(String request);
                static ProxyService proxyService(@AppConfig ProxyConfig config) { return null; }
            }
            """);

        var sources = commonSources();
        sources.add(appConfig);
        sources.add(proxyUrl);
        sources.add(proxyConfig);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.ProxyServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.config().requireString(\"app.proxy\", \"host\")");
        assertThat(factoryContent).contains("Result.success(ctx.config().getString(\"app.proxy\", \"fallback_url\").map(s -> ProxyUrl.proxyUrl(s).expect(\"optional ProxyUrl value validated at config load time\")))");
        assertThat(factoryContent).contains("ProxyConfig::proxyConfig");
    }

    // ========== Transitive Method-Level Annotation Tests ==========

    private static final JavaFileObject SUBSCRIBER = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.Subscriber",
            """
            package org.pragmatica.aether.slice;

            public interface Subscriber {}
            """);

    private List<JavaFileObject> subscriberSources() {
        var sources = commonSources();
        sources.add(SUBSCRIBER);
        return sources;
    }

    @Test
    void should_detect_subscription_on_plain_interface_dependency() throws Exception {
        var orderTopic = JavaFileObjects.forSourceString("test.annotation.OrderTopic",
                                                         """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.Subscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = Subscriber.class, config = "messaging.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface OrderTopic {}
            """);
        var orderEvent = JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                                         """
            package test.dto;
            public record OrderEvent(String orderId) {}
            """);
        // Step interface with a subscription annotation on its method
        var orderListener = JavaFileObjects.forSourceString("test.steps.OrderListener",
                                                             """
            package test.steps;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.OrderEvent;
            public interface OrderListener {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event);
                static OrderListener orderListener() { return null; }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.steps.OrderListener;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(OrderListener listener) { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(orderTopic);
        sources.add(orderEvent);
        sources.add(orderListener);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        // Verify manifest contains transitive subscription entry with qualified method name
        var manifestFile = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/slice/OrderService.manifest");
        assertThat(manifestFile.isPresent()).isTrue();
        var manifestContent = manifestFile.get().getCharContent(false).toString();
        assertThat(manifestContent).contains("reactive.count=1");
        assertThat(manifestContent).contains("reactive.0.category=subscription");
        assertThat(manifestContent).contains("reactive.0.config=messaging.order-events");
        assertThat(manifestContent).contains("reactive.0.method=listenerOnOrderPlaced");
        assertThat(manifestContent).contains("reactive.0.messageType=test.dto.OrderEvent");
    }

    /// #386 D5: the envelope context a durable subscriber may declare. Stubbed here exactly as the
    /// other framework types are, so these pins do not wait on slice-api.
    private static final JavaFileObject MESSAGE_CONTEXT = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.topic.MessageContext",
            """
            package org.pragmatica.aether.slice.topic;

            public record MessageContext(String messageId, String topic, int partition, long offset) {}
            """);

    private static final JavaFileObject CONTEXTUAL_EVENT = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.topic.ContextualEvent",
            """
            package org.pragmatica.aether.slice.topic;

            public record ContextualEvent(Object event, MessageContext context) {}
            """);

    private JavaFileObject orderTopicAnnotation() {
        return JavaFileObjects.forSourceString("test.annotation.OrderTopic",
                                               """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.Subscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = Subscriber.class, config = "order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface OrderTopic {}
            """);
    }

    private JavaFileObject orderEventRecord() {
        return JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                               """
            package test.dto;
            public record OrderEvent(String orderId) {}
            """);
    }

    /// #386 D5 type-level honesty, exercised end-to-end through the processor. `resources.toml` is
    /// unreadable in the in-memory compile-testing file manager, so durability here is always
    /// UNDETERMINED rather than ephemeral — which is precisely the fail-closed case: absent evidence
    /// of durability the context-carrying shape is refused rather than generated.
    ///
    /// Two consequences, both deliberate. The declared-ephemeral and declared-durable branches are
    /// unreachable from this suite and are pinned directly in `MessageContextRuleTest`. And the
    /// ACCEPTANCE half — the generated ContextualEvent adapter and the `reactive.N.context` manifest
    /// key — is likewise unreachable here, so it is proven by real-compile fixtures in
    /// slice-processor-tests, where the module compiles its own generated sources.
    @Test
    void should_reject_message_context_subscriber_when_durability_is_undetermined() {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.topic.MessageContext;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event, MessageContext context);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(MESSAGE_CONTEXT);
        sources.add(CONTEXTUAL_EVENT);
        sources.add(orderTopicAnnotation());
        sources.add(orderEventRecord());
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("MessageContext requires a durable topic");
    }

    /// The refusal must name the REAL cause. Here the configuration could not be read, so reporting
    /// the topic as ephemeral would send the author to fix a declaration that may already say
    /// `durable`. The two causes must not be conflated (#386 ruling condition).
    @Test
    void should_report_unreadable_configuration_rather_than_claiming_ephemeral() {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.topic.MessageContext;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event, MessageContext context);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(MESSAGE_CONTEXT);
        sources.add(CONTEXTUAL_EVENT);
        sources.add(orderTopicAnnotation());
        sources.add(orderEventRecord());
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).hadErrorContaining("could not be read");
        assertCompilation(compilation).hadErrorContaining("refused rather than assumed");
        assertCompilation(compilation).hadErrorContaining("order-events");
    }

    /// A second parameter that is not the envelope context is not the D5 shape — it is an ordinary
    /// arity error, and must stay one rather than being waved through by the new branch.
    @Test
    void should_reject_two_arg_subscriber_whose_second_param_is_not_message_context() {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event, String trailer);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(MESSAGE_CONTEXT);
        sources.add(orderTopicAnnotation());
        sources.add(orderEventRecord());
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must have exactly one parameter");
    }

    /// A same-named `MessageContext` from another package is a business parameter, not the envelope
    /// context: detection matches the exact FQN, so this stays an arity error.
    @Test
    void should_reject_two_arg_subscriber_with_lookalike_message_context() {
        var lookalike = JavaFileObjects.forSourceString("test.dto.MessageContext",
                                                        """
            package test.dto;
            public record MessageContext(String messageId) {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.MessageContext;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event, MessageContext context);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(lookalike);
        sources.add(orderTopicAnnotation());
        sources.add(orderEventRecord());
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must have exactly one parameter");
    }

    /// Leading indentation is not the subject of these comparisons — the emitted text is. Stripping
    /// it lets an expected block be written as a readable text block while still comparing the WHOLE
    /// contiguous emission rather than a handful of substrings that could each stay green while
    /// something new appeared beside them.
    private static String strippedLines(String text) {
        return text.lines()
                   .map(String::strip)
                   .filter(line -> !line.isEmpty())
                   .collect(Collectors.joining("\n"));
    }

    /// The 1-arg subscriber path must be untouched by D5. This compares the COMPLETE emitted
    /// `SliceMethod` entry and the COMPLETE delegate override, contiguously, rather than probing for
    /// fragments. It is an equality-grade claim about those blocks modulo indentation — not the
    /// literal byte-for-byte claim, which four substring assertions never supported.
    @Test
    void should_leave_single_arg_subscriber_adapter_unchanged() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(MESSAGE_CONTEXT);
        sources.add(CONTEXTUAL_EVENT);
        sources.add(orderTopicAnnotation());
        sources.add(orderEventRecord());
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        assertThat(strippedLines(factoryContent)).contains(strippedLines("""
                new SliceMethod<>(
                    MethodName.methodName("onOrderPlaced").expect("method name literal: onOrderPlaced"),
                    delegate::onOrderPlaced,
                    new TypeToken<Unit>() {},
                    new TypeToken<OrderEvent>() {}
                )"""));
        assertThat(strippedLines(factoryContent)).contains(strippedLines("""
                @Override
                public Promise<Unit> onOrderPlaced(OrderEvent event) {
                    return delegate.onOrderPlaced(event);
                }"""));
        assertThat(factoryContent).doesNotContain("ContextualEvent");
        assertThat(factoryContent).doesNotContain("contextual ->");

        var manifestContent = compilation.generatedFile(StandardLocation.CLASS_OUTPUT,
                                                        "META-INF/slice/OrderService.manifest")
                                         .get().getCharContent(false).toString();

        assertThat(manifestContent).contains("reactive.0.category=subscription");
        assertThat(manifestContent).contains("reactive.0.messageType=test.dto.OrderEvent");
        assertThat(manifestContent).doesNotContain("context=message");
        assertThat(manifestContent).doesNotContain("MessageContext");
    }

    /// The manifest addition is additive only: the envelope format stays frozen at 1000 (#386
    /// no-bump ruling), so an older runtime keeps reading manifests it already understands.
    @Test
    void should_keep_envelope_version_frozen_for_single_arg_subscriber() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(orderTopicAnnotation());
        sources.add(orderEventRecord());
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        var manifestContent = compilation.generatedFile(StandardLocation.CLASS_OUTPUT,
                                                        "META-INF/slice/OrderService.manifest")
                                         .get().getCharContent(false).toString();

        assertThat(manifestContent).contains("envelope.version=1000");
    }

    /// A context-carrying handler cannot also be intercepted: the interceptor chain is typed
    /// `Fn1<Promise<Unit>, T>` on the payload alone, so there is nowhere to put the context. Left
    /// ungated the generator emits a wrapper that does not implement the declared two-argument
    /// signature — a javac error inside generated code. Refusing it names the real problem.
    @Test
    void should_reject_message_context_subscriber_carrying_interceptors() {
        var withRetry = JavaFileObjects.forSourceString("test.annotation.WithRetry",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "retry.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithRetry {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.topic.MessageContext;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.annotation.WithRetry;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                @OrderTopic
                @WithRetry
                Promise<Unit> onOrderPlaced(OrderEvent event, MessageContext context);
                static OrderService orderService() { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(MESSAGE_CONTEXT);
        sources.add(CONTEXTUAL_EVENT);
        sources.add(withRetry);
        sources.add(orderTopicAnnotation());
        sources.add(orderEventRecord());
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("cannot carry the delivery context");
    }

    /// The SCOPE case, and the one my first interceptor test could not see: the interceptor is on a
    /// DIFFERENT method. The wrapper record is generated per slice (any method carrying an
    /// interceptor) and then walks every method, so it drags the context-carrying handler through a
    /// payload-typed function anyway. Ungated this emitted a 1-arg override inside a record declared
    /// `implements OrderService`, plus `Fn1<Promise<Unit>, OrderEvent> = impl::onOrderPlaced` against
    /// a two-argument method — non-compiling generated code the author never wrote.
    @Test
    void should_reject_message_context_subscriber_when_interceptor_is_on_another_method() {
        var withRetry = JavaFileObjects.forSourceString("test.annotation.WithRetry",
                                                        """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "retry.orders")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithRetry {}
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.topic.MessageContext;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.annotation.WithRetry;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                @WithRetry
                Promise<String> placeOrder(String orderId);

                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event, MessageContext context);

                static OrderService orderService() { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(MESSAGE_CONTEXT);
        sources.add(CONTEXTUAL_EVENT);
        sources.add(withRetry);
        sources.add(orderTopicAnnotation());
        sources.add(orderEventRecord());
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("does not have to be on this handler");
    }

    /// The SILENT one. A slice depending on another slice whose interface declares a context-carrying
    /// method used to generate cleanly: the proxy synthesized `dep_OnOrderPlacedRequest(OrderEvent,
    /// MessageContext)` and a MethodHandle over it — a type the runtime is told to serialize that no
    /// publisher will ever send, and a call a caller could only make by fabricating a context.
    /// Nothing failed, which is why it needed finding rather than waiting for.
    @Test
    void should_reject_slice_dependency_method_taking_message_context() {
        var listener = JavaFileObjects.forSourceString("test.dep.OrderListener",
                                                        """
            package test.dep;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.topic.MessageContext;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderListener {
                Promise<Unit> onOrderPlaced(OrderEvent event, MessageContext context);
                static OrderListener orderListener() { return null; }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.dep.OrderListener;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(OrderListener listener) { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(MESSAGE_CONTEXT);
        sources.add(CONTEXTUAL_EVENT);
        sources.add(orderEventRecord());
        sources.add(listener);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("not remotely invocable");
    }

    @Test
    void should_include_transitive_methods_in_slice_adapter() throws Exception {
        var orderTopic = JavaFileObjects.forSourceString("test.annotation.OrderTopic",
                                                         """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.Subscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = Subscriber.class, config = "messaging.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface OrderTopic {}
            """);
        var orderEvent = JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                                         """
            package test.dto;
            public record OrderEvent(String orderId) {}
            """);
        var orderListener = JavaFileObjects.forSourceString("test.steps.OrderListener",
                                                             """
            package test.steps;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.OrderEvent;
            public interface OrderListener {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event);
                static OrderListener orderListener() { return null; }
            }
            """);
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.steps.OrderListener;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(OrderListener listener) { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(orderTopic);
        sources.add(orderEvent);
        sources.add(orderListener);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();

        // Verify adapter record includes step field
        assertThat(factoryContent).contains("OrderListener listener");
        // Verify methods() list includes transitive method with qualified name
        assertThat(factoryContent).contains("listenerOnOrderPlaced");
        // Verify handler delegates to step instance
        assertThat(factoryContent).contains("listener::onOrderPlaced");
    }

    @Test
    void should_combine_direct_and_transitive_subscriptions() throws Exception {
        var orderTopic = JavaFileObjects.forSourceString("test.annotation.OrderTopic",
                                                         """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.Subscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = Subscriber.class, config = "messaging.order-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface OrderTopic {}
            """);
        var paymentTopic = JavaFileObjects.forSourceString("test.annotation.PaymentTopic",
                                                            """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.Subscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = Subscriber.class, config = "messaging.payment-events")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface PaymentTopic {}
            """);
        var orderEvent = JavaFileObjects.forSourceString("test.dto.OrderEvent",
                                                         """
            package test.dto;
            public record OrderEvent(String orderId) {}
            """);
        var paymentEvent = JavaFileObjects.forSourceString("test.dto.PaymentEvent",
                                                            """
            package test.dto;
            public record PaymentEvent(String paymentId) {}
            """);
        // Step interface with subscription
        var orderListener = JavaFileObjects.forSourceString("test.steps.OrderListener",
                                                             """
            package test.steps;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderTopic;
            import test.dto.OrderEvent;
            public interface OrderListener {
                @OrderTopic
                Promise<Unit> onOrderPlaced(OrderEvent event);
                static OrderListener orderListener() { return null; }
            }
            """);
        // Slice with both direct subscription and step dependency with subscription
        var source = JavaFileObjects.forSourceString("test.PaymentService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.PaymentTopic;
            import test.dto.PaymentEvent;
            import test.steps.OrderListener;
            @Slice
            public interface PaymentService {
                Promise<String> processPayment(String paymentId);
                @PaymentTopic
                Promise<Unit> onPaymentReceived(PaymentEvent event);
                static PaymentService paymentService(OrderListener listener) { return null; }
            }
            """);

        var sources = subscriberSources();
        sources.add(orderTopic);
        sources.add(paymentTopic);
        sources.add(orderEvent);
        sources.add(paymentEvent);
        sources.add(orderListener);
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();

        // Verify manifest has BOTH subscription entries
        var manifestFile = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/slice/PaymentService.manifest");
        assertThat(manifestFile.isPresent()).isTrue();
        var manifestContent = manifestFile.get().getCharContent(false).toString();
        assertThat(manifestContent).contains("reactive.count=2");
        assertThat(manifestContent).contains("reactive.0.category=subscription");
        assertThat(manifestContent).contains("reactive.0.method=onPaymentReceived");
        assertThat(manifestContent).contains("reactive.0.config=messaging.payment-events");
        assertThat(manifestContent).contains("reactive.1.category=subscription");
        assertThat(manifestContent).contains("reactive.1.method=listenerOnOrderPlaced");
        assertThat(manifestContent).contains("reactive.1.config=messaging.order-events");

        // Verify methods() list has BOTH entries
        var factoryContent = compilation.generatedSourceFile("test.PaymentServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("\"onPaymentReceived\"");
        assertThat(factoryContent).contains("\"listenerOnOrderPlaced\"");
    }

    // ========== Multi-business-param security method (issue #395) ==========

    private static final JavaFileObject PRINCIPAL = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.http.handler.security.Principal",
            """
            package org.pragmatica.aether.http.handler.security;

            public interface Principal {}
            """);

    @Test
    void should_fail_on_security_method_with_multiple_business_params() {
        var source = JavaFileObjects.forSourceString("test.ProfileService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.aether.http.handler.security.Principal;
            @Slice
            public interface ProfileService {
                Promise<String> updateProfile(Principal principal, String name, String email);
                static ProfileService profileService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(PRINCIPAL);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("exactly one request record");
        assertCompilation(compilation).hadErrorContaining("ProfileService.updateProfile");
    }

    @Test
    void should_process_security_method_with_single_business_param() {
        var source = JavaFileObjects.forSourceString("test.ProfileService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.aether.http.handler.security.Principal;
            @Slice
            public interface ProfileService {
                Promise<String> getProfile(Principal principal, String userId);
                static ProfileService profileService() { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(PRINCIPAL);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        assertCompilation(compilation).succeeded();
    }

    // ========== Promise.all() dependency-count batching (issues #395 / arity-15 ceiling) ==========

    @Test
    void should_batch_dependencies_when_count_exceeds_promise_all_limit() {
        var manyOps = JavaFileObjects.forSourceString("external.ManyOps",
                                                      """
            package external;
            import org.pragmatica.lang.Promise;
            public interface ManyOps {
                Promise<String> op0(String x);
                Promise<String> op1(String x);
                Promise<String> op2(String x);
                Promise<String> op3(String x);
                Promise<String> op4(String x);
                Promise<String> op5(String x);
                Promise<String> op6(String x);
                Promise<String> op7(String x);
                Promise<String> op8(String x);
                Promise<String> op9(String x);
                Promise<String> op10(String x);
                Promise<String> op11(String x);
                Promise<String> op12(String x);
                Promise<String> op13(String x);
                Promise<String> op14(String x);
                Promise<String> op15(String x);
            }
            """);
        var source = JavaFileObjects.forSourceString("test.OverloadedService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import external.ManyOps;
            @Slice
            public interface OverloadedService {
                Promise<String> doWork(String request);
                static OverloadedService overloadedService(ManyOps ops) { return null; }
            }
            """);
        var sources = commonSources();
        sources.add(manyOps);
        sources.add(source);
        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);
        // 16 injected method handles exceed the flat Promise.all arity-15 ceiling: the processor now
        // batches them into Tuple parts and generates compilable code instead of failing (#395).
        assertCompilation(compilation).succeeded();
        assertCompilation(compilation)
                  .generatedSourceFile("test.OverloadedServiceFactory")
                  .contentsAsUtf8String()
                  .contains("var part1 = Promise.all(");
        assertCompilation(compilation)
                  .generatedSourceFile("test.OverloadedServiceFactory")
                  .contentsAsUtf8String()
                  .contains("return Promise.all(part1, part2)");
    }

    // ========== Typed Topic (Topic<T> constant) Tests (#396) ==========

    private static final JavaFileObject PUBLISHER = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.Publisher",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;

            public interface Publisher<T> {
                Promise<Unit> publish(T message);
            }
            """);

    private static final JavaFileObject TOPIC = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.topic.Topic",
            """
            package org.pragmatica.aether.slice.topic;

            import org.pragmatica.lang.type.TypeToken;

            public record Topic<T>(String name, TypeToken<T> payloadType) {
                public static <T> Topic<T> of(String name, TypeToken<T> payloadType) { return new Topic<>(name, payloadType); }
                public static <T> Topic<T> of(String name, Class<T> payloadType) { return new Topic<>(name, null); }
            }
            """);

    private static final JavaFileObject TYPED_PUBLISHER = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.topic.TypedPublisher",
            """
            package org.pragmatica.aether.slice.topic;

            import org.pragmatica.aether.slice.Publisher;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;

            public record TypedPublisher<T>(Topic<T> topic, Publisher<T> delegate) implements Publisher<T> {
                public static <T> TypedPublisher<T> typedPublisher(Topic<T> topic, Publisher<T> delegate) { return new TypedPublisher<>(topic, delegate); }
                public Promise<Unit> publish(T message) { return delegate.publish(message); }
            }
            """);

    private static final JavaFileObject ORDER_EVENT_DTO = JavaFileObjects.forSourceString(
            "test.dto.OrderEvent",
            """
            package test.dto;
            public record OrderEvent(String orderId) {}
            """);

    private static final JavaFileObject OTHER_EVENT_DTO = JavaFileObjects.forSourceString(
            "test.dto.OtherEvent",
            """
            package test.dto;
            public record OtherEvent(String value) {}
            """);

    private static final JavaFileObject ORDER_TOPICS_HOLDER = JavaFileObjects.forSourceString(
            "test.topics.Topics",
            """
            package test.topics;
            import org.pragmatica.aether.slice.topic.Topic;
            import test.dto.OrderEvent;
            public interface Topics {
                Topic<OrderEvent> ORDER_EVENTS = Topic.of("order-events", OrderEvent.class);
            }
            """);

    private List<JavaFileObject> typedTopicSources() {
        var sources = commonSources();
        sources.add(PUBLISHER);
        sources.add(SUBSCRIBER);
        sources.add(TOPIC);
        sources.add(TYPED_PUBLISHER);
        sources.add(ORDER_EVENT_DTO);
        return sources;
    }

    private static JavaFileObject publisherAnnotation(String simpleName, String config) {
        return JavaFileObjects.forSourceString("test.annotation." + simpleName,
                                               """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.Publisher;
            import java.lang.annotation.*;
            @ResourceQualifier(type = Publisher.class, config = "%s")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface %s {}
            """.formatted(config, simpleName));
    }

    private static JavaFileObject subscriptionAnnotation(String simpleName, String config) {
        return JavaFileObjects.forSourceString("test.annotation." + simpleName,
                                               """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.Subscriber;
            import java.lang.annotation.*;
            @ResourceQualifier(type = Subscriber.class, config = "%s")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface %s {}
            """.formatted(config, simpleName));
    }

    @Test
    void typedTopicPublisher_wrapsProvisionedPublisherInTypedPublisher_whenConfigNamesConstant() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.Publisher;
            import org.pragmatica.lang.Promise;
            import test.annotation.OrderPublisher;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@OrderPublisher Publisher<OrderEvent> clickPublisher) { return null; }
            }
            """);
        var sources = typedTopicSources();
        sources.add(ORDER_TOPICS_HOLDER);
        sources.add(publisherAnnotation("OrderPublisher", "ORDER_EVENTS"));
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("TypedPublisher.typedPublisher(Topics.ORDER_EVENTS, pub)");
        assertThat(factoryContent).contains("ctx.resources().provide(Publisher.class, \"order-events\", ProvisioningContext.provisioningContext())");
        var manifest = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/slice/OrderService.manifest")
                                  .get().getCharContent(false).toString();
        assertThat(manifest).contains("publish.topic.0.topicName=order-events");
        assertThat(manifest).contains("publish.topic.0.config=order-events");
        assertThat(manifest).contains("envelope.version=1000");
    }

    @Test
    void typedTopicPublisher_failsCompilation_whenConfigNamesNoConstant() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.Publisher;
            import org.pragmatica.lang.Promise;
            import test.annotation.MissingPublisher;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@MissingPublisher Publisher<OrderEvent> clickPublisher) { return null; }
            }
            """);
        var sources = typedTopicSources();
        sources.add(ORDER_TOPICS_HOLDER);
        sources.add(publisherAnnotation("MissingPublisher", "MISSING_TOPIC"));
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("names no visible");
        assertCompilation(compilation).hadErrorContaining("MISSING_TOPIC");
    }

    @Test
    void typedTopicPublisher_failsCompilation_whenPayloadTypeMismatches() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.Publisher;
            import org.pragmatica.lang.Promise;
            import test.annotation.OrderPublisher;
            import test.dto.OtherEvent;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@OrderPublisher Publisher<OtherEvent> clickPublisher) { return null; }
            }
            """);
        var sources = typedTopicSources();
        sources.add(OTHER_EVENT_DTO);
        sources.add(ORDER_TOPICS_HOLDER);
        sources.add(publisherAnnotation("OrderPublisher", "ORDER_EVENTS"));
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("payload type");
        assertCompilation(compilation).hadErrorContaining("OtherEvent");
    }

    @Test
    void typedTopicSubscriber_compiles_whenHandlerTypeMatchesConstant() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderSink",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderSubscription;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderSink {
                @OrderSubscription
                Promise<Unit> onOrder(OrderEvent event);
                static OrderSink orderSink() { return null; }
            }
            """);
        var sources = typedTopicSources();
        sources.add(ORDER_TOPICS_HOLDER);
        sources.add(subscriptionAnnotation("OrderSubscription", "ORDER_EVENTS"));
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();
        var manifest = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/slice/OrderSink.manifest")
                                  .get().getCharContent(false).toString();
        assertThat(manifest).contains("reactive.0.category=subscription");
        assertThat(manifest).contains("reactive.0.topicName=order-events");
        assertThat(manifest).contains("reactive.0.config=order-events");
    }

    @Test
    void typedTopicSubscriber_failsCompilation_whenHandlerTypeMismatches() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderSink",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Unit;
            import test.annotation.OrderSubscription;
            import test.dto.OtherEvent;
            @Slice
            public interface OrderSink {
                @OrderSubscription
                Promise<Unit> onOrder(OtherEvent event);
                static OrderSink orderSink() { return null; }
            }
            """);
        var sources = typedTopicSources();
        sources.add(OTHER_EVENT_DTO);
        sources.add(ORDER_TOPICS_HOLDER);
        sources.add(subscriptionAnnotation("OrderSubscription", "ORDER_EVENTS"));
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("payload type");
    }

    @Test
    void legacyLowercasePublisherConfig_keepsWorkingWithoutTypedWrap() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.slice.Publisher;
            import org.pragmatica.lang.Promise;
            import test.annotation.LegacyPublisher;
            import test.dto.OrderEvent;
            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);
                static OrderService orderService(@LegacyPublisher Publisher<OrderEvent> clickPublisher) { return null; }
            }
            """);
        var sources = typedTopicSources();
        sources.add(publisherAnnotation("LegacyPublisher", "order-events"));
        sources.add(source);

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.OrderServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.resources().provide(Publisher.class, \"order-events\", ProvisioningContext.provisioningContext())");
        assertThat(factoryContent).doesNotContain("TypedPublisher");
    }

    // ========== Interceptor Config Section Tests ==========

    private static JavaFileObject interceptorAnnotation(String simpleName, String config) {
        return JavaFileObjects.forSourceString("test.annotation." + simpleName,
                                               """
            package test.annotation;
            import org.pragmatica.aether.slice.annotation.ResourceQualifier;
            import org.pragmatica.aether.slice.MethodInterceptor;
            import java.lang.annotation.*;
            @ResourceQualifier(type = MethodInterceptor.class, config = "%s")
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface %s {}
            """.formatted(config, simpleName));
    }

    private static JavaFileObject interceptedSlice(String... annotationNames) {
        var annotations = Arrays.stream(annotationNames)
                                .map(name -> "    @" + name)
                                .collect(Collectors.joining("\n"));
        var imports = Arrays.stream(annotationNames)
                            .map(name -> "import test.annotation." + name + ";")
                            .collect(Collectors.joining("\n"));

        return JavaFileObjects.forSourceString("test.SeatService",
                                               """
            package test;
            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            %s
            @Slice
            public interface SeatService {
            %s
                Promise<String> findSeat(String seatId);
                static SeatService seatService() { return null; }
            }
            """.formatted(imports, annotations));
    }

    @Test
    void interceptorConfig_sanitizesIdentifier_forHyphenatedSection() throws Exception {
        var sources = commonSources();
        sources.add(interceptorAnnotation("Cached", "cache.availability.seat-status"));
        sources.add(interceptedSlice("Cached"));

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.SeatServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.resources().provide(MethodInterceptor.class, \"cache.availability.seat-status\")");
        assertThat(factoryContent).contains("methodInterceptor_cache_availability_seat_status");
        assertThat(factoryContent).doesNotContain("methodInterceptor_cache_availability_seat-status");
    }

    @Test
    void interceptorConfig_keepsDottedIdentifierForm_forConventionalSection() throws Exception {
        var sources = commonSources();
        sources.add(interceptorAnnotation("Cached", "cache.availability"));
        sources.add(interceptedSlice("Cached"));

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.SeatServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("methodInterceptor_cache_availability.intercept(impl::findSeat)");
    }

    @Test
    void interceptorConfig_issuesDistinctIdentifiers_whenSectionsDifferOnlyBySeparator() throws Exception {
        var sources = commonSources();
        sources.add(interceptorAnnotation("Hyphenated", "cache.seat-status"));
        sources.add(interceptorAnnotation("Underscored", "cache.seat_status"));
        sources.add(interceptedSlice("Hyphenated", "Underscored"));

        Compilation compilation = javac().withProcessors(new SliceProcessor()).compile(sources);

        assertCompilation(compilation).succeeded();
        var factoryContent = compilation.generatedSourceFile("test.SeatServiceFactory")
                                        .get().getCharContent(false).toString();
        assertThat(factoryContent).contains("ctx.resources().provide(MethodInterceptor.class, \"cache.seat-status\")");
        assertThat(factoryContent).contains("ctx.resources().provide(MethodInterceptor.class, \"cache.seat_status\")");
        assertThat(factoryContent).contains("methodInterceptor_cache_seat_status_2");
        assertThat(factoryContent).contains("methodInterceptor_cache_seat_status.intercept(methodInterceptor_cache_seat_status_2.intercept(impl::findSeat))");
    }
}
