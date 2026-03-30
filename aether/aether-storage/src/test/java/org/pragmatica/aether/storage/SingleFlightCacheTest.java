package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;

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
