package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Async-callback probe for the lifecycle bookkeeping of [StorageInstance]. The metadata a caller reads right after an
/// operation resolves (that the block is gone, that a cache tier now holds it, that a read counted as an access) is
/// written by the operation's own continuation, so it has to land before the caller's. The tiers here answer LATER, from
/// another thread, as a disk or network tier does; tiers that answer inline resolve the promise on the caller's thread
/// and run a detached callback inline, hiding the race. Each scenario repeats, because a detached callback loses the race
/// most of the time, not every time.
class StorageInstanceLateTierTest {
    private static final int TRIALS = 20;
    private static final long ANSWER_DELAY_MS = 2;

    private final MetadataStore metadata = MetadataStore.inMemoryMetadataStore("late-tiers");
    private final StorageInstance storage = StorageInstance.storageInstance("late-tiers",
                                                                            List.of(new LateTier(MemoryTier.memoryTier(1024 * 1024)),
                                                                                    new LateTier(MemoryTier.memoryTier(1024 * 1024,
                                                                                                                       TierLevel.LOCAL_DISK))),
                                                                            metadata);

    @Test
    void delete_removesBlockBeforeContinuation_whenTiersAnswerLaterOnAnotherThread() {
        var stillThere = new AtomicInteger();

        for (var trial = 0; trial < TRIALS; trial++) {
            var id = putAndAwait("delete-" + trial);

            storage.delete(id).await().onFailure(cause -> fail(cause.message()));
            stillThere.addAndGet(storage.exists(id).await().fold(_ -> 1, present -> present ? 1 : 0));
        }

        assertThat(stillThere.get()).as("trials where the caller still found the block right after its delete resolved")
                                    .isZero();
    }

    @Test
    void put_recordsCacheTierPresenceBeforeContinuation_whenTiersAnswerLaterOnAnotherThread() {
        var missing = new AtomicInteger();

        for (var trial = 0; trial < TRIALS; trial++) {
            var id = putAndAwait("promote-" + trial);

            missing.addAndGet(metadata.getLifecycle(id).filter(lc -> lc.presentIn().contains(TierLevel.MEMORY)).isPresent() ? 0 : 1);
        }

        assertThat(missing.get()).as("trials where the caller did not yet see the cache tier recorded as holding the block")
                                 .isZero();
    }

    @Test
    void get_recordsAccessBeforeContinuation_whenTiersAnswerLaterOnAnotherThread() {
        var uncounted = new AtomicInteger();

        for (var trial = 0; trial < TRIALS; trial++) {
            var id = putAndAwait("access-" + trial);
            var before = accessCount(id);

            storage.get(id).await().onFailure(cause -> fail(cause.message()));
            uncounted.addAndGet(accessCount(id) > before ? 0 : 1);
        }

        assertThat(uncounted.get()).as("trials where the caller's read had not yet been counted as an access")
                                   .isZero();
    }

    private int accessCount(BlockId id) {
        return metadata.getLifecycle(id).map(BlockLifecycle::accessCount).or(-1);
    }

    private BlockId putAndAwait(String content) {
        return storage.put(content.getBytes(StandardCharsets.UTF_8))
                      .await()
                      .fold(cause -> {
                                fail(cause.message());
                                return null;
                            },
                            id -> id);
    }

    /// A tier whose every answer is produced on another thread after a short delay.
    private record LateTier(StorageTier delegate) implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return later(() -> delegate.get(id));
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return later(() -> delegate.put(id, content));
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return later(() -> delegate.delete(id));
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return later(() -> delegate.exists(id));
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }

        @Override
        public boolean isShared() {
            return delegate.isShared();
        }

        @Override
        public boolean isDurable() {
            return delegate.isDurable();
        }

        private static <T> Promise<T> later(Supplier<Promise<T>> call) {
            var promise = Promise.<T> promise();

            Thread.ofVirtual()
                  .start(() -> answerAfterDelay(promise, call));

            return promise;
        }

        private static <T> void answerAfterDelay(Promise<T> promise, Supplier<Promise<T>> call) {
            try {
                Thread.sleep(ANSWER_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            call.get().onResult(promise::resolve);
        }
    }
}
