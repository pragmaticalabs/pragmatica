// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.consensus.NodeId;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the cross-node ROUTING → ACTIVE epoch-ack fast-path tracker.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §12.
class NodeDeploymentManagerEpochAckTest {
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = NodeId.nodeId("node-3").unwrap();
    private static final NodeId NODE_4 = NodeId.nodeId("node-4").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    private RoutingEpochAckTracker tracker;
    private SliceNodeKey sliceKey;

    @BeforeEach
    void setUp() {
        tracker = RoutingEpochAckTracker.routingEpochAckTracker();
        sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_1);
    }

    @Nested
    class ThresholdReaching {
        @Test
        void allTargetsAckAtOrAboveEpoch_signalsReady() {
            tracker.registerExpectation(sliceKey, Epoch.epoch(7L, 10L), Set.of(NODE_1, NODE_2, NODE_3));

            assertThat(tracker.observeAck(sliceKey, NODE_1, Epoch.epoch(7L, 10L)).isPresent()).isFalse();
            assertThat(tracker.observeAck(sliceKey, NODE_2, Epoch.epoch(7L, 10L)).isPresent()).isFalse();
            var ready = tracker.observeAck(sliceKey, NODE_3, Epoch.epoch(7L, 11L));

            assertThat(ready.isPresent())
                    .as("third ack must signal ready")
                    .isTrue();
            assertThat(ready.unwrap()).isEqualTo(sliceKey);
        }

        @Test
        void readySignal_firesExactlyOnce_subsequentAcksAreNoop() {
            tracker.registerExpectation(sliceKey, Epoch.epoch(7L, 10L), Set.of(NODE_1, NODE_2));

            assertThat(tracker.observeAck(sliceKey, NODE_1, Epoch.epoch(7L, 10L)).isPresent()).isFalse();
            assertThat(tracker.observeAck(sliceKey, NODE_2, Epoch.epoch(7L, 10L)).isPresent())
                    .as("second ack triggers ready")
                    .isTrue();
            assertThat(tracker.observeAck(sliceKey, NODE_1, Epoch.epoch(7L, 11L)).isPresent())
                    .as("subsequent ack after consume is no-op")
                    .isFalse();
        }
    }

    @Nested
    class FencingAndFiltering {
        @Test
        void ackBelowExpectedEpoch_isIgnored() {
            tracker.registerExpectation(sliceKey, Epoch.epoch(7L, 10L), Set.of(NODE_1, NODE_2));

            var stale = tracker.observeAck(sliceKey, NODE_1, Epoch.epoch(7L, 5L));

            assertThat(stale.isPresent()).isFalse();
            assertThat(tracker.observeAck(sliceKey, NODE_2, Epoch.epoch(7L, 10L)).isPresent())
                    .as("only NODE_2 has actually acked")
                    .isFalse();
        }

        @Test
        void ackFromUntargetedNode_isIgnored() {
            tracker.registerExpectation(sliceKey, Epoch.epoch(7L, 10L), Set.of(NODE_1, NODE_2));

            assertThat(tracker.observeAck(sliceKey, NODE_3, Epoch.epoch(7L, 50L)).isPresent()).isFalse();
            assertThat(tracker.observeAck(sliceKey, NODE_1, Epoch.epoch(7L, 10L)).isPresent()).isFalse();
            assertThat(tracker.observeAck(sliceKey, NODE_2, Epoch.epoch(7L, 10L)).isPresent()).isTrue();
        }

        @Test
        void noExpectationRegistered_observeAck_returnsNone() {
            assertThat(tracker.observeAck(sliceKey, NODE_1, Epoch.epoch(99L, 0L)).isPresent()).isFalse();
        }

        @Test
        void clearedExpectation_observeAck_returnsNone() {
            tracker.registerExpectation(sliceKey, Epoch.epoch(7L, 0L), Set.of(NODE_1));
            tracker.clear(sliceKey);

            assertThat(tracker.observeAck(sliceKey, NODE_1, Epoch.epoch(7L, 0L)).isPresent()).isFalse();
        }
    }

    @Nested
    class CrossNodeAckBeforeLocalPublish {
        @Test
        void ackThresholdCanBeReached_beforeLocalPublishCompletes() {
            // Simulate: NDM registers expectation when issuing publish; remote nodes
            // process and ack via NodeRoutesKey BEFORE the local publish Promise resolves.
            tracker.registerExpectation(sliceKey, Epoch.epoch(11L, 100L), Set.of(NODE_2, NODE_3, NODE_4));

            assertThat(tracker.observeAck(sliceKey, NODE_2, Epoch.epoch(11L, 100L)).isPresent()).isFalse();
            assertThat(tracker.observeAck(sliceKey, NODE_3, Epoch.epoch(11L, 105L)).isPresent()).isFalse();
            var ready = tracker.observeAck(sliceKey, NODE_4, Epoch.epoch(11L, 110L));

            assertThat(ready.isPresent())
                    .as("cross-node acks alone are sufficient to fast-transition")
                    .isTrue();
        }

        @Test
        void laterLocalClear_isSafe_idempotentWithFastPath() {
            tracker.registerExpectation(sliceKey, Epoch.epoch(7L, 10L), Set.of(NODE_1));

            var ready = tracker.observeAck(sliceKey, NODE_1, Epoch.epoch(7L, 10L));
            assertThat(ready.isPresent()).isTrue();

            // Local publish promise resolves later → calls clear; must not throw.
            tracker.clear(sliceKey);
            // Idempotent: clearing twice is fine.
            tracker.clear(sliceKey);
        }
    }
}
