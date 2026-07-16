// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
    void fails_with_rebuild_hint_when_factory_parameter_type_missing() throws ClassNotFoundException {
        // Load GhostParamFactory through a classloader that hides its parameter type GhostAspect,
        // so reflective parameter inspection throws (as an rc1 slice referencing removed Aspect does).
        var hiddenType = GhostAspect.class.getName();
        var loader = new HidingClassLoader(hiddenType);
        var factoryClass = loader.loadClass(GhostParamFactory.class.getName());

        SliceFactory.createSlice(factoryClass, STUB_CONTEXT, List.of(), List.of()).await().onSuccessRun(Assertions::fail).onFailure(cause -> {
            assertThat(cause.message()).contains("Parameter mismatch");
            assertThat(cause.message()).contains("rebuild against this runtime version");
            assertThat(cause.message()).contains("GhostAspect");
        });
    }

    /// Test classloader implementing the JDK {@link ClassLoader} SPI: it defines the ghost factory
    /// from parent bytes (so the factory's defining loader is this one) while refusing to load the
    /// hidden parameter type, forcing a {@link ClassNotFoundException} during reflective inspection.
    /// try/catch/throw here satisfy the {@code loadClass}/{@code findClass} contract, mirroring the
    /// production SliceClassLoader boundary.
    private static final class HidingClassLoader extends ClassLoader {
        private static final String GHOST_PREFIX = GhostParamFactory.class.getName();

        private final String hiddenType;

        private HidingClassLoader(String hiddenType) {
            super(HidingClassLoader.class.getClassLoader());
            this.hiddenType = hiddenType;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(hiddenType)) {
                throw new ClassNotFoundException(name);
            }

            if (name.equals(GHOST_PREFIX)) {
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
