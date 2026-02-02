package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.Slice;
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
 * New-style factories take only (Aspect, SliceInvokerFacade) and resolve
 * dependencies dynamically via SliceInvokerFacade at runtime.
 */
class SliceFactoryTest {

    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact, String method, TypeToken<T> requestType, TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    // Test factory with no dependencies (matches generated factory pattern)
    public static class SimpleSliceFactory {
        public static Promise<SimpleSlice> simpleSlice(Aspect<SimpleSlice> aspect, SliceInvokerFacade invoker) {
            return Promise.success(aspect.apply(new SimpleSlice()));
        }

        public static Promise<Slice> simpleSliceSlice(Aspect<SimpleSlice> aspect, SliceInvokerFacade invoker) {
            return simpleSlice(aspect, invoker).map(s -> s);
        }
    }

    public static class SimpleSlice implements Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    // Test factory that simulates dependency resolution via SliceInvokerFacade
    public static class OrderServiceFactory {
        public static Promise<OrderService> orderService(Aspect<OrderService> aspect,
                                                         SliceInvokerFacade invoker) {
            // In real generated code, dependencies are resolved via invoker.methodHandle()
            return Promise.success(aspect.apply(new OrderService()));
        }

        public static Promise<Slice> orderServiceSlice(Aspect<OrderService> aspect,
                                                       SliceInvokerFacade invoker) {
            return orderService(aspect, invoker).map(s -> s);
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
        SliceFactory.createSlice(SimpleSliceFactory.class, STUB_INVOKER, List.of(), List.of())
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(slice -> {
                        assertThat(slice).isInstanceOf(SimpleSlice.class);
                    });
    }

    @Test
    void creates_slice_with_dynamic_dependencies() {
        // Dependencies are passed but not used in factory call
        // (they're resolved via SliceInvokerFacade at runtime)
        SliceFactory.createSlice(OrderServiceFactory.class, STUB_INVOKER, List.of(), List.of())
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

        SliceFactory.createSlice(NoMethodFactory.class, STUB_INVOKER, List.of(), List.of())
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

        SliceFactory.createSlice(WrongParamCountFactory.class, STUB_INVOKER, List.of(), List.of())
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
            public static Promise<Slice> wrongFirstParamSlice(String notAspect, SliceInvokerFacade invoker) {
                return Promise.success(new SimpleSlice());
            }
        }

        SliceFactory.createSlice(WrongFirstParamFactory.class, STUB_INVOKER, List.of(), List.of())
                    .await()
                    .onSuccessRun(Assertions::fail)
                    .onFailure(cause -> {
                        assertThat(cause.message()).contains("First parameter");
                        assertThat(cause.message()).contains("must be Aspect");
                    });
    }
}
