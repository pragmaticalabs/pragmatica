// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.HoldRecords.HoldRefusal;
import org.pragmatica.aether.node.HoldRecords.LossConfirmation;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.refusedWith;
import static org.pragmatica.aether.node.HoldRecordsFixture.ofEpisode;

/// The override is checked against the record's last report, kept past the FAN sweep (H3); a record raise that fails is
/// "record not landed" and releases the acceptance (H4). Schedules S5 to S9 of the PR 3401 behaviour verification.
class HoldRecordsOverrideScheduleTest {
    private static final LossConfirmation D1 = LossConfirmation.Digest.digest("d1");

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    /// S5: a stale digest is refused, the right one then succeeds.
    @Test
    void staleDigestThenRightDigest() {
        fx.pong(held(3, "d1"));

        var stale = fx.records.acceptLoss(NODE, EPISODE, LossConfirmation.Digest.digest("old")).await();
        var right = fx.records.acceptLoss(NODE, EPISODE, D1).await();

        assertThat(refusedWith(stale, HoldRefusal.StaleDigest.class)).isTrue();
        assertThat(right.isSuccess()).isTrue();
    }

    /// S6: the FAN swept the drainer's report: the override is checked against the record's last report and succeeds.
    @Test
    void overrideAfterFanSweepUsesTheRecordsReport() {
        fx.pong(held(3, "d1"));
        fx.live.clear();

        var result = fx.records.acceptLoss(NODE, EPISODE, D1).await();

        assertThat(result.isSuccess()).as("override of a latched hold whose FAN entry was swept").isTrue();
        assertThat(fx.raisedOf(OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED)).isEqualTo(1);
    }

    /// S7: the record raise FAILS (a failed promise, not `false`): 503 RecordNotLanded, and a retry is not refused as accepted.
    @Test
    void failedRecordRaiseIsNotLandedAndRetryable() {
        var calls = new AtomicInteger();
        var records = HoldRecords.holdRecords(nodeId -> Option.option(fx.live.get(nodeId)),
                                              new HoldRecords.WarningRaiser() {
                                                  @Override
                                                  public Promise<Boolean> raiseLanded(OperatorWarning warning) {
                                                      return warning.code() == OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED
                                                             && calls.getAndIncrement() == 0
                                                             ? Causes.cause("timeout").promise()
                                                             : Promise.success(true);
                                                  }

                                                  @Override
                                                  public Promise<Boolean> raiseClosing(OperatorWarning warning, OperatorWarning closer) {
                                                      return Promise.success(true);
                                                  }
                                              },
                                              () -> Promise.success(List.of()),
                                              () -> fx.now,
                                              HoldRecords.REPORT_AFTER_MS);
        fx.live.put(NODE, held(3, "d1"));
        records.onPong(NODE);

        var first = records.acceptLoss(NODE, EPISODE, D1).await();
        var second = records.acceptLoss(NODE, EPISODE, D1).await();

        assertThat(refusedWith(first, HoldRefusal.RecordNotLanded.class)).as("failed raise = not landed")
                                                                                                 .isTrue();
        assertThat(second.isSuccess()).as("retry after a failed record raise").isTrue();
    }

    /// S8: the override called twice concurrently: exactly one succeeds and exactly one record is raised.
    @Test
    void concurrentOverridesRecordOnce() throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            var rx = new HoldRecordsFixture();
            rx.pong(held(3, "d1"));
            var go = new CountDownLatch(1);
            var results = new CopyOnWriteArrayList<Promise<HoldRecords.LossAccepted>>();
            var threads = new ArrayList<Thread>();

            for (int t = 0; t < 2; t++) {
                var thread = new Thread(() -> overrideAfter(go, rx, results));
                thread.start();
                threads.add(thread);
            }
            go.countDown();
            for (var thread : threads) {
                thread.join();
            }

            assertThat(results.stream().map(Promise::await).filter(Result::isSuccess).count()).as("round " + round).isEqualTo(1);
            assertThat(rx.raisedOf(OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED)).as("round " + round).isEqualTo(1);
        }
    }

    /// S9: an override for a node never seen is NotHeld; that node's first held report latches it.
    @Test
    void unseenNodeIsNotHeldUntilItsFirstReport() {
        var other = NodeId.nodeId("stranger-9").unwrap();

        var none = fx.records.acceptLoss(other, "stranger-9@5", LossConfirmation.Digest.digest("x")).await();
        fx.live.put(other, ofEpisode("stranger-9@5", 1, "x", HoldRecordsFixture.T0, HeldEnd.NONE));
        fx.records.onPong(other);

        assertThat(refusedWith(none, HoldRefusal.NotHeld.class)).isTrue();
        assertThat(fx.records.heldLatched(other)).isTrue();
    }

    private static void overrideAfter(CountDownLatch go,
                                      HoldRecordsFixture rx,
                                      List<Promise<HoldRecords.LossAccepted>> results) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        results.add(rx.records.acceptLoss(NODE, EPISODE, D1));
    }
}
