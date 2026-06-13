// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

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

        @Test
        void mode_defaultsToAny() {
            assertThat(retentionPolicy().mode()).isEqualTo(RetentionMode.ANY);
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

        @Test
        void customMode_isPreserved() {
            var policy = retentionPolicy(500, 1024L, 60_000L, RetentionMode.ALL);

            assertThat(policy.mode()).isEqualTo(RetentionMode.ALL);
        }
    }

    @Nested
    class ShouldEvictAnyMode {

        @Test
        void shouldEvict_true_whenCountExceeded() {
            var policy = retentionPolicy(100, Long.MAX_VALUE, Long.MAX_VALUE);

            assertThat(policy.shouldEvict(101, 0, 0)).isTrue();
        }

        @Test
        void shouldEvict_true_whenBytesExceeded() {
            var policy = retentionPolicy(Long.MAX_VALUE, 1024, Long.MAX_VALUE);

            assertThat(policy.shouldEvict(0, 1025, 0)).isTrue();
        }

        @Test
        void shouldEvict_true_whenAgeExceeded() {
            var policy = retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, 60_000);

            assertThat(policy.shouldEvict(0, 0, 60_001)).isTrue();
        }

        @Test
        void shouldEvict_false_whenNothingExceeded() {
            var policy = retentionPolicy(100, 1024, 60_000);

            assertThat(policy.shouldEvict(50, 512, 30_000)).isFalse();
        }
    }

    @Nested
    class ShouldEvictAllMode {

        @Test
        void shouldEvict_false_whenOnlyCountExceeded() {
            var policy = retentionPolicy(100, 1024, 60_000, RetentionMode.ALL);

            assertThat(policy.shouldEvict(101, 512, 30_000)).isFalse();
        }

        @Test
        void shouldEvict_false_whenOnlyTwoExceeded() {
            var policy = retentionPolicy(100, 1024, 60_000, RetentionMode.ALL);

            assertThat(policy.shouldEvict(101, 1025, 30_000)).isFalse();
        }

        @Test
        void shouldEvict_true_whenAllExceeded() {
            var policy = retentionPolicy(100, 1024, 60_000, RetentionMode.ALL);

            assertThat(policy.shouldEvict(101, 1025, 60_001)).isTrue();
        }

        @Test
        void shouldEvict_true_whenAllExceeded_andUnconfiguredDimensionsIgnored() {
            var policy = retentionPolicy(100, Long.MAX_VALUE, 60_000, RetentionMode.ALL);

            // maxBytes is MAX_VALUE (unconfigured), so only count + age need to exceed
            assertThat(policy.shouldEvict(101, 0, 60_001)).isTrue();
        }

        @Test
        void shouldEvict_false_whenNothingExceeded() {
            var policy = retentionPolicy(100, 1024, 60_000, RetentionMode.ALL);

            assertThat(policy.shouldEvict(50, 512, 30_000)).isFalse();
        }
    }

    @Nested
    class BackwardCompatibility {

        @Test
        void threeArgFactory_defaultsToAnyMode() {
            var policy = retentionPolicy(500, 1024L, 60_000L);

            assertThat(policy.mode()).isEqualTo(RetentionMode.ANY);
        }

        @Test
        void tierAwareFactory_defaultsToAnyMode() {
            var tierAware = TierAwareRetention.tierAwareRetention();
            var policy = retentionPolicy(500, 1024L, 60_000L, tierAware);

            assertThat(policy.mode()).isEqualTo(RetentionMode.ANY);
        }
    }
}
