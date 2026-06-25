// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityValueTest {
    @Nested
    class Fields {
        @Test
        void communityValue_withAllFields_populatesRecord() {
            var v = CommunityValue.communityValue("orders",
                                                  "WORKER",
                                                  5,
                                                  CommunityState.ACTIVE,
                                                  1710072000000L,
                                                  Option.some(1710072500000L));

            assertThat(v.sourceName()).isEqualTo("orders");
            assertThat(v.role()).isEqualTo("WORKER");
            assertThat(v.targetSize()).isEqualTo(5);
            assertThat(v.state()).isEqualTo(CommunityState.ACTIVE);
            assertThat(v.createdAt()).isEqualTo(1710072000000L);
            assertThat(v.dissolvedAt()).isEqualTo(Option.some(1710072500000L));
        }

        @Test
        void communityValue_formingMint_hasFormingStateAndNoDissolvedAt() {
            var v = CommunityValue.communityValue("orders", "WORKER", 3);

            assertThat(v.state()).isEqualTo(CommunityState.FORMING);
            assertThat(v.dissolvedAt().isPresent()).isFalse();
            assertThat(v.createdAt()).isPositive();
        }
    }

    @Nested
    class Canonicalization {
        @Test
        void construct_nullDissolvedAt_normalizesToNone() {
            var v = new CommunityValue("orders", "WORKER", 3, CommunityState.FORMING, 1000L, null);

            assertThat(v.dissolvedAt().isPresent()).isFalse();
        }

        @Test
        void construct_presentDissolvedAt_preserved() {
            var v = new CommunityValue("orders", "WORKER", 3, CommunityState.DISSOLVED, 1000L, Option.some(2000L));

            assertThat(v.dissolvedAt()).isEqualTo(Option.some(2000L));
        }

        @Test
        void construct_nullSourceName_normalizesToEmpty() {
            var v = new CommunityValue(null, "WORKER", 3, CommunityState.FORMING, 1000L, Option.none());

            assertThat(v.sourceName()).isEmpty();
        }

        @Test
        void construct_nullRole_normalizesToEmpty() {
            var v = new CommunityValue("orders", null, 3, CommunityState.FORMING, 1000L, Option.none());

            assertThat(v.role()).isEmpty();
        }

        @Test
        void construct_nullState_normalizesToForming() {
            var v = new CommunityValue("orders", "WORKER", 3, null, 1000L, Option.none());

            assertThat(v.state()).isEqualTo(CommunityState.FORMING);
        }
    }

    @Nested
    class AllStates {
        @Test
        void communityValue_eachState_preservesState() {
            for (var state : CommunityState.values()) {
                var dissolvedAt = state == CommunityState.DISSOLVED
                                  ? Option.some(2000L)
                                  : Option.<Long> none();

                var v = CommunityValue.communityValue("orders", "WORKER", 3, state, 1000L, dissolvedAt);

                assertThat(v.state()).isEqualTo(state);
                assertThat(v.dissolvedAt()).isEqualTo(dissolvedAt);
            }
        }
    }

    @Nested
    class WithState {
        @Test
        void withState_swapsStateAndPreservesEveryOtherField() {
            var original = CommunityValue.communityValue("orders",
                                                         "WORKER",
                                                         5,
                                                         CommunityState.FORMING,
                                                         1710072000000L,
                                                         Option.some(1710072500000L));

            var updated = original.withState(CommunityState.ACTIVE);

            assertThat(updated.state()).isEqualTo(CommunityState.ACTIVE);
            assertThat(updated.sourceName()).isEqualTo(original.sourceName());
            assertThat(updated.role()).isEqualTo(original.role());
            assertThat(updated.targetSize()).isEqualTo(original.targetSize());
            assertThat(updated.createdAt()).isEqualTo(original.createdAt());
            assertThat(updated.dissolvedAt()).isEqualTo(original.dissolvedAt());
        }

        @Test
        void withState_leavesOriginalUnchanged() {
            var original = CommunityValue.communityValue("orders", "WORKER", 3);

            original.withState(CommunityState.DEGRADED);

            assertThat(original.state()).isEqualTo(CommunityState.FORMING);
        }
    }
}
