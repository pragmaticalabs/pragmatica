package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class WriteBehindIntegrationTest {

    private static final byte[] CONTENT_A = "write-behind-integration-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "write-behind-integration-bravo".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_C = "write-behind-integration-charlie".getBytes(StandardCharsets.UTF_8);
    private static final long TIER_MAX = 1024 * 1024;

    private MemoryTier fastTier;
    private MemoryTier slowTier;

    @BeforeEach
    void setUp() {
        fastTier = MemoryTier.memoryTier(TIER_MAX, TierLevel.MEMORY);
        slowTier = MemoryTier.memoryTier(TIER_MAX, TierLevel.LOCAL_DISK);
    }

    @Nested
    class WriteBehindFlushTests {

        private StorageInstance instance;

        @BeforeEach
        void setUp() {
            instance = StorageInstance.storageInstance("wb-integration", List.of(fastTier, slowTier), WritePolicy.WRITE_BEHIND);
        }

        @AfterEach
        void tearDown() {
            instance.shutdown();
        }

        @Test
        void storageInstance_writeBehind_eventuallyWritesToAllTiers() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(_ -> {
                        assertThat(fastTier.usedBytes()).isGreaterThan(0);
                        waitForFlush();
                        assertThat(slowTier.usedBytes()).isGreaterThan(0);
                    });
        }

        @Test
        void storageInstance_writeBehind_fastTierReadableImmediately() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(id ->
                        instance.get(id).await()
                                .onFailure(c -> fail("get failed: " + c.message()))
                                .onSuccess(opt -> {
                                    assertThat(opt.isPresent()).isTrue();
                                    opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                                })
                    );
        }

        @Test
        void storageInstance_writeBehind_multipleBlocksEventuallyFlushed() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put A failed: " + c.message()));
            instance.put(CONTENT_B).await()
                    .onFailure(c -> fail("put B failed: " + c.message()));
            instance.put(CONTENT_C).await()
                    .onFailure(c -> fail("put C failed: " + c.message()));

            waitForFlush();

            assertThat(slowTier.usedBytes()).isGreaterThanOrEqualTo(CONTENT_A.length + CONTENT_B.length + CONTENT_C.length);
        }

        @Test
        void storageInstance_writeBehind_readFromSlowTierAfterFastTierEviction() {
            var id = instance.put(CONTENT_A).await()
                             .fold(c -> { fail("put failed: " + c.message()); return null; },
                                   blockId -> blockId);

            waitForFlush();
            assertThat(slowTier.usedBytes()).isGreaterThan(0);

            // Remove from fast tier to force waterfall read from slow tier
            fastTier.delete(id).await();

            instance.get(id).await()
                    .onFailure(c -> fail("get from slow tier failed: " + c.message()))
                    .onSuccess(opt -> {
                        assertThat(opt.isPresent()).isTrue();
                        opt.onPresent(data -> assertThat(data).isEqualTo(CONTENT_A));
                    });
        }
    }

    @Nested
    class WriteThroughComparisonTests {

        private StorageInstance instance;

        @BeforeEach
        void setUp() {
            instance = StorageInstance.storageInstance("wt-integration", List.of(fastTier, slowTier), WritePolicy.WRITE_THROUGH);
        }

        @AfterEach
        void tearDown() {
            instance.shutdown();
        }

        @Test
        void storageInstance_writeThrough_writesToAllTiersImmediately() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(_ -> {
                        assertThat(fastTier.usedBytes()).isGreaterThan(0);
                        assertThat(slowTier.usedBytes()).isGreaterThan(0);
                    });
        }

        @Test
        void storageInstance_writeThrough_bothTiersHaveSameData() {
            var id = instance.put(CONTENT_A).await()
                             .fold(c -> { fail("put failed: " + c.message()); return null; },
                                   blockId -> blockId);

            var fastData = fastTier.get(id).await()
                                   .fold(c -> { fail("fast get failed: " + c.message()); return null; },
                                         opt -> opt);
            var slowData = slowTier.get(id).await()
                                   .fold(c -> { fail("slow get failed: " + c.message()); return null; },
                                         opt -> opt);

            assertThat(fastData.isPresent()).isTrue();
            assertThat(slowData.isPresent()).isTrue();
            fastData.onPresent(fd -> slowData.onPresent(sd -> assertThat(fd).isEqualTo(sd)));
        }
    }

    private static void waitForFlush() {
        try {
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
