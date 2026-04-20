// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;


class PeerObservationReducerTest {
    private static final NodeId PEER = NodeId.nodeId("peer-x").unwrap();
    private static final NodeId OBS_1 = NodeId.nodeId("obs-1").unwrap();
    private static final NodeId OBS_2 = NodeId.nodeId("obs-2").unwrap();
    private static final NodeId OBS_3 = NodeId.nodeId("obs-3").unwrap();
    private static final NodeId OBS_4 = NodeId.nodeId("obs-4").unwrap();
    private static final NodeId OBS_5 = NodeId.nodeId("obs-5").unwrap();
    private static final Epoch E1 = Epoch.epoch(1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 2L);
    private static final Epoch E3 = Epoch.epoch(1L, 3L);

    private PeerObservationReducer reducer;

    @BeforeEach
    void setUp() {
        reducer = PeerObservationReducer.peerObservationReducer();
    }

    @Nested
    class Resolution {
        @Test
        void resolvedHint_singleSuspectedObserver_resolvesSuspected() {
            reducer.recordHint(OBS_1, PEER, HealthHint.SUSPECTED, E1);

            assertThat(reducer.resolvedHint(PEER, 5)).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void resolvedHint_oneFaultyOneHealthyOutOfFive_resolvesSuspected() {
            reducer.recordHint(OBS_1, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_2, PEER, HealthHint.HEALTHY, E1);

            assertThat(reducer.resolvedHint(PEER, 5)).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void resolvedHint_threeFaultyOutOfFive_resolvesFaulty() {
            reducer.recordHint(OBS_1, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_2, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_3, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_4, PEER, HealthHint.HEALTHY, E1);
            reducer.recordHint(OBS_5, PEER, HealthHint.HEALTHY, E1);

            assertThat(reducer.resolvedHint(PEER, 5)).isEqualTo(HealthHint.FAULTY);
        }

        @Test
        void resolvedHint_allObserversHealthy_resolvesHealthy() {
            reducer.recordHint(OBS_1, PEER, HealthHint.HEALTHY, E1);
            reducer.recordHint(OBS_2, PEER, HealthHint.HEALTHY, E1);
            reducer.recordHint(OBS_3, PEER, HealthHint.HEALTHY, E1);

            assertThat(reducer.resolvedHint(PEER, 3)).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void resolvedHint_noObservations_resolvesHealthy() {
            assertThat(reducer.resolvedHint(PEER, 5)).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void resolvedHint_exactMajorityFaulty_resolvesFaulty() {
            reducer.recordHint(OBS_1, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_2, PEER, HealthHint.FAULTY, E1);

            assertThat(reducer.resolvedHint(PEER, 3)).isEqualTo(HealthHint.FAULTY);
        }

        @Test
        void resolvedHint_belowFaultyThresholdAnyFaulty_resolvesSuspected() {
            reducer.recordHint(OBS_1, PEER, HealthHint.FAULTY, E1);

            assertThat(reducer.resolvedHint(PEER, 5)).isEqualTo(HealthHint.SUSPECTED);
        }
    }

    @Nested
    class EpochTracking {
        @Test
        void recordHint_sameObserverNewerEpoch_overwritesOlder() {
            reducer.recordHint(OBS_1, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_1, PEER, HealthHint.HEALTHY, E2);

            assertThat(reducer.resolvedHint(PEER, 3)).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void recordHint_sameObserverOlderEpoch_keepsNewer() {
            reducer.recordHint(OBS_1, PEER, HealthHint.FAULTY, E2);
            reducer.recordHint(OBS_1, PEER, HealthHint.HEALTHY, E1);

            assertThat(reducer.resolvedHint(PEER, 5)).isEqualTo(HealthHint.SUSPECTED);
        }
    }

    @Nested
    class Pruning {
        @Test
        void prune_dropsStaleEntries() {
            reducer.recordHint(OBS_1, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_2, PEER, HealthHint.FAULTY, E3);

            reducer.prune(E2);

            assertThat(reducer.resolvedHint(PEER, 5)).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void prune_dropsAllEntriesBeforeBoundary() {
            reducer.recordHint(OBS_1, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_2, PEER, HealthHint.FAULTY, E1);
            reducer.recordHint(OBS_3, PEER, HealthHint.FAULTY, E1);

            reducer.prune(E2);

            assertThat(reducer.resolvedHint(PEER, 5)).isEqualTo(HealthHint.HEALTHY);
        }
    }
}
