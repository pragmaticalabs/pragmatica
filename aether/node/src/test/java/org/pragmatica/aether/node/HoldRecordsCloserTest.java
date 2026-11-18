// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.report;

/// The end of a hold (#3345, memo §4, fable3345b 11): closers go through `raiseClosing` (a new leader may never have seen the
/// warning open), are re-raised until landed, and only a landed closer is echoed to the drainer as `EndRecorded`.
class HoldRecordsCloserTest {
    private static final OperatorWarningCode HOLD_ENDED = OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED;
    private static final OperatorWarningCode DELIVERED = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED;

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    @Test
    void closerOnDeathCarriesLastReport() {
        fx.pong(held(3, "d1"));
        fx.pong(held(2, "d2"));

        fx.records.onDead(NODE);

        var closers = fx.closersOf(HOLD_ENDED);
        assertThat(closers).as("death closes through raiseClosing").hasSize(1);
        assertThat(closers.getFirst().subject()).isEqualTo(EPISODE);
        assertThat(closers.getFirst().message())
            .startsWith("Draining node drainer-1 (episode drainer-1@1000): declared dead by failure detection while held; "
                        + "2 key(s) owed as of ")
            .endsWith("(digest d2) may be lost; restore them from the source of truth");
        assertThat(fx.records.heldLatched(NODE)).isFalse();
    }

    @Test
    void restartedDrainerGetsDeathRecord() {
        fx.pong(held(3, "d1"));
        fx.live.clear();
        fx.records.onPong(NODE);

        fx.records.onDead(NODE);

        assertThat(fx.closersOf(HOLD_ENDED)).hasSize(1);
        assertThat(fx.closersOf(HOLD_ENDED).getFirst().message()).contains("3 key(s) owed as of ").contains("(digest d1)");
    }

    @Test
    void deathOfASeededLatchWithoutAReportStillCloses() {
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK, EPISODE);
        fx.records.seedFromLog().await();

        fx.records.onDead(NODE);

        assertThat(fx.closersOf(HOLD_ENDED)).hasSize(1);
        assertThat(fx.closersOf(HOLD_ENDED).getFirst().message()).contains("count unknown to this leader");
    }

    @Test
    void closerReRaisedUntilLandedThenRecordDropped() {
        fx.lands = false;
        fx.pong(held(3, "d1"));
        fx.records.onDead(NODE);
        fx.records.tick();
        assertThat(fx.closersOf(HOLD_ENDED)).hasSize(2);

        fx.lands = true;
        fx.records.tick();
        fx.records.tick();

        assertThat(fx.closersOf(HOLD_ENDED)).hasSize(3);
        assertThat(fx.records.decisionFor(NODE)).as("closed and departed: the record is gone").isEqualTo(Option.none());
        assertThat(fx.records.lastReport(NODE)).isEqualTo(Option.none());
    }

    @Test
    void deliveredReportClosesWithDelivered() {
        fx.pong(held(3, "d1"));
        fx.pong(report(0, 0, "", HeldEnd.DELIVERED));

        assertThat(fx.closersOf(DELIVERED)).hasSize(1);
        assertThat(fx.closersOf(HOLD_ENDED)).isEmpty();
    }

    @Test
    void lossAcceptedReportClosesWithHoldEnded() {
        fx.pong(held(3, "d1"));
        fx.records.acceptLoss(NODE, EPISODE, HoldRecords.LossConfirmation.Digest.digest("d1")).await();
        fx.pong(report(3, 0, "d1", HeldEnd.LOSS_ACCEPTED));

        assertThat(fx.closersOf(HOLD_ENDED)).hasSize(1);
        assertThat(fx.closersOf(HOLD_ENDED).getFirst().message()).contains("loss accepted by an operator");
    }

    @Test
    void endEchoAfterCloserLanded() {
        fx.pong(held(3, "d1"));
        fx.holdOpen = true;
        fx.pong(report(0, 0, "", HeldEnd.DELIVERED));
        assertThat(fx.closersOf(DELIVERED)).hasSize(1);
        assertThat(fx.records.decisionFor(NODE)).as("closer pending: no echo").isEqualTo(Option.none());

        fx.settlePending(true, () -> fx.records.decisionFor(NODE).isPresent());

        assertThat(fx.records.decisionFor(NODE)).isEqualTo(Option.some(HoldRecords.HoldDecision.EndRecorded.endRecorded(EPISODE)));
        fx.records.onLeft(NODE);
        assertThat(fx.records.decisionFor(NODE)).as("echoed and left: the record is gone").isEqualTo(Option.none());
    }
}
