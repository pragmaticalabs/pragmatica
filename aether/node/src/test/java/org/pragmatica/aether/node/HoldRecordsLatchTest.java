// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.report;

/// R2 (#3345, D45-6): HELD is latched per drain episode on the leader; absence of information is never "not held". R1(a): the
/// latched episode keeps its last report past the FAN sweep.
class HoldRecordsLatchTest {
    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    @Test
    void latchSetByFirstHeldReport() {
        fx.records.onPong(NODE);
        assertThat(fx.records.heldLatched(NODE)).as("no report yet").isFalse();

        fx.pong(held(3, "d1"));

        assertThat(fx.records.heldLatched(NODE)).isTrue();
        assertThat(fx.records.lastReport(NODE).map(report -> report.owedDigest())).isEqualTo(Option.some("d1"));
    }

    @Test
    void latchSurvivesMissingView() {
        fx.pong(held(3, "d1"));
        fx.live.clear();

        fx.records.tick();
        fx.records.onLeft(NODE);
        fx.records.tick();

        assertThat(fx.records.heldLatched(NODE)).as("drainer missing from the view, then left").isTrue();
    }

    @Test
    void latchSurvivesFanSweep() {
        fx.pong(held(3, "d1"));
        fx.pong(held(2, "d2"));
        fx.live.clear();

        fx.records.onPong(NODE);

        assertThat(fx.records.heldLatched(NODE)).isTrue();
        assertThat(fx.records.lastReport(NODE).map(report -> report.owedDigest()))
            .as("the declared former holder keeps its last report (R1(a))")
            .isEqualTo(Option.some("d2"));
    }

    @Test
    void latchClearedByDeliveryOverrideOrDeath() {
        fx.pong(held(3, "d1"));
        fx.pong(report(0, 0, "d0", HeldEnd.DELIVERED));
        assertThat(fx.records.heldLatched(NODE)).as("delivery report").isFalse();

        var override = new HoldRecordsFixture();
        override.pong(held(3, "d1"));
        override.records.acceptLoss(NODE, EPISODE, HoldRecords.LossConfirmation.Digest.digest("d1")).await();
        assertThat(override.records.heldLatched(NODE)).as("override").isFalse();

        var death = new HoldRecordsFixture();
        death.pong(held(3, "d1"));
        death.records.onDead(NODE);
        assertThat(death.records.heldLatched(NODE)).as("death").isFalse();
    }

    @Test
    void endedReportDoesNotRelatchAfterOverride() {
        fx.pong(held(3, "d1"));
        fx.records.acceptLoss(NODE, EPISODE, HoldRecords.LossConfirmation.Digest.digest("d1")).await();

        fx.pong(held(3, "d1"));

        assertThat(fx.records.heldLatched(NODE)).as("a later open report never re-latches").isFalse();
    }

    @Test
    void newLeaderSeedsLatch() {
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK, EPISODE);
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK, "drainer-2@5");
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_HOLD_ENDED, "drainer-2@5");
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK, "drainer-3@6");
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED, "drainer-3@6");

        var seeded = fx.records.seedFromLog().await();

        assertThat(seeded.unwrap()).isEqualTo(1);
        assertThat(fx.records.heldLatched(NODE)).as("open at-risk, no closer").isTrue();
        assertThat(fx.records.heldLatched(NodeId.nodeId("drainer-2").unwrap())).as("closed by hold-ended").isFalse();
        assertThat(fx.records.heldLatched(NodeId.nodeId("drainer-3").unwrap())).as("closed by delivered").isFalse();
    }

    @Test
    void seededLatchReplacedByAReportOfANewerEpisode() {
        fx.logWarning(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK, "drainer-1@1");
        fx.records.seedFromLog().await();

        fx.pong(held(3, "d1"));

        assertThat(fx.records.lastReport(NODE).map(report -> report.episode())).isEqualTo(Option.some(EPISODE));
        assertThat(fx.records.heldLatched(NODE)).isTrue();
    }
}
