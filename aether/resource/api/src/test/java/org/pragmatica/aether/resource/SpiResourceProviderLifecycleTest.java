// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        private final AtomicInteger closes = new AtomicInteger();

        boolean isClosed() {
            return closes.get() > 0;
        }

        int closeCount() {
            return closes.get();
        }

        @Override
        public Promise<Unit> close() {
            closes.incrementAndGet();

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
    ///
    /// Hands out a DISTINCT resource per provisioning call and keeps them all. A single shared
    /// resource object would make a scoped-cache assertion conflate two different cache entries —
    /// the fixture has to be able to tell slice-a's resource from slice-b's before a test can claim
    /// anything about which one was closed.
    private static final class AsyncFactory implements ResourceFactory<AsyncResource, TrackedConfig> {
        private final List<AsyncResource> provisioned = new CopyOnWriteArrayList<>();

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
            var resource = new AsyncResource();

            provisioned.add(resource);

            return Promise.success(resource);
        }

        int provisionCount() {
            return provisioned.size();
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

    /// Fails the first provision ASYNCHRONOUSLY — the promise is returned pending and failed from
    /// another thread, only once the test says so — and succeeds afterwards.
    ///
    /// [FlakyFactory] fails synchronously, which means every continuation the caller chains sees an
    /// already-resolved promise and runs inline; the ORDER in which the provider's eviction and the
    /// caller's retry are registered is then invisible. A pending failure is the only way to
    /// observe that order, and it is the production shape: a connector fails on a worker thread.
    private static final class AsyncFlakyFactory implements ResourceFactory<AsyncResource, TrackedConfig> {
        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch releaseFailure = new CountDownLatch(1);

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
            if (attempts.incrementAndGet() > 1) {
                return Promise.success(new AsyncResource());
            }

            return Promise.promise(promise -> Thread.ofVirtual().start(() -> failWhenReleased(promise)));
        }

        private void failWhenReleased(Promise<AsyncResource> promise) {
            try {
                releaseFailure.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            promise.fail(Causes.cause("transient provisioning failure"));
        }

        void releaseFailure() {
            releaseFailure.countDown();
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

    /// Mirrors SpiResourceProvider's own UNATTRIBUTED_SCOPE. Deliberately duplicated rather than
    /// exposed: releasing it must be a no-op, and a test that reached into the production constant
    /// could not tell the difference between "never matches" and "constant renamed".
    private static final String UNATTRIBUTED_SCOPE_LITERAL = "<unattributed>";

    private interface Codec {}

    private record SliceCodec(String owner) implements Codec {}

    /// Stands in for a stream or DHT-cache resource: its VALUE embeds the codec it was built with,
    /// which is what makes cross-slice sharing observable.
    private record CodecTaggedResource(String codecOwner) {}

    private static final class CodecTaggedFactory implements ResourceFactory<CodecTaggedResource, TrackedConfig> {
        @Override
        public Class<CodecTaggedResource> resourceType() {
            return CodecTaggedResource.class;
        }

        @Override
        public Class<TrackedConfig> configType() {
            return TrackedConfig.class;
        }

        @Override
        public Promise<CodecTaggedResource> provision(TrackedConfig config) {
            return Promise.success(new CodecTaggedResource("no-codec"));
        }

        @Override
        public Promise<CodecTaggedResource> provision(TrackedConfig config, ProvisioningContext context) {
            return context.extension(Codec.class)
                          .map(codec -> new CodecTaggedResource(((SliceCodec) codec).owner()))
                          .async();
        }
    }

    private static ProvisioningContext contextFor(String sliceId, String codecOwner) {
        return contextFor(sliceId).withExtension(Codec.class, new SliceCodec(codecOwner));
    }

    @Nested
    class SliceScopedLifecycle {

        /// #268 R1. Production reaches the CONTEXT overload; before the fix only the plain overload
        /// wrote `promiseCache`, so `releaseAll` drained a map nothing had been inserted into and
        /// no context-provisioned resource was ever closed.
        @Test
        void releaseAll_closesResource_provisionedThroughContextOverload() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a"))
                    .await(TIMEOUT);

            assertThat(factory.provisioned.getFirst().isClosed()).isFalse();

            provider.releaseAll("slice-a")
                    .await(TIMEOUT);

            assertThat(factory.provisioned.getFirst().isClosed()).isTrue();
        }

        /// Memoization within one slice: repeated provisioning of the same type+section by the same
        /// slice yields one resource, which is what makes the release path have a single thing to
        /// close.
        @Test
        void provide_provisionsOnce_whenOneSliceProvidesTwice() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            var first = provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            var second = provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);

            assertThat(factory.provisionCount()).isEqualTo(1);
            assertThat(second.unwrap()).isSameAs(first.unwrap());
        }

        /// The cache key carries a slice dimension, so two slices get two instances.
        ///
        /// This is not an efficiency preference — it is what keeps #526 fixed, and the codec test
        /// below shows what goes wrong without it.
        @Test
        void provide_givesEachSliceItsOwnInstance() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            var forA = provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            var forB = provider.provide(AsyncResource.class, SECTION, contextFor("slice-b")).await(TIMEOUT);

            assertThat(factory.provisionCount()).isEqualTo(2);
            assertThat(forB.unwrap()).isNotSameAs(forA.unwrap());
        }

        /// A slice unload releases exactly that slice's resources and leaves every other slice's
        /// alone.
        ///
        /// Deliberately NOT phrased as cross-slice refcounting: with a scoped key the two slices
        /// never share an entry, so a "does not close while another slice holds it" assertion would
        /// be true by construction and would pass against broken code.
        @Test
        void releaseAll_closesOnlyTheReleasingSlicesResource() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            provider.provide(AsyncResource.class, SECTION, contextFor("slice-b")).await(TIMEOUT);

            var forA = factory.provisioned.get(0);
            var forB = factory.provisioned.get(1);

            provider.releaseAll("slice-a").await(TIMEOUT);

            assertThat(forA.isClosed()).isTrue();
            assertThat(forB.isClosed()).isFalse();
        }
    }

    @Nested
    class UnattributedScopeIsSharedAndPinned {

        /// The context-free overload is the genuinely shared cache: it carries no slice id and no
        /// codec, so every unattributed caller can safely have the same instance.
        @Test
        void provide_sharesOneInstance_acrossPlainOverloadCallers() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            var first = provider.provide(AsyncResource.class, SECTION).await(TIMEOUT);
            var second = provider.provide(AsyncResource.class, SECTION).await(TIMEOUT);

            assertThat(factory.provisionCount()).isEqualTo(1);
            assertThat(second.unwrap()).isSameAs(first.unwrap());
        }

        /// #268 R2. An unattributed caller cannot be tied to any slice, so no slice's unload may
        /// close its resource. Closing it would be use-after-close on a live connection pool; the
        /// safe direction is a bounded leak.
        @Test
        void releaseAll_neverCloses_theUnattributedScopesResource() {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);

            provider.provide(AsyncResource.class, SECTION).await(TIMEOUT);
            provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);

            var unattributed = factory.provisioned.get(0);

            provider.releaseAll("slice-a").await(TIMEOUT);
            provider.releaseAll(UNATTRIBUTED_SCOPE_LITERAL).await(TIMEOUT);

            assertThat(unattributed.isClosed()).isFalse();
        }
    }

    @Nested
    class ProvideRacingReleaseOfTheSameScope {
        private static final int ROUNDS = 40_000;

        /// One round: a provision and two back-to-back releases of the SAME scope, started as close
        /// together as two threads and a latch allow. The result of the provision is kept; the
        /// releases' are not. The window the old code lost was the few instructions between its two
        /// map updates, so the releasing thread fires twice per round to land in it more often.
        private static Result<AsyncResource> race(SpiResourceProvider provider, ExecutorService executor) throws Exception {
            var go = new CountDownLatch(1);
            var provided = executor.submit(() -> {
                go.await();

                return provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")).await(TIMEOUT);
            });
            var released = executor.submit(() -> {
                go.await();
                provider.releaseAll("slice-a").await(TIMEOUT);

                return provider.releaseAll("slice-a").await(TIMEOUT);
            });

            go.countDown();
            released.get();

            return provided.get();
        }

        /// The lifecycle state is ONE map mutated only through per-key atomics, so a provision is
        /// linearized against a release of its scope: it either receives the entry the release is
        /// about to close, or creates a fresh one that the NEXT release of the scope finds. That is
        /// what each round asserts: after the race, one more release of the scope must leave the
        /// resource the caller received closed. Two more things follow and are asserted at the
        /// end: no resource is closed twice (exactly one remover per entry) and none is left open.
        ///
        /// With the previous two-map state — a consumer set updated beside the cache — the
        /// provision could insert into the cache AFTER the release had dropped the set, and a
        /// release, which walked the set's keys, could not reach that entry: the caller held a
        /// resource no release could close until some LATER provision re-registered the key and
        /// adopted it. A final release alone therefore cannot see the defect; the per-round check
        /// can. The review of #900 measured 187 of 3000 rounds (SF-2) with its own probe; with
        /// this fixture the window is hit far less often, so the round count is sized for the
        /// revert to be red on every run, not most — see the fix report for the measured rate.
        @Test
        void afterEachRacingRound_theNextReleaseClosesWhatTheCallerReceived() throws Exception {
            var factory = new AsyncFactory();
            var provider = providerOf(factory);
            var unreachable = 0;

            try (var executor = Executors.newFixedThreadPool(2)) {
                for (var round = 0; round < ROUNDS; round++) {
                    var received = race(provider, executor);

                    assertThat(received.isSuccess()).as("round %d must hand out a resource", round).isTrue();

                    provider.releaseAll("slice-a").await(TIMEOUT);

                    if (!received.unwrap().isClosed()) {
                        unreachable++;
                    }
                }
            }

            var closedTwice = factory.provisioned.stream().filter(resource -> resource.closeCount() > 1).count();
            var stillOpen = factory.provisioned.stream().filter(resource -> !resource.isClosed()).count();

            assertThat(unreachable).as("rounds whose resource the next release could not reach, of %d", ROUNDS).isZero();
            assertThat(closedTwice).as("resources closed by more than one remover").isZero();
            assertThat(stillOpen).as("resources left open after the last release").isZero();
        }
    }

    @Nested
    class CodecScopingSurvivesCaching {

        /// #526 guard. `CodecAwareResourceProvider` injects the DEPLOYED SLICE's codec as
        /// Serializer/Deserializer on every context-overload call, because it is the only codec
        /// that knows the application's own record types.
        ///
        /// Caching the context overload under a key WITHOUT a slice dimension hands slice B the
        /// resource slice A built with A's codec — reintroducing #526 through a leak fix. This test
        /// fails (slice-b receives codec-a) if the slice dimension is dropped from CacheKey.
        @Test
        void secondSlice_receivesItsOwnCodec_notTheFirstSlices() {
            var provider = providerOf(new CodecTaggedFactory());

            var forA = provider.provide(CodecTaggedResource.class, SECTION, contextFor("slice-a", "codec-a"))
                               .await(TIMEOUT);
            var forB = provider.provide(CodecTaggedResource.class, SECTION, contextFor("slice-b", "codec-b"))
                               .await(TIMEOUT);

            assertThat(forA.unwrap().codecOwner()).isEqualTo("codec-a");
            assertThat(forB.unwrap().codecOwner()).isEqualTo("codec-b");
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

        /// The eviction must run BEFORE the caller can observe the failure, not merely eventually.
        ///
        /// The retry here is issued from the failure continuation itself (`fold` is a dependent
        /// transform, so it runs on the resolving thread the moment the failure lands). Evicting
        /// through an `onFailure` EVENT ran after every dependent, so a retry from the continuation
        /// called `computeIfAbsent` first and received the memoized failure (review of #900, SF-1:
        /// 25 of 50 retries). Registering the eviction as a dependent ahead of the caller's `map`
        /// makes this deterministic: with the event-based eviction it fails every time, because the
        /// failure is released only after the retry is chained.
        @Test
        void provide_retryIssuedFromTheFailureContinuation_provisionsAfresh() {
            var factory = new AsyncFlakyFactory();
            var provider = providerOf(factory);

            var retried = provider.provide(AsyncResource.class, SECTION, contextFor("slice-a"))
                                  .fold(first -> first.isSuccess()
                                                 ? Promise.resolved(first)
                                                 : provider.provide(AsyncResource.class, SECTION, contextFor("slice-a")));

            factory.releaseFailure();

            assertThat(retried.await(TIMEOUT).isSuccess()).isTrue();
            assertThat(factory.attempts.get()).isEqualTo(2);
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

            assertThat(factory.provisioned.getFirst().isClosed()).isTrue();
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
