package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.utils.Causes.cause;


/// Context for loading a slice that buffers method handles for eager materialization.
///
/// During slice loading, this context wraps the SliceCreationContext and buffers all
/// MethodHandle instances created via {@link #invoker()}. When activation begins,
/// {@link #materializeAll()} is called to verify all dependencies exist before
/// the slice's start() method is called.
///
/// This implements Part 2 of the Slice Lifecycle design: Eager Dependency Validation.
/// The goal is to ensure no technical failures occur after a slice reaches ACTIVE state.
///
/// Thread-safe: Uses CopyOnWriteArrayList for handle buffering and AtomicBoolean for state.
public final class SliceLoadingContext implements SliceCreationContext {
    private final SliceCreationContext delegate;
    private final BufferingInvokerFacade bufferingInvoker;

    private final AtomicBoolean materialized = new AtomicBoolean(false);

    private SliceLoadingContext(SliceCreationContext delegate) {
        this.delegate = delegate;
        this.bufferingInvoker = new BufferingInvokerFacade(delegate.invoker());
    }

    public static SliceLoadingContext sliceLoadingContext(SliceCreationContext delegate) {
        return new SliceLoadingContext(delegate);
    }

    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade,
                                                          ResourceProviderFacade resourceFacade) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, resourceFacade));
    }

    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade,
                                                          ResourceProviderFacade resourceFacade,
                                                          String sliceId) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, resourceFacade, sliceId));
    }

    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, noOpResourceProvider()));
    }

    private static ResourceProviderFacade noOpResourceProvider() {
        return new NoOpResourceProvider();
    }

    @Override public SliceInvokerFacade invoker() {
        return bufferingInvoker;
    }

    @Override public Option<String> sliceId() {
        return delegate.sliceId();
    }

    @Override public ConfigFacade config() {
        return delegate.config();
    }

    @Override public ResourceProviderFacade resources() {
        return delegate.sliceId().map(id -> sliceAwareResourceProvider(delegate.resources(),
                                                                       id))
                               .or(delegate.resources());
    }

    public Result<Unit> materializeAll() {
        for (var handle : bufferingInvoker.bufferedHandles()) {
            var result = handle.materialize();
            if (result.isFailure()) {return result;}
        }
        return Result.unitResult();
    }

    public Result<Unit> markMaterialized() {
        materialized.set(true);
        bufferingInvoker.stopBuffering();
        return success(unit());
    }

    public boolean isMaterialized() {
        return materialized.get();
    }

    public int bufferedHandleCount() {
        return bufferingInvoker.bufferedHandles().size();
    }

    public SliceCreationContext delegate() {
        return delegate;
    }

    private static ResourceProviderFacade sliceAwareResourceProvider(ResourceProviderFacade delegate, String sliceId) {
        return new SliceAwareResourceProvider(delegate, sliceId);
    }

    private static final class SliceAwareResourceProvider implements ResourceProviderFacade {
        private final ResourceProviderFacade delegate;
        private final String sliceId;

        SliceAwareResourceProvider(ResourceProviderFacade delegate, String sliceId) {
            this.delegate = delegate;
            this.sliceId = sliceId;
        }

        @Override public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return delegate.provide(resourceType, configSection);
        }

        @Override public <T> Promise<T> provide(Class<T> resourceType,
                                                String configSection,
                                                ProvisioningContext context) {
            return delegate.provide(resourceType, configSection, context.withExtension(String.class, sliceId));
        }

        @Override public Promise<Unit> releaseAll(String releaseSliceId) {
            return delegate.releaseAll(releaseSliceId);
        }
    }

    private static final class NoOpResourceProvider implements ResourceProviderFacade {
        private static final Cause NOT_CONFIGURED = cause("Resource provisioning not configured");

        @Override public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return NOT_CONFIGURED.promise();
        }

        @Override public <T> Promise<T> provide(Class<T> resourceType,
                                                String configSection,
                                                ProvisioningContext context) {
            return NOT_CONFIGURED.promise();
        }
    }

    private static final class BufferingInvokerFacade implements SliceInvokerFacade {
        private final SliceInvokerFacade delegate;

        private final List<MethodHandle<?, ?>> bufferedHandles = new CopyOnWriteArrayList<>();

        private final AtomicBoolean buffering = new AtomicBoolean(true);

        BufferingInvokerFacade(SliceInvokerFacade delegate) {
            this.delegate = delegate;
        }

        @Override public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                        String methodName,
                                                                        TypeToken<T> requestType,
                                                                        TypeToken<R> responseType) {
            return delegate.methodHandle(sliceArtifact, methodName, requestType, responseType)
                                        .onSuccess(this::bufferHandleIfActive);
        }

        private <R, T> void bufferHandleIfActive(MethodHandle<R, T> handle) {
            if (buffering.get()) {bufferedHandles.add(handle);}
        }

        List<MethodHandle<?, ?>> bufferedHandles() {
            return bufferedHandles;
        }

        void stopBuffering() {
            buffering.set(false);
        }
    }
}
