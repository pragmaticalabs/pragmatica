// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpochTest {
    @Nested
    class Factory {
        @Test
        void epoch_withValues_producesRecordWithFields() {
            var e = Epoch.epoch(7L, 42L);

            assertThat(e.rabiaTerm()).isEqualTo(7L);
            assertThat(e.localCounter()).isEqualTo(42L);
        }

        @Test
        void zero_constant_hasZeroTermAndCounter() {
            assertThat(Epoch.ZERO.rabiaTerm()).isZero();
            assertThat(Epoch.ZERO.localCounter()).isZero();
        }
    }

    @Nested
    class Comparison {
        @Test
        void compareTo_sameValues_returnsZero() {
            assertThat(Epoch.epoch(3L, 5L).compareTo(Epoch.epoch(3L, 5L))).isZero();
        }

        @Test
        void compareTo_lowerTerm_returnsNegative() {
            assertThat(Epoch.epoch(2L, 99L).compareTo(Epoch.epoch(3L, 0L))).isNegative();
        }

        @Test
        void compareTo_higherTerm_returnsPositive() {
            assertThat(Epoch.epoch(5L, 0L).compareTo(Epoch.epoch(4L, 99L))).isPositive();
        }

        @Test
        void compareTo_sameTermLowerCounter_returnsNegative() {
            assertThat(Epoch.epoch(3L, 5L).compareTo(Epoch.epoch(3L, 6L))).isNegative();
        }

        @Test
        void compareTo_sameTermHigherCounter_returnsPositive() {
            assertThat(Epoch.epoch(3L, 7L).compareTo(Epoch.epoch(3L, 6L))).isPositive();
        }

        @Test
        void isAtLeast_equal_returnsTrue() {
            assertThat(Epoch.epoch(3L, 5L).isAtLeast(Epoch.epoch(3L, 5L))).isTrue();
        }

        @Test
        void isAtLeast_greater_returnsTrue() {
            assertThat(Epoch.epoch(3L, 6L).isAtLeast(Epoch.epoch(3L, 5L))).isTrue();
        }

        @Test
        void isAtLeast_less_returnsFalse() {
            assertThat(Epoch.epoch(3L, 4L).isAtLeast(Epoch.epoch(3L, 5L))).isFalse();
        }

        @Test
        void isStrictlyAfter_greater_returnsTrue() {
            assertThat(Epoch.epoch(4L, 0L).isStrictlyAfter(Epoch.epoch(3L, 99L))).isTrue();
        }

        @Test
        void isStrictlyAfter_equal_returnsFalse() {
            assertThat(Epoch.epoch(3L, 5L).isStrictlyAfter(Epoch.epoch(3L, 5L))).isFalse();
        }
    }

    @Nested
    class Mutations {
        @Test
        void nextCounter_bumpsLocalCounterOnly() {
            var next = Epoch.epoch(7L, 3L).nextCounter();

            assertThat(next.rabiaTerm()).isEqualTo(7L);
            assertThat(next.localCounter()).isEqualTo(4L);
        }

        @Test
        void withTerm_setsNewTermAndResetsCounter() {
            var changed = Epoch.epoch(3L, 99L).withTerm(10L);

            assertThat(changed.rabiaTerm()).isEqualTo(10L);
            assertThat(changed.localCounter()).isZero();
        }

        @Test
        void nextCounter_keepsMonotonicOrdering() {
            var base = Epoch.epoch(5L, 0L);
            var next = base.nextCounter();

            assertThat(next.isStrictlyAfter(base)).isTrue();
        }
    }

    @Nested
    class StringRepresentation {
        @Test
        void toString_formatsTermColonCounter() {
            assertThat(Epoch.epoch(7L, 42L).toString()).isEqualTo("7:42");
        }

        @Test
        void toString_zero_formatsZeroColonZero() {
            assertThat(Epoch.ZERO.toString()).isEqualTo("0:0");
        }
    }
}
