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

            public interface ResourceProviderFacade {
                <T> Promise<T> provide(Class<T> resourceType, String configSection);
                <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);
            }
            """);

    private static final JavaFileObject SLICE_CREATION_CONTEXT = JavaFileObjects.forSourceString(
            "org.pragmatica.aether.slice.SliceCreationContext",
            """
            package org.pragmatica.aether.slice;

            public interface SliceCreationContext {
                SliceInvokerFacade invoker();
                ResourceProviderFacade resources();
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

    private List<JavaFileObject> commonSources() {
        return new ArrayList<>(List.of(
                SLICE_ANNOTATION,
                ASPECT, SLICE, SLICE_METHOD, METHOD_NAME, METHOD_HANDLE, INVOKER_FACADE,
                METHOD_INTERCEPTOR, PROVISIONING_CONTEXT,
                RESOURCE_PROVIDER_FACADE, SLICE_CREATION_CONTEXT, RESOURCE_QUALIFIER,
                KEY_ANNOTATION
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

        // Resource is provisioned for the plain interface's factory param (fully qualified)
        assertThat(factoryContent).contains("ctx.resources().provide(test.infra.HttpClient.class, \"http.kyc\")");
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

        // Both resources provisioned (fully qualified)
        assertThat(factoryContent).contains("ctx.resources().provide(test.infra.HttpClient.class, \"http.kyc\")");
        assertThat(factoryContent).contains("ctx.resources().provide(test.infra.HttpClient.class, \"http.fraud\")");
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
        // Should use Result.map + async instead of Promise.success
        assertThat(factoryContent).contains(".map(aspect::apply).async()");
        assertThat(factoryContent).doesNotContain("Promise.success(");
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
        assertThat(factoryContent).contains(".toResult().map(aspect::apply).async()");
        assertThat(factoryContent).doesNotContain("Promise.success(");
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
        // Promise factory: .map(aspect::apply) without .async()
        assertThat(factoryContent).contains(".map(aspect::apply)");
        assertThat(factoryContent).doesNotContain("Promise.success(");
        assertThat(factoryContent).doesNotContain(".async()");
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
        assertThat(factoryContent).contains(".map(aspect::apply).async()");
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
}
