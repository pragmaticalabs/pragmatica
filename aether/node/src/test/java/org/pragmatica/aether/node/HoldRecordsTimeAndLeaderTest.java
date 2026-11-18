// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.cluster.metrics.HeldReport;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.T0;
import static org.pragmatica.aether.node.HoldRecordsFixture.eventually;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.ofEpisode;

/// Time and report order (H6) and the leader change. Schedules S10 and S11 of the PR 3401 behaviour verification, and the
/// report-order case of its finding 8.
class HoldRecordsTimeAndLeaderTest {
    private static final OperatorWarningCode AT_RISK = OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK;

    /// S10: the clock steps back 60 s after the first report: the warning must be reportable 16 s of elapsed time later.
    @Test
    void clockStepBackDoesNotDelayTheWarning() throws InterruptedException {
        var fx = new HoldRecordsFixture();
        fx.pong(held(3, "d1"));
        fx.now = T0 - 60_000 + 16_000;
        fx.records.tick();
        Thread.sleep(300);

        assertThat(fx.raisedOf(AT_RISK)).as("raised 16 s after first report despite a 60 s backward step").isEqualTo(1);
    }

    /// Finding 8: a report older by its own asOf than the stored one is ignored, so the texts never carry older counts.
    @Test
    void olderReportOfTheSameEpisodeIsIgnored() {
        var fx = new HoldRecordsFixture();
        var newer = ofEpisode(EPISODE, 2, "d2", T0, HeldEnd.NONE);
        fx.pong(newer);

        fx.pong(ofEpisode(EPISODE, 3, "d1", T0 - 1_000, HeldEnd.NONE));

        assertThat(fx.records.lastReport(NODE)).isEqualTo(Option.some(newer));
    }

    /// S11a: the raise did not land at leader A; leader B (an empty log) latches on the first held pong and raises after
    /// `reportAfter`; until that pong `heldLatched` is false (the seed gap written on [HoldRecords#heldLatched]).
    @Test
    void newLeaderWithoutLogLatchesOnFirstPong() {
        var a = new HoldRecordsFixture();
        a.lands = false;
        a.pong(held(3, "d1"));
        a.now = T0 + 16_000;
        a.records.tick();
        eventually(() -> a.raisedOf(AT_RISK) >= 1);

        var b = new HoldRecordsFixture();
        b.now = T0 + 20_000;
        var seeded = b.records.seedFromLog().await();
        var latchedBeforePong = b.records.heldLatched(NODE);
        b.pong(held(3, "d1"));
        var atFirstPong = b.raisedOf(AT_RISK);
        b.now = T0 + 36_000;
        b.records.tick();
        eventually(() -> b.raisedOf(AT_RISK) == 1);

        assertThat(seeded.unwrap()).isZero();
        assertThat(latchedBeforePong).isFalse();
        assertThat(atFirstPong).isZero();
        assertThat(b.records.heldLatched(NODE)).isTrue();
    }

    /// S11b: the at-risk landed at A and A died before its ack: C seeds the latch from the log and raises once at the first pong.
    @Test
    void newLeaderSeedsFromLogAndRaisesOnce() {
        var c = new HoldRecordsFixture();
        c.logWarning(AT_RISK, EPISODE);
        var seeded = c.records.seedFromLog().await();
        c.pong(held(3, "d1"));
        eventually(() -> c.raisedOf(AT_RISK) == 1);
        c.records.tick();
        c.records.tick();

        assertThat(seeded.unwrap()).isEqualTo(1);
        assertThat(c.raisedOf(AT_RISK)).as("landed: not re-raised").isEqualTo(1);
        assertThat(c.records.lastReport(NODE).map(HeldReport::episode)).isEqualTo(Option.some(EPISODE));
    }
}
