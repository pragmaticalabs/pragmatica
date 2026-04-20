// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterSyncSchedulerSnapshotTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    @BeforeEach
    void setUp() {
        // stable signature reference to ensure imports used
        assertThat(SELF).isNotNull();
    }

    @Test
    void observedEpoch_recordsHigherEpochOnly() {
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new NoopNetwork(),
                                                           new NoopClusterSyncCollector(),
                                                           org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           () -> Option.some(ClusterGenerationSnapshot.empty(7L)),
                                                           _ -> new byte[0]);

        scheduler.recordObservedEpoch(PEER_A, Epoch.epoch(7L, 3L));
        scheduler.recordObservedEpoch(PEER_A, Epoch.epoch(7L, 1L));  // lower — ignored
        scheduler.recordObservedEpoch(PEER_A, Epoch.epoch(7L, 5L));  // higher — kept
        scheduler.recordObservedEpoch(PEER_B, Epoch.epoch(7L, 2L));

        assertThat(scheduler.observedEpochs()).containsEntry(PEER_A, Epoch.epoch(7L, 5L))
                                               .containsEntry(PEER_B, Epoch.epoch(7L, 2L));
    }

    @Test
    void observedEpoch_acceptsHigherTermOverlower() {
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new NoopNetwork(),
                                                           new NoopClusterSyncCollector(),
                                                           org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           () -> Option.some(ClusterGenerationSnapshot.empty(7L)),
                                                           _ -> new byte[0]);

        scheduler.recordObservedEpoch(PEER_A, Epoch.epoch(7L, 99L));
        scheduler.recordObservedEpoch(PEER_A, Epoch.epoch(8L, 0L));  // new term wins

        assertThat(scheduler.observedEpochs().get(PEER_A)).isEqualTo(Epoch.epoch(8L, 0L));
    }

    @Test
    void observedEpochs_startEmptyBeforeAnyPong() {
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new NoopNetwork(),
                                                           new NoopClusterSyncCollector(),
                                                           org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds(),
                                                           () -> 7L,
                                                           Option::none,
                                                           _ -> new byte[0]);

        assertThat(scheduler.observedEpochs()).isEmpty();
    }

    @Test
    void snapshotSupplier_emptyOption_schedulerStillConstructible() {
        var ref = new AtomicReference<Option<ClusterGenerationSnapshot>>(Option.none());
        var scheduler = ClusterSyncScheduler.clusterSyncScheduler(SELF,
                                                           new NoopNetwork(),
                                                           new NoopClusterSyncCollector(),
                                                           org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds(),
                                                           () -> 0L,
                                                           ref::get,
                                                           _ -> new byte[0]);

        assertThat(scheduler).isNotNull();
    }
}
