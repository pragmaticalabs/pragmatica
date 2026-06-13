// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;


class WorkerMetricsPongTest {
    private static final NodeId FOLLOWER = NodeId.nodeId("follower-1").unwrap();

    @Test
    void workerMetricsPong_allFields_populatesRecord() {
        var epoch = Epoch.epoch(4L, 11L);

        var pong = WorkerMetricsPong.workerMetricsPong(FOLLOWER, 0.4, 0.6, 7L, 120.0, 0.01, 5000L, epoch);

        assertThat(pong.sender()).isEqualTo(FOLLOWER);
        assertThat(pong.cpuUsage()).isEqualTo(0.4);
        assertThat(pong.observedCommunityEpoch()).isEqualTo(epoch);
    }

    @Test
    void workerMetricsPong_backwardCompatFactory_populatesZeroEpoch() {
        var pong = WorkerMetricsPong.workerMetricsPong(FOLLOWER, 0.4, 0.6, 0L, 0.0, 0.0);

        assertThat(pong.observedCommunityEpoch()).isEqualTo(Epoch.ZERO);
    }

    @Test
    void construct_nullEpoch_normalizesToZero() {
        var pong = new WorkerMetricsPong(FOLLOWER, 0.0, 0.0, 0L, 0.0, 0.0, 0L, null);

        assertThat(pong.observedCommunityEpoch()).isEqualTo(Epoch.ZERO);
    }
}
