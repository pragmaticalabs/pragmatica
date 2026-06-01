// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;


class NodeReportedStateHolderTest {
    @Nested
    class InitialState {
        @Test
        void current_freshHolderWithInactiveConsensus_isSyncing() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder(() -> false);

            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);
        }
    }

    @Nested
    class Promotion {
        @Test
        void current_consensusActiveWithoutSubsystems_isSyncing() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder(() -> true);

            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);
        }

        @Test
        void current_subsystemsReadyWithoutConsensus_isSyncing() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder(() -> false);

            holder.onSubsystemsReady();

            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);
        }

        @Test
        void current_consensusActiveAndSubsystemsReady_isReady() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder(() -> true);

            holder.onSubsystemsReady();

            assertThat(holder.current()).isEqualTo(NodeReportedState.READY);
        }
    }

    @Nested
    class LiveConsensusLevel {
        @Test
        void current_consensusLevelTrueThenFalse_fallsBackToSyncing() {
            var active = new AtomicBoolean(true);
            var holder = NodeReportedStateHolder.nodeReportedStateHolder(active::get);
            holder.onSubsystemsReady();

            assertThat(holder.current()).isEqualTo(NodeReportedState.READY);

            active.set(false);

            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);
        }

        @Test
        void current_consensusLevelTrueFalseTrue_recoversToReady() {
            // Regression for the edge-cached desync bug: a PASSIVE not followed by a fresh ACTIVE
            // edge left the cached flag stuck SYNCING. The live level signal self-heals — once the
            // level returns to active the node reports READY again on the next pong, no stuck state.
            var active = new AtomicBoolean(true);
            var holder = NodeReportedStateHolder.nodeReportedStateHolder(active::get);
            holder.onSubsystemsReady();

            assertThat(holder.current()).isEqualTo(NodeReportedState.READY);

            active.set(false);
            assertThat(holder.current()).isEqualTo(NodeReportedState.SYNCING);

            active.set(true);
            assertThat(holder.current()).isEqualTo(NodeReportedState.READY);
        }
    }

    @Nested
    class Draining {
        @Test
        void current_drainStartedFromSyncing_isDraining() {
            var holder = NodeReportedStateHolder.nodeReportedStateHolder(() -> false);

            holder.onDrainStarted();

            assertThat(holder.current()).isEqualTo(NodeReportedState.DRAINING);
        }

        @Test
        void current_drainStartedWhileConsensusActiveAndReady_staysDraining() {
            // Drain is uninterruptible per spec I9: once DRAINING, neither a live consensus-active
            // level nor subsystems-ready can promote the node back to READY/SYNCING.
            var active = new AtomicBoolean(true);
            var holder = NodeReportedStateHolder.nodeReportedStateHolder(active::get);
            holder.onSubsystemsReady();
            holder.onDrainStarted();

            assertThat(holder.current()).isEqualTo(NodeReportedState.DRAINING);

            active.set(false);
            assertThat(holder.current()).isEqualTo(NodeReportedState.DRAINING);

            active.set(true);
            assertThat(holder.current()).isEqualTo(NodeReportedState.DRAINING);
        }
    }
}
