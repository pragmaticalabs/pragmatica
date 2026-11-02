// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the M-1 harness's `gov.refresh-only` classifier to the #2542 definition: equal in every field except `announcedAt` and
/// `observedCoreEpoch`. Comparing `observedCoreEpoch` as well undercounted refresh-only commits (66% at N=100).
class M1InstrumentRefreshOnlyTest {
    private static final NodeId GOVERNOR = new NodeId("gov");
    private static final NodeId MEMBER = new NodeId("member");
    private static final NodeId OTHER = new NodeId("other");
    private static final String ADDRESS = "10.0.0.1:9000";
    private static final Epoch STAMP = Epoch.epoch(1, 2, 3);

    private static GovernorAnnouncementValue announcement(NodeId governor,
                                                          int count,
                                                          List<NodeId> members,
                                                          String address,
                                                          long announcedAt,
                                                          long term,
                                                          Epoch communityEpoch,
                                                          Epoch observedCoreEpoch,
                                                          boolean dissolved) {
        return new GovernorAnnouncementValue(governor,
                                             count,
                                             members,
                                             address,
                                             announcedAt,
                                             term,
                                             communityEpoch,
                                             observedCoreEpoch,
                                             HlcTimestamp.ZERO,
                                             dissolved);
    }

    private static GovernorAnnouncementValue base() {
        return announcement(GOVERNOR, 1, List.of(MEMBER), ADDRESS, 1000L, 5L, Epoch.ZERO, Epoch.ZERO, false);
    }

    @Test
    void refreshOnly_true_whenOnlyAnnouncedAtDiffers() {
        var next = announcement(GOVERNOR, 1, List.of(MEMBER), ADDRESS, 2000L, 5L, Epoch.ZERO, Epoch.ZERO, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isTrue();
    }

    @Test
    void refreshOnly_true_whenOnlyObservedCoreEpochDiffers() {
        var next = announcement(GOVERNOR, 1, List.of(MEMBER), ADDRESS, 1000L, 5L, Epoch.ZERO, STAMP, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isTrue();
    }

    @Test
    void refreshOnly_true_whenBothStampsDiffer() {
        var next = announcement(GOVERNOR, 1, List.of(MEMBER), ADDRESS, 2000L, 5L, Epoch.ZERO, STAMP, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isTrue();
    }

    @Test
    void refreshOnly_false_whenGovernorDiffers() {
        var next = announcement(OTHER, 1, List.of(MEMBER), ADDRESS, 1000L, 5L, Epoch.ZERO, Epoch.ZERO, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isFalse();
    }

    @Test
    void refreshOnly_false_whenMemberCountDiffers() {
        var next = announcement(GOVERNOR, 2, List.of(MEMBER), ADDRESS, 1000L, 5L, Epoch.ZERO, Epoch.ZERO, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isFalse();
    }

    @Test
    void refreshOnly_false_whenMembersDiffer() {
        var next = announcement(GOVERNOR, 1, List.of(MEMBER, OTHER), ADDRESS, 1000L, 5L, Epoch.ZERO, Epoch.ZERO, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isFalse();
    }

    @Test
    void refreshOnly_false_whenAddressDiffers() {
        var next = announcement(GOVERNOR, 1, List.of(MEMBER), "10.0.0.2:9000", 1000L, 5L, Epoch.ZERO, Epoch.ZERO, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isFalse();
    }

    @Test
    void refreshOnly_false_whenCommunityTermDiffers() {
        var next = announcement(GOVERNOR, 1, List.of(MEMBER), ADDRESS, 1000L, 6L, Epoch.ZERO, Epoch.ZERO, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isFalse();
    }

    @Test
    void refreshOnly_false_whenCommunityEpochDiffers() {
        var next = announcement(GOVERNOR, 1, List.of(MEMBER), ADDRESS, 1000L, 5L, STAMP, Epoch.ZERO, false);

        assertThat(M1Instrument.refreshOnly(base(), next)).isFalse();
    }

    @Test
    void refreshOnly_false_whenDissolvedDiffers() {
        var next = announcement(GOVERNOR, 1, List.of(MEMBER), ADDRESS, 1000L, 5L, Epoch.ZERO, Epoch.ZERO, true);

        assertThat(M1Instrument.refreshOnly(base(), next)).isFalse();
    }

    @Test
    void refreshOnly_false_whenTransitionedAtDiffers() {
        var previous = base();
        var next = new GovernorAnnouncementValue(GOVERNOR, 1, List.of(MEMBER), ADDRESS, 1000L, 5L, Epoch.ZERO, Epoch.ZERO,
                                                 new HlcTimestamp(1L, GOVERNOR), false);

        assertThat(M1Instrument.refreshOnly(previous, next)).isFalse();
    }
}
