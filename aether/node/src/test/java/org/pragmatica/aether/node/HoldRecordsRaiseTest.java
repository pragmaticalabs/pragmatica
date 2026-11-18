// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.report;

/// The at-risk warning (#3345, memo §4): raised when a held report becomes reportable, re-raised until it lands, re-raised by
/// a new leader from the next pong; its text predicts nothing and conditions the override on a reachable leader.
class HoldRecordsRaiseTest {
    private static final OperatorWarningCode AT_RISK = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK;
    private static final Pattern PREDICTION = Pattern.compile("(?i)\\b(within|will|expect\\w*|eta|soon|seconds?|minutes?|hours?|"
                                                              + "estimated|remaining time|by \\d|about|approximately|mins?|secs?|\\d+\\s*(?:ms|s|m|h))\\b");

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    @Test
    void enumeratedHoldRaisesOnlyAfterReportAfter() {
        fx.pong(held(3, "d1"));
        assertThat(fx.raisedOf(AT_RISK)).isZero();

        fx.now += HoldRecords.REPORT_AFTER_MS;
        fx.records.tick();

        assertThat(fx.raisedOf(AT_RISK)).isEqualTo(1);
    }

    @Test
    void unknownCountRaisesAtOnce() {
        fx.pong(held(-1, ""));

        assertThat(fx.raisedOf(AT_RISK)).isEqualTo(1);
        assertThat(fx.raised.getFirst().warning().subject()).isEqualTo(EPISODE);
    }

    @Test
    void undeliverableRaisesAtOnce() {
        fx.pong(report(3, 1, "d1", HeldEnd.NONE));

        assertThat(fx.raisedOf(AT_RISK)).isEqualTo(1);
    }

    @Test
    void reRaisedUntilLanded() {
        fx.lands = false;
        fx.pong(held(-1, ""));
        fx.records.tick();
        fx.live.clear();
        fx.records.tick();
        assertThat(fx.raisedOf(AT_RISK)).as("re-raised while not landed, the drainer silent included").isEqualTo(3);

        fx.lands = true;
        fx.records.tick();
        fx.records.tick();
        fx.pong(held(-1, ""));

        assertThat(fx.raisedOf(AT_RISK)).as("landed once, never again").isEqualTo(4);
    }

    @Test
    void inFlightRaiseIsNotDuplicated() {
        fx.holdOpen = true;
        fx.pong(held(-1, ""));
        fx.records.tick();
        assertThat(fx.raisedOf(AT_RISK)).isEqualTo(1);

        fx.holdOpen = false;
        fx.settlePending(true, () -> true);
        fx.records.tick();

        assertThat(fx.raisedOf(AT_RISK)).isEqualTo(1);
    }

    @Test
    void newLeaderReRaises() {
        fx.logWarning(AT_RISK, EPISODE);
        fx.records.seedFromLog().await();

        fx.pong(held(3, "d1"));

        assertThat(fx.raisedOf(AT_RISK)).as("an enumerated report of a seeded episode raises at once").isEqualTo(1);
        assertThat(fx.raised.getFirst().warning().subject()).as("same subject").isEqualTo(EPISODE);
    }

    @Test
    void textHasNoPrediction() {
        fx.pong(report(-1, 2, "", HeldEnd.NONE));
        fx.now += HoldRecords.REPORT_AFTER_MS;
        fx.pong(report(5, 0, "d5", HeldEnd.NONE));
        fx.records.onDead(NODE);

        assertThat(fx.raised).isNotEmpty();
        fx.raised.forEach(raise -> {
            assertThat(PREDICTION.matcher(raise.warning().message()).find()).as(raise.warning().message()).isFalse();
            raise.closer().onPresent(closer -> assertThat(PREDICTION.matcher(closer.message()).find())
                .as(closer.message()).isFalse());
        });
    }

    @Test
    void textConditionsOverrideOnLeader() {
        fx.pong(report(4, 2, "d4", HeldEnd.NONE));
        var text = fx.raised.getFirst().warning().message();

        assertThat(text).startsWith("Draining node drainer-1 (episode drainer-1@1000) is held: 4 DHT chunk(s) not acknowledged "
                                    + "by a current holder as of ")
                        .contains("; 2 chunk(s) cannot be encoded; no retry delivers them. First keys: k1,k2; full list in "
                                  + "the node's WARN log; live count: `aether node lifecycle drainer-1`.")
                        .endsWith("Exits: a holder acknowledges them (a target returns or stops refusing), or, while a leader "
                                  + "is reachable, `aether node drain accept-loss drainer-1 --episode drainer-1@1000 --digest d4`.");

        var unknown = new HoldRecordsFixture();
        unknown.pong(held(-1, ""));

        assertThat(unknown.raised.getFirst().warning().message())
            .contains("inventory not enumerated as of ")
            .endsWith("while a leader is reachable, `aether node drain accept-loss drainer-1 --episode drainer-1@1000 "
                      + "--inventory-unknown`.");
    }
}
