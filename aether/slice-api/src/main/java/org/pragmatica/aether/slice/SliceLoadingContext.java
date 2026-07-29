// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.DeferredSliceCodec;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.utils.Causes.cause;


public final class SliceLoadingContext implements SliceCreationContext {
    private final SliceCreationContext delegate;
    private final BufferingInvokerFacade bufferingInvoker;
    private final AtomicBoolean materialized = new AtomicBoolean(false);

    private final AtomicReference<Option<ConfigurationProvider>> sliceComposite = new AtomicReference<>(Option.none());

    private final AtomicReference<Option<Fn1<Option<ConfigurationProvider>, ClassLoader>>> compositeBuilder = new AtomicReference<>(Option.none());

    /// The node codec this slice's own codec will be layered on, when the loader supplied one.
    /// Absent on the legacy / test paths that construct a loading context without codec support —
    /// those keep receiving the node-wide codec through the SPI runtime extensions, unchanged.
    private final Option<SliceCodec> nodeCodec;
    /// Late-bound holder for the deployed slice's codec, handed to every resource this slice
    /// provisions. Present exactly when `nodeCodec` is (see [#bindSliceCodec]).
    private final Option<DeferredSliceCodec> sliceCodec;

    private SliceLoadingContext(SliceCreationContext delegate, Option<SliceCodec> nodeCodec) {
        this.delegate = delegate;
        this.bufferingInvoker = new BufferingInvokerFacade(delegate.invoker());
        this.nodeCodec = nodeCodec;
        this.sliceCodec = nodeCodec.map(_ -> DeferredSliceCodec.deferredSliceCodec(delegate.sliceId()
                                                                                           .or("<unnamed slice>")));
    }

    public static SliceLoadingContext sliceLoadingContext(SliceCreationContext delegate) {
        return new SliceLoadingContext(delegate, Option.none());
    }

    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade,
                                                          ResourceProviderFacade resourceFacade) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, resourceFacade),
                                       Option.none());
    }

    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade,
                                                          ResourceProviderFacade resourceFacade,
                                                          String sliceId) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, resourceFacade, sliceId),
                                       Option.none());
    }

    /// Loading context that scopes resource provisioning to the DEPLOYED SLICE's codec.
    ///
    /// The slice's codec (`slice.codec(nodeCodec)`) is the only one that knows the application's
    /// own record types; the node codec knows framework types alone. Passing the node codec here
    /// arms the late-bound holder that every resource provisioned through [#resources] receives as
    /// its `Serializer`/`Deserializer`, so stream publishers, stream readers, distributed caches
    /// and idempotency stores all encode application types instead of failing on them (#526).
    /// Pass `Option.none()` to keep the pre-#526 behaviour of node-codec-only resources.
    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade,
                                                          ResourceProviderFacade resourceFacade,
                                                          String sliceId,
                                                          Option<SliceCodec> nodeCodec) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, resourceFacade, sliceId),
                                       nodeCodec);
    }

    public static SliceLoadingContext sliceLoadingContext(SliceInvokerFacade invokerFacade) {
        return new SliceLoadingContext(SliceCreationContext.sliceCreationContext(invokerFacade, noOpResourceProvider()),
                                       Option.none());
    }

    private static ResourceProviderFacade noOpResourceProvider() {
        return new NoOpResourceProvider();
    }

    @Override
    public SliceInvokerFacade invoker() {
        return bufferingInvoker;
    }

    @Override
    public Option<String> sliceId() {
        return delegate.sliceId();
    }

    @Override
    public ConfigFacade config() {
        return delegate.config();
    }

    @Override
    public ResourceProviderFacade resources() {
        var base = delegate.sliceId()
                           .map(id -> sliceAwareResourceProvider(delegate.resources(),
                                                                 id))
                           .or(delegate.resources());

        return new CompositeAwareResourceProvider(codecAwareResourceProvider(base, sliceCodec), sliceComposite);
    }

    /// Bind the deployed slice's codec so every resource this slice already provisioned starts
    /// resolving application types.
    ///
    /// Called by the loader as soon as the slice instance exists — which is the earliest moment it
    /// CAN be called, since `Slice.codec(parent)` is an instance method, and still strictly before
    /// `start()` and before the slice is reachable for invocation. A no-op when the context was
    /// built without a node codec.
    @Contract
    public void bindSliceCodec(Slice slice) {
        sliceCodec.onPresent(deferred -> nodeCodec.onPresent(parent -> deferred.bind(slice.codec(parent))));
    }

    /// The late-bound slice codec handed to this slice's resources, when codec scoping is armed.
    public Option<DeferredSliceCodec> sliceCodec() {
        return sliceCodec;
    }

    /// Set the slice-composite (`slice.toml ⊕ nodeComposite`) for this loading context.
    ///
    /// Must be called BEFORE the slice factory invokes `ctx.resources().provide(...)`. The
    /// composite will be attached to every `ProvisioningContext` produced by `resources()`
    /// so resource factories can read per-slice configuration without consulting the global
    /// `ConfigService.instance()` singleton.
    ///
    /// Calling with `Option.none()` is a no-op. Subsequent calls overwrite the prior value.
    @Contract
    public void setSliceComposite(Option<ConfigurationProvider> composite) {
        composite.onPresent(_ -> sliceComposite.set(composite));
    }

    /// Register a deferred composite builder that will be invoked once the slice classloader
    /// is available (just before the slice factory runs). The builder reads slice-local
    /// resources (e.g. `META-INF/resources.toml`) and layers them over the node-composite.
    ///
    /// Use this when the caller can't construct the composite up front because it depends on
    /// the slice JAR's contents loaded via the slice classloader. Call `materializeComposite`
    /// with the resolved classloader to trigger the build and attach the result.
    @Contract
    public void setCompositeBuilder(Fn1<Option<ConfigurationProvider>, ClassLoader> builder) {
        compositeBuilder.set(Option.some(builder));
    }

    /// Invoke the registered composite builder with the slice's classloader (if a builder is
    /// registered) and attach the resulting composite. Idempotent — once a non-empty composite
    /// is attached, subsequent calls don't overwrite it.
    @Contract
    public void materializeComposite(ClassLoader sliceClassLoader) {
        if (sliceComposite.get().isPresent()) {
            return;
        }

        compositeBuilder.get().onPresent(builder -> setSliceComposite(builder.apply(sliceClassLoader)));
    }

    /// Return the currently-attached slice-composite, if any.
    public Option<ConfigurationProvider> sliceComposite() {
        return sliceComposite.get();
    }

    public Result<Unit> materializeAll() {
        for (var handle : bufferingInvoker.bufferedHandles()) {
            var result = handle.materialize();

            if (result.isFailure()) {
                return result;
            }
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
        return bufferingInvoker.bufferedHandles()
                               .size();
    }

    public SliceCreationContext delegate() {
        return delegate;
    }

    private static ResourceProviderFacade sliceAwareResourceProvider(ResourceProviderFacade delegate, String sliceId) {
        return new SliceAwareResourceProvider(delegate, sliceId);
    }

    private static ResourceProviderFacade codecAwareResourceProvider(ResourceProviderFacade delegate,
                                                                     Option<DeferredSliceCodec> sliceCodec) {
        return sliceCodec.map(codec -> (ResourceProviderFacade) new CodecAwareResourceProvider(delegate, codec))
                         .or(delegate);
    }

    /// Resource facade that scopes serialization to the DEPLOYED SLICE.
    ///
    /// Every `ProvisioningContext` forwarded downstream carries the slice's own codec as both
    /// `Serializer` and `Deserializer`, so a resource that encodes application values — stream
    /// publishers and readers, distributed cache and idempotency backends — resolves the types
    /// the application declared. The node-wide codec remains reachable through it: the slice codec
    /// is built as a CHILD of the node codec, inheriting every framework registration verbatim,
    /// which is why framework-typed resources behave identically (#526).
    ///
    /// The codec arrives here unbound and is filled in the moment the slice instance exists; see
    /// [SliceLoadingContext#bindSliceCodec]. `SpiResourceProvider` layers node-wide runtime
    /// extensions UNDER what the caller supplied, so these two are not overwritten.
    private static final class CodecAwareResourceProvider implements ResourceProviderFacade {
        private final ResourceProviderFacade delegate;
        private final DeferredSliceCodec sliceCodec;

        CodecAwareResourceProvider(ResourceProviderFacade delegate, DeferredSliceCodec sliceCodec) {
            this.delegate = delegate;
            this.sliceCodec = sliceCodec;
        }

        /// Deliberately NOT upgraded to the context overload. The no-context overload is the cached
        /// path in `SpiResourceProvider` (one shared promise per type+section); upgrading it here
        /// would silently drop that caching. Nothing is lost: every resource that encodes values —
        /// streams, publishers, interceptors — is provisioned through the context overload by
        /// generated code, and the stream factories reject a context-less provisioning outright.
        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return delegate.provide(resourceType, configSection);
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
            return delegate.provide(resourceType, configSection, withSliceCodec(context));
        }

        private ProvisioningContext withSliceCodec(ProvisioningContext context) {
            return context.withExtension(Serializer.class, sliceCodec)
                          .withExtension(Deserializer.class, sliceCodec);
        }

        @Override
        public Promise<Unit> releaseAll(String sliceId) {
            return delegate.releaseAll(sliceId);
        }
    }

    /// Resource facade that attaches the slice-composite to every `ProvisioningContext`
    /// it forwards to the delegate. Calls to the no-context overload are upgraded to use
    /// the context overload when a slice-composite is present, so the composite reaches
    /// downstream resource factories regardless of which `provide(...)` overload the
    /// generated slice factory uses.
    private static final class CompositeAwareResourceProvider implements ResourceProviderFacade {
        private final ResourceProviderFacade delegate;
        private final AtomicReference<Option<ConfigurationProvider>> compositeRef;

        CompositeAwareResourceProvider(ResourceProviderFacade delegate,
                                       AtomicReference<Option<ConfigurationProvider>> compositeRef) {
            this.delegate = delegate;
            this.compositeRef = compositeRef;
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return compositeRef.get()
                               .map(composite -> delegate.provide(resourceType,
                                                                  configSection,
                                                                  ProvisioningContext.provisioningContext().withExtension(ConfigurationProvider.class,
                                                                                                                          composite)))
                               .or(() -> delegate.provide(resourceType, configSection));
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
            var enriched = compositeRef.get()
                                       .map(composite -> context.withExtension(ConfigurationProvider.class, composite))
                                       .or(context);

            return delegate.provide(resourceType, configSection, enriched);
        }

        @Override
        public Promise<Unit> releaseAll(String sliceId) {
            return delegate.releaseAll(sliceId);
        }
    }

    private static final class SliceAwareResourceProvider implements ResourceProviderFacade {
        private final ResourceProviderFacade delegate;
        private final String sliceId;

        SliceAwareResourceProvider(ResourceProviderFacade delegate, String sliceId) {
            this.delegate = delegate;
            this.sliceId = sliceId;
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return delegate.provide(resourceType, configSection);
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
            return delegate.provide(resourceType, configSection, context.withExtension(String.class, sliceId));
        }

        @Override
        public Promise<Unit> releaseAll(String releaseSliceId) {
            return delegate.releaseAll(releaseSliceId);
        }
    }

    private static final class NoOpResourceProvider implements ResourceProviderFacade {
        private static final Cause NOT_CONFIGURED = cause("Resource provisioning not configured");

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
            return NOT_CONFIGURED.promise();
        }

        @Override
        public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
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

        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                              String methodName,
                                                              TypeToken<T> requestType,
                                                              TypeToken<R> responseType) {
            return delegate.methodHandle(sliceArtifact, methodName, requestType, responseType)
                           .onSuccess(this::bufferHandleIfActive);
        }

        private <R, T> void bufferHandleIfActive(MethodHandle<R, T> handle) {
            if (buffering.get()) {
                bufferedHandles.add(handle);
            }
        }

        List<MethodHandle<?, ?>> bufferedHandles() {
            return bufferedHandles;
        }

        @Contract
        void stopBuffering() {
            buffering.set(false);
        }
    }
}
