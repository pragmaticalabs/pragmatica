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

    private static final JavaFileObject INVOKER_FACADE = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.SliceInvokerFacade",
            """
            package org.pragmatica.aether.slice;

            import org.pragmatica.lang.Promise;

            public interface SliceInvokerFacade {
                <T> Promise<T> invoke(String artifact, String method, Object request, Class<T> responseType);
            }
            """);

    private List<JavaFileObject> commonSources() {
        return new ArrayList<>(List.of(
                SLICE_ANNOTATION, PROMISE, UNIT, TYPE_TOKEN,
                ASPECT, SLICE, SLICE_METHOD, METHOD_NAME, INVOKER_FACADE
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
        assertCompilation(compilation).generatedSourceFile("test.api.TestService");
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
        assertCompilation(compilation).generatedSourceFile("test.api.OrderService");
        assertCompilation(compilation).generatedSourceFile("test.OrderServiceFactory");

        // Verify proxy record is generated inside factory
        assertCompilation(compilation)
                  .generatedSourceFile("test.OrderServiceFactory")
                  .contentsAsUtf8String()
                  .contains("record inventoryService(SliceInvokerFacade invoker) implements InventoryService");
    }

    @Test
    void should_call_internal_dependency_factory_directly() {
        // Internal dependency in same base package
        var validator = JavaFileObjects.forSourceString("test.validation.OrderValidator",
                                                        """
            package test.validation;

            public interface OrderValidator {
                boolean validate(String orderId);

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

        // Internal deps should call factory directly, not create proxy
        assertCompilation(compilation)
                  .generatedSourceFile("test.OrderServiceFactory")
                  .contentsAsUtf8String()
                  .contains("OrderValidator.orderValidator()");
    }

    @Test
    void should_handle_mixed_internal_and_external_dependencies() throws Exception {
        // External dependency (slice methods have exactly one parameter)
        var paymentService = JavaFileObjects.forSourceString("payments.PaymentService",
                                                             """
            package payments;

            import org.pragmatica.lang.Promise;

            public interface PaymentService {
                Promise<Boolean> processPayment(String paymentRequest);
            }
            """);

        // Internal dependency
        var validator = JavaFileObjects.forSourceString("test.validation.OrderValidator",
                                                        """
            package test.validation;

            public interface OrderValidator {
                boolean validate(String orderId);

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
                                        .orElseThrow()
                                        .getCharContent(false)
                                        .toString();

        // Internal dependency calls factory
        assertThat(factoryContent).contains("OrderValidator.orderValidator()");

        // External dependency gets proxy record
        assertThat(factoryContent).contains("record paymentService(SliceInvokerFacade invoker) implements PaymentService");
    }

    @Test
    void should_handle_deeply_nested_slice_dependencies() throws Exception {
        // Level 3: External inventory service
        var inventoryService = JavaFileObjects.forSourceString("inventory.InventoryService",
                                                               """
            package inventory;

            import org.pragmatica.lang.Promise;

            public interface InventoryService {
                Promise<Integer> getStock(String sku);
            }
            """);

        // Level 2: Internal validation (depends on nothing)
        var validator = JavaFileObjects.forSourceString("test.core.OrderValidator",
                                                        """
            package test.core;

            public interface OrderValidator {
                boolean isValid(String orderId);

                static OrderValidator orderValidator() {
                    return orderId -> true;
                }
            }
            """);

        // Level 2: Internal pricing (depends on nothing)
        var pricingEngine = JavaFileObjects.forSourceString("test.core.PricingEngine",
                                                            """
            package test.core;

            import org.pragmatica.lang.Promise;

            public interface PricingEngine {
                Promise<Integer> calculatePrice(String productId, int quantity);

                static PricingEngine pricingEngine() {
                    return null;
                }
            }
            """);

        // Level 1: Order processing slice (depends on validator, pricing, inventory)
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
                                        .orElseThrow()
                                        .getCharContent(false)
                                        .toString();

        // Internal dependencies call their factories
        assertThat(factoryContent).contains("OrderValidator.orderValidator()");
        assertThat(factoryContent).contains("PricingEngine.pricingEngine()");

        // External dependency gets proxy
        assertThat(factoryContent).contains("record inventoryService(SliceInvokerFacade invoker) implements InventoryService");

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
                                        .orElseThrow()
                                        .getCharContent(false)
                                        .toString();

        // createSlice method exists
        assertThat(factoryContent).contains("public static Promise<Slice> createSlice");

        // All methods are registered
        assertThat(factoryContent).contains("delegate::getUser");
        assertThat(factoryContent).contains("delegate::updateUser");
        assertThat(factoryContent).contains("delegate::deleteUser");

        // Local adapter record
        assertThat(factoryContent).contains("record userServiceSlice(UserService delegate) implements Slice");
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
                                        .orElseThrow()
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

        var manifestContent = manifestFile.orElseThrow()
                                          .getCharContent(false)
                                          .toString();

        // Verify manifest contains required properties
        assertThat(manifestContent).contains("slice.name=OrderService");
        assertThat(manifestContent).contains("slice.artifactSuffix=order-service");
        assertThat(manifestContent).contains("api.classes=test.api.OrderService");
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

        var manifestContent = manifestFile.orElseThrow()
                                          .getCharContent(false)
                                          .toString();

        // Verify dependency properties for blueprint generation
        assertThat(manifestContent).contains("dependencies.count=1");
        assertThat(manifestContent).contains("dependency.0.interface=inventory.InventoryService");
        assertThat(manifestContent).contains("dependency.0.external=true");
    }
}
