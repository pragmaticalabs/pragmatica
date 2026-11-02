// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;

/// #2574 F1: a forward request whose frame the transport refused before it left fails AT ONCE with the transient
/// [StreamForwardError.ForwardNotSent], not at the read or publish timeout. Only a refusal before write proves the frame
/// never left; `Sent` and `ConnectionDead` keep the request waiting (a ConnectionDead frame may still be delivered from
/// the offline buffer). Red before #2574: every request below waited out its timeout.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StreamForwardRefusedSendTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final TimeSpan LONG_WAIT = TimeSpan.timeSpan(20).seconds();
    private static final TimeSpan SHORT_WAIT = TimeSpan.timeSpan(300).millis();
    /// Far below either configured wait: the refusal is answered on the sending thread.
    private static final long FAST_MILLIS = 1_000L;

    /// A transport whose every send reports `outcome` and delivers nothing; counts the frames handed to it.
    private static class OutcomeTransport implements StreamForwardTransport {
        private final WriteOutcome outcome;
        private final AtomicInteger sends = new AtomicInteger();

        private OutcomeTransport(WriteOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public void send(NodeId target, StreamForwardMessage message) {
            sends.incrementAndGet();
        }

        @Override
        public Promise<WriteOutcome> sendOutcome(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
            sends.incrementAndGet();

            return Promise.success(outcome);
        }
    }

    private static List<WriteOutcome> refusals() {
        return List.of(new WriteOutcome.BackpressureRefused(OWNER),
                       new WriteOutcome.NoPeerState(OWNER),
                       new WriteOutcome.EncodeFailed(OWNER, "ReadForward"));
    }

    private static StreamForwardClient client(StreamForwardTransport transport, TimeSpan wait) {
        return streamForwardClient(SELF, transport, wait, wait);
    }

    private static <T> Timed<T> timed(Supplier<Promise<T>> request) {
        var startedAt = System.nanoTime();
        var result = request.get().await(TimeSpan.timeSpan(25).seconds());

        return new Timed<>(result, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private record Timed<T>(Result<T> result, long millis) {}

    private static void assertRefusedFast(Timed<?> timed, WriteOutcome outcome) {
        assertThat(timed.millis()).as("a refused send fails at once, not at the 20 s wait (%s)", outcome)
                                  .isLessThan(FAST_MILLIS);
        assertThat(timed.result().isFailure()).as("a refused send fails (%s)", outcome).isTrue();
        timed.result()
             .onFailure(cause -> assertForwardNotSent(cause, outcome));
    }

    private static void assertForwardNotSent(Cause cause, WriteOutcome outcome) {
        assertThat(cause).isInstanceOfSatisfying(StreamForwardError.ForwardNotSent.class,
                                                 notSent -> assertNotSent(notSent, outcome));
        assertThat(cause.isTransient()).as("the refusal is a transient cause a paced retry may outlive").isTrue();
        assertThat(StreamForwardError.refusalRetryAfter(cause)).as("a retrying caller reads the wait from the cause")
                                                               .isEqualTo(Option.some(LONG_WAIT));
    }

    /// The refusal names its outcome and carries the wait the request would have spent on its answer (the 20 s configured
    /// wait, no ambient deadline here), which a retrying caller keeps before sending again.
    private static void assertNotSent(StreamForwardError.ForwardNotSent notSent, WriteOutcome outcome) {
        assertThat(notSent.outcome()).isEqualTo(outcome);
        assertThat(notSent.retryAfter()).isEqualTo(LONG_WAIT);
    }

    @Nested
    class ReadsFailAtOnce {
        @Test
        void readRemoteCatchup_refusedSend_failsWithinMillis_withForwardNotSent() {
            refusals().forEach(outcome -> assertRefusedFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT)
                                                                                     .readRemoteCatchup(OWNER, STREAM, 0, 0L, 16)),
                                                            outcome));
        }

        @Test
        void readRemote_refusedSend_failsWithinMillis_withForwardNotSent() {
            refusals().forEach(outcome -> assertRefusedFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT)
                                                                                     .readRemote(OWNER, STREAM, 0, 0L, 16)),
                                                            outcome));
        }

        @Test
        void readRemoteValidated_refusedSend_failsWithinMillis_withForwardNotSent() {
            refusals().forEach(outcome -> assertRefusedFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT)
                                                                                     .readRemoteValidated(OWNER, STREAM, 0, 0L, 16, Epoch.ZERO)),
                                                            outcome));
        }

        @Test
        void ringTailRemote_refusedSend_failsWithinMillis_withForwardNotSent() {
            var outcome = new WriteOutcome.BackpressureRefused(OWNER);

            assertRefusedFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT).ringTailRemote(OWNER, STREAM, 0)),
                              outcome);
        }

        /// The refusal removed the pending entry: a response that arrives afterwards finds nothing and resolves nothing.
        @Test
        void readRemoteCatchup_refusedSend_lateResponseIsOrphaned() {
            var outcome = new WriteOutcome.BackpressureRefused(OWNER);
            var captured = new AtomicReference<StreamForwardMessage>();
            StreamForwardTransport capturing = new OutcomeTransport(outcome) {
                @Override
                public Promise<WriteOutcome> sendOutcome(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
                    captured.set(message);

                    return super.sendOutcome(target, message, callerWait);
                }
            };
            var forwardClient = client(capturing, LONG_WAIT);
            var read = forwardClient.readRemoteCatchup(OWNER, STREAM, 0, 0L, 16);
            var correlationId = ((StreamForwardMessage.ReadForward) captured.get()).correlationId();

            forwardClient.onReadForwardResponse(ReadForwardResponse.successResponse(OWNER, correlationId, List.of()));

            assertThat(read.await(TimeSpan.timeSpan(5).seconds()).isFailure()).as("the refusal, not the late answer, resolved it")
                                                                               .isTrue();
        }
    }

    @Nested
    class PublishFailsAtOnce {
        /// A refused publish was provably not applied, so it is the transient ForwardNotSent, never "outcome unknown".
        @Test
        void publishRemote_refusedSend_failsWithinMillis_withForwardNotSent_notOutcomeUnknown() {
            refusals().forEach(outcome -> assertRefusedFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT)
                                                                                     .publishRemote(OWNER, STREAM, 0, new byte[]{1}, 1L)),
                                                            outcome));
        }

        @Test
        void publishRemote_systemWriter_refusedSend_failsWithinMillis_withForwardNotSent() {
            var outcome = new WriteOutcome.BackpressureRefused(OWNER);

            assertRefusedFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT).publishRemote(OWNER,
                                                                                                          STREAM,
                                                                                                          0,
                                                                                                          new byte[]{1},
                                                                                                          1L,
                                                                                                          true)),
                              outcome);
        }
    }

    /// A frame reported `Sent` or `ConnectionDead` may still be delivered, so the request keeps waiting for its answer:
    /// it resolves at its wait, as before #2574.
    @Nested
    class MayBeDeliveredKeepsWaiting {
        @Test
        void readRemoteCatchup_connectionDeadOrSent_waitsForTheTimeout() {
            List.<WriteOutcome> of(new WriteOutcome.ConnectionDead(OWNER), new WriteOutcome.Sent(OWNER))
                .forEach(outcome -> assertWaitedOut(timed(() -> client(new OutcomeTransport(outcome), SHORT_WAIT)
                                                                          .readRemoteCatchup(OWNER, STREAM, 0, 0L, 16)),
                                                    outcome));
        }

        @Test
        void publishRemote_connectionDead_isOutcomeUnknownAtTheTimeout() {
            var timed = timed(() -> client(new OutcomeTransport(new WriteOutcome.ConnectionDead(OWNER)), SHORT_WAIT)
                                         .publishRemote(OWNER, STREAM, 0, new byte[]{1}, 1L));

            assertThat(timed.millis()).as("waited for the answer").isGreaterThanOrEqualTo(250L);
            timed.result()
                 .onSuccess(_ -> Assertions.fail("an unanswered publish cannot succeed"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(PublishOutcomeUnknown.class));
        }

        private static void assertWaitedOut(Timed<?> timed, WriteOutcome outcome) {
            assertThat(timed.millis()).as("waited for the answer (%s)", outcome).isGreaterThanOrEqualTo(250L);
            timed.result()
                 .onSuccess(_ -> Assertions.fail("an unanswered read cannot succeed"))
                 .onFailure(cause -> assertThat(cause).isEqualTo(StreamForwardError.General.READ_FORWARD_TIMEOUT));
        }
    }

    /// The wait a refused request carries is the caller's wait, capped by the ambient deadline like the wait itself.
    @Nested
    class RetryAfterIsTheCallersWait {
        @Test
        void readRemoteCatchup_refusedUnderAnAmbientBudget_carriesTheBudgetedWait() {
            var outcome = new WriteOutcome.BackpressureRefused(OWNER);
            var result = Deadline.runWith(Deadline.fromWireMillis(800),
                                          () -> client(new OutcomeTransport(outcome), LONG_WAIT).readRemoteCatchup(OWNER, STREAM, 0, 0L, 16))
                                 .await(TimeSpan.timeSpan(5).seconds());

            result.onSuccess(_ -> Assertions.fail("a refused read cannot succeed"))
                  .onFailure(cause -> assertThat(StreamForwardError.refusalRetryAfter(cause)
                                                                   .map(TimeSpan::millis)
                                                                   .or(-1L)).as("bounded by the 800 ms budget, not the 20 s configured wait")
                                                                            .isBetween(1L, 800L));
        }

        /// The cause chain is searched: a refusal wrapped by an outer cause still paces the caller; any other cause does not.
        @Test
        void refusalRetryAfter_findsAWrappedRefusal_andNothingElse() {
            var refusal = StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(OWNER), SHORT_WAIT);
            Cause wrapped = org.pragmatica.lang.utils.Causes.cause("outer", Option.some(refusal));

            assertThat(StreamForwardError.refusalRetryAfter(wrapped)).isEqualTo(Option.some(SHORT_WAIT));
            assertThat(StreamForwardError.refusalRetryAfter(StreamForwardError.General.READ_FORWARD_TIMEOUT)).isEqualTo(Option.none());
        }
    }

    /// The default `sendOutcome` (a transport that does not track writes) sends through `send` with the caller's wait
    /// and reports `Sent`: it never claims a frame was not sent.
    @Nested
    class DefaultTransportOutcome {
        @Test
        void sendOutcome_default_sendsWithTheCallerWait_andReportsSent() {
            var waits = new CopyOnWriteArrayList<TimeSpan>();
            StreamForwardTransport untracked = new StreamForwardTransport() {
                @Override
                public void send(NodeId target, StreamForwardMessage message) {}

                @Override
                public void send(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
                    waits.add(callerWait);
                }
            };
            var read = Deadline.runWith(Deadline.fromWireMillis(400),
                                        () -> client(untracked, LONG_WAIT).readRemoteCatchup(OWNER, STREAM, 0, 0L, 16));

            assertThat(waits).as("the frame went out bounded by the caller's wait").hasSize(1);
            assertThat(waits.getFirst().millis()).isLessThanOrEqualTo(400L);
            read.await(TimeSpan.timeSpan(5).seconds())
                .onSuccess(_ -> Assertions.fail("an unanswered read cannot succeed"))
                .onFailure(cause -> assertThat(cause).as("reported Sent, so it waited for its answer and timed out")
                                                     .isEqualTo(StreamForwardError.General.READ_FORWARD_TIMEOUT));
        }
    }
}
