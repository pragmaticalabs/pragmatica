// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.swim.QuorumReport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.swim.QuorumReport.HELD;
import static org.pragmatica.swim.QuorumReport.LOST;
import static org.pragmatica.swim.QuorumReport.OBSERVER;

/// #2936, PR #2940 ruling: the REAL `ClusterStateNotification` route (`AetherNode.routeQuorumDisappearedToDrain`) and
/// the `ObserverResumedNotification` route feed the consensus ROLE into this node's quorum report. A #1790 demotion
/// arrives as `PASSIVE` with `demoted()`; it must report `OBSERVER` — passive by role, never `LOST` — so peers keep
/// forwarding to the observer. A voter's plain `PASSIVE` is the control: it still reports `LOST`.
class QuorumReportRouteTest {
    private final List<QuorumReport> reports = new CopyOnWriteArrayList<>();
    private final AtomicReference<QuorumLossDetector> detectorRef = new AtomicReference<>();
    private QuorumLossDetector detector;

    @BeforeEach
    void setUp() {
        detector = QuorumLossDetector.quorumLossDetector(membershipConfig(), () -> 3, () -> 3);
        detector.reevaluate();
        assertThat(detector.isArmed()).isTrue();
        detector.setQuorumReportListener(reports::add);
        detectorRef.set(detector);
    }

    @AfterEach
    void tearDown() {
        detector.stop();
    }

    @Test
    void routeQuorumDisappearedToDrain_demotion_reportsObserver_soPeersKeepForwarding() {
        AetherNode.routeQuorumDisappearedToDrain(ClusterStateNotification.demotion(), detectorRef);

        assertThat(reports).as("a #1790 demotion is passive by role, never quorum loss").containsExactly(HELD, OBSERVER);
    }

    @Test
    void routeQuorumDisappearedToDrain_voterPassive_reportsLost() {
        AetherNode.routeQuorumDisappearedToDrain(ClusterStateNotification.passive(), detectorRef);

        assertThat(reports).as("control: a voter's plain PASSIVE is quorum loss").containsExactly(HELD, LOST);
    }

    @Test
    void routeQuorumDisappearedToDrain_demotedObserverPassiveThenRePromoted_neverReportsLostUntilAGenuineLoss() {
        AetherNode.routeQuorumDisappearedToDrain(ClusterStateNotification.demotion(), detectorRef);
        AetherNode.routeQuorumDisappearedToDrain(ClusterStateNotification.passive(), detectorRef);
        AetherNode.routeQuorumDisappearedToDrain(ClusterStateNotification.active(), detectorRef);

        assertThat(reports).as("an observer's PASSIVE keeps the role; re-promotion clears without a LOST flash")
                           .containsExactly(HELD, OBSERVER, HELD);

        AetherNode.routeQuorumDisappearedToDrain(ClusterStateNotification.passive(), detectorRef);

        assertThat(reports).as("a re-promoted voter that cannot write is excluded as before")
                           .containsExactly(HELD, OBSERVER, HELD, LOST);
    }

    @Test
    void routeObserverResumedToReport_configuredObserver_reportsObserver() {
        AetherNode.routeObserverResumedToReport(detectorRef);

        assertThat(reports).as("an observer applying decisions is passive by role").containsExactly(HELD, OBSERVER);
    }
}
