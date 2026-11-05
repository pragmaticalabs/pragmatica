// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.messaging.StreamType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;

/// #2574 F1 (CTO, know de7d2783de): a forward READ whose frame the transport did not send fails AT ONCE, not at its read
/// timeout. Refused under backpressure, a dead connection or no peer state is the transient
/// [StreamForwardError.ForwardNotSent] every re-reading caller backs off on; an encode failure is the hard
/// [StreamForwardError.ForwardNotEncoded]; `Sent` keeps the read waiting. Red before #2574: every read waited out its
/// timeout. Publishes are out of this change's scope (a follow-up) and are not tested here.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StreamForwardRefusedSendTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final TimeSpan LONG_WAIT = TimeSpan.timeSpan(20).seconds();
    private static final TimeSpan SHORT_WAIT = TimeSpan.timeSpan(300).millis();
    /// Far below the configured wait: the outcome is answered on the sending thread.
    private static final long FAST_MILLIS = 1_000L;

    /// A transport whose every send reports `outcome` and delivers nothing; records the frames handed to it.
    private static class OutcomeTransport implements StreamForwardTransport {
        private final WriteOutcome outcome;
        final List<StreamForwardMessage> frames = new CopyOnWriteArrayList<>();

        private OutcomeTransport(WriteOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public Promise<WriteOutcome> send(NodeId target, StreamForwardMessage message) {
            frames.add(message);

            return Promise.success(outcome);
        }
    }

    private static List<WriteOutcome> notSent() {
        return List.of(new WriteOutcome.BackpressureRefused(OWNER),
                       new WriteOutcome.ConnectionDead(OWNER),
                       new WriteOutcome.NoPeerState(OWNER));
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

    private static void assertNotSentFast(Timed<?> timed, WriteOutcome outcome) {
        assertThat(timed.millis()).as("a read that was not sent fails at once, not at the 20 s wait (%s)", outcome)
                                  .isLessThan(FAST_MILLIS);
        timed.result()
             .onSuccess(_ -> Assertions.fail("a read that was not sent cannot succeed"))
             .onFailure(cause -> assertTransientNotSent(cause, outcome));
    }

    private static void assertTransientNotSent(Cause cause, WriteOutcome outcome) {
        assertThat(cause).isInstanceOfSatisfying(StreamForwardError.ForwardNotSent.class,
                                                 notSent -> assertThat(notSent.outcome()).isEqualTo(outcome));
        assertThat(cause.isTransient()).as("a transient cause every re-reading caller backs off on").isTrue();
        assertThat(StreamForwardError.isRefusal(cause)).as("the backoff predicate recognises it").isTrue();
    }

    /// Each of the three outcomes that mean "not sent, may work later" maps to the transient refusal, on every read path.
    @Nested
    class NotSentReadsFailAtOnce {
        @Test
        void readRemoteCatchup_notSent_failsWithinMillis_withForwardNotSent() {
            notSent().forEach(outcome -> assertNotSentFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT)
                                                                                     .readRemoteCatchup(OWNER, STREAM, 0, 0L, 16)),
                                                           outcome));
        }

        @Test
        void readRemote_notSent_failsWithinMillis_withForwardNotSent() {
            notSent().forEach(outcome -> assertNotSentFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT)
                                                                                     .readRemote(OWNER, STREAM, 0, 0L, 16)),
                                                           outcome));
        }

        @Test
        void readRemoteValidated_notSent_failsWithinMillis_withForwardNotSent() {
            notSent().forEach(outcome -> assertNotSentFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT)
                                                                                     .readRemoteValidated(OWNER, STREAM, 0, 0L, 16, Epoch.ZERO)),
                                                           outcome));
        }

        /// The activation gate's ring-tail peer query rides the same read path.
        @Test
        void ringTailRemote_notSent_failsWithinMillis_withForwardNotSent() {
            var outcome = new WriteOutcome.BackpressureRefused(OWNER);

            assertNotSentFast(timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT).ringTailRemote(OWNER, STREAM, 0)),
                              outcome);
        }

        /// The failure removed the pending entry: a response that arrives afterwards finds nothing and resolves nothing.
        @Test
        void readRemoteCatchup_notSent_lateResponseIsOrphaned() {
            var transport = new OutcomeTransport(new WriteOutcome.BackpressureRefused(OWNER));
            var forwardClient = client(transport, LONG_WAIT);
            var read = forwardClient.readRemoteCatchup(OWNER, STREAM, 0, 0L, 16);
            var correlationId = ((StreamForwardMessage.ReadForward) transport.frames.getFirst()).correlationId();

            forwardClient.onReadForwardResponse(ReadForwardResponse.successResponse(OWNER, correlationId, List.of()));

            assertThat(read.await(TimeSpan.timeSpan(5).seconds()).isFailure()).as("the refusal, not the late answer, resolved it")
                                                                               .isTrue();
        }
    }

    /// An encode failure is a hard error: nothing was written and no retry can change it, so it is not the transient refusal
    /// callers back off and retry on.
    @Nested
    class EncodeFailureIsHard {
        @Test
        void readRemoteCatchup_encodeFailed_failsAtOnce_withATerminalForwardNotEncoded() {
            var outcome = new WriteOutcome.EncodeFailed(OWNER, "ReadForward");
            var timed = timed(() -> client(new OutcomeTransport(outcome), LONG_WAIT).readRemoteCatchup(OWNER, STREAM, 0, 0L, 16));

            assertThat(timed.millis()).isLessThan(FAST_MILLIS);
            timed.result()
                 .onSuccess(_ -> Assertions.fail("a read that could not be encoded cannot succeed"))
                 .onFailure(cause -> assertHardEncodeFailure(cause, outcome));
        }

        private static void assertHardEncodeFailure(Cause cause, WriteOutcome outcome) {
            assertThat(cause).isInstanceOfSatisfying(StreamForwardError.ForwardNotEncoded.class,
                                                     notEncoded -> assertThat(notEncoded.outcome()).isEqualTo(outcome));
            assertThat(cause.isTerminal()).as("no retry can change an encode failure").isTrue();
            assertThat(cause.isTransient()).isFalse();
            assertThat(StreamForwardError.isRefusal(cause)).as("not the refusal callers back off and retry on").isFalse();
        }
    }

    /// A frame reported `Sent` is waiting for its answer: the read resolves at its wait, as before #2574.
    @Nested
    class SentKeepsWaiting {
        @Test
        void readRemoteCatchup_sent_waitsForTheTimeout() {
            var timed = timed(() -> client(new OutcomeTransport(new WriteOutcome.Sent(OWNER)), SHORT_WAIT)
                                          .readRemoteCatchup(OWNER, STREAM, 0, 0L, 16));

            assertThat(timed.millis()).as("waited for the answer").isGreaterThanOrEqualTo(250L);
            timed.result()
                 .onSuccess(_ -> Assertions.fail("an unanswered read cannot succeed"))
                 .onFailure(cause -> assertThat(cause).isEqualTo(StreamForwardError.General.READ_FORWARD_TIMEOUT));
        }
    }

    /// #2574 lane pin, the message side: the forward read rides the FORWARD lane -- the lane `QuicClusterNetwork` refuses at
    /// its watermark with BackpressureRefused (pinned on the transport side in `QuicConsensusBackpressureTest`), and the one
    /// `send` dispatches on.
    @Nested
    class Lane {
        @Test
        void readForward_ridesTheForwardLane() {
            var transport = new OutcomeTransport(new WriteOutcome.BackpressureRefused(OWNER));

            client(transport, LONG_WAIT).readRemoteCatchup(OWNER, STREAM, 0, 0L, 16);

            assertThat(transport.frames).singleElement()
                                        .satisfies(frame -> assertThat(frame.streamType()).isEqualTo(StreamType.FORWARD));
        }
    }

    /// The backoff predicate searches the cause chain: a refusal wrapped by an outer cause still paces its caller; any other
    /// cause does not.
    @Nested
    class RefusalPredicate {
        @Test
        void isRefusal_findsAWrappedRefusal_andNothingElse() {
            var refusal = StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(OWNER));
            Cause wrapped = Causes.cause("outer", Option.some(refusal));

            assertThat(StreamForwardError.isRefusal(wrapped)).isTrue();
            assertThat(StreamForwardError.isRefusal(StreamForwardError.General.READ_FORWARD_TIMEOUT)).isFalse();
        }
    }

    /// A transport that does not track its writes reports `Sent` after sending with the caller's wait: it never claims a
    /// frame was not sent, so the read waits for its answer.
    @Nested
    class UntrackedTransportOutcome {
        @Test
        void send_untracked_sendsWithTheCallerWait_andReportsSent() {
            var waits = new CopyOnWriteArrayList<TimeSpan>();
            StreamForwardTransport untracked = new StreamForwardTransport() {
                @Override
                public Promise<WriteOutcome> send(NodeId target, StreamForwardMessage message) {
                    return Promise.success(new WriteOutcome.Sent(target));
                }

                @Override
                public Promise<WriteOutcome> send(NodeId target, StreamForwardMessage message, TimeSpan callerWait) {
                    waits.add(callerWait);

                    return Promise.success(new WriteOutcome.Sent(target));
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
