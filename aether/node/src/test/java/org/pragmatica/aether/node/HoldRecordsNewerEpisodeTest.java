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
import org.pragmatica.cluster.metrics.HeldReport;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.E2;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.T0;
import static org.pragmatica.aether.node.HoldRecordsFixture.eventually;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.refusedWith;
import static org.pragmatica.aether.node.HoldRecordsFixture.ofEpisode;
import static org.pragmatica.aether.node.HoldRecordsFixture.report;

/// A report of a NEWER episode of the same node closes the open older one with the hold-ended closer "superseded" and opens
/// the new one (H2): exactly one closer per episode, and the node is not latched once the new episode ended.
/// Schedules S2, S3 and S3b of the PR 3401 behaviour verification.
class HoldRecordsNewerEpisodeTest {
    private static final OperatorWarningCode HOLD_ENDED = OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED;

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    /// S2: a seeded episode E1 (no report yet), then the drainer reports E2: E1 is closed as superseded, E2 latches and closes on death.
    @Test
    void seededEpisodeSupersededByANewerEpisodeStillCloses() {
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK, EPISODE);
        fx.records.seedFromLog().await();
        assertThat(fx.records.heldLatched(NODE)).isTrue();

        fx.pong(ofEpisode(E2, 2, "d2", T0, HeldEnd.NONE));
        eventually(() -> fx.closersOfSubject(EPISODE) == 1);
        assertThat(fx.records.heldLatched(NODE)).as("the newer episode latches").isTrue();
        fx.records.onDead(NODE);
        eventually(() -> fx.closersOfSubject(E2) == 1);
        fx.records.tick();

        assertThat(fx.closersOfSubject(EPISODE)).as("closers of the seeded episode E1").isEqualTo(1);
        assertThat(fx.closersOfSubject(E2)).as("closers of E2").isEqualTo(1);
        assertThat(closerOf(EPISODE).code()).isEqualTo(HOLD_ENDED);
        assertThat(closerOf(EPISODE).message()).contains("superseded by episode " + E2);
        assertThat(fx.records.heldLatched(NODE)).isFalse();
    }

    /// S3: back to back: E1 delivered with its closer in flight, then E2 reports: E2 latches; E1 keeps its single DELIVERED closer.
    @Test
    void newEpisodeAfterADeliveredOneLatches() {
        fx.pong(held(3, "d1"));
        fx.holdOpen = true;
        fx.pong(report(3, 0, "d1", HeldEnd.DELIVERED));
        fx.pong(ofEpisode(E2, 2, "d2", T0, HeldEnd.NONE));
        var latchedDuringE1Closer = fx.records.heldLatched(NODE);
        fx.holdOpen = false;
        fx.settlePending(true, fx.pending::isEmpty);
        fx.pong(ofEpisode(E2, 2, "d2", T0, HeldEnd.NONE));
        fx.records.tick();

        assertThat(latchedDuringE1Closer).as("E2 latched while E1's closer is in flight").isTrue();
        assertThat(fx.records.heldLatched(NODE)).isTrue();
        assertThat(fx.records.lastReport(NODE).map(HeldReport::episode)).isEqualTo(Option.some(E2));
        assertThat(fx.closersOfSubject(EPISODE)).as("E1's closer landed once, not re-raised").isEqualTo(1);
        assertThat(fx.closersOf(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED)).hasSize(1);
    }

    /// S3b: E1 open, the drainer restarts under E2: E1 is superseded, the override of E2 is checked against E2's report and
    /// relayed; after E2 ended and the drainer left, the node is not latched and each episode has exactly one closer.
    @Test
    void openEpisodeSupersededThenNewerEpisodeOverridden() throws InterruptedException {
        fx.pong(held(3, "d1"));
        fx.pong(ofEpisode(E2, 2, "d2", T0, HeldEnd.NONE));
        fx.now = T0 + 20_000;
        fx.records.tick();
        eventually(() -> fx.closersOfSubject(EPISODE) == 1);

        var acceptE1 = fx.records.acceptLoss(NODE, EPISODE, LossConfirmation.Digest.digest("d1")).await();
        var acceptE2 = fx.records.acceptLoss(NODE, E2, LossConfirmation.Digest.digest("d2")).await();

        assertThat(fx.records.lastReport(NODE).map(HeldReport::episode)).isEqualTo(Option.some(E2));
        assertThat(refusedWith(acceptE1, HoldRefusal.OtherEpisode.class)).as("E1 is not the held episode")
                                                                                                  .isTrue();
        assertThat(acceptE2.isSuccess()).as("override of the held E2").isTrue();
        assertThat(fx.records.decisionFor(NODE)).isEqualTo(Option.some(HoldDecision.AcceptLoss.acceptLoss(E2,
                                                                                                          LossConfirmation.Digest.digest("d2"))));

        fx.pong(ofEpisode(E2, 2, "d2", T0 + 1, HeldEnd.DELIVERED));
        fx.records.onLeft(NODE);
        fx.records.tick();
        Thread.sleep(300);

        assertThat(fx.records.heldLatched(NODE)).isFalse();
        assertThat(fx.closersOfSubject(EPISODE)).as("closers of E1").isEqualTo(1);
        assertThat(fx.closersOfSubject(E2)).as("closers of E2").isEqualTo(1);
    }

    private OperatorWarning closerOf(String subject) {
        return fx.raised.stream()
                        .flatMap(raise -> raise.closer().stream())
                        .filter(closer -> closer.subject().equals(subject))
                        .findFirst()
                        .orElseThrow();
    }
}
