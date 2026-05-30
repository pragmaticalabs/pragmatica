// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


class CommunityReportTest {
    @Test
    void communityReport_withAllFields_populatesRecord() {
        var governor = NodeId.nodeId("gov-1").unwrap();

        var report = new CommunityReport("pool-a",
                                         3L,
                                         7L,
                                         42L,
                                         governor,
                                         5,
                                         4,
                                         1,
                                         0,
                                         Set.of("p-1", "p-2"),
                                         123456L);

        assertThat(report.communityId()).isEqualTo("pool-a");
        assertThat(report.communityTerm()).isEqualTo(3L);
        assertThat(report.communityEpochTerm()).isEqualTo(7L);
        assertThat(report.communityEpochCounter()).isEqualTo(42L);
        assertThat(report.governorNodeId()).isEqualTo(governor);
        assertThat(report.memberCount()).isEqualTo(5);
        assertThat(report.healthyMembers()).isEqualTo(4);
        assertThat(report.suspectedMembers()).isEqualTo(1);
        assertThat(report.faultyMembers()).isZero();
        assertThat(report.partitionsHeld()).containsExactlyInAnyOrder("p-1", "p-2");
        assertThat(report.lastPongFromGovernorAtMs()).isEqualTo(123456L);
    }

    @Test
    void construct_nullPartitions_normalizesToEmpty() {
        var governor = NodeId.nodeId("gov-1").unwrap();

        var report = new CommunityReport("pool-a", 0L, 0L, 0L, governor, 0, 0, 0, 0, null, 0L);

        assertThat(report.partitionsHeld()).isEmpty();
    }

    @Test
    void construct_nullCommunityId_normalizesToEmpty() {
        var governor = NodeId.nodeId("gov-1").unwrap();

        var report = new CommunityReport(null, 0L, 0L, 0L, governor, 0, 0, 0, 0, Set.of(), 0L);

        assertThat(report.communityId()).isEmpty();
    }
}
