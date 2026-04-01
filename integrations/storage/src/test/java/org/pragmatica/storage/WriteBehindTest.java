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

class WriteBehindTest {

    private static final byte[] CONTENT_A = "write-behind-alpha".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_B = "write-behind-bravo".getBytes(StandardCharsets.UTF_8);
    private static final long TIER_MAX = 1024 * 1024;

    private MemoryTier fastTier;
    private MemoryTier slowTier;

    @BeforeEach
    void setUp() {
        fastTier = MemoryTier.memoryTier(TIER_MAX, TierLevel.MEMORY);
        slowTier = MemoryTier.memoryTier(TIER_MAX, TierLevel.LOCAL_DISK);
    }

    @Nested
    class WriteThroughTests {

        private StorageInstance instance;

        @BeforeEach
        void setUp() {
            instance = StorageInstance.storageInstance("wt-test", List.of(fastTier, slowTier), WritePolicy.WRITE_THROUGH);
        }

        @AfterEach
        void tearDown() {
            instance.shutdown();
        }

        @Test
        void writeThrough_writesToAllTiers() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(_ -> {
                        assertThat(fastTier.usedBytes()).isGreaterThan(0);
                        assertThat(slowTier.usedBytes()).isGreaterThan(0);
                    });
        }
    }

    @Nested
    class WriteBehindTests {

        private StorageInstance instance;

        @BeforeEach
        void setUp() {
            instance = StorageInstance.storageInstance("wb-test", List.of(fastTier, slowTier), WritePolicy.WRITE_BEHIND);
        }

        @AfterEach
        void tearDown() {
            instance.shutdown();
        }

        @Test
        void writeBehind_writesToFastTierOnly_thenFlushes() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()))
                    .onSuccess(_ -> {
                        assertThat(fastTier.usedBytes()).isGreaterThan(0);
                        waitForFlush();
                        assertThat(slowTier.usedBytes()).isGreaterThan(0);
                    });
        }

        @Test
        void writeBehind_dataReadableFromFastTier_immediately() {
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
        void writeBehind_multipleBlocks_allFlushed() {
            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put A failed: " + c.message()));
            instance.put(CONTENT_B).await()
                    .onFailure(c -> fail("put B failed: " + c.message()));

            waitForFlush();

            assertThat(slowTier.usedBytes()).isGreaterThanOrEqualTo(CONTENT_A.length + CONTENT_B.length);
        }
    }

    @Nested
    class ShutdownTests {

        @Test
        void writeBehind_queueDrainOnShutdown() {
            var instance = StorageInstance.storageInstance("shutdown-test", List.of(fastTier, slowTier), WritePolicy.WRITE_BEHIND);

            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()));

            instance.shutdown();

            assertThat(slowTier.usedBytes()).isGreaterThan(0);
        }

        @Test
        void writeThrough_shutdownIsNoop() {
            var instance = StorageInstance.storageInstance("noop-shutdown", List.of(fastTier, slowTier), WritePolicy.WRITE_THROUGH);

            instance.put(CONTENT_A).await()
                    .onFailure(c -> fail("put failed: " + c.message()));

            instance.shutdown();

            assertThat(fastTier.usedBytes()).isGreaterThan(0);
            assertThat(slowTier.usedBytes()).isGreaterThan(0);
        }
    }

    private static void waitForFlush() {
        try {
            TimeUnit.MILLISECONDS.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
