package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SliceFactory with new-style 2-parameter factories.
 * <p>
 * New-style factories take only (Aspect, SliceCreationContext) and resolve
 * dependencies dynamically via SliceCreationContext at runtime.
 */
class SliceFactoryTest {

    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact, String method, TypeToken<T> requestType, TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    private static final ResourceProviderFacade STUB_RESOURCES = new ResourceProviderFacade() {
        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return Causes.cause("Stub resource provider").promise();
        }
    };

    private static final SliceCreationContext STUB_CONTEXT =
        SliceCreationContext.sliceCreationContext(STUB_INVOKER, STUB_RESOURCES);

    // Test factory with no dependencies (matches generated factory pattern)
    public static class SimpleSliceFactory {
        public static Promise<SimpleSlice> simpleSlice(Aspect<SimpleSlice> aspect, SliceCreationContext ctx) {
            return Promise.success(aspect.apply(new SimpleSlice()));
        }

        public static Promise<Slice> simpleSliceSlice(Aspect<SimpleSlice> aspect, SliceCreationContext ctx) {
            return simpleSlice(aspect, ctx).map(s -> s);
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
        public static Promise<OrderService> orderService(Aspect<OrderService> aspect,
                                                         SliceCreationContext ctx) {
            // In real generated code, dependencies are resolved via ctx.invoker().methodHandle()
            return Promise.success(aspect.apply(new OrderService()));
        }

        public static Promise<Slice> orderServiceSlice(Aspect<OrderService> aspect,
                                                       SliceCreationContext ctx) {
            return orderService(aspect, ctx).map(s -> s);
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
        SliceFactory.createSlice(SimpleSliceFactory.class, STUB_CONTEXT, List.of(), List.of())
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(slice -> {
                        assertThat(slice).isInstanceOf(SimpleSlice.class);
                    });
    }

    @Test
    void creates_slice_with_dynamic_dependencies() {
        // Dependencies are passed but not used in factory call
        // (they're resolved via SliceCreationContext at runtime)
        SliceFactory.createSlice(OrderServiceFactory.class, STUB_CONTEXT, List.of(), List.of())
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(slice -> {
                        assertThat(slice).isInstanceOf(OrderService.class);
                    });
    }

    @Test
    void fails_when_factory_method_not_found() {
        // NoMethodFactory doesn't have the required noMethodSlice() method
        class NoMethodFactory {
        }

        SliceFactory.createSlice(NoMethodFactory.class, STUB_CONTEXT, List.of(), List.of())
                    .await()
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> {
                        assertThat(cause.message()).contains("Factory method not found");
                        assertThat(cause.message()).contains("noMethodSlice");
                    });
    }

    @Test
    void fails_when_factory_has_wrong_parameter_count() {
        // Factory with wrong number of parameters
        class WrongParamCountFactory {
            public static Promise<Slice> wrongParamCountSlice(Aspect<?> aspect) {
                return Promise.success(new SimpleSlice());
            }
        }

        SliceFactory.createSlice(WrongParamCountFactory.class, STUB_CONTEXT, List.of(), List.of())
                    .await()
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> {
                        assertThat(cause.message()).contains("Parameter count mismatch");
                    });
    }

    @Test
    void fails_when_first_parameter_is_not_aspect() {
        // Factory with wrong first parameter type
        class WrongFirstParamFactory {
            public static Promise<Slice> wrongFirstParamSlice(String notAspect, SliceCreationContext ctx) {
                return Promise.success(new SimpleSlice());
            }
        }

        SliceFactory.createSlice(WrongFirstParamFactory.class, STUB_CONTEXT, List.of(), List.of())
                    .await()
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> {
                        assertThat(cause.message()).contains("First parameter");
                        assertThat(cause.message()).contains("must be Aspect");
                    });
    }
}
