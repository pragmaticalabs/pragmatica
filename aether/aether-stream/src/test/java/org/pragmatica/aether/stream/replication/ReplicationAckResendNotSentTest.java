// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// #2847: a replica's ack the transport refused to write (FORWARD-lane backpressure) is re-sent. The owner's await of a publish
/// needs an ack from every in-sync replica and waits [DefaultReplicationManager#DEFAULT_ACK_TIMEOUT] (5 s) for it, longer than
/// the entry node's 2.5 s management forward, so before #2847 one refused ack turned an acknowledged publish into 503 "outcome
/// unknown" (`EmberRefusedReplicaAckTest` end to end). The first test runs on the production scheduler, so it compiles and goes
/// red at the base; the others drive the timer and clock by hand.
class ReplicationAckResendNotSentTest {
    private static final NodeId SELF = new NodeId("replica-r");
    private static final NodeId OWNER = new NodeId("owner");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long WAIT_MS = 5_000L;

    private ScheduledExecutorService neverFires;
    private AtomicLong clock;
    private Deque<Scheduled> scheduled;

    @BeforeEach
    void setUp() {
        neverFires = Executors.newSingleThreadScheduledExecutor();
        clock = new AtomicLong();
        scheduled = new ArrayDeque<>();
    }

    @AfterEach
    void tearDown() {
        neverFires.shutdownNow();
    }

    @Test
    void refusedAck_isResent_untilWritten_onTheProductionScheduler() throws InterruptedException {
        var transport = new ScriptedTransport(1);
        var handler = replicationReceiveHandler(SELF, appendingAll(), transport, (_, _) -> {});

        handler.onReplicateEvents(batch(10L, 3));

        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (transport.delivered().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }

        assertThat(transport.attempts()).as("the refused ack was sent again").hasSize(2);
        assertThat(transport.delivered()).as("the re-send carries the refused ack, to its owner")
                                         .extracting(ReplicationMessage.ReplicateAck::confirmedOffset)
                                         .containsExactly(12L);
        assertThat(transport.targets()).containsOnly(OWNER);
    }

    @Test
    void writtenAck_isNotResent() {
        var transport = new ScriptedTransport(0);
        var handler = manualTimerHandler(transport);

        handler.onReplicateEvents(batch(10L, 3));

        assertThat(transport.attempts()).hasSize(1);
        assertThat(scheduled).as("a written ack schedules nothing").isEmpty();
    }

    @Test
    void refusedAck_isResentAfterTheFirstBackoff_thenDoubling() {
        var transport = new ScriptedTransport(2);
        var handler = manualTimerHandler(transport);

        handler.onReplicateEvents(batch(10L, 3));

        assertThat(scheduled).extracting(Scheduled::delay).containsExactly(DefaultReplicationManager.FIRST_RESEND_BACKOFF);
        runNextResend();
        assertThat(scheduled).extracting(Scheduled::delay)
                             .containsExactly(DefaultReplicationManager.nextBackoff(DefaultReplicationManager.FIRST_RESEND_BACKOFF));
        runNextResend();

        assertThat(scheduled).as("written on the third attempt: nothing more scheduled").isEmpty();
        assertThat(transport.attempts()).extracting(ReplicationMessage.ReplicateAck::confirmedOffset).containsExactly(12L, 12L, 12L);
        assertThat(transport.delivered()).extracting(ReplicationMessage.ReplicateAck::confirmedOffset).containsExactly(12L);
    }

    /// The owner overwrites a replica's confirmed offset with each ack it gets, so a refused ack re-sent after a newer one was
    /// written would lower it: a superseded ack is dropped.
    @Test
    void refusedAck_supersededByANewerAck_isNotResent() {
        var transport = new ScriptedTransport(1);
        var handler = manualTimerHandler(transport);

        handler.onReplicateEvents(batch(10L, 3));
        handler.onReplicateEvents(batch(13L, 1));
        runNextResend();

        assertThat(transport.attempts()).extracting(ReplicationMessage.ReplicateAck::confirmedOffset).containsExactly(12L, 13L);
        assertThat(scheduled).isEmpty();
    }

    /// Past the owner's ack timeout no publish waits on the ack any more: one WARN, no re-send.
    @Test
    void refusedAck_stopsResending_atTheAckDeadline() {
        var transport = new ScriptedTransport(Integer.MAX_VALUE);
        var handler = manualTimerHandler(transport);

        handler.onReplicateEvents(batch(10L, 3));
        clock.set(DefaultReplicationManager.DEFAULT_ACK_TIMEOUT.nanos() - DefaultReplicationManager.FIRST_RESEND_BACKOFF.nanos()
                  - TimeSpan.timeSpan(10).millis().nanos());
        runNextResend();

        assertThat(transport.attempts()).as("re-sent once, then the next backoff would outlive the deadline").hasSize(2);
        assertThat(scheduled).as("no re-send past the ack deadline").isEmpty();
    }

    /// The removed stream's pending re-send never reaches the owner of a stream recreated under the same name.
    @Test
    void refusedAck_ofARemovedStream_isNotResent() {
        var transport = new ScriptedTransport(1);
        var handler = manualTimerHandler(transport);

        handler.onReplicateEvents(batch(10L, 3));
        handler.forgetDeferredAcks(STREAM);
        runNextResend();

        assertThat(transport.attempts()).hasSize(1);
        assertThat(scheduled).isEmpty();
    }

    private ReplicationReceiveHandler manualTimerHandler(ScriptedTransport transport) {
        var handler = replicationReceiveHandler(SELF, appendingAll(), transport, (_, _) -> {});

        handler.ackResendTimer(this::schedule, clock::get);

        return handler;
    }

    private static ReplicationReceiveHandler.RecoveredAppender appendingAll() {
        return (_, _, offset, _, _, _) -> Result.success(offset);
    }

    private static ReplicationMessage.ReplicateEvents batch(long fromOffset, int count) {
        var payloads = new ArrayList<byte[]>(count);
        var timestamps = new ArrayList<Long>(count);

        for (var i = 0; i < count; i++) {
            payloads.add(("p-" + (fromOffset + i)).getBytes());
            timestamps.add(1000L + fromOffset + i);
        }

        return replicateEvents(OWNER, STREAM, PARTITION, fromOffset, payloads, timestamps, Epoch.ZERO);
    }

    /// Advances the clock by the next re-send's delay, then runs it, as the shared scheduler would.
    private void runNextResend() {
        var next = scheduled.removeFirst();

        clock.addAndGet(next.delay().nanos());
        next.task().run();
    }

    private ScheduledFuture<?> schedule(Runnable task, TimeSpan delay) {
        scheduled.add(new Scheduled(task, delay));

        return neverFires.schedule(() -> {}, 1, TimeUnit.HOURS);
    }

    private record Scheduled(Runnable task, TimeSpan delay) {}

    /// Refuses the first `refusals` sends ([WriteOutcome.BackpressureRefused]), then writes; records every attempt and every
    /// written ack.
    private static final class ScriptedTransport implements ReplicationTransport {
        private final int refusals;
        private final AtomicInteger sends = new AtomicInteger();
        private final List<ReplicationMessage.ReplicateAck> attempts = new CopyOnWriteArrayList<>();
        private final List<ReplicationMessage.ReplicateAck> delivered = new CopyOnWriteArrayList<>();
        private final List<NodeId> targets = new CopyOnWriteArrayList<>();

        ScriptedTransport(int refusals) {
            this.refusals = refusals;
        }

        @Override
        public Promise<WriteOutcome> send(NodeId target, ReplicationMessage message) {
            var ack = (ReplicationMessage.ReplicateAck) message;

            attempts.add(ack);
            targets.add(target);

            if (sends.getAndIncrement() < refusals) {
                return Promise.success(new WriteOutcome.BackpressureRefused(target));
            }

            delivered.add(ack);

            return Promise.success(new WriteOutcome.Sent(target));
        }

        List<ReplicationMessage.ReplicateAck> attempts() {
            return attempts;
        }

        List<ReplicationMessage.ReplicateAck> delivered() {
            return delivered;
        }

        List<NodeId> targets() {
            return targets;
        }
    }
}
