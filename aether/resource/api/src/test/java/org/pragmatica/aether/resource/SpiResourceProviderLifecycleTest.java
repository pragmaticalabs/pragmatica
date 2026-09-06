// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The resource lifecycle: memoization, refcounting and close (#268), and the close-convention
/// dispatch the release path relies on (#891).
///
/// Every assertion here is on whether the RESOURCE was actually closed, never on whether the close
/// promise succeeded — it succeeded before these fixes too, which is precisely what made the leak
/// invisible.
class SpiResourceProviderLifecycleTest {
    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();
    private static final String SECTION = "tracked.section";

    private record TrackedConfig(String section) {}

    /// Implements the PROJECT's async close convention and nothing else — the #891 case.
    private static final class AsyncResource implements AsyncCloseable {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        boolean isClosed() {
            return closed.get();
        }

        @Override
        public Promise<Unit> close() {
            closed.set(true);

            return Promise.unitPromise();
        }
    }

    /// Implements the JDK convention — the only one dispatched before #891.
    private static final class SyncResource implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /// Implements neither convention: nothing to close, and that must stay a quiet success.
    private static final class InertResource {}

    /// Factory with NO close override, so the default dispatch in [ResourceFactory] is under test.
    private static final class AsyncFactory implements ResourceFactory<AsyncResource, TrackedConfig> {
        private final AsyncResource resource = new AsyncResource();
        private final AtomicInteger provisionCount = new AtomicInteger();

        @Override
        public Class<AsyncResource> resourceType() {
            return AsyncResource.class;
        }

        @Override
        public Class<TrackedConfig> configType() {
            return TrackedConfig.class;
        }

        @Override
        public Promise<AsyncResource> provision(TrackedConfig config) {
            provisionCount.incrementAndGet();

            return Promise.success(resource);
        }
    }

    private static final class SyncFactory implements ResourceFactory<SyncResource, TrackedConfig> {
        private final SyncResource resource = new SyncResource();

        @Override
        public Class<SyncResource> resourceType() {
            return SyncResource.class;
        }

        @Override
        public Class<TrackedConfig> configType() {
            return TrackedConfig.class;
        }

        @Override
        public Promise<SyncResource> provision(TrackedConfig config) {
            return Promise.success(resource);
        }
    }

    private static final class InertFactory implements ResourceFactory<InertResource, TrackedConfig> {
        @Override
        public Class<InertResource> resourceType() {
            return InertResource.class;
        }

        @Override
        public Class<TrackedConfig> configType() {
            return TrackedConfig.class;
        }

        @Override
        public Promise<InertResource> provision(TrackedConfig config) {
            return Promise.success(new InertResource());
        }
    }

    /// Fails the first provision and succeeds afterwards, so a memoized failure is observable as a
    /// second failure (#268 R4).
    private static final class FlakyFactory implements ResourceFactory<AsyncResource, TrackedConfig> {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public Class<AsyncResource> resourceType() {
            return AsyncResource.class;
        }

        @Override
        public Class<TrackedConfig> configType() {
            return TrackedConfig.class;
        }

        @Override
        public Promise<AsyncResource> provision(TrackedConfig config) {
            return attempts.incrementAndGet() == 1
                   ? Causes.cause("transient provisioning failure").promise()
                   : Promise.success(new AsyncResource());
        }
    }

    /// One of two factories answering for the SAME resource type. `supports` and `priority` decide
    /// which one provisions; only that one is entitled to close (#268 R3).
    private static final class SelectableFactory implements ResourceFactory<AsyncResource, TrackedConfig> {
        private final int priority;
        private final boolean supported;
        private final AtomicInteger closeCount = new AtomicInteger();

        private SelectableFactory(int priority, boolean supported) {
            this.priority = priority;
            this.supported = supported;
        }

        @Override
        public Class<AsyncResource> resourceType() {
            return AsyncResource.class;
        }

        @Override
        public Class<TrackedConfig> configType() {
            return TrackedConfig.class;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean supports(TrackedConfig config) {
            return supported;
        }

        @Override
        public Promise<AsyncResource> provision(TrackedConfig config) {
            return Promise.success(new AsyncResource());
        }

        @Override
        public Promise<Unit> close(AsyncResource resource) {
            closeCount.incrementAndGet();

            return Promise.unitPromise();
        }

        int closeCount() {
            return closeCount.get();
        }
    }

    private static SpiResourceProvider providerOf(ResourceFactory<?, ?>... factories) {
        return spiResourceProvider(List.of(factories),
                                   (section, _) -> Result.success(new TrackedConfig(section)));
    }

    private static ProvisioningContext contextFor(String sliceId) {
        return ProvisioningContext.provisioningContext()
                                  .withExtension(String.class, sliceId);
    }

    @Nested
    class ContextOverloadParticipatesInTheLifecycle {

        /// #268 R1. Production reaches the CONTEXT overload; before the fix only the plain overload
        /// wrote `promiseCache`, so `releaseAll` drained a map nothing had been inserted into and
        /// no context-provisioned resource was ever closed.
        @Test
        void releaseAll_closesResource_provisionedThroughContextOverload() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a"))
                    .await(TIMEOUT);

            assertThat(factory.resource.isClosed()).isFalse();

            provider.releaseAll("slice-a")
                    .await(TIMEOUT);

            assertThat(factory.resource.isClosed()).isTrue();
        }

        /// The context overload must memoize, or refcounting has nothing to count.
        @Test
        void provide_provisionsOnce_whenContextOverloadCalledTwice() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            provider.provide(AsyncResource.class, SECTION, contextFor("slice-b")).await(TIMEOUT);

            assertThat(factory.provisionCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class Refcounting {

        @Test
        void releaseAll_doesNotClose_whileAnotherSliceStillHoldsTheResource() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            provider.provide(AsyncResource.class, SECTION, contextFor("slice-b")).await(TIMEOUT);

            provider.releaseAll("slice-a").await(TIMEOUT);

            assertThat(factory.resource.isClosed()).isFalse();
        }

        @Test
        void releaseAll_closes_whenLastConsumerReleases() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            provider.provide(AsyncResource.class, SECTION, contextFor("slice-b")).await(TIMEOUT);

            provider.releaseAll("slice-a").await(TIMEOUT);
            provider.releaseAll("slice-b").await(TIMEOUT);

            assertThat(factory.resource.isClosed()).isTrue();
        }

        /// #268 R2. `CacheKey` has no slice dimension, so a resource IS shared; a caller that came
        /// in through the context-free overload cannot be attributed to any slice and therefore
        /// pins the entry. Closing it on some other slice's unload is use-after-close on a live
        /// connection pool.
        @Test
        void releaseAll_doesNotClose_whileAnUnattributedCallerStillHoldsTheResource() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION).await(TIMEOUT);
            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);

            provider.releaseAll("slice-a").await(TIMEOUT);

            assertThat(factory.resource.isClosed()).isFalse();
        }
    }

    @Nested
    class ClosesThroughTheProvisioningFactory {

        /// #268 R3. `releaseAll` used `factoryList.getFirst()` — the highest-priority factory —
        /// rather than the one whose `supports()` matched. The DB connectors are exactly this
        /// shape: async, R2DBC and JDBC all answer for `SqlConnector`, ordered by priority.
        @Test
        void releaseAll_closesThroughMatchingFactory_notTheHighestPriorityOne() {
            var declining = new SelectableFactory(100, false);
            var matching = new SelectableFactory(1, true);
            var provider = providerOf(declining, matching);

            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            provider.releaseAll("slice-a").await(TIMEOUT);

            assertThat(matching.closeCount()).isEqualTo(1);
            assertThat(declining.closeCount()).isZero();
        }
    }

    @Nested
    class FailedProvisioningIsNotMemoized {

        /// #268 R4. `computeIfAbsent` cached the failed promise forever, so an `Intermittent`
        /// failure classified for retry-with-backoff handed back the same poisoned promise on every
        /// retry.
        @Test
        void provide_retriesProvisioning_afterAFailedAttempt() {
            var provider = providerOf(new FlakyFactory());

            var first = provider.provide(AsyncResource.class, SECTION, contextFor("slice-a"))
                                .await(TIMEOUT);
            var second = provider.provide(AsyncResource.class, SECTION, contextFor("slice-a"))
                                 .await(TIMEOUT);

            assertThat(first.isFailure()).isTrue();
            assertThat(second.isSuccess()).isTrue();
        }
    }

    @Nested
    class DefaultCloseConventionDispatch {

        /// #891. The default close dispatched only on the JDK's `AutoCloseable`, so a resource
        /// implementing the project's own `AsyncCloseable` was never closed — and the promise
        /// reported success, which is why nothing upstream could tell.
        @Test
        void releaseAll_closesAsyncCloseableResource_throughDefaultDispatch() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            provider.releaseAll("slice-a").await(TIMEOUT);

            assertThat(factory.resource.isClosed()).isTrue();
        }

        @Test
        void releaseAll_stillClosesAutoCloseableResource_throughDefaultDispatch() {
            var factory = new SyncFactory();
            var provider = providerOf(factory);

            provider.provide(SyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            provider.releaseAll("slice-a").await(TIMEOUT);

            assertThat(factory.resource.isClosed()).isTrue();
        }

        /// A resource implementing neither convention has nothing to close; the release must still
        /// succeed rather than fail the slice's unload.
        @Test
        void releaseAll_succeeds_forResourceImplementingNeitherConvention() {
            var provider = providerOf(new InertFactory());

            provider.provide(InertResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);

            var released = provider.releaseAll("slice-a")
                                   .await(TIMEOUT);

            assertThat(released.isSuccess()).isTrue();
        }
    }
}
