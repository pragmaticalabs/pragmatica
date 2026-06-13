// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.consensus.NodeId;


import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpokesmanValueTest {
    @Nested
    class Factory {
        @Test
        void spokesmanValue_withFields_populatesRecord() {
            var epoch = Epoch.epoch(3L, 10L);
            var hlc = new HlcTimestamp(50L, new NodeId("core-1"));

            var v = SpokesmanValue.spokesmanValue(List.of("community-a", "community-b"), epoch, hlc, 2L);

            assertThat(v.communities()).containsExactly("community-a", "community-b");
            assertThat(v.assignedEpoch()).isEqualTo(epoch);
            assertThat(v.assignedAt()).isEqualTo(hlc);
            assertThat(v.version()).isEqualTo(2L);
            assertThat(v.status()).isEqualTo(SpokesmanStatus.ASSIGNED);
            assertThat(v.failureReason()).isEmpty();
        }

        @Test
        void construct_nullCommunities_normalizesToEmpty() {
            var v = new SpokesmanValue(null, Epoch.ZERO, HlcTimestamp.ZERO, 0L, SpokesmanStatus.ASSIGNED, "");

            assertThat(v.communities()).isEmpty();
        }

        @Test
        void construct_nullStatus_normalizesToAssigned() {
            var v = new SpokesmanValue(List.of(), Epoch.ZERO, HlcTimestamp.ZERO, 0L, null, "");

            assertThat(v.status()).isEqualTo(SpokesmanStatus.ASSIGNED);
        }

        @Test
        void construct_nullFailureReason_normalizesToEmpty() {
            var v = new SpokesmanValue(List.of(), Epoch.ZERO, HlcTimestamp.ZERO, 0L, SpokesmanStatus.FAILED, null);

            assertThat(v.failureReason()).isEmpty();
        }
    }

    @Nested
    class StatusTransitions {
        @Test
        void withStatus_switchesToGivenStatus() {
            var base = SpokesmanValue.spokesmanValue(List.of("c1"), Epoch.ZERO, HlcTimestamp.ZERO, 1L);

            var active = base.withStatus(SpokesmanStatus.ACTIVE);

            assertThat(active.status()).isEqualTo(SpokesmanStatus.ACTIVE);
            assertThat(active.communities()).isEqualTo(base.communities());
            assertThat(active.version()).isEqualTo(base.version());
        }

        @Test
        void withFailure_setsFailedStatusAndReason() {
            var base = SpokesmanValue.spokesmanValue(List.of("c1"), Epoch.ZERO, HlcTimestamp.ZERO, 1L);

            var failed = base.withFailure("activation timeout");

            assertThat(failed.status()).isEqualTo(SpokesmanStatus.FAILED);
            assertThat(failed.failureReason()).isEqualTo("activation timeout");
        }
    }
}
