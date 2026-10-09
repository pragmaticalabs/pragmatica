// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 part F — one event per COMMITTED transition, and every condition's recovery: STARTED -> COMPLETED / ABORTED,
/// PAUSED -> RESUMED / PAUSE_ENDED. Nothing is raised for a commit that changes no state (progress ticks).
class UpgradeRunAnnouncementsTest {
    private static UpgradeRunValue run(UpgradeRunState state, int index, long startedAt) {
        return new UpgradeRunValue("1.1.0", List.of(new NodeId("a"), new NodeId("b")), index, "", state, UpgradeStop.NONE, "why", startedAt, 0L, 0L);
    }

    private static List<OperatorWarningCode> codes(Option<UpgradeRunValue> before, UpgradeRunValue after) {
        return UpgradeRunAnnouncements.of(before, after).stream().map(UpgradeRunAnnouncements.Announcement::code).toList();
    }

    @Test
    void theFirstCommit_announcesStarted() {
        assertThat(codes(Option.none(), run(UpgradeRunState.RUNNING, 0, 1L))).containsExactly(OperatorWarningCode.UPGRADE_STARTED);
    }

    @Test
    void aNewRun_afterAnEndedOne_announcesStartedAgain() {
        assertThat(codes(Option.some(run(UpgradeRunState.COMPLETED, 2, 1L)), run(UpgradeRunState.RUNNING, 0, 9L)))
            .containsExactly(OperatorWarningCode.UPGRADE_STARTED);
    }

    @Test
    void progressWithinTheSameState_announcesNothing() {
        assertThat(codes(Option.some(run(UpgradeRunState.RUNNING, 0, 1L)), run(UpgradeRunState.RUNNING, 1, 1L))).isEmpty();
    }

    @Test
    void pausedThenResumed_pairsTheCondition() {
        assertThat(codes(Option.some(run(UpgradeRunState.RUNNING, 1, 1L)), run(UpgradeRunState.PAUSED, 1, 1L))).containsExactly(OperatorWarningCode.UPGRADE_PAUSED);
        assertThat(codes(Option.some(run(UpgradeRunState.PAUSED, 1, 1L)), run(UpgradeRunState.RUNNING, 1, 1L))).containsExactly(OperatorWarningCode.UPGRADE_RESUMED);
    }

    @Test
    void completed_closesStarted() {
        assertThat(codes(Option.some(run(UpgradeRunState.RUNNING, 2, 1L)), run(UpgradeRunState.COMPLETED, 2, 1L)))
            .containsExactly(OperatorWarningCode.UPGRADE_COMPLETED);
        assertThat(OperatorWarningCode.UPGRADE_COMPLETED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.UPGRADE_STARTED);
    }

    @Test
    void abortedWhileRunning_closesStarted_andAbortedWhilePaused_alsoClosesThePause() {
        assertThat(codes(Option.some(run(UpgradeRunState.RUNNING, 1, 1L)), run(UpgradeRunState.ABORTED, 1, 1L)))
            .containsExactly(OperatorWarningCode.UPGRADE_ABORTED);
        assertThat(codes(Option.some(run(UpgradeRunState.PAUSED, 1, 1L)), run(UpgradeRunState.ABORTED, 1, 1L)))
            .containsExactly(OperatorWarningCode.UPGRADE_PAUSE_ENDED, OperatorWarningCode.UPGRADE_ABORTED);
    }

    @Test
    void everyEventOfTheRunSharesOneSubject_whichIsWhatPairsARecoveryWithItsCondition() {
        var subjects = UpgradeRunAnnouncements.of(Option.some(run(UpgradeRunState.PAUSED, 1, 1L)), run(UpgradeRunState.ABORTED, 1, 1L))
                                              .stream()
                                              .map(UpgradeRunAnnouncements.Announcement::subject)
                                              .distinct()
                                              .toList();

        assertThat(subjects).containsExactly("upgrade");
    }
}
