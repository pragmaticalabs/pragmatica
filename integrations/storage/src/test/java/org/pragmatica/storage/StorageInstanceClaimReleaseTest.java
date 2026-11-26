package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Regression coverage for the claim-not-released bug: a failed durable-tier write
/// must release the block claim so that a retry actually re-writes the block,
/// instead of short-circuiting to deduplication and never persisting the content.
class StorageInstanceClaimReleaseTest {

    private static final byte[] CONTENT = "claim-release-regression".getBytes(StandardCharsets.UTF_8);
    private static final long MEMORY_MAX = 1024 * 1024;

    private MemoryTier cacheTier;
    private FailFirstTier durableTier;
    private StorageInstance instance;

    @BeforeEach
    void setUp() {
        cacheTier = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.MEMORY);
        durableTier = new FailFirstTier();
        instance = StorageInstance.storageInstance("claim-release-instance", List.of(cacheTier, durableTier));
    }

    @Test
    void put_afterFailedWrite_retrySucceedsAndPersists() {
        instance.put(CONTENT).await()
                .onSuccess(_ -> fail("first put should fail under durable-tier backpressure"));

        var retry = instance.put(CONTENT).await();

        retry.onFailure(c -> fail("retry put should succeed after claim release: " + c.message()))
             .onSuccess(id ->
                 instance.get(id).await()
                         .onFailure(c -> fail("get failed: " + c.message()))
                         .onSuccess(opt -> {
                             assertThat(opt.isPresent())
                                 .as("retry must durably persist the block (claim was released)")
                                 .isTrue();
                             opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT));
                         })
             );

        assertThat(durableTier.putCount()).isEqualTo(2);
    }

    /// Durable tier that fails the first put and succeeds on every subsequent put.
    private static final class FailFirstTier implements StorageTier {
        private final MemoryTier backing = MemoryTier.memoryTier(MEMORY_MAX, TierLevel.REMOTE);
        private final AtomicInteger putCount = new AtomicInteger();

        int putCount() {
            return putCount.get();
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return backing.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return putCount.incrementAndGet() == 1
                   ? StorageError.WriteError.writeError("induced durable-tier backpressure").promise()
                   : backing.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return backing.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return backing.exists(id);
        }

        @Override
        public TierLevel level() {
            return TierLevel.REMOTE;
        }

        @Override
        public long usedBytes() {
            return backing.usedBytes();
        }

        @Override
        public long maxBytes() {
            return backing.maxBytes();
        }
    }
}
