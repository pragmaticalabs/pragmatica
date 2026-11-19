// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.HoldRecords.HoldDecision;
import org.pragmatica.aether.node.HoldRecords.HoldRefusal;
import org.pragmatica.aether.node.HoldRecords.LossConfirmation;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.T0;
import static org.pragmatica.aether.node.HoldRecordsFixture.eventually;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.refusedWith;
import static org.pragmatica.aether.node.HoldRecordsFixture.report;

/// A closed episode stays closed and is remembered (H1), and no decision is relayed for an episode that has a closer (H5).
/// Schedules S1, S1b and S4 of the PR 3401 behaviour verification.
class HoldRecordsClosedEpisodeTest {
    private static final OperatorWarningCode AT_RISK = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK;

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    /// S1: report, death (the closer lands), then a late HELD report of the same episode: no second latch, no second at-risk.
    @Test
    void lateHeldReportAfterDeathDoesNotReLatch() {
        fx.pong(held(3, "d1"));
        fx.now = T0 + 16_000;
        fx.records.tick();
        eventually(() -> fx.raisedOf(AT_RISK) == 1);
        fx.records.onDead(NODE);
        eventually(() -> fx.closersOfSubject(EPISODE) == 1);
        eventually(() -> fx.records.lastReport(NODE).isEmpty());

        fx.pong(held(3, "d1"));
        fx.now = T0 + 40_000;
        fx.records.tick();

        assertThat(fx.records.heldLatched(NODE)).as("dead episode re-latched by a late report").isFalse();
        assertThat(fx.raisedOf(AT_RISK)).as("at-risk re-raised for a closed episode").isEqualTo(1);
        assertThat(fx.closersOfSubject(EPISODE)).isEqualTo(1);
    }

    /// S1b: report, death (the closer lands), then a late DELIVERED report of the same episode: still exactly one closer.
    @Test
    void lateDeliveredReportAfterDeathRaisesNoSecondCloser() throws InterruptedException {
        fx.pong(held(3, "d1"));
        fx.records.onDead(NODE);
        eventually(() -> fx.closersOfSubject(EPISODE) == 1);
        eventually(() -> fx.records.lastReport(NODE).isEmpty());

        fx.pong(report(3, 0, "d1", HeldEnd.DELIVERED));
        fx.records.tick();
        Thread.sleep(300);

        assertThat(fx.closersOfSubject(EPISODE)).as("closers landed for one episode").isEqualTo(1);
        assertThat(fx.records.decisionFor(NODE)).as("closed and departed: nothing to relay").isEqualTo(Option.none());
    }

    /// S4: accept-loss racing a DELIVERED report: the delivery wins, the acceptance is refused, AcceptLoss is never relayed.
    @Test
    void deliveredReportWinsOverAnAcceptanceInFlight() {
        fx.pong(held(3, "d1"));
        fx.holdOpen = true;
        var accept = fx.records.acceptLoss(NODE, EPISODE, LossConfirmation.Digest.digest("d1"));
        eventually(() -> fx.pending.size() == 1);
        fx.pong(report(3, 0, "d1", HeldEnd.DELIVERED));
        eventually(() -> fx.pending.size() == 2);

        fx.pending.get(0).succeed(true);
        var result = accept.await();
        var decisionWhileCloserPending = fx.records.decisionFor(NODE);
        fx.pending.get(1).succeed(true);
        eventually(() -> fx.records.decisionFor(NODE).filter(HoldDecision.EndRecorded.class::isInstance).isPresent());

        assertThat(refusedWith(result, HoldRefusal.Ended.class)).as("acceptance refused: delivered")
                                                                                         .isTrue();
        assertThat(decisionWhileCloserPending).as("AcceptLoss relayed after a DELIVERED report").isEqualTo(Option.none());
        assertThat(fx.closersOf(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED)).hasSize(1);
        assertThat(fx.records.heldLatched(NODE)).isFalse();
    }

    /// H5: the drainer dies after the acceptance landed and before it echoed the end: no AcceptLoss is relayed for an episode
    /// that has a closer.
    @Test
    void deathAfterAcceptanceReleasesTheRelay() {
        fx.pong(held(3, "d1"));
        fx.records.acceptLoss(NODE, EPISODE, LossConfirmation.Digest.digest("d1")).await();
        var relayedBeforeDeath = fx.records.decisionFor(NODE);

        fx.records.onDead(NODE);

        assertThat(relayedBeforeDeath).as("the accepted loss is relayed while the drainer lives")
                                      .isEqualTo(Option.some(HoldDecision.AcceptLoss.acceptLoss(EPISODE, LossConfirmation.Digest.digest("d1"))));
        assertThat(fx.records.decisionFor(NODE).filter(HoldDecision.AcceptLoss.class::isInstance))
            .as("no AcceptLoss relayed after the death closed the episode").isEqualTo(Option.none());
    }
}
