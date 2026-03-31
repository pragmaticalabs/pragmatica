package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.TierAwareRetention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.RetentionPolicy.retentionPolicy;
import static org.pragmatica.aether.slice.TierAwareRetention.tierAwareRetention;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

class TierAwareRetentionTest {

    private static final long CAPACITY = 100;
    private static final long DATA_REGION = 4096;

    private OffHeapRingBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);
    }

    @AfterEach
    void tearDown() {
        buffer.close();
    }

    @Nested
    class AfterSeal {

        @Test
        void applyRetention_evictsMoreAggressively_afterSeal() {
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            // Mark first 30 events as sealed
            buffer.updateLastSealedOffset(29);

            // Tier-aware: keep at most 5 sealed events
            var tierAware = tierAwareRetention(Long.MAX_VALUE, 5);
            var policy = retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, tierAware);

            buffer.applyRetention(policy);

            // Should have evicted sealed events beyond the 5 limit (30 sealed - 5 kept = 25 evicted)
            assertThat(buffer.eventCount()).isEqualTo(25L);
            assertThat(buffer.tailOffset()).isEqualTo(25L);
        }

        @Test
        void applyRetention_evictsSealedByAge_afterSeal() {
            var now = System.currentTimeMillis();

            // Old sealed events
            for (int i = 0; i < 10; i++) {
                buffer.append(("old-" + i).getBytes(), now - 120_000);
            }

            // Recent events (not sealed)
            for (int i = 0; i < 10; i++) {
                buffer.append(("new-" + i).getBytes(), now);
            }

            // Mark first 10 events as sealed
            buffer.updateLastSealedOffset(9);

            // Tier-aware: keep sealed events for at most 60s
            var tierAware = tierAwareRetention(60_000, Long.MAX_VALUE);
            var policy = retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, tierAware);

            buffer.applyRetention(policy);

            // Old sealed events (120s old) should be evicted, recent ones kept
            assertThat(buffer.eventCount()).isEqualTo(10L);
            assertThat(buffer.tailOffset()).isEqualTo(10L);
        }
    }

    @Nested
    class BeforeSeal {

        @Test
        void applyRetention_usesNormalRetention_beforeAnySeal() {
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            // No sealed offset set (default -1)
            assertThat(buffer.lastSealedOffset()).isEqualTo(-1L);

            // Tier-aware configured but should not apply (no sealed events)
            var tierAware = tierAwareRetention(0, 0);
            var policy = retentionPolicy(20, Long.MAX_VALUE, Long.MAX_VALUE, tierAware);

            buffer.applyRetention(policy);

            // Normal retention (maxCount=20) should apply
            assertThat(buffer.eventCount()).isEqualTo(20L);
            assertThat(buffer.tailOffset()).isEqualTo(30L);
        }
    }

    @Nested
    class Disabled {

        @Test
        void applyRetention_usesNormalRetention_whenTierAwareNotConfigured() {
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            buffer.updateLastSealedOffset(29);

            // No tier-aware retention configured (default)
            var policy = retentionPolicy(20, Long.MAX_VALUE, Long.MAX_VALUE);

            buffer.applyRetention(policy);

            // Normal retention only
            assertThat(buffer.eventCount()).isEqualTo(20L);
            assertThat(buffer.tailOffset()).isEqualTo(30L);
        }
    }

    @Nested
    class SealedOffsetTracking {

        @Test
        void updateLastSealedOffset_tracksCorrectly() {
            assertThat(buffer.lastSealedOffset()).isEqualTo(-1L);

            buffer.updateLastSealedOffset(10);
            assertThat(buffer.lastSealedOffset()).isEqualTo(10L);

            buffer.updateLastSealedOffset(25);
            assertThat(buffer.lastSealedOffset()).isEqualTo(25L);
        }

        @Test
        void lastSealedOffset_updatedAutomatically_withEvictionListener() {
            var sealingBuffer = offHeapRingBuffer("test", 0, 5, 1024, (_, _, _) -> {});

            try {
                // Fill beyond capacity to trigger eviction with listener
                for (int i = 0; i < 8; i++) {
                    sealingBuffer.append(("msg-" + i).getBytes(), 1000L + i);
                }

                // The listener was called, so lastSealedOffset should be updated
                assertThat(sealingBuffer.lastSealedOffset()).isGreaterThanOrEqualTo(0L);
            } finally {
                sealingBuffer.close();
            }
        }

        @Test
        void lastSealedOffset_notUpdated_withNoopListener() {
            // Default buffer uses NOOP listener
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            assertThat(buffer.lastSealedOffset()).isEqualTo(-1L);
        }
    }

    @Nested
    class TierAwareRetentionDefaults {

        @Test
        void defaultFactory_creates60sAnd10kLimits() {
            var defaults = tierAwareRetention();

            assertThat(defaults.postSealBufferMs()).isEqualTo(60_000L);
            assertThat(defaults.postSealMaxCount()).isEqualTo(10_000L);
        }

        @Test
        void customFactory_preservesValues() {
            var custom = tierAwareRetention(30_000, 5_000);

            assertThat(custom.postSealBufferMs()).isEqualTo(30_000L);
            assertThat(custom.postSealMaxCount()).isEqualTo(5_000L);
        }
    }

    @Nested
    class RetentionPolicyIntegration {

        @Test
        void retentionPolicy_defaultsToNoTierAware() {
            var policy = retentionPolicy();

            assertThat(policy.tierAwareRetention().isEmpty()).isTrue();
        }

        @Test
        void retentionPolicy_threeArgFactory_hasNoTierAware() {
            var policy = retentionPolicy(100, 1024, 60_000);

            assertThat(policy.tierAwareRetention().isEmpty()).isTrue();
        }

        @Test
        void retentionPolicy_fourArgFactory_hasTierAware() {
            var tierAware = tierAwareRetention(30_000, 5_000);
            var policy = retentionPolicy(100, 1024, 60_000, tierAware);

            assertThat(policy.tierAwareRetention().isPresent()).isTrue();
        }
    }
}
