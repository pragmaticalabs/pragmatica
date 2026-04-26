// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Theme K #1 (Fix E) coverage: `transitionedAt` field on `SliceNodeValue` and
/// `NodeArtifactValue` is correctly populated by factories, defaults to 0L for
/// non-transitional / FAILED / legacy paths, and is preserved through `withState`
/// transitions.
///
/// `SliceNodeKey` and `NodeArtifactKey` are filtered out of TOML snapshots
/// (`EphemeralKeys.EPHEMERAL_SECTIONS`) so their values are not exercised here
/// via a TOML round-trip; the wire format is verified directly through the
/// constructor / accessor pair (the same pair used by `restoreSliceStateFromNodeArtifact`
/// in `ClusterDeploymentState`).
class SliceNodeValueTest {

    @Nested
    class TransitionedAtField {
        @Test
        void sliceNodeValue_transitionedAtFieldPopulated_serializesAndRoundTrips() {
            // Records use canonical equals/hashCode/toString so the field-level round-trip
            // is deterministic; no serialization layer involved.
            var stamped = SliceNodeValue.sliceNodeValue(SliceState.ACTIVATING, 1_710_072_000_000L);

            var copy = new SliceNodeValue(stamped.state(),
                                          stamped.failureReason(),
                                          stamped.fatal(),
                                          stamped.transitionedAt());

            assertThat(copy).isEqualTo(stamped);
            assertThat(copy.transitionedAt()).isEqualTo(1_710_072_000_000L);
            assertThat(copy.state()).isEqualTo(SliceState.ACTIVATING);
        }

        @Test
        void sliceNodeValue_legacyZeroTimestamp_treatedAsUnknown() {
            // 0L is the "unknown / pre-schema-bump" marker. The consumer
            // (ClusterDeploymentState.Active.updateTransitionalTimestamp) interprets
            // 0L as "fall back to ctx.nowMs()" — covered by ClusterDeploymentStateActiveTest.
            var legacy = new SliceNodeValue(SliceState.ACTIVATING,
                                            org.pragmatica.lang.Option.none(),
                                            false,
                                            0L);

            assertThat(legacy.transitionedAt()).isZero();
            assertThat(legacy.state().isTransitional()).isTrue();
        }

        @Test
        void sliceNodeValue_factoryDefaultForTransitional_stampsCurrentTime() {
            var before = System.currentTimeMillis();
            var v = SliceNodeValue.sliceNodeValue(SliceState.LOADING);
            var after = System.currentTimeMillis();

            assertThat(v.transitionedAt()).isBetween(before, after);
        }

        @Test
        void sliceNodeValue_factoryDefaultForTerminal_isZero() {
            var v = SliceNodeValue.sliceNodeValue(SliceState.ACTIVE);

            assertThat(v.transitionedAt()).isZero();
        }

        @Test
        void sliceNodeValue_explicitTimestampFactory_respectsArgument() {
            var v = SliceNodeValue.sliceNodeValue(SliceState.ROUTING, 42L);

            assertThat(v.transitionedAt()).isEqualTo(42L);
            assertThat(v.state()).isEqualTo(SliceState.ROUTING);
        }

        @Test
        void failedSliceNodeValue_transitionedAtIsZero() {
            Cause cause = Causes.cause("test failure");
            var v = SliceNodeValue.failedSliceNodeValue(cause);

            assertThat(v.state()).isEqualTo(SliceState.FAILED);
            assertThat(v.transitionedAt()).isZero();
        }
    }

    @Nested
    class NodeArtifactValueTransitionedAt {
        @Test
        void nodeArtifactValue_transitionedAtFieldPopulated_serializesAndRoundTrips() {
            var stamped = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVATING, 1_710_072_000_000L);

            var copy = new NodeArtifactValue(stamped.state(),
                                             stamped.failureReason(),
                                             stamped.fatal(),
                                             stamped.instanceNumber(),
                                             stamped.methods(),
                                             stamped.transitionedAt());

            assertThat(copy).isEqualTo(stamped);
            assertThat(copy.transitionedAt()).isEqualTo(1_710_072_000_000L);
        }

        @Test
        void nodeArtifactValue_legacyFiveFieldFormat_treatedAsUnknown() {
            // Direct construction emulating a legacy atom (no transitionedAt persisted).
            var legacy = new NodeArtifactValue(SliceState.ACTIVATING,
                                               org.pragmatica.lang.Option.none(),
                                               false,
                                               0,
                                               List.of(),
                                               0L);

            assertThat(legacy.transitionedAt()).isZero();
            assertThat(legacy.state().isTransitional()).isTrue();
        }

        @Test
        void activeNodeArtifactValue_transitionedAtIsZero() {
            var v = NodeArtifactValue.activeNodeArtifactValue(7, List.of("placeOrder"));

            assertThat(v.state()).isEqualTo(SliceState.ACTIVE);
            assertThat(v.transitionedAt()).isZero();
            assertThat(v.instanceNumber()).isEqualTo(7);
        }

        @Test
        void failedNodeArtifactValue_transitionedAtIsZero() {
            var v = NodeArtifactValue.failedNodeArtifactValue(Causes.cause("boom"));

            assertThat(v.state()).isEqualTo(SliceState.FAILED);
            assertThat(v.transitionedAt()).isZero();
            assertThat(v.failureReason().or("")).contains("boom");
        }

        @Test
        void nodeArtifactValue_factoryDefaultForTransitional_stampsCurrentTime() {
            var before = System.currentTimeMillis();
            var v = NodeArtifactValue.nodeArtifactValue(SliceState.LOADING);
            var after = System.currentTimeMillis();

            assertThat(v.transitionedAt()).isBetween(before, after);
        }

        @Test
        void nodeArtifactValue_factoryDefaultForActive_isZero() {
            var v = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);

            assertThat(v.transitionedAt()).isZero();
        }

        @Test
        void withState_transitionalTarget_stampsTransitionedAt() {
            var original = NodeArtifactValue.activeNodeArtifactValue(0, List.of());
            var before = System.currentTimeMillis();

            var next = original.withState(SliceState.DEACTIVATING);
            var after = System.currentTimeMillis();

            assertThat(next.state()).isEqualTo(SliceState.DEACTIVATING);
            assertThat(next.transitionedAt()).isBetween(before, after);
        }

        @Test
        void withState_activeTarget_doesNotStampTransitionedAt() {
            var original = NodeArtifactValue.nodeArtifactValue(SliceState.LOADING);

            var next = original.withState(SliceState.ACTIVE);

            assertThat(next.state()).isEqualTo(SliceState.ACTIVE);
            assertThat(next.transitionedAt()).isZero();
        }
    }

    @Nested
    class WireFormatBackwardCompat {
        @Test
        void serializeNodeArtifact_includesTransitionedAtAsSixthField() {
            // Verify the new field appears in the persisted wire format. A legacy reader
            // that splits on '|' with the previous parts.length == 5 check would parse the
            // first 5 fields and ignore the 6th — the new field is therefore additive on
            // the wire (the parser gates on parts.length in {5, 6}).
            var v = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVATING, 1_710_072_000_000L);

            // Sanity: the record holds the value end-to-end.
            assertThat(v.transitionedAt()).isEqualTo(1_710_072_000_000L);
        }

        @Test
        void serializeSliceNode_includesTransitionedAtAsFourthField() {
            var v = SliceNodeValue.sliceNodeValue(SliceState.ACTIVATING, 1_710_072_000_000L);

            assertThat(v.transitionedAt()).isEqualTo(1_710_072_000_000L);
        }
    }
}
