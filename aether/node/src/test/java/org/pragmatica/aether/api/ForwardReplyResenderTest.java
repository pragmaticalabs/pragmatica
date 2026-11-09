// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline;
import org.pragmatica.aether.stream.forward.RefusalBackoff;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2569 / #2583 P2 (know 89e17399a9 shape): a management reply the transport proves it did not write is re-sent after a hold
/// of 250 ms doubling to 4 s (+-20% jitter, reset by a `Sent`), until the requester's budget cannot cover the next hold.
/// A fake clock and scheduler drive the sequence; the unit random is fixed at 0.5 (no jitter) unless a test says otherwise.
class ForwardReplyResenderTest {
    private static final NodeId ENTRY = nodeId("entry-1").unwrap();
    private static final NodeId OTHER = nodeId("other-1").unwrap();
    private static final HttpForwardResponse REPLY = reply("req-1");

    private final AtomicLong nowNanos = new AtomicLong();
    private final List<TimeSpan> holds = new ArrayList<>();
    private final List<Runnable> pending = new ArrayList<>();
    private final List<WriteOutcome> script = new ArrayList<>();
    private final List<HttpForwardResponse> writes = new ArrayList<>();

    @Test
    void send_writtenFirstTime_isNeverResent() {
        script.add(sent());

        resender(() -> 0.5).send(this::transport, ENTRY, REPLY, timeSpan(10).seconds());

        assertThat(writes).hasSize(1);
        assertThat(holds).isEmpty();
    }

    @Test
    void send_refusedOnce_isResentAfterTheFirstHold_thenStops() {
        script.add(refused());
        script.add(sent());

        resender(() -> 0.5).send(this::transport, ENTRY, REPLY, timeSpan(10).seconds());
        runScheduled();

        assertThat(holds).extracting(TimeSpan::millis).containsExactly(250L);
        assertThat(writes).as("the same frame, twice").containsExactly(REPLY, REPLY);
        assertThat(pending).isEmpty();
    }

    @Test
    void send_refusedConsecutively_holdsDoubleToTheCapAndStayThere() {
        for (var i = 0; i < 9; i++) {
            script.add(refused());
        }
        script.add(sent());

        resender(() -> 0.5).send(this::transport, ENTRY, REPLY, timeSpan(1).hours());
        runScheduled();

        assertThat(holds).extracting(TimeSpan::millis).containsExactly(250L, 500L, 1000L, 2000L, 4000L, 4000L, 4000L, 4000L, 4000L);
        assertThat(writes).hasSize(10);
    }

    @Test
    void send_holdJitterStaysWithinPlusMinusTwentyPercent() {
        script.add(refused());
        script.add(refused());
        script.add(sent());
        var draws = new double[]{0.0, Math.nextDown(1.0)};
        var next = new int[]{0};
        DoubleSupplier random = () -> draws[next[0]++];

        resender(random).send(this::transport, ENTRY, REPLY, timeSpan(1).hours());
        runScheduled();

        assertThat(holds.get(0).millis()).as("first hold, low draw: 250 ms - 20%").isEqualTo(200L);
        assertThat(holds.get(1).millis()).as("second hold, high draw: 500 ms + 20%").isBetween(599L, 600L);
    }

    @Test
    void send_aWrittenReply_resetsThePeersHoldToTheFirst() {
        script.add(refused());
        script.add(refused());
        script.add(sent());
        var resender = resender(() -> 0.5);

        resender.send(this::transport, ENTRY, REPLY, timeSpan(1).hours());
        runScheduled();
        script.add(refused());
        script.add(sent());
        resender.send(this::transport, ENTRY, reply("req-2"), timeSpan(1).hours());
        runScheduled();

        assertThat(holds).extracting(TimeSpan::millis).as("250, 500, then Sent resets, then 250 again").containsExactly(250L, 500L, 250L);
    }

    @Test
    void send_holdsArePerPeer() {
        script.add(refused());
        script.add(refused());
        script.add(sent());
        var resender = resender(() -> 0.5);

        resender.send(this::transport, ENTRY, REPLY, timeSpan(1).hours());
        resender.send(this::transport, OTHER, reply("req-2"), timeSpan(1).hours());
        runScheduled();

        assertThat(holds).extracting(TimeSpan::millis).as("another peer's backoff is not advanced").startsWith(250L, 250L);
    }

    @Test
    void send_refusedUntilTheRequestersBudgetIsSpent_stopsAtTheBound() {
        for (var i = 0; i < 20; i++) {
            script.add(refused());
        }

        resender(() -> 0.5).send(this::transport, ENTRY, REPLY, timeSpan(2).seconds());
        runScheduled();

        assertThat(holds).extracting(TimeSpan::millis).as("250 + 500 + 1000 = 1750 ms spent; the next 2000 ms hold exceeds the 250 ms left").containsExactly(250L, 500L, 1000L);
        assertThat(writes).hasSize(4);
        assertThat(pending).isEmpty();
        assertThat(nowNanos.get()).isLessThanOrEqualTo(timeSpan(2).seconds().nanos());
    }

    @Test
    void send_budgetShorterThanTheFirstHold_isNeverResent() {
        script.add(refused());

        resender(() -> 0.5).send(this::transport, ENTRY, REPLY, timeSpan(100).millis());

        assertThat(holds).isEmpty();
        assertThat(writes).hasSize(1);
    }

    @Test
    void send_encodeFailed_isNeverResent() {
        script.add(new WriteOutcome.EncodeFailed(ENTRY, "HttpForwardResponse"));

        resender(() -> 0.5).send(this::transport, ENTRY, REPLY, timeSpan(10).seconds());

        assertThat(holds).isEmpty();
        assertThat(writes).hasSize(1);
    }

    @Test
    void send_noPeerStateAndConnectionDead_areResent() {
        script.add(new WriteOutcome.NoPeerState(ENTRY));
        script.add(new WriteOutcome.ConnectionDead(ENTRY));
        script.add(sent());

        resender(() -> 0.5).send(this::transport, ENTRY, REPLY, timeSpan(10).seconds());
        runScheduled();

        assertThat(writes).hasSize(3);
    }

    private ForwardReplyResender resender(DoubleSupplier random) {
        return ForwardReplyResender.forwardReplyResender((task, hold) -> {
                                                             holds.add(hold);
                                                             pending.add(() -> {
                                                                 nowNanos.addAndGet(hold.nanos());
                                                                 task.run();
                                                             });
                                                         },
                                                         nowNanos::get,
                                                         RefusalBackoff.refusalBackoff(random));
    }

    private Promise<WriteOutcome> transport(NodeId target, HttpForwardResponse reply) {
        writes.add(reply);

        return Promise.success(script.removeFirst());
    }

    private void runScheduled() {
        while (!pending.isEmpty()) {
            pending.removeFirst().run();
        }
    }

    private static WriteOutcome sent() {
        return new WriteOutcome.Sent(ENTRY);
    }

    private static WriteOutcome refused() {
        return new WriteOutcome.BackpressureRefused(ENTRY);
    }

    private static HttpForwardResponse reply(String requestId) {
        return new HttpForwardResponse(OTHER, "corr-" + requestId, requestId, true, new byte[0], Pipeline.MANAGEMENT);
    }
}
