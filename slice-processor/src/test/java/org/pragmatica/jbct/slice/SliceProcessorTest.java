package org.pragmatica.jbct.slice;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;

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

    private static final JavaFileObject PROMISE = JavaFileObjects.forSourceString(
            "org.pragmatica.lang.Promise",
            """
            package org.pragmatica.lang;

            public interface Promise<T> {
                <R> Promise<R> map(java.util.function.Function<T, R> fn);
                <R> Promise<R> flatMap(java.util.function.Function<T, Promise<R>> fn);
                static <T> Promise<T> success(T value) { return null; }
            }
            """);

    private static final JavaFileObject UNIT = JavaFileObjects.forSourceString(
            "org.pragmatica.lang.Unit",
            """
            package org.pragmatica.lang;

            public interface Unit {
                static Unit unit() { return null; }
            }
            """);

    private static final JavaFileObject TYPE_TOKEN = JavaFileObjects.forSourceString(
            "org.pragmatica.lang.type.TypeToken",
            """
            package org.pragmatica.lang.type;

            public abstract class TypeToken<T> {}
            """);

    private static final JavaFileObject ASPECT = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.Aspect",
            """
            package org.pragmatica.aether.slice;

            public interface Aspect<T> {
                T apply(T instance);
            }
            """);

    private static final JavaFileObject SLICE = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.Slice",
            """
            package org.pragmatica.aether.slice;

            import java.util.List;

            public interface Slice {
                List<SliceMethod<?, ?>> methods();
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
                public record Wrapper(MethodName name) { public MethodName unwrap() { return name; } }
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

    // Aspect-related stubs
    private static final JavaFileObject ASPECT_KIND = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.infra.aspect.AspectKind",
            """
            package org.pragmatica.aether.infra.aspect;

            public enum AspectKind { CACHE, LOG, METRICS, RETRY, TIMEOUT }
            """);

    private static final JavaFileObject ASPECT_ANNOTATION = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.infra.aspect.Aspect",
            """
            package org.pragmatica.aether.infra.aspect;

            import java.lang.annotation.*;

            @Target({ElementType.TYPE, ElementType.METHOD})
            @Retention(RetentionPolicy.SOURCE)
            public @interface Aspect {
                AspectKind[] value();
            }
            """);

    private static final JavaFileObject KEY_ANNOTATION = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.infra.aspect.Key",
            """
            package org.pragmatica.aether.infra.aspect;

            import java.lang.annotation.*;

            @Target(ElementType.RECORD_COMPONENT)
            @Retention(RetentionPolicy.SOURCE)
            public @interface Key {}
            """);

    private static final JavaFileObject FN1 = JavaFileObjects.forSourceString(
            "org.pragmatica.lang.Functions",
            """
            package org.pragmatica.lang;

            public final class Functions {
                public interface Fn1<R, T> {
                    R apply(T input);
                }
            }
            """);

    private static final JavaFileObject RESULT = JavaFileObjects.forSourceString(
            "org.pragmatica.lang.Result",
            """
            package org.pragmatica.lang;

            public interface Result<T> {
                Promise<T> async();
                static <T> Result<T> success(T value) { return null; }
            }
            """);

    private static final JavaFileObject OPTION = JavaFileObjects.forSourceString(
            "org.pragmatica.lang.Option",
            """
            package org.pragmatica.lang;

            public interface Option<T> {
                static <T> Option<T> option(T value) { return null; }
                Result<T> toResult(Object cause);
            }
            """);

    private static final JavaFileObject SLICE_RUNTIME = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.SliceRuntime",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Result;

            public final class SliceRuntime {
                public static Result<AspectFactory> getAspectFactory() { return null; }
            }
            """);

    private static final JavaFileObject ASPECT_FACTORY = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.AspectFactory",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Result;

            public interface AspectFactory {
                <C, R> Result<R> create(Class<R> type, C config);
            }
            """);

    private static final JavaFileObject CACHE = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.infra.aspect.Cache",
            """
            package org.pragmatica.aether.infra.aspect;

            import org.pragmatica.lang.Promise;
            import org.pragmatica.lang.Option;
            import org.pragmatica.lang.Unit;

            public interface Cache<K, V> {
                Promise<Option<V>> get(K key);
                Promise<Unit> put(K key, V value);
            }
            """);

    private static final JavaFileObject CACHE_CONFIG = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.infra.aspect.CacheConfig",
            """
            package org.pragmatica.aether.infra.aspect;

            import org.pragmatica.lang.Result;
            import org.pragmatica.lang.type.TypeToken;

            public record CacheConfig<K, V>(String name, TypeToken<K> keyType, TypeToken<V> valueType) {
                public static <K, V> Result<CacheConfig<K, V>> cacheConfig(String name,
                                                                            TypeToken<K> keyType,
                                                                            TypeToken<V> valueType) {
                    return null;
                }
            }
            """);

    private static final JavaFileObject ASPECTS = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.infra.aspect.Aspects",
            """
            package org.pragmatica.aether.infra.aspect;

            import org.pragmatica.lang.Functions.Fn1;
            import org.pragmatica.lang.Promise;

            public final class Aspects {
                public static <T, K, R> Fn1<Promise<R>, T> withCaching(Fn1<Promise<R>, T> fn,
                                                                        Fn1<K, T> keyExtractor,
                                                                        Cache<K, R> cache) {
                    return null;
                }
            }
            """);

    private List<JavaFileObject> commonSources() {
        return new ArrayList<>(List.of(
                SLICE_ANNOTATION, PROMISE, UNIT, TYPE_TOKEN, RESULT,
                ASPECT, SLICE, SLICE_METHOD, METHOD_NAME, METHOD_HANDLE, INVOKER_FACADE
        ));
    }

    private List<JavaFileObject> aspectSources() {
        var sources = commonSources();
        sources.addAll(List.of(
                ASPECT_KIND, ASPECT_ANNOTATION, KEY_ANNOTATION,
                FN1, OPTION,
                SLICE_RUNTIME, ASPECT_FACTORY, CACHE, CACHE_CONFIG, ASPECTS
        ));
        return sources;
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

    @Test
    void should_generate_proxy_for_external_dependency() {
        // External dependency in different package
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

        // Verify proxy record is generated inside factory with MethodHandle
        assertCompilation(compilation)
                  .generatedSourceFile("test.OrderServiceFactory")
                  .contentsAsUtf8String()
                  .contains("record inventoryService(MethodHandle<");
    }

    @Test
    void should_generate_proxy_for_internal_dependency() {
        // Internal dependency in same base package - now also gets proxy
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

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(OrderValidator validator) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(validator);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        // All deps now create proxies through invoker
        assertCompilation(compilation)
                  .generatedSourceFile("test.OrderServiceFactory")
                  .contentsAsUtf8String()
                  .contains("record orderValidator(MethodHandle<");
    }

    @Test
    void should_handle_multiple_dependencies() throws Exception {
        // External dependency (slice methods have exactly one parameter)
        var paymentService = JavaFileObjects.forSourceString("payments.PaymentService",
                                                             """
            package payments;

            import org.pragmatica.lang.Promise;

            public interface PaymentService {
                Promise<Boolean> processPayment(String paymentRequest);
            }
            """);

        // Internal dependency - now also uses Promise return type for proxy
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

        // Both dependencies get proxy records with MethodHandle
        assertThat(factoryContent).contains("record orderValidator(MethodHandle<");
        assertThat(factoryContent).contains("record paymentService(MethodHandle<");
    }

    @Test
    void should_handle_multiple_dependencies_from_different_packages() throws Exception {
        // External inventory service
        var inventoryService = JavaFileObjects.forSourceString("inventory.InventoryService",
                                                               """
            package inventory;

            import org.pragmatica.lang.Promise;

            public interface InventoryService {
                Promise<Integer> getStock(String sku);
            }
            """);

        // Internal validation - must use Promise for proxy generation
        var validator = JavaFileObjects.forSourceString("test.core.OrderValidator",
                                                        """
            package test.core;

            import org.pragmatica.lang.Promise;

            public interface OrderValidator {
                Promise<Boolean> isValid(String orderId);

                static OrderValidator orderValidator() {
                    return null;
                }
            }
            """);

        // Internal pricing
        var pricingEngine = JavaFileObjects.forSourceString("test.core.PricingEngine",
                                                            """
            package test.core;

            import org.pragmatica.lang.Promise;

            public interface PricingEngine {
                Promise<Integer> calculatePrice(String productId);

                static PricingEngine pricingEngine() {
                    return null;
                }
            }
            """);

        // Order processing slice (depends on validator, pricing, inventory)
        var orderProcessor = JavaFileObjects.forSourceString("test.OrderProcessor",
                                                             """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.core.OrderValidator;
            import test.core.PricingEngine;
            import inventory.InventoryService;

            @Slice
            public interface OrderProcessor {
                Promise<String> processOrder(String orderId);

                static OrderProcessor orderProcessor(OrderValidator validator,
                                                     PricingEngine pricing,
                                                     InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(inventoryService);
        sources.add(validator);
        sources.add(pricingEngine);
        sources.add(orderProcessor);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.OrderProcessorFactory")
                                        .get()
                                        .getCharContent(false)
                                        .toString();

        // All dependencies get proxies with MethodHandle
        assertThat(factoryContent).contains("record orderValidator(MethodHandle<");
        assertThat(factoryContent).contains("record pricingEngine(MethodHandle<");
        assertThat(factoryContent).contains("record inventoryService(MethodHandle<");

        // Factory instantiates slice with all dependencies
        assertThat(factoryContent).contains("OrderProcessor.orderProcessor(validator, pricing, inventory)");
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

        // userServiceSlice method exists (follows {sliceName}Slice naming)
        assertThat(factoryContent).contains("public static Promise<Slice> userServiceSlice");

        // All methods are registered
        assertThat(factoryContent).contains("delegate::getUser");
        assertThat(factoryContent).contains("delegate::updateUser");
        assertThat(factoryContent).contains("delegate::deleteUser");

        // Local adapter record
        assertThat(factoryContent).contains("record userServiceSlice(UserService delegate) implements Slice, UserService");
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

        // TypeTokens use correct types
        assertThat(factoryContent).contains("new TypeToken<test.dto.UserResponse>() {}");
        assertThat(factoryContent).contains("new TypeToken<test.dto.CreateUserRequest>() {}");
    }

    @Test
    void should_generate_slice_manifest_with_class_listings() throws Exception {
        var request = JavaFileObjects.forSourceString("test.dto.CreateOrderRequest",
                                                      """
            package test.dto;

            public record CreateOrderRequest(String orderId, int quantity) {}
            """);

        var response = JavaFileObjects.forSourceString("test.dto.OrderResult",
                                                       """
            package test.dto;

            public record OrderResult(String status) {}
            """);

        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;
            import test.dto.CreateOrderRequest;
            import test.dto.OrderResult;

            @Slice
            public interface OrderService {
                Promise<OrderResult> createOrder(CreateOrderRequest request);

                static OrderService orderService() {
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

        // Verify slice manifest was generated
        var manifestFile = compilation.generatedFile(
                javax.tools.StandardLocation.CLASS_OUTPUT,
                "",
                "META-INF/slice/OrderService.manifest");

        assertThat(manifestFile.isPresent()).isTrue();

        var manifestContent = manifestFile.get()
                                          .getCharContent(false)
                                          .toString();

        // Verify manifest contains required properties
        assertThat(manifestContent).contains("slice.name=OrderService");
        assertThat(manifestContent).contains("slice.artifactSuffix=order-service");
        assertThat(manifestContent).contains("test.OrderService");
        assertThat(manifestContent).contains("test.OrderServiceFactory");
        // Verify dependencies count (no dependencies in this test)
        assertThat(manifestContent).contains("dependencies.count=0");
    }

    @Test
    void should_generate_slice_manifest_with_dependencies() throws Exception {
        // External dependency
        var inventoryService = JavaFileObjects.forSourceString("inventory.InventoryService",
                                                               """
            package inventory;

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
            import inventory.InventoryService;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService(InventoryService inventory) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(inventoryService);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var manifestFile = compilation.generatedFile(
                javax.tools.StandardLocation.CLASS_OUTPUT,
                "",
                "META-INF/slice/OrderService.manifest");

        assertThat(manifestFile.isPresent()).isTrue();

        var manifestContent = manifestFile.get()
                                          .getCharContent(false)
                                          .toString();

        // Verify dependency properties for blueprint generation
        assertThat(manifestContent).contains("dependencies.count=1");
        assertThat(manifestContent).contains("dependency.0.interface=inventory.InventoryService");
    }

    // ========== Aspect Tests ==========

    @Test
    void should_generate_cache_wrapper_with_key_extractor() throws Exception {
        var userId = JavaFileObjects.forSourceString("test.dto.UserId",
                                                     """
            package test.dto;

            public record UserId(String value) {}
            """);

        var request = JavaFileObjects.forSourceString("test.dto.GetUserRequest",
                                                      """
            package test.dto;

            import org.pragmatica.aether.infra.aspect.Key;

            public record GetUserRequest(@Key UserId userId, boolean includeDetails) {}
            """);

        var response = JavaFileObjects.forSourceString("test.dto.User",
                                                       """
            package test.dto;

            public record User(String id, String name) {}
            """);

        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.infra.aspect.Aspect;
            import org.pragmatica.aether.infra.aspect.AspectKind;
            import org.pragmatica.lang.Promise;
            import test.dto.GetUserRequest;
            import test.dto.User;

            @Slice
            public interface UserService {
                @Aspect(AspectKind.CACHE)
                Promise<User> getUser(GetUserRequest request);

                static UserService userService() {
                    return null;
                }
            }
            """);

        var sources = aspectSources();
        sources.add(userId);
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

        // Wrapper record generated
        assertThat(factoryContent).contains("record UserServiceWrapper(");
        assertThat(factoryContent).contains("implements UserService");

        // Cache config with correct types
        assertThat(factoryContent).contains("CacheConfig.cacheConfig(\"userService.getUser\"");
        assertThat(factoryContent).contains("new TypeToken<test.dto.UserId>() {}");
        assertThat(factoryContent).contains("new TypeToken<test.dto.User>() {}");

        // Key extractor uses method reference with fully qualified type
        assertThat(factoryContent).contains("Aspects.withCaching(impl::getUser, test.dto.GetUserRequest::userId,");

        // SliceRuntime.getAspectFactory() chain
        assertThat(factoryContent).contains("SliceRuntime.getAspectFactory()");
    }

    @Test
    void should_use_identity_extractor_when_no_key_annotation() throws Exception {
        var request = JavaFileObjects.forSourceString("test.dto.GetUserRequest",
                                                      """
            package test.dto;

            public record GetUserRequest(String userId, boolean includeDetails) {}
            """);

        var response = JavaFileObjects.forSourceString("test.dto.User",
                                                       """
            package test.dto;

            public record User(String id, String name) {}
            """);

        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.infra.aspect.Aspect;
            import org.pragmatica.aether.infra.aspect.AspectKind;
            import org.pragmatica.lang.Promise;
            import test.dto.GetUserRequest;
            import test.dto.User;

            @Slice
            public interface UserService {
                @Aspect(AspectKind.CACHE)
                Promise<User> getUser(GetUserRequest request);

                static UserService userService() {
                    return null;
                }
            }
            """);

        var sources = aspectSources();
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

        // Identity extractor (request type as key)
        assertThat(factoryContent).contains("new TypeToken<test.dto.GetUserRequest>() {}");
        assertThat(factoryContent).contains("request -> request");
    }

    @Test
    void should_fail_on_multiple_key_annotations() {
        var request = JavaFileObjects.forSourceString("test.dto.GetUserRequest",
                                                      """
            package test.dto;

            import org.pragmatica.aether.infra.aspect.Key;

            public record GetUserRequest(@Key String userId, @Key String tenantId) {}
            """);

        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.infra.aspect.Aspect;
            import org.pragmatica.aether.infra.aspect.AspectKind;
            import org.pragmatica.lang.Promise;
            import test.dto.GetUserRequest;

            @Slice
            public interface UserService {
                @Aspect(AspectKind.CACHE)
                Promise<String> getUser(GetUserRequest request);

                static UserService userService() {
                    return null;
                }
            }
            """);

        var sources = aspectSources();
        sources.add(request);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("Multiple @Key annotations");
    }

    @Test
    void should_not_generate_wrapper_for_methods_without_aspects() throws Exception {
        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface UserService {
                Promise<String> getUser(String userId);

                static UserService userService() {
                    return null;
                }
            }
            """);

        var sources = aspectSources();
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.UserServiceFactory")
                                        .get()
                                        .getCharContent(false)
                                        .toString();

        // No wrapper record
        assertThat(factoryContent).doesNotContain("UserServiceWrapper");
        // No aspect imports
        assertThat(factoryContent).doesNotContain("SliceRuntime");
        assertThat(factoryContent).doesNotContain("Aspects");
    }

    @Test
    void should_generate_mixed_wrapped_and_unwrapped_methods() throws Exception {
        var request = JavaFileObjects.forSourceString("test.dto.GetUserRequest",
                                                      """
            package test.dto;

            import org.pragmatica.aether.infra.aspect.Key;

            public record GetUserRequest(@Key String userId) {}
            """);

        var source = JavaFileObjects.forSourceString("test.UserService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.aether.infra.aspect.Aspect;
            import org.pragmatica.aether.infra.aspect.AspectKind;
            import org.pragmatica.lang.Promise;
            import test.dto.GetUserRequest;

            @Slice
            public interface UserService {
                @Aspect(AspectKind.CACHE)
                Promise<String> getUser(GetUserRequest request);

                Promise<Boolean> updateUser(String request);

                static UserService userService() {
                    return null;
                }
            }
            """);

        var sources = aspectSources();
        sources.add(request);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        var factoryContent = compilation.generatedSourceFile("test.UserServiceFactory")
                                        .get()
                                        .getCharContent(false)
                                        .toString();

        // Wrapper record with both method Fn1 components
        assertThat(factoryContent).contains("record UserServiceWrapper(");
        assertThat(factoryContent).contains("getUserFn");
        assertThat(factoryContent).contains("updateUserFn");

        // getUser is cached
        assertThat(factoryContent).contains("getUserWrapped = Aspects.withCaching(impl::getUser");

        // updateUser is direct method reference (no caching)
        assertThat(factoryContent).contains("updateUserWrapped = impl::updateUser");
    }

    // ========== Additional Negative Test Cases ==========

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

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must return Promise<T>");
    }

    @Test
    void should_fail_on_method_with_no_parameters() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface TestService {
                Promise<String> getUser();

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

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must have exactly one parameter");
    }

    @Test
    void should_fail_on_method_with_multiple_parameters() {
        var source = JavaFileObjects.forSourceString("test.TestService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface TestService {
                Promise<String> getUser(String userId, String tenantId);

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

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("must have exactly one parameter");
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

                static TestService testService(NotAnInterface dep) {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(dependency);
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .compile(sources);

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

        assertCompilation(compilation).failed();
        assertCompilation(compilation).hadErrorContaining("with type argument");
    }

    @Test
    void should_generate_slice_manifest_with_correct_property_names() throws Exception {
        var source = JavaFileObjects.forSourceString("test.OrderService",
                                                     """
            package test;

            import org.pragmatica.aether.slice.annotation.Slice;
            import org.pragmatica.lang.Promise;

            @Slice
            public interface OrderService {
                Promise<String> placeOrder(String orderId);

                static OrderService orderService() {
                    return null;
                }
            }
            """);

        var sources = commonSources();
        sources.add(source);

        Compilation compilation = javac()
                                       .withProcessors(new SliceProcessor())
                                       .withOptions("-Aslice.groupId=org.example",
                                                    "-Aslice.artifactId=orders")
                                       .compile(sources);

        assertCompilation(compilation).succeeded();

        // Verify slice manifest was generated with RFC-0004 compliant properties
        var manifestFile = compilation.generatedFile(
                javax.tools.StandardLocation.CLASS_OUTPUT,
                "",
                "META-INF/slice/OrderService.manifest");

        assertThat(manifestFile.isPresent()).isTrue();

        var manifestContent = manifestFile.get()
                                          .getCharContent(false)
                                          .toString();

        // Verify RFC-0004 compliant properties
        assertThat(manifestContent).contains("slice.interface=test.OrderService");
        assertThat(manifestContent).contains("slice.artifactId=orders-order-service");
    }
}
