// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.HoldRecords.HoldRefusal;
import org.pragmatica.aether.node.HoldRecords.LossConfirmation;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.eventually;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.refusedWith;

/// Leadership loss (#3345): `reset` forgets every drainer's record (open and closed episodes), a raise still in flight when it
/// ran settles on nothing, and an acceptance in flight is answered as ended.
class HoldRecordsResetTest {
    private static final OperatorWarningCode AT_RISK = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK;

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    @Test
    void reset_latchedHold_holdsCleared() {
        fx.pong(held(-1, ""));
        assertThat(fx.records.heldLatched(NODE)).isTrue();

        fx.records.reset();

        assertThat(fx.records.heldLatched(NODE)).isFalse();
        assertThat(fx.records.lastReport(NODE).isEmpty()).isTrue();
        assertThat(fx.records.decisionFor(NODE).isEmpty()).isTrue();

        fx.pong(held(-1, ""));
        assertThat(fx.raisedOf(AT_RISK)).as("the landed warning is forgotten: the next report raises again").isEqualTo(2);
    }

    @Test
    void reset_raiseInFlight_settlesOnNothing() {
        fx.holdOpen = true;
        fx.pong(held(-1, ""));
        assertThat(fx.raisedOf(AT_RISK)).isEqualTo(1);
        assertThat(fx.pending).hasSize(1);

        fx.records.reset();
        fx.live.clear();
        fx.holdOpen = false;
        fx.settlePending(true, () -> true);

        assertThat(fx.records.heldLatched(NODE)).as("the landed raise recreates no hold").isFalse();
        fx.records.tick();
        assertThat(fx.raisedOf(AT_RISK)).as("nothing left to raise").isEqualTo(1);
    }

    @Test
    void reset_acceptanceInFlight_answeredEnded() {
        fx.pong(held(3, "d1"));
        fx.holdOpen = true;
        var accept = fx.records.acceptLoss(NODE, EPISODE, LossConfirmation.Digest.digest("d1"));
        eventually(() -> fx.pending.size() == 1);

        fx.records.reset();
        fx.pending.getFirst().succeed(true);

        assertThat(refusedWith(accept.await(), HoldRefusal.Ended.class)).as("no open episode after the reset").isTrue();
        assertThat(fx.records.decisionFor(NODE).isEmpty()).as("AcceptLoss never relayed").isTrue();
    }
}
