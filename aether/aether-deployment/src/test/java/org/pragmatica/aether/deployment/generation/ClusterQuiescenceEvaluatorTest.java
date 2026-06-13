// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.CommunityQuiescence;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterQuiescenceEvaluatorTest {
    private static final NodeId GOVERNOR = NodeId.nodeId("governor").unwrap();

    @Test
    void evaluateCluster_returnsQuiesced_whenAllHealthyAndNoPending() {
        var result = ClusterQuiescenceEvaluator.evaluateCluster(List.of(HealthHint.HEALTHY, HealthHint.HEALTHY),
                                                                List.of(CommunityQuiescence.QUIESCED),
                                                                0);

        assertThat(result.quiescence()).isEqualTo(ClusterQuiescence.QUIESCED);
        assertThat(result.detail()).isEmpty();
    }

    @Test
    void evaluateCluster_returnsDegraded_whenMemberSuspected() {
        var result = ClusterQuiescenceEvaluator.evaluateCluster(List.of(HealthHint.HEALTHY, HealthHint.SUSPECTED),
                                                                List.of(),
                                                                0);

        assertThat(result.quiescence()).isEqualTo(ClusterQuiescence.DEGRADED);
        assertThat(result.detail()).isEqualTo("1 members SUSPECTED");
    }

    @Test
    void evaluateCluster_returnsDegraded_whenMemberFaulty() {
        var result = ClusterQuiescenceEvaluator.evaluateCluster(List.of(HealthHint.FAULTY),
                                                                List.of(),
                                                                0);

        assertThat(result.quiescence()).isEqualTo(ClusterQuiescence.DEGRADED);
        assertThat(result.detail()).isEqualTo("1 members FAULTY");
    }

    @Test
    void evaluateCluster_returnsConverging_whenCommunityConverging() {
        var result = ClusterQuiescenceEvaluator.evaluateCluster(List.of(HealthHint.HEALTHY),
                                                                List.of(CommunityQuiescence.CONVERGING),
                                                                0);

        assertThat(result.quiescence()).isEqualTo(ClusterQuiescence.CONVERGING);
        assertThat(result.detail()).isEqualTo("1 communities CONVERGING");
    }

    @Test
    void evaluateCluster_returnsConverging_whenPendingRebalancePositive() {
        var result = ClusterQuiescenceEvaluator.evaluateCluster(List.of(HealthHint.HEALTHY),
                                                                List.of(CommunityQuiescence.QUIESCED),
                                                                2);

        assertThat(result.quiescence()).isEqualTo(ClusterQuiescence.CONVERGING);
        assertThat(result.detail()).isEqualTo("2 communities awaiting spokesman");
    }

    @Test
    void evaluateCommunity_returnsDissolving_whenGovernorDissolved() {
        var result = ClusterQuiescenceEvaluator.evaluateCommunity(announcement(Epoch.epoch(1L, 0L), true), Epoch.ZERO);

        assertThat(result.state()).isEqualTo(CommunityQuiescence.DISSOLVING);
        assertThat(result.detail()).isEqualTo("community dissolved");
    }

    @Test
    void evaluateCommunity_returnsConverging_whenLastAckBelowCommunityEpoch() {
        var communityEpoch = Epoch.epoch(5L, 0L);
        var result = ClusterQuiescenceEvaluator.evaluateCommunity(announcement(communityEpoch, false), Epoch.ZERO);

        assertThat(result.state()).isEqualTo(CommunityQuiescence.CONVERGING);
        assertThat(result.detail()).isEqualTo("governor has not acked epoch " + communityEpoch);
    }

    @Test
    void evaluateCommunity_returnsQuiesced_whenLastAckReachedCommunityEpoch() {
        var communityEpoch = Epoch.epoch(5L, 0L);
        var result = ClusterQuiescenceEvaluator.evaluateCommunity(announcement(communityEpoch, false), communityEpoch);

        assertThat(result.state()).isEqualTo(CommunityQuiescence.QUIESCED);
        assertThat(result.detail()).isEmpty();
    }

    private static GovernorAnnouncementValue announcement(Epoch communityEpoch, boolean dissolved) {
        return new GovernorAnnouncementValue(GOVERNOR,
                                             0,
                                             List.of(),
                                             "",
                                             0L,
                                             0L,
                                             communityEpoch,
                                             Epoch.ZERO,
                                             HlcTimestamp.ZERO,
                                             dissolved);
    }
}
