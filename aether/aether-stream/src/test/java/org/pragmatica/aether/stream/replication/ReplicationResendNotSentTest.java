// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;


/// #2820: a replicate chunk the transport provably did not write (backpressure on the FORWARD lane, no peer state, an
/// encode failure) is re-sent to that replica after a short backoff, until the ack deadline of the publish waiting on
/// it; a chunk that was, or may have been, written is never re-sent. Before the fix `sendChunk` discarded the outcome,
/// so one refused write lost the replicate silently and the publish failed after the full 5 s ack wait.
///
/// The clock and every timer are driven by hand: a scheduled task runs only when the test runs it, after advancing the
/// clock by the task's delay. The ack timer is the one task scheduled for [DefaultReplicationManager#DEFAULT_ACK_TIMEOUT].
class ReplicationResendNotSentTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId REPLICA = new NodeId("replica-r");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long OFFSET = 7L;
    private static final long DEADLINE_NANOS = DefaultReplicationManager.DEFAULT_ACK_TIMEOUT.nanos();

    private ScheduledExecutorService neverFires;
    private ReplicaRegistry registry;
    private AtomicLong clock;
    private Deque<Scheduled> scheduled;
    private ScriptedTransport transport;
    private DefaultReplicationManager manager;

    @BeforeEach
    void setUp() {
        neverFires = Executors.newSingleThreadScheduledExecutor();
        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, OWNER);
        registry.registerReplica(STREAM, PARTITION, REPLICA);
        clock = new AtomicLong();
        scheduled = new ArrayDeque<>();
        transport = new ScriptedTransport(clock);
        manager = new DefaultReplicationManager(OWNER, registry, transport, this::schedule, clock::get);
    }

    @AfterEach
    void tearDown() {
        neverFires.shutdownNow();
    }

    /// (a) The #2820 shape: the first write is refused under backpressure, the re-send is written, the replica acks it
    /// and the publish completes. The replica receives the chunk exactly once, carrying the original offset and epoch.
    @Test
    void replicateEvent_resendsOnce_whenFirstWriteIsRefusedUnderBackpressure() {
        transport.script(notSent("backpressure"));
        manager.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);
        var publish = manager.awaitReplication(STREAM, PARTITION, OFFSET, 1);

        assertThat(transport.attempts()).hasSize(1);
        assertThat(pendingResends()).as("one re-send scheduled after the refusal").hasSize(1);
        assertThat(pendingResends().getFirst().delay()).isEqualTo(DefaultReplicationManager.FIRST_RESEND_BACKOFF);

        runNextResend();

        assertThat(transport.attempts()).hasSize(2);
        assertThat(transport.delivered()).as("the replica got the chunk exactly once").hasSize(1);
        var delivered = transport.delivered().getFirst();
        assertThat(delivered.fromOffset()).isEqualTo(OFFSET);
        assertThat(delivered.ownerEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(delivered).as("the same message is re-sent").isSameAs(transport.attempts().getFirst().message());

        manager.handleAck(replicateAck(REPLICA, STREAM, PARTITION, OFFSET));

        assertThat(publish.isResolved()).as("acked by the replica, not timed out").isTrue();
        assertThat(publish.await().isSuccess()).isTrue();
        assertThat(pendingResends()).isEmpty();
    }

    /// (a) Each provably-not-sent outcome is re-sent, not only backpressure.
    @Test
    void replicateEvent_resends_whenTheFirstWriteHasNoPeerState() {
        assertResentOnce("noPeerState");
    }

    @Test
    void replicateEvent_resends_whenTheFirstWriteFailedToEncode() {
        assertResentOnce("encodeFailed");
    }

    private void assertResentOnce(String kind) {
        transport.script(notSent(kind));
        manager.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);

        runNextResend();

        assertThat(transport.attempts()).hasSize(2);
        assertThat(transport.delivered()).hasSize(1);
    }

    /// (b) Refused until the deadline: no attempt goes out at or after it, nothing is delivered, one WARN names the
    /// replica, the lane and the stream, and the publish fails with today's REPLICATION_TIMEOUT when its timer fires.
    @Test
    void replicateEvent_givesUpWithOneWarn_whenEveryAttemptIsRefusedUntilTheDeadline() {
        transport.refuseAll();

        try (var capture = LogCapture.capture(DefaultReplicationManager.class, Level.WARN, true)) {
            manager.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);
            var publish = manager.awaitReplication(STREAM, PARTITION, OFFSET, 1);

            for (int guard = 0; !pendingResends().isEmpty(); guard++) {
                assertThat(guard).as("re-sends are bounded").isLessThan(100);
                runNextResend();
            }

            assertThat(transport.attempts()).as("re-sent more than once before giving up").hasSizeGreaterThan(2);
            assertThat(transport.attempts()).allSatisfy(attempt -> assertThat(attempt.atNanos()).isLessThan(DEADLINE_NANOS));
            assertThat(transport.delivered()).isEmpty();

            var warns = capture.messages(Level.WARN);
            assertThat(warns).hasSize(1);
            assertThat(warns.getFirst()).contains(REPLICA.toString()).contains("FORWARD").contains(STREAM);

            clock.set(DEADLINE_NANOS);
            ackTimer().task().run();

            assertThat(publish.isResolved()).isTrue();
            publish.await()
                   .onSuccessRun(() -> fail("an unwritten replicate must fail the publish"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(ReplicationError.General.REPLICATION_TIMEOUT));
        }
    }

    /// (c) A chunk that was written (but not yet acked), or whose fate is unknown, is never re-sent by this path.
    @Test
    void replicateEvent_neverResends_whenTheWriteWasSentButNotAcked() {
        assertNeverResent("sent");
    }

    @Test
    void replicateEvent_neverResends_whenTheConnectionWasDead() {
        assertNeverResent("connectionDead");
    }

    @Test
    void replicateEvent_neverResends_whenTheSendPromiseFailed() {
        assertNeverResent("sendFailed");
    }

    private void assertNeverResent(String kind) {
        transport.script(writtenOrUnknown(kind));
        manager.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);
        manager.awaitReplication(STREAM, PARTITION, OFFSET, 1);

        assertThat(transport.attempts()).hasSize(1);
        assertThat(pendingResends()).as("no re-send of a written or unknown frame").isEmpty();
    }

    /// (d) A re-send is sent with the REMAINING wait as its frame lifetime, and none is scheduled whose backoff would
    /// reach the deadline: here 30 ms remain after the second refusal and the next backoff is 40 ms.
    @Test
    void replicateEvent_stopsResending_whenTheNextBackoffWouldPassTheDeadline() {
        transport.refuseAll();
        manager.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);

        clock.set(DEADLINE_NANOS - TimeUnit.MILLISECONDS.toNanos(50));
        runNextResend();

        assertThat(transport.attempts()).hasSize(2);
        assertThat(transport.attempts().get(0).callerWait()).isEqualTo(DefaultReplicationManager.DEFAULT_ACK_TIMEOUT);
        assertThat(transport.attempts().get(1).callerWait().nanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(30));
        assertThat(pendingResends()).as("no re-send past the deadline").isEmpty();
    }

    /// (b) The backoff doubles from the first backoff and is capped at MAX_RESEND_BACKOFF, so a long refusal does not stretch
    /// the gap between attempts past it.
    @Test
    void replicateEvent_capsTheBackoff_whenRefusedForLong() {
        transport.refuseAll();
        manager.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);

        var delays = new ArrayList<TimeSpan>();

        for (int guard = 0; !pendingResends().isEmpty(); guard++) {
            assertThat(guard).as("re-sends are bounded").isLessThan(100);
            delays.add(pendingResends().getFirst().delay());
            runNextResend();
        }

        assertThat(delays).first().isEqualTo(DefaultReplicationManager.FIRST_RESEND_BACKOFF);
        assertThat(delays.stream().mapToLong(delay -> delay.nanos()).max().orElseThrow()).as("the cap is 500 ms")
                                                                                 .isEqualTo(TimeUnit.MILLISECONDS.toNanos(500));
    }

    /// (b) A re-send whose timer fires after the deadline (a late timer) hands the transport a zero wait, never a negative one.
    @Test
    void replicateEvent_passesAZeroWait_whenTheResendTimerFiresPastTheDeadline() {
        transport.refuseAll();
        manager.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);

        clock.set(DEADLINE_NANOS + TimeUnit.SECONDS.toNanos(1));
        runNextResend();

        assertThat(transport.attempts()).hasSize(2);
        assertThat(transport.attempts().get(1).callerWait().nanos()).isZero();
    }

    /// (b) The deadline boundary: a re-send whose backoff equals the time left would leave exactly at the deadline, when the
    /// publish's own timer fires, so it is not scheduled. Here 40 ms remain after the second refusal and the next backoff is 40 ms.
    @Test
    void replicateEvent_stopsResending_whenTheNextBackoffEqualsTheTimeLeft() {
        transport.refuseAll();
        manager.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);

        clock.set(DEADLINE_NANOS - TimeUnit.MILLISECONDS.toNanos(60));
        runNextResend();

        assertThat(transport.attempts()).hasSize(2);
        assertThat(pendingResends()).as("no re-send at the deadline instant").isEmpty();
    }

    private static Function<NodeId, Promise<WriteOutcome>> notSent(String kind) {
        return switch (kind) {
            case "backpressure" -> node -> Promise.success(new WriteOutcome.BackpressureRefused(node));
            case "noPeerState" -> node -> Promise.success(new WriteOutcome.NoPeerState(node));
            default -> node -> Promise.success(new WriteOutcome.EncodeFailed(node, "ReplicateEvents"));
        };
    }

    private static Function<NodeId, Promise<WriteOutcome>> writtenOrUnknown(String kind) {
        return switch (kind) {
            case "sent" -> node -> Promise.success(new WriteOutcome.Sent(node));
            case "connectionDead" -> node -> Promise.success(new WriteOutcome.ConnectionDead(node));
            default -> _ -> Causes.cause("transport failed").promise();
        };
    }

    /// (h) Production wiring: the manager the product builds (public factory, real monotonic clock, shared scheduler)
    /// stops re-sending a transport that refuses every write once the ack deadline has passed. Every other test here
    /// drives the 5-arg seam with a hand clock, so only this one reaches the constructor's `System::nanoTime`: with a
    /// frozen clock the deadline never arrives and a stalled lane is re-sent every 500 ms for ever. The wait is the
    /// publish's own ack timeout (no guessed sleep before the snapshot); the settle and quiet windows are longer than the
    /// 500 ms backoff cap, so a live re-send loop adds at least two sends inside the quiet window.
    @Test
    void replicateEvent_stopsResending_afterTheAckDeadline_withTheProductionClock() throws Exception {
        var sends = new AtomicInteger();
        ReplicationTransport refusing = (target, message) -> {
            sends.incrementAndGet();
            return Promise.success(new WriteOutcome.BackpressureRefused(target));
        };
        var production = ReplicationManager.replicationManager(OWNER, registry, refusing);
        var timedOut = new CountDownLatch(1);

        try {
            production.replicateEvent(STREAM, PARTITION, OFFSET, new byte[]{1}, 1L, Epoch.ZERO);
            production.awaitReplication(STREAM, PARTITION, OFFSET, 1)
                      .onResultRun(timedOut::countDown);

            assertThat(timedOut.await(15, TimeUnit.SECONDS)).as("the publish's ack timer fired").isTrue();
            Thread.sleep(700);

            var atDeadline = sends.get();

            Thread.sleep(1_300);

            assertThat(sends.get()).as("no re-send after the ack deadline").isEqualTo(atDeadline);
            assertThat(atDeadline).as("re-sent, and bounded by the deadline").isBetween(3, 20);
        } finally {
            production.close();
        }
    }

    private List<Scheduled> pendingResends() {
        return scheduled.stream()
                        .filter(task -> !task.delay().equals(DefaultReplicationManager.DEFAULT_ACK_TIMEOUT))
                        .toList();
    }

    private Scheduled ackTimer() {
        return scheduled.stream()
                        .filter(task -> task.delay().equals(DefaultReplicationManager.DEFAULT_ACK_TIMEOUT))
                        .findFirst()
                        .orElseThrow();
    }

    /// Advances the clock by the next re-send's delay, then runs it, as the shared scheduler would.
    private void runNextResend() {
        var next = pendingResends().getFirst();

        scheduled.remove(next);
        clock.addAndGet(next.delay().nanos());
        next.task().run();
    }

    private ScheduledFuture<?> schedule(Runnable task, TimeSpan delay) {
        scheduled.add(new Scheduled(task, delay));

        return neverFires.schedule(() -> {}, 1, TimeUnit.HOURS);
    }

    private record Scheduled(Runnable task, TimeSpan delay) {}

    private record Attempt(ReplicationMessage.ReplicateEvents message, TimeSpan callerWait, long atNanos) {}

    /// Answers each send from a script (first outcome, then `Sent` by default, or refusal throughout), records every
    /// attempt, and counts a delivery only for a `Sent` outcome: a refused frame never reached the replica.
    private static final class ScriptedTransport implements ReplicationTransport {
        private final AtomicLong clock;
        private final List<Attempt> attempts = new ArrayList<>();
        private final List<ReplicationMessage.ReplicateEvents> delivered = new ArrayList<>();
        private Function<NodeId, Promise<WriteOutcome>> first = node -> Promise.success(new WriteOutcome.Sent(node));
        private boolean refuseAll;

        ScriptedTransport(AtomicLong clock) {
            this.clock = clock;
        }

        void script(Function<NodeId, Promise<WriteOutcome>> outcome) {
            first = outcome;
        }

        void refuseAll() {
            refuseAll = true;
        }

        List<Attempt> attempts() {
            return attempts;
        }

        List<ReplicationMessage.ReplicateEvents> delivered() {
            return delivered;
        }

        @Override
        public Promise<WriteOutcome> send(NodeId target, ReplicationMessage message) {
            return send(target, message, TimeSpan.timeSpan(0).nanos());
        }

        @Override
        public Promise<WriteOutcome> send(NodeId target, ReplicationMessage message, TimeSpan callerWait) {
            var events = (ReplicationMessage.ReplicateEvents) message;

            attempts.add(new Attempt(events, callerWait, clock.get()));
            var outcome = refuseAll
                          ? Promise.<WriteOutcome>success(new WriteOutcome.BackpressureRefused(target))
                          : attempts.size() == 1
                            ? first.apply(target)
                            : Promise.<WriteOutcome>success(new WriteOutcome.Sent(target));

            outcome.onSuccess(written -> recordDelivery(written, events));

            return outcome;
        }

        private void recordDelivery(WriteOutcome outcome, ReplicationMessage.ReplicateEvents events) {
            if (outcome instanceof WriteOutcome.Sent) {
                delivered.add(events);
            }
        }
    }
}
