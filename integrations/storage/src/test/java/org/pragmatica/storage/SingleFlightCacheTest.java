package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
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
