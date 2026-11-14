// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.forward;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OwnerLease;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testlogging.LogCapture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ControlAppendForward.controlAppendForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.validatedReadForward;

/// #2583 P2: a reply the handler sends to a forwarding requester reports its [WriteOutcome]. A reply the transport did not
/// accept moves [StreamReadForwardMetrics#replyNotSent] (read through the interface the node assembly shares), opens one
/// WARN episode per requester, closed by one INFO when replies are accepted again, and, when the outcome proves the frame
/// never left ([WriteOutcome#provablyNotSent]), is sent again with the #2574 backoff (250 ms doubling, no jitter here, at most
/// four re-sends). An ambiguous outcome (connection dead, a failed send promise) is never re-sent. Every reply shape the
/// handler sends is driven by name. Red before P2: the handler discarded the outcome.
class StreamForwardReplyNotSentTest {
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final NodeId REQUESTER = NodeId.randomNodeId();
    private static final NodeId OTHER_REQUESTER = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final String CORRELATION_ID = "corr-p2";
    private static final byte[] PAYLOAD = "p".getBytes();
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);
    private static final double NO_JITTER = 0.5;

    private StreamPartitionManager partitionManager;
    private StreamReadForwardMetrics metrics;
    private List<StreamForwardMessage> replies;
    private List<NodeId> targets;
    private Deque<Promise<WriteOutcome>> script;
    private List<Scheduled> scheduled;
    private ScheduledExecutorService neverFires;
    private LogCapture handlerLog;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE);
        metrics = StreamReadForwardMetrics.inMemory();
        replies = new CopyOnWriteArrayList<>();
        targets = new CopyOnWriteArrayList<>();
        script = new ArrayDeque<>();
        scheduled = new CopyOnWriteArrayList<>();
        neverFires = Executors.newSingleThreadScheduledExecutor();
        handlerLog = LogCapture.capture(StreamForwardHandler.class, Level.INFO, true);
        partitionManager.createStream(streamConfig(STREAM));
    }

    @AfterEach
    void tearDown() {
        handlerLog.close();
        neverFires.shutdownNow();
    }

    private DefaultStreamForwardHandler handlerAnswering(WriteOutcome... outcomes) {
        return handlerOver(partitionManager, outcomes);
    }

    /// The handler under test, with the re-send scheduler and jitter source replaced; a re-send runs only when the test runs it.
    /// `outcomes` are answered in order, and the last one repeats.
    private DefaultStreamForwardHandler handlerOver(StreamPartitionManager manager, WriteOutcome... outcomes) {
        for (var outcome : outcomes) {
            script.add(Promise.success(outcome));
        }

        StreamForwardTransport transport = this::answer;

        return new DefaultStreamForwardHandler(OWNER,
                                               manager,
                                               transport,
                                               1_048_576L,
                                               metrics,
                                               Option.none(),
                                               Option.none(),
                                               Option.none(),
                                               RefusalBackoff.refusalBackoff(() -> NO_JITTER),
                                               this::schedule);
    }

    private Promise<WriteOutcome> answer(NodeId target, StreamForwardMessage message) {
        replies.add(message);
        targets.add(target);

        return script.size() > 1
               ? script.poll()
               : script.peek();
    }

    private ScheduledFuture<?> schedule(Runnable task, TimeSpan delay) {
        scheduled.add(new Scheduled(task, delay));

        return neverFires.schedule(() -> {}, 1, TimeUnit.HOURS);
    }

    private record Scheduled(Runnable task, TimeSpan delay) {}

    private void runNextResend() {
        scheduled.removeFirst().task().run();
    }

    private void readTo(StreamForwardHandler handler, NodeId requester) {
        handler.onReadForward(readForward(requester, CORRELATION_ID, STREAM, 0, 0L, 10));
    }

    private List<String> episodeLines(Level level) {
        return handlerLog.messages(level).stream()
                         .filter(line -> line.startsWith("Forward reply to") || line.startsWith("Forward replies to"))
                         .toList();
    }

    private static WriteOutcome refused() {
        return new WriteOutcome.BackpressureRefused(REQUESTER);
    }

    private static WriteOutcome sent() {
        return new WriteOutcome.Sent(REQUESTER);
    }

    private static WriteOutcome dead() {
        return new WriteOutcome.ConnectionDead(REQUESTER);
    }

    // ---- counting: every reply shape, by name ----

    private void assertRefusedReplyCounted(Consumer<StreamForwardHandler> trigger) {
        trigger.accept(handlerAnswering(dead()));

        assertThat(replies).as("the reply was handed to the transport").hasSize(1);
        assertThat(metrics.replyNotSent()).as("refused reply counted").isEqualTo(1L);
    }

    @Test
    void publishSuccessReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onPublishForward(publishForward(REQUESTER, CORRELATION_ID, STREAM, 0, PAYLOAD, 1L)));
    }

    @Test
    void publishRetryableReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onPublishForward(publishForward(REQUESTER, CORRELATION_ID, "absent", 0, PAYLOAD, 1L)));
    }

    @Test
    void publishFailureReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onPublishForward(publishForward(REQUESTER, CORRELATION_ID, STREAM, 99, PAYLOAD, 1L)));
    }

    @Test
    void publishOutcomeUnknownReply_refused_isCounted() {
        var barrierManager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, new FailingBarrier());
        barrierManager.committedConfigSource(_ -> Option.some(configWithMinSync(2)));
        var handler = handlerOver(barrierManager, dead());

        handler.onPublishForward(publishForward(REQUESTER, CORRELATION_ID, STREAM, 0, PAYLOAD, 1L));

        assertThat(replies).hasSize(1);
        assertThat(replies.getFirst()).isInstanceOfSatisfying(PublishForwardResponse.class,
                                                              response -> assertThat(response.outcomeUnknown()).as("the shape under test").isTrue());
        assertThat(metrics.replyNotSent()).isEqualTo(1L);
    }

    @Test
    void readSuccessReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onReadForward(readForward(REQUESTER, CORRELATION_ID, STREAM, 0, 0L, 10)));
    }

    @Test
    void readFailureReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onReadForward(readForward(REQUESTER, CORRELATION_ID, STREAM, 99, 0L, 10)));
    }

    @Test
    void readEpochDivergedReply_refused_isCounted() {
        var manager = streamPartitionManager();
        var record = new AtomicReference<Option<StreamPartitionOwnershipValue>>(Option.some(owned(E1, List.of(EpochStart.epochStart(E1, 0L, Option.none())))));
        manager.createStream(streamConfig(STREAM));
        manager.ownerLease(OwnerLease.TEST_VALID);
        manager.ownershipRecords((_, _) -> record.get());
        for (var i = 0; i < 5; i++) {
            manager.publishLocal(STREAM, 0, ("r" + i).getBytes(UTF_8), i).unwrap();
        }
        record.set(Option.some(owned(E2, List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E2, 3L, Option.none())))));
        var handler = handlerOver(manager, dead());

        handler.onReadForward(validatedReadForward(REQUESTER, CORRELATION_ID, STREAM, 0, 5L, 10, E1));

        assertThat(replies).hasSize(1);
        assertThat(replies.getFirst()).isInstanceOfSatisfying(ReadForwardResponse.class,
                                                              response -> assertThat(response.divergenceResumeAt()).as("the shape under test").isEqualTo(3L));
        assertThat(metrics.replyNotSent()).isEqualTo(1L);
        manager.close();
    }

    @Test
    void controlAppendReply_refused_isCounted() {
        assertRefusedReplyCounted(handler -> handler.onControlAppendForward(controlAppendForward(REQUESTER, CORRELATION_ID, STREAM, 0, List.of())));
    }

    @Test
    void everyNonSentOutcome_isCounted() {
        var outcomes = List.of(new WriteOutcome.BackpressureRefused(REQUESTER),
                               new WriteOutcome.ConnectionDead(REQUESTER),
                               new WriteOutcome.NoPeerState(REQUESTER),
                               new WriteOutcome.EncodeFailed(REQUESTER, "ReadForwardResponse"));

        outcomes.forEach(outcome -> {
            script.clear();
            readTo(handlerAnswering(outcome), REQUESTER);
        });

        assertThat(metrics.replyNotSent()).isEqualTo(outcomes.size());
    }

    @Test
    void acceptedReply_isNotCounted() {
        readTo(handlerAnswering(sent()), REQUESTER);

        assertThat(replies).hasSize(1);
        assertThat(metrics.replyNotSent()).isZero();
        assertThat(scheduled).isEmpty();
    }

    @Test
    void failedSendPromise_isCountedAndWarned_butNotResent() {
        script.add(Causes.cause("link torn down").promise());
        var handler = handlerOver(partitionManager, new WriteOutcome[0]);

        readTo(handler, REQUESTER);

        assertThat(metrics.replyNotSent()).as("a failed send promise is counted").isEqualTo(1L);
        assertThat(episodeLines(Level.WARN)).hasSize(1).first().asString().contains("link torn down");
        assertThat(scheduled).as("the frame may have left: never re-sent").isEmpty();
        assertThat(replies).hasSize(1);
    }

    @Test
    void noopMetrics_replyNotSent_isZero() {
        StreamReadForwardMetrics.NOOP.recordReplyNotSent();

        assertThat(StreamReadForwardMetrics.NOOP.replyNotSent()).isZero();
    }

    // ---- re-send ----

    @Test
    void provablyNotSentReply_isResentWithTheFirstHold_andDeliveredOnALaterAttempt() {
        var handler = handlerAnswering(refused(), refused(), sent());

        readTo(handler, REQUESTER);
        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.getFirst().delay().nanos()).as("first hold").isEqualTo(TimeUnit.MILLISECONDS.toNanos(250));
        runNextResend();
        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.getFirst().delay().nanos()).as("second hold doubles").isEqualTo(TimeUnit.MILLISECONDS.toNanos(500));
        runNextResend();

        assertThat(replies).as("the same reply, three sends").hasSize(3).containsOnly(replies.getFirst());
        assertThat(targets).containsOnly(REQUESTER);
        assertThat(scheduled).as("delivered: nothing further scheduled").isEmpty();
        assertThat(metrics.replyNotSent()).as("one count per refused send").isEqualTo(2L);
    }

    @Test
    void resend_stopsAfterFourResends_withDoublingHolds() {
        var handler = handlerAnswering(refused());
        var holds = new ArrayList<Long>();

        readTo(handler, REQUESTER);
        while (!scheduled.isEmpty()) {
            holds.add(TimeUnit.NANOSECONDS.toMillis(scheduled.getFirst().delay().nanos()));
            runNextResend();
        }

        assertThat(holds).as("four re-sends, 250+500+1000+2000 ms = 3.75 s, inside the requester's 5 s publish timeout")
                         .containsExactly(250L, 500L, 1000L, 2000L);
        assertThat(replies).as("the original send and four re-sends").hasSize(5);
        assertThat(metrics.replyNotSent()).isEqualTo(5L);
    }

    @Test
    void ambiguousOutcome_isNeverResent() {
        readTo(handlerAnswering(dead()), REQUESTER);

        assertThat(scheduled).isEmpty();
        assertThat(replies).hasSize(1);
    }

    @Test
    void everyProvablyNotSentOutcome_isResent() {
        var outcomes = List.of(new WriteOutcome.BackpressureRefused(REQUESTER),
                               new WriteOutcome.NoPeerState(REQUESTER),
                               new WriteOutcome.EncodeFailed(REQUESTER, "ReadForwardResponse"));

        outcomes.forEach(outcome -> {
            script.clear();
            scheduled.clear();
            readTo(handlerAnswering(outcome), REQUESTER);

            assertThat(scheduled).as(outcome.toString()).hasSize(1);
        });
    }

    @Test
    void holdResetsAfterASentReply() {
        var handler = handlerAnswering(refused(), sent(), refused());

        readTo(handler, REQUESTER);
        runNextResend();
        readTo(handler, REQUESTER);

        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.getFirst().delay().nanos()).as("reset to the first hold, not 500 ms")
                                                        .isEqualTo(TimeUnit.MILLISECONDS.toNanos(250));
    }

    @Test
    void resendAfterTheRequesterLeft_stillTerminates() {
        var handler = handlerAnswering(refused());

        readTo(handler, REQUESTER);
        handler.onNodeRemoved(removed(REQUESTER));
        while (!scheduled.isEmpty()) {
            runNextResend();
        }

        assertThat(replies).as("the departed requester gets no re-send").hasSize(1);
        assertThat(handler.openEpisodes()).as("NodeRemoved's eviction is not undone by a late refusal").isZero();
        assertThat(handler.trackedReplies()).isZero();
    }

    @Test
    void refusalArrivingAfterTheRequesterLeft_opensNoEpisode() {
        var inFlight = Promise.<WriteOutcome>promise();
        script.add(inFlight);
        var handler = handlerOver(partitionManager);

        readTo(handler, REQUESTER);
        handler.onNodeRemoved(removed(REQUESTER));
        inFlight.succeed(refused());
        awaitHandlerCallback();

        assertThat(metrics.replyNotSent()).as("the refusal is still counted").isEqualTo(1L);
        assertThat(handler.openEpisodes()).as("but it re-opens no episode for the departed node").isZero();
        assertThat(episodeLines(Level.WARN)).isEmpty();
    }

    /// A promise resolved after the handler subscribed runs the handler's callback on another thread. The callback's last
    /// act for a refused reply is scheduling the re-send, so a non-empty `scheduled` means it has finished.
    private void awaitHandlerCallback() {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (scheduled.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    @Test
    void backoffEntry_isClearedWhenTheResendsAreGivenUp() {
        var handler = handlerAnswering(refused());

        readTo(handler, REQUESTER);
        while (!scheduled.isEmpty()) {
            runNextResend();
        }

        assertThat(replies).hasSize(5);
        assertThat(handler.trackedReplies()).as("a dropped reply leaves no backoff entry").isZero();
    }

    @Test
    void backoffEntry_isClearedWhenAResendPromiseFails() {
        var handler = handlerAnswering(refused());

        readTo(handler, REQUESTER);
        assertThat(handler.trackedReplies()).as("control: the refusal is tracked").isEqualTo(1);
        script.clear();
        script.add(Causes.cause("link torn down").promise());
        runNextResend();

        assertThat(handler.trackedReplies()).as("a failed send promise resets the key").isZero();
    }

    // ---- episode: W1-W4 (WARN open, dedup, per-peer), I1-I4 (INFO clear) ----

    @Test
    void repeatedRefusals_toOneRequester_warnOnce_andCountEach() {
        var handler = handlerAnswering(dead());

        readTo(handler, REQUESTER);
        readTo(handler, REQUESTER);
        readTo(handler, REQUESTER);

        assertThat(episodeLines(Level.WARN)).as("one WARN opens the episode").hasSize(1);
        assertThat(episodeLines(Level.WARN).getFirst()).contains(REQUESTER.toString());
        assertThat(metrics.replyNotSent()).isEqualTo(3L);
    }

    @Test
    void refusals_toTwoRequesters_warnOncePerRequester() {
        var handler = handlerAnswering(dead());

        readTo(handler, REQUESTER);
        readTo(handler, OTHER_REQUESTER);
        readTo(handler, REQUESTER);

        assertThat(episodeLines(Level.WARN)).hasSize(2);
        assertThat(episodeLines(Level.WARN).get(0)).contains(REQUESTER.toString());
        assertThat(episodeLines(Level.WARN).get(1)).contains(OTHER_REQUESTER.toString());
    }

    @Test
    void sentReply_withNoOpenEpisode_logsNothing() {
        readTo(handlerAnswering(sent()), REQUESTER);

        assertThat(episodeLines(Level.INFO)).isEmpty();
        assertThat(episodeLines(Level.WARN)).isEmpty();
    }

    @Test
    void sentReply_afterARefusal_closesTheEpisodeWithOneInfo() {
        var handler = handlerAnswering(dead(), sent());

        readTo(handler, REQUESTER);
        readTo(handler, REQUESTER);
        readTo(handler, REQUESTER);

        assertThat(episodeLines(Level.INFO)).as("one INFO, not one per accepted reply").hasSize(1);
        assertThat(episodeLines(Level.INFO).getFirst()).contains(REQUESTER.toString());
    }

    @Test
    void refusalAfterTheEpisodeClosed_opensASecondEpisode() {
        var handler = handlerAnswering(dead());

        readTo(handler, REQUESTER);
        script.clear();
        script.add(Promise.success(sent()));
        readTo(handler, REQUESTER);
        script.clear();
        script.add(Promise.success(dead()));
        readTo(handler, REQUESTER);

        assertThat(episodeLines(Level.WARN)).as("the closed episode really closed").hasSize(2);
        assertThat(episodeLines(Level.INFO)).hasSize(1);
    }

    @Test
    void sentReply_closesOnlyItsOwnRequestersEpisode() {
        var handler = handlerAnswering(dead());

        readTo(handler, REQUESTER);
        script.clear();
        script.add(Promise.success(sent()));
        readTo(handler, OTHER_REQUESTER);
        script.clear();
        script.add(Promise.success(dead()));
        readTo(handler, REQUESTER);

        assertThat(episodeLines(Level.INFO)).as("another requester's Sent closes nothing").isEmpty();
        assertThat(episodeLines(Level.WARN)).as("REQUESTER's episode is still open").hasSize(1);
    }

    @Test
    void departedRequester_hasItsEpisodeEvicted() {
        var handler = handlerAnswering(dead());

        readTo(handler, REQUESTER);
        handler.onNodeRemoved(removed(REQUESTER));
        readTo(handler, REQUESTER);

        assertThat(episodeLines(Level.WARN)).as("the evicted episode opens afresh").hasSize(2);
    }

    @Test
    void anotherNodesDeparture_keepsTheEpisode() {
        var handler = handlerAnswering(dead());

        readTo(handler, REQUESTER);
        handler.onNodeRemoved(removed(OTHER_REQUESTER));
        readTo(handler, REQUESTER);

        assertThat(episodeLines(Level.WARN)).hasSize(1);
    }

    private static MembershipDecision.NodeRemoved removed(NodeId node) {
        return new MembershipDecision.NodeRemoved(node, List.of(), 0L, HlcTimestamp.ZERO);
    }

    private static StreamPartitionOwnershipValue owned(Epoch epoch, List<EpochStart> starts) {
        return new StreamPartitionOwnershipValue(OWNER, epoch, 2L, HlcTimestamp.ZERO, List.of(OWNER), 3L, false, List.of(), starts);
    }

    private static StreamConfig configWithMinSync(int minSyncReplicas) {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000),
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         3,
                                         minSyncReplicas,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    /// A replication manager whose peer-ack barrier fails after the append: the owner answers a publish outcome-unknown.
    private record FailingBarrier() implements ReplicationManager {
        @Override
        public void replicateEvent(String streamName, int partition, long offset, byte[] payload, long timestamp, Epoch ownerEpoch) {}

        @Override
        public void handleAck(ReplicationMessage.ReplicateAck ack) {}

        @Override
        public ReplicaRegistry registry() {
            return ReplicationManager.NONE.registry();
        }

        @Override
        public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
            return Causes.cause("barrier failed").promise();
        }

        @Override
        public long replicatedThrough(String streamName, int partition, int minAcks) {
            return -1L;
        }

        @Override
        public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
            return -1L;
        }

        @Override
        public void observeAcks(AckObserver observer) {}
    }
}
