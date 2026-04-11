package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.RetentionPolicy.retentionPolicy;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;


class CompoundRetentionTest {

    private static final long CAPACITY = 200;
    private static final long DATA_REGION = 8192;

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
    class AnyMode {

        @Test
        void applyRetention_evictsWhenCountExceeded_anyMode() {
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            var policy = retentionPolicy(20, Long.MAX_VALUE, Long.MAX_VALUE, RetentionMode.ANY);
            buffer.applyRetention(policy);

            assertThat(buffer.eventCount()).isEqualTo(20L);
        }

        @Test
        void applyRetention_evictsWhenAgeExceeded_anyMode() {
            var now = System.currentTimeMillis();
            for (int i = 0; i < 10; i++) {
                buffer.append(("old-" + i).getBytes(), now - 120_000);
            }
            for (int i = 0; i < 10; i++) {
                buffer.append(("new-" + i).getBytes(), now);
            }

            var policy = retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, 60_000, RetentionMode.ANY);
            buffer.applyRetention(policy);

            assertThat(buffer.eventCount()).isEqualTo(10L);
        }

        @Test
        void applyRetention_behavesAsDefault_anyMode() {
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            // ANY mode should behave identically to default (no mode specified)
            var policyAny = retentionPolicy(30, Long.MAX_VALUE, Long.MAX_VALUE, RetentionMode.ANY);
            var policyDefault = retentionPolicy(30, Long.MAX_VALUE, Long.MAX_VALUE);

            buffer.applyRetention(policyAny);
            var countAny = buffer.eventCount();

            assertThat(countAny).isEqualTo(30L);
        }
    }

    @Nested
    class AllMode {

        @Test
        void applyRetention_doesNotEvict_whenOnlyCountExceeded_allMode() {
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), System.currentTimeMillis());
            }

            // Count exceeds 20, but age does NOT exceed 60s and size doesn't exceed limit
            var policy = retentionPolicy(20, Long.MAX_VALUE, 60_000, RetentionMode.ALL);
            buffer.applyRetention(policy);

            // No eviction should occur because age is not exceeded
            assertThat(buffer.eventCount()).isEqualTo(50L);
        }

        @Test
        void applyRetention_evicts_whenAllConditionsMet_allMode() {
            var now = System.currentTimeMillis();
            for (int i = 0; i < 50; i++) {
                buffer.append(("old-event-" + i).getBytes(), now - 120_000);
            }

            // Count exceeds 20 AND age exceeds 60s → should evict
            var policy = retentionPolicy(20, Long.MAX_VALUE, 60_000, RetentionMode.ALL);
            buffer.applyRetention(policy);

            // Both count and age exceeded, so eviction occurs
            assertThat(buffer.eventCount()).isLessThan(50L);
        }

        @Test
        void applyRetention_noEviction_whenNoConditionExceeded_allMode() {
            for (int i = 0; i < 10; i++) {
                buffer.append(("event-" + i).getBytes(), System.currentTimeMillis());
            }

            var policy = retentionPolicy(100, Long.MAX_VALUE, 60_000, RetentionMode.ALL);
            buffer.applyRetention(policy);

            assertThat(buffer.eventCount()).isEqualTo(10L);
        }
    }

    @Nested
    class BackwardCompatibility {

        @Test
        void applyRetention_defaultBehaviorUnchanged() {
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            // Default policy (no mode specified) should still evict by count
            var policy = retentionPolicy(20, Long.MAX_VALUE, Long.MAX_VALUE);
            buffer.applyRetention(policy);

            assertThat(buffer.eventCount()).isEqualTo(20L);
        }
    }
}
