// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.HoldRecords.LossConfirmation;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.E2;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.T0;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.ofEpisode;
import static org.pragmatica.aether.node.HoldRecordsFixture.report;

/// The wording of the closers and of the unreported at-risk text (#3345): a closer states what the leader knows, never a
/// digest of an inventory that was not enumerated, and never a prediction.
class HoldRecordsTextsTest {
    private static final OperatorWarningCode HOLD_ENDED = OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED;

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    @Test
    void supersededCloserNamesSuccessorAndLastReportCounts() {
        fx.pong(held(3, "d1"));
        fx.pong(ofEpisode(E2, 2, "d2", T0, HeldEnd.NONE));

        var message = fx.closersOf(HOLD_ENDED).getFirst().message();

        assertThat(message).startsWith("Draining node drainer-1 (episode " + EPISODE + "): hold ended, superseded by episode " + E2)
                           .contains("last report of this episode: 3 key(s) owed as of ")
                           .contains("(digest d1)")
                           .doesNotContain("will")
                           .doesNotContain("soon");
    }

    @Test
    void deathCloserOfAnUnenumeratedInventoryPrintsNoDigest() {
        fx.pong(held(-1, ""));

        fx.records.onDead(NODE);

        assertThat(fx.closersOf(HOLD_ENDED).getFirst().message()).contains("an unenumerated number of key(s) owed as of ")
                                                                 .doesNotContain("digest");
    }

    @Test
    void lossCloserOfAnUnenumeratedInventoryPrintsNoDigest() {
        fx.pong(held(-1, ""));

        fx.records.acceptLoss(NODE, EPISODE, LossConfirmation.InventoryUnknown.INVENTORY_UNKNOWN).await();
        fx.pong(report(-1, 0, "", HeldEnd.LOSS_ACCEPTED));

        assertThat(fx.closersOf(HOLD_ENDED).getFirst().message()).contains("loss accepted by an operator; an unenumerated number of key(s) owed as of ")
                                                                 .doesNotContain("digest");
    }

    @Test
    void unreportedTextNamesBothConfirmationForms() {
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK, EPISODE);
        fx.records.seedFromLog().await();

        fx.records.onDead(NODE);

        assertThat(fx.raised).as("the opening warning travels with the closer of a seeded episode without a report").hasSize(1);
        assertThat(fx.raised.getFirst().warning().message()).contains("its last report did not reach this leader")
                                                            .contains("with the digest it reports")
                                                            .contains("--inventory-unknown");
    }
}
