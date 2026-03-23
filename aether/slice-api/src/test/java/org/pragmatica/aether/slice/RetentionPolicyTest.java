package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.RetentionPolicy.retentionPolicy;

class RetentionPolicyTest {

    @Nested
    class DefaultFactory {

        @Test
        void maxCount_is100K() {
            assertThat(retentionPolicy().maxCount()).isEqualTo(100_000);
        }

        @Test
        void maxBytes_is256MB() {
            assertThat(retentionPolicy().maxBytes()).isEqualTo(256 * 1024 * 1024L);
        }

        @Test
        void maxAgeMs_is24Hours() {
            assertThat(retentionPolicy().maxAgeMs()).isEqualTo(24 * 60 * 60 * 1000L);
        }
    }

    @Nested
    class CustomFactory {

        @Test
        void customValues_arePreserved() {
            var policy = retentionPolicy(500, 1024L, 60_000L);

            assertThat(policy.maxCount()).isEqualTo(500);
            assertThat(policy.maxBytes()).isEqualTo(1024L);
            assertThat(policy.maxAgeMs()).isEqualTo(60_000L);
        }
    }
}
