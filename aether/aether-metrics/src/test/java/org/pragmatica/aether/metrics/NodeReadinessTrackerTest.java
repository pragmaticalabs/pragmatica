// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;


class NodeReadinessTrackerTest {
    private static final NodeId SELF = NodeId.nodeId("self-1").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("self-2").unwrap();

    @Nested
    class InitialState {
        @Test
        void candidate_freshTracker_returnsNone() {
            var tracker = NodeReadinessTracker.nodeReadinessTracker();

            assertThat(tracker.candidate()).isEqualTo(Option.none());
        }
    }

    @Nested
    class MarkReady {
        @Test
        void markReady_singleInvocation_setsCandidateToSelf() {
            var tracker = NodeReadinessTracker.nodeReadinessTracker();

            tracker.markReady(SELF);

            assertThat(tracker.candidate()).isEqualTo(Option.some(SELF));
        }

        @Test
        void markReady_idempotent_keepsFirstCandidate() {
            var tracker = NodeReadinessTracker.nodeReadinessTracker();

            tracker.markReady(SELF);
            tracker.markReady(SELF);
            tracker.markReady(SELF);

            assertThat(tracker.candidate()).isEqualTo(Option.some(SELF));
        }

        @Test
        void markReady_secondNodeId_doesNotOverwriteFirst() {
            // markReady uses compareAndSet(none(), some(self)); subsequent calls with a
            // different node id while the first is still present are no-ops.
            var tracker = NodeReadinessTracker.nodeReadinessTracker();
            tracker.markReady(SELF);

            tracker.markReady(OTHER);

            assertThat(tracker.candidate()).isEqualTo(Option.some(SELF));
        }

        @Test
        void markReady_nullNodeId_doesNotMutate() {
            var tracker = NodeReadinessTracker.nodeReadinessTracker();

            tracker.markReady(null);

            assertThat(tracker.candidate()).isEqualTo(Option.none());
        }
    }

    @Nested
    class Clear {
        @Test
        void clear_afterMarkReady_returnsToNone() {
            var tracker = NodeReadinessTracker.nodeReadinessTracker();
            tracker.markReady(SELF);

            tracker.clear();

            assertThat(tracker.candidate()).isEqualTo(Option.none());
        }

        @Test
        void clear_idempotent_onAlreadyEmpty() {
            var tracker = NodeReadinessTracker.nodeReadinessTracker();

            tracker.clear();
            tracker.clear();

            assertThat(tracker.candidate()).isEqualTo(Option.none());
        }

        @Test
        void clear_allowsSubsequentMarkReady() {
            // Verifies the round trip: mark → clear → mark again works.
            var tracker = NodeReadinessTracker.nodeReadinessTracker();
            tracker.markReady(SELF);
            tracker.clear();

            tracker.markReady(OTHER);

            assertThat(tracker.candidate()).isEqualTo(Option.some(OTHER));
        }
    }
}
