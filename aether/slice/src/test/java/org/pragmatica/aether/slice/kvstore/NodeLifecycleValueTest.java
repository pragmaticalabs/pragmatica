// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.hlc.HlcTimestamp;

import static org.assertj.core.api.Assertions.assertThat;

class NodeLifecycleValueTest {
    @Nested
    class BackwardCompatibleFactories {
        @Test
        void nodeLifecycleValue_stateOnly_hasZeroEpochAndTransition() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY);

            assertThat(v.state()).isEqualTo(NodeLifecycleState.ON_DUTY);
            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
            assertThat(v.transitionedAt()).isEqualTo(HlcTimestamp.ZERO);
            assertThat(v.host()).isEmpty();
            assertThat(v.port()).isZero();
        }

        @Test
        void nodeLifecycleValue_stateAndUpdatedAt_preservesTimestamp() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, 1710072000000L);

            assertThat(v.state()).isEqualTo(NodeLifecycleState.DRAINING);
            assertThat(v.updatedAt()).isEqualTo(1710072000000L);
            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
            assertThat(v.transitionedAt()).isEqualTo(HlcTimestamp.ZERO);
        }

        @Test
        void nodeLifecycleValue_stateHostPort_populatesAddress() {
            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "10.0.0.1", 7301);

            assertThat(v.host()).isEqualTo("10.0.0.1");
            assertThat(v.port()).isEqualTo(7301);
            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
            assertThat(v.transitionedAt()).isEqualTo(HlcTimestamp.ZERO);
        }
    }

    @Nested
    class NewFactory {
        @Test
        void nodeLifecycleValue_withAllFields_preservesEpochAndTransition() {
            var epoch = Epoch.epoch(5L, 12L);
            var hlc = new HlcTimestamp(100L, "core-1");

            var v = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING,
                                                          1710072000000L,
                                                          "10.0.0.2",
                                                          7302,
                                                          epoch,
                                                          hlc);

            assertThat(v.observedCoreEpoch()).isEqualTo(epoch);
            assertThat(v.transitionedAt()).isEqualTo(hlc);
        }

        @Test
        void construct_nullHost_normalizesToEmpty() {
            var v = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY, 0L, null, 0, Epoch.ZERO, HlcTimestamp.ZERO);

            assertThat(v.host()).isEmpty();
        }

        @Test
        void construct_nullEpoch_normalizesToZero() {
            var v = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY, 0L, "", 0, null, HlcTimestamp.ZERO);

            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
        }

        @Test
        void construct_nullHlcTimestamp_normalizesToZero() {
            var v = new NodeLifecycleValue(NodeLifecycleState.ON_DUTY, 0L, "", 0, Epoch.ZERO, null);

            assertThat(v.transitionedAt()).isEqualTo(HlcTimestamp.ZERO);
        }
    }

    @Nested
    class WithStateTransitions {
        @Test
        void withState_sameState_preservesTransitionedAt() {
            var original = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                 1000L,
                                                                 "h",
                                                                 1,
                                                                 Epoch.epoch(3L, 4L),
                                                                 new HlcTimestamp(50L, "a"));

            var next = original.withState(NodeLifecycleState.ON_DUTY, new HlcTimestamp(200L, "b"));

            assertThat(next.transitionedAt()).isEqualTo(original.transitionedAt());
        }

        @Test
        void withState_differentState_stampsTransitionedAtWithSuppliedHlc() {
            var original = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                 1000L,
                                                                 "h",
                                                                 1,
                                                                 Epoch.epoch(3L, 4L),
                                                                 new HlcTimestamp(50L, "a"));
            var now = new HlcTimestamp(175L, "leader");

            var next = original.withState(NodeLifecycleState.DRAINING, now);

            assertThat(next.state()).isEqualTo(NodeLifecycleState.DRAINING);
            assertThat(next.transitionedAt()).isEqualTo(now);
        }

        @Test
        void withState_preservesHostPortAndEpoch() {
            var original = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                 1000L,
                                                                 "h",
                                                                 1,
                                                                 Epoch.epoch(3L, 4L),
                                                                 HlcTimestamp.ZERO);

            var next = original.withState(NodeLifecycleState.DRAINING, new HlcTimestamp(99L, "leader"));

            assertThat(next.host()).isEqualTo("h");
            assertThat(next.port()).isEqualTo(1);
            assertThat(next.observedCoreEpoch()).isEqualTo(Epoch.epoch(3L, 4L));
        }

        @Test
        void withState_differentState_receivedZeroTimestamp_keepsZero() {
            var original = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                 1000L,
                                                                 "h",
                                                                 1,
                                                                 Epoch.epoch(3L, 4L),
                                                                 new HlcTimestamp(50L, "a"));

            var next = original.withState(NodeLifecycleState.DRAINING, HlcTimestamp.ZERO);

            assertThat(next.state()).isEqualTo(NodeLifecycleState.DRAINING);
            assertThat(next.transitionedAt()).isEqualTo(HlcTimestamp.ZERO);
        }
    }

    @Nested
    class HasAddress {
        @Test
        void hasAddress_populatedHostAndPort_true() {
            assertThat(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "10.0.0.1", 7301)
                                         .hasAddress()).isTrue();
        }

        @Test
        void hasAddress_emptyHost_false() {
            assertThat(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY).hasAddress()).isFalse();
        }
    }
}
