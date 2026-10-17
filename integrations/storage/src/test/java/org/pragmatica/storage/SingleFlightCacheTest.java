package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class SingleFlightCacheTest {

    private SingleFlightCache cache;

    @BeforeEach
    void setUp() {
        cache = SingleFlightCache.singleFlightCache();
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    @Nested
    class DeduplicationTests {

        @Test
        void deduplicate_singleCall_executesLoader() {
            var id = blockIdOf("test".getBytes(StandardCharsets.UTF_8));
            var data = "payload".getBytes(StandardCharsets.UTF_8);
            var callCount = new AtomicInteger(0);

            var result = cache.deduplicate(id, () -> loaderWithCounter(data, callCount));

            result.await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> {
                      assertThat(opt.isPresent()).isTrue();
                      opt.onPresent(bytes -> assertThat(bytes).isEqualTo(data));
                  });

            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void deduplicate_concurrentCalls_sharesPromise() {
            var id = blockIdOf("shared".getBytes(StandardCharsets.UTF_8));
            var data = "shared-data".getBytes(StandardCharsets.UTF_8);
            var callCount = new AtomicInteger(0);

            Promise<Option<byte[]>> slowPromise = Promise.promise();

            var first = cache.deduplicate(id, () -> delayedLoaderWithCounter(slowPromise, data, callCount));
            var second = cache.deduplicate(id, () -> loaderWithCounter(data, callCount));

            assertThat(first).isSameAs(second);
            assertThat(callCount.get()).isEqualTo(1);

            slowPromise.succeed(some(data));
        }

        @Test
        void deduplicate_afterCompletion_allowsNewLoad() {
            var id = blockIdOf("reload".getBytes(StandardCharsets.UTF_8));
            var data = "data".getBytes(StandardCharsets.UTF_8);
            var callCount = new AtomicInteger(0);

            cache.deduplicate(id, () -> loaderWithCounter(data, callCount)).await();
            assertThat(callCount.get()).isEqualTo(1);

            cache.deduplicate(id, () -> loaderWithCounter(data, callCount)).await();
            assertThat(callCount.get()).isEqualTo(2);
        }
    }

    @Nested
    class HungLoaderEvictionTests {

        private static final TimeSpan SHORT_BOUND = timeSpan(150).millis();

        /// A loader that never resolves must NOT poison its BlockId forever. The in-flight
        /// entry carries an aggregate timeout; once it fires the entry is evicted via
        /// onResultRun, so the FIRST caller's promise resolves with a Timeout failure (rather
        /// than hanging) and a SUBSEQUENT caller starts a fresh load.
        @Test
        void deduplicate_hungLoader_evictedAfterBoundAndNextCallerFresh() {
            var bounded = SingleFlightCache.singleFlightCache(SHORT_BOUND);
            var id = blockIdOf("hung".getBytes(StandardCharsets.UTF_8));
            var hungCalls = new AtomicInteger(0);

            // First caller: loader never resolves → must surface a Timeout, not hang.
            bounded.deduplicate(id, () -> hangingLoaderWithCounter(hungCalls))
                   .await(timeSpan(5).seconds())
                   .onSuccessRun(() -> fail("Expected timeout failure for hung loader"))
                   .onFailure(c -> assertThat(c).isInstanceOf(CoreError.Timeout.class));

            assertThat(hungCalls.get()).isEqualTo(1);

            // Entry must have been evicted on timeout → a fresh, healthy load now runs.
            var data = "fresh".getBytes(StandardCharsets.UTF_8);
            var freshCalls = new AtomicInteger(0);

            bounded.deduplicate(id, () -> loaderWithCounter(data, freshCalls))
                   .await(timeSpan(5).seconds())
                   .onFailure(c -> fail("Expected fresh load to succeed: " + c.message()))
                   .onSuccess(opt -> opt.onPresent(bytes -> assertThat(bytes).isEqualTo(data)));

            assertThat(freshCalls.get()).isEqualTo(1);
        }

        /// Deterministic proof of the eviction race, independent of ambient scheduling: eviction
        /// removes the map entry via `onResultRun`, which `Promise` dispatches on the general
        /// virtual-thread carrier pool — a DIFFERENT path than the timeout firing itself, which
        /// runs on a dedicated platform-thread scheduler and is unaffected by carrier starvation.
        /// Saturating every carrier before the bound fires guarantees the cleanup task is queued
        /// (not yet run) at the moment the next caller arrives, forcing the exact interleaving a
        /// wall-clock race can only hit by luck. Without the `isResolved` check in `deduplicate`,
        /// the next caller would inherit the stale Timeout instead of a fresh load; this fails
        /// deterministically against the pre-fix `computeIfAbsent` and passes deterministically
        /// against the fix.
        ///
        /// Occupiers busy-spin rather than park on a CountDownLatch/lock: blocking on a
        /// java.util.concurrent synchronizer UNMOUNTS a virtual thread from its carrier (the JVM
        /// frees the carrier for other work while parked), which would defeat the whole point —
        /// the carrier must stay genuinely held, not merely "a virtual thread is waiting".
        ///
        /// `carrierCount` must equal the ACTUAL carrier parallelism, not exceed it: a busy-spinning
        /// virtual thread never yields its carrier, so once `parallelism` occupiers have mounted,
        /// any additional occupier can never be scheduled at all — the whole point (every carrier
        /// held) is reached exactly at that count, and oversubscribing past it self-deadlocks the
        /// setup rather than the intended target code.
        @Test
        void deduplicate_hungLoader_nextCallerFreshEvenWithCleanupQueuedBehindSaturatedCarriers() throws InterruptedException {
            var bounded = SingleFlightCache.singleFlightCache(SHORT_BOUND);
            var id = blockIdOf("hung-starved".getBytes(StandardCharsets.UTF_8));
            var hungCalls = new AtomicInteger(0);

            int carrierCount = Integer.getInteger("jdk.virtualThreadScheduler.parallelism",
                                                   Runtime.getRuntime().availableProcessors());
            var released = new AtomicBoolean(false);
            var started = new CountDownLatch(carrierCount);
            var occupiers = new Thread[carrierCount];

            for (int i = 0; i < carrierCount; i++) {
                occupiers[i] = Thread.ofVirtual().start(() -> {
                    started.countDown();
                    while (!released.get()) {
                        Thread.onSpinWait();
                    }
                });
            }

            try {
                assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("every virtual-thread carrier must be occupied before the bound fires")
                    .isTrue();

                // First caller: loader never resolves → surfaces a Timeout once the bound fires.
                // The cleanup this triggers cannot run yet — every carrier is held by a spinning
                // occupier.
                bounded.deduplicate(id, () -> hangingLoaderWithCounter(hungCalls))
                       .await(timeSpan(5).seconds())
                       .onSuccessRun(() -> fail("Expected timeout failure for hung loader"))
                       .onFailure(c -> assertThat(c).isInstanceOf(CoreError.Timeout.class));

                // Second caller, arriving while the cleanup is still queued behind the occupiers:
                // must get a fresh load, not the stale already-resolved entry.
                var data = "fresh".getBytes(StandardCharsets.UTF_8);
                var freshCalls = new AtomicInteger(0);

                bounded.deduplicate(id, () -> loaderWithCounter(data, freshCalls))
                       .await(timeSpan(5).seconds())
                       .onFailure(c -> fail("Expected fresh load to succeed: " + c.message()))
                       .onSuccess(opt -> opt.onPresent(bytes -> assertThat(bytes).isEqualTo(data)));

                assertThat(freshCalls.get()).isEqualTo(1);
            } finally {
                released.set(true);
                for (var occupier : occupiers) {
                    occupier.join();
                }
            }
        }

        /// Pins the single-flight guarantee ACROSS an eviction, not just the freshness of what a
        /// caller receives. `deduplicate`'s cleanup fires by (id, promise) — the atomic
        /// compare-and-remove — specifically so a settled promise A's delayed cleanup cannot
        /// clobber a live promise B that has since taken A's map slot (a resolved entry is treated
        /// as absent, so supersession is legitimate). A key-only `remove(id)` would delete
        /// whatever currently occupies the slot regardless of identity.
        ///
        /// Sequence, using the same carrier-saturation technique as the test above for
        /// deterministic ordering: (1) A is driven to its Timeout while every carrier is held, so
        /// A's cleanup is enqueued but cannot run; (2) B is created for the same id — legal,
        /// since A now reads as resolved — and kept deliberately UNRESOLVED (a manually-controlled
        /// promise), so it is still live when A's cleanup finally executes; (3) an observer chained
        /// onto A's own promise BEFORE it resolves lets us wait for "A's cleanup has actually run"
        /// without reaching into cache internals — same-promise `onResultRun` completions dispatch
        /// as one batch in registration order (`PromiseImpl.processActions`/`runEventHandlers`), so
        /// an observer registered after the cache's own cleanup is guaranteed to fire strictly
        /// after it; (4) carriers are released and we wait on that observer; (5) a third caller
        /// must JOIN the still-live B, and B's own loader must not have been invoked a second time.
        /// Red against `remove(id)` (the third caller starts a duplicate load C instead of joining
        /// B), green against `remove(id, promise)`.
        @Test
        void deduplicate_liveSuccessorSurvives_predecessorsDelayedCleanup() throws InterruptedException {
            var bounded = SingleFlightCache.singleFlightCache(SHORT_BOUND);
            var id = blockIdOf("cleanup-clobber".getBytes(StandardCharsets.UTF_8));
            var hungCallsA = new AtomicInteger(0);

            int carrierCount = Integer.getInteger("jdk.virtualThreadScheduler.parallelism",
                                                   Runtime.getRuntime().availableProcessors());
            var released = new AtomicBoolean(false);
            var started = new CountDownLatch(carrierCount);
            var occupiers = new Thread[carrierCount];

            for (int i = 0; i < carrierCount; i++) {
                occupiers[i] = Thread.ofVirtual().start(() -> {
                    started.countDown();
                    while (!released.get()) {
                        Thread.onSpinWait();
                    }
                });
            }

            try {
                assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("every virtual-thread carrier must be occupied before A's bound fires")
                    .isTrue();

                // A: hung loader, resolved via the bound to a Timeout. Registered while still
                // unresolved, so this observer shares the same async dispatch batch as the cache's
                // own cleanup and is guaranteed to run strictly after it (registration order).
                var aPromise = bounded.deduplicate(id, () -> hangingLoaderWithCounter(hungCallsA));
                var cleanupAObserved = new CountDownLatch(1);
                aPromise.onResultRun(cleanupAObserved::countDown);

                aPromise.await(timeSpan(5).seconds())
                        .onSuccessRun(() -> fail("Expected timeout failure for hung loader"))
                        .onFailure(c -> assertThat(c).isInstanceOf(CoreError.Timeout.class));

                // B: a fresh, deliberately unresolved load for the same id, created while A's
                // cleanup is still queued behind the occupiers (A reads as resolved, so this is a
                // legitimate supersession, not a bug).
                Promise<Option<byte[]>> controlledB = Promise.promise();
                var bData = "b-data".getBytes(StandardCharsets.UTF_8);
                var bCalls = new AtomicInteger(0);

                var bPromise = bounded.deduplicate(id, () -> delayedLoaderWithCounter(controlledB, bData, bCalls));

                assertThat(bPromise).isNotSameAs(aPromise);
                assertThat(bCalls.get()).isEqualTo(1);

                // Release the carriers so A's queued cleanup can finally run, and wait for proof
                // that it actually has — not just that we asked it to.
                released.set(true);
                for (var occupier : occupiers) {
                    occupier.join();
                }
                assertThat(cleanupAObserved.await(5, TimeUnit.SECONDS))
                    .as("A's delayed cleanup must actually execute before checking for clobbering")
                    .isTrue();

                // A's cleanup ran. With a key-only remove(id) it would have deleted B's still-live
                // entry; a further caller must instead JOIN B, and B's loader must not run twice.
                var cCalls = new AtomicInteger(0);
                var thirdCallPromise = bounded.deduplicate(id, () -> loaderWithCounter("c-data".getBytes(StandardCharsets.UTF_8), cCalls));

                assertThat(thirdCallPromise)
                    .as("a caller arriving after A's delayed cleanup must join the still-live B, not start a third load")
                    .isSameAs(bPromise);
                assertThat(cCalls.get())
                    .as("the single-flight guarantee is broken if a third load was started")
                    .isZero();
                assertThat(bCalls.get())
                    .as("B's loader must not have been invoked again")
                    .isEqualTo(1);

                controlledB.succeed(some(bData));

                bPromise.await(timeSpan(5).seconds())
                        .onFailure(c -> fail("Expected B's load to succeed: " + c.message()))
                        .onSuccess(opt -> opt.onPresent(bytes -> assertThat(bytes).isEqualTo(bData)));
            } finally {
                released.set(true);
                for (var occupier : occupiers) {
                    occupier.join();
                }
            }
        }

        /// Dedup semantics for healthy concurrent callers are unchanged by the bound: a slow
        /// (but resolving) load is shared by joiners, and once it completes the entry is
        /// released so the next call reloads — with NO bound-induced extra loader invocations.
        @Test
        void deduplicate_healthyConcurrentLoad_sharesPromiseUnderBound() {
            var bounded = SingleFlightCache.singleFlightCache(SHORT_BOUND);
            var id = blockIdOf("shared-bounded".getBytes(StandardCharsets.UTF_8));
            var data = "shared-data".getBytes(StandardCharsets.UTF_8);
            var callCount = new AtomicInteger(0);

            Promise<Option<byte[]>> slowPromise = Promise.promise();

            var first = bounded.deduplicate(id, () -> delayedLoaderWithCounter(slowPromise, data, callCount));
            var second = bounded.deduplicate(id, () -> loaderWithCounter(data, callCount));

            assertThat(first).isSameAs(second);
            assertThat(callCount.get()).isEqualTo(1);

            slowPromise.succeed(some(data));

            first.await(timeSpan(5).seconds())
                 .onFailure(c -> fail("Expected shared load to succeed: " + c.message()))
                 .onSuccess(opt -> opt.onPresent(bytes -> assertThat(bytes).isEqualTo(data)));
        }
    }

    private static Promise<Option<byte[]>> hangingLoaderWithCounter(AtomicInteger counter) {
        counter.incrementAndGet();
        return Promise.promise();  // never resolves
    }

    private static Promise<Option<byte[]>> loaderWithCounter(byte[] data, AtomicInteger counter) {
        counter.incrementAndGet();
        return Promise.success(some(data));
    }

    private static Promise<Option<byte[]>> delayedLoaderWithCounter(
        Promise<Option<byte[]>> controlled, byte[] data, AtomicInteger counter
    ) {
        counter.incrementAndGet();
        return controlled;
    }
}
