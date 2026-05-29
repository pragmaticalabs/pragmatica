// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class NodeReportedStateHolderTest {
    @Nested
    class InitialState {
        @Test
        void current_freshHolder_isSyncing() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder();

            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);
        }
    }

    @Nested
    class Promotion {
        @Test
        void onConsensusActive_withoutSubsystems_staysSyncing() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder();

            holder.onConsensusActive();

            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);
        }

        @Test
        void onSubsystemsReady_withoutConsensus_staysSyncing() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder();

            holder.onSubsystemsReady();

            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);
        }

        @Test
        void onConsensusActive_withSubsystemsReady_becomesReady() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder();

            holder.onConsensusActive();
            holder.onSubsystemsReady();

            assertThat(holder.current()).isEqualTo(NodeReportedState.READY);
        }
    }

    @Nested
    class ConsensusPassive {
        @Test
        void onConsensusPassive_afterReady_fallsBackToSyncing() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder();
            holder.onConsensusActive();
            holder.onSubsystemsReady();

            holder.onConsensusPassive();

            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);
        }
    }

    @Nested
    class Draining {
        @Test
        void onDrainStarted_fromSyncing_becomesDraining() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder();

            holder.onDrainStarted();

            assertThat(holder.current()).isEqualTo(NodeReportedState.DRAINING);
        }

        @Test
        void onDrainStarted_isStickyAcrossSubsequentEdges_staysDraining() {
            // Drain is uninterruptible per spec I9: once DRAINING, no consensus or
            // subsystem edge can promote the node back to READY/SYNCING.
            var holder = NodeReportedStateHolder.nodeReportedStateHolder();
            holder.onDrainStarted();

            holder.onConsensusActive();
            holder.onSubsystemsReady();
            holder.onConsensusPassive();

            assertThat(holder.current()).isEqualTo(NodeReportedState.DRAINING);
        }
    }
}
