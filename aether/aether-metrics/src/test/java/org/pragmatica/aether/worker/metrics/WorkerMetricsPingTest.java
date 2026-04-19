// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.CommunityGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;


class WorkerMetricsPingTest {
    private static final NodeId GOVERNOR = NodeId.nodeId("gov-1").unwrap();

    @Test
    void workerMetricsPing_allFields_populatesRecord() {
        var commEpoch = Epoch.epoch(3L, 5L);
        var coreEpoch = Epoch.epoch(7L, 17L);
        var snapshot = CommunityGenerationSnapshot.empty("pool-a", GOVERNOR);

        var ping = WorkerMetricsPing.workerMetricsPing(GOVERNOR,
                                                        1000L,
                                                        3L,
                                                        commEpoch,
                                                        coreEpoch,
                                                        Option.some(snapshot));

        assertThat(ping.sender()).isEqualTo(GOVERNOR);
        assertThat(ping.timestampMs()).isEqualTo(1000L);
        assertThat(ping.communityTerm()).isEqualTo(3L);
        assertThat(ping.communityEpoch()).isEqualTo(commEpoch);
        assertThat(ping.observedCoreEpoch()).isEqualTo(coreEpoch);
        assertThat(ping.snapshot().map(CommunityGenerationSnapshot::communityId).or("")).isEqualTo("pool-a");
    }

    @Test
    void workerMetricsPing_backwardCompatFactory_populatesZeroEpoch() {
        var ping = WorkerMetricsPing.workerMetricsPing(GOVERNOR);

        assertThat(ping.communityTerm()).isZero();
        assertThat(ping.communityEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(ping.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(ping.snapshot()).isEqualTo(Option.none());
    }

    @Test
    void construct_nullEpochs_normalizeToZero() {
        var ping = new WorkerMetricsPing(GOVERNOR, 0L, 0L, null, null, null);

        assertThat(ping.communityEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(ping.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(ping.snapshot()).isEqualTo(Option.none());
    }
}
